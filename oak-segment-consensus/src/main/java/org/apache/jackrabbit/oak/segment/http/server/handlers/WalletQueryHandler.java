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
import org.apache.jackrabbit.oak.segment.http.server.util.ApiErrorUtil;
import org.apache.jackrabbit.oak.segment.http.server.util.FormatUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Handler for wallet stats and content endpoints.
 */
public class WalletQueryHandler {

    private static final Logger log = LoggerFactory.getLogger(WalletQueryHandler.class);

    private final ServerContext context;

    public WalletQueryHandler(ServerContext context) {
        this.context = context;
    }

    /**
     * Query wallet statistics - GET /v1/wallets/stats
     * Returns aggregated stats for all wallets or specific wallet.
     */
    public void handleWalletStats(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String wallet = request.getParameter("wallet");

        try {
            response.setContentType("application/json");
            StringBuilder json = new StringBuilder();

            if (wallet != null && !wallet.isEmpty()) {
                // Single wallet stats
                json.append(queryWalletNode(wallet));
            } else {
                // All wallets (top 100 by contentCount)
                json.append(queryTopWallets());
            }

            response.getWriter().write(json.toString());

        } catch (Exception e) {
            log.error("Failed to query wallet stats", e);
            ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                FormatUtils.escapeJson(e.getMessage()));
        }
    }

    /**
     * Query content by wallet - GET /v1/wallets/content
     * Returns content items for a specific wallet.
     */
    public void handleWalletContent(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String wallet = request.getParameter("wallet");

        if (wallet == null || wallet.isEmpty()) {
            ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_BAD_REQUEST, "Missing wallet parameter");
            return;
        }

        try {
            response.setContentType("application/json");
            String json = queryWalletContent(wallet);
            response.getWriter().write(json);

        } catch (Exception e) {
            log.error("Failed to query wallet content", e);
            ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                FormatUtils.escapeJson(e.getMessage()));
        }
    }

    /**
     * Query a single wallet node and return its metadata.
     */
    private String queryWalletNode(String walletAddress) {
        try {
            // Build wallet path
            String[] levels = org.apache.jackrabbit.oak.segment.consensus.util.WalletPathUtil.getShardLevels(walletAddress);
            String walletPath = String.format("/oak-chain/%s/%s/%s/%s", levels[0], levels[1], levels[2], walletAddress);

            // Get wallet node
            org.apache.jackrabbit.oak.spi.state.NodeState root = context.nodeStore.getRoot();
            org.apache.jackrabbit.oak.spi.state.NodeState walletNode = root.getChildNode("oak-chain")
                .getChildNode(levels[0])
                .getChildNode(levels[1])
                .getChildNode(levels[2])
                .getChildNode(walletAddress);

            if (!walletNode.exists()) {
                return "{\"error\":\"Wallet not found\"}";
            }

            // Build JSON
            StringBuilder json = new StringBuilder("{");
            json.append("\"wallet\":\"").append(FormatUtils.escapeJson(walletAddress)).append("\",");
            json.append("\"path\":\"").append(FormatUtils.escapeJson(walletPath)).append("\",");

            if (walletNode.hasProperty("nodeType")) {
                json.append("\"nodeType\":\"").append(FormatUtils.escapeJson(walletNode.getProperty("nodeType").getValue(org.apache.jackrabbit.oak.api.Type.STRING))).append("\",");
            }
            if (walletNode.hasProperty("walletCreated")) {
                json.append("\"walletCreated\":").append(walletNode.getProperty("walletCreated").getValue(org.apache.jackrabbit.oak.api.Type.LONG)).append(",");
            }
            if (walletNode.hasProperty("lastWrite")) {
                json.append("\"lastWrite\":").append(walletNode.getProperty("lastWrite").getValue(org.apache.jackrabbit.oak.api.Type.LONG)).append(",");
            }
            if (walletNode.hasProperty("contentCount")) {
                json.append("\"contentCount\":").append(walletNode.getProperty("contentCount").getValue(org.apache.jackrabbit.oak.api.Type.LONG)).append(",");
            }
            if (walletNode.hasProperty("totalWrites")) {
                json.append("\"totalWrites\":").append(walletNode.getProperty("totalWrites").getValue(org.apache.jackrabbit.oak.api.Type.LONG)).append(",");
            }
            if (walletNode.hasProperty("description")) {
                json.append("\"description\":\"").append(FormatUtils.escapeJson(walletNode.getProperty("description").getValue(org.apache.jackrabbit.oak.api.Type.STRING))).append("\"");
            }

            json.append("}");
            return json.toString();

        } catch (Exception e) {
            log.error("Failed to query wallet node: {}", walletAddress, e);
            return "{\"error\":\"" + FormatUtils.escapeJson(e.getMessage()) + "\"}";
        }
    }

    /**
     * Query top wallets by content count.
     */
    private String queryTopWallets() {
        StringBuilder json = new StringBuilder("{\"wallets\":[");
        boolean first = true;

        try {
            // Traverse /oak-chain tree and collect wallet metadata
            org.apache.jackrabbit.oak.spi.state.NodeState root = context.nodeStore.getRoot();
            org.apache.jackrabbit.oak.spi.state.NodeState oakChain = root.getChildNode("oak-chain");

            if (oakChain.exists()) {
                // Level 1
                for (org.apache.jackrabbit.oak.spi.state.ChildNodeEntry l1 : oakChain.getChildNodeEntries()) {
                    // Level 2
                    for (org.apache.jackrabbit.oak.spi.state.ChildNodeEntry l2 : l1.getNodeState().getChildNodeEntries()) {
                        // Level 3
                        for (org.apache.jackrabbit.oak.spi.state.ChildNodeEntry l3 : l2.getNodeState().getChildNodeEntries()) {
                            // Wallets
                            for (org.apache.jackrabbit.oak.spi.state.ChildNodeEntry wallet : l3.getNodeState().getChildNodeEntries()) {
                                String walletName = wallet.getName();
                                if (walletName.startsWith("0x")) {
                                    org.apache.jackrabbit.oak.spi.state.NodeState walletNode = wallet.getNodeState();

                                    if (!first) json.append(",");
                                    first = false;

                                    json.append("{");
                                    json.append("\"wallet\":\"").append(FormatUtils.escapeJson(walletName)).append("\",");

                                    if (walletNode.hasProperty("contentCount")) {
                                        json.append("\"contentCount\":").append(walletNode.getProperty("contentCount").getValue(org.apache.jackrabbit.oak.api.Type.LONG)).append(",");
                                    }
                                    if (walletNode.hasProperty("totalWrites")) {
                                        json.append("\"totalWrites\":").append(walletNode.getProperty("totalWrites").getValue(org.apache.jackrabbit.oak.api.Type.LONG)).append(",");
                                    }
                                    if (walletNode.hasProperty("lastWrite")) {
                                        json.append("\"lastWrite\":").append(walletNode.getProperty("lastWrite").getValue(org.apache.jackrabbit.oak.api.Type.LONG));
                                    }
                                    json.append("}");
                                }
                            }
                        }
                    }
                }
            }

        } catch (Exception e) {
            log.error("Failed to query top wallets", e);
        }

        json.append("]}");
        return json.toString();
    }

    /**
     * Query content items for a wallet.
     */
    private String queryWalletContent(String walletAddress) {
        StringBuilder json = new StringBuilder("{\"content\":[");
        boolean first = true;

        try {
            // Build wallet path
            String[] levels = org.apache.jackrabbit.oak.segment.consensus.util.WalletPathUtil.getShardLevels(walletAddress);

            // Get wallet content node
            org.apache.jackrabbit.oak.spi.state.NodeState root = context.nodeStore.getRoot();
            org.apache.jackrabbit.oak.spi.state.NodeState contentNode = root.getChildNode("oak-chain")
                .getChildNode(levels[0])
                .getChildNode(levels[1])
                .getChildNode(levels[2])
                .getChildNode(walletAddress)
                .getChildNode("content");

            if (contentNode.exists()) {
                // Traverse content children
                for (org.apache.jackrabbit.oak.spi.state.ChildNodeEntry entry : contentNode.getChildNodeEntries()) {
                    org.apache.jackrabbit.oak.spi.state.NodeState item = entry.getNodeState();

                    if (!first) json.append(",");
                    first = false;

                    json.append("{");
                    json.append("\"name\":\"").append(FormatUtils.escapeJson(entry.getName())).append("\"");

                    if (item.hasProperty("contentType")) {
                        json.append(",\"contentType\":\"").append(FormatUtils.escapeJson(item.getProperty("contentType").getValue(org.apache.jackrabbit.oak.api.Type.STRING))).append("\"");
                    }
                    if (item.hasProperty("timestamp")) {
                        json.append(",\"timestamp\":").append(item.getProperty("timestamp").getValue(org.apache.jackrabbit.oak.api.Type.LONG));
                    }
                    if (item.hasProperty("message")) {
                        json.append(",\"message\":\"").append(FormatUtils.escapeJson(item.getProperty("message").getValue(org.apache.jackrabbit.oak.api.Type.STRING))).append("\"");
                    }
                    json.append("}");
                }
            }

        } catch (Exception e) {
            log.error("Failed to query wallet content: {}", walletAddress, e);
        }

        json.append("]}");
        return json.toString();
    }
}
