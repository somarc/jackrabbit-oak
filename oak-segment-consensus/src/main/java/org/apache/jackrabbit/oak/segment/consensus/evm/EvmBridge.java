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
package org.apache.jackrabbit.oak.segment.consensus.evm;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Bridge to EVM-compatible blockchains for payment verification.
 * <p>
 * This service connects to Ethereum, Polygon, or other EVM chains
 * to verify that users have paid for write operations.
 * <p>
 * Key responsibilities:
 * - Listen for WritePayment events from OakChainPaymentVerifier contract
 * - Verify transaction confirmations
 * - Map Ethereum addresses to Oak wallet UUIDs
 * - Calculate required payment amounts
 */
public interface EvmBridge {
    
    /**
     * Verify that payment was made for a specific proposal.
     *
     * @param proposalId the proposal ID
     * @return payment proof, or null if no valid payment found
     */
    PaymentProof verifyPayment(@NotNull String proposalId);
    
    /**
     * Get the required payment amount for a write operation.
     * <p>
     * Based on segment count, byte size, and blob references.
     *
     * @param segmentCount number of segments being written
     * @param byteSize total size in bytes
     * @param blobCount number of blob references
     * @return amount in wei
     */
    @NotNull
    String calculateRequiredPayment(int segmentCount, long byteSize, int blobCount);
    
    /**
     * Map an Ethereum address to an Oak wallet UUID.
     * <p>
     * This allows users to associate their Ethereum wallet
     * with their Oak content wallet.
     *
     * @param ethereumAddress the Ethereum address (0x...)
     * @return Oak wallet UUID, or null if not mapped
     */
    String getWalletUuidForAddress(@NotNull String ethereumAddress);
    
    /**
     * Get the current block number on the connected blockchain.
     *
     * @return current block number
     */
    long getCurrentBlockNumber();
    
    /**
     * Get the blockchain network name.
     *
     * @return network name (e.g., "mainnet", "polygon", "sepolia")
     */
    @NotNull
    String getNetworkName();
    
    /**
     * Get the payment verifier contract address.
     *
     * @return contract address (0x...)
     */
    @NotNull
    String getContractAddress();
    
    /**
     * Start listening for payment events.
     */
    void start();
    
    /**
     * Stop listening for payment events.
     */
    void stop();

    /**
     * Resolve basic settlement details by on-chain proposal identifier.
     *
     * <p>This is intentionally a minimal, evolving surface for chain-derived
     * transaction basics rather than a complete domain model.
     *
     * @param proposalId on-chain proposal identifier
     * @return settlement details, or {@code null} when unavailable
     */
    @Nullable
    default SettlementDetails getSettlementDetailsByProposalId(@NotNull String proposalId) {
        PaymentProof proof = verifyPayment(proposalId);
        return proof != null ? SettlementDetails.fromProof(getNetworkName(), proof) : null;
    }

    /**
     * Resolve basic settlement details by transaction hash.
     *
     * @param transactionHash Ethereum transaction hash
     * @return settlement details, or {@code null} when unavailable
     */
    @Nullable
    default SettlementDetails getSettlementDetailsByTransactionHash(@NotNull String transactionHash) {
        return null;
    }
}
