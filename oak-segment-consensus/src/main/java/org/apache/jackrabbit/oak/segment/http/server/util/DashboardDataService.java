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
package org.apache.jackrabbit.oak.segment.http.server.util;

import org.apache.jackrabbit.oak.segment.consensus.state.ConsensusState;
import org.apache.jackrabbit.oak.segment.http.server.ServerContext;
import org.apache.jackrabbit.oak.segment.http.server.handlers.AeronApiHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Data service for dashboard that fetches data from the same sources as APIs.
 * 
 * BLOCKCHAIN CONSENSUS: This ensures dashboard uses single source of truth
 * by fetching data from the same sources as API endpoints.
 */
public class DashboardDataService {
    
    private static final Logger log = LoggerFactory.getLogger(DashboardDataService.class);
    
    private final ServerContext context;
    
    public DashboardDataService(ServerContext context) {
        this.context = context;
    }
    
    /**
     * Get FileStore statistics (same as /health/deep API).
     */
    public FileStoreStats getFileStoreStats() {
        FileStoreStats stats = new FileStoreStats();
        try {
            if (context.fileStore != null) {
                stats.size = context.fileStore.size();
                stats.segmentCount = context.fileStore.getSegmentCount();
                stats.status = "UP";
            } else {
                stats.status = "DOWN";
                stats.error = "FileStore not initialized";
            }
        } catch (Exception e) {
            stats.status = "DOWN";
            stats.error = e.getMessage();
            log.warn("Failed to get FileStore stats", e);
        }
        return stats;
    }
    
    /**
     * Get Aeron Cluster state from /v1/aeron/cluster-state API.
     * 
     * This is the preferred source for Aeron Cluster data as it includes
     * complete member information and leader discovery.
     * 
     * @return Cluster state map, or null if not available
     */
    public Map<String, Object> getAeronClusterState() {
        if (context.aeronConsensusEngine == null) {
            return null;
        }
        AeronApiHandler aeronApiHandler = new AeronApiHandler(context);
        return aeronApiHandler.getClusterStateData();
    }

    /**
     * Get recent leadership changes recorded by Aeron (via onRoleChange callbacks).
     *
     * @param limit maximum number of history entries to return
     * @return list of leadership changes, most recent first
     */
    public List<org.apache.jackrabbit.oak.segment.consensus.aeron.AeronConsensusEngine.LeadershipChange> getLeadershipHistory(int limit) {
        if (context.aeronConsensusEngine == null) {
            return java.util.Collections.emptyList();
        }
        return context.aeronConsensusEngine.getLeadershipHistory(limit);
    }
    
    /**
     * Get consensus state (same as /v1/consensus/status API).
     * 
     * CRITICAL: Uses EXACT same logic as ConsensusApiHandler.handleGetConsensusStatus()
     * to ensure dashboard matches API output exactly.
     * 
     * This method mirrors the logic in ConsensusApiHandler.handleGetConsensusStatus()
     * to ensure single source of truth.
     * 
     * @deprecated For Aeron Cluster, prefer getAeronClusterState() which uses /v1/aeron/cluster-state
     */
    public ConsensusState getConsensusState() {
        // Check for Aeron Cluster consensus first (newest, preferred)
        // EXACT same order and logic as ConsensusApiHandler.handleGetConsensusStatus()
        if (context.aeronConsensusEngine != null) {
            // Use same data as API endpoint (mirroring ConsensusApiHandler.handleGetConsensusStatus())
            String consensusType = "aeron-cluster";
            String currentRole = context.aeronConsensusEngine.getCurrentRole().name();
            String currentLeader = context.aeronConsensusEngine.getCurrentLeader();
            
            // ✈️ AERON CLUSTER: Try to discover leader if null (same as API handler)
            // Only use discovered leader if we successfully found it
            if (currentLeader == null && !context.aeronConsensusEngine.isLeader()) {
                // Try discovery (but don't fail if it doesn't work - null means discovery failed, not no leader)
                // We'll omit currentLeader from ConsensusState if null (same as API)
                currentLeader = discoverLeaderFromPeerClusterState();
            }
            
            int currentEpoch = context.aeronConsensusEngine.getCurrentEpoch();
            java.util.List<String> allFollowers = context.aeronConsensusEngine.getAllFollowers();
            
            // Build validator list (same as API)
            java.util.List<String> allValidators = new java.util.ArrayList<>();
            allValidators.add(context.selfUrl);
            allValidators.addAll(allFollowers);
            java.util.Collections.sort(allValidators);
            
            // For Aeron, all followers are voting (Raft doesn't have probation concept)
            java.util.List<String> electorate = new java.util.ArrayList<>(allValidators);
            java.util.List<String> nonVotingFollowers = context.aeronConsensusEngine.getNonVotingFollowers();
            
            // Convert to ConsensusState (for dashboard compatibility)
            // Only include currentLeader if we successfully discovered it (not null)
            // null means discovery failed, not that there's no leader
            ConsensusState.Builder builder = new ConsensusState.Builder()
                .consensusType(consensusType)
                .currentRole(currentRole)
                .currentEpoch(currentEpoch)
                .leaderTermSeconds(300) // Default for Aeron (Raft terms are dynamic)
                .secondsUntilRotation(0) // Not applicable for Aeron
                .electorateSize(electorate.size())
                .totalValidators(allValidators.size())
                .allValidators(allValidators)
                .electorate(electorate)
                .nonVotingFollowers(nonVotingFollowers)
                .nextLeader(null) // Not applicable for Aeron (Raft handles leader election)
                .selfUrl(context.selfUrl);
            
            // Only set currentLeader if we have it (omit if null = discovery failed)
            if (currentLeader != null) {
                builder.currentLeader(currentLeader);
            }
            
            return builder.build();
        }
        
        // No consensus engine configured
        return null;
    }
    
    /**
     * ✈️ AERON CLUSTER SOURCE OF TRUTH: Discover leader by querying peers' /v1/aeron/cluster-state.
     * 
     * This method queries peers' Aeron Cluster state API directly, which reflects Aeron's
     * internal Raft consensus state. This is the authoritative source for leader information.
     * 
     * @return Leader URL if found, null otherwise
     */
    private String discoverLeaderFromPeerClusterState() {
        if (context.aeronConsensusEngine == null) {
            return null;
        }
        
        // Get all peer URLs (including self)
        java.util.List<String> allUrls = new java.util.ArrayList<>();
        allUrls.add(context.selfUrl);
        allUrls.addAll(context.aeronConsensusEngine.getAllFollowers());
        
        log.debug("🔍 Querying {} peers' Aeron Cluster state to find leader", allUrls.size());
        
        for (String url : allUrls) {
            try {
                // Resolve hostname to IP for reliable networking
                String queryUrl = resolveUrlToIP(url);
                java.net.URL apiUrl = new java.net.URL(queryUrl + "/v1/aeron/cluster-state");
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) apiUrl.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(2000);
                conn.setReadTimeout(3000);
                
                int responseCode = conn.getResponseCode();
                if (responseCode == 200) {
                    java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(conn.getInputStream())
                    );
                    String response = reader.lines().collect(java.util.stream.Collectors.joining());
                    reader.close();
                    
                    // ✈️ AERON CLUSTER SOURCE OF TRUTH: Parse Aeron Cluster state JSON
                    // Priority 1: Check top-level "isLeader":true (this node is the leader)
                    if (response.contains("\"isLeader\":true")) {
                        log.debug("✅ Found leader (top-level isLeader:true): {}", url);
                        return url;
                    }
                    
                    // Priority 2: Check top-level "role":"LEADER"
                    if (response.contains("\"role\":\"LEADER\"")) {
                        log.debug("✅ Found leader (top-level role:LEADER): {}", url);
                        return url;
                    }
                    
                    // Priority 3: Parse members array to find leader
                    // Look for member with "role":"LEADER"
                    int leaderRoleIndex = response.indexOf("\"role\":\"LEADER\"");
                    if (leaderRoleIndex != -1) {
                        // Find the URL field in the same member object (search backwards from role)
                        int urlStart = response.lastIndexOf("\"url\":\"", leaderRoleIndex);
                        if (urlStart != -1) {
                            urlStart += 6; // Skip past "url":"
                            int urlEnd = response.indexOf("\"", urlStart);
                            if (urlEnd != -1) {
                                String leaderUrl = response.substring(urlStart, urlEnd);
                                log.debug("✅ Found leader in members array: {}", leaderUrl);
                                return leaderUrl;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("Failed to query {} for cluster state: {}", url, e.getMessage());
            }
        }
        
        return null;
    }
    
    /**
     * Resolve URL hostname to IP address for reliable networking.
     */
    private String resolveUrlToIP(String url) {
        try {
            java.net.URL urlObj = new java.net.URL(url);
            String host = urlObj.getHost();
            int port = urlObj.getPort() != -1 ? urlObj.getPort() : urlObj.getDefaultPort();
            String protocol = urlObj.getProtocol();
            
            // Try to resolve hostname to IP
            java.net.InetAddress addr = java.net.InetAddress.getByName(host);
            String ip = addr.getHostAddress();
            
            return protocol + "://" + ip + ":" + port + urlObj.getPath();
        } catch (Exception e) {
            log.debug("Failed to resolve {} to IP, using original: {}", url, e.getMessage());
            return url;
        }
    }
    
    /**
     * Calculate next leader for next epoch using deterministic algorithm.
     */
    private String calculateNextLeader(int currentEpoch, java.util.List<String> electorate) {
        if (electorate.isEmpty()) {
            return null;
        }
        int nextEpoch = currentEpoch + 1;
        int nextLeaderIndex = nextEpoch % electorate.size();
        return electorate.get(nextLeaderIndex);
    }
    
    /**
     * Get recent segment writes (same as /api/segments/recent API).
     */
    public List<String> getRecentSegments() {
        List<String> recentWrites = new ArrayList<>();
        try {
            java.nio.file.Path journalPath = context.storeDirectory.resolve("journal.log");
            if (java.nio.file.Files.exists(journalPath)) {
                List<String> allLines = java.nio.file.Files.readAllLines(journalPath);
                int start = Math.max(0, allLines.size() - 10);
                recentWrites = allLines.subList(start, allLines.size());
                java.util.Collections.reverse(recentWrites); // Most recent first
            }
        } catch (Exception e) {
            log.warn("Failed to read journal for recent segments", e);
        }
        return recentWrites;
    }
    
    /**
     * Get TarMK growth statistics (TAR file count, sizes, generations).
     */
    public TarMkGrowthStats getTarMkGrowthStats() {
        TarMkGrowthStats stats = new TarMkGrowthStats();
        try {
            if (context.fileStore != null && context.storeDirectory != null) {
                java.nio.file.Path storeDir = context.storeDirectory;
                
                // List all .tar files
                java.util.List<java.nio.file.Path> tarFiles = new java.util.ArrayList<>();
                try (java.util.stream.Stream<java.nio.file.Path> paths = java.nio.file.Files.list(storeDir)) {
                    tarFiles = paths
                        .filter(p -> p.toString().endsWith(".tar"))
                        .sorted(java.util.Comparator.comparing(java.nio.file.Path::toString))
                        .collect(java.util.stream.Collectors.toList());
                }
                
                stats.tarFileCount = tarFiles.size();
                stats.totalSize = 0;
                long largestSize = 0;
                long smallestSize = Long.MAX_VALUE;
                
                for (java.nio.file.Path tarFile : tarFiles) {
                    long fileSize = java.nio.file.Files.size(tarFile);
                    stats.totalSize += fileSize;
                    if (fileSize > largestSize) {
                        largestSize = fileSize;
                    }
                    if (fileSize < smallestSize) {
                        smallestSize = fileSize;
                    }
                }
                
                stats.largestTarSize = largestSize == 0 ? 0 : largestSize;
                stats.smallestTarSize = smallestSize == Long.MAX_VALUE ? 0 : smallestSize;
                stats.averageTarSize = tarFiles.isEmpty() ? 0 : stats.totalSize / tarFiles.size();
                
                // Get segment count from FileStore
                stats.segmentCount = context.fileStore.getSegmentCount();
                
                // Calculate growth efficiency
                // Ideal: TAR files should be close to maxFileSize (256 MB default)
                // Many small TAR files indicate inefficient packing
                long maxFileSize = 256L * 1024 * 1024; // 256 MB default
                if (stats.averageTarSize > 0) {
                    stats.packingEfficiency = (double) stats.averageTarSize / maxFileSize * 100.0;
                }
                
                stats.status = "UP";
            } else {
                stats.status = "DOWN";
                stats.error = "FileStore or storeDirectory not initialized";
            }
        } catch (Exception e) {
            stats.status = "DOWN";
            stats.error = e.getMessage();
            log.warn("Failed to get TarMK growth stats", e);
        }
        return stats;
    }
    
    /**
     * FileStore statistics data class.
     */
    public static class FileStoreStats {
        public long size = 0;
        public int segmentCount = 0;
        public String status = "UNKNOWN";
        public String error = null;
    }
    
    /**
     * TarMK growth statistics data class.
     */
    public static class TarMkGrowthStats {
        public int tarFileCount = 0;
        public long totalSize = 0;
        public long largestTarSize = 0;
        public long smallestTarSize = 0;
        public long averageTarSize = 0;
        public int segmentCount = 0;
        public double packingEfficiency = 0.0; // Percentage of max file size
        public String status = "UNKNOWN";
        public String error = null;
    }
}

