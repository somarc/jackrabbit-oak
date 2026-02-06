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

import org.apache.jackrabbit.oak.segment.consensus.queue.ProposalStatus;
import org.apache.jackrabbit.oak.segment.consensus.queue.ProposalState;
import org.apache.jackrabbit.oak.segment.http.server.ServerContext;
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
 * Handler for proposal status and queue queries.
 */
public class ProposalQueryHandler {

    private static final Logger log = LoggerFactory.getLogger(ProposalQueryHandler.class);
    private static final long OPS_QUEUE_SNAPSHOT_TTL_MS = 1000L;

    private final ServerContext context;
    private final Object queueSnapshotLock = new Object();
    private volatile Map<String, Object> cachedQueueStatsData;
    private volatile long cachedQueueStatsSourceTimestampMs;

    public ProposalQueryHandler(ServerContext context) {
        this.context = context;
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
                ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_BAD_REQUEST, "Invalid proposal ID");
                return;
            }
            String proposalId = parts[3];

            if (context.proposalQueueManager == null) {
                ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Proposal queue not available");
                return;
            }

            ProposalStatus status = context.proposalQueueManager.getProposalStatus(proposalId);
            if (status == null) {
                ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_NOT_FOUND, "Proposal not found");
                return;
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("proposalId", status.getProposalId());
            payload.put("state", status.getState().name());
            payload.put("ethereumTxHash", status.getEthereumTxHash());
            payload.put("timeoutTimestamp", status.getTimeoutTimestamp());
            payload.put("confirmedBlock", status.getConfirmedBlock() != null ? status.getConfirmedBlock() : -1);
            payload.put("rejectionReason", status.getRejectionReason());
            payload.put("durabilityState", status.getDurabilityState() != null ? status.getDurabilityState().name() : "UNKNOWN");
            payload.put("durabilityTimestamp", status.getDurabilityTimestamp());
            payload.put("durabilityError", status.getDurabilityError());
            payload.put("durableHead", status.getDurableHead());
            response.setStatus(HttpServletResponse.SC_OK);
            response.getWriter().write(JsonOutputUtil.toJson(payload));
        } catch (Exception e) {
            log.error("Error getting proposal status", e);
            ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Error: " + e.getMessage());
        }
    }

    /**
     * Get operation status (ops.v1 adapter over proposal status).
     * GET /v1/ops/operations/{operationId}
     */
    public void handleGetOperationStatus(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setContentType("application/json");

        try {
            String path = request.getRequestURI();
            // Extract operationId from path: /v1/ops/operations/{operationId}
            String[] parts = path.split("/");
            if (parts.length < 5) {
                ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_BAD_REQUEST, "Invalid operation ID");
                return;
            }
            String operationId = parts[4];

            if (context.proposalQueueManager == null) {
                ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Proposal queue not available");
                return;
            }

            ProposalStatus status = context.proposalQueueManager.getProposalStatus(operationId);
            if (status == null) {
                ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_NOT_FOUND, "Operation not found");
                return;
            }

            long now = System.currentTimeMillis();
            String opsState = mapToOpsLifecycleState(status);
            long deadlineMs = status.getTimeoutTimestamp();
            boolean terminal = isTerminalOpsState(opsState);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("contractVersion", "ops.v1");
            payload.put("timestampMs", now);
            payload.put("operationId", status.getProposalId());
            payload.put("proposalId", status.getProposalId());
            payload.put("state", opsState);
            payload.put("type", "WRITE_PROPOSAL");
            payload.put("correlationId", status.getProposalId());
            Map<String, Object> queue = new LinkedHashMap<>();
            queue.put("name", "proposal-queue");
            queue.put("position", null);
            payload.put("queue", queue);
            payload.put("startedAtMs", null);
            payload.put("updatedAtMs", now);
            payload.put("completedAtMs", terminal ? now : null);
            payload.put("deadlineMs", deadlineMs);
            payload.put("sourceState", status.getState().name());
            payload.put("durabilityState", status.getDurabilityState() != null ? status.getDurabilityState().name() : "UNKNOWN");
            payload.put("durabilityTimestamp", status.getDurabilityTimestamp());
            payload.put("durabilityError", status.getDurabilityError());
            payload.put("rejectionReason", status.getRejectionReason());
            payload.put("ethereumTxHash", status.getEthereumTxHash());
            payload.put("confirmedBlock", status.getConfirmedBlock());
            payload.put("error", toOpsError(status, opsState));
            response.setStatus(HttpServletResponse.SC_OK);
            response.getWriter().write(JsonOutputUtil.toJson(payload));
        } catch (Exception e) {
            log.error("Error getting operation status", e);
            ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Error: " + e.getMessage());
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
                ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Proposal queue not available");
                return;
            }

            int count = context.proposalQueueManager.getPendingCount();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("pendingCount", count);
            response.setStatus(HttpServletResponse.SC_OK);
            response.getWriter().write(JsonOutputUtil.toJson(payload));
        } catch (Exception e) {
            log.error("Error getting pending count", e);
            ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Error: " + e.getMessage());
        }
    }

    /**
     * Get detailed proposal queue statistics.
     * GET /v1/proposals/queue/stats
     */
    public void handleGetQueueStats(HttpServletResponse response) throws IOException {
        response.setContentType("application/json");

        try {
            if (context.proposalQueueManager == null) {
                ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Proposal queue not available");
                return;
            }

            Map<String, Object> stats = context.proposalQueueManager.getQueueStats();
            response.setStatus(HttpServletResponse.SC_OK);
            response.getWriter().write(JsonOutputUtil.toJson(stats));
        } catch (Exception e) {
            log.error("Error getting queue stats", e);
            ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Error: " + e.getMessage());
        }
    }

    /**
     * Get ops.v1 queue snapshot with freshness/degraded metadata.
     * GET /v1/ops/snapshots/queue
     */
    public void handleGetOpsQueueSnapshot(HttpServletResponse response) throws IOException {
        response.setContentType("application/json");

        long servedAtMs = System.currentTimeMillis();

        if (context.proposalQueueManager == null) {
            ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Proposal queue not available");
            return;
        }

        try {
            Map<String, Object> data;
            long sourceTimestampMs;
            boolean fromCache = false;

            synchronized (queueSnapshotLock) {
                long now = System.currentTimeMillis();
                boolean cacheValid = cachedQueueStatsData != null
                    && cachedQueueStatsSourceTimestampMs > 0
                    && (now - cachedQueueStatsSourceTimestampMs) <= OPS_QUEUE_SNAPSHOT_TTL_MS;

                if (cacheValid) {
                    data = cachedQueueStatsData;
                    sourceTimestampMs = cachedQueueStatsSourceTimestampMs;
                    fromCache = true;
                } else {
                    data = context.proposalQueueManager.getQueueStats();
                    sourceTimestampMs = now;
                    cachedQueueStatsData = data;
                    cachedQueueStatsSourceTimestampMs = sourceTimestampMs;
                }
            }

            long stalenessMs = Math.max(0L, servedAtMs - sourceTimestampMs);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("contractVersion", "ops.v1");
            payload.put("sourceTimestampMs", sourceTimestampMs);
            payload.put("servedAtMs", servedAtMs);
            payload.put("stalenessMs", stalenessMs);
            payload.put("degraded", false);
            payload.put("degradedReason", null);
            Map<String, Object> cache = new LinkedHashMap<>();
            cache.put("hit", fromCache);
            cache.put("ttlMs", OPS_QUEUE_SNAPSHOT_TTL_MS);
            payload.put("cache", cache);
            payload.put("data", data);
            response.setStatus(HttpServletResponse.SC_OK);
            response.getWriter().write(JsonOutputUtil.toJson(payload));
        } catch (Exception e) {
            log.warn("Error building ops queue snapshot, attempting stale fallback: {}", e.getMessage());

            Map<String, Object> staleData = cachedQueueStatsData;
            long sourceTimestampMs = cachedQueueStatsSourceTimestampMs;
            if (staleData != null && sourceTimestampMs > 0) {
                long stalenessMs = Math.max(0L, servedAtMs - sourceTimestampMs);
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("contractVersion", "ops.v1");
                payload.put("sourceTimestampMs", sourceTimestampMs);
                payload.put("servedAtMs", servedAtMs);
                payload.put("stalenessMs", stalenessMs);
                payload.put("degraded", true);
                payload.put("degradedReason", "STALE_CACHE_FALLBACK");
                Map<String, Object> cache = new LinkedHashMap<>();
                cache.put("hit", true);
                cache.put("ttlMs", OPS_QUEUE_SNAPSHOT_TTL_MS);
                payload.put("cache", cache);
                payload.put("data", staleData);
                response.setStatus(HttpServletResponse.SC_OK);
                response.getWriter().write(JsonOutputUtil.toJson(payload));
                return;
            }

            ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Error: " + e.getMessage());
        }
    }

    private String mapToOpsLifecycleState(ProposalStatus status) {
        ProposalState state = status.getState();

        if (state == ProposalState.PENDING) {
            return "QUEUED";
        }
        if (state == ProposalState.CONFIRMED || state == ProposalState.VERIFIED) {
            return "PROCESSING";
        }
        if (state == ProposalState.PROCESSED) {
            if (status.getDurabilityState() != null && status.getDurabilityState().name().equals("ACKED")) {
                return "COMMITTED";
            }
            if (status.getDurabilityState() != null && status.getDurabilityState().name().equals("FAILED")) {
                return "FAILED";
            }
            return "PROCESSING";
        }
        if (state == ProposalState.REJECTED) {
            String reason = status.getRejectionReason();
            if (reason != null && reason.toLowerCase().contains("timeout")) {
                return "TIMED_OUT";
            }
            return "FAILED";
        }

        return "PROCESSING";
    }

    private boolean isTerminalOpsState(String state) {
        return "COMMITTED".equals(state)
            || "FAILED".equals(state)
            || "TIMED_OUT".equals(state)
            || "ABORTED".equals(state);
    }

    private Map<String, Object> toOpsError(ProposalStatus status, String opsState) {
        if (!"FAILED".equals(opsState) && !"TIMED_OUT".equals(opsState)) {
            return null;
        }

        String code = "FAILED".equals(opsState) ? "OPERATION_FAILED" : "OPERATION_TIMED_OUT";
        String message = status.getDurabilityError() != null
            ? status.getDurabilityError()
            : (status.getRejectionReason() != null ? status.getRejectionReason() : "operation_failed");

        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        error.put("retryable", "TIMED_OUT".equals(opsState));
        return error;
    }
}
