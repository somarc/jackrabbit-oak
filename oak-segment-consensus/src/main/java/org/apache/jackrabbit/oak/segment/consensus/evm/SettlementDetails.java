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

import java.util.Objects;

/**
 * Minimal chain-derived settlement details for a proposal/transaction.
 *
 * <p>This surface intentionally starts with basics only. Additional fields can
 * be added as real transactions clarify the stable contract.
 */
public final class SettlementDetails {

    private final String networkName;
    private final String proposalId;
    private final String transactionHash;
    private final long blockNumber;
    private final String fromAddress;
    private final String contractAddress;
    private final String amountWei;
    private final PaymentProof.ProposalKind proposalKind;
    private final PaymentProof.PaymentToken paymentToken;
    private final int capabilityFlags;
    private final int confirmations;

    public SettlementDetails(@NotNull String networkName,
                             @NotNull String proposalId,
                             @NotNull String transactionHash,
                             long blockNumber,
                             @NotNull String fromAddress,
                             @NotNull String contractAddress,
                             @NotNull String amountWei,
                             @NotNull PaymentProof.ProposalKind proposalKind,
                             @NotNull PaymentProof.PaymentToken paymentToken,
                             int capabilityFlags,
                             int confirmations) {
        this.networkName = Objects.requireNonNull(networkName, "networkName");
        this.proposalId = Objects.requireNonNull(proposalId, "proposalId");
        this.transactionHash = Objects.requireNonNull(transactionHash, "transactionHash");
        this.blockNumber = blockNumber;
        this.fromAddress = Objects.requireNonNull(fromAddress, "fromAddress");
        this.contractAddress = Objects.requireNonNull(contractAddress, "contractAddress");
        this.amountWei = Objects.requireNonNull(amountWei, "amountWei");
        this.proposalKind = Objects.requireNonNull(proposalKind, "proposalKind");
        this.paymentToken = Objects.requireNonNull(paymentToken, "paymentToken");
        this.capabilityFlags = capabilityFlags;
        this.confirmations = confirmations;
    }

    @NotNull
    public static SettlementDetails fromProof(@NotNull String networkName, @NotNull PaymentProof proof) {
        Objects.requireNonNull(proof, "proof");
        return new SettlementDetails(
            networkName,
            proof.getProposalId(),
            proof.getTransactionHash(),
            proof.getBlockNumber(),
            proof.getFromAddress(),
            proof.getContractAddress(),
            proof.getAmountWei(),
            proof.getProposalKind(),
            proof.getPaymentToken(),
            proof.getCapabilityFlags(),
            proof.getConfirmations()
        );
    }

    @NotNull
    public String getNetworkName() {
        return networkName;
    }

    @NotNull
    public String getProposalId() {
        return proposalId;
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
    public String getAmountWei() {
        return amountWei;
    }

    @NotNull
    public PaymentProof.ProposalKind getProposalKind() {
        return proposalKind;
    }

    @NotNull
    public PaymentProof.PaymentToken getPaymentToken() {
        return paymentToken;
    }

    public int getCapabilityFlags() {
        return capabilityFlags;
    }

    public int getConfirmations() {
        return confirmations;
    }
}
