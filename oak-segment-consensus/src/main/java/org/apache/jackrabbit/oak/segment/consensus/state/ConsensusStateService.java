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

import org.apache.jackrabbit.oak.segment.consensus.leader.EpochLeaderEngine;
import org.apache.jackrabbit.oak.segment.consensus.leader.ValidatorRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Service providing normalized consensus state - single source of truth.
 * 
 * <p>This service ensures all components (APIs, dashboard, heartbeats, leader election)
 * use the same consensus state data, eliminating inconsistencies from multiple
 * sources of truth.</p>
 * 
 * <p>All validator lists are normalized (sorted alphabetically) for deterministic
 * ordering across all validators.</p>
 */
public class ConsensusStateService {
    
    private static final Logger log = LoggerFactory.getLogger(ConsensusStateService.class);
    
    private final EpochLeaderEngine epochLeaderEngine;
    private final String selfUrl;
    
    public ConsensusStateService(EpochLeaderEngine epochLeaderEngine, String selfUrl) {
        this.epochLeaderEngine = epochLeaderEngine;
        this.selfUrl = selfUrl;
    }
    
    /**
     * Get normalized consensus state - single source of truth.
     * 
     * <p>This method aggregates data from EpochLeaderEngine and normalizes it
     * into a consistent ConsensusState object that all components can use.</p>
     * 
     * @return Normalized consensus state
     */
    public ConsensusState getConsensusState() {
        if (epochLeaderEngine == null) {
            // No consensus engine - standalone mode
            return new ConsensusState.Builder()
                .consensusType("none")
                .currentRole("STANDALONE")
                .selfUrl(selfUrl)
                .allValidators(Collections.singletonList(selfUrl))
                .electorate(Collections.singletonList(selfUrl))
                .totalValidators(1)
                .electorateSize(1)
                .build();
        }
        
        // Leader-based consensus mode
        ValidatorRole role = epochLeaderEngine.getCurrentRole();
        String currentRole = role.toString();
        String currentLeader = epochLeaderEngine.getCurrentLeader();
        int currentEpoch = epochLeaderEngine.getCurrentEpoch();
        int leaderTermSeconds = epochLeaderEngine.getElection().getLeaderTermSeconds();
        int secondsUntilRotation = epochLeaderEngine.getElection().getSecondsUntilRotation();
        
        // Get all followers (voting + non-voting)
        List<String> allFollowers = epochLeaderEngine.getAllFollowers();
        
        // Build normalized allValidators list (includes self)
        List<String> allValidators = new ArrayList<>();
        allValidators.add(selfUrl);
        allValidators.addAll(allFollowers);
        Collections.sort(allValidators);
        
        // Get electorate (voting members only)
        List<String> electorate = epochLeaderEngine.getElection().getAllValidators();
        Collections.sort(electorate);
        
        // Get non-voting followers (on probation)
        List<String> nonVotingFollowers = epochLeaderEngine.getNonVotingFollowers();
        Collections.sort(nonVotingFollowers);
        
        // Calculate next leader for next epoch
        String nextLeader = calculateNextLeader(currentEpoch, electorate);
        
        // Build consensus state
        ConsensusState state = new ConsensusState.Builder()
            .consensusType("leader-based")
            .currentRole(currentRole)
            .currentLeader(currentLeader)
            .currentEpoch(currentEpoch)
            .leaderTermSeconds(leaderTermSeconds)
            .secondsUntilRotation(secondsUntilRotation)
            .electorateSize(electorate.size())
            .totalValidators(allValidators.size())
            .allValidators(allValidators)
            .electorate(electorate)
            .nonVotingFollowers(nonVotingFollowers)
            .nextLeader(nextLeader)
            .selfUrl(selfUrl)
            .build();
        
        log.debug("ConsensusState: role={}, leader={}, epoch={}, electorate={}, total={}, nextLeader={}",
            currentRole, currentLeader, currentEpoch, electorate.size(), allValidators.size(), nextLeader);
        
        return state;
    }
    
    /**
     * Calculate the next leader for the next epoch.
     * 
     * <p>Uses deterministic algorithm: nextLeaderIndex = (nextEpoch % electorateSize)
     * Only voting members (electorate) can be leaders.</p>
     * 
     * @param currentEpoch Current epoch number
     * @param electorate Voting validators (sorted alphabetically)
     * @return URL of next leader, or null if no electorate
     */
    private String calculateNextLeader(int currentEpoch, List<String> electorate) {
        if (electorate.isEmpty()) {
            return null;
        }
        
        int nextEpoch = currentEpoch + 1;
        int nextLeaderIndex = nextEpoch % electorate.size();
        return electorate.get(nextLeaderIndex);
    }
    
    /**
     * Get all validators for heartbeat broadcasting.
     * 
     * <p>Returns all followers (voting + non-voting) that should receive heartbeats.</p>
     * 
     * @return List of validator URLs to send heartbeats to
     */
    public List<String> getHeartbeatRecipients() {
        if (epochLeaderEngine == null) {
            return Collections.emptyList();
        }
        
        List<String> recipients = new ArrayList<>(epochLeaderEngine.getAllFollowers());
        Collections.sort(recipients);
        return recipients;
    }
    
    /**
     * Get electorate size for quorum calculations.
     * 
     * @return Number of voting validators
     */
    public int getElectorateSize() {
        ConsensusState state = getConsensusState();
        return state.electorateSize;
    }
    
    /**
     * Check if a validator URL is in the electorate (can vote and become leader).
     * 
     * @param validatorUrl Validator URL to check
     * @return true if validator is in electorate
     */
    public boolean isInElectorate(String validatorUrl) {
        ConsensusState state = getConsensusState();
        return state.electorate.contains(validatorUrl);
    }
    
    /**
     * Check if a validator URL is on probation (non-voting).
     * 
     * @param validatorUrl Validator URL to check
     * @return true if validator is on probation
     */
    public boolean isOnProbation(String validatorUrl) {
        ConsensusState state = getConsensusState();
        return state.nonVotingFollowers.contains(validatorUrl);
    }
}

