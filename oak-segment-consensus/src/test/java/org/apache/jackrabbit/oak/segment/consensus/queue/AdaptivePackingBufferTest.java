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

import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AdaptivePackingBufferTest {

    @Test
    public void testHealthyDrainWaitsForHoldWindowAndSortsByPath() {
        AdaptivePackingBuffer buffer = new AdaptivePackingBuffer();
        buffer.addProposal(buildProposal("proposal-b", "0xwallet-a", "/oak-chain/aa/bb/cc/0xwallet-a/content/z-path", 20L), 1_000L);
        buffer.addProposal(buildProposal("proposal-a", "0xwallet-a", "/oak-chain/aa/bb/cc/0xwallet-a/content/a-path", 10L), 1_000L);

        assertTrue(buffer.drainReadyBatches(1_024L, AdaptiveReleaseGovernor.Decision.healthyDirect()).isEmpty());

        List<List<QueuedProposal>> batches = buffer.drainReadyBatches(1_025L, AdaptiveReleaseGovernor.Decision.healthyDirect());

        assertEquals(1, batches.size());
        assertEquals(2, batches.get(0).size());
        assertEquals("proposal-a", batches.get(0).get(0).getProposalId());
        assertEquals("proposal-b", batches.get(0).get(1).getProposalId());

        Map<String, Object> stats = buffer.getStatsMap();
        assertEquals(0L, ((Number) stats.get("pendingProposals")).longValue());
        assertEquals(2L, ((Number) stats.get("totalProposalsDrained")).longValue());
        assertEquals(1L, ((Number) stats.get("totalBatchesCreated")).longValue());
    }

    @Test
    public void testPressuredDrainLimitsWalletsPerCycle() {
        AdaptivePackingBuffer buffer = new AdaptivePackingBuffer();
        for (int i = 0; i < 5; i++) {
            String wallet = "0xwallet-" + i;
            buffer.addProposal(buildProposal("proposal-" + i, wallet,
                "/oak-chain/aa/bb/cc/" + wallet + "/content/item-" + i, i), 1_000L);
        }

        AdaptiveReleaseGovernor.Decision decision = new AdaptiveReleaseGovernor.Decision(
            AdaptiveReleaseGovernor.GovernorState.PRESSURED,
            AdaptiveReleaseGovernor.ReleaseAction.BUFFERED,
            Collections.singletonList("verified_finalized_gap_high")
        );
        List<List<QueuedProposal>> batches = buffer.drainReadyBatches(1_200L, decision);

        assertEquals(4, batches.size());
        for (List<QueuedProposal> batch : batches) {
            assertEquals(1, batch.size());
        }

        Map<String, Object> stats = buffer.getStatsMap();
        assertEquals(1L, ((Number) stats.get("pendingProposals")).longValue());
        assertEquals(4L, ((Number) stats.get("totalProposalsDrained")).longValue());
    }

    @Test
    public void testOverloadedDrainThrottlesLargeWalletBatches() {
        AdaptivePackingBuffer buffer = new AdaptivePackingBuffer();
        String wallet = "0xwallet-throttled";
        for (int i = 0; i < 30; i++) {
            buffer.addProposal(buildProposal("proposal-" + i, wallet,
                "/oak-chain/aa/bb/cc/" + wallet + "/content/item-" + String.format("%02d", i), i), 1_000L);
        }

        AdaptiveReleaseGovernor.Decision decision = new AdaptiveReleaseGovernor.Decision(
            AdaptiveReleaseGovernor.GovernorState.OVERLOADED,
            AdaptiveReleaseGovernor.ReleaseAction.THROTTLED,
            Collections.singletonList("backpressure_active")
        );

        assertTrue(buffer.drainReadyBatches(1_300L, decision).isEmpty());

        List<List<QueuedProposal>> firstDrain = buffer.drainReadyBatches(1_600L, decision);
        assertEquals(1, firstDrain.size());
        assertEquals(25, firstDrain.get(0).size());
        assertEquals(5L, ((Number) buffer.getStatsMap().get("pendingProposals")).longValue());

        List<List<QueuedProposal>> secondDrain = buffer.drainReadyBatches(1_700L, decision);
        assertEquals(1, secondDrain.size());
        assertEquals(5, secondDrain.get(0).size());
        assertEquals(0L, ((Number) buffer.getStatsMap().get("pendingProposals")).longValue());
    }

    @Test
    public void testOverloadedDrainPromotesForcedResidencyBeforeNewerWallets() {
        AdaptivePackingBuffer buffer = new AdaptivePackingBuffer();
        buffer.addProposal(buildProposal("forced-cold", "0xwallet-cold",
            "/oak-chain/aa/bb/cc/0xwallet-cold/content/oldest", 1L), 0L);
        buffer.addProposal(buildProposal("hot-a", "0xwallet-hot-a",
            "/oak-chain/aa/bb/cc/0xwallet-hot-a/content/item-a", 2L), 1_400L);
        buffer.addProposal(buildProposal("hot-b", "0xwallet-hot-b",
            "/oak-chain/aa/bb/cc/0xwallet-hot-b/content/item-b", 3L), 1_450L);

        AdaptiveReleaseGovernor.Decision decision = new AdaptiveReleaseGovernor.Decision(
            AdaptiveReleaseGovernor.GovernorState.OVERLOADED,
            AdaptiveReleaseGovernor.ReleaseAction.THROTTLED,
            Collections.singletonList("backpressure_active")
        );

        List<List<QueuedProposal>> batches = buffer.drainReadyBatches(2_200L, decision);

        assertEquals(2, batches.size());
        assertEquals("forced-cold", batches.get(0).get(0).getProposalId());
        assertEquals(1L, ((Number) buffer.getStatsMap().get("pendingProposals")).longValue());
    }

    private static QueuedProposal buildProposal(String proposalId, String wallet, String path, long timestamp) {
        QueuedProposal proposal = new QueuedProposal(
            proposalId,
            "0xtx-" + proposalId,
            null,
            timestamp,
            timestamp + 30_000L,
            ProposalState.VERIFIED
        );
        proposal.setWalletAddress(wallet);
        proposal.setPath(path);
        proposal.setEpoch(1L);
        return proposal;
    }
}
