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
package org.apache.jackrabbit.oak.segment.consensus.osgi;

import org.apache.jackrabbit.oak.segment.consensus.mount.GlobalChainMounter;
import org.apache.jackrabbit.oak.spi.state.NodeStore;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.util.tracker.ServiceTracker;
import org.osgi.util.tracker.ServiceTrackerCustomizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * BundleActivator for the oak-segment-consensus fragment.
 * 
 * <p>Since fragment bundles cannot use Declarative Services (@Component),
 * we manually track the NodeStore service and instantiate GlobalChainMounter
 * when it becomes available.</p>
 * 
 * <p>This is a POC workaround to access internal oak-segment-tar APIs.
 * Production (Option A) will use public Oak SPIs and won't need this.</p>
 */
public class ConsensusActivator implements BundleActivator {
    
    private static final Logger LOG = LoggerFactory.getLogger(ConsensusActivator.class);
    
    private ServiceTracker<NodeStore, NodeStore> nodeStoreTracker;
    private GlobalChainMounter mounter;
    
    @Override
    public void start(BundleContext context) throws Exception {
        LOG.info("===========================================");
        LOG.info("  Blockchain AEM Consensus Fragment");
        LOG.info("  BundleActivator Starting...");
        LOG.info("===========================================");
        
        // Track NodeStore service - mount when it becomes available
        nodeStoreTracker = new ServiceTracker<>(
            context,
            NodeStore.class,
            new NodeStoreTracker(context)
        );
        
        nodeStoreTracker.open();
        
        LOG.info("NodeStore tracker opened - waiting for service...");
    }
    
    @Override
    public void stop(BundleContext context) throws Exception {
        LOG.info("Blockchain AEM Consensus Fragment stopping...");
        
        if (mounter != null) {
            try {
                mounter.shutdown();
                LOG.info("GlobalChainMounter shut down successfully");
            } catch (Exception e) {
                LOG.error("Error shutting down GlobalChainMounter", e);
            }
        }
        
        if (nodeStoreTracker != null) {
            nodeStoreTracker.close();
        }
        
        LOG.info("Blockchain AEM Consensus Fragment stopped");
    }
    
    /**
     * Tracks NodeStore service and instantiates GlobalChainMounter when found.
     */
    private class NodeStoreTracker implements ServiceTrackerCustomizer<NodeStore, NodeStore> {
        
        private final BundleContext context;
        
        NodeStoreTracker(BundleContext context) {
            this.context = context;
        }
        
        @Override
        public NodeStore addingService(ServiceReference<NodeStore> reference) {
            NodeStore nodeStore = context.getService(reference);
            
            // Skip if this is our own Composite NodeStore (prevent cycles)
            Object description = reference.getProperty("oak.nodestore.description");
            if (description != null && description.toString().contains("Blockchain AEM")) {
                LOG.debug("Skipping our own Composite NodeStore");
                return nodeStore;
            }
            
            LOG.info("NodeStore service detected: {}", nodeStore.getClass().getSimpleName());
            LOG.info("Attempting to mount global blockchain store...");
            
            try {
                // Instantiate GlobalChainMounter with the backing NodeStore
                mounter = new GlobalChainMounter(context, nodeStore);
                mounter.mount();
                
                LOG.info("✅ GlobalChainMounter instantiated and mount attempted");
                
            } catch (Exception e) {
                LOG.error("❌ Failed to instantiate GlobalChainMounter", e);
            }
            
            return nodeStore;
        }
        
        @Override
        public void modifiedService(ServiceReference<NodeStore> reference, NodeStore service) {
            // Not concerned with modifications
        }
        
        @Override
        public void removedService(ServiceReference<NodeStore> reference, NodeStore service) {
            LOG.warn("NodeStore service removed - global mount may become invalid");
            context.ungetService(reference);
        }
    }
}

