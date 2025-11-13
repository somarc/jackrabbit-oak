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

import org.apache.jackrabbit.oak.segment.consensus.leader.EpochLeaderEngine;
import org.apache.jackrabbit.oak.segment.http.server.ServerContext;
import org.apache.jackrabbit.oak.segment.http.server.model.ValidatorRegistration;
import org.apache.jackrabbit.oak.segment.consensus.state.ConsensusState;
import org.apache.jackrabbit.oak.segment.consensus.state.ConsensusStateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
            
            // BLOCKCHAIN CONSENSUS: Use ConsensusStateService for single source of truth
            // This ensures /v1/peers returns the same validator list as /v1/consensus/status
            final long now = System.currentTimeMillis();
            final List<String> nonVotingFollowers;
            final List<String> allValidatorUrls;
            int leaderTermSeconds = 300; // default
            
            if (context.aeronConsensusEngine != null) {
                // Aeron Cluster consensus - use registered validators + Aeron peers
                nonVotingFollowers = context.aeronConsensusEngine.getNonVotingFollowers();
                allValidatorUrls = new ArrayList<>();
                allValidatorUrls.add(context.selfUrl);
                allValidatorUrls.addAll(context.aeronConsensusEngine.getAllFollowers());
                Collections.sort(allValidatorUrls); // Deterministic ordering
                // For Aeron, use registered validators from context as source of truth
                // Aeron Cluster manages peers internally, but we expose via HTTP registration
                Set<String> registeredUrls = new HashSet<>();
                for (ValidatorRegistration reg : context.registeredValidators.values()) {
                    registeredUrls.add(reg.validatorUrl);
                }
                // Merge Aeron peers with registered validators
                for (String peerUrl : context.aeronConsensusEngine.getAllFollowers()) {
                    if (!registeredUrls.contains(peerUrl)) {
                        allValidatorUrls.add(peerUrl);
                    }
                }
                Collections.sort(allValidatorUrls);
            } else if (context.consensusStateService != null) {
                // Use ConsensusStateService for normalized, deterministic state
                ConsensusState state = context.consensusStateService.getConsensusState();
                nonVotingFollowers = state.nonVotingFollowers;
                allValidatorUrls = new ArrayList<>(state.allValidators); // Already sorted
                leaderTermSeconds = state.leaderTermSeconds;
            } else if (context.epochLeaderEngine != null) {
                // Fallback to direct engine access (shouldn't happen if ConsensusStateService is set)
                nonVotingFollowers = context.epochLeaderEngine.getNonVotingFollowers();
                allValidatorUrls = new ArrayList<>();
                allValidatorUrls.add(context.selfUrl);
                allValidatorUrls.addAll(context.epochLeaderEngine.getAllFollowers());
                Collections.sort(allValidatorUrls); // Deterministic ordering
                leaderTermSeconds = context.epochLeaderEngine.getElection().getLeaderTermSeconds();
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
            
            boolean first = true;
            for (String validatorUrl : allValidatorUrls) {
                if (!first) {
                    json.append(",\n");
                }
                first = false;
                
                // Get registration if it exists
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
                
                // Determine status with self-awareness for probation
                if (timeSinceLastSeen > offlineThresholdMs) {
                    status = "OFFLINE";
                } else if (nonVotingFollowers.contains(validatorUrl)) {
                    // Leader knows this validator is on probation
                    status = "PROBATION";
                } else if (isSelf && context.epochLeaderEngine != null) {
                    // Self-check: Are WE still on probation?
                    // Even if leader doesn't have us in nonVotingFollowers yet,
                    // we know our own join time
                    Map<String, Long> joinTimes = context.epochLeaderEngine.getValidatorJoinTimes();
                    Long myJoinTime = joinTimes.get(context.selfUrl);
                    
                    if (myJoinTime != null) {
                        long timeSinceJoin = now - myJoinTime;
                        long probationPeriod = leaderTermSeconds * 1000L; // 300 seconds (1 epoch)
                        
                        if (timeSinceJoin < probationPeriod) {
                            status = "PROBATION";  // Still within probationary period
                        } else {
                            status = "READY";  // Probation ended, fully participating
                        }
                    } else {
                        // No join time recorded (shouldn't happen), assume READY
                        status = "READY";
                    }
                } else {
                    status = "READY";  // Voting member, fully participating
                }
                
                String validatorId = reg != null ? reg.validatorId : extractValidatorId(validatorUrl);
                
                json.append("  {");
                json.append("\"validatorId\":\"").append(validatorId.replace("\"", "\\\"")).append("\",");
                json.append("\"validatorUrl\":\"").append(validatorUrl.replace("\"", "\\\"")).append("\",");
                json.append("\"lastSeen\":").append(lastSeen).append(",");
                json.append("\"status\":\"").append(status).append("\"");
                
                // Add epoch information for probation status
                if ("PROBATION".equals(status) && context.epochLeaderEngine != null) {
                    Map<String, Long> joinTimes = context.epochLeaderEngine.getValidatorJoinTimes();
                    Long joinTime = joinTimes.get(validatorUrl);
                    
                    if (joinTime != null) {
                        // Reuse leaderTermSeconds from above (declared at line 3210)
                        long probationPeriod = leaderTermSeconds * 1000L;
                        long timeSinceJoin = now - joinTime;
                        
                        // Calculate epochs
                        int joinEpoch = (int) (joinTime / (leaderTermSeconds * 1000L));
                        int currentEpoch = context.epochLeaderEngine.getCurrentEpoch();
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
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Failed to get peer list: " + e.getMessage());
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
     * Extract validator ID from URL when no registration exists.
     * Format: http://validator-N:port -> validator-N
     */
    private String extractValidatorId(String validatorUrl) {
        if (validatorUrl == null) return "unknown";
        // Extract hostname from URL
        try {
            URL url = new URL(validatorUrl);
            String host = url.getHost();
            if (host.startsWith("validator-")) {
                return host; // e.g., "validator-1", "validator-2"
            }
            return host;
        } catch (Exception e) {
            return "unknown";
        }
    }
}

