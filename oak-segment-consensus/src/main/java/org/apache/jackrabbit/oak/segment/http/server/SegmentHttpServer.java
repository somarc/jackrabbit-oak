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

import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.apache.jackrabbit.oak.segment.consensus.ConsensusEngine;
import org.apache.jackrabbit.oak.segment.http.server.model.ValidatorRegistration;
import org.apache.jackrabbit.oak.spi.state.NodeStore;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.prometheus.client.hotspot.DefaultExports;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

/**
 * HTTP server for serving Oak segment store files over HTTP.
 * 
 * <p>This server exposes Oak segment store persistence files to remote clients
 * following the HTTP Segment Transfer pattern. It serves:</p>
 * <ul>
 *   <li>/journal.log - The revision journal</li>
 *   <li>/manifest - The manifest file</li>
 *   <li>/gc.log - The garbage collection log</li>
 *   <li>/segments/{id} - Individual segment files</li>
 *   <li>/health - Health check endpoint</li>
 * </ul>
 * 
 * <p>This implementation uses Jetty for HTTP serving and reads files directly
 * from the segment store directory, avoiding dependencies on internal Oak classes.</p>
 */
public class SegmentHttpServer {
    
    private static final Logger log = LoggerFactory.getLogger(SegmentHttpServer.class);
    
    private final Server server;
    private final ServerContext context;
    private final RequestRouter router;
    
    // Keep references for backward compatibility and methods that need direct access
    private final FileStore fileStore;
    private final NodeStore nodeStore;
    private final Path storeDirectory;
    
    /**
     * Create a new HTTP server for serving segment store files.
     * 
     * @param storeDirectory The segment store directory path
     * @param port The HTTP port to listen on
     * @param fileStore The Oak FileStore instance
     * @param nodeStore The Oak NodeStore instance
     */
    public SegmentHttpServer(File storeDirectory, int port, FileStore fileStore, NodeStore nodeStore) {
        this.storeDirectory = storeDirectory.toPath();
        this.fileStore = fileStore;  // Use existing FileStore!
        this.nodeStore = nodeStore;  // Use existing NodeStore!
        
        // Create ServerContext with initial values
        this.context = new ServerContext(fileStore, nodeStore, this.storeDirectory, "http://localhost:8090");
        
        // Create RequestRouter (will be updated when consensus engines are set)
        this.router = new RequestRouter(context);
        
        this.server = new Server(port);
        this.server.setHandler(new SegmentStoreHandler());
        
        // Initialize Prometheus metrics (JVM metrics: memory, GC, threads, etc.)
        DefaultExports.initialize();
        
        log.info("Initialized SegmentHttpServer");
        log.info("   - Port: {}", port);
        log.info("   - Store: {}", this.storeDirectory);
        log.info("   - Prometheus metrics enabled at /metrics");
    }
    
    /**
     * Set the consensus engine for coordinating writes (linear blockchain mode).
     * Must be called before start() if consensus is needed.
     */
    public void setConsensusEngine(ConsensusEngine engine) {
        context.setConsensusEngine(engine);
        log.info("Linear Blockchain consensus engine configured");
    }
    
    /**
     * Set the DAG consensus engine (distributed DAG mode).
     * Must be called before start() if DAG consensus is needed.
     */
    public void setDagConsensusEngine(org.apache.jackrabbit.oak.segment.consensus.dag.DagConsensusEngine engine) {
        context.setDagConsensusEngine(engine);
        log.info("🌳 DAG consensus engine configured");
    }
    
    /**
     * Set the Leader consensus engine (leader-based mode).
     * Must be called before start() if Leader consensus is needed.
     */
    public void setEpochLeaderEngine(org.apache.jackrabbit.oak.segment.consensus.leader.EpochLeaderEngine engine) {
        context.setEpochLeaderEngine(engine);
        log.info("🎖️  Epoch-based leader consensus engine configured");
        
        // Initialize proof verifier for Byzantine fault tolerance
        if (engine != null) {
            int leaderTermSeconds = engine.getElection().getLeaderTermSeconds();
            org.apache.jackrabbit.oak.segment.consensus.security.ProofVerifier proofVerifier = 
                new org.apache.jackrabbit.oak.segment.consensus.security.ProofVerifier(
                    fileStore, leaderTermSeconds);
            context.setProofVerifier(proofVerifier);
            log.info("🛡️  Proof-of-Readiness verifier initialized");
        }
    }
    
    /**
     * Set the Aeron Cluster consensus engine (Raft-based mode).
     * Must be called before start() if Aeron Cluster consensus is needed.
     */
    public void setAeronConsensusEngine(org.apache.jackrabbit.oak.segment.consensus.aeron.AeronConsensusEngine engine) {
        context.setAeronConsensusEngine(engine);
        log.info("✈️  Aeron Cluster consensus engine configured");
    }
    
    /**
     * Set the self URL for this validator (used for correct display in dashboard).
     */
    public void setSelfUrl(String url) {
        context.setSelfUrl(url);
        log.info("Self URL set to: {}", url);
    }
    
    /**
     * Generate Proof-of-Readiness for joining consensus.
     * 
     * This cryptographically proves the validator is ready to participate in consensus.
     * Called before broadcasting presence to the network.
     */
    private org.apache.jackrabbit.oak.segment.consensus.security.JoinProof generateJoinProof(
            String validatorId, String validatorUrl) {
        
        org.apache.jackrabbit.oak.segment.consensus.security.JoinProof proof = 
            new org.apache.jackrabbit.oak.segment.consensus.security.JoinProof();
        
        try {
            // 1. Proof of Sync - Current HEAD
            String currentHead = fileStore.getHead().getRecordId().toString();
            proof.setHeadSegmentId(currentHead);
            proof.setHeadCapturedAt(System.currentTimeMillis());
            
            // 2. Proof of Epoch Alignment - Current epoch
            if (context.epochLeaderEngine != null) {
                int currentEpoch = context.epochLeaderEngine.getElection().getCurrentEpoch();
                proof.setCurrentEpoch(currentEpoch);
                proof.setEpochCalculatedAt(System.currentTimeMillis());
            }
            
            // 3. Proof of Genesis - Genesis segment
            // For POC, we'll use the HEAD as genesis (in production, track actual genesis)
            proof.setGenesisSegmentId(currentHead);
            proof.setGenesisHash("sha256-poc-genesis"); // TODO: Actual hash
            
            // 4. Proof of Capability - Validator ID
            proof.setValidatorId(validatorId);
            proof.setValidatorUrl(validatorUrl);
            // TODO: Sign a nonce for production
            proof.setChallengeNonce("poc-nonce");
            proof.setNonceSignature("poc-signature");
            
            // 5. Proof of Segment Access - Sample segments
            // Get a few random segments as proof we have the data
            java.util.List<String> sampleIds = new java.util.ArrayList<>();
            java.util.List<String> sampleHashes = new java.util.ArrayList<>();
            
            // For POC, just use current HEAD as sample
            for (int i = 0; i < 5; i++) {
                sampleIds.add(currentHead);
                sampleHashes.add("sha256-sample-" + i);
            }
            
            proof.setSampleSegmentIds(sampleIds);
            proof.setSampleSegmentHashes(sampleHashes);
            
            log.info("✅ Generated Join Proof:");
            log.info("   HEAD: {}", currentHead.substring(0, Math.min(24, currentHead.length())));
            log.info("   Epoch: {}", proof.getCurrentEpoch());
            log.info("   Samples: {}", sampleIds.size());
            
        } catch (Exception e) {
            log.error("❌ Failed to generate join proof", e);
        }
        
        return proof;
    }
    
    /**
     * Register this validator with peer validators.
     * Called during startup to announce this validator's presence to the network.
     * 
     * <p>Uses retry logic with exponential backoff to handle timing issues when
     * peers aren't ready yet (common in Docker Compose startup scenarios).
     * 
     * @param validatorId Unique identifier for this validator (e.g., "validator-1")
     * @param peerUrls List of peer validator URLs to register with
     */
    public void registerWithPeers(String validatorId, java.util.List<String> peerUrls) {
        // CRITICAL: Register self first, even with no peers (genesis scenario)
        ValidatorRegistration selfReg = new ValidatorRegistration(validatorId, context.selfUrl);
        selfReg.updateStatus(ValidatorRegistration.Status.READY);
        context.registeredValidators.put(validatorId, selfReg);
        log.info("✅ Self registered: {} ({})", validatorId, context.selfUrl);
        
        if (peerUrls == null || peerUrls.isEmpty()) {
            log.info("📡 Genesis validator - no peers to register with");
            return;
        }
        
        log.info("📡 Registering with {} peer validators (with retry logic)...", peerUrls.size());
        
        // Retry configuration
        final int maxRetries = 5;
        final int initialDelayMs = 2000; // 2 seconds
        final int maxDelayMs = 16000;    // 16 seconds
        
        for (String peerUrl : peerUrls) {
            // Skip self
            if (peerUrl.equals(context.selfUrl)) {
                continue;
            }
            
            boolean registered = false;
            for (int attempt = 1; attempt <= maxRetries; attempt++) {
                try {
                    // Build registration URL
                    String registrationUrl = peerUrl + "/v1/register-validator";
                    
                    // Build JSON payload
                    String jsonPayload = String.format(
                        "{\"validatorId\":\"%s\",\"validatorUrl\":\"%s\"}",
                        validatorId.replace("\"", "\\\""),
                        context.selfUrl.replace("\"", "\\\"")
                    );
                    
                    // Send registration request
                    java.net.URL url = new java.net.URL(registrationUrl);
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.setDoOutput(true);
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(10000);
                    
                    // Write JSON payload
                    try (java.io.OutputStream os = conn.getOutputStream()) {
                        byte[] input = jsonPayload.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        os.write(input, 0, input.length);
                    }
                    
                    int responseCode = conn.getResponseCode();
                    if (responseCode == 200) {
                        log.info("✅ Registered with peer validator: {} (attempt {}/{})", peerUrl, attempt, maxRetries);
                        registered = true;
                        break; // Success - exit retry loop
                    } else {
                        log.debug("⚠️  Registration attempt {}/{} failed for {}: HTTP {}", attempt, maxRetries, peerUrl, responseCode);
                    }
                    
                } catch (Exception e) {
                    log.debug("⚠️  Registration attempt {}/{} failed for {}: {}", attempt, maxRetries, peerUrl, e.getMessage());
                }
                
                // Exponential backoff: 2s, 4s, 8s, 16s, 16s
                if (attempt < maxRetries) {
                    int delayMs = Math.min(initialDelayMs * (1 << (attempt - 1)), maxDelayMs);
                    try {
                        log.debug("⏳ Retrying registration with {} in {}ms...", peerUrl, delayMs);
                        Thread.sleep(delayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.warn("⚠️  Registration retry interrupted");
                        break;
                    }
                }
            }
            
            if (!registered) {
                log.warn("⚠️  Failed to register with peer validator {} after {} attempts", peerUrl, maxRetries);
            }
        }
        
        log.info("📡 Validator peer registration complete");
    }
    
    /**
     * Broadcast presence to the consensus network (Dynamic Peer Discovery).
     * 
     * This is called when a validator is promoted to PRIMARY and is ready to
     * join the consensus network. Existing validators will receive this broadcast
     * and dynamically add this validator to their consensus peer list.
     * 
     * @param validatorId The unique ID of this validator
     * @param validatorUrl The URL of this validator (consensus endpoint)
     * @param peerUrls List of known peer validators to broadcast to
     */
    public void broadcastPresenceToNetwork(String validatorId, String validatorUrl, 
                                          java.util.List<String> peerUrls) {
        if (peerUrls == null || peerUrls.isEmpty()) {
            log.info("📡 No peers configured - running as genesis validator");
            return;
        }
        
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        log.info("📣 BROADCASTING PRESENCE TO CONSENSUS NETWORK");
        log.info("   Validator ID: {}", validatorId);
        log.info("   Validator URL: {}", validatorUrl);
        log.info("   Broadcasting to: {} peers", peerUrls.size());
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        
        // Generate Proof-of-Readiness before broadcasting
        org.apache.jackrabbit.oak.segment.consensus.security.JoinProof proof = 
            generateJoinProof(validatorId, validatorUrl);
        
        int successCount = 0;
        int failureCount = 0;
        
        for (String peerUrl : peerUrls) {
            try {
                // Skip self
                if (peerUrl.equals(validatorUrl)) {
                    log.debug("   Skipping self: {}", peerUrl);
                    continue;
                }
                
                // Build peer-joined endpoint URL
                String peerJoinedUrl = peerUrl + "/v1/consensus/peer-joined";
                
                // PHASE 3: Get public key for Byzantine fault tolerance
                String publicKeyHex = context.epochLeaderEngine != null ? 
                    context.epochLeaderEngine.getPublicKeyHex() : "";
                
                // Build JSON payload with proof and public key (PHASE 3)
                String jsonPayload = String.format(
                    "{\"validatorId\":\"%s\",\"validatorUrl\":\"%s\",\"proof\":%s,\"publicKey\":\"%s\"}",
                    validatorId.replace("\"", "\\\""),
                    validatorUrl.replace("\"", "\\\""),
                    proof.toJson(),
                    publicKeyHex
                );
                
                log.info("   → Broadcasting to {} (with public key)", peerUrl);
                
                // Send broadcast request
                java.net.URL url = new java.net.URL(peerJoinedUrl);
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(10000);
                
                // Write JSON payload
                try (java.io.OutputStream os = conn.getOutputStream()) {
                    byte[] input = jsonPayload.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    os.write(input, 0, input.length);
                }
                
                int responseCode = conn.getResponseCode();
                
                if (responseCode == 200) {
                    // Read response
                    java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(conn.getInputStream())
                    );
                    String response = reader.lines().collect(java.util.stream.Collectors.joining());
                    reader.close();
                    
                    log.info("   ✅ Accepted by peer: {}", peerUrl);
                    log.debug("      Response: {}", response);
                    
                    // CRITICAL: Extract peer's public key from response and register it
                    // This allows us to verify their leadership claims later
                    try {
                        int pkStart = response.indexOf("\"publicKey\"");
                        if (pkStart != -1 && context.epochLeaderEngine != null) {
                            pkStart = response.indexOf(":", pkStart) + 1;
                            int pkEnd = response.indexOf("\"", pkStart + 2);
                            if (pkEnd != -1) {
                                String peerPublicKey = response.substring(pkStart + 1, pkEnd);
                                if (!peerPublicKey.isEmpty()) {
                                    context.epochLeaderEngine.getClaimVerifier().registerPublicKey(peerUrl, peerPublicKey);
                                    log.info("   🔑 Registered public key from peer: {}...", 
                                        peerPublicKey.substring(0, Math.min(18, peerPublicKey.length())));
                                }
                            }
                        }
                    } catch (Exception e) {
                        log.warn("   ⚠️  Failed to extract peer public key: {}", e.getMessage());
                    }
                    
                    successCount++;
                    
                } else {
                    log.warn("   ❌ Rejected by peer {}: HTTP {}", peerUrl, responseCode);
                    failureCount++;
                }
                
            } catch (java.net.ConnectException e) {
                log.warn("   ⚠️  Cannot reach peer {}: {}", peerUrl, e.getMessage());
                failureCount++;
            } catch (Exception e) {
                log.error("   ❌ Failed to broadcast to peer {}: {}", peerUrl, e.getMessage());
                failureCount++;
            }
        }
        
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        log.info("📡 BROADCAST COMPLETE: {} accepted, {} failed", successCount, failureCount);
        
        if (successCount > 0) {
            log.info("✅ Successfully joined consensus network ({}/{} peers)", 
                successCount, peerUrls.size());
            // Mark self as READY after successful broadcast
            ValidatorRegistration selfReg = new ValidatorRegistration(validatorId, validatorUrl);
            selfReg.updateStatus(ValidatorRegistration.Status.READY);
            context.registeredValidators.put(validatorId, selfReg);
            log.info("✅ Self marked as READY");
        } else if (peerUrls.isEmpty() || (peerUrls.size() == 1 && peerUrls.get(0).equals(validatorUrl))) {
            log.info("✅ Genesis validator - no peers to broadcast to");
            // Genesis validator is immediately READY
            ValidatorRegistration selfReg = new ValidatorRegistration(validatorId, validatorUrl);
            selfReg.updateStatus(ValidatorRegistration.Status.READY);
            context.registeredValidators.put(validatorId, selfReg);
        } else {
            log.warn("⚠️  FAILED to join consensus - no peers accepted broadcast!");
            log.warn("    This validator may be isolated from the network.");
        }
        
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    }
    
    /**
     * Start the HTTP server.
     * FileStore is already provided via constructor - no need to open again!
     */
    public void start() throws Exception {
        log.info("Starting HTTP server (using existing FileStore)...");
        
        server.start();
        log.info("SegmentHttpServer started on port {}", server.getURI().getPort());
        log.info("   - GET  /journal.log");
        log.info("   - GET  /manifest");
        log.info("   - GET  /gc.log");
        log.info("   - GET  /segments/{{segmentId}}");
        log.info("   - HEAD /segments/{{segmentId}}");
        log.info("   - GET  /health");
    }
    
    /**
     * Stop the HTTP server.
     * Note: FileStore is owned by GlobalStoreServer, don't close it here!
     */
    public void stop() throws Exception {
        if (server != null) {
            server.stop();
            log.info("SegmentHttpServer stopped");
        }
    }
    
    /**
     * Join the server thread (for standalone operation).
     */
    public void join() throws InterruptedException {
        server.join();
    }
    
    /**
     * Jetty handler for serving segment store files.
     * Delegates to RequestRouter for routing to appropriate handlers.
     */
    private class SegmentStoreHandler extends AbstractHandler {
        
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request,
                          HttpServletResponse response) throws IOException, ServletException {
            
            String path = request.getPathInfo();
            String method = request.getMethod();
            
            log.debug("HTTP {} {}", method, path);
            
            try {
                // Delegate to RequestRouter
                router.route(baseRequest, request, response);
            } catch (Exception e) {
                log.error("Error handling request {} {}", method, path, e);
                if (!response.isCommitted()) {
                    response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, 
                        "Internal server error: " + e.getMessage());
                }
            }
        }
    }
}

