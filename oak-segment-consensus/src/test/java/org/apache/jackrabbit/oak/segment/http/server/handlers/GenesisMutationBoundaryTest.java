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
package org.apache.jackrabbit.oak.segment.http.server.handlers;

import java.io.PrintWriter;
import java.io.StringWriter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.jackrabbit.oak.plugins.memory.MemoryNodeStore;
import org.apache.jackrabbit.oak.segment.consensus.aeron.AeronConsensusEngine;
import org.apache.jackrabbit.oak.segment.consensus.config.BlockchainConfig;
import org.apache.jackrabbit.oak.segment.consensus.genesis.CanonicalGenesisContent;
import org.apache.jackrabbit.oak.segment.consensus.queue.ProposalQueueManagerOptimized;
import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.apache.jackrabbit.oak.segment.http.server.ServerContext;
import org.apache.jackrabbit.oak.segment.http.server.binary.UploadSession;
import org.apache.jackrabbit.oak.segment.http.server.binary.UploadSessionManager;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

public class GenesisMutationBoundaryTest {
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();
    private ServerContext context;
    private HttpServletRequest request;
    private HttpServletResponse response;
    private StringWriter body;
    private String mode;

    @Before
    public void setUp() throws Exception {
        mode = System.getProperty("oak.blockchain.mode");
        System.setProperty("oak.blockchain.mode", "mock");
        BlockchainConfig.reset();
        context = new ServerContext(mock(FileStore.class), new MemoryNodeStore(),
            temporary.getRoot().toPath(), "http://localhost:8090");
        context.aeronConsensusEngine = mock(AeronConsensusEngine.class);
        context.proposalQueueManager = mock(ProposalQueueManagerOptimized.class);
        when(context.aeronConsensusEngine.isClusterHealthy()).thenReturn(true);
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        body = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(body));
        when(request.getParameter("walletAddress")).thenReturn(CanonicalGenesisContent.GENESIS_ADDRESS);
    }

    @After
    public void restoreMode() {
        if (mode == null) {
            System.clearProperty("oak.blockchain.mode");
        } else {
            System.setProperty("oak.blockchain.mode", mode);
        }
        BlockchainConfig.reset();
    }

    @Test
    public void ordinaryWriteCannotAutoRegisterOrQueueTheZeroWallet() throws Exception {
        new WriteProposalHandler(context).handleProposeWrite(request, response);
        assertReserved();
        assertTrue(context.registeredClients.isEmpty());
        verifyNoInteractions(context.proposalQueueManager);
    }

    @Test
    public void ordinaryDeleteCannotRemoveGenesisOrAnAncestor() throws Exception {
        when(request.getParameter("signature")).thenReturn("0x1234");
        when(request.getParameter("contentPath")).thenReturn("/oak-chain");
        new DeleteProposalHandler(context).handleDeleteProposal(request, response);
        assertReserved();
        verifyNoInteractions(context.proposalQueueManager);
    }

    @Test
    public void ordinaryClientCannotRegisterTheZeroWallet() throws Exception {
        new RegistrationHandler(context).handleClientRegistration(request, response);
        assertReserved();
        assertTrue(context.registeredClients.isEmpty());
    }

    @Test
    public void binaryIntentCannotTargetTheZeroWallet() throws Exception {
        UploadSessionManager sessions = mock(UploadSessionManager.class);
        when(request.getParameter("filesize")).thenReturn("1");
        when(request.getParameter("mimeType")).thenReturn("text/plain");
        new BinaryUploadHandler(sessions).handleDeclareIntent(request, response);
        assertReserved();
        verifyNoInteractions(sessions);
    }

    @Test
    public void legacyBinarySessionCannotCompleteForTheZeroWallet() throws Exception {
        UploadSessionManager sessions = mock(UploadSessionManager.class);
        when(request.getParameter("intentToken")).thenReturn("test-intent");
        when(request.getParameter("cid")).thenReturn("test-cid");
        when(sessions.getSession("test-intent")).thenReturn(mock(UploadSession.class));
        new BinaryUploadHandler(sessions).handleCompleteUpload(request, response);
        assertReserved();
        org.mockito.Mockito.verify(sessions, org.mockito.Mockito.never()).completeUpload("test-intent", "test-cid");
    }

    private void assertReserved() {
        verify(response).setStatus(HttpServletResponse.SC_FORBIDDEN);
        assertTrue(body.toString().toLowerCase().contains("genesis_namespace_reserved"));
    }
}
