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
import org.apache.jackrabbit.oak.segment.http.server.util.JsonParser;
import org.apache.jackrabbit.oak.segment.http.server.util.JsonOutputUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.jackrabbit.oak.segment.http.server.util.ApiErrorUtil;

/**
 * Handler for Aeron Cluster consensus endpoints.
 * 
 * <p>This class handles follower HEAD updates for Aeron Cluster consensus mode.
 * All other consensus operations (peer joins, leadership claims, etc.) are handled
 * internally by Aeron Cluster via Raft consensus protocol.
 * 
 * <p>Note: This class only supports AeronConsensusEngine. EpochLeaderEngine support
 * has been removed as part of the commitment to Aeron-only consensus.
 */
public class LeaderConsensusHandler {

    private static final Logger log = LoggerFactory.getLogger(LeaderConsensusHandler.class);

    private final ServerContext context;

    public LeaderConsensusHandler(ServerContext context) {
        this.context = context;
    }

    /**
     * Handle POST /v1/follower/head-update - Follower receives HEAD update from leader
     * 
     * <p>This endpoint is called by the leader to broadcast HEAD updates to followers.
     * Followers pull missing segments from the leader and update their HEAD to match.
     * 
     * <p>Request body (JSON):
     * <pre>
     * {
     *   "head": "abc123:r999",      // The new HEAD RecordId from leader
     *   "epoch": 408206,            // Current epoch number
     *   "leaderUrl": "http://..."   // URL of the current leader
     * }
     * </pre>
     * 
     * <p>Response (JSON):
     * <pre>
     * {
     *   "success": true,
     *   "message": "HEAD replicated",
     *   "segmentCount": 42
     * }
     * </pre>
     * 
     * @param request HTTP request containing HEAD update JSON
     * @param response HTTP response
     * @throws IOException if I/O error occurs
     */
    public void handleFollowerHeadUpdate(HttpServletRequest request, HttpServletResponse response) throws IOException {
        // AERON MODE ONLY: Require AeronConsensusEngine
        if (context.aeronConsensusEngine == null) {
            ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, 
                "Aeron consensus engine not configured");
            return;
        }
        
        // Only followers can receive HEAD updates (leader broadcasts, doesn't receive)
        if (context.aeronConsensusEngine.isLeader()) {
            ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_BAD_REQUEST, 
                "I am the leader, not a follower");
            return;
        }
        
        try {
            // Read JSON body
            StringBuilder json = new StringBuilder();
            BufferedReader reader = request.getReader();
            String line;
            while ((line = reader.readLine()) != null) {
                json.append(line);
            }
            
            String body = json.toString();
            
            // Parse HEAD update
            String head = JsonParser.extractField(body, "head");
            String epochStr = JsonParser.extractField(body, "epoch");
            String leaderUrl = JsonParser.extractField(body, "leaderUrl");
            
            if (head == null || head.isEmpty()) {
                ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_BAD_REQUEST, 
                    "Missing required field: head");
                return;
            }
            
            if (leaderUrl == null || leaderUrl.isEmpty()) {
                ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_BAD_REQUEST, 
                    "Missing required field: leaderUrl");
                return;
            }
            
            int epoch = -1;
            if (epochStr != null && !epochStr.isEmpty()) {
                try {
                    epoch = Integer.parseInt(epochStr);
                } catch (NumberFormatException e) {
                    log.debug("Invalid epoch format: {}", epochStr);
                }
            }
            
            log.info("📥 Received HEAD update from leader: {}", leaderUrl);
            log.info("   HEAD: {}...", head.substring(0, Math.min(20, head.length())));
            if (epoch >= 0) {
                log.info("   Epoch: {}", epoch);
            }
            
            // Verify this is from the legitimate leader
            // Use port-based comparison to handle localhost vs 127.0.0.1 variations
            String currentLeader = context.aeronConsensusEngine.getCurrentLeader();
            if (currentLeader != null && !isSameUrlByPort(leaderUrl, currentLeader)) {
                log.warn("🚫 HEAD update from non-leader: {} (expected: {})", 
                    leaderUrl, currentLeader);
                ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_FORBIDDEN, 
                    "Not current leader");
                return;
            }
            
            // Pull segments for this HEAD from leader
            int segmentCount = context.aeronConsensusEngine.pullSegmentsForHead(head, leaderUrl);
            
            log.info("✅ HEAD update complete - replicated {} segments", segmentCount);
            
            // Return success
            response.setContentType("application/json");
            response.setStatus(HttpServletResponse.SC_OK);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("message", "HEAD replicated");
            result.put("segmentCount", segmentCount);
            response.getWriter().write(JsonOutputUtil.toJson(result));
            
        } catch (Exception e) {
            log.error("❌ Failed to process follower HEAD update", e);
            ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, 
                "Failed to process HEAD update: " + e.getMessage());
        }
    }
    
    /**
     * Compare two URLs by port only (handles localhost vs 127.0.0.1 variations).
     * @param url1 First URL
     * @param url2 Second URL
     * @return true if both URLs have the same port
     */
    private boolean isSameUrlByPort(String url1, String url2) {
        if (url1 == null || url2 == null) {
            return false;
        }
        try {
            int port1 = extractPort(url1);
            int port2 = extractPort(url2);
            return port1 == port2 && port1 != -1;
        } catch (Exception e) {
            log.debug("Failed to compare URLs: {} vs {}", url1, url2);
            return url1.equals(url2); // Fallback to exact match
        }
    }
    
    /**
     * Extract port from URL string.
     * @param url URL string (e.g., "http://localhost:8090" or "http://127.0.0.1:8092")
     * @return Port number, or -1 if not found
     */
    private int extractPort(String url) {
        try {
            int colonIndex = url.lastIndexOf(':');
            if (colonIndex > 0) {
                String portStr = url.substring(colonIndex + 1);
                // Remove trailing slash if present
                if (portStr.endsWith("/")) {
                    portStr = portStr.substring(0, portStr.length() - 1);
                }
                return Integer.parseInt(portStr);
            }
        } catch (Exception e) {
            // Ignore parsing errors
        }
        return -1;
    }
    
}
