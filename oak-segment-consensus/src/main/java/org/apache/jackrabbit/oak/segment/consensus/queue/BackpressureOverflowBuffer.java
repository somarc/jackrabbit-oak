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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

final class BackpressureOverflowBuffer {

    private final ConcurrentLinkedQueue<List<QueuedProposal>> overflowBatches =
        new ConcurrentLinkedQueue<List<QueuedProposal>>();
    private final java.util.concurrent.atomic.AtomicLong pendingProposalCount =
        new java.util.concurrent.atomic.AtomicLong(0L);
    private final java.util.concurrent.atomic.AtomicLong totalBatchesBuffered =
        new java.util.concurrent.atomic.AtomicLong(0L);
    private final java.util.concurrent.atomic.AtomicLong totalProposalsBuffered =
        new java.util.concurrent.atomic.AtomicLong(0L);
    private final java.util.concurrent.atomic.AtomicLong totalBatchesPromoted =
        new java.util.concurrent.atomic.AtomicLong(0L);
    private final java.util.concurrent.atomic.AtomicLong totalProposalsPromoted =
        new java.util.concurrent.atomic.AtomicLong(0L);

    void bufferBatch(List<QueuedProposal> batch) {
        if (batch == null || batch.isEmpty()) {
            return;
        }

        List<QueuedProposal> snapshot = new ArrayList<QueuedProposal>(batch);
        overflowBatches.offer(snapshot);
        pendingProposalCount.addAndGet(snapshot.size());
        totalBatchesBuffered.incrementAndGet();
        totalProposalsBuffered.addAndGet(snapshot.size());
    }

    int promoteTo(ConcurrentLinkedQueue<List<QueuedProposal>> targetQueue,
                  int maxBatches,
                  int maxTargetBatches) {
        if (targetQueue == null || maxBatches <= 0) {
            return 0;
        }

        int promoted = 0;
        while (promoted < maxBatches && targetQueue.size() < maxTargetBatches) {
            List<QueuedProposal> batch = overflowBatches.poll();
            if (batch == null) {
                break;
            }
            targetQueue.offer(batch);
            promoted++;
            pendingProposalCount.addAndGet(-batch.size());
            totalBatchesPromoted.incrementAndGet();
            totalProposalsPromoted.addAndGet(batch.size());
        }
        return promoted;
    }

    boolean isEmpty() {
        return overflowBatches.isEmpty();
    }

    int getPendingBatchCount() {
        return overflowBatches.size();
    }

    long getPendingProposalCount() {
        return pendingProposalCount.get();
    }

    Map<String, Object> getStatsMap() {
        Map<String, Object> stats = new HashMap<String, Object>();
        stats.put("pendingBatches", overflowBatches.size());
        stats.put("pendingProposals", pendingProposalCount.get());
        stats.put("totalBatchesBuffered", totalBatchesBuffered.get());
        stats.put("totalProposalsBuffered", totalProposalsBuffered.get());
        stats.put("totalBatchesPromoted", totalBatchesPromoted.get());
        stats.put("totalProposalsPromoted", totalProposalsPromoted.get());
        return stats;
    }

    String getStats() {
        Map<String, Object> stats = getStatsMap();
        return String.format(
            "Pending Batches: %d, Pending Proposals: %d, Buffered Batches: %d, Buffered Proposals: %d, Promoted Batches: %d, Promoted Proposals: %d",
            stats.get("pendingBatches"),
            stats.get("pendingProposals"),
            stats.get("totalBatchesBuffered"),
            stats.get("totalProposalsBuffered"),
            stats.get("totalBatchesPromoted"),
            stats.get("totalProposalsPromoted")
        );
    }
}
