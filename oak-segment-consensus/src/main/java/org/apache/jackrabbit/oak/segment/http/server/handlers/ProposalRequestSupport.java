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

import org.apache.jackrabbit.oak.segment.consensus.sharding.ShardWriteAuthorityEnforcer;
import org.apache.jackrabbit.oak.segment.consensus.validation.ValidationResult;
import org.apache.jackrabbit.oak.segment.consensus.validation.WalletValidator;
import org.apache.jackrabbit.oak.segment.http.server.ServerContext;
import org.apache.jackrabbit.oak.segment.http.server.model.ClientRegistration;
import org.apache.jackrabbit.oak.segment.http.server.util.ApiErrorUtil;
import org.apache.jackrabbit.oak.segment.http.server.util.LeaderWriteRedirectUtil;

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

    static boolean isValidProposalId(String trimmedProposalId) {
        return PROPOSAL_ID.matcher(trimmedProposalId).matches();
    }

    static String normalizeProposalId(String trimmedProposalId) {
        return trimmedProposalId.startsWith("0X") ? "0x" + trimmedProposalId.substring(2) : trimmedProposalId;
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
