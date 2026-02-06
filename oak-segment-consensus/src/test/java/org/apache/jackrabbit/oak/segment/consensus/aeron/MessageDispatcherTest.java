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

import org.agrona.DirectBuffer;
import org.junit.Test;
import org.mockito.Mockito;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class MessageDispatcherTest {

    @Test
    public void testDispatchRejectsShortMessage() {
        MessageDispatcher dispatcher = new MessageDispatcher();
        DirectBuffer buffer = bufferFor(buildMessageBytes(SimpleMessageHeader.TEMPLATE_ID_WRITE_PROPOSAL, ""));

        boolean result = dispatcher.dispatch(System.currentTimeMillis(), buffer, 0, 4);

        assertFalse(result);
    }

    @Test
    public void testWriteProposalDispatchCallsCallback() {
        List<String> calls = new ArrayList<>();
        MessageDispatcher dispatcher = new MessageDispatcher(new MessageDispatcher.WriteCallback() {
            @Override
            public void applyWrite(String wallet, String path, String contentType, String message, String signature,
                                   String intentToken, String blobId, String mimeType, String ipfsCid, String proposalId) {
                calls.add(wallet + "|" + path + "|" + proposalId);
            }

            @Override
            public void applyDelete(String walletAddress, String path, String signature, String proposalId) {
            }
        });
        dispatcher.setTermProvider(() -> 7L);

        String payload = "{\"walletAddress\":\"0xabc\",\"path\":\"/oak-chain/test\",\"proposalId\":\"p1\",\"term\":7}";
        DirectBuffer buffer = bufferFor(buildMessageBytes(SimpleMessageHeader.TEMPLATE_ID_WRITE_PROPOSAL, payload));

        boolean result = dispatcher.dispatch(System.currentTimeMillis(), buffer, 0,
            SimpleMessageHeader.ENCODED_LENGTH + payload.getBytes(StandardCharsets.UTF_8).length);

        assertTrue(result);
        assertEquals(1, calls.size());
        assertEquals("0xabc|/oak-chain/test|p1", calls.get(0));
    }

    @Test
    public void testWriteProposalStaleTermRejected() {
        AtomicReference<String> called = new AtomicReference<>(null);
        MessageDispatcher dispatcher = new MessageDispatcher(new MessageDispatcher.WriteCallback() {
            @Override
            public void applyWrite(String wallet, String path, String contentType, String message, String signature,
                                   String intentToken, String blobId, String mimeType, String ipfsCid, String proposalId) {
                called.set(wallet);
            }

            @Override
            public void applyDelete(String walletAddress, String path, String signature, String proposalId) {
            }
        });
        dispatcher.setTermProvider(() -> 10L);

        String payload = "{\"walletAddress\":\"0xabc\",\"path\":\"/oak-chain/test\",\"proposalId\":\"p1\",\"term\":7}";
        DirectBuffer buffer = bufferFor(buildMessageBytes(SimpleMessageHeader.TEMPLATE_ID_WRITE_PROPOSAL, payload));

        boolean result = dispatcher.dispatch(System.currentTimeMillis(), buffer, 0,
            SimpleMessageHeader.ENCODED_LENGTH + payload.getBytes(StandardCharsets.UTF_8).length);

        assertFalse(result);
        assertNull(called.get());
    }

    @Test
    public void testWriteBatchDispatchProcessesAll() {
        List<String> calls = new ArrayList<>();
        MessageDispatcher dispatcher = new MessageDispatcher(new MessageDispatcher.WriteCallback() {
            @Override
            public void applyWrite(String wallet, String path, String contentType, String message, String signature,
                                   String intentToken, String blobId, String mimeType, String ipfsCid, String proposalId) {
                calls.add(path);
            }

            @Override
            public void applyDelete(String walletAddress, String path, String signature, String proposalId) {
            }
        });

        String payload = "{\"batch\":[{\"walletAddress\":\"0x1\",\"path\":\"/a\"},{\"walletAddress\":\"0x2\",\"path\":\"/b\"}]}";
        DirectBuffer buffer = bufferFor(buildMessageBytes(SimpleMessageHeader.TEMPLATE_ID_WRITE_BATCH, payload));

        boolean result = dispatcher.dispatch(System.currentTimeMillis(), buffer, 0,
            SimpleMessageHeader.ENCODED_LENGTH + payload.getBytes(StandardCharsets.UTF_8).length);

        assertTrue(result);
        assertEquals(2, calls.size());
        assertEquals(2, dispatcher.getLastBatchSize());
    }

    @Test
    public void testGcProposalDispatch() {
        AtomicReference<String> proposalId = new AtomicReference<>(null);
        MessageDispatcher dispatcher = new MessageDispatcher();
        dispatcher.setGCCallback(new MessageDispatcher.GCCallback() {
            @Override
            public void applyGCProposal(String proposalIdValue, String proposerWallet, String targetRevision,
                                        long estimatedReclaimableSizeMB, String estimatedCostUSDC) {
                proposalId.set(proposalIdValue);
            }

            @Override
            public void applyGCVote(String proposalId, int validatorId, boolean approve, String reason) {
            }

            @Override
            public void applyGCExecute(String proposalId, int executorId) {
            }
        });

        String payload = "{\"proposalId\":\"gc-1\",\"proposerWallet\":\"0xabc\",\"targetRevision\":\"r1\",\"estimatedReclaimableSizeMB\":12,\"estimatedCostUSDC\":\"3.1\"}";
        DirectBuffer buffer = bufferFor(buildMessageBytes(SimpleMessageHeader.TEMPLATE_ID_GC_PROPOSAL, payload));

        boolean result = dispatcher.dispatch(System.currentTimeMillis(), buffer, 0,
            SimpleMessageHeader.ENCODED_LENGTH + payload.getBytes(StandardCharsets.UTF_8).length);

        assertTrue(result);
        assertEquals("gc-1", proposalId.get());
    }

    @Test
    public void testDurabilityQueueSegmentDispatch() {
        AtomicReference<String> queued = new AtomicReference<>(null);
        MessageDispatcher dispatcher = new MessageDispatcher();
        dispatcher.setDurabilityCallback(new MessageDispatcher.DurabilityCallback() {
            @Override
            public void onQueueSegment(String proposalId, int totalMembers, int requiredAcks) {
                queued.set(proposalId + ":" + totalMembers + ":" + requiredAcks);
            }

            @Override
            public void onSegmentPersisted(String proposalId, int memberId, String durableHead, boolean success, String error) {
            }

            @Override
            public void onAckSegmentPersisted(String proposalId, boolean success, String durableHead, String error,
                                              int totalMembers, int requiredAcks) {
            }
        });

        String payload = "{\"proposalId\":\"p-1\",\"totalMembers\":3,\"requiredAcks\":2}";
        DirectBuffer buffer = bufferFor(buildMessageBytes(SimpleMessageHeader.TEMPLATE_ID_QUEUE_SEGMENT, payload));

        boolean result = dispatcher.dispatch(System.currentTimeMillis(), buffer, 0,
            SimpleMessageHeader.ENCODED_LENGTH + payload.getBytes(StandardCharsets.UTF_8).length);

        assertTrue(result);
        assertEquals("p-1:3:2", queued.get());
    }

    @Test
    public void testStartTransactionDispatch() {
        AtomicReference<String> started = new AtomicReference<>(null);
        MessageDispatcher dispatcher = new MessageDispatcher();
        dispatcher.setTransactionCallback(new MessageDispatcher.TransactionCallback() {
            @Override
            public void onStartTransaction(String transactionId, String correlationId, long timeoutMs, String initiatorWallet) {
                started.set(transactionId + "|" + correlationId + "|" + timeoutMs + "|" + initiatorWallet);
            }

            @Override
            public void onCommitTransaction(String transactionId, String correlationId) {
            }

            @Override
            public void onAbortTransaction(String transactionId, String correlationId, String reason) {
            }
        });
        dispatcher.setTermProvider(() -> 11L);

        String payload = "{\"transactionId\":\"tx-1\",\"correlationId\":\"corr-1\",\"timeoutMs\":15000,\"initiatorWallet\":\"0xabc\",\"term\":11}";
        DirectBuffer buffer = bufferFor(buildMessageBytes(SimpleMessageHeader.TEMPLATE_ID_START_TRANSACTION, payload));

        boolean result = dispatcher.dispatch(System.currentTimeMillis(), buffer, 0,
            SimpleMessageHeader.ENCODED_LENGTH + payload.getBytes(StandardCharsets.UTF_8).length);

        assertTrue(result);
        assertEquals("tx-1|corr-1|15000|0xabc", started.get());
    }

    @Test
    public void testCommitTransactionDispatch() {
        AtomicReference<String> committed = new AtomicReference<>(null);
        MessageDispatcher dispatcher = new MessageDispatcher();
        dispatcher.setTransactionCallback(new MessageDispatcher.TransactionCallback() {
            @Override
            public void onStartTransaction(String transactionId, String correlationId, long timeoutMs, String initiatorWallet) {
            }

            @Override
            public void onCommitTransaction(String transactionId, String correlationId) {
                committed.set(transactionId + "|" + correlationId);
            }

            @Override
            public void onAbortTransaction(String transactionId, String correlationId, String reason) {
            }
        });

        String payload = "{\"transactionId\":\"tx-2\",\"correlationId\":\"corr-2\"}";
        DirectBuffer buffer = bufferFor(buildMessageBytes(SimpleMessageHeader.TEMPLATE_ID_COMMIT_TRANSACTION, payload));

        boolean result = dispatcher.dispatch(System.currentTimeMillis(), buffer, 0,
            SimpleMessageHeader.ENCODED_LENGTH + payload.getBytes(StandardCharsets.UTF_8).length);

        assertTrue(result);
        assertEquals("tx-2|corr-2", committed.get());
    }

    @Test
    public void testAbortTransactionStaleTermRejected() {
        AtomicReference<String> aborted = new AtomicReference<>(null);
        MessageDispatcher dispatcher = new MessageDispatcher();
        dispatcher.setTransactionCallback(new MessageDispatcher.TransactionCallback() {
            @Override
            public void onStartTransaction(String transactionId, String correlationId, long timeoutMs, String initiatorWallet) {
            }

            @Override
            public void onCommitTransaction(String transactionId, String correlationId) {
            }

            @Override
            public void onAbortTransaction(String transactionId, String correlationId, String reason) {
                aborted.set(transactionId);
            }
        });
        dispatcher.setTermProvider(() -> 20L);

        String payload = "{\"transactionId\":\"tx-3\",\"correlationId\":\"corr-3\",\"reason\":\"timeout\",\"term\":19}";
        DirectBuffer buffer = bufferFor(buildMessageBytes(SimpleMessageHeader.TEMPLATE_ID_ABORT_TRANSACTION, payload));

        boolean result = dispatcher.dispatch(System.currentTimeMillis(), buffer, 0,
            SimpleMessageHeader.ENCODED_LENGTH + payload.getBytes(StandardCharsets.UTF_8).length);

        assertFalse(result);
        assertNull(aborted.get());
    }

    private byte[] buildMessageBytes(int templateId, String payload) {
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[SimpleMessageHeader.ENCODED_LENGTH + payloadBytes.length];
        putShortLE(bytes, 0, (short) payloadBytes.length);
        putShortLE(bytes, 2, (short) templateId);
        putShortLE(bytes, 4, (short) 1);
        putShortLE(bytes, 6, (short) 1);
        System.arraycopy(payloadBytes, 0, bytes, SimpleMessageHeader.ENCODED_LENGTH, payloadBytes.length);
        return bytes;
    }

    private DirectBuffer bufferFor(byte[] bytes) {
        DirectBuffer buffer = mock(DirectBuffer.class);
        Mockito.doAnswer(invocation -> {
            int offset = invocation.getArgument(0);
            byte[] dest = invocation.getArgument(1);
            int length = Math.min(dest.length, bytes.length - offset);
            if (length > 0) {
                System.arraycopy(bytes, offset, dest, 0, length);
            }
            return null;
        }).when(buffer).getBytes(Mockito.anyInt(), any(byte[].class));
        when(buffer.getShort(Mockito.anyInt(), Mockito.any(java.nio.ByteOrder.class))).thenAnswer(invocation -> {
            int offset = invocation.getArgument(0);
            java.nio.ByteOrder order = invocation.getArgument(1);
            if (order != java.nio.ByteOrder.LITTLE_ENDIAN) {
                throw new IllegalArgumentException("Unexpected byte order");
            }
            int lo = bytes[offset] & 0xFF;
            int hi = bytes[offset + 1] & 0xFF;
            return (short) ((hi << 8) | lo);
        });
        return buffer;
    }

    private void putShortLE(byte[] bytes, int offset, short value) {
        bytes[offset] = (byte) (value & 0xFF);
        bytes[offset + 1] = (byte) ((value >> 8) & 0xFF);
    }
}
