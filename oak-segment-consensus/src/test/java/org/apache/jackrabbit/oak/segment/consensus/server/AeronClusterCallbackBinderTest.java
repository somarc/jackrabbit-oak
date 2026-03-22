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

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.apache.jackrabbit.oak.segment.consensus.aeron.AeronConsensusEngine;
import org.apache.jackrabbit.oak.segment.consensus.gc.GCProposalManager;
import org.apache.jackrabbit.oak.segment.http.server.SegmentHttpServer;
import org.apache.jackrabbit.oak.segment.http.server.ServerContext;
import org.apache.jackrabbit.oak.segment.http.server.handlers.ConsensusApiHandler;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
        callback.applyReplicatedWrite("wallet", "/content", "text/plain", "body", "sig", "intent", "blob", "image/png", "cid", "proposal-1");
        callback.applyReplicatedDelete("wallet", "/content", "sig", "proposal-2");

        verify(handler).applyReplicatedWrite("wallet", "/content", "text/plain", "body", "sig", "intent", "blob", "image/png", "cid", "proposal-1");
        verify(handler).applyReplicatedDelete("wallet", "/content", "sig", "proposal-2");
    }

    @Test
    public void bindRoutesReplicatedGCOperationsToManager() throws Exception {
        AeronConsensusEngine engine = mock(AeronConsensusEngine.class);
        SegmentHttpServer httpServer = mock(SegmentHttpServer.class);
        ServerContext context = mock(ServerContext.class);
        GCProposalManager manager = mock(GCProposalManager.class);

        context.gcProposalManager = manager;
        when(httpServer.getContext()).thenReturn(context);
        when(httpServer.getConsensusApiHandler()).thenReturn(mock(ConsensusApiHandler.class));

        new AeronClusterCallbackBinder().bind(engine, httpServer);

        ArgumentCaptor<AeronConsensusEngine.GCApplicationCallback> gcCaptor =
            ArgumentCaptor.forClass(AeronConsensusEngine.GCApplicationCallback.class);
        verify(engine).setGCCallback(gcCaptor.capture());

        AeronConsensusEngine.GCApplicationCallback callback = gcCaptor.getValue();
        callback.applyGCProposal("proposal-1", "wallet", "r42", 64L, "2.50");
        callback.applyGCVote("proposal-1", 2, true, null);
        callback.applyGCExecute("proposal-1", 3);

        verify(manager).applyReplicatedProposal("proposal-1", "wallet", "r42", 64L, "2.50");
        verify(manager).voteOnProposal("proposal-1", 2, true, "");
        verify(manager).executeGC("proposal-1", 3);
    }

    @Test
    public void bindSkipsGCOperationsWhenManagerMissing() {
        AeronConsensusEngine engine = mock(AeronConsensusEngine.class);
        SegmentHttpServer httpServer = mock(SegmentHttpServer.class);
        ServerContext context = mock(ServerContext.class);

        context.gcProposalManager = null;
        when(httpServer.getContext()).thenReturn(context);
        when(httpServer.getConsensusApiHandler()).thenReturn(mock(ConsensusApiHandler.class));

        ListAppender<ILoggingEvent> appender = TestLogAppenderSupport.attach(AeronClusterCallbackBinder.class);
        try {
            new AeronClusterCallbackBinder().bind(engine, httpServer);

            ArgumentCaptor<AeronConsensusEngine.GCApplicationCallback> gcCaptor =
                ArgumentCaptor.forClass(AeronConsensusEngine.GCApplicationCallback.class);
            verify(engine).setGCCallback(gcCaptor.capture());

            AeronConsensusEngine.GCApplicationCallback callback = gcCaptor.getValue();
            callback.applyGCProposal("proposal-1", "wallet", "r42", 64L, "2.50");
            callback.applyGCVote("proposal-1", 2, true, "ok");
            callback.applyGCExecute("proposal-1", 3);

            assertTrue(TestLogAppenderSupport.contains(appender, "GC proposal manager not initialized"));
        } finally {
            TestLogAppenderSupport.detach(AeronClusterCallbackBinder.class, appender);
        }
    }

    @Test
    public void bindSwallowsGCExecuteFailures() throws Exception {
        AeronConsensusEngine engine = mock(AeronConsensusEngine.class);
        SegmentHttpServer httpServer = mock(SegmentHttpServer.class);
        ServerContext context = mock(ServerContext.class);
        GCProposalManager manager = mock(GCProposalManager.class);

        context.gcProposalManager = manager;
        when(httpServer.getContext()).thenReturn(context);
        when(httpServer.getConsensusApiHandler()).thenReturn(mock(ConsensusApiHandler.class));
        org.mockito.Mockito.doThrow(new java.io.IOException("disk")).when(manager).executeGC("proposal-1", 3);

        new AeronClusterCallbackBinder().bind(engine, httpServer);

        ArgumentCaptor<AeronConsensusEngine.GCApplicationCallback> gcCaptor =
            ArgumentCaptor.forClass(AeronConsensusEngine.GCApplicationCallback.class);
        verify(engine).setGCCallback(gcCaptor.capture());

        gcCaptor.getValue().applyGCExecute("proposal-1", 3);

        verify(manager).executeGC("proposal-1", 3);
    }
}
