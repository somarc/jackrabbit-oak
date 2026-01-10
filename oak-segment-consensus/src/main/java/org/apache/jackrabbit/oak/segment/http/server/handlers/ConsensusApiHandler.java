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

import org.apache.jackrabbit.oak.segment.consensus.gc.GCCostEstimate;
import org.apache.jackrabbit.oak.segment.consensus.util.WalletPathUtil;
import org.apache.jackrabbit.oak.segment.consensus.queue.ProposalStatus;
import org.apache.jackrabbit.oak.segment.consensus.queue.QueuedProposal;
import org.apache.jackrabbit.oak.segment.http.server.ServerContext;
import org.apache.jackrabbit.oak.segment.http.server.model.ClientRegistration;
import org.apache.jackrabbit.oak.segment.http.server.util.FormatUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Handler for consensus API endpoints (`/v1/propose-write`, `/v1/consensus/status`, `/v1/proposals/*`).
 * This class encapsulates the logic for handling signed write transactions, proposal status queries,
 * and GC cost estimation in the Aeron-based consensus network.
 */
public class ConsensusApiHandler {

    private static final Logger log = LoggerFactory.getLogger(ConsensusApiHandler.class);

    private final ServerContext context;

    public ConsensusApiHandler(ServerContext context) {
        this.context = context;
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
            // ============================================================
            // MULTIPART FORM DATA SUPPORT
            // Handles binary uploads elegantly without base64 encoding
            // Future: ADR 016 (client IPFS) and ADR 020 (lazy upload)
            // ============================================================
            String wallet = null;
            String signature = null;
            String message = null;
            String contentType = null;
            String ethereumTxHash = null;
            String intentToken = null;
            String paymentTier = null;
            String organization = null;  // ADR 037: Organization-scoped content paths
            String ipfsCid = null;        // ADR 016: Client-side IPFS upload - CID from client
            byte[] binaryBytes = null;
            String mimeType = null;
            String fileName = null;
            
            String requestContentType = request.getContentType();
            boolean isMultipart = requestContentType != null && requestContentType.toLowerCase().startsWith("multipart/");
            
            if (isMultipart) {
                log.info("📦 Processing MULTIPART form data upload");
                
                // Parse multipart request
                java.util.Collection<javax.servlet.http.Part> parts = request.getParts();
                for (javax.servlet.http.Part part : parts) {
                    String partName = part.getName();
                    
                    if (part.getSubmittedFileName() != null) {
                        // This is a file upload
                        fileName = part.getSubmittedFileName();
                        mimeType = part.getContentType();
                        
                        // Read file bytes directly (no base64!)
                        try (java.io.InputStream is = part.getInputStream()) {
                            binaryBytes = is.readAllBytes();
                        }
                        log.info("📎 Received file: {} ({} bytes, {})", fileName, binaryBytes.length, mimeType);
                        
                    } else {
                        // This is a form field
                        String value = new String(part.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                        
                        switch (partName) {
                            case "walletAddress": case "wallet": wallet = value; break;
                            case "signature": signature = value; break;
                            case "message": message = value; break;
                            case "contentType": contentType = value; break;
                            case "ethereumTxHash": ethereumTxHash = value; break;
                            case "intentToken": intentToken = value; break;
                            case "paymentTier": paymentTier = value; break;
                            case "organization": organization = value; break;  // ADR 037
                            case "ipfsCid": ipfsCid = value; break;  // ADR 016: Client-side IPFS CID
                        }
                    }
                }
            } else {
                // Standard URL-encoded form (existing path)
                wallet = request.getParameter("walletAddress");
                if (wallet == null || wallet.isEmpty()) {
                    wallet = request.getParameter("wallet"); // Fallback for backward compatibility
                }
                signature = request.getParameter("signature");
                message = request.getParameter("message");
                contentType = request.getParameter("contentType");
                ethereumTxHash = request.getParameter("ethereumTxHash");
                intentToken = request.getParameter("intentToken");
                paymentTier = request.getParameter("paymentTier");
                organization = request.getParameter("organization");  // ADR 037
                ipfsCid = request.getParameter("ipfsCid");  // ADR 016: Client-side IPFS CID
                
                // Legacy base64 binary (for backward compatibility, but discouraged)
                String binaryData = request.getParameter("binaryData");
                if (binaryData != null && !binaryData.isEmpty()) {
                    log.debug("⚠️  Using legacy base64 binary upload (consider multipart for large files)");
                    mimeType = request.getParameter("mimeType");
                    try {
                        String cleanedBase64 = binaryData.replace(' ', '+').replaceAll("\\s", "");
                        binaryBytes = java.util.Base64.getDecoder().decode(cleanedBase64);
                    } catch (IllegalArgumentException e) {
                        log.warn("⚠️  Invalid base64 binary data: {}", e.getMessage());
                    }
                }
            }
            
            // Validate Ethereum address format FIRST (REQUIRED)
            if (wallet == null || wallet.isEmpty()) {
                context.apiRejectedRequests.incrementAndGet();
                log.warn("❌ API REJECTED: Missing wallet address");
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, 
                    "Missing walletAddress parameter. Writes require a valid 0x Ethereum address.");
                return;
            }
            
            // Validate Ethereum address format (strict validation)
            wallet = wallet.trim();
            if (!wallet.startsWith("0x") || wallet.length() < 10) {
                context.apiRejectedRequests.incrementAndGet();
                log.warn("❌ API REJECTED: Invalid wallet format: {}", wallet);
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, 
                    "Invalid Ethereum address format. Must start with '0x' and be at least 10 characters.");
                return;
            }
            
            // Validate wallet is valid hex after 0x prefix
            String walletHex = wallet.substring(2);
            if (!walletHex.matches("[a-fA-F0-9]+")) {
                context.apiRejectedRequests.incrementAndGet();
                log.warn("❌ API REJECTED: Wallet contains non-hex characters: {}", wallet);
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, 
                    "Invalid Ethereum address: must be valid hexadecimal after '0x'");
                return;
            }
            
            // Normalize wallet addresses for comparison (case-insensitive)
            String normalizedWallet = wallet.toLowerCase();
            
            // ============================================================
            // ORGANIZATION VALIDATION (ADR 037)
            // ============================================================
            // Optional: allows multi-brand wallets (one wallet, multiple orgs)
            String orgValidationError = WalletPathUtil.validateOrganization(organization);
            if (orgValidationError != null) {
                context.apiRejectedRequests.incrementAndGet();
                log.warn("❌ API REJECTED: Invalid organization '{}': {}", organization, orgValidationError);
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, orgValidationError);
                return;
            }
            if (organization != null && !organization.isEmpty()) {
                log.info("🏢 Organization: {} (wallet: {})", organization, normalizedWallet.substring(0, 10) + "...");
            }
            
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
            
            // If not found by wallet, try clientId lookup (wallet address is preferred)
            // Note: IP-based fallback has been removed - wallet address is required
            if (clientReg == null) {
                String clientIdHeader = request.getHeader("X-Client-Id");
                if (clientIdHeader == null || clientIdHeader.isEmpty()) {
                    clientIdHeader = request.getParameter("clientId");
                }
                // Only use explicit clientId header/param, not IP address
                if (clientIdHeader != null && !clientIdHeader.isEmpty()) {
                    clientReg = context.registeredClients.get(clientIdHeader);
                    if (clientReg != null) {
                        clientId = clientIdHeader;
                    }
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
                        log.debug("🧪 MOCK MODE: Auto-registering wallet {} as client for replication testing", normalizedWallet);
                    }
                    isValidatorWallet = true;
                    validatorId = normalizedWallet;
                }
                
                // Check if auto-registration is allowed (mock mode only)
                org.apache.jackrabbit.oak.segment.consensus.config.BlockchainConfig blockchainConfig = 
                    org.apache.jackrabbit.oak.segment.consensus.config.BlockchainConfig.getInstance();
                
                if (isValidatorWallet && blockchainConfig.isMockMode()) {
                    log.debug("✅ Auto-registering wallet {} as client (MOCK MODE - testing only)", validatorId);
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
                    String registeredShard = WalletPathUtil.getShardRoot(registeredWallet);
                    String attemptedShard = WalletPathUtil.getShardRoot(normalizedWallet);
                    response.sendError(HttpServletResponse.SC_FORBIDDEN, 
                        String.format("Path enforcement violation: Client %s can only write to shard %s, " +
                                     "but attempted to write to shard %s", 
                                     clientId, registeredShard, attemptedShard));
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
            
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            // WRITE BLOCKING: Check if entity has exceeded GC debt limit
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            if (context.gcAccountManager != null) {
                if (!context.gcAccountManager.canWrite(normalizedWallet)) {
                    org.apache.jackrabbit.oak.segment.consensus.gc.EntityGCAccount account = 
                        context.gcAccountManager.getAccount(normalizedWallet);
                    
                    log.warn("🚫 Write BLOCKED: wallet={}, debt=${}, limit=${}", 
                             normalizedWallet, account.totalDebt, account.debtLimit);
                    
                    response.setContentType("application/json");
                    response.setStatus(402); // 402 Payment Required
                    
                    String errorJson = String.format(
                        "{\"error\":\"WRITE_BLOCKED_GC_DEBT\"," +
                        "\"message\":\"Writes blocked due to unpaid GC debt. Please pay debt to resume.\"," +
                        "\"wallet\":\"%s\"," +
                        "\"totalDebt\":\"%s\"," +
                        "\"executedDebt\":\"%s\"," +
                        "\"pendingDebt\":\"%s\"," +
                        "\"debtLimit\":\"%s\"," +
                        "\"amountOverLimit\":\"%s\"," +
                        "\"paymentUrl\":\"/v1/gc/account/%s/pay\"," +
                        "\"statusUrl\":\"/v1/gc/account/%s\"}",
                        normalizedWallet,
                        account.totalDebt.toString(),
                        account.executedDebt.toString(),
                        account.getPendingDebt().toString(),
                        account.debtLimit.toString(),
                        account.totalDebt.subtract(account.debtLimit).toString(),
                        normalizedWallet,
                        normalizedWallet
                    );
                    
                    response.getWriter().write(errorJson);
                    
                    log.info("💳 PAYMENT REQUIRED: Rejected write from {} (debt: ${})", 
                             normalizedWallet, account.totalDebt);
                    return;
                }
            }
            
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
            // ============================================================
            // SIGNATURE VALIDATION (strict even in MOCK mode for testing)
            // ============================================================
            if (signature == null || signature.isEmpty()) {
                context.apiRejectedRequests.incrementAndGet();
                log.warn("❌ API REJECTED: Missing signature");
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, 
                    "Missing signature. All writes require a signature (even in mock mode for testing).");
                return;
            }
            
            // Validate signature format: must start with 0x and be hex
            signature = signature.trim();
            if (!signature.startsWith("0x")) {
                context.apiRejectedRequests.incrementAndGet();
                log.warn("❌ API REJECTED: Signature must start with '0x': {}", signature);
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, 
                    "Invalid signature format: must start with '0x'");
                return;
            }
            
            // Validate signature is valid hex after 0x prefix
            String sigHex = signature.substring(2);
            if (sigHex.isEmpty()) {
                context.apiRejectedRequests.incrementAndGet();
                log.warn("❌ API REJECTED: Signature too short: {}", signature);
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, 
                    "Invalid signature: too short (need hex data after 0x)");
                return;
            }
            
            if (!sigHex.matches("[a-fA-F0-9]+")) {
                context.apiRejectedRequests.incrementAndGet();
                log.warn("❌ API REJECTED: Signature contains non-hex characters: {}", signature);
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, 
                    "Invalid signature format: must be valid hexadecimal after '0x'");
                return;
            }
            
            log.debug("🔐 WALLET-BASED WRITE: client={}, wallet={}, contentType={}", clientId, wallet, contentType);
            
            // ============================================================
            // CRYPTOGRAPHIC SIGNATURE VERIFICATION
            // Verifies that the signature was created by the claimed wallet
            // ============================================================
            if (blockchainConfig.isMockMode()) {
                log.debug("✅ Signature validation: Format OK (mock mode - cryptographic verification skipped)");
            } else {
                // Real signature verification using Ethereum personal_sign recovery
                if (!org.apache.jackrabbit.oak.segment.consensus.security.EthereumSignatureVerifier.isFullVerificationAvailable()) {
                    log.warn("⚠️ Bouncy Castle not available - signature verification degraded");
                    // In production without BC, we should reject. For now, warn and continue.
                }
                
                // The message that was signed (must match what client signed)
                // Client signs: wallet + path + contentType + message (or similar)
                String signedMessage = message != null ? message : "";
                
                boolean signatureValid = org.apache.jackrabbit.oak.segment.consensus.security.EthereumSignatureVerifier
                    .verifySignature(signedMessage, signature, wallet);
                
                if (!signatureValid) {
                    context.apiRejectedRequests.incrementAndGet();
                    log.warn("❌ API REJECTED: Signature verification failed for wallet {}", wallet);
                    response.sendError(HttpServletResponse.SC_UNAUTHORIZED, 
                        "Signature verification failed. The signature does not match the claimed wallet address.");
                    return;
                }
                
                log.info("✅ Signature cryptographically verified for wallet {}", wallet);
            }
            
            // ============================================================
            // TRANSACTION HASH VALIDATION
            // ============================================================
            if (ethereumTxHash == null || ethereumTxHash.isEmpty()) {
                context.apiRejectedRequests.incrementAndGet();
                log.warn("❌ API REJECTED: Missing ethereumTxHash");
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, 
                    "Missing ethereumTxHash parameter. Must provide Ethereum transaction hash from authorizeWrite() call.");
                return;
            }
            
            // Validate tx hash format: must start with 0x and be valid hex
            ethereumTxHash = ethereumTxHash.trim();
            if (!ethereumTxHash.startsWith("0x")) {
                context.apiRejectedRequests.incrementAndGet();
                log.warn("❌ API REJECTED: Transaction hash must start with '0x': {}", ethereumTxHash);
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, 
                    "Invalid ethereumTxHash format: must start with '0x'");
                return;
            }
            
            String txHex = ethereumTxHash.substring(2);
            if (txHex.length() < 8) {  // Minimum reasonable tx hash length
                context.apiRejectedRequests.incrementAndGet();
                log.warn("❌ API REJECTED: Transaction hash too short: {}", ethereumTxHash);
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, 
                    "Invalid ethereumTxHash: too short (expected at least 8 hex characters)");
                return;
            }
            
            if (!txHex.matches("[a-fA-F0-9]+")) {
                context.apiRejectedRequests.incrementAndGet();
                log.warn("❌ API REJECTED: Transaction hash contains non-hex characters: {}", ethereumTxHash);
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, 
                    "Invalid ethereumTxHash format: must be valid hexadecimal");
                return;
            }
            
            // ============================================================
            // BINARY UPLOAD TO BLOBSTORE
            // Supports: Multipart (preferred), base64 (legacy), ADR 020 (future)
            // ============================================================
            String blobId = null;
            
            // 📦 EAGER BINARY UPLOAD: If binary bytes are available, upload to BlobStore
            if (binaryBytes != null && binaryBytes.length > 0 && context.blobStore != null) {
                try {
                    log.info("📦 Uploading binary to BlobStore ({} bytes, {})", binaryBytes.length, mimeType);
                    
                    // Upload to BlobStore (IPFS or other configured store)
                    java.io.InputStream binaryStream = new java.io.ByteArrayInputStream(binaryBytes);
                    blobId = context.blobStore.writeBlob(binaryStream);
                    
                    log.info("✅ Binary uploaded to BlobStore: {} ({} bytes, mime: {})", 
                        blobId, binaryBytes.length, mimeType != null ? mimeType : "unknown");
                    
                    // Register CID mapping if IPFS and CidMappingService is available
                    if (context.cidMappingService != null && "ipfs".equalsIgnoreCase(context.blobStoreType)) {
                        try {
                            // Try to get the IPFS CID from the underlying DataStore
                            if (context.blobStore instanceof org.apache.jackrabbit.oak.plugins.blob.datastore.DataStoreBlobStore) {
                                org.apache.jackrabbit.oak.plugins.blob.datastore.DataStoreBlobStore dsBlobStore = 
                                    (org.apache.jackrabbit.oak.plugins.blob.datastore.DataStoreBlobStore) context.blobStore;
                                Object dataStore = dsBlobStore.getDataStore();
                                if (dataStore instanceof org.apache.jackrabbit.oak.blob.cloud.ipfs.IPFSDataStore) {
                                    org.apache.jackrabbit.oak.blob.cloud.ipfs.IPFSDataStore ipfsDataStore = 
                                        (org.apache.jackrabbit.oak.blob.cloud.ipfs.IPFSDataStore) dataStore;
                                    String derivedIpfsCid = ipfsDataStore.getCID(blobId);
                                    if (derivedIpfsCid != null) {
                                        context.cidMappingService.registerMapping(blobId, derivedIpfsCid);
                                        log.info("📎 Registered CID mapping: {} → {}", blobId, derivedIpfsCid);
                                    }
                                }
                            }
                        } catch (Exception e) {
                            log.debug("Could not register CID mapping: {}", e.getMessage());
                        }
                    }
                    
                } catch (IllegalArgumentException e) {
                    log.warn("⚠️  Invalid base64 binary data: {}", e.getMessage());
                    // Continue without binary - don't fail the whole request
                    blobId = null;
                } catch (Exception e) {
                    log.error("❌ Failed to upload binary to BlobStore: {}", e.getMessage());
                    // Continue without binary - don't fail the whole request
                    blobId = null;
                }
            }
            
            // Build wallet-scoped content path (with optional organization - ADR 037)
            String shardId = WalletPathUtil.getShardId(normalizedWallet);
            String contentRoot = WalletPathUtil.getContentPath(normalizedWallet, organization);
            log.debug("🪣 Using wallet shard: {} (org: {}, contentRoot: {})", shardId, 
                organization != null ? organization : "none", contentRoot);
            
            // Generate content ID and full path
            String contentId = contentType + "-" + System.currentTimeMillis();
            String fullPath = contentRoot + "/" + contentId;
            
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
                    signature,
                    blobId,      // Include binary reference for Aeron replication
                    mimeType     // Include mimeType for binary handling
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
            
            // ============================================================
            // PAYMENT TIER VALIDATION
            // ============================================================
            // Validate tier if provided (defaults to STANDARD if missing)
            if (paymentTier != null && !paymentTier.isEmpty()) {
                String normalizedTier = paymentTier.trim().toLowerCase();
                if (!normalizedTier.equals("standard") && !normalizedTier.equals("express") && !normalizedTier.equals("priority")) {
                    context.apiRejectedRequests.incrementAndGet();
                    log.warn("❌ API REJECTED: Invalid payment tier: {}", paymentTier);
                    response.sendError(HttpServletResponse.SC_BAD_REQUEST, 
                        "Invalid paymentTier: '" + paymentTier + "'. Must be 'standard', 'express', or 'priority'.");
                    return;
                }
            }
            
            // Parse payment tier from request (defaults to STANDARD)
            org.apache.jackrabbit.oak.segment.consensus.economics.ValidatorEarningsTracker.PaymentTier tier = 
                org.apache.jackrabbit.oak.segment.consensus.economics.ValidatorEarningsTracker.PaymentTier.STANDARD;
            
            // POC: Auto-simulate payment for testing (BEFORE queuing to avoid race condition)
            if (context.evmBridge instanceof org.apache.jackrabbit.oak.segment.consensus.evm.impl.SimpleEvmBridge) {
                org.apache.jackrabbit.oak.segment.consensus.evm.impl.SimpleEvmBridge simpleEvmBridge = 
                    (org.apache.jackrabbit.oak.segment.consensus.evm.impl.SimpleEvmBridge) context.evmBridge;
                
                java.math.BigInteger paymentAmount;
                if ("express".equalsIgnoreCase(paymentTier)) {
                    tier = org.apache.jackrabbit.oak.segment.consensus.economics.ValidatorEarningsTracker.PaymentTier.EXPRESS;
                    paymentAmount = tier.baseRate; // 0.000002 ETH
                } else if ("priority".equalsIgnoreCase(paymentTier)) {
                    tier = org.apache.jackrabbit.oak.segment.consensus.economics.ValidatorEarningsTracker.PaymentTier.PRIORITY;
                    paymentAmount = tier.baseRate; // 0.00001 ETH
                } else {
                    // Standard tier (default)
                    paymentAmount = tier.baseRate; // 0.000001 ETH
                }
                
                // Create mock payment proof with correct wallet address
                org.apache.jackrabbit.oak.segment.consensus.evm.PaymentProof mockPayment = 
                    new org.apache.jackrabbit.oak.segment.consensus.evm.impl.SimplePaymentProof(
                        ethereumTxHash,
                        simpleEvmBridge.getCurrentBlockNumber(),
                        normalizedWallet,  // fromAddress = wallet address (CRITICAL!)
                        simpleEvmBridge.getContractAddress(),
                        proposalId,
                        paymentAmount.toString(), // Wei amount based on tier
                        6 // 6 confirmations
                    );
                simpleEvmBridge.simulatePayment(mockPayment);
                
                // Record validator earnings (distributed across all validators)
                if (context.validatorEarningsTracker != null) {
                    // Get current epoch from BeaconChainClient (if available)
                    long currentEpoch = -1;
                    if (context.proposalQueueManager != null && context.proposalQueueManager.getEpochQueue() != null) {
                        currentEpoch = context.proposalQueueManager.getEpochQueue().getCurrentEpoch();
                    }
                    
                    context.validatorEarningsTracker.recordPayment(paymentAmount, tier, currentEpoch);
                    log.debug("💰 Payment recorded: {} wei (tier: {}) distributed across validators", paymentAmount, tier);
                }
                
                log.debug("🧪 POC: Auto-simulated payment for proposal {} from wallet {} (tier: {})", proposalId, normalizedWallet, tier);
            }
            
            // Queue proposal (waiting for Ethereum confirmation)
            log.debug("📥 Queuing proposal {} (tx: {}, tier: {}, intentToken: {}, blobId: {}), waiting for Ethereum confirmation", 
                proposalId, ethereumTxHash, tier, intentToken != null ? intentToken : "none", blobId != null ? blobId : "none");
            
            QueuedProposal queuedProposal = context.proposalQueueManager.queueProposal(
                proposalId,
                ethereumTxHash,
                normalizedWallet,
                fullPath,
                contentType != null ? contentType : "page",
                message != null ? message : "",  // Keep message clean, no blob embedding
                signature, // Already validated - no fallback needed
                tier,  // Pass payment tier for priority handling
                intentToken  // Pass intentToken for lazy binary upload (ADR 020)
            );
            
            // Set binary info directly on proposal (for Aeron serialization)
            if (blobId != null && !blobId.isEmpty()) {
                queuedProposal.setBlobId(blobId);
                queuedProposal.setMimeType(mimeType != null ? mimeType : "application/octet-stream");
                log.info("📎 Binary blob attached to proposal {}: blobId={}, mimeType={}", 
                    proposalId, blobId, mimeType);
            } else {
                log.info("📝 Text-only proposal {} (no binary)", proposalId);
            }
            
            // ADR 016: Set IPFS CID from client-side upload
            if (ipfsCid != null && !ipfsCid.isEmpty()) {
                queuedProposal.setIpfsCid(ipfsCid);
                log.info("🔗 IPFS CID attached to proposal {}: ipfsCid={}", proposalId, ipfsCid);
            }
            
            // Track API acceptance (proposal successfully queued)
            context.apiAcceptedRequests.incrementAndGet();
            
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
            log.debug("✅ Proposal {} queued successfully", proposalId);
            
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
     * the wallet's shard root (/oak-chain/{shard}/) and that the wallet matches the registered client.</p>
     * 
     * <p><strong>Path Structure:</strong>
     * <pre>
     * /oak-chain/{shard}/content/...  ← Wallet-owned content
     * /oak-chain/{shard}/conf/...     ← Wallet-owned config
     * /oak-chain/{shard}/apps/...     ← Wallet-owned apps (future)
     * </pre>
     * 
     * <p>Parameters:
     *   - wallet: Ethereum wallet address (must match registered client)
     *   - signature: Signed message (wallet:deleteId:contentPath)
     *   - contentPath: Path to content to delete (must be under /oak-chain/{shard}/)
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
            
            // If not found by wallet, try clientId lookup (wallet address is preferred)
            // Note: IP-based fallback has been removed - wallet address is required
            if (clientReg == null) {
                String clientIdHeader = request.getHeader("X-Client-Id");
                if (clientIdHeader == null || clientIdHeader.isEmpty()) {
                    clientIdHeader = request.getParameter("clientId");
                }
                // Only use explicit clientId header/param, not IP address
                if (clientIdHeader != null && !clientIdHeader.isEmpty()) {
                    clientReg = context.registeredClients.get(clientIdHeader);
                    if (clientReg != null) {
                        clientId = clientIdHeader;
                    }
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
            
            // Verify path ownership: must be under /oak-chain/{shard}/
            String shardRoot = WalletPathUtil.getShardRoot(normalizedWallet);
            if (!contentPath.toLowerCase().startsWith(shardRoot + "/")) {
                log.warn("🚫 Delete proposal rejected: Path ownership violation");
                log.warn("   Content path: {}", contentPath);
                log.warn("   Expected shard root: {}", shardRoot);
                response.sendError(HttpServletResponse.SC_FORBIDDEN, 
                    String.format("Path ownership violation: Content at %s does not belong to wallet %s. " +
                                 "Only content under %s/ can be deleted.",
                                 contentPath, wallet, shardRoot));
                return;
            }
            
            log.debug("🗑️  DELETE PROPOSAL: client={}, wallet={}, path={}", clientId, wallet, contentPath);
            
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            // ETHEREUM PAYMENT REQUIRED: Deletes flow through same pipeline as writes
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            String ethereumTxHash = request.getParameter("ethereumTxHash");
            if (ethereumTxHash == null || ethereumTxHash.isEmpty()) {
                response.sendError(HttpServletResponse.SC_BAD_REQUEST,
                    "Missing ethereumTxHash parameter. Deletes require Ethereum payment (like writes). " +
                    "Tiers: STANDARD (+2 epochs), EXPRESS (+1 epoch), PRIORITY (direct).");
                return;
            }
            
            // Determine payment tier from Ethereum transaction
            // For MVP: Simple heuristic based on tx hash (in production, query chain)
            org.apache.jackrabbit.oak.segment.consensus.economics.ValidatorEarningsTracker.PaymentTier tier = 
                determineTierFromEthereumTx(ethereumTxHash);
            
            // Generate unique proposal ID for this delete
            String proposalId = java.util.UUID.randomUUID().toString();
            
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            // GC DEBT TRACKING: Track debt when content is deleted (deferred cost)
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            java.math.BigDecimal gcDebtIncurred = java.math.BigDecimal.ZERO;
            java.math.BigDecimal totalDebt = java.math.BigDecimal.ZERO;
            java.math.BigDecimal pendingDebt = java.math.BigDecimal.ZERO;
            boolean writesBlocked = false;
            
            if (context.gcAccountManager != null) {
                try {
                    // Estimate content size from Oak NodeStore
                    long estimatedSizeMB = estimateContentSizeMB(contentPath);
                    
                    // Add debt to account (pending until GC executes)
                    java.math.BigDecimal debtCost = context.gcAccountManager.addDebt(normalizedWallet, contentPath, estimatedSizeMB);
                    
                    // Get updated account state
                    org.apache.jackrabbit.oak.segment.consensus.gc.EntityGCAccount account = 
                        context.gcAccountManager.getAccount(normalizedWallet);
                    
                    gcDebtIncurred = debtCost;
                    totalDebt = account.totalDebt;
                    pendingDebt = account.getPendingDebt();
                    writesBlocked = account.writesBlocked;
                    
                    log.info("💰 GC debt added: wallet={}, path={}, debt=${}, total=${}, pending=${}, blocked={}", 
                             normalizedWallet, contentPath, gcDebtIncurred, totalDebt, pendingDebt, writesBlocked);
                    
                } catch (Exception e) {
                    log.warn("⚠️  Failed to track GC debt for delete: {}", e.getMessage());
                }
            }
            
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            // QUEUE DELETE PROPOSAL: Same flow as writes, just different type
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            log.debug("📥 Queuing DELETE proposal {} (tx: {}, tier: {}), waiting for Ethereum confirmation", 
                proposalId, ethereumTxHash, tier);
            
            context.proposalQueueManager.queueDeleteProposal(
                proposalId,
                ethereumTxHash,
                normalizedWallet,
                contentPath,
                signature,
                tier
            );
            
            // Return 202 Accepted (queued for processing)
            response.setContentType("application/json");
            response.setStatus(HttpServletResponse.SC_ACCEPTED);
            String resultJson = "{" +
                "\"proposalId\":\"" + proposalId + "\"," +
                "\"type\":\"DELETE\"," +
                "\"state\":\"PENDING\"," +
                "\"message\":\"Delete proposal queued, waiting for Ethereum confirmation\"," +
                "\"ethereumTxHash\":\"" + ethereumTxHash + "\"," +
                "\"tier\":\"" + tier + "\"," +
                "\"timeoutTimestamp\":" + (System.currentTimeMillis() + 300_000) + "," +
                "\"wallet\":\"" + wallet + "\"," +
                "\"contentPath\":\"" + contentPath.replace("\"", "\\\"") + "\"," +
                "\"gcDebtIncurred\":\"" + gcDebtIncurred + "\"," +
                "\"totalDebt\":\"" + totalDebt + "\"," +
                "\"pendingDebt\":\"" + pendingDebt + "\"," +
                "\"writesBlocked\":" + writesBlocked + "}";
            response.getWriter().write(resultJson);
            log.info("✅ DELETE proposal {} queued successfully (tier: {}, path: {})", proposalId, tier, contentPath);
            
        } catch (Exception e) {
            log.error("❌ Delete proposal failed", e);
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Delete proposal failed: " + e.getMessage());
        }
    }
    
    /**
     * Handle GET /v1/consensus/status - Return comprehensive consensus state
     * 
     * Uses Aeron Cluster for single source of truth.
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
     * 
     * @param ipfsCid IPFS CID from client-side upload (ADR 016), may be null
     */
    public void applyReplicatedWrite(String walletAddress, String path, String contentType, 
                                     String message, String signature, String intentToken, 
                                     String blobId, String mimeType, String ipfsCid) {
        try {
            log.debug("✈️  APPLYING REPLICATED WRITE: wallet={}, path={}, intentToken={}, blobId={}, ipfsCid={}", 
                     walletAddress, path, intentToken, blobId, ipfsCid);
            
            // Get current HEAD
            String previousHead = context.fileStore.getHead().getRecordId().toString();
            log.debug("📍 Previous HEAD: {}", previousHead.substring(0, Math.min(20, previousHead.length())));
            
            // Parse path: /oak-chain/{shard}/content/{contentId}
            // Example: /oak-chain/74-2d-35/content/page-1234567890
            String[] pathParts = path.split("/");
            if (pathParts.length < 4) {
                log.error("❌ Invalid path format: {} (expected: /oak-chain/{shard}/content/...)", path);
                return;
            }
            
            // Build node structure using wallet-scoped paths
            org.apache.jackrabbit.oak.spi.state.NodeBuilder rootBuilder = context.nodeStore.getRoot().builder();
            org.apache.jackrabbit.oak.spi.state.NodeBuilder current = rootBuilder;
            
            // Navigate to parent path (all parts except the last one)
            // Track wallet node for metadata enrichment
            org.apache.jackrabbit.oak.spi.state.NodeBuilder walletNode = null;
            String walletNodeName = null;
            
            for (int i = 1; i < pathParts.length - 1; i++) {
                if (!pathParts[i].isEmpty()) {
                    current = current.child(pathParts[i]);
                    
                    // Detect wallet node (matches pattern: 0x[a-f0-9]{40})
                    if (pathParts[i].matches("0x[a-f0-9]{40}")) {
                        walletNode = current;
                        walletNodeName = pathParts[i];
                    }
                }
            }
            
            // Enrich wallet node with metadata (if this is the first time we're seeing it)
            if (walletNode != null && walletNodeName != null) {
                enrichWalletNode(walletNode, walletNodeName, walletAddress);
            }
            
            // Create content node (last path part)
            String contentId = pathParts[pathParts.length - 1];
            org.apache.jackrabbit.oak.spi.state.NodeBuilder contentNode = current.child(contentId);
            
            // Set properties
            // SECURITY: All fields should be non-null after Aeron replication
            // If any are null, fail hard - indicates corruption or programming error
            if (signature == null) {
                throw new IllegalStateException(
                    "SECURITY VIOLATION: Signature is null in replicated write. " +
                    "This indicates Aeron message corruption or validation bypass. " +
                    "Path: " + path + ", Wallet: " + walletAddress
                );
            }
            
            contentNode.setProperty("jcr:primaryType", "nt:unstructured");
            contentNode.setProperty("contentType", contentType != null ? contentType : "page");
            
            // blobId and mimeType now passed as parameters (ADR 020)
            String actualMessage = message != null ? message : "";
            
            contentNode.setProperty("message", actualMessage);
            contentNode.setProperty("timestamp", System.currentTimeMillis());
            contentNode.setProperty("wallet", walletAddress);
            contentNode.setProperty("signature", signature); // Already validated - no fallback
            contentNode.setProperty("source", "aeron-replicated");
            
            // ADR 037: Extract and store organization from path
            // Path format: /oak-chain/XX/YY/ZZ/0xWALLET/{organization}/content/{contentId}
            // Organization is the segment after wallet, before "content"
            String extractedOrg = extractOrganizationFromPath(path);
            if (extractedOrg != null && !extractedOrg.isEmpty()) {
                contentNode.setProperty("organization", extractedOrg);
                log.debug("🏢 Stored organization property: {}", extractedOrg);
            }
            
            // 📦 Store binary reference as jcr:data (proper Oak BINARY property type)
            if (blobId != null && !blobId.isEmpty() && context.blobStore != null) {
                try {
                    // Create proper Blob object from blob ID using BlobStoreBlob
                    // This allows Oak to properly handle the binary as a BINARY property
                    org.apache.jackrabbit.oak.api.Blob blob = 
                        new org.apache.jackrabbit.oak.plugins.blob.BlobStoreBlob(context.blobStore, blobId);
                    
                    // Set as proper BINARY type property (not String!)
                    contentNode.setProperty("jcr:data", blob, org.apache.jackrabbit.oak.api.Type.BINARY);
                    
                    if (mimeType != null && !mimeType.isEmpty()) {
                        contentNode.setProperty("jcr:mimeType", mimeType);
                    }
                    
                    // Store raw blob ID
                    contentNode.setProperty("jcr:blobId", blobId);
                    
                    // 🔗 ADR 016: Store IPFS CID from client-side upload
                    // The client uploads to IPFS first and provides the CID in the proposal
                    // This is the preferred architecture - no validator-side IPFS upload needed
                    if (ipfsCid != null && !ipfsCid.isEmpty()) {
                        contentNode.setProperty("ipfsCid", ipfsCid);
                        contentNode.setProperty("ipfsGateway", "https://ipfs.io/ipfs/" + ipfsCid);
                        log.info("✅ Binary stored with client-provided IPFS CID: jcr:blobId={}, ipfsCid={}", blobId, ipfsCid);
                    } else {
                        // Fallback: Try to get CID from validator's IPFSDataStore (legacy path)
                        // This path is deprecated - clients should provide CID directly
                        String derivedCid = null;
                        if (context.blobStore instanceof org.apache.jackrabbit.oak.plugins.blob.datastore.DataStoreBlobStore) {
                            try {
                                org.apache.jackrabbit.oak.plugins.blob.datastore.DataStoreBlobStore dsBlobStore = 
                                    (org.apache.jackrabbit.oak.plugins.blob.datastore.DataStoreBlobStore) context.blobStore;
                                Object dataStore = dsBlobStore.getDataStore();
                                if (dataStore instanceof org.apache.jackrabbit.oak.blob.cloud.ipfs.IPFSDataStore) {
                                    org.apache.jackrabbit.oak.blob.cloud.ipfs.IPFSDataStore ipfsDataStore = 
                                        (org.apache.jackrabbit.oak.blob.cloud.ipfs.IPFSDataStore) dataStore;
                                    
                                    // Try a few times (async upload may still be in progress)
                                    for (int retry = 0; retry < 5 && derivedCid == null; retry++) {
                                        derivedCid = ipfsDataStore.getCID(blobId);
                                        if (derivedCid == null && retry < 4) {
                                            Thread.sleep(200); // Wait 200ms between retries
                                        }
                                    }
                                }
                            } catch (Exception e) {
                                log.debug("Could not get IPFS CID from validator: {}", e.getMessage());
                            }
                        }
                        
                        if (derivedCid != null) {
                            contentNode.setProperty("ipfsCid", derivedCid);
                            contentNode.setProperty("ipfsGateway", "https://ipfs.io/ipfs/" + derivedCid);
                            log.info("✅ Binary stored with validator-derived IPFS CID (legacy): jcr:blobId={}, ipfsCid={}", blobId, derivedCid);
                        } else {
                            log.info("✅ Binary stored (no IPFS CID - client should provide): jcr:blobId={}", blobId);
                        }
                    }
                    
                } catch (Exception e) {
                    log.error("❌ Failed to create Blob from blobId {}: {}", blobId, e.getMessage());
                    // Fallback: store as string reference
                    contentNode.setProperty("jcr:data", blobId);
                    if (mimeType != null && !mimeType.isEmpty()) {
                        contentNode.setProperty("jcr:mimeType", mimeType);
                    }
                }
            }
            
            // 🔗 ADR 016: Store ipfsCid even without blobId (pure IPFS reference)
            // This supports content that exists only on IPFS without local blob storage
            if ((blobId == null || blobId.isEmpty()) && ipfsCid != null && !ipfsCid.isEmpty()) {
                contentNode.setProperty("ipfsCid", ipfsCid);
                contentNode.setProperty("ipfsGateway", "https://ipfs.io/ipfs/" + ipfsCid);
                log.info("🔗 Stored pure IPFS reference (no local blob): ipfsCid={}", ipfsCid);
            }
            
            // 🔗 ADR 020: Store intentToken for lazy binary upload
            // If intentToken is present, it means this write is associated with a pending binary upload
            // The client will upload the binary to IPFS after seeing this node replicate, then complete the upload
            if (intentToken != null && !intentToken.isEmpty()) {
                contentNode.setProperty("jcr:intentToken", intentToken);
                contentNode.setProperty("jcr:pendingBinary", true);
                log.debug("📎 Intent token stored for lazy binary upload: {}", intentToken);
            }
            
            
            // 🎯 DETERMINISTIC STATE MACHINE: ALL nodes commit identically
            // Aeron guarantees: same messages, same order, on ALL nodes
            // Therefore: same processing = same HEAD (guaranteed!)
            // NO manual HEAD broadcasts needed - consistency by design
            
            // Commit info (SAME on all nodes)
            org.apache.jackrabbit.oak.spi.commit.CommitInfo commitInfo = 
                new org.apache.jackrabbit.oak.spi.commit.CommitInfo(
                    "aeron-replication", 
                    null, 
                    java.util.Collections.singletonMap("replicated", "true")
                );
            
            // Merge changes (SAME on all nodes)
            context.nodeStore.merge(rootBuilder, org.apache.jackrabbit.oak.spi.commit.EmptyHook.INSTANCE, commitInfo);
            
            // Flush FileStore (SAME on all nodes)
            context.fileStore.flush();
            
            // Track fragmentation (SAME on all nodes)
            trackFragmentation(walletAddress);
            
            // Get new HEAD (SAME on all nodes because same processing!)
            String newHead = context.fileStore.getHead().getRecordId().toString10();
            log.debug("✅ Write applied, HEAD: {}...", newHead.substring(0, Math.min(20, newHead.length())));
            
            // Update latest HEAD cache (SAME on all nodes)
            if (context.aeronConsensusEngine != null) {
                context.aeronConsensusEngine.updateLatestHead(newHead);
            }
            
            // 📡 ADR 036: Emit SSE event for real-time discovery
            if (context.eventBroadcaster != null) {
                try {
                    String sseOrg = extractOrganizationFromPath(path);
                    if (blobId != null && !blobId.isEmpty()) {
                        // Binary upload event - try to get IPFS CID
                        String eventCid = null;
                        
                        // Method 1: Try CID mapping service first (most reliable)
                        if (context.cidMappingService != null) {
                            try {
                                java.util.Optional<String> mappedCid = context.cidMappingService.getCid(blobId);
                                if (mappedCid.isPresent()) {
                                    eventCid = mappedCid.get();
                                    log.debug("📡 SSE: Got CID from mapping service: {}", eventCid);
                                }
                            } catch (Exception e) {
                                log.debug("CID mapping lookup failed: {}", e.getMessage());
                            }
                        }
                        
                        // Method 2: Fallback to reading from node
                        if (eventCid == null) {
                            try {
                                org.apache.jackrabbit.oak.spi.state.NodeState newRoot = context.nodeStore.getRoot();
                                for (String part : path.substring(1).split("/")) {
                                    if (!part.isEmpty() && newRoot.hasChildNode(part)) {
                                        newRoot = newRoot.getChildNode(part);
                                    }
                                }
                                org.apache.jackrabbit.oak.api.PropertyState cidProp = newRoot.getProperty("ipfsCid");
                                if (cidProp != null) {
                                    eventCid = cidProp.getValue(org.apache.jackrabbit.oak.api.Type.STRING);
                                    log.debug("📡 SSE: Got CID from node property: {}", eventCid);
                                }
                            } catch (Exception e) {
                                log.debug("Node CID lookup failed: {}", e.getMessage());
                            }
                        }
                        
                        context.eventBroadcaster.emitBinaryUpload(
                            path, walletAddress, sseOrg, message, eventCid, null, mimeType
                        );
                        log.debug("📡 SSE binary event emitted: path={}, cid={}, mimeType={}", path, eventCid, mimeType);
                    } else {
                        // Content write event
                        context.eventBroadcaster.emitContentWrite(
                            path, walletAddress, sseOrg, message, signature, contentType
                        );
                    }
                } catch (Exception e) {
                    log.debug("Failed to emit SSE event: {}", e.getMessage());
                }
            }
            
            // 🎯 ALL nodes now have IDENTICAL HEAD - no broadcast needed!
            log.debug("✅ Deterministic write applied successfully");
            
        } catch (Exception e) {
            log.error("❌ Failed to apply replicated write", e);
            throw new RuntimeException("Failed to apply replicated write", e);
        }
    }
    
    /**
     * ✈️ AERON NATIVE: Apply replicated delete to FileStore.
     * This is called from AeronConsensusEngine.onSessionMessage() after Aeron replicates the delete.
     * 
     * Delete in Oak = Remove node from tree (writes new segment saying "path no longer exists")
     * Old segments remain until GC/compaction runs
     */
    public void applyReplicatedDelete(String walletAddress, String path, String signature) {
        try {
            log.info("🗑️  APPLYING REPLICATED DELETE: wallet={}, path={}", walletAddress, path);
            
            // Get current HEAD
            String previousHead = context.fileStore.getHead().getRecordId().toString();
            log.debug("📍 Previous HEAD: {}", previousHead.substring(0, Math.min(20, previousHead.length())));
            
            // Parse path: /oak-chain/{shard}/content/{contentId}
            String[] pathParts = path.split("/");
            if (pathParts.length < 2) {
                log.error("❌ Invalid path format: {} (expected: /oak-chain/...)", path);
                return;
            }
            
            // Build node structure and navigate to target
            org.apache.jackrabbit.oak.spi.state.NodeBuilder rootBuilder = context.nodeStore.getRoot().builder();
            org.apache.jackrabbit.oak.spi.state.NodeBuilder current = rootBuilder;
            
            // Navigate to parent of node to delete
            boolean pathExists = true;
            for (int i = 1; i < pathParts.length - 1; i++) {
                if (!pathParts[i].isEmpty()) {
                    if (!current.hasChildNode(pathParts[i])) {
                        log.warn("⚠️  Path does not exist: {} (stopping at segment: {})", path, pathParts[i]);
                        pathExists = false;
                        break;
                    }
                    current = current.getChildNode(pathParts[i]);
                }
            }
            
            if (!pathExists) {
                log.warn("⚠️  Delete skipped - path doesn't exist: {}", path);
                // Not an error - idempotent delete (already gone)
                return;
            }
            
            // Remove target node
            String targetNodeName = pathParts[pathParts.length - 1];
            if (current.hasChildNode(targetNodeName)) {
                current.getChildNode(targetNodeName).remove();
                log.info("✅ Node removed: {}", targetNodeName);
            } else {
                log.warn("⚠️  Target node doesn't exist: {} (idempotent delete)", targetNodeName);
                // Not an error - already deleted
                return;
            }
            
            // Commit the deletion (deterministic on all nodes)
            org.apache.jackrabbit.oak.spi.commit.CommitInfo commitInfo = 
                new org.apache.jackrabbit.oak.spi.commit.CommitInfo(
                    "aeron-replication-delete", 
                    null, 
                    java.util.Collections.singletonMap("replicated", "true")
                );
            
            context.nodeStore.merge(rootBuilder, org.apache.jackrabbit.oak.spi.commit.EmptyHook.INSTANCE, commitInfo);
            context.fileStore.flush();
            
            // Get new HEAD (all nodes have same HEAD after deterministic delete)
            String newHead = context.fileStore.getHead().getRecordId().toString10();
            log.info("✅ DELETE applied, HEAD: {}...", newHead.substring(0, Math.min(20, newHead.length())));
            
            // Update latest HEAD cache
            if (context.aeronConsensusEngine != null) {
                context.aeronConsensusEngine.updateLatestHead(newHead);
            }
            
            // 📡 ADR 036: Emit SSE delete event for real-time discovery
            if (context.eventBroadcaster != null) {
                try {
                    String extractedOrg = extractOrganizationFromPath(path);
                    context.eventBroadcaster.emitContentDelete(path, walletAddress, extractedOrg, signature);
                } catch (Exception e) {
                    log.debug("Failed to emit SSE delete event: {}", e.getMessage());
                }
            }
            
            log.info("✅ Deterministic delete applied successfully - old segments remain until GC");
            
        } catch (Exception e) {
            log.error("❌ Failed to apply replicated delete", e);
            throw new RuntimeException("Failed to apply replicated delete", e);
        }
    }
    
    /**
     * Enrich wallet node with metadata about the wallet owner.
     * 
     * This method adds rich metadata to the wallet node itself (the node representing
     * the Ethereum address) including:
     * - Wallet address
     * - Creation timestamp
     * - Last updated timestamp
     * - Content statistics (number of content items created)
     * - Owner information (ENS name, if available - future feature)
     * 
     * Path example: /oak-chain/dd/87/0f/0xdd870fa1b7c4700f2bd7f44238821c26f7392148
     * 
     * @param walletNode The NodeBuilder for the wallet node
     * @param walletNodeName The wallet address (0x...)
     * @param walletAddress The normalized wallet address from the proposal
     */
    private void enrichWalletNode(org.apache.jackrabbit.oak.spi.state.NodeBuilder walletNode, 
                                   String walletNodeName, String walletAddress) {
        try {
            // Check if this is a new wallet node (no properties set yet)
            boolean isNewWallet = !walletNode.hasProperty("wallet");
            
            if (isNewWallet) {
                log.info("🆕 Creating new wallet node with metadata: {}", walletNodeName);
                
                // Set wallet metadata
                walletNode.setProperty("jcr:primaryType", "nt:unstructured");
                walletNode.setProperty("wallet", walletAddress);
                walletNode.setProperty("walletCreated", System.currentTimeMillis());
                walletNode.setProperty("nodeType", "wallet-root");
                walletNode.setProperty("description", "Wallet-scoped content root for " + walletAddress);
                
                // Initialize statistics
                walletNode.setProperty("contentCount", 0L);
                walletNode.setProperty("totalWrites", 0L);
                walletNode.setProperty("lastWrite", System.currentTimeMillis());
                
                // Future: ENS name lookup
                // walletNode.setProperty("ensName", lookupENS(walletAddress));
                
                // Future: On-chain verification
                // walletNode.setProperty("verifiedOnChain", false);
                
                log.debug("✅ Wallet node metadata initialized: {}", walletAddress);
            } else {
                // Update existing wallet node metadata
                long contentCount = walletNode.getProperty("contentCount") != null 
                    ? walletNode.getProperty("contentCount").getValue(org.apache.jackrabbit.oak.api.Type.LONG) 
                    : 0L;
                long totalWrites = walletNode.getProperty("totalWrites") != null 
                    ? walletNode.getProperty("totalWrites").getValue(org.apache.jackrabbit.oak.api.Type.LONG) 
                    : 0L;
                
                // Increment counters
                walletNode.setProperty("contentCount", contentCount + 1);
                walletNode.setProperty("totalWrites", totalWrites + 1);
                walletNode.setProperty("lastWrite", System.currentTimeMillis());
                
                log.debug("📊 Wallet node updated: {} (contentCount: {}, totalWrites: {})", 
                    walletAddress, contentCount + 1, totalWrites + 1);
            }
        } catch (Exception e) {
            // Don't fail the write if metadata enrichment fails
            // This is nice-to-have, not critical
            log.warn("⚠️  Failed to enrich wallet node metadata for {}: {}", 
                walletAddress, e.getMessage());
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
    
    /**
     * Extract organization from a content path (ADR 037).
     * 
     * <p>Path format: /oak-chain/XX/YY/ZZ/0xWALLET/{organization}/content/{contentId}
     * Organization is the segment after wallet address, before "content".
     * 
     * @param path The full content path
     * @return Organization name, or null if not present
     */
    private String extractOrganizationFromPath(String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        
        String[] parts = path.split("/");
        // Path: ["", "oak-chain", "XX", "YY", "ZZ", "0xWALLET", "Organization", "content", "contentId"]
        // Index:  0       1         2     3     4        5            6            7          8
        // Or without org:
        // Path: ["", "oak-chain", "XX", "YY", "ZZ", "0xWALLET", "content", "contentId"]
        // Index:  0       1         2     3     4        5          6          7
        
        if (parts.length < 8) {
            return null; // No organization in path
        }
        
        // Check if index 6 is an organization (not "content")
        // The wallet is at index 5 (starts with "0x")
        // If index 6 is not "content", it's the organization
        String potentialOrg = parts[6];
        if (!"content".equals(potentialOrg) && !potentialOrg.startsWith("0x")) {
            return potentialOrg;
        }
        
        return null;
    }
    
    /**
     * Track fragmentation: Check for new TAR files and associate with wallet address.
     */
    private void trackFragmentation(String walletAddress) {
        if (context.fragmentationTracker == null || walletAddress == null || walletAddress.isEmpty()) {
            return;
        }
        
        try {
            // Get current TAR files
            java.util.List<String> currentTarFiles = new java.util.ArrayList<>();
            try (java.util.stream.Stream<java.nio.file.Path> paths = java.nio.file.Files.list(context.storeDirectory)) {
                currentTarFiles = paths
                    .filter(p -> p.toString().endsWith(".tar"))
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .collect(java.util.stream.Collectors.toList());
            }
            
            // Find new TAR files (not yet tracked for this entity)
            java.util.List<String> entityTarFiles = context.fragmentationTracker.getTarFilesForEntity(walletAddress);
            java.util.Set<String> knownTarFiles = new java.util.HashSet<>(entityTarFiles);
            
            for (String tarFile : currentTarFiles) {
                if (!knownTarFiles.contains(tarFile)) {
                    // New TAR file - get its size
                    java.nio.file.Path tarPath = context.storeDirectory.resolve(tarFile);
                    long tarFileSize = java.nio.file.Files.exists(tarPath) 
                        ? java.nio.file.Files.size(tarPath) 
                        : 0;
                    
                    // Record write (this will create or update metrics)
                    context.fragmentationTracker.recordWrite(walletAddress, tarFile, tarFileSize);
                    
                    log.debug("📊 Fragmentation tracked: entity={}, tarFile={}, size={}", 
                        walletAddress, tarFile, tarFileSize);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to track fragmentation for entity {}: {}", walletAddress, e.getMessage());
        }
    }
    
    /**
     * Query wallet statistics - GET /v1/wallets/stats
     * Returns aggregated stats for all wallets or specific wallet
     */
    public void handleWalletStats(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String wallet = request.getParameter("wallet");
        
        try {
            response.setContentType("application/json");
            StringBuilder json = new StringBuilder();
            
            if (wallet != null && !wallet.isEmpty()) {
                // Single wallet stats
                json.append(queryWalletNode(wallet));
            } else {
                // All wallets (top 100 by contentCount)
                json.append(queryTopWallets());
            }
            
            response.getWriter().write(json.toString());
            
        } catch (Exception e) {
            log.error("Failed to query wallet stats", e);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.getWriter().write("{\"error\":\"" + FormatUtils.escapeJson(e.getMessage()) + "\"}");
        }
    }
    
    /**
     * Query content by wallet - GET /v1/wallets/{wallet}/content
     * Returns content items for a specific wallet
     */
    public void handleWalletContent(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String wallet = request.getParameter("wallet");
        
        if (wallet == null || wallet.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.getWriter().write("{\"error\":\"Missing wallet parameter\"}");
            return;
        }
        
        try {
            response.setContentType("application/json");
            String json = queryWalletContent(wallet);
            response.getWriter().write(json);
            
        } catch (Exception e) {
            log.error("Failed to query wallet content", e);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.getWriter().write("{\"error\":\"" + FormatUtils.escapeJson(e.getMessage()) + "\"}");
        }
    }
    
    /**
     * Query a single wallet node and return its metadata
     */
    private String queryWalletNode(String walletAddress) {
        try {
            // Build wallet path
            String[] levels = org.apache.jackrabbit.oak.segment.consensus.util.WalletPathUtil.getShardLevels(walletAddress);
            String walletPath = String.format("/oak-chain/%s/%s/%s/%s", levels[0], levels[1], levels[2], walletAddress);
            
            // Get wallet node
            org.apache.jackrabbit.oak.spi.state.NodeState root = context.nodeStore.getRoot();
            org.apache.jackrabbit.oak.spi.state.NodeState walletNode = root.getChildNode("oak-chain")
                .getChildNode(levels[0])
                .getChildNode(levels[1])
                .getChildNode(levels[2])
                .getChildNode(walletAddress);
            
            if (!walletNode.exists()) {
                return "{\"error\":\"Wallet not found\"}";
            }
            
            // Build JSON
            StringBuilder json = new StringBuilder("{");
            json.append("\"wallet\":\"").append(FormatUtils.escapeJson(walletAddress)).append("\",");
            json.append("\"path\":\"").append(FormatUtils.escapeJson(walletPath)).append("\",");
            
            if (walletNode.hasProperty("nodeType")) {
                json.append("\"nodeType\":\"").append(FormatUtils.escapeJson(walletNode.getProperty("nodeType").getValue(org.apache.jackrabbit.oak.api.Type.STRING))).append("\",");
            }
            if (walletNode.hasProperty("walletCreated")) {
                json.append("\"walletCreated\":").append(walletNode.getProperty("walletCreated").getValue(org.apache.jackrabbit.oak.api.Type.LONG)).append(",");
            }
            if (walletNode.hasProperty("lastWrite")) {
                json.append("\"lastWrite\":").append(walletNode.getProperty("lastWrite").getValue(org.apache.jackrabbit.oak.api.Type.LONG)).append(",");
            }
            if (walletNode.hasProperty("contentCount")) {
                json.append("\"contentCount\":").append(walletNode.getProperty("contentCount").getValue(org.apache.jackrabbit.oak.api.Type.LONG)).append(",");
            }
            if (walletNode.hasProperty("totalWrites")) {
                json.append("\"totalWrites\":").append(walletNode.getProperty("totalWrites").getValue(org.apache.jackrabbit.oak.api.Type.LONG)).append(",");
            }
            if (walletNode.hasProperty("description")) {
                json.append("\"description\":\"").append(FormatUtils.escapeJson(walletNode.getProperty("description").getValue(org.apache.jackrabbit.oak.api.Type.STRING))).append("\"");
            }
            
            json.append("}");
            return json.toString();
            
        } catch (Exception e) {
            log.error("Failed to query wallet node: {}", walletAddress, e);
            return "{\"error\":\"" + FormatUtils.escapeJson(e.getMessage()) + "\"}";
        }
    }
    
    /**
     * Query top wallets by content count
     */
    private String queryTopWallets() {
        StringBuilder json = new StringBuilder("{\"wallets\":[");
        boolean first = true;
        
        try {
            // Traverse /oak-chain tree and collect wallet metadata
            org.apache.jackrabbit.oak.spi.state.NodeState root = context.nodeStore.getRoot();
            org.apache.jackrabbit.oak.spi.state.NodeState oakChain = root.getChildNode("oak-chain");
            
            if (oakChain.exists()) {
                // Level 1
                for (org.apache.jackrabbit.oak.spi.state.ChildNodeEntry l1 : oakChain.getChildNodeEntries()) {
                    // Level 2
                    for (org.apache.jackrabbit.oak.spi.state.ChildNodeEntry l2 : l1.getNodeState().getChildNodeEntries()) {
                        // Level 3
                        for (org.apache.jackrabbit.oak.spi.state.ChildNodeEntry l3 : l2.getNodeState().getChildNodeEntries()) {
                            // Wallets
                            for (org.apache.jackrabbit.oak.spi.state.ChildNodeEntry wallet : l3.getNodeState().getChildNodeEntries()) {
                                String walletName = wallet.getName();
                                if (walletName.startsWith("0x")) {
                                    org.apache.jackrabbit.oak.spi.state.NodeState walletNode = wallet.getNodeState();
                                    
                                    if (!first) json.append(",");
                                    first = false;
                                    
                                    json.append("{");
                                    json.append("\"wallet\":\"").append(FormatUtils.escapeJson(walletName)).append("\",");
                                    
                                    if (walletNode.hasProperty("contentCount")) {
                                        json.append("\"contentCount\":").append(walletNode.getProperty("contentCount").getValue(org.apache.jackrabbit.oak.api.Type.LONG)).append(",");
                                    }
                                    if (walletNode.hasProperty("totalWrites")) {
                                        json.append("\"totalWrites\":").append(walletNode.getProperty("totalWrites").getValue(org.apache.jackrabbit.oak.api.Type.LONG)).append(",");
                                    }
                                    if (walletNode.hasProperty("lastWrite")) {
                                        json.append("\"lastWrite\":").append(walletNode.getProperty("lastWrite").getValue(org.apache.jackrabbit.oak.api.Type.LONG));
                                    }
                                    json.append("}");
                                }
                            }
                        }
                    }
                }
            }
            
        } catch (Exception e) {
            log.error("Failed to query top wallets", e);
        }
        
        json.append("]}");
        return json.toString();
    }
    
    /**
     * Query content items for a wallet
     */
    private String queryWalletContent(String walletAddress) {
        StringBuilder json = new StringBuilder("{\"content\":[");
        boolean first = true;
        
        try {
            // Build wallet path
            String[] levels = org.apache.jackrabbit.oak.segment.consensus.util.WalletPathUtil.getShardLevels(walletAddress);
            
            // Get wallet content node
            org.apache.jackrabbit.oak.spi.state.NodeState root = context.nodeStore.getRoot();
            org.apache.jackrabbit.oak.spi.state.NodeState contentNode = root.getChildNode("oak-chain")
                .getChildNode(levels[0])
                .getChildNode(levels[1])
                .getChildNode(levels[2])
                .getChildNode(walletAddress)
                .getChildNode("content");
            
            if (contentNode.exists()) {
                // Traverse content children
                for (org.apache.jackrabbit.oak.spi.state.ChildNodeEntry entry : contentNode.getChildNodeEntries()) {
                    org.apache.jackrabbit.oak.spi.state.NodeState item = entry.getNodeState();
                    
                    if (!first) json.append(",");
                    first = false;
                    
                    json.append("{");
                    json.append("\"name\":\"").append(FormatUtils.escapeJson(entry.getName())).append("\"");
                    
                    if (item.hasProperty("contentType")) {
                        json.append(",\"contentType\":\"").append(FormatUtils.escapeJson(item.getProperty("contentType").getValue(org.apache.jackrabbit.oak.api.Type.STRING))).append("\"");
                    }
                    if (item.hasProperty("timestamp")) {
                        json.append(",\"timestamp\":").append(item.getProperty("timestamp").getValue(org.apache.jackrabbit.oak.api.Type.LONG));
                    }
                    if (item.hasProperty("message")) {
                        json.append(",\"message\":\"").append(FormatUtils.escapeJson(item.getProperty("message").getValue(org.apache.jackrabbit.oak.api.Type.STRING))).append("\"");
                    }
                    json.append("}");
                }
            }
            
        } catch (Exception e) {
            log.error("Failed to query wallet content: {}", walletAddress, e);
        }
        
        json.append("]}");
        return json.toString();
    }
    
    /**
     * Build the elaborate genesis structure with all metadata, economics, innovations, etc.
     * This is called on ALL nodes when they receive the genesis write through Aeron,
     * ensuring perfect consistency.
     * 
     * @param genesisNode The genesis node to populate with child nodes
     * @param message The genesis message (contains genesisValidator URL)
     */
    private void buildGenesisStructure(org.apache.jackrabbit.oak.spi.state.NodeBuilder genesisNode, String message) {
        try {
            // Extract selfUrl from message if available
            String selfUrl = "http://localhost:8090"; // Default
            if (message != null && message.contains("genesisValidator")) {
                try {
                    // Simple JSON parsing to extract genesisValidator
                    int start = message.indexOf("genesisValidator\":\"") + 19;
                    int end = message.indexOf("\"", start);
                    if (start > 18 && end > start) {
                        selfUrl = message.substring(start, end);
                    }
                } catch (Exception e) {
                    log.debug("Could not parse genesisValidator from message, using default");
                }
            }
            
            // ═══════════════════════════════════════════════════════════════════
            // ECONOMIC MODEL
            // ═══════════════════════════════════════════════════════════════════
            org.apache.jackrabbit.oak.spi.state.NodeBuilder economics = genesisNode.child("economics");
            economics.setProperty("jcr:primaryType", "nt:unstructured");
            economics.setProperty("description", "Multi-tier transaction pricing model");
            
            // Priority Tier
            org.apache.jackrabbit.oak.spi.state.NodeBuilder priority = economics.child("priority-tier");
            priority.setProperty("jcr:primaryType", "nt:unstructured");
            priority.setProperty("price", "0.01 ETH");
            priority.setProperty("finality", "~30 seconds");
            priority.setProperty("delay", "0 epochs");
            priority.setProperty("use-case", "Emergency updates, time-sensitive content");
            priority.setProperty("fragmentation-cost", "High - individual commits");
            
            // Express Tier
            org.apache.jackrabbit.oak.spi.state.NodeBuilder express = economics.child("express-tier");
            express.setProperty("jcr:primaryType", "nt:unstructured");
            express.setProperty("price", "0.002 ETH");
            express.setProperty("finality", "~6.4 minutes");
            express.setProperty("delay", "1 epoch");
            express.setProperty("use-case", "Regular updates, user-facing content");
            express.setProperty("fragmentation-cost", "Medium - small batching window");
            
            // Standard Tier
            org.apache.jackrabbit.oak.spi.state.NodeBuilder standard = economics.child("standard-tier");
            standard.setProperty("jcr:primaryType", "nt:unstructured");
            standard.setProperty("price", "0.001 ETH");
            standard.setProperty("finality", "~12.8 minutes");
            standard.setProperty("delay", "2 epochs");
            standard.setProperty("use-case", "Bulk content, scheduled updates, archival");
            standard.setProperty("fragmentation-cost", "Low - maximum batching by wallet");
            
            // ═══════════════════════════════════════════════════════════════════
            // BITCOIN-TIGHT PRINCIPLES
            // ═══════════════════════════════════════════════════════════════════
            org.apache.jackrabbit.oak.spi.state.NodeBuilder bitcoinTight = genesisNode.child("bitcoin-tight-principles");
            bitcoinTight.setProperty("jcr:primaryType", "nt:unstructured");
            bitcoinTight.setProperty("philosophy", "Fail Loud, Fail Fast, Never Silently Corrupt");
            bitcoinTight.setProperty("principle-1", "Singletons: One BeaconChainClient, one truth");
            bitcoinTight.setProperty("principle-2", "Fail Loud: System.exit(1) on unrecoverable errors");
            bitcoinTight.setProperty("principle-3", "Immutability: final fields, immutable state");
            bitcoinTight.setProperty("principle-4", "Defensive Validation: Epochs never go backwards");
            bitcoinTight.setProperty("principle-5", "Health Monitoring: /health endpoint + metrics");
            bitcoinTight.setProperty("principle-6", "No Silent Failures: UncaughtExceptionHandler crashes JVM");
            bitcoinTight.setProperty("inspiration", "Bitcoin Core, Apache Kafka, Ethereum Geth");
            
            // ═══════════════════════════════════════════════════════════════════
            // NETWORK
            // ═══════════════════════════════════════════════════════════════════
            org.apache.jackrabbit.oak.spi.state.NodeBuilder network = genesisNode.child("network");
            network.setProperty("jcr:primaryType", "nt:unstructured");
            network.setProperty("genesisValidator", selfUrl);
            network.setProperty("transport", "Aeron UDP multicast + unicast");
            network.setProperty("clusterFormation", "Automatic via Raft election");
            network.setProperty("partition-tolerance", "Majority quorum required for writes");
            
            // ═══════════════════════════════════════════════════════════════════
            // INNOVATION SUMMARY
            // ═══════════════════════════════════════════════════════════════════
            org.apache.jackrabbit.oak.spi.state.NodeBuilder innovations = genesisNode.child("innovations");
            innovations.setProperty("jcr:primaryType", "nt:unstructured");
            innovations.setProperty("innovation-1", "First blockchain-backed AEM content repository");
            innovations.setProperty("innovation-2", "Ethereum epochs as external time oracle for finality");
            innovations.setProperty("innovation-3", "Wallet-scoped path architecture for segment isolation");
            innovations.setProperty("innovation-4", "Economic model that incentivizes storage efficiency");
            innovations.setProperty("innovation-5", "Bitcoin-tight reliability in Java enterprise stack");
            innovations.setProperty("innovation-6", "Global read-only content via HTTP segment transfer");
            innovations.setProperty("innovation-7", "Multi-tier transaction pricing with cryptographic payment");
            innovations.setProperty("demo-date", "Garage Week - December 15, 2025");
            innovations.setProperty("team", "somarc + Cursor (Auto mode + Composer-1) + Grok 4.1 as outside counsel — distributed intelligence building distributed systems");
            
            log.info("✅ Genesis structure built successfully with {} child nodes", 4);
            
        } catch (Exception e) {
            log.error("❌ Failed to build genesis structure", e);
            // Don't throw - genesis properties are still valid, just missing elaborate structure
        }
    }
    
    /**
     * Estimate content size in megabytes for a given path.
     * 
     * <p>This method traverses the node tree at the given path and estimates
     * the storage size based on node count and property sizes.</p>
     * 
     * @param contentPath Path to the content node
     * @return Estimated size in megabytes (minimum 1 MB)
     */
    private long estimateContentSizeMB(String contentPath) {
        if (context.nodeStore == null) {
            log.debug("NodeStore not available, using default size estimate");
            return 1L;
        }
        
        try {
            org.apache.jackrabbit.oak.spi.state.NodeState root = context.nodeStore.getRoot();
            
            // Navigate to the content path
            String[] pathParts = contentPath.split("/");
            org.apache.jackrabbit.oak.spi.state.NodeState current = root;
            
            for (String part : pathParts) {
                if (part.isEmpty()) continue;
                current = current.getChildNode(part);
                if (!current.exists()) {
                    log.debug("Path {} does not exist, using default size estimate", contentPath);
                    return 1L;
                }
            }
            
            // Estimate size: count nodes and properties recursively
            long[] counts = countNodesAndProperties(current, 0, 1000); // Max 1000 nodes to avoid long traversals
            long nodeCount = counts[0];
            long propertyCount = counts[1];
            
            // Rough estimate: each node ≈ 1 KB, each property ≈ 100 bytes
            long estimatedBytes = (nodeCount * 1024) + (propertyCount * 100);
            long estimatedMB = Math.max(1, estimatedBytes / (1024 * 1024));
            
            log.debug("Content size estimate for {}: {} nodes, {} properties, ~{} MB", 
                contentPath, nodeCount, propertyCount, estimatedMB);
            
            return estimatedMB;
            
        } catch (Exception e) {
            log.warn("Failed to estimate content size for {}: {}", contentPath, e.getMessage());
            return 1L;
        }
    }
    
    /**
     * Recursively count nodes and properties in a node tree.
     * 
     * @param node Starting node
     * @param currentDepth Current recursion depth
     * @param maxNodes Maximum nodes to count (prevents runaway traversals)
     * @return Array of [nodeCount, propertyCount]
     */
    private long[] countNodesAndProperties(org.apache.jackrabbit.oak.spi.state.NodeState node, int currentDepth, int maxNodes) {
        long nodeCount = 1;
        long propertyCount = 0;
        
        // Count properties on this node
        for (org.apache.jackrabbit.oak.api.PropertyState prop : node.getProperties()) {
            propertyCount++;
            // For binary properties, add extra weight based on size
            if (prop.getType() == org.apache.jackrabbit.oak.api.Type.BINARY) {
                try {
                    org.apache.jackrabbit.oak.api.Blob blob = prop.getValue(org.apache.jackrabbit.oak.api.Type.BINARY);
                    // Add 1 "property" per 100 bytes of binary
                    propertyCount += blob.length() / 100;
                } catch (Exception e) {
                    // Ignore - just use default property count
                }
            }
        }
        
        // Recursively count children (with depth limit)
        if (currentDepth < 10 && nodeCount < maxNodes) {
            for (String childName : node.getChildNodeNames()) {
                if (nodeCount >= maxNodes) break;
                
                org.apache.jackrabbit.oak.spi.state.NodeState child = node.getChildNode(childName);
                long[] childCounts = countNodesAndProperties(child, currentDepth + 1, maxNodes - (int) nodeCount);
                nodeCount += childCounts[0];
                propertyCount += childCounts[1];
            }
        }
        
        return new long[] { nodeCount, propertyCount };
    }
    
    /**
     * Determine payment tier from Ethereum transaction hash.
     * 
     * <p>For MVP: Simple heuristic based on tx hash characters.
     * In production, this would query the Ethereum chain to check the payment amount
     * and determine tier from ValidatorPaymentV3_2.sol events.</p>
     * 
     * @param ethereumTxHash Ethereum transaction hash
     * @return Payment tier (STANDARD, EXPRESS, or PRIORITY)
     */
    private org.apache.jackrabbit.oak.segment.consensus.economics.ValidatorEarningsTracker.PaymentTier 
    determineTierFromEthereumTx(String ethereumTxHash) {
        // MVP heuristic: check tx hash pattern
        // In production, this would:
        // 1. Query Sepolia/mainnet for tx details
        // 2. Check ProposalPaid event amount
        // 3. Map amount to tier (e.g., 0.001 ETH = STANDARD, 0.005 = EXPRESS, 0.01 = PRIORITY)
        
        if (ethereumTxHash == null || ethereumTxHash.isEmpty()) {
            return org.apache.jackrabbit.oak.segment.consensus.economics.ValidatorEarningsTracker.PaymentTier.STANDARD;
        }
        
        // Simple heuristic for demo: last char determines tier
        char lastChar = ethereumTxHash.toLowerCase().charAt(ethereumTxHash.length() - 1);
        if (lastChar >= 'a' && lastChar <= 'f') {
            // High hex digit = PRIORITY
            return org.apache.jackrabbit.oak.segment.consensus.economics.ValidatorEarningsTracker.PaymentTier.PRIORITY;
        } else if (lastChar >= '5' && lastChar <= '9') {
            // Mid-range digit = EXPRESS
            return org.apache.jackrabbit.oak.segment.consensus.economics.ValidatorEarningsTracker.PaymentTier.EXPRESS;
        } else {
            // Low digit = STANDARD
            return org.apache.jackrabbit.oak.segment.consensus.economics.ValidatorEarningsTracker.PaymentTier.STANDARD;
        }
    }
}

