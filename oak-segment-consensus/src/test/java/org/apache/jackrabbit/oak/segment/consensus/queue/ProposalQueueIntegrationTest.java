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
package org.apache.jackrabbit.oak.segment.consensus.queue;

import org.apache.jackrabbit.oak.segment.consensus.evm.PaymentProof;
import org.apache.jackrabbit.oak.segment.consensus.evm.impl.EventDrivenEvmBridge;
import org.apache.jackrabbit.oak.segment.consensus.evm.impl.SimplePaymentProof;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.math.BigInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * Integration test for proposal queue with mocked Ethereum events.
 */
public class ProposalQueueIntegrationTest {
    
    private EventDrivenEvmBridge bridge;
    private ProposalQueueManager queueManager;
    private CountDownLatch raftAppendLatch;
    private String appendedProposalId;
    
    @Before
    public void setUp() {
        // Create bridge in mock mode
        bridge = new EventDrivenEvmBridge(
            "sepolia",
            "0x742d35Cc6634C0532925a3b844Bc9e7595f0bEb0",
            true  // mock mode
        );
        bridge.start();
        
        // Create queue manager with callback
        raftAppendLatch = new CountDownLatch(1);
        RaftAppendCallback callback = (walletAddress, path, contentType, message, signature) -> {
            appendedProposalId = "captured"; // Mark that callback was called
            raftAppendLatch.countDown();
        };
        
        queueManager = new ProposalQueueManager(bridge, callback);
        queueManager.start();
    }
    
    @After
    public void tearDown() {
        if (queueManager != null) {
            queueManager.shutdown();
        }
        if (bridge != null) {
            bridge.stop();
        }
    }
    
    @Test
    public void testFullFlowWithMockEvent() throws InterruptedException {
        String proposalId = "test-proposal-123";
        String ethereumTxHash = "0xtx123456789";
        String walletAddress = "0x742d35cc6634c0532925a3b844bc9e7595f0beb0";
        String path = "/oak-chain/content/74/2d/35/0x742d35cc.../page-123";
        
        // Step 1: Queue proposal (simulating POST /v1/propose-write)
        queueManager.queueProposal(
            proposalId,
            ethereumTxHash,
            walletAddress,
            path,
            "page",
            "Hello Oak Chain!",
            "0xsig..."
        );
        
        // Verify queued
        ProposalStatus status1 = queueManager.getProposalStatus(proposalId);
        assertNotNull("Proposal should be queued", status1);
        assertEquals("Should be PENDING", ProposalState.PENDING, status1.getState());
        
        // Step 2: Simulate WriteAuthorized event from contract
        bridge.simulateWriteAuthorizedEvent(
            proposalId,
            walletAddress,
            "0xdef456...",  // shardHash
            BigInteger.valueOf(1_000_000),  // 1 USDC (6 decimals)
            12345L,  // blockNumber
            ethereumTxHash
        );
        
        // Step 3: Wait for event processing and Raft append
        assertTrue("Raft append should be called", raftAppendLatch.await(3, TimeUnit.SECONDS));
        
        // Step 4: Verify proposal processed
        ProposalStatus status2 = queueManager.getProposalStatus(proposalId);
        assertNotNull("Proposal should still exist", status2);
        assertEquals("Should be PROCESSED", ProposalState.PROCESSED, status2.getState());
        assertNotNull("Should have confirmed block", status2.getConfirmedBlock());
    }
    
    @Test
    public void testTimeoutRejection() throws InterruptedException {
        String proposalId = "test-timeout-456";
        
        // Queue proposal
        queueManager.queueProposal(
            proposalId,
            "0xtx789",
            "0x742d35cc...",
            "/oak-chain/content/74/2d/35/0x742d35cc.../page-456",
            "page",
            "Test",
            "0xsig..."
        );
        
        // Don't simulate event - wait for timeout
        // Note: Timeout is 5 minutes, so we'll need to mock time or reduce timeout for test
        // For now, verify it's pending
        ProposalStatus status = queueManager.getProposalStatus(proposalId);
        assertEquals("Should be PENDING", ProposalState.PENDING, status.getState());
        
        // TODO: Add mechanism to advance time or reduce timeout for testing
    }
    
    @Test
    public void testMultipleProposals() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(3);
        RaftAppendCallback callback = (wallet, path, contentType, message, signature) -> {
            latch.countDown();
        };
        
        ProposalQueueManager multiQueue = new ProposalQueueManager(bridge, callback);
        multiQueue.start();
        
        try {
            // Queue 3 proposals
            for (int i = 0; i < 3; i++) {
                String proposalId = "test-multi-" + i;
                multiQueue.queueProposal(
                    proposalId,
                    "0xtx" + i,
                    "0x742d35cc...",
                    "/oak-chain/content/74/2d/35/0x742d35cc.../page-" + i,
                    "page",
                    "Message " + i,
                    "0xsig..."
                );
                
                // Simulate event
                bridge.simulateWriteAuthorizedEvent(
                    proposalId,
                    "0x742d35cc...",
                    "0xdef456...",
                    BigInteger.valueOf(1_000_000),
                    12345L + i,
                    "0xtx" + i
                );
            }
            
            // Wait for all to process
            assertTrue("All proposals should be processed", latch.await(5, TimeUnit.SECONDS));
            
            // Verify all processed
            for (int i = 0; i < 3; i++) {
                ProposalStatus status = multiQueue.getProposalStatus("test-multi-" + i);
                assertEquals("Should be PROCESSED", ProposalState.PROCESSED, status.getState());
            }
        } finally {
            multiQueue.shutdown();
        }
    }
}

