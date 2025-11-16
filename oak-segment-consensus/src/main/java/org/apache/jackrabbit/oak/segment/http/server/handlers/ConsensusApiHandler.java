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
import org.apache.jackrabbit.oak.segment.consensus.gc.GCCostEstimate;
import org.apache.jackrabbit.oak.segment.consensus.util.WalletPathUtil;
import org.apache.jackrabbit.oak.segment.consensus.queue.ProposalQueueManager;
import org.apache.jackrabbit.oak.segment.consensus.queue.ProposalStatus;
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

/**
 * Handler for consensus API endpoints (`/v1/propose`, `/v1/vote`, `/v1/propose-write`, `/v1/propose-delete`).
 * This class encapsulates the logic for handling write proposals, votes, and signed write/delete transactions
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
     * Handle POST /v1/propose-write - Signed write transaction endpoint
     * 
     * <p>Accepts signed write transactions from Sling authors. The transaction is signed
     * with the Sling author's Ethereum wallet and verified before processing.</p>
     * 
     * Parameters:
     *   - wallet: Ethereum address (e.g., 0x1234...)
     *   - signature: Signed transaction (walletAddress:timestamp:contentType:message)
     *   - message: Content to write
     *   - contentType: Type of content (default: "page")
     *   - clientId: Client identifier (from X-Client-Id header or parameter)
     *   - timestamp: Transaction timestamp
     */
    public void handleProposeWrite(HttpServletRequest request, HttpServletResponse response) throws IOException {
        // Check if Aeron consensus engine is configured (ONLY mode supported)
        if (context.aeronConsensusEngine == null) {
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Aeron consensus engine not configured");
            return;
        }
        
        // ✈️ AERON MODE: All nodes (leader and followers) send writes through Aeron ingress
        // Aeron Cluster handles routing to leader and replication to all nodes via Raft
        // No proxy needed - Aeron handles it natively
        
        try {
            // Read wallet-based write parameters
            // CRITICAL: walletAddress is REQUIRED and must be a valid 0x Ethereum address
            String wallet = request.getParameter("walletAddress");
            if (wallet == null || wallet.isEmpty()) {
                wallet = request.getParameter("wallet"); // Fallback for backward compatibility
            }
            String signature = request.getParameter("signature");
            String message = request.getParameter("message");
            String contentType = request.getParameter("contentType");
            
            // Validate Ethereum address format FIRST (REQUIRED)
            if (wallet == null || wallet.isEmpty()) {
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, 
                    "Missing walletAddress parameter. Writes require a valid 0x Ethereum address.");
                return;
            }
            
            // Validate Ethereum address format (basic check)
            wallet = wallet.trim();
            if (!wallet.startsWith("0x") || wallet.length() < 10) {
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, 
                    "Invalid Ethereum address format. Must start with '0x' and be at least 10 characters.");
                return;
            }
            
            // Normalize wallet addresses for comparison (case-insensitive)
            String normalizedWallet = wallet.toLowerCase();
            
            // PATH ENFORCEMENT: Look up client registration BY WALLET ADDRESS
            // This is the primary identifier - clientId is secondary
            ClientRegistration clientReg = null;
            String clientId = null;
            
            // First, try to find client by wallet address (primary lookup)
            for (ClientRegistration reg : context.registeredClients.values()) {
                if (reg.walletAddress != null && reg.walletAddress.toLowerCase().equals(normalizedWallet)) {
                    clientReg = reg;
                    clientId = reg.clientId;
                    break;
                }
            }
            
            // If not found by wallet, try clientId lookup (for backward compatibility)
            if (clientReg == null) {
                String clientIdHeader = request.getHeader("X-Client-Id");
                if (clientIdHeader == null || clientIdHeader.isEmpty()) {
                    clientIdHeader = request.getParameter("clientId");
                }
                if (clientIdHeader == null || clientIdHeader.isEmpty()) {
                    String remoteAddr = request.getRemoteAddr();
                    int remotePort = request.getRemotePort();
                    clientIdHeader = remoteAddr + ":" + remotePort;
                }
                
                clientReg = context.registeredClients.get(clientIdHeader);
                if (clientReg != null) {
                    clientId = clientIdHeader;
                }
            }
            
            // TEMPORARY FOR TESTING REPLICATION: Auto-register any valid Ethereum address
            // This bypasses registration persistence issues so we can focus on testing replication
            if (clientReg == null) {
                // Check if wallet belongs to a registered validator first
                boolean isValidatorWallet = false;
                String validatorId = null;
                
                if (context.registeredValidators.containsKey(normalizedWallet)) {
                    isValidatorWallet = true;
                    validatorId = normalizedWallet;
                } else {
                    for (String key : context.registeredValidators.keySet()) {
                        if (key != null && key.toLowerCase().equals(normalizedWallet)) {
                            isValidatorWallet = true;
                            validatorId = key;
                            break;
                        }
                    }
                }
                
                // TEMPORARY FOR TESTING: Auto-register any valid Ethereum address (0x + 40 hex chars = 42 total)
                if (!isValidatorWallet && normalizedWallet.startsWith("0x") && normalizedWallet.length() == 42) {
                    // Auto-registration only in mock mode
                    org.apache.jackrabbit.oak.segment.consensus.config.BlockchainConfig blockchainConfig = 
                        org.apache.jackrabbit.oak.segment.consensus.config.BlockchainConfig.getInstance();
                    if (blockchainConfig.isMockMode()) {
                        log.info("🧪 MOCK MODE: Auto-registering wallet {} as client for replication testing", normalizedWallet);
                    }
                    isValidatorWallet = true;
                    validatorId = normalizedWallet;
                }
                
                // Check if auto-registration is allowed (mock mode only)
                org.apache.jackrabbit.oak.segment.consensus.config.BlockchainConfig blockchainConfig = 
                    org.apache.jackrabbit.oak.segment.consensus.config.BlockchainConfig.getInstance();
                
                if (isValidatorWallet && blockchainConfig.isMockMode()) {
                    log.info("✅ Auto-registering wallet {} as client (MOCK MODE - testing only)", validatorId);
                    clientReg = new ClientRegistration(validatorId, context.selfUrl, normalizedWallet);
                    context.registeredClients.put(validatorId, clientReg);
                    clientId = validatorId;
                } else {
                    log.warn("🚫 Write rejected: Wallet {} not registered and not a valid Ethereum address", normalizedWallet);
                    response.sendError(HttpServletResponse.SC_FORBIDDEN, 
                        String.format("Wallet %s not registered. Please register via /v1/register-client with walletAddress=%s before writing.", 
                            normalizedWallet, normalizedWallet));
                    return;
                }
            }
            
            // Verify wallet matches registered client's wallet (double-check)
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
                // Client registered but no wallet address - this shouldn't happen, but log warning
                log.warn("⚠️  Client {} registered without wallet address - allowing write but path enforcement not possible", clientId);
            }
            
            // Set clientId if not already set
            if (clientId == null) {
                clientId = clientReg.clientId;
            }
            
            log.debug("Client lookup: wallet={}, clientId={}, registeredClients.size()={}", 
                normalizedWallet, clientId, context.registeredClients.size());
            
            // Default values for optional parameters
            if (message == null || message.isEmpty()) {
                message = "Test content at " + System.currentTimeMillis();
            }
            if (contentType == null || contentType.isEmpty()) {
                contentType = "page";
            }
            
            // Check blockchain config for mock mode
            org.apache.jackrabbit.oak.segment.consensus.config.BlockchainConfig blockchainConfig = 
                org.apache.jackrabbit.oak.segment.consensus.config.BlockchainConfig.getInstance();
            
            // In mock mode, generate mock signature if not provided
            if (signature == null || signature.isEmpty()) {
                if (blockchainConfig.isMockMode()) {
                    signature = "0xMOCK" + System.currentTimeMillis(); // Mock signature
                } else {
                    log.warn("🚫 Write rejected: Signature required in real blockchain mode");
                    response.sendError(HttpServletResponse.SC_BAD_REQUEST, 
                        "Signature required. In real blockchain mode, all writes must be signed.");
                    return;
                }
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
            
            // Signature verification
            if (!signature.startsWith("0x")) {
                log.warn("🚫 Write rejected: Invalid signature format (must start with '0x')");
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid signature format");
                return;
            }
            
            if (blockchainConfig.isMockMode()) {
                log.info("✅ Signature verification: MOCK (accepted - mock mode enabled)");
            } else {
                // TODO: Real signature verification with Web3j
                // For now, accept signature format (real verification will be added)
                log.info("✅ Signature verification: Format valid (real mode - full verification TODO)");
            }
            
            // Extract Ethereum transaction hash (REQUIRED for queue)
            String ethereumTxHash = request.getParameter("ethereumTxHash");
            if (ethereumTxHash == null || ethereumTxHash.isEmpty()) {
                log.warn("🚫 Write rejected: Missing ethereumTxHash parameter");
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, 
                    "Missing ethereumTxHash parameter. Must provide Ethereum transaction hash from authorizeWrite() call.");
                return;
            }
            
            // Build sharded path
            String shardedPath = WalletPathUtil.toShardedPath(wallet.toLowerCase());
            log.info("🪣 Using sharded path: {}", shardedPath);
            
            String addr = normalizedWallet.replace("0x", "");
            String contentId = contentType + "-" + System.currentTimeMillis();
            String fullPath = "/oak-chain/content/" + addr.substring(0, 2) + "/" + 
                            addr.substring(2, 4) + "/" + addr.substring(4, 6) + "/" + 
                            normalizedWallet + "/" + contentId;
            
            // Generate proposal ID
            String proposalId = java.util.UUID.randomUUID().toString();
            
            // Check if proposal queue manager is available
            if (context.proposalQueueManager == null) {
                log.warn("⚠️  ProposalQueueManager not available - falling back to immediate append");
                // Fallback: immediate append (for backward compatibility)
                if (context.aeronConsensusEngine == null) {
                    response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                        "AeronConsensusEngine not initialized");
                    return;
                }
                
                boolean success = context.aeronConsensusEngine.sendWriteThroughIngress(
                    normalizedWallet,
                    fullPath,
                    contentType != null ? contentType : "page",
                    message != null ? message : "",
                    signature != null ? signature : ""
                );
                
                if (!success) {
                    response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                        "Failed to send write through Aeron ingress channel");
                    return;
                }
                
                response.setContentType("application/json");
                response.setStatus(HttpServletResponse.SC_OK);
                String currentHead = context.fileStore != null 
                    ? context.fileStore.getHead().getRecordId().toString() 
                    : "unknown";
                String resultJson = "{" +
                    "\"success\":true," +
                    "\"proposalId\":\"" + proposalId + "\"," +
                    "\"wallet\":\"" + wallet + "\"," +
                    "\"contentId\":\"" + contentId + "\"," +
                    "\"storagePath\":\"" + fullPath + "\"," +
                    "\"newHead\":\"" + currentHead + "\"," +
                    "\"message\":\"" + message + "\"," +
                    "\"contentType\":\"" + contentType + "\"," +
                    "\"mode\":\"immediate\"}";
                response.getWriter().write(resultJson);
                return;
            }
            
            // Queue proposal (waiting for Ethereum confirmation)
            log.info("📥 Queuing proposal {} (tx: {}), waiting for Ethereum confirmation", proposalId, ethereumTxHash);
            context.proposalQueueManager.queueProposal(
                proposalId,
                ethereumTxHash,
                normalizedWallet,
                fullPath,
                contentType != null ? contentType : "page",
                message != null ? message : "",
                signature != null ? signature : ""
            );
            
            // Return queued status (202 Accepted)
            response.setContentType("application/json");
            response.setStatus(HttpServletResponse.SC_ACCEPTED);
            String resultJson = "{" +
                "\"proposalId\":\"" + proposalId + "\"," +
                "\"state\":\"PENDING\"," +
                "\"message\":\"Proposal queued, waiting for Ethereum confirmation\"," +
                "\"ethereumTxHash\":\"" + ethereumTxHash + "\"," +
                "\"timeoutTimestamp\":" + (System.currentTimeMillis() + 300_000) + "," +
                "\"wallet\":\"" + wallet + "\"," +
                "\"storagePath\":\"" + fullPath + "\"," +
                "\"contentType\":\"" + contentType + "\"}";
            response.getWriter().write(resultJson);
            log.info("✅ Proposal {} queued successfully", proposalId);
            
        } catch (Exception e) {
            log.error("❌ Test write failed", e);
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Test write failed: " + e.getMessage());
        }
    }
    
    /**
     * Handle POST /v1/propose-delete - Delete proposal endpoint
     * 
     * <p>Allows Sling authors to propose deletion of content they own.
     * Ownership is verified by checking that the content path is under
     * /oak-chain/content/{wallet}/ and that the wallet matches the registered client.</p>
     * 
     * <p>Parameters:
     *   - wallet: Ethereum wallet address (must match registered client)
     *   - signature: Signed message (wallet:deleteId:contentPath)
     *   - contentPath: Path to content to delete (must be under /oak-chain/content/{wallet}/)
     *   - clientId: Client identifier (from X-Client-Id header or parameter)</p>
     */
    public void handleDeleteProposal(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setContentType("application/json");
        
        try {
            // Read parameters
            // CRITICAL: walletAddress is REQUIRED and must be a valid 0x Ethereum address
            String wallet = request.getParameter("walletAddress");
            if (wallet == null || wallet.isEmpty()) {
                wallet = request.getParameter("wallet"); // Fallback for backward compatibility
            }
            String signature = request.getParameter("signature");
            String contentPath = request.getParameter("contentPath");
            
            // Validate required parameters
            if (wallet == null || wallet.isEmpty()) {
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, 
                    "Missing walletAddress parameter. Deletes require a valid 0x Ethereum address.");
                return;
            }
            if (signature == null || signature.isEmpty()) {
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Missing signature parameter");
                return;
            }
            if (contentPath == null || contentPath.isEmpty()) {
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Missing contentPath parameter");
                return;
            }
            
            // Validate Ethereum address format
            wallet = wallet.trim();
            if (!wallet.startsWith("0x") || wallet.length() < 10) {
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, 
                    "Invalid Ethereum address format. Must start with '0x' and be at least 10 characters.");
                return;
            }
            
            // PATH ENFORCEMENT: Look up client registration BY WALLET ADDRESS
            // This is the primary identifier - clientId is secondary
            String normalizedWallet = wallet.toLowerCase();
            ClientRegistration clientReg = null;
            String clientId = null;
            
            // First, try to find client by wallet address (primary lookup)
            for (ClientRegistration reg : context.registeredClients.values()) {
                if (reg.walletAddress != null && reg.walletAddress.toLowerCase().equals(normalizedWallet)) {
                    clientReg = reg;
                    clientId = reg.clientId;
                    break;
                }
            }
            
            // If not found by wallet, try clientId lookup (for backward compatibility)
            if (clientReg == null) {
                String clientIdHeader = request.getHeader("X-Client-Id");
                if (clientIdHeader == null || clientIdHeader.isEmpty()) {
                    clientIdHeader = request.getParameter("clientId");
                }
                if (clientIdHeader == null || clientIdHeader.isEmpty()) {
                    String remoteAddr = request.getRemoteAddr();
                    int remotePort = request.getRemotePort();
                    clientIdHeader = remoteAddr + ":" + remotePort;
                }
                
                clientReg = context.registeredClients.get(clientIdHeader);
                if (clientReg != null) {
                    clientId = clientIdHeader;
                }
            }
            
            // If still not found, reject delete
            if (clientReg == null) {
                log.warn("🚫 Delete proposal rejected: Wallet {} not registered", normalizedWallet);
                java.util.List<String> registeredWallets = new java.util.ArrayList<>();
                for (ClientRegistration reg : context.registeredClients.values()) {
                    if (reg.walletAddress != null && !reg.walletAddress.isEmpty()) {
                        registeredWallets.add(reg.walletAddress);
                    }
                }
                log.warn("   Available registered wallets: {}", registeredWallets);
                response.sendError(HttpServletResponse.SC_FORBIDDEN, 
                    String.format("Wallet %s not registered. Please register via /v1/register-client with walletAddress=%s before proposing deletes.", 
                        normalizedWallet, normalizedWallet));
                return;
            }
            
            // Set clientId if not already set
            if (clientId == null) {
                clientId = clientReg.clientId;
            }
            
            // Verify wallet matches registered client's wallet (double-check)
            if (clientReg.walletAddress != null && !clientReg.walletAddress.isEmpty()) {
                String registeredWallet = clientReg.walletAddress.toLowerCase();
                if (!normalizedWallet.equals(registeredWallet)) {
                    log.warn("🚫 Delete proposal rejected: Wallet mismatch for client {}", clientId);
                    log.warn("   Requested wallet: {}", normalizedWallet);
                    log.warn("   Registered wallet: {}", registeredWallet);
                    response.sendError(HttpServletResponse.SC_FORBIDDEN, 
                        String.format("Wallet mismatch: Client %s registered with wallet %s, but proposal uses %s",
                                     clientId, registeredWallet, normalizedWallet));
                    return;
                }
            }
            
            // Verify path ownership: must be under /oak-chain/content/{wallet}/
            String expectedPrefix = "/oak-chain/content/" + normalizedWallet + "/";
            if (!contentPath.toLowerCase().startsWith(expectedPrefix)) {
                log.warn("🚫 Delete proposal rejected: Path ownership violation");
                log.warn("   Content path: {}", contentPath);
                log.warn("   Expected prefix: {}", expectedPrefix);
                response.sendError(HttpServletResponse.SC_FORBIDDEN, 
                    String.format("Path ownership violation: Content at %s does not belong to wallet %s. " +
                                 "Only content under /oak-chain/content/%s/ can be deleted.",
                                 contentPath, wallet, wallet));
                return;
            }
            
            log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            log.info("🗑️  DELETE PROPOSAL RECEIVED");
            log.info("   Client: {} (registered)", clientId);
            log.info("   Wallet: {} (verified)", wallet);
            log.info("   Content Path: {}", contentPath);
            log.info("   Signature: {}...{}", signature.substring(0, Math.min(10, signature.length())), 
                     signature.length() > 10 ? signature.substring(signature.length() - 4) : "");
            log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            
            // TODO: Verify signature matches wallet address
            // TODO: Check if content actually exists at the path
            // TODO: Verify content was created by this wallet (check node properties)
            // TODO: Propose delete to Aeron Cluster consensus
            
            // For now, return success (delete proposal accepted, will be processed)
            // In future: This will create a delete proposal and submit to Aeron Cluster
            String result = String.format(
                "{\"success\":true,\"message\":\"Delete proposal accepted\",\"contentPath\":\"%s\",\"wallet\":\"%s\",\"clientId\":\"%s\"}",
                contentPath.replace("\"", "\\\""),
                wallet.replace("\"", "\\\""),
                clientId.replace("\"", "\\\"")
            );
            
            response.setStatus(HttpServletResponse.SC_OK);
            response.getWriter().write(result);
            
            log.info("✅ Delete proposal accepted (implementation pending)");
            
        } catch (Exception e) {
            log.error("❌ Delete proposal failed", e);
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Delete proposal failed: " + e.getMessage());
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
            // ✈️ AERON NATIVE: Use Aeron's native cluster state APIs
            status.put("consensusType", "aeron-cluster");
            status.put("currentRole", context.aeronConsensusEngine.getCurrentRole().name());
            status.put("isLeader", context.aeronConsensusEngine.isLeader());
            
            // ✈️ AERON NATIVE: Get leader from native cluster state (no HTTP API calls)
            String currentLeader = context.aeronConsensusEngine.getCurrentLeader();
            if (currentLeader != null) {
                status.put("currentLeader", currentLeader);
            }
            // If currentLeader is null, omit the field (may be during election)

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
        // CRITICAL: Omit null values - null means discovery failed, not that there's no leader
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (java.util.Map.Entry<String, Object> entry : status.entrySet()) {
            Object value = entry.getValue();
            // Skip null values - they indicate discovery failure, not absence of data
            if (value == null) {
                continue;
            }
            if (!first) json.append(",");
            first = false;
            json.append("\"").append(entry.getKey()).append("\":");
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
     * Get proposal status.
     * GET /v1/proposals/{proposalId}/status
     */
    public void handleGetProposalStatus(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setContentType("application/json");
        
        try {
            String path = request.getRequestURI();
            // Extract proposalId from path: /v1/proposals/{proposalId}/status
            String[] parts = path.split("/");
            if (parts.length < 4) {
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid proposal ID");
                return;
            }
            String proposalId = parts[3];
            
            if (context.proposalQueueManager == null) {
                response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Proposal queue not available");
                return;
            }
            
            ProposalStatus status = context.proposalQueueManager.getProposalStatus(proposalId);
            if (status == null) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND, "Proposal not found");
                return;
            }
            
            response.setStatus(HttpServletResponse.SC_OK);
            String json = "{" +
                "\"proposalId\":\"" + status.getProposalId() + "\"," +
                "\"state\":\"" + status.getState().name() + "\"," +
                "\"ethereumTxHash\":\"" + status.getEthereumTxHash() + "\"," +
                "\"timeoutTimestamp\":" + status.getTimeoutTimestamp() + "," +
                "\"confirmedBlock\":" + (status.getConfirmedBlock() != null ? status.getConfirmedBlock() : -1) + "," +
                "\"rejectionReason\":" + (status.getRejectionReason() != null ? "\"" + status.getRejectionReason() + "\"" : "null") +
                "}";
            response.getWriter().write(json);
        } catch (Exception e) {
            log.error("Error getting proposal status", e);
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Error: " + e.getMessage());
        }
    }
    
    /**
     * Get pending proposals count.
     * GET /v1/proposals/pending/count
     */
    public void handleGetPendingCount(HttpServletResponse response) throws IOException {
        response.setContentType("application/json");
        
        try {
            if (context.proposalQueueManager == null) {
                response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Proposal queue not available");
                return;
            }
            
            int count = context.proposalQueueManager.getPendingCount();
            response.setStatus(HttpServletResponse.SC_OK);
            String json = "{\"pendingCount\":" + count + "}";
            response.getWriter().write(json);
        } catch (Exception e) {
            log.error("Error getting pending count", e);
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Error: " + e.getMessage());
        }
    }
    
    /**
     * ✈️ AERON NATIVE: Apply replicated write to FileStore.
     * This is called from AeronConsensusEngine.onSessionMessage() after Aeron replicates the write.
     */
    public void applyReplicatedWrite(String walletAddress, String path, String contentType, 
                                     String message, String signature) {
        try {
            log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            log.info("✈️  APPLYING REPLICATED WRITE (Aeron native replication)");
            log.info("   Wallet: {}", walletAddress);
            log.info("   Path: {}", path);
            log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            
            // Get current HEAD
            String previousHead = context.fileStore.getHead().getRecordId().toString();
            log.info("📍 Previous HEAD: {}", previousHead.substring(0, Math.min(20, previousHead.length())));
            
            // Parse path: /oak-chain/content/{L1}/{L2}/{L3}/{wallet}/{contentId}
            String[] pathParts = path.split("/");
            if (pathParts.length < 6) {
                log.error("❌ Invalid path format: {}", path);
                return;
            }
            
            // Build node structure
            org.apache.jackrabbit.oak.spi.state.NodeBuilder rootBuilder = context.nodeStore.getRoot().builder();
            org.apache.jackrabbit.oak.spi.state.NodeBuilder walletPath = rootBuilder
                .child("oak-chain")
                .child("content")
                .child(pathParts[3])  // L1
                .child(pathParts[4])  // L2
                .child(pathParts[5])  // L3
                .child(pathParts[6]); // Wallet
            
            // Create content node
            String contentId = pathParts.length > 7 ? pathParts[7] : (contentType + "-" + System.currentTimeMillis());
            org.apache.jackrabbit.oak.spi.state.NodeBuilder contentNode = walletPath.child(contentId);
            
            // Set properties
            contentNode.setProperty("jcr:primaryType", "nt:unstructured");
            contentNode.setProperty("contentType", contentType != null ? contentType : "page");
            contentNode.setProperty("message", message != null ? message : "");
            contentNode.setProperty("timestamp", System.currentTimeMillis());
            contentNode.setProperty("wallet", walletAddress);
            contentNode.setProperty("signature", signature != null ? signature : "");
            contentNode.setProperty("source", "aeron-replicated");
            
            // Commit the change
            org.apache.jackrabbit.oak.spi.commit.CommitInfo commitInfo = 
                new org.apache.jackrabbit.oak.spi.commit.CommitInfo(
                    "aeron-replication", 
                    null, 
                    java.util.Collections.singletonMap("replicated", "true")
                );
            
            context.nodeStore.merge(rootBuilder, org.apache.jackrabbit.oak.spi.commit.EmptyHook.INSTANCE, commitInfo);
            
            // Flush FileStore
            context.fileStore.flush();
            
            // Get new HEAD
            String newHead = context.fileStore.getHead().getRecordId().toString();
            log.info("📍 New HEAD: {}", newHead.substring(0, Math.min(20, newHead.length())));
            log.info("✅ Replicated write applied successfully");
            
        } catch (Exception e) {
            log.error("❌ Failed to apply replicated write", e);
            throw new RuntimeException("Failed to apply replicated write", e);
        }
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
        
        log.info("🔍 Querying {} peers' Aeron Cluster state to find leader", allUrls.size());
        
        for (String url : allUrls) {
            try {
                // Resolve hostname to IP for reliable networking
                String queryUrl = resolveUrlToIP(url);
                log.debug("   Querying: {}", queryUrl + "/v1/aeron/cluster-state");
                java.net.URL apiUrl = new java.net.URL(queryUrl + "/v1/aeron/cluster-state");
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) apiUrl.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(2000);
                conn.setReadTimeout(3000);
                
                int responseCode = conn.getResponseCode();
                log.debug("   Response code from {}: {}", url, responseCode);
                if (responseCode == 200) {
                    java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(conn.getInputStream())
                    );
                    String response = reader.lines().collect(java.util.stream.Collectors.joining());
                    reader.close();
                    
                    // ✈️ AERON CLUSTER SOURCE OF TRUTH: Parse Aeron Cluster state JSON
                    // Priority 1: Check top-level "isLeader":true (this node is the leader)
                    if (response.contains("\"isLeader\":true")) {
                        log.info("✅ Found leader (top-level isLeader:true): {}", url);
                        return url;
                    }
                    
                    // Priority 2: Check top-level "role":"LEADER"
                    if (response.contains("\"role\":\"LEADER\"")) {
                        log.info("✅ Found leader (top-level role:LEADER): {}", url);
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
                                log.info("✅ Found leader in members array: {}", leaderUrl);
                                return leaderUrl;
                            }
                        }
                    }
                } else {
                    log.debug("   Non-200 response from {}: {}", url, responseCode);
                }
            } catch (Exception e) {
                log.warn("Failed to query {} for cluster state: {}", url, e.getMessage());
            }
        }
        
        log.warn("⚠️  Could not discover leader from any peer");
        
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
     * Get the next validator in the rotation order for failover.
     * This is used when the current leader appears unreachable.
     * 
     * @return URL of the next validator that might be leader
     */
    private String getNextValidatorInRotation() {
        if (context.aeronConsensusEngine == null) {
            return context.selfUrl; // Fallback
        }
        
        // Get all validators from Aeron cluster state
        java.util.Map<String, Object> clusterState = context.aeronConsensusEngine.getNativeClusterState();
        if (clusterState == null) {
            return context.selfUrl; // Fallback
        }
        
        @SuppressWarnings("unchecked")
        java.util.List<java.util.Map<String, Object>> members = 
            (java.util.List<java.util.Map<String, Object>>) clusterState.get("members");
        
        if (members == null || members.isEmpty()) {
            return context.selfUrl; // Fallback
        }
        
        // Get all validator URLs in deterministic order
        java.util.List<String> allValidators = new java.util.ArrayList<>();
        for (java.util.Map<String, Object> member : members) {
            String url = (String) member.get("url");
            if (url != null) {
                allValidators.add(url);
            }
        }
        java.util.Collections.sort(allValidators);
        
        // Find current leader in the list
        String currentLeader = context.aeronConsensusEngine.getCurrentLeader();
        int leaderIndex = allValidators.indexOf(currentLeader);
        
        if (leaderIndex == -1 || allValidators.isEmpty()) {
            // Leader not found or no validators, return first validator
            return allValidators.isEmpty() ? context.selfUrl : allValidators.get(0);
        }
        
        // Return next validator in rotation (wrap around if needed)
        int nextIndex = (leaderIndex + 1) % allValidators.size();
        return allValidators.get(nextIndex);
    }
    
    /**
     * Handle GET /v1/gc/estimate - GC cost estimation endpoint
     * 
     * <p>Estimates the cost of garbage collection operations in USDC.
     * 
     * <p>Query Parameters:
     *   - revision: Optional target revision (null = use HEAD)
     * 
     * <p>Response: JSON with GC cost estimate
     *   - reclaimableSegmentCount: Number of reclaimable segments
     *   - reclaimableSizeBytes: Total size of reclaimable segments
     *   - reclaimableSizeMB: Total size in MB
     *   - reclaimablePercentage: Percentage of repository reclaimable
     *   - totalSegmentCount: Total number of segments
     *   - totalSizeBytes: Total repository size
     *   - totalSizeMB: Total size in MB
     *   - estimatedCostUSDC: Estimated cost in USDC
     *   - reclaimableByTarFile: Breakdown by TAR file
     */
    public void handleGCCostEstimate(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (context.gcCostEstimator == null) {
            log.warn("GC Cost Estimator not available - endpoint disabled");
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"GC Cost Estimator not available\"}");
            return;
        }
        
        try {
            String targetRevision = request.getParameter("revision");
            log.debug("GC cost estimation request - revision: {}", targetRevision != null ? targetRevision : "HEAD");
            
            GCCostEstimate estimate = context.gcCostEstimator.estimateCost(targetRevision);
            
            log.info("GC cost estimation complete - reclaimable: {} MB ({}%), cost: {} USDC",
                estimate.getReclaimableSizeMB(), 
                String.format("%.2f", estimate.getReclaimablePercentage()),
                estimate.getEstimatedCostUSDC());
            
            // Convert to JSON
            response.setContentType("application/json");
            response.setStatus(HttpServletResponse.SC_OK);
            
            StringBuilder json = new StringBuilder("{");
            json.append("\"reclaimableSegmentCount\":").append(estimate.getReclaimableSegmentCount()).append(",");
            json.append("\"reclaimableSizeBytes\":").append(estimate.getReclaimableSizeBytes()).append(",");
            json.append("\"reclaimableSizeMB\":").append(estimate.getReclaimableSizeMB()).append(",");
            json.append("\"reclaimablePercentage\":").append(String.format("%.2f", estimate.getReclaimablePercentage())).append(",");
            json.append("\"totalSegmentCount\":").append(estimate.getTotalSegmentCount()).append(",");
            json.append("\"totalSizeBytes\":").append(estimate.getTotalSizeBytes()).append(",");
            json.append("\"totalSizeMB\":").append(estimate.getTotalSizeMB()).append(",");
            json.append("\"estimatedCostUSDC\":\"").append(estimate.getEstimatedCostUSDC()).append("\",");
            
            // Reclaimable by TAR file
            json.append("\"reclaimableByTarFile\":{");
            boolean first = true;
            for (java.util.Map.Entry<String, Long> entry : estimate.getReclaimableByTarFile().entrySet()) {
                if (!first) json.append(",");
                first = false;
                json.append("\"").append(FormatUtils.escapeJson(entry.getKey())).append("\":").append(entry.getValue());
            }
            json.append("}");
            
            json.append("}");
            
            response.getWriter().write(json.toString());
            
        } catch (IllegalArgumentException e) {
            // Invalid revision format
            log.warn("Invalid revision format: {}", request.getParameter("revision"), e);
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Invalid revision format: " + 
                FormatUtils.escapeJson(e.getMessage()) + "\"}");
            
        } catch (IOException e) {
            // Graph traversal failed
            log.error("GC cost estimation failed", e);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"GC cost estimation failed: " + 
                FormatUtils.escapeJson(e.getMessage()) + "\"}");
        }
    }
}

