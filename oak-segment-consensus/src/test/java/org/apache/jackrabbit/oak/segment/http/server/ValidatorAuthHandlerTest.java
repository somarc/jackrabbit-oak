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
package org.apache.jackrabbit.oak.segment.http.server;

import org.apache.jackrabbit.oak.segment.http.server.util.PasskeyOperatorId;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ValidatorAuthHandlerTest {

    @Test
    public void testRequiresAuthOnlyProtectsConfiguredRoutes() {
        ValidatorAuthHandler enabled = new ValidatorAuthHandler(true, 24, null);
        ValidatorAuthHandler disabled = new ValidatorAuthHandler(false, 24, null);

        assertTrue(enabled.requiresAuth("/"));
        assertTrue(enabled.requiresAuth("/dashboard"));
        assertTrue(enabled.requiresAuth("/explorer/tree"));
        assertTrue(enabled.requiresAuth("/api-browser"));
        assertTrue(enabled.requiresAuth("/chat/room"));
        assertTrue(enabled.requiresAuth("/v1/admin/config"));
        assertTrue(enabled.requiresAuth("/v1/gc/propose/demo"));
        assertFalse(enabled.requiresAuth("/health"));
        assertFalse(enabled.requiresAuth("/auth/login"));
        assertFalse(disabled.requiresAuth("/dashboard"));
    }

    @Test
    public void testCheckAuthAllowsWhenHandlerDisabledOrPathIsPublic() throws Exception {
        ValidatorAuthHandler disabled = new ValidatorAuthHandler(false, 24, null);
        assertTrue(disabled.checkAuth(request("/dashboard", null, null), mock(HttpServletResponse.class)));

        ValidatorAuthHandler enabled = new ValidatorAuthHandler(true, 24, null);
        assertTrue(enabled.checkAuth(request("/health", null, null), mock(HttpServletResponse.class)));
    }

    @Test
    public void testCheckAuthRedirectsUnauthenticatedProtectedRequest() throws Exception {
        ValidatorAuthHandler handler = new ValidatorAuthHandler(true, 24, null);
        HttpServletRequest request = request("/dashboard", "view=leader", null);
        HttpServletResponse response = mock(HttpServletResponse.class);
        ArgumentCaptor<String> redirect = ArgumentCaptor.forClass(String.class);

        assertFalse(handler.checkAuth(request, response));

        verify(response).sendRedirect(redirect.capture());
        String value = redirect.getValue();
        assertTrue(value.startsWith("/auth/login?return="));
        String encoded = value.substring("/auth/login?return=".length());
        assertEquals(
            "/dashboard?view=leader",
            new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8)
        );
    }

    @Test
    public void testCheckAuthAllowsAuthorizedSession() throws Exception {
        ValidatorAuthHandler handler = new ValidatorAuthHandler(true, 24, new String[] {"0xabc"});
        ValidatorAuthHandler.Session session = new ValidatorAuthHandler.Session(
            "session-1",
            "0xAbC",
            TimeUnit.HOURS.toMillis(1)
        );
        sessions(handler).put(session.sessionId, session);

        boolean allowed = handler.checkAuth(
            request("/dashboard", null, new Cookie[] {new Cookie("oak_validator_session", session.sessionId)}),
            mock(HttpServletResponse.class)
        );

        assertTrue(allowed);
    }

    @Test
    public void testCheckAuthRejectsSessionForDisallowedWallet() throws Exception {
        ValidatorAuthHandler handler = new ValidatorAuthHandler(true, 24, new String[] {"0xallowed"});
        ValidatorAuthHandler.Session session = new ValidatorAuthHandler.Session(
            "session-2",
            "0xblocked",
            TimeUnit.HOURS.toMillis(1)
        );
        sessions(handler).put(session.sessionId, session);
        StringWriter body = new StringWriter();
        HttpServletResponse response = responseWithBody(body);

        boolean allowed = handler.checkAuth(
            request("/dashboard", null, new Cookie[] {new Cookie("oak_validator_session", session.sessionId)}),
            response
        );

        assertFalse(allowed);
        verify(response).setStatus(HttpServletResponse.SC_FORBIDDEN);
        assertTrue(body.toString().contains("Operator not authorized"));
    }

    @Test
    public void testGetSessionRemovesExpiredSessions() throws Exception {
        ValidatorAuthHandler handler = new ValidatorAuthHandler(true, 24, null);
        ValidatorAuthHandler.Session expired = new ValidatorAuthHandler.Session("expired", "0xabc", -1);
        sessions(handler).put(expired.sessionId, expired);

        ValidatorAuthHandler.Session session = handler.getSession(
            request("/dashboard", null, new Cookie[] {new Cookie("oak_validator_session", expired.sessionId)})
        );

        assertNull(session);
        assertFalse(sessions(handler).containsKey(expired.sessionId));
    }

    @Test
    public void testCreateChallengeStoresEntryAndCleansExpiredChallenges() throws Exception {
        ValidatorAuthHandler handler = new ValidatorAuthHandler(true, 24, null);
        ValidatorAuthHandler.Challenge expired = new ValidatorAuthHandler.Challenge("expired", new byte[] {1}, -1);
        challenges(handler).put(expired.challengeId, expired);

        ValidatorAuthHandler.Challenge challenge = handler.createChallenge();

        assertEquals(32, challenge.challengeBytes.length);
        assertSame(challenge, challenges(handler).get(challenge.challengeId));
        assertFalse(challenges(handler).containsKey(expired.challengeId));
    }

    @Test
    public void testVerifyAndCreateSessionRejectsMissingOrExpiredChallenge() throws Exception {
        ValidatorAuthHandler handler = new ValidatorAuthHandler(true, 24, null);

        assertNull(handler.verifyAndCreateSession("missing", new byte[] {1}, new byte[] {2}, "0xabc"));

        ValidatorAuthHandler.Challenge expired = new ValidatorAuthHandler.Challenge("expired", new byte[] {1}, -1);
        challenges(handler).put(expired.challengeId, expired);

        assertNull(handler.verifyAndCreateSession(expired.challengeId, new byte[] {1}, new byte[] {2}, "0xabc"));
    }

    @Test
    public void testVerifyAndCreateSessionReturnsNullWhenVerifierUnavailable() throws Exception {
        ValidatorAuthHandler handler = new ValidatorAuthHandler(true, 24, null);
        ValidatorAuthHandler.Challenge challenge = new ValidatorAuthHandler.Challenge(
            "challenge-1",
            new byte[] {1, 2, 3},
            TimeUnit.MINUTES.toMillis(5)
        );
        challenges(handler).put(challenge.challengeId, challenge);

        String operatorId = PasskeyOperatorId.derive(new byte[] {8, 7});
        assertNull(handler.verifyAndCreateSession(challenge.challengeId, new byte[] {9}, new byte[] {8, 7}, operatorId));
        assertFalse(challenges(handler).containsKey(challenge.challengeId));
    }

    @Test
    public void testSetSessionCookieUsesConfiguredTtlAndSecurityFlags() {
        ValidatorAuthHandler handler = new ValidatorAuthHandler(true, 12, null);
        ValidatorAuthHandler.Session session = new ValidatorAuthHandler.Session(
            "session-3",
            "0xabc",
            TimeUnit.HOURS.toMillis(1)
        );
        HttpServletResponse response = mock(HttpServletResponse.class);
        ArgumentCaptor<Cookie> captor = ArgumentCaptor.forClass(Cookie.class);

        handler.setSessionCookie(response, session);

        verify(response).addCookie(captor.capture());
        Cookie cookie = captor.getValue();
        assertEquals("oak_validator_session", cookie.getName());
        assertEquals("session-3", cookie.getValue());
        assertEquals("/", cookie.getPath());
        assertTrue(cookie.isHttpOnly());
        assertTrue(cookie.getSecure());
        assertEquals((int) TimeUnit.HOURS.toSeconds(12), cookie.getMaxAge());
    }

    @Test
    public void testClearSessionRemovesStoredSessionAndExpiresCookie() throws Exception {
        ValidatorAuthHandler handler = new ValidatorAuthHandler(true, 24, null);
        ValidatorAuthHandler.Session session = new ValidatorAuthHandler.Session(
            "session-4",
            "0xabc",
            TimeUnit.HOURS.toMillis(1)
        );
        sessions(handler).put(session.sessionId, session);
        HttpServletResponse response = mock(HttpServletResponse.class);
        ArgumentCaptor<Cookie> captor = ArgumentCaptor.forClass(Cookie.class);

        handler.clearSession(
            request("/dashboard", null, new Cookie[] {new Cookie("oak_validator_session", session.sessionId)}),
            response
        );

        verify(response).addCookie(captor.capture());
        Cookie cookie = captor.getValue();
        assertEquals("oak_validator_session", cookie.getName());
        assertEquals("", cookie.getValue());
        assertEquals("/", cookie.getPath());
        assertEquals(0, cookie.getMaxAge());
        assertFalse(sessions(handler).containsKey(session.sessionId));
    }

    @Test
    public void testHandleLoginPageUsesDefaultReturnUrlAndEmitsChallengeCookie() throws Exception {
        ValidatorAuthHandler handler = new ValidatorAuthHandler(true, 24, null);
        StringWriter body = new StringWriter();
        HttpServletResponse response = responseWithBody(body);
        ArgumentCaptor<Cookie> captor = ArgumentCaptor.forClass(Cookie.class);

        handler.handleLoginPage(request("/auth/login", null, null), response);

        verify(response).addCookie(captor.capture());
        verify(response).setContentType("text/html");
        verify(response).setStatus(HttpServletResponse.SC_OK);

        Cookie cookie = captor.getValue();
        assertEquals("oak_auth_challenge", cookie.getName());
        assertTrue(cookie.isHttpOnly());
        assertTrue(cookie.getSecure());
        assertEquals(300, cookie.getMaxAge());
        assertNotNull(challenges(handler).get(cookie.getValue()));

        String html = body.toString();
        assertTrue(html.contains("Validator Access"));
        assertTrue(html.contains("Authenticate with Passkey"));
        assertTrue(html.contains("const challengeId = '" + cookie.getValue() + "';"));
        assertTrue(html.contains(
            Base64.getUrlEncoder().encodeToString("/dashboard".getBytes(StandardCharsets.UTF_8))
        ));
    }

    @Test
    public void testHandleLoginPagePreservesProvidedReturnUrl() throws Exception {
        ValidatorAuthHandler handler = new ValidatorAuthHandler(true, 24, null);
        StringWriter body = new StringWriter();
        HttpServletResponse response = responseWithBody(body);
        HttpServletRequest request = request("/auth/login", null, null);
        when(request.getParameter("return")).thenReturn("custom-return");

        handler.handleLoginPage(request, response);

        assertTrue(body.toString().contains("const returnUrl = 'custom-return';"));
    }

    @Test
    public void testSessionAliasesOperatorIdToLegacyWalletField() {
        ValidatorAuthHandler.Session session = new ValidatorAuthHandler.Session(
            "session-operator",
            "0xAbCd",
            TimeUnit.HOURS.toMillis(1)
        );

        assertEquals("0xabcd", session.operatorId);
        assertEquals(session.operatorId, session.walletAddress);
    }

    private static HttpServletRequest request(String uri, String query, Cookie[] cookies) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn(uri);
        when(request.getQueryString()).thenReturn(query);
        when(request.getCookies()).thenReturn(cookies);
        return request;
    }

    private static HttpServletResponse responseWithBody(StringWriter body) throws Exception {
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getWriter()).thenReturn(new PrintWriter(body));
        return response;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ValidatorAuthHandler.Session> sessions(ValidatorAuthHandler handler) throws Exception {
        return (Map<String, ValidatorAuthHandler.Session>) field("sessions").get(handler);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ValidatorAuthHandler.Challenge> challenges(ValidatorAuthHandler handler) throws Exception {
        return (Map<String, ValidatorAuthHandler.Challenge>) field("challenges").get(handler);
    }

    private static Field field(String name) throws Exception {
        Field field = ValidatorAuthHandler.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
