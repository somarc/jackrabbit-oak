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

import java.util.List;

/**
 * Information about a shard, including its ID and peer validator URLs.
 */
public class ShardInfo {
    
    private final int shardId;
    private final List<String> peerUrls;
    
    public ShardInfo(int shardId, List<String> peerUrls) {
        if (shardId < 0) {
            throw new IllegalArgumentException("Shard ID must be non-negative: " + shardId);
        }
        if (peerUrls == null || peerUrls.isEmpty()) {
            throw new IllegalArgumentException("Peer URLs cannot be null or empty");
        }
        this.shardId = shardId;
        this.peerUrls = List.copyOf(peerUrls); // Immutable copy
    }
    
    /**
     * Get the shard ID.
     * 
     * @return Shard ID (0-based index)
     */
    public int getShardId() {
        return shardId;
    }
    
    /**
     * Get the list of peer validator URLs for this shard.
     * 
     * @return Immutable list of validator URLs
     */
    public List<String> getPeerUrls() {
        return peerUrls;
    }
    
    @Override
    public String toString() {
        return "ShardInfo{shardId=" + shardId + ", peerUrls=" + peerUrls + "}";
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ShardInfo shardInfo = (ShardInfo) o;
        return shardId == shardInfo.shardId && peerUrls.equals(shardInfo.peerUrls);
    }
    
    @Override
    public int hashCode() {
        return shardId * 31 + peerUrls.hashCode();
    }
}

