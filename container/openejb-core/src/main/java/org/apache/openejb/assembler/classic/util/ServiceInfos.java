/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.openejb.assembler.classic.util;

import org.apache.openejb.JndiConstants;
import org.apache.openejb.OpenEJBException;
import org.apache.openejb.OpenEJBRuntimeException;
import org.apache.openejb.assembler.classic.Assembler;
import org.apache.openejb.assembler.classic.OpenEjbConfiguration;
import org.apache.openejb.assembler.classic.ServiceInfo;
import org.apache.openejb.loader.SystemInstance;
import org.apache.openejb.spi.ContainerSystem;
import org.apache.openejb.util.LogCategory;
import org.apache.openejb.util.Logger;
import org.apache.xbean.recipe.ObjectRecipe;
import org.apache.xbean.recipe.Option;
import org.apache.xbean.recipe.UnsetPropertiesRecipe;

import javax.naming.Context;
import javax.naming.NamingException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Properties;

// mainly an utility class when using services as config. Null is returned if the action is not possible
// (instead of throwing an exception)
public final class ServiceInfos {

    private ServiceInfos() {
        // no-op
    }

    public static Object resolve(final Collection<ServiceInfo> services, final String id) {
        if (id == null) {
            return null;
        }

        try {
            return build(services, find(services, id));
        } catch (final OpenEJBException e) {
            throw new OpenEJBRuntimeException(e);
        }
    }

    public static ServiceInfo find(final Collection<ServiceInfo> services, final String id) {
        for (final ServiceInfo s : services) {
            if (id.equals(s.id)) {
                return s;
            }
        }
        return null;
    }

    public static ServiceInfo findByClass(final Collection<ServiceInfo> services, final String clazz) {
        for (final ServiceInfo s : services) {
            if (clazz.equals(s.className)) {
                return s;
            }
        }
        return null;
    }

    public static List<Object> resolve(final Collection<ServiceInfo> serviceInfos, final String[] ids) {
        return resolve(serviceInfos, ids, null);
    }

    public static List<Object> resolve(final Collection<ServiceInfo> serviceInfos, final String[] ids, final Factory factory) {
        if (ids == null || ids.length == 0) {
            return null;
        }

        final List<Object> instances = new ArrayList<Object>();
        for (final String id : ids) {
            Object instance = resolve(serviceInfos, id);
            if (instance == null) {  // maybe id == classname
                try {
                    final Class<?> aClass = Thread.currentThread().getContextClassLoader().loadClass(id);
                    if (factory == null) {
                        instance = aClass.newInstance();
                    } else {
                        instance = factory.newInstance(aClass);
                    }
                } catch (final Exception e) {
                    // ignore
                }
            }
            if (instance == null) {
                instance = resolve(SystemInstance.get().getComponent(OpenEjbConfiguration.class).facilities.services, id);
            }

            if (instance != null) {
                instances.add(instance);
            }
        }
        return instances;
    }

    public static Properties serviceProperties(final Collection<ServiceInfo> serviceInfos, final String id) {
        if (id == null) {
            return null;
        }

        final ServiceInfo info = find(serviceInfos, id);
        if (info == null) {
            return null;
        }
        return info.properties;
    }

    private static Object build(final Collection<ServiceInfo> services, final ServiceInfo info) throws OpenEJBException {
        if (info == null) {
            return null;
        }

        final ObjectRecipe serviceRecipe = Assembler.prepareRecipe(info);
        return build(services, info, serviceRecipe);
    }

    public static Object build(final Collection<ServiceInfo> services, final ServiceInfo info, final ObjectRecipe serviceRecipe) {
        if (!info.properties.containsKey("properties")) {
            info.properties.put("properties", new UnsetPropertiesRecipe());
        }

        // we can't ask to have a setter for existing code
        serviceRecipe.allow(Option.FIELD_INJECTION);
        serviceRecipe.allow(Option.PRIVATE_PROPERTIES);

        if ("org.apache.openejb.config.sys.MapFactory".equals(info.className)) {
            serviceRecipe.setProperty("prop", info.properties);
        } else {
            for (final Map.Entry<Object, Object> entry : info.properties.entrySet()) { // manage links
                final String key = entry.getKey().toString();
                final Object value = entry.getValue();
                if (value instanceof String) {
                    final String valueStr = value.toString();
                    if (valueStr.startsWith("$")){
                        serviceRecipe.setProperty(key, resolve(services, valueStr.substring(1)));
                    } else if (valueStr.startsWith("@")){
                        final Context jndiContext = SystemInstance.get().getComponent(ContainerSystem.class).getJNDIContext();
                        try {
                            serviceRecipe.setProperty(key, jndiContext.lookup(JndiConstants.OPENEJB_RESOURCE_JNDI_PREFIX + valueStr.substring(1)));
                        } catch (final NamingException e) {
                            try {
                                serviceRecipe.setProperty(key, jndiContext.lookup(valueStr.substring(1)));
                            } catch (final NamingException e1) {
                                Logger.getInstance(LogCategory.OPENEJB, ServiceInfos.class).warning("Value " + valueStr + " starting with @ but doesn't point to an existing resource, using raw value");
                                serviceRecipe.setProperty(key, value);
                            }
                        }
                    } else {
                        serviceRecipe.setProperty(key, value);
                    }
                } else {
                    serviceRecipe.setProperty(key, entry.getValue());
                }
            }
        }

        final Object service = serviceRecipe.create();

        SystemInstance.get().addObserver(service);
        Assembler.logUnusedProperties(serviceRecipe, info);

        return service;
    }

    public interface Factory {
        Object newInstance(final Class<?> clazz) throws Exception;
    }
}
