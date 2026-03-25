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
package org.apache.jackrabbit.oak.segment.http.server.util;

import org.apache.jackrabbit.oak.segment.http.server.ServerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Enforces leader-only proposal ingress within a shard.
 *
 * <p>Shard authority decides <em>which cluster</em> owns a wallet prefix.
 * This helper decides <em>which node within that cluster</em> may originate
 * Aeron write/delete proposals. Followers must redirect the client to the
 * current leader instead of originating proposals with potentially stale term
 * information.</p>
 */
public final class LeaderWriteRedirectUtil {

    private static final Logger log = LoggerFactory.getLogger(LeaderWriteRedirectUtil.class);

    private LeaderWriteRedirectUtil() {
    }

    public static boolean allowLeaderWrite(ServerContext context,
                                           String apiPath,
                                           HttpServletResponse response) throws IOException {
        if (context == null || context.aeronConsensusEngine == null) {
            return true;
        }

        if (context.aeronConsensusEngine.isLeader()) {
            return true;
        }

        String leaderUrl = context.aeronConsensusEngine.getCurrentLeader();
        if (leaderUrl == null || leaderUrl.trim().isEmpty()) {
            context.apiRejectedRequests.incrementAndGet();
            response.setHeader("Retry-After", "1");
            ApiErrorUtil.sendJsonError(
                response,
                HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                "leader_unknown",
                "Current leader is not yet known. Please retry in a moment."
            );
            return false;
        }

        String redirectUrl = buildRedirectUrl(leaderUrl, apiPath);
        context.apiRejectedRequests.incrementAndGet();
        response.setHeader("Location", redirectUrl);
        response.setContentType("application/json");
        response.setStatus(HttpServletResponse.SC_TEMPORARY_REDIRECT);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", "wrong_leader");
        payload.put("error", "Write must be sent to the current leader");
        payload.put("currentLeader", leaderUrl);
        payload.put("redirectUrl", redirectUrl);
        response.getWriter().write(JsonOutputUtil.toJson(payload));

        log.info("↪️ Redirecting write ingress to leader: {}", redirectUrl);
        return false;
    }

    static String buildRedirectUrl(String leaderUrl, String apiPath) {
        String base = leaderUrl.endsWith("/") ? leaderUrl.substring(0, leaderUrl.length() - 1) : leaderUrl;
        String path = apiPath.startsWith("/") ? apiPath : "/" + apiPath;
        return base + path;
    }
}
