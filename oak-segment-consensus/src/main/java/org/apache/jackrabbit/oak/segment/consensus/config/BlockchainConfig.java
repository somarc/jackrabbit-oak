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
 * Unified configuration for blockchain/mock mode settings.
 * 
 * <p>Consolidates all mock/test mode flags behind environment variables and system properties.
 * This allows easy switching between mock and real blockchain functionality.
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
 *   <li>{@code OAK_BLOCKCHAIN_MOCK_MODE} - Enable mock mode (default: true for POC)</li>
 *   <li>{@code OAK_BLOCKCHAIN_NETWORK} - Blockchain network (default: "sepolia")</li>
 *   <li>{@code OAK_BLOCKCHAIN_CONTRACT_ADDRESS} - Contract address (default: testnet address)</li>
 *   <li>{@code OAK_BLOCKCHAIN_RPC_URL} - RPC URL for Web3j (default: null)</li>
 * </ul>
 * 
 * <p>System Properties (alternative to env vars):
 * <ul>
 *   <li>{@code oak.blockchain.mockMode}</li>
 *   <li>{@code oak.blockchain.network}</li>
 *   <li>{@code oak.blockchain.contractAddress}</li>
 *   <li>{@code oak.blockchain.rpcUrl}</li>
 * </ul>
 */
public class BlockchainConfig {
    
    private static final Logger log = LoggerFactory.getLogger(BlockchainConfig.class);
    
    // Environment variable names
    private static final String ENV_MOCK_MODE = "OAK_BLOCKCHAIN_MOCK_MODE";
    private static final String ENV_NETWORK = "OAK_BLOCKCHAIN_NETWORK";
    private static final String ENV_CONTRACT_ADDRESS = "OAK_BLOCKCHAIN_CONTRACT_ADDRESS";
    private static final String ENV_RPC_URL = "OAK_BLOCKCHAIN_RPC_URL";
    
    // System property names (alternative to env vars)
    private static final String PROP_MOCK_MODE = "oak.blockchain.mockMode";
    private static final String PROP_NETWORK = "oak.blockchain.network";
    private static final String PROP_CONTRACT_ADDRESS = "oak.blockchain.contractAddress";
    private static final String PROP_RPC_URL = "oak.blockchain.rpcUrl";
    
    // Default values
    private static final boolean DEFAULT_MOCK_MODE = true; // Default to mock for POC
    private static final String DEFAULT_NETWORK = "sepolia";
    private static final String DEFAULT_CONTRACT_ADDRESS = "0x742d35Cc6634C0532925a3b844Bc9e7595f0bEb0"; // Testnet address
    
    // Singleton instance
    private static volatile BlockchainConfig instance;
    
    private final boolean mockMode;
    private final String network;
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
        // Read mock mode (env var > system property > default)
        this.mockMode = readBooleanConfig(ENV_MOCK_MODE, PROP_MOCK_MODE, DEFAULT_MOCK_MODE);
        
        // Read network (env var > system property > default)
        this.network = readStringConfig(ENV_NETWORK, PROP_NETWORK, DEFAULT_NETWORK);
        
        // Read contract address (env var > system property > default)
        this.contractAddress = readStringConfig(ENV_CONTRACT_ADDRESS, PROP_CONTRACT_ADDRESS, DEFAULT_CONTRACT_ADDRESS);
        
        // Read RPC URL (env var > system property > null)
        this.rpcUrl = readStringConfig(ENV_RPC_URL, PROP_RPC_URL, null);
        
        // Log configuration
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        log.info("🔧 Blockchain Configuration");
        log.info("   Mock Mode: {} ({})", mockMode, mockMode ? "TESTING" : "PRODUCTION");
        log.info("   Network: {}", network);
        log.info("   Contract Address: {}", contractAddress);
        log.info("   RPC URL: {}", rpcUrl != null ? rpcUrl : "not configured");
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        
        if (mockMode) {
            log.warn("⚠️  MOCK MODE ENABLED - Blockchain verification disabled");
            log.warn("   Set {}={} to enable real blockchain mode", ENV_MOCK_MODE, "false");
        } else {
            log.info("✅ REAL MODE ENABLED - Blockchain verification active");
            if (rpcUrl == null) {
                log.warn("⚠️  RPC URL not configured - Web3j connections may fail");
                log.warn("   Set {} to configure RPC endpoint", ENV_RPC_URL);
            }
        }
    }
    
    /**
     * Read boolean configuration (env var > system property > default).
     */
    private boolean readBooleanConfig(String envVar, String sysProp, boolean defaultValue) {
        // Check environment variable first
        String envValue = System.getenv(envVar);
        if (envValue != null) {
            return parseBoolean(envValue, defaultValue);
        }
        
        // Check system property
        String propValue = System.getProperty(sysProp);
        if (propValue != null) {
            return parseBoolean(propValue, defaultValue);
        }
        
        // Return default
        return defaultValue;
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
     * Parse boolean string (case-insensitive: "true", "1", "yes" = true).
     */
    private boolean parseBoolean(String value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        String lower = value.toLowerCase().trim();
        return lower.equals("true") || lower.equals("1") || lower.equals("yes");
    }
    
    /**
     * Check if mock mode is enabled.
     * 
     * @return true if mock mode (testing), false if real mode (production)
     */
    public boolean isMockMode() {
        return mockMode;
    }
    
    /**
     * Get blockchain network name.
     * 
     * @return network name (e.g., "mainnet", "polygon", "sepolia")
     */
    public String getNetwork() {
        return network;
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
        return String.format("BlockchainConfig{mockMode=%s, network=%s, contractAddress=%s, rpcUrl=%s}",
            mockMode, network, contractAddress, rpcUrl != null ? "configured" : "null");
    }
}

