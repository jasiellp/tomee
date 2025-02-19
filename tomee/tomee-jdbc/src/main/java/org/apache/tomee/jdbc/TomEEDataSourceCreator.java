/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.tomee.jdbc;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.openejb.cipher.PasswordCipher;
import org.apache.openejb.cipher.PasswordCipherFactory;
import org.apache.openejb.monitoring.LocalMBeanServer;
import org.apache.openejb.monitoring.ObjectNameBuilder;
import org.apache.openejb.resource.jdbc.BasicDataSourceUtil;
import org.apache.openejb.resource.jdbc.plugin.DataSourcePlugin;
import org.apache.openejb.resource.jdbc.pool.PoolDataSourceCreator;
import org.apache.openejb.resource.jdbc.pool.XADataSourceResource;
import org.apache.openejb.util.Duration;
import org.apache.openejb.util.LogCategory;
import org.apache.openejb.util.Logger;
import org.apache.openejb.util.Strings;
import org.apache.openejb.util.SuperProperties;
import org.apache.openejb.util.reflection.Reflections;
import org.apache.tomcat.jdbc.pool.ConnectionPool;
import org.apache.tomcat.jdbc.pool.PoolConfiguration;
import org.apache.tomcat.jdbc.pool.PoolProperties;
import org.apache.tomcat.jdbc.pool.PooledConnection;

import javax.management.ObjectName;
import javax.sql.CommonDataSource;
import javax.sql.DataSource;
import javax.sql.XADataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

public class TomEEDataSourceCreator extends PoolDataSourceCreator {
    private static final Logger LOGGER = Logger.getInstance(LogCategory.OPENEJB, TomEEDataSourceCreator.class);

    @Override
    public DataSource pool(final String name, final DataSource ds, final Properties properties) {
        final Properties converted = new Properties();
        final SuperProperties prop = new SuperProperties().caseInsensitive(true);
        prop.putAll(properties);
        updateProperties(prop, converted, null);

        final PoolConfiguration config = build(PoolProperties.class, converted);
        config.setDataSource(ds);
        final ConnectionPool pool;
        try {
            pool = new ConnectionPool(config);
        } catch (final SQLException e) {
            throw new IllegalStateException(e);
        }
        return build(TomEEDataSource.class, new TomEEDataSource(config, pool, name), converted);
    }

    @Override
    public CommonDataSource pool(final String name, final String driver, final Properties properties) {
        final Properties converted = new Properties();
        converted.setProperty("name", name);

        final SuperProperties prop = new SuperProperties().caseInsensitive(true);
        prop.putAll(properties);

        updateProperties(prop, converted, driver);
        final PoolConfiguration config = build(PoolProperties.class, converted);
        final TomEEDataSource ds = build(TomEEDataSource.class, new TomEEDataSource(config, name), converted);

        final String xa = String.class.cast(properties.remove("XaDataSource"));
        if (xa != null) {
            cleanProperty(ds, "xadatasource");

            final XADataSource xaDs = XADataSourceResource.proxy(Thread.currentThread().getContextClassLoader(), xa);
            ds.setDataSource(xaDs);
        }

        return ds;
    }

    private void updateProperties(final SuperProperties properties, final Properties converted, final String driver) {
        // some compatibility with old dbcp style
        if (driver != null && !properties.containsKey("driverClassName")) {
            converted.setProperty("driverClassName", driver);
        }
        final String jdbcDriver = (String) properties.remove("JdbcDriver");
        if (jdbcDriver != null && !properties.containsKey("driverClassName")) {
            converted.setProperty("driverClassName", jdbcDriver);
        }
        final String url = (String) properties.remove("JdbcUrl");
        if (url != null && !properties.containsKey("url")) {
            converted.setProperty("url", url);
        }
        final String user = (String) properties.remove("user");
        if (user != null && !properties.containsKey("username")) {
            converted.setProperty("username", user);
        }
        final String maxWait = toMillis((String) properties.remove("maxWaitTime"));
        if (maxWait != null && !properties.containsKey("maxWait")) {
            converted.setProperty("maxWait", maxWait);
        }
        final String tb = toMillis((String) properties.remove("timeBetweenEvictionRuns"));
        if (tb != null && !properties.containsKey("timeBetweenEvictionRunsMillis")) {
            converted.setProperty("timeBetweenEvictionRunsMillis", tb);
        }
        final String minEvict = toMillis((String) properties.remove("minEvictableIdleTime"));
        if (minEvict != null && !properties.containsKey("minEvictableIdleTimeMillis")) {
            converted.setProperty("minEvictableIdleTimeMillis", minEvict);
        }

        final String passwordCipher = properties.getProperty("PasswordCipher");
        if (passwordCipher != null && "PlainText".equals(passwordCipher)) { // no need to warn about it
            properties.remove("PasswordCipher");
        } else {
            final String password = properties.getProperty("Password");
            if (passwordCipher != null) {
                final PasswordCipher cipher = PasswordCipherFactory.getPasswordCipher(passwordCipher);
                final String plainPwd = cipher.decrypt(password.toCharArray());
                converted.setProperty("password", plainPwd);

                // all went fine so remove it to avoid errors later
                properties.remove("PasswordCipher");
                properties.remove("Password");
            }
        }

        for (final Map.Entry<Object, Object> entry : properties.entrySet()) {
            final String key = entry.getKey().toString();
            final String value = entry.getValue().toString().trim();
            if (!converted.containsKey(key)) {
                if (!value.isEmpty()) {
                    if ("MaxOpenPreparedStatements".equalsIgnoreCase(key) || "PoolPreparedStatements".equalsIgnoreCase(key)) {
                        if ("0".equalsIgnoreCase(properties.getProperty("MaxOpenPreparedStatements", "0"))
                                || "false".equalsIgnoreCase(properties.getProperty("PoolPreparedStatements", "false"))) {
                            continue;
                        }

                        final String interceptors = properties.getProperty("jdbcInterceptors");
                        if (interceptors == null) {
                            converted.setProperty("jdbcInterceptors",
                                    "StatementCache(max=" + properties.getProperty("MaxOpenPreparedStatements", "128") + ")");
                            LOGGER.debug("Tomcat-jdbc StatementCache added to handle prepared statement cache/pool");
                        } else if (!interceptors.contains("StatementCache")) {
                            converted.setProperty("jdbcInterceptors", interceptors
                                    + ";StatementCache(max=" + properties.getProperty("MaxOpenPreparedStatements", "128") + ")");
                            LOGGER.debug("Tomcat-jdbc StatementCache added to handle prepared statement cache/pool");
                        }
                        continue;
                    }

                    converted.put(Strings.lcfirst(key), value);
                } else if (key.toLowerCase().equals("username") || key.toLowerCase().equals("password")) { // avoid NPE
                    converted.put(Strings.lcfirst(key), "");
                }
            }
        }

        if (!converted.containsKey("password")) {
            converted.setProperty("password", "");
        }

        final String currentUrl = converted.getProperty("url");
        if (currentUrl != null) {
            try {
                final DataSourcePlugin helper = BasicDataSourceUtil.getDataSourcePlugin(currentUrl);
                if (helper != null) {
                    final String newUrl = helper.updatedUrl(currentUrl);
                    if (!currentUrl.equals(newUrl)) {
                        properties.setProperty("url", newUrl);
                    }
                }
            } catch (final SQLException ignored) {
                // no-op
            }
        }
    }

    private String toMillis(final String d) {
        if (d == null) {
            return null;
        }
        return Long.toString(new Duration(d).getTime(TimeUnit.MILLISECONDS));
    }

    @Override
    protected void doDestroy(final CommonDataSource dataSource) throws Throwable {
        final org.apache.tomcat.jdbc.pool.DataSource ds = (org.apache.tomcat.jdbc.pool.DataSource) dataSource;
        if (ds instanceof TomEEDataSource) {
            ((TomEEDataSource) ds).internalJMXUnregister();
        }
        ds.close(true);
    }

    public static class TomEEDataSource extends org.apache.tomcat.jdbc.pool.DataSource {
        private static final Log LOGGER = LogFactory.getLog(TomEEDataSource.class);
        private static final Class<?>[] CONNECTION_POOL_CLASS = new Class<?>[]{ PoolConfiguration.class };

        private ObjectName internalOn;

        public TomEEDataSource(final PoolConfiguration properties, final ConnectionPool pool, final String name) {
            super(readOnly(properties));
            this.pool = pool;
            initJmx(name);
        }

        public TomEEDataSource(final PoolConfiguration poolConfiguration, final String name) {
            super(readOnly(poolConfiguration));
            try { // just to force the pool to be created and be able to register the mbean
                createPool();
                initJmx(name);
            } catch (final Throwable e) {
                LOGGER.error("Can't create DataSource", e);
            }
        }

        @Override
        protected void registerJmx() {
            // no-op
        }

        @Override
        protected void unregisterJmx() {
            // no-op
        }

        @Override
        public ConnectionPool createPool() throws SQLException {
            if (pool != null) {
                return pool;
            } else {
                pool = new TomEEConnectionPool(poolProperties); // to force to init the driver with TCCL
                return pool;
            }
        }

        private static PoolConfiguration readOnly(final PoolConfiguration pool) {
            // if validationQuery is not filled disable testXXX
            if (pool.getValidationQuery() == null || pool.getValidationQuery().isEmpty()) {
                if (pool.isTestOnBorrow()) {
                    LOGGER.info("Disabling testOnBorrow since no validation query is provided");
                    pool.setTestOnBorrow(false);
                }
                if (pool.isTestOnConnect()) {
                    LOGGER.info("Disabling testOnConnect since no validation query is provided");
                    pool.setTestOnConnect(false);
                }
                if (pool.isTestOnReturn()) {
                    LOGGER.info("Disabling testOnReturn since no validation query is provided");
                    pool.setTestOnReturn(false);
                }
                if (pool.isTestWhileIdle()) {
                    LOGGER.info("Disabling testWhileIdle since no validation query is provided");
                    pool.setTestWhileIdle(false);
                }
            }

            // prevent overriding of the configuration
            try {
                return (PoolConfiguration) Proxy.newProxyInstance(TomEEDataSourceCreator.class.getClassLoader(), CONNECTION_POOL_CLASS, new ReadOnlyConnectionpool(pool));
            } catch (final Throwable e) {
                return (PoolConfiguration) Proxy.newProxyInstance(Thread.currentThread().getContextClassLoader(), CONNECTION_POOL_CLASS, new ReadOnlyConnectionpool(pool));
            }
        }

        private void initJmx(final String name) {
            try {
                internalOn = ObjectNameBuilder.uniqueName("datasources", name.replace("/", "_"), this);
                try {
                    if (pool.getJmxPool() != null) {
                        LocalMBeanServer.get().registerMBean(pool.getJmxPool(), internalOn);
                    }
                } catch (final Exception e) {
                    LOGGER.error("Unable to register JDBC pool with JMX", e);
                }
            } catch (final Exception ignored) {
                // no-op
            }
        }

        public void internalJMXUnregister() {
            if (internalOn != null) {
                try {
                    LocalMBeanServer.get().unregisterMBean(internalOn);
                } catch (final Exception e) {
                    LOGGER.error("Unable to unregister JDBC pool with JMX", e);
                }
            }
        }
    }

    private static class ReadOnlyConnectionpool implements InvocationHandler {
        private final PoolConfiguration delegate;

        public ReadOnlyConnectionpool(final PoolConfiguration pool) {
            delegate = pool;
        }

        @Override
        public Object invoke(final Object proxy, final Method method, final Object[] args) throws Throwable {
            final String name = method.getName();
            if (!(name.startsWith("set") && args != null && args.length == 1 && Void.TYPE.equals(method.getReturnType()))) {
                return method.invoke(delegate, args);
            }
            if (name.equals("setDataSource")) {
                delegate.setDataSource(args[0]);
            }
            return null;
        }
    }

    private static class TomEEConnectionPool extends ConnectionPool {
        public TomEEConnectionPool(final PoolConfiguration poolProperties) throws SQLException {
            super(poolProperties);
        }

        @Override
        protected PooledConnection create(final boolean incrementCounter) {
            final PooledConnection con = super.create(incrementCounter);
            if (getPoolProperties().getDataSource() == null) { // using driver
                // init driver with TCCL
                ClassLoader cl = Thread.currentThread().getContextClassLoader();
                if (cl == null) {
                    cl = TomEEConnectionPool.class.getClassLoader();
                }
                try {
                    Reflections.set(con, "driver", Class.forName(getPoolProperties().getDriverClassName(), true, cl).newInstance());
                } catch (final java.lang.Exception cn) {
                    // will fail later, no worry
                }
            }
            return con;
        }
    }
}
