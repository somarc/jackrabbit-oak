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
package org.apache.jackrabbit.oak.segment.consensus.server;

import org.apache.jackrabbit.oak.segment.consensus.aeron.AeronConsensusEngine;
import org.apache.jackrabbit.oak.segment.consensus.service.MutationAuditMetadata;
import org.apache.jackrabbit.oak.segment.http.server.SegmentHttpServer;
import org.apache.jackrabbit.oak.segment.http.server.ServerContext;
import org.apache.jackrabbit.oak.segment.http.server.handlers.ConsensusApiHandler;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

public class AeronClusterCallbackBinderTest {

    @Test
    public void bindRoutesReplicatedWriteAndDeleteToConsensusHandler() {
        AeronConsensusEngine engine = mock(AeronConsensusEngine.class);
        SegmentHttpServer httpServer = mock(SegmentHttpServer.class);
        ConsensusApiHandler handler = mock(ConsensusApiHandler.class);
        ServerContext context = mock(ServerContext.class);

        when(httpServer.getConsensusApiHandler()).thenReturn(handler);
        when(httpServer.getContext()).thenReturn(context);

        new AeronClusterCallbackBinder().bind(engine, httpServer);

        ArgumentCaptor<AeronConsensusEngine.WriteApplicationCallback> writeCaptor =
            ArgumentCaptor.forClass(AeronConsensusEngine.WriteApplicationCallback.class);
        verify(engine).setWriteApplicationCallback(writeCaptor.capture());

        AeronConsensusEngine.WriteApplicationCallback callback = writeCaptor.getValue();
        MutationAuditMetadata writeAuditMetadata = MutationAuditMetadata.write(
            "tx-1", "corr-1", "proposal-1", "0xeth", 42L, 84L, null
        );
        MutationAuditMetadata deleteAuditMetadata = MutationAuditMetadata.delete(
            "tx-2", "corr-2", "proposal-2", null, null, null, 83L
        );
        callback.applyReplicatedWrite(
            "wallet", "/content", "text/plain", "body", "sig", "intent", "blob", "image/png", "cid",
            writeAuditMetadata
        );
        callback.applyReplicatedDelete("wallet", "/content", "sig", deleteAuditMetadata);

        ArgumentCaptor<MutationAuditMetadata> writeAuditCaptor = ArgumentCaptor.forClass(MutationAuditMetadata.class);
        ArgumentCaptor<MutationAuditMetadata> deleteAuditCaptor = ArgumentCaptor.forClass(MutationAuditMetadata.class);
        verify(handler).applyReplicatedWriteWithAuditMetadata(
            org.mockito.Mockito.eq("wallet"),
            org.mockito.Mockito.eq("/content"),
            org.mockito.Mockito.eq("text/plain"),
            org.mockito.Mockito.eq("body"),
            org.mockito.Mockito.eq("sig"),
            org.mockito.Mockito.eq("intent"),
            org.mockito.Mockito.eq("blob"),
            org.mockito.Mockito.eq("image/png"),
            org.mockito.Mockito.eq("cid"),
            writeAuditCaptor.capture()
        );
        verify(handler).applyReplicatedDeleteWithAuditMetadata(
            org.mockito.Mockito.eq("wallet"),
            org.mockito.Mockito.eq("/content"),
            org.mockito.Mockito.eq("sig"),
            deleteAuditCaptor.capture()
        );

        assertEquals(MutationAuditMetadata.Operation.WRITE, writeAuditCaptor.getValue().getOperation());
        assertEquals("proposal-1", writeAuditCaptor.getValue().getProposalId());
        assertEquals("tx-1", writeAuditCaptor.getValue().getTransactionId());
        assertEquals(Long.valueOf(84L), writeAuditCaptor.getValue().getEthereumObservedEpoch());
        assertEquals(MutationAuditMetadata.Operation.DELETE, deleteAuditCaptor.getValue().getOperation());
        assertEquals("proposal-2", deleteAuditCaptor.getValue().getProposalId());
        assertEquals("corr-2", deleteAuditCaptor.getValue().getCorrelationId());
        assertEquals(Long.valueOf(83L), deleteAuditCaptor.getValue().getEthereumFinalizedEpoch());
    }

    @Test
    public void bindWiresOnlyTheWriteApplicationCallback() {
        AeronConsensusEngine engine = mock(AeronConsensusEngine.class);
        SegmentHttpServer httpServer = mock(SegmentHttpServer.class);
        when(httpServer.getConsensusApiHandler()).thenReturn(mock(ConsensusApiHandler.class));

        new AeronClusterCallbackBinder().bind(engine, httpServer);

        verify(engine).setWriteApplicationCallback(org.mockito.ArgumentMatchers.any());
        verifyNoMoreInteractions(engine);
    }
}
