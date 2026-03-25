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
import org.apache.jackrabbit.oak.segment.consensus.config.BlockchainConfigIntrospection;
import org.apache.jackrabbit.oak.segment.consensus.queue.ProposalQueueTuningIntrospection;
import org.apache.jackrabbit.oak.segment.http.server.ServerContext;
import org.apache.jackrabbit.oak.segment.http.server.util.JsonOutputUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
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
        json.put("contractVersion", "blockchain.config.v1");
        json.put("mode", config.getMode().getKey());
        json.put("network", getNetworkName(config.getMode()));
        json.put("chainId", getChainId(config.getMode()));
        json.put("contractAddress", config.getContractAddress());
        json.put("rpcUrl", config.getRpcUrl() != null ? config.getRpcUrl() : "");
        json.put("requiresMetaMask", config.getMode() != BlockchainConfig.Mode.MOCK);
        json.put("useTestnet", config.getMode() == BlockchainConfig.Mode.SEPOLIA);
        json.put("displayName", getDisplayName(config.getMode()));
        json.put("badgeColor", getBadgeColor(config.getMode()));
        json.put("configSource", BlockchainConfigIntrospection.source());

        Map<String, Object> gasModel = new LinkedHashMap<>();
        gasModel.put("source", "measured-sepolia-baseline");
        gasModel.put("gasPriceGwei", config.getGasPriceGwei());
        gasModel.put("writeGasUnitsStandard", config.getWriteGasUnitsStandard());
        gasModel.put("writeGasUnitsExpress", config.getWriteGasUnitsExpress());
        gasModel.put("writeGasUnitsPriority", config.getWriteGasUnitsPriority());
        json.put("gasModel", gasModel);

        Map<String, Object> queueTuning = ProposalQueueTuningIntrospection.effectiveValues();
        Map<String, Object> releasePolicy = new LinkedHashMap<>();
        releasePolicy.put("schedulerModel", "adaptive-capacity");
        releasePolicy.put("releaseMode", queueTuning.get("release_mode"));
        releasePolicy.put("requiredConfirmations", queueTuning.get("required_confirmations"));
        releasePolicy.put("priorityDirectReleaseEnabled", queueTuning.get("priority_direct_release_enabled"));
        releasePolicy.put("validatorHostedBinaryUploadEnabled", queueTuning.get("validator_hosted_binary_upload_enabled"));
        releasePolicy.put("validatorHostedBinaryRequiresPriorityTier", queueTuning.get("validator_hosted_binary_requires_priority_tier"));
        releasePolicy.put("validatorHostedBinaryEntitlementSource", "settlement-capability");
        releasePolicy.put("normalPath", "Verified proposals enter an adaptive packing buffer and release immediately when Aeron is healthy.");
        releasePolicy.put("underPressure", "Packing widens and verified work can spill into backpressure overflow before release.");
        releasePolicy.put("fixedTierDelayDeprecated", true);
        json.put("releasePolicy", releasePolicy);

        if (context.selfUrl != null) {
            json.put("validatorUrl", context.selfUrl);
        }

        Map<String, Object> tiers = new LinkedHashMap<>();
        tiers.put("STANDARD", buildTier(config, 0, "Compatibility price class; adaptive release has no fixed delay."));
        tiers.put("EXPRESS", buildTier(config, 1, "Compatibility price class; adaptive release has no fixed delay."));
        tiers.put("PRIORITY", buildTier(config, 2,
            Boolean.TRUE.equals(queueTuning.get("priority_direct_release_enabled"))
                ? "Compatibility price class; direct release is currently enabled after verification."
                : "Compatibility price class; direct release is disabled unless explicitly toggled."));
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

    private Map<String, Object> buildTier(BlockchainConfig config, int tier, String releaseBehavior) {
        BigInteger baseFeeWei = config.getTierBasePriceWei(tier);
        long gasUnits = config.getWriteGasUnitsForTier(tier);
        BigInteger gasFeeWei = config.estimateWriteGasFeeWei(tier);
        BigInteger totalWei = baseFeeWei.add(gasFeeWei);

        Map<String, Object> t = new LinkedHashMap<>();
        t.put("tier", tier);
        t.put("releaseBehavior", releaseBehavior);
        t.put("maxDelay", "Adaptive (no fixed epoch wait)");
        t.put("baseFeeWei", baseFeeWei.toString());
        t.put("gasUnits", gasUnits);
        t.put("gasPriceGwei", config.getGasPriceGwei());
        t.put("estimatedGasFeeWei", gasFeeWei.toString());
        t.put("estimatedTotalWei", totalWei.toString());
        t.put("estimatedCost", "~" + formatEth(totalWei) + " ETH");
        return t;
    }

    private String formatEth(BigInteger wei) {
        BigDecimal eth = new BigDecimal(wei).divide(new BigDecimal("1000000000000000000"), 6, RoundingMode.HALF_UP);
        return eth.stripTrailingZeros().toPlainString();
    }
}
