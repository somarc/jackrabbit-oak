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

import org.apache.jackrabbit.oak.segment.consensus.aeron.AeronConsensusEngine;
import org.apache.jackrabbit.oak.segment.consensus.config.BlockchainConfig;
import org.apache.jackrabbit.oak.segment.consensus.gc.GCAccountManager;
import org.apache.jackrabbit.oak.segment.consensus.queue.ProposalState;
import org.apache.jackrabbit.oak.segment.consensus.queue.QueuedProposal;
import org.apache.jackrabbit.oak.segment.consensus.queue.ProposalQueueManagerOptimized;
import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.apache.jackrabbit.oak.segment.http.server.ServerContext;
import org.apache.jackrabbit.oak.spi.state.NodeStore;
import org.junit.After;
import org.junit.Test;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class WriteProposalHandlerTest {

    private static final String VALID_WALLET = "0x1111111111111111111111111111111111111111";
    private static final String VALID_SIGNATURE = "0xabcdef12";
    private static final String VALID_TX_HASH = "0xabcdef1234567890";

    @After
    public void tearDown() {
        System.clearProperty("oak.blockchain.mode");
        System.clearProperty("oak.proposal.validator.binary.upload.enabled");
        System.clearProperty("oak.proposal.validator.binary.requires.priority");
        BlockchainConfig.reset();
    }

    @Test
    public void testHandleProposeWriteRejectsWhenEngineNotConfigured() throws Exception {
        ServerContext context = newContext();
        WriteProposalHandler handler = new WriteProposalHandler(context);
        StringWriter body = new StringWriter();
        HttpServletResponse response = responseWithBody(body);

        handler.handleProposeWrite(request(), response);

        verify(response).setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        assertTrue(body.toString().contains("\"error\":\"Aeron consensus engine not configured\""));
    }

    @Test
    public void testHandleProposeWriteRejectsWhenClusterUnhealthy() throws Exception {
        ServerContext context = newContext();
        AeronConsensusEngine engine = baseEngine();
        when(engine.isClusterHealthy()).thenReturn(false);
        when(engine.getUnhealthyReason()).thenReturn("no_leader");
        context.aeronConsensusEngine = engine;
        WriteProposalHandler handler = new WriteProposalHandler(context);
        StringWriter body = new StringWriter();
        HttpServletResponse response = responseWithBody(body);

        handler.handleProposeWrite(request(), response);

        verify(response).setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        assertTrue(body.toString().contains("\"error\":\"Cluster unhealthy: no_leader. Please retry in a few seconds.\""));
        assertEquals(1L, context.apiRejectedRequests.get());
    }

    @Test
    public void testHandleProposeWriteRejectsMissingWalletAddress() throws Exception {
        ServerContext context = readyContext();
        WriteProposalHandler handler = new WriteProposalHandler(context);
        StringWriter body = new StringWriter();
        HttpServletResponse response = responseWithBody(body);

        handler.handleProposeWrite(request(), response);

        verify(response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
        assertTrue(body.toString().contains("Missing wallet address. Please provide a valid Ethereum address"));
        assertEquals(1L, context.apiRejectedRequests.get());
    }

    @Test
    public void testHandleProposeWriteRejectsNonEnterpriseClientIpfsCid() throws Exception {
        ServerContext context = readyContext();
        WriteProposalHandler handler = new WriteProposalHandler(context);
        HttpServletRequest request = request();
        when(request.getParameter("walletAddress")).thenReturn(VALID_WALLET);
        when(request.getParameter("ipfsCid")).thenReturn("bafy-test");
        StringWriter body = new StringWriter();
        HttpServletResponse response = responseWithBody(body);

        handler.handleProposeWrite(request, response);

        verify(response).setStatus(HttpServletResponse.SC_FORBIDDEN);
        String json = body.toString();
        assertTrue(json.contains("\"code\":\"client_ipfs_cid_requires_enterprise_registration\""));
        assertTrue(json.contains("Client-side ipfsCid is restricted to registered enterprise clients."));
        assertEquals(1L, context.apiRejectedRequests.get());
    }

    @Test
    public void testHandleProposeWriteRejectsMissingSignature() throws Exception {
        ServerContext context = readyContext();
        WriteProposalHandler handler = new WriteProposalHandler(context);
        HttpServletRequest request = request();
        when(request.getParameter("walletAddress")).thenReturn(VALID_WALLET);
        StringWriter body = new StringWriter();
        HttpServletResponse response = responseWithBody(body);

        handler.handleProposeWrite(request, response);

        verify(response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
        assertTrue(body.toString().contains("Missing signature. All writes require a signature"));
        assertEquals(1L, context.apiRejectedRequests.get());
    }

    @Test
    public void testHandleProposeWriteRejectsInvalidSignaturePrefix() throws Exception {
        ServerContext context = readyContext();
        WriteProposalHandler handler = new WriteProposalHandler(context);
        HttpServletRequest request = request();
        when(request.getParameter("walletAddress")).thenReturn(VALID_WALLET);
        when(request.getParameter("signature")).thenReturn("abcdef");
        StringWriter body = new StringWriter();
        HttpServletResponse response = responseWithBody(body);

        handler.handleProposeWrite(request, response);

        verify(response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
        assertTrue(body.toString().contains("Invalid signature format: must start with '0x'"));
        assertEquals(1L, context.apiRejectedRequests.get());
    }

    @Test
    public void testHandleProposeWriteRejectsMissingEthereumTxHash() throws Exception {
        ServerContext context = readyContext();
        WriteProposalHandler handler = new WriteProposalHandler(context);
        HttpServletRequest request = request();
        when(request.getParameter("walletAddress")).thenReturn(VALID_WALLET);
        when(request.getParameter("signature")).thenReturn(VALID_SIGNATURE);
        StringWriter body = new StringWriter();
        HttpServletResponse response = responseWithBody(body);

        handler.handleProposeWrite(request, response);

        verify(response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
        assertTrue(body.toString().contains("Missing ethereumTxHash parameter."));
        assertEquals(1L, context.apiRejectedRequests.get());
    }

    @Test
    public void testHandleProposeWriteRejectsInvalidProposalIdFormat() throws Exception {
        ServerContext context = readyContext();
        WriteProposalHandler handler = new WriteProposalHandler(context);
        HttpServletRequest request = request();
        when(request.getParameter("walletAddress")).thenReturn(VALID_WALLET);
        when(request.getParameter("signature")).thenReturn(VALID_SIGNATURE);
        when(request.getParameter("ethereumTxHash")).thenReturn(VALID_TX_HASH);
        when(request.getParameter("proposalId")).thenReturn("bad-proposal-id");
        StringWriter body = new StringWriter();
        HttpServletResponse response = responseWithBody(body);

        handler.handleProposeWrite(request, response);

        verify(response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
        assertTrue(body.toString().contains("Invalid proposalId format. Expected 0x-prefixed 32-byte hex or UUID."));
        assertEquals(1L, context.apiRejectedRequests.get());
    }

    @Test
    public void testHandleProposeWriteImmediateIngressFailureReturnsServerError() throws Exception {
        ServerContext context = readyContext();
        when(context.aeronConsensusEngine.sendWriteThroughIngress(
            anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString()
        )).thenReturn(false);
        WriteProposalHandler handler = new WriteProposalHandler(context);
        HttpServletRequest request = request();
        when(request.getParameter("walletAddress")).thenReturn(VALID_WALLET);
        when(request.getParameter("signature")).thenReturn(VALID_SIGNATURE);
        when(request.getParameter("ethereumTxHash")).thenReturn(VALID_TX_HASH);
        StringWriter body = new StringWriter();
        HttpServletResponse response = responseWithBody(body);

        handler.handleProposeWrite(request, response);

        verify(response).setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        assertTrue(body.toString().contains("Failed to send write through Aeron ingress channel"));
    }

    @Test
    public void testHandleProposeWriteRejectsInvalidPaymentTierWhenQueueConfigured() throws Exception {
        ServerContext context = readyContext();
        context.proposalQueueManager = mock(ProposalQueueManagerOptimized.class);
        WriteProposalHandler handler = new WriteProposalHandler(context);
        HttpServletRequest request = request();
        when(request.getParameter("walletAddress")).thenReturn(VALID_WALLET);
        when(request.getParameter("signature")).thenReturn(VALID_SIGNATURE);
        when(request.getParameter("ethereumTxHash")).thenReturn(VALID_TX_HASH);
        when(request.getParameter("paymentTier")).thenReturn("gold");
        StringWriter body = new StringWriter();
        HttpServletResponse response = responseWithBody(body);

        handler.handleProposeWrite(request, response);

        verify(response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
        assertTrue(body.toString().contains("Invalid paymentTier: 'gold'. Must be 'standard', 'express', or 'priority'."));
        assertEquals(1L, context.apiRejectedRequests.get());
    }

    @Test
    public void testHandleProposeWriteReturnsQueueOverloadedWhenAdmissionRejected() throws Exception {
        ServerContext context = readyContext();
        ProposalQueueManagerOptimized queueManager = mock(ProposalQueueManagerOptimized.class);
        context.proposalQueueManager = queueManager;
        when(queueManager.queueProposal(
            anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.nullable(String.class),
            org.mockito.ArgumentMatchers.nullable(String.class), anyString(),
            org.mockito.ArgumentMatchers.nullable(String.class)
        )).thenThrow(new java.util.concurrent.RejectedExecutionException("queue_overloaded"));
        WriteProposalHandler handler = new WriteProposalHandler(context);
        HttpServletRequest request = request();
        when(request.getParameter("walletAddress")).thenReturn(VALID_WALLET);
        when(request.getParameter("signature")).thenReturn(VALID_SIGNATURE);
        when(request.getParameter("ethereumTxHash")).thenReturn(VALID_TX_HASH);
        when(request.getParameter("message")).thenReturn("hello");
        when(request.getParameter("contentType")).thenReturn("page");
        StringWriter body = new StringWriter();
        HttpServletResponse response = responseWithBody(body);

        handler.handleProposeWrite(request, response);

        verify(response).setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        assertTrue(body.toString().contains("\"code\":\"queue_overloaded\""));
        assertEquals(1L, context.apiRejectedRequests.get());
    }

    @Test
    public void testHandleProposeWriteRejectsValidatorHostedBinaryWithoutPriorityByDefault() throws Exception {
        System.setProperty("oak.blockchain.mode", "mock");
        BlockchainConfig.reset();
        ServerContext context = readyContext();
        context.proposalQueueManager = mock(ProposalQueueManagerOptimized.class);
        WriteProposalHandler handler = new WriteProposalHandler(context);
        HttpServletRequest request = request();
        when(request.getParameter("walletAddress")).thenReturn(VALID_WALLET);
        when(request.getParameter("signature")).thenReturn(VALID_SIGNATURE);
        when(request.getParameter("ethereumTxHash")).thenReturn(VALID_TX_HASH);
        when(request.getParameter("paymentTier")).thenReturn("standard");
        when(request.getParameter("binaryData")).thenReturn("AQID");
        when(request.getParameter("mimeType")).thenReturn("application/octet-stream");
        StringWriter body = new StringWriter();
        HttpServletResponse response = responseWithBody(body);

        handler.handleProposeWrite(request, response);

        verify(response).setStatus(HttpServletResponse.SC_PAYMENT_REQUIRED);
        assertTrue(body.toString().contains("\"code\":\"validator_binary_requires_priority\""));
        assertEquals(1L, context.apiRejectedRequests.get());
    }

    @Test
    public void testHandleProposeWriteAllowsValidatorHostedBinaryWhenPriorityRequirementDisabled() throws Exception {
        System.setProperty("oak.blockchain.mode", "mock");
        System.setProperty("oak.proposal.validator.binary.requires.priority", "false");
        BlockchainConfig.reset();
        ServerContext context = readyContext();
        ProposalQueueManagerOptimized queueManager = mock(ProposalQueueManagerOptimized.class);
        context.proposalQueueManager = queueManager;
        when(queueManager.queueProposal(
            anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.nullable(String.class),
            org.mockito.ArgumentMatchers.nullable(String.class), anyString(),
            org.mockito.ArgumentMatchers.nullable(String.class)
        )).thenReturn(new QueuedProposal(
            "proposal-1",
            VALID_TX_HASH,
            null,
            System.currentTimeMillis(),
            System.currentTimeMillis() + 300_000L,
            ProposalState.PENDING
        ));
        WriteProposalHandler handler = new WriteProposalHandler(context);
        HttpServletRequest request = request();
        when(request.getParameter("walletAddress")).thenReturn(VALID_WALLET);
        when(request.getParameter("signature")).thenReturn(VALID_SIGNATURE);
        when(request.getParameter("ethereumTxHash")).thenReturn(VALID_TX_HASH);
        when(request.getParameter("paymentTier")).thenReturn("standard");
        when(request.getParameter("binaryData")).thenReturn("AQID");
        when(request.getParameter("mimeType")).thenReturn("application/octet-stream");
        when(request.getParameter("contentType")).thenReturn("page");
        when(request.getParameter("message")).thenReturn("hello");
        StringWriter body = new StringWriter();
        HttpServletResponse response = responseWithBody(body);

        handler.handleProposeWrite(request, response);

        verify(response).setStatus(HttpServletResponse.SC_ACCEPTED);
        assertTrue(body.toString().contains("\"status\":\"accepted\""));
    }

    @Test
    public void testHandleProposeWriteRejectsValidatorHostedBinaryWhenCapabilityDisabled() throws Exception {
        System.setProperty("oak.blockchain.mode", "mock");
        System.setProperty("oak.proposal.validator.binary.upload.enabled", "false");
        BlockchainConfig.reset();
        ServerContext context = readyContext();
        context.proposalQueueManager = mock(ProposalQueueManagerOptimized.class);
        WriteProposalHandler handler = new WriteProposalHandler(context);
        HttpServletRequest request = request();
        when(request.getParameter("walletAddress")).thenReturn(VALID_WALLET);
        when(request.getParameter("signature")).thenReturn(VALID_SIGNATURE);
        when(request.getParameter("ethereumTxHash")).thenReturn(VALID_TX_HASH);
        when(request.getParameter("paymentTier")).thenReturn("priority");
        when(request.getParameter("binaryData")).thenReturn("AQID");
        when(request.getParameter("mimeType")).thenReturn("application/octet-stream");
        StringWriter body = new StringWriter();
        HttpServletResponse response = responseWithBody(body);

        handler.handleProposeWrite(request, response);

        verify(response).setStatus(HttpServletResponse.SC_FORBIDDEN);
        assertTrue(body.toString().contains("\"code\":\"validator_binary_upload_disabled\""));
        assertEquals(1L, context.apiRejectedRequests.get());
    }

    @Test
    public void testHandleProposeWriteBlocksWritesWhenGcDebtExceeded() throws Exception {
        ServerContext context = readyContext();
        GCAccountManager accountManager = new GCAccountManager();
        accountManager.addDebt(VALID_WALLET.toLowerCase(), "/oak-chain/demo", 1200L);
        accountManager.convertAllPendingToExecuted();
        context.gcAccountManager = accountManager;
        WriteProposalHandler handler = new WriteProposalHandler(context);
        HttpServletRequest request = request();
        when(request.getParameter("walletAddress")).thenReturn(VALID_WALLET);
        StringWriter body = new StringWriter();
        HttpServletResponse response = responseWithBody(body);

        handler.handleProposeWrite(request, response);

        verify(response).setStatus(402);
        assertTrue(body.toString().contains("\"code\":\"write_blocked_gc_debt\""));
        assertTrue(body.toString().contains("\"paymentUrl\":\"/v1/gc/account/" + VALID_WALLET.toLowerCase() + "/pay\""));
    }

    private static ServerContext readyContext() {
        ServerContext context = newContext();
        context.aeronConsensusEngine = baseEngine();
        return context;
    }

    private static ServerContext newContext() {
        return new ServerContext(
            mock(FileStore.class),
            mock(NodeStore.class),
            Paths.get("/tmp/store"),
            "http://localhost:8090"
        );
    }

    private static AeronConsensusEngine baseEngine() {
        AeronConsensusEngine engine = mock(AeronConsensusEngine.class);
        when(engine.isClusterHealthy()).thenReturn(true);
        return engine;
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
