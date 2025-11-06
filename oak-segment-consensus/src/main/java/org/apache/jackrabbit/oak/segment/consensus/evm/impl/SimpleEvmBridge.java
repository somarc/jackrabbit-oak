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
import org.jetbrains.annotations.NotNull;

import java.math.BigInteger;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple EVM bridge implementation.
 * <p>
 * This is a POC implementation that simulates blockchain interaction.
 * A production implementation would:
 * - Use Web3j or ethers-java to connect to actual Ethereum nodes
 * - Listen for smart contract events using WebSocket subscriptions
 * - Verify transaction receipts and confirmations
 * - Handle chain reorganizations
 * - Implement retry logic and error handling
 * <p>
 * For now, we simulate payments in memory.
 */
public class SimpleEvmBridge implements EvmBridge {
    
    // Pricing constants (all in wei)
    private static final BigInteger BASE_FEE = new BigInteger("1000000000000000"); // 0.001 ETH
    private static final BigInteger SEGMENT_FEE = new BigInteger("100000000000000"); // 0.0001 ETH per segment
    private static final BigInteger STORAGE_FEE_PER_KB = new BigInteger("10000000000000"); // 0.00001 ETH per KB
    private static final BigInteger BLOB_FEE = new BigInteger("50000000000000"); // 0.00005 ETH per blob
    
    private final String networkName;
    private final String contractAddress;
    private final Map<String, PaymentProof> payments = new ConcurrentHashMap<>();
    private final Map<String, String> addressToWalletMapping = new ConcurrentHashMap<>();
    private long currentBlock = 1000000;
    private boolean running = false;
    
    /**
     * Create a new EVM bridge.
     *
     * @param networkName the network (e.g., "mainnet", "polygon")
     * @param contractAddress the payment verifier contract address
     */
    public SimpleEvmBridge(@NotNull String networkName, @NotNull String contractAddress) {
        this.networkName = networkName;
        this.contractAddress = contractAddress;
    }
    
    /**
     * Convenience constructor for testnet.
     */
    public SimpleEvmBridge() {
        this("sepolia", "0x742d35Cc6634C0532925a3b844Bc9e7595f0bEb0");
    }
    
    @Override
    public PaymentProof verifyPayment(@NotNull String proposalId) {
        return payments.get(proposalId);
    }
    
    @Override
    @NotNull
    public String calculateRequiredPayment(int segmentCount, long byteSize, int blobCount) {
        // write_cost = base_fee + (segment_count * segment_fee) + (byte_size * storage_fee) + (blob_count * blob_fee)
        
        BigInteger total = BASE_FEE;
        
        // Segment cost
        total = total.add(SEGMENT_FEE.multiply(BigInteger.valueOf(segmentCount)));
        
        // Storage cost (per KB)
        long kilobytes = byteSize / 1024;
        total = total.add(STORAGE_FEE_PER_KB.multiply(BigInteger.valueOf(kilobytes)));
        
        // Blob cost
        total = total.add(BLOB_FEE.multiply(BigInteger.valueOf(blobCount)));
        
        return total.toString();
    }
    
    @Override
    public String getWalletUuidForAddress(@NotNull String ethereumAddress) {
        return addressToWalletMapping.get(ethereumAddress.toLowerCase());
    }
    
    @Override
    public long getCurrentBlockNumber() {
        return currentBlock;
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
        running = true;
        System.out.println("EVM Bridge started on " + networkName + " (contract: " + contractAddress + ")");
    }
    
    @Override
    public void stop() {
        running = false;
        System.out.println("EVM Bridge stopped");
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
        System.out.println("Detected payment: " + payment);
    }
    
    /**
     * Register a wallet mapping (for testing).
     * <p>
     * In production, this would be done through a smart contract or off-chain registry.
     *
     * @param ethereumAddress the Ethereum address
     * @param walletUuid the Oak wallet UUID
     */
    public void registerWallet(@NotNull String ethereumAddress, @NotNull String walletUuid) {
        addressToWalletMapping.put(ethereumAddress.toLowerCase(), walletUuid);
        System.out.println("Registered wallet mapping: " + ethereumAddress + " -> " + walletUuid);
    }
    
    /**
     * Simulate block progression (for testing).
     */
    public void advanceBlock() {
        currentBlock++;
    }
    
    /**
     * Check if the bridge is running.
     *
     * @return true if running
     */
    public boolean isRunning() {
        return running;
    }
}

