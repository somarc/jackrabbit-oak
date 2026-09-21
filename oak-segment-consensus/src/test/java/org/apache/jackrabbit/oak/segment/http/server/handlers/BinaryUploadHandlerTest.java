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

import org.apache.jackrabbit.oak.segment.http.server.binary.UploadSession;
import org.apache.jackrabbit.oak.segment.http.server.binary.UploadSessionManager;
import org.junit.Test;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class BinaryUploadHandlerTest {

    private static final String WALLET = "0x1234567890abcdef1234567890abcdef12345678";
    private static final String CID = "Qmf4F3CWU6Ly958TFiR8BRP18gwvW3Xsj2yXu5DqkonWc3";

    @Test
    public void testHandleDeclareIntentRequiresParameters() throws Exception {
        UploadSessionManager manager = new UploadSessionManager();
        StringWriter body = new StringWriter();
        HttpServletResponse response = responseWithBody(body);

        new BinaryUploadHandler(manager).handleDeclareIntent(request("POST", "/v1/binary/declare-intent"), response);

        verify(response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
        assertTrue(body.toString().contains("Missing required parameters"));
    }

    @Test
    public void testHandleDeclareIntentRejectsInvalidWalletAddress() throws Exception {
        UploadSessionManager manager = new UploadSessionManager();
        HttpServletRequest request = request("POST", "/v1/binary/declare-intent");
        when(request.getParameter("walletAddress")).thenReturn("0x1234");
        when(request.getParameter("filesize")).thenReturn("123");
        when(request.getParameter("mimeType")).thenReturn("image/png");
        StringWriter body = new StringWriter();
        HttpServletResponse response = responseWithBody(body);

        new BinaryUploadHandler(manager).handleDeclareIntent(request, response);

        verify(response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
        assertTrue(body.toString().contains("Invalid wallet address format"));
    }

    @Test
    public void testHandleDeclareIntentReturnsIntentToken() throws Exception {
        UploadSessionManager manager = new UploadSessionManager();
        HttpServletRequest request = request("POST", "/v1/binary/declare-intent");
        when(request.getParameter("walletAddress")).thenReturn(WALLET);
        when(request.getParameter("filesize")).thenReturn("123");
        when(request.getParameter("mimeType")).thenReturn("image/png");
        when(request.getParameter("contentHash")).thenReturn("sha256:abc");
        StringWriter body = new StringWriter();
        HttpServletResponse response = responseWithBody(body);

        new BinaryUploadHandler(manager).handleDeclareIntent(request, response);

        verify(response).setStatus(HttpServletResponse.SC_OK);
        String json = body.toString();
        assertTrue(json.contains("\"intentToken\":\"intent-"));
        assertTrue(json.contains("\"expirySeconds\":900"));
    }

    @Test
    public void testHandleCheckIntentReturnsReadyForUploadState() throws Exception {
        UploadSessionManager manager = new UploadSessionManager();
        UploadSession session = manager.createSession(WALLET, 123L, "image/png", null);
        manager.markReadyForUpload(session.getIntentToken(), 42L);
        HttpServletRequest request = request("GET", "/v1/binary/check-intent/" + session.getIntentToken());
        StringWriter body = new StringWriter();
        HttpServletResponse response = responseWithBody(body);

        new BinaryUploadHandler(manager).handleCheckIntent(request, response);

        verify(response).setStatus(HttpServletResponse.SC_OK);
        String json = body.toString();
        assertTrue(json.contains("\"status\":\"READY_FOR_UPLOAD\""));
        assertTrue(json.contains("\"epochNumber\":42"));
        assertTrue(json.contains("\"uploadDeadline\":"));
    }

    @Test
    public void testHandleCheckIntentReturnsCompletedCid() throws Exception {
        UploadSessionManager manager = new UploadSessionManager();
        UploadSession session = manager.createSession(WALLET, 123L, "image/png", null);
        manager.completeUpload(session.getIntentToken(), CID);
        HttpServletRequest request = request("GET", "/v1/binary/check-intent/" + session.getIntentToken());
        StringWriter body = new StringWriter();
        HttpServletResponse response = responseWithBody(body);

        new BinaryUploadHandler(manager).handleCheckIntent(request, response);

        verify(response).setStatus(HttpServletResponse.SC_OK);
        String json = body.toString();
        assertTrue(json.contains("\"status\":\"COMPLETED\""));
        assertTrue(json.contains("\"cid\":\"" + CID + "\""));
    }

    @Test
    public void testHandleCompleteUploadRejectsWalletMismatch() throws Exception {
        UploadSessionManager manager = new UploadSessionManager();
        UploadSession session = manager.createSession(WALLET, 123L, "image/png", null);
        HttpServletRequest request = request("POST", "/v1/binary/complete-upload");
        when(request.getParameter("intentToken")).thenReturn(session.getIntentToken());
        when(request.getParameter("cid")).thenReturn(CID);
        when(request.getParameter("walletAddress")).thenReturn("0xabcdefabcdefabcdefabcdefabcdefabcdefabcd");
        StringWriter body = new StringWriter();
        HttpServletResponse response = responseWithBody(body);

        new BinaryUploadHandler(manager).handleCompleteUpload(request, response);

        verify(response).setStatus(HttpServletResponse.SC_FORBIDDEN);
        assertTrue(body.toString().contains("Wallet address mismatch"));
    }

    @Test
    public void testHandleCompleteUploadRejectsInvalidCidFormat() throws Exception {
        UploadSessionManager manager = new UploadSessionManager();
        UploadSession session = manager.createSession(WALLET, 123L, "image/png", null);
        HttpServletRequest request = request("POST", "/v1/binary/complete-upload");
        when(request.getParameter("intentToken")).thenReturn(session.getIntentToken());
        when(request.getParameter("cid")).thenReturn("not-a-cid");
        when(request.getParameter("walletAddress")).thenReturn(WALLET);
        StringWriter body = new StringWriter();
        HttpServletResponse response = responseWithBody(body);

        new BinaryUploadHandler(manager).handleCompleteUpload(request, response);

        verify(response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
        assertTrue(body.toString().contains("Invalid IPFS CID format"));
    }

    @Test
    public void testHandleCompleteUploadReturnsSuccessPayload() throws Exception {
        UploadSessionManager manager = new UploadSessionManager();
        UploadSession session = manager.createSession(WALLET, 123L, "image/png", null);
        HttpServletRequest request = request("POST", "/v1/binary/complete-upload");
        when(request.getParameter("intentToken")).thenReturn(session.getIntentToken());
        when(request.getParameter("cid")).thenReturn(CID);
        when(request.getParameter("walletAddress")).thenReturn(WALLET);
        StringWriter body = new StringWriter();
        HttpServletResponse response = responseWithBody(body);

        new BinaryUploadHandler(manager).handleCompleteUpload(request, response);

        verify(response).setStatus(HttpServletResponse.SC_OK);
        String json = body.toString();
        assertTrue(json.contains("\"status\":\"complete\""));
        assertTrue(json.contains("\"cid\":\"" + CID + "\""));
    }

    private static HttpServletRequest request(String method, String uri) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn(method);
        when(request.getRequestURI()).thenReturn(uri);
        return request;
    }

    private static HttpServletResponse responseWithBody(StringWriter body) throws Exception {
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getWriter()).thenReturn(new PrintWriter(body));
        return response;
    }
}
