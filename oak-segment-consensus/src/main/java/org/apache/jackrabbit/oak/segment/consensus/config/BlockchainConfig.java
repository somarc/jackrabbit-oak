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

import java.math.BigInteger;
import java.util.Locale;
import java.util.StringJoiner;

/**
 * Unified configuration for blockchain mode settings.
 *
 * <p>Supports four blockchain mode names for configuration. Any other value is rejected:
 * <ul>
 *   <li><strong>MOCK</strong> - Pure simulation (instant, no blockchain verification)</li>
 *   <li><strong>LOCAL</strong> - Chain-backed against a local dev chain (chainId 31337), local-clock epochs</li>
 *   <li><strong>SEPOLIA</strong> - Chain-backed testnet mode (requires deployed Oak contract + RPC)</li>
 *   <li><strong>MAINNET</strong> - Reserved namespace; disabled for oak-chain v1 runtime</li>
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
 *   <li>{@code OAK_BLOCKCHAIN_MODE} - Mode: "mock", "local", "sepolia", "mainnet" (default: "mock")</li>
 *   <li>{@code OAK_BLOCKCHAIN_CONTRACT_ADDRESS} - Contract address (required for chain-backed modes)</li>
 *   <li>{@code OAK_BLOCKCHAIN_RPC_URL} - RPC URL for Web3j (required for chain-backed modes)</li>
 *   <li>{@code OAK_BLOCKCHAIN_CONTRACT_CODEHASH} - keccak256 of the contract's deployed bytecode (optional
 *       for local/sepolia, mandatory for mainnet)</li>
 *   <li>{@code OAK_BLOCKCHAIN_GAS_PRICE_GWEI} - Gas price assumption in gwei for pricing estimates</li>
 *   <li>{@code OAK_BLOCKCHAIN_GAS_WRITE_STANDARD} - Measured gas units for STANDARD write tx</li>
 *   <li>{@code OAK_BLOCKCHAIN_GAS_WRITE_EXPRESS} - Measured gas units for EXPRESS write tx</li>
 *   <li>{@code OAK_BLOCKCHAIN_GAS_WRITE_PRIORITY} - Measured gas units for PRIORITY write tx</li>
 * </ul>
 *
 * <p>System Properties (alternative to env vars):
 * <ul>
 *   <li>{@code oak.blockchain.mode}</li>
 *   <li>{@code oak.blockchain.contractAddress}</li>
 *   <li>{@code oak.blockchain.rpcUrl}</li>
 *   <li>{@code oak.blockchain.contract.codehash}</li>
 *   <li>{@code oak.blockchain.gasPriceGwei}</li>
 *   <li>{@code oak.blockchain.gas.write.standard}</li>
 *   <li>{@code oak.blockchain.gas.write.express}</li>
 *   <li>{@code oak.blockchain.gas.write.priority}</li>
 * </ul>
 */
public class BlockchainConfig {

    private static final Logger log = LoggerFactory.getLogger(BlockchainConfig.class);

    /**
     * Blockchain mode enumeration.
     */
    public enum Mode {
        /** Pure mock mode - instant payment simulation, no blockchain */
        MOCK("mock", 0L, 1),

        /** Local dev chain (e.g. anvil/hardhat) - chain-backed verification, local-clock epochs */
        LOCAL("local", 31337L, 1),

        /** Sepolia testnet - chain-backed verification with testnet infrastructure */
        SEPOLIA("sepolia", 11155111L, 3),

        /** Ethereum mainnet - config namespace retained, but runtime is disabled for v1 */
        MAINNET("mainnet", 1L, 1);

        private final String key;
        private final long expectedChainId;
        private final int defaultRequiredConfirmations;

        Mode(String key, long expectedChainId, int defaultRequiredConfirmations) {
            this.key = key;
            this.expectedChainId = expectedChainId;
            this.defaultRequiredConfirmations = defaultRequiredConfirmations;
        }

        public String getKey() {
            return key;
        }

        /** The EIP-155 chain id the RPC endpoint must report; 0 for mock (no chain). */
        public long getExpectedChainId() {
            return expectedChainId;
        }

        public boolean isChainBacked() {
            return this != MOCK;
        }

        /** Mock and local derive beacon epochs from the local clock; no beacon provider is contacted. */
        public boolean usesLocalClockEpochs() {
            return this == MOCK || this == LOCAL;
        }

        /** Payment confirmations required when none are configured explicitly. */
        public int getDefaultRequiredConfirmations() {
            return defaultRequiredConfirmations;
        }

        /** Mainnet payments count only once their block is at or below the {@code finalized} block tag. */
        public boolean usesFinalizedTag() {
            return this == MAINNET;
        }

        /** Mainnet must pin the payment contract's code hash; elsewhere it is recommended. */
        public boolean requiresContractCodeHash() {
            return this == MAINNET;
        }

        /**
         * Parses a configured mode. An unset (null or blank) value means {@link #MOCK}; any other unknown value
         * is rejected so that a typo cannot silently disable chain verification.
         */
        public static Mode fromString(String str) {
            if (str == null || str.trim().isEmpty()) {
                return MOCK;
            }
            String lower = str.toLowerCase(Locale.ROOT).trim();
            for (Mode mode : values()) {
                if (mode.key.equals(lower)) {
                    return mode;
                }
            }
            throw new IllegalArgumentException("Unknown blockchain mode '" + str.trim() + "' ("
                + PROP_MODE + " / " + ENV_MODE + "); allowed values: " + allowedValues());
        }

        static String allowedValues() {
            StringJoiner joiner = new StringJoiner(", ");
            for (Mode mode : values()) {
                joiner.add(mode.key);
            }
            return joiner.toString();
        }
    }

    // Environment variable names
    private static final String ENV_MODE = "OAK_BLOCKCHAIN_MODE";
    private static final String ENV_CONTRACT_ADDRESS = "OAK_BLOCKCHAIN_CONTRACT_ADDRESS";
    private static final String ENV_RPC_URL = "OAK_BLOCKCHAIN_RPC_URL";
    private static final String ENV_CONTRACT_CODEHASH = "OAK_BLOCKCHAIN_CONTRACT_CODEHASH";
    private static final String ENV_GAS_PRICE_GWEI = "OAK_BLOCKCHAIN_GAS_PRICE_GWEI";
    private static final String ENV_GAS_WRITE_STANDARD = "OAK_BLOCKCHAIN_GAS_WRITE_STANDARD";
    private static final String ENV_GAS_WRITE_EXPRESS = "OAK_BLOCKCHAIN_GAS_WRITE_EXPRESS";
    private static final String ENV_GAS_WRITE_PRIORITY = "OAK_BLOCKCHAIN_GAS_WRITE_PRIORITY";

    // System property names (alternative to env vars)
    private static final String PROP_MODE = "oak.blockchain.mode";
    private static final String PROP_CONTRACT_ADDRESS = "oak.blockchain.contractAddress";
    private static final String PROP_RPC_URL = "oak.blockchain.rpcUrl";
    private static final String PROP_CONTRACT_CODEHASH = "oak.blockchain.contract.codehash";
    private static final String PROP_GAS_PRICE_GWEI = "oak.blockchain.gasPriceGwei";
    private static final String PROP_GAS_WRITE_STANDARD = "oak.blockchain.gas.write.standard";
    private static final String PROP_GAS_WRITE_EXPRESS = "oak.blockchain.gas.write.express";
    private static final String PROP_GAS_WRITE_PRIORITY = "oak.blockchain.gas.write.priority";

    // Default contract addresses
    private static final String SEPOLIA_CONTRACT = "0x742d35Cc6634C0532925a3b844Bc9e7595f0bEb0";
    private static final String MAINNET_CONTRACT = "0x0000000000000000000000000000000000000000"; // SEPOLIA_PHASE: Deploy mainnet contract after Sepolia validation

    // Tier base prices (ValidatorPaymentV3_2)
    private static final BigInteger STANDARD_PRICE_WEI = new BigInteger("1000000000000000");   // 0.001 ETH
    private static final BigInteger EXPRESS_PRICE_WEI  = new BigInteger("2000000000000000");   // 0.002 ETH
    private static final BigInteger PRIORITY_PRICE_WEI = new BigInteger("10000000000000000");  // 0.01 ETH

    // Measured Sepolia gas baselines (ValidatorPaymentV3_2 payForProposal path)
    private static final long DEFAULT_GAS_PRICE_GWEI = 3L;
    private static final long DEFAULT_GAS_WRITE_STANDARD = 74_534L;
    private static final long DEFAULT_GAS_WRITE_EXPRESS = 74_534L;
    private static final long DEFAULT_GAS_WRITE_PRIORITY = 74_534L;

    // Singleton instance
    private static volatile BlockchainConfig instance;

    private final Mode mode;
    private final String contractAddress;
    private final String rpcUrl;
    private final String contractCodeHash;
    private final long gasPriceGwei;
    private final long gasWriteStandard;
    private final long gasWriteExpress;
    private final long gasWritePriority;

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
        String modeStr = RuntimeConfigValueResolver.readStringEnvFirst(PROP_MODE, ENV_MODE, "mock");
        this.mode = Mode.fromString(modeStr);

        // Determine default contract address based on mode
        String defaultContract = mode == Mode.MAINNET ? MAINNET_CONTRACT : SEPOLIA_CONTRACT;

        // Read contract address (env var > system property > mode-based default)
        this.contractAddress = RuntimeConfigValueResolver.readStringEnvFirst(PROP_CONTRACT_ADDRESS, ENV_CONTRACT_ADDRESS, defaultContract);

        // Read RPC URL (env var > system property > null)
        this.rpcUrl = RuntimeConfigValueResolver.readStringEnvFirst(PROP_RPC_URL, ENV_RPC_URL, null);
        this.contractCodeHash = RuntimeConfigValueResolver.readStringEnvFirst(PROP_CONTRACT_CODEHASH, ENV_CONTRACT_CODEHASH, null);

        // Read measured gas assumptions used for user-facing write pricing estimates
        this.gasPriceGwei = RuntimeConfigValueResolver.readLongEnvFirst(PROP_GAS_PRICE_GWEI, ENV_GAS_PRICE_GWEI, DEFAULT_GAS_PRICE_GWEI);
        this.gasWriteStandard = RuntimeConfigValueResolver.readLongEnvFirst(PROP_GAS_WRITE_STANDARD, ENV_GAS_WRITE_STANDARD, DEFAULT_GAS_WRITE_STANDARD);
        this.gasWriteExpress = RuntimeConfigValueResolver.readLongEnvFirst(PROP_GAS_WRITE_EXPRESS, ENV_GAS_WRITE_EXPRESS, DEFAULT_GAS_WRITE_EXPRESS);
        this.gasWritePriority = RuntimeConfigValueResolver.readLongEnvFirst(PROP_GAS_WRITE_PRIORITY, ENV_GAS_WRITE_PRIORITY, DEFAULT_GAS_WRITE_PRIORITY);

        // Log configuration
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        log.info("🔧 Blockchain Configuration");
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        log.info("   Mode: {}", mode);
        log.info("   Contract: {}", contractAddress);
        log.info("   RPC URL: {}", rpcUrl != null ? "configured" : "not configured");
        log.info("   Gas model: price={} gwei, writeGasUnits=[std={}, exp={}, pri={}]",
            gasPriceGwei, gasWriteStandard, gasWriteExpress, gasWritePriority);
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

        switch (mode) {
            case MOCK:
                log.warn("⚠️  MOCK MODE - Instant payment simulation (no blockchain)");
                log.warn("   Set {}=local or {}=sepolia for real verification", ENV_MODE, ENV_MODE);
                break;

            case LOCAL:
                log.info("✅ LOCAL MODE - Local dev chain verification (chainId {})", mode.getExpectedChainId());
                break;

            case SEPOLIA:
                log.info("✅ SEPOLIA MODE - Testnet verification (test ETH)");
                if (rpcUrl == null) {
                    log.warn("⚠️  RPC URL not configured - Web3j connections may fail");
                    log.warn("   Set {} to configure RPC endpoint", ENV_RPC_URL);
                }
                break;

            case MAINNET:
                log.info("🔴 MAINNET MODE - Disabled for oak-chain v1");
                if (rpcUrl == null) {
                    log.error("❌ RPC URL REQUIRED for mainnet mode!");
                    log.error("   Set {} to configure RPC endpoint", ENV_RPC_URL);
                }
                log.error("❌ MAINNET RUNTIME IS NOT ENABLED FOR V1 MERGE POSTURE");
                log.error("   Keep mainnet disabled until contract, verification, and operational review are complete");
                break;
        }
    }

    /**
     * Get the configured blockchain mode.
     *
     * @return MOCK, LOCAL, SEPOLIA, or MAINNET
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
     * Get the expected keccak256 hash of the payment contract's deployed bytecode.
     *
     * @return 0x-prefixed hash, or null if not configured
     */
    public String getContractCodeHash() {
        return contractCodeHash;
    }

    /**
     * Get RPC URL for Web3j connections.
     *
     * @return RPC URL, or null if not configured
     */
    public String getRpcUrl() {
        return rpcUrl;
    }

    public long getGasPriceGwei() {
        return gasPriceGwei;
    }

    public BigInteger getGasPriceWei() {
        return BigInteger.valueOf(gasPriceGwei).multiply(BigInteger.TEN.pow(9));
    }

    public long getWriteGasUnitsStandard() {
        return gasWriteStandard;
    }

    public long getWriteGasUnitsExpress() {
        return gasWriteExpress;
    }

    public long getWriteGasUnitsPriority() {
        return gasWritePriority;
    }

    public BigInteger getTierBasePriceWei(int tier) {
        switch (tier) {
            case 0: return STANDARD_PRICE_WEI;
            case 1: return EXPRESS_PRICE_WEI;
            default: return PRIORITY_PRICE_WEI;
        }
    }

    public long getWriteGasUnitsForTier(int tier) {
        switch (tier) {
            case 0: return gasWriteStandard;
            case 1: return gasWriteExpress;
            default: return gasWritePriority;
        }
    }

    public BigInteger estimateWriteGasFeeWei(int tier) {
        return getGasPriceWei().multiply(BigInteger.valueOf(getWriteGasUnitsForTier(tier)));
    }

    public BigInteger estimateWriteTotalCostWei(int tier) {
        return getTierBasePriceWei(tier).add(estimateWriteGasFeeWei(tier));
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
        return String.format(
            "BlockchainConfig{mode=%s, contractAddress=%s, rpcUrl=%s, gasPriceGwei=%d, writeGas=[%d,%d,%d]}",
            mode,
            contractAddress,
            rpcUrl != null ? "configured" : "null",
            gasPriceGwei,
            gasWriteStandard,
            gasWriteExpress,
            gasWritePriority
        );
    }
}
