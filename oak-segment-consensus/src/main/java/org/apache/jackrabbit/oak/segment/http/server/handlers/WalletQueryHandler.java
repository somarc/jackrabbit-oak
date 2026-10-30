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

import org.apache.jackrabbit.oak.api.Type;
import org.apache.jackrabbit.oak.segment.consensus.util.WalletPathUtil;
import org.apache.jackrabbit.oak.segment.http.server.ServerContext;
import org.apache.jackrabbit.oak.segment.http.server.util.ApiErrorUtil;
import org.apache.jackrabbit.oak.segment.http.server.util.FormatUtils;
import org.apache.jackrabbit.oak.segment.http.server.util.JsonOutputUtil;
import org.apache.jackrabbit.oak.spi.state.ChildNodeEntry;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
            Object payload;
            if (wallet != null && !wallet.isEmpty()) {
                // Single wallet stats
                payload = queryWalletNode(wallet);
            } else {
                // All wallets (top 100 by contentCount)
                payload = queryTopWallets();
            }
            response.getWriter().write(JsonOutputUtil.toJson(payload));

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
            response.getWriter().write(JsonOutputUtil.toJson(queryWalletContent(wallet)));

        } catch (Exception e) {
            log.error("Failed to query wallet content", e);
            ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                FormatUtils.escapeJson(e.getMessage()));
        }
    }

    /**
     * Query a single wallet node and return its metadata.
     */
    private Map<String, Object> queryWalletNode(String walletAddress) {
        try {
            // Build wallet path
            String[] levels = WalletPathUtil.getShardLevels(walletAddress);
            String walletPath = String.format("/oak-chain/%s/%s/%s/%s", levels[0], levels[1], levels[2], walletAddress);

            // Get wallet node
            NodeState root = context.nodeStore.getRoot();
            NodeState walletNode = root.getChildNode("oak-chain")
                .getChildNode(levels[0])
                .getChildNode(levels[1])
                .getChildNode(levels[2])
                .getChildNode(walletAddress);

            if (!walletNode.exists()) {
                Map<String, Object> error = new LinkedHashMap<>();
                error.put("error", "Wallet not found");
                return error;
            }

            Map<String, Object> json = new LinkedHashMap<>();
            json.put("wallet", walletAddress);
            json.put("path", walletPath);

            copyProperty(json, walletNode, "nodeType", Type.STRING);
            copyProperty(json, walletNode, "walletCreated", Type.LONG);
            copyProperty(json, walletNode, "lastWrite", Type.LONG);
            copyProperty(json, walletNode, "contentCount", Type.LONG);
            copyProperty(json, walletNode, "totalWrites", Type.LONG);
            copyProperty(json, walletNode, "description", Type.STRING);
            return json;

        } catch (Exception e) {
            log.error("Failed to query wallet node: {}", walletAddress, e);
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", e.getMessage());
            return error;
        }
    }

    /**
     * Query top wallets by content count.
     */
    private Map<String, Object> queryTopWallets() {
        List<Map<String, Object>> wallets = new ArrayList<>();

        try {
            // Traverse /oak-chain tree and collect wallet metadata
            NodeState root = context.nodeStore.getRoot();
            NodeState oakChain = root.getChildNode("oak-chain");

            if (oakChain.exists()) {
                // Level 1
                for (ChildNodeEntry l1 : oakChain.getChildNodeEntries()) {
                    // Level 2
                    for (ChildNodeEntry l2 : l1.getNodeState().getChildNodeEntries()) {
                        // Level 3
                        for (ChildNodeEntry l3 : l2.getNodeState().getChildNodeEntries()) {
                            // Wallets
                            for (ChildNodeEntry wallet : l3.getNodeState().getChildNodeEntries()) {
                                String walletName = wallet.getName();
                                if (walletName.startsWith("0x")) {
                                    NodeState walletNode = wallet.getNodeState();

                                    Map<String, Object> walletJson = new LinkedHashMap<>();
                                    walletJson.put("wallet", walletName);

                                    copyProperty(walletJson, walletNode, "contentCount", Type.LONG);
                                    copyProperty(walletJson, walletNode, "totalWrites", Type.LONG);
                                    copyProperty(walletJson, walletNode, "lastWrite", Type.LONG);
                                    wallets.add(walletJson);
                                }
                            }
                        }
                    }
                }
            }

        } catch (Exception e) {
            log.error("Failed to query top wallets", e);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("wallets", wallets);
        return out;
    }

    /**
     * Query content items for a wallet.
     */
    private Map<String, Object> queryWalletContent(String walletAddress) {
        List<Map<String, Object>> content = new ArrayList<>();

        try {
            // Build wallet path
            String[] levels = WalletPathUtil.getShardLevels(walletAddress);

            // Get wallet content node
            NodeState root = context.nodeStore.getRoot();
            NodeState contentNode = root.getChildNode("oak-chain")
                .getChildNode(levels[0])
                .getChildNode(levels[1])
                .getChildNode(levels[2])
                .getChildNode(walletAddress)
                .getChildNode("content");

            if (contentNode.exists()) {
                // Traverse content children
                for (ChildNodeEntry entry : contentNode.getChildNodeEntries()) {
                    NodeState item = entry.getNodeState();

                    Map<String, Object> itemJson = new LinkedHashMap<>();
                    itemJson.put("name", entry.getName());

                    copyProperty(itemJson, item, "contentType", Type.STRING);
                    copyProperty(itemJson, item, "timestamp", Type.LONG);
                    copyProperty(itemJson, item, "message", Type.STRING);
                    content.add(itemJson);
                }
            }

        } catch (Exception e) {
            log.error("Failed to query wallet content: {}", walletAddress, e);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("content", content);
        return out;
    }

    private static void copyProperty(Map<String, Object> target, NodeState node, String name, Type<?> type) {
        if (node.hasProperty(name)) {
            target.put(name, node.getProperty(name).getValue(type));
        }
    }
}
