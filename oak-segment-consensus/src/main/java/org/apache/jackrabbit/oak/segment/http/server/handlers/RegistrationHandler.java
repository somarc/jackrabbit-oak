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

import org.apache.jackrabbit.oak.segment.consensus.genesis.CanonicalGenesisContent;

import org.apache.jackrabbit.oak.segment.http.server.ServerContext;
import org.apache.jackrabbit.oak.segment.http.server.model.ClientRegistration;
import org.apache.jackrabbit.oak.segment.http.server.util.JsonParser;
import org.apache.jackrabbit.oak.segment.http.server.util.JsonOutputUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.jackrabbit.oak.segment.http.server.util.ApiErrorUtil;

/**
 * Handler for client registration ({@code /v1/register/client}).
 */
public class RegistrationHandler {

    private static final Logger log = LoggerFactory.getLogger(RegistrationHandler.class);

    private final ServerContext context;

    public RegistrationHandler(ServerContext context) {
        this.context = context;
    }

    /**
     * Handle POST/PUT /v1/register-client - Sling authors register when successfully mounting
     * 
     * Parameters (JSON body or query params):
     *   - clientId: Unique identifier (e.g., container name "sling-author-1a")
     *   - clientUrl: Client's URL/address (e.g., "http://sling-author-1a:8080")
     *   - walletAddress: REQUIRED Ethereum wallet address (0x...)
     *   - signature: Optional signed message (for future verification)
     */
    public void handleClientRegistration(HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            String body = "";
            String contentType = request.getContentType();
            boolean isJson = contentType != null && contentType.toLowerCase().contains("application/json");
            if (isJson) {
                try {
                    // Read JSON body if present
                    StringBuilder json = new StringBuilder();
                    BufferedReader reader = request.getReader();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        json.append(line);
                    }
                    body = json.toString();
                } catch (IllegalStateException e) {
                    log.debug("Registration request body already consumed; falling back to query params", e);
                    body = "";
                }
            }
            
            // Parse parameters (from JSON body or query params)
            String clientId = null;
            String clientUrl = null;
            String walletAddress = null;
            String clientType = null;
            
            if (body != null && !body.isEmpty()) {
                clientId = JsonParser.extractField(body, "clientId");
                clientUrl = JsonParser.extractField(body, "clientUrl");
                walletAddress = JsonParser.extractField(body, "walletAddress");
                clientType = JsonParser.extractField(body, "clientType");
            }
            
            // Fallback to query params if JSON not provided
            if (clientId == null || clientId.isEmpty()) {
                clientId = request.getParameter("clientId");
            }
            if (clientUrl == null || clientUrl.isEmpty()) {
                clientUrl = request.getParameter("clientUrl");
            }
            if (walletAddress == null || walletAddress.isEmpty()) {
                walletAddress = request.getParameter("walletAddress");
            }
            if (clientType == null || clientType.isEmpty()) {
                clientType = request.getParameter("clientType");
            }
            
            // REQUIRE Ethereum wallet address - this is the primary identifier
            // Note: IP-based fallback has been removed - wallet address is required
            if (walletAddress == null || walletAddress.isEmpty()) {
                log.warn("🚫 Registration rejected: Missing walletAddress");
                ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_BAD_REQUEST, 
                    "Registration requires an Ethereum wallet address (0x...). Please provide walletAddress parameter.");
                return;
            }
            
            // Validate Ethereum address format (full 42 chars: 0x + 40 hex)
            walletAddress = walletAddress.trim().toLowerCase();
            if (!walletAddress.matches("^0x[0-9a-f]{40}$")) {
                log.warn("🚫 Registration rejected: Invalid Ethereum address format: {}", walletAddress);
                ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_BAD_REQUEST, 
                    "Invalid Ethereum address format. Must be 0x followed by 40 hex characters (e.g., 0x1234...abcd).");
                return;
            }

            if (CanonicalGenesisContent.isReservedMutation(walletAddress, null)) {
                ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_FORBIDDEN,
                    "genesis_namespace_reserved", "The zero wallet cannot register as an ordinary client.");
                return;
            }

            String normalizedClientType = ClientRegistration.normalizeClientType(clientType);
            if (clientType != null && !clientType.trim().isEmpty()
                && !ClientRegistration.CLIENT_TYPE_ENTERPRISE.equalsIgnoreCase(clientType)
                && !ClientRegistration.CLIENT_TYPE_SUPPLY_CHAIN.equalsIgnoreCase(clientType)) {
                ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_BAD_REQUEST,
                    "Invalid clientType. Supported values: 'supply-chain' (default), 'enterprise'.");
                return;
            }
            
            // Use wallet address as primary identifier if clientId not provided
            if (clientId == null || clientId.isEmpty()) {
                clientId = walletAddress;
            }
            if (clientUrl == null || clientUrl.isEmpty()) {
                // Use a placeholder URL - the wallet address is the real identifier
                clientUrl = "wallet://" + walletAddress;
            }
            
            ClientRegistration existingByWallet = context.findClientRegistrationByWallet(walletAddress);
            ClientRegistration existingByClientId = walletAddress.equals(clientId)
                ? existingByWallet
                : context.findClientRegistrationByClientId(clientId);
            ClientRegistration existing = existingByWallet != null ? existingByWallet : existingByClientId;

            if (existingByClientId != null
                    && existingByClientId.walletAddress != null
                    && !existingByClientId.walletAddress.equalsIgnoreCase(walletAddress)) {
                log.warn("⚠️  Client {} already registered with different wallet: {} (attempted: {})",
                    clientId, existingByClientId.walletAddress, walletAddress);
                ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_CONFLICT,
                    String.format("Client %s already registered with wallet %s", clientId, existingByClientId.walletAddress));
                return;
            }

            String previousType = existing != null ? existing.clientType : null;
            boolean created = existing == null;
            ClientRegistration registration = context.registerClient(clientId, clientUrl, walletAddress, normalizedClientType);

            if (created) {
                log.info("✅ New client registered: wallet={} (clientId={}, type={}, url={})",
                    walletAddress, registration.clientId, registration.clientType, registration.clientUrl);
            } else if (previousType != null && !previousType.equals(registration.clientType)) {
                log.info("🔄 Updated clientType for wallet {}: {} -> {}", walletAddress,
                    previousType, normalizedClientType);
            } else {
                log.debug("Client heartbeat: wallet={} (clientId={}, type={})",
                    walletAddress, registration.clientId, registration.clientType);
            }
            
            // Return success
            response.setContentType("application/json");
            response.setStatus(HttpServletResponse.SC_OK);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("clientId", registration.clientId);
            result.put("walletAddress", walletAddress);
            result.put("clientType", registration.clientType);
            result.put("message", "Client registered");
            response.getWriter().write(JsonOutputUtil.toJson(result));
            
        } catch (IllegalStateException e) {
            ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_CONFLICT, e.getMessage());
        } catch (Exception e) {
            log.error("Failed to register client", e);
            ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Registration failed: " + e.getMessage());
        }
    }
    
}
