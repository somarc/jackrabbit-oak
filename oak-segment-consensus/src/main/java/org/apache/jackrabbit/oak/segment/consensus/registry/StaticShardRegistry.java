/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jackrabbit.oak.segment.consensus.registry;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.web3j.crypto.Hash;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Static/in-memory implementation of ShardRegistry for testing and development.
 * <p>
 * This implementation allows manual registration of clusters without
 * connecting to the blockchain. Useful for:
 * <ul>
 *   <li>Unit tests</li>
 *   <li>Local development</li>
 *   <li>Integration tests without blockchain dependency</li>
 * </ul>
 */
public class StaticShardRegistry implements ShardRegistry {
    
    private static final Logger log = LoggerFactory.getLogger(StaticShardRegistry.class);
    
    private final List<ClusterRegistration> clusters = new ArrayList<>();
    private final Map<Integer, ClusterRegistration> shardToCluster = new ConcurrentHashMap<>();
    
    /**
     * Create an empty static registry.
     */
    public StaticShardRegistry() {
        log.info("✅ StaticShardRegistry initialized (empty)");
    }
    
    /**
     * Create a static registry with initial clusters.
     *
     * @param initialClusters Initial cluster registrations
     */
    public StaticShardRegistry(@NotNull List<ClusterRegistration> initialClusters) {
        for (ClusterRegistration cluster : initialClusters) {
            addCluster(cluster);
        }
        log.info("✅ StaticShardRegistry initialized with {} clusters", clusters.size());
    }
    
    /**
     * Add a cluster to the registry.
     *
     * @param cluster Cluster registration to add
     * @throws IllegalArgumentException if shard range conflicts with existing cluster
     */
    public synchronized void addCluster(@NotNull ClusterRegistration cluster) {
        // Check for conflicts
        for (int shard = cluster.getShardRangeStart(); shard <= cluster.getShardRangeEnd(); shard++) {
            ClusterRegistration existing = shardToCluster.get(shard);
            if (existing != null) {
                throw new IllegalArgumentException(String.format(
                    "Shard 0x%03X already claimed by cluster %s",
                    shard, existing.getClusterWallet()
                ));
            }
        }
        
        // Register
        clusters.add(cluster);
        for (int shard = cluster.getShardRangeStart(); shard <= cluster.getShardRangeEnd(); shard++) {
            shardToCluster.put(shard, cluster);
        }
        
        log.info("✅ Registered cluster {} for shards 0x{}-0x{}",
            cluster.getClusterWallet().substring(0, Math.min(10, cluster.getClusterWallet().length())),
            String.format("%03X", cluster.getShardRangeStart()),
            String.format("%03X", cluster.getShardRangeEnd())
        );
    }
    
    /**
     * Remove a cluster from the registry.
     *
     * @param clusterWallet Wallet address of cluster to remove
     */
    public synchronized void removeCluster(@NotNull String clusterWallet) {
        ClusterRegistration toRemove = null;
        for (ClusterRegistration cluster : clusters) {
            if (cluster.getClusterWallet().equalsIgnoreCase(clusterWallet)) {
                toRemove = cluster;
                break;
            }
        }
        
        if (toRemove != null) {
            clusters.remove(toRemove);
            for (int shard = toRemove.getShardRangeStart(); shard <= toRemove.getShardRangeEnd(); shard++) {
                shardToCluster.remove(shard);
            }
            log.info("✅ Removed cluster {}", clusterWallet);
        }
    }
    
    /**
     * Create a cluster registration helper.
     *
     * @param wallet Cluster wallet address
     * @param shardStart Start of shard range
     * @param shardEnd End of shard range
     * @param endpoint HTTP endpoint (may be null)
     * @return ClusterRegistration
     */
    public static ClusterRegistration createCluster(
            @NotNull String wallet,
            int shardStart,
            int shardEnd,
            @Nullable String endpoint) {
        return new ClusterRegistration(
            wallet,
            shardStart,
            shardEnd,
            BigInteger.valueOf(System.currentTimeMillis() / 1000),
            true,
            endpoint
        );
    }
    
    @Override
    @NotNull
    public List<ClusterRegistration> getAllClusters() {
        return Collections.unmodifiableList(new ArrayList<>(clusters));
    }
    
    @Override
    @NotNull
    public List<ClusterRegistration> getActiveClusters() {
        return clusters.stream()
            .filter(ClusterRegistration::isActive)
            .collect(Collectors.toList());
    }
    
    @Override
    @Nullable
    public ClusterRegistration getCluster(@NotNull String clusterWallet) {
        return clusters.stream()
            .filter(c -> c.getClusterWallet().equalsIgnoreCase(clusterWallet))
            .findFirst()
            .orElse(null);
    }
    
    @Override
    @Nullable
    public ClusterRegistration getClusterForShard(int shardId) {
        return shardToCluster.get(shardId);
    }
    
    @Override
    @Nullable
    public ClusterRegistration getClusterForWallet(@NotNull String contentOwnerWallet) {
        int shardId = computeShardId(contentOwnerWallet);
        return getClusterForShard(shardId);
    }
    
    @Override
    public int computeShardId(@NotNull String wallet) {
        // Same algorithm as Solidity: first 12 bits of keccak256(wallet)
        byte[] hash = Hash.sha3(Numeric.hexStringToByteArray(wallet));
        // Take first 2 bytes and mask to 12 bits
        int value = ((hash[0] & 0xFF) << 8) | (hash[1] & 0xFF);
        return value >> 4; // Shift right 4 bits to get 12 bits
    }
    
    @Override
    public boolean isRangeAvailable(int shardRangeStart, int shardRangeEnd) {
        for (int shard = shardRangeStart; shard <= shardRangeEnd; shard++) {
            if (shardToCluster.containsKey(shard)) {
                return false;
            }
        }
        return true;
    }
    
    @Override
    public int getClusterCount() {
        return clusters.size();
    }
    
    @Override
    public void refresh() {
        // No-op for static registry
        log.debug("StaticShardRegistry.refresh() called (no-op)");
    }
    
    /**
     * Clear all clusters (for testing).
     */
    public synchronized void clear() {
        clusters.clear();
        shardToCluster.clear();
        log.info("✅ StaticShardRegistry cleared");
    }
    
    /**
     * Builder for creating a StaticShardRegistry with fluent API.
     */
    public static class Builder {
        private final List<ClusterRegistration> clusters = new ArrayList<>();
        
        /**
         * Add a cluster.
         *
         * @param wallet Cluster wallet address
         * @param shardStart Start of shard range
         * @param shardEnd End of shard range
         * @param endpoint HTTP endpoint (may be null)
         * @return this builder
         */
        public Builder addCluster(String wallet, int shardStart, int shardEnd, String endpoint) {
            clusters.add(createCluster(wallet, shardStart, shardEnd, endpoint));
            return this;
        }
        
        /**
         * Add a cluster without endpoint.
         *
         * @param wallet Cluster wallet address
         * @param shardStart Start of shard range
         * @param shardEnd End of shard range
         * @return this builder
         */
        public Builder addCluster(String wallet, int shardStart, int shardEnd) {
            return addCluster(wallet, shardStart, shardEnd, null);
        }
        
        /**
         * Build the registry.
         *
         * @return StaticShardRegistry
         */
        public StaticShardRegistry build() {
            return new StaticShardRegistry(clusters);
        }
    }
    
    /**
     * Create a new builder.
     *
     * @return Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }
}
