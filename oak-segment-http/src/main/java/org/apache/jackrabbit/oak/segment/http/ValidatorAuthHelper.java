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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.HttpURLConnection;

/**
 * Adds validator authentication headers when a token has been configured.
 *
 * <p>The token is resolved once per JVM, preferring the
 * {@code oak.validator.auth.token} system property over the
 * {@code OAK_VALIDATOR_AUTH_TOKEN} environment variable. Blank values are
 * treated as absent, in which case requests are sent without an
 * {@code Authorization} header.</p>
 */
public class ValidatorAuthHelper {
    
    private static final Logger log = LoggerFactory.getLogger(ValidatorAuthHelper.class);
    
    /**
     * System property name for auth token (matches validator's property name).
     */
    public static final String TOKEN_PROPERTY_NAME = "oak.validator.auth.token";
    
    /**
     * Environment variable name for auth token (matches validator's env var name).
     */
    public static final String TOKEN_ENV_VAR_NAME = "OAK_VALIDATOR_AUTH_TOKEN";
    
    /**
     * HTTP header name for authorization.
     */
    public static final String AUTHORIZATION_HEADER = "Authorization";
    
    private static String cachedToken;
    private static boolean tokenInitialized = false;
    
    /**
     * Returns the configured authentication token, if one has been supplied.
     *
     * <p>The lookup result is cached after the first call.</p>
     *
     * @return the configured token, or {@code null} when authentication is not
     *         enabled
     */
    public static String getAuthToken() {
        synchronized (ValidatorAuthHelper.class) {
            if (!tokenInitialized) {
                // Try system property first, then environment variable
                String token = normalizeToken(System.getProperty(TOKEN_PROPERTY_NAME));
                if (token == null) {
                    token = normalizeToken(System.getenv(TOKEN_ENV_VAR_NAME));
                }

                if (token != null) {
                    cachedToken = token;
                    log.debug("Validator auth token configured (from {} or {})",
                        TOKEN_PROPERTY_NAME, TOKEN_ENV_VAR_NAME);
                } else {
                    cachedToken = null;
                    log.debug("No validator auth token configured - requests will be unauthenticated (POC mode)");
                }
                tokenInitialized = true;
            }
            return cachedToken;
        }
    }

    /**
     * Trims surrounding whitespace and collapses blank values to {@code null}.
     */
    private static String normalizeToken(String token) {
        if (token == null) {
            return null;
        }
        String trimmedToken = token.trim();
        return trimmedToken.isEmpty() ? null : trimmedToken;
    }
    
    /**
     * Adds the configured {@code Authorization} header to a
     * {@link HttpURLConnection}.
     *
     * @param conn the request to mutate
     */
    public static void addAuthHeader(HttpURLConnection conn) {
        String token = getAuthToken();
        if (token != null) {
            conn.setRequestProperty(AUTHORIZATION_HEADER, token);
        }
    }
    
    /**
     * Adds the configured {@code Authorization} header to an Apache
     * {@link HttpRequest}.
     *
     * @param request the request to mutate
     */
    public static void addAuthHeader(HttpRequest request) {
        String token = getAuthToken();
        if (token != null) {
            request.setHeader(AUTHORIZATION_HEADER, token);
        }
    }
    
    /**
     * Indicates whether validator authentication is enabled.
     *
     * @return {@code true} when a non-blank token has been configured
     */
    public static boolean isAuthEnabled() {
        return getAuthToken() != null;
    }
}
