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

import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.apache.jackrabbit.oak.segment.http.server.ServerContext;
import org.apache.jackrabbit.oak.segment.http.server.model.ClientRegistration;
import org.apache.jackrabbit.oak.segment.http.server.model.ValidatorRegistration;
import org.apache.jackrabbit.oak.spi.state.NodeStore;
import org.junit.Test;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class RegistrationHandlerTest {

    private static final String WALLET = "0x1234567890abcdef1234567890abcdef12345678";

    @Test
    public void testHandleClientRegistrationRequiresWalletAddress() throws Exception {
        StringWriter body = new StringWriter();
        HttpServletResponse response = responseWithBody(body);
        HttpServletRequest request = baseRequest();
        when(request.getParameter("clientId")).thenReturn("author-1");
        when(request.getParameter("clientUrl")).thenReturn("http://author-1:4502");

        RegistrationHandler handler = new RegistrationHandler(newContext());
        handler.handleClientRegistration(request, response);

        verify(response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
        assertTrue(body.toString().contains("Registration requires an Ethereum wallet address"));
    }

    @Test
    public void testHandleClientRegistrationRejectsInvalidWalletFormat() throws Exception {
        StringWriter body = new StringWriter();
        HttpServletResponse response = responseWithBody(body);
        HttpServletRequest request = baseRequest();
        when(request.getParameter("walletAddress")).thenReturn("0x1234");

        RegistrationHandler handler = new RegistrationHandler(newContext());
        handler.handleClientRegistration(request, response);

        verify(response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
        assertTrue(body.toString().contains("Invalid Ethereum address format"));
    }

    @Test
    public void testHandleClientRegistrationAcceptsJsonBodyAndStoresAliases() throws Exception {
        StringWriter body = new StringWriter();
        HttpServletResponse response = responseWithBody(body);
        HttpServletRequest request = baseRequest();
        when(request.getContentType()).thenReturn("application/json");
        when(request.getReader()).thenReturn(readerFor(
            "{\"clientId\":\"author-1\",\"clientUrl\":\"http://author-1:4502\",\"walletAddress\":\"" + WALLET
                + "\",\"clientType\":\"enterprise\"}"
        ));

        ServerContext context = newContext();
        RegistrationHandler handler = new RegistrationHandler(context);
        handler.handleClientRegistration(request, response);

        verify(response).setStatus(HttpServletResponse.SC_OK);
        ClientRegistration byWallet = context.registeredClients.get(WALLET);
        ClientRegistration byClientId = context.registeredClients.get("author-1");
        assertSame(byWallet, byClientId);
        assertEquals(ClientRegistration.CLIENT_TYPE_ENTERPRISE, byWallet.clientType);
        assertTrue(body.toString().contains("\"clientType\":\"enterprise\""));
    }

    @Test
    public void testHandleClientRegistrationUsesWalletAsFallbackClientId() throws Exception {
        StringWriter body = new StringWriter();
        HttpServletResponse response = responseWithBody(body);
        HttpServletRequest request = baseRequest();
        when(request.getParameter("walletAddress")).thenReturn(WALLET);

        ServerContext context = newContext();
        RegistrationHandler handler = new RegistrationHandler(context);
        handler.handleClientRegistration(request, response);

        verify(response).setStatus(HttpServletResponse.SC_OK);
        ClientRegistration registration = context.registeredClients.get(WALLET);
        assertEquals(WALLET, registration.clientId);
        assertEquals("wallet://" + WALLET, registration.clientUrl);
        assertTrue(body.toString().contains("\"clientId\":\"" + WALLET + "\""));
    }

    @Test
    public void testHandleClientRegistrationPersistsAcrossServerContextRestart() throws Exception {
        Path storeDirectory = Files.createTempDirectory("client-registration-store");
        try {
            StringWriter body = new StringWriter();
            HttpServletResponse response = responseWithBody(body);
            HttpServletRequest request = baseRequest();
            when(request.getParameter("walletAddress")).thenReturn(WALLET);
            when(request.getParameter("clientId")).thenReturn("author-1");
            when(request.getParameter("clientUrl")).thenReturn("http://author-1:4502");
            when(request.getParameter("clientType")).thenReturn("enterprise");

            RegistrationHandler handler = new RegistrationHandler(newContext(storeDirectory));
            handler.handleClientRegistration(request, response);

            ServerContext restored = newContext(storeDirectory);
            ClientRegistration restoredByWallet = restored.findClientRegistrationByWallet(WALLET);
            ClientRegistration restoredByClientId = restored.findClientRegistrationByClientId("author-1");
            assertNotNull(restoredByWallet);
            assertSame(restoredByWallet, restoredByClientId);
            assertEquals(ClientRegistration.CLIENT_TYPE_ENTERPRISE, restoredByWallet.clientType);
            assertEquals("http://author-1:4502", restoredByWallet.clientUrl);
        } finally {
            Files.deleteIfExists(storeDirectory.resolve("client-registrations.properties"));
            Files.deleteIfExists(storeDirectory);
        }
    }

    @Test
    public void testHandleClientRegistrationRejectsInvalidClientType() throws Exception {
        StringWriter body = new StringWriter();
        HttpServletResponse response = responseWithBody(body);
        HttpServletRequest request = baseRequest();
        when(request.getParameter("walletAddress")).thenReturn(WALLET);
        when(request.getParameter("clientType")).thenReturn("invalid");

        RegistrationHandler handler = new RegistrationHandler(newContext());
        handler.handleClientRegistration(request, response);

        verify(response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
        assertTrue(body.toString().contains("Invalid clientType"));
    }

    @Test
    public void testHandleValidatorRegistrationRejectsMissingValidatorId() throws Exception {
        StringWriter body = new StringWriter();
        HttpServletResponse response = responseWithBody(body);
        HttpServletRequest request = baseRequest();
        when(request.getReader()).thenReturn(readerFor(""));

        RegistrationHandler handler = new RegistrationHandler(newContext());
        handler.handleValidatorRegistration(request, response);

        verify(response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
        assertTrue(body.toString().contains("Missing validatorId"));
    }

    @Test
    public void testHandleValidatorRegistrationIgnoresSelfRegistration() throws Exception {
        StringWriter body = new StringWriter();
        HttpServletResponse response = responseWithBody(body);
        HttpServletRequest request = baseRequest();
        when(request.getReader()).thenReturn(readerFor(""));
        when(request.getParameter("validatorId")).thenReturn("validator-1");
        when(request.getParameter("validatorUrl")).thenReturn("http://validator-1:8090");

        RegistrationHandler handler = new RegistrationHandler(newContext());
        handler.handleValidatorRegistration(request, response);

        verify(response).setStatus(HttpServletResponse.SC_OK);
        assertTrue(body.toString().contains("Self-registration ignored"));
    }

    @Test
    public void testHandleValidatorRegistrationInfersUrlWhenMissing() throws Exception {
        StringWriter body = new StringWriter();
        HttpServletResponse response = responseWithBody(body);
        HttpServletRequest request = baseRequest();
        when(request.getReader()).thenReturn(readerFor(""));
        when(request.getParameter("validatorId")).thenReturn("validator-2");
        when(request.getRemoteAddr()).thenReturn("10.0.0.2");
        when(request.getRemotePort()).thenReturn(8090);

        ServerContext context = newContext();
        RegistrationHandler handler = new RegistrationHandler(context);
        handler.handleValidatorRegistration(request, response);

        verify(response).setStatus(HttpServletResponse.SC_OK);
        ValidatorRegistration registration = context.registeredValidators.get("validator-2");
        assertEquals("http://10.0.0.2:8090", registration.validatorUrl);
        assertTrue(body.toString().contains("\"validatorId\":\"validator-2\""));
    }

    private static ServerContext newContext() {
        return newContext(Paths.get("/tmp/store"));
    }

    private static ServerContext newContext(Path storeDirectory) {
        return new ServerContext(
            mock(FileStore.class),
            mock(NodeStore.class),
            storeDirectory,
            "http://validator-1:8090"
        );
    }

    private static HttpServletRequest baseRequest() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getReader()).thenReturn(readerFor(""));
        return request;
    }

    private static HttpServletResponse responseWithBody(StringWriter body) throws Exception {
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getWriter()).thenReturn(new PrintWriter(body));
        return response;
    }

    private static BufferedReader readerFor(String value) {
        return new BufferedReader(new StringReader(value));
    }
}
