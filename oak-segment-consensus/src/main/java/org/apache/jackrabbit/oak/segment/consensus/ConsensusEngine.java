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

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * The consensus engine coordinates the distributed consensus protocol.
 * <p>
 * Responsibilities include:
 * <ul>
 *   <li>Receiving proposals from participants</li>
 *   <li>Broadcasting proposals to validators</li>
 *   <li>Collecting votes from validators</li>
 *   <li>Calculating consensus (quorum, supermajority)</li>
 *   <li>Committing approved proposals to the global segment store</li>
 *   <li>Maintaining the distributed audit log</li>
 * </ul>
 * <p>
 * This is the heart of the Blockchain AEM system.
 *
 * @see Proposal
 * @see Vote
 * @see Validator
 */
public interface ConsensusEngine {

    /**
     * Consensus result indicating the outcome of a proposal.
     */
    enum ConsensusResult {
        /**
         * Proposal achieved consensus and was committed.
         */
        APPROVED,

        /**
         * Proposal was rejected by consensus.
         */
        REJECTED,

        /**
         * Consensus could not be reached (timeout, insufficient validators, etc.).
         */
        NO_CONSENSUS,

        /**
         * Proposal is still being voted on.
         */
        PENDING
    }

    /**
     * Submits a proposal to the consensus network.
     * <p>
     * This is the entry point for participants wanting to publish content.
     * The engine will:
     * <ol>
     *   <li>Validate the proposal structure</li>
     *   <li>Broadcast it to all registered validators</li>
     *   <li>Collect votes</li>
     *   <li>Determine consensus</li>
     *   <li>Commit if approved</li>
     * </ol>
     *
     * @param proposal the proposal to submit
     * @return a future that completes with the consensus result
     */
    @NotNull
    CompletableFuture<ConsensusResult> submitProposal(@NotNull Proposal proposal);

    /**
     * Registers a validator with the consensus network.
     * <p>
     * Only registered validators participate in consensus.
     *
     * @param validator the validator to register
     * @return true if registration succeeded
     */
    boolean registerValidator(@NotNull Validator validator);

    /**
     * Unregisters a validator from the consensus network.
     *
     * @param validatorId the validator ID to unregister
     * @return true if unregistration succeeded
     */
    boolean unregisterValidator(@NotNull UUID validatorId);

    /**
     * Returns all currently registered validators.
     *
     * @return list of validators
     */
    @NotNull
    List<Validator> getValidators();

    /**
     * Returns the current active validator count.
     *
     * @return number of active validators
     */
    int getActiveValidatorCount();

    /**
     * Calculates the quorum threshold for consensus.
     * <p>
     * Typically 50% for simple majority, or 66% for supermajority.
     *
     * @return the quorum threshold (0.0 to 1.0)
     */
    double getQuorumThreshold();

    /**
     * Sets the quorum threshold.
     *
     * @param threshold the new threshold (0.0 to 1.0)
     */
    void setQuorumThreshold(double threshold);

    /**
     * Checks if a proposal has reached consensus.
     *
     * @param proposalId the proposal ID
     * @return the consensus result, or null if proposal not found
     */
    @Nullable
    ConsensusResult checkConsensus(@NotNull UUID proposalId);

    /**
     * Returns all votes for a given proposal.
     *
     * @param proposalId the proposal ID
     * @return list of votes, or empty list if none
     */
    @NotNull
    List<Vote> getVotes(@NotNull UUID proposalId);

    /**
     * Returns the proposal object by ID.
     *
     * @param proposalId the proposal ID
     * @return the proposal, or null if not found
     */
    @Nullable
    Proposal getProposal(@NotNull UUID proposalId);

    /**
     * Returns pending proposals (not yet reached consensus).
     *
     * @return list of pending proposals
     */
    @NotNull
    List<Proposal> getPendingProposals();

    /**
     * Returns approved proposals (reached consensus and committed).
     *
     * @param limit maximum number to return
     * @return list of approved proposals (most recent first)
     */
    @NotNull
    List<Proposal> getApprovedProposals(int limit);

    /**
     * Starts the consensus engine.
     * <p>
     * Begins listening for proposals and coordinating consensus.
     */
    void start();

    /**
     * Stops the consensus engine.
     * <p>
     * Gracefully shuts down, completing pending operations.
     */
    void stop();

    /**
     * Indicates if the consensus engine is running.
     *
     * @return true if running
     */
    boolean isRunning();

    /**
     * Returns consensus engine statistics.
     * <p>
     * Metrics like total proposals, approval rate, average consensus time, etc.
     *
     * @return statistics as JSON string
     */
    @NotNull
    String getStatistics();
}

