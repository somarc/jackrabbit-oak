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
package org.apache.jackrabbit.oak.segment.consensus.aeron;

import org.apache.jackrabbit.oak.plugins.memory.MemoryNodeStore;
import org.apache.jackrabbit.oak.segment.consensus.service.AppliedLogPosition;
import org.apache.jackrabbit.oak.segment.consensus.service.BinaryReleaseApplicationService;
import org.apache.jackrabbit.oak.segment.consensus.service.ReplicatedBinaryIndex;
import org.apache.jackrabbit.oak.spi.commit.CommitInfo;
import org.apache.jackrabbit.oak.spi.commit.EmptyHook;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** BINARY_RELEASE through the dispatcher: watermark, replay floor and rejection, as for the other store commands. */
public class BinaryReleaseDispatchTest {

    private static final String RELEASED = "aa".repeat(32);
    private static final String KEPT = "bb".repeat(32);

    private final AeronIngressControlPayloadBuilder controls = new AeronIngressControlPayloadBuilder();

    @Test
    public void aReleaseIsAppliedOnceAndSkippedBelowTheReplayFloor() throws Exception {
        MemoryNodeStore store = new MemoryNodeStore();
        index(store, RELEASED, KEPT);
        BinaryReleaseApplicationService service = new BinaryReleaseApplicationService(() -> store);
        List<BinaryReleaseApplicationService.Result> results = new ArrayList<>();
        MessageDispatcher dispatcher = dispatcher((command, position) -> results.add(service.apply(command, position)));
        AeronEncodedMessage release = controls.buildBinaryRelease(1, 0, List.of(RELEASED));

        assertTrue(dispatch(dispatcher, release, 640));
        assertEquals(List.of(RELEASED), results.get(0).released());
        assertEquals(new AppliedLogPosition(640, 0, 5), AppliedLogPosition.read(store.getRoot()));
        assertFalse(ReplicatedBinaryIndex.contains(store.getRoot(), RELEASED));
        assertTrue(ReplicatedBinaryIndex.contains(store.getRoot(), KEPT));

        MessageDispatcher restarted = dispatcher((command, position) -> results.add(service.apply(command, position)));
        restarted.setReplayFloor(AppliedLogPosition.read(store.getRoot()));
        assertTrue(dispatch(restarted, release, 640));
        assertEquals("replay below the floor skips the release", 1, results.size());
        assertTrue(dispatch(restarted, controls.buildBinaryRelease(1, 0, List.of(KEPT)), 960));
        assertEquals(List.of(KEPT), results.get(1).released());
    }

    @Test
    public void rejectionsSkipTheEntryAndMissingWiringStopsTheMember() {
        MemoryNodeStore store = new MemoryNodeStore();
        BinaryReleaseApplicationService service = new BinaryReleaseApplicationService(() -> store);
        MessageDispatcher unwired = dispatcher(null);
        assertThrows(MessageDispatcher.ReplicatedApplyException.class,
            () -> dispatch(unwired, controls.buildBinaryRelease(1, 0, List.of(RELEASED)), 64));

        MessageDispatcher dispatcher = dispatcher(service::apply);
        assertFalse(dispatch(dispatcher, controls.buildBinaryRelease(1, 0, List.of()), 64));
        assertFalse(dispatch(dispatcher,
            AeronIngressPayloadSupport.encode(SimpleMessageHeader.TEMPLATE_ID_BINARY_RELEASE, "not json"), 64));
        assertTrue(AppliedLogPosition.read(store.getRoot()).isNone());

        MessageDispatcher failing = dispatcher((command, position) -> {
            throw new IllegalStateException("disk full");
        });
        assertThrows(MessageDispatcher.ReplicatedApplyException.class,
            () -> dispatch(failing, controls.buildBinaryRelease(1, 0, List.of(RELEASED)), 64));
    }

    private static MessageDispatcher dispatcher(MessageDispatcher.BinaryReleaseCallback callback) {
        MessageDispatcher dispatcher = new MessageDispatcher();
        dispatcher.setTermProvider(() -> 5L);
        dispatcher.setBinaryReleaseCallback(callback);
        return dispatcher;
    }

    private static boolean dispatch(MessageDispatcher dispatcher, AeronEncodedMessage message, long logPosition) {
        return dispatcher.dispatch(1L, logPosition, message.buffer, 0, message.totalLength);
    }

    private static void index(MemoryNodeStore store, String... identifiers) throws Exception {
        NodeBuilder root = store.getRoot().builder();
        for (String identifier : identifiers) {
            NodeBuilder entry = root.child(":oak-chain-binaries").child(identifier.substring(0, 2))
                .child(identifier.substring(2, 4)).child(identifier);
            entry.setProperty("cid", "Qm" + "a".repeat(44));
        }
        store.merge(root, EmptyHook.INSTANCE, CommitInfo.EMPTY);
    }
}
