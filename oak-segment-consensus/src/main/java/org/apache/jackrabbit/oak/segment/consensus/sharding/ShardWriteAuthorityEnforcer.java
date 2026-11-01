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
package org.apache.jackrabbit.oak.segment.consensus.sharding;

import org.apache.jackrabbit.oak.segment.consensus.federation.Charter;
import org.apache.jackrabbit.oak.segment.consensus.federation.DirectoryEntry;
import org.apache.jackrabbit.oak.segment.consensus.federation.DirectorySnapshot;
import org.apache.jackrabbit.oak.segment.consensus.federation.FederationCoordinator;
import org.apache.jackrabbit.oak.segment.consensus.federation.NamespaceAuthority;
import org.apache.jackrabbit.oak.segment.http.server.ServerContext;
import org.apache.jackrabbit.oak.segment.http.server.util.ApiErrorUtil;
import org.apache.jackrabbit.oak.segment.http.server.util.JsonOutputUtil;

import jakarta.servlet.http.HttpServletResponse;
import org.apache.jackrabbit.oak.spi.state.NodeState;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Enforces local write authority before a request enters the local queue or
 * Aeron ingress. A federated cluster decides from its committed directory, the same decision replicated
 * apply makes; an unfederated cluster falls back to the static prefix routes.
 */
public final class ShardWriteAuthorityEnforcer {

    private ShardWriteAuthorityEnforcer() {
    }

    public static boolean allowLocalWrite(ServerContext context,
                                          String normalizedWallet,
                                          String apiPath,
                                          HttpServletResponse response) throws IOException {
        return allowLocalWrite(context, normalizedWallet, normalizedWallet, apiPath, response);
    }

    /**
     * @param signer    the request's signing wallet, lower-cased
     * @param namespace the address root written into, lower-cased; the signer's own unless it writes on
     *                  behalf of an enterprise namespace, which only a federated cluster allows
     */
    public static boolean allowLocalWrite(ServerContext context,
                                          String signer,
                                          String namespace,
                                          String apiPath,
                                          HttpServletResponse response) throws IOException {
        FederationCoordinator coordinator = context.federationCoordinator;
        if (coordinator != null && !coordinator.getStatus().isCharterMatching()) {
            return reject(context, response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "charter_mismatch",
                "This validator is configured for a different charter than its cluster committed.");
        }
        NodeState root = context.authoritativeRoot();
        DirectorySnapshot directory = DirectorySnapshot.read(root);
        if (directory != null) {
            return allowByDirectory(context, Charter.read(root), directory, signer, namespace, apiPath, response);
        }
        if (!namespace.equals(signer)) {
            return reject(context, response, HttpServletResponse.SC_BAD_REQUEST, "namespace_requires_federation",
                "Writing into another wallet's namespace requires a federation directory.");
        }
        return allowByPrefixRoute(context, signer, apiPath, response);
    }

    private static boolean allowByDirectory(ServerContext context, Charter charter, DirectorySnapshot directory,
                                            String signer, String namespace, String apiPath,
                                            HttpServletResponse response) throws IOException {
        NamespaceAuthority.Decision decision = NamespaceAuthority.decide(charter, directory, signer, namespace);
        switch (decision.getVerdict()) {
            case ALLOW:
                return true;
            case REMOTE:
                return redirectToOwner(context, decision.getOwner(), namespace, apiPath, response);
            case UNCLAIMED:
                return reject(context, response, HttpServletResponse.SC_FORBIDDEN, "namespace_unclaimed",
                    decision.describe());
            case NOT_WRITER:
                return reject(context, response, HttpServletResponse.SC_FORBIDDEN, "writer_not_authorized",
                    decision.describe());
            case INACTIVE:
                return reject(context, response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "enterprise_inactive",
                    decision.describe());
            default:
                return reject(context, response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "federation_inconsistent",
                    decision.describe());
        }
    }

    private static boolean redirectToOwner(ServerContext context, DirectoryEntry owner, String namespace,
                                           String apiPath, HttpServletResponse response) throws IOException {
        if (owner.getEndpoints().isEmpty()) {
            return reject(context, response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "owner_unreachable",
                "Enterprise " + owner.getEnterpriseId() + " owns namespace " + namespace + " but lists no endpoints");
        }
        context.apiRejectedRequests.incrementAndGet();
        String redirectUrl = owner.getEndpoints().get(0) + apiPath;
        response.setHeader("Location", redirectUrl);
        response.setContentType("application/json");
        response.setStatus(HttpServletResponse.SC_TEMPORARY_REDIRECT);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", "wrong_cluster");
        payload.put("error", "Namespace belongs to another enterprise cluster");
        payload.put("namespace", namespace);
        payload.put("enterpriseId", owner.getEnterpriseId());
        payload.put("endpoints", owner.getEndpoints());
        payload.put("redirectUrl", redirectUrl);
        response.getWriter().write(JsonOutputUtil.toJson(payload));
        return false;
    }

    private static boolean reject(ServerContext context, HttpServletResponse response, int status, String code,
                                  String message) throws IOException {
        context.apiRejectedRequests.incrementAndGet();
        ApiErrorUtil.sendJsonError(response, status, code, message);
        return false;
    }

    private static boolean allowByPrefixRoute(ServerContext context,
                                              String normalizedWallet,
                                              String apiPath,
                                              HttpServletResponse response) throws IOException {
        ShardingRuntimeConfig config = context.shardingRuntimeConfig;
        if (config == null || !config.isEnabled()) {
            return true;
        }

        ShardingRuntimeConfig.ResolvedWallet resolved = config.resolveWallet(normalizedWallet);
        switch (resolved.getOwnership()) {
            case LOCAL:
            case DISABLED:
                return true;
            case REMOTE:
                context.apiRejectedRequests.incrementAndGet();
                String redirectUrl = resolved.buildRedirectUrl(apiPath);
                if (redirectUrl != null) {
                    response.setHeader("Location", redirectUrl);
                }
                response.setContentType("application/json");
                response.setStatus(HttpServletResponse.SC_TEMPORARY_REDIRECT);
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("code", "wrong_shard");
                payload.put("error", "Wallet belongs to a different shard cluster");
                payload.put("wallet", normalizedWallet);
                payload.put("l1Prefix", resolved.getL1Prefix());
                payload.put("redirectBaseUrl", resolved.getRedirectBaseUrl());
                payload.put("redirectUrl", redirectUrl);
                response.getWriter().write(JsonOutputUtil.toJson(payload));
                return false;
            case UNCLAIMED:
            default:
                context.apiRejectedRequests.incrementAndGet();
                ApiErrorUtil.sendJsonError(
                    response,
                    HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "shard_unclaimed",
                    "Wallet prefix " + resolved.getL1Prefix() + " is not claimed by any configured shard cluster."
                );
                return false;
        }
    }
}
