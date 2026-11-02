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
package org.apache.jackrabbit.oak.segment.consensus.binarygc;

import org.apache.jackrabbit.oak.plugins.memory.EmptyNodeState;
import org.apache.jackrabbit.oak.segment.consensus.service.AppliedLogPosition;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class BinaryGcCoordinatorTest {

    private static final long DAY = 86_400_000L;
    private static final long NOW = 10 * DAY;
    private static final String UNREFERENCED = "aa".repeat(32);
    private static final String REFERENCED = "bb".repeat(32);
    private static final String YOUNG = "cc".repeat(32);
    private static final String AFTER_MARK = "dd".repeat(32);
    private static final String LEGACY = "ee".repeat(32);

    private final AtomicBoolean leader = new AtomicBoolean(true);
    private final AtomicBoolean accept = new AtomicBoolean(true);
    private final AtomicLong clock = new AtomicLong(NOW);
    private final List<String> proposals = new ArrayList<>();

    @Test
    public void theLeaderProposesOnlyUnreferencedEntriesOlderThanTheGraceAndTheMark() {
        NodeBuilder root = root(1000);
        entry(root, UNREFERENCED, 100, NOW - 2 * DAY);
        entry(root, REFERENCED, 100, NOW - 2 * DAY);
        entry(root, YOUNG, 100, NOW - DAY + 1);
        entry(root, AFTER_MARK, 1000, NOW - 2 * DAY);
        entry(root, LEGACY, -1, -1);
        BinaryGcStatus status = status();
        BinaryGcCoordinator coordinator = coordinator(root.getNodeState(),
            collector -> {
                collector.accept(REFERENCED + "#40000");
                collector.accept("0x0102");
            }, status);

        coordinator.mark();

        assertEquals(List.of("1000|" + (NOW - DAY) + "|" + List.of(UNREFERENCED, LEGACY)), proposals);
        Map<String, Object> mark = lastMark(status);
        assertEquals(NOW, mark.get("at"));
        assertEquals(1000L, mark.get("markedAtPosition"));
        assertEquals(2L, mark.get("references"));
        assertEquals(2L, mark.get("candidates"));
        assertEquals(2L, mark.get("proposed"));
        assertNull(mark.get("error"));
    }

    @Test
    public void onlyTheLeaderMarksAndOnlyWhenAMarkIsDue() {
        NodeBuilder root = root(1000);
        entry(root, UNREFERENCED, 100, 0);
        BinaryGcStatus status = status();
        BinaryGcCoordinator coordinator = coordinator(root.getNodeState(), collector -> { }, status);
        assertEquals("the first mark is due after at most ten minutes", NOW + 600_000L,
            status.toMap().get("nextMarkAt"));

        clock.set(NOW + 600_000L);
        leader.set(false);
        coordinator.tick();
        assertTrue(proposals.isEmpty());

        leader.set(true);
        coordinator.tick();
        assertEquals(1, proposals.size());
        assertEquals(NOW + 600_000L + DAY, status.toMap().get("nextMarkAt"));

        clock.addAndGet(DAY - 1);
        coordinator.tick();
        assertEquals("not due yet", 1, proposals.size());
        clock.addAndGet(1);
        coordinator.tick();
        assertEquals(2, proposals.size());
    }

    @Test
    public void candidatesAreProposedInBoundedBatchesAndAFailedSendStopsTheMark() {
        NodeBuilder root = root(1000);
        for (int i = 0; i < 2500; i++) {
            entry(root, String.format("%064x", i), 1, 0);
        }
        BinaryGcStatus status = status();
        coordinator(root.getNodeState(), collector -> { }, status).mark();
        assertEquals(List.of(1000, 1000, 500), batchSizes());
        assertEquals(2500L, lastMark(status).get("proposed"));

        proposals.clear();
        accept.set(false);
        coordinator(root.getNodeState(), collector -> { }, status).mark();
        assertEquals(List.of(1000), batchSizes());
        assertEquals(0L, lastMark(status).get("proposed"));
        assertEquals(2500L, lastMark(status).get("candidates"));
        assertTrue(String.valueOf(lastMark(status).get("error")).contains("not sent"));
    }

    @Test
    public void nothingIsProposedWithoutAppliedEntriesOrWhenReferencesCannotBeCollected() {
        NodeBuilder fresh = EmptyNodeState.EMPTY_NODE.builder();
        entry(fresh, UNREFERENCED, 0, 0);
        BinaryGcStatus status = status();
        coordinator(fresh.getNodeState(), collector -> { }, status).mark();
        assertEquals("nothing applied yet", lastMark(status).get("error"));

        NodeBuilder root = root(1000);
        entry(root, UNREFERENCED, 1, 0);
        coordinator(root.getNodeState(), collector -> {
            collector.accept(REFERENCED);
            throw new IOException("tar file gone");
        }, status).mark();
        assertTrue(proposals.isEmpty());
        assertEquals(1L, lastMark(status).get("references"));
        assertTrue(String.valueOf(lastMark(status).get("error")).contains("tar file gone"));
    }

    @Test
    public void startAndCloseRunTheMarkThread() {
        BinaryGcCoordinator coordinator = coordinator(EmptyNodeState.EMPTY_NODE, collector -> { }, status());
        coordinator.start();
        coordinator.start();
        coordinator.close();
        coordinator.close();
    }

    private BinaryGcCoordinator coordinator(NodeState root, BinaryGcCoordinator.ReferenceSource references,
                                            BinaryGcStatus status) {
        return new BinaryGcCoordinator(new BinaryGcConfig(true, DAY, DAY, 0), () -> root, references,
            new BinaryGcCoordinator.Proposer() {
                @Override
                public boolean canPropose() {
                    return leader.get();
                }

                @Override
                public boolean propose(long markedAtPosition, long maxLastModified, List<String> blobIds) {
                    proposals.add(markedAtPosition + "|" + maxLastModified + "|" + blobIds);
                    return accept.get();
                }
            }, clock::get, status);
    }

    private List<Integer> batchSizes() {
        List<Integer> sizes = new ArrayList<>();
        for (String proposal : proposals) {
            sizes.add(proposal.split(",").length);
        }
        return sizes;
    }

    private static BinaryGcStatus status() {
        return new BinaryGcStatus(new BinaryGcConfig(true, DAY, DAY, 0), false);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> lastMark(BinaryGcStatus status) {
        return (Map<String, Object>) status.toMap().get("lastMark");
    }

    private static NodeBuilder root(long appliedPosition) {
        NodeBuilder root = EmptyNodeState.EMPTY_NODE.builder();
        new AppliedLogPosition(appliedPosition, 0, 1).writeTo(root);
        return root;
    }

    /** Negative values leave the stamp out, as for entries written before stamps existed. */
    private static void entry(NodeBuilder root, String identifier, long refPosition, long refTime) {
        NodeBuilder entry = root.child(":oak-chain-binaries").child(identifier.substring(0, 2))
            .child(identifier.substring(2, 4)).child(identifier);
        entry.setProperty("cid", "Qm" + "a".repeat(44));
        if (refPosition >= 0) {
            entry.setProperty("refPosition", refPosition);
        }
        if (refTime >= 0) {
            entry.setProperty("refTime", refTime);
        }
    }
}
