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

import org.junit.Test;

import java.util.Set;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link LeadershipClaimTracker}.
 * 
 * <p>Tests the leadership claim state machine:
 * <pre>
 * PENDING → ACCEPTED (quorum reached)
 * PENDING → REJECTED (timeout or insufficient ACKs)
 * PENDING → SUPERSEDED (new epoch started)
 * </pre>
 */
public class LeadershipClaimTrackerTest {
    
    /**
     * Test initial state is PENDING.
     */
    @Test
    public void testInitialStateIsPending() {
        LeadershipClaimTracker tracker = new LeadershipClaimTracker(
            1, "http://validator-0:8090", 3);
        
        assertEquals(LeadershipClaimTracker.ClaimState.PENDING, tracker.getState());
        assertEquals(0, tracker.getAckCount());
        assertFalse(tracker.hasQuorum());
    }
    
    /**
     * Test quorum calculation for 3-node cluster.
     */
    @Test
    public void testQuorumCalculation3Nodes() {
        LeadershipClaimTracker tracker = new LeadershipClaimTracker(
            1, "http://validator-0:8090", 3);
        
        // Quorum for 3 nodes = (3/2) + 1 = 2
        assertEquals(2, tracker.getRequiredQuorum());
    }
    
    /**
     * Test quorum calculation for 5-node cluster.
     */
    @Test
    public void testQuorumCalculation5Nodes() {
        LeadershipClaimTracker tracker = new LeadershipClaimTracker(
            1, "http://validator-0:8090", 5);
        
        // Quorum for 5 nodes = (5/2) + 1 = 3
        assertEquals(3, tracker.getRequiredQuorum());
    }
    
    /**
     * Test quorum calculation for single node.
     */
    @Test
    public void testQuorumCalculationSingleNode() {
        LeadershipClaimTracker tracker = new LeadershipClaimTracker(
            1, "http://validator-0:8090", 1);
        
        // Quorum for 1 node = (1/2) + 1 = 1
        assertEquals(1, tracker.getRequiredQuorum());
    }
    
    /**
     * Test PENDING → ACCEPTED transition on quorum.
     */
    @Test
    public void testPendingToAcceptedOnQuorum() {
        LeadershipClaimTracker tracker = new LeadershipClaimTracker(
            1, "http://validator-0:8090", 3);
        
        // First ACK - not yet quorum
        boolean quorumReached = tracker.addAck("http://validator-1:8092");
        assertFalse(quorumReached);
        assertEquals(LeadershipClaimTracker.ClaimState.PENDING, tracker.getState());
        assertEquals(1, tracker.getAckCount());
        
        // Second ACK - quorum reached (2/3)
        quorumReached = tracker.addAck("http://validator-2:8094");
        assertTrue(quorumReached);
        assertEquals(LeadershipClaimTracker.ClaimState.ACCEPTED, tracker.getState());
        assertEquals(2, tracker.getAckCount());
        assertTrue(tracker.hasQuorum());
    }
    
    /**
     * Test PENDING → REJECTED transition.
     */
    @Test
    public void testPendingToRejected() {
        LeadershipClaimTracker tracker = new LeadershipClaimTracker(
            1, "http://validator-0:8090", 3);
        
        // Add one ACK (not enough for quorum)
        tracker.addAck("http://validator-1:8092");
        assertEquals(LeadershipClaimTracker.ClaimState.PENDING, tracker.getState());
        
        // Reject the claim (timeout)
        tracker.reject();
        
        assertEquals(LeadershipClaimTracker.ClaimState.REJECTED, tracker.getState());
        assertFalse(tracker.hasQuorum());
    }
    
    /**
     * Test PENDING → SUPERSEDED transition.
     */
    @Test
    public void testPendingToSuperseded() {
        LeadershipClaimTracker tracker = new LeadershipClaimTracker(
            1, "http://validator-0:8090", 3);
        
        // Add one ACK
        tracker.addAck("http://validator-1:8092");
        assertEquals(LeadershipClaimTracker.ClaimState.PENDING, tracker.getState());
        
        // Supersede the claim (new epoch started)
        tracker.supersede();
        
        assertEquals(LeadershipClaimTracker.ClaimState.SUPERSEDED, tracker.getState());
        assertFalse(tracker.hasQuorum());
    }
    
    /**
     * Test duplicate ACK is ignored.
     */
    @Test
    public void testDuplicateAckIgnored() {
        LeadershipClaimTracker tracker = new LeadershipClaimTracker(
            1, "http://validator-0:8090", 3);
        
        // First ACK - returns false because quorum not yet reached
        boolean quorumReached = tracker.addAck("http://validator-1:8092");
        assertFalse(quorumReached); // Quorum not reached yet
        assertEquals(1, tracker.getAckCount());
        
        // Duplicate ACK from same validator - should be ignored
        quorumReached = tracker.addAck("http://validator-1:8092");
        assertFalse(quorumReached);
        assertEquals(1, tracker.getAckCount()); // Count unchanged
        
        // State should still be PENDING
        assertEquals(LeadershipClaimTracker.ClaimState.PENDING, tracker.getState());
    }
    
    /**
     * Test ACK after quorum is ignored.
     */
    @Test
    public void testAckAfterQuorumIgnored() {
        LeadershipClaimTracker tracker = new LeadershipClaimTracker(
            1, "http://validator-0:8090", 3);
        
        // Reach quorum
        tracker.addAck("http://validator-1:8092");
        tracker.addAck("http://validator-2:8094");
        assertEquals(LeadershipClaimTracker.ClaimState.ACCEPTED, tracker.getState());
        
        // Additional ACK after quorum
        boolean quorumReached = tracker.addAck("http://validator-3:8096");
        assertFalse(quorumReached); // Already accepted, returns false
        
        // State should still be ACCEPTED
        assertEquals(LeadershipClaimTracker.ClaimState.ACCEPTED, tracker.getState());
    }
    
    /**
     * Test ACK after rejection is ignored.
     */
    @Test
    public void testAckAfterRejectionIgnored() {
        LeadershipClaimTracker tracker = new LeadershipClaimTracker(
            1, "http://validator-0:8090", 3);
        
        // Reject the claim
        tracker.reject();
        assertEquals(LeadershipClaimTracker.ClaimState.REJECTED, tracker.getState());
        
        // ACK after rejection
        boolean quorumReached = tracker.addAck("http://validator-1:8092");
        assertFalse(quorumReached);
        
        // State should still be REJECTED
        assertEquals(LeadershipClaimTracker.ClaimState.REJECTED, tracker.getState());
        assertEquals(0, tracker.getAckCount()); // ACK was not added
    }
    
    /**
     * Test reject after acceptance has no effect.
     */
    @Test
    public void testRejectAfterAcceptanceNoEffect() {
        LeadershipClaimTracker tracker = new LeadershipClaimTracker(
            1, "http://validator-0:8090", 3);
        
        // Reach quorum
        tracker.addAck("http://validator-1:8092");
        tracker.addAck("http://validator-2:8094");
        assertEquals(LeadershipClaimTracker.ClaimState.ACCEPTED, tracker.getState());
        
        // Try to reject
        tracker.reject();
        
        // State should still be ACCEPTED
        assertEquals(LeadershipClaimTracker.ClaimState.ACCEPTED, tracker.getState());
    }
    
    /**
     * Test supersede after acceptance has no effect.
     */
    @Test
    public void testSupersedeAfterAcceptanceNoEffect() {
        LeadershipClaimTracker tracker = new LeadershipClaimTracker(
            1, "http://validator-0:8090", 3);
        
        // Reach quorum
        tracker.addAck("http://validator-1:8092");
        tracker.addAck("http://validator-2:8094");
        assertEquals(LeadershipClaimTracker.ClaimState.ACCEPTED, tracker.getState());
        
        // Try to supersede
        tracker.supersede();
        
        // State should still be ACCEPTED
        assertEquals(LeadershipClaimTracker.ClaimState.ACCEPTED, tracker.getState());
    }
    
    /**
     * Test getAcksReceived returns immutable copy.
     */
    @Test
    public void testGetAcksReceivedReturnsImmutableCopy() {
        LeadershipClaimTracker tracker = new LeadershipClaimTracker(
            1, "http://validator-0:8090", 3);
        
        tracker.addAck("http://validator-1:8092");
        
        Set<String> acks = tracker.getAcksReceived();
        assertEquals(1, acks.size());
        assertTrue(acks.contains("http://validator-1:8092"));
        
        // Try to modify the returned set
        try {
            acks.add("http://validator-2:8094");
            fail("Should not be able to modify returned set");
        } catch (UnsupportedOperationException e) {
            // Expected - Set.copyOf returns immutable set
        }
        
        // Original tracker should be unchanged
        assertEquals(1, tracker.getAckCount());
    }
    
    /**
     * Test quorum time tracking.
     */
    @Test
    public void testQuorumTimeTracking() throws InterruptedException {
        LeadershipClaimTracker tracker = new LeadershipClaimTracker(
            1, "http://validator-0:8090", 3);
        
        // Before quorum, time should be -1
        assertEquals(-1, tracker.getQuorumTime());
        
        // Add ACKs with small delay
        Thread.sleep(10);
        tracker.addAck("http://validator-1:8092");
        Thread.sleep(10);
        tracker.addAck("http://validator-2:8094");
        
        // After quorum, time should be positive
        long quorumTime = tracker.getQuorumTime();
        assertTrue("Quorum time should be positive: " + quorumTime, quorumTime > 0);
        assertTrue("Quorum time should be at least 20ms: " + quorumTime, quorumTime >= 20);
    }
    
    /**
     * Test epoch getter.
     */
    @Test
    public void testEpochGetter() {
        LeadershipClaimTracker tracker = new LeadershipClaimTracker(
            42, "http://validator-0:8090", 3);
        
        assertEquals(42, tracker.getEpoch());
    }
    
    /**
     * Test claimant URL getter.
     */
    @Test
    public void testClaimantUrlGetter() {
        LeadershipClaimTracker tracker = new LeadershipClaimTracker(
            1, "http://validator-0:8090", 3);
        
        assertEquals("http://validator-0:8090", tracker.getClaimantUrl());
    }
    
    /**
     * Test all claim states exist.
     */
    @Test
    public void testAllClaimStatesExist() {
        LeadershipClaimTracker.ClaimState[] states = LeadershipClaimTracker.ClaimState.values();
        assertEquals(4, states.length);
        
        assertNotNull(LeadershipClaimTracker.ClaimState.PENDING);
        assertNotNull(LeadershipClaimTracker.ClaimState.ACCEPTED);
        assertNotNull(LeadershipClaimTracker.ClaimState.REJECTED);
        assertNotNull(LeadershipClaimTracker.ClaimState.SUPERSEDED);
    }
    
    /**
     * Test concurrent ACK handling (thread safety).
     */
    @Test
    public void testConcurrentAckHandling() throws InterruptedException {
        LeadershipClaimTracker tracker = new LeadershipClaimTracker(
            1, "http://validator-0:8090", 5);
        
        // Create threads to add ACKs concurrently
        Thread[] threads = new Thread[4];
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            threads[i] = new Thread(() -> {
                tracker.addAck("http://validator-" + (idx + 1) + ":809" + (idx * 2));
            });
        }
        
        // Start all threads
        for (Thread t : threads) {
            t.start();
        }
        
        // Wait for all threads to complete
        for (Thread t : threads) {
            t.join();
        }
        
        // Should have at least 3 ACKs (quorum for 5 nodes is 3)
        // Due to race conditions, some ACKs might be ignored after quorum is reached
        assertTrue("Should have at least 3 ACKs", tracker.getAckCount() >= 3);
        assertEquals(LeadershipClaimTracker.ClaimState.ACCEPTED, tracker.getState());
        assertTrue(tracker.hasQuorum());
    }
    
    /**
     * Test single node cluster reaches quorum immediately.
     */
    @Test
    public void testSingleNodeQuorum() {
        LeadershipClaimTracker tracker = new LeadershipClaimTracker(
            1, "http://validator-0:8090", 1);
        
        // Single node cluster: quorum = 1
        assertEquals(1, tracker.getRequiredQuorum());
        
        // First ACK reaches quorum
        boolean quorumReached = tracker.addAck("http://validator-0:8090");
        assertTrue(quorumReached);
        assertEquals(LeadershipClaimTracker.ClaimState.ACCEPTED, tracker.getState());
    }
}
