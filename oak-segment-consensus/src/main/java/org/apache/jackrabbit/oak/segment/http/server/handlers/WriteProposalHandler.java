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

import org.apache.jackrabbit.oak.segment.consensus.queue.ProposalQueuePolicy;
import org.apache.jackrabbit.oak.segment.consensus.queue.QueuedProposal;
import org.apache.jackrabbit.oak.segment.consensus.metrics.ConsensusMetrics;
import org.apache.jackrabbit.oak.segment.consensus.util.WalletPathUtil;
import org.apache.jackrabbit.oak.segment.consensus.validation.ValidationResult;
import org.apache.jackrabbit.oak.segment.consensus.validation.WalletValidator;
import org.apache.jackrabbit.oak.segment.http.server.ServerContext;
import org.apache.jackrabbit.oak.segment.http.server.model.ClientRegistration;
import org.apache.jackrabbit.oak.segment.http.server.util.ApiErrorUtil;
import org.apache.jackrabbit.oak.segment.http.server.util.JsonOutputUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Handler for write proposals (`/v1/propose-write`).
 */
public class WriteProposalHandler {

    private static final Logger log = LoggerFactory.getLogger(WriteProposalHandler.class);

    private final ServerContext context;

    public WriteProposalHandler(ServerContext context) {
        this.context = context;
    }

    /**
     * Handle POST /v1/propose-write - Signed write transaction endpoint.
     */
    public void handleProposeWrite(HttpServletRequest request, HttpServletResponse response) throws IOException {
        // Check if Aeron consensus engine is configured (ONLY mode supported)
        if (context.aeronConsensusEngine == null) {
            ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Aeron consensus engine not configured");
            return;
        }

        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // ADR 028: PRE-FLIGHT HEALTH CHECK
        // Prevent silent proposal loss by rejecting requests when cluster unhealthy
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        if (!context.aeronConsensusEngine.isClusterHealthy()) {
            String reason = context.aeronConsensusEngine.getUnhealthyReason();
            log.warn("❌ Cluster unhealthy, rejecting proposal: {}", reason);
            context.apiRejectedRequests.incrementAndGet();
            ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                "Cluster unhealthy: " + reason + ". Please retry in a few seconds.");
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
            String clientProposalId = null;
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
                log.debug("📦 Processing MULTIPART form data upload");

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
                        log.debug("📎 Received file: {} ({} bytes, {})", fileName, binaryBytes.length, mimeType);

                    } else {
                        // This is a form field
                        String value = new String(part.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);

                        switch (partName) {
                            case "walletAddress": case "wallet": wallet = value; break;
                            case "signature": signature = value; break;
                            case "message": message = value; break;
                            case "contentType": contentType = value; break;
                            case "ethereumTxHash": ethereumTxHash = value; break;
                            case "proposalId": clientProposalId = value; break;
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
                clientProposalId = request.getParameter("proposalId");
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

            // ✅ REFACTORED: Validate Ethereum address using WalletValidator
            ValidationResult<String> walletValidation = WalletValidator.validate(wallet);
            if (!walletValidation.isValid()) {
                context.apiRejectedRequests.incrementAndGet();
                log.warn("❌ API REJECTED: {}", walletValidation.getError());
                ApiErrorUtil.sendJsonError(response, walletValidation.getHttpStatus(), walletValidation.getError());
                return;
            }
            String normalizedWallet = walletValidation.getNormalizedValue();

            // ============================================================
            // ORGANIZATION VALIDATION (ADR 037)
            // ============================================================
            // Optional: allows multi-brand wallets (one wallet, multiple orgs)
            String orgValidationError = WalletPathUtil.validateOrganization(organization);
            if (orgValidationError != null) {
                context.apiRejectedRequests.incrementAndGet();
                log.warn("❌ API REJECTED: Invalid organization '{}': {}", organization, orgValidationError);
                ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_BAD_REQUEST, orgValidationError);
                return;
            }
            if (organization != null && !organization.isEmpty()) {
                log.debug("🏢 Organization: {} (wallet: {})", organization, normalizedWallet.substring(0, 10) + "...");
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
                    ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_FORBIDDEN,
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
                    ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_FORBIDDEN,
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

            // ============================================================
            // IPFS SUPPLY CHAIN POLICY ENFORCEMENT
            // - supply-chain clients: validator-hosted IPFS only (intent/file)
            // - enterprise clients: may use ipfsCid, but only if CID is known to validator
            // ============================================================
            if (ipfsCid != null) {
                ipfsCid = ipfsCid.trim();
                if (ipfsCid.isEmpty()) {
                    ipfsCid = null;
                }
            }
            if (intentToken != null) {
                intentToken = intentToken.trim();
                if (intentToken.isEmpty()) {
                    intentToken = null;
                }
            }

            boolean hasClientIpfsCid = ipfsCid != null;
            boolean hasBinaryPayload = binaryBytes != null && binaryBytes.length > 0;
            boolean hasIntentToken = intentToken != null;

            if (hasClientIpfsCid && hasBinaryPayload) {
                context.apiRejectedRequests.incrementAndGet();
                context.apiIpfsPolicyRejectAmbiguousSource.incrementAndGet();
                ConsensusMetrics.recordIpfsPolicyRejection("ambiguous_source");
                ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_BAD_REQUEST,
                    "Ambiguous binary source: provide either ipfsCid or validator-hosted binary payload, not both.");
                return;
            }
            if (hasClientIpfsCid && hasIntentToken) {
                context.apiRejectedRequests.incrementAndGet();
                context.apiIpfsPolicyRejectAmbiguousSource.incrementAndGet();
                ConsensusMetrics.recordIpfsPolicyRejection("ambiguous_source");
                ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_BAD_REQUEST,
                    "Ambiguous binary source: provide either ipfsCid or intentToken, not both.");
                return;
            }
            if (hasClientIpfsCid && !clientReg.isEnterpriseClient()) {
                context.apiRejectedRequests.incrementAndGet();
                context.apiIpfsPolicyRejectNonEnterpriseCid.incrementAndGet();
                ConsensusMetrics.recordIpfsPolicyRejection("non_enterprise_client");
                ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_FORBIDDEN,
                    "client_ipfs_cid_requires_enterprise_registration",
                    "Client-side ipfsCid is restricted to registered enterprise clients. " +
                    "Use validator-hosted binary upload (intentToken or multipart/base64) for supply-chain clients.");
                return;
            }
            if (hasClientIpfsCid) {
                if (context.cidMappingService == null) {
                    context.apiRejectedRequests.incrementAndGet();
                    context.apiIpfsPolicyRejectCidServiceUnavailable.incrementAndGet();
                    ConsensusMetrics.recordIpfsPolicyRejection("cid_service_unavailable");
                    ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                        "CID provenance service unavailable. Cannot validate external ipfsCid.");
                    return;
                }
                Optional<String> knownBlobId = context.cidMappingService.getOakBlobId(ipfsCid);
                if (knownBlobId.isEmpty()) {
                    context.apiRejectedRequests.incrementAndGet();
                    context.apiIpfsPolicyRejectUnknownCid.incrementAndGet();
                    ConsensusMetrics.recordIpfsPolicyRejection("unknown_cid");
                    ApiErrorUtil.sendJsonError(response, 422,
                        "unknown_ipfs_cid",
                        "ipfsCid is not known to validator CID mappings. " +
                        "Upload via validator-hosted flow first, or register/ingest CID through enterprise pipeline.");
                    return;
                }
                log.debug("🔐 Enterprise ipfsCid accepted for wallet {}: cid={} mappedBlob={}",
                    normalizedWallet, ipfsCid, knownBlobId.get());
                context.apiIpfsPolicyAcceptedEnterpriseCid.incrementAndGet();
                ConsensusMetrics.recordEnterpriseCidAccepted();
            }

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

                    Map<String, Object> errorPayload = new LinkedHashMap<>();
                    errorPayload.put("success", false);
                    errorPayload.put("error", "Writes blocked due to unpaid GC debt. Please pay debt to resume.");
                    errorPayload.put("code", "write_blocked_gc_debt");
                    errorPayload.put("status", 402);
                    errorPayload.put("timestamp", System.currentTimeMillis());
                    errorPayload.put("wallet", normalizedWallet);
                    errorPayload.put("totalDebt", account.totalDebt.toString());
                    errorPayload.put("executedDebt", account.executedDebt.toString());
                    errorPayload.put("pendingDebt", account.getPendingDebt().toString());
                    errorPayload.put("debtLimit", account.debtLimit.toString());
                    errorPayload.put("amountOverLimit", account.totalDebt.subtract(account.debtLimit).toString());
                    errorPayload.put("paymentUrl", "/v1/gc/account/" + normalizedWallet + "/pay");
                    errorPayload.put("statusUrl", "/v1/gc/account/" + normalizedWallet);
                    response.getWriter().write(JsonOutputUtil.toJson(errorPayload));

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
                ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_BAD_REQUEST,
                    "Missing signature. All writes require a signature (even in mock mode for testing)."
                );
                return;
            }

            // Validate signature format: must start with 0x and be hex
            signature = signature.trim();
            if (!signature.startsWith("0x")) {
                context.apiRejectedRequests.incrementAndGet();
                log.warn("❌ API REJECTED: Signature must start with '0x': {}", signature);
                ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_BAD_REQUEST,
                    "Invalid signature format: must start with '0x'"
                );
                return;
            }

            // Validate signature is valid hex after 0x prefix
            String sigHex = signature.substring(2);
            if (sigHex.isEmpty()) {
                context.apiRejectedRequests.incrementAndGet();
                log.warn("❌ API REJECTED: Signature too short: {}", signature);
                ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_BAD_REQUEST,
                    "Invalid signature: too short (need hex data after 0x)"
                );
                return;
            }

            if (!sigHex.matches("[a-fA-F0-9]+")) {
                context.apiRejectedRequests.incrementAndGet();
                log.warn("❌ API REJECTED: Signature contains non-hex characters: {}", signature);
                ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_BAD_REQUEST,
                    "Invalid signature format: must be valid hexadecimal after '0x'"
                );
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
                    ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_UNAUTHORIZED,
                        "Signature verification failed. The signature does not match the claimed wallet address."
                    );
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
                ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_BAD_REQUEST,
                    "Missing ethereumTxHash parameter. Must provide Ethereum transaction hash from authorizeWrite() call."
                );
                return;
            }

            // Validate tx hash format: must start with 0x and be valid hex
            ethereumTxHash = ethereumTxHash.trim();
            if (!ethereumTxHash.startsWith("0x")) {
                context.apiRejectedRequests.incrementAndGet();
                log.warn("❌ API REJECTED: Transaction hash must start with '0x': {}", ethereumTxHash);
                ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_BAD_REQUEST,
                    "Invalid ethereumTxHash format: must start with '0x'"
                );
                return;
            }

            String txHex = ethereumTxHash.substring(2);
            if (txHex.length() < 8) {  // Minimum reasonable tx hash length
                context.apiRejectedRequests.incrementAndGet();
                log.warn("❌ API REJECTED: Transaction hash too short: {}", ethereumTxHash);
                ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_BAD_REQUEST,
                    "Invalid ethereumTxHash: too short (expected at least 8 hex characters)"
                );
                return;
            }

            if (!txHex.matches("[a-fA-F0-9]+")) {
                context.apiRejectedRequests.incrementAndGet();
                log.warn("❌ API REJECTED: Transaction hash contains non-hex characters: {}", ethereumTxHash);
                ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_BAD_REQUEST,
                    "Invalid ethereumTxHash format: must be valid hexadecimal"
                );
                return;
            }

            // ADR 059: Validator-hosted binary uploads are governed by explicit backend policy.
            if (binaryBytes != null && binaryBytes.length > 0) {
                if (!ProposalQueuePolicy.isValidatorHostedBinaryUploadEnabled()) {
                    context.apiRejectedRequests.incrementAndGet();
                    ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_FORBIDDEN,
                        "validator_binary_upload_disabled",
                        "Validator-hosted binary upload is currently disabled. " +
                        "For default client-side IPFS, upload to IPFS and pass ipfsCid instead.");
                    return;
                }
                if (ProposalQueuePolicy.isValidatorHostedBinaryRequiresPriorityTier() && !isPriorityTier(paymentTier)) {
                    context.apiRejectedRequests.incrementAndGet();
                    ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_PAYMENT_REQUIRED,
                        "validator_binary_requires_priority",
                        "Validator-hosted binary upload requires paymentTier=priority. " +
                        "For default client-side IPFS, upload to IPFS and pass ipfsCid instead.");
                    return;
                }
            }

            // ============================================================
            // BINARY UPLOAD TO BLOBSTORE
            // Supports: Multipart (preferred), base64 (legacy), ADR 020 (future)
            // ============================================================
            String blobId = null;

            // 📦 EAGER BINARY UPLOAD: If binary bytes are available, upload to BlobStore
            if (binaryBytes != null && binaryBytes.length > 0 && context.blobStore != null) {
                try {
                    log.debug("📦 Uploading binary to BlobStore ({} bytes, {})", binaryBytes.length, mimeType);

                    // Upload to BlobStore (IPFS or other configured store)
                    java.io.InputStream binaryStream = new java.io.ByteArrayInputStream(binaryBytes);
                    blobId = context.blobStore.writeBlob(binaryStream);

                    log.debug("✅ Binary uploaded to BlobStore: {} ({} bytes, mime: {})",
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
                                        log.debug("📎 Registered CID mapping: {} → {}", blobId, derivedIpfsCid);
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
            String[] shardLevels = WalletPathUtil.getShardLevels(normalizedWallet);
            String shardId = String.join("-", shardLevels);
            String contentRoot = WalletPathUtil.getContentPath(normalizedWallet, organization);
            log.debug("🪣 Using wallet shard: {} (org: {}, contentRoot: {})", shardId,
                organization != null ? organization : "none", contentRoot);

            // Generate content ID and full path
            String contentId = contentType + "-" + System.currentTimeMillis();
            String fullPath = contentRoot + "/" + contentId;

            // V5 alignment: allow client to supply on-chain proposalId (e.g. bytes32 from authorizeWrite()).
            String proposalId;
            if (clientProposalId != null && !clientProposalId.trim().isEmpty()) {
                proposalId = clientProposalId.trim();
                if (!isValidClientProposalId(proposalId)) {
                    context.apiRejectedRequests.incrementAndGet();
                    ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_BAD_REQUEST,
                        "Invalid proposalId format. Expected 0x-prefixed 32-byte hex or UUID.");
                    return;
                }
                if (proposalId.startsWith("0X")) {
                    proposalId = "0x" + proposalId.substring(2);
                }
            } else {
                proposalId = java.util.UUID.randomUUID().toString();
            }

            // Check if proposal queue manager is available
            if (context.proposalQueueManager == null) {
                log.warn("⚠️  ProposalQueueManager not available - falling back to immediate append");
                // Fallback: immediate append (for backward compatibility)
                if (context.aeronConsensusEngine == null) {
                    ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                        "AeronConsensusEngine not initialized"
                    );
                    return;
                }

                boolean success = context.aeronConsensusEngine.sendWriteThroughIngress(
                    normalizedWallet,
                    fullPath,
                    contentType != null ? contentType : "page",
                    message != null ? message : "",
                    signature,
                    blobId,      // Include binary reference for Aeron replication
                    mimeType,    // Include mimeType for binary handling
                    ipfsCid,
                    proposalId
                );

                if (!success) {
                    ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                        "Failed to send write through Aeron ingress channel"
                    );
                    return;
                }

                response.setContentType("application/json");
                response.setStatus(HttpServletResponse.SC_OK);
                String currentHead = context.fileStore != null
                    ? context.fileStore.getHead().getRecordId().toString()
                    : "unknown";
                Map<String, Object> resultPayload = new LinkedHashMap<>();
                resultPayload.put("success", true);
                resultPayload.put("proposalId", proposalId);
                resultPayload.put("wallet", wallet);
                resultPayload.put("contentId", contentId);
                resultPayload.put("storagePath", fullPath);
                resultPayload.put("newHead", currentHead);
                resultPayload.put("message", message);
                resultPayload.put("contentType", contentType);
                resultPayload.put("mode", "immediate");
                response.getWriter().write(JsonOutputUtil.toJson(resultPayload));
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
                    ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_BAD_REQUEST,
                        "Invalid paymentTier: '" + paymentTier + "'. Must be 'standard', 'express', or 'priority'."
                    );
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
                        ProposalQueuePolicy.requiredConfirmations()
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
                log.debug("📎 Binary blob attached to proposal {}: blobId={}, mimeType={}",
                    proposalId, blobId, mimeType);
            } else {
                log.debug("📝 Text-only proposal {} (no binary)", proposalId);
            }

            // ADR 016: Set IPFS CID from client-side upload
            if (ipfsCid != null && !ipfsCid.isEmpty()) {
                queuedProposal.setIpfsCid(ipfsCid);
                log.debug("🔗 IPFS CID attached to proposal {}: ipfsCid={}", proposalId, ipfsCid);
            }

            // Track API acceptance (proposal successfully queued)
            context.apiAcceptedRequests.incrementAndGet();

            // Return queued status (202 Accepted)
            response.setContentType("application/json");
            response.setStatus(HttpServletResponse.SC_ACCEPTED);
            Map<String, Object> links = new LinkedHashMap<>();
            links.put("self", "/v1/ops/operations/" + proposalId);
            Map<String, Object> resultPayload = new LinkedHashMap<>();
            resultPayload.put("contractVersion", "ops.v1");
            resultPayload.put("status", "accepted");
            resultPayload.put("operationId", proposalId);
            resultPayload.put("receivedAtMs", System.currentTimeMillis());
            resultPayload.put("ackState", "ACCEPTED");
            resultPayload.put("links", links);
            resultPayload.put("proposalId", proposalId);
            resultPayload.put("state", "PENDING");
            resultPayload.put("message", "Proposal queued, waiting for Ethereum confirmation");
            resultPayload.put("ethereumTxHash", ethereumTxHash);
            resultPayload.put("proposalIdSource", clientProposalId != null && !clientProposalId.trim().isEmpty() ? "client" : "server");
            resultPayload.put("timeoutTimestamp", System.currentTimeMillis() + ProposalQueuePolicy.confirmationTimeoutMs());
            resultPayload.put("wallet", wallet);
            resultPayload.put("storagePath", fullPath);
            resultPayload.put("contentType", contentType);
            response.getWriter().write(JsonOutputUtil.toJson(resultPayload));
            log.debug("✅ Proposal {} queued successfully", proposalId);

        } catch (Exception e) {
            log.error("❌ Test write failed", e);
            ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Test write failed: " + e.getMessage());
        }
    }

    private static boolean isValidClientProposalId(String proposalId) {
        if (proposalId == null) {
            return false;
        }
        String value = proposalId.trim();
        if (value.matches("(?i)^0x[a-f0-9]{64}$")) {
            return true;
        }
        return value.matches("(?i)^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$");
    }

    private static boolean isPriorityTier(String paymentTier) {
        return paymentTier != null && paymentTier.trim().equalsIgnoreCase("priority");
    }

}
