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

import org.apache.jackrabbit.oak.segment.consensus.config.RuntimeConfigValueResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.jackrabbit.oak.segment.http.server.util.ApiErrorUtil;
import java.io.IOException;

/**
 * Simple token-based authentication validator for validator HTTP endpoints.
 * 
 * <p>Token can be configured via:
 * <ul>
 *   <li>System property: {@code oak.validator.auth.token}</li>
 *   <li>Environment variable: {@code OAK_VALIDATOR_AUTH_TOKEN}</li>
 * </ul>
 * 
 * <p>If no token is configured, authentication is disabled (POC mode).
 * This allows validators to run without authentication for development/testing.
 * 
 * <p>For production, set a token via system property or environment variable.
 * Clients must include the token in the {@code Authorization} header.
 */
public class AuthTokenValidator {
    
    private static final Logger log = LoggerFactory.getLogger(AuthTokenValidator.class);
    
    /**
     * System property name for auth token.
     */
    public static final String TOKEN_PROPERTY_NAME = "oak.validator.auth.token";
    
    /**
     * Environment variable name for auth token.
     */
    public static final String TOKEN_ENV_VAR_NAME = "OAK_VALIDATOR_AUTH_TOKEN";
    
    /**
     * HTTP header name for authorization.
     */
    public static final String AUTHORIZATION_HEADER = "Authorization";
    
    private final String expectedToken;
    private final boolean authEnabled;
    
    /**
     * Initialize the auth token validator.
     * Reads token from system property or environment variable.
     * If no token is configured, authentication is disabled (POC mode).
     */
    public AuthTokenValidator() {
        String token = RuntimeConfigValueResolver.readString(TOKEN_PROPERTY_NAME, TOKEN_ENV_VAR_NAME, null);

        if (token != null && !token.trim().isEmpty()) {
            this.expectedToken = token.trim();
            this.authEnabled = true;
            log.info("🔐 Token-based authentication ENABLED (token configured)");
        } else {
            this.expectedToken = null;
            this.authEnabled = false;
            log.info("🔓 Token-based authentication DISABLED (no token configured - POC mode)");
        }
    }
    
    /**
     * Check if authentication is enabled.
     * 
     * @return true if a token is configured, false otherwise (POC mode)
     */
    public boolean isAuthEnabled() {
        return authEnabled;
    }
    
    /**
     * Validate the Authorization header in the request.
     * 
     * <p>If authentication is disabled (no token configured), this always returns true.
     * 
     * @param request HTTP request
     * @param response HTTP response (used to send error if validation fails)
     * @return true if request is authorized, false if authorization failed
     * @throws IOException if an error occurs writing the response
     */
    public boolean validateRequest(HttpServletRequest request, HttpServletResponse response) throws IOException {
        // If auth is disabled (POC mode), allow all requests
        if (!authEnabled) {
            return true;
        }
        
        // Get Authorization header
        String authHeader = request.getHeader(AUTHORIZATION_HEADER);
        
        if (authHeader == null || authHeader.trim().isEmpty()) {
            log.debug("Missing Authorization header");
            sendUnauthorized(response, "Missing Authorization header");
            return false;
        }
        
        // Compare token (exact match, case-sensitive)
        String providedToken = authHeader.trim();
        if (!expectedToken.equals(providedToken)) {
            log.debug("Invalid token provided");
            sendUnauthorized(response, "Invalid credentials");
            return false;
        }
        
        // Token matches
        log.debug("Request authorized");
        return true;
    }
    
    /**
     * Send 401 Unauthorized response.
     */
    private void sendUnauthorized(HttpServletResponse response, String message) throws IOException {
        ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_UNAUTHORIZED, message);
    }
}
