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
package org.apache.jackrabbit.oak.segment.http.server.handlers;

import org.apache.jackrabbit.oak.segment.http.server.ServerContext;
import org.apache.jackrabbit.oak.segment.http.server.util.FormatUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Handler for Aeron Cluster-specific API endpoints.
 * 
 * <p>This handler provides Aeron Cluster-specific information that isn't available
 * through the generic consensus APIs. It exposes Raft metrics, cluster membership,
 * and leadership history.</p>
 */
public class AeronApiHandler {

    private static final Logger log = LoggerFactory.getLogger(AeronApiHandler.class);

    private final ServerContext context;

    public AeronApiHandler(ServerContext context) {
        this.context = context;
    }

    /**
     * ✈️ AERON NATIVE: Get Aeron Cluster state using native Aeron APIs.
     * 
     * This uses Aeron's internal cluster state directly - no HTTP API calls or custom discovery.
     * Returns complete Aeron Cluster state including:
     * - role: Current role (LEADER/FOLLOWER) from cluster.role()
     * - memberId: This node's member ID from cluster.memberId()
     * - leadershipTermId: Current Raft term from cluster.leadershipTermId()
     * - clusterMemberCount: Total members from cluster.clusterMemberCount()
     * - clusterTime: Cluster time from cluster.time()
     * - logPosition: Log position from cluster.logPosition()
     * - members: List of all cluster members from cluster.clusterMembers()
     * 
     * @return Cluster state map, or null if Aeron Cluster not configured
     */
    public Map<String, Object> getClusterStateData() {
        if (context.aeronConsensusEngine == null) {
            return null;
        }

        // ✈️ AERON NATIVE: Use Aeron's native cluster state API
        Map<String, Object> nativeState = context.aeronConsensusEngine.getNativeClusterState();
        if (nativeState == null) {
            return null;
        }
        
        // Add our cluster identifier and enrich with additional info
        Map<String, Object> state = new HashMap<>(nativeState);
        state.put("clusterId", "oak-consensus-cluster");
        state.put("nodeId", getNodeIdFromUrl(context.selfUrl));
        
        // Add validator identity (wallet address and public key)
        Map<String, Object> validatorIdentity = new HashMap<>();
        if (context.aeronConsensusEngine != null) {
            String walletAddress = context.aeronConsensusEngine.getWalletAddress();
            String publicKey = context.aeronConsensusEngine.getPublicKeyHex();
            if (walletAddress != null) {
                validatorIdentity.put("walletAddress", walletAddress);
            }
            if (publicKey != null) {
                validatorIdentity.put("publicKey", publicKey);
            }
        }
        if (!validatorIdentity.isEmpty()) {
            state.put("validatorIdentity", validatorIdentity);
        }
        
        // Enrich members list with wallet addresses (only self - fast, no network calls)
        // Peer wallet addresses are optional and can be fetched via separate endpoint if needed
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> members = (List<Map<String, Object>>) state.get("members");
        if (members != null) {
            enrichSelfWalletAddress(members);
        }
        
        // Add MediaDriver health status
        Map<String, Object> mediaDriver = new HashMap<>();
        if (context.aeronClusterLauncher != null) {
            org.apache.jackrabbit.oak.segment.consensus.aeron.CrashHandler crashHandler = 
                context.aeronClusterLauncher.getCrashHandler();
            if (crashHandler != null) {
                mediaDriver.put("status", crashHandler.hasCrashed() ? "UNHEALTHY" : "HEALTHY");
                mediaDriver.put("crashCount", crashHandler.getCrashCount());
                mediaDriver.put("hasCrashed", crashHandler.hasCrashed());
                mediaDriver.put("forceBootstrap", crashHandler.shouldForceBootstrap());
            } else {
                mediaDriver.put("status", "UNKNOWN");
            }
        } else {
            mediaDriver.put("status", "NOT_CONFIGURED");
        }
        state.put("mediaDriver", mediaDriver);
        
        // Add quorum information
        Integer memberCount = (Integer) state.get("memberCount");
        if (memberCount == null) {
            Object clusterMemberCount = state.get("clusterMemberCount");
            if (clusterMemberCount instanceof Number) {
                memberCount = ((Number) clusterMemberCount).intValue();
            }
        }
        if (memberCount != null) {
            Map<String, Object> quorum = new HashMap<>();
            int quorumSize = (memberCount / 2) + 1; // Majority
            quorum.put("required", quorumSize);
            quorum.put("current", context.aeronConsensusEngine.getReachableValidatorCount());
            quorum.put("totalMembers", memberCount);
            quorum.put("hasQuorum", context.aeronConsensusEngine.getReachableValidatorCount() >= quorumSize);
            state.put("quorum", quorum);
        }
        
        // Add reachable count (if available from native state, otherwise use fallback)
        if (!state.containsKey("reachableCount")) {
            state.put("reachableCount", context.aeronConsensusEngine.getReachableValidatorCount());
        }
        
        // Ensure consensus metrics are present
        Map<String, Object> consensus = new HashMap<>();
        consensus.put("reachableValidators", 
            state.containsKey("reachableCount") ? state.get("reachableCount") : 
            context.aeronConsensusEngine.getReachableValidatorCount());
        consensus.put("totalMembers", state.get("clusterMemberCount"));
        consensus.put("lastHeartbeat", context.aeronConsensusEngine.getLastHeartbeatTime());
        state.put("consensus", consensus);
        
        // Add Aeron metrics summary (if available)
        if (context.aeronPrometheusMetrics != null) {
            Map<String, Object> aeronMetrics = new HashMap<>();
            aeronMetrics.put("available", true);
            aeronMetrics.put("note", "Detailed metrics available at /metrics endpoint");
            state.put("aeronMetrics", aeronMetrics);
        }
        
        // Add cluster health summary
        Map<String, Object> health = new HashMap<>();
        boolean hasQuorum = false;
        if (memberCount != null) {
            int quorumSize = (memberCount / 2) + 1;
            hasQuorum = context.aeronConsensusEngine.getReachableValidatorCount() >= quorumSize;
        }
        health.put("status", hasQuorum ? "HEALTHY" : "DEGRADED");
        health.put("hasQuorum", hasQuorum);
        health.put("mediaDriverHealthy", mediaDriver.get("status").equals("HEALTHY"));
        state.put("health", health);
        
        return state;
    }
    
    /**
     * Handle GET /v1/aeron/cluster-state - Returns complete Aeron Cluster state
     * 
     * Response includes:
     * - clusterId: Cluster identifier
     * - nodeId: This node's ID
     * - role: Current role (LEADER/FOLLOWER)
     * - term: Current Raft term
     * - members: List of all cluster members with their status
     * - consensus: Raft consensus metrics
     */
    public void handleClusterState(HttpServletResponse response) throws IOException {
        Map<String, Object> state = getClusterStateData();
        if (state == null) {
            sendError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, 
                "Aeron Cluster consensus not configured");
            return;
        }

        response.setContentType("application/json");
        response.setStatus(HttpServletResponse.SC_OK);
        
        // Write JSON response
        writeJsonResponse(response, state);
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
        List<String> allUrls = new ArrayList<>();
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
                        log.info("✅ Found leader (top-level isLeader:true): {} (from {})", url, url);
                        return url;
                    }
                    
                    // Priority 2: Check top-level "role":"LEADER"
                    if (response.contains("\"role\":\"LEADER\"")) {
                        log.info("✅ Found leader (top-level role:LEADER): {} (from {})", url, url);
                        return url;
                    }
                    
                    // Priority 3: Parse members array to find leader
                    // Look for member with "role":"LEADER"
                    int leaderRoleIndex = response.indexOf("\"role\":\"LEADER\"");
                    if (leaderRoleIndex != -1) {
                        // Find the URL field in the same member object (search backwards from role)
                        // Look for "url":"..." before the role field
                        int urlStart = response.lastIndexOf("\"url\":\"", leaderRoleIndex);
                        if (urlStart == -1) {
                            // Try forward search
                            urlStart = response.indexOf("\"url\":\"", leaderRoleIndex);
                        }
                        if (urlStart != -1) {
                            urlStart += 7; // Skip past "url":"
                            int urlEnd = response.indexOf("\"", urlStart);
                            if (urlEnd != -1 && urlEnd < leaderRoleIndex + 200) { // Ensure URL is near role field
                                String leaderUrl = response.substring(urlStart, urlEnd);
                                log.info("✅ Found leader via members array: {} (from {})", leaderUrl, url);
                                return leaderUrl;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("Failed to query Aeron Cluster state from {}: {}", url, e.getMessage());
            }
        }
        return null;
    }
    
    /**
     * Resolve hostname-based URL to IP-based URL for reliable networking.
     */
    private String resolveUrlToIP(String url) {
        try {
            java.net.URL parsedUrl = new java.net.URL(url);
            String hostname = parsedUrl.getHost();
            int port = parsedUrl.getPort();
            String protocol = parsedUrl.getProtocol();
            String path = parsedUrl.getPath();
            
            // If already an IP address, return as-is
            if (hostname.matches("^\\d+\\.\\d+\\.\\d+\\.\\d+$")) {
                return url;
            }
            
            // Resolve hostname to IP
            try {
                String ip = java.net.InetAddress.getByName(hostname).getHostAddress();
                return String.format("%s://%s%s%s",
                    protocol,
                    ip,
                    port != -1 ? ":" + port : "",
                    path != null ? path : "");
            } catch (java.net.UnknownHostException e) {
                // If resolution fails, return original URL (may be ngrok/Ethos URL)
                return url;
            }
        } catch (Exception e) {
            return url;
        }
    }

    /**
     * Handle GET /v1/aeron/raft-metrics - Returns Raft-specific metrics
     * 
     * Response includes:
     * - electionMetrics: Leader election statistics
     * - replicationMetrics: Log replication statistics
     * - commitMetrics: Commit statistics
     */
    public void handleRaftMetrics(HttpServletResponse response) throws IOException {
        if (context.aeronConsensusEngine == null) {
            sendError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, 
                "Aeron Cluster consensus not configured");
            return;
        }

        response.setContentType("application/json");
        response.setStatus(HttpServletResponse.SC_OK);

        Map<String, Object> metrics = new HashMap<>();
        
        // Election metrics
        Map<String, Object> electionMetrics = new HashMap<>();
        electionMetrics.put("currentTerm", context.aeronConsensusEngine.getCurrentTerm());
        electionMetrics.put("isLeader", context.aeronConsensusEngine.isLeader());
        electionMetrics.put("currentLeader", context.aeronConsensusEngine.getCurrentLeader());
        metrics.put("electionMetrics", electionMetrics);
        
        // Replication metrics
        Map<String, Object> replicationMetrics = new HashMap<>();
        replicationMetrics.put("reachableValidators", context.aeronConsensusEngine.getReachableValidatorCount());
        replicationMetrics.put("totalFollowers", context.aeronConsensusEngine.getAllFollowers().size());
        metrics.put("replicationMetrics", replicationMetrics);
        
        // Commit metrics
        Map<String, Object> commitMetrics = new HashMap<>();
        commitMetrics.put("currentEpoch", context.aeronConsensusEngine.getCurrentEpoch());
        commitMetrics.put("ethereumEpoch", context.aeronConsensusEngine.getCurrentEthereumEpoch());
        metrics.put("commitMetrics", commitMetrics);
        
        // Write JSON response
        writeJsonResponse(response, metrics);
    }

    /**
     * Handle GET /v1/aeron/node-status - Returns status of specific cluster node
     * 
     * Query params:
     * - nodeId: Node ID (optional, defaults to self)
     * - url: Node URL (optional, alternative to nodeId)
     */
    public void handleNodeStatus(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (context.aeronConsensusEngine == null) {
            sendError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, 
                "Aeron Cluster consensus not configured");
            return;
        }

        response.setContentType("application/json");
        response.setStatus(HttpServletResponse.SC_OK);

        String nodeIdParam = request.getParameter("nodeId");
        String urlParam = request.getParameter("url");
        
        String targetUrl = context.selfUrl; // Default to self
        if (urlParam != null && !urlParam.isEmpty()) {
            targetUrl = urlParam;
        } else if (nodeIdParam != null && !nodeIdParam.isEmpty()) {
            // Find URL by node ID
            int targetNodeId = Integer.parseInt(nodeIdParam);
            String currentLeader = context.aeronConsensusEngine.getCurrentLeader();
            if (currentLeader != null && getNodeIdFromUrl(currentLeader) == targetNodeId) {
                targetUrl = currentLeader;
            } else {
                for (String followerUrl : context.aeronConsensusEngine.getAllFollowers()) {
                    if (getNodeIdFromUrl(followerUrl) == targetNodeId) {
                        targetUrl = followerUrl;
                        break;
                    }
                }
            }
        }
        
        Map<String, Object> nodeStatus = new HashMap<>();
        nodeStatus.put("nodeId", getNodeIdFromUrl(targetUrl));
        nodeStatus.put("url", targetUrl);
        nodeStatus.put("role", targetUrl.equals(context.aeronConsensusEngine.getCurrentLeader()) ? "LEADER" : "FOLLOWER");
        nodeStatus.put("status", "ACTIVE");
        nodeStatus.put("lastHeartbeat", context.aeronConsensusEngine.getLastHeartbeatTime());
        nodeStatus.put("isSelf", targetUrl.equals(context.selfUrl));
        
        // Basic metrics
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("reachableValidators", context.aeronConsensusEngine.getReachableValidatorCount());
        nodeStatus.put("metrics", metrics);
        
        // Write JSON response
        writeJsonResponse(response, nodeStatus);
    }

    /**
     * ✈️ AERON NATIVE: Handle GET /v1/aeron/leadership-history - Returns recent leadership rotations
     * 
     * This uses Aeron's onRoleChange() callback history to show when leaders rotated.
     * 
     * Query params:
     * - limit: Number of entries to return (default: 10)
     */
    public void handleLeadershipHistory(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (context.aeronConsensusEngine == null) {
            sendError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, 
                "Aeron Cluster consensus not configured");
            return;
        }

        response.setContentType("application/json");
        response.setStatus(HttpServletResponse.SC_OK);

        // Parse limit parameter
        int limit = 10; // Default
        String limitParam = request.getParameter("limit");
        if (limitParam != null && !limitParam.isEmpty()) {
            try {
                limit = Integer.parseInt(limitParam);
                if (limit < 1) limit = 10;
                if (limit > 100) limit = 100; // Cap at 100
            } catch (NumberFormatException e) {
                // Use default
            }
        }
        
        // ✈️ AERON NATIVE: Get leadership history from onRoleChange() callbacks
        java.util.List<org.apache.jackrabbit.oak.segment.consensus.aeron.AeronConsensusEngine.LeadershipChange> changes = 
            context.aeronConsensusEngine.getLeadershipHistory(limit);
        
        Map<String, Object> history = new HashMap<>();
        List<Map<String, Object>> entries = new ArrayList<>();
        
        for (org.apache.jackrabbit.oak.segment.consensus.aeron.AeronConsensusEngine.LeadershipChange change : changes) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("timestamp", change.timestamp);
            entry.put("clusterTime", change.timestamp); // Aeron cluster time
            entry.put("term", change.term);
            entry.put("memberId", change.memberId);
            entry.put("memberUrl", change.memberUrl);
            entry.put("previousRole", change.previousRole != null ? change.previousRole.name() : "UNKNOWN");
            entry.put("newRole", change.newRole.name());
            entry.put("isLeaderRotation", change.newRole == io.aeron.cluster.service.Cluster.Role.LEADER && 
                change.previousRole != io.aeron.cluster.service.Cluster.Role.LEADER);
            entries.add(entry);
        }
        
        history.put("history", entries);
        history.put("totalEntries", entries.size());
        history.put("limit", limit);
        
        // Write JSON response
        writeJsonResponse(response, history);
    }

    /**
     * Enrich self member with wallet address (fast, no network calls).
     * Peer wallet addresses are not included to keep API response fast (<100ms).
     * Use /v1/aeron/node-status?url=<peer-url> to get peer wallet addresses if needed.
     */
    private void enrichSelfWalletAddress(List<Map<String, Object>> members) {
        // Only enrich self wallet address (fast, no network calls)
        for (Map<String, Object> member : members) {
            String memberUrl = (String) member.get("url");
            if (memberUrl != null && memberUrl.equals(context.selfUrl)) {
                // This is us - add our wallet info
                if (context.aeronConsensusEngine != null) {
                    String walletAddress = context.aeronConsensusEngine.getWalletAddress();
                    String publicKey = context.aeronConsensusEngine.getPublicKeyHex();
                    if (walletAddress != null) {
                        member.put("walletAddress", walletAddress);
                    }
                    if (publicKey != null) {
                        member.put("publicKey", publicKey);
                    }
                }
                break; // Found self, no need to continue
            }
        }
    }
    
    /**
     * Extract node ID from validator URL.
     * Assumes format: http://validator-N:port
     */
    private int getNodeIdFromUrl(String url) {
        if (url == null) return -1;
        try {
            // Extract hostname from URL
            java.net.URL urlObj = new java.net.URL(url);
            String host = urlObj.getHost();
            if (host.startsWith("validator-")) {
                String nodeIdStr = host.substring("validator-".length());
                return Integer.parseInt(nodeIdStr);
            }
            // Fallback: hash URL to get consistent node ID
            return Math.abs(url.hashCode() % 100);
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Write JSON response from Map.
     */
    private void writeJsonResponse(HttpServletResponse response, Map<String, Object> data) throws IOException {
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            if (!first) json.append(",");
            first = false;
            json.append("\"").append(entry.getKey()).append("\":");
            appendJsonValue(json, entry.getValue());
        }
        json.append("}");
        response.getWriter().write(json.toString());
    }

    /**
     * Append JSON value (recursive for nested structures).
     */
    private void appendJsonValue(StringBuilder json, Object value) {
        if (value == null) {
            json.append("null");
        } else if (value instanceof String) {
            json.append("\"").append(FormatUtils.escapeJson((String) value)).append("\"");
        } else if (value instanceof Number || value instanceof Boolean) {
            json.append(value);
        } else if (value instanceof List) {
            json.append("[");
            boolean first = true;
            for (Object item : (List<?>) value) {
                if (!first) json.append(",");
                first = false;
                appendJsonValue(json, item);
            }
            json.append("]");
        } else if (value instanceof Map) {
            json.append("{");
            boolean first = true;
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (!first) json.append(",");
                first = false;
                json.append("\"").append(entry.getKey()).append("\":");
                appendJsonValue(json, entry.getValue());
            }
            json.append("}");
        } else {
            json.append("\"").append(FormatUtils.escapeJson(value.toString())).append("\"");
        }
    }

    /**
     * ✅ ADR 025: Handle GET /v1/aeron/replication-lag - Returns replication lag status
     * 
     * <p>Shows how far behind this follower is from the leader's log position.
     * Useful for monitoring cluster health and detecting slow followers.
     * 
     * <p>Response includes:
     * - role: Current role (LEADER/FOLLOWER)
     * - myLogPosition: This node's log position
     * - leaderLogPosition: Leader's log position
     * - replicationLag: Number of messages behind leader
     * - lagThreshold: Alert threshold (1000 messages)
     * - healthy: Whether lag is within acceptable range
     */
    public void handleReplicationLag(HttpServletResponse response) throws IOException {
        if (context.aeronConsensusEngine == null) {
            sendError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, 
                "Aeron Cluster consensus not configured");
            return;
        }
        
        Map<String, Object> lagStatus = context.aeronConsensusEngine.getReplicationLagStatus();
        
        if (lagStatus == null) {
            sendError(response, HttpServletResponse.SC_NOT_FOUND, 
                "Replication lag not applicable (cluster not initialized)");
            return;
        }
        
        response.setContentType("application/json");
        response.setStatus(HttpServletResponse.SC_OK);
        writeJsonResponse(response, lagStatus);
    }
    
    /**
     * Send standardized error response.
     */
    private void sendError(HttpServletResponse response, int statusCode, String message) throws IOException {
        response.setContentType("application/json");
        response.setStatus(statusCode);
        
        Map<String, Object> error = new HashMap<>();
        error.put("success", false);
        Map<String, Object> errorDetails = new HashMap<>();
        errorDetails.put("code", "AERON_NOT_CONFIGURED");
        errorDetails.put("message", message);
        error.put("error", errorDetails);
        error.put("timestamp", System.currentTimeMillis());
        
        writeJsonResponse(response, error);
    }
}

