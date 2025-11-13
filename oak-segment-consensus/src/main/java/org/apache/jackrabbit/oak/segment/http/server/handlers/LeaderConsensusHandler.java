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

import org.apache.jackrabbit.oak.segment.http.server.ServerContext;
import org.apache.jackrabbit.oak.segment.http.server.model.ValidatorRegistration;
import org.apache.jackrabbit.oak.segment.http.server.util.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;

/**
 * Handler for leader consensus endpoints.
 * This class encapsulates the logic for leader-based consensus operations including
 * follower HEAD updates, peer joins, leadership claims, claim ACKs, and public key registration.
 */
public class LeaderConsensusHandler {

    private static final Logger log = LoggerFactory.getLogger(LeaderConsensusHandler.class);

    private final ServerContext context;

    public LeaderConsensusHandler(ServerContext context) {
        this.context = context;
    }

    /**
     * Handle POST /v1/follower/head-update - Follower receives HEAD update from leader
     * 
     * Parameters (JSON body):
     *   - head: The new HEAD RecordId from leader
     *   - epoch: Current epoch number
     *   - leaderUrl: URL of the current leader
     */
    public void handleFollowerHeadUpdate(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (context.epochLeaderEngine == null) {
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Leader consensus not configured");
            return;
        }
        
        // Only followers should receive HEAD updates
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
            
            // Parse HEAD update
            String head = JsonParser.extractField(body, "head");
            String epochStr = JsonParser.extractField(body, "epoch");
            String leaderUrl = JsonParser.extractField(body, "leaderUrl");
            
            int epoch = Integer.parseInt(epochStr);
            
            // Verify this is from the legitimate leader
            if (!leaderUrl.equals(context.epochLeaderEngine.getCurrentLeader())) {
                log.warn("🚫 HEAD update from non-leader: {} (expected: {})", 
                    leaderUrl, context.epochLeaderEngine.getCurrentLeader());
                response.sendError(HttpServletResponse.SC_FORBIDDEN, "Not current leader");
                return;
            }
            
            log.info("📥 Received HEAD update from leader: {}", leaderUrl);
            log.info("   HEAD: {}...", head.substring(0, Math.min(16, head.length())));
            log.info("   Epoch: {}", epoch);
            
            // Pull segments for this HEAD from leader
            try {
                int segmentCount = context.epochLeaderEngine.pullSegmentsForHead(head, leaderUrl);
                log.info("✅ HEAD update complete - replicated {} segments", segmentCount);
                
                // Return success
                response.setContentType("application/json");
                response.setStatus(HttpServletResponse.SC_OK);
                response.getWriter().write(String.format(
                    "{\"success\":true,\"message\":\"HEAD replicated\",\"segmentCount\":%d}",
                    segmentCount
                ));
            } catch (Exception e) {
                log.error("❌ Failed to replicate HEAD from leader: {}", e.getMessage());
                response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, 
                    "Segment replication failed: " + e.getMessage());
            }
            
        } catch (Exception e) {
            log.error("❌ Failed to process follower HEAD update", e);
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, 
                "Failed to process HEAD update: " + e.getMessage());
        }
    }
    
    /**
     * Handle POST /v1/consensus/peer-joined - A validator broadcasts its presence
     * 
     * Called when a validator completes bootstrap and is ready to join consensus.
     * The receiving validator adds the new peer to its consensus engine.
     */
    public void handlePeerJoined(HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            // Read JSON body
            StringBuilder json = new StringBuilder();
            BufferedReader reader = request.getReader();
            String line;
            while ((line = reader.readLine()) != null) {
                json.append(line);
            }
            
            String body = json.toString();
            
            // Extract fields
            String validatorId = JsonParser.extractField(body, "validatorId");
            String validatorUrl = JsonParser.extractField(body, "validatorUrl");
            
            if (validatorId == null || validatorUrl == null) {
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, 
                    "Missing required fields: validatorId, validatorUrl");
                return;
            }
            
            log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            log.info("📣 PEER JOIN BROADCAST RECEIVED");
            log.info("   Validator ID: {}", validatorId);
            log.info("   Validator URL: {}", validatorUrl);
            log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            
            // 🛡️ PROOF-OF-READINESS VERIFICATION
            // Extract and verify proof to prevent Byzantine validators
            String proofJson = JsonParser.extractObject(body, "proof");
            if (proofJson != null && !proofJson.isEmpty() && context.proofVerifier != null) {
                try {
                    org.apache.jackrabbit.oak.segment.consensus.security.JoinProof proof = 
                        org.apache.jackrabbit.oak.segment.consensus.security.JoinProof.fromJson(proofJson);
                    
                    org.apache.jackrabbit.oak.segment.consensus.security.ProofVerifier.VerificationResult result = 
                        context.proofVerifier.verify(proof);
                    
                    if (!result.isValid()) {
                        log.error("❌ PROOF-OF-READINESS VERIFICATION FAILED");
                        log.error("   Error: {} - {}", result.getErrorCode(), result.getMessage());
                        response.sendError(HttpServletResponse.SC_FORBIDDEN, 
                            "Proof-of-Readiness verification failed: " + result.getErrorCode());
                        return;
                    }
                    
                    log.info("✅ Proof-of-Readiness verified successfully");
                    
                } catch (Exception e) {
                    log.error("❌ Proof verification error", e);
                    response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, 
                        "Proof verification error: " + e.getMessage());
                    return;
                }
            } else {
                log.warn("⚠️  No proof provided (OK for POC, would fail in production)");
            }
            
            // Register in HTTP server's peer list
            // Mark as READY because they've already completed bootstrap before broadcasting
            ValidatorRegistration registration = new ValidatorRegistration(validatorId, validatorUrl);
            registration.updateStatus(ValidatorRegistration.Status.READY);
            context.registeredValidators.putIfAbsent(validatorId, registration);
            log.info("✅ Validator registered in HTTP server (status: READY)");
            
            // PHASE 3: Exchange public keys for Byzantine fault tolerance
            String incomingPublicKey = JsonParser.extractField(body, "publicKey");
            if (incomingPublicKey != null && context.epochLeaderEngine != null) {
                context.epochLeaderEngine.getClaimVerifier().registerPublicKey(validatorUrl, incomingPublicKey);
                log.info("🔑 Registered public key from {}", validatorUrl);
            }
            
            // Update consensus engine
            if (context.epochLeaderEngine != null) {
                context.epochLeaderEngine.addPeer(validatorUrl);
                log.info("✅ Peer added to leader consensus engine");
            } else {
                log.warn("⚠️  No consensus engine to update");
            }
            
            log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            log.info("✅ Peer join processed successfully");
            log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            
            // PHASE 3: Send our public key back to the joining validator
            String ourPublicKey = context.epochLeaderEngine != null ? 
                context.epochLeaderEngine.getPublicKeyHex() : "";
            
            // Return success
            response.setContentType("application/json");
            response.setStatus(HttpServletResponse.SC_OK);
            response.getWriter().write(String.format(
                "{\"success\":true,\"message\":\"Peer %s accepted into network\",\"publicKey\":\"%s\"}", 
                validatorId, ourPublicKey));
            
        } catch (Exception e) {
            log.error("❌ Failed to process peer join", e);
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, 
                "Failed to process peer join: " + e.getMessage());
        }
    }
    
    /**
     * Handle POST /v1/consensus/claim-leadership
     * 
     * Elected leader broadcasts claim to prove liveness and readiness.
     * Followers validate claim and accept leader or trigger re-election on timeout.
     * 
     * Expected JSON body:
     * {
     *   "epoch": 43,
     *   "validatorId": "validator-3",
     *   "validatorUrl": "http://validator-3:8091",
     *   "timestamp": 1762954918989,
     *   "claimType": "EPOCH_ROTATION" | "FAILOVER_CLAIM",
     *   "signature": "0x..."  // Future: cryptographic proof
     * }
     */
    public void handleLeadershipClaim(HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            // Read JSON body
            StringBuilder json = new StringBuilder();
            BufferedReader reader = request.getReader();
            String line;
            while ((line = reader.readLine()) != null) {
                json.append(line);
            }
            
            String body = json.toString();
            
            // Extract fields (PHASE 3: Now includes signature)
            String epochStr = JsonParser.extractField(body, "epoch");
            String validatorId = JsonParser.extractField(body, "validatorId");
            String validatorUrl = JsonParser.extractField(body, "validatorUrl");
            String timestampStr = JsonParser.extractField(body, "timestamp");
            String claimType = JsonParser.extractField(body, "claimType");
            String signature = JsonParser.extractField(body, "signature");  // PHASE 3
            
            if (epochStr == null || validatorId == null || validatorUrl == null || timestampStr == null) {
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, 
                    "Missing required fields: epoch, validatorId, validatorUrl, timestamp");
                return;
            }
            
            // PHASE 3: Signature is required for Byzantine fault tolerance
            if (signature == null || signature.isEmpty()) {
                log.error("❌ Missing signature in leadership claim from {}", validatorUrl);
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, 
                    "Missing required field: signature (Phase 3 Byzantine fault tolerance)");
                return;
            }
            
            int claimedEpoch = Integer.parseInt(epochStr);
            long claimTimestamp = Long.parseLong(timestampStr);
            
            log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            log.info("👑 LEADERSHIP CLAIM RECEIVED");
            log.info("   Epoch: {}", claimedEpoch);
            log.info("   Validator: {}", validatorId);
            log.info("   URL: {}", validatorUrl);
            log.info("   Claim Type: {}", claimType != null ? claimType : "EPOCH_ROTATION");
            log.info("   Timestamp: {}", new java.util.Date(claimTimestamp));
            log.info("   Signature: {}...", signature.substring(0, Math.min(18, signature.length())));
            log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            
            // CRITICAL FIX: Update lastSeen even if claim is rejected (validator is alive)
            // This prevents validators from being marked OFFLINE when claims are rejected
            // due to epoch mismatches or other validation failures
            org.apache.jackrabbit.oak.segment.http.server.model.ValidatorRegistration reg = 
                context.registeredValidators.get(validatorId);
            if (reg != null) {
                reg.lastSeen = System.currentTimeMillis();
                log.debug("📅 Updated lastSeen for {}: {}", validatorId, reg.lastSeen);
            } else {
                // Register validator if not already registered (from claim)
                reg = new org.apache.jackrabbit.oak.segment.http.server.model.ValidatorRegistration(validatorId, validatorUrl);
                reg.lastSeen = System.currentTimeMillis();
                context.registeredValidators.putIfAbsent(validatorId, reg);
                log.info("📝 Registered validator from claim: {} ({})", validatorId, validatorUrl);
            }
            
            // Delegate to consensus engine for validation (PHASE 3: includes signature)
            if (context.epochLeaderEngine != null) {
                boolean accepted = context.epochLeaderEngine.handleLeadershipClaim(
                    claimedEpoch, validatorId, validatorUrl, claimTimestamp, signature);
                
                if (accepted) {
                    log.info("✅ Leadership claim ACCEPTED (signature verified)");
                    response.setContentType("application/json");
                    response.setStatus(HttpServletResponse.SC_OK);
                    response.getWriter().write(String.format(
                        "{\"success\":true,\"message\":\"Leadership claim accepted for epoch %d\"}", 
                        claimedEpoch));
                } else {
                    log.warn("❌ Leadership claim REJECTED");
                    response.sendError(HttpServletResponse.SC_CONFLICT, 
                        "Leadership claim rejected - invalid signature, epoch, or validator");
                }
            } else {
                log.error("❌ No leader consensus engine configured");
                response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, 
                    "Leader consensus not enabled");
            }
            
            log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            
        } catch (NumberFormatException e) {
            log.error("❌ Invalid number format in claim", e);
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, 
                "Invalid number format: " + e.getMessage());
        } catch (Exception e) {
            log.error("❌ Failed to process leadership claim", e);
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, 
                "Failed to process leadership claim: " + e.getMessage());
        }
    }
    
    /**
     * Handle POST /v1/consensus/claim-ack
     * 
     * PHASE 2: Followers send ACK to leader after accepting claim.
     * PHASE 3: ACK is cryptographically signed for Byzantine fault tolerance.
     */
    public void handleClaimAck(HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            // Read JSON body
            StringBuilder json = new StringBuilder();
            BufferedReader reader = request.getReader();
            String line;
            while ((line = reader.readLine()) != null) {
                json.append(line);
            }
            
            String body = json.toString();
            
            // Extract fields
            String epochStr = JsonParser.extractField(body, "epoch");
            String claimantUrl = JsonParser.extractField(body, "claimantUrl");
            String ackValidatorUrl = JsonParser.extractField(body, "ackValidatorUrl");
            String timestampStr = JsonParser.extractField(body, "timestamp");
            String signature = JsonParser.extractField(body, "signature");
            
            if (epochStr == null || claimantUrl == null || ackValidatorUrl == null || timestampStr == null || signature == null) {
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, 
                    "Missing required fields: epoch, claimantUrl, ackValidatorUrl, timestamp, signature");
                return;
            }
            
            int epoch = Integer.parseInt(epochStr);
            long timestamp = Long.parseLong(timestampStr);
            
            log.info("👍 CLAIM ACK RECEIVED");
            log.info("   Epoch: {}", epoch);
            log.info("   Claimant: {}", claimantUrl);
            log.info("   From: {}", ackValidatorUrl);
            log.info("   Signature: {}...", signature.substring(0, Math.min(18, signature.length())));
            
            // Delegate to consensus engine
            if (context.epochLeaderEngine != null) {
                boolean accepted = context.epochLeaderEngine.handleClaimAck(
                    epoch, claimantUrl, ackValidatorUrl, timestamp, signature);
                
                if (accepted) {
                    log.info("✅ ACK accepted");
                    response.setContentType("application/json");
                    response.setStatus(HttpServletResponse.SC_OK);
                    response.getWriter().write(
                        "{\"success\":true,\"message\":\"ACK accepted\"}");
                } else {
                    log.warn("❌ ACK rejected");
                    response.sendError(HttpServletResponse.SC_CONFLICT, "ACK rejected");
                }
            } else {
                response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, 
                    "Leader consensus not enabled");
            }
            
        } catch (Exception e) {
            log.error("❌ Failed to process ACK", e);
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, 
                "Failed to process ACK: " + e.getMessage());
        }
    }
    
    /**
     * Handle POST /v1/consensus/register-public-key
     * 
     * PHASE 3: Validators register their public keys for signature verification.
     */
    public void handlePublicKeyRegistration(HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            // Read JSON body
            StringBuilder json = new StringBuilder();
            BufferedReader reader = request.getReader();
            String line;
            while ((line = reader.readLine()) != null) {
                json.append(line);
            }
            
            String body = json.toString();
            
            // Extract fields
            String validatorUrl = JsonParser.extractField(body, "validatorUrl");
            String publicKeyHex = JsonParser.extractField(body, "publicKey");
            
            if (validatorUrl == null || publicKeyHex == null) {
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, 
                    "Missing required fields: validatorUrl, publicKey");
                return;
            }
            
            log.info("🔑 PUBLIC KEY REGISTRATION");
            log.info("   Validator: {}", validatorUrl);
            log.info("   Key: {}...", publicKeyHex.substring(0, Math.min(18, publicKeyHex.length())));
            
            // Register with consensus engine
            if (context.epochLeaderEngine != null) {
                context.epochLeaderEngine.getClaimVerifier().registerPublicKey(validatorUrl, publicKeyHex);
                
                log.info("✅ Public key registered");
                response.setContentType("application/json");
                response.setStatus(HttpServletResponse.SC_OK);
                response.getWriter().write(
                    "{\"success\":true,\"message\":\"Public key registered\"}");
            } else {
                response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, 
                    "Leader consensus not enabled");
            }
            
        } catch (Exception e) {
            log.error("❌ Failed to register public key", e);
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, 
                "Failed to register public key: " + e.getMessage());
        }
    }
}

