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
import org.apache.jackrabbit.oak.segment.http.server.model.ValidatorRegistration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Set;
import org.apache.jackrabbit.oak.segment.http.server.util.ApiErrorUtil;

/**
 * Handler for peer discovery endpoints (`/v1/peers`).
 * This class encapsulates the logic for returning lists of known validators
 * for organic peer discovery in the consensus network.
 */
public class PeerDiscoveryHandler {

    private static final Logger log = LoggerFactory.getLogger(PeerDiscoveryHandler.class);

    private final ServerContext context;

    public PeerDiscoveryHandler(ServerContext context) {
        this.context = context;
    }

    /**
     * Handle GET /v1/peers - Return list of all known validators for organic peer discovery
     * 
     * Returns JSON array with enriched status:
     * [
     *   {"validatorId": "validator-1", "validatorUrl": "http://validator-1:8090", "lastSeen": 1234567890, "status": "READY"},
     *   {"validatorId": "validator-2", "validatorUrl": "http://validator-2:8090", "lastSeen": 1234567891, "status": "PROBATION"}
     * ]
     * 
     * Status values:
     * - READY: Voting member, fully participating in consensus
     * - PROBATION: Non-voting follower, must wait 1 epoch before joining electorate
     * - OFFLINE: Last seen > 2 epochs ago (10 minutes), likely disconnected
     */
    public void handlePeerList(HttpServletResponse response) throws IOException {
        try {
            response.setContentType("application/json");
            response.setStatus(HttpServletResponse.SC_OK);
            
            // BLOCKCHAIN CONSENSUS: Use Aeron Cluster for single source of truth
            // This ensures /v1/peers returns the same validator list as /v1/consensus/status
            final long now = System.currentTimeMillis();
            final List<String> nonVotingFollowers;
            final List<String> allValidatorUrls;
            int leaderTermSeconds = 300; // default
            
            if (context.aeronConsensusEngine != null) {
                // Aeron Cluster consensus - use registered validators + Aeron peers
                nonVotingFollowers = context.aeronConsensusEngine.getNonVotingFollowers();
                // Use Set to deduplicate URLs - LinkedHashSet preserves insertion order
                Set<String> validatorUrlSet = new LinkedHashSet<>();
                if (context.selfUrl != null) {
                    validatorUrlSet.add(context.selfUrl);
                }
                // Add all Aeron followers (these are already deduplicated by Aeron)
                List<String> aeronFollowers = context.aeronConsensusEngine.getAllFollowers();
                if (aeronFollowers != null) {
                    validatorUrlSet.addAll(aeronFollowers);
                }
                // Add any registered validators (deduplicated by Set)
                for (ValidatorRegistration reg : context.registeredValidators.values()) {
                    if (reg != null && reg.validatorUrl != null) {
                        validatorUrlSet.add(reg.validatorUrl);
                    }
                }
                // Convert to sorted list (ensures deterministic ordering)
                allValidatorUrls = new ArrayList<>(validatorUrlSet);
                Collections.sort(allValidatorUrls);
            } else {
                // No consensus engine - standalone mode, but still show registered validators
                nonVotingFollowers = new ArrayList<>();
                allValidatorUrls = new ArrayList<>();
                allValidatorUrls.add(context.selfUrl);
                // Add any registered validators (from HTTP registration)
                for (ValidatorRegistration reg : context.registeredValidators.values()) {
                    if (!reg.validatorUrl.equals(context.selfUrl)) {
                        allValidatorUrls.add(reg.validatorUrl);
                    }
                }
                Collections.sort(allValidatorUrls);
            }
            
            // OFFLINE = missed 2 full epochs (2 x leaderTermSeconds)
            final long offlineThresholdMs = leaderTermSeconds * 2 * 1000L; // 2 epochs
            
            StringBuilder json = new StringBuilder();
            json.append("[\n");
            
            // Use Set to track processed URLs to avoid duplicates
            Set<String> processedUrls = new HashSet<>();
            
            boolean first = true;
            for (String validatorUrl : allValidatorUrls) {
                // Skip if we've already processed this URL
                if (processedUrls.contains(validatorUrl)) {
                    continue;
                }
                processedUrls.add(validatorUrl);
                
                if (!first) {
                    json.append(",\n");
                }
                first = false;
                
                // Get registration if it exists (find by URL, not ID)
                ValidatorRegistration reg = null;
                for (ValidatorRegistration r : context.registeredValidators.values()) {
                    if (r.validatorUrl.equals(validatorUrl)) {
                        reg = r;
                        break;
                    }
                }
                
                // Determine status
                String status;
                long lastSeen = reg != null ? reg.lastSeen : now;
                long timeSinceLastSeen = now - lastSeen;
                
                // Check if this is self
                boolean isSelf = validatorUrl.equals(context.selfUrl);
                
                // For Aeron Cluster consensus, use Aeron's own status instead of lastSeen
                if (context.aeronConsensusEngine != null) {
                    // Aeron Cluster: Check if validator is in cluster
                    String currentLeader = context.aeronConsensusEngine.getCurrentLeader();
                    List<String> allFollowers = context.aeronConsensusEngine.getAllFollowers();
                    
                    if (validatorUrl.equals(currentLeader) || allFollowers.contains(validatorUrl)) {
                        // Validator is part of Aeron cluster - mark as READY
                        status = "READY";
                        // Update lastSeen to now since we know it's active
                        lastSeen = now;
                    } else if (isSelf) {
                        // Self is always READY if Aeron is active
                        status = "READY";
                        lastSeen = now;
                    } else {
                        // Not in Aeron cluster - check lastSeen as fallback
                        if (timeSinceLastSeen > offlineThresholdMs) {
                            status = "OFFLINE";
                        } else {
                            status = "READY";
                        }
                    }
                } else {
                    // Standalone mode - use lastSeen for status
                    if (timeSinceLastSeen > offlineThresholdMs) {
                        status = "OFFLINE";
                    } else if (nonVotingFollowers.contains(validatorUrl)) {
                        status = "PROBATION";
                    } else {
                        status = "READY";
                    }
                }
                
                // Get validator ID - ALWAYS use 0x address format
                // Priority: 1) Self wallet address, 2) Deterministic from URL (always use this for consistency)
                String validatorId;
                if (isSelf && context.myValidatorId != null && context.myValidatorId.startsWith("0x") && context.myValidatorId.length() == 42) {
                    // For self, prefer stored validator ID (from wallet) if valid
                    validatorId = context.myValidatorId;
                } else {
                    // Always generate deterministic 0x address from URL (ensures 0x format and consistency)
                    // This ensures all validators have deterministic, consistent 0x addresses
                    validatorId = generateDeterministicAddress(validatorUrl);
                }
                
                // Final safety check - ensure validator ID is always 0x format
                if (validatorId == null || !validatorId.startsWith("0x") || validatorId.length() != 42) {
                    validatorId = generateDeterministicAddress(validatorUrl);
                }
                
                json.append("  {");
                json.append("\"validatorId\":\"").append(validatorId.replace("\"", "\\\"")).append("\",");
                json.append("\"validatorUrl\":\"").append(validatorUrl.replace("\"", "\\\"")).append("\",");
                json.append("\"lastSeen\":").append(lastSeen).append(",");
                json.append("\"status\":\"").append(status).append("\"");
                
                // Add epoch information for probation status (Aeron mode)
                if ("PROBATION".equals(status) && context.aeronConsensusEngine != null) {
                    Map<String, Long> joinTimes = context.aeronConsensusEngine.getValidatorJoinTimes();
                    Long joinTime = joinTimes.get(validatorUrl);
                    
                    if (joinTime != null) {
                        long probationPeriod = leaderTermSeconds * 1000L;
                        long timeSinceJoin = now - joinTime;
                        
                        // Calculate epochs
                        int joinEpoch = (int) (joinTime / (leaderTermSeconds * 1000L));
                        int currentEpoch = context.aeronConsensusEngine.getCurrentEpoch();
                        int eligibleEpoch = joinEpoch + 1; // Must wait 1 full epoch
                        
                        json.append(",");
                        json.append("\"joinEpoch\":").append(joinEpoch).append(",");
                        json.append("\"eligibleEpoch\":").append(eligibleEpoch).append(",");
                        json.append("\"currentEpoch\":").append(currentEpoch).append(",");
                        json.append("\"secondsRemaining\":").append((probationPeriod - timeSinceJoin) / 1000L);
                    }
                }
                
                json.append("}");
            }
            
            json.append("\n]");
            response.getWriter().write(json.toString());
            
            log.debug("Served peer list: {} total validators", allValidatorUrls.size());
            
        } catch (Exception e) {
            log.error("Failed to serve peer list", e);
            ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Failed to get peer list: " + e.getMessage());
        }
    }
    
    /**
     * Handle GET /v1/ngrok-url - Get public ngrok URL (text)
     */
    public void handleNgrokUrl(HttpServletResponse response) throws IOException {
        response.setContentType("text/plain");
        response.setStatus(HttpServletResponse.SC_OK);
        // selfUrl is set from CONSENSUS_SELF_URL which includes ngrok URL
        response.getWriter().write(context.selfUrl != null ? context.selfUrl : "");
    }
    
    /**
     * Generate a deterministic 0x address from validator URL.
     * This ensures all validators have 0x address format even without registration.
     * Uses SHA-256 hash of URL to generate a deterministic address.
     */
    private String generateDeterministicAddress(String validatorUrl) {
        if (validatorUrl == null) {
            return "0x0000000000000000000000000000000000000000";
        }
        try {
            // Use SHA-256 hash of URL to generate deterministic address
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(validatorUrl.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            // Take first 20 bytes (40 hex chars) for Ethereum address format
            StringBuilder address = new StringBuilder("0x");
            for (int i = 0; i < 20; i++) {
                address.append(String.format("%02x", hash[i]));
            }
            return address.toString();
        } catch (Exception e) {
            log.warn("Failed to generate deterministic address for {}, using zero address", validatorUrl, e);
            return "0x0000000000000000000000000000000000000000";
        }
    }
}

