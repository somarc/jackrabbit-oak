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

import org.apache.jackrabbit.oak.segment.http.server.util.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Stateless shard router that routes requests to the correct shard's current leader.
 * 
 * <p>Key features:
 * <ul>
 *   <li>Real-time leader discovery via /v1/aeron/cluster-state endpoint</li>
 *   <li>5-second TTL cache per shard (reduces leader discovery overhead)</li>
 *   <li>Cache invalidation on HTTP 307 redirects (Aeron follower → leader)</li>
 *   <li>Handles Raft leader changes gracefully (leader can change every few seconds)</li>
 * </ul>
 * 
 * <p>In a 3-node Aeron Cluster, the leader can change every few seconds (normal Raft behavior).
 * Cached leader URLs go stale instantly, so we use real-time discovery with caching optimization.
 */
public class ShardRouter {
    
    private static final Logger log = LoggerFactory.getLogger(ShardRouter.class);
    
    private static final long LEADER_CACHE_TTL_MS = 5000; // 5 seconds
    private static final int CONNECT_TIMEOUT_MS = 2000;
    private static final int READ_TIMEOUT_MS = 3000;
    
    private final ShardDirectory shardDirectory;
    private final ShardingStrategy strategy;
    
    /**
     * Cache of current leader per shard (with TTL).
     * Key: shardId, Value: CachedLeader
     */
    private final Map<Integer, CachedLeader> leaderCache = new ConcurrentHashMap<>();
    
    /**
     * Create shard router.
     * 
     * @param shardDirectory Shard directory (maps shardId → peerUrls)
     * @param strategy Sharding strategy (computes shardId from wallet)
     */
    public ShardRouter(ShardDirectory shardDirectory, ShardingStrategy strategy) {
        if (shardDirectory == null) {
            throw new IllegalArgumentException("ShardDirectory cannot be null");
        }
        if (strategy == null) {
            throw new IllegalArgumentException("ShardingStrategy cannot be null");
        }
        this.shardDirectory = shardDirectory;
        this.strategy = strategy;
        log.info("✅ ShardRouter initialized with {} shards", shardDirectory.getNumShards());
    }
    
    /**
     * Route request to the correct shard's current leader.
     * 
     * <p>Flow:
     * 1. Extract wallet address from request
     * 2. Compute shard ID using strategy
     * 3. Look up shard info from directory
     * 4. Discover current leader (with caching)
     * 5. Return leader URL
     * 
     * @param walletAddress Ethereum wallet address (0x... format)
     * @return Leader URL for the shard handling this wallet, or null if routing fails
     */
    public String routeRequest(String walletAddress) {
        if (walletAddress == null || walletAddress.isEmpty()) {
            log.warn("⚠️  Cannot route request: wallet address is null or empty");
            return null;
        }
        
        try {
            // Compute shard ID
            int shardId = strategy.getShardId(walletAddress);
            log.debug("Wallet {} → Shard {}", walletAddress, shardId);
            
            // Get shard info from directory
            ShardInfo shard = shardDirectory.getShard(shardId);
            if (shard == null) {
                log.error("❌ Shard {} not found in directory for wallet {}", shardId, walletAddress);
                return null;
            }
            
            // Discover current leader (with caching)
            String leaderUrl = discoverCurrentLeader(shardId, shard.getPeerUrls());
            
            if (leaderUrl == null) {
                log.warn("⚠️  Could not discover leader for shard {}, falling back to first peer", shardId);
                // Fallback: Return first peer (will handle redirect if needed)
                List<String> peerUrls = shard.getPeerUrls();
                return peerUrls.isEmpty() ? null : peerUrls.get(0);
            }
            
            return leaderUrl;
        } catch (Exception e) {
            log.error("❌ Error routing request for wallet {}: {}", walletAddress, e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * Discover current leader by querying cluster state endpoint.
     * 
     * <p>Uses 5-second TTL cache to avoid sequential peer queries on every request.
     * Aeron Cluster followers return HTTP 307 to leader on writes, so we can snoop
     * that response and update cache instantly.
     * 
     * @param shardId Shard ID
     * @param peerUrls List of peer URLs for the shard
     * @return Current leader URL, or null if not found
     */
    private String discoverCurrentLeader(int shardId, List<String> peerUrls) {
        // Check cache first (with TTL)
        CachedLeader cached = leaderCache.get(shardId);
        if (cached != null && !cached.isExpired()) {
            log.debug("✅ Using cached leader for shard {}: {}", shardId, cached.leaderUrl);
            return cached.leaderUrl;
        }
        
        // Cache miss or expired: discover leader
        log.debug("🔍 Cache miss for shard {}, discovering leader from {} peers", shardId, peerUrls.size());
        String leaderUrl = discoverLeaderFromPeers(peerUrls);
        
        // Update cache
        if (leaderUrl != null) {
            leaderCache.put(shardId, new CachedLeader(leaderUrl));
            log.debug("✅ Discovered leader for shard {}: {} (cached)", shardId, leaderUrl);
        } else {
            // Remove expired cache entry if leader discovery failed
            leaderCache.remove(shardId);
        }
        
        return leaderUrl;
    }
    
    /**
     * Discover leader by querying peers sequentially.
     * Called on cache miss or expiration.
     * 
     * @param peerUrls List of peer URLs to query
     * @return Leader URL, or null if not found
     */
    private String discoverLeaderFromPeers(List<String> peerUrls) {
        for (String peerUrl : peerUrls) {
            try {
                URL url = new URL(peerUrl + "/v1/consensus/leader");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                
                int responseCode = conn.getResponseCode();
                if (responseCode == 200) {
                    String response = readResponseBody(conn);
                    String currentLeader = JsonParser.extractField(response, "currentLeader");
                    if (currentLeader != null && !currentLeader.isEmpty() && !"null".equals(currentLeader)) {
                        log.debug("✅ Found leader via consensus leader endpoint: {}", currentLeader);
                        return currentLeader;
                    }

                    String isLeader = JsonParser.extractField(response, "isLeader");
                    if ("true".equalsIgnoreCase(isLeader)) {
                        log.debug("✅ Peer {} reports itself as leader", peerUrl);
                        return peerUrl;
                    }
                } else if (responseCode == HttpURLConnection.HTTP_NOT_FOUND) {
                    String legacyLeaderUrl = discoverLeaderFromLegacyClusterState(peerUrl);
                    if (legacyLeaderUrl != null) {
                        return legacyLeaderUrl;
                    }
                } else {
                    log.debug("   Non-200 response from {}: {}", peerUrl, responseCode);
                }
            } catch (Exception e) {
                log.debug("Failed to query {} for cluster state: {}", peerUrl, e.getMessage());
                // Try next peer
                continue;
            }
        }
        
        log.warn("⚠️  Could not discover leader from any peer");
        return null;
    }

    private String discoverLeaderFromLegacyClusterState(String peerUrl) {
        try {
            URL url = new URL(peerUrl + "/v1/aeron/cluster-state");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);

            if (conn.getResponseCode() != 200) {
                return null;
            }

            String response = readResponseBody(conn);
            String currentLeader = JsonParser.extractField(response, "currentLeader");
            if (currentLeader != null && !currentLeader.isEmpty() && !"null".equals(currentLeader)) {
                return currentLeader;
            }
            if (response.contains("\"isLeader\":true") || response.contains("\"role\":\"LEADER\"")) {
                return peerUrl;
            }

            int leaderRoleIndex = response.indexOf("\"role\":\"LEADER\"");
            if (leaderRoleIndex != -1) {
                int urlStart = response.lastIndexOf("\"url\":\"", leaderRoleIndex);
                if (urlStart != -1) {
                    urlStart += 7;
                    int urlEnd = response.indexOf("\"", urlStart);
                    if (urlEnd != -1) {
                        return response.substring(urlStart, urlEnd);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Failed legacy cluster-state discovery for {}: {}", peerUrl, e.getMessage());
        }
        return null;
    }

    private String readResponseBody(HttpURLConnection conn) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        try {
            return reader.lines().collect(Collectors.joining());
        } finally {
            reader.close();
        }
    }
    
    /**
     * Invalidate leader cache on HTTP 307 redirect (Aeron follower → leader redirect).
     * Call this when handling redirects to instantly update cache.
     * 
     * @param shardId Shard ID
     * @param newLeaderUrl New leader URL (from redirect)
     */
    public void invalidateLeaderCache(int shardId, String newLeaderUrl) {
        if (newLeaderUrl != null && !newLeaderUrl.isEmpty()) {
            leaderCache.put(shardId, new CachedLeader(newLeaderUrl));
            log.debug("✅ Updated leader cache for shard {}: {}", shardId, newLeaderUrl);
        } else {
            leaderCache.remove(shardId);
            log.debug("✅ Invalidated leader cache for shard {}", shardId);
        }
    }
    
    /**
     * Clear all leader caches (for testing or manual invalidation).
     */
    public void clearLeaderCache() {
        leaderCache.clear();
        log.info("✅ Cleared all leader caches");
    }
    
    /**
     * Get shard directory.
     * 
     * @return Shard directory
     */
    public ShardDirectory getShardDirectory() {
        return shardDirectory;
    }
    
    /**
     * Get sharding strategy.
     * 
     * @return Sharding strategy
     */
    public ShardingStrategy getStrategy() {
        return strategy;
    }
    
    /**
     * Cached leader information with TTL.
     */
    private static class CachedLeader {
        final String leaderUrl;
        final long timestamp;
        
        CachedLeader(String leaderUrl) {
            this.leaderUrl = leaderUrl;
            this.timestamp = System.currentTimeMillis();
        }
        
        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > LEADER_CACHE_TTL_MS;
        }
    }
}
