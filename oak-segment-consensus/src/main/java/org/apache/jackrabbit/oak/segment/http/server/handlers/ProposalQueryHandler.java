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
import org.apache.jackrabbit.oak.segment.http.server.ServerContext;
import org.apache.jackrabbit.oak.segment.http.server.util.ApiErrorUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Handler for proposal status and queue queries.
 */
public class ProposalQueryHandler {

    private static final Logger log = LoggerFactory.getLogger(ProposalQueryHandler.class);

    private final ServerContext context;

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

            response.setStatus(HttpServletResponse.SC_OK);
            String json = "{" +
                "\"proposalId\":\"" + status.getProposalId() + "\"," +
                "\"state\":\"" + status.getState().name() + "\"," +
                "\"ethereumTxHash\":\"" + status.getEthereumTxHash() + "\"," +
                "\"timeoutTimestamp\":" + status.getTimeoutTimestamp() + "," +
                "\"confirmedBlock\":" + (status.getConfirmedBlock() != null ? status.getConfirmedBlock() : -1) + "," +
                "\"rejectionReason\":" + (status.getRejectionReason() != null ? "\"" + status.getRejectionReason() + "\"" : "null") + "," +
                "\"durabilityState\":\"" + (status.getDurabilityState() != null ? status.getDurabilityState().name() : "UNKNOWN") + "\"," +
                "\"durabilityTimestamp\":" + status.getDurabilityTimestamp() + "," +
                "\"durabilityError\":" + (status.getDurabilityError() != null ? "\"" + status.getDurabilityError() + "\"" : "null") + "," +
                "\"durableHead\":" + (status.getDurableHead() != null ? "\"" + status.getDurableHead() + "\"" : "null") +
                "}";
            response.getWriter().write(json);
        } catch (Exception e) {
            log.error("Error getting proposal status", e);
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
            response.setStatus(HttpServletResponse.SC_OK);
            String json = "{\"pendingCount\":" + count + "}";
            response.getWriter().write(json);
        } catch (Exception e) {
            log.error("Error getting pending count", e);
            ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Error: " + e.getMessage());
        }
    }
}
