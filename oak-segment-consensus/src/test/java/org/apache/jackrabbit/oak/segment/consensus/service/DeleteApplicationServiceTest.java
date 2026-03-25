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
import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.apache.jackrabbit.oak.spi.commit.CommitInfo;
import org.apache.jackrabbit.oak.spi.commit.EmptyHook;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class DeleteApplicationServiceTest {

    private static final String WALLET = "0x1234567890abcdef1234567890abcdef12345678";
    private static final String EXISTING_PATH = "/oak-chain/aa/bb/cc/" + WALLET + "/Acme/content/doc-1";
    private static final String MISSING_TARGET_PATH = "/oak-chain/aa/bb/cc/" + WALLET + "/Acme/content/doc-2";
    private static final String MISSING_BRANCH_PATH = "/oak-chain/aa/bb/cc/" + WALLET + "/Missing/content/doc-9";

    @Test
    public void testApplyDeleteRemovesNodeAndInvokesCallbacks() throws Exception {
        FileStore fileStore = fileStoreWithHeads("prev-head", "new-head");
        MemoryNodeStore nodeStore = seededNodeStore(EXISTING_PATH);
        FileStoreFlushService flushService = mock(FileStoreFlushService.class);
        when(flushService.onChangeApplied()).thenReturn(true);
        DeleteApplicationService service = new DeleteApplicationService(fileStore, nodeStore, flushService);

        AtomicReference<String> updatedHead = new AtomicReference<>();
        AtomicReference<String> durableProposal = new AtomicReference<>();
        AtomicReference<String> durableHead = new AtomicReference<>();
        AtomicReference<String> ssePayload = new AtomicReference<>();
        service.setHeadUpdateCallback(updatedHead::set);
        service.setDurabilityCallback(new DeleteApplicationService.DurabilityCallback() {
            @Override
            public void onDurable(String proposalId, String durableHeadValue) {
                durableProposal.set(proposalId);
                durableHead.set(durableHeadValue);
            }

            @Override
            public void onFailure(String proposalId, String error) {
            }
        });
        service.setSseEventCallback((path, wallet, org, signature) ->
            ssePayload.set(path + "|" + wallet + "|" + org + "|" + signature));

        String newHead = service.applyDelete(WALLET, EXISTING_PATH, "0xsig", "proposal-1");

        assertEquals("new-head", newHead);
        assertEquals("new-head", updatedHead.get());
        assertEquals("proposal-1", durableProposal.get());
        assertEquals("new-head", durableHead.get());
        assertEquals(EXISTING_PATH + "|" + WALLET + "|Acme|0xsig", ssePayload.get());
        verify(flushService).onChangeApplied();
        assertFalse(nodeAt(nodeStore, EXISTING_PATH).exists());
    }

    @Test
    public void testApplyDeleteSkipsDurabilityCallbackWhenFlushIsDeferred() throws Exception {
        FileStore fileStore = fileStoreWithHeads("prev-head", "current-head");
        MemoryNodeStore nodeStore = seededNodeStore(EXISTING_PATH);
        FileStoreFlushService flushService = mock(FileStoreFlushService.class);
        when(flushService.onChangeApplied()).thenReturn(false);
        DeleteApplicationService service = new DeleteApplicationService(fileStore, nodeStore, flushService);

        AtomicReference<String> durableProposal = new AtomicReference<>();
        AtomicReference<String> durableHead = new AtomicReference<>();
        service.setDurabilityCallback(new DeleteApplicationService.DurabilityCallback() {
            @Override
            public void onDurable(String proposalId, String durableHeadValue) {
                durableProposal.set(proposalId);
                durableHead.set(durableHeadValue);
            }

            @Override
            public void onFailure(String proposalId, String error) {
            }
        });

        String newHead = service.applyDelete(WALLET, EXISTING_PATH, "0xsig", "proposal-deferred");

        assertEquals("current-head", newHead);
        assertNull(durableProposal.get());
        assertNull(durableHead.get());
        verify(flushService).onChangeApplied();
    }

    @Test
    public void testApplyDeleteIsIdempotentWhenParentPathMissing() throws Exception {
        FileStore fileStore = fileStoreWithHeads("prev-head", "current-head");
        MemoryNodeStore nodeStore = seededNodeStore(EXISTING_PATH);
        FileStoreFlushService flushService = mock(FileStoreFlushService.class);
        DeleteApplicationService service = new DeleteApplicationService(fileStore, nodeStore, flushService);

        AtomicReference<String> durableProposal = new AtomicReference<>();
        AtomicReference<String> durableHead = new AtomicReference<>();
        service.setDurabilityCallback(new DeleteApplicationService.DurabilityCallback() {
            @Override
            public void onDurable(String proposalId, String durableHeadValue) {
                durableProposal.set(proposalId);
                durableHead.set(durableHeadValue);
            }

            @Override
            public void onFailure(String proposalId, String error) {
            }
        });

        String newHead = service.applyDelete(WALLET, MISSING_BRANCH_PATH, "0xsig", "proposal-missing-branch");

        assertNull(newHead);
        assertEquals("proposal-missing-branch", durableProposal.get());
        assertEquals("current-head", durableHead.get());
    }

    @Test
    public void testApplyDeleteIsIdempotentWhenTargetNodeAlreadyMissing() throws Exception {
        FileStore fileStore = fileStoreWithHeads("prev-head", "current-head");
        MemoryNodeStore nodeStore = seededNodeStore("/oak-chain/aa/bb/cc/" + WALLET + "/Acme/content");
        FileStoreFlushService flushService = mock(FileStoreFlushService.class);
        DeleteApplicationService service = new DeleteApplicationService(fileStore, nodeStore, flushService);

        AtomicReference<String> durableProposal = new AtomicReference<>();
        AtomicReference<String> durableHead = new AtomicReference<>();
        service.setDurabilityCallback(new DeleteApplicationService.DurabilityCallback() {
            @Override
            public void onDurable(String proposalId, String durableHeadValue) {
                durableProposal.set(proposalId);
                durableHead.set(durableHeadValue);
            }

            @Override
            public void onFailure(String proposalId, String error) {
            }
        });

        String newHead = service.applyDelete(WALLET, MISSING_TARGET_PATH, "0xsig", "proposal-missing-target");

        assertNull(newHead);
        assertEquals("proposal-missing-target", durableProposal.get());
        assertEquals("current-head", durableHead.get());
    }

    @Test
    public void testApplyDeleteReportsDurabilityFailureForInvalidPath() {
        FileStore fileStore = fileStoreWithHeads("prev-head", "new-head");
        MemoryNodeStore nodeStore = new MemoryNodeStore();
        FileStoreFlushService flushService = mock(FileStoreFlushService.class);
        DeleteApplicationService service = new DeleteApplicationService(fileStore, nodeStore, flushService);

        AtomicReference<String> failedProposal = new AtomicReference<>();
        AtomicReference<String> failureMessage = new AtomicReference<>();
        service.setDurabilityCallback(new DeleteApplicationService.DurabilityCallback() {
            @Override
            public void onDurable(String proposalId, String durableHead) {
            }

            @Override
            public void onFailure(String proposalId, String error) {
                failedProposal.set(proposalId);
                failureMessage.set(error);
            }
        });

        try {
            service.applyDelete(WALLET, "invalid", "0xsig", "proposal-fail");
        } catch (RuntimeException e) {
            assertTrue(e.getMessage().contains("Failed to apply replicated delete"));
        }

        assertEquals("proposal-fail", failedProposal.get());
        assertTrue(failureMessage.get().contains("Invalid path format"));
    }

    private static FileStore fileStoreWithHeads(String previousHead, String currentHead) {
        FileStore fileStore = mock(FileStore.class, RETURNS_DEEP_STUBS);
        when(fileStore.getHead().getRecordId().toString()).thenReturn(previousHead);
        when(fileStore.getHead().getRecordId().toString10()).thenReturn(currentHead);
        return fileStore;
    }

    private static MemoryNodeStore seededNodeStore(String path) throws Exception {
        MemoryNodeStore nodeStore = new MemoryNodeStore();
        NodeBuilder root = nodeStore.getRoot().builder();
        NodeBuilder current = root;
        for (String part : path.substring(1).split("/")) {
            current = current.child(part);
        }
        current.setProperty("contentType", "page");
        nodeStore.merge(root, EmptyHook.INSTANCE, CommitInfo.EMPTY);
        return nodeStore;
    }

    private static NodeState nodeAt(MemoryNodeStore nodeStore, String path) {
        NodeState current = nodeStore.getRoot();
        for (String part : path.substring(1).split("/")) {
            current = current.getChildNode(part);
        }
        return current;
    }
}
