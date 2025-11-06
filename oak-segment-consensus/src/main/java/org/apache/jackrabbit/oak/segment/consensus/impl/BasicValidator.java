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
package org.apache.jackrabbit.oak.segment.consensus.impl;

import org.apache.jackrabbit.oak.segment.consensus.Proposal;
import org.apache.jackrabbit.oak.segment.consensus.Validator;
import org.apache.jackrabbit.oak.segment.consensus.Vote;
import org.apache.jackrabbit.oak.segment.consensus.wallet.Wallet;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * Basic validator implementation with standard validation rules.
 * <p>
 * Validates:
 * - Proposal structure and required fields
 * - Path ownership (wallet owns the target path)
 * - Signature validity
 * - Payment proof exists (actual verification would check EVM chain)
 */
public class BasicValidator implements Validator {

    private static final Logger LOG = LoggerFactory.getLogger(BasicValidator.class);

    private final UUID validatorId;
    private final Wallet wallet;
    private final double stakeWeight;
    private boolean active;
    private double reputation;

    /**
     * Creates a new validator with the given wallet.
     *
     * @param wallet the validator's wallet for signing votes
     */
    public BasicValidator(@NotNull Wallet wallet) {
        this(wallet, 1.0);
    }

    /**
     * Creates a new validator with custom stake weight.
     *
     * @param wallet the validator's wallet
     * @param stakeWeight the validator's voting weight
     */
    public BasicValidator(@NotNull Wallet wallet, double stakeWeight) {
        this.validatorId = wallet.getWalletId();
        this.wallet = wallet;
        this.stakeWeight = stakeWeight;
        this.active = true;
        this.reputation = 1.0;
    }

    @Override
    @NotNull
    public UUID getValidatorId() {
        return validatorId;
    }

    @Override
    @NotNull
    public byte[] getPublicKey() {
        return wallet.getPublicKey();
    }

    @Override
    @NotNull
    public Vote validate(@NotNull Proposal proposal) {
        LOG.debug("Validating proposal: {}", proposal.getProposalId());

        // Step 1: Validate proposal structure
        String structureError = proposal.validate();
        if (structureError != null) {
            LOG.warn("Proposal {} failed structure validation: {}", 
                    proposal.getProposalId(), structureError);
            return rejectProposal(proposal, "Structure validation failed: " + structureError);
        }

        // Step 2: Verify signature
        if (!verifyProposalSignature(proposal)) {
            LOG.warn("Proposal {} has invalid signature", proposal.getProposalId());
            return rejectProposal(proposal, "Invalid signature");
        }

        // Step 3: Verify path ownership
        String expectedPrefix = "/oak-chain/content/" + proposal.getWalletId() + "/";
        if (!proposal.getTargetPath().startsWith(expectedPrefix)) {
            LOG.warn("Proposal {} attempts to write outside wallet namespace: {} (expected: {})",
                    proposal.getProposalId(), proposal.getTargetPath(), expectedPrefix);
            return rejectProposal(proposal, "Path outside wallet namespace");
        }

        // Step 4: Verify payment proof exists
        // TODO: Actually verify on EVM chain - for now just check it's not empty
        if (proposal.getPaymentProof() == null || proposal.getPaymentProof().isEmpty()) {
            LOG.warn("Proposal {} has no payment proof", proposal.getProposalId());
            return rejectProposal(proposal, "No payment proof");
        }

        // Step 5: Check segment IDs are present
        if (proposal.getSegmentIds().isEmpty()) {
            LOG.warn("Proposal {} has no segments", proposal.getProposalId());
            return rejectProposal(proposal, "No segments provided");
        }

        // All checks passed - approve!
        LOG.info("Proposal {} approved by validator {}", 
                proposal.getProposalId(), validatorId);
        return approveProposal(proposal);
    }

    @Override
    public boolean verifyVote(@NotNull Vote vote) {
        // TODO: Verify vote signature against validator's public key
        // For now, just check basic structure
        return vote.getProposalId() != null && 
               vote.getValidatorId() != null && 
               vote.getSignature() != null;
    }

    @Override
    public double getStakeWeight() {
        return stakeWeight;
    }

    @Override
    public boolean isActive() {
        return active;
    }

    /**
     * Sets the validator's active status.
     *
     * @param active true to activate, false to deactivate
     */
    public void setActive(boolean active) {
        this.active = active;
        LOG.info("Validator {} is now {}", validatorId, active ? "active" : "inactive");
    }

    @Override
    public double getReputation() {
        return reputation;
    }

    /**
     * Updates the validator's reputation score.
     *
     * @param reputation the new reputation (0.0 to 1.0)
     */
    public void setReputation(double reputation) {
        this.reputation = Math.max(0.0, Math.min(1.0, reputation));
    }

    /**
     * Verifies the proposal's signature.
     *
     * @param proposal the proposal to verify
     * @return true if signature is valid
     */
    private boolean verifyProposalSignature(Proposal proposal) {
        if (!(proposal instanceof SimpleProposal)) {
            LOG.warn("Cannot verify signature of non-SimpleProposal");
            return false;
        }

        SimpleProposal simpleProposal = (SimpleProposal) proposal;
        byte[] signableBytes = simpleProposal.getSignableBytes();
        byte[] signature = proposal.getSignature();

        // TODO: Get the wallet's public key from a registry
        // For now, we can't verify without the proposer's public key
        // In production, validators would maintain a registry of wallet ID → public key
        
        LOG.debug("Signature verification skipped (public key registry not implemented)");
        return signature != null && signature.length > 0;
    }

    /**
     * Creates an approval vote for a proposal.
     *
     * @param proposal the proposal to approve
     * @return the approval vote
     */
    private Vote approveProposal(Proposal proposal) {
        return new SimpleVote.Builder()
                .proposalId(proposal.getProposalId())
                .validatorId(validatorId)
                .approve()
                .stakeWeight(stakeWeight)
                .metadata("{\"validation_time_ms\": " + System.currentTimeMillis() + "}")
                .build();
    }

    /**
     * Creates a rejection vote for a proposal.
     *
     * @param proposal the proposal to reject
     * @param reason the rejection reason
     * @return the rejection vote
     */
    private Vote rejectProposal(Proposal proposal, String reason) {
        return new SimpleVote.Builder()
                .proposalId(proposal.getProposalId())
                .validatorId(validatorId)
                .reject(reason)
                .stakeWeight(stakeWeight)
                .build();
    }

    @Override
    public String toString() {
        return "Validator{" +
                "id=" + validatorId +
                ", weight=" + stakeWeight +
                ", active=" + active +
                ", reputation=" + reputation +
                '}';
    }
}

