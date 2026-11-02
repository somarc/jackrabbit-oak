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
 * A confirmed payment for a proposal on the payment contract, as the verifier checks it and the settlement
 * lookup reports it.
 */
public final class PaymentProof {

    /** Proposal kind settled by the payment contract. */
    public enum ProposalKind {
        WRITE,
        DELETE
    }

    /** Payment token used for settlement. */
    public enum PaymentToken {
        UNKNOWN,
        ETH,
        USDC
    }

    private final String transactionHash;
    private final long blockNumber;
    private final String fromAddress;
    private final String contractAddress;
    private final String proposalId;
    private final String amountWei;
    private final ValidatorEarningsTracker.PaymentTier paymentTier;
    private final ProposalKind proposalKind;
    private final PaymentToken paymentToken;
    private final int capabilityFlags;
    private final int confirmations;

    /** A write proposal paid in an unknown token, without tier or capability flags. */
    public PaymentProof(@NotNull String transactionHash, long blockNumber, @NotNull String fromAddress,
                        @NotNull String contractAddress, @NotNull String proposalId, @NotNull String amountWei,
                        int confirmations) {
        this(transactionHash, blockNumber, fromAddress, contractAddress, proposalId, amountWei, null,
            ProposalKind.WRITE, PaymentToken.UNKNOWN, 0, confirmations);
    }

    public PaymentProof(@NotNull String transactionHash, long blockNumber, @NotNull String fromAddress,
                        @NotNull String contractAddress, @NotNull String proposalId, @NotNull String amountWei,
                        @Nullable ValidatorEarningsTracker.PaymentTier paymentTier, @NotNull ProposalKind proposalKind,
                        @NotNull PaymentToken paymentToken, int capabilityFlags, int confirmations) {
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

    /** This proof with a new confirmation count. */
    @NotNull
    public PaymentProof withConfirmations(int confirmations) {
        return new PaymentProof(transactionHash, blockNumber, fromAddress, contractAddress, proposalId, amountWei,
            paymentTier, proposalKind, paymentToken, capabilityFlags, confirmations);
    }

    @NotNull
    public String getTransactionHash() {
        return transactionHash;
    }

    public long getBlockNumber() {
        return blockNumber;
    }

    @NotNull
    public String getFromAddress() {
        return fromAddress;
    }

    @NotNull
    public String getContractAddress() {
        return contractAddress;
    }

    @NotNull
    public String getProposalId() {
        return proposalId;
    }

    /** Amount in the token's smallest unit (the name predates non-ETH tokens). */
    @NotNull
    public String getAmountWei() {
        return amountWei;
    }

    /** The on-chain payment tier, or {@code null} when the event does not carry one. */
    @Nullable
    public ValidatorEarningsTracker.PaymentTier getPaymentTier() {
        return paymentTier;
    }

    @NotNull
    public ProposalKind getProposalKind() {
        return proposalKind;
    }

    @NotNull
    public PaymentToken getPaymentToken() {
        return paymentToken;
    }

    public int getCapabilityFlags() {
        return capabilityFlags;
    }

    public boolean hasCapabilityFlag(int capabilityFlag) {
        return capabilityFlag != 0 && (capabilityFlags & capabilityFlag) == capabilityFlag;
    }

    public int getConfirmations() {
        return confirmations;
    }

    public boolean isConfirmed(int requiredConfirmations) {
        return confirmations >= requiredConfirmations;
    }

    @Override
    public String toString() {
        return "PaymentProof{txHash='" + transactionHash + "', block=" + blockNumber
            + ", confirmations=" + confirmations + ", from='" + fromAddress + "', proposalId='" + proposalId
            + "', kind=" + proposalKind + ", tier=" + paymentTier + ", token=" + paymentToken
            + ", capabilityFlags=" + capabilityFlags + ", amount=" + amountWei + " wei}";
    }
}
