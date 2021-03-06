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
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.openejb.server;

import org.apache.openejb.loader.SystemInstance;
import org.apache.openejb.monitoring.LocalMBeanServer;
import org.apache.openejb.monitoring.ManagedMBean;
import org.apache.openejb.monitoring.ObjectNameBuilder;
import org.apache.openejb.util.LogCategory;
import org.apache.openejb.util.Logger;
import org.apache.xbean.finder.ResourceFinder;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.io.IOException;
import java.net.InetAddress;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * @version $Rev$ $Date$
 * @org.apache.xbean.XBean element="simpleServiceManager"
 */
public class SimpleServiceManager extends ServiceManager {
    private static final Logger LOGGER = Logger.getInstance(LogCategory.OPENEJB_SERVER, SimpleServiceManager.class);

    private static ObjectName objectName = null;

    private ServerService[] daemons;
    private boolean stop = false;


    public SimpleServiceManager() {
    }

    // Have properties files (like xinet.d) that specifies what daemons to
    // Look into the xinet.d file structure again
    // conf/server.d/
    //    admin.properties
    //    ejbd.properties
    //    webadmin.properties
    //    telnet.properties
    //    corba.properties
    //    soap.properties
    //    xmlrpc.properties
    //    httpejb.properties
    //    webejb.properties
    //    xmlejb.properties
    // Each contains the class name of the daemon implamentation
    // The port to use
    // whether it's turned on
    // May be reusable elsewhere, move if another use occurs
    public static class ServiceFinder {

        private final ResourceFinder resourceFinder;
        private ClassLoader classLoader;

        public ServiceFinder(String basePath) {
            this(basePath, Thread.currentThread().getContextClassLoader());
        }

        public ServiceFinder(String basePath, ClassLoader classLoader) {
            this.resourceFinder = new ResourceFinder(basePath, classLoader);
            this.classLoader = classLoader;
        }

        public Map mapAvailableServices(Class interfase) throws IOException, ClassNotFoundException {
            Map services = resourceFinder.mapAvailableProperties(ServerService.class.getName());

            for (Iterator iterator = services.entrySet().iterator(); iterator.hasNext(); ) {
                Map.Entry entry = (Map.Entry) iterator.next();
                String name = (String) entry.getKey();
                Properties properties = (Properties) entry.getValue();

                String className = properties.getProperty("className");
                if (className == null) {
                    className = properties.getProperty("classname");
                    if (className == null) {
                        className = properties.getProperty("server");
                    }
                }

                Class impl = classLoader.loadClass(className);

                properties.put(interfase, impl);
                String rawProperties = resourceFinder.findString(interfase.getName() + "/" + name);
                properties.put(Properties.class, rawProperties);

            }
            return services;
        }
    }

    private static ObjectName getDiscoveryRegistryObjectName() {
        if (null == objectName) {
            final ObjectNameBuilder jmxName = new ObjectNameBuilder("openejb");
            jmxName.set("type", "Server");
            jmxName.set("name", "DiscoveryRegistry");
            objectName = jmxName.build();
        }
        return objectName;
    }

    @Override
    public void init() throws Exception {
        try {
            ServiceLogger.MDCput("SERVER", "main");
            InetAddress localhost = InetAddress.getLocalHost();
            ServiceLogger.MDCput("HOST", localhost.getHostName());
        } catch (Throwable e) {
            //Ignore
        }

        DiscoveryRegistry registry = new DiscoveryRegistry();

        // register the mbean
        try {
            LocalMBeanServer.get().registerMBean(new ManagedMBean(registry), getDiscoveryRegistryObjectName());
        } catch (Throwable e) {
            logger.error("Failed to register 'openejb' MBean", e);
        }

        SystemInstance.get().setComponent(DiscoveryRegistry.class, registry);

        ServiceFinder serviceFinder = new ServiceFinder("META-INF/");

        Map<String, Properties> availableServices = serviceFinder.mapAvailableServices(ServerService.class);

        List<ServerService> enabledServers = initServers(availableServices);

        daemons = enabledServers.toArray(new ServerService[]{});
    }

    @Override
    public synchronized void start(boolean block) throws ServiceException {
        boolean display = SystemInstance.get().getOptions().get("openejb.nobanner", (String) null) == null;

        // starting then displaying to get a more relevant log

        final Exception[] errors = new Exception[daemons.length];
        for (int i = 0; i < daemons.length; i++) {
            final ServerService d = daemons[i];

            LOGGER.info("Starting service " + d.getName());
            try {
                d.start();
                errors[i] = null;
                LOGGER.info("Started service " + d.getName());
            } catch (Exception e) {
                errors[i] = e;
                LOGGER.info("Can't start service " + d.getName(), e);
            }
        }

        if (display) {
            LOGGER.info("  ** Bound Services **");
            printRow("NAME", "IP", "PORT");
        }
        for (int i = 0; i < daemons.length; i++) {
            final ServerService d = daemons[i];
            if (errors[i] == null) {
                if (display && d.getPort() != -1) {
                    printRow(d.getName(), d.getIP(), d.getPort() + "");
                }
            } else {
                logger.fatal("Service Start Failed: " + d.getName() + " " + d.getIP() + " " + d.getPort() + ": " + errors[i].getMessage());
                if (display) {
                    printRow(d.getName(), "----", "FAILED");
                }
            }
        }

        if (display) {
            LOGGER.info("-------");
            LOGGER.info("Ready!");
        }

        if (!block) {
            return;
        }

        /*
         * This will cause the user thread (the thread that keeps the
         *  vm alive) to go into a state of constant waiting.
         *  Each time the thread is woken up, it checks to see if
         *  it should continue waiting.
         *
         *  To stop the thread (and the VM), just call the stop method
         *  which will set 'stop' to true and notify the user thread.
         */
        try {
            while (!stop) {

                this.wait(Long.MAX_VALUE);
            }
        } catch (Throwable t) {
            logger.fatal("Unable to keep the server thread alive. Received exception: " + t.getClass().getName() + " : " + t.getMessage());
        }
        logger.info("Stopping Remote Server");
    }

    @Override
    public synchronized void stop() throws ServiceException {
        logger.info("Stopping server services");
        stop = true;

        final ServerService[] services = daemons.clone();

        final MBeanServer server = LocalMBeanServer.get();
        for (int i = 0; i < services.length; i++) {
            final ObjectName on = getObjectName(services[i].getName());
            if (server.isRegistered(on)) {
                try {
                    server.unregisterMBean(on);
                } catch (Exception ignored) {
                    // no-op
                }
            }

            try {
                services[i].stop();
            } catch (ServiceException e) {
                logger.fatal("Service Shutdown Failed: " + services[i].getName() + ".  Exception: " + e.getMessage(), e);
            }
        }

        // De-register the mbean
        try {
            final ObjectName objectName = getDiscoveryRegistryObjectName();

            if (server.isRegistered(objectName)) {
                server.unregisterMBean(objectName);
            }

        } catch (Throwable e) {
            logger.warning("Failed to de-register the 'openejb' mbean", e);
        }

        setServiceManager(null);
        notifyAll();
    }

    private void printRow(String col1, String col2, String col3) {

        col1 += "                    ";
        col1 = col1.substring(0, 20);

        col2 += "                    ";
        col2 = col2.substring(0, 15);

        col3 += "                    ";
        col3 = col3.substring(0, 6);

        final StringBuilder sb = new StringBuilder(50)
            .append("  ").append(col1)
            .append(" ").append(col2)
            .append(" ").append(col3);

        LOGGER.info(sb.toString());
    }

    public ServerService[] getDaemons() {
        return daemons;
    }
}
