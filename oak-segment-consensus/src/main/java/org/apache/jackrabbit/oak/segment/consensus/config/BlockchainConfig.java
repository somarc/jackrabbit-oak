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
package org.apache.jackrabbit.oak.segment.consensus.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Unified configuration for blockchain mode settings.
 * 
 * <p>Supports three distinct blockchain modes:
 * <ul>
 *   <li><strong>MOCK</strong> - Pure simulation (instant, no blockchain verification)</li>
 *   <li><strong>SEPOLIA</strong> - Sepolia testnet (real verification, test ETH)</li>
 *   <li><strong>MAINNET</strong> - Ethereum mainnet (real verification, real ETH)</li>
 * </ul>
 * 
 * <p>Configuration Priority:
 * <ol>
 *   <li>Environment variables (highest priority)</li>
 *   <li>System properties (-D flags)</li>
 *   <li>Default values (lowest priority)</li>
 * </ol>
 * 
 * <p>Environment Variables:
 * <ul>
 *   <li>{@code OAK_BLOCKCHAIN_MODE} - Mode: "mock", "sepolia", "mainnet" (default: "mock")</li>
 *   <li>{@code OAK_BLOCKCHAIN_CONTRACT_ADDRESS} - Contract address (optional, has defaults)</li>
 *   <li>{@code OAK_BLOCKCHAIN_RPC_URL} - RPC URL for Web3j (required for sepolia/mainnet)</li>
 * </ul>
 * 
 * <p>System Properties (alternative to env vars):
 * <ul>
 *   <li>{@code oak.blockchain.mode}</li>
 *   <li>{@code oak.blockchain.contractAddress}</li>
 *   <li>{@code oak.blockchain.rpcUrl}</li>
 * </ul>
 */
public class BlockchainConfig {
    
    private static final Logger log = LoggerFactory.getLogger(BlockchainConfig.class);
    
    /**
     * Blockchain mode enumeration.
     */
    public enum Mode {
        /** Pure mock mode - instant payment simulation, no blockchain */
        MOCK("mock"),
        
        /** Sepolia testnet - real blockchain verification with test ETH */
        SEPOLIA("sepolia"),
        
        /** Ethereum mainnet - real blockchain verification with real ETH */
        MAINNET("mainnet");
        
        private final String key;
        
        Mode(String key) {
            this.key = key;
        }
        
        public String getKey() {
            return key;
        }
        
        public static Mode fromString(String str) {
            if (str == null) {
                return MOCK; // Default
            }
            String lower = str.toLowerCase().trim();
            for (Mode mode : values()) {
                if (mode.key.equals(lower)) {
                    return mode;
                }
            }
            return MOCK; // Default
        }
    }
    
    // Environment variable names
    private static final String ENV_MODE = "OAK_BLOCKCHAIN_MODE";
    private static final String ENV_CONTRACT_ADDRESS = "OAK_BLOCKCHAIN_CONTRACT_ADDRESS";
    private static final String ENV_RPC_URL = "OAK_BLOCKCHAIN_RPC_URL";
    
    // System property names (alternative to env vars)
    private static final String PROP_MODE = "oak.blockchain.mode";
    private static final String PROP_CONTRACT_ADDRESS = "oak.blockchain.contractAddress";
    private static final String PROP_RPC_URL = "oak.blockchain.rpcUrl";
    
    // Default contract addresses
    private static final String SEPOLIA_CONTRACT = "0x742d35Cc6634C0532925a3b844Bc9e7595f0bEb0";
    private static final String MAINNET_CONTRACT = "0x0000000000000000000000000000000000000000"; // TODO: Deploy mainnet contract
    
    // Singleton instance
    private static volatile BlockchainConfig instance;
    
    private final Mode mode;
    private final String contractAddress;
    private final String rpcUrl;
    
    /**
     * Get singleton instance (lazy initialization).
     */
    public static BlockchainConfig getInstance() {
        if (instance == null) {
            synchronized (BlockchainConfig.class) {
                if (instance == null) {
                    instance = new BlockchainConfig();
                }
            }
        }
        return instance;
    }
    
    /**
     * Create configuration from environment variables and system properties.
     */
    private BlockchainConfig() {
        // Read mode (env var > system property > default)
        String modeStr = readStringConfig(ENV_MODE, PROP_MODE, "mock");
        this.mode = Mode.fromString(modeStr);
        
        // Determine default contract address based on mode
        String defaultContract = mode == Mode.MAINNET ? MAINNET_CONTRACT : SEPOLIA_CONTRACT;
        
        // Read contract address (env var > system property > mode-based default)
        this.contractAddress = readStringConfig(ENV_CONTRACT_ADDRESS, PROP_CONTRACT_ADDRESS, defaultContract);
        
        // Read RPC URL (env var > system property > null)
        this.rpcUrl = readStringConfig(ENV_RPC_URL, PROP_RPC_URL, null);
        
        // Log configuration
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        log.info("🔧 Blockchain Configuration");
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        log.info("   Mode: {}", mode);
        log.info("   Contract: {}", contractAddress);
        log.info("   RPC URL: {}", rpcUrl != null ? rpcUrl : "not configured");
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        
        switch (mode) {
            case MOCK:
                log.warn("⚠️  MOCK MODE - Instant payment simulation (no blockchain)");
                log.warn("   Set {}=sepolia or {}=mainnet for real verification", ENV_MODE, ENV_MODE);
                break;
                
            case SEPOLIA:
                log.info("✅ SEPOLIA MODE - Testnet verification (test ETH)");
                if (rpcUrl == null) {
                    log.warn("⚠️  RPC URL not configured - Web3j connections may fail");
                    log.warn("   Set {} to configure RPC endpoint", ENV_RPC_URL);
                }
                break;
                
            case MAINNET:
                log.info("🔴 MAINNET MODE - Production verification (REAL ETH)");
                if (rpcUrl == null) {
                    log.error("❌ RPC URL REQUIRED for mainnet mode!");
                    log.error("   Set {} to configure RPC endpoint", ENV_RPC_URL);
                }
                if (MAINNET_CONTRACT.equals("0x0000000000000000000000000000000000000000")) {
                    log.error("❌ MAINNET CONTRACT NOT DEPLOYED!");
                    log.error("   Deploy contract and set {} env var", ENV_CONTRACT_ADDRESS);
                }
                break;
        }
    }
    
    /**
     * Read string configuration (env var > system property > default).
     */
    private String readStringConfig(String envVar, String sysProp, String defaultValue) {
        // Check environment variable first
        String envValue = System.getenv(envVar);
        if (envValue != null && !envValue.isEmpty()) {
            return envValue;
        }
        
        // Check system property
        String propValue = System.getProperty(sysProp);
        if (propValue != null && !propValue.isEmpty()) {
            return propValue;
        }
        
        // Return default
        return defaultValue;
    }
    
    /**
     * Get the configured blockchain mode.
     * 
     * @return MOCK, SEPOLIA, or MAINNET
     */
    public Mode getMode() {
        return mode;
    }
    
    /**
     * Check if mock mode is enabled (for backwards compatibility).
     * 
     * @return true if MOCK mode, false otherwise
     */
    public boolean isMockMode() {
        return mode == Mode.MOCK;
    }
    
    /**
     * Get blockchain network name (for backwards compatibility).
     * 
     * @return network name (e.g., "mainnet", "sepolia", "mock")
     */
    public String getNetwork() {
        return mode.getKey();
    }
    
    /**
     * Get smart contract address.
     * 
     * @return contract address (0x...)
     */
    public String getContractAddress() {
        return contractAddress;
    }
    
    /**
     * Get RPC URL for Web3j connections.
     * 
     * @return RPC URL, or null if not configured
     */
    public String getRpcUrl() {
        return rpcUrl;
    }
    
    /**
     * Reset singleton instance (for testing).
     */
    public static void reset() {
        synchronized (BlockchainConfig.class) {
            instance = null;
        }
    }
    
    @Override
    public String toString() {
        return String.format("BlockchainConfig{mode=%s, contractAddress=%s, rpcUrl=%s}",
            mode, contractAddress, rpcUrl != null ? "configured" : "null");
    }
}

