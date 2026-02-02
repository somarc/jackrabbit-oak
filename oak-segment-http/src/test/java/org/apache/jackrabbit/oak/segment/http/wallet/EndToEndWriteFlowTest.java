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
package org.apache.jackrabbit.oak.segment.http.wallet;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.math.BigInteger;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * End-to-end test: Sling Author → Mock Validator → Proposal Queue → Raft
 * 
 * <p>This test demonstrates the full flow with mocked components:
 * - SlingWriteProposalService calls mock validator
 * - Mock validator queues proposal
 * - Mock Ethereum event triggers processing
 * - Proposal appended to Raft
 * 
 * <p>NOTE: Full integration with ProposalQueueManager requires oak-segment-consensus dependency.
 * For now, this test focuses on Sling → Validator flow.
 */
public class EndToEndWriteFlowTest {
    
    private MockValidatorServer mockServer;
    // NOTE: These require oak-segment-consensus dependency
    // private EventDrivenEvmBridge evmBridge;
    // private ProposalQueueManager queueManager;
    private MockEthereumContract mockContract;
    
    @Mock
    private SlingAuthorWalletService mockWalletService;
    
    private SlingWriteProposalService slingService;
    
    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        
        // Start mock validator server
        mockServer = new MockValidatorServer();
        mockServer.start();
        
        // Create mock Ethereum contract
        mockContract = new MockEthereumContract();
        
        // NOTE: Full integration requires oak-segment-consensus dependency
        // For now, just test Sling → Validator flow
        
        // Configure mock validator response
        mockServer.mockProposeWrite(request -> {
            String proposalId = UUID.randomUUID().toString();
            String ethereumTxHash = "0x" + UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
            return new MockValidatorServer.MockResponse(
                202,
                String.format(
                    "{\"proposalId\":\"%s\",\"state\":\"PENDING\",\"ethereumTxHash\":\"%s\",\"message\":\"Proposal queued\"}",
                    proposalId,
                    ethereumTxHash
                )
            );
        });
        
        // Create Sling service
        slingService = new SlingWriteProposalService();
        java.lang.reflect.Field validatorUrlField = SlingWriteProposalService.class.getDeclaredField("validatorUrl");
        validatorUrlField.setAccessible(true);
        validatorUrlField.set(slingService, mockServer.getBaseUrl());
        
        java.lang.reflect.Field walletServiceField = SlingWriteProposalService.class.getDeclaredField("walletService");
        walletServiceField.setAccessible(true);
        walletServiceField.set(slingService, mockWalletService);
        
        java.lang.reflect.Field enabledField = SlingWriteProposalService.class.getDeclaredField("enabled");
        enabledField.setAccessible(true);
        enabledField.set(slingService, true);
        
        java.lang.reflect.Field clientIdField = SlingWriteProposalService.class.getDeclaredField("clientId");
        clientIdField.setAccessible(true);
        clientIdField.set(slingService, "test-client");
        
        // Configure mock wallet
        when(mockWalletService.isAvailable()).thenReturn(true);
        when(mockWalletService.getWalletAddress()).thenReturn("0x742d35cc6634c0532925a3b844bc9e7595f0beb0");
        when(mockWalletService.sign(anyString())).thenReturn("0xsig1234567890abcdef");
    }
    
    @After
    public void tearDown() throws Exception {
        if (mockServer != null) {
            mockServer.stop();
        }
    }
    
    @Test
    public void testSlingToValidatorFlow() throws Exception {
        // Step 1: Sling author proposes write
        SlingWriteProposalService.WriteResult result = slingService.proposeWrite("page", "Hello Oak Chain!");
        
        // Verify proposal accepted
        assertTrue("Write proposal should succeed", result.success);
        assertNotNull("Should have response body", result.responseBody);
        
        // Extract proposal ID from response
        String proposalId = extractJsonField(result.responseBody, "proposalId");
        assertNotNull("Should have proposal ID", proposalId);
        
        // Verify validator was called
        assertEquals("Validator should receive 1 request", 1, mockServer.getRequestCount("/v1/propose-write"));
        
        // Verify request parameters
        String walletParam = mockServer.getLastRequestParam("/v1/propose-write", "wallet");
        assertNotNull("Should have wallet parameter", walletParam);
        assertEquals("Should have wallet parameter", "0x742d35cc6634c0532925a3b844bc9e7595f0beb0", walletParam);
    }
    
    @Test
    public void testWriteWithMockContract() throws Exception {
        // Step 1: Simulate Ethereum contract call
        CompletableFuture<String> txHashFuture = mockContract.authorizeWrite(
            "proposal-123",
            "/oak-chain/content/74/2d/35/0x742d35cc.../",
            "0xmessageHash",
            "0xsig...",
            BigInteger.valueOf(1),
            System.currentTimeMillis()
        );
        
        String ethereumTxHash = txHashFuture.get();
        assertNotNull("Should have transaction hash", ethereumTxHash);
        
        // Step 2: Configure validator to accept optional txHash (current service does not send it)
        mockServer.mockProposeWrite(request -> {
            String txHash = request.getParameter("ethereumTxHash");
            if (txHash == null || txHash.isEmpty()) {
                txHash = ethereumTxHash;
            }
            return new MockValidatorServer.MockResponse(
                202,
                "{\"proposalId\":\"test-456\",\"ethereumTxHash\":\"" + txHash + "\",\"state\":\"PENDING\"}"
            );
        });
        
        // Step 3: Call Sling service
        // NOTE: Current SlingWriteProposalService doesn't include ethereumTxHash yet
        // This test demonstrates the pattern for future integration
        SlingWriteProposalService.WriteResult result = slingService.proposeWrite("page", "Test");
        assertTrue("Write should succeed", result.success);
    }
    
    /**
     * Extract JSON field value (simple parser for tests).
     */
    private String extractJsonField(String json, String fieldName) {
        // Try quoted string value
        String pattern1 = "\"" + fieldName + "\"\\s*:\\s*\"([^\"]+)\"";
        java.util.regex.Pattern p1 = java.util.regex.Pattern.compile(pattern1);
        java.util.regex.Matcher m1 = p1.matcher(json);
        if (m1.find()) {
            return m1.group(1);
        }
        
        // Try unquoted value
        String pattern2 = "\"" + fieldName + "\"\\s*:\\s*([^,}\\s]+)";
        java.util.regex.Pattern p2 = java.util.regex.Pattern.compile(pattern2);
        java.util.regex.Matcher m2 = p2.matcher(json);
        if (m2.find()) {
            return m2.group(1);
        }
        
        return null;
    }
}
