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

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Test SlingWriteProposalService with mocked validator server.
 */
public class SlingWriteProposalServiceTest {
    
    private MockValidatorServer mockServer;
    private SlingWriteProposalService service;
    
    @Mock
    private SlingAuthorWalletService mockWalletService;
    
    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        
        // Start mock validator server
        mockServer = new MockValidatorServer();
        mockServer.start();
        
        // Create service
        service = new SlingWriteProposalService();
        
        // Use reflection to set private fields (for testing)
        java.lang.reflect.Field validatorUrlField = SlingWriteProposalService.class.getDeclaredField("validatorUrl");
        validatorUrlField.setAccessible(true);
        validatorUrlField.set(service, mockServer.getBaseUrl());
        
        java.lang.reflect.Field walletServiceField = SlingWriteProposalService.class.getDeclaredField("walletService");
        walletServiceField.setAccessible(true);
        walletServiceField.set(service, mockWalletService);
        
        java.lang.reflect.Field enabledField = SlingWriteProposalService.class.getDeclaredField("enabled");
        enabledField.setAccessible(true);
        enabledField.set(service, true);
        
        java.lang.reflect.Field clientIdField = SlingWriteProposalService.class.getDeclaredField("clientId");
        clientIdField.setAccessible(true);
        clientIdField.set(service, "test-client");
        
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
    public void testProposeWriteSuccess() throws Exception {
        // Configure mock response
        mockServer.mockProposeWrite(request -> {
            return new MockValidatorServer.MockResponse(
                202,
                "{\"proposalId\":\"test-123\",\"state\":\"PENDING\",\"message\":\"Proposal queued\"}"
            );
        });
        
        // Propose write
        SlingWriteProposalService.WriteResult result = service.proposeWrite("page", "Hello Oak Chain!");
        
        // Verify
        assertTrue("Write should succeed", result.success);
        assertEquals("Should have proposal ID", "test-123", 
            extractJsonField(result.responseBody, "proposalId"));
        
        // Verify request was made
        assertEquals("Should have made 1 request", 1, mockServer.getRequestCount("/v1/propose-write"));
    }
    
    @Test
    public void testProposeWriteWithEthereumTxHash() throws Exception {
        // Configure mock to accept optional ethereumTxHash
        mockServer.mockProposeWrite(request -> {
            String ethereumTxHash = request.getParameter("ethereumTxHash");
            return new MockValidatorServer.MockResponse(
                202,
                String.format("{\"proposalId\":\"test-456\",\"ethereumTxHash\":\"%s\",\"state\":\"PENDING\"}", 
                    ethereumTxHash == null ? "" : ethereumTxHash)
            );
        });
        
        // Note: Current SlingWriteProposalService doesn't include ethereumTxHash
        // This test shows what's needed for full integration
        // TODO: Update SlingWriteProposalService to call authorizeWrite() first

        // For now, test will succeed (validator accepts without ethereumTxHash)
        SlingWriteProposalService.WriteResult result = service.proposeWrite("page", "Test");
        assertTrue("Write should succeed (validator accepts without ethereumTxHash for now)", result.success);
    }
    
    @Test
    public void testProposeWriteValidatorRejection() throws Exception {
        // Configure mock to reject
        mockServer.mockProposeWrite(request -> {
            return new MockValidatorServer.MockResponse(
                400,
                "{\"error\":\"Invalid signature\"}"
            );
        });
        
        // Propose write
        SlingWriteProposalService.WriteResult result = service.proposeWrite("page", "Test");
        
        // Verify rejection
        assertFalse("Write should fail", result.success);
        assertTrue("Error message should contain rejection reason", 
            result.message.contains("rejected"));
    }
    
    @Test
    public void testProposeWriteWalletNotAvailable() {
        // Configure wallet as unavailable
        when(mockWalletService.isAvailable()).thenReturn(false);
        
        // Propose write
        SlingWriteProposalService.WriteResult result = service.proposeWrite("page", "Test");
        
        // Verify failure
        assertFalse("Write should fail", result.success);
        assertTrue("Error should mention wallet", result.message.contains("Wallet"));
    }
    
    /**
     * Extract JSON field value (simple parser for tests).
     */
    private String extractJsonField(String json, String fieldName) {
        String pattern = "\"" + fieldName + "\"\\s*:\\s*\"([^\"]+)\"";
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern);
        java.util.regex.Matcher m = p.matcher(json);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }
}
