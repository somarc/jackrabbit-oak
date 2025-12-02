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

import java.io.File;
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
import org.apache.jackrabbit.oak.plugins.index.lucene.luke.LukeIndexStatsMBeanImplSimple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OSGi Component that registers the LUKE Index Statistics MBean.
 * 
 * <p>Provides LUKE-style index inspection capabilities via JMX, answering:</p>
 * <ul>
 *   <li>What fields are in my index?</li>
 *   <li>What's consuming the most space?</li>
 *   <li>Is my index healthy?</li>
 * </ul>
 * 
 * <p>Compatible with AEM 6.5.x (Oak 1.22.x) using Felix SCR annotations.</p>
 * 
 * <h2>For Fulltext Backup</h2>
 * <p>Use oak-run tika commands (can run while AEM is online):</p>
 * <pre>
 * oak-run tika --generate ...
 * oak-run tika --populate ...
 * </pre>
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
    
    /**
     * IndexTracker is optional - not available in Oak 1.22.x via OSGi.
     * We use direct filesystem access instead.
     */
    @Reference(
        cardinality = ReferenceCardinality.OPTIONAL_UNARY,
        policy = ReferencePolicy.DYNAMIC
    )
    private volatile IndexTracker indexTracker;
    
    /**
     * IndexCopier provides the local index cache path.
     * Optional - falls back to repository.home if not available.
     */
    @Reference(
        cardinality = ReferenceCardinality.OPTIONAL_UNARY,
        policy = ReferencePolicy.DYNAMIC
    )
    private volatile IndexCopier indexCopier;
    
    private LukeIndexStatsMBeanImplSimple lukeMBean;
    private ObjectName mbeanObjectName;
    private MBeanServer mbeanServer;
    
    @Activate
    protected void activate() {
        try {
            log.info("Activating Oak LUKE Index Statistics Service");
            
            if (indexTracker == null) {
                log.info("IndexTracker not available - using filesystem-only mode (normal for Oak 1.22.x)");
            }
            
            if (indexCopier == null) {
                log.info("IndexCopier not available - will scan repository/index directory");
            }
            
            // Determine repository home
            File repositoryHome = new File(System.getProperty("repository.home", "crx-quickstart"));
            log.info("Using repository home: {}", repositoryHome.getAbsolutePath());
            
            // Create the MBean implementation
            lukeMBean = new LukeIndexStatsMBeanImplSimple(indexTracker, indexCopier, repositoryHome);
            
            // Register with JMX
            mbeanServer = ManagementFactory.getPlatformMBeanServer();
            mbeanObjectName = new ObjectName(MBEAN_NAME);
            
            if (mbeanServer.isRegistered(mbeanObjectName)) {
                log.warn("MBean {} already registered, unregistering old instance", MBEAN_NAME);
                mbeanServer.unregisterMBean(mbeanObjectName);
            }
            
            mbeanServer.registerMBean(lukeMBean, mbeanObjectName);
            log.info("✅ LUKE Index Statistics MBean registered: {}", MBEAN_NAME);
            log.info("   Access via: http://localhost:4502/system/console/jmx");
            log.info("   Search for: LukeIndexStats");
            
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
    
    // Dynamic bind/unbind for IndexTracker
    protected void bindIndexTracker(IndexTracker indexTracker) {
        this.indexTracker = indexTracker;
        log.info("IndexTracker service bound");
    }
    
    protected void unbindIndexTracker(IndexTracker indexTracker) {
        if (this.indexTracker == indexTracker) {
            this.indexTracker = null;
            log.info("IndexTracker service unbound");
        }
    }
    
    // Dynamic bind/unbind for IndexCopier
    protected void bindIndexCopier(IndexCopier indexCopier) {
        this.indexCopier = indexCopier;
        log.info("IndexCopier service bound");
    }
    
    protected void unbindIndexCopier(IndexCopier indexCopier) {
        if (this.indexCopier == indexCopier) {
            this.indexCopier = null;
            log.info("IndexCopier service unbound");
        }
    }
}
