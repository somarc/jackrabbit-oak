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
package org.apache.jackrabbit.oak.segment.consensus.economics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks validator earnings from write transactions.
 * 
 * <p>In a real-world Ethereum-based Oak network, validators earn income from:
 * <ul>
 *   <li>Write payments (users pay per MB)</li>
 *   <li>Delete taxes (proportional deletion costs)</li>
 *   <li>Fragmentation penalties (inefficient storage patterns)</li>
 * </ul>
 * 
 * <p>Payments are distributed equitably across ALL validators in the cluster,
 * not just the Aeron leader. This ensures fair compensation even though Aeron
 * has a single leader at any time.</p>
 * 
 * <p>POC Implementation: Simulates payment distribution for demonstration.</p>
 */
public class ValidatorEarningsTracker {
    
    private static final Logger log = LoggerFactory.getLogger(ValidatorEarningsTracker.class);
    
    /**
     * Validator wallet addresses (known at startup).
     * In production, this comes from cluster configuration.
     */
    private final List<String> validatorWallets;
    
    /**
     * Per-validator earnings tracking: wallet -> total wei earned
     */
    private final Map<String, BigInteger> validatorEarnings;
    
    /**
     * Per-validator transaction count
     */
    private final Map<String, AtomicLong> validatorTxCounts;
    
    /**
     * Payment tier tracking (Standard/Express/Priority)
     */
    private final Map<String, Map<PaymentTier, AtomicLong>> validatorTierCounts;
    
    /**
     * Total network earnings
     */
    private final AtomicLong totalPaymentsWei;
    
    /**
     * Epoch earnings tracking (for historical analysis)
     */
    private final Map<Long, BigInteger> earningsByEpoch;
    
    /**
     * Payment tier enumeration (future feature)
     */
    public enum PaymentTier {
        STANDARD(BigInteger.valueOf(1_000_000_000_000L), 2),     // 0.000001 ETH, 2 epochs (~12.8 min)
        EXPRESS(BigInteger.valueOf(2_000_000_000_000L), 1),      // 0.000002 ETH, 1 epoch (~6.4 min)
        PRIORITY(BigInteger.valueOf(10_000_000_000_000L), 0);    // 0.00001 ETH, immediate
        
        public final BigInteger baseRate;
        public final int epochDelay;
        
        PaymentTier(BigInteger baseRate, int epochDelay) {
            this.baseRate = baseRate;
            this.epochDelay = epochDelay;
        }
    }
    
    public ValidatorEarningsTracker(List<String> validatorWallets) {
        this.validatorWallets = new ArrayList<>(validatorWallets);
        this.validatorEarnings = new ConcurrentHashMap<>();
        this.validatorTxCounts = new ConcurrentHashMap<>();
        this.validatorTierCounts = new ConcurrentHashMap<>();
        this.totalPaymentsWei = new AtomicLong(0);
        this.earningsByEpoch = new ConcurrentHashMap<>();
        
        // Initialize validator earnings
        for (String wallet : validatorWallets) {
            validatorEarnings.put(wallet, BigInteger.ZERO);
            validatorTxCounts.put(wallet, new AtomicLong(0));
            
            Map<PaymentTier, AtomicLong> tierCounts = new HashMap<>();
            for (PaymentTier tier : PaymentTier.values()) {
                tierCounts.put(tier, new AtomicLong(0));
            }
            validatorTierCounts.put(wallet, tierCounts);
        }
        
        log.info("💰 ValidatorEarningsTracker initialized with {} validators", validatorWallets.size());
    }
    
    /**
     * Record a payment and distribute equitably across all validators.
     * 
     * @param paymentWei Payment amount in wei (from user)
     * @param tier Payment tier (Standard/Express/Priority)
     * @param epoch Current Ethereum epoch
     */
    public void recordPayment(BigInteger paymentWei, PaymentTier tier, long epoch) {
        if (validatorWallets.isEmpty()) {
            log.warn("No validators registered, cannot distribute payment");
            return;
        }
        
        // Distribute payment equitably across all validators
        BigInteger perValidatorShare = paymentWei.divide(BigInteger.valueOf(validatorWallets.size()));
        
        for (String validatorWallet : validatorWallets) {
            validatorEarnings.computeIfPresent(validatorWallet, (k, v) -> v.add(perValidatorShare));
            validatorTxCounts.get(validatorWallet).incrementAndGet();
            validatorTierCounts.get(validatorWallet).get(tier).incrementAndGet();
        }
        
        // Track total network earnings
        totalPaymentsWei.addAndGet(paymentWei.longValue());
        
        // Track by epoch
        earningsByEpoch.merge(epoch, paymentWei, BigInteger::add);
        
        log.debug("💸 Payment distributed: {} wei → {} validators ({} wei each, tier: {})", 
            paymentWei, validatorWallets.size(), perValidatorShare, tier);
    }
    
    /**
     * Get total earnings for a specific validator.
     */
    public BigInteger getValidatorEarnings(String validatorWallet) {
        return validatorEarnings.getOrDefault(validatorWallet, BigInteger.ZERO);
    }
    
    /**
     * Get transaction count for a specific validator.
     */
    public long getValidatorTxCount(String validatorWallet) {
        AtomicLong count = validatorTxCounts.get(validatorWallet);
        return count != null ? count.get() : 0;
    }
    
    /**
     * Get all validator earnings (for dashboard display).
     */
    public Map<String, ValidatorEarnings> getAllValidatorEarnings() {
        Map<String, ValidatorEarnings> result = new LinkedHashMap<>();
        
        for (String wallet : validatorWallets) {
            BigInteger earnings = validatorEarnings.get(wallet);
            long txCount = validatorTxCounts.get(wallet).get();
            Map<PaymentTier, AtomicLong> tierCounts = validatorTierCounts.get(wallet);
            
            long standardCount = tierCounts.get(PaymentTier.STANDARD).get();
            long expressCount = tierCounts.get(PaymentTier.EXPRESS).get();
            long priorityCount = tierCounts.get(PaymentTier.PRIORITY).get();
            
            result.put(wallet, new ValidatorEarnings(
                wallet, earnings, txCount, 
                standardCount, expressCount, priorityCount
            ));
        }
        
        return result;
    }
    
    /**
     * Get total network earnings.
     */
    public long getTotalNetworkEarnings() {
        return totalPaymentsWei.get();
    }
    
    /**
     * Get earnings for a specific epoch.
     */
    public BigInteger getEpochEarnings(long epoch) {
        return earningsByEpoch.getOrDefault(epoch, BigInteger.ZERO);
    }
    
    /**
     * Validator earnings snapshot (for dashboard).
     */
    public static class ValidatorEarnings {
        public final String walletAddress;
        public final BigInteger totalEarningsWei;
        public final long transactionCount;
        public final long standardTierCount;
        public final long expressTierCount;
        public final long priorityTierCount;
        
        public ValidatorEarnings(String walletAddress, BigInteger totalEarningsWei, long transactionCount,
                                long standardTierCount, long expressTierCount, long priorityTierCount) {
            this.walletAddress = walletAddress;
            this.totalEarningsWei = totalEarningsWei;
            this.transactionCount = transactionCount;
            this.standardTierCount = standardTierCount;
            this.expressTierCount = expressTierCount;
            this.priorityTierCount = priorityTierCount;
        }
        
        /**
         * Get earnings in ETH (formatted for display).
         */
        public String getEarningsEth() {
            if (totalEarningsWei.equals(BigInteger.ZERO)) {
                return "0";
            }
            BigInteger eth = totalEarningsWei.divide(BigInteger.TEN.pow(18));
            BigInteger remainder = totalEarningsWei.remainder(BigInteger.TEN.pow(18));
            
            if (remainder.equals(BigInteger.ZERO)) {
                return eth.toString();
            }
            
            String remainderStr = remainder.toString();
            while (remainderStr.length() < 18) {
                remainderStr = "0" + remainderStr;
            }
            
            // Take first 6 decimal places
            return eth.toString() + "." + remainderStr.substring(0, Math.min(6, remainderStr.length()));
        }
    }
}

