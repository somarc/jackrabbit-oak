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

import java.math.BigInteger;
import java.util.Objects;

/**
 * Represents a cluster registration from the on-chain ShardRegistry contract.
 * <p>
 * Each cluster registration contains:
 * <ul>
 *   <li>Cluster wallet address (payment destination)</li>
 *   <li>Shard range (start and end, inclusive)</li>
 *   <li>Registration block number</li>
 *   <li>Active status</li>
 *   <li>HTTP endpoint for segment transfer (optional)</li>
 * </ul>
 */
public class ClusterRegistration {
    
    private final String clusterWallet;
    private final int shardRangeStart;
    private final int shardRangeEnd;
    private final BigInteger registeredAt;
    private final boolean active;
    private final String endpoint;
    
    /**
     * Create a new cluster registration.
     *
     * @param clusterWallet Ethereum wallet address of the cluster
     * @param shardRangeStart Start of shard range (inclusive, 0x000-0xFFF)
     * @param shardRangeEnd End of shard range (inclusive, 0x000-0xFFF)
     * @param registeredAt Block number when registered
     * @param active Whether the cluster is currently active
     * @param endpoint HTTP endpoint for segment transfer (may be null)
     */
    public ClusterRegistration(
            @NotNull String clusterWallet,
            int shardRangeStart,
            int shardRangeEnd,
            @NotNull BigInteger registeredAt,
            boolean active,
            @Nullable String endpoint) {
        this.clusterWallet = Objects.requireNonNull(clusterWallet, "clusterWallet");
        this.shardRangeStart = shardRangeStart;
        this.shardRangeEnd = shardRangeEnd;
        this.registeredAt = Objects.requireNonNull(registeredAt, "registeredAt");
        this.active = active;
        this.endpoint = endpoint;
    }
    
    /**
     * Get the cluster wallet address.
     *
     * @return Ethereum wallet address (0x...)
     */
    @NotNull
    public String getClusterWallet() {
        return clusterWallet;
    }
    
    /**
     * Get the start of the shard range.
     *
     * @return Shard range start (0x000-0xFFF)
     */
    public int getShardRangeStart() {
        return shardRangeStart;
    }
    
    /**
     * Get the end of the shard range.
     *
     * @return Shard range end (0x000-0xFFF)
     */
    public int getShardRangeEnd() {
        return shardRangeEnd;
    }
    
    /**
     * Get the number of shards in this cluster's range.
     *
     * @return Number of shards (shardRangeEnd - shardRangeStart + 1)
     */
    public int getShardCount() {
        return shardRangeEnd - shardRangeStart + 1;
    }
    
    /**
     * Get the block number when this cluster was registered.
     *
     * @return Registration block number
     */
    @NotNull
    public BigInteger getRegisteredAt() {
        return registeredAt;
    }
    
    /**
     * Check if this cluster is currently active.
     *
     * @return true if active, false if deactivated
     */
    public boolean isActive() {
        return active;
    }
    
    /**
     * Get the HTTP endpoint for segment transfer.
     *
     * @return Endpoint URL, or null if not set
     */
    @Nullable
    public String getEndpoint() {
        return endpoint;
    }
    
    /**
     * Check if a shard ID is within this cluster's range.
     *
     * @param shardId Shard ID to check (0x000-0xFFF)
     * @return true if shard is in range
     */
    public boolean containsShard(int shardId) {
        return shardId >= shardRangeStart && shardId <= shardRangeEnd;
    }
    
    /**
     * Get a mount name for this cluster (used in CompositeNodeStore).
     *
     * @return Mount name (e.g., "cluster-0x1234abcd")
     */
    @NotNull
    public String getMountName() {
        // Use first 8 chars of wallet address for readability
        String shortWallet = clusterWallet.length() > 10 
            ? clusterWallet.substring(0, 10) 
            : clusterWallet;
        return "cluster-" + shortWallet.toLowerCase();
    }
    
    /**
     * Get the mount path for this cluster's shard range.
     *
     * @return Mount path (e.g., "/oak-chain/shard-0x000")
     */
    @NotNull
    public String getMountPath() {
        return String.format("/oak-chain/shard-%03x", shardRangeStart);
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ClusterRegistration that = (ClusterRegistration) o;
        return clusterWallet.equalsIgnoreCase(that.clusterWallet);
    }
    
    @Override
    public int hashCode() {
        return clusterWallet.toLowerCase().hashCode();
    }
    
    @Override
    public String toString() {
        return String.format(
            "ClusterRegistration{wallet=%s, shards=0x%03X-0x%03X, active=%s, endpoint=%s}",
            clusterWallet.substring(0, Math.min(10, clusterWallet.length())) + "...",
            shardRangeStart,
            shardRangeEnd,
            active,
            endpoint != null ? endpoint : "none"
        );
    }
}
