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
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Exception thrown when a write is directed to the wrong cluster.
 * <p>
 * Contains redirect information so clients can retry against the correct cluster.
 * <p>
 * HTTP response should be 307 Temporary Redirect with:
 * <ul>
 *   <li>Location header pointing to correct cluster endpoint</li>
 *   <li>X-Oak-Shard-Id header with the shard ID</li>
 *   <li>X-Oak-Redirect-Cluster header with cluster wallet</li>
 * </ul>
 */
public class ShardRoutingException extends RuntimeException {
    
    private final int shardId;
    private final ClusterRegistration redirectCluster;
    
    /**
     * Create a shard routing exception.
     *
     * @param message Error message
     * @param shardId The shard ID that was targeted
     * @param redirectCluster The cluster that should handle this shard
     */
    public ShardRoutingException(
            @NotNull String message,
            int shardId,
            @Nullable ClusterRegistration redirectCluster) {
        super(message);
        this.shardId = shardId;
        this.redirectCluster = redirectCluster;
    }
    
    /**
     * Create a shard routing exception for an unclaimed shard.
     *
     * @param message Error message
     * @param shardId The shard ID that was targeted
     */
    public ShardRoutingException(@NotNull String message, int shardId) {
        this(message, shardId, null);
    }
    
    /**
     * Get the shard ID.
     *
     * @return Shard ID (0x000-0xFFF)
     */
    public int getShardId() {
        return shardId;
    }
    
    /**
     * Get the cluster to redirect to.
     *
     * @return Cluster registration, or null if shard is unclaimed
     */
    @Nullable
    public ClusterRegistration getRedirectCluster() {
        return redirectCluster;
    }
    
    /**
     * Get the redirect endpoint URL.
     *
     * @return Endpoint URL, or null if unavailable
     */
    @Nullable
    public String getRedirectEndpoint() {
        return redirectCluster != null ? redirectCluster.getEndpoint() : null;
    }
    
    /**
     * Get the redirect cluster wallet.
     *
     * @return Cluster wallet address, or null if unavailable
     */
    @Nullable
    public String getRedirectClusterWallet() {
        return redirectCluster != null ? redirectCluster.getClusterWallet() : null;
    }
    
    /**
     * Check if redirect information is available.
     *
     * @return true if redirect cluster is known
     */
    public boolean hasRedirectInfo() {
        return redirectCluster != null && redirectCluster.getEndpoint() != null;
    }
    
    /**
     * Get HTTP status code for this error.
     *
     * @return 307 if redirect available, 503 if shard unclaimed
     */
    public int getHttpStatusCode() {
        return hasRedirectInfo() ? 307 : 503;
    }
    
    @Override
    public String toString() {
        if (hasRedirectInfo()) {
            return String.format(
                "ShardRoutingException{shard=0x%03X, redirect=%s}",
                shardId, redirectCluster.getEndpoint()
            );
        } else {
            return String.format(
                "ShardRoutingException{shard=0x%03X, unclaimed}",
                shardId
            );
        }
    }
}
