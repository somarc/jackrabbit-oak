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

import org.apache.jackrabbit.oak.blob.cloud.ipfs.IPFSDataStore;
import org.apache.jackrabbit.oak.segment.http.server.ServerContext;
import org.apache.jackrabbit.oak.segment.http.server.util.ApiErrorUtil;
import org.apache.jackrabbit.oak.segment.http.server.util.JsonOutputUtil;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;

/**
 * Operator views of the IPFS DataStore, on guarded routes:
 * {@code GET /v1/ops/ipfs/health} and {@code GET /v1/ops/ipfs/verify?limit=}.
 */
public class IpfsOpsHandler {

    static final int DEFAULT_VERIFY_LIMIT = 1000;
    static final int MAX_VERIFY_LIMIT = 100_000;

    private final ServerContext context;

    public IpfsOpsHandler(ServerContext context) {
        this.context = context;
    }

    /**
     * IPFS node and cluster reachability, encryption and network settings, and
     * operation counts; 503 when the IPFS node or cluster is down.
     */
    public void handleHealth(HttpServletResponse response) throws IOException {
        IPFSDataStore dataStore = dataStore(response);
        if (dataStore == null) {
            return;
        }
        Map<String, Object> health = dataStore.checkHealth();
        JsonOutputUtil.send(response, "UP".equals(health.get("status"))
            ? HttpServletResponse.SC_OK : HttpServletResponse.SC_SERVICE_UNAVAILABLE, JsonOutputUtil.toJson(health));
    }

    /**
     * Checks that up to {@code limit} binaries in this node's index are
     * retained (pinned, or held by the IPFS Cluster replication minimum).
     */
    public void handleVerify(HttpServletRequest request, HttpServletResponse response) throws IOException {
        IPFSDataStore dataStore = dataStore(response);
        if (dataStore == null) {
            return;
        }
        int limit;
        try {
            String value = request.getParameter("limit");
            limit = value == null || value.isBlank() ? DEFAULT_VERIFY_LIMIT : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            limit = -1;
        }
        if (limit < 1 || limit > MAX_VERIFY_LIMIT) {
            ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_BAD_REQUEST,
                "limit must be between 1 and " + MAX_VERIFY_LIMIT);
            return;
        }
        try {
            JsonOutputUtil.send(response, HttpServletResponse.SC_OK,
                JsonOutputUtil.toJson(dataStore.verifyRetention(limit)));
        } catch (Exception e) {
            ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                "ipfs_verify_failed", String.valueOf(e.getMessage()));
        }
    }

    private IPFSDataStore dataStore(HttpServletResponse response) throws IOException {
        IPFSDataStore dataStore = context.ipfsDataStore();
        if (dataStore == null) {
            ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_NOT_FOUND, "ipfs_not_configured",
                "Binaries are not stored in IPFS on this validator");
        }
        return dataStore;
    }
}
