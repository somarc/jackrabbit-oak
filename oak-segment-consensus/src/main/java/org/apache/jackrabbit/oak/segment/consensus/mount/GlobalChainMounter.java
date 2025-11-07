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
package org.apache.jackrabbit.oak.segment.consensus.mount;

import org.apache.jackrabbit.oak.composite.CompositeNodeStore;
import org.apache.jackrabbit.oak.segment.SegmentNodeStoreBuilders;
import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.apache.jackrabbit.oak.segment.file.FileStoreBuilder;
import org.apache.jackrabbit.oak.segment.file.InvalidFileStoreVersionException;
import org.apache.jackrabbit.oak.spi.mount.MountInfoProvider;
import org.apache.jackrabbit.oak.spi.mount.Mounts;
import org.apache.jackrabbit.oak.spi.state.NodeStore;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceRegistration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.Dictionary;
import java.util.Hashtable;

/**
 * Mounts the global blockchain store at /oak-chain by creating a
 * CompositeNodeStore at runtime. This wraps the existing NodeStore
 * without requiring configuration changes.
 * 
 * <p>This is the POC implementation using filesystem-based mounting (Option B).
 * Future versions will support network-based protocols (Option A).</p>
 * 
 * <p>Configure the global store path with system property:
 * {@code -Doak.global.store.path=/path/to/global/store}</p>
 * 
 * <p><strong>NOTE:</strong> This class is manually instantiated by
 * {@link org.apache.jackrabbit.oak.segment.consensus.osgi.ConsensusActivator}
 * because we're in a fragment bundle where Declarative Services don't work.</p>
 */
public class GlobalChainMounter {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalChainMounter.class);
    
    private static final String GLOBAL_STORE_PATH_PROPERTY = "oak.global.store.path";
    private static final String DEFAULT_GLOBAL_STORE_PATH = "/mnt/oak-chain/segmentstore";
    
    private final BundleContext bundleContext;
    private final NodeStore existingNodeStore;
    
    private FileStore globalFileStore;
    private ServiceRegistration<NodeStore> compositeRegistration;
    
    /**
     * Constructor for manual instantiation by BundleActivator.
     * 
     * @param context OSGi bundle context for service registration
     * @param existingNodeStore The backing NodeStore to wrap
     */
    public GlobalChainMounter(BundleContext context, NodeStore existingNodeStore) {
        this.bundleContext = context;
        this.existingNodeStore = existingNodeStore;
        
        LOG.info("===========================================");
        LOG.info("  Blockchain AEM - Global Chain Mounter");
        LOG.info("  (Manually instantiated)");
        LOG.info("===========================================");
    }
    
    /**
     * Attempts to mount the global store.
     * Called by BundleActivator after instantiation.
     */
    public synchronized void mount() {
        // Prevent double-mount
        if (compositeRegistration != null) {
            LOG.warn("Global store already mounted - skipping");
            return;
        }
        
        LOG.info("Attempting to mount global blockchain store...");
        
        try {
            String globalStorePath = System.getProperty(
                GLOBAL_STORE_PATH_PROPERTY,
                DEFAULT_GLOBAL_STORE_PATH
            );
            
            LOG.info("Global store path: {}", globalStorePath);
            LOG.info("  (Configure with -D{}=<path>)", GLOBAL_STORE_PATH_PROPERTY);
            LOG.info("");
            
            // 1. Validate global store exists
            File globalStoreDir = new File(globalStorePath);
            if (!globalStoreDir.exists()) {
                LOG.warn("❌ Global store directory does not exist: {}", globalStorePath);
                LOG.warn("   Skipping mount. The bundle will remain inactive.");
                LOG.warn("   To enable: ensure GlobalStoreServer is running and path is correct.");
                return;
            }
            
            if (!globalStoreDir.isDirectory()) {
                LOG.error("❌ Path exists but is not a directory: {}", globalStorePath);
                return;
            }
            
            File journalFile = new File(globalStoreDir, "journal.log");
            if (!journalFile.exists()) {
                LOG.warn("❌ Global store exists but appears uninitialized (no journal.log)");
                LOG.warn("   Ensure GlobalStoreServer has been started at least once.");
                return;
            }
            
            LOG.info("✅ Global store validated at: {}", globalStorePath);
            
            // 2. Open global store as read-only
            LOG.info("");
            LOG.info("Opening global store as READ-ONLY FileStore...");
            
            try {
                // Open FileStore - read-only enforcement is via OS-level volume mount
                globalFileStore = FileStoreBuilder
                    .fileStoreBuilder(globalStoreDir)
                    .withMemoryMapping(false)  // Safer for Docker/shared volumes
                    .build();
                
                LOG.info("✅ FileStore opened successfully");
                LOG.info("   - Store version: {}", globalFileStore.getHead().getRecordId());
                
            } catch (InvalidFileStoreVersionException e) {
                LOG.error("❌ Invalid FileStore version: {}", e.getMessage());
                LOG.error("   The global store may be from an incompatible Oak version.");
                return;
            }
            
            // 3. Create NodeStore wrapper
            NodeStore globalNodeStore = SegmentNodeStoreBuilders
                .builder(globalFileStore)
                .build();
            
            LOG.info("✅ Global NodeStore created");
            
            // 4. Create mount configuration
            LOG.info("");
            LOG.info("Configuring mount points...");
            
            MountInfoProvider mountInfo = Mounts.newBuilder()
                .readOnlyMount("oak-chain-global", "/oak-chain")
                .build();
            
            LOG.info("  - Mount name: oak-chain-global");
            LOG.info("  - Mount path: /oak-chain");
            LOG.info("  - Access mode: READ-ONLY");
            
            // 5. Create Composite NodeStore
            LOG.info("");
            LOG.info("Building Composite NodeStore...");
            LOG.info("  - Default mount: Existing AEM repository (read-write)");
            LOG.info("  - /oak-chain mount: Global blockchain store (read-only)");
            
            CompositeNodeStore composite = new CompositeNodeStore.Builder(
                mountInfo,
                existingNodeStore  // Global (default) mount
            )
            .addMount("oak-chain-global", globalNodeStore)
            .build();
            
            LOG.info("✅ Composite NodeStore created");
            
            // 6. Register with highest priority
            LOG.info("");
            LOG.info("Registering Composite NodeStore with OSGi...");
            
            Dictionary<String, Object> props = new Hashtable<>();
            props.put(Constants.SERVICE_RANKING, Integer.MAX_VALUE);
            props.put("oak.nodestore.description", "Blockchain AEM Composite NodeStore");
            
            compositeRegistration = bundleContext.registerService(
                NodeStore.class,
                composite,
                props
            );
            
            LOG.info("✅ Service registered with ranking: {}", Integer.MAX_VALUE);
            LOG.info("");
            LOG.info("===========================================");
            LOG.info("  🎉 SUCCESS! Global chain is mounted!");
            LOG.info("===========================================");
            LOG.info("");
            LOG.info("The /oak-chain path is now accessible in JCR:");
            LOG.info("  - Browse to /oak-chain in CRX/DE");
            LOG.info("  - Query with JCR-SQL2: SELECT * FROM [nt:base] WHERE ISDESCENDANTNODE('/oak-chain')");
            LOG.info("  - Any content under /oak-chain/content/<wallet-uuid> will be visible");
            LOG.info("");
            LOG.info("This instance can READ from the global blockchain store.");
            LOG.info("Writes to /oak-chain are managed by consensus (future phase).");
            LOG.info("===========================================");
            
        } catch (Exception e) {
            LOG.error("❌ Failed to mount global chain", e);
            if (e instanceof IOException) {
                LOG.error("   Check file permissions and disk space.");
            }
            cleanup();
        }
    }
    
    /**
     * Shuts down the mounter and cleans up resources.
     * Called by BundleActivator on bundle stop.
     */
    public void shutdown() {
        LOG.info("Shutting down Global Chain Mounter...");
        cleanup();
        LOG.info("Global chain unmounted.");
    }
    
    private void cleanup() {
        if (compositeRegistration != null) {
            try {
                compositeRegistration.unregister();
                LOG.info("Composite NodeStore service unregistered");
            } catch (Exception e) {
                LOG.warn("Error unregistering Composite NodeStore", e);
            }
            compositeRegistration = null;
        }
        
        if (globalFileStore != null) {
            try {
                globalFileStore.close();
                LOG.info("Global FileStore closed");
            } catch (Exception e) {
                LOG.warn("Error closing global FileStore", e);
            }
            globalFileStore = null;
        }
    }
}

