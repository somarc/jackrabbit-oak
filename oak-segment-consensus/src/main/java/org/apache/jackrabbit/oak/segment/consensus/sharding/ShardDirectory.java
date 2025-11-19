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
package org.apache.jackrabbit.oak.segment.consensus.sharding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Directory mapping shard IDs to shard information (validator URLs).
 * 
 * <p>Phase 1: In-memory map (hardcoded or configured).
 * Future: Will read from meta-cluster or Ethereum Beacon Chain state.
 */
public class ShardDirectory {
    
    private static final Logger log = LoggerFactory.getLogger(ShardDirectory.class);
    
    private final Map<Integer, ShardInfo> shards;
    
    /**
     * Create shard directory with initial shard configuration.
     * 
     * @param initialShards Initial shard configuration (shardId → peerUrls)
     */
    public ShardDirectory(Map<Integer, List<String>> initialShards) {
        this.shards = new HashMap<>();
        
        if (initialShards != null && !initialShards.isEmpty()) {
            for (Map.Entry<Integer, List<String>> entry : initialShards.entrySet()) {
                int shardId = entry.getKey();
                List<String> peerUrls = entry.getValue();
                shards.put(shardId, new ShardInfo(shardId, peerUrls));
            }
            log.info("✅ ShardDirectory initialized with {} shards", shards.size());
        } else {
            log.warn("⚠️  ShardDirectory initialized with no shards (empty configuration)");
        }
    }
    
    /**
     * Create shard directory for single-shard mode (all requests route to same cluster).
     * 
     * @param peerUrls List of validator URLs for the single shard
     */
    public ShardDirectory(List<String> peerUrls) {
        this.shards = new HashMap<>();
        
        if (peerUrls != null && !peerUrls.isEmpty()) {
            // Single shard (shard 0) with all peers
            shards.put(0, new ShardInfo(0, peerUrls));
            log.info("✅ ShardDirectory initialized in single-shard mode with {} peers", peerUrls.size());
        } else {
            log.warn("⚠️  ShardDirectory initialized with no peers (empty configuration)");
        }
    }
    
    /**
     * Get shard information for a given shard ID.
     * 
     * @param shardId Shard ID (0-based index)
     * @return ShardInfo, or null if shard not found
     */
    public ShardInfo getShard(int shardId) {
        ShardInfo shard = shards.get(shardId);
        if (shard == null) {
            log.warn("⚠️  Shard {} not found in directory", shardId);
        }
        return shard;
    }
    
    /**
     * Get all configured shards.
     * 
     * @return List of all shard IDs
     */
    public List<Integer> getAllShardIds() {
        return new ArrayList<>(shards.keySet());
    }
    
    /**
     * Get number of configured shards.
     * 
     * @return Number of shards
     */
    public int getNumShards() {
        return shards.size();
    }
    
    /**
     * Update shard information (for dynamic shard membership changes).
     * 
     * @param shardId Shard ID
     * @param peerUrls New peer URLs for the shard
     */
    public void updateShard(int shardId, List<String> peerUrls) {
        if (peerUrls == null || peerUrls.isEmpty()) {
            log.warn("⚠️  Attempted to update shard {} with empty peer URLs", shardId);
            return;
        }
        
        ShardInfo oldShard = shards.get(shardId);
        ShardInfo newShard = new ShardInfo(shardId, peerUrls);
        shards.put(shardId, newShard);
        
        log.info("✅ Updated shard {}: {} → {} peers", shardId, 
            oldShard != null ? oldShard.getPeerUrls().size() : 0, 
            peerUrls.size());
    }
    
    /**
     * Add a new shard (for dynamic shard scaling).
     * 
     * @param shardId Shard ID
     * @param peerUrls Peer URLs for the new shard
     */
    public void addShard(int shardId, List<String> peerUrls) {
        if (shards.containsKey(shardId)) {
            log.warn("⚠️  Shard {} already exists, updating instead", shardId);
            updateShard(shardId, peerUrls);
            return;
        }
        
        ShardInfo shard = new ShardInfo(shardId, peerUrls);
        shards.put(shardId, shard);
        log.info("✅ Added shard {} with {} peers", shardId, peerUrls.size());
    }
    
    /**
     * Remove a shard (for shard decommissioning).
     * 
     * @param shardId Shard ID to remove
     */
    public void removeShard(int shardId) {
        ShardInfo removed = shards.remove(shardId);
        if (removed != null) {
            log.info("✅ Removed shard {}", shardId);
        } else {
            log.warn("⚠️  Attempted to remove non-existent shard {}", shardId);
        }
    }
    
    @Override
    public String toString() {
        return "ShardDirectory{shards=" + shards.size() + "}";
    }
}

