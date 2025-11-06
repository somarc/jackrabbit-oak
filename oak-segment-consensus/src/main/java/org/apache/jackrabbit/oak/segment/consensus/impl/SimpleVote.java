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

import org.apache.jackrabbit.oak.segment.consensus.Vote;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Simple implementation of a validator vote.
 */
public class SimpleVote implements Vote {

    private final UUID voteId;
    private final UUID proposalId;
    private final UUID validatorId;
    private final Decision decision;
    private final long timestamp;
    private final String reason;
    private final byte[] signature;
    private final double stakeWeight;
    private final String metadata;

    private SimpleVote(Builder builder) {
        this.voteId = builder.voteId;
        this.proposalId = builder.proposalId;
        this.validatorId = builder.validatorId;
        this.decision = builder.decision;
        this.timestamp = builder.timestamp;
        this.reason = builder.reason;
        this.signature = builder.signature;
        this.stakeWeight = builder.stakeWeight;
        this.metadata = builder.metadata;
    }

    @Override
    @NotNull
    public UUID getVoteId() {
        return voteId;
    }

    @Override
    @NotNull
    public UUID getProposalId() {
        return proposalId;
    }

    @Override
    @NotNull
    public UUID getValidatorId() {
        return validatorId;
    }

    @Override
    @NotNull
    public Decision getDecision() {
        return decision;
    }

    @Override
    public long getTimestamp() {
        return timestamp;
    }

    @Override
    @Nullable
    public String getReason() {
        return reason;
    }

    @Override
    @NotNull
    public byte[] getSignature() {
        return signature;
    }

    @Override
    public double getStakeWeight() {
        return stakeWeight;
    }

    @Override
    @Nullable
    public String getMetadata() {
        return metadata;
    }

    @Override
    public String toString() {
        return "Vote{" +
                "validator=" + validatorId +
                ", proposal=" + proposalId +
                ", decision=" + decision +
                ", weight=" + stakeWeight +
                (reason != null ? ", reason='" + reason + '\'' : "") +
                '}';
    }

    /**
     * Builder for creating votes.
     */
    public static class Builder {
        private final UUID voteId = UUID.randomUUID();
        private UUID proposalId;
        private UUID validatorId;
        private Decision decision = Decision.ABSTAIN;
        private final long timestamp = System.currentTimeMillis();
        private String reason;
        private byte[] signature = new byte[0];
        private double stakeWeight = 1.0;
        private String metadata;

        public Builder proposalId(UUID proposalId) {
            this.proposalId = proposalId;
            return this;
        }

        public Builder validatorId(UUID validatorId) {
            this.validatorId = validatorId;
            return this;
        }

        public Builder decision(Decision decision) {
            this.decision = decision;
            return this;
        }

        public Builder approve() {
            this.decision = Decision.APPROVE;
            return this;
        }

        public Builder reject(String reason) {
            this.decision = Decision.REJECT;
            this.reason = reason;
            return this;
        }

        public Builder abstain(String reason) {
            this.decision = Decision.ABSTAIN;
            this.reason = reason;
            return this;
        }

        public Builder reason(String reason) {
            this.reason = reason;
            return this;
        }

        public Builder signature(byte[] signature) {
            this.signature = signature;
            return this;
        }

        public Builder stakeWeight(double stakeWeight) {
            this.stakeWeight = stakeWeight;
            return this;
        }

        public Builder metadata(String metadata) {
            this.metadata = metadata;
            return this;
        }

        public SimpleVote build() {
            if (proposalId == null) {
                throw new IllegalStateException("Proposal ID is required");
            }
            if (validatorId == null) {
                throw new IllegalStateException("Validator ID is required");
            }
            return new SimpleVote(this);
        }
    }
}

