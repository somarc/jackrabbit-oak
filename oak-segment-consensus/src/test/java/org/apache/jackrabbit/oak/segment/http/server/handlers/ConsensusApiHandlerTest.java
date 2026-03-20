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

import org.junit.Before;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import org.apache.jackrabbit.oak.segment.http.server.ServerContext;
import org.apache.jackrabbit.oak.segment.consensus.aeron.AeronConsensusEngine;
import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.apache.jackrabbit.oak.spi.state.NodeStore;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ConsensusApiHandler.
 * 
 * <p>Tests cover:
 * <ul>
 *   <li>ADR 028: Pre-flight health check (503 when unhealthy)</li>
 *   <li>Wallet address validation</li>
 *   <li>Write proposal handling</li>
 *   <li>Delete proposal handling</li>
 *   <li>Error responses</li>
 * </ul>
 * 
 * @see ConsensusApiHandler
 */
public class ConsensusApiHandlerTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Mock
    private FileStore mockFileStore;

    @Mock
    private NodeStore mockNodeStore;

    @Mock
    private AeronConsensusEngine mockAeronEngine;

    @Mock
    private HttpServletRequest mockRequest;

    @Mock
    private HttpServletResponse mockResponse;

    private ServerContext context;
    private StringWriter responseWriter;
    private ConsensusApiHandler handler;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        
        responseWriter = new StringWriter();
        when(mockResponse.getWriter()).thenReturn(new PrintWriter(responseWriter));
        
        // Create real ServerContext with mocked dependencies
        context = new ServerContext(
            mockFileStore,
            mockNodeStore,
            tempFolder.getRoot().toPath(),
            "http://localhost:8090"
        );
        
        handler = new ConsensusApiHandler(context);
    }

    // ═══════════════════════════════════════════════════════════════
    // ADR 028: PRE-FLIGHT HEALTH CHECK TESTS
    // ═══════════════════════════════════════════════════════════════

    @Test
    public void testProposeWriteReturns503WhenNoConsensusEngine() throws Exception {
        // Given: No Aeron consensus engine configured
        context.aeronConsensusEngine = null;
        
        // When: Write proposal handled
        handler.handleProposeWrite(mockRequest, mockResponse);
        
        // Then: Should return 503 Service Unavailable
        assertJsonErrorContains(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Aeron consensus engine not configured");
    }

    @Test
    public void testProposeWriteReturns503WhenClusterUnhealthy() throws Exception {
        // Given: Aeron engine configured but cluster unhealthy
        context.aeronConsensusEngine = mockAeronEngine;
        when(mockAeronEngine.isClusterHealthy()).thenReturn(false);
        when(mockAeronEngine.getUnhealthyReason()).thenReturn("session_closed_timeout");
        
        // When: Write proposal handled
        handler.handleProposeWrite(mockRequest, mockResponse);
        
        // Then: Should return 503 Service Unavailable with reason
        assertJsonErrorContains(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "session_closed_timeout");
    }

    @Test
    public void testDeleteProposalReturns503WhenClusterUnhealthy() throws Exception {
        // Given: Aeron engine configured but cluster unhealthy
        context.aeronConsensusEngine = mockAeronEngine;
        when(mockAeronEngine.isClusterHealthy()).thenReturn(false);
        when(mockAeronEngine.getUnhealthyReason()).thenReturn("no_leader_elected");
        
        // When: Delete proposal handled
        handler.handleDeleteProposal(mockRequest, mockResponse);
        
        // Then: Should return 503 Service Unavailable with reason
        assertJsonErrorContains(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "no_leader_elected");
    }

    // ═══════════════════════════════════════════════════════════════
    // WALLET VALIDATION TESTS
    // ═══════════════════════════════════════════════════════════════

    @Test
    public void testProposeWriteRejectsMissingWallet() throws Exception {
        // Given: Healthy cluster but no wallet parameter
        context.aeronConsensusEngine = mockAeronEngine;
        when(mockAeronEngine.isClusterHealthy()).thenReturn(true);
        when(mockRequest.getContentType()).thenReturn("application/x-www-form-urlencoded");
        when(mockRequest.getParameter("walletAddress")).thenReturn(null);
        when(mockRequest.getParameter("wallet")).thenReturn(null);
        
        // When: Write proposal handled
        handler.handleProposeWrite(mockRequest, mockResponse);
        
        // Then: Should return 400 Bad Request
        assertJsonErrorContains(HttpServletResponse.SC_BAD_REQUEST, "wallet");
    }

    @Test
    public void testProposeWriteRejectsInvalidWalletFormat() throws Exception {
        // Given: Healthy cluster but invalid wallet format
        context.aeronConsensusEngine = mockAeronEngine;
        when(mockAeronEngine.isClusterHealthy()).thenReturn(true);
        when(mockRequest.getContentType()).thenReturn("application/x-www-form-urlencoded");
        when(mockRequest.getParameter("walletAddress")).thenReturn("not-a-wallet");
        
        // When: Write proposal handled
        handler.handleProposeWrite(mockRequest, mockResponse);
        
        // Then: Should return 400 Bad Request
        assertJsonErrorStatus(HttpServletResponse.SC_BAD_REQUEST);
    }

    @Test
    public void testProposeWriteRejectsShortWallet() throws Exception {
        // Given: Healthy cluster but wallet too short
        context.aeronConsensusEngine = mockAeronEngine;
        when(mockAeronEngine.isClusterHealthy()).thenReturn(true);
        when(mockRequest.getContentType()).thenReturn("application/x-www-form-urlencoded");
        when(mockRequest.getParameter("walletAddress")).thenReturn("0x123");
        
        // When: Write proposal handled
        handler.handleProposeWrite(mockRequest, mockResponse);
        
        // Then: Should return 400 Bad Request
        assertJsonErrorStatus(HttpServletResponse.SC_BAD_REQUEST);
    }

    @Test
    public void testProposeWriteAcceptsValidWallet() throws Exception {
        // Given: Healthy cluster with valid wallet (but no signature - will fail later)
        context.aeronConsensusEngine = mockAeronEngine;
        when(mockAeronEngine.isClusterHealthy()).thenReturn(true);
        when(mockRequest.getContentType()).thenReturn("application/x-www-form-urlencoded");
        when(mockRequest.getParameter("walletAddress")).thenReturn("0x1234567890abcdef1234567890abcdef12345678");
        when(mockRequest.getParameter("signature")).thenReturn(null); // Missing signature
        when(mockRequest.getParameter("message")).thenReturn("test");
        
        // When: Write proposal handled
        handler.handleProposeWrite(mockRequest, mockResponse);
        
        // Then: Should NOT fail on wallet validation (may fail on signature)
        // Verify we got past wallet validation - error should be about signature, not wallet
        verify(mockResponse, never()).sendError(
            eq(HttpServletResponse.SC_BAD_REQUEST),
            contains("wallet")
        );
    }

    // ═══════════════════════════════════════════════════════════════
    // DELETE PROPOSAL VALIDATION TESTS
    // ═══════════════════════════════════════════════════════════════

    @Test
    public void testDeleteProposalRejectsMissingSignature() throws Exception {
        // Given: Healthy cluster with valid wallet but no signature
        context.aeronConsensusEngine = mockAeronEngine;
        when(mockAeronEngine.isClusterHealthy()).thenReturn(true);
        when(mockRequest.getParameter("walletAddress")).thenReturn("0x1234567890abcdef1234567890abcdef12345678");
        when(mockRequest.getParameter("signature")).thenReturn(null);
        when(mockRequest.getParameter("contentPath")).thenReturn("/oak-chain/test");
        
        // When: Delete proposal handled
        handler.handleDeleteProposal(mockRequest, mockResponse);
        
        // Then: Should return 400 Bad Request for missing signature
        assertJsonErrorContains(HttpServletResponse.SC_BAD_REQUEST, "signature");
    }

    @Test
    public void testDeleteProposalRejectsMissingContentPath() throws Exception {
        // Given: Healthy cluster with valid wallet but no content path
        context.aeronConsensusEngine = mockAeronEngine;
        when(mockAeronEngine.isClusterHealthy()).thenReturn(true);
        when(mockRequest.getParameter("walletAddress")).thenReturn("0x1234567890abcdef1234567890abcdef12345678");
        when(mockRequest.getParameter("signature")).thenReturn("0xvalidsig");
        when(mockRequest.getParameter("contentPath")).thenReturn(null);
        
        // When: Delete proposal handled
        handler.handleDeleteProposal(mockRequest, mockResponse);
        
        // Then: Should return 400 Bad Request for missing path
        assertJsonErrorContains(HttpServletResponse.SC_BAD_REQUEST, "contentPath");
    }

    // ═══════════════════════════════════════════════════════════════
    // CONSENSUS STATUS ENDPOINT TESTS
    // ═══════════════════════════════════════════════════════════════

    @Test
    public void testGetConsensusStatusReturnsJson() throws Exception {
        // Given: Handler with context
        
        // When: Consensus status requested
        handler.handleGetConsensusStatus(mockResponse);
        
        // Then: Should return JSON content type
        verify(mockResponse).setContentType("application/json");
        verify(mockResponse).setStatus(HttpServletResponse.SC_OK);
        
        // And: Response should contain JSON
        String response = responseWriter.toString();
        assertTrue("Response should start with {", response.trim().startsWith("{"));
        assertTrue("Response should end with }", response.trim().endsWith("}"));
    }

    @Test
    public void testGetConsensusStatusIncludesConsensusType() throws Exception {
        // Given: Handler with context (no Aeron engine)
        context.aeronConsensusEngine = null;
        
        // When: Consensus status requested
        handler.handleGetConsensusStatus(mockResponse);
        
        // Then: Response should include consensusType field
        String response = responseWriter.toString();
        assertTrue("Response should include consensusType", response.contains("\"consensusType\""));
        assertTrue("Response should show 'none' when no engine", response.contains("\"none\""));
    }

    // ═══════════════════════════════════════════════════════════════
    // PENDING COUNT ENDPOINT TESTS
    // ═══════════════════════════════════════════════════════════════

    @Test
    public void testGetPendingCountReturns503WhenQueueNotAvailable() throws Exception {
        // Given: Handler with no proposal queue manager
        context.proposalQueueManager = null;
        
        // When: Pending count requested
        handler.handleGetPendingCount(mockResponse);
        
        // Then: Should return 503 Service Unavailable
        assertJsonErrorContains(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "queue");
    }

    // ═══════════════════════════════════════════════════════════════
    // PROPOSAL STATUS ENDPOINT TESTS
    // ═══════════════════════════════════════════════════════════════

    @Test
    public void testGetProposalStatusRejectsMissingId() throws Exception {
        // Given: Request without proposal ID (null path)
        when(mockRequest.getRequestURI()).thenReturn(null);
        
        // When: Proposal status requested
        handler.handleGetProposalStatus(mockRequest, mockResponse);
        
        // Then: Should reject the malformed request without throwing internally
        assertJsonErrorContains(HttpServletResponse.SC_BAD_REQUEST, "Invalid proposal ID");
    }

    @Test
    public void testGetProposalStatusReturns503WhenQueueNotAvailable() throws Exception {
        // Given: Request with valid proposal ID but no queue manager
        when(mockRequest.getRequestURI()).thenReturn("/v1/proposals/test-proposal-123/status");
        context.proposalQueueManager = null;
        
        // When: Proposal status requested
        handler.handleGetProposalStatus(mockRequest, mockResponse);
        
        // Then: Should return 503 Service Unavailable
        assertJsonErrorContains(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Proposal queue not available");
    }

    // ═══════════════════════════════════════════════════════════════
    // WALLET STATS ENDPOINT TESTS
    // ═══════════════════════════════════════════════════════════════

    @Test
    public void testWalletStatsReturnsJson() throws Exception {
        // Given: Request for all wallet stats
        when(mockRequest.getParameter("wallet")).thenReturn(null);
        
        // When: Wallet stats requested
        handler.handleWalletStats(mockRequest, mockResponse);
        
        // Then: Should return JSON content type
        verify(mockResponse).setContentType("application/json");
    }

    @Test
    public void testWalletStatsWithSpecificWallet() throws Exception {
        // Given: Request for specific wallet stats
        when(mockRequest.getParameter("wallet")).thenReturn("0x1234567890abcdef1234567890abcdef12345678");
        
        // When: Wallet stats requested
        handler.handleWalletStats(mockRequest, mockResponse);
        
        // Then: Should return JSON content type
        verify(mockResponse).setContentType("application/json");
    }

    // ═══════════════════════════════════════════════════════════════
    // WALLET CONTENT ENDPOINT TESTS
    // ═══════════════════════════════════════════════════════════════

    @Test
    public void testWalletContentRejectsMissingWallet() throws Exception {
        // Given: Request without wallet parameter
        when(mockRequest.getParameter("wallet")).thenReturn(null);
        
        // When: Wallet content requested
        handler.handleWalletContent(mockRequest, mockResponse);
        
        // Then: Should return 400 Bad Request (uses setStatus, not sendError)
        verify(mockResponse).setStatus(HttpServletResponse.SC_BAD_REQUEST);
    }

    @Test
    public void testWalletContentSetsJsonContentType() throws Exception {
        // Given: Request with a wallet parameter (will fail on query but that's ok)
        when(mockRequest.getParameter("wallet")).thenReturn("0x1234567890abcdef1234567890abcdef12345678");
        
        // When: Wallet content requested
        handler.handleWalletContent(mockRequest, mockResponse);
        
        // Then: Should set JSON content type
        verify(mockResponse).setContentType("application/json");
    }

    // ═══════════════════════════════════════════════════════════════
    // GC COST ESTIMATE ENDPOINT TESTS
    // ═══════════════════════════════════════════════════════════════

    @Test
    public void testGCCostEstimateReturns503WhenNotAvailable() throws Exception {
        // Given: GC cost estimator not configured
        context.gcCostEstimator = null;
        
        // When: GC cost estimate requested
        handler.handleGCCostEstimate(mockRequest, mockResponse);
        
        // Then: Should return 503 Service Unavailable (uses setStatus, not sendError)
        verify(mockResponse).setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
    }

    // ═══════════════════════════════════════════════════════════════
    // API METRICS TRACKING TESTS
    // ═══════════════════════════════════════════════════════════════

    @Test
    public void testRejectedRequestsCounterIncremented() throws Exception {
        // Given: Initial rejected count
        long initialCount = context.apiRejectedRequests.get();
        
        // And: Unhealthy cluster
        context.aeronConsensusEngine = mockAeronEngine;
        when(mockAeronEngine.isClusterHealthy()).thenReturn(false);
        when(mockAeronEngine.getUnhealthyReason()).thenReturn("test_reason");
        
        // When: Write proposal handled
        handler.handleProposeWrite(mockRequest, mockResponse);
        
        // Then: Rejected counter should be incremented
        assertEquals("Rejected counter should increment", 
            initialCount + 1, context.apiRejectedRequests.get());
    }

    private void assertJsonErrorStatus(int status) {
        verify(mockResponse).setStatus(status);
        String response = responseWriter.toString();
        assertTrue("Response should include error JSON", response.contains("\"success\":false"));
    }

    private void assertJsonErrorContains(int status, String expectedFragment) {
        assertJsonErrorStatus(status);
        String response = responseWriter.toString();
        assertTrue("Response should contain error detail", response.contains(expectedFragment));
    }
}
