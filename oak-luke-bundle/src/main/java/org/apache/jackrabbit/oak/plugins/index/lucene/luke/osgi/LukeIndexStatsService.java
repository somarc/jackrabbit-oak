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
import java.util.ArrayList;
import java.util.List;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.StandardMBean;

import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.apache.felix.scr.annotations.ReferencePolicy;
import org.apache.jackrabbit.oak.plugins.index.lucene.IndexCopier;
import org.apache.jackrabbit.oak.plugins.index.lucene.IndexTracker;
import org.apache.jackrabbit.oak.plugins.index.lucene.luke.LukeIndexStatsMBean;
import org.apache.jackrabbit.oak.plugins.index.lucene.luke.LukeIndexStatsMBeanImplSimple;
import org.apache.jackrabbit.oak.plugins.index.lucene.luke.mbean.LukeBasicStatsMBean;
import org.apache.jackrabbit.oak.plugins.index.lucene.luke.mbean.LukeDiscoveryMBean;
import org.apache.jackrabbit.oak.plugins.index.lucene.luke.mbean.LukeDocumentsMBean;
import org.apache.jackrabbit.oak.plugins.index.lucene.luke.mbean.LukeFieldAnalysisMBean;
import org.apache.jackrabbit.oak.plugins.index.lucene.luke.mbean.LukeInsightsMBean;
import org.apache.jackrabbit.oak.plugins.index.lucene.luke.mbean.LukeMBeanImpl;
import org.apache.jackrabbit.oak.plugins.index.lucene.luke.mbean.LukeTermAnalysisMBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OSGi Component that registers multiple LUKE Index MBeans for organized JMX access.
 * 
 * <h2>Registered MBeans</h2>
 * <ul>
 *   <li><b>LukeDiscovery</b> - Find and validate indexes</li>
 *   <li><b>LukeBasicStats</b> - Quick health check and basic stats</li>
 *   <li><b>LukeFieldAnalysis</b> - Field-level analysis</li>
 *   <li><b>LukeTermAnalysis</b> - Term-level analysis</li>
 *   <li><b>LukeDocuments</b> - Document inspection</li>
 *   <li><b>LukeInsights</b> - ⭐ Actionable optimization insights</li>
 *   <li><b>LukeIndexStats</b> - Legacy single MBean with all operations</li>
 * </ul>
 * 
 * <p>Compatible with AEM 6.5.x (Oak 1.22.x) using Felix SCR annotations.</p>
 */
@Component(
    immediate = true,
    metatype = false,
    label = "Apache Jackrabbit Oak LUKE Index Statistics",
    description = "Provides LUKE-based index inspection and statistics via JMX (multiple organized MBeans)"
)
public class LukeIndexStatsService {
    
    private static final Logger log = LoggerFactory.getLogger(LukeIndexStatsService.class);
    
    // MBean naming pattern
    private static final String MBEAN_DOMAIN = "org.apache.jackrabbit.oak";
    private static final String MBEAN_TYPE = "LukeIndexStats";
    
    @Reference(
        cardinality = ReferenceCardinality.OPTIONAL_UNARY,
        policy = ReferencePolicy.DYNAMIC
    )
    private volatile IndexTracker indexTracker;
    
    @Reference(
        cardinality = ReferenceCardinality.OPTIONAL_UNARY,
        policy = ReferencePolicy.DYNAMIC
    )
    private volatile IndexCopier indexCopier;
    
    private LukeIndexStatsMBeanImplSimple coreImpl;
    private LukeMBeanImpl lukeMBeanImpl;
    private MBeanServer mbeanServer;
    private List<ObjectName> registeredMBeans = new ArrayList<ObjectName>();
    
    @Activate
    protected void activate() {
        try {
            log.info("Activating Oak LUKE Index Statistics Service (Multi-MBean)");
            
            // Determine repository home
            File repositoryHome = new File(System.getProperty("repository.home", "crx-quickstart"));
            log.info("Using repository home: {}", repositoryHome.getAbsolutePath());
            
            // Create the core implementation
            coreImpl = new LukeIndexStatsMBeanImplSimple(indexTracker, indexCopier, repositoryHome);
            lukeMBeanImpl = new LukeMBeanImpl(coreImpl);
            
            // Get MBean server
            mbeanServer = ManagementFactory.getPlatformMBeanServer();
            
            // Register grouped MBeans
            registerMBean(LukeDiscoveryMBean.class, lukeMBeanImpl, "Discovery", 
                    "Find and validate local index directories");
            
            registerMBean(LukeBasicStatsMBean.class, lukeMBeanImpl, "BasicStats", 
                    "Quick health check, basic stats, segment info");
            
            registerMBean(LukeFieldAnalysisMBean.class, lukeMBeanImpl, "FieldAnalysis", 
                    "Field-level analysis: sizes, flags, cardinality");
            
            registerMBean(LukeTermAnalysisMBean.class, lukeMBeanImpl, "TermAnalysis", 
                    "Term-level analysis: frequencies, distributions");
            
            registerMBean(LukeDocumentsMBean.class, lukeMBeanImpl, "Documents", 
                    "Document inspection and sampling");
            
            registerMBean(LukeInsightsMBean.class, lukeMBeanImpl, "Insights", 
                    "⭐ Actionable optimization insights - START HERE!");
            
            // Also register the legacy all-in-one MBean for backwards compatibility
            registerMBean(LukeIndexStatsMBean.class, coreImpl, "All", 
                    "All operations in one MBean (legacy)");
            
            log.info("═══════════════════════════════════════════════════════════════════════════");
            log.info("✅ LUKE Index Statistics MBeans registered successfully!");
            log.info("   Access via: http://localhost:4502/system/console/jmx");
            log.info("   Search for: LukeIndexStats");
            log.info("");
            log.info("   Available MBeans:");
            log.info("   • LukeIndexStats,name=Discovery     - Find indexes");
            log.info("   • LukeIndexStats,name=BasicStats    - Health & overview");
            log.info("   • LukeIndexStats,name=FieldAnalysis - Field analysis");
            log.info("   • LukeIndexStats,name=TermAnalysis  - Term analysis");
            log.info("   • LukeIndexStats,name=Documents     - Doc inspection");
            log.info("   • LukeIndexStats,name=Insights      - ⭐ START HERE!");
            log.info("   • LukeIndexStats,name=All           - Legacy (all ops)");
            log.info("═══════════════════════════════════════════════════════════════════════════");
            
        } catch (Exception e) {
            log.error("Failed to register LUKE Index Statistics MBeans", e);
        }
    }
    
    private <T> void registerMBean(Class<T> mbeanInterface, T impl, String name, String description) {
        try {
            ObjectName objectName = new ObjectName(
                    MBEAN_DOMAIN + ":type=" + MBEAN_TYPE + ",name=" + name);
            
            // Unregister if already exists
            if (mbeanServer.isRegistered(objectName)) {
                log.debug("MBean {} already registered, replacing", objectName);
                mbeanServer.unregisterMBean(objectName);
            }
            
            // Create StandardMBean wrapper to properly expose the interface
            StandardMBean mbean = new StandardMBean(impl, mbeanInterface);
            
            mbeanServer.registerMBean(mbean, objectName);
            registeredMBeans.add(objectName);
            
            log.debug("Registered MBean: {} - {}", objectName, description);
            
        } catch (Exception e) {
            log.error("Failed to register MBean: " + name, e);
        }
    }
    
    @Deactivate
    protected void deactivate() {
        try {
            log.info("Deactivating Oak LUKE Index Statistics Service");
            
            // Unregister all MBeans
            for (ObjectName objectName : registeredMBeans) {
                try {
                    if (mbeanServer != null && mbeanServer.isRegistered(objectName)) {
                        mbeanServer.unregisterMBean(objectName);
                        log.debug("Unregistered MBean: {}", objectName);
                    }
                } catch (Exception e) {
                    log.warn("Failed to unregister MBean: " + objectName, e);
                }
            }
            
            registeredMBeans.clear();
            lukeMBeanImpl = null;
            coreImpl = null;
            mbeanServer = null;
            
            log.info("LUKE Index Statistics MBeans unregistered");
            
        } catch (Exception e) {
            log.error("Failed to deactivate LUKE Index Statistics Service", e);
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
