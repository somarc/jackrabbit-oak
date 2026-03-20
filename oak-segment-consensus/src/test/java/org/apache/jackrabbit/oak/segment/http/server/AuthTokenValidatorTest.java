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

import org.junit.Test;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class AuthTokenValidatorTest {

    @Test
    public void testValidateRequestAllowsAllWhenTokenNotConfigured() throws Exception {
        withToken(null, () -> {
            AuthTokenValidator validator = new AuthTokenValidator();
            assertFalse(validator.isAuthEnabled());

            boolean allowed = validator.validateRequest(mock(HttpServletRequest.class), mock(HttpServletResponse.class));

            assertTrue(allowed);
        });
    }

    @Test
    public void testValidateRequestRejectsMissingAuthorizationHeader() throws Exception {
        withToken("secret-token", () -> {
            AuthTokenValidator validator = new AuthTokenValidator();
            HttpServletRequest request = mock(HttpServletRequest.class);
            StringWriter body = new StringWriter();
            HttpServletResponse response = responseWithBody(body);

            boolean allowed = validator.validateRequest(request, response);

            assertFalse(allowed);
            verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            assertTrue(body.toString().contains("Missing Authorization header"));
        });
    }

    @Test
    public void testValidateRequestRejectsInvalidToken() throws Exception {
        withToken("secret-token", () -> {
            AuthTokenValidator validator = new AuthTokenValidator();
            HttpServletRequest request = mock(HttpServletRequest.class);
            when(request.getHeader(AuthTokenValidator.AUTHORIZATION_HEADER)).thenReturn("wrong-token");
            StringWriter body = new StringWriter();
            HttpServletResponse response = responseWithBody(body);

            boolean allowed = validator.validateRequest(request, response);

            assertFalse(allowed);
            verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            assertTrue(body.toString().contains("Invalid credentials"));
        });
    }

    @Test
    public void testValidateRequestAcceptsExactTokenMatch() throws Exception {
        withToken("secret-token", () -> {
            AuthTokenValidator validator = new AuthTokenValidator();
            HttpServletRequest request = mock(HttpServletRequest.class);
            when(request.getHeader(AuthTokenValidator.AUTHORIZATION_HEADER)).thenReturn("secret-token");

            boolean allowed = validator.validateRequest(request, mock(HttpServletResponse.class));

            assertTrue(allowed);
        });
    }

    private static void withToken(String token, ThrowingRunnable runnable) throws Exception {
        String previous = System.getProperty(AuthTokenValidator.TOKEN_PROPERTY_NAME);
        try {
            if (token == null) {
                System.clearProperty(AuthTokenValidator.TOKEN_PROPERTY_NAME);
            } else {
                System.setProperty(AuthTokenValidator.TOKEN_PROPERTY_NAME, token);
            }
            runnable.run();
        } finally {
            if (previous == null) {
                System.clearProperty(AuthTokenValidator.TOKEN_PROPERTY_NAME);
            } else {
                System.setProperty(AuthTokenValidator.TOKEN_PROPERTY_NAME, previous);
            }
        }
    }

    private static HttpServletResponse responseWithBody(StringWriter body) throws Exception {
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getWriter()).thenReturn(new PrintWriter(body));
        return response;
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
