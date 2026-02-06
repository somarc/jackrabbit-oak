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

import org.apache.jackrabbit.oak.segment.http.server.ServerContext;
import org.apache.jackrabbit.oak.segment.http.server.util.JsonOutputUtil;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Handler for consensus status (`/v1/consensus/status`).
 */
public class ConsensusStatusHandler {

    private final ServerContext context;

    public ConsensusStatusHandler(ServerContext context) {
        this.context = context;
    }

    /**
     * Handle GET /v1/consensus/status - Return comprehensive consensus state.
     */
    public void handleGetConsensusStatus(HttpServletResponse response) throws IOException {
        response.setContentType("application/json");
        response.setStatus(HttpServletResponse.SC_OK);

        Map<String, Object> status = new LinkedHashMap<>();

        // Check for Aeron Cluster consensus first (newest, preferred)
        if (context.aeronConsensusEngine != null) {
            // ✈️ AERON NATIVE: Use Aeron's native cluster state APIs
            status.put("consensusType", "aeron-cluster");
            status.put("currentRole", context.aeronConsensusEngine.getCurrentRole().name());
            status.put("isLeader", context.aeronConsensusEngine.isLeader());

            // ✈️ AERON NATIVE: Get leader from native cluster state (no HTTP API calls)
            String currentLeader = context.aeronConsensusEngine.getCurrentLeader();
            if (currentLeader != null) {
                status.put("currentLeader", currentLeader);
            }
            // If currentLeader is null, omit the field (may be during election)

            status.put("currentEpoch", context.aeronConsensusEngine.getCurrentEpoch());
            status.put("currentTerm", context.aeronConsensusEngine.getCurrentTerm());
            status.put("reachableValidators", context.aeronConsensusEngine.getReachableValidatorCount());
            status.put("allFollowers", context.aeronConsensusEngine.getAllFollowers());
            status.put("ethereumEpoch", context.aeronConsensusEngine.getCurrentEthereumEpoch());
        } else {
            // No consensus engine
            status.put("consensusType", "none");
            status.put("currentRole", "STANDALONE");
        }

        // CRITICAL: Omit null values - null means discovery failed, not that there's no leader.
        status.values().removeIf(v -> v == null);
        response.getWriter().write(JsonOutputUtil.toJson(status));
    }
}
