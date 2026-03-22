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

import org.apache.jackrabbit.oak.segment.consensus.eth.BeaconChainClient;
import org.apache.jackrabbit.oak.segment.consensus.evm.PaymentProof;
import org.apache.jackrabbit.oak.segment.consensus.evm.impl.EventDrivenEvmBridge;
import org.apache.jackrabbit.oak.segment.consensus.evm.impl.SimplePaymentProof;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.*;

/**
 * Integration test for proposal queue with mocked Ethereum events.
 * 
 * <p>Uses {@link ProposalQueueManagerOptimized} (production implementation) with:
 * <ul>
 *   <li>Mock EVM bridge for instant payment verification</li>
 *   <li>Mock Beacon Chain client for epoch compatibility overlays and mock controls</li>
 *   <li>Tri-agent architecture (EVM verifier, Aeron sender, adaptive release finalizer)</li>
 * </ul>
 * 
 * <p>This tests the same code path used in production, ensuring test coverage
 * of adaptive release, payment tiers, and retry logic.
 */
public class ProposalQueueIntegrationTest {
    
    private EventDrivenEvmBridge bridge;
    private ProposalQueueManagerOptimized queueManager;
    private BeaconChainClient beaconClient;
    private CountDownLatch raftAppendLatch;
    private String appendedProposalId;
    
    @BeforeClass
    public static void setUpClass() {
        // Ensure mock mode is set for all tests
        // BlockchainConfig reads from system property oak.blockchain.mode
        System.setProperty("oak.blockchain.mode", "mock");
    }
    
    @Before
    public void setUp() {
        // Create bridge in mock mode
        bridge = new EventDrivenEvmBridge(
            "sepolia",
            "0x742d35Cc6634C0532925a3b844Bc9e7595f0bEb0",
            true  // mock mode
        );
        bridge.start();
        
        // Create Beacon Chain client (will use mock mode - 30s epochs)
        beaconClient = new BeaconChainClient("ignored-in-mock-mode");
        beaconClient.startBackgroundPolling();

        createQueueManager();
    }

    private void createQueueManager() {
        raftAppendLatch = new CountDownLatch(1);
        RaftAppendCallback callback = new RaftAppendCallback() {
            @Override
            public void appendProposal(String walletAddress, String path, String contentType, 
                                       String message, String signature) {
                appendedProposalId = "captured";
                raftAppendLatch.countDown();
            }
            
            @Override
            public void appendProposal(String walletAddress, String path, String contentType,
                                       String message, String signature, String blobId, String mimeType) {
                appendProposal(walletAddress, path, contentType, message, signature);
            }
            
            @Override
            public void appendDeleteProposal(String walletAddress, String path, String signature) {
                appendedProposalId = "delete-captured";
                raftAppendLatch.countDown();
            }
            
            @Override
            public int appendProposalBatch(java.util.List<QueuedProposal> batch) {
                for (QueuedProposal p : batch) {
                    appendedProposalId = p.getProposalId();
                }
                for (int i = 0; i < batch.size(); i++) {
                    raftAppendLatch.countDown();
                }
                return batch.size();
            }
        };
        
        // Create backpressure manager for test
        BackpressureManager backpressureManager = new BackpressureManager();

        // Use optimized queue manager (production implementation)
        queueManager = new ProposalQueueManagerOptimized(bridge, callback, backpressureManager, beaconClient);
        queueManager.start();
    }

    private void recreateQueueManager() {
        if (queueManager != null) {
            queueManager.stop();
        }
        if (bridge != null) {
            bridge.stop();
        }
        if (beaconClient != null) {
            beaconClient.stopBackgroundPolling();
        }

        bridge = new EventDrivenEvmBridge(
            "sepolia",
            "0x742d35Cc6634C0532925a3b844Bc9e7595f0bEb0",
            true
        );
        bridge.start();

        beaconClient = new BeaconChainClient("ignored-in-mock-mode");
        beaconClient.startBackgroundPolling();

        createQueueManager();
    }
    
    @After
    public void tearDown() {
        if (queueManager != null) {
            queueManager.stop();
        }
        if (bridge != null) {
            bridge.stop();
        }
        if (beaconClient != null) {
            beaconClient.stopBackgroundPolling();
        }
        System.clearProperty("oak.proposal.release.mode");
        System.clearProperty("oak.proposal.persistence.flush.ms");
        System.clearProperty("oak.proposal.persistence.flush.batch");
        System.clearProperty("oak.consensus.max.pending.messages");
        System.clearProperty("oak.proposal.confirmation.required");
        System.clearProperty("oak.proposal.priority.direct.release.enabled");
        System.clearProperty("oak.proposal.payload.inline.max.bytes");
        System.clearProperty("oak.proposal.payload.spill.soft.pending");
        System.clearProperty("oak.proposal.payload.spill.max.bytes");
        System.clearProperty("oak.proposal.hard.max.pending");
        System.clearProperty("oak.proposal.payload.spill.dir");
    }
    
    @Test
    public void testFullFlowWithMockEvent() throws InterruptedException {
        String proposalId = "test-proposal-123";
        String ethereumTxHash = "0xtx123456789";
        String walletAddress = "0x742d35cc6634c0532925a3b844bc9e7595f0beb0";
        // Path must match wallet's shard root for authorization check
        String path = "/oak-chain/74/2d/35/0x742d35cc6634c0532925a3b844bc9e7595f0beb0/content/page-123";
        
        // Step 1: Queue proposal (simulating POST /v1/propose-write)
        // Using PRIORITY tier for immediate processing (bypasses epoch batching)
        queueManager.queueProposal(
            proposalId,
            ethereumTxHash,
            walletAddress,
            path,
            "page",
            "Hello Oak Chain!",
            "0xsig...",
            org.apache.jackrabbit.oak.segment.consensus.economics.ValidatorEarningsTracker.PaymentTier.PRIORITY,
            null // no intent token
        );
        
        // Verify queued
        QueuedProposal proposal = queueManager.getProposal(proposalId);
        assertNotNull("Proposal should be queued", proposal);
        assertEquals("Should be PENDING initially", ProposalState.PENDING, proposal.getState());
        
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
        // PRIORITY tier bypasses epoch batching, so should be fast
        assertTrue("Raft append should be called within 10s", raftAppendLatch.await(10, TimeUnit.SECONDS));
        
        // Step 4: Verify proposal processed
        // Note: After processing, proposal is removed from allProposals map
        // So we check via the callback capture
        assertEquals("Callback should have captured proposal", "captured", appendedProposalId);
    }

    @Test
    public void testPriorityTierWaitsForConfiguredConfirmations() throws InterruptedException {
        System.setProperty("oak.proposal.confirmation.required", "2");
        recreateQueueManager();
        assertEquals("Queue should load the configured confirmation depth",
            2L, longStat(queueManager.getQueueStats(), "requiredConfirmations"));

        String proposalId = "test-confirmations-001";
        String ethereumTxHash = "0xtxconfirm001";
        String walletAddress = "0x742d35cc6634c0532925a3b844bc9e7595f0beb0";
        String path = "/oak-chain/74/2d/35/0x742d35cc6634c0532925a3b844bc9e7595f0beb0/content/page-confirmations";

        queueManager.queueProposal(
            proposalId,
            ethereumTxHash,
            walletAddress,
            path,
            "page",
            "Needs two confirmations",
            "0xsig...",
            org.apache.jackrabbit.oak.segment.consensus.economics.ValidatorEarningsTracker.PaymentTier.PRIORITY,
            null
        );

        long paymentBlockNumber = bridge.getCurrentBlockNumber();
        bridge.simulateWriteAuthorizedEvent(
            proposalId,
            walletAddress,
            "0xdef456...",
            BigInteger.valueOf(1_000_000),
            paymentBlockNumber,
            ethereumTxHash
        );

        assertFalse("Proposal should not release before the second confirmation",
            raftAppendLatch.await(300, TimeUnit.MILLISECONDS));

        bridge.advanceMockBlocks(1);

        assertTrue("Proposal should release once required confirmations are satisfied",
            raftAppendLatch.await(10, TimeUnit.SECONDS));
        assertEquals("Callback should have captured proposal", "captured", appendedProposalId);
    }

    @Test
    public void testPriorityTierCanRouteThroughSchedulerWhenDirectReleaseDisabled() throws InterruptedException {
        System.setProperty("oak.proposal.priority.direct.release.enabled", "false");
        System.setProperty("oak.proposal.release.mode", "adaptive-active");
        recreateQueueManager();

        String proposalId = "test-priority-scheduled-001";
        String ethereumTxHash = "0xtxpriorityscheduled001";
        String walletAddress = "0x742d35cc6634c0532925a3b844bc9e7595f0beb0";
        String path = "/oak-chain/74/2d/35/0x742d35cc6634c0532925a3b844bc9e7595f0beb0/content/page-priority-scheduled";

        queueManager.queueProposal(
            proposalId,
            ethereumTxHash,
            walletAddress,
            path,
            "page",
            "Priority routed through scheduler",
            "0xsig...",
            org.apache.jackrabbit.oak.segment.consensus.economics.ValidatorEarningsTracker.PaymentTier.PRIORITY,
            null
        );

        bridge.simulateWriteAuthorizedEvent(
            proposalId,
            walletAddress,
            "0xdef456...",
            BigInteger.valueOf(1_000_000),
            bridge.getCurrentBlockNumber(),
            ethereumTxHash
        );

        assertTrue("Priority proposal should still process when direct release is disabled",
            raftAppendLatch.await(10, TimeUnit.SECONDS));

        Map<String, Object> stats = queueManager.getQueueStats();
        assertEquals(Boolean.FALSE, stats.get("priorityDirectReleaseEnabled"));
        assertEquals("Priority direct-send counter should remain zero when direct release is disabled",
            0L, longStat(stats, "priorityProposalsSent"));
        assertTrue("Proposal should drain through the scheduled/batched path instead",
            longStat(stats, "batchedProposalsSent") >= 1L);
    }

    @Test
    public void testProofTierOverridesRequestedPriorityForCompatibilityRouting() throws InterruptedException {
        System.setProperty("oak.proposal.priority.direct.release.enabled", "true");
        System.setProperty("oak.proposal.release.mode", "adaptive-active");
        recreateQueueManager();

        String proposalId = "test-proof-tier-reconcile-001";
        String ethereumTxHash = "0xtxprooftier001";
        String walletAddress = "0x742d35cc6634c0532925a3b844bc9e7595f0beb0";
        String path = "/oak-chain/74/2d/35/0x742d35cc6634c0532925a3b844bc9e7595f0beb0/content/page-proof-tier";

        queueManager.queueProposal(
            proposalId,
            ethereumTxHash,
            walletAddress,
            path,
            "page",
            "Priority requested, standard proved",
            "0xsig...",
            org.apache.jackrabbit.oak.segment.consensus.economics.ValidatorEarningsTracker.PaymentTier.PRIORITY,
            null
        );

        bridge.simulateWriteAuthorizedEvent(
            proposalId,
            walletAddress,
            "0xdef456...",
            BigInteger.valueOf(1_000_000),
            bridge.getCurrentBlockNumber(),
            ethereumTxHash,
            org.apache.jackrabbit.oak.segment.consensus.economics.ValidatorEarningsTracker.PaymentTier.STANDARD
        );

        assertTrue("Proposal should still process after tier reconciliation",
            raftAppendLatch.await(10, TimeUnit.SECONDS));

        Map<String, Object> stats = queueManager.getQueueStats();
        assertEquals(Boolean.TRUE, stats.get("priorityDirectReleaseEnabled"));
        assertEquals("Priority direct-send counter should remain zero when proof resolves to standard",
            0L, longStat(stats, "priorityProposalsSent"));
        assertTrue("Proposal should drain through the normal scheduled/batched path",
            longStat(stats, "batchedProposalsSent") >= 1L);
    }
    
    @Test
    public void testStandardTierDrainsWithoutLegacyEpochScheduling() throws InterruptedException {
        String proposalId = "test-standard-456";
        String ethereumTxHash = "0xtx456789";
        String walletAddress = "0x742d35cc6634c0532925a3b844bc9e7595f0beb0";
        String path = "/oak-chain/74/2d/35/0x742d35cc6634c0532925a3b844bc9e7595f0beb0/content/page-456";
        
        // Queue with STANDARD tier (2-epoch finality delay)
        queueManager.queueProposal(
            proposalId,
            ethereumTxHash,
            walletAddress,
            path,
            "page",
            "Standard tier content",
            "0xsig...",
            org.apache.jackrabbit.oak.segment.consensus.economics.ValidatorEarningsTracker.PaymentTier.STANDARD,
            null
        );
        
        // Simulate payment event
        bridge.simulateWriteAuthorizedEvent(
            proposalId,
            walletAddress,
            "0xdef456...",
            BigInteger.valueOf(500_000),  // 0.5 USDC (cheaper for STANDARD)
            12346L,
            ethereumTxHash
        );

        assertTrue("Standard tier should drain on the default adaptive path within 10s",
            raftAppendLatch.await(10, TimeUnit.SECONDS));
        assertTrue("Standard tier should be sent via single or batched callback",
            "captured".equals(appendedProposalId) || proposalId.equals(appendedProposalId));
    }
    
    @Test
    public void testTimeoutRejection() throws InterruptedException {
        String proposalId = "test-timeout-789";
        String walletAddress = "0x742d35cc6634c0532925a3b844bc9e7595f0beb0";
        String path = "/oak-chain/74/2d/35/0x742d35cc6634c0532925a3b844bc9e7595f0beb0/content/page-789";
        
        // Queue proposal but DON'T simulate payment event
        queueManager.queueProposal(
            proposalId,
            "0xtx789",
            walletAddress,
            path,
            "page",
            "Test",
            "0xsig...",
            org.apache.jackrabbit.oak.segment.consensus.economics.ValidatorEarningsTracker.PaymentTier.PRIORITY,
            null
        );
        
        // Verify it's pending
        QueuedProposal proposal = queueManager.getProposal(proposalId);
        assertNotNull("Proposal should be queued", proposal);
        assertEquals("Should be PENDING", ProposalState.PENDING, proposal.getState());
        
        // Note: Full timeout test would require 5 minutes
        // For unit test, we just verify the proposal is tracked
        java.util.Map<String, Object> stats = queueManager.getQueueStats();
        assertTrue("Should have pending proposals", (Long) stats.get("pendingCount") >= 1);
    }

    @Test
    public void testAdaptiveActiveStandardTierBypassesEpochDelay() throws InterruptedException {
        queueManager.stop();

        System.setProperty("oak.proposal.release.mode", "adaptive-active");
        ProposalQueueTuning tuning = ProposalQueueTuning.fromSystemProperties();
        CountDownLatch latch = new CountDownLatch(1);

        RaftAppendCallback callback = new RaftAppendCallback() {
            @Override
            public void appendProposal(String walletAddress, String path, String contentType,
                                       String message, String signature) {
                appendedProposalId = "captured";
                latch.countDown();
            }

            @Override
            public void appendProposal(String walletAddress, String path, String contentType,
                                       String message, String signature, String blobId, String mimeType) {
                appendProposal(walletAddress, path, contentType, message, signature);
            }

            @Override
            public void appendDeleteProposal(String walletAddress, String path, String signature) {
                appendedProposalId = "delete-captured";
                latch.countDown();
            }

            @Override
            public int appendProposalBatch(java.util.List<QueuedProposal> batch) {
                for (QueuedProposal proposal : batch) {
                    appendedProposalId = proposal.getProposalId();
                }
                for (int i = 0; i < batch.size(); i++) {
                    latch.countDown();
                }
                return batch.size();
            }
        };

        queueManager = new ProposalQueueManagerOptimized(
            bridge,
            callback,
            new BackpressureManager(),
            beaconClient,
            null,
            tuning
        );
        queueManager.start();

        String proposalId = "test-adaptive-standard-123";
        String ethereumTxHash = "0xtx-adaptive-123";
        String walletAddress = "0x742d35cc6634c0532925a3b844bc9e7595f0beb0";
        String path = "/oak-chain/74/2d/35/0x742d35cc6634c0532925a3b844bc9e7595f0beb0/content/page-adaptive";

        queueManager.queueProposal(
            proposalId,
            ethereumTxHash,
            walletAddress,
            path,
            "page",
            "Adaptive tier content",
            "0xsig...",
            org.apache.jackrabbit.oak.segment.consensus.economics.ValidatorEarningsTracker.PaymentTier.STANDARD,
            null
        );

        bridge.simulateWriteAuthorizedEvent(
            proposalId,
            walletAddress,
            "0xdef456...",
            BigInteger.valueOf(500_000),
            12347L,
            ethereumTxHash
        );

        assertTrue("Adaptive-active standard proposal should drain without epoch wait",
            latch.await(10, TimeUnit.SECONDS));
        assertTrue("Adaptive-active proposal should be sent via single or batched callback",
            "captured".equals(appendedProposalId) || proposalId.equals(appendedProposalId));
        assertTrue("Adaptive-active proposal should reach PROCESSED state",
            waitForCondition(() -> queueManager.getProposal(proposalId).getState() == ProposalState.PROCESSED,
                5_000, 25));

        Map<String, Object> stats = queueManager.getQueueStats();
        assertEquals("adaptive-active", stats.get("releaseMode"));
    }

    @Test
    public void testAdaptiveActiveBuffersOverflowAndPromotesAfterPressureClears() throws Exception {
        queueManager.stop();

        System.setProperty("oak.proposal.release.mode", "adaptive-active");
        System.setProperty("oak.consensus.max.pending.messages", "1");
        ProposalQueueTuning tuning = ProposalQueueTuning.fromSystemProperties();
        CountDownLatch latch = new CountDownLatch(1);

        RaftAppendCallback callback = new RaftAppendCallback() {
            @Override
            public void appendProposal(String walletAddress, String path, String contentType,
                                       String message, String signature) {
                appendedProposalId = "captured";
                latch.countDown();
            }

            @Override
            public void appendProposal(String walletAddress, String path, String contentType,
                                       String message, String signature, String blobId, String mimeType) {
                appendProposal(walletAddress, path, contentType, message, signature);
            }

            @Override
            public void appendDeleteProposal(String walletAddress, String path, String signature) {
                appendedProposalId = "delete-captured";
                latch.countDown();
            }

            @Override
            public int appendProposalBatch(java.util.List<QueuedProposal> batch) {
                for (QueuedProposal proposal : batch) {
                    appendedProposalId = proposal.getProposalId();
                }
                for (int i = 0; i < batch.size(); i++) {
                    latch.countDown();
                }
                return batch.size();
            }
        };

        BackpressureManager pressuredBackpressure = new BackpressureManager(
            1L,
            tuning.getBackpressureTimeoutMs(),
            tuning.getBackpressureParkNanos()
        );
        pressuredBackpressure.incrementSent(2L);

        queueManager = new ProposalQueueManagerOptimized(
            bridge,
            callback,
            pressuredBackpressure,
            beaconClient,
            null,
            tuning
        );
        queueManager.start();

        String proposalId = "adaptive-overflow-standard-001";
        String ethereumTxHash = "0xtx-adaptive-overflow-001";
        String walletAddress = "0x742d35cc6634c0532925a3b844bc9e7595f0beb0";
        String path = "/oak-chain/74/2d/35/0x742d35cc6634c0532925a3b844bc9e7595f0beb0/content/page-adaptive-overflow";

        queueManager.queueProposal(
            proposalId,
            ethereumTxHash,
            walletAddress,
            path,
            "page",
            "Adaptive overflow content",
            "0xsig...",
            org.apache.jackrabbit.oak.segment.consensus.economics.ValidatorEarningsTracker.PaymentTier.STANDARD,
            null
        );

        bridge.simulateWriteAuthorizedEvent(
            proposalId,
            walletAddress,
            "0xdef456...",
            BigInteger.valueOf(500_000),
            12348L,
            ethereumTxHash
        );

        assertTrue("Overloaded adaptive mode should buffer release debt in overflow",
            waitForCondition(() -> {
                Map<String, Object> stats = queueManager.getQueueStats();
                @SuppressWarnings("unchecked")
                Map<String, Object> runtimeStages = (Map<String, Object>) stats.get("runtimeStageCounts");
                return longStat(stats, "backpressureOverflowProposalCount") >= 1L
                    && longStat(stats, "releaseReadyProposalCount") == 0L
                    && runtimeStages != null
                    && Boolean.TRUE.equals(runtimeStages.get("backpressureOverflowSeparateBufferEnabled"));
            }, 10_000, 25));

        Map<String, Object> overflowStats = queueManager.getQueueStats();
        assertTrue("Adaptive packing totals should expose queued proposals",
            longStat(overflowStats, "adaptivePackingQueuedProposalCountTotal") >= 1L);
        assertTrue("Overflow totals should record buffered proposals",
            longStat(overflowStats, "backpressureOverflowBufferedProposalCountTotal") >= 1L);
        assertTrue("Verified resident count should include overflow debt",
            longStat(overflowStats, "verifiedResidentProposalCount") >= 1L);

        pressuredBackpressure.incrementAcknowledged(2L);

        assertTrue("Overflowed proposal should drain after backpressure clears",
            latch.await(10, TimeUnit.SECONDS));
        assertTrue("Adaptive overflow proposal should be sent via single or batched callback",
            "captured".equals(appendedProposalId) || proposalId.equals(appendedProposalId));
        assertTrue("Overflowed adaptive proposal should reach PROCESSED state",
            waitForCondition(() -> queueManager.getProposal(proposalId).getState() == ProposalState.PROCESSED,
                5_000, 25));

        assertTrue("Overflow buffer should eventually drain after promotion",
            waitForCondition(() -> {
                Map<String, Object> stats = queueManager.getQueueStats();
                return longStat(stats, "backpressureOverflowProposalCount") == 0L
                    && longStat(stats, "backpressureOverflowPromotedProposalCountTotal") >= 1L;
            }, 10_000, 25));
    }

    @Test
    public void testAdaptiveActiveDeleteBypassesEpochDelay() throws InterruptedException {
        queueManager.stop();

        System.setProperty("oak.proposal.release.mode", "adaptive-active");
        ProposalQueueTuning tuning = ProposalQueueTuning.fromSystemProperties();
        CountDownLatch latch = new CountDownLatch(1);

        RaftAppendCallback callback = new RaftAppendCallback() {
            @Override
            public void appendProposal(String walletAddress, String path, String contentType,
                                       String message, String signature) {
                appendedProposalId = "captured";
                latch.countDown();
            }

            @Override
            public void appendProposal(String walletAddress, String path, String contentType,
                                       String message, String signature, String blobId, String mimeType) {
                appendProposal(walletAddress, path, contentType, message, signature);
            }

            @Override
            public void appendDeleteProposal(String walletAddress, String path, String signature) {
                appendedProposalId = "delete-captured";
                latch.countDown();
            }

            @Override
            public int appendProposalBatch(java.util.List<QueuedProposal> batch) {
                for (QueuedProposal proposal : batch) {
                    appendedProposalId = proposal.getProposalId();
                }
                for (int i = 0; i < batch.size(); i++) {
                    latch.countDown();
                }
                return batch.size();
            }
        };

        queueManager = new ProposalQueueManagerOptimized(
            bridge,
            callback,
            new BackpressureManager(),
            beaconClient,
            null,
            tuning
        );
        queueManager.start();

        String proposalId = "test-adaptive-delete-123";
        String ethereumTxHash = "0xtx-adaptive-delete-123";
        String walletAddress = "0x742d35cc6634c0532925a3b844bc9e7595f0beb0";
        String path = "/oak-chain/74/2d/35/0x742d35cc6634c0532925a3b844bc9e7595f0beb0/content/delete-adaptive";

        queueManager.queueDeleteProposal(
            proposalId,
            ethereumTxHash,
            walletAddress,
            path,
            "0xsig...",
            org.apache.jackrabbit.oak.segment.consensus.economics.ValidatorEarningsTracker.PaymentTier.STANDARD
        );

        bridge.simulateWriteAuthorizedEvent(
            proposalId,
            walletAddress,
            "0xdef456...",
            BigInteger.valueOf(2_000_000),
            12349L,
            ethereumTxHash
        );

        assertTrue("Adaptive-active delete proposal should drain without epoch wait",
            latch.await(10, TimeUnit.SECONDS));
        assertEquals("Adaptive-active delete proposal should use delete callback",
            "delete-captured", appendedProposalId);
        assertTrue("Adaptive-active delete proposal should reach PROCESSED state",
            waitForCondition(() -> queueManager.getProposal(proposalId).getState() == ProposalState.PROCESSED,
                5_000, 25));
    }

    @Test
    public void testAdaptiveActiveRestoresVerifiedProposalAfterRestart() throws Exception {
        queueManager.stop();
        bridge.stop();

        System.setProperty("oak.proposal.release.mode", "adaptive-active");
        System.setProperty("oak.proposal.persistence.flush.ms", "0");
        System.setProperty("oak.proposal.persistence.flush.batch", "1");
        System.setProperty("oak.consensus.max.pending.messages", "1");

        ProposalQueueTuning tuning = ProposalQueueTuning.fromSystemProperties();
        Path persistenceDir = Files.createTempDirectory("proposal-restore-adaptive");

        EventDrivenEvmBridge firstBridge = new EventDrivenEvmBridge(
            "sepolia",
            "0x742d35Cc6634C0532925a3b844Bc9e7595f0bEb0",
            true
        );
        firstBridge.start();
        bridge = firstBridge;

        BackpressureManager pressuredBackpressure = new BackpressureManager(1L, tuning.getBackpressureTimeoutMs(),
            tuning.getBackpressureParkNanos());
        pressuredBackpressure.incrementSent(2L);
        queueManager = new ProposalQueueManagerOptimized(
            firstBridge,
            new NoopRaftAppendCallback(),
            pressuredBackpressure,
            beaconClient,
            persistenceDir.toString(),
            tuning
        );
        queueManager.start();

        String proposalId = "restore-adaptive-standard-001";
        String ethereumTxHash = "0xtx-restore-adaptive-001";
        String walletAddress = "0x742d35cc6634c0532925a3b844bc9e7595f0beb0";
        String path = "/oak-chain/74/2d/35/0x742d35cc6634c0532925a3b844bc9e7595f0beb0/content/page-restored";

        queueManager.queueProposal(
            proposalId,
            ethereumTxHash,
            walletAddress,
            path,
            "page",
            "Restore adaptive tier content",
            "0xsig...",
            org.apache.jackrabbit.oak.segment.consensus.economics.ValidatorEarningsTracker.PaymentTier.STANDARD,
            null
        );

        firstBridge.simulateWriteAuthorizedEvent(
            proposalId,
            walletAddress,
            "0xdef456...",
            BigInteger.valueOf(500_000),
            12348L,
            ethereumTxHash
        );

        assertTrue("Proposal should reach verified adaptive buffer before restart",
            waitForCondition(() -> {
                Map<String, Object> stats = queueManager.getQueueStats();
                return longStat(stats, "adaptiveVerifiedPackingBufferCount") >= 1L
                    && longStat(stats, "releaseReadyProposalCount") == 0L;
            }, 10_000, 25));

        queueManager.stop();
        firstBridge.stop();

        CountDownLatch restoredLatch = new CountDownLatch(1);
        EventDrivenEvmBridge restoredBridge = new EventDrivenEvmBridge(
            "sepolia",
            "0x742d35Cc6634C0532925a3b844Bc9e7595f0bEb0",
            true
        );
        restoredBridge.start();
        bridge = restoredBridge;
        appendedProposalId = null;

        RaftAppendCallback callback = new RaftAppendCallback() {
            @Override
            public void appendProposal(String walletAddress, String path, String contentType,
                                       String message, String signature) {
                appendedProposalId = "captured";
                restoredLatch.countDown();
            }

            @Override
            public void appendProposal(String walletAddress, String path, String contentType,
                                       String message, String signature, String blobId, String mimeType) {
                appendProposal(walletAddress, path, contentType, message, signature);
            }

            @Override
            public void appendDeleteProposal(String walletAddress, String path, String signature) {
                appendedProposalId = "delete-captured";
                restoredLatch.countDown();
            }

            @Override
            public int appendProposalBatch(java.util.List<QueuedProposal> batch) {
                for (QueuedProposal proposal : batch) {
                    appendedProposalId = proposal.getProposalId();
                }
                for (int i = 0; i < batch.size(); i++) {
                    restoredLatch.countDown();
                }
                return batch.size();
            }
        };

        queueManager = new ProposalQueueManagerOptimized(
            restoredBridge,
            callback,
            new BackpressureManager(1L, tuning.getBackpressureTimeoutMs(), tuning.getBackpressureParkNanos()),
            beaconClient,
            persistenceDir.toString(),
            tuning
        );
        queueManager.start();

        assertTrue("Restored verified proposal should drain without replaying bridge event",
            restoredLatch.await(10, TimeUnit.SECONDS));
        assertTrue("Restored proposal should be sent via single or batched callback",
            "captured".equals(appendedProposalId) || proposalId.equals(appendedProposalId));
        assertTrue("Restored proposal should remain tracked as processed",
            waitForCondition(() -> queueManager.getProposal(proposalId).getState() == ProposalState.PROCESSED,
                5_000, 25));
    }

    @Test
    public void testAdaptiveActiveRestoresOverflowedVerifiedProposalAfterRestart() throws Exception {
        queueManager.stop();
        bridge.stop();

        System.setProperty("oak.proposal.release.mode", "adaptive-active");
        System.setProperty("oak.proposal.persistence.flush.ms", "0");
        System.setProperty("oak.proposal.persistence.flush.batch", "1");
        System.setProperty("oak.consensus.max.pending.messages", "1");

        ProposalQueueTuning tuning = ProposalQueueTuning.fromSystemProperties();
        Path persistenceDir = Files.createTempDirectory("proposal-restore-adaptive-overflow");

        EventDrivenEvmBridge firstBridge = new EventDrivenEvmBridge(
            "sepolia",
            "0x742d35Cc6634C0532925a3b844Bc9e7595f0bEb0",
            true
        );
        firstBridge.start();
        bridge = firstBridge;

        BackpressureManager pressuredBackpressure = new BackpressureManager(
            1L,
            tuning.getBackpressureTimeoutMs(),
            tuning.getBackpressureParkNanos()
        );
        pressuredBackpressure.incrementSent(2L);
        queueManager = new ProposalQueueManagerOptimized(
            firstBridge,
            new NoopRaftAppendCallback(),
            pressuredBackpressure,
            beaconClient,
            persistenceDir.toString(),
            tuning
        );
        queueManager.start();

        String proposalId = "restore-adaptive-overflow-001";
        String ethereumTxHash = "0xtx-restore-adaptive-overflow-001";
        String walletAddress = "0x742d35cc6634c0532925a3b844bc9e7595f0beb0";
        String path = "/oak-chain/74/2d/35/0x742d35cc6634c0532925a3b844bc9e7595f0beb0/content/page-restored-overflow";

        queueManager.queueProposal(
            proposalId,
            ethereumTxHash,
            walletAddress,
            path,
            "page",
            "Restore adaptive overflow content",
            "0xsig...",
            org.apache.jackrabbit.oak.segment.consensus.economics.ValidatorEarningsTracker.PaymentTier.STANDARD,
            null
        );

        firstBridge.simulateWriteAuthorizedEvent(
            proposalId,
            walletAddress,
            "0xdef456...",
            BigInteger.valueOf(500_000),
            12350L,
            ethereumTxHash
        );

        assertTrue("Proposal should reach overflow before restart",
            waitForCondition(() -> longStat(queueManager.getQueueStats(), "backpressureOverflowProposalCount") >= 1L,
                10_000, 25));

        queueManager.stop();
        firstBridge.stop();

        CountDownLatch restoredLatch = new CountDownLatch(1);
        EventDrivenEvmBridge restoredBridge = new EventDrivenEvmBridge(
            "sepolia",
            "0x742d35Cc6634C0532925a3b844Bc9e7595f0bEb0",
            true
        );
        restoredBridge.start();
        bridge = restoredBridge;
        appendedProposalId = null;

        BackpressureManager restoredBackpressure = new BackpressureManager(
            1L,
            tuning.getBackpressureTimeoutMs(),
            tuning.getBackpressureParkNanos()
        );
        restoredBackpressure.incrementSent(2L);

        RaftAppendCallback callback = new RaftAppendCallback() {
            @Override
            public void appendProposal(String walletAddress, String path, String contentType,
                                       String message, String signature) {
                appendedProposalId = "captured";
                restoredLatch.countDown();
            }

            @Override
            public void appendProposal(String walletAddress, String path, String contentType,
                                       String message, String signature, String blobId, String mimeType) {
                appendProposal(walletAddress, path, contentType, message, signature);
            }

            @Override
            public void appendDeleteProposal(String walletAddress, String path, String signature) {
                appendedProposalId = "delete-captured";
                restoredLatch.countDown();
            }

            @Override
            public int appendProposalBatch(java.util.List<QueuedProposal> batch) {
                for (QueuedProposal proposal : batch) {
                    appendedProposalId = proposal.getProposalId();
                }
                for (int i = 0; i < batch.size(); i++) {
                    restoredLatch.countDown();
                }
                return batch.size();
            }
        };

        queueManager = new ProposalQueueManagerOptimized(
            restoredBridge,
            callback,
            restoredBackpressure,
            beaconClient,
            persistenceDir.toString(),
            tuning
        );
        queueManager.start();

        assertTrue("Restored overflowed proposal should remain resident until pressure clears",
            waitForCondition(() -> longStat(queueManager.getQueueStats(), "verifiedResidentProposalCount") >= 1L,
                10_000, 25));
        assertEquals("No callback should fire while restored backpressure remains active", null, appendedProposalId);

        restoredBackpressure.incrementAcknowledged(2L);

        assertTrue("Restored overflowed proposal should drain after backpressure clears",
            restoredLatch.await(10, TimeUnit.SECONDS));
        assertTrue("Restored overflowed proposal should be sent via single or batched callback",
            "captured".equals(appendedProposalId) || proposalId.equals(appendedProposalId));
        assertTrue("Restored overflowed proposal should remain tracked as processed",
            waitForCondition(() -> queueManager.getProposal(proposalId).getState() == ProposalState.PROCESSED,
                5_000, 25));
    }

    @Test
    public void testLargePayloadSpillsToDiskAndCleansUpAfterProcessing() throws Exception {
        queueManager.stop();

        System.setProperty("oak.proposal.payload.inline.max.bytes", "4");
        System.setProperty("oak.proposal.priority.direct.release.enabled", "true");
        ProposalQueueTuning tuning = ProposalQueueTuning.fromSystemProperties();
        Path persistenceDir = Files.createTempDirectory("proposal-payload-spill");
        CountDownLatch latch = new CountDownLatch(1);
        final String message = "payload that is much larger than four bytes";
        final String[] capturedMessage = {null};

        RaftAppendCallback callback = new RaftAppendCallback() {
            @Override
            public void appendProposal(String walletAddress, String path, String contentType,
                                       String resolvedMessage, String signature) {
                capturedMessage[0] = resolvedMessage;
                appendedProposalId = "captured";
                latch.countDown();
            }

            @Override
            public void appendProposal(String walletAddress, String path, String contentType,
                                       String resolvedMessage, String signature, String blobId, String mimeType) {
                appendProposal(walletAddress, path, contentType, resolvedMessage, signature);
            }
        };

        queueManager = new ProposalQueueManagerOptimized(
            bridge,
            callback,
            new BackpressureManager(),
            beaconClient,
            persistenceDir.toString(),
            tuning
        );
        queueManager.start();

        String proposalId = "spill-large-payload-001";
        String ethereumTxHash = "0xtxspillpayload001";
        String walletAddress = "0x742d35cc6634c0532925a3b844bc9e7595f0beb0";
        String path = "/oak-chain/74/2d/35/0x742d35cc6634c0532925a3b844bc9e7595f0beb0/content/spill-large";

        QueuedProposal proposal = queueManager.queueProposal(
            proposalId,
            ethereumTxHash,
            walletAddress,
            path,
            "page",
            message,
            "0xsig...",
            org.apache.jackrabbit.oak.segment.consensus.economics.ValidatorEarningsTracker.PaymentTier.PRIORITY,
            null
        );

        assertNull("Large payload should not remain inline in steady queue state", proposal.getMessage());
        assertNotNull("Large payload should have durable spill reference", proposal.getPayloadRef());
        assertTrue("Payload file should exist on disk",
            Files.exists(persistenceDir.resolve("payloads").resolve(proposal.getPayloadRef())));

        bridge.simulateWriteAuthorizedEvent(
            proposalId,
            walletAddress,
            "0xdef456...",
            BigInteger.valueOf(1_000_000),
            bridge.getCurrentBlockNumber(),
            ethereumTxHash
        );

        assertTrue("Spilled proposal should still process", latch.await(10, TimeUnit.SECONDS));
        assertEquals("Resolved message should round-trip through spill store", message, capturedMessage[0]);
        assertTrue("Payload spool should drain after terminal cleanup",
            waitForCondition(() -> longStat(queueManager.getQueueStats(), "payloadSpoolBytes") == 0L, 10_000, 25));
        assertEquals("Processed proposal should clear payload reference", null, proposal.getPayloadRef());
        assertEquals("Processed proposal should clear inline message cache", null, proposal.getMessage());
        assertTrue("Disk-only payload counter should increment",
            longStat(queueManager.getQueueStats(), "payloadDiskOnlyCount") >= 1L);
        assertTrue("Payload resolve counter should increment on lazy load",
            longStat(queueManager.getQueueStats(), "payloadResolveCount") >= 1L);
    }

    @Test
    public void testHardPendingLimitRejectsNewProposal() throws Exception {
        queueManager.stop();

        System.setProperty("oak.proposal.hard.max.pending", "1");
        ProposalQueueTuning tuning = ProposalQueueTuning.fromSystemProperties();
        ProposalQueueManagerOptimized testQueue = new ProposalQueueManagerOptimized(
            bridge,
            new NoopRaftAppendCallback(),
            new BackpressureManager(),
            beaconClient,
            null,
            tuning
        );
        testQueue.start();

        try {
            String walletAddress = "0x742d35cc6634c0532925a3b844bc9e7595f0beb0";
            String basePath = "/oak-chain/74/2d/35/0x742d35cc6634c0532925a3b844bc9e7595f0beb0/content/";

            testQueue.queueProposal(
                "hard-limit-first",
                "0xhardlimit1",
                walletAddress,
                basePath + "first",
                "page",
                "first",
                "0xsig...",
                org.apache.jackrabbit.oak.segment.consensus.economics.ValidatorEarningsTracker.PaymentTier.STANDARD,
                null
            );

            try {
                testQueue.queueProposal(
                    "hard-limit-second",
                    "0xhardlimit2",
                    walletAddress,
                    basePath + "second",
                    "page",
                    "second",
                    "0xsig...",
                    org.apache.jackrabbit.oak.segment.consensus.economics.ValidatorEarningsTracker.PaymentTier.STANDARD,
                    null
                );
                fail("Second proposal should be rejected once hard pending limit is reached");
            } catch (java.util.concurrent.RejectedExecutionException expected) {
                assertEquals("queue_overloaded", expected.getMessage());
            }

            assertEquals("Overload counter should track rejected admissions",
                1L, longStat(testQueue.getQueueStats(), "payloadOverloadRejectCount"));
        } finally {
            testQueue.stop();
        }
    }

    @Test
    public void testRestoreSkipsProposalWhenPayloadSidecarMissing() throws Exception {
        queueManager.stop();
        bridge.stop();

        System.setProperty("oak.proposal.persistence.flush.ms", "0");
        System.setProperty("oak.proposal.persistence.flush.batch", "1");
        System.setProperty("oak.proposal.payload.inline.max.bytes", "4");
        ProposalQueueTuning tuning = ProposalQueueTuning.fromSystemProperties();
        Path persistenceDir = Files.createTempDirectory("proposal-missing-payload-restore");

        EventDrivenEvmBridge firstBridge = new EventDrivenEvmBridge(
            "sepolia",
            "0x742d35Cc6634C0532925a3b844Bc9e7595f0bEb0",
            true
        );
        firstBridge.start();
        bridge = firstBridge;

        queueManager = new ProposalQueueManagerOptimized(
            firstBridge,
            new NoopRaftAppendCallback(),
            new BackpressureManager(),
            beaconClient,
            persistenceDir.toString(),
            tuning
        );
        queueManager.start();

        String proposalId = "restore-missing-payload-001";
        QueuedProposal queuedProposal = queueManager.queueProposal(
            proposalId,
            "0xmissingpayload001",
            "0x742d35cc6634c0532925a3b844bc9e7595f0beb0",
            "/oak-chain/74/2d/35/0x742d35cc6634c0532925a3b844bc9e7595f0beb0/content/missing-payload",
            "page",
            "restore me from disk",
            "0xsig...",
            org.apache.jackrabbit.oak.segment.consensus.economics.ValidatorEarningsTracker.PaymentTier.STANDARD,
            null
        );
        assertNotNull("Proposal should spill to disk before restart", queuedProposal.getPayloadRef());

        Path payloadPath = persistenceDir.resolve("payloads").resolve(queuedProposal.getPayloadRef());
        assertTrue(Files.exists(payloadPath));

        queueManager.stop();
        Files.delete(payloadPath);

        EventDrivenEvmBridge restoredBridge = new EventDrivenEvmBridge(
            "sepolia",
            "0x742d35Cc6634C0532925a3b844Bc9e7595f0bEb0",
            true
        );
        restoredBridge.start();
        bridge = restoredBridge;

        queueManager = new ProposalQueueManagerOptimized(
            restoredBridge,
            new NoopRaftAppendCallback(),
            new BackpressureManager(),
            beaconClient,
            persistenceDir.toString(),
            tuning
        );
        queueManager.start();

        assertEquals("Proposal with missing payload should be skipped during restore",
            null, queueManager.getProposal(proposalId));
        assertEquals("Missing payload restore counter should increment",
            1L, longStat(queueManager.getQueueStats(), "payloadRestoreMissingCount"));
    }
    
    @Test
    public void testMultipleProposals() throws InterruptedException {
        int proposalCount = 3;
        CountDownLatch latch = new CountDownLatch(proposalCount);
        
        RaftAppendCallback callback = new RaftAppendCallback() {
            @Override
            public void appendProposal(String walletAddress, String path, String contentType,
                                       String message, String signature) {
                latch.countDown();
            }
            
            @Override
            public void appendProposal(String walletAddress, String path, String contentType,
                                       String message, String signature, String blobId, String mimeType) {
                latch.countDown();
            }
            
            @Override
            public void appendDeleteProposal(String walletAddress, String path, String signature) {
                latch.countDown();
            }
            
            @Override
            public int appendProposalBatch(java.util.List<QueuedProposal> batch) {
                for (int i = 0; i < batch.size(); i++) {
                    latch.countDown();
                }
                return batch.size();
            }
        };
        
        // Create separate queue manager for this test
        BackpressureManager testBackpressureManager = new BackpressureManager();
        ProposalQueueManagerOptimized multiQueue = new ProposalQueueManagerOptimized(
            bridge, callback, testBackpressureManager, beaconClient);
        multiQueue.start();
        
        try {
            String walletAddress = "0x742d35cc6634c0532925a3b844bc9e7595f0beb0";
            
            // Queue 3 proposals with PRIORITY tier for fast processing
            for (int i = 0; i < proposalCount; i++) {
                String proposalId = "test-multi-" + i;
                String path = "/oak-chain/74/2d/35/0x742d35cc6634c0532925a3b844bc9e7595f0beb0/content/page-" + i;
                
                multiQueue.queueProposal(
                    proposalId,
                    "0xtx" + i,
                    walletAddress,
                    path,
                    "page",
                    "Message " + i,
                    "0xsig...",
                    org.apache.jackrabbit.oak.segment.consensus.economics.ValidatorEarningsTracker.PaymentTier.PRIORITY,
                    null
                );
                
                // Simulate payment event
                bridge.simulateWriteAuthorizedEvent(
                    proposalId,
                    walletAddress,
                    "0xdef456...",
                    BigInteger.valueOf(1_000_000),
                    12345L + i,
                    "0xtx" + i
                );
            }
            
            // Wait for all to process (PRIORITY tier should be fast)
            assertTrue("All proposals should be processed within 30s", 
                latch.await(30, TimeUnit.SECONDS));
            
        } finally {
            multiQueue.stop();
        }
    }
    
    @Test
    public void testDeleteProposal() throws InterruptedException {
        String proposalId = "test-delete-001";
        String ethereumTxHash = "0xtxdelete001";
        String walletAddress = "0x742d35cc6634c0532925a3b844bc9e7595f0beb0";
        String path = "/oak-chain/74/2d/35/0x742d35cc6634c0532925a3b844bc9e7595f0beb0/content/to-delete";
        
        // Queue DELETE proposal with PRIORITY tier
        queueManager.queueDeleteProposal(
            proposalId,
            ethereumTxHash,
            walletAddress,
            path,
            "0xsig...",
            org.apache.jackrabbit.oak.segment.consensus.economics.ValidatorEarningsTracker.PaymentTier.PRIORITY
        );
        
        // Verify queued as DELETE type
        QueuedProposal proposal = queueManager.getProposal(proposalId);
        assertNotNull("Delete proposal should be queued", proposal);
        assertEquals("Should be DELETE type", QueuedProposal.ProposalType.DELETE, proposal.getType());
        
        // Simulate payment event
        bridge.simulateWriteAuthorizedEvent(
            proposalId,
            walletAddress,
            "0xdef456...",
            BigInteger.valueOf(2_000_000),  // Higher cost for delete (GC debt)
            12350L,
            ethereumTxHash
        );
        
        // Wait for processing
        assertTrue("Delete should be processed within 10s", raftAppendLatch.await(10, TimeUnit.SECONDS));
        assertEquals("Should capture delete", "delete-captured", appendedProposalId);
    }
    
    @Test
    public void testQueueStats() {
        // Queue a proposal
        String walletAddress = "0x742d35cc6634c0532925a3b844bc9e7595f0beb0";
        queueManager.queueProposal(
            "stats-test-001",
            "0xtxstats",
            walletAddress,
            "/oak-chain/74/2d/35/0x742d35cc6634c0532925a3b844bc9e7595f0beb0/content/stats",
            "page",
            "Stats test",
            "0xsig...",
            org.apache.jackrabbit.oak.segment.consensus.economics.ValidatorEarningsTracker.PaymentTier.STANDARD,
            null
        );
        
        // Get queue stats
        java.util.Map<String, Object> stats = queueManager.getQueueStats();
        
        // Verify stats structure
        assertNotNull("Stats should not be null", stats);
        assertTrue("Should have unverifiedQueueSize", stats.containsKey("unverifiedQueueSize"));
        assertTrue("Should have currentEpoch", stats.containsKey("currentEpoch"));
        assertTrue("Should have finalizedEpoch", stats.containsKey("finalizedEpoch"));
        assertTrue("Should have pendingCount", stats.containsKey("pendingCount"));
        assertTrue("Should have maxRetryLimit", stats.containsKey("maxRetryLimit"));
        assertTrue("Should expose adaptive packing totals", stats.containsKey("adaptivePackingQueuedProposalCountTotal"));
        assertTrue("Should expose overflow totals", stats.containsKey("backpressureOverflowBufferedProposalCountTotal"));
        assertTrue("Should expose verified resident count", stats.containsKey("verifiedResidentProposalCount"));
        assertTrue("Should expose payload spool bytes", stats.containsKey("payloadSpoolBytes"));
        assertTrue("Should expose payload inline counter", stats.containsKey("payloadInlineRetainedCount"));
        assertTrue("Should expose payload disk-only counter", stats.containsKey("payloadDiskOnlyCount"));
        assertTrue("Should expose overload counter", stats.containsKey("payloadOverloadRejectCount"));
        
        // Verify retry limit is configured
        assertEquals("Max retry limit should be 5", 5, stats.get("maxRetryLimit"));
    }

    @Test
    public void testSubmissionEpochNoLongerDerivedFromTier() {
        final String walletAddress = "0x742d35cc6634c0532925a3b844bc9e7595f0beb0";
        final String basePath = "/oak-chain/74/2d/35/0x742d35cc6634c0532925a3b844bc9e7595f0beb0/content/";

        QueuedProposal standard = queueManager.queueProposal(
            "submission-epoch-standard",
            "0xsubepoch1",
            walletAddress,
            basePath + "standard",
            "page",
            "standard",
            "0xsig...",
            org.apache.jackrabbit.oak.segment.consensus.economics.ValidatorEarningsTracker.PaymentTier.STANDARD,
            null
        );
        QueuedProposal express = queueManager.queueProposal(
            "submission-epoch-express",
            "0xsubepoch2",
            walletAddress,
            basePath + "express",
            "page",
            "express",
            "0xsig...",
            org.apache.jackrabbit.oak.segment.consensus.economics.ValidatorEarningsTracker.PaymentTier.EXPRESS,
            null
        );
        QueuedProposal priority = queueManager.queueProposal(
            "submission-epoch-priority",
            "0xsubepoch3",
            walletAddress,
            basePath + "priority",
            "page",
            "priority",
            "0xsig...",
            org.apache.jackrabbit.oak.segment.consensus.economics.ValidatorEarningsTracker.PaymentTier.PRIORITY,
            null
        );
        QueuedProposal delete = queueManager.queueDeleteProposal(
            "submission-epoch-delete",
            "0xsubepoch4",
            walletAddress,
            basePath + "delete",
            "0xsig...",
            org.apache.jackrabbit.oak.segment.consensus.economics.ValidatorEarningsTracker.PaymentTier.PRIORITY
        );

        assertEquals("EXPRESS should share the same submission epoch as STANDARD",
            standard.getEpoch(), express.getEpoch());
        assertEquals("PRIORITY should share the same submission epoch as STANDARD",
            standard.getEpoch(), priority.getEpoch());
        assertEquals("DELETE should share the same submission epoch as WRITE proposals",
            standard.getEpoch(), delete.getEpoch());
    }

    @Test
    public void testStandardBurstBuildsDebtThenDrainsUnderAdaptiveRelease() throws InterruptedException {
        final int proposalCount = 40;
        final CountDownLatch finalizedLatch = new CountDownLatch(proposalCount);
        final String walletAddress = "0x742d35cc6634c0532925a3b844bc9e7595f0beb0";

        RaftAppendCallback callback = new RaftAppendCallback() {
            @Override
            public void appendProposal(String walletAddress, String path, String contentType,
                                       String message, String signature) {
                finalizedLatch.countDown();
            }

            @Override
            public void appendProposal(String walletAddress, String path, String contentType,
                                       String message, String signature, String blobId, String mimeType) {
                finalizedLatch.countDown();
            }

            @Override
            public void appendDeleteProposal(String walletAddress, String path, String signature) {
                // No-op for this test
            }

            @Override
            public int appendProposalBatch(java.util.List<QueuedProposal> batch) {
                for (int i = 0; i < batch.size(); i++) {
                    finalizedLatch.countDown();
                }
                return batch.size();
            }
        };

        BackpressureManager bp = new BackpressureManager();
        ProposalQueueManagerOptimized testQueue = new ProposalQueueManagerOptimized(
            bridge, callback, bp, beaconClient);
        testQueue.start();

        try {
            for (int i = 0; i < proposalCount; i++) {
                String proposalId = "standard-burst-" + i;
                String txHash = "0xstd" + i;
                String path = "/oak-chain/74/2d/35/0x742d35cc6634c0532925a3b844bc9e7595f0beb0/content/burst-" + i;
                testQueue.queueProposal(
                    proposalId,
                    txHash,
                    walletAddress,
                    path,
                    "page",
                    "standard-" + i,
                    "0xsig...",
                    org.apache.jackrabbit.oak.segment.consensus.economics.ValidatorEarningsTracker.PaymentTier.STANDARD,
                    null
                );
                bridge.simulateWriteAuthorizedEvent(
                    proposalId,
                    walletAddress,
                    "0xdef456...",
                    BigInteger.valueOf(500_000),
                    20000L + i,
                    txHash
                );
            }

            assertTrue("Burst proposals should become verified",
                waitForCondition(() -> longStat(testQueue.getQueueStats(), "totalVerifiedCount") >= proposalCount,
                    15_000, 100));

            Map<String, Object> preAdvance = testQueue.getQueueStats();
            long preVerified = longStat(preAdvance, "totalVerifiedCount");
            long preFinalized = longStat(preAdvance, "totalFinalizedCount");
            long preGap = preVerified - preFinalized;

            assertTrue("Invariant: verified count must be >= finalized count", preVerified >= preFinalized);

            assertTrue("Finalization should drain under adaptive release without epoch gating",
                finalizedLatch.await(20, TimeUnit.SECONDS));

            Map<String, Object> postAdvance = testQueue.getQueueStats();
            long postVerified = longStat(postAdvance, "totalVerifiedCount");
            long postFinalized = longStat(postAdvance, "totalFinalizedCount");
            long postGap = postVerified - postFinalized;

            assertTrue("Invariant: verified count must remain >= finalized count", postVerified >= postFinalized);
            if (preGap > 0L) {
                assertTrue("Finalization debt should reduce as adaptive release drains verified work", postGap < preGap);
            } else {
                assertEquals("Adaptive release is allowed to stay debt-free through the burst", 0L, postGap);
            }
            assertTrue("Backpressure pending should never be negative",
                longStat(postAdvance, "backpressurePendingCount") >= 0);
        } finally {
            testQueue.stop();
        }
    }

    @Test
    public void testStandardBurstDebtTrendsDownUnderAdaptiveRelease() throws InterruptedException {
        final int proposalCount = 80;
        final String walletAddress = "0x742d35cc6634c0532925a3b844bc9e7595f0beb0";

        BackpressureManager bp = new BackpressureManager();
        ProposalQueueManagerOptimized testQueue = new ProposalQueueManagerOptimized(
            bridge, new NoopRaftAppendCallback(), bp, beaconClient);
        testQueue.start();

        try {
            for (int i = 0; i < proposalCount; i++) {
                String proposalId = "standard-slope-" + i;
                String txHash = "0xslope" + i;
                String path = "/oak-chain/74/2d/35/0x742d35cc6634c0532925a3b844bc9e7595f0beb0/content/slope-" + i;
                testQueue.queueProposal(
                    proposalId,
                    txHash,
                    walletAddress,
                    path,
                    "page",
                    "slope-" + i,
                    "0xsig...",
                    org.apache.jackrabbit.oak.segment.consensus.economics.ValidatorEarningsTracker.PaymentTier.STANDARD,
                    null
                );
                bridge.simulateWriteAuthorizedEvent(
                    proposalId,
                    walletAddress,
                    "0xdef456...",
                    BigInteger.valueOf(500_000),
                    30000L + i,
                    txHash
                );
            }

            assertTrue("Burst proposals should become verified",
                waitForCondition(() -> longStat(testQueue.getQueueStats(), "totalVerifiedCount") >= proposalCount,
                    20_000, 100));

            long firstGap = queueGap(testQueue.getQueueStats());
            long firstTs = System.currentTimeMillis();
            long maxObservedGap = firstGap;
            long minObservedGap = firstGap;
            long lastGap = firstGap;
            long lastTs = firstTs;

            long deadline = firstTs + 12_000;
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(500);
                long gap = queueGap(testQueue.getQueueStats());
                long now = System.currentTimeMillis();
                maxObservedGap = Math.max(maxObservedGap, gap);
                minObservedGap = Math.min(minObservedGap, gap);
                lastGap = gap;
                lastTs = now;
            }

            if (maxObservedGap == 0L) {
                assertEquals("Adaptive release should be allowed to stay effectively debt-free under burst load",
                    0L, lastGap);
            } else {
                assertTrue("Adaptive release debt should improve over the observation window",
                    minObservedGap < maxObservedGap);
                assertTrue("Adaptive release should not end the window with more debt than it observed at peak",
                    lastGap < maxObservedGap);
            }
        } finally {
            testQueue.stop();
        }
    }

    @Test
    public void testBackpressurePendingNormalizesToZeroWhenQueueIdle() {
        BackpressureManager bp = new BackpressureManager();
        ProposalQueueManagerOptimized testQueue = new ProposalQueueManagerOptimized(
            bridge, new NoopRaftAppendCallback(), bp, beaconClient);
        testQueue.start();

        try {
            // Simulate tiny sent/acked residual drift while queue has no work.
            bp.incrementSent(2);

            Map<String, Object> stats = testQueue.getQueueStats();
            assertEquals("Queue should be empty for idle normalization check", 0L, longStat(stats, "batchQueueSize"));
            assertEquals("Dashboard-facing backpressure pending should normalize to zero when idle", 0L,
                longStat(stats, "backpressurePendingCount"));
            assertEquals("Raw backpressure pending should still expose underlying counter drift", 2L,
                longStat(stats, "backpressurePendingRawCount"));
            assertFalse("Backpressure should not be active for tiny idle residual", (Boolean) stats.get("backpressureActive"));
        } finally {
            testQueue.stop();
        }
    }

    @Test
    public void testQueueStatsExposeCurrentAndLifetimeCounters() throws InterruptedException {
        BackpressureManager bp = new BackpressureManager();
        ProposalQueueManagerOptimized testQueue = new ProposalQueueManagerOptimized(
            bridge, new NoopRaftAppendCallback(), bp, beaconClient);
        testQueue.start();

        try {
            assertTrue("Should be able to control mock epoch", beaconClient.setMockEpochOffset(0));

            String proposalId = "counter-contract-1";
            String txHash = "0xcounter1";
            String walletAddress = "0x742d35cc6634c0532925a3b844bc9e7595f0beb0";
            String path = "/oak-chain/74/2d/35/0x742d35cc6634c0532925a3b844bc9e7595f0beb0/content/counter-1";

            testQueue.queueProposal(
                proposalId,
                txHash,
                walletAddress,
                path,
                "page",
                "counter-1",
                "0xsig...",
                org.apache.jackrabbit.oak.segment.consensus.economics.ValidatorEarningsTracker.PaymentTier.STANDARD,
                null
            );
            bridge.simulateWriteAuthorizedEvent(
                proposalId,
                walletAddress,
                "0xdef456...",
                BigInteger.valueOf(500_000),
                40000L,
                txHash
            );

            assertTrue("Proposal should become verified",
                waitForCondition(() -> longStat(testQueue.getQueueStats(), "totalVerifiedCount") >= 1L, 10_000, 100));

            Map<String, Object> stats = testQueue.getQueueStats();
            assertTrue("Counter window start should be present",
                longStat(stats, "counterWindowStartMs") > 0);
            assertTrue("Counter rotation interval should be present",
                longStat(stats, "counterRotationIntervalMs") >= 0);
            assertTrue("Current verified should be <= lifetime verified",
                longStat(stats, "totalVerifiedCount") <= longStat(stats, "totalVerifiedCountLifetime"));
            assertTrue("Current finalized should be <= lifetime finalized",
                longStat(stats, "totalFinalizedCount") <= longStat(stats, "totalFinalizedCountLifetime"));
            assertTrue("Current sent should be <= lifetime sent",
                longStat(stats, "totalProposalsSent") <= longStat(stats, "totalProposalsSentLifetime"));
        } finally {
            testQueue.stop();
        }
    }

    private static long longStat(Map<String, Object> stats, String key) {
        Object value = stats.get(key);
        assertNotNull("Missing stat: " + key, value);
        assertTrue("Stat is not numeric: " + key, value instanceof Number);
        return ((Number) value).longValue();
    }

    private static long queueGap(Map<String, Object> stats) {
        return longStat(stats, "totalVerifiedCount") - longStat(stats, "totalFinalizedCount");
    }

    private static final class NoopRaftAppendCallback implements RaftAppendCallback {
        @Override
        public void appendProposal(String walletAddress, String path, String contentType, String message,
                                   String signature) {
            // No-op
        }

        @Override
        public void appendProposal(String walletAddress, String path, String contentType, String message,
                                   String signature, String blobId, String mimeType) {
            // No-op
        }

        @Override
        public void appendDeleteProposal(String walletAddress, String path, String signature) {
            // No-op
        }

        @Override
        public int appendProposalBatch(java.util.List<QueuedProposal> batch) {
            return batch.size();
        }
    }

    private static boolean waitForCondition(BooleanSupplier condition, long timeoutMs, long sleepMs)
        throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(sleepMs);
        }
        return condition.getAsBoolean();
    }
}
