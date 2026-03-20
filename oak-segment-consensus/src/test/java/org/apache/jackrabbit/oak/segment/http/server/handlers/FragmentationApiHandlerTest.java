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

import org.apache.jackrabbit.oak.segment.consensus.fragmentation.FragmentationTracker;
import org.apache.jackrabbit.oak.segment.consensus.gc.EntityGCAccount;
import org.apache.jackrabbit.oak.segment.consensus.gc.GCAccountManager;
import org.apache.jackrabbit.oak.segment.consensus.gc.GCExecutionResult;
import org.apache.jackrabbit.oak.segment.consensus.gc.GCProposal;
import org.apache.jackrabbit.oak.segment.consensus.gc.GCProposalManager;
import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.apache.jackrabbit.oak.segment.http.server.ServerContext;
import org.apache.jackrabbit.oak.spi.state.NodeStore;
import org.junit.Before;
import org.junit.Test;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.nio.file.Paths;
import java.util.Collections;

import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class FragmentationApiHandlerTest {

    private ServerContext context;
    private FragmentationApiHandler handler;
    private HttpServletRequest request;
    private HttpServletResponse response;
    private StringWriter body;

    @Before
    public void setUp() throws Exception {
        context = new ServerContext(
            mock(FileStore.class),
            mock(NodeStore.class),
            Paths.get("/tmp/store"),
            "http://localhost:8090"
        );
        handler = new FragmentationApiHandler(context);
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        body = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(body));
    }

    @Test
    public void testGetAllMetricsReturns503WhenTrackerMissing() throws Exception {
        handler.handleGetAllMetrics(request, response);

        verify(response).setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        assertTrue(body.toString().contains("\"error\":\"Fragmentation tracker not initialized\""));
    }

    @Test
    public void testGetAllMetricsSerializesTrackedEntities() throws Exception {
        FragmentationTracker tracker = new FragmentationTracker();
        tracker.recordWrite("0xabc", "data00001a.tar", 1024L);
        context.fragmentationTracker = tracker;

        handler.handleGetAllMetrics(request, response);

        verify(response).setStatus(HttpServletResponse.SC_OK);
        String json = body.toString();
        assertTrue(json.contains("\"totalEntities\":1"));
        assertTrue(json.contains("\"walletAddress\":\"0xabc\""));
        assertTrue(json.contains("\"tarFilesCreated\":1"));
        assertTrue(json.contains("\"fragmentationTaxFormatted\":"));
    }

    @Test
    public void testGetGcStatusReturnsSummary() throws Exception {
        GCProposalManager gcManager = mock(GCProposalManager.class);
        GCProposal proposal = new GCProposal();
        proposal.proposalId = "gc-1";
        GCExecutionResult result = new GCExecutionResult();
        result.timestamp = 12345L;
        result.actualReclaimedSizeMB = 42L;
        result.actualCostUSDC = new BigDecimal("4.25");
        context.gcProposalManager = gcManager;
        when(gcManager.getPendingProposals()).thenReturn(Collections.singletonList(proposal));
        when(gcManager.getGCHistory(1)).thenReturn(Collections.singletonList(result));

        handler.handleGetGcStatus(request, response);

        verify(response).setStatus(HttpServletResponse.SC_OK);
        String json = body.toString();
        assertTrue(json.contains("\"pendingProposals\":1"));
        assertTrue(json.contains("\"lastGcRun\":12345"));
        assertTrue(json.contains("\"lastGcReclaimedMB\":42"));
        assertTrue(json.contains("\"lastGcCostUSDC\":\"4.25\""));
    }

    @Test
    public void testProposeGcRejectsMissingWalletAddress() throws Exception {
        context.gcProposalManager = mock(GCProposalManager.class);
        when(request.getContentType()).thenReturn("application/x-www-form-urlencoded");
        when(request.getParameter("walletAddress")).thenReturn(null);
        when(request.getParameter("wallet")).thenReturn(null);

        handler.handleProposeGC(request, response);

        verify(response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
        assertTrue(body.toString().contains("walletAddress parameter required"));
    }

    @Test
    public void testProposeGcAcceptsJsonBody() throws Exception {
        GCProposalManager gcManager = mock(GCProposalManager.class);
        GCProposal proposal = new GCProposal();
        proposal.proposalId = "gc-proposal-1";
        proposal.proposerWallet = "0xabc";
        proposal.targetRevision = "rev-1";
        proposal.estimatedReclaimableSizeMB = 64L;
        proposal.estimatedCostUSDC = new BigDecimal("6.40");
        proposal.fragmentationOverheadMB = 12L;
        proposal.fragmentationCostUSDC = new BigDecimal("1.20");
        context.gcProposalManager = gcManager;
        when(request.getContentType()).thenReturn("application/json");
        when(request.getReader()).thenReturn(readerFor("{\"walletAddress\":\"0xabc\",\"targetRevision\":\"rev-1\"}"));
        when(gcManager.proposeGC("0xabc", "rev-1")).thenReturn(proposal);

        handler.handleProposeGC(request, response);

        verify(gcManager).proposeGC("0xabc", "rev-1");
        verify(response).setStatus(HttpServletResponse.SC_OK);
        String json = body.toString();
        assertTrue(json.contains("\"proposalId\":\"gc-proposal-1\""));
        assertTrue(json.contains("\"proposerWallet\":\"0xabc\""));
        assertTrue(json.contains("\"targetRevision\":\"rev-1\""));
    }

    @Test
    public void testExecuteGcRejectsMissingProposalId() throws Exception {
        context.gcProposalManager = mock(GCProposalManager.class);
        when(request.getContentType()).thenReturn("application/x-www-form-urlencoded");
        when(request.getParameter("proposalId")).thenReturn(null);

        handler.handleExecuteGC(request, response);

        verify(response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
        assertTrue(body.toString().contains("proposalId parameter required"));
    }

    @Test
    public void testVoteGcRejectsMissingProposalId() throws Exception {
        context.gcProposalManager = mock(GCProposalManager.class);
        when(request.getContentType()).thenReturn("application/x-www-form-urlencoded");
        when(request.getParameter("proposalId")).thenReturn(null);

        handler.handleVoteGC(request, response);

        verify(response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
        assertTrue(body.toString().contains("proposalId parameter required"));
    }

    @Test
    public void testGetGcAccountReturnsAccountState() throws Exception {
        GCAccountManager accountManager = new GCAccountManager();
        accountManager.addDebt("0xwallet", "/oak-chain/test", 5L);
        accountManager.convertAllPendingToExecuted();
        context.gcAccountManager = accountManager;

        handler.handleGetGCAccount(request, response, "0xwallet");

        verify(response).setStatus(HttpServletResponse.SC_OK);
        String json = body.toString();
        assertTrue(json.contains("\"walletAddress\":\"0xwallet\""));
        assertTrue(json.contains("\"totalDebt\":\"0.50\""));
        assertTrue(json.contains("\"executedDebt\":\"0.50\""));
    }

    @Test
    public void testPayGcDebtRejectsMissingAmount() throws Exception {
        context.gcAccountManager = new GCAccountManager();
        when(request.getParameter("amount")).thenReturn(null);

        handler.handlePayGCDebt(request, response, "0xwallet");

        verify(response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
        assertTrue(body.toString().contains("\"error\":\"amount parameter required\""));
    }

    @Test
    public void testTriggerGcConvertsPendingDebtAndReportsBlockedWallets() throws Exception {
        GCAccountManager accountManager = new GCAccountManager();
        EntityGCAccount account = accountManager.getAccount("0xblocked");
        account.totalDebt = new BigDecimal("150.00");
        account.executedDebt = BigDecimal.ZERO;
        account.debtLimit = new BigDecimal("100.00");
        context.gcAccountManager = accountManager;

        handler.handleTriggerGC(request, response);

        verify(response).setStatus(HttpServletResponse.SC_OK);
        String json = body.toString();
        assertTrue(json.contains("\"success\":true"));
        assertTrue(json.contains("\"entitiesWithExecutedDebt\":1"));
        assertTrue(json.contains("\"entitiesBlocked\":1"));
        assertTrue(json.contains("\"blockedWallets\":[\"0xblocked\"]"));
    }

    private static BufferedReader readerFor(String json) {
        return new BufferedReader(new StringReader(json));
    }
}
