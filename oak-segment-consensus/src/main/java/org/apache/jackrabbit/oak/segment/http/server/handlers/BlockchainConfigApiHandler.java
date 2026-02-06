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

import org.apache.jackrabbit.oak.segment.consensus.config.BlockchainConfig;
import org.apache.jackrabbit.oak.segment.http.server.ServerContext;
import org.apache.jackrabbit.oak.segment.http.server.util.JsonOutputUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * API handler for blockchain configuration endpoint.
 * 
 * <p>Exposes the blockchain mode (MOCK/SEPOLIA/MAINNET) and related
 * configuration to clients (validator dashboards and Sling authors).</p>
 */
public class BlockchainConfigApiHandler {
    
    private static final Logger log = LoggerFactory.getLogger(BlockchainConfigApiHandler.class);
    private final ServerContext context;
    
    public BlockchainConfigApiHandler(ServerContext context) {
        this.context = context;
    }
    
    /**
     * Handle GET /v1/blockchain/config
     * Returns blockchain configuration as JSON.
     */
    public void handle(HttpServletResponse response) throws IOException {
        BlockchainConfig config = BlockchainConfig.getInstance();

        Map<String, Object> json = new LinkedHashMap<>();
        json.put("mode", config.getMode().getKey());
        json.put("network", getNetworkName(config.getMode()));
        json.put("chainId", getChainId(config.getMode()));
        json.put("contractAddress", config.getContractAddress());
        json.put("rpcUrl", config.getRpcUrl() != null ? config.getRpcUrl() : "");
        json.put("requiresMetaMask", config.getMode() != BlockchainConfig.Mode.MOCK);
        json.put("useTestnet", config.getMode() == BlockchainConfig.Mode.SEPOLIA);
        json.put("displayName", getDisplayName(config.getMode()));
        json.put("badgeColor", getBadgeColor(config.getMode()));
        if (context.selfUrl != null) {
            json.put("validatorUrl", context.selfUrl);
        }

        Map<String, Object> tiers = new LinkedHashMap<>();
        tiers.put("STANDARD", buildTier(0, "13 min", "~0.001 ETH"));
        tiers.put("EXPRESS", buildTier(1, "6.5 min", "~0.002 ETH"));
        tiers.put("PRIORITY", buildTier(2, "45 sec", "~0.01 ETH"));
        json.put("tiers", tiers);

        // Send response
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json");
        response.getWriter().write(JsonOutputUtil.toJson(json));
        
        log.debug("Served blockchain config: mode={}", config.getMode());
    }
    
    private String getNetworkName(BlockchainConfig.Mode mode) {
        switch (mode) {
            case MOCK: return "Mock (Simulated)";
            case SEPOLIA: return "Sepolia Testnet";
            case MAINNET: return "Ethereum Mainnet";
            default: return "Unknown";
        }
    }
    
    private int getChainId(BlockchainConfig.Mode mode) {
        switch (mode) {
            case SEPOLIA: return 11155111;
            case MAINNET: return 1;
            default: return 0; // Mock
        }
    }
    
    private String getDisplayName(BlockchainConfig.Mode mode) {
        switch (mode) {
            case MOCK: return "🎭 MOCK MODE";
            case SEPOLIA: return "✅ SEPOLIA TESTNET";
            case MAINNET: return "🔴 MAINNET";
            default: return "UNKNOWN";
        }
    }
    
    private String getBadgeColor(BlockchainConfig.Mode mode) {
        switch (mode) {
            case MOCK: return "#fbbf24"; // yellow
            case SEPOLIA: return "#10b981"; // green
            case MAINNET: return "#ef4444"; // red
            default: return "#6b7280"; // gray
        }
    }
    
    private Map<String, Object> buildTier(int tier, String maxDelay, String estimatedCost) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("tier", tier);
        t.put("maxDelay", maxDelay);
        t.put("estimatedCost", estimatedCost);
        return t;
    }
}
