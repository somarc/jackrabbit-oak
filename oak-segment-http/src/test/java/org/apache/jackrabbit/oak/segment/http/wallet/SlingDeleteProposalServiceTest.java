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

import org.apache.jackrabbit.oak.segment.http.ValidatorAuthHelper;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.lang.reflect.Field;

import static org.junit.Assert.*;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.when;

public class SlingDeleteProposalServiceTest {

    private static final String WALLET = "0x742d35cc6634c0532925a3b844bc9e7595f0beb0";

    private MockValidatorServer mockServer;
    private SlingDeleteProposalService service;

    @Mock
    private SlingAuthorWalletService mockWalletService;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        resetValidatorAuthHelper();
        System.clearProperty(ValidatorAuthHelper.TOKEN_PROPERTY_NAME);

        mockServer = new MockValidatorServer();
        mockServer.start();

        service = new SlingDeleteProposalService();
        setField("validatorUrl", mockServer.getBaseUrl());
        setField("walletService", mockWalletService);
        setField("enabled", true);
        setField("clientId", "test-client");

        when(mockWalletService.isAvailable()).thenReturn(true);
        when(mockWalletService.getWalletAddress()).thenReturn(WALLET);
        when(mockWalletService.sign(anyString())).thenReturn("0xdeleteabcdef");
    }

    @After
    public void tearDown() throws Exception {
        System.clearProperty(ValidatorAuthHelper.TOKEN_PROPERTY_NAME);
        resetValidatorAuthHelper();
        if (mockServer != null) {
            mockServer.stop();
        }
    }

    @Test
    public void testProposeDeleteSuccess() {
        mockServer.mockProposeDelete(request -> new MockValidatorServer.MockResponse(
            200,
            "{\"proposalId\":\"delete-123\",\"state\":\"PENDING\"}"
        ));

        SlingDeleteProposalService.DeleteResult result =
            service.proposeDelete("/oak-chain/content/" + WALLET + "/doc-1");

        assertTrue(result.success);
        assertEquals(1, mockServer.getRequestCount("/v1/propose-delete"));
        assertEquals("/oak-chain/content/" + WALLET + "/doc-1",
            mockServer.getLastRequestParam("/v1/propose-delete", "contentPath"));
    }

    @Test
    public void testProposeDeleteRejectsOwnershipViolationBeforeTransport() {
        SlingDeleteProposalService.DeleteResult result =
            service.proposeDelete("/oak-chain/content/0x1111111111111111111111111111111111111111/doc-1");

        assertFalse(result.success);
        assertTrue(result.message.contains("ownership"));
        assertEquals(0, mockServer.getRequestCount("/v1/propose-delete"));
    }

    @Test
    public void testProposeDeleteValidatorRejection() {
        mockServer.mockProposeDelete(request -> new MockValidatorServer.MockResponse(
            400,
            "{\"error\":\"Delete rejected\"}"
        ));

        SlingDeleteProposalService.DeleteResult result =
            service.proposeDelete("/oak-chain/content/" + WALLET + "/doc-2");

        assertFalse(result.success);
        assertTrue(result.message.contains("rejected"));
    }

    @Test
    public void testProposeDeleteWalletNotAvailable() {
        when(mockWalletService.isAvailable()).thenReturn(false);

        SlingDeleteProposalService.DeleteResult result =
            service.proposeDelete("/oak-chain/content/" + WALLET + "/doc-3");

        assertFalse(result.success);
        assertTrue(result.message.contains("Wallet"));
    }

    @Test
    public void testProposeDeleteAddsAuthorizationHeaderWhenConfigured() throws Exception {
        System.setProperty(ValidatorAuthHelper.TOKEN_PROPERTY_NAME, "Bearer secret-token");
        resetValidatorAuthHelper();
        mockServer.mockProposeDelete(request -> new MockValidatorServer.MockResponse(200, "{\"state\":\"PENDING\"}"));

        SlingDeleteProposalService.DeleteResult result =
            service.proposeDelete("/oak-chain/content/" + WALLET + "/doc-4");

        assertTrue(result.success);
        assertEquals("Bearer secret-token",
            mockServer.getLastRequestHeader("/v1/propose-delete", ValidatorAuthHelper.AUTHORIZATION_HEADER));
    }

    private void setField(String name, Object value) throws Exception {
        Field field = SlingDeleteProposalService.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(service, value);
    }

    private static void resetValidatorAuthHelper() throws Exception {
        Field cachedToken = ValidatorAuthHelper.class.getDeclaredField("cachedToken");
        cachedToken.setAccessible(true);
        cachedToken.set(null, null);
        Field tokenInitialized = ValidatorAuthHelper.class.getDeclaredField("tokenInitialized");
        tokenInitialized.setAccessible(true);
        tokenInitialized.setBoolean(null, false);
    }
}
