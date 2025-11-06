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
package org.apache.jackrabbit.oak.segment.consensus;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Represents a validator's vote on a proposal.
 * <p>
 * Part of the Blockchain AEM consensus protocol. Validators examine proposals
 * and cast votes (APPROVE, REJECT, or ABSTAIN) based on validation rules.
 * <p>
 * A proposal achieves consensus when it receives votes from a supermajority
 * of validators (typically >66%).
 *
 * @see Proposal
 * @see Validator
 * @see ConsensusEngine
 */
public interface Vote {

    /**
     * Vote decision types.
     */
    enum Decision {
        /**
         * Validator approves the proposal.
         */
        APPROVE,

        /**
         * Validator rejects the proposal.
         */
        REJECT,

        /**
         * Validator abstains from voting (e.g., due to conflicts or unavailability).
         */
        ABSTAIN
    }

    /**
     * Returns the unique identifier for this vote.
     *
     * @return the vote ID
     */
    @NotNull
    UUID getVoteId();

    /**
     * Returns the proposal ID this vote is for.
     *
     * @return the proposal ID
     */
    @NotNull
    UUID getProposalId();

    /**
     * Returns the validator ID who cast this vote.
     *
     * @return the validator ID
     */
    @NotNull
    UUID getValidatorId();

    /**
     * Returns the vote decision.
     *
     * @return APPROVE, REJECT, or ABSTAIN
     */
    @NotNull
    Decision getDecision();

    /**
     * Returns the timestamp when this vote was cast (Unix epoch milliseconds).
     *
     * @return vote timestamp
     */
    long getTimestamp();

    /**
     * Returns the reason for the vote decision.
     * <p>
     * For APPROVE votes, this may be empty. For REJECT votes, this should
     * contain the validation failure reason (e.g., "Invalid signature",
     * "Insufficient payment", "Path outside wallet namespace").
     *
     * @return the reason, or null if none provided
     */
    @Nullable
    String getReason();

    /**
     * Returns the cryptographic signature of this vote.
     * <p>
     * The vote is signed by the validator's private key to prevent forgery.
     * Other validators verify this signature using the validator's public key.
     *
     * @return the signature bytes
     */
    @NotNull
    byte[] getSignature();

    /**
     * Returns the validator's stake weight at the time of voting.
     * <p>
     * In Proof of Authority, this is typically 1 (each validator has equal weight).
     * Future versions might implement weighted voting based on stake or reputation.
     *
     * @return the stake weight
     */
    double getStakeWeight();

    /**
     * Returns additional validation metadata (optional).
     * <p>
     * May include details like:
     * <ul>
     *   <li>Segment integrity check results</li>
     *   <li>Payment verification details</li>
     *   <li>Performance metrics</li>
     * </ul>
     *
     * @return metadata as JSON string, or null if none
     */
    @Nullable
    String getMetadata();
}

