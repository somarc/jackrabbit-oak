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
package org.apache.jackrabbit.oak.segment.consensus.state;

import java.util.Collections;
import java.util.List;

/**
 * Immutable consensus state data class.
 * 
 * <p>Used by dashboard and APIs to represent the current consensus state
 * in a normalized, consistent format.</p>
 */
public class ConsensusState {
    
    public final String consensusType;
    public final String currentRole;
    public final String currentLeader;
    public final int currentEpoch;
    public final int leaderTermSeconds;
    public final int secondsUntilRotation;
    public final int electorateSize;
    public final int totalValidators;
    public final List<String> allValidators;
    public final List<String> electorate;
    public final List<String> nonVotingFollowers;
    public final String nextLeader;
    public final String selfUrl;
    
    private ConsensusState(Builder builder) {
        this.consensusType = builder.consensusType;
        this.currentRole = builder.currentRole;
        this.currentLeader = builder.currentLeader;
        this.currentEpoch = builder.currentEpoch;
        this.leaderTermSeconds = builder.leaderTermSeconds;
        this.secondsUntilRotation = builder.secondsUntilRotation;
        this.electorateSize = builder.electorateSize;
        this.totalValidators = builder.totalValidators;
        this.allValidators = builder.allValidators != null ? 
            Collections.unmodifiableList(builder.allValidators) : Collections.emptyList();
        this.electorate = builder.electorate != null ? 
            Collections.unmodifiableList(builder.electorate) : Collections.emptyList();
        this.nonVotingFollowers = builder.nonVotingFollowers != null ? 
            Collections.unmodifiableList(builder.nonVotingFollowers) : Collections.emptyList();
        this.nextLeader = builder.nextLeader;
        this.selfUrl = builder.selfUrl;
    }
    
    /**
     * Builder for ConsensusState.
     */
    public static class Builder {
        private String consensusType;
        private String currentRole;
        private String currentLeader;
        private int currentEpoch;
        private int leaderTermSeconds;
        private int secondsUntilRotation;
        private int electorateSize;
        private int totalValidators;
        private List<String> allValidators;
        private List<String> electorate;
        private List<String> nonVotingFollowers;
        private String nextLeader;
        private String selfUrl;
        
        public Builder consensusType(String consensusType) {
            this.consensusType = consensusType;
            return this;
        }
        
        public Builder currentRole(String currentRole) {
            this.currentRole = currentRole;
            return this;
        }
        
        public Builder currentLeader(String currentLeader) {
            this.currentLeader = currentLeader;
            return this;
        }
        
        public Builder currentEpoch(int currentEpoch) {
            this.currentEpoch = currentEpoch;
            return this;
        }
        
        public Builder leaderTermSeconds(int leaderTermSeconds) {
            this.leaderTermSeconds = leaderTermSeconds;
            return this;
        }
        
        public Builder secondsUntilRotation(int secondsUntilRotation) {
            this.secondsUntilRotation = secondsUntilRotation;
            return this;
        }
        
        public Builder electorateSize(int electorateSize) {
            this.electorateSize = electorateSize;
            return this;
        }
        
        public Builder totalValidators(int totalValidators) {
            this.totalValidators = totalValidators;
            return this;
        }
        
        public Builder allValidators(List<String> allValidators) {
            this.allValidators = allValidators;
            return this;
        }
        
        public Builder electorate(List<String> electorate) {
            this.electorate = electorate;
            return this;
        }
        
        public Builder nonVotingFollowers(List<String> nonVotingFollowers) {
            this.nonVotingFollowers = nonVotingFollowers;
            return this;
        }
        
        public Builder nextLeader(String nextLeader) {
            this.nextLeader = nextLeader;
            return this;
        }
        
        public Builder selfUrl(String selfUrl) {
            this.selfUrl = selfUrl;
            return this;
        }
        
        public ConsensusState build() {
            return new ConsensusState(this);
        }
    }
}
