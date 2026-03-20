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
import org.apache.jackrabbit.oak.spi.state.NodeStore;
import org.junit.After;
import org.junit.Test;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ValidatorRegistrationHandlerTest {

    @After
    public void tearDown() {
        System.clearProperty(ValidatorRegistrationHandler.PROP_REGISTRATION_ENABLED);
        System.clearProperty(ValidatorRegistrationHandler.PROP_REGISTRATION_APPROVAL_REQUIRED);
        System.clearProperty(ValidatorRegistrationHandler.PROP_RP_ID);
        System.clearProperty(ValidatorRegistrationHandler.PROP_RP_NAME);
    }

    @Test
    public void testHandleRegistrationPageDisabledReturnsForbidden() throws Exception {
        System.setProperty(ValidatorRegistrationHandler.PROP_REGISTRATION_ENABLED, "false");
        ValidatorRegistrationHandler handler = newHandler();
        HttpServletRequest request = mock(HttpServletRequest.class);
        ResponseCapture response = newResponse();

        handler.handleRegistrationPage(request, response.response);

        assertEquals(HttpServletResponse.SC_FORBIDDEN, response.status);
        assertTrue(response.body.toString().contains("Registration is disabled"));
    }

    @Test
    public void testHandleRegistrationPageReturnsHtmlAndStoresChallenge() throws Exception {
        ValidatorRegistrationHandler handler = newHandler();
        HttpServletRequest request = mock(HttpServletRequest.class);
        ResponseCapture response = newResponse();

        handler.handleRegistrationPage(request, response.response);

        assertEquals("text/html", response.contentType);
        assertTrue(response.body.toString().contains("Validator Registration - Oak Chain"));
        Map<String, ValidatorRegistrationHandler.RegistrationChallenge> challenges = challenges(handler);
        assertEquals(1, challenges.size());
        ValidatorRegistrationHandler.RegistrationChallenge challenge = challenges.values().iterator().next();
        assertFalse(challenge.isExpired());
        assertNotNull(challenge.userId);
    }

    @Test
    public void testHandleRegistrationCompleteRejectsMissingFields() throws Exception {
        ValidatorRegistrationHandler handler = newHandler();
        HttpServletRequest request = requestWithBody("{\"challengeId\":\"c-1\"}");
        ResponseCapture response = newResponse();

        handler.handleRegistrationComplete(request, response.response);

        assertEquals(HttpServletResponse.SC_BAD_REQUEST, response.status);
        assertTrue(response.body.toString().contains("Missing required fields"));
    }

    @Test
    public void testHandleRegistrationCompleteRejectsInvalidChallenge() throws Exception {
        ValidatorRegistrationHandler handler = newHandler();
        HttpServletRequest request = requestWithBody(validCompletionBody("missing", "cred-1", Base64.getEncoder().encodeToString(new byte[] {1, 2, 3}), "Validator 1"));
        ResponseCapture response = newResponse();

        handler.handleRegistrationComplete(request, response.response);

        assertEquals(HttpServletResponse.SC_BAD_REQUEST, response.status);
        assertTrue(response.body.toString().contains("Invalid or expired challenge"));
    }

    @Test
    public void testHandleRegistrationCompleteRejectsInvalidPublicKeyEncoding() throws Exception {
        ValidatorRegistrationHandler handler = newHandler();
        String challengeId = addChallenge(handler, "challenge-user");
        HttpServletRequest request = requestWithBody(validCompletionBody(challengeId, "cred-1", "%%%not-base64%%%", "Validator 1"));
        ResponseCapture response = newResponse();

        handler.handleRegistrationComplete(request, response.response);

        assertEquals(HttpServletResponse.SC_BAD_REQUEST, response.status);
        assertTrue(response.body.toString().contains("Invalid public key encoding"));
    }

    @Test
    public void testHandleRegistrationCompleteAutoApprovesCredential() throws Exception {
        ValidatorRegistrationHandler handler = newHandler();
        byte[] publicKey = new byte[] {1, 2, 3, 4, 5, 6};
        String challengeId = addChallenge(handler, "user-auto");
        HttpServletRequest request = requestWithBody(validCompletionBody(
            challengeId,
            "cred-auto",
            Base64.getEncoder().encodeToString(publicKey),
            "Validator Auto"));
        ResponseCapture response = newResponse();

        handler.handleRegistrationComplete(request, response.response);

        String walletAddress = deriveWalletAddress(publicKey);
        assertEquals("application/json", response.contentType);
        assertTrue(response.body.toString().contains("\"status\":\"approved\""));
        assertTrue(response.body.toString().contains(walletAddress));
        assertTrue(credentials(handler).containsKey(walletAddress));
        assertTrue(handler.isValidatorRegistered(walletAddress));
        assertEquals("Validator Auto", handler.getCredential(walletAddress).displayName);
    }

    @Test
    public void testHandleRegistrationCompleteUsesDefaultDisplayNameWhenMissing() throws Exception {
        ValidatorRegistrationHandler handler = newHandler();
        byte[] publicKey = new byte[] {9, 8, 7, 6, 5, 4};
        String challengeId = addChallenge(handler, "user-default");
        HttpServletRequest request = requestWithBody(validCompletionBody(
            challengeId,
            "cred-default",
            Base64.getEncoder().encodeToString(publicKey),
            null));
        ResponseCapture response = newResponse();

        handler.handleRegistrationComplete(request, response.response);

        String walletAddress = deriveWalletAddress(publicKey);
        assertEquals("Validator " + walletAddress.substring(0, 8), handler.getCredential(walletAddress).displayName);
    }

    @Test
    public void testHandleRegistrationCompleteCreatesPendingApprovalWhenEnabled() throws Exception {
        System.setProperty(ValidatorRegistrationHandler.PROP_REGISTRATION_APPROVAL_REQUIRED, "true");
        ValidatorRegistrationHandler handler = newHandler();
        byte[] publicKey = new byte[] {11, 12, 13, 14};
        String challengeId = addChallenge(handler, "user-pending");
        HttpServletRequest request = requestWithBody(validCompletionBody(
            challengeId,
            "cred-pending",
            Base64.getEncoder().encodeToString(publicKey),
            "Validator Pending"));
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        ResponseCapture response = newResponse();

        handler.handleRegistrationComplete(request, response.response);

        String walletAddress = deriveWalletAddress(publicKey);
        assertTrue(response.body.toString().contains("\"status\":\"pending\""));
        assertTrue(pendingApprovals(handler).containsKey(walletAddress));
        assertFalse(credentials(handler).containsKey(walletAddress));
    }

    @Test
    public void testHandleRegistrationCompleteRejectsDuplicateWallet() throws Exception {
        ValidatorRegistrationHandler handler = newHandler();
        byte[] publicKey = new byte[] {3, 3, 3, 3};
        String firstChallenge = addChallenge(handler, "user-first");
        handler.handleRegistrationComplete(
            requestWithBody(validCompletionBody(firstChallenge, "cred-1", Base64.getEncoder().encodeToString(publicKey), "First")),
            newResponse().response);

        String secondChallenge = addChallenge(handler, "user-second");
        ResponseCapture duplicateResponse = newResponse();
        handler.handleRegistrationComplete(
            requestWithBody(validCompletionBody(secondChallenge, "cred-2", Base64.getEncoder().encodeToString(publicKey), "Second")),
            duplicateResponse.response);

        assertEquals(HttpServletResponse.SC_CONFLICT, duplicateResponse.status);
        assertTrue(duplicateResponse.body.toString().contains("Wallet already registered"));
    }

    @Test
    public void testHandleApproveRegistrationMovesPendingCredentialToApproved() throws Exception {
        ValidatorRegistrationHandler handler = newHandler();
        ValidatorRegistrationHandler.ValidatorCredential credential =
            new ValidatorRegistrationHandler.ValidatorCredential("cred-approve", new byte[] {1, 2}, "0xabc", "Validator Approve", false);
        pendingApprovals(handler).put("0xabc", new ValidatorRegistrationHandler.PendingRegistration(credential, "10.0.0.1"));
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getParameter("wallet")).thenReturn("0xabc");
        ResponseCapture response = newResponse();

        handler.handleApproveRegistration(request, response.response);

        assertTrue(response.body.toString().contains("Registration approved"));
        assertTrue(credentials(handler).containsKey("0xabc"));
        assertFalse(pendingApprovals(handler).containsKey("0xabc"));
        assertTrue(credentials(handler).get("0xabc").approved);
    }

    @Test
    public void testHandleRejectRegistrationRemovesPendingCredential() throws Exception {
        ValidatorRegistrationHandler handler = newHandler();
        ValidatorRegistrationHandler.ValidatorCredential credential =
            new ValidatorRegistrationHandler.ValidatorCredential("cred-reject", new byte[] {1, 2}, "0xdef", "Validator Reject", false);
        pendingApprovals(handler).put("0xdef", new ValidatorRegistrationHandler.PendingRegistration(credential, "10.0.0.2"));
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getParameter("wallet")).thenReturn("0xdef");
        ResponseCapture response = newResponse();

        handler.handleRejectRegistration(request, response.response);

        assertTrue(response.body.toString().contains("Registration rejected"));
        assertFalse(pendingApprovals(handler).containsKey("0xdef"));
        assertFalse(credentials(handler).containsKey("0xdef"));
    }

    @Test
    public void testHandleListPendingAndValidatorsRenderJson() throws Exception {
        ValidatorRegistrationHandler handler = newHandler();
        ValidatorRegistrationHandler.ValidatorCredential pendingCredential =
            new ValidatorRegistrationHandler.ValidatorCredential("cred-p", new byte[] {1}, "0x111", "Pending Validator", false);
        ValidatorRegistrationHandler.ValidatorCredential approvedCredential =
            new ValidatorRegistrationHandler.ValidatorCredential("cred-a", new byte[] {2}, "0x222", "Approved Validator", true);
        pendingApprovals(handler).put("0x111", new ValidatorRegistrationHandler.PendingRegistration(pendingCredential, "10.0.0.3"));
        credentials(handler).put("0x222", approvedCredential);

        ResponseCapture pendingResponse = newResponse();
        handler.handleListPending(mock(HttpServletRequest.class), pendingResponse.response);
        assertTrue(pendingResponse.body.toString().contains("Pending Validator"));
        assertTrue(pendingResponse.body.toString().contains("10.0.0.3"));

        ResponseCapture validatorsResponse = newResponse();
        handler.handleListValidators(mock(HttpServletRequest.class), validatorsResponse.response);
        assertTrue(validatorsResponse.body.toString().contains("Approved Validator"));
        assertTrue(validatorsResponse.body.toString().contains("\"approved\":true"));
    }

    private static ValidatorRegistrationHandler newHandler() {
        ServerContext context = new ServerContext(
            mock(FileStore.class),
            mock(NodeStore.class),
            Paths.get("/tmp/store"),
            "http://localhost:8090");
        return new ValidatorRegistrationHandler(context);
    }

    private static HttpServletRequest requestWithBody(String body) throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getReader()).thenReturn(new BufferedReader(new StringReader(body)));
        return request;
    }

    private static ResponseCapture newResponse() throws Exception {
        HttpServletResponse response = mock(HttpServletResponse.class);
        StringWriter body = new StringWriter();
        PrintWriter writer = new PrintWriter(body, true);
        ResponseCapture capture = new ResponseCapture(response, body);
        when(response.getWriter()).thenReturn(writer);
        doAnswer(invocation -> {
            capture.status = invocation.getArgument(0);
            return null;
        }).when(response).setStatus(org.mockito.ArgumentMatchers.anyInt());
        doAnswer(invocation -> {
            capture.contentType = invocation.getArgument(0);
            return null;
        }).when(response).setContentType(org.mockito.ArgumentMatchers.anyString());
        return capture;
    }

    private static String addChallenge(ValidatorRegistrationHandler handler, String userId) throws Exception {
        ValidatorRegistrationHandler.RegistrationChallenge challenge =
            new ValidatorRegistrationHandler.RegistrationChallenge(
                "challenge-" + userId,
                new byte[] {1, 2, 3, 4},
                userId,
                60_000);
        challenges(handler).put(challenge.challengeId, challenge);
        return challenge.challengeId;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ValidatorRegistrationHandler.RegistrationChallenge> challenges(ValidatorRegistrationHandler handler) throws Exception {
        return (Map<String, ValidatorRegistrationHandler.RegistrationChallenge>) readField(handler, "challenges");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ValidatorRegistrationHandler.ValidatorCredential> credentials(ValidatorRegistrationHandler handler) throws Exception {
        return (Map<String, ValidatorRegistrationHandler.ValidatorCredential>) readField(handler, "credentials");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ValidatorRegistrationHandler.PendingRegistration> pendingApprovals(ValidatorRegistrationHandler handler) throws Exception {
        return (Map<String, ValidatorRegistrationHandler.PendingRegistration>) readField(handler, "pendingApprovals");
    }

    private static Object readField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    private static String validCompletionBody(String challengeId, String credentialId, String publicKeyB64, String displayName) {
        StringBuilder json = new StringBuilder();
        json.append("{");
        json.append("\"challengeId\":\"").append(challengeId).append("\",");
        json.append("\"credentialId\":\"").append(credentialId).append("\",");
        json.append("\"publicKey\":\"").append(publicKeyB64).append("\"");
        if (displayName != null) {
            json.append(",\"displayName\":\"").append(displayName).append("\"");
        }
        json.append("}");
        return json.toString();
    }

    private static String deriveWalletAddress(byte[] publicKey) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(publicKey);
        StringBuilder sb = new StringBuilder("0x");
        for (int i = hash.length - 20; i < hash.length; i++) {
            sb.append(String.format("%02x", hash[i]));
        }
        return sb.toString();
    }

    private static final class ResponseCapture {
        private final HttpServletResponse response;
        private final StringWriter body;
        private int status;
        private String contentType;

        private ResponseCapture(HttpServletResponse response, StringWriter body) {
            this.response = response;
            this.body = body;
        }
    }
}
