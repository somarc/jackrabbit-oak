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

import org.apache.jackrabbit.oak.segment.http.server.ServerContext;
import org.apache.jackrabbit.oak.segment.http.server.util.ApiErrorUtil;
import org.apache.jackrabbit.oak.segment.http.server.util.JsonOutputUtil;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Enforces local write authority before a request enters the local queue or
 * Aeron ingress.
 */
public final class ShardWriteAuthorityEnforcer {

    private ShardWriteAuthorityEnforcer() {
    }

    public static boolean allowLocalWrite(ServerContext context,
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
