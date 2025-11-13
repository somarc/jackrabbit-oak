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

import org.apache.jackrabbit.oak.segment.consensus.leader.EpochLeaderEngine;
import org.apache.jackrabbit.oak.segment.http.server.ServerContext;
import org.apache.jackrabbit.oak.segment.http.server.model.ClientRegistration;
import org.apache.jackrabbit.oak.segment.http.server.model.ValidatorRegistration;
import org.apache.jackrabbit.oak.segment.http.server.util.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;

/**
 * Handler for registration endpoints (`/v1/register/client`, `/v1/register/validator`, `/v1/heartbeat`).
 * This class encapsulates the logic for client and validator registration and heartbeat handling.
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
     *   - walletAddress: Optional wallet address
     *   - signature: Optional signed message (for future verification)
     */
    public void handleClientRegistration(HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            // Read JSON body if present
            StringBuilder json = new StringBuilder();
            BufferedReader reader = request.getReader();
            String line;
            while ((line = reader.readLine()) != null) {
                json.append(line);
            }
            
            String body = json.toString();
            
            // Parse parameters (from JSON body or query params)
            String clientId = null;
            String clientUrl = null;
            String walletAddress = null;
            
            if (body != null && !body.isEmpty()) {
                clientId = JsonParser.extractField(body, "clientId");
                clientUrl = JsonParser.extractField(body, "clientUrl");
                walletAddress = JsonParser.extractField(body, "walletAddress");
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
            
            // Use remote address as fallback
            if (clientId == null || clientId.isEmpty()) {
                String remoteAddr = request.getRemoteAddr();
                int remotePort = request.getRemotePort();
                clientId = remoteAddr + ":" + remotePort;
            }
            if (clientUrl == null || clientUrl.isEmpty()) {
                String remoteAddr = request.getRemoteAddr();
                int remotePort = request.getRemotePort();
                clientUrl = "http://" + remoteAddr + ":" + remotePort;
            }
            
            // Register or update client
            ClientRegistration registration = context.registeredClients.get(clientId);
            if (registration == null) {
                registration = new ClientRegistration(clientId, clientUrl, walletAddress);
                context.registeredClients.put(clientId, registration);
                log.info("✅ New client registered: {} ({})", clientId, clientUrl);
            } else {
                registration.updateLastSeen();
                if (walletAddress != null && !walletAddress.isEmpty()) {
                    // Note: walletAddress is final in ClientRegistration, so we can't update it
                    // This is expected behavior - wallet is set at registration time
                }
                log.debug("Client heartbeat: {} ({})", clientId, clientUrl);
            }
            
            // Return success
            response.setContentType("application/json");
            response.setStatus(HttpServletResponse.SC_OK);
            response.getWriter().write("{\"success\":true,\"clientId\":\"" + clientId + "\",\"message\":\"Client registered\"}");
            
        } catch (Exception e) {
            log.error("Failed to register client", e);
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Registration failed: " + e.getMessage());
        }
    }
    
    /**
     * Handle POST/PUT /v1/register-validator - Validators register with each other
     * 
     * Parameters (JSON body or query params):
     *   - validatorId: Unique identifier (e.g., "validator-1")
     *   - validatorUrl: Validator's URL/address (e.g., "http://validator-1:8090")
     */
    public void handleValidatorRegistration(HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            // Parse JSON body or query parameters
            String validatorId = null;
            String validatorUrl = null;
            
            // Try JSON body first
            StringBuilder json = new StringBuilder();
            BufferedReader reader = request.getReader();
            String line;
            while ((line = reader.readLine()) != null) {
                json.append(line);
            }
            
            if (json.length() > 0) {
                String body = json.toString();
                validatorId = JsonParser.extractField(body, "validatorId");
                validatorUrl = JsonParser.extractField(body, "validatorUrl");
            }
            
            // Fallback to query parameters
            if (validatorId == null || validatorId.isEmpty()) {
                validatorId = request.getParameter("validatorId");
            }
            if (validatorUrl == null || validatorUrl.isEmpty()) {
                validatorUrl = request.getParameter("validatorUrl");
            }
            
            // Validate required fields
            if (validatorId == null || validatorId.isEmpty()) {
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Missing validatorId");
                return;
            }
            if (validatorUrl == null || validatorUrl.isEmpty()) {
                // Try to infer from request
                String remoteAddr = request.getRemoteAddr();
                int remotePort = request.getRemotePort();
                validatorUrl = "http://" + remoteAddr + ":" + remotePort;
            }
            
            // Don't register self
            if (validatorUrl.equals(context.selfUrl)) {
                response.setContentType("application/json");
                response.setStatus(HttpServletResponse.SC_OK);
                response.getWriter().write("{\"success\":true,\"message\":\"Self-registration ignored\"}");
                return;
            }
            
            // Register or update validator
            ValidatorRegistration registration = context.registeredValidators.get(validatorId);
            if (registration == null) {
                registration = new ValidatorRegistration(validatorId, validatorUrl);
                context.registeredValidators.put(validatorId, registration);
                log.info("✅ New validator peer registered: {} ({})", validatorId, validatorUrl);
            } else {
                registration.lastSeen = System.currentTimeMillis();
                if (!registration.validatorUrl.equals(validatorUrl)) {
                    // Note: validatorUrl is final in ValidatorRegistration, so we can't update it
                    // This is expected behavior - URL is set at registration time
                    log.info("🔄 Validator peer URL changed: {} ({})", validatorId, validatorUrl);
                } else {
                    log.debug("Validator heartbeat: {} ({})", validatorId, validatorUrl);
                }
            }
            
            // Return success
            response.setContentType("application/json");
            response.setStatus(HttpServletResponse.SC_OK);
            response.getWriter().write("{\"success\":true,\"validatorId\":\"" + validatorId + "\",\"message\":\"Validator registered\"}");
            
        } catch (Exception e) {
            log.error("Failed to register validator", e);
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Registration failed: " + e.getMessage());
        }
    }
    
    /**
     * Handle heartbeat from leader.
     * Followers receive these periodically to confirm leader is alive.
     */
    public void handleHeartbeat(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (context.epochLeaderEngine == null) {
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Leader consensus not configured");
            return;
        }
        
        // Only followers should receive heartbeats
        if (context.epochLeaderEngine.isLeader()) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "I am the leader, not a follower");
            return;
        }
        
        try {
            // Read JSON body
            StringBuilder json = new StringBuilder();
            BufferedReader reader = request.getReader();
            String line;
            while ((line = reader.readLine()) != null) {
                json.append(line);
            }
            
            String body = json.toString();
            
            // Parse heartbeat
            String leaderUrl = JsonParser.extractField(body, "leaderUrl");
            String epochStr = JsonParser.extractField(body, "epoch");
            
            // Verify this is from the legitimate leader
            if (!leaderUrl.equals(context.epochLeaderEngine.getCurrentLeader())) {
                log.debug("🚫 Heartbeat from non-leader: {} (expected: {})", 
                    leaderUrl, context.epochLeaderEngine.getCurrentLeader());
                response.sendError(HttpServletResponse.SC_FORBIDDEN, "Not current leader");
                return;
            }
            
            // CRITICAL FIX: Synchronize epoch from leader's heartbeat
            // This prevents followers from being stuck at their initial epoch
            if (epochStr != null) {
                try {
                    int leaderEpoch = Integer.parseInt(epochStr);
                    context.epochLeaderEngine.updateCurrentEpoch(leaderEpoch);
                } catch (NumberFormatException e) {
                    log.warn("⚠️  Invalid epoch in heartbeat: {}", epochStr);
                }
            }
            
            // Record the heartbeat
            context.epochLeaderEngine.getHealthMonitor().recordHeartbeat();
            
            // Register the leader in our validator registry (if not already registered)
            // This ensures followers learn about the leader via heartbeat
            // Extract leaderId (wallet address) from heartbeat payload
            String leaderId = JsonParser.extractField(body, "leaderId");
            if (leaderId == null || leaderId.isEmpty()) {
                // Fallback: derive from URL (for backward compatibility)
                leaderId = leaderUrl.contains("validator-") 
                    ? leaderUrl.substring(leaderUrl.indexOf("validator-")).split(":")[0]
                    : "leader-" + leaderUrl.hashCode();
                log.warn("⚠️  No leaderId in heartbeat, using derived ID: {}", leaderId);
            }
            
            if (!context.registeredValidators.containsKey(leaderId)) {
                ValidatorRegistration leaderReg = new ValidatorRegistration(leaderId, leaderUrl);
                leaderReg.updateStatus(ValidatorRegistration.Status.READY);
                context.registeredValidators.put(leaderId, leaderReg);
                log.info("✅ Leader registered via heartbeat: {} ({})", leaderId, leaderUrl);
            }
            
            // Return success
            response.setContentType("application/json");
            response.setStatus(HttpServletResponse.SC_OK);
            response.getWriter().write("{\"success\":true}");
            
        } catch (Exception e) {
            log.error("❌ Failed to process heartbeat", e);
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, 
                "Failed to process heartbeat: " + e.getMessage());
        }
    }
}

