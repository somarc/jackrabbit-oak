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

import org.apache.jackrabbit.oak.segment.consensus.aeron.AeronConsensusEngine;
import org.apache.jackrabbit.oak.segment.consensus.queue.DurabilityState;
import org.apache.jackrabbit.oak.segment.consensus.queue.ProposalState;
import org.apache.jackrabbit.oak.segment.consensus.queue.QueuedProposal;
import org.apache.jackrabbit.oak.segment.consensus.service.AppliedProposalIndex;
import org.apache.jackrabbit.oak.segment.consensus.sharding.ShardWriteAuthorityEnforcer;
import org.apache.jackrabbit.oak.segment.consensus.validation.ValidationResult;
import org.apache.jackrabbit.oak.segment.consensus.validation.WalletValidator;
import org.apache.jackrabbit.oak.segment.http.server.ServerContext;
import org.apache.jackrabbit.oak.segment.http.server.model.ClientRegistration;
import org.apache.jackrabbit.oak.segment.http.server.util.ApiErrorUtil;
import org.apache.jackrabbit.oak.segment.http.server.util.JsonOutputUtil;
import org.apache.jackrabbit.oak.segment.http.server.util.LeaderWriteRedirectUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Request steps shared by {@code /v1/propose-write} and {@code /v1/propose-delete}.
 */
final class ProposalRequestSupport {

    private static final Logger log = LoggerFactory.getLogger(ProposalRequestSupport.class);
    private static final Pattern PROPOSAL_ID = Pattern.compile("(?i)^0x[a-f0-9]{64}$");

    private ProposalRequestSupport() {
    }

    static final class ClientLookup {
        final ClientRegistration registration;
        /** The X-Client-Id/clientId value when matched that way, else the registration's own clientId. */
        final String clientId;

        private ClientLookup(ClientRegistration registration, String clientId) {
            this.registration = registration;
            this.clientId = clientId;
        }
    }

    /** Sends the redirect/rejection and returns false unless this node owns the shard and leads. */
    static boolean allowShardAndLeader(ServerContext context, String normalizedWallet, String route,
                                       HttpServletResponse response) throws IOException {
        return allowShardAndLeader(context, normalizedWallet, normalizedWallet, route, response);
    }

    /** As above for a signer writing into {@code namespace}, which a federated cluster may allow. */
    static boolean allowShardAndLeader(ServerContext context, String signer, String namespace, String route,
                                       HttpServletResponse response) throws IOException {
        return ShardWriteAuthorityEnforcer.allowLocalWrite(context, signer, namespace, route, response)
            && LeaderWriteRedirectUtil.allowLeaderWrite(context, route, response);
    }

    /**
     * The optional {@code namespace} parameter, lower-cased, or the signer's wallet when absent; null after
     * sending a 400 for a malformed value.
     */
    static String resolveNamespace(String rawNamespace, String signer, HttpServletResponse response)
            throws IOException {
        if (rawNamespace == null || rawNamespace.trim().isEmpty()) {
            return signer;
        }
        ValidationResult<String> validation = WalletValidator.validate(rawNamespace.trim());
        if (!validation.isValid()) {
            ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_BAD_REQUEST, "invalid_namespace",
                "namespace must be a 0x-prefixed address: " + validation.getError());
            return null;
        }
        return validation.getNormalizedValue();
    }

    /** Looks the client up by wallet first, then by explicit X-Client-Id header or clientId parameter. */
    static ClientLookup lookupClient(ServerContext context, HttpServletRequest request, String normalizedWallet) {
        ClientRegistration registration = context.findClientRegistrationByWallet(normalizedWallet);
        String clientId = registration != null ? registration.clientId : null;
        if (registration == null) {
            String clientIdHeader = request.getHeader("X-Client-Id");
            if (clientIdHeader == null || clientIdHeader.isEmpty()) {
                clientIdHeader = request.getParameter("clientId");
            }
            if (clientIdHeader != null && !clientIdHeader.isEmpty()) {
                registration = context.findClientRegistrationByClientId(clientIdHeader);
                if (registration != null) {
                    clientId = clientIdHeader;
                }
            }
        }
        return new ClientLookup(registration, clientId);
    }

    /**
     * ADR 028 pre-flight: sends 503 and returns true when the consensus engine is missing or the cluster is
     * unhealthy, so a proposal is not silently lost.
     */
    static boolean rejectIfUnhealthy(ServerContext context, String flow, HttpServletResponse response)
            throws IOException {
        AeronConsensusEngine engine = context.aeronConsensusEngine;
        if (engine != null && engine.isClusterHealthy()) {
            return false;
        }
        String reason = engine != null ? engine.getUnhealthyReason() : "consensus_engine_not_configured";
        log.warn("❌ Cluster unhealthy, rejecting {}: {}", flow, reason);
        context.apiRejectedRequests.incrementAndGet();
        ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
            "Cluster unhealthy: " + reason + ". Please retry in a few seconds.");
        return true;
    }

    /**
     * The 400 message for a missing or malformed client proposalId (V5: the on-chain bytes32 from
     * {@code contractFlow}), or null when it is a 0x-prefixed 32-byte hex value.
     */
    static String proposalIdError(String rawProposalId, String contractFlow) {
        if (rawProposalId == null || rawProposalId.trim().isEmpty()) {
            return "Missing proposalId parameter. Clients must supply a 0x-prefixed 32-byte hex proposalId from the "
                + contractFlow + " flow.";
        }
        return PROPOSAL_ID.matcher(rawProposalId.trim()).matches() ? null
            : "Invalid proposalId format. Expected 0x-prefixed 32-byte hex.";
    }

    static String normalizeProposalId(String trimmedProposalId) {
        return trimmedProposalId.startsWith("0X") ? "0x" + trimmedProposalId.substring(2) : trimmedProposalId;
    }

    /**
     * Sends {@code 409 duplicate_proposal} and returns true when {@code proposalId} has already taken effect (it is
     * in the replicated applied-proposal index at this node's head) or is still in flight in this leader's queue.
     * Replicated apply ignores a resend either way; this answers it before it is queued.
     */
    static boolean rejectDuplicate(ServerContext context, String proposalId, HttpServletResponse response)
            throws IOException {
        AppliedProposalIndex.Entry applied = AppliedProposalIndex.find(context.authoritativeRoot(), proposalId);
        QueuedProposal queued = applied == null && context.proposalQueueManager != null
            ? context.proposalQueueManager.getProposal(proposalId) : null;
        if (applied == null && (queued == null || !isInFlight(queued))) {
            return false;
        }
        context.apiRejectedRequests.incrementAndGet();
        Map<String, Object> links = new LinkedHashMap<>();
        links.put("self", "/v1/ops/operations/" + proposalId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", false);
        payload.put("error", applied != null
            ? "Proposal " + proposalId + " has already taken effect; a proposal id is applied once."
            : "Proposal " + proposalId + " is already queued on this leader; a proposal id is applied once.");
        payload.put("code", "duplicate_proposal");
        payload.put("status", HttpServletResponse.SC_CONFLICT);
        payload.put("timestamp", System.currentTimeMillis());
        payload.put("proposalId", proposalId);
        payload.put("proposalState", applied != null ? "APPLIED" : queued.getState().name());
        payload.put("answeredFrom", applied != null ? "replicated-state" : "leader-queue");
        payload.put("path", applied != null ? applied.path() : queued.getPath());
        if (applied != null) {
            payload.put("kind", applied.kind());
            payload.put("appliedAt", applied.appliedAt());
        }
        payload.put("links", links);
        JsonOutputUtil.write(response, HttpServletResponse.SC_CONFLICT, payload);
        return true;
    }

    /** Queued and not finished without effect: neither rejected nor failed after it reached the log. */
    private static boolean isInFlight(QueuedProposal queued) {
        ProposalState state = queued.getState();
        return state != ProposalState.REJECTED
            && !(state == ProposalState.PROCESSED && queued.getDurabilityState() == DurabilityState.FAILED);
    }

    /** The leading ops.v1 keys of a 202 proposal response; callers append their own keys. */
    static Map<String, Object> acceptedEnvelope(String proposalId) {
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
        return payload;
    }
}
