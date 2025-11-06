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

import java.util.UUID;

/**
 * A validator in the Blockchain AEM consensus network.
 * <p>
 * Validators are responsible for:
 * <ul>
 *   <li>Receiving and examining proposals</li>
 *   <li>Validating proposal structure, signatures, and payments</li>
 *   <li>Verifying segment integrity and references</li>
 *   <li>Casting votes (APPROVE, REJECT, or ABSTAIN)</li>
 *   <li>Participating in consensus to determine if a proposal is accepted</li>
 * </ul>
 * <p>
 * In the Proof of Authority model, validators are known, trusted participants
 * (typically AEM instances operated by organizations in the network).
 *
 * @see Proposal
 * @see Vote
 * @see ConsensusEngine
 */
public interface Validator {

    /**
     * Returns the unique identifier for this validator.
     *
     * @return the validator ID
     */
    @NotNull
    UUID getValidatorId();

    /**
     * Returns the validator's public key (for signature verification).
     *
     * @return the public key bytes
     */
    @NotNull
    byte[] getPublicKey();

    /**
     * Validates a proposal and returns a vote.
     * <p>
     * This is the core validation logic. The validator checks:
     * <ul>
     *   <li>Proposal structure is valid</li>
     *   <li>Target path is within the wallet's namespace</li>
     *   <li>Signature is valid (matches wallet public key)</li>
     *   <li>Payment proof is valid (EVM transaction exists and has sufficient tokens)</li>
     *   <li>Segments exist and have valid references</li>
     *   <li>Binary URIs (if any) are accessible</li>
 </ul>
     *
     * @param proposal the proposal to validate
     * @return a vote (APPROVE, REJECT, or ABSTAIN)
     */
    @NotNull
    Vote validate(@NotNull Proposal proposal);

    /**
     * Verifies a vote's signature.
     * <p>
     * Used by other validators to ensure votes are authentic.
     *
     * @param vote the vote to verify
     * @return true if the vote signature is valid
     */
    boolean verifyVote(@NotNull Vote vote);

    /**
     * Returns the validator's stake weight.
     * <p>
     * In simple Proof of Authority, all validators have weight 1.0.
     * Future implementations might use weighted voting.
     *
     * @return the stake weight (typically 1.0)
     */
    double getStakeWeight();

    /**
     * Indicates if this validator is currently active.
     * <p>
     * Inactive validators don't participate in consensus.
     *
     * @return true if active
     */
    boolean isActive();

    /**
     * Returns the validator's reputation score (0.0 to 1.0).
     * <p>
     * Based on past behavior (uptime, correct validations, etc.).
     * May influence consensus decisions in future protocol versions.
     *
     * @return reputation score
     */
    double getReputation();
}
