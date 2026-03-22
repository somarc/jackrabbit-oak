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

import org.apache.jackrabbit.oak.plugins.memory.MemoryNodeStore;
import org.apache.jackrabbit.oak.segment.consensus.aeron.AeronConsensusEngine;
import org.apache.jackrabbit.oak.segment.consensus.economics.ValidatorEarningsTracker;
import org.apache.jackrabbit.oak.segment.consensus.gc.GCAccountManager;
import org.apache.jackrabbit.oak.segment.consensus.queue.ProposalQueueManagerOptimized;
import org.apache.jackrabbit.oak.segment.consensus.util.WalletPathUtil;
import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.apache.jackrabbit.oak.segment.http.server.ServerContext;
import org.apache.jackrabbit.oak.segment.http.server.model.ClientRegistration;
import org.apache.jackrabbit.oak.spi.commit.CommitInfo;
import org.apache.jackrabbit.oak.spi.commit.EmptyHook;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;
import org.junit.After;
import org.junit.Test;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class DeleteProposalHandlerTest {

    private static final String VALID_WALLET = "0x1234567890abcdef1234567890abcdef12345678";
    private static final String OTHER_WALLET = "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd";
    private static final String VALID_SIGNATURE = "0xabcdef12";
    private static final String PRIORITY_TX_HASH = "0xabcdef123456789f";

    @After
    public void tearDown() {
        System.clearProperty("oak.blockchain.mode");
        org.apache.jackrabbit.oak.segment.consensus.config.BlockchainConfig.reset();
    }

    @Test
    public void testHandleDeleteProposalRejectsUnregisteredWallet() throws Exception {
        ServerContext context = readyContext(new MemoryNodeStore());
        DeleteProposalHandler handler = new DeleteProposalHandler(context);
        HttpServletRequest request = request();
        when(request.getParameter("walletAddress")).thenReturn(VALID_WALLET);
        when(request.getParameter("signature")).thenReturn(VALID_SIGNATURE);
        when(request.getParameter("contentPath")).thenReturn(WalletPathUtil.getShardRoot(VALID_WALLET) + "/content/doc-1");
        StringWriter body = new StringWriter();
        HttpServletResponse response = responseWithBody(body);

        handler.handleDeleteProposal(request, response);

        verify(response).setStatus(HttpServletResponse.SC_FORBIDDEN);
        assertTrue(body.toString().contains("not registered"));
    }

    @Test
    public void testHandleDeleteProposalRejectsClientIdWalletMismatch() throws Exception {
        ServerContext context = readyContext(new MemoryNodeStore());
        context.registeredClients.put("client-1", new ClientRegistration("client-1", "http://author-1:4502", OTHER_WALLET));
        DeleteProposalHandler handler = new DeleteProposalHandler(context);
        HttpServletRequest request = request();
        when(request.getParameter("walletAddress")).thenReturn(VALID_WALLET);
        when(request.getParameter("signature")).thenReturn(VALID_SIGNATURE);
        when(request.getParameter("contentPath")).thenReturn(WalletPathUtil.getShardRoot(VALID_WALLET) + "/content/doc-1");
        when(request.getParameter("ethereumTxHash")).thenReturn(PRIORITY_TX_HASH);
        when(request.getHeader("X-Client-Id")).thenReturn("client-1");
        StringWriter body = new StringWriter();
        HttpServletResponse response = responseWithBody(body);

        handler.handleDeleteProposal(request, response);

        verify(response).setStatus(HttpServletResponse.SC_FORBIDDEN);
        assertTrue(body.toString().contains("Wallet mismatch"));
    }

    @Test
    public void testHandleDeleteProposalRejectsPathOwnershipViolation() throws Exception {
        ServerContext context = readyContext(new MemoryNodeStore());
        registerClient(context, VALID_WALLET, "client-1");
        DeleteProposalHandler handler = new DeleteProposalHandler(context);
        HttpServletRequest request = request();
        when(request.getParameter("walletAddress")).thenReturn(VALID_WALLET);
        when(request.getParameter("signature")).thenReturn(VALID_SIGNATURE);
        when(request.getParameter("contentPath")).thenReturn("/oak-chain/wrong/path");
        when(request.getParameter("ethereumTxHash")).thenReturn(PRIORITY_TX_HASH);
        StringWriter body = new StringWriter();
        HttpServletResponse response = responseWithBody(body);

        handler.handleDeleteProposal(request, response);

        verify(response).setStatus(HttpServletResponse.SC_FORBIDDEN);
        assertTrue(body.toString().contains("Path ownership violation"));
    }

    @Test
    public void testHandleDeleteProposalRejectsMissingEthereumTxHash() throws Exception {
        ServerContext context = readyContext(new MemoryNodeStore());
        registerClient(context, VALID_WALLET, "client-1");
        DeleteProposalHandler handler = new DeleteProposalHandler(context);
        HttpServletRequest request = request();
        when(request.getParameter("walletAddress")).thenReturn(VALID_WALLET);
        when(request.getParameter("signature")).thenReturn(VALID_SIGNATURE);
        when(request.getParameter("contentPath")).thenReturn(WalletPathUtil.getShardRoot(VALID_WALLET) + "/content/doc-1");
        StringWriter body = new StringWriter();
        HttpServletResponse response = responseWithBody(body);

        handler.handleDeleteProposal(request, response);

        verify(response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
        assertTrue(body.toString().contains("Missing ethereumTxHash parameter"));
    }

    @Test
    public void testHandleDeleteProposalRejectsChainBackedModeForV1() throws Exception {
        System.setProperty("oak.blockchain.mode", "sepolia");
        org.apache.jackrabbit.oak.segment.consensus.config.BlockchainConfig.reset();

        MemoryNodeStore nodeStore = new MemoryNodeStore();
        String contentPath = seedContent(nodeStore, VALID_WALLET);
        ServerContext context = readyContext(nodeStore);
        registerClient(context, VALID_WALLET, "client-1");
        context.proposalQueueManager = mock(ProposalQueueManagerOptimized.class);
        DeleteProposalHandler handler = new DeleteProposalHandler(context);
        HttpServletRequest request = request();
        when(request.getParameter("walletAddress")).thenReturn(VALID_WALLET);
        when(request.getParameter("signature")).thenReturn(VALID_SIGNATURE);
        when(request.getParameter("contentPath")).thenReturn(contentPath);
        when(request.getParameter("ethereumTxHash")).thenReturn(PRIORITY_TX_HASH);
        StringWriter body = new StringWriter();
        HttpServletResponse response = responseWithBody(body);

        handler.handleDeleteProposal(request, response);

        verify(response).setStatus(HttpServletResponse.SC_NOT_IMPLEMENTED);
        assertTrue(body.toString().contains("\"code\":\"delete_chain_mode_unsupported\""));
        assertTrue(body.toString().contains("Delete proposals are only supported in MOCK mode"));
    }

    @Test
    public void testHandleDeleteProposalQueuesDeleteAndTracksGcDebt() throws Exception {
        MemoryNodeStore nodeStore = new MemoryNodeStore();
        String contentPath = seedContent(nodeStore, VALID_WALLET);
        ServerContext context = readyContext(nodeStore);
        registerClient(context, VALID_WALLET, "client-1");
        context.gcAccountManager = new GCAccountManager();
        context.proposalQueueManager = mock(ProposalQueueManagerOptimized.class);
        DeleteProposalHandler handler = new DeleteProposalHandler(context);
        HttpServletRequest request = request();
        when(request.getParameter("walletAddress")).thenReturn(VALID_WALLET);
        when(request.getParameter("signature")).thenReturn(VALID_SIGNATURE);
        when(request.getParameter("contentPath")).thenReturn(contentPath);
        when(request.getParameter("ethereumTxHash")).thenReturn(PRIORITY_TX_HASH);
        StringWriter body = new StringWriter();
        HttpServletResponse response = responseWithBody(body);

        handler.handleDeleteProposal(request, response);

        verify(response).setStatus(HttpServletResponse.SC_ACCEPTED);
        verify(context.proposalQueueManager).queueDeleteProposal(
            anyString(),
            eq(PRIORITY_TX_HASH),
            eq(VALID_WALLET),
            eq(contentPath),
            eq(VALID_SIGNATURE),
            eq(ValidatorEarningsTracker.PaymentTier.STANDARD)
        );
        assertEquals("0.10", context.gcAccountManager.getAccount(VALID_WALLET).totalDebt.toString());
        String json = body.toString();
        assertTrue(json.contains("\"status\":\"accepted\""));
        assertTrue(json.contains("\"type\":\"DELETE\""));
        assertTrue(json.contains("\"tier\":\"STANDARD\""));
        assertTrue(json.contains("\"gcDebtIncurred\":\"0.10\""));
        assertTrue(json.contains("\"totalDebt\":\"0.10\""));
    }

    @Test
    public void testHandleDeleteProposalUsesExplicitPriorityTierWhenProvided() throws Exception {
        MemoryNodeStore nodeStore = new MemoryNodeStore();
        String contentPath = seedContent(nodeStore, VALID_WALLET);
        ServerContext context = readyContext(nodeStore);
        registerClient(context, VALID_WALLET, "client-1");
        context.gcAccountManager = new GCAccountManager();
        context.proposalQueueManager = mock(ProposalQueueManagerOptimized.class);
        DeleteProposalHandler handler = new DeleteProposalHandler(context);
        HttpServletRequest request = request();
        when(request.getParameter("walletAddress")).thenReturn(VALID_WALLET);
        when(request.getParameter("signature")).thenReturn(VALID_SIGNATURE);
        when(request.getParameter("contentPath")).thenReturn(contentPath);
        when(request.getParameter("ethereumTxHash")).thenReturn(PRIORITY_TX_HASH);
        when(request.getParameter("paymentTier")).thenReturn("priority");
        StringWriter body = new StringWriter();
        HttpServletResponse response = responseWithBody(body);

        handler.handleDeleteProposal(request, response);

        verify(response).setStatus(HttpServletResponse.SC_ACCEPTED);
        verify(context.proposalQueueManager).queueDeleteProposal(
            anyString(),
            eq(PRIORITY_TX_HASH),
            eq(VALID_WALLET),
            eq(contentPath),
            eq(VALID_SIGNATURE),
            eq(ValidatorEarningsTracker.PaymentTier.PRIORITY)
        );
        assertTrue(body.toString().contains("\"tier\":\"PRIORITY\""));
    }

    @Test
    public void testHandleDeleteProposalPrefersExplicitPaymentTierOverTxHashHeuristic() throws Exception {
        MemoryNodeStore nodeStore = new MemoryNodeStore();
        String contentPath = seedContent(nodeStore, VALID_WALLET);
        ServerContext context = readyContext(nodeStore);
        registerClient(context, VALID_WALLET, "client-1");
        context.gcAccountManager = new GCAccountManager();
        context.proposalQueueManager = mock(ProposalQueueManagerOptimized.class);
        DeleteProposalHandler handler = new DeleteProposalHandler(context);
        HttpServletRequest request = request();
        when(request.getParameter("walletAddress")).thenReturn(VALID_WALLET);
        when(request.getParameter("signature")).thenReturn(VALID_SIGNATURE);
        when(request.getParameter("contentPath")).thenReturn(contentPath);
        when(request.getParameter("ethereumTxHash")).thenReturn(PRIORITY_TX_HASH);
        when(request.getParameter("paymentTier")).thenReturn("standard");
        StringWriter body = new StringWriter();
        HttpServletResponse response = responseWithBody(body);

        handler.handleDeleteProposal(request, response);

        verify(response).setStatus(HttpServletResponse.SC_ACCEPTED);
        verify(context.proposalQueueManager).queueDeleteProposal(
            anyString(),
            eq(PRIORITY_TX_HASH),
            eq(VALID_WALLET),
            eq(contentPath),
            eq(VALID_SIGNATURE),
            eq(ValidatorEarningsTracker.PaymentTier.STANDARD)
        );
        assertTrue(body.toString().contains("\"tier\":\"STANDARD\""));
    }

    @Test
    public void testHandleDeleteProposalRejectsInvalidPaymentTier() throws Exception {
        MemoryNodeStore nodeStore = new MemoryNodeStore();
        String contentPath = seedContent(nodeStore, VALID_WALLET);
        ServerContext context = readyContext(nodeStore);
        registerClient(context, VALID_WALLET, "client-1");
        context.proposalQueueManager = mock(ProposalQueueManagerOptimized.class);
        DeleteProposalHandler handler = new DeleteProposalHandler(context);
        HttpServletRequest request = request();
        when(request.getParameter("walletAddress")).thenReturn(VALID_WALLET);
        when(request.getParameter("signature")).thenReturn(VALID_SIGNATURE);
        when(request.getParameter("contentPath")).thenReturn(contentPath);
        when(request.getParameter("ethereumTxHash")).thenReturn(PRIORITY_TX_HASH);
        when(request.getParameter("paymentTier")).thenReturn("banana");
        StringWriter body = new StringWriter();
        HttpServletResponse response = responseWithBody(body);

        handler.handleDeleteProposal(request, response);

        verify(response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
        assertTrue(body.toString().contains("Invalid paymentTier"));
    }

    private static ServerContext readyContext(MemoryNodeStore nodeStore) {
        ServerContext context = new ServerContext(
            mock(FileStore.class),
            nodeStore,
            Paths.get("/tmp/store"),
            "http://localhost:8090"
        );
        AeronConsensusEngine engine = mock(AeronConsensusEngine.class);
        when(engine.isClusterHealthy()).thenReturn(true);
        context.aeronConsensusEngine = engine;
        return context;
    }

    private static void registerClient(ServerContext context, String wallet, String clientId) {
        ClientRegistration registration = new ClientRegistration(clientId, "http://author-1:4502", wallet);
        context.registeredClients.put(wallet, registration);
        context.registeredClients.put(clientId, registration);
    }

    private static String seedContent(MemoryNodeStore nodeStore, String wallet) throws Exception {
        String path = WalletPathUtil.getShardRoot(wallet) + "/content/doc-1";
        String[] parts = path.substring(1).split("/");
        NodeBuilder root = nodeStore.getRoot().builder();
        NodeBuilder current = root;
        for (String part : parts) {
            current = current.child(part);
        }
        current.setProperty("contentType", "fragment");
        current.setProperty("message", "hello");
        nodeStore.merge(root, EmptyHook.INSTANCE, CommitInfo.EMPTY);
        return path;
    }

    private static HttpServletRequest request() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getContentType()).thenReturn(null);
        return request;
    }

    private static HttpServletResponse responseWithBody(StringWriter body) throws Exception {
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getWriter()).thenReturn(new PrintWriter(body));
        return response;
    }
}
