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
import org.apache.jackrabbit.oak.segment.consensus.registry.ClusterRegistration;
import org.apache.jackrabbit.oak.segment.consensus.registry.ShardRegistry;
import org.apache.jackrabbit.oak.spi.mount.MountInfoProvider;
import org.apache.jackrabbit.oak.spi.mount.Mounts;
import org.apache.jackrabbit.oak.spi.state.NodeStore;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceRegistration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;

/**
 * Multi-cluster mounter that creates composite mounts for all registered clusters.
 * <p>
 * This class:
 * <ul>
 *   <li>Reads cluster registrations from the ShardRegistry</li>
 *   <li>Creates HTTP-backed NodeStores for each remote cluster</li>
 *   <li>Builds a CompositeNodeStore with all mounts</li>
 *   <li>Registers the composite as the primary NodeStore</li>
 * </ul>
 * <p>
 * Mount structure:
 * <pre>
 * /                          - Local cluster (read-write)
 * /oak-chain/shard-0x000     - Remote cluster A (read-only via HTTP)
 * /oak-chain/shard-0x100     - Remote cluster B (read-only via HTTP)
 * /oak-chain/shard-0x200     - Remote cluster C (read-only via HTTP)
 * ...
 * </pre>
 */
public class MultiClusterMounter {

    private static final Logger LOG = LoggerFactory.getLogger(MultiClusterMounter.class);
    
    private final BundleContext bundleContext;
    private final NodeStore localNodeStore;
    private final ShardRegistry shardRegistry;
    private final String localClusterWallet;
    private final HttpNodeStoreFactory nodeStoreFactory;
    
    private ServiceRegistration<NodeStore> compositeRegistration;
    private final Map<String, Closeable> remoteMounts = new HashMap<>();
    
    /**
     * Factory interface for creating HTTP-backed NodeStores.
     */
    @FunctionalInterface
    public interface HttpNodeStoreFactory {
        /**
         * Create an HTTP-backed NodeStore for a remote cluster.
         *
         * @param endpoint HTTP endpoint URL
         * @param mountName Mount name for logging
         * @return NodeStore instance (may be lazy-initialized)
         */
        NodeStore create(String endpoint, String mountName);
    }
    
    /**
     * Create a multi-cluster mounter.
     *
     * @param bundleContext OSGi bundle context
     * @param localNodeStore Local cluster's NodeStore (read-write)
     * @param shardRegistry Registry for discovering clusters
     * @param localClusterWallet This cluster's wallet address
     * @param nodeStoreFactory Factory for creating remote NodeStores
     */
    public MultiClusterMounter(
            BundleContext bundleContext,
            NodeStore localNodeStore,
            ShardRegistry shardRegistry,
            String localClusterWallet,
            HttpNodeStoreFactory nodeStoreFactory) {
        this.bundleContext = bundleContext;
        this.localNodeStore = localNodeStore;
        this.shardRegistry = shardRegistry;
        this.localClusterWallet = localClusterWallet;
        this.nodeStoreFactory = nodeStoreFactory;
        
        LOG.info("===========================================");
        LOG.info("  Blockchain AEM - Multi-Cluster Mounter");
        LOG.info("  Local wallet: {}", abbreviate(localClusterWallet));
        LOG.info("===========================================");
    }
    
    /**
     * Mount all remote clusters from the registry.
     */
    public synchronized void mount() {
        if (compositeRegistration != null) {
            LOG.warn("Already mounted - skipping");
            return;
        }
        
        LOG.info("🔄 Discovering clusters from registry...");
        
        try {
            // Get remote clusters (excludes local)
            List<ClusterRegistration> remoteClusters = shardRegistry.getRemoteClusters(localClusterWallet);
            
            if (remoteClusters.isEmpty()) {
                LOG.info("ℹ️  No remote clusters found - running as single cluster");
                LOG.info("   Local cluster handles all shards");
                return;
            }
            
            LOG.info("✅ Found {} remote clusters to mount", remoteClusters.size());
            
            // Build mount configuration
            Mounts.Builder mountBuilder = Mounts.newBuilder();
            List<MountEntry> mountEntries = new ArrayList<>();
            
            for (ClusterRegistration cluster : remoteClusters) {
                String mountName = cluster.getMountName();
                String mountPath = cluster.getMountPath();
                
                LOG.info("  📁 {} -> {} (shards 0x{}-0x{})",
                    mountName,
                    mountPath,
                    String.format("%03X", cluster.getShardRangeStart()),
                    String.format("%03X", cluster.getShardRangeEnd())
                );
                
                mountBuilder.readOnlyMount(mountName, mountPath);
                mountEntries.add(new MountEntry(cluster, mountName, mountPath));
            }
            
            MountInfoProvider mountInfo = mountBuilder.build();
            
            // Create composite builder
            CompositeNodeStore.Builder compositeBuilder = new CompositeNodeStore.Builder(
                mountInfo,
                localNodeStore
            );
            
            // Create NodeStore for each remote cluster
            LOG.info("");
            LOG.info("🔗 Creating HTTP-backed NodeStores for remote clusters...");
            
            for (MountEntry entry : mountEntries) {
                ClusterRegistration cluster = entry.cluster;
                String endpoint = cluster.getEndpoint();
                
                if (endpoint == null || endpoint.isEmpty()) {
                    LOG.warn("  ⚠️  {} has no endpoint - skipping", entry.mountName);
                    continue;
                }
                
                LOG.info("  🌐 {} -> {}", entry.mountName, endpoint);
                
                try {
                    NodeStore remoteStore = nodeStoreFactory.create(endpoint, entry.mountName);
                    compositeBuilder.addMount(entry.mountName, remoteStore);
                    
                    // Track for cleanup
                    if (remoteStore instanceof Closeable) {
                        remoteMounts.put(entry.mountName, (Closeable) remoteStore);
                    }
                    
                    LOG.info("     ✅ Connected");
                } catch (Exception e) {
                    LOG.warn("     ❌ Failed to connect: {}", e.getMessage());
                    // Continue with other mounts - graceful degradation
                }
            }
            
            // Build composite
            LOG.info("");
            LOG.info("🏗️  Building Composite NodeStore...");
            
            CompositeNodeStore composite = compositeBuilder.build();
            
            // Register with highest priority
            Dictionary<String, Object> props = new Hashtable<>();
            props.put(Constants.SERVICE_RANKING, Integer.MAX_VALUE);
            props.put("oak.nodestore.description", "Blockchain AEM Multi-Cluster Composite");
            props.put("oak.cluster.wallet", localClusterWallet);
            props.put("oak.cluster.remote.count", remoteClusters.size());
            
            compositeRegistration = bundleContext.registerService(
                NodeStore.class,
                composite,
                props
            );
            
            LOG.info("");
            LOG.info("===========================================");
            LOG.info("  🎉 Multi-Cluster Mount Complete!");
            LOG.info("===========================================");
            LOG.info("  Local cluster: {} (read-write)", abbreviate(localClusterWallet));
            LOG.info("  Remote clusters: {} (read-only)", remoteMounts.size());
            LOG.info("");
            LOG.info("  Content paths:");
            LOG.info("    /                    - Local content");
            for (MountEntry entry : mountEntries) {
                LOG.info("    {}  - {}", entry.mountPath, abbreviate(entry.cluster.getClusterWallet()));
            }
            LOG.info("===========================================");
            
        } catch (Exception e) {
            LOG.error("❌ Failed to mount clusters", e);
            cleanup();
        }
    }
    
    /**
     * Refresh mounts from registry (add new clusters, remove deregistered ones).
     */
    public synchronized void refresh() {
        LOG.info("🔄 Refreshing cluster mounts...");
        
        // For now, do a full remount
        // Future: incremental update
        cleanup();
        mount();
    }
    
    /**
     * Shutdown and cleanup all mounts.
     */
    public synchronized void shutdown() {
        LOG.info("Shutting down Multi-Cluster Mounter...");
        cleanup();
        LOG.info("All remote mounts closed.");
    }
    
    private void cleanup() {
        // Unregister composite
        if (compositeRegistration != null) {
            try {
                compositeRegistration.unregister();
                LOG.info("Composite NodeStore unregistered");
            } catch (Exception e) {
                LOG.warn("Error unregistering composite: {}", e.getMessage());
            }
            compositeRegistration = null;
        }
        
        // Close remote stores
        for (Map.Entry<String, Closeable> entry : remoteMounts.entrySet()) {
            try {
                entry.getValue().close();
                LOG.info("Closed remote mount: {}", entry.getKey());
            } catch (Exception e) {
                LOG.warn("Error closing {}: {}", entry.getKey(), e.getMessage());
            }
        }
        remoteMounts.clear();
    }
    
    /**
     * Check if mounts are active.
     *
     * @return true if composite is registered
     */
    public boolean isMounted() {
        return compositeRegistration != null;
    }
    
    /**
     * Get count of active remote mounts.
     *
     * @return Number of remote mounts
     */
    public int getRemoteMountCount() {
        return remoteMounts.size();
    }
    
    private static String abbreviate(String wallet) {
        if (wallet == null || wallet.length() < 12) {
            return wallet;
        }
        return wallet.substring(0, 10) + "...";
    }
    
    /**
     * Internal class to track mount entries during setup.
     */
    private static class MountEntry {
        final ClusterRegistration cluster;
        final String mountName;
        final String mountPath;
        
        MountEntry(ClusterRegistration cluster, String mountName, String mountPath) {
            this.cluster = cluster;
            this.mountName = mountName;
            this.mountPath = mountPath;
        }
    }
}
