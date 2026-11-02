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

import org.apache.jackrabbit.oak.segment.consensus.config.BlockchainConfig;
import org.apache.jackrabbit.oak.segment.consensus.queue.ProposalQueuePolicy;
import org.apache.jackrabbit.oak.segment.consensus.security.EthereumSignatureVerifier;
import org.apache.jackrabbit.oak.segment.consensus.util.WalletPathUtil;
import org.apache.jackrabbit.oak.segment.consensus.validation.ValidationResult;
import org.apache.jackrabbit.oak.segment.consensus.validation.WalletValidator;
import org.apache.jackrabbit.oak.segment.consensus.genesis.CanonicalGenesisContent;
import org.apache.jackrabbit.oak.segment.http.server.ServerContext;
import org.apache.jackrabbit.oak.segment.http.server.model.ClientRegistration;
import org.apache.jackrabbit.oak.segment.http.server.util.ApiErrorUtil;
import org.apache.jackrabbit.oak.segment.http.server.util.JsonOutputUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;

/**
 * Handler for delete proposals (`/v1/propose-delete`).
 */
public class DeleteProposalHandler {

    private static final Logger log = LoggerFactory.getLogger(DeleteProposalHandler.class);

    private final ServerContext context;

    public DeleteProposalHandler(ServerContext context) {
        this.context = context;
    }

    /**
     * Handle POST /v1/propose-delete - Delete proposal endpoint.
     */
    public void handleDeleteProposal(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setContentType("application/json");

        if (ProposalRequestSupport.rejectIfUnhealthy(context, "delete proposal", response)) {
            return;
        }

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
            if (signature == null || signature.isEmpty()) {
                ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_BAD_REQUEST, "Missing signature parameter");
                return;
            }
            if (contentPath == null || contentPath.isEmpty()) {
                ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_BAD_REQUEST, "Missing contentPath parameter");
                return;
            }

            BlockchainConfig blockchainConfig = BlockchainConfig.getInstance();
            if (!blockchainConfig.isMockMode() && !EthereumSignatureVerifier.isFullVerificationAvailable()) {
                context.apiRejectedRequests.incrementAndGet();
                log.error("❌ Full Ethereum signature verification unavailable: {}",
                    EthereumSignatureVerifier.getAvailabilityReason());
                ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "Full Ethereum signature verification unavailable. Validator is missing required Bouncy Castle support.");
                return;
            }

            // ✅ REFACTORED: Validate Ethereum address using WalletValidator
            ValidationResult<String> walletValidation = WalletValidator.validate(wallet);
            if (!walletValidation.isValid()) {
                ApiErrorUtil.sendJsonError(response, walletValidation.getHttpStatus(), walletValidation.getError());
                return;
            }
            String normalizedWallet = walletValidation.getNormalizedValue();

            if (CanonicalGenesisContent.isReservedMutation(normalizedWallet, contentPath)) {
                ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_FORBIDDEN,
                    "genesis_namespace_reserved", "Ordinary deletes cannot remove the genesis namespace or its ancestors.");
                return;
            }

            String namespace = ProposalRequestSupport.resolveNamespace(
                request.getParameter("namespace"), normalizedWallet, response);
            if (namespace == null || !ProposalRequestSupport.allowShardAndLeader(
                    context, normalizedWallet, namespace, "/v1/propose-delete", response)) {
                return;
            }

            // PATH ENFORCEMENT: wallet address is the primary identifier, clientId is secondary
            ProposalRequestSupport.ClientLookup lookup =
                ProposalRequestSupport.lookupClient(context, request, normalizedWallet);
            ClientRegistration clientReg = lookup.registration;
            String clientId = lookup.clientId;

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
                ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_FORBIDDEN,
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
                    ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_FORBIDDEN,
                        String.format("Wallet mismatch: Client %s registered with wallet %s, but proposal uses %s",
                                     clientId, registeredWallet, normalizedWallet));
                    return;
                }
            }

            // Verify path ownership: allow deleting either the wallet root itself
            // or any descendant beneath it.
            String shardRoot = WalletPathUtil.getShardRoot(namespace);
            String normalizedContentPath = contentPath.toLowerCase();
            String normalizedShardRoot = shardRoot.toLowerCase();
            boolean ownsPath = normalizedContentPath.equals(normalizedShardRoot)
                || normalizedContentPath.startsWith(normalizedShardRoot + "/");
            if (!ownsPath) {
                log.warn("🚫 Delete proposal rejected: Path ownership violation");
                log.warn("   Content path: {}", contentPath);
                log.warn("   Expected shard root: {}", shardRoot);
                ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_FORBIDDEN,
                    String.format("Path ownership violation: Content at %s does not belong to wallet %s. " +
                                 "Only content at or under %s can be deleted.",
                                 contentPath, wallet, shardRoot));
                return;
            }

            log.debug("🗑️  DELETE PROPOSAL: client={}, wallet={}, path={}", clientId, wallet, contentPath);

            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            // ETHEREUM PAYMENT REQUIRED: Deletes flow through same pipeline as writes
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            String ethereumTxHash = request.getParameter("ethereumTxHash");
            if (ethereumTxHash == null || ethereumTxHash.isEmpty()) {
                ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_BAD_REQUEST,
                    "Missing ethereumTxHash parameter. Deletes require Ethereum payment (like writes). " +
                    "Release is adaptive after verification and valid deletes enter the durable backlog."
                );
                return;
            }

            String clientProposalId = request.getParameter("proposalId");
            String proposalIdError = ProposalRequestSupport.proposalIdError(clientProposalId, "settlement contract");
            if (proposalIdError != null) {
                ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_BAD_REQUEST, proposalIdError);
                return;
            }
            String proposalId = ProposalRequestSupport.normalizeProposalId(clientProposalId.trim());

            // A delete signature is an EIP-191 personal_sign over exactly the contentPath.
            if (!blockchainConfig.isMockMode()
                && !EthereumSignatureVerifier.verifySignature(contentPath, signature, normalizedWallet)) {
                context.apiRejectedRequests.incrementAndGet();
                log.warn("❌ API REJECTED: Delete signature verification failed for wallet {}", normalizedWallet);
                ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "Signature verification failed. The signature must be a personal_sign over contentPath by the claimed wallet.");
                return;
            }
            if (ProposalRequestSupport.rejectDuplicate(context, proposalId, response)) {
                return;
            }

            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            // QUEUE DELETE PROPOSAL: Same flow as writes, just different type
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            log.debug("📥 Queuing DELETE proposal {} (tx: {}), waiting for Ethereum confirmation",
                proposalId, ethereumTxHash);

            context.proposalQueueManager.queueDeleteProposal(
                proposalId,
                ethereumTxHash,
                normalizedWallet,
                contentPath,
                signature
            );

            // Return 202 Accepted (queued for processing)
            response.setContentType("application/json");
            Map<String, Object> payload = ProposalRequestSupport.acceptedEnvelope(proposalId);
            payload.put("proposalIdSource", "client");
            payload.put("type", "DELETE");
            payload.put("state", "PENDING");
            payload.put("message", "Delete proposal queued, waiting for Ethereum confirmation");
            payload.put("ethereumTxHash", ethereumTxHash);
            payload.put("timeoutTimestamp", System.currentTimeMillis() + ProposalQueuePolicy.confirmationTimeoutMs());
            payload.put("wallet", wallet);
            payload.put("contentPath", contentPath);
            JsonOutputUtil.write(response, HttpServletResponse.SC_ACCEPTED, payload);
            log.info("✅ DELETE proposal {} queued successfully (path: {})", proposalId, contentPath);

        } catch (Exception e) {
            log.error("❌ Delete proposal failed", e);
            ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Delete proposal failed: " + e.getMessage());
        }
    }
}
