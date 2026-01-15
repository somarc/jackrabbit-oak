/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jackrabbit.oak.segment.consensus.economics;

import org.apache.jackrabbit.oak.segment.consensus.contracts.PropagationPaymentClient;
import org.apache.jackrabbit.oak.segment.consensus.registry.ShardRegistry;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/**
 * Calculator for propagation costs before write submission.
 * <p>
 * This service provides:
 * <ul>
 *   <li>Pre-write cost estimation (local calculation)</li>
 *   <li>Cost breakdown (local fee, propagation fee, delete fee)</li>
 *   <li>Human-readable cost formatting</li>
 *   <li>Storage mode recommendations based on content lifecycle</li>
 * </ul>
 * <p>
 * The calculator can work in two modes:
 * <ul>
 *   <li><b>Local mode</b>: Uses constants for quick estimates (no network calls)</li>
 *   <li><b>Contract mode</b>: Calls PropagationPayment contract for exact costs</li>
 * </ul>
 */
public class PropagationCostCalculator {
    
    private static final Logger LOG = LoggerFactory.getLogger(PropagationCostCalculator.class);
    
    // Wei to ETH conversion
    private static final BigInteger WEI_PER_ETH = new BigInteger("1000000000000000000");
    
    // Default ETH price for USD estimates (can be updated)
    private static final BigDecimal DEFAULT_ETH_PRICE_USD = new BigDecimal("3000");
    
    private final ShardRegistry shardRegistry;
    private final PropagationPaymentClient paymentClient;
    private volatile BigDecimal ethPriceUsd = DEFAULT_ETH_PRICE_USD;
    
    /**
     * Create a cost calculator with local-only estimation.
     *
     * @param shardRegistry Registry for cluster count
     */
    public PropagationCostCalculator(@NotNull ShardRegistry shardRegistry) {
        this(shardRegistry, null);
    }
    
    /**
     * Create a cost calculator with contract integration.
     *
     * @param shardRegistry Registry for cluster count
     * @param paymentClient Payment contract client (optional)
     */
    public PropagationCostCalculator(
            @NotNull ShardRegistry shardRegistry,
            PropagationPaymentClient paymentClient) {
        this.shardRegistry = shardRegistry;
        this.paymentClient = paymentClient;
    }
    
    /**
     * Update ETH price for USD estimates.
     *
     * @param priceUsd Current ETH price in USD
     */
    public void setEthPriceUsd(BigDecimal priceUsd) {
        this.ethPriceUsd = priceUsd;
    }
    
    // ═══════════════════════════════════════════════════════════════
    // COST ESTIMATION
    // ═══════════════════════════════════════════════════════════════
    
    /**
     * Estimate cost for archival content.
     *
     * @param sizeBytes Content size in bytes
     * @return Cost estimate
     */
    public CostEstimate estimateArchivalCost(long sizeBytes) {
        int clusterCount = getClusterCount();
        BigInteger costWei = PropagationPaymentClient.estimateCostLocally(
            sizeBytes,
            clusterCount,
            PropagationPaymentClient.STORAGE_MODE_ARCHIVAL
        );
        
        return new CostEstimate(
            StorageMode.ARCHIVAL,
            sizeBytes,
            clusterCount,
            costWei,
            BigInteger.ZERO, // No delete fee for archival
            ethPriceUsd
        );
    }
    
    /**
     * Estimate cost for ephemeral prepaid content.
     *
     * @param sizeBytes Content size in bytes
     * @return Cost estimate
     */
    public CostEstimate estimateEphemeralPrepaidCost(long sizeBytes) {
        int clusterCount = getClusterCount();
        BigInteger costWei = PropagationPaymentClient.estimateCostLocally(
            sizeBytes,
            clusterCount,
            PropagationPaymentClient.STORAGE_MODE_EPHEMERAL_PREPAID
        );
        
        // Calculate delete fee portion
        BigInteger bufferedCount = BigInteger.valueOf(clusterCount)
            .multiply(PropagationPaymentClient.BPS_DENOMINATOR.add(PropagationPaymentClient.GROWTH_BUFFER_BPS))
            .divide(PropagationPaymentClient.BPS_DENOMINATOR);
        BigInteger deleteFee = PropagationPaymentClient.BASE_DELETE_FEE.multiply(bufferedCount);
        
        return new CostEstimate(
            StorageMode.EPHEMERAL_PREPAID,
            sizeBytes,
            clusterCount,
            costWei,
            deleteFee,
            ethPriceUsd
        );
    }
    
    /**
     * Estimate cost for on-demand delete.
     *
     * @return Cost estimate for delete operation
     */
    public CostEstimate estimateOnDemandDeleteCost() {
        int clusterCount = getClusterCount();
        BigInteger deleteFee = PropagationPaymentClient.BASE_DELETE_FEE
            .multiply(BigInteger.valueOf(clusterCount));
        
        return new CostEstimate(
            StorageMode.DELETE_ONDEMAND,
            0,
            clusterCount,
            deleteFee,
            deleteFee,
            ethPriceUsd
        );
    }
    
    /**
     * Compare costs for archival vs ephemeral.
     *
     * @param sizeBytes Content size in bytes
     * @return Cost comparison
     */
    public CostComparison compareCosts(long sizeBytes) {
        CostEstimate archival = estimateArchivalCost(sizeBytes);
        CostEstimate ephemeralPrepaid = estimateEphemeralPrepaidCost(sizeBytes);
        CostEstimate deleteOnDemand = estimateOnDemandDeleteCost();
        
        return new CostComparison(archival, ephemeralPrepaid, deleteOnDemand);
    }
    
    /**
     * Recommend storage mode based on expected content lifecycle.
     *
     * @param sizeBytes Content size in bytes
     * @param expectedDaysToKeep Expected days before deletion (0 = forever)
     * @return Recommended storage mode with reasoning
     */
    public StorageRecommendation recommend(long sizeBytes, int expectedDaysToKeep) {
        CostComparison comparison = compareCosts(sizeBytes);
        
        if (expectedDaysToKeep == 0) {
            // Forever = archival
            return new StorageRecommendation(
                StorageMode.ARCHIVAL,
                comparison.archival,
                "Content will be stored permanently. Archival mode is most cost-effective."
            );
        }
        
        // Calculate break-even point
        // Archival cost vs Ephemeral prepaid cost
        BigInteger archivalCost = comparison.archival.totalCostWei;
        BigInteger ephemeralCost = comparison.ephemeralPrepaid.totalCostWei;
        
        if (ephemeralCost.compareTo(archivalCost.multiply(BigInteger.TWO)) <= 0) {
            // Ephemeral is less than 2x archival - recommend ephemeral for short-term
            if (expectedDaysToKeep <= 60) {
                return new StorageRecommendation(
                    StorageMode.EPHEMERAL_PREPAID,
                    comparison.ephemeralPrepaid,
                    String.format(
                        "Content will be deleted in %d days. Ephemeral prepaid locks in delete cost now.",
                        expectedDaysToKeep
                    )
                );
            }
        }
        
        // For longer retention, archival is usually better
        return new StorageRecommendation(
            StorageMode.ARCHIVAL,
            comparison.archival,
            String.format(
                "Content will be kept for %d days. Archival is more cost-effective for longer retention.",
                expectedDaysToKeep
            )
        );
    }
    
    // ═══════════════════════════════════════════════════════════════
    // HELPER METHODS
    // ═══════════════════════════════════════════════════════════════
    
    private int getClusterCount() {
        try {
            return shardRegistry.getActiveClusters().size();
        } catch (Exception e) {
            LOG.warn("Failed to get cluster count, using default of 1: {}", e.getMessage());
            return 1;
        }
    }
    
    // ═══════════════════════════════════════════════════════════════
    // DATA CLASSES
    // ═══════════════════════════════════════════════════════════════
    
    /**
     * Storage mode options.
     */
    public enum StorageMode {
        ARCHIVAL("Archival (Permanent)"),
        EPHEMERAL_PREPAID("Ephemeral (Prepaid Delete)"),
        EPHEMERAL_ONDEMAND("Ephemeral (On-Demand Delete)"),
        DELETE_ONDEMAND("Delete (On-Demand)");
        
        private final String displayName;
        
        StorageMode(String displayName) {
            this.displayName = displayName;
        }
        
        public String getDisplayName() {
            return displayName;
        }
    }
    
    /**
     * Cost estimate for a storage operation.
     */
    public static class CostEstimate {
        public final StorageMode mode;
        public final long sizeBytes;
        public final int clusterCount;
        public final BigInteger totalCostWei;
        public final BigInteger deleteFeeWei;
        public final BigDecimal ethPriceUsd;
        
        public CostEstimate(
                StorageMode mode,
                long sizeBytes,
                int clusterCount,
                BigInteger totalCostWei,
                BigInteger deleteFeeWei,
                BigDecimal ethPriceUsd) {
            this.mode = mode;
            this.sizeBytes = sizeBytes;
            this.clusterCount = clusterCount;
            this.totalCostWei = totalCostWei;
            this.deleteFeeWei = deleteFeeWei;
            this.ethPriceUsd = ethPriceUsd;
        }
        
        /**
         * Get total cost in ETH.
         */
        public BigDecimal getTotalCostEth() {
            return new BigDecimal(totalCostWei)
                .divide(new BigDecimal(WEI_PER_ETH), 18, RoundingMode.HALF_UP);
        }
        
        /**
         * Get total cost in USD.
         */
        public BigDecimal getTotalCostUsd() {
            return getTotalCostEth().multiply(ethPriceUsd).setScale(2, RoundingMode.HALF_UP);
        }
        
        /**
         * Get delete fee in ETH.
         */
        public BigDecimal getDeleteFeeEth() {
            return new BigDecimal(deleteFeeWei)
                .divide(new BigDecimal(WEI_PER_ETH), 18, RoundingMode.HALF_UP);
        }
        
        /**
         * Get delete fee in USD.
         */
        public BigDecimal getDeleteFeeUsd() {
            return getDeleteFeeEth().multiply(ethPriceUsd).setScale(2, RoundingMode.HALF_UP);
        }
        
        /**
         * Get human-readable size.
         */
        public String getHumanReadableSize() {
            if (sizeBytes < 1024) {
                return sizeBytes + " B";
            } else if (sizeBytes < 1024 * 1024) {
                return String.format("%.2f KB", sizeBytes / 1024.0);
            } else if (sizeBytes < 1024 * 1024 * 1024) {
                return String.format("%.2f MB", sizeBytes / (1024.0 * 1024));
            } else {
                return String.format("%.2f GB", sizeBytes / (1024.0 * 1024 * 1024));
            }
        }
        
        @Override
        public String toString() {
            return String.format(
                "%s: %s across %d clusters = %s ETH (~$%s USD)",
                mode.getDisplayName(),
                getHumanReadableSize(),
                clusterCount,
                getTotalCostEth().stripTrailingZeros().toPlainString(),
                getTotalCostUsd().toPlainString()
            );
        }
    }
    
    /**
     * Comparison of costs across storage modes.
     */
    public static class CostComparison {
        public final CostEstimate archival;
        public final CostEstimate ephemeralPrepaid;
        public final CostEstimate deleteOnDemand;
        
        public CostComparison(
                CostEstimate archival,
                CostEstimate ephemeralPrepaid,
                CostEstimate deleteOnDemand) {
            this.archival = archival;
            this.ephemeralPrepaid = ephemeralPrepaid;
            this.deleteOnDemand = deleteOnDemand;
        }
        
        /**
         * Get the cheapest option for permanent storage.
         */
        public CostEstimate getCheapestPermanent() {
            return archival;
        }
        
        /**
         * Get the cheapest option for temporary storage.
         */
        public CostEstimate getCheapestTemporary() {
            return ephemeralPrepaid;
        }
        
        /**
         * Calculate how much more expensive ephemeral is vs archival.
         */
        public BigDecimal getEphemeralPremiumPercent() {
            if (archival.totalCostWei.equals(BigInteger.ZERO)) {
                return BigDecimal.ZERO;
            }
            BigDecimal archivalCost = new BigDecimal(archival.totalCostWei);
            BigDecimal ephemeralCost = new BigDecimal(ephemeralPrepaid.totalCostWei);
            return ephemeralCost.subtract(archivalCost)
                .divide(archivalCost, 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));
        }
        
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("Cost Comparison:\n");
            sb.append("  ").append(archival).append("\n");
            sb.append("  ").append(ephemeralPrepaid).append("\n");
            sb.append("  ").append(deleteOnDemand).append("\n");
            sb.append(String.format("  Ephemeral premium: +%.1f%%", getEphemeralPremiumPercent()));
            return sb.toString();
        }
    }
    
    /**
     * Storage mode recommendation.
     */
    public static class StorageRecommendation {
        public final StorageMode recommendedMode;
        public final CostEstimate estimate;
        public final String reasoning;
        
        public StorageRecommendation(
                StorageMode recommendedMode,
                CostEstimate estimate,
                String reasoning) {
            this.recommendedMode = recommendedMode;
            this.estimate = estimate;
            this.reasoning = reasoning;
        }
        
        @Override
        public String toString() {
            return String.format(
                "Recommendation: %s\n  Cost: %s\n  Reason: %s",
                recommendedMode.getDisplayName(),
                estimate,
                reasoning
            );
        }
    }
}
