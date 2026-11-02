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

import org.apache.jackrabbit.oak.api.CommitFailedException;
import org.apache.jackrabbit.oak.plugins.memory.MemoryNodeStore;
import org.apache.jackrabbit.oak.segment.consensus.aeron.ReplayIdempotencyTest.Entry;
import org.apache.jackrabbit.oak.segment.consensus.aeron.ReplayIdempotencyTest.Member;
import org.apache.jackrabbit.oak.segment.consensus.queue.QueuedProposal;
import org.apache.jackrabbit.oak.segment.consensus.service.AppliedLogPosition;
import org.apache.jackrabbit.oak.segment.consensus.service.AppliedProposalIndex;
import org.apache.jackrabbit.oak.segment.consensus.service.MutationAuditMetadata;
import org.apache.jackrabbit.oak.spi.commit.CommitInfo;
import org.apache.jackrabbit.oak.spi.commit.EmptyHook;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * Resends of one proposal id reach the log as separate entries or batch items; every member applies the first and
 * ignores the rest, in log order.
 */
public class OneEffectPerProposalTest {

    private static final String WALLET = "0x1234567890abcdef1234567890abcdef12345678";
    private static final String CONTENT = "/oak-chain/12/34/56/" + WALLET + "/Acme/content";
    private static final String PROPOSAL = "0x85b28694" + "ab".repeat(28);
    private static final int TERM = 7;
    private static final AeronIngressWritePayloadBuilder BUILDER = new AeronIngressWritePayloadBuilder();

    @Test
    public void identicalProposalsBackToBackInOneBatchHaveOneEffectOnEveryMember() {
        List<QueuedProposal> items = Arrays.asList(
            item(PROPOSAL, CONTENT + "/page-1793586396590-85b28694"),
            item(PROPOSAL, CONTENT + "/page-1793586397606-85b28694"));
        List<Entry> log = Arrays.asList(new Entry(100, 1_000L, BUILDER.buildWriteBatch(items, TERM)));
        MemoryNodeStore memberA = new MemoryNodeStore();
        MemoryNodeStore memberB = new MemoryNodeStore();

        new Member(memberA).apply(log);
        new Member(memberB).apply(log);

        assertEquals(Arrays.asList("page-1793586396590-85b28694"), children(memberA, CONTENT));
        assertEquals(memberA.getRoot(), memberB.getRoot());
        assertEquals("the duplicate item merged nothing, so the watermark stays at the first",
            new AppliedLogPosition(100, 0, TERM), AppliedLogPosition.read(memberA.getRoot()));
    }

    @Test
    public void concurrentResendInALaterEntryHasNoEffect() {
        List<Entry> log = Arrays.asList(
            new Entry(100, 1_000L, write(CONTENT + "/page-1-85b28694", PROPOSAL)),
            new Entry(200, 2_000L, write(CONTENT + "/page-2-85b28694", PROPOSAL)),
            new Entry(300, 3_000L, BUILDER.buildDeleteProposal(WALLET, CONTENT + "/page-1-85b28694", "sig", TERM,
                MutationAuditMetadata.delete(null, null, PROPOSAL, null, null, null, null))));
        MemoryNodeStore store = new MemoryNodeStore();

        new Member(store).apply(log);

        assertEquals(Arrays.asList("page-1-85b28694"), children(store, CONTENT));
        assertEquals(CONTENT + "/page-1-85b28694", AppliedProposalIndex.find(store.getRoot(), PROPOSAL).path());
    }

    @Test
    public void replayOfALogWrittenBeforeTheIndexKeepsTheSamePathDedupAndLoads() throws CommitFailedException {
        Entry first = new Entry(100, 1_000L, write(CONTENT + "/page-1-85b28694", PROPOSAL));
        MemoryNodeStore store = new MemoryNodeStore();
        new Member(store).apply(Arrays.asList(first));
        olderBuildStore(store);
        NodeState before = store.getRoot();
        assertNull(AppliedProposalIndex.find(before, PROPOSAL));

        Member restarted = new Member(store).restart();
        restarted.apply(Arrays.asList(first));

        assertEquals("the entry is offered again: the older store has no watermark",
            Arrays.asList(CONTENT + "/page-1-85b28694"), restarted.applies);
        assertEquals("the node at the same path with the same id is acknowledged, not rewritten",
            before, store.getRoot());

        String next = "0x01" + "23".repeat(31);
        restarted.apply(Arrays.asList(new Entry(200, 2_000L, write(CONTENT + "/page-2-01232323", next))));
        assertEquals(Arrays.asList("page-1-85b28694", "page-2-01232323"), children(store, CONTENT));
        assertNotNull(AppliedProposalIndex.find(store.getRoot(), next));
    }

    /** What a build without the index or the watermark leaves: the content node and nothing under :consensus. */
    private static void olderBuildStore(MemoryNodeStore store) throws CommitFailedException {
        NodeBuilder root = store.getRoot().builder();
        root.getChildNode(AppliedLogPosition.NODE_NAME).remove();
        store.merge(root, EmptyHook.INSTANCE, CommitInfo.EMPTY);
    }

    private static AeronEncodedMessage write(String path, String proposalId) {
        return BUILDER.buildWriteProposal(WALLET, path, "page", "one", "sig", TERM, null,
            MutationAuditMetadata.write(null, null, proposalId, null, null, null, null));
    }

    private static QueuedProposal item(String proposalId, String path) {
        return ReplayIdempotencyTest.batchItem(proposalId, path);
    }

    private static List<String> children(MemoryNodeStore store, String path) {
        NodeState current = store.getRoot();
        for (String part : path.substring(1).split("/")) {
            current = current.getChildNode(part);
        }
        List<String> names = new ArrayList<>();
        current.getChildNodeNames().forEach(names::add);
        return names;
    }
}
