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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import org.agrona.concurrent.UnsafeBuffer;
import org.apache.jackrabbit.oak.api.Type;
import org.apache.jackrabbit.oak.plugins.memory.MemoryNodeStore;
import org.apache.jackrabbit.oak.segment.consensus.aeron.MessageDispatcher;
import org.apache.jackrabbit.oak.segment.consensus.aeron.SimpleMessageHeader;
import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.apache.jackrabbit.oak.spi.commit.CommitInfo;
import org.apache.jackrabbit.oak.spi.commit.EmptyHook;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ConsensusWriteSafetyTest {
    private static final String WALLET = "0x1234567890abcdef1234567890abcdef12345678";
    private static final String WALLET_PATH = "/oak-chain/12/34/56/" + WALLET;
    private static final String PATH = WALLET_PATH + "/content/probe";
    private String flushInterval;
    private String flushBatch;

    @Before
    public void synchronousFlush() {
        flushInterval = System.getProperty("oak.filestore.flush.ms");
        flushBatch = System.getProperty("oak.filestore.flush.batch");
        System.setProperty("oak.filestore.flush.ms", "0");
        System.setProperty("oak.filestore.flush.batch", "1");
    }

    @After
    public void restoreProperties() {
        restore("oak.filestore.flush.ms", flushInterval);
        restore("oak.filestore.flush.batch", flushBatch);
    }

    @Test
    public void duplicateWriteMustFlushBeforeAcknowledgingDurability() throws Exception {
        FileStore fileStore = fileStore();
        doThrow(new IOException("disk unavailable")).when(fileStore).flush();
        MemoryNodeStore store = new MemoryNodeStore();
        AtomicInteger durable = new AtomicInteger();
        try (FileStoreFlushService flush = new FileStoreFlushService(fileStore)) {
            WriteApplicationService service = new WriteApplicationService(fileStore, store, null, flush);
            service.setDurabilityCallback(new WriteApplicationService.DurabilityCallback() {
                public void onDurable(String proposalId, String head) {
                    durable.incrementAndGet();
                }
                public void onFailure(String proposalId, String error) {
                }
            });
            service.applyWrite(WALLET, PATH, "page", "body", "0xsig", null, null, null, null, "proposal");
            assertEquals(0, durable.get());
            service.applyWrite(WALLET, PATH, "page", "body", "0xsig", null, null, null, null, "proposal");
            assertEquals("A replay cannot turn a failed flush into a durability vote", 0, durable.get());
            verify(fileStore, times(2)).flush();
            assertEquals(1L, node(store, WALLET_PATH).getProperty("totalWrites").getValue(Type.LONG).longValue());
        }
    }

    @Test
    public void duplicateDeleteMustFlushBeforeAcknowledgingDurability() throws Exception {
        FileStore fileStore = fileStore();
        doThrow(new IOException("disk unavailable")).when(fileStore).flush();
        MemoryNodeStore store = new MemoryNodeStore();
        NodeBuilder root = store.getRoot().builder();
        NodeBuilder child = root;
        for (String part : PATH.substring(1).split("/")) {
            child = child.child(part);
        }
        store.merge(root, EmptyHook.INSTANCE, CommitInfo.EMPTY);
        AtomicInteger durable = new AtomicInteger();
        try (FileStoreFlushService flush = new FileStoreFlushService(fileStore)) {
            DeleteApplicationService service = new DeleteApplicationService(fileStore, store, flush);
            service.setDurabilityCallback(new DeleteApplicationService.DurabilityCallback() {
                public void onDurable(String proposalId, String head) {
                    durable.incrementAndGet();
                }
                public void onFailure(String proposalId, String error) {
                }
            });
            service.applyDelete(WALLET, PATH, "0xsig", "proposal");
            assertFalse(node(store, PATH).exists());
            assertEquals(0, durable.get());
            service.applyDelete(WALLET, PATH, "0xsig", "proposal");
            assertEquals("An absent node does not prove its deletion was flushed", 0, durable.get());
            verify(fileStore, times(2)).flush();
        }
    }

    @Test
    public void replicatedWriteUsesClusterTimestampForAllPersistedClockFields() {
        MemoryNodeStore store = new MemoryNodeStore();
        WriteApplicationService service = new WriteApplicationService(
            fileStore(), store, null, mock(FileStoreFlushService.class));
        MessageDispatcher dispatcher = new MessageDispatcher(new MessageDispatcher.WriteCallback() {
            @Override
            public void applyWrite(String wallet, String path, String contentType, String message,
                                   String signature, String intentToken, String blobId, String mimeType,
                                   String ipfsCid, MutationAuditMetadata metadata) {
                service.applyWriteWithAuditMetadata(wallet, path, contentType, message, signature,
                    intentToken, blobId, mimeType, ipfsCid, metadata);
            }
        });
        byte[] payload = ("{\"walletAddress\":\"" + WALLET + "\",\"path\":\"" + PATH
            + "\",\"message\":\"body\",\"signature\":\"0xsig\",\"proposalId\":\"proposal\"}")
            .getBytes(StandardCharsets.UTF_8);
        UnsafeBuffer buffer = new UnsafeBuffer(new byte[SimpleMessageHeader.ENCODED_LENGTH + payload.length]);
        SimpleMessageHeader.encode(buffer, 0, payload.length, SimpleMessageHeader.TEMPLATE_ID_WRITE_PROPOSAL);
        buffer.putBytes(SimpleMessageHeader.ENCODED_LENGTH, payload);
        long timestamp = 123456789L;
        assertTrue(dispatcher.dispatch(timestamp, buffer, 0, buffer.capacity()));
        assertEquals(timestamp, node(store, PATH).getProperty("timestamp").getValue(Type.LONG).longValue());
        assertEquals(timestamp, node(store, WALLET_PATH).getProperty("walletCreated").getValue(Type.LONG).longValue());
        assertEquals(timestamp, node(store, WALLET_PATH).getProperty("lastWrite").getValue(Type.LONG).longValue());
    }

    @Test
    public void commandTimeIsImmutableAndSurvivesOperationNormalization() {
        MutationAuditMetadata original = MutationAuditMetadata.write("tx", "correlation", "proposal", "hash", 1L, 2L, 3L);
        MutationAuditMetadata timed = original.withAppliedAt(42L);
        MutationAuditMetadata deleted = timed.withOperation(MutationAuditMetadata.Operation.DELETE);
        assertNull(original.getAppliedAt());
        assertEquals(Long.valueOf(42), deleted.getAppliedAt());
        assertEquals("proposal", deleted.getProposalId());
        assertEquals("tx", deleted.getTransactionId());
        assertEquals("correlation", deleted.getCorrelationId());
        assertEquals("hash", deleted.getEthereumTxHash());
        assertEquals(Long.valueOf(1), deleted.getConfirmedBlockNumber());
        assertEquals(Long.valueOf(2), deleted.getEthereumObservedEpoch());
        assertEquals(Long.valueOf(3), deleted.getEthereumFinalizedEpoch());
        assertThrows(IllegalArgumentException.class, () -> original.withAppliedAt(-1));
    }

    @Test
    public void applyBoundaryRejectsGenesisMutationsBeforeTouchingStorage() {
        FileStore fileStore = fileStore();
        MemoryNodeStore store = new MemoryNodeStore();
        FileStoreFlushService flush = mock(FileStoreFlushService.class);
        WriteApplicationService writes = new WriteApplicationService(fileStore, store, null, flush);
        DeleteApplicationService deletes = new DeleteApplicationService(fileStore, store, flush);
        assertThrows(RuntimeException.class, () -> writes.applyWrite(WALLET,
            org.apache.jackrabbit.oak.segment.consensus.genesis.CanonicalGenesisContent.getGenesisPath(),
            "page", "body", "0xsig", null, null, null, null, "proposal"));
        assertThrows(RuntimeException.class, () -> deletes.applyDelete(WALLET, "/oak-chain", "0xsig", "proposal"));
        assertFalse(store.getRoot().hasChildNode("oak-chain"));
        org.mockito.Mockito.verifyNoInteractions(flush);
    }

    private static FileStore fileStore() {
        FileStore store = mock(FileStore.class, RETURNS_DEEP_STUBS);
        when(store.getHead().getRecordId().toString10()).thenReturn("head");
        return store;
    }

    private static NodeState node(MemoryNodeStore store, String path) {
        NodeState node = store.getRoot();
        for (String part : path.substring(1).split("/")) {
            node = node.getChildNode(part);
        }
        return node;
    }

    private static void restore(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }
}
