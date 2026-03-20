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

import java.math.BigInteger;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.apache.jackrabbit.oak.segment.consensus.eth.BeaconChainClient;
import org.apache.jackrabbit.oak.segment.consensus.evm.impl.EventDrivenEvmBridge;
import org.apache.jackrabbit.oak.segment.consensus.util.WalletPathUtil;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ProposalQueueAdaptiveCapacityTest {

    private static final Logger log = LoggerFactory.getLogger(ProposalQueueAdaptiveCapacityTest.class);

    @BeforeClass
    public static void setUpClass() {
        System.setProperty("oak.blockchain.mode", "mock");
    }

    @After
    public void tearDown() {
        System.clearProperty("oak.proposal.release.mode");
        System.clearProperty("oak.consensus.max.pending.messages");
    }

    @Test
    public void testHealthyAdaptiveBurstAvoidsOverflow() throws Exception {
        CapacityScenarioResult result = runScenario(new CapacityScenario(
            "healthy-burst",
            24,
            6,
            8L,
            0L,
            false,
            0L
        ));

        assertEquals("Healthy adaptive burst should not use overflow", 0L, result.peakOverflowProposalCount);
        assertEquals("Healthy adaptive burst should not buffer overflow proposals", 0L, result.totalBufferedProposalCount);
        assertEquals("All proposals should be processed", result.proposalCount, result.processedCount);
    }

    @Test
    public void testConstrainedAdaptiveBurstUsesOverflowAndRecovers() throws Exception {
        CapacityScenarioResult result = runScenario(new CapacityScenario(
            "constrained-burst",
            32,
            8,
            1L,
            2L,
            true,
            1_200L
        ));

        assertTrue("Constrained adaptive burst should enter overflow", result.peakOverflowProposalCount > 0L);
        assertTrue("Constrained adaptive burst should buffer overflow proposals", result.totalBufferedProposalCount > 0L);
        assertTrue("Constrained adaptive burst should promote overflow back into release-ready flow",
            result.totalPromotedProposalCount > 0L);
        assertEquals("All proposals should be processed after recovery", result.proposalCount, result.processedCount);
        assertTrue("Recovery after pressure clears should remain bounded", result.recoveryDurationMs >= 0L
            && result.recoveryDurationMs < 10_000L);
    }

    @Test
    public void testHigherPendingBudgetReducesOverflowForSameBurst() throws Exception {
        CapacityScenarioResult constrained = runScenario(new CapacityScenario(
            "budget-low",
            32,
            8,
            1L,
            2L,
            true,
            1_200L
        ));
        CapacityScenarioResult roomier = runScenario(new CapacityScenario(
            "budget-high",
            32,
            8,
            4L,
            2L,
            true,
            1_200L
        ));

        assertTrue("Low pending budget scenario should overflow", constrained.peakOverflowProposalCount > 0L);
        assertTrue("Higher pending budget should not overflow more than the constrained case",
            roomier.peakOverflowProposalCount <= constrained.peakOverflowProposalCount);
        assertTrue("Higher pending budget should not buffer more proposals than the constrained case",
            roomier.totalBufferedProposalCount <= constrained.totalBufferedProposalCount);
    }

    private CapacityScenarioResult runScenario(CapacityScenario scenario) throws Exception {
        System.setProperty("oak.proposal.release.mode", "adaptive-active");
        System.setProperty("oak.consensus.max.pending.messages", String.valueOf(scenario.maxPendingMessages));

        ProposalQueueTuning tuning = ProposalQueueTuning.fromSystemProperties();
        EventDrivenEvmBridge bridge = new EventDrivenEvmBridge(
            "sepolia",
            "0x742d35Cc6634C0532925a3b844Bc9e7595f0bEb0",
            true
        );
        BeaconChainClient beaconClient = new BeaconChainClient("ignored-in-mock-mode");
        bridge.start();
        beaconClient.startBackgroundPolling();

        CountDownLatch processedLatch = new CountDownLatch(scenario.proposalCount);
        AtomicInteger processedCount = new AtomicInteger(0);
        RaftAppendCallback callback = new CountingRaftAppendCallback(processedLatch, processedCount);
        BackpressureManager backpressureManager = new BackpressureManager(
            scenario.maxPendingMessages,
            tuning.getBackpressureTimeoutMs(),
            tuning.getBackpressureParkNanos()
        );
        if (scenario.initialPendingDebt > 0L) {
            backpressureManager.incrementSent(scenario.initialPendingDebt);
        }

        ProposalQueueManagerOptimized queueManager = new ProposalQueueManagerOptimized(
            bridge,
            callback,
            backpressureManager,
            beaconClient,
            null,
            tuning
        );
        queueManager.start();

        try {
            long startMs = System.currentTimeMillis();
            for (int i = 0; i < scenario.proposalCount; i++) {
                String walletAddress = walletAddressForIndex(i % scenario.walletCount);
                String proposalId = scenario.name + "-proposal-" + i;
                String txHash = "0x" + scenario.name.replace("-", "") + String.format("%08x", i);
                String path = WalletPathUtil.getContentPath(walletAddress) + "/" + scenario.name + "/item-" + i;

                queueManager.queueProposal(
                    proposalId,
                    txHash,
                    walletAddress,
                    path,
                    "page",
                    "payload-" + i,
                    "0xsig...",
                    org.apache.jackrabbit.oak.segment.consensus.economics.ValidatorEarningsTracker.PaymentTier.STANDARD,
                    null
                );

                bridge.simulateWriteAuthorizedEvent(
                    proposalId,
                    walletAddress,
                    "0xdef456...",
                    BigInteger.valueOf(500_000),
                    50_000L + i,
                    txHash
                );
            }

            CapacityScenarioResult result = new CapacityScenarioResult(scenario.proposalCount);
            result.startMs = startMs;

            long pressureReleasedAtMs = startMs;
            if (scenario.clearPressureAfterVerification && scenario.initialPendingDebt > 0L) {
                assertTrue("Scenario should become fully verified before clearing pressure: " + scenario.name,
                    waitForCondition(() -> {
                        Map<String, Object> stats = queueManager.getQueueStats();
                        observeStats(stats, result);
                        return longStat(stats, "totalVerifiedCount") >= scenario.proposalCount;
                    }, 15_000L, 25L));
                if (scenario.holdAfterVerificationMs > 0L) {
                    long holdDeadline = System.currentTimeMillis() + scenario.holdAfterVerificationMs;
                    while (System.currentTimeMillis() < holdDeadline) {
                        observeStats(queueManager.getQueueStats(), result);
                        Thread.sleep(25L);
                    }
                }
                pressureReleasedAtMs = System.currentTimeMillis();
                backpressureManager.incrementAcknowledged(scenario.initialPendingDebt);
            }

            assertTrue("Scenario should process all proposals: " + scenario.name,
                waitForCondition(() -> {
                    observeStats(queueManager.getQueueStats(), result);
                    return processedLatch.getCount() == 0L;
                }, 15_000L, 25L));

            result.processedCount = processedCount.get();
            result.endMs = System.currentTimeMillis();
            result.processingDurationMs = result.endMs - result.startMs;
            result.recoveryDurationMs = scenario.clearPressureAfterVerification
                ? Math.max(0L, result.endMs - pressureReleasedAtMs)
                : 0L;
            observeStats(queueManager.getQueueStats(), result);

            log.info("ADAPTIVE_CAPACITY scenario={} proposals={} wallets={} maxPending={} initialDebt={} peakResident={} peakOverflow={} bufferedTotal={} promotedTotal={} processingMs={} recoveryMs={}",
                scenario.name,
                scenario.proposalCount,
                scenario.walletCount,
                scenario.maxPendingMessages,
                scenario.initialPendingDebt,
                result.peakVerifiedResidentProposalCount,
                result.peakOverflowProposalCount,
                result.totalBufferedProposalCount,
                result.totalPromotedProposalCount,
                result.processingDurationMs,
                result.recoveryDurationMs);

            return result;
        } finally {
            queueManager.stop();
            bridge.stop();
            beaconClient.stopBackgroundPolling();
        }
    }

    private static void observeStats(Map<String, Object> stats, CapacityScenarioResult result) {
        result.peakVerifiedResidentProposalCount = Math.max(
            result.peakVerifiedResidentProposalCount,
            longStat(stats, "verifiedResidentProposalCount")
        );
        result.peakOverflowProposalCount = Math.max(
            result.peakOverflowProposalCount,
            longStat(stats, "backpressureOverflowProposalCount")
        );
        result.totalBufferedProposalCount = Math.max(
            result.totalBufferedProposalCount,
            longStat(stats, "backpressureOverflowBufferedProposalCountTotal")
        );
        result.totalPromotedProposalCount = Math.max(
            result.totalPromotedProposalCount,
            longStat(stats, "backpressureOverflowPromotedProposalCountTotal")
        );
    }

    private static long longStat(Map<String, Object> stats, String key) {
        Object value = stats.get(key);
        assertTrue("Missing stat: " + key, value instanceof Number);
        return ((Number) value).longValue();
    }

    private static String walletAddressForIndex(int index) {
        int b1 = index & 0xff;
        int b2 = (index + 17) & 0xff;
        int b3 = (index + 34) & 0xff;
        return String.format("0x%02x%02x%02x%034x", b1, b2, b3, index + 1L);
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

    private static final class CountingRaftAppendCallback implements RaftAppendCallback {
        private final CountDownLatch processedLatch;
        private final AtomicInteger processedCount;

        private CountingRaftAppendCallback(CountDownLatch processedLatch, AtomicInteger processedCount) {
            this.processedLatch = processedLatch;
            this.processedCount = processedCount;
        }

        @Override
        public void appendProposal(String walletAddress, String path, String contentType, String message,
                                   String signature) {
            processedCount.incrementAndGet();
            processedLatch.countDown();
        }

        @Override
        public void appendProposal(String walletAddress, String path, String contentType, String message,
                                   String signature, String blobId, String mimeType) {
            appendProposal(walletAddress, path, contentType, message, signature);
        }

        @Override
        public void appendDeleteProposal(String walletAddress, String path, String signature) {
            processedCount.incrementAndGet();
            processedLatch.countDown();
        }

        @Override
        public int appendProposalBatch(java.util.List<QueuedProposal> batch) {
            processedCount.addAndGet(batch.size());
            for (int i = 0; i < batch.size(); i++) {
                processedLatch.countDown();
            }
            return batch.size();
        }
    }

    private static final class CapacityScenario {
        private final String name;
        private final int proposalCount;
        private final int walletCount;
        private final long maxPendingMessages;
        private final long initialPendingDebt;
        private final boolean clearPressureAfterVerification;
        private final long holdAfterVerificationMs;

        private CapacityScenario(String name,
                                 int proposalCount,
                                 int walletCount,
                                 long maxPendingMessages,
                                 long initialPendingDebt,
                                 boolean clearPressureAfterVerification,
                                 long holdAfterVerificationMs) {
            this.name = name;
            this.proposalCount = proposalCount;
            this.walletCount = walletCount;
            this.maxPendingMessages = maxPendingMessages;
            this.initialPendingDebt = initialPendingDebt;
            this.clearPressureAfterVerification = clearPressureAfterVerification;
            this.holdAfterVerificationMs = holdAfterVerificationMs;
        }
    }

    private static final class CapacityScenarioResult {
        private final int proposalCount;
        private volatile int processedCount;
        private volatile long peakVerifiedResidentProposalCount;
        private volatile long peakOverflowProposalCount;
        private volatile long totalBufferedProposalCount;
        private volatile long totalPromotedProposalCount;
        private volatile long startMs;
        private volatile long endMs;
        private volatile long processingDurationMs;
        private volatile long recoveryDurationMs;

        private CapacityScenarioResult(int proposalCount) {
            this.proposalCount = proposalCount;
        }
    }
}
