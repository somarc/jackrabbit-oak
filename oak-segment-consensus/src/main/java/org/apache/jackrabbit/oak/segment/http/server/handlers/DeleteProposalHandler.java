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

import org.apache.jackrabbit.oak.segment.consensus.economics.ValidatorEarningsTracker;
import org.apache.jackrabbit.oak.segment.consensus.queue.ProposalQueuePolicy;
import org.apache.jackrabbit.oak.segment.consensus.sharding.ShardWriteAuthorityEnforcer;
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

        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // ADR 028: PRE-FLIGHT HEALTH CHECK
        // Prevent silent proposal loss by rejecting requests when cluster unhealthy
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        if (context.aeronConsensusEngine == null || !context.aeronConsensusEngine.isClusterHealthy()) {
            String reason = context.aeronConsensusEngine != null
                ? context.aeronConsensusEngine.getUnhealthyReason()
                : "consensus_engine_not_configured";
            log.warn("❌ Cluster unhealthy, rejecting delete proposal: {}", reason);
            context.apiRejectedRequests.incrementAndGet();
            ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                "Cluster unhealthy: " + reason + ". Please retry in a few seconds.");
            return;
        }

        try {
            org.apache.jackrabbit.oak.segment.consensus.config.BlockchainConfig blockchainConfig =
                org.apache.jackrabbit.oak.segment.consensus.config.BlockchainConfig.getInstance();

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

            // ✅ REFACTORED: Validate Ethereum address using WalletValidator
            ValidationResult<String> walletValidation = WalletValidator.validate(wallet);
            if (!walletValidation.isValid()) {
                ApiErrorUtil.sendJsonError(response, walletValidation.getHttpStatus(), walletValidation.getError());
                return;
            }
            String normalizedWallet = walletValidation.getNormalizedValue();

            if (!ShardWriteAuthorityEnforcer.allowLocalWrite(
                context,
                normalizedWallet,
                "/v1/propose-delete",
                response
            )) {
                return;
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

            // Verify path ownership: must be under /oak-chain/{shard}/
            String shardRoot = WalletPathUtil.getShardRoot(normalizedWallet);
            if (!contentPath.toLowerCase().startsWith(shardRoot + "/")) {
                log.warn("🚫 Delete proposal rejected: Path ownership violation");
                log.warn("   Content path: {}", contentPath);
                log.warn("   Expected shard root: {}", shardRoot);
                ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_FORBIDDEN,
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
                ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_BAD_REQUEST,
                    "Missing ethereumTxHash parameter. Deletes require Ethereum payment (like writes). " +
                    "Release is adaptive after verification; paymentTier remains a compatibility/economic selector."
                );
                return;
            }

            String paymentTier = request.getParameter("paymentTier");
            ValidatorEarningsTracker.PaymentTier tier = ValidatorEarningsTracker.PaymentTier.STANDARD;
            if (paymentTier != null && !paymentTier.trim().isEmpty()) {
                tier = parsePaymentTier(paymentTier);
                if (tier == null) {
                    ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_BAD_REQUEST,
                        "Invalid paymentTier: '" + paymentTier + "'. Must be 'standard', 'express', or 'priority'.");
                    return;
                }
            }

            String clientProposalId = request.getParameter("proposalId");
            String proposalId;
            if (clientProposalId != null && !clientProposalId.trim().isEmpty()) {
                proposalId = clientProposalId.trim();
                if (!isValidClientProposalId(proposalId)) {
                    ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_BAD_REQUEST,
                        "Invalid proposalId format. Expected 0x-prefixed 32-byte hex or UUID.");
                    return;
                }
                if (proposalId.startsWith("0X")) {
                    proposalId = "0x" + proposalId.substring(2);
                }
            } else if (!blockchainConfig.isMockMode()) {
                ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_BAD_REQUEST,
                    "Chain-backed deletes require a client-supplied proposalId from the settlement contract flow (expected 0x-prefixed 32-byte hex).");
                return;
            } else {
                proposalId = java.util.UUID.randomUUID().toString();
            }

            if (!blockchainConfig.isMockMode() && !isChainBackedProposalId(proposalId)) {
                ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_BAD_REQUEST,
                    "Chain-backed deletes require proposalId to be a 0x-prefixed 32-byte hex value. UUID proposalIds are mock-only.");
                return;
            }

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
            Map<String, Object> links = new LinkedHashMap<>();
            links.put("self", "/v1/ops/operations/" + proposalId);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("contractVersion", "ops.v1");
            payload.put("status", "accepted");
            payload.put("operationId", proposalId);
            payload.put("receivedAtMs", System.currentTimeMillis());
            payload.put("ackState", "ACCEPTED");
            payload.put("links", links);
            payload.put("proposalId", proposalId);
            payload.put("proposalIdSource", clientProposalId != null && !clientProposalId.trim().isEmpty() ? "client" : "server");
            payload.put("type", "DELETE");
            payload.put("state", "PENDING");
            payload.put("message", "Delete proposal queued, waiting for Ethereum confirmation");
            payload.put("ethereumTxHash", ethereumTxHash);
            payload.put("tier", String.valueOf(tier));
            payload.put("timeoutTimestamp", System.currentTimeMillis() + ProposalQueuePolicy.confirmationTimeoutMs());
            payload.put("wallet", wallet);
            payload.put("contentPath", contentPath);
            payload.put("gcDebtIncurred", gcDebtIncurred.toString());
            payload.put("totalDebt", totalDebt.toString());
            payload.put("pendingDebt", pendingDebt.toString());
            payload.put("writesBlocked", writesBlocked);
            response.getWriter().write(JsonOutputUtil.toJson(payload));
            log.info("✅ DELETE proposal {} queued successfully (tier: {}, path: {})", proposalId, tier, contentPath);

        } catch (Exception e) {
            log.error("❌ Delete proposal failed", e);
            ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Delete proposal failed: " + e.getMessage());
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

    private ValidatorEarningsTracker.PaymentTier parsePaymentTier(String paymentTier) {
        String normalized = paymentTier.trim().toLowerCase();
        switch (normalized) {
            case "standard":
                return ValidatorEarningsTracker.PaymentTier.STANDARD;
            case "express":
                return ValidatorEarningsTracker.PaymentTier.EXPRESS;
            case "priority":
                return ValidatorEarningsTracker.PaymentTier.PRIORITY;
            default:
                return null;
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

    private static boolean isChainBackedProposalId(String proposalId) {
        return proposalId != null && proposalId.trim().matches("(?i)^0x[a-f0-9]{64}$");
    }

}
