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

import org.apache.jackrabbit.oak.segment.consensus.economics.ValidatorEarningsTracker;
import org.apache.jackrabbit.oak.segment.consensus.eth.BeaconChainClient;
import org.apache.jackrabbit.oak.segment.consensus.evm.EvmBridge;
import org.junit.After;
import org.junit.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ProposalQueueManagerReportingTest {

    private static final String WALLET = "0x742d35cc6634c0532925a3b844bc9e7595f0beb0";

    @After
    public void tearDown() {
        System.clearProperty("oak.proposal.counter.rotation.ms");
    }

    @Test
    public void getProposalReleaseFlowStatsExposesAdaptiveContractSnapshot() {
        BeaconChainClient beaconClient = beaconClient(9L, 7L);
        ProposalQueueManagerOptimized queueManager = createQueueManager(beaconClient);
        try {
            queueManager.queueProposal(
                "release-flow-001",
                "0xtx-release-flow-001",
                WALLET,
                walletPath("release-flow"),
                "page",
                "release flow payload",
                "0xsig-release-flow",
                ValidatorEarningsTracker.PaymentTier.STANDARD,
                null
            );

            Map<String, Object> payload = queueManager.getProposalReleaseFlowStats();

            assertEquals("proposal.release-flow.v1", payload.get("contractVersion"));
            assertEquals("adaptive-release", payload.get("source"));
            assertEquals("adaptive-capacity", payload.get("schedulerModel"));
            assertEquals(9L, longValue(payload.get("currentEpoch")));
            assertEquals(7L, longValue(payload.get("finalizedEpoch")));
            assertEquals(2L, longValue(payload.get("epochsUntilFinality")));

            Map<String, Object> releaseStages = mapValue(payload.get("releaseStages"));
            assertEquals(1L, longValue(releaseStages.get("unverifiedMempoolCount")));
            assertEquals(0L, longValue(releaseStages.get("releaseReadyProposalCount")));
            assertEquals(0L, longValue(releaseStages.get("backpressureOverflowProposalCount")));

            Map<String, Object> governor = mapValue(payload.get("governor"));
            assertTrue(governor.containsKey("state"));
            assertTrue(governor.containsKey("action"));
            assertTrue(governor.containsKey("reasonCodes"));
            assertTrue(!payload.containsKey("epochCompatibility"));
        } finally {
            queueManager.stop();
        }
    }

    @Test
    public void getStatsIncludesAdaptiveAndOverflowSummaries() {
        BeaconChainClient beaconClient = beaconClient(11L, 8L);
        ProposalQueueManagerOptimized queueManager = createQueueManager(beaconClient);
        try {
            String stats = queueManager.getStats();

            assertTrue(stats.contains("Unverified: 0"));
            assertTrue(stats.contains("Adaptive:"));
            assertTrue(stats.contains("Overflow Buffer:"));
        } finally {
            queueManager.stop();
        }
    }

    @Test
    public void counterRotationPersistsLifetimeTotalsAcrossRestart() throws Exception {
        System.setProperty("oak.proposal.counter.rotation.ms", "1");
        ProposalQueueTuning tuning = ProposalQueueTuning.fromSystemProperties();
        Path persistenceDir = Files.createTempDirectory("proposal-queue-counters");
        BeaconChainClient beaconClient = beaconClient(6L, 4L);

        ProposalQueueManagerOptimized queueManager = new ProposalQueueManagerOptimized(
            mock(EvmBridge.class),
            new NoopRaftAppendCallback(),
            new BackpressureManager(),
            beaconClient,
            persistenceDir.toString(),
            tuning
        );
        try {
            setAtomicLongField(queueManager, "totalVerifiedCount", 5L);
            setAtomicLongField(queueManager, "totalFinalizedCount", 3L);
            setAtomicLongField(queueManager, "totalRejectedCount", 2L);
            setAtomicLongField(queueManager, "priorityProposalsSent", 1L);
            setAtomicLongField(queueManager, "batchedProposalsSent", 4L);
            setAtomicLongField(queueManager, "counterWindowStartMs", System.currentTimeMillis() - 10_000L);

            Map<String, Object> rotated = queueManager.getQueueStats();

            assertEquals(0L, longValue(rotated.get("totalVerifiedCount")));
            assertEquals(0L, longValue(rotated.get("totalFinalizedCount")));
            assertEquals(0L, longValue(rotated.get("totalRejectedCount")));
            assertEquals(5L, longValue(rotated.get("totalVerifiedCountLifetime")));
            assertEquals(3L, longValue(rotated.get("totalFinalizedCountLifetime")));
            assertEquals(2L, longValue(rotated.get("totalRejectedCountLifetime")));
            assertEquals(1L, longValue(rotated.get("priorityProposalsSentLifetime")));
            assertEquals(4L, longValue(rotated.get("batchedProposalsSentLifetime")));
        } finally {
            queueManager.stop();
        }

        ProposalQueueManagerOptimized restored = new ProposalQueueManagerOptimized(
            mock(EvmBridge.class),
            new NoopRaftAppendCallback(),
            new BackpressureManager(),
            beaconClient,
            persistenceDir.toString(),
            tuning
        );
        try {
            Map<String, Object> restoredStats = restored.getQueueStats();

            assertEquals(5L, longValue(restoredStats.get("totalVerifiedCountLifetime")));
            assertEquals(3L, longValue(restoredStats.get("totalFinalizedCountLifetime")));
            assertEquals(2L, longValue(restoredStats.get("totalRejectedCountLifetime")));
            assertEquals(1L, longValue(restoredStats.get("priorityProposalsSentLifetime")));
            assertEquals(4L, longValue(restoredStats.get("batchedProposalsSentLifetime")));
        } finally {
            restored.stop();
        }
    }

    private static ProposalQueueManagerOptimized createQueueManager(BeaconChainClient beaconClient) {
        return new ProposalQueueManagerOptimized(
            mock(EvmBridge.class),
            new NoopRaftAppendCallback(),
            new BackpressureManager(),
            beaconClient
        );
    }

    private static BeaconChainClient beaconClient(long currentEpoch, long finalizedEpoch) {
        BeaconChainClient beaconClient = mock(BeaconChainClient.class);
        when(beaconClient.getCachedCurrentEpoch()).thenReturn(currentEpoch);
        when(beaconClient.getCachedFinalizedEpoch()).thenReturn(finalizedEpoch);
        return beaconClient;
    }

    private static String walletPath(String leaf) {
        return "/oak-chain/74/2d/35/" + WALLET + "/content/" + leaf;
    }

    private static void setAtomicLongField(Object target, String fieldName, long value) throws Exception {
        Field field = ProposalQueueManagerOptimized.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        ((AtomicLong) field.get(target)).set(value);
    }

    @SuppressWarnings("unchecked")
    private static void putTerminalCounter(ProposalQueueManagerOptimized queueManager,
                                           String fieldName,
                                           long epoch,
                                           String tier,
                                           long value) throws Exception {
        Field field = ProposalQueueManagerOptimized.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        ConcurrentHashMap<Long, ConcurrentHashMap<String, AtomicLong>> store =
            (ConcurrentHashMap<Long, ConcurrentHashMap<String, AtomicLong>>) field.get(queueManager);
        store.computeIfAbsent(epoch, ignored -> new ConcurrentHashMap<>())
            .put(tier, new AtomicLong(value));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapValue(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listValue(Object value) {
        return (List<Map<String, Object>>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Long>> nestedCountMap(Object value) {
        return (Map<String, Map<String, Long>>) value;
    }

    private static Map<String, Object> findBlock(List<Map<String, Object>> blocks, String status) {
        for (Map<String, Object> block : blocks) {
            if (status.equals(block.get("status"))) {
                return block;
            }
        }
        throw new AssertionError("Missing block with status " + status);
    }

    private static long longValue(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    private static final class NoopRaftAppendCallback implements RaftAppendCallback {
        @Override
        public void appendProposal(String walletAddress, String path, String contentType, String message, String signature) {
        }

        @Override
        public void appendProposal(String walletAddress, String path, String contentType,
                                   String message, String signature, String blobId, String mimeType) {
        }

        @Override
        public void appendDeleteProposal(String walletAddress, String path, String signature) {
        }
    }
}
