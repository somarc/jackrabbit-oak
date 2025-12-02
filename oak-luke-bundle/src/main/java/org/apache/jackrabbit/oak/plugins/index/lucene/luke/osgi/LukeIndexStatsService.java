/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.jackrabbit.oak.plugins.index.lucene.luke.osgi;

import java.lang.management.ManagementFactory;

import javax.management.MBeanServer;
import javax.management.ObjectName;

import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.apache.felix.scr.annotations.ReferencePolicy;
import org.apache.jackrabbit.oak.plugins.index.lucene.IndexCopier;
import org.apache.jackrabbit.oak.plugins.index.lucene.IndexTracker;
import org.apache.jackrabbit.oak.plugins.index.lucene.luke.LukeIndexStatsMBean;
import org.apache.jackrabbit.oak.plugins.index.lucene.luke.LukeIndexStatsMBeanImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OSGi Component that registers LUKE Index Statistics MBean.
 * Compatible with AEM 6.5.x using Felix SCR annotations.
 */
@Component(
    immediate = true,
    metatype = false,
    label = "Apache Jackrabbit Oak LUKE Index Statistics",
    description = "Provides LUKE-based index inspection and statistics via JMX"
)
public class LukeIndexStatsService {
    
    private static final Logger log = LoggerFactory.getLogger(LukeIndexStatsService.class);
    private static final String MBEAN_NAME = "org.apache.jackrabbit.oak:name=LukeIndexStats,type=LukeIndexStats";
    
    @Reference(
        cardinality = ReferenceCardinality.MANDATORY_UNARY,
        policy = ReferencePolicy.STATIC
    )
    private IndexTracker indexTracker;
    
    @Reference(
        cardinality = ReferenceCardinality.OPTIONAL_UNARY,
        policy = ReferencePolicy.DYNAMIC
    )
    private volatile IndexCopier indexCopier;
    
    private LukeIndexStatsMBeanImpl lukeMBean;
    private ObjectName mbeanObjectName;
    private MBeanServer mbeanServer;
    
    @Activate
    protected void activate() {
        try {
            log.info("Activating Oak LUKE Index Statistics Service");
            
            // Create the MBean implementation
            lukeMBean = new LukeIndexStatsMBeanImpl(indexTracker, indexCopier);
            
            // Register with JMX
            mbeanServer = ManagementFactory.getPlatformMBeanServer();
            mbeanObjectName = new ObjectName(MBEAN_NAME);
            
            if (mbeanServer.isRegistered(mbeanObjectName)) {
                log.warn("MBean {} already registered, unregistering old instance", MBEAN_NAME);
                mbeanServer.unregisterMBean(mbeanObjectName);
            }
            
            mbeanServer.registerMBean(lukeMBean, mbeanObjectName);
            log.info("Successfully registered LUKE Index Statistics MBean at: {}", MBEAN_NAME);
            log.info("Access via JMX Console: http://localhost:4502/system/console/jmx");
            
        } catch (Exception e) {
            log.error("Failed to register LUKE Index Statistics MBean", e);
        }
    }
    
    @Deactivate
    protected void deactivate() {
        try {
            log.info("Deactivating Oak LUKE Index Statistics Service");
            
            if (mbeanServer != null && mbeanObjectName != null) {
                if (mbeanServer.isRegistered(mbeanObjectName)) {
                    mbeanServer.unregisterMBean(mbeanObjectName);
                    log.info("Successfully unregistered LUKE Index Statistics MBean");
                }
            }
            
            lukeMBean = null;
            mbeanObjectName = null;
            mbeanServer = null;
            
        } catch (Exception e) {
            log.error("Failed to unregister LUKE Index Statistics MBean", e);
        }
    }
    
    /**
     * Bind method for IndexCopier (dynamic reference)
     */
    protected void bindIndexCopier(IndexCopier indexCopier) {
        this.indexCopier = indexCopier;
        log.info("IndexCopier service bound to LUKE Index Statistics");
        
        // Update the MBean if already created
        if (lukeMBean != null) {
            log.info("Updating LUKE MBean with new IndexCopier reference");
            // MBean will use the new indexCopier on next operation
        }
    }
    
    /**
     * Unbind method for IndexCopier (dynamic reference)
     */
    protected void unbindIndexCopier(IndexCopier indexCopier) {
        if (this.indexCopier == indexCopier) {
            this.indexCopier = null;
            log.warn("IndexCopier service unbound from LUKE Index Statistics");
        }
    }
}

