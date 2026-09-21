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

import java.util.List;
import java.util.stream.Collectors;

/**
 * Interface for accessing the shard registry.
 * <p>
 * The shard registry maintains the authoritative mapping of:
 * <ul>
 *   <li>Cluster wallet addresses to shard ranges</li>
 *   <li>Shard IDs to authoritative clusters</li>
 *   <li>Content owner wallets to authoritative clusters (via shard computation)</li>
 * </ul>
 * <p>
 * Implementations may read from:
 * <ul>
 *   <li>On-chain ShardRegistry contract (production)</li>
 *   <li>Static configuration (development/testing)</li>
 *   <li>In-memory mock (unit tests)</li>
 * </ul>
 */
public interface ShardRegistry {
    
    /**
     * Maximum shard ID (12-bit = 4096 shards).
     */
    int MAX_SHARD_ID = 0xFFF;
    
    /**
     * Maximum number of clusters in the network.
     */
    int MAX_CLUSTERS = 100;
    
    /**
     * Get all registered clusters.
     *
     * @return List of all cluster registrations
     */
    @NotNull
    List<ClusterRegistration> getAllClusters();
    
    /**
     * Get all active clusters (excludes deactivated clusters).
     *
     * @return List of active cluster registrations
     */
    @NotNull
    List<ClusterRegistration> getActiveClusters();
    
    /**
     * Get the cluster registration for a specific wallet address.
     *
     * @param clusterWallet Ethereum wallet address
     * @return Cluster registration, or null if not registered
     */
    @Nullable
    ClusterRegistration getCluster(@NotNull String clusterWallet);
    
    /**
     * Get the authoritative cluster for a specific shard ID.
     *
     * @param shardId Shard ID (0x000-0xFFF)
     * @return Cluster registration, or null if shard is unclaimed
     */
    @Nullable
    ClusterRegistration getClusterForShard(int shardId);
    
    /**
     * Get the authoritative cluster for a content owner wallet.
     * <p>
     * This computes the shard ID from the wallet hash and looks up the cluster.
     *
     * @param contentOwnerWallet Ethereum wallet address of content owner
     * @return Cluster registration, or null if shard is unclaimed
     */
    @Nullable
    ClusterRegistration getClusterForWallet(@NotNull String contentOwnerWallet);
    
    /**
     * Compute the shard ID for a wallet address.
     * <p>
     * Uses first 12 bits of keccak256 hash.
     *
     * @param wallet Ethereum wallet address
     * @return Shard ID (0x000-0xFFF)
     */
    int computeShardId(@NotNull String wallet);
    
    /**
     * Check if a shard range is available for registration.
     *
     * @param shardRangeStart Start of range (inclusive)
     * @param shardRangeEnd End of range (inclusive)
     * @return true if entire range is unclaimed
     */
    boolean isRangeAvailable(int shardRangeStart, int shardRangeEnd);
    
    /**
     * Get the number of registered clusters.
     *
     * @return Cluster count
     */
    int getClusterCount();
    
    /**
     * Refresh the registry from the source (e.g., re-read from blockchain).
     * <p>
     * Implementations may cache data; this forces a refresh.
     */
    void refresh();
    
    /**
     * Get remote clusters (all clusters except the local one).
     *
     * @param localWallet The local cluster's wallet address
     * @return List of remote cluster registrations
     */
    @NotNull
    default List<ClusterRegistration> getRemoteClusters(@NotNull String localWallet) {
        return getActiveClusters().stream()
            .filter(c -> !c.getClusterWallet().equalsIgnoreCase(localWallet))
            .collect(Collectors.toList());
    }
}
