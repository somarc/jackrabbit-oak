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
package org.apache.jackrabbit.oak.segment.consensus.service;

import org.apache.jackrabbit.oak.plugins.memory.MemoryNodeStore;
import org.apache.jackrabbit.oak.segment.consensus.validation.MutationRejectedException;
import org.apache.jackrabbit.oak.spi.commit.CommitInfo;
import org.apache.jackrabbit.oak.spi.commit.EmptyHook;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

public class BinaryReleaseApplicationServiceTest {

    private static final String OLD = "aa".repeat(32);
    private static final String YOUNG = "bb".repeat(32);
    private static final String LEGACY = "cc".repeat(32);
    private static final String ABSENT = "dd".repeat(32);

    @Test
    public void releasesOnlyEntriesUnreferencedSinceTheMarkAndRecordsTheWatermark() throws Exception {
        MemoryNodeStore first = indexed();
        MemoryNodeStore second = indexed();
        Map<String, Object> command = command(500, 42, List.of(OLD, YOUNG, LEGACY, ABSENT, OLD));
        AppliedLogPosition position = new AppliedLogPosition(800, 0, 3);

        BinaryReleaseApplicationService.Result result =
            new BinaryReleaseApplicationService(() -> first).apply(command, position);
        new BinaryReleaseApplicationService(() -> second).apply(command, position);

        assertEquals(List.of(OLD, LEGACY), result.released());
        assertEquals("referenced at 500 and absent", 2, result.skipped());
        assertEquals(500, result.markedAtPosition());
        assertEquals(42, result.maxLastModified());
        assertEquals(position, result.position());
        assertEquals(Map.of(YOUNG, "500@5"), BinaryIndexStampTest.entries(first));
        assertEquals(first.getRoot().getChildNode(ReplicatedBinaryIndex.ROOT),
            second.getRoot().getChildNode(ReplicatedBinaryIndex.ROOT));
        assertEquals(position, AppliedLogPosition.read(first.getRoot()));
    }

    @Test
    public void replayingAReleaseChangesNothingMore() throws Exception {
        MemoryNodeStore store = indexed();
        BinaryReleaseApplicationService service = new BinaryReleaseApplicationService(() -> store);
        Map<String, Object> command = command(500, 0, List.of(OLD, YOUNG));
        service.apply(command, new AppliedLogPosition(800, 0, 3));
        Map<String, String> after = BinaryIndexStampTest.entries(store);

        BinaryReleaseApplicationService.Result again = service.apply(command, null);

        assertEquals(List.of(), again.released());
        assertEquals(2, again.skipped());
        assertEquals(after, BinaryIndexStampTest.entries(store));
        assertNull(again.position());
    }

    @Test
    public void malformedReleasesAreRejectedOnEveryMember() throws Exception {
        MemoryNodeStore store = indexed();
        BinaryReleaseApplicationService service = new BinaryReleaseApplicationService(() -> store);
        List<String> tooMany = new ArrayList<>(Collections.nCopies(BinaryReleaseApplicationService.MAX_BLOB_IDS + 1,
            OLD));
        for (Map<String, Object> command : List.<Map<String, Object>>of(
                command(-1, 0, List.of(OLD)),
                command(5, -1, List.of(OLD)),
                command(5, 0, List.of()),
                command(5, 0, tooMany),
                command(5, 0, List.of(OLD, "../" + OLD)),
                command(5, 0, List.of(OLD.toUpperCase())),
                Map.of("markedAtPosition", 5L),
                Map.of("markedAtPosition", "5", "blobIds", List.of(OLD)),
                Map.of("markedAtPosition", 5L, "blobIds", List.of(7L)))) {
            assertThrows(command.toString(), MutationRejectedException.class, () -> service.apply(command, null));
        }
        assertEquals(3, BinaryIndexStampTest.entries(store).size());
    }

    @Test
    public void maxLastModifiedIsOptional() throws Exception {
        MemoryNodeStore store = indexed();
        Map<String, Object> command = new LinkedHashMap<>();
        command.put("markedAtPosition", 500L);
        command.put("blobIds", List.of(OLD));
        assertEquals(0, new BinaryReleaseApplicationService(() -> store).apply(command, null).maxLastModified());
    }

    private static Map<String, Object> command(long markedAtPosition, long maxLastModified, List<String> ids) {
        Map<String, Object> command = new LinkedHashMap<>();
        command.put("markedAtPosition", markedAtPosition);
        command.put("maxLastModified", maxLastModified);
        command.put("blobIds", new ArrayList<Object>(ids));
        return command;
    }

    /** OLD referenced at 100, YOUNG at 500 (the mark), LEGACY written before stamps existed. */
    private static MemoryNodeStore indexed() throws Exception {
        MemoryNodeStore store = new MemoryNodeStore();
        NodeBuilder root = store.getRoot().builder();
        ReplicatedBinaryIndex.record(root, OLD + "#1", "Qm" + "a".repeat(44));
        ReplicatedBinaryIndex.stamp(root, OLD, new AppliedLogPosition(100, 0, 1), 1L);
        ReplicatedBinaryIndex.record(root, YOUNG + "#1", "Qm" + "b".repeat(44));
        ReplicatedBinaryIndex.stamp(root, YOUNG, new AppliedLogPosition(500, 0, 1), 5L);
        ReplicatedBinaryIndex.record(root, LEGACY + "#1", "Qm" + "c".repeat(44));
        store.merge(root, EmptyHook.INSTANCE, CommitInfo.EMPTY);
        return store;
    }
}
