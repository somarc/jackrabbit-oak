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

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tracks leadership claims and acknowledgments for quorum-based consensus.
 * 
 * PHASE 2: Quorum-Based Acceptance
 * - Prevents split-brain by requiring majority acknowledgments
 * - Leader cannot finalize without quorum
 * - Inspired by Paxos two-phase protocol
 */
public class LeadershipClaimTracker {
    
    private static final Logger log = LoggerFactory.getLogger(LeadershipClaimTracker.class);
    
    private final int epoch;
    private final String claimantUrl;
    private final int electorateSize;
    private final int requiredQuorum;
    private final Set<String> acksReceived = ConcurrentHashMap.newKeySet();
    private volatile ClaimState state = ClaimState.PENDING;
    private final long claimTimestamp;
    private volatile long quorumReachedTimestamp = 0;
    
    public enum ClaimState {
        PENDING,      // Claim sent, waiting for ACKs
        ACCEPTED,     // Quorum reached, leader finalized
        REJECTED,     // Timeout or insufficient ACKs
        SUPERSEDED    // New epoch started before quorum
    }
    
    /**
     * Create a new claim tracker.
     * 
     * @param epoch          The epoch being claimed
     * @param claimantUrl    The validator claiming leadership
     * @param electorateSize Total number of voting members
     */
    public LeadershipClaimTracker(int epoch, String claimantUrl, int electorateSize) {
        this.epoch = epoch;
        this.claimantUrl = claimantUrl;
        this.electorateSize = electorateSize;
        this.requiredQuorum = (electorateSize / 2) + 1;  // Majority
        this.claimTimestamp = System.currentTimeMillis();
        
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        log.info("📊 CLAIM TRACKER CREATED");
        log.info("   Epoch: {}", epoch);
        log.info("   Claimant: {}", claimantUrl);
        log.info("   Electorate size: {}", electorateSize);
        log.info("   Required quorum: {}/{}", requiredQuorum, electorateSize);
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    }
    
    /**
     * Add an acknowledgment from a follower.
     * 
     * @param validatorUrl The validator sending the ACK
     * @return true if quorum reached, false otherwise
     */
    public synchronized boolean addAck(String validatorUrl) {
        if (state != ClaimState.PENDING) {
            log.warn("⚠️  ACK from {} ignored - claim already {}", validatorUrl, state);
            return false;
        }
        
        // Add ACK (Set prevents duplicates)
        boolean added = acksReceived.add(validatorUrl);
        if (!added) {
            log.debug("Duplicate ACK from {} ignored", validatorUrl);
            return false;
        }
        
        int currentAcks = acksReceived.size();
        log.info("✅ ACK received from {} ({}/{})", validatorUrl, currentAcks, requiredQuorum);
        
        // Check quorum
        if (currentAcks >= requiredQuorum) {
            state = ClaimState.ACCEPTED;
            quorumReachedTimestamp = System.currentTimeMillis();
            long quorumTime = quorumReachedTimestamp - claimTimestamp;
            
            log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            log.info("🎊 QUORUM REACHED!");
            log.info("   Epoch: {}", epoch);
            log.info("   Leader: {}", claimantUrl);
            log.info("   ACKs: {}/{}", currentAcks, electorateSize);
            log.info("   Time to quorum: {}ms", quorumTime);
            log.info("   ACKs from: {}", acksReceived);
            log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            
            return true;  // Quorum reached!
        }
        
        return false;  // Still waiting for more ACKs
    }
    
    /**
     * Mark claim as rejected (timeout or insufficient ACKs).
     */
    public synchronized void reject() {
        if (state == ClaimState.PENDING) {
            state = ClaimState.REJECTED;
            int received = acksReceived.size();
            
            log.error("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            log.error("❌ CLAIM REJECTED - QUORUM NOT REACHED");
            log.error("   Epoch: {}", epoch);
            log.error("   Claimant: {}", claimantUrl);
            log.error("   Required: {}/{}", requiredQuorum, electorateSize);
            log.error("   Received: {}/{}", received, electorateSize);
            log.error("   Missing: {}", requiredQuorum - received);
            log.error("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        }
    }
    
    /**
     * Mark claim as superseded (new epoch started).
     */
    public synchronized void supersede() {
        if (state == ClaimState.PENDING) {
            state = ClaimState.SUPERSEDED;
            log.warn("⚠️  Claim for epoch {} superseded by new epoch", epoch);
        }
    }
    
    // Getters
    
    public int getEpoch() {
        return epoch;
    }
    
    public String getClaimantUrl() {
        return claimantUrl;
    }
    
    public ClaimState getState() {
        return state;
    }
    
    public int getAckCount() {
        return acksReceived.size();
    }
    
    public int getRequiredQuorum() {
        return requiredQuorum;
    }
    
    public boolean hasQuorum() {
        return state == ClaimState.ACCEPTED;
    }
    
    public long getQuorumTime() {
        if (quorumReachedTimestamp == 0) {
            return -1;
        }
        return quorumReachedTimestamp - claimTimestamp;
    }
    
    public Set<String> getAcksReceived() {
        return Set.copyOf(acksReceived);
    }
}

