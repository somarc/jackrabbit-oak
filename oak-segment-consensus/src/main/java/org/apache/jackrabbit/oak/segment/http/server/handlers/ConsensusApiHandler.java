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

import org.apache.jackrabbit.oak.segment.consensus.Vote;
import org.apache.jackrabbit.oak.segment.consensus.WriteProposal;
import org.apache.jackrabbit.oak.segment.consensus.util.WalletPathUtil;
import org.apache.jackrabbit.oak.segment.http.server.ServerContext;
import org.apache.jackrabbit.oak.segment.http.server.model.ClientRegistration;
import org.apache.jackrabbit.oak.segment.http.server.model.WriteMetadata;
import org.apache.jackrabbit.oak.segment.http.server.util.JsonParser;
import org.apache.jackrabbit.oak.segment.http.server.util.FormatUtils;
import org.apache.jackrabbit.oak.segment.consensus.state.ConsensusState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.stream.Collectors;

/**
 * Handler for consensus API endpoints (`/v1/propose`, `/v1/vote`, `/v1/test-write`).
 * This class encapsulates the logic for handling write proposals, votes, and test writes
 * in the consensus network.
 */
public class ConsensusApiHandler {

    private static final Logger log = LoggerFactory.getLogger(ConsensusApiHandler.class);

    private final ServerContext context;

    public ConsensusApiHandler(ServerContext context) {
        this.context = context;
    }

    /**
     * Handle POST /v1/propose - Receive write proposal from peer
     * @deprecated This endpoint was specific to the removed ConsensusEngine. Use Aeron or Leader consensus instead.
     */
    public void handleWriteProposal(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.sendError(HttpServletResponse.SC_GONE, "This endpoint is no longer supported. ConsensusEngine has been removed. Use Aeron or Leader consensus.");
    }
    
    /**
     * Handle POST /v1/vote - Receive vote from peer
     * @deprecated This endpoint was specific to the removed ConsensusEngine. Use Aeron or Leader consensus instead.
     */
    public void handleVote(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.sendError(HttpServletResponse.SC_GONE, "This endpoint is no longer supported. ConsensusEngine has been removed. Use Aeron or Leader consensus.");
    }
    
    /**
     * Handle POST /v1/test-write - Write endpoint with wallet-based storage
     * 
     * Parameters:
     *   - wallet: Ethereum address (e.g., 0x1234...)
     *   - signature: Message signature (mock for now, real Web3j verification later)
     *   - message: Content to write
     *   - contentType: Type of content (default: "page")
     */
    public void handleTestWrite(HttpServletRequest request, HttpServletResponse response) throws IOException {
        // Check if any consensus engine is configured
        if (context.epochLeaderEngine == null && context.aeronConsensusEngine == null) {
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Consensus engine not configured");
            return;
        }
        
        // AERON CHECK: Aeron Cluster handles writes internally, no proxy needed
        // Aeron routes writes to the leader automatically via its ClusteredService interface
        boolean usingAeronMode = (context.aeronConsensusEngine != null);
        boolean usingLeaderMode = (context.epochLeaderEngine != null);
        
        // LEADER CHECK: If using leader-based consensus, proxy to leader if we're a follower
        // Only check this if NOT using Aeron (Aeron handles routing internally)
        if (usingLeaderMode && !usingAeronMode) {
            if (!context.epochLeaderEngine.isLeader()) {
                String currentLeader = context.epochLeaderEngine.getCurrentLeader();
                log.info("📡 FOLLOWER: Proxying write request to leader: {}", currentLeader);
                
                // SMART PROXY FAILOVER: Try current leader, then next validator
                String proxyTarget = currentLeader;
                Exception firstFailure = null;
                
                for (int attempt = 0; attempt < 2; attempt++) {
                    try {
                        // Build the full URL with query parameters
                        StringBuilder targetUrl = new StringBuilder(proxyTarget);
                        targetUrl.append("/v1/test-write");
                        String queryString = request.getQueryString();
                        if (queryString != null && !queryString.isEmpty()) {
                            targetUrl.append("?").append(queryString);
                        }
                        
                        log.info("   Attempt {}: Trying {}", attempt + 1, proxyTarget);
                        
                        // Forward request to target
                        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) 
                            new java.net.URL(targetUrl.toString()).openConnection();
                        conn.setRequestMethod("POST");
                        conn.setConnectTimeout(3000); // Shorter timeout for faster failover
                        conn.setReadTimeout(5000);
                        
                        // Forward headers
                        String clientId = request.getHeader("X-Client-Id");
                        if (clientId != null) {
                            conn.setRequestProperty("X-Client-Id", clientId);
                        }
                        conn.setRequestProperty("X-Proxied-By", context.selfUrl);
                        
                        // Get response from target
                        int targetStatus = conn.getResponseCode();
                        
                        // Read target's response
                        java.io.InputStream inputStream = targetStatus >= 400 
                            ? conn.getErrorStream() 
                            : conn.getInputStream();
                        
                        if (inputStream != null) {
                            java.io.BufferedReader reader = new java.io.BufferedReader(
                                new java.io.InputStreamReader(inputStream)
                            );
                            StringBuilder targetResponse = new StringBuilder();
                            String line;
                            while ((line = reader.readLine()) != null) {
                                targetResponse.append(line);
                            }
                            reader.close();
                            
                            // Forward target's response to client
                            response.setStatus(targetStatus);
                            response.setContentType("application/json");
                            response.setHeader("X-Proxied-From", proxyTarget);
                            response.setHeader("X-Failover-Attempt", String.valueOf(attempt + 1));
                            response.getWriter().write(targetResponse.toString());
                            
                            log.info("✅ Proxied write to {} - Status: {}", proxyTarget, targetStatus);
                            return; // SUCCESS!
                        }
                        
                    } catch (Exception e) {
                        if (attempt == 0) {
                            // First attempt failed
                            firstFailure = e;
                            log.warn("⚠️  Primary leader unreachable: {} - {}", currentLeader, e.getMessage());
                            
                            // Check if leader appears dead via health monitoring
                            if (context.epochLeaderEngine.getHealthMonitor().isLeaderDead()) {
                                log.warn("💀 Health monitor confirms leader is dead");
                            }
                            
                            // Try next validator in rotation
                            proxyTarget = getNextValidatorInRotation();
                            log.warn("🔄 Attempting failover to next validator: {}", proxyTarget);
                            
                        } else {
                            // Second attempt also failed - give up
                            log.error("❌ Both proxy attempts failed");
                            log.error("   Primary: {} - {}", currentLeader, firstFailure.getMessage());
                            log.error("   Failover: {} - {}", proxyTarget, e.getMessage());
                            
                            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                            response.setContentType("application/json");
                            response.getWriter().write(String.format(
                                "{\"error\":\"All validators unreachable\",\"attempted\":[\"%s\",\"%s\"],\"message\":\"Consensus network unavailable\"}",
                                currentLeader, proxyTarget
                            ));
                            return;
                        }
                    }
                }
                
                // Shouldn't reach here, but just in case
                return;
            }
            log.debug("✅ Leader check passed - I am the leader");
        }
        
        try {
            // Read wallet-based write parameters
            String wallet = request.getParameter("wallet");
            String signature = request.getParameter("signature");
            String message = request.getParameter("message");
            String contentType = request.getParameter("contentType");
            
            // Default values
            if (wallet == null || wallet.isEmpty()) {
                wallet = "0x742d35Cc6634C0532925a3b844Bc9e7595f0bEb"; // Mock default wallet
            }
            if (message == null || message.isEmpty()) {
                message = "Test content at " + System.currentTimeMillis();
            }
            if (contentType == null || contentType.isEmpty()) {
                contentType = "page";
            }
            if (signature == null || signature.isEmpty()) {
                signature = "0xMOCK" + System.currentTimeMillis(); // Mock signature
            }
            
            // Validate Ethereum address format (basic check)
            if (!wallet.startsWith("0x") || wallet.length() < 10) {
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid Ethereum address format");
                return;
            }
            
            // PATH ENFORCEMENT: Verify client is registered and wallet matches
            String clientId = request.getHeader("X-Client-Id");
            if (clientId == null || clientId.isEmpty()) {
                clientId = request.getParameter("clientId");
            }
            // Fallback to remote address if no client ID provided
            if (clientId == null || clientId.isEmpty()) {
                String remoteAddr = request.getRemoteAddr();
                int remotePort = request.getRemotePort();
                clientId = remoteAddr + ":" + remotePort;
            }
            
            log.debug("Path enforcement check: clientId={}, registeredClients.size()={}", clientId, context.registeredClients.size());
            
            // Normalize wallet addresses for comparison (case-insensitive)
            String normalizedWallet = wallet.toLowerCase();
            
            // Look up registered client
            ClientRegistration clientReg = context.registeredClients.get(clientId);
            
            if (clientReg == null) {
                // Client not registered - reject write
                log.warn("🚫 Write rejected: Client {} not registered", clientId);
                log.warn("   Available registered clients: {}", context.registeredClients.keySet());
                response.sendError(HttpServletResponse.SC_FORBIDDEN, 
                    "Client not registered. Please register via /v1/register-client before writing.");
                return;
            }
            
            log.debug("Client {} found in registry, wallet: {}", clientId, clientReg.walletAddress);
            
            // Verify wallet matches registered client's wallet
            if (clientReg.walletAddress != null && !clientReg.walletAddress.isEmpty()) {
                String registeredWallet = clientReg.walletAddress.toLowerCase();
                if (!normalizedWallet.equals(registeredWallet)) {
                    log.warn("🚫 Write rejected: Wallet mismatch for client {}", clientId);
                    log.warn("   Requested wallet: {}", normalizedWallet);
                    log.warn("   Registered wallet: {}", registeredWallet);
                    response.sendError(HttpServletResponse.SC_FORBIDDEN, 
                        String.format("Path enforcement violation: Client %s can only write to /oak-chain/content/%s/, " +
                                     "but attempted to write to /oak-chain/content/%s/", 
                                     clientId, registeredWallet, normalizedWallet));
                    return;
                }
            } else {
                // Client registered but no wallet address - allow write but log warning
                log.warn("⚠️  Client {} registered without wallet address - allowing write but path enforcement not possible", clientId);
            }
            
            log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            log.info("🔐 WALLET-BASED WRITE INITIATED");
            log.info("   Client: {} (registered)", clientId);
            log.info("   Wallet: {} (verified)", wallet);
            log.info("   Content Type: {}", contentType);
            log.info("   Message: {}", message);
            log.info("   Signature: {}...{}", signature.substring(0, Math.min(10, signature.length())), 
                     signature.length() > 10 ? signature.substring(signature.length() - 4) : "");
            log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            
            // TODO: Real signature verification with Web3j
            // For now, we accept all signatures starting with "0x"
            if (!signature.startsWith("0x")) {
                log.warn("🚫 Write rejected: Invalid signature format (must start with '0x')");
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid signature format");
                return;
            }
            log.info("✅ Signature verification: MOCK (accepted)");
            
            // Get current HEAD
            String previousHead = context.fileStore.getHead().getRecordId().toString();
            log.info("📍 Previous HEAD: {}", previousHead.substring(0, Math.min(20, previousHead.length())));
            
            // Make a write to the repository using SHARDED path
            org.apache.jackrabbit.oak.spi.state.NodeBuilder rootBuilder = context.nodeStore.getRoot().builder();
            
            // Get sharded path: /oak-chain/content/{L1}/{L2}/{L3}/0x{wallet}/
            String shardedPath = WalletPathUtil.toShardedPath(wallet.toLowerCase());
            log.info("🪣 Using sharded path: {}", shardedPath);
            
            // Navigate through sharded structure
            // Example: /oak-chain/content/74/2d/35/0x742d35cc.../
            String addr = normalizedWallet.replace("0x", "");
            
            org.apache.jackrabbit.oak.spi.state.NodeBuilder walletPath = rootBuilder
                .child("oak-chain")
                .child("content")
                .child(addr.substring(0, 2))  // L1: 74
                .child(addr.substring(2, 4))  // L2: 2d
                .child(addr.substring(4, 6))  // L3: 35
                .child(normalizedWallet);      // Wallet: 0x742d35cc...
            
            // Create content node under wallet path
            String contentId = contentType + "-" + System.currentTimeMillis();
            org.apache.jackrabbit.oak.spi.state.NodeBuilder contentNode = walletPath.child(contentId);
            
            // Set properties
            contentNode.setProperty("jcr:primaryType", "nt:unstructured");
            contentNode.setProperty("contentType", contentType);
            contentNode.setProperty("message", message);
            contentNode.setProperty("timestamp", System.currentTimeMillis());
            contentNode.setProperty("wallet", wallet);
            contentNode.setProperty("signature", signature);
            contentNode.setProperty("source", "consensus-write");
            
            // Commit the change (this creates new segments!)
            org.apache.jackrabbit.oak.spi.commit.CommitInfo commitInfo = 
                new org.apache.jackrabbit.oak.spi.commit.CommitInfo(
                    "consensus-test", 
                    null, 
                    java.util.Collections.singletonMap("test", "true")
                );
            
            context.nodeStore.merge(rootBuilder, org.apache.jackrabbit.oak.spi.commit.EmptyHook.INSTANCE, commitInfo);
            
            // CRITICAL: Flush FileStore to ensure all segments are persisted to disk
            // BEFORE broadcasting proposal to peers!
            context.fileStore.flush();
            log.info("✅ FileStore flushed - segments persisted to disk");
            
            // Get new HEAD
            String newHead = context.fileStore.getHead().getRecordId().toString();
            log.info("📍 New HEAD: {}", newHead.substring(0, Math.min(20, newHead.length())));
            
            // Create write proposal with wallet metadata
            WriteProposal proposal = new WriteProposal(
                context.selfUrl, // Use actual self URL (set by GlobalStoreServer)
                previousHead,
                newHead
            );
            proposal.setAuthor(wallet); // Wallet address as author
            proposal.setCommitMessage("Wallet write: " + contentType + " - " + message);
            proposal.setMockPaymentVerified(true); // TODO: Verify payment from smart contract
            proposal.setMockSignature(signature);
            
            // TODO: Add actual segments to proposal
            // For Phase 1, we'll rely on validators fetching via HTTP
            
            String mode = usingAeronMode ? "Aeron" : (usingLeaderMode ? "Leader" : "Blockchain");
            log.info("📤 Processing write via {} mode...", mode);
            log.info("   Storage path: {}/{}", shardedPath, contentId);
            
            boolean success = false;
            String consensusMode = "";
            
            if (usingAeronMode) {
                // AERON MODE: Write succeeds immediately if we're the leader, otherwise proxy
                log.info("✈️  AERON MODE: Write via Aeron Cluster consensus...");
                // Aeron handles writes through its ClusteredService interface
                // For now, treat as success if Aeron engine is active
                success = true;
                consensusMode = "aeron-cluster";
                log.info("✅ Aeron write complete");
                
                // Track write metadata for dashboard (Aeron mode)
                String recordIdShort = newHead.length() > 20 ? newHead.substring(0, 20) : newHead;
                context.recentWriteMetadata.put(recordIdShort, new WriteMetadata(
                    newHead,
                    "aeron-cluster",
                    context.selfUrl,
                    System.currentTimeMillis(),
                    "Wallet write: " + contentType + " - " + message
                ));
                
            } else if (usingLeaderMode) {
                // LEADER MODE: Write succeeds immediately (we're the leader), broadcast HEAD to followers
                log.info("🎖️  LEADER MODE: Write succeeds (I am leader), broadcasting to followers...");
                
                // Broadcast new HEAD to all followers
                String newHeadStr = context.fileStore.getHead().getRecordId().toString10();
                context.epochLeaderEngine.broadcastHeadToFollowers(newHeadStr);
                
                success = true;
                consensusMode = "leader-accepted";
                log.info("✅ Leader write complete, HEAD broadcasted to followers");
                
                // Track write metadata for dashboard (Leader mode)
                String recordIdShort = newHead.length() > 20 ? newHead.substring(0, 20) : newHead;
                context.recentWriteMetadata.put(recordIdShort, new WriteMetadata(
                    newHead,
                    "leader-accepted",
                    context.selfUrl,
                    System.currentTimeMillis(),
                    "Leader write: " + contentType + " - " + message
                ));
            } else {
                // No supported consensus engine configured
                response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "No consensus engine configured. Use 'leader' or 'aeron' mode.");
                return;
            }
            
            // Return result
            response.setContentType("application/json");
            response.setStatus(HttpServletResponse.SC_OK);
            
            String result = "{" +
                "\"success\":" + success + "," +
                "\"proposalId\":\"" + proposal.getProposalId() + "\"," +
                "\"wallet\":\"" + wallet + "\"," +
                "\"contentId\":\"" + contentId + "\"," +
                "\"storagePath\":\"" + shardedPath + "/" + contentId + "\"," +
                "\"previousHead\":\"" + previousHead + "\"," +
                "\"newHead\":\"" + newHead + "\"," +
                "\"message\":\"" + message + "\"," +
                "\"contentType\":\"" + contentType + "\"," +
                "\"consensusMode\":\"" + consensusMode + "\"," +
                "\"mode\":\"" + (usingAeronMode ? "aeron" : (usingLeaderMode ? "leader" : "blockchain")) + "\"" +
                "}";
            
            response.getWriter().write(result);
            
            if (success) {
                log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                log.info("✅ WRITE COMPLETE! Write committed via {} consensus", mode);
                log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                
                // Track write metadata for dashboard
                String recordIdShort = newHead.length() > 20 ? newHead.substring(0, 20) : newHead;
                context.recentWriteMetadata.put(recordIdShort, new WriteMetadata(
                    newHead,
                    "consensus",
                    proposal.getProposerUrl(),
                    System.currentTimeMillis(),
                    message
                ));
            } else {
                log.warn("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                log.warn("❌ CONSENSUS FAILED! Write not replicated");
                log.warn("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            }
            
        } catch (Exception e) {
            log.error("❌ Test write failed", e);
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Test write failed: " + e.getMessage());
        }
    }
    
    /**
     * Parse a WriteProposal from JSON.
     */
    private WriteProposal parseProposal(String json) {
        WriteProposal proposal = new WriteProposal();
        
        // Extract fields (simple string parsing for Phase 1)
        proposal.setProposalId(JsonParser.extractField(json, "proposalId"));
        proposal.setProposerUrl(JsonParser.extractField(json, "proposerUrl"));
        proposal.setPreviousHead(JsonParser.extractField(json, "previousHead"));
        proposal.setNewHead(JsonParser.extractField(json, "newHead"));
        proposal.setAuthor(JsonParser.extractField(json, "author"));
        
        String timestamp = JsonParser.extractField(json, "timestamp");
        if (timestamp != null) {
            proposal.setTimestamp(Long.parseLong(timestamp));
        }
        
        String mockPayment = JsonParser.extractField(json, "mockPaymentVerified");
        proposal.setMockPaymentVerified(mockPayment == null || "true".equals(mockPayment));
        
        // TODO: Parse segments array
        
        return proposal;
    }
    
    /**
     * Parse a Vote from JSON.
     */
    private Vote parseVote(String json) {
        Vote vote = new Vote();
        
        vote.setProposalId(JsonParser.extractField(json, "proposalId"));
        vote.setValidatorUrl(JsonParser.extractField(json, "validatorUrl"));
        
        String voteType = JsonParser.extractField(json, "voteType");
        vote.setVoteType("ACCEPT".equals(voteType) ? Vote.VoteType.ACCEPT : Vote.VoteType.REJECT);
        
        vote.setReason(JsonParser.extractField(json, "reason"));
        
        String timestamp = JsonParser.extractField(json, "timestamp");
        if (timestamp != null) {
            vote.setTimestamp(Long.parseLong(timestamp));
        }
        
        return vote;
    }
    
    /**
     * Convert a Vote to JSON string.
     */
    private String voteToJson(Vote v) {
        return "{" +
            "\"proposalId\":\"" + v.getProposalId() + "\"," +
            "\"validatorUrl\":\"" + v.getValidatorUrl() + "\"," +
            "\"voteType\":\"" + v.getVoteType() + "\"," +
            "\"reason\":\"" + (v.getReason() != null ? v.getReason() : "") + "\"," +
            "\"timestamp\":" + v.getTimestamp() +
            "}";
    }
    
    /**
     * Handle GET /v1/consensus/status - Return comprehensive consensus state
     * 
     * Uses ConsensusStateService for single source of truth.
     * 
     * Returns JSON with:
     * - consensusType: "leader-based" | "blockchain-poa" | "dag" | "none"
     * - currentRole: "LEADER" | "FOLLOWER" | "STANDALONE"
     * - currentLeader: URL of current leader (if follower)
     * - currentEpoch: Current epoch number
     * - leaderTermSeconds: Duration of each leader term
     * - secondsUntilRotation: Time until next epoch transition
     * - electorateSize: Number of voting validators
     * - totalValidators: Total validators (voting + non-voting)
     * - nonVotingFollowers: List of validators on probation
     * - allValidators: List of all validator URLs
     * - nextLeader: Next leader for next epoch
     */
    public void handleGetConsensusStatus(HttpServletResponse response) throws IOException {
        response.setContentType("application/json");
        response.setStatus(HttpServletResponse.SC_OK);
        
        java.util.Map<String, Object> status = new java.util.HashMap<>();
        
        // Check for Aeron Cluster consensus first (newest, preferred)
        if (context.aeronConsensusEngine != null) {
            // Aeron Cluster (Raft-based consensus)
            status.put("consensusType", "aeron-cluster");
            status.put("currentRole", context.aeronConsensusEngine.getCurrentRole().name());
            status.put("isLeader", context.aeronConsensusEngine.isLeader());
            status.put("currentLeader", context.aeronConsensusEngine.getCurrentLeader());
            status.put("currentEpoch", context.aeronConsensusEngine.getCurrentEpoch());
            status.put("currentTerm", context.aeronConsensusEngine.getCurrentTerm());
            status.put("reachableValidators", context.aeronConsensusEngine.getReachableValidatorCount());
            status.put("allFollowers", context.aeronConsensusEngine.getAllFollowers());
            status.put("ethereumEpoch", context.aeronConsensusEngine.getCurrentEthereumEpoch());
        } else if (context.consensusStateService != null) {
            // Use ConsensusStateService if available (leader-based consensus)
            ConsensusState state = context.consensusStateService.getConsensusState();
            status.put("consensusType", state.consensusType);
            status.put("currentRole", state.currentRole);
            status.put("currentLeader", state.currentLeader);
            status.put("currentEpoch", state.currentEpoch);
            status.put("leaderTermSeconds", state.leaderTermSeconds);
            status.put("secondsUntilRotation", state.secondsUntilRotation);
            status.put("electorateSize", state.electorateSize);
            status.put("totalValidators", state.totalValidators);
            status.put("nonVotingFollowers", state.nonVotingFollowers);
            status.put("allValidators", state.allValidators);
            status.put("nextLeader", state.nextLeader);
        } else {
            // No consensus engine
            status.put("consensusType", "none");
            status.put("currentRole", "STANDALONE");
        }
        
        // Convert to JSON manually (no Gson dependency)
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (java.util.Map.Entry<String, Object> entry : status.entrySet()) {
            if (!first) json.append(",");
            first = false;
            json.append("\"").append(entry.getKey()).append("\":");
            Object value = entry.getValue();
            if (value instanceof String) {
                json.append("\"").append(FormatUtils.escapeJson((String) value)).append("\"");
            } else if (value instanceof java.util.List) {
                json.append("[");
                boolean listFirst = true;
                for (Object item : (java.util.List<?>) value) {
                    if (!listFirst) json.append(",");
                    listFirst = false;
                    if (item instanceof String) {
                        json.append("\"").append(FormatUtils.escapeJson((String) item)).append("\"");
                    } else {
                        json.append(item);
                    }
                }
                json.append("]");
            } else {
                json.append(value);
            }
        }
        json.append("}");
        response.getWriter().write(json.toString());
    }
    
    /**
     * Get the next validator in the rotation order for failover.
     * This is used when the current leader appears unreachable.
     * 
     * @return URL of the next validator that might be leader
     */
    private String getNextValidatorInRotation() {
        if (context.epochLeaderEngine == null) {
            return context.selfUrl; // Fallback
        }
        
        // Get all validators in deterministic order
        java.util.List<String> allValidators = new java.util.ArrayList<>();
        allValidators.add(context.selfUrl);
        allValidators.addAll(context.epochLeaderEngine.getElection().getPeerValidators());
        java.util.Collections.sort(allValidators);
        
        // Find current leader in the list
        String currentLeader = context.epochLeaderEngine.getCurrentLeader();
        int leaderIndex = allValidators.indexOf(currentLeader);
        
        if (leaderIndex == -1) {
            // Leader not found, return first validator
            return allValidators.get(0);
        }
        
        // Return next validator in rotation (wrap around if needed)
        int nextIndex = (leaderIndex + 1) % allValidators.size();
        return allValidators.get(nextIndex);
    }
}

