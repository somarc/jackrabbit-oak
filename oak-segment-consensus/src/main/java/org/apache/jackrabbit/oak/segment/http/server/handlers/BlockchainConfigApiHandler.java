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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

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
        
        // Build JSON manually (no JSON library dependency in project)
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        
        // Core config
        json.append("  \"mode\": \"").append(config.getMode().getKey()).append("\",\n");
        json.append("  \"network\": \"").append(getNetworkName(config.getMode())).append("\",\n");
        json.append("  \"chainId\": ").append(getChainId(config.getMode())).append(",\n");
        json.append("  \"contractAddress\": \"").append(escapeJson(config.getContractAddress())).append("\",\n");
        
        String rpcUrl = config.getRpcUrl();
        if (rpcUrl != null && !rpcUrl.isEmpty()) {
            json.append("  \"rpcUrl\": \"").append(escapeJson(rpcUrl)).append("\",\n");
        } else {
            json.append("  \"rpcUrl\": \"\",\n");
        }
        
        // Client helper info
        json.append("  \"requiresMetaMask\": ").append(config.getMode() != BlockchainConfig.Mode.MOCK).append(",\n");
        json.append("  \"useTestnet\": ").append(config.getMode() == BlockchainConfig.Mode.SEPOLIA).append(",\n");
        json.append("  \"displayName\": \"").append(getDisplayName(config.getMode())).append("\",\n");
        json.append("  \"badgeColor\": \"").append(getBadgeColor(config.getMode())).append("\",\n");
        
        // Validator info
        if (context.selfUrl != null) {
            json.append("  \"validatorUrl\": \"").append(escapeJson(context.selfUrl)).append("\",\n");
        }
        
        // Tier pricing (could be made configurable later)
        json.append("  \"tiers\": {\n");
        json.append("    \"STANDARD\": {\n");
        json.append("      \"tier\": 0,\n");
        json.append("      \"maxDelay\": \"13 min\",\n");
        json.append("      \"estimatedCost\": \"~0.001 ETH\"\n");
        json.append("    },\n");
        json.append("    \"EXPRESS\": {\n");
        json.append("      \"tier\": 1,\n");
        json.append("      \"maxDelay\": \"6.5 min\",\n");
        json.append("      \"estimatedCost\": \"~0.002 ETH\"\n");
        json.append("    },\n");
        json.append("    \"PRIORITY\": {\n");
        json.append("      \"tier\": 2,\n");
        json.append("      \"maxDelay\": \"45 sec\",\n");
        json.append("      \"estimatedCost\": \"~0.01 ETH\"\n");
        json.append("    }\n");
        json.append("  }\n");
        json.append("}");
        
        // Send response
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json");
        response.getWriter().write(json.toString());
        
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
    
    /**
     * Escape JSON string (minimal - handles quotes and backslashes).
     */
    private String escapeJson(String str) {
        if (str == null) {
            return "";
        }
        return str.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r")
                  .replace("\t", "\\t");
    }
}

