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

import org.apache.jackrabbit.oak.segment.consensus.economics.ValidatorEarningsTracker;
import org.apache.jackrabbit.oak.segment.consensus.evm.PaymentProof;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Simple implementation of payment proof.
 */
public class SimplePaymentProof implements PaymentProof {
    
    private final String transactionHash;
    private final long blockNumber;
    private final String fromAddress;
    private final String contractAddress;
    private final String proposalId;
    private final String amountWei;
    private final ValidatorEarningsTracker.PaymentTier paymentTier;
    private final PaymentProof.ProposalKind proposalKind;
    private final PaymentProof.PaymentToken paymentToken;
    private final int capabilityFlags;
    private final int confirmations;
    
    public SimplePaymentProof(
            @NotNull String transactionHash,
            long blockNumber,
            @NotNull String fromAddress,
            @NotNull String contractAddress,
            @NotNull String proposalId,
            @NotNull String amountWei,
            int confirmations) {
        this(
            transactionHash,
            blockNumber,
            fromAddress,
            contractAddress,
            proposalId,
            amountWei,
            null,
            PaymentProof.ProposalKind.WRITE,
            PaymentProof.PaymentToken.UNKNOWN,
            0,
            confirmations
        );
    }

    public SimplePaymentProof(
            @NotNull String transactionHash,
            long blockNumber,
            @NotNull String fromAddress,
            @NotNull String contractAddress,
            @NotNull String proposalId,
            @NotNull String amountWei,
            @Nullable ValidatorEarningsTracker.PaymentTier paymentTier,
            int confirmations) {
        this(
            transactionHash,
            blockNumber,
            fromAddress,
            contractAddress,
            proposalId,
            amountWei,
            paymentTier,
            PaymentProof.ProposalKind.WRITE,
            PaymentProof.PaymentToken.UNKNOWN,
            0,
            confirmations
        );
    }

    public SimplePaymentProof(
            @NotNull String transactionHash,
            long blockNumber,
            @NotNull String fromAddress,
            @NotNull String contractAddress,
            @NotNull String proposalId,
            @NotNull String amountWei,
            @Nullable ValidatorEarningsTracker.PaymentTier paymentTier,
            @NotNull PaymentProof.ProposalKind proposalKind,
            @NotNull PaymentProof.PaymentToken paymentToken,
            int capabilityFlags,
            int confirmations) {
        this.transactionHash = transactionHash;
        this.blockNumber = blockNumber;
        this.fromAddress = fromAddress;
        this.contractAddress = contractAddress;
        this.proposalId = proposalId;
        this.amountWei = amountWei;
        this.paymentTier = paymentTier;
        this.proposalKind = proposalKind;
        this.paymentToken = paymentToken;
        this.capabilityFlags = capabilityFlags;
        this.confirmations = confirmations;
    }
    
    @Override
    @NotNull
    public String getTransactionHash() {
        return transactionHash;
    }
    
    @Override
    public long getBlockNumber() {
        return blockNumber;
    }
    
    @Override
    @NotNull
    public String getFromAddress() {
        return fromAddress;
    }
    
    @Override
    @NotNull
    public String getContractAddress() {
        return contractAddress;
    }
    
    @Override
    @NotNull
    public String getProposalId() {
        return proposalId;
    }
    
    @Override
    @NotNull
    public String getAmountWei() {
        return amountWei;
    }

    @Override
    @Nullable
    public ValidatorEarningsTracker.PaymentTier getPaymentTier() {
        return paymentTier;
    }

    @Override
    @NotNull
    public PaymentProof.ProposalKind getProposalKind() {
        return proposalKind;
    }

    @Override
    @NotNull
    public PaymentProof.PaymentToken getPaymentToken() {
        return paymentToken;
    }

    @Override
    public int getCapabilityFlags() {
        return capabilityFlags;
    }
    
    @Override
    public int getConfirmations() {
        return confirmations;
    }
    
    @Override
    public boolean isConfirmed(int requiredConfirmations) {
        return confirmations >= requiredConfirmations;
    }
    
    @Override
    public String toString() {
        return "PaymentProof{" +
                "txHash='" + transactionHash + '\'' +
                ", block=" + blockNumber +
                ", confirmations=" + confirmations +
                ", from='" + fromAddress + '\'' +
                ", proposalId='" + proposalId + '\'' +
                ", kind=" + proposalKind +
                ", tier=" + paymentTier +
                ", token=" + paymentToken +
                ", capabilityFlags=" + capabilityFlags +
                ", amount=" + amountWei + " wei" +
                '}';
    }
}
