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
package org.apache.jackrabbit.oak.segment.consensus.evm.impl;

import org.apache.jackrabbit.oak.segment.consensus.evm.EvmBridge;
import org.apache.jackrabbit.oak.segment.consensus.evm.PaymentProof;
import org.apache.jackrabbit.oak.segment.consensus.queue.ProposalQueuePolicy;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mock-mode EVM bridge: every queued proposal gets a simulated payment from its registered wallet, kept in memory.
 */
public class SimpleEvmBridge implements EvmBridge {
    
    private static final Logger log = LoggerFactory.getLogger(SimpleEvmBridge.class);
    
    private final String networkName;
    private final String contractAddress;
    private final Map<String, PaymentProof> payments = new ConcurrentHashMap<>();
    private final Map<String, String> proposalToWalletMapping = new ConcurrentHashMap<>(); // proposalId -> walletAddress
    private long currentBlock = 1000000;
    
    /**
     * @param networkName the network name reported by the bridge (e.g. "mock")
     * @param contractAddress the payment verifier contract address
     */
    public SimpleEvmBridge(@NotNull String networkName, @NotNull String contractAddress) {
        this.networkName = networkName;
        this.contractAddress = contractAddress;
        log.warn("⚠️  SimpleEvmBridge in MOCK MODE - all payments auto-simulated");
    }
    
    @Override
    public PaymentProof verifyPayment(@NotNull String proposalId) {
        // Check if payment already exists
        PaymentProof existing = payments.get(proposalId);
        if (existing != null) {
            return existing;
        }
        
        log.debug("🎭 MOCK MODE: Auto-simulating payment for proposal {}", proposalId);
        // SECURITY: the wallet must be registered when the proposal is queued; a missing one is a programming error
        String walletAddress = proposalToWalletMapping.get(proposalId);
        if (walletAddress == null) {
            String errorMsg = String.format(
                "SECURITY VIOLATION: No wallet address registered for proposal %s. " +
                "registerProposalWallet() must be called when queuing. " +
                "Cannot create payment proof without authenticated wallet.",
                proposalId
            );
            log.error("🔒 {}", errorMsg);
            throw new IllegalStateException(errorMsg);
        }

        PaymentProof mockPayment = new PaymentProof(
            buildMockTransactionHash(proposalId),
            currentBlock++,
            walletAddress,
            contractAddress,
            proposalId,
            "1000000000000000", // 0.001 ETH
            ProposalQueuePolicy.requiredConfirmations()
        );
        payments.put(proposalId, mockPayment);
        return mockPayment;
    }

    @Override
    @Nullable
    public PaymentProof getSettlementDetailsByProposalId(@NotNull String proposalId) {
        // Read path: a proposal this bridge never registered (or no longer retains)
        // is a lookup miss, not a security violation. verifyPayment() keeps its
        // fail-hard registerProposalWallet() enforcement for the queue/settlement path.
        PaymentProof proof = payments.get(proposalId);
        if (proof == null && proposalToWalletMapping.containsKey(proposalId)) {
            proof = verifyPayment(proposalId);
        }
        return proof;
    }

    @Override
    @Nullable
    public PaymentProof getSettlementDetailsByTransactionHash(@NotNull String transactionHash) {
        if (transactionHash.trim().isEmpty()) {
            return null;
        }
        for (PaymentProof proof : payments.values()) {
            if (proof != null && transactionHash.equalsIgnoreCase(proof.getTransactionHash())) {
                return proof;
            }
        }
        return null;
    }
    
    /**
     * Register a wallet address for a proposal (used in mock mode).
     * This allows mock payment simulation to use the correct from address.
     * 
     * @param proposalId the proposal ID
     * @param walletAddress the wallet address
     */
    public void registerProposalWallet(@NotNull String proposalId, @NotNull String walletAddress) {
        proposalToWalletMapping.put(proposalId, walletAddress);
    }
    
    @Override
    @NotNull
    public String getNetworkName() {
        return networkName;
    }
    
    @Override
    @NotNull
    public String getContractAddress() {
        return contractAddress;
    }
    
    @Override
    public void start() {
        log.info("EVM Bridge started on {} (contract: {})", networkName, contractAddress);
    }
    
    @Override
    public void stop() {
        log.info("EVM Bridge stopped");
    }
    
    // ========== POC Helper Methods ==========
    
    /**
     * Simulate a payment (for testing).
     * <p>
     * In production, this would be detected by listening to blockchain events.
     *
     * @param payment the payment proof
     */
    public void simulatePayment(@NotNull PaymentProof payment) {
        payments.put(payment.getProposalId(), payment);
        log.debug("Detected payment: {}", payment);
    }

    private String buildMockTransactionHash(String proposalId) {
        if (proposalId != null && proposalId.matches("^0x[0-9a-fA-F]{64}$")) {
            return proposalId;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(String.valueOf(proposalId).getBytes(StandardCharsets.UTF_8));
            return "0x" + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 must be available for mock transaction synthesis", e);
        }
    }
}
