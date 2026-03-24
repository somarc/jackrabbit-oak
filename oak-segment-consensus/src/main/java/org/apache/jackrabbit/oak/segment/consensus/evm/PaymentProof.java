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

import org.apache.jackrabbit.oak.segment.consensus.economics.ValidatorEarningsTracker;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Proof of payment on an EVM-compatible blockchain.
 * <p>
 * This represents a transaction on Ethereum/Polygon that paid
 * for the right to write or unpublish content in Blockchain AEM.
 */
public interface PaymentProof {

    /**
     * Proposal kind settled by the payment contract.
     */
    enum ProposalKind {
        WRITE,
        DELETE
    }

    /**
     * Payment token used for settlement.
     */
    enum PaymentToken {
        UNKNOWN,
        ETH,
        USDC
    }
    
    /**
     * Get the transaction hash on the blockchain.
     *
     * @return transaction hash (0x...)
     */
    @NotNull
    String getTransactionHash();
    
    /**
     * Get the block number where this transaction was confirmed.
     *
     * @return block number
     */
    long getBlockNumber();
    
    /**
     * Get the wallet address that paid.
     *
     * @return Ethereum address (0x...)
     */
    @NotNull
    String getFromAddress();
    
    /**
     * Get the smart contract address that received payment.
     *
     * @return contract address (0x...)
     */
    @NotNull
    String getContractAddress();
    
    /**
     * Get the proposal ID this payment is for.
     *
     * @return proposal ID (matches Proposal.getProposalId())
     */
    @NotNull
    String getProposalId();
    
    /**
     * Get the amount paid in the token's smallest unit.
     *
     * <p>The method name is retained for compatibility with older Oak payment
     * bridges, but V4 settlement proofs may carry non-ETH token units.
     *
     * @return amount in base units
     */
    @NotNull
    String getAmountWei();

    /**
     * Get the on-chain payment tier when the proof source provides it.
     *
     * @return resolved payment tier, or {@code null} when unavailable
     */
    @Nullable
    default ValidatorEarningsTracker.PaymentTier getPaymentTier() {
        return null;
    }

    /**
     * Get the proposal kind settled on-chain when available.
     *
     * @return settled proposal kind, defaults to {@link ProposalKind#WRITE}
     */
    @NotNull
    default ProposalKind getProposalKind() {
        return ProposalKind.WRITE;
    }

    /**
     * Get the payment token used for settlement.
     *
     * @return token used for settlement, defaults to {@link PaymentToken#UNKNOWN}
     */
    @NotNull
    default PaymentToken getPaymentToken() {
        return PaymentToken.UNKNOWN;
    }

    /**
     * Get on-chain capability flags when the proof source provides them.
     *
     * @return settlement capability flags, or {@code 0} when unavailable
     */
    default int getCapabilityFlags() {
        return 0;
    }
    
    /**
     * Get the number of confirmations this transaction has.
     *
     * @return confirmation count
     */
    int getConfirmations();
    
    /**
     * Check if this payment has enough confirmations to be trusted.
     * <p>
     * Typically requires 12+ confirmations on Ethereum mainnet,
     * fewer on L2s or testnets.
     *
     * @param requiredConfirmations minimum confirmations needed
     * @return true if sufficiently confirmed
     */
    boolean isConfirmed(int requiredConfirmations);
}
