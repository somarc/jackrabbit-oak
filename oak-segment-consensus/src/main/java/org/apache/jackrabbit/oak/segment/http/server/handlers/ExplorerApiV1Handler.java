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

import org.apache.jackrabbit.oak.api.Type;
import org.apache.jackrabbit.oak.segment.consensus.queue.ProposalStatus;
import org.apache.jackrabbit.oak.segment.http.server.ServerContext;
import org.apache.jackrabbit.oak.segment.http.server.util.ApiErrorUtil;
import org.apache.jackrabbit.oak.segment.http.server.util.JsonOutputUtil;
import org.apache.jackrabbit.oak.spi.state.ChildNodeEntry;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Explorer-focused API surface for external blockscan/etherscan-style UIs.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>GET /v1/explorer/summary</li>
 *   <li>GET /v1/explorer/proposals/{proposalId}</li>
 *   <li>GET /v1/explorer/wallets/{walletAddress}</li>
 *   <li>GET /v1/explorer/release-flow</li>
 *   <li>GET /v1/explorer/epochs (compatibility alias)</li>
 * </ul>
 */
public class ExplorerApiV1Handler {

    private static final Logger log = LoggerFactory.getLogger(ExplorerApiV1Handler.class);
    private final ServerContext context;

    public ExplorerApiV1Handler(ServerContext context) {
        this.context = context;
    }

    public void handleSummary(HttpServletResponse response) throws IOException {
        response.setContentType("application/json");
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("contractVersion", "explorer.v1");
            payload.put("generatedAtMs", System.currentTimeMillis());

            Map<String, Object> cluster = new LinkedHashMap<>();
            if (context.aeronConsensusEngine != null) {
                int nodeCount = Math.max(0, context.aeronConsensusEngine.getClusterSize());
                int quorum = nodeCount > 0 ? (nodeCount / 2) + 1 : 0;
                int reachable = Math.max(0, context.aeronConsensusEngine.getReachableValidatorCount());
                cluster.put("consensusType", "aeron-cluster");
                cluster.put("role", context.aeronConsensusEngine.getCurrentRole().name());
                cluster.put("isLeader", context.aeronConsensusEngine.isLeader());
                cluster.put("currentLeader", context.aeronConsensusEngine.getCurrentLeader());
                cluster.put("currentTerm", context.aeronConsensusEngine.getCurrentTerm());
                cluster.put("currentEpoch", context.aeronConsensusEngine.getCurrentEpoch());
                cluster.put("ethereumEpoch", context.aeronConsensusEngine.getCurrentEthereumEpoch());
                cluster.put("nodeCount", nodeCount);
                cluster.put("quorum", quorum);
                cluster.put("reachableValidators", reachable);
                cluster.put("clusterState", reachable >= quorum ? "HEALTHY" : "DEGRADED");
            } else {
                cluster.put("consensusType", "none");
                cluster.put("role", "STANDALONE");
                cluster.put("isLeader", true);
                cluster.put("nodeCount", 1);
                cluster.put("quorum", 1);
                cluster.put("reachableValidators", 1);
                cluster.put("clusterState", "HEALTHY");
            }
            payload.put("cluster", cluster);

            if (context.proposalQueueManager != null) {
                Map<String, Object> queue = context.proposalQueueManager.getQueueStats();
                Map<String, Object> compact = new LinkedHashMap<>();
                long verified = asLong(queue.get("verifiedCount"));
                long finalized = asLong(queue.get("totalFinalizedCount"));
                long queuePending = Math.max(asLong(queue.get("batchQueueSize")), asLong(queue.get("pendingCount")));
                long backpressurePending = asLong(queue.get("backpressurePendingCount"));
                long backpressureRaw = asLong(queue.get("backpressurePendingRawCount"));
                long backpressureMax = asLong(queue.get("backpressureMaxPending"));
                long sentCurrent = asLong(queue.get("totalProposalsSent"));
                long routingDebt = Math.max(0L, sentCurrent - finalized);
                compact.put("queuePending", queuePending);
                compact.put("pendingCount", asLong(queue.get("pendingCount")));
                compact.put("batchQueueSize", asLong(queue.get("batchQueueSize")));
                compact.put("mempoolPendingCount", asLong(queue.get("mempoolPendingCount")));
                compact.put("verified", verified);
                compact.put("finalized", finalized);
                compact.put("gap", Math.max(0L, verified - finalized));
                compact.put("rejected", asLong(queue.get("rejectedCount")));
                compact.put("backpressurePending", backpressurePending);
                compact.put("backpressurePendingRaw", backpressureRaw);
                compact.put("backpressureMax", backpressureMax);
                compact.put("backpressureActive", asBoolean(queue.get("backpressureActive")));
                compact.put("routingDebt", routingDebt);
                compact.put("releaseMode", queue.get("releaseMode"));
                compact.put("requiredConfirmations", queue.get("requiredConfirmations"));
                compact.put("verifiedResidentProposalCount", asLong(queue.get("verifiedResidentProposalCount")));
                compact.put("releaseReadyProposalCount", asLong(queue.get("releaseReadyProposalCount")));
                compact.put("backpressureOverflowProposalCount", asLong(queue.get("backpressureOverflowProposalCount")));
                if (queue.get("adaptiveReleaseGovernorState") != null) {
                    compact.put("adaptiveReleaseGovernorState", String.valueOf(queue.get("adaptiveReleaseGovernorState")));
                }
                if (queue.get("adaptiveReleaseAction") != null) {
                    compact.put("adaptiveReleaseAction", String.valueOf(queue.get("adaptiveReleaseAction")));
                }
                compact.put("currentEpoch", asLong(queue.get("currentEpoch")));
                compact.put("finalizedEpoch", asLong(queue.get("finalizedEpoch")));
                compact.put("epochsUntilFinality", asLong(queue.get("epochsUntilFinality")));

                Map<String, Object> queuePayload = new LinkedHashMap<>();
                queuePayload.put("compact", compact);
                queuePayload.put("raw", queue);
                payload.put("queue", queuePayload);
            } else {
                payload.put("queue", new LinkedHashMap<>());
            }

            Map<String, Object> identities = new LinkedHashMap<>();
            identities.put("validatorWalletAddress", context.validatorWalletAddress);
            identities.put("clusterWalletAddress", context.clusterWalletAddress);
            identities.put("registeredClients", context.registeredClients.size());
            identities.put("registeredValidators", context.registeredValidators.size());
            if (context.aeronConsensusEngine != null) {
                identities.put("reachableValidators", Math.max(0, context.aeronConsensusEngine.getReachableValidatorCount()));
                identities.put("clusterNodeCount", Math.max(0, context.aeronConsensusEngine.getClusterSize()));
            }
            payload.put("identities", identities);

            response.setStatus(HttpServletResponse.SC_OK);
            response.getWriter().write(JsonOutputUtil.toJson(payload));
        } catch (Exception e) {
            log.error("Failed explorer summary", e);
            ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Failed explorer summary: " + e.getMessage());
        }
    }

    public void handleProposalById(HttpServletResponse response, String proposalId) throws IOException {
        response.setContentType("application/json");
        if (proposalId == null || proposalId.isEmpty()) {
            ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_BAD_REQUEST, "proposalId is required");
            return;
        }
        if (context.proposalQueueManager == null) {
            ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Proposal queue not available");
            return;
        }
        try {
            ProposalStatus status = context.proposalQueueManager.getProposalStatus(proposalId);
            if (status == null) {
                ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_NOT_FOUND, "Proposal not found");
                return;
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("contractVersion", "explorer.v1");
            payload.put("generatedAtMs", System.currentTimeMillis());
            payload.put("proposalId", status.getProposalId());
            payload.put("state", status.getState().name());
            payload.put("ethereumTxHash", status.getEthereumTxHash());
            payload.put("timeoutTimestamp", status.getTimeoutTimestamp());
            payload.put("confirmedBlock", status.getConfirmedBlock());
            payload.put("rejectionReason", status.getRejectionReason());
            payload.put("durabilityState", status.getDurabilityState() != null ? status.getDurabilityState().name() : "UNKNOWN");
            payload.put("durabilityTimestamp", status.getDurabilityTimestamp());
            payload.put("durabilityError", status.getDurabilityError());
            payload.put("durableHead", status.getDurableHead());

            response.setStatus(HttpServletResponse.SC_OK);
            response.getWriter().write(JsonOutputUtil.toJson(payload));
        } catch (Exception e) {
            log.error("Failed explorer proposal lookup {}", proposalId, e);
            ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Failed explorer proposal lookup: " + e.getMessage());
        }
    }

    public void handleWalletByAddress(HttpServletResponse response, String walletAddress) throws IOException {
        response.setContentType("application/json");
        if (walletAddress == null || walletAddress.isEmpty()) {
            ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_BAD_REQUEST, "walletAddress is required");
            return;
        }

        try {
            String[] levels = org.apache.jackrabbit.oak.segment.consensus.util.WalletPathUtil.getShardLevels(walletAddress);
            NodeState root = context.nodeStore.getRoot();
            NodeState walletNode = root.getChildNode("oak-chain")
                .getChildNode(levels[0])
                .getChildNode(levels[1])
                .getChildNode(levels[2])
                .getChildNode(walletAddress);

            if (!walletNode.exists()) {
                ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_NOT_FOUND, "Wallet not found");
                return;
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("contractVersion", "explorer.v1");
            payload.put("generatedAtMs", System.currentTimeMillis());
            payload.put("wallet", walletAddress);
            payload.put("walletPath", String.format("/oak-chain/%s/%s/%s/%s", levels[0], levels[1], levels[2], walletAddress));

            Map<String, Object> walletMeta = new LinkedHashMap<>();
            if (walletNode.hasProperty("contentCount")) walletMeta.put("contentCount", walletNode.getProperty("contentCount").getValue(Type.LONG));
            if (walletNode.hasProperty("totalWrites")) walletMeta.put("totalWrites", walletNode.getProperty("totalWrites").getValue(Type.LONG));
            if (walletNode.hasProperty("walletCreated")) walletMeta.put("walletCreated", walletNode.getProperty("walletCreated").getValue(Type.LONG));
            if (walletNode.hasProperty("lastWrite")) walletMeta.put("lastWrite", walletNode.getProperty("lastWrite").getValue(Type.LONG));
            if (walletNode.hasProperty("nodeType")) walletMeta.put("nodeType", walletNode.getProperty("nodeType").getValue(Type.STRING));
            payload.put("meta", walletMeta);

            NodeState contentNode = walletNode.getChildNode("content");
            List<Map<String, Object>> recentContent = new ArrayList<>();
            if (contentNode.exists()) {
                int i = 0;
                for (ChildNodeEntry entry : contentNode.getChildNodeEntries()) {
                    if (i >= 50) break;
                    NodeState item = entry.getNodeState();
                    Map<String, Object> content = new LinkedHashMap<>();
                    content.put("name", entry.getName());
                    if (item.hasProperty("contentType")) content.put("contentType", item.getProperty("contentType").getValue(Type.STRING));
                    if (item.hasProperty("timestamp")) content.put("timestamp", item.getProperty("timestamp").getValue(Type.LONG));
                    if (item.hasProperty("message")) content.put("message", item.getProperty("message").getValue(Type.STRING));
                    recentContent.add(content);
                    i++;
                }
            }
            payload.put("recentContent", recentContent);

            if (context.gcAccountManager != null) {
                try {
                    org.apache.jackrabbit.oak.segment.consensus.gc.EntityGCAccount account = context.gcAccountManager.getAccount(walletAddress.toLowerCase());
                    if (account != null) {
                        Map<String, Object> gc = new LinkedHashMap<>();
                        gc.put("totalDebt", account.totalDebt != null ? account.totalDebt.toString() : "0");
                        gc.put("pendingDebt", account.getPendingDebt() != null ? account.getPendingDebt().toString() : "0");
                        gc.put("writesBlocked", account.writesBlocked);
                        payload.put("gcAccount", gc);
                    }
                } catch (Exception ignored) {
                    // Keep explorer endpoint resilient even if GC account introspection fails.
                }
            }

            response.setStatus(HttpServletResponse.SC_OK);
            response.getWriter().write(JsonOutputUtil.toJson(payload));
        } catch (Exception e) {
            log.error("Failed explorer wallet lookup {}", walletAddress, e);
            ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Failed explorer wallet lookup: " + e.getMessage());
        }
    }

    public void handleReleaseFlow(HttpServletResponse response) throws IOException {
        response.setContentType("application/json");
        if (context.proposalQueueManager == null) {
            ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Proposal queue not available");
            return;
        }
        try {
            Map<String, Object> flow = context.proposalQueueManager.getProposalReleaseFlowStats();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("contractVersion", "explorer.v1");
            payload.put("generatedAtMs", System.currentTimeMillis());
            payload.put("releaseFlow", flow);
            response.setStatus(HttpServletResponse.SC_OK);
            response.getWriter().write(JsonOutputUtil.toJson(payload));
        } catch (Exception e) {
            log.error("Failed explorer release flow", e);
            ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Failed explorer release flow: " + e.getMessage());
        }
    }

    public void handleEpochs(HttpServletResponse response) throws IOException {
        response.setContentType("application/json");
        if (context.proposalQueueManager == null) {
            ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Proposal queue not available");
            return;
        }
        try {
            Map<String, Object> flow = context.proposalQueueManager.getProposalEpochFlowStats();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("contractVersion", "explorer.v1");
            payload.put("generatedAtMs", System.currentTimeMillis());
            payload.put("deprecated", true);
            payload.put("deprecatedReason",
                "Legacy compatibility route. Use /v1/explorer/release-flow for adaptive release stages.");
            payload.put("canonicalPath", "/v1/explorer/release-flow");
            payload.put("epochs", flow);
            response.setStatus(HttpServletResponse.SC_OK);
            response.getWriter().write(JsonOutputUtil.toJson(payload));
        } catch (Exception e) {
            log.error("Failed explorer epoch flow", e);
            ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Failed explorer epoch flow: " + e.getMessage());
        }
    }

    private long asLong(Object value) {
        if (value == null) return 0L;
        if (value instanceof Number) return ((Number) value).longValue();
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception e) {
            return 0L;
        }
    }

    private boolean asBoolean(Object value) {
        if (value instanceof Boolean) return (Boolean) value;
        if (value == null) return false;
        return Boolean.parseBoolean(String.valueOf(value));
    }
}
