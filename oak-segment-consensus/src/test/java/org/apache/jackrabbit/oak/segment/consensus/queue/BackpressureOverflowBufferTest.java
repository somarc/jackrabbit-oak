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

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class BackpressureOverflowBufferTest {

    @Test
    public void testBufferAndPromoteTracksPendingCounts() {
        BackpressureOverflowBuffer buffer = new BackpressureOverflowBuffer();
        ConcurrentLinkedQueue<List<QueuedProposal>> target = new ConcurrentLinkedQueue<List<QueuedProposal>>();

        buffer.bufferBatch(Arrays.asList(
            buildProposal("proposal-a", 10L),
            buildProposal("proposal-b", 11L)
        ));
        buffer.bufferBatch(Collections.singletonList(buildProposal("proposal-c", 12L)));

        assertEquals(2, buffer.getPendingBatchCount());
        assertEquals(3L, buffer.getPendingProposalCount());

        int promoted = buffer.promoteTo(target, 1, 4);

        assertEquals(1, promoted);
        assertEquals(1, target.size());
        assertEquals(1, buffer.getPendingBatchCount());
        assertEquals(1L, buffer.getPendingProposalCount());

        Map<String, Object> stats = buffer.getStatsMap();
        assertEquals(2L, ((Number) stats.get("totalBatchesBuffered")).longValue());
        assertEquals(3L, ((Number) stats.get("totalProposalsBuffered")).longValue());
        assertEquals(1L, ((Number) stats.get("totalBatchesPromoted")).longValue());
        assertEquals(2L, ((Number) stats.get("totalProposalsPromoted")).longValue());
    }

    @Test
    public void testPromoteHonorsTargetQueueLimit() {
        BackpressureOverflowBuffer buffer = new BackpressureOverflowBuffer();
        ConcurrentLinkedQueue<List<QueuedProposal>> target = new ConcurrentLinkedQueue<List<QueuedProposal>>();

        buffer.bufferBatch(Collections.singletonList(buildProposal("proposal-a", 10L)));
        target.offer(Collections.singletonList(buildProposal("existing", 9L)));

        int promoted = buffer.promoteTo(target, 2, 1);

        assertEquals(0, promoted);
        assertEquals(1, buffer.getPendingBatchCount());
        assertEquals(1L, buffer.getPendingProposalCount());
        assertEquals(1, target.size());
        assertTrue(buffer.getStats().contains("Pending Batches: 1"));
    }

    private static QueuedProposal buildProposal(String proposalId, long timestamp) {
        QueuedProposal proposal = new QueuedProposal(
            proposalId,
            "0xtx-" + proposalId,
            null,
            timestamp,
            timestamp + 30_000L,
            ProposalState.VERIFIED
        );
        proposal.setWalletAddress("0x742d35cc6634c0532925a3b844bc9e7595f0beb0");
        proposal.setPath("/oak-chain/74/2d/35/0x742d35cc6634c0532925a3b844bc9e7595f0beb0/content/" + proposalId);
        proposal.setEpoch(1L);
        return proposal;
    }
}
