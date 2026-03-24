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
package org.apache.jackrabbit.oak.segment.http;

import org.apache.http.HttpRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.net.HttpURLConnection;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

public class ValidatorAuthHelperTest {

    @Before
    @After
    public void resetAuthState() throws Exception {
        System.clearProperty(ValidatorAuthHelper.TOKEN_PROPERTY_NAME);
        Field cachedToken = ValidatorAuthHelper.class.getDeclaredField("cachedToken");
        cachedToken.setAccessible(true);
        cachedToken.set(null, null);
        Field tokenInitialized = ValidatorAuthHelper.class.getDeclaredField("tokenInitialized");
        tokenInitialized.setAccessible(true);
        tokenInitialized.setBoolean(null, false);
    }

    @Test
    public void testGetAuthTokenUsesSystemProperty() {
        System.setProperty(ValidatorAuthHelper.TOKEN_PROPERTY_NAME, "Bearer test-token");

        assertEquals("Bearer test-token", ValidatorAuthHelper.getAuthToken());
        assertTrue(ValidatorAuthHelper.isAuthEnabled());
    }

    @Test
    public void testAddAuthHeaderToConnectionWhenTokenConfigured() {
        System.setProperty(ValidatorAuthHelper.TOKEN_PROPERTY_NAME, "Bearer test-token");
        HttpURLConnection connection = mock(HttpURLConnection.class);

        ValidatorAuthHelper.addAuthHeader(connection);

        verify(connection).setRequestProperty(ValidatorAuthHelper.AUTHORIZATION_HEADER, "Bearer test-token");
    }

    @Test
    public void testAddAuthHeaderToRequestWhenTokenConfigured() {
        System.setProperty(ValidatorAuthHelper.TOKEN_PROPERTY_NAME, "Bearer test-token");
        HttpRequest request = mock(HttpRequest.class);

        ValidatorAuthHelper.addAuthHeader(request);

        verify(request).setHeader(ValidatorAuthHelper.AUTHORIZATION_HEADER, "Bearer test-token");
    }

    @Test
    public void testNoAuthHeaderAddedWhenTokenMissing() {
        HttpURLConnection connection = mock(HttpURLConnection.class);
        HttpRequest request = mock(HttpRequest.class);

        assertFalse(ValidatorAuthHelper.isAuthEnabled());
        assertNull(ValidatorAuthHelper.getAuthToken());
        ValidatorAuthHelper.addAuthHeader(connection);
        ValidatorAuthHelper.addAuthHeader(request);

        verify(connection, never()).setRequestProperty(eq(ValidatorAuthHelper.AUTHORIZATION_HEADER), anyString());
        verify(request, never()).setHeader(eq(ValidatorAuthHelper.AUTHORIZATION_HEADER), anyString());
    }

    @Test
    public void testBlankSystemPropertyIsTreatedAsMissing() {
        System.setProperty(ValidatorAuthHelper.TOKEN_PROPERTY_NAME, "   ");

        assertNull(ValidatorAuthHelper.getAuthToken());
        assertFalse(ValidatorAuthHelper.isAuthEnabled());
    }

    @Test
    public void testCachedTokenDoesNotReinitializeAfterFirstRead() {
        System.setProperty(ValidatorAuthHelper.TOKEN_PROPERTY_NAME, "Bearer original-token");

        assertEquals("Bearer original-token", ValidatorAuthHelper.getAuthToken());

        System.setProperty(ValidatorAuthHelper.TOKEN_PROPERTY_NAME, "Bearer updated-token");
        assertEquals("Bearer original-token", ValidatorAuthHelper.getAuthToken());
    }

    @Test
    public void testUtilityClassCanBeInstantiated() {
        assertNotNull(new ValidatorAuthHelper());
    }
}
