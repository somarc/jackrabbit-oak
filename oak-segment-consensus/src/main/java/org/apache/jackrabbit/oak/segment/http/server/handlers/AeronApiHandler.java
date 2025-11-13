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
        if (context.aeronConsensusEngine == null) {
            sendError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, 
                "Aeron Cluster consensus not configured");
            return;
        }

        response.setContentType("application/json");
        response.setStatus(HttpServletResponse.SC_OK);

        Map<String, Object> state = new HashMap<>();
        
        // Basic cluster info
        state.put("clusterId", "oak-consensus-cluster");
        state.put("nodeId", getNodeIdFromUrl(context.selfUrl));
        state.put("role", context.aeronConsensusEngine.getCurrentRole().name());
        state.put("isLeader", context.aeronConsensusEngine.isLeader());
        state.put("term", context.aeronConsensusEngine.getCurrentTerm());
        state.put("epoch", context.aeronConsensusEngine.getCurrentEpoch());
        state.put("ethereumEpoch", context.aeronConsensusEngine.getCurrentEthereumEpoch());
        
        // Cluster members
        List<Map<String, Object>> members = new ArrayList<>();
        String currentLeader = context.aeronConsensusEngine.getCurrentLeader();
        List<String> allFollowers = context.aeronConsensusEngine.getAllFollowers();
        
        // Add leader
        if (currentLeader != null) {
            Map<String, Object> leader = new HashMap<>();
            leader.put("nodeId", getNodeIdFromUrl(currentLeader));
            leader.put("url", currentLeader);
            leader.put("role", "LEADER");
            leader.put("status", "ACTIVE");
            leader.put("lastHeartbeat", context.aeronConsensusEngine.getLastHeartbeatTime());
            members.add(leader);
        }
        
        // Add followers
        for (String followerUrl : allFollowers) {
            Map<String, Object> follower = new HashMap<>();
            follower.put("nodeId", getNodeIdFromUrl(followerUrl));
            follower.put("url", followerUrl);
            follower.put("role", "FOLLOWER");
            follower.put("status", "ACTIVE");
            follower.put("lastHeartbeat", context.aeronConsensusEngine.getLastHeartbeatTime());
            members.add(follower);
        }
        
        state.put("members", members);
        state.put("memberCount", members.size());
        state.put("reachableCount", context.aeronConsensusEngine.getReachableValidatorCount());
        
        // Consensus metrics
        Map<String, Object> consensus = new HashMap<>();
        consensus.put("reachableValidators", context.aeronConsensusEngine.getReachableValidatorCount());
        consensus.put("totalMembers", members.size());
        state.put("consensus", consensus);
        
        // Write JSON response
        writeJsonResponse(response, state);
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
     * Handle GET /v1/aeron/leadership-history - Returns recent leadership changes
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

        String limitParam = request.getParameter("limit");
        if (limitParam != null && !limitParam.isEmpty()) {
            try {
                Integer.parseInt(limitParam); // Validate format (limit not yet used)
            } catch (NumberFormatException e) {
                // Use default
            }
        }
        
        Map<String, Object> history = new HashMap<>();
        List<Map<String, Object>> entries = new ArrayList<>();
        
        // For now, return current leadership info
        // TODO: Implement actual history tracking with limit parameter
        Map<String, Object> currentEntry = new HashMap<>();
        currentEntry.put("term", context.aeronConsensusEngine.getCurrentTerm());
        currentEntry.put("leaderNodeId", getNodeIdFromUrl(context.aeronConsensusEngine.getCurrentLeader()));
        currentEntry.put("leaderUrl", context.aeronConsensusEngine.getCurrentLeader());
        currentEntry.put("timestamp", System.currentTimeMillis());
        entries.add(currentEntry);
        
        history.put("history", entries);
        history.put("totalEntries", entries.size());
        
        // Write JSON response
        writeJsonResponse(response, history);
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

