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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Normalized consensus state - single source of truth for all consensus information.
 * 
 * <p>This class provides a consistent view of consensus state that all components
 * (APIs, dashboard, heartbeats, leader election) can use. This eliminates
 * inconsistencies from multiple sources of truth.</p>
 * 
 * <p>All validator lists are sorted alphabetically for deterministic ordering.</p>
 */
public class ConsensusState {
    
    /**
     * Consensus type: "leader-based", "blockchain-poa", "dag", or "none"
     */
    public final String consensusType;
    
    /**
     * Current role: "LEADER", "FOLLOWER", or "STANDALONE"
     */
    public final String currentRole;
    
    /**
     * URL of the current leader (null if standalone)
     */
    public final String currentLeader;
    
    /**
     * Current epoch number
     */
    public final int currentEpoch;
    
    /**
     * Leader term duration in seconds
     */
    public final int leaderTermSeconds;
    
    /**
     * Seconds until next epoch transition (leader rotation)
     */
    public final int secondsUntilRotation;
    
    /**
     * Number of voting validators (electorate size)
     */
    public final int electorateSize;
    
    /**
     * Total number of validators (voting + non-voting)
     */
    public final int totalValidators;
    
    /**
     * All validators in the network (voting + non-voting), sorted alphabetically.
     * Includes self.
     */
    public final List<String> allValidators;
    
    /**
     * Voting validators only (electorate), sorted alphabetically.
     * These validators can vote and become leaders.
     */
    public final List<String> electorate;
    
    /**
     * Non-voting followers (on probation), sorted alphabetically.
     * These validators receive heartbeats and replicate data but cannot vote or lead.
     */
    public final List<String> nonVotingFollowers;
    
    /**
     * Next leader for the next epoch (calculated deterministically)
     */
    public final String nextLeader;
    
    /**
     * Self URL (this validator's URL)
     */
    public final String selfUrl;
    
    public ConsensusState(String consensusType, String currentRole, String currentLeader,
                         int currentEpoch, int leaderTermSeconds, int secondsUntilRotation,
                         int electorateSize, int totalValidators,
                         List<String> allValidators, List<String> electorate,
                         List<String> nonVotingFollowers, String nextLeader, String selfUrl) {
        this.consensusType = consensusType;
        this.currentRole = currentRole;
        this.currentLeader = currentLeader;
        this.currentEpoch = currentEpoch;
        this.leaderTermSeconds = leaderTermSeconds;
        this.secondsUntilRotation = secondsUntilRotation;
        this.electorateSize = electorateSize;
        this.totalValidators = totalValidators;
        this.allValidators = Collections.unmodifiableList(new ArrayList<>(allValidators));
        this.electorate = Collections.unmodifiableList(new ArrayList<>(electorate));
        this.nonVotingFollowers = Collections.unmodifiableList(new ArrayList<>(nonVotingFollowers));
        this.nextLeader = nextLeader;
        this.selfUrl = selfUrl;
    }
    
    /**
     * Check if this validator is the leader.
     */
    public boolean isLeader() {
        return "LEADER".equals(currentRole);
    }
    
    /**
     * Check if this validator is a follower.
     */
    public boolean isFollower() {
        return "FOLLOWER".equals(currentRole);
    }
    
    /**
     * Check if this validator is on probation (non-voting).
     */
    public boolean isOnProbation() {
        return nonVotingFollowers.contains(selfUrl);
    }
    
    /**
     * Get peer validators (all validators except self).
     */
    public List<String> getPeerValidators() {
        List<String> peers = new ArrayList<>(allValidators);
        peers.remove(selfUrl);
        return peers;
    }
    
    /**
     * Builder for creating ConsensusState instances.
     */
    public static class Builder {
        private String consensusType = "none";
        private String currentRole = "STANDALONE";
        private String currentLeader = null;
        private int currentEpoch = 0;
        private int leaderTermSeconds = 60;
        private int secondsUntilRotation = 0;
        private int electorateSize = 0;
        private int totalValidators = 1;
        private List<String> allValidators = new ArrayList<>();
        private List<String> electorate = new ArrayList<>();
        private List<String> nonVotingFollowers = new ArrayList<>();
        private String nextLeader = null;
        private String selfUrl = null;
        
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
            this.allValidators = new ArrayList<>(allValidators);
            Collections.sort(this.allValidators);
            return this;
        }
        
        public Builder electorate(List<String> electorate) {
            this.electorate = new ArrayList<>(electorate);
            Collections.sort(this.electorate);
            return this;
        }
        
        public Builder nonVotingFollowers(List<String> nonVotingFollowers) {
            this.nonVotingFollowers = new ArrayList<>(nonVotingFollowers);
            Collections.sort(this.nonVotingFollowers);
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
            return new ConsensusState(
                consensusType, currentRole, currentLeader,
                currentEpoch, leaderTermSeconds, secondsUntilRotation,
                electorateSize, totalValidators,
                allValidators, electorate, nonVotingFollowers,
                nextLeader, selfUrl
            );
        }
    }
}

