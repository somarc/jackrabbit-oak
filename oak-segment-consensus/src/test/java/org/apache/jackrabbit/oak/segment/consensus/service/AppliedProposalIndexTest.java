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

import org.apache.jackrabbit.oak.api.Type;
import org.apache.jackrabbit.oak.plugins.memory.MemoryNodeStore;
import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A client proposal id takes effect once, whatever path, content type or organization a resend carries.
 */
public class AppliedProposalIndexTest {

    private static final String WALLET = "0x1234567890abcdef1234567890abcdef12345678";
    private static final String WALLET_PATH = "/oak-chain/12/34/56/" + WALLET;
    private static final String PROPOSAL = "0x85b28694" + "ab".repeat(28);
    private static final String OTHER_PROPOSAL = "0x77" + "cd".repeat(31);

    private final MemoryNodeStore store = new MemoryNodeStore();
    private final List<String> durable = new ArrayList<>();
    private final List<String> heads = new ArrayList<>();
    private final WriteApplicationService writes;
    private final DeleteApplicationService deletes;

    public AppliedProposalIndexTest() {
        FileStore fileStore = mock(FileStore.class, RETURNS_DEEP_STUBS);
        when(fileStore.getHead().getRecordId().toString10()).thenReturn("head");
        FileStoreFlushService flush = mock(FileStoreFlushService.class);
        doAnswer(invocation -> {
            Runnable callback = invocation.getArgument(0);
            if (callback != null) {
                callback.run();
            }
            return true;
        }).when(flush).onChangeApplied(any());
        WriteApplicationService.DurabilityCallback callback = new WriteApplicationService.DurabilityCallback() {
            @Override
            public void onDurable(String proposalId, String durableHead) {
                durable.add(proposalId);
            }

            @Override
            public void onFailure(String proposalId, String error) {
                durable.add("failed:" + proposalId);
            }
        };
        writes = new WriteApplicationService(fileStore, () -> store, () -> null, flush);
        writes.setDurabilityCallback(callback);
        writes.setHeadUpdateCallback(heads::add);
        deletes = new DeleteApplicationService(fileStore, () -> store, flush);
        deletes.setDurabilityCallback(callback);
    }

    @Test
    public void writeResentAfterCommitMutatesNothingAndStillReportsDurability() {
        // The leader names each resend afresh, as in the F1-05 evidence
        write(contentPath("acme", "page-1793586396590-85b28694"), "page", PROPOSAL, 1_000L);
        NodeState afterFirst = store.getRoot();

        String head = write(contentPath("acme", "page-1793586397606-85b28694"), "page", PROPOSAL, 2_000L);

        assertEquals("head", head);
        assertEquals(afterFirst, store.getRoot());
        assertEquals(1, contentNodes("acme").size());
        assertEquals(1L, walletLong("totalWrites"));
        assertEquals("both applies report durability; the engine skips what the log already holds",
            List.of(PROPOSAL, PROPOSAL), durable);
        assertEquals(List.of("head", "head"), heads);

        AppliedProposalIndex.Entry entry = AppliedProposalIndex.find(store.getRoot(), PROPOSAL);
        assertNotNull(entry);
        assertEquals("WRITE", entry.kind());
        assertEquals(contentPath("acme", "page-1793586396590-85b28694"), entry.path());
        assertEquals("the cluster-log time of the first apply", 1_000L, entry.appliedAt());
    }

    @Test
    public void resendWithAnotherContentTypeOrOrganizationStillHasOneEffect() {
        write(contentPath("acme", "page-1-85b28694"), "page", PROPOSAL, 1_000L);
        NodeState afterFirst = store.getRoot();

        write(contentPath("acme", "asset-2-85b28694"), "asset", PROPOSAL, 2_000L);
        write(contentPath("globex", "page-3-85b28694"), "page", PROPOSAL, 3_000L);
        write(contentPath("acme", "page-4-85b28694"), "page", PROPOSAL.toUpperCase().replace("0X", "0x"), 4_000L);

        assertEquals(afterFirst, store.getRoot());
        assertFalse(node(WALLET_PATH + "/globex").exists());
    }

    @Test
    public void indexEntryLivesInTheHiddenConsensusNodeUnderATwoLevelFanOut() {
        write(contentPath("acme", "page-1-85b28694"), "page", PROPOSAL, 1_000L);

        NodeState entry = store.getRoot().getChildNode(":consensus").getChildNode("proposals")
            .getChildNode("85").getChildNode("b2").getChildNode(PROPOSAL);
        assertTrue(entry.exists());
        assertEquals("WRITE", entry.getProperty("kind").getValue(Type.STRING));
        assertEquals(1_000L, (long) entry.getProperty("appliedAt").getValue(Type.LONG));
    }

    @Test
    public void deleteResentAfterTheNodeWasRecreatedDoesNotDeleteIt() {
        String path = contentPath("acme", "doc");
        write(path, "page", OTHER_PROPOSAL, 1_000L);
        delete(path, PROPOSAL, 2_000L);
        assertFalse(node(path).exists());
        String recreatedBy = "0x99" + "ef".repeat(31);
        write(path, "page", recreatedBy, 3_000L);
        NodeState beforeResend = store.getRoot();

        assertNull(delete(path, PROPOSAL, 4_000L));

        assertEquals(beforeResend, store.getRoot());
        assertTrue(node(path).exists());
        assertEquals(PROPOSAL, durable.get(durable.size() - 1));
        AppliedProposalIndex.Entry entry = AppliedProposalIndex.find(store.getRoot(), PROPOSAL);
        assertEquals("DELETE", entry.kind());
        assertEquals(2_000L, entry.appliedAt());
    }

    @Test
    public void deleteOfAnAbsentTargetConsumesTheProposal() {
        String path = contentPath("acme", "doc");

        assertNull(delete(path, PROPOSAL, 1_000L));
        assertNotNull("the no-op delete is recorded", AppliedProposalIndex.find(store.getRoot(), PROPOSAL));
        assertEquals(new AppliedLogPosition(10, 0, 1), AppliedLogPosition.read(store.getRoot()));
        write(path, "page", OTHER_PROPOSAL, 2_000L);

        delete(path, PROPOSAL, 3_000L);

        assertTrue(node(path).exists());
        assertEquals(List.of(PROPOSAL, OTHER_PROPOSAL, PROPOSAL), durable);
    }

    @Test
    public void writeWhoseIdIsAlreadyConsumedByADeleteChangesNothing() {
        delete(contentPath("acme", "doc"), PROPOSAL, 1_000L);
        NodeState afterDelete = store.getRoot();

        write(contentPath("acme", "page-2-85b28694"), "page", PROPOSAL, 2_000L);

        assertEquals(afterDelete, store.getRoot());
    }

    @Test
    public void idsThatAreNotThirtyTwoByteHexAreNotIndexed() {
        write(contentPath("acme", "a"), "page", "proposal-legacy", 1_000L);

        assertNull(AppliedProposalIndex.find(store.getRoot(), "proposal-legacy"));
        assertFalse(store.getRoot().getChildNode(":consensus").hasChildNode("proposals"));
        assertNull(AppliedProposalIndex.key(null));
        assertNull(AppliedProposalIndex.key("0x1234"));
        assertNull(AppliedProposalIndex.find(null, PROPOSAL));
    }

    private String write(String path, String contentType, String proposalId, long appliedAt) {
        return writes.applyWriteWithAuditMetadata(WALLET, path, contentType, "m", "0xsig", null, null, null, null,
            audit(MutationAuditMetadata.write(null, null, proposalId, null, null, null, null), appliedAt));
    }

    private String delete(String path, String proposalId, long appliedAt) {
        return deletes.applyDeleteWithAuditMetadata(WALLET, path, "0xsig",
            audit(MutationAuditMetadata.delete(null, null, proposalId, null, null, null, null), appliedAt));
    }

    private static MutationAuditMetadata audit(MutationAuditMetadata metadata, long appliedAt) {
        return metadata.withAppliedAt(appliedAt)
            .withAppliedLogPosition(new AppliedLogPosition(appliedAt / 100, 0, 1));
    }

    private static String contentPath(String organization, String contentId) {
        return WALLET_PATH + "/" + organization + "/content/" + contentId;
    }

    private List<String> contentNodes(String organization) {
        List<String> names = new ArrayList<>();
        node(WALLET_PATH + "/" + organization + "/content").getChildNodeNames().forEach(names::add);
        return names;
    }

    private long walletLong(String property) {
        return node(WALLET_PATH).getProperty(property).getValue(Type.LONG);
    }

    private NodeState node(String path) {
        NodeState current = store.getRoot();
        for (String part : path.substring(1).split("/")) {
            current = current.getChildNode(part);
        }
        return current;
    }
}
