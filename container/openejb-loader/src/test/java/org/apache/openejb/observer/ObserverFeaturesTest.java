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
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.openejb.observer;

import org.apache.openejb.observer.event.AfterEvent;
import org.apache.openejb.observer.event.BeforeEvent;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Method;
import java.net.URI;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class ObserverFeaturesTest {


    @Test
    public void observeAll() {
        a(new Object() {
            public void observe(final @Observes Object event) {
                pass();
            }
        }, new Date());
    }

    @Test
    public void noFalsePositive() {
        a(new Object() {
            public void observe(final @Observes Integer event) {
                fail();
            }

            public void observe(final @Observes Date event) {
                pass();
            }
        }, new Date());
    }

    @Test
    public void inheritance() {
        a(new Object() {
            public void observe(final @Observes Number event) {
                pass();
            }
        }, 42);
    }

    @Test
    public void overloaded() {
        a(new Object() {
            public void number(final @Observes Number event) {
                fail();
            }

            public void integer(final @Observes Integer event) {
                pass();
            }
        }, 42);
    }

    @Test
    @Assert({ "before", "observe" })
    public void beforeEvent() {
        a(new Object() {
            public void before(final @Observes BeforeEvent<Integer> event) {
                invoked();
            }

            public void observe(final @Observes Integer event) {
                invoked();
            }
        }, 42);
    }

    @Test
    @Assert({ "observe", "after" })
    public void afterEvent() {
        a(new Object() {
            public void after(final @Observes AfterEvent<Integer> event) {
                invoked();
            }

            public void observe(final @Observes Integer event) {
                invoked();
            }
        }, 42);
    }


    @Test
    @Assert({ "before", "after" })
    public void beforeInvokeAfter() {
        a(new Object() {
            public void after(final @Observes AfterEvent<Integer> event) {
                invoked();
            }

            public void before(final @Observes BeforeEvent<Integer> event) {
                invoked();
            }
        }, 42);
    }

    @Test
    public void noFalseBeforePositive() {
        a(new Object() {
            public void integer(final @Observes BeforeEvent<Integer> event) {
                pass();
            }

            public void date(final @Observes BeforeEvent<Date> event) {
                fail();
            }
        }, 42);
    }

    @Test
    @Assert("integer")
    public void noFalseAfterPositive() {
        a(new Object() {
            public void integer(final @Observes AfterEvent<Integer> event) {
                invoked();
            }

            public void date(final @Observes AfterEvent<Date> event) {
                invoked();
            }
        }, 42);
    }

    @Test
    public void beforeInheritance() {
        a(new Object() {
            public void number(final @Observes AfterEvent<Number> event) {
                pass();
            }
        }, 42);
    }

    @Test
    public void beforeObject() {
        a(new Object() {
            public void integer(final @Observes AfterEvent<Object> event) {
                pass();
            }
        }, 42);
    }

    @Test
    public void beforeInheritanceOverloaded() {
        a(new Object() {
            public void integer(final @Observes AfterEvent<Integer> event) {
                pass();
            }

            public void number(final @Observes AfterEvent<Number> event) {
                fail();
            }
        }, 42);
    }

    @Test
    public void afterInheritance() {
        a(new Object() {
            public void number(final @Observes AfterEvent<Number> event) {
                pass();
            }
        }, 42);
    }

    @Test
    public void afterObject() {
        a(new Object() {
            public void integer(final @Observes AfterEvent<Object> event) {
                pass();
            }
        }, 42);
    }

    @Test
    public void afterInheritanceOverloaded() {
        a(new Object() {
            public void integer(final @Observes AfterEvent<Integer> event) {
                pass();
            }

            public void number(final @Observes AfterEvent<Number> event) {
                fail();
            }
        }, 42);
    }


    @Test
    @Assert({ "number", "afterInteger", "beforeDate", "object", "afterObject", "object", "afterObject" })
    public void sequence() {
        a(new Object() {
            public void object(final @Observes Object event) {
                invoked();
            }

            public void number(final @Observes Number event) {
                invoked();
            }

            public void afterInteger(final @Observes AfterEvent<Integer> event) {
                invoked();
            }

            public void afterObject(final @Observes AfterEvent<Object> event) {
                invoked();
            }

            public void beforeDate(final @Observes BeforeEvent<Date> event) {
                invoked();
            }
        }, 42, new Date(), URI.create("foo:bar"));
    }


    private List<Boolean> conditions = new ArrayList<Boolean>();
    private List<String> invocations = new ArrayList<String>();

    @Before
    public void init() {
        conditions.clear();
    }

    public void pass() {
        conditions.add(true);
    }

    public void fail() {
        conditions.add(false);
    }

    public void invoked() {
        final Method method = caller(2);
        invocations.add(method.getName());
    }

    private void a(final Object observer, Object... events) {
        final ObserverManager observers = new ObserverManager();
        observers.addObserver(observer);

        conditions.clear();
        invocations.clear();

        for (Object event : events) {
            observers.fireEvent(event);
        }

        final Method testMethod = caller(2);
        final Assert annotation = testMethod.getAnnotation(Assert.class);
        if (annotation != null) {

            Util.assertEvent(invocations, annotation.value());

        } else {

            org.junit.Assert.assertNotEquals(0, conditions.size());
            for (Boolean condition : conditions) {
                org.junit.Assert.assertTrue(condition);
            }
        }
    }

    private Method caller(final int i) {
        try {
            final StackTraceElement[] stackTrace = new Exception().fillInStackTrace().getStackTrace();
            final String methodName = stackTrace[i].getMethodName();
            final String className = stackTrace[i].getClassName();

            final Class<?> clazz = this.getClass().getClassLoader().loadClass(className);
            for (Method method : clazz.getDeclaredMethods()) {
                if (methodName.endsWith(method.getName())) {
                    return method;
                }
            }

            throw new NoSuchMethodException(methodName);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
    @java.lang.annotation.Target({ java.lang.annotation.ElementType.METHOD })
    public @interface Assert {
        String[] value();
    }

}
