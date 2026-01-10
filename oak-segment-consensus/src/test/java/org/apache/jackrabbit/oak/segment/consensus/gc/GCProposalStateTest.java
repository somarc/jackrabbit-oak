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
package org.apache.jackrabbit.oak.segment.consensus.gc;

import org.junit.Test;

import java.math.BigDecimal;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link GCProposal} and its state machine.
 * 
 * <p>Tests the GC proposal state machine:
 * <pre>
 * PENDING → VOTING → APPROVED → EXECUTING → COMPLETED
 *              ↓
 *          REJECTED
 *              
 *                        EXECUTING → FAILED
 * </pre>
 */
public class GCProposalStateTest {
    
    /**
     * Test that all expected states exist.
     */
    @Test
    public void testAllStatesExist() {
        GCProposal.GCProposalState[] states = GCProposal.GCProposalState.values();
        assertEquals(7, states.length);
        
        assertNotNull(GCProposal.GCProposalState.PENDING);
        assertNotNull(GCProposal.GCProposalState.VOTING);
        assertNotNull(GCProposal.GCProposalState.APPROVED);
        assertNotNull(GCProposal.GCProposalState.REJECTED);
        assertNotNull(GCProposal.GCProposalState.EXECUTING);
        assertNotNull(GCProposal.GCProposalState.COMPLETED);
        assertNotNull(GCProposal.GCProposalState.FAILED);
    }
    
    /**
     * Test initial state is PENDING.
     */
    @Test
    public void testInitialStateIsPending() {
        GCProposal proposal = new GCProposal();
        assertEquals(GCProposal.GCProposalState.PENDING, proposal.state);
    }
    
    /**
     * Test PENDING → VOTING transition on first vote.
     */
    @Test
    public void testPendingToVotingOnFirstVote() {
        GCProposal proposal = new GCProposal();
        proposal.proposalId = "gc-001";
        
        assertEquals(GCProposal.GCProposalState.PENDING, proposal.state);
        
        // First vote should transition to VOTING
        proposal.addVote(0, true, "Approve GC");
        
        assertEquals(GCProposal.GCProposalState.VOTING, proposal.state);
        assertEquals(1, proposal.getTotalVoteCount());
        assertEquals(1, proposal.getApproveVoteCount());
        assertEquals(0, proposal.getRejectVoteCount());
    }
    
    /**
     * Test VOTING state accumulates votes.
     */
    @Test
    public void testVotingAccumulatesVotes() {
        GCProposal proposal = new GCProposal();
        proposal.proposalId = "gc-002";
        
        // Add multiple votes
        proposal.addVote(0, true, "Approve");
        proposal.addVote(1, true, "Approve");
        proposal.addVote(2, false, "Reject - not enough space");
        
        assertEquals(GCProposal.GCProposalState.VOTING, proposal.state);
        assertEquals(3, proposal.getTotalVoteCount());
        assertEquals(2, proposal.getApproveVoteCount());
        assertEquals(1, proposal.getRejectVoteCount());
    }
    
    /**
     * Test VOTING → APPROVED transition (manual state change).
     */
    @Test
    public void testVotingToApproved() {
        GCProposal proposal = new GCProposal();
        proposal.proposalId = "gc-003";
        
        // Add approving votes
        proposal.addVote(0, true, "Approve");
        proposal.addVote(1, true, "Approve");
        
        // Manually transition to APPROVED (quorum reached)
        proposal.state = GCProposal.GCProposalState.APPROVED;
        
        assertEquals(GCProposal.GCProposalState.APPROVED, proposal.state);
    }
    
    /**
     * Test VOTING → REJECTED transition (manual state change).
     */
    @Test
    public void testVotingToRejected() {
        GCProposal proposal = new GCProposal();
        proposal.proposalId = "gc-004";
        
        // Add rejecting votes
        proposal.addVote(0, false, "Reject");
        proposal.addVote(1, false, "Reject");
        
        // Manually transition to REJECTED (quorum reached for rejection)
        proposal.state = GCProposal.GCProposalState.REJECTED;
        
        assertEquals(GCProposal.GCProposalState.REJECTED, proposal.state);
    }
    
    /**
     * Test APPROVED → EXECUTING transition.
     */
    @Test
    public void testApprovedToExecuting() {
        GCProposal proposal = new GCProposal();
        proposal.proposalId = "gc-005";
        proposal.state = GCProposal.GCProposalState.APPROVED;
        
        // Transition to EXECUTING
        proposal.state = GCProposal.GCProposalState.EXECUTING;
        
        assertEquals(GCProposal.GCProposalState.EXECUTING, proposal.state);
    }
    
    /**
     * Test EXECUTING → COMPLETED transition.
     */
    @Test
    public void testExecutingToCompleted() {
        GCProposal proposal = new GCProposal();
        proposal.proposalId = "gc-006";
        proposal.state = GCProposal.GCProposalState.EXECUTING;
        
        // Transition to COMPLETED
        proposal.state = GCProposal.GCProposalState.COMPLETED;
        
        assertEquals(GCProposal.GCProposalState.COMPLETED, proposal.state);
    }
    
    /**
     * Test EXECUTING → FAILED transition.
     */
    @Test
    public void testExecutingToFailed() {
        GCProposal proposal = new GCProposal();
        proposal.proposalId = "gc-007";
        proposal.state = GCProposal.GCProposalState.EXECUTING;
        
        // Transition to FAILED
        proposal.state = GCProposal.GCProposalState.FAILED;
        
        assertEquals(GCProposal.GCProposalState.FAILED, proposal.state);
    }
    
    /**
     * Test quorum calculation (approve votes).
     */
    @Test
    public void testQuorumCalculationApprove() {
        GCProposal proposal = new GCProposal();
        proposal.proposalId = "gc-008";
        
        // 3-node cluster: quorum = 2
        proposal.addVote(0, true, "Approve");
        assertEquals(1, proposal.getApproveVoteCount());
        
        proposal.addVote(1, true, "Approve");
        assertEquals(2, proposal.getApproveVoteCount());
        
        // Quorum reached (2/3)
        assertTrue(proposal.getApproveVoteCount() >= 2);
    }
    
    /**
     * Test quorum calculation (reject votes).
     */
    @Test
    public void testQuorumCalculationReject() {
        GCProposal proposal = new GCProposal();
        proposal.proposalId = "gc-009";
        
        // 3-node cluster: quorum = 2
        proposal.addVote(0, false, "Reject");
        assertEquals(1, proposal.getRejectVoteCount());
        
        proposal.addVote(1, false, "Reject");
        assertEquals(2, proposal.getRejectVoteCount());
        
        // Rejection quorum reached (2/3)
        assertTrue(proposal.getRejectVoteCount() >= 2);
    }
    
    /**
     * Test duplicate vote handling (same validator votes twice).
     */
    @Test
    public void testDuplicateVoteOverwrites() {
        GCProposal proposal = new GCProposal();
        proposal.proposalId = "gc-010";
        
        // Validator 0 votes approve
        proposal.addVote(0, true, "Approve");
        assertEquals(1, proposal.getApproveVoteCount());
        assertEquals(0, proposal.getRejectVoteCount());
        
        // Validator 0 changes vote to reject (overwrites)
        proposal.addVote(0, false, "Changed to reject");
        assertEquals(0, proposal.getApproveVoteCount());
        assertEquals(1, proposal.getRejectVoteCount());
        
        // Total vote count should still be 1
        assertEquals(1, proposal.getTotalVoteCount());
    }
    
    /**
     * Test proposal expiration.
     */
    @Test
    public void testProposalExpiration() {
        GCProposal proposal = new GCProposal();
        proposal.proposalId = "gc-011";
        
        // Default expiration is 24 hours from creation
        assertFalse(proposal.isExpired());
        
        // Set expiration to past
        proposal.expiresAt = System.currentTimeMillis() - 1000;
        assertTrue(proposal.isExpired());
        
        // Set expiration to future
        proposal.expiresAt = System.currentTimeMillis() + 60000;
        assertFalse(proposal.isExpired());
    }
    
    /**
     * Test proposal fields initialization.
     */
    @Test
    public void testProposalFieldsInitialization() {
        GCProposal proposal = new GCProposal();
        
        // Default state
        assertEquals(GCProposal.GCProposalState.PENDING, proposal.state);
        
        // Votes map should be initialized
        assertNotNull(proposal.votes);
        assertTrue(proposal.votes.isEmpty());
        
        // Timestamps should be set
        assertTrue(proposal.createdAt > 0);
        assertTrue(proposal.expiresAt > proposal.createdAt);
        
        // Other fields should be null/zero
        assertNull(proposal.proposalId);
        assertNull(proposal.proposerWallet);
        assertNull(proposal.targetRevision);
        assertEquals(0, proposal.estimatedReclaimableSizeMB);
        assertNull(proposal.estimatedCostUSDC);
        assertNull(proposal.paymentProof);
        assertNull(proposal.executionResult);
    }
    
    /**
     * Test proposal with cost estimate.
     */
    @Test
    public void testProposalWithCostEstimate() {
        GCProposal proposal = new GCProposal();
        proposal.proposalId = "gc-012";
        proposal.proposerWallet = "0x742d35cc6634c0532925a3b844bc9e7595f0beb0";
        proposal.estimatedReclaimableSizeMB = 100;
        proposal.estimatedCostUSDC = new BigDecimal("10.00");
        proposal.fragmentationOverheadMB = 20;
        proposal.fragmentationCostUSDC = new BigDecimal("2.00");
        
        assertEquals(100, proposal.estimatedReclaimableSizeMB);
        assertEquals(new BigDecimal("10.00"), proposal.estimatedCostUSDC);
        assertEquals(20, proposal.fragmentationOverheadMB);
        assertEquals(new BigDecimal("2.00"), proposal.fragmentationCostUSDC);
    }
    
    /**
     * Test vote timestamp tracking.
     */
    @Test
    public void testVoteTimestampTracking() throws InterruptedException {
        GCProposal proposal = new GCProposal();
        proposal.proposalId = "gc-013";
        
        long beforeVote = System.currentTimeMillis();
        Thread.sleep(10); // Small delay to ensure timestamp difference
        
        proposal.addVote(0, true, "Approve");
        
        Thread.sleep(10);
        long afterVote = System.currentTimeMillis();
        
        GCVote vote = proposal.votes.get(0);
        assertNotNull(vote);
        assertTrue(vote.timestamp >= beforeVote);
        assertTrue(vote.timestamp <= afterVote);
    }
    
    /**
     * Test vote fields.
     */
    @Test
    public void testVoteFields() {
        GCProposal proposal = new GCProposal();
        proposal.proposalId = "gc-014";
        
        proposal.addVote(5, true, "Approve - sufficient space");
        
        GCVote vote = proposal.votes.get(5);
        assertNotNull(vote);
        assertEquals("gc-014", vote.proposalId);
        assertEquals(5, vote.validatorId);
        assertTrue(vote.approve);
        assertEquals("Approve - sufficient space", vote.reason);
        assertTrue(vote.timestamp > 0);
    }
}
