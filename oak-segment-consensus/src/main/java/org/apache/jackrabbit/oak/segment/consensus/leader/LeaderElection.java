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
     * This validator's URL.
     */
    private final String selfUrl;
    
    public LeaderElection(String selfUrl, List<String> peerUrls, int leaderTermSeconds) {
        this.selfUrl = selfUrl;
        this.leaderTermSeconds = leaderTermSeconds;
        
        // Create sorted list of all validators
        this.allValidators = new ArrayList<>();
        this.allValidators.add(selfUrl);
        this.allValidators.addAll(peerUrls);
        Collections.sort(this.allValidators);
        
        log.info("📋 Leader Election initialized");
        log.info("   Validators: {}", allValidators.size());
        log.info("   Leader term: {} seconds", leaderTermSeconds);
        log.info("   Rotation order: {}", allValidators);
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
     * Elect the leader for the current epoch.
     * All validators compute the same result independently.
     * 
     * Algorithm: leader_index = current_epoch % validator_count
     * 
     * @return URL of the elected leader
     */
    public String electLeader() {
        int epoch = getCurrentEpoch();
        int leaderIndex = epoch % allValidators.size();
        return allValidators.get(leaderIndex);
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
}

