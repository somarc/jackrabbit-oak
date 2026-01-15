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
package org.apache.jackrabbit.oak.segment.consensus.sharding;

import org.apache.jackrabbit.oak.segment.consensus.registry.ClusterRegistration;
import org.apache.jackrabbit.oak.segment.consensus.registry.ShardRegistry;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Checks if this cluster is authoritative for a given write operation.
 * <p>
 * In a sharded multi-cluster topology:
 * <ul>
 *   <li>Each cluster is authoritative (read-write) for a specific shard range</li>
 *   <li>Writes to other shards must be rejected with redirect information</li>
 *   <li>The shard is determined by the content owner's wallet address</li>
 * </ul>
 * <p>
 * This checker is called during write proposal validation to ensure:
 * <ol>
 *   <li>The content owner's wallet maps to a shard in our range</li>
 *   <li>If not, provide redirect info to the correct cluster</li>
 * </ol>
 */
public class ShardAuthorityChecker {
    
    private static final Logger LOG = LoggerFactory.getLogger(ShardAuthorityChecker.class);
    
    private final ShardRegistry registry;
    private final String localClusterWallet;
    private final int localShardStart;
    private final int localShardEnd;
    
    /**
     * Create a shard authority checker.
     *
     * @param registry Shard registry for cluster lookups
     * @param localClusterWallet This cluster's wallet address
     */
    public ShardAuthorityChecker(
            @NotNull ShardRegistry registry,
            @NotNull String localClusterWallet) {
        this.registry = registry;
        this.localClusterWallet = localClusterWallet;
        
        // Get our shard range
        ClusterRegistration localReg = registry.getCluster(localClusterWallet);
        if (localReg != null) {
            this.localShardStart = localReg.getShardRangeStart();
            this.localShardEnd = localReg.getShardRangeEnd();
            LOG.info("ShardAuthorityChecker initialized for cluster {} (shards 0x{}-0x{})",
                abbreviate(localClusterWallet),
                String.format("%03X", localShardStart),
                String.format("%03X", localShardEnd));
        } else {
            // Not registered - accept all writes (single cluster mode)
            this.localShardStart = 0;
            this.localShardEnd = ShardRegistry.MAX_SHARD_ID;
            LOG.warn("ShardAuthorityChecker: Local cluster not registered, accepting all shards");
        }
    }
    
    /**
     * Check if this cluster is authoritative for a content owner.
     *
     * @param contentOwnerWallet Wallet address of the content owner
     * @return AuthorityResult with status and redirect info if needed
     */
    @NotNull
    public AuthorityResult checkAuthority(@NotNull String contentOwnerWallet) {
        int shardId = registry.computeShardId(contentOwnerWallet);
        
        // Check if shard is in our range
        if (shardId >= localShardStart && shardId <= localShardEnd) {
            LOG.debug("✅ Authoritative for wallet {} (shard 0x{})",
                abbreviate(contentOwnerWallet), String.format("%03X", shardId));
            return AuthorityResult.authorized(shardId);
        }
        
        // Not our shard - find the authoritative cluster
        ClusterRegistration authoritative = registry.getClusterForShard(shardId);
        
        if (authoritative == null) {
            // Shard is unclaimed - this shouldn't happen in production
            LOG.warn("⚠️ Shard 0x{} is unclaimed (wallet: {})",
                String.format("%03X", shardId), abbreviate(contentOwnerWallet));
            return AuthorityResult.shardUnclaimed(shardId);
        }
        
        LOG.debug("🔀 Redirect wallet {} (shard 0x{}) to cluster {}",
            abbreviate(contentOwnerWallet),
            String.format("%03X", shardId),
            abbreviate(authoritative.getClusterWallet()));
        
        return AuthorityResult.redirect(shardId, authoritative);
    }
    
    /**
     * Check if this cluster is authoritative for a specific shard.
     *
     * @param shardId Shard ID to check
     * @return true if this cluster owns the shard
     */
    public boolean isAuthoritativeForShard(int shardId) {
        return shardId >= localShardStart && shardId <= localShardEnd;
    }
    
    /**
     * Get the local cluster's shard range.
     *
     * @return Array of [start, end]
     */
    public int[] getLocalShardRange() {
        return new int[] { localShardStart, localShardEnd };
    }
    
    /**
     * Get the local cluster wallet.
     *
     * @return Local cluster wallet address
     */
    @NotNull
    public String getLocalClusterWallet() {
        return localClusterWallet;
    }
    
    private static String abbreviate(String wallet) {
        if (wallet == null || wallet.length() < 12) {
            return wallet;
        }
        return wallet.substring(0, 10) + "...";
    }
    
    /**
     * Result of an authority check.
     */
    public static class AuthorityResult {
        
        /**
         * Status codes for authority check.
         */
        public enum Status {
            /** This cluster is authoritative - proceed with write */
            AUTHORIZED,
            /** Redirect to another cluster */
            REDIRECT,
            /** Shard is unclaimed - error condition */
            SHARD_UNCLAIMED
        }
        
        private final Status status;
        private final int shardId;
        private final ClusterRegistration redirectCluster;
        
        private AuthorityResult(Status status, int shardId, ClusterRegistration redirectCluster) {
            this.status = status;
            this.shardId = shardId;
            this.redirectCluster = redirectCluster;
        }
        
        /**
         * Create an authorized result.
         */
        public static AuthorityResult authorized(int shardId) {
            return new AuthorityResult(Status.AUTHORIZED, shardId, null);
        }
        
        /**
         * Create a redirect result.
         */
        public static AuthorityResult redirect(int shardId, ClusterRegistration redirectTo) {
            return new AuthorityResult(Status.REDIRECT, shardId, redirectTo);
        }
        
        /**
         * Create a shard unclaimed result.
         */
        public static AuthorityResult shardUnclaimed(int shardId) {
            return new AuthorityResult(Status.SHARD_UNCLAIMED, shardId, null);
        }
        
        /**
         * Get the status.
         */
        @NotNull
        public Status getStatus() {
            return status;
        }
        
        /**
         * Check if authorized.
         */
        public boolean isAuthorized() {
            return status == Status.AUTHORIZED;
        }
        
        /**
         * Check if redirect is needed.
         */
        public boolean isRedirect() {
            return status == Status.REDIRECT;
        }
        
        /**
         * Get the shard ID.
         */
        public int getShardId() {
            return shardId;
        }
        
        /**
         * Get the cluster to redirect to (if redirect).
         */
        @Nullable
        public ClusterRegistration getRedirectCluster() {
            return redirectCluster;
        }
        
        /**
         * Get redirect endpoint URL (if redirect).
         */
        @Nullable
        public String getRedirectEndpoint() {
            return redirectCluster != null ? redirectCluster.getEndpoint() : null;
        }
        
        @Override
        public String toString() {
            switch (status) {
                case AUTHORIZED:
                    return String.format("AuthorityResult{AUTHORIZED, shard=0x%03X}", shardId);
                case REDIRECT:
                    return String.format("AuthorityResult{REDIRECT, shard=0x%03X, to=%s}",
                        shardId, redirectCluster != null ? redirectCluster.getEndpoint() : "unknown");
                case SHARD_UNCLAIMED:
                    return String.format("AuthorityResult{SHARD_UNCLAIMED, shard=0x%03X}", shardId);
                default:
                    return "AuthorityResult{UNKNOWN}";
            }
        }
    }
}
