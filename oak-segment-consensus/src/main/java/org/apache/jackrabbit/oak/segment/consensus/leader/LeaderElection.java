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
package org.apache.jackrabbit.oak.segment.consensus.leader;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Deterministic leader election using time-based epochs.
 * 
 * All validators independently compute the same leader based on:
 * - Current time
 * - Leader term duration
 * - Sorted list of validator URLs
 * 
 * SECURITY: New validators have a probationary period of one full epoch
 * before they're eligible for leadership. This prevents malicious actors
 * from repeatedly joining/leaving to manipulate leadership.
 * 
 * This ensures all validators agree on who the leader is without
 * needing network coordination or voting.
 */
public class LeaderElection {
    
    private static final Logger log = LoggerFactory.getLogger(LeaderElection.class);
    
    /**
     * Leader term duration in seconds (default: 5 minutes).
     * This is how long each validator holds the leader role before rotation.
     */
    private final int leaderTermSeconds;
    
    /**
     * All validator URLs in the network (including self).
     * Sorted alphabetically for deterministic election.
     */
    private final List<String> allValidators;
    
    /**
     * Timestamps when each validator joined the network (URL -> joinedAt millis).
     * Used to enforce probationary period for new validators.
     */
    private final Map<String, Long> validatorJoinTimes;
    
    /**
     * This validator's URL.
     */
    private final String selfUrl;
    
    public LeaderElection(String selfUrl, List<String> peerUrls, int leaderTermSeconds) {
        this(selfUrl, peerUrls, leaderTermSeconds, new HashMap<>());
    }
    
    public LeaderElection(String selfUrl, List<String> peerUrls, int leaderTermSeconds, Map<String, Long> validatorJoinTimes) {
        this.selfUrl = selfUrl;
        this.leaderTermSeconds = leaderTermSeconds;
        this.validatorJoinTimes = new HashMap<>(validatorJoinTimes);
        
        // Create sorted list of all validators
        this.allValidators = new ArrayList<>();
        this.allValidators.add(selfUrl);
        this.allValidators.addAll(peerUrls);
        Collections.sort(this.allValidators);
        
        // Set join time for self if not already set (genesis case)
        if (!this.validatorJoinTimes.containsKey(selfUrl)) {
            this.validatorJoinTimes.put(selfUrl, System.currentTimeMillis());
        }
        
        log.info("📋 Leader Election initialized");
        log.info("   Validators: {}", allValidators.size());
        log.info("   Leader term: {} seconds", leaderTermSeconds);
        log.info("   Rotation order: {}", allValidators);
        
        // Log probationary status
        List<String> onProbation = getValidatorsOnProbation();
        if (!onProbation.isEmpty()) {
            log.warn("⚠️  Validators on probation (not eligible for leadership yet): {}", onProbation);
        }
    }
    
    /**
     * Calculate current epoch based on system time.
     * Epoch changes every LEADER_TERM_SECONDS.
     * 
     * @return Current epoch number (0, 1, 2, ...)
     */
    public int getCurrentEpoch() {
        long nowSeconds = System.currentTimeMillis() / 1000;
        return (int) (nowSeconds / leaderTermSeconds);
    }
    
    /**
     * Get validators that are eligible for leadership (past probationary period).
     * 
     * A validator is eligible if:
     * - It joined more than one full epoch ago (leaderTermSeconds)
     * 
     * This prevents new validators from immediately becoming leaders and
     * protects against join/leave manipulation attacks.
     * 
     * @return List of eligible validator URLs
     */
    private List<String> getEligibleValidators() {
        long now = System.currentTimeMillis();
        long probationPeriodMs = leaderTermSeconds * 1000L;
        
        List<String> eligible = new ArrayList<>();
        for (String validatorUrl : allValidators) {
            Long joinedAt = validatorJoinTimes.get(validatorUrl);
            if (joinedAt == null) {
                // Unknown join time - assume eligible (backward compatibility)
                eligible.add(validatorUrl);
            } else {
                long timeSinceJoin = now - joinedAt;
                if (timeSinceJoin >= probationPeriodMs) {
                    eligible.add(validatorUrl);
                }
            }
        }
        
        // If no validators are eligible (all too new), use all validators
        // to ensure there's always a leader
        if (eligible.isEmpty()) {
            log.warn("⚠️  No eligible validators (all on probation), using all validators");
            return new ArrayList<>(allValidators);
        }
        
        return eligible;
    }
    
    /**
     * Get validators currently on probation (not yet eligible for leadership).
     * 
     * @return List of validators on probation
     */
    public List<String> getValidatorsOnProbation() {
        long now = System.currentTimeMillis();
        long probationPeriodMs = leaderTermSeconds * 1000L;
        
        List<String> onProbation = new ArrayList<>();
        for (String validatorUrl : allValidators) {
            Long joinedAt = validatorJoinTimes.get(validatorUrl);
            if (joinedAt != null) {
                long timeSinceJoin = now - joinedAt;
                if (timeSinceJoin < probationPeriodMs) {
                    onProbation.add(validatorUrl);
                }
            }
        }
        return onProbation;
    }
    
    /**
     * Elect the leader for the current epoch.
     * All validators compute the same result independently.
     * 
     * SECURITY: Only validators past their probationary period are eligible.
     * This prevents join/leave manipulation attacks.
     * 
     * Algorithm: leader_index = current_epoch % eligible_validator_count
     * 
     * @return URL of the elected leader
     */
    public String electLeader() {
        List<String> eligible = getEligibleValidators();
        int epoch = getCurrentEpoch();
        int leaderIndex = epoch % eligible.size();
        String electedLeader = eligible.get(leaderIndex);
        
        // Log if a probationary validator would have been elected
        List<String> onProbation = getValidatorsOnProbation();
        if (!onProbation.isEmpty()) {
            int wouldBeIndex = epoch % allValidators.size();
            String wouldBeLeader = allValidators.get(wouldBeIndex);
            if (!wouldBeLeader.equals(electedLeader)) {
                log.info("🛡️  Probationary protection: {} would be leader but is on probation, {} elected instead", 
                    wouldBeLeader, electedLeader);
            }
        }
        
        return electedLeader;
    }
    
    /**
     * Check if this validator is currently the leader.
     * 
     * @return true if this validator is the elected leader
     */
    public boolean isLeader() {
        return selfUrl.equals(electLeader());
    }
    
    /**
     * Get the current validator role.
     * 
     * @return LEADER if this validator is leader, FOLLOWER otherwise
     */
    public ValidatorRole getRole() {
        return isLeader() ? ValidatorRole.LEADER : ValidatorRole.FOLLOWER;
    }
    
    /**
     * Get time remaining in current epoch (seconds).
     * 
     * @return Seconds until next epoch (leader rotation)
     */
    public int getSecondsUntilRotation() {
        long nowSeconds = System.currentTimeMillis() / 1000;
        long epochStartSeconds = (nowSeconds / leaderTermSeconds) * leaderTermSeconds;
        long epochEndSeconds = epochStartSeconds + leaderTermSeconds;
        return (int) (epochEndSeconds - nowSeconds);
    }
    
    /**
     * Get timestamp when current epoch ends (leader rotates).
     * 
     * @return Epoch end time in milliseconds
     */
    public long getEpochEndTime() {
        long nowSeconds = System.currentTimeMillis() / 1000;
        long epochStartSeconds = (nowSeconds / leaderTermSeconds) * leaderTermSeconds;
        long epochEndSeconds = epochStartSeconds + leaderTermSeconds;
        return epochEndSeconds * 1000;
    }
    
    /**
     * Get all validators in the network.
     * 
     * @return Sorted list of all validator URLs
     */
    public List<String> getAllValidators() {
        return new ArrayList<>(allValidators);
    }
    
    /**
     * Get peer validators (all validators except self).
     * 
     * @return List of peer validator URLs
     */
    public List<String> getPeerValidators() {
        List<String> peers = new ArrayList<>(allValidators);
        peers.remove(selfUrl);
        return peers;
    }
    
    /**
     * Get the leader term duration.
     * 
     * @return Leader term in seconds
     */
    public int getLeaderTermSeconds() {
        return leaderTermSeconds;
    }
    
    /**
     * Get validator join times for rebuilding election with updated info.
     * 
     * @return Map of validator URL to join timestamp
     */
    public Map<String, Long> getValidatorJoinTimes() {
        return new HashMap<>(validatorJoinTimes);
    }
}

