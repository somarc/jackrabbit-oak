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

import org.apache.jackrabbit.oak.segment.Segment;
import org.apache.jackrabbit.oak.segment.SegmentId;
import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.apache.jackrabbit.oak.segment.file.FileStoreBuilder;
import org.apache.jackrabbit.oak.segment.consensus.ConsensusEngine;
import org.apache.jackrabbit.oak.segment.consensus.Vote;
import org.apache.jackrabbit.oak.segment.consensus.WriteProposal;
import org.apache.jackrabbit.oak.segment.consensus.metrics.ConsensusMetrics;
import org.apache.jackrabbit.oak.spi.state.NodeStore;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.exporter.common.TextFormat;
import io.prometheus.client.hotspot.DefaultExports;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;
import java.util.stream.Collectors;

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
    private final Path storeDirectory;
    private FileStore fileStore;  // Oak FileStore for reading segments
    private NodeStore nodeStore;  // Oak NodeStore for content browsing
    private ConsensusEngine consensusEngine;  // Linear blockchain consensus
    private org.apache.jackrabbit.oak.segment.consensus.dag.DagConsensusEngine dagConsensusEngine;  // Distributed DAG consensus
    private org.apache.jackrabbit.oak.segment.consensus.leader.LeaderConsensusEngine leaderConsensusEngine;  // Leader-based consensus
    
    // Track registered Sling Author clients (deterministic registration)
    private final java.util.Map<String, ClientRegistration> registeredClients = new java.util.concurrent.ConcurrentHashMap<>();
    
    // Track registered validator peers (validator-to-validator registration)
    private final java.util.Map<String, ValidatorRegistration> registeredValidators = new java.util.concurrent.ConcurrentHashMap<>();
    
    // Self URL for this validator (set by GlobalStoreServer)
    private String selfUrl = "http://localhost:8090";
    
    // Track connected peers (Sling Author instances mounting this store)
    private final java.util.Set<String> connectedPeers = java.util.concurrent.ConcurrentHashMap.newKeySet();
    
    // Track recent writes with metadata (recordId -> WriteMetadata)
    private final java.util.Map<String, WriteMetadata> recentWriteMetadata = new java.util.concurrent.ConcurrentHashMap<>();
    
    // Proof-of-Readiness verifier for Byzantine fault tolerance
    private org.apache.jackrabbit.oak.segment.consensus.security.ProofVerifier proofVerifier;
    
    /**
     * Client registration information
     */
    private static class ClientRegistration {
        String clientId;        // Unique identifier (e.g., container name)
        String clientUrl;       // Client's URL/address
        String walletAddress;   // Wallet address if provided
        long registeredAt;      // Timestamp
        long lastSeen;          // Last heartbeat
        
        ClientRegistration(String clientId, String clientUrl, String walletAddress) {
            this.clientId = clientId;
            this.clientUrl = clientUrl;
            this.walletAddress = walletAddress;
            this.registeredAt = System.currentTimeMillis();
            this.lastSeen = System.currentTimeMillis();
        }
    }
    
    /**
     * Validator peer registration information
     */
    private static class ValidatorRegistration {
        enum Status {
            JOINING,    // Just joined, broadcasting presence
            SYNCING,    // Bootstrap sync in progress
            READY       // Fully synced and participating in consensus
        }
        
        String validatorId;     // Unique identifier (e.g., validator-1)
        String validatorUrl;    // Validator's URL/address
        Status status;          // Current validator status
        long registeredAt;      // Timestamp
        long lastSeen;          // Last heartbeat
        
        ValidatorRegistration(String validatorId, String validatorUrl) {
            this.validatorId = validatorId;
            this.validatorUrl = validatorUrl;
            this.status = Status.JOINING;  // Start as JOINING
            this.registeredAt = System.currentTimeMillis();
            this.lastSeen = System.currentTimeMillis();
        }
        
        void updateStatus(Status newStatus) {
            this.status = newStatus;
            this.lastSeen = System.currentTimeMillis();
        }
    }
    
    /**
     * Metadata about a write for dashboard display
     */
    private static class WriteMetadata {
        String recordId;
        String source;      // "consensus", "epoch-sync", or "local"
        String validator;   // URL of validator that wrote this
        long timestamp;
        String message;     // Optional message (for test writes)
        
        WriteMetadata(String recordId, String source, String validator, long timestamp, String message) {
            this.recordId = recordId;
            this.source = source;
            this.validator = validator;
            this.timestamp = timestamp;
            this.message = message;
        }
    }
    
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
        this.consensusEngine = engine;
        log.info("Linear Blockchain consensus engine configured");
    }
    
    /**
     * Set the DAG consensus engine (distributed DAG mode).
     * Must be called before start() if DAG consensus is needed.
     */
    public void setDagConsensusEngine(org.apache.jackrabbit.oak.segment.consensus.dag.DagConsensusEngine engine) {
        this.dagConsensusEngine = engine;
        log.info("🌳 DAG consensus engine configured");
    }
    
    /**
     * Set the Leader consensus engine (leader-based mode).
     * Must be called before start() if Leader consensus is needed.
     */
    public void setLeaderConsensusEngine(org.apache.jackrabbit.oak.segment.consensus.leader.LeaderConsensusEngine engine) {
        this.leaderConsensusEngine = engine;
        log.info("🎖️  Leader consensus engine configured");
        
        // Initialize proof verifier for Byzantine fault tolerance
        if (engine != null) {
            int leaderTermSeconds = engine.getElection().getLeaderTermSeconds();
            this.proofVerifier = new org.apache.jackrabbit.oak.segment.consensus.security.ProofVerifier(
                fileStore, leaderTermSeconds);
            log.info("🛡️  Proof-of-Readiness verifier initialized");
        }
    }
    
    /**
     * Set the self URL for this validator (used for correct display in dashboard).
     */
    public void setSelfUrl(String url) {
        this.selfUrl = url;
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
            if (leaderConsensusEngine != null) {
                int currentEpoch = leaderConsensusEngine.getElection().getCurrentEpoch();
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
     * @param validatorId Unique identifier for this validator (e.g., "validator-1")
     * @param peerUrls List of peer validator URLs to register with
     */
    public void registerWithPeers(String validatorId, java.util.List<String> peerUrls) {
        // CRITICAL: Register self first, even with no peers (genesis scenario)
        ValidatorRegistration selfReg = new ValidatorRegistration(validatorId, selfUrl);
        selfReg.updateStatus(ValidatorRegistration.Status.READY);
        registeredValidators.put(validatorId, selfReg);
        log.info("✅ Self registered: {} ({})", validatorId, selfUrl);
        
        if (peerUrls == null || peerUrls.isEmpty()) {
            log.info("📡 Genesis validator - no peers to register with");
            return;
        }
        
        log.info("📡 Registering with {} peer validators...", peerUrls.size());
        
        for (String peerUrl : peerUrls) {
            try {
                // Skip self
                if (peerUrl.equals(selfUrl)) {
                    continue;
                }
                
                // Build registration URL
                String registrationUrl = peerUrl + "/v1/register-validator";
                
                // Build JSON payload
                String jsonPayload = String.format(
                    "{\"validatorId\":\"%s\",\"validatorUrl\":\"%s\"}",
                    validatorId.replace("\"", "\\\""),
                    selfUrl.replace("\"", "\\\"")
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
                    log.info("✅ Registered with peer validator: {} ({})", peerUrl, validatorId);
                } else {
                    log.warn("⚠️  Failed to register with {}: HTTP {}", peerUrl, responseCode);
                }
                
            } catch (Exception e) {
                log.warn("⚠️  Failed to register with peer validator {}: {}", peerUrl, e.getMessage());
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
                String publicKeyHex = leaderConsensusEngine != null ? 
                    leaderConsensusEngine.getPublicKeyHex() : "";
                
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
            registeredValidators.put(validatorId, selfReg);
            log.info("✅ Self marked as READY");
        } else if (peerUrls.isEmpty() || (peerUrls.size() == 1 && peerUrls.get(0).equals(validatorUrl))) {
            log.info("✅ Genesis validator - no peers to broadcast to");
            // Genesis validator is immediately READY
            ValidatorRegistration selfReg = new ValidatorRegistration(validatorId, validatorUrl);
            selfReg.updateStatus(ValidatorRegistration.Status.READY);
            registeredValidators.put(validatorId, selfReg);
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
     */
    private class SegmentStoreHandler extends AbstractHandler {
        
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request,
                          HttpServletResponse response) throws IOException, ServletException {
            
            String path = request.getPathInfo();
            String method = request.getMethod();
            
            log.debug("HTTP {} {}", method, path);
            
            try {
                // Dashboard homepage
                if ("/".equals(path) || path == null) {
                    handleDashboard(response);
                    baseRequest.setHandled(true);
                    return;
                }
                
                // Health check (simple)
                if ("/health".equals(path)) {
                    handleHealth(response);
                    baseRequest.setHandled(true);
                    return;
                }
                
                // Deep health check (comprehensive validation)
                if ("/health/deep".equals(path)) {
                    handleDeepHealth(response);
                    baseRequest.setHandled(true);
                    return;
                }
                
                // Journal file
                if ("/journal.log".equals(path)) {
                    handleFile(request, response, "journal.log", "text/plain");
                    baseRequest.setHandled(true);
                    return;
                }
                
                // Manifest file
                if ("/manifest".equals(path)) {
                    handleFile(request, response, "manifest", "text/plain");
                    baseRequest.setHandled(true);
                    return;
                }
                
                // GC log
                if ("/gc.log".equals(path)) {
                    handleFile(request, response, "gc.log", "text/plain");
                    baseRequest.setHandled(true);
                    return;
                }
                
                // Segments
                if (path != null && path.startsWith("/segments/")) {
                    String segmentId = path.substring("/segments/".length());
                    if ("HEAD".equals(method)) {
                        handleSegmentHead(response, segmentId);
                    } else if ("GET".equals(method)) {
                        handleSegmentGet(request, response, segmentId);
                    } else {
                        response.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
                    }
                    baseRequest.setHandled(true);
                    return;
                }
                
                // Explorer API - Browse nodes
                if ("/api/explore".equals(path)) {
                    String nodePath = request.getParameter("path");
                    handleExploreNode(response, nodePath != null ? nodePath : "/");
                    baseRequest.setHandled(true);
                    return;
                }
                
                // Explorer API - Recent segments
                if ("/api/segments/recent".equals(path)) {
                    handleRecentSegments(response);
                    baseRequest.setHandled(true);
                    return;
                }
                
                // Explorer API - TAR files
                if ("/api/segments/tars".equals(path)) {
                    handleTarFiles(response);
                    baseRequest.setHandled(true);
                    return;
                }
                
                // Explorer UI
                if ("/explorer".equals(path)) {
                    handleExplorerUI(response);
                    baseRequest.setHandled(true);
                    return;
                }
                
                // API Browser UI (HAL-style interactive API explorer)
                if ("/api-browser".equals(path)) {
                    handleApiBrowserUI(response);
                    baseRequest.setHandled(true);
                    return;
                }
                
                // Consensus API - Propose write
                if ("/v1/propose".equals(path) && "POST".equals(method)) {
                    handleWriteProposal(request, response);
                    baseRequest.setHandled(true);
                    return;
                }
                
                // Consensus API - Vote
                if ("/v1/vote".equals(path) && "POST".equals(method)) {
                    handleVote(request, response);
                    baseRequest.setHandled(true);
                    return;
                }
                
                // Consensus API - List peers (REMOVED - duplicate of handlePeerList at line ~690)
                
                // Metrics API - Consensus and replication metrics (JSON format)
                if ("/api/metrics".equals(path) && "GET".equals(method)) {
                    handleMetrics(response);
                    baseRequest.setHandled(true);
                    return;
                }
                
                // Prometheus Metrics Endpoint - Prometheus format for scraping
                if ("/metrics".equals(path) && "GET".equals(method)) {
                    handlePrometheusMetrics(response);
                    baseRequest.setHandled(true);
                    return;
                }
                
                // HEAD ENDPOINT - Return current HEAD record ID (for bootstrap sync detection)
                if ("/v1/head".equals(path) && "GET".equals(method)) {
                    response.setContentType("text/plain");
                    response.setStatus(HttpServletResponse.SC_OK);
                    String headId = fileStore.getHead().getRecordId().toString();
                    response.getWriter().write(headId);
                    baseRequest.setHandled(true);
                    return;
                }
                
                // NGROK URL ENDPOINT - Return this validator's ngrok public URL for dynamic discovery
                if ("/v1/ngrok-url".equals(path) && "GET".equals(method)) {
                    response.setContentType("text/plain");
                    response.setStatus(HttpServletResponse.SC_OK);
                    // selfUrl is set from CONSENSUS_SELF_URL which includes ngrok URL
                    response.getWriter().write(selfUrl != null ? selfUrl : "");
                    baseRequest.setHandled(true);
                    return;
                }
                
                // CLIENT REGISTRATION - Sling authors register when mounting
                if ("/v1/register-client".equals(path) && ("POST".equals(method) || "PUT".equals(method))) {
                    handleClientRegistration(request, response);
                    baseRequest.setHandled(true);
                    return;
                }
                
                // VALIDATOR REGISTRATION - Validators register with each other
                if ("/v1/register-validator".equals(path) && ("POST".equals(method) || "PUT".equals(method))) {
                    handleValidatorRegistration(request, response);
                    baseRequest.setHandled(true);
                    return;
                }
                
                // PEER LIST - Return list of all known validators for organic discovery
                if ("/v1/peers".equals(path) && "GET".equals(method)) {
                    handlePeerList(response);
                    baseRequest.setHandled(true);
                    return;
                }
                
                // TEST ENDPOINT - Simulate a write with consensus
                if ("/v1/test-write".equals(path) && "POST".equals(method)) {
                    handleTestWrite(request, response);
                    baseRequest.setHandled(true);
                    return;
                }
                
                // DAG ENDPOINT - Receive HEAD update from peer (git fetch)
                if ("/v1/dag/head".equals(path) && "POST".equals(method)) {
                    handleDagHeadUpdate(request, response);
                    baseRequest.setHandled(true);
                    return;
                }
                
                // LEADER ENDPOINT - Follower receives HEAD update from leader
                if ("/v1/follower/head-update".equals(path) && "POST".equals(method)) {
                    handleFollowerHeadUpdate(request, response);
                    baseRequest.setHandled(true);
                    return;
                }
                
                // HEARTBEAT ENDPOINT - Follower receives heartbeat from leader
                if ("/v1/heartbeat".equals(path) && "POST".equals(method)) {
                    handleHeartbeat(request, response);
                    baseRequest.setHandled(true);
                    return;
                }
                
                // PEER JOINED ENDPOINT - Validator broadcasts its presence to network
                if ("/v1/consensus/peer-joined".equals(path) && "POST".equals(method)) {
                    handlePeerJoined(request, response);
                    baseRequest.setHandled(true);
                    return;
                }
                
                // LEADERSHIP CLAIM ENDPOINT - Elected leader broadcasts claim to prove liveness
                if ("/v1/consensus/claim-leadership".equals(path) && "POST".equals(method)) {
                    handleLeadershipClaim(request, response);
                    baseRequest.setHandled(true);
                    return;
                }
                
                // CLAIM ACK ENDPOINT - Follower acknowledges leader's claim (PHASE 2: Quorum)
                if ("/v1/consensus/claim-ack".equals(path) && "POST".equals(method)) {
                    handleClaimAck(request, response);
                    baseRequest.setHandled(true);
                    return;
                }
                
                // PUBLIC KEY REGISTRATION - Validators register their public keys (PHASE 3)
                if ("/v1/consensus/register-public-key".equals(path) && "POST".equals(method)) {
                    handlePublicKeyRegistration(request, response);
                    baseRequest.setHandled(true);
                    return;
                }
                
                // Not found
                response.sendError(HttpServletResponse.SC_NOT_FOUND);
                baseRequest.setHandled(true);
                
            } catch (Exception e) {
                log.error("Error handling request: " + path, e);
                response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
                baseRequest.setHandled(true);
            }
        }
        
        /**
         * Handle dashboard homepage with live statistics.
         */
        private void handleDashboard(HttpServletResponse response) throws IOException {
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType("text/html; charset=UTF-8");
            
            // Get FileStore statistics
            long storeSize = fileStore.size();
            int segmentCount = fileStore.getSegmentCount();
            
            // Read journal for latest writes (last 10 lines)
            java.util.List<String> recentWrites = new java.util.ArrayList<>();
            try {
                Path journalPath = storeDirectory.resolve("journal.log");
                if (java.nio.file.Files.exists(journalPath)) {
                    java.util.List<String> allLines = java.nio.file.Files.readAllLines(journalPath);
                    int start = Math.max(0, allLines.size() - 10);
                    recentWrites = allLines.subList(start, allLines.size());
                    java.util.Collections.reverse(recentWrites); // Most recent first
                }
            } catch (Exception e) {
                log.warn("Failed to read journal for dashboard", e);
            }
            
            StringBuilder html = new StringBuilder();
            html.append("<!DOCTYPE html>\n");
            html.append("<html>\n<head>\n");
            html.append("<meta charset='UTF-8'>\n");
            html.append("<meta name='viewport' content='width=device-width, initial-scale=1.0'>\n");
            html.append("<title>🔗 Oak Segment Consensus - Global Store</title>\n");
            html.append("<script src='https://cdn.jsdelivr.net/npm/mermaid@10/dist/mermaid.min.js'></script>\n");
            html.append("<script>mermaid.initialize({ startOnLoad: true, theme: 'dark' });</script>\n");
            html.append("<style>\n");
            html.append("* { margin: 0; padding: 0; box-sizing: border-box; }\n");
            html.append("body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Oxygen, Ubuntu, sans-serif; ");
            html.append("background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); color: #fff; min-height: 100vh; padding: 20px; }\n");
            html.append(".container { max-width: 1200px; margin: 0 auto; }\n");
            html.append("header { text-align: center; padding: 40px 0; }\n");
            html.append("h1 { font-size: 3em; margin-bottom: 10px; text-shadow: 2px 2px 4px rgba(0,0,0,0.3); }\n");
            html.append(".subtitle { font-size: 1.2em; opacity: 0.9; }\n");
            html.append(".grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 20px; margin: 30px 0; max-width: 1400px; }\n");
            html.append(".card { background: rgba(255,255,255,0.1); backdrop-filter: blur(10px); border-radius: 15px; ");
            html.append("padding: 25px; box-shadow: 0 8px 32px rgba(0,0,0,0.1); border: 1px solid rgba(255,255,255,0.2); }\n");
            html.append(".card h2 { font-size: 1.5em; margin-bottom: 15px; display: flex; align-items: center; gap: 10px; }\n");
            html.append(".stat { font-size: 2.5em; font-weight: bold; margin: 10px 0; }\n");
            html.append(".label { font-size: 0.9em; opacity: 0.8; text-transform: uppercase; letter-spacing: 1px; }\n");
            html.append(".journal-entry { background: rgba(0,0,0,0.2); padding: 10px; margin: 8px 0; border-radius: 5px; ");
            html.append("font-family: 'Courier New', monospace; font-size: 0.85em; word-break: break-all; }\n");
            html.append(".pulse { animation: pulse 2s ease-in-out infinite; }\n");
            html.append("@keyframes pulse { 0%, 100% { opacity: 1; } 50% { opacity: 0.6; } }\n");
            html.append(".status { display: inline-block; width: 12px; height: 12px; background: #4ade80; border-radius: 50%; ");
            html.append("animation: pulse 2s ease-in-out infinite; margin-right: 8px; }\n");
            html.append(".endpoints { display: grid; gap: 10px; margin-top: 15px; }\n");
            html.append(".endpoint { background: rgba(0,0,0,0.2); padding: 10px; border-radius: 5px; font-size: 0.9em; }\n");
            html.append(".endpoint code { background: rgba(255,255,255,0.1); padding: 2px 6px; border-radius: 3px; }\n");
            html.append(".tooltip { position: relative; display: inline-block; cursor: help; }\n");
            html.append(".tooltip .tooltiptext { visibility: hidden; width: 320px; background-color: rgba(0,0,0,0.95); color: #fff; ");
            html.append("text-align: left; border-radius: 8px; padding: 15px; position: absolute; z-index: 1000; bottom: 125%; ");
            html.append("left: 50%; margin-left: -160px; opacity: 0; transition: opacity 0.3s; font-size: 0.85em; ");
            html.append("box-shadow: 0 8px 24px rgba(0,0,0,0.6); border: 1px solid rgba(255,255,255,0.1); }\n");
            html.append(".tooltip .tooltiptext::after { content: ''; position: absolute; top: 100%; left: 50%; margin-left: -5px; ");
            html.append("border-width: 5px; border-style: solid; border-color: rgba(0,0,0,0.95) transparent transparent transparent; }\n");
            html.append(".tooltip:hover .tooltiptext { visibility: visible; opacity: 1; }\n");
            html.append(".mermaid { background: rgba(0,0,0,0.3); border-radius: 12px; padding: 20px; margin: 20px 0; overflow-x: auto; }\n");
            html.append(".network-graph { background: rgba(0,0,0,0.2); border-radius: 12px; padding: 30px; margin: 20px 0; min-height: 300px; position: relative; }\n");
            html.append(".validator-node { background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); border-radius: 50%; ");
            html.append("width: 80px; height: 80px; display: flex; align-items: center; justify-content: center; font-size: 1.5em; ");
            html.append("position: absolute; box-shadow: 0 4px 12px rgba(0,0,0,0.3); cursor: pointer; transition: transform 0.3s; }\n");
            html.append(".validator-node:hover { transform: scale(1.1); }\n");
            html.append(".connection-line { stroke: rgba(255,255,255,0.3); stroke-width: 2; stroke-dasharray: 5,5; }\n");
            html.append("</style>\n");
            html.append("<script>\n");
            html.append("// Auto-refresh every 10 seconds\n");
            html.append("setTimeout(() => window.location.reload(), 10000);\n");
            html.append("</script>\n");
            html.append("</head>\n<body>\n");
            html.append("<div class='container'>\n");
            
            // Header
            html.append("<header>\n");
            html.append("<h1>🔗 Oak Segment Consensus</h1>\n");
            html.append("<div class='subtitle'>Global P2P Oak Repository</div>\n");
            html.append("</header>\n");
            
            // Stats Grid
            html.append("<div class='grid'>\n");
            
            // Status Card
            html.append("<div class='card'>\n");
            html.append("<h2><span class='status'></span>Server Status</h2>\n");
            html.append("<div class='stat pulse'>LIVE</div>\n");
            html.append("<div class='label'>HTTP Segment Transfer Active</div>\n");
            html.append("</div>\n");
            
            // Store Size Card
            html.append("<div class='card'>\n");
            html.append("<h2>📦 Store Size</h2>\n");
            html.append("<div class='stat'>").append(formatBytes(storeSize)).append("</div>\n");
            html.append("<div class='label'>").append(segmentCount).append(" Segments</div>\n");
            html.append("</div>\n");
            
            // Validator Network Card (works for blockchain, DAG, and Leader mode)
            int validatorCount = 1; // Self
            String consensusType = "Single";
            String myHeadId = "N/A";
            String myRole = "STANDALONE";
            String roleColor = "#94a3b8"; // slate
            String currentLeader = null;
            
            if (consensusEngine != null) {
                validatorCount = 1 + consensusEngine.getPeerCount();
                consensusType = "Blockchain PoA";
            } else if (dagConsensusEngine != null) {
                // In DAG mode, show total configured validators (self + peers)
                validatorCount = 1 + dagConsensusEngine.getPeerUrls().size();
                consensusType = "Distributed DAG";
                
                // Get my current HEAD ID
                org.apache.jackrabbit.oak.segment.consensus.dag.DagHead myHead = dagConsensusEngine.getMyHead();
                if (myHead != null && myHead.getRecordId() != null) {
                    myHeadId = myHead.getRecordId().length() > 16 
                        ? myHead.getRecordId().substring(0, 16) + "..."
                        : myHead.getRecordId();
                }
            } else if (leaderConsensusEngine != null) {
                // Leader-based consensus mode
                validatorCount = 1 + leaderConsensusEngine.getElection().getPeerValidators().size();
                consensusType = "Leader-Based";
                
                // Check if I'm on probation (self-awareness)
                boolean amOnProbation = false;
                if (!leaderConsensusEngine.isLeader()) {
                    // Check if selfUrl is in non-voting followers
                    java.util.List<String> nonVotingFollowers = leaderConsensusEngine.getNonVotingFollowers();
                    amOnProbation = nonVotingFollowers.contains(selfUrl);
                }
                
                if (leaderConsensusEngine.isLeader()) {
                    myRole = "LEADER";
                    roleColor = "#fbbf24"; // gold
                } else if (amOnProbation) {
                    myRole = "FOLLOWER (PROBATION)";
                    roleColor = "#eab308"; // yellow (probation)
                } else {
                    myRole = "FOLLOWER";
                    roleColor = "#3b82f6"; // blue
                }
                currentLeader = leaderConsensusEngine.getCurrentLeader();
            }
            
            html.append("<div class='card'>\n");
            html.append("<h2>🗳️  Validator Network</h2>\n");
            html.append("<div class='stat'>").append(validatorCount).append("</div>\n");
            html.append("<div class='label'>").append(consensusType).append("</div>\n");
            
            // Leader mode: Show role prominently
            if (leaderConsensusEngine != null) {
                html.append("<div style='margin-top: 12px; padding: 10px; background: ").append(roleColor).append("; border-radius: 8px; text-align: center;'>\n");
                html.append("<div style='font-size: 1.2em; font-weight: 700; color: #fff;'>");
                if (leaderConsensusEngine.isLeader()) {
                    html.append("👑 ").append(myRole).append(" 👑");
                } else {
                    html.append("📡 ").append(myRole);
                }
                html.append("</div>\n");
                html.append("</div>\n");
                
                // Show current leader if we're a follower
                if (!leaderConsensusEngine.isLeader() && currentLeader != null) {
                    String leaderName = currentLeader.contains("validator-") 
                        ? currentLeader.substring(currentLeader.indexOf("validator-")).split(":")[0]
                        : "unknown";
                    html.append("<div style='margin-top: 8px; padding: 8px; background: rgba(0,0,0,0.2); border-radius: 6px; font-size: 0.8em; text-align: center;'>\n");
                    html.append("<div style='opacity: 0.8;'>Current Leader:</div>\n");
                    html.append("<div style='font-weight: 600; color: #fbbf24; margin-top: 4px;'>👑 ").append(leaderName).append("</div>\n");
                    html.append("</div>\n");
                }
                
                // Leader rewards note
                if (leaderConsensusEngine.isLeader()) {
                    html.append("<div style='margin-top: 8px; padding: 8px; background: rgba(251,191,36,0.15); border-radius: 6px; font-size: 0.75em; border-left: 3px solid #fbbf24;'>\n");
                    html.append("<div style='font-weight: 600; margin-bottom: 4px;'>💰 Leader Rewards:</div>\n");
                    html.append("<div style='opacity: 0.9; line-height: 1.4;'>Earning all transaction fees during leadership term</div>\n");
                    html.append("</div>\n");
                }
            } else if (dagConsensusEngine != null) {
                html.append("<div style='margin-top: 12px; padding: 8px; background: rgba(0,0,0,0.2); border-radius: 6px; font-size: 0.8em;'>\n");
                html.append("<div style='opacity: 0.7; margin-bottom: 4px;'>My Current HEAD:</div>\n");
                html.append("<code style='color: #fbbf24; font-weight: 600;'>").append(myHeadId).append("</code>\n");
                html.append("</div>\n");
            }
            
            // Show all validators in network (voting + non-voting)
            if (leaderConsensusEngine != null) {
                // Build complete validator list: self + all followers (voting + non-voting)
                java.util.List<String> allValidators = new java.util.ArrayList<>();
                allValidators.add(selfUrl); // Add self first
                allValidators.addAll(leaderConsensusEngine.getAllFollowers()); // Add all followers
                
                // Get non-voting followers (probationary validators)
                java.util.List<String> nonVotingFollowers = leaderConsensusEngine.getNonVotingFollowers();
                
                html.append("<div style='margin-top: 12px; font-size: 0.75em; opacity: 0.8;'>");
                html.append("<div style='margin-bottom: 6px; font-weight: 600;'>Validator Network:</div>");
                
                for (String validatorUrl : allValidators) {
                    boolean isSelf = validatorUrl.equals(selfUrl);
                    String validatorName = validatorUrl.contains("validator-") 
                        ? validatorUrl.substring(validatorUrl.indexOf("validator-")).split(":")[0]
                        : validatorUrl;
                    
                    // Get validator status
                    ValidatorRegistration.Status status = ValidatorRegistration.Status.READY;  // Default
                    String statusEmoji = "🟢";  // Default green
                    String statusLabel = "";
                    
                    if (!isSelf) {
                        // CRITICAL: Check if validator is on probation (non-voting)
                        boolean isOnProbation = nonVotingFollowers.contains(validatorUrl);
                        
                        if (isOnProbation) {
                            // Override status for probationary validators
                            statusEmoji = "🟡";  // Yellow
                            statusLabel = " <span style='font-size: 10px; background: rgba(234,179,8,0.2); color: #fbbf24; padding: 2px 6px; border-radius: 3px;'>PROBATION</span>";
                        } else {
                            // Check if we have status for this validator
                            for (ValidatorRegistration reg : registeredValidators.values()) {
                                if (reg.validatorUrl.equals(validatorUrl)) {
                                    status = reg.status;
                                    break;
                                }
                            }
                            
                            // Set emoji and label based on status
                            switch (status) {
                                case JOINING:
                                    statusEmoji = "🟡";  // Yellow
                                    statusLabel = " <span style='font-size: 10px; background: rgba(234,179,8,0.2); color: #fbbf24; padding: 2px 6px; border-radius: 3px;'>JOINING</span>";
                                    break;
                                case SYNCING:
                                    statusEmoji = "🟠";  // Orange
                                    statusLabel = " <span style='font-size: 10px; background: rgba(249,115,22,0.2); color: #fb923c; padding: 2px 6px; border-radius: 3px;'>SYNCING</span>";
                                    break;
                                case READY:
                                    statusEmoji = "🔵";  // Blue
                                    break;
                            }
                        }
                    }
                    
                    html.append("<div style='margin-top: 4px; padding: 4px 8px; background: rgba(255,255,255,0.05); border-radius: 4px; display: flex; justify-content: space-between; align-items: center;'>");
                    html.append("<span>");
                    if (isSelf) {
                        html.append("🟢 <strong>").append(validatorName).append("</strong> (YOU)");
                    } else {
                        html.append(statusEmoji).append(" ").append(validatorName).append(statusLabel);
                    }
                    html.append("</span>");
                    html.append("</div>");
                }
                html.append("</div>");
            }
            html.append("</div>\n");
            
            // Connected Peers Card (deterministic - tracks registered Sling authors)
            int peerCount = registeredClients.size();
            html.append("<div class='card'>\n");
            html.append("<h2>🌐 Connected Peers</h2>\n");
            html.append("<div class='stat'>").append(peerCount).append("</div>\n");
            html.append("<div class='label'>Registered Sling Authors</div>\n");
            
            // Show registered client details
            if (!registeredClients.isEmpty()) {
                html.append("<div style='margin-top: 12px; font-size: 0.75em; opacity: 0.8;'>");
                html.append("<div style='margin-bottom: 4px;'>Registered Clients:</div>");
                int shown = 0;
                for (ClientRegistration reg : registeredClients.values()) {
                    if (shown >= 5) {
                        html.append("<div style='margin-top: 4px;'>... and ").append(registeredClients.size() - 5).append(" more</div>");
                        break;
                    }
                    String displayName = reg.clientId;
                    if (displayName.length() > 20) {
                        displayName = displayName.substring(0, 17) + "...";
                    }
                    html.append("<div style='margin-top: 2px;'>• ").append(escapeHtml(displayName));
                    if (reg.walletAddress != null && !reg.walletAddress.isEmpty()) {
                        html.append(" (").append(escapeHtml(reg.walletAddress.substring(0, Math.min(10, reg.walletAddress.length())))).append("...)");
                    }
                    html.append("</div>");
                    shown++;
                }
                html.append("</div>");
            }
            html.append("</div>\n");
            
            // Next Leader Election Card (only in Leader mode)
            if (leaderConsensusEngine != null) {
                int currentEpoch = leaderConsensusEngine.getCurrentEpoch();
                int leaderTermSeconds = leaderConsensusEngine.getElection().getLeaderTermSeconds();
                
                // Calculate time until next election
                long epochStartTime = (long) currentEpoch * leaderTermSeconds * 1000L; // Convert to milliseconds
                long currentTime = System.currentTimeMillis();
                long nextElectionTime = epochStartTime + (leaderTermSeconds * 1000L);
                long secondsUntilElection = (nextElectionTime - currentTime) / 1000;
                
                // Format time remaining
                String timeRemaining = "0s";
                if (secondsUntilElection > 0) {
                    long minutes = secondsUntilElection / 60;
                    long seconds = secondsUntilElection % 60;
                    if (minutes > 0) {
                        timeRemaining = minutes + "m " + seconds + "s";
                    } else {
                        timeRemaining = seconds + "s";
                    }
                }
                
                html.append("<div class='card'>\n");
                html.append("<h2>⏱️  Next Leader Election</h2>\n");
                html.append("<div class='stat' style='font-size: 2em;'>").append(timeRemaining).append("</div>\n");
                html.append("<div class='label'>").append(leaderTermSeconds).append("s Term Duration</div>\n");
                
                // Show epoch info
                html.append("<div style='margin-top: 12px; padding: 8px; background: rgba(0,0,0,0.2); border-radius: 6px; font-size: 0.8em;'>\n");
                html.append("<div style='opacity: 0.7; margin-bottom: 4px;'>Current Epoch:</div>\n");
                html.append("<div style='font-weight: 600; color: #3b82f6;'>").append(currentEpoch).append("</div>\n");
                html.append("</div>\n");
                
                // Calculate next leader (deterministic)
                int nextEpoch = currentEpoch + 1;
                int nextLeaderIndex = nextEpoch % validatorCount;
                java.util.List<String> allValidators = new java.util.ArrayList<>();
                allValidators.add(selfUrl);
                allValidators.addAll(leaderConsensusEngine.getElection().getPeerValidators());
                java.util.Collections.sort(allValidators); // Ensure deterministic ordering
                
                if (nextLeaderIndex < allValidators.size()) {
                    String nextLeaderUrl = allValidators.get(nextLeaderIndex);
                    String nextLeaderName = nextLeaderUrl.contains("validator-") 
                        ? nextLeaderUrl.substring(nextLeaderUrl.indexOf("validator-")).split(":")[0]
                        : "unknown";
                    boolean willBeMe = nextLeaderUrl.equals(selfUrl);
                    
                    html.append("<div style='margin-top: 8px; padding: 8px; background: rgba(59,130,246,0.15); border-radius: 6px; font-size: 0.75em; border-left: 3px solid #3b82f6;'>\n");
                    html.append("<div style='font-weight: 600; margin-bottom: 4px;'>Next Leader:</div>\n");
                    html.append("<div style='opacity: 0.9; line-height: 1.4;'>");
                    if (willBeMe) {
                        html.append("👑 <strong style='color: #fbbf24;'>YOU</strong> will be the next leader!");
                    } else {
                        html.append("👑 ").append(nextLeaderName);
                    }
                    html.append("</div>\n");
                    html.append("</div>\n");
                }
                
                html.append("</div>\n");
            }
            
            // Add dynamic metrics cards via JavaScript
            html.append("<div id='dynamic-metrics'></div>\n");
            
            html.append("</div>\n"); // End grid
            
            // Recent Writes
            html.append("<div class='card'>\n");
            html.append("<h2>📝 Recent Segment Writes</h2>\n");
            if (recentWrites.isEmpty()) {
                html.append("<div class='journal-entry'>No recent writes</div>\n");
            } else {
                for (String entry : recentWrites) {
                    // Extract recordId (first part before colon)
                    String recordIdShort = entry.split(":")[0];
                    if (recordIdShort.length() > 20) {
                        recordIdShort = recordIdShort.substring(0, 20);
                    }
                    
                    // Check if we have metadata for this write
                    WriteMetadata meta = recentWriteMetadata.get(recordIdShort);
                    
                    if (meta != null) {
                        // Enhanced display with validator info
                        String badge = "";
                        String badgeColor = "";
                        if ("consensus".equals(meta.source)) {
                            badge = "CONSENSUS";
                            badgeColor = "#10b981"; // green
                        } else if ("dag-local".equals(meta.source)) {
                            badge = "DAG";
                            badgeColor = "#fbbf24"; // yellow/gold
                        } else if ("leader-accepted".equals(meta.source)) {
                            badge = "CONSENSUS";
                            badgeColor = "#fbbf24"; // gold for leader writes
                        } else if ("epoch-sync".equals(meta.source)) {
                            badge = "EPOCH";
                            badgeColor = "#3b82f6"; // blue
                        }
                        
                        // Extract validator name (e.g., "validator-1" from "http://validator-1:8090")
                        String validatorName = meta.validator;
                        if (validatorName.contains("validator-")) {
                            validatorName = validatorName.substring(validatorName.indexOf("validator-"));
                            validatorName = validatorName.split(":")[0];
                        }
                        
                        html.append("<div class='journal-entry' style='border-left: 4px solid " + badgeColor + ";'>");
                        html.append("<div style='display: flex; justify-content: space-between; align-items: center;'>");
                        html.append("<code style='flex: 1;'>").append(escapeHtml(entry)).append("</code>");
                        html.append("<div style='display: flex; gap: 8px; margin-left: 12px;'>");
                        html.append("<span style='background: " + badgeColor + "; padding: 2px 8px; border-radius: 4px; font-size: 0.75em; font-weight: 600;'>");
                        html.append(badge).append("</span>");
                        html.append("<span style='background: rgba(255,255,255,0.1); padding: 2px 8px; border-radius: 4px; font-size: 0.75em;'>");
                        html.append("🗳️ ").append(validatorName).append("</span>");
                        html.append("</div></div>");
                        if (meta.message != null && !meta.message.isEmpty()) {
                            html.append("<div style='margin-top: 4px; font-size: 0.85em; opacity: 0.8;'>💬 ").append(escapeHtml(meta.message)).append("</div>");
                        }
                        html.append("</div>\n");
                    } else {
                        // Plain display (no metadata)
                        html.append("<div class='journal-entry'>").append(escapeHtml(entry)).append("</div>\n");
                    }
                }
            }
            html.append("</div>\n");
            
            // DAG Visualization (only in DAG mode)
            if (dagConsensusEngine != null) {
                html.append("<div class='card'>\n");
                html.append("<h2>🌳 Distributed DAG State</h2>\n");
                html.append("<p style='opacity: 0.8; margin: 10px 0 20px 0; font-size: 0.9em;'>").append("Git-like distributed consensus - hover over validators for merge details</p>\n");
                html.append("<div style='background: rgba(0,0,0,0.2); padding: 20px; border-radius: 8px; margin-top: 15px;'>\n");
                
                // Get DAG state
                java.util.Map<String, org.apache.jackrabbit.oak.segment.consensus.dag.DagHead> knownHeads = 
                    dagConsensusEngine.getKnownHeads();
                org.apache.jackrabbit.oak.segment.consensus.dag.DagHead myHead = dagConsensusEngine.getMyHead();
                
                // Show current state
                html.append("<div style='margin-bottom: 20px;'>\n");
                html.append("<div style='font-size: 0.9em; opacity: 0.7; margin-bottom: 10px;'>Current Network State:</div>\n");
                html.append("<div style='font-family: monospace; font-size: 0.9em;'>\n");
                
                for (java.util.Map.Entry<String, org.apache.jackrabbit.oak.segment.consensus.dag.DagHead> entry : knownHeads.entrySet()) {
                    org.apache.jackrabbit.oak.segment.consensus.dag.DagHead head = entry.getValue();
                    String validatorUrl = entry.getKey();
                    boolean isSelf = validatorUrl.equals(myHead.getValidatorUrl());
                    
                    String shortRecordId = head.getRecordId() != null && head.getRecordId().length() > 12 
                        ? head.getRecordId().substring(0, 12) + "..." 
                        : head.getRecordId();
                    
                    String style = isSelf 
                        ? "background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); padding: 10px 15px; margin: 5px 0; border-radius: 6px;" 
                        : "background: rgba(255,255,255,0.05); padding: 10px 15px; margin: 5px 0; border-radius: 6px; border-left: 3px solid #667eea;";
                    
                    html.append("<div style='").append(style).append("'>\n");
                    html.append("<div style='display: flex; justify-content: space-between; align-items: center;'>\n");
                    
                    // Validator name
                    String validatorName = validatorUrl.contains("validator") 
                        ? validatorUrl.substring(validatorUrl.indexOf("validator")) 
                        : "validator";
                    if (validatorName.contains(":")) {
                        validatorName = validatorName.split(":")[0];
                    }
                    
                    html.append("<div>\n");
                    html.append("<span style='font-weight: 600;'>").append(isSelf ? "👑 " : "🗳️  ").append(validatorName);
                    if (isSelf) html.append(" (ME)");
                    html.append("</span>\n");
                    html.append("<div style='font-size: 0.85em; opacity: 0.8; margin-top: 4px;'>HEAD: <code>").append(shortRecordId).append("</code></div>\n");
                    html.append("</div>\n");
                    
                    // Depth badge
                    html.append("<div style='text-align: right;'>\n");
                    html.append("<div style='background: rgba(0,0,0,0.3); padding: 4px 12px; border-radius: 12px; font-size: 0.85em;'>\n");
                    html.append("Depth: <span style='font-weight: 600;'>").append(head.getDepth()).append("</span>\n");
                    html.append("</div>\n");
                    
                    // Show if merge HEAD with tooltip
                    if (head.isMerge()) {
                        html.append("<div class='tooltip' style='margin-top: 4px; font-size: 0.75em; color: #fbbf24;'>");
                        html.append("🔀 MERGE");
                        html.append("<span class='tooltiptext'>");
                        html.append("<strong>Merge Commit Details</strong><br/><br/>");
                        html.append("This HEAD is a merge of multiple branches.<br/><br/>");
                        if (head.getParentIds() != null && !head.getParentIds().isEmpty()) {
                            html.append("<strong>Parents (").append(head.getParentIds().size()).append("):</strong><br/>");
                            int parentCount = 0;
                            for (String parentId : head.getParentIds()) {
                                parentCount++;
                                String shortParent = parentId != null && parentId.length() > 12 
                                    ? parentId.substring(0, 12) + "..." 
                                    : parentId;
                                html.append("• Parent ").append(parentCount).append(": <code>").append(shortParent).append("</code><br/>");
                            }
                            html.append("<br/><em>Segments from all parents have been<br/>replicated and merged into this HEAD.</em>");
                        } else {
                            html.append("<em>Merge commit (parent info pending)</em>");
                        }
                        html.append("</span>");
                        html.append("</div>\n");
                    }
                    html.append("</div>\n");
                    
                    html.append("</div>\n");
                    html.append("</div>\n");
                }
                
                html.append("</div>\n");
                html.append("</div>\n");
                
                // DAG Status Summary
                html.append("<div style='margin-top: 15px; padding: 15px; background: rgba(16, 185, 129, 0.1); border-radius: 8px; border-left: 4px solid #10b981;'>\n");
                html.append("<div style='font-size: 0.9em;'>\n");
                html.append("<div>✅ <strong>DAG Mode Active</strong></div>\n");
                html.append("<div style='margin-top: 8px; opacity: 0.8;'>• ").append(knownHeads.size()).append(" active HEADs in network</div>\n");
                html.append("<div style='opacity: 0.8;'>• Parallel non-conflicting writes proceed independently</div>\n");
                html.append("<div style='opacity: 0.8;'>• Periodic merges consolidate divergent branches</div>\n");
                html.append("</div>\n");
                html.append("</div>\n");
                
                // Mermaid DAG Visualization
                html.append("<div style='margin-top: 20px;'>\n");
                html.append("<h3 style='margin-bottom: 15px; font-size: 1.2em;'>📊 Network Topology</h3>\n");
                html.append("<div class='mermaid'>\n");
                html.append("graph TD\n");
                
                // Build Mermaid graph from knownHeads
                int nodeIndex = 0;
                java.util.Map<String, String> nodeIds = new java.util.HashMap<>();
                
                for (java.util.Map.Entry<String, org.apache.jackrabbit.oak.segment.consensus.dag.DagHead> entry : knownHeads.entrySet()) {
                    org.apache.jackrabbit.oak.segment.consensus.dag.DagHead head = entry.getValue();
                    String validatorUrl = entry.getKey();
                    
                    String validatorName = validatorUrl.contains("validator") 
                        ? validatorUrl.substring(validatorUrl.indexOf("validator")).split(":")[0]
                        : "V" + nodeIndex;
                    
                    String nodeId = "V" + nodeIndex;
                    nodeIds.put(head.getRecordId(), nodeId);
                    
                    String shortHead = head.getRecordId() != null && head.getRecordId().length() > 8 
                        ? head.getRecordId().substring(0, 8) 
                        : head.getRecordId();
                    
                    // Node definition with styling
                    String nodeStyle = head.isMerge() ? ":::mergeNode" : ":::normalNode";
                    html.append("    ").append(nodeId).append("[\"").append(validatorName).append("<br/>").append(shortHead);
                    html.append("<br/>Depth: ").append(head.getDepth());
                    if (head.isMerge()) {
                        html.append("<br/>🔀 MERGE");
                    }
                    html.append("\"]").append(nodeStyle).append("\n");
                    
                    nodeIndex++;
                }
                
                // Add connections between validators (P2P mesh)
                java.util.List<String> validatorNames = new java.util.ArrayList<>();
                for (int i = 0; i < nodeIds.size(); i++) {
                    validatorNames.add("V" + i);
                }
                for (int i = 0; i < validatorNames.size(); i++) {
                    for (int j = i + 1; j < validatorNames.size(); j++) {
                        html.append("    ").append(validatorNames.get(i)).append(" -.->|\"P2P sync\"| ").append(validatorNames.get(j)).append("\n");
                    }
                }
                
                // Styling for nodes
                html.append("    classDef normalNode fill:#667eea,stroke:#fff,stroke-width:2px,color:#fff\n");
                html.append("    classDef mergeNode fill:#fbbf24,stroke:#fff,stroke-width:3px,color:#000\n");
                
                html.append("</div>\n");
                html.append("<p style='font-size: 0.85em; opacity: 0.7; margin-top: 10px; text-align: center;'>").append("Dotted lines show peer-to-peer connections | Yellow nodes are merge commits</p>\n");
                html.append("</div>\n");
                
                html.append("</div>\n");
                html.append("</div>\n");
            }
            
            // Explorer Link
            html.append("<div class='card' style='text-align: center; padding: 40px;'>\n");
            html.append("<h2>🔍 Content Explorer</h2>\n");
            html.append("<p style='margin: 20px 0; opacity: 0.9;'>Browse the global repository content tree and inspect segments</p>\n");
            html.append("<a href='/explorer' style='display: inline-block; padding: 15px 40px; background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); ");
            html.append("color: white; text-decoration: none; border-radius: 8px; font-weight: 600; font-size: 1.1em; ");
            html.append("box-shadow: 0 4px 12px rgba(102, 126, 234, 0.4); transition: transform 0.2s;' ");
            html.append("onmouseover='this.style.transform=\"scale(1.05)\"' onmouseout='this.style.transform=\"scale(1)\"'>");
            html.append("Launch Explorer →</a>\n");
            html.append("</div>\n");
            
            // API Endpoints - Comprehensive List
            html.append("<div class='card'>\n");
            html.append("<h2>🔌 API Endpoints</h2>\n");
            
            // Interactive API Browser Link
            html.append("<div style='margin-bottom: 16px; padding: 12px; background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); border-radius: 8px;'>\n");
            html.append("<a href='/api-browser' style='color: white; text-decoration: none; font-weight: 600; display: flex; align-items: center; gap: 8px;'>\n");
            html.append("🧪 Interactive API Browser →</a>\n");
            html.append("</div>\n");
            
            html.append("<div class='endpoints'>\n");
            
            // Explorer APIs
            html.append("<div style='margin-top: 12px; font-weight: 600; color: #3b82f6;'>📊 Explorer APIs</div>\n");
            html.append("<div class='endpoint'><code>GET /explorer</code> - Blockchain content explorer UI</div>\n");
            html.append("<div class='endpoint'><code>GET /api/explore?path={path}</code> - Browse node tree (JSON)</div>\n");
            html.append("<div class='endpoint'><code>GET /api/segments/tars</code> - TAR files and storage (JSON)</div>\n");
            html.append("<div class='endpoint'><code>GET /api/segments/recent</code> - Recent segment writes (JSON)</div>\n");
            
            // Health & Monitoring
            html.append("<div style='margin-top: 12px; font-weight: 600; color: #10b981;'>💚 Health & Monitoring</div>\n");
            html.append("<div class='endpoint'><code>GET /health</code> - Basic health check (JSON)</div>\n");
            html.append("<div class='endpoint'><code>GET /health/deep</code> - Comprehensive health validation (JSON)</div>\n");
            html.append("<div class='endpoint'><code>GET /api/metrics</code> - Consensus & replication metrics (JSON)</div>\n");
            html.append("<div class='endpoint'><code>GET /metrics</code> - Prometheus metrics (text)</div>\n");
            
            // Consensus APIs
            html.append("<div style='margin-top: 12px; font-weight: 600; color: #f59e0b;'>🔄 Consensus APIs</div>\n");
            html.append("<div class='endpoint'><code>POST /v1/propose</code> - Propose write to consensus</div>\n");
            html.append("<div class='endpoint'><code>POST /v1/vote</code> - Submit vote for proposal</div>\n");
            html.append("<div class='endpoint'><code>POST /v1/test-write</code> - Test write with consensus</div>\n");
            html.append("<div class='endpoint'><code>GET /v1/head</code> - Current HEAD record ID (text)</div>\n");
            
            // Registration & Discovery
            html.append("<div style='margin-top: 12px; font-weight: 600; color: #8b5cf6;'>🌐 Registration & Discovery</div>\n");
            html.append("<div class='endpoint'><code>POST /v1/register-client</code> - Register Sling author</div>\n");
            html.append("<div class='endpoint'><code>POST /v1/register-validator</code> - Register validator node</div>\n");
            html.append("<div class='endpoint'><code>GET /v1/peers</code> - List all known validators (JSON)</div>\n");
            html.append("<div class='endpoint'><code>GET /v1/ngrok-url</code> - Get public ngrok URL (text)</div>\n");
            
            // Leader-Based Consensus
            html.append("<div style='margin-top: 12px; font-weight: 600; color: #ec4899;'>👑 Leader Consensus</div>\n");
            html.append("<div class='endpoint'><code>POST /v1/follower/head-update</code> - Leader broadcasts HEAD</div>\n");
            html.append("<div class='endpoint'><code>POST /v1/heartbeat</code> - Leader heartbeat</div>\n");
            
            // DAG Consensus
            html.append("<div style='margin-top: 12px; font-weight: 600; color: #06b6d4;'>🕸️  DAG Consensus</div>\n");
            html.append("<div class='endpoint'><code>POST /v1/dag/head</code> - Receive HEAD update from peer</div>\n");
            
            // Oak Files
            html.append("<div style='margin-top: 12px; font-weight: 600; color: #6b7280;'>📄 Oak Files</div>\n");
            html.append("<div class='endpoint'><code>GET /journal.log</code> - Journal file (text)</div>\n");
            html.append("<div class='endpoint'><code>GET /manifest</code> - Manifest file (text)</div>\n");
            html.append("<div class='endpoint'><code>GET /gc.log</code> - Garbage collection log (text)</div>\n");
            html.append("<div class='endpoint'><code>GET /segments/{id}</code> - Fetch segment by ID (binary)</div>\n");
            html.append("<div class='endpoint'><code>HEAD /segments/{id}</code> - Check segment existence</div>\n");
            
            html.append("</div>\n");
            html.append("</div>\n");
            
            html.append("</div>\n"); // End container
            
            // Add JavaScript for dynamic metrics
            html.append("<script>\n");
            html.append("async function loadMetrics() {\n");
            html.append("  try {\n");
            html.append("    const response = await fetch('/api/metrics');\n");
            html.append("    const data = await response.json();\n");
            html.append("    \n");
            html.append("    let html = '';\n");
            html.append("    \n");
            html.append("    // Consensus Performance Card\n");
            html.append("    if (data.consensus) {\n");
            html.append("      html += '<div class=\"card\">';\n");
            html.append("      html += '<h2>🎯 Consensus Performance</h2>';\n");
            html.append("      html += '<div class=\"stat\">' + data.consensus.successRate.toFixed(1) + '%</div>';\n");
            html.append("      html += '<div class=\"label\">Success Rate (' + data.consensus.successfulProposals + '/' + data.consensus.totalProposals + ')</div>';\n");
            html.append("      html += '<div style=\"margin-top: 10px; font-size: 0.9em; opacity: 0.8;\">⏱️  Avg Consensus: ' + data.consensus.averageConsensusTimeMs + 'ms</div>';\n");
            html.append("      html += '</div>';\n");
            html.append("    }\n");
            html.append("    \n");
            html.append("    // Replication Metrics Card\n");
            html.append("    if (data.replication) {\n");
            html.append("      html += '<div class=\"card\">';\n");
            html.append("      html += '<h2>🔄 Replication</h2>';\n");
            html.append("      html += '<div class=\"stat\">' + data.replication.totalSegments + '</div>';\n");
            html.append("      html += '<div class=\"label\">Segments Replicated (' + data.replication.totalMb + ' MB)</div>';\n");
            html.append("      html += '</div>';\n");
            html.append("    }\n");
            html.append("    \n");
            html.append("    // Validator Identity Card\n");
            html.append("    if (data.validator) {\n");
            html.append("      html += '<div class=\"card\">';\n");
            html.append("      html += '<h2>🪪 Validator Identity</h2>';\n");
            html.append("      html += '<div style=\"font-family: monospace; font-size: 0.85em; word-break: break-all; margin: 10px 0;\">' + data.validator.url + '</div>';\n");
            html.append("      html += '<div class=\"label\">My Address</div>';\n");
            html.append("      html += '</div>';\n");
            html.append("    }\n");
            html.append("    \n");
            html.append("    document.getElementById('dynamic-metrics').innerHTML = html;\n");
            html.append("  } catch (e) {\n");
            html.append("    console.error('Failed to load metrics:', e);\n");
            html.append("  }\n");
            html.append("}\n");
            html.append("\n");
            html.append("// Load metrics on page load and refresh every 5 seconds\n");
            html.append("loadMetrics();\n");
            html.append("setInterval(loadMetrics, 5000);\n");
            html.append("</script>\n");
            
            html.append("</body>\n</html>");
            
            response.getWriter().write(html.toString());
        }
        
        /**
         * Format bytes to human-readable string.
         */
        private String formatBytes(long bytes) {
            if (bytes < 1024) return bytes + " B";
            if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
            if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
            return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
        }
        
        /**
         * Escape HTML to prevent XSS.
         */
        private String escapeHtml(String text) {
            return text.replace("&", "&amp;")
                       .replace("<", "&lt;")
                       .replace(">", "&gt;")
                       .replace("\"", "&quot;")
                       .replace("'", "&#39;");
        }
        
        /**
         * Handle health check endpoint.
         */
        private void handleHealth(HttpServletResponse response) throws IOException {
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType("application/json");
            response.getWriter().write("{\"status\":\"UP\",\"store\":\"" + storeDirectory + "\"}");
        }
        
        /**
         * Handle comprehensive health check - validates all system components.
         */
        private void handleDeepHealth(HttpServletResponse response) throws IOException {
            response.setContentType("application/json");
            
            StringBuilder json = new StringBuilder();
            json.append("{\n");
            
            boolean allHealthy = true;
            
            // 1. Check FileStore health
            json.append("  \"fileStore\": {\n");
            try {
                if (fileStore != null) {
                    String headId = fileStore.getHead().getRecordId().toString10();
                    json.append("    \"status\": \"UP\",\n");
                    json.append("    \"head\": \"").append(headId.substring(0, Math.min(16, headId.length()))).append("...\"\n");
                } else {
                    json.append("    \"status\": \"DOWN\",\n");
                    json.append("    \"error\": \"FileStore not initialized\"\n");
                    allHealthy = false;
                }
            } catch (Exception e) {
                json.append("    \"status\": \"DOWN\",\n");
                json.append("    \"error\": \"").append(e.getMessage()).append("\"\n");
                allHealthy = false;
            }
            json.append("  },\n");
            
            // 2. Check NodeStore health
            json.append("  \"nodeStore\": {\n");
            try {
                if (nodeStore != null) {
                    org.apache.jackrabbit.oak.spi.state.NodeState root = nodeStore.getRoot();
                    json.append("    \"status\": \"UP\",\n");
                    json.append("    \"rootExists\": ").append(root != null).append("\n");
                } else {
                    json.append("    \"status\": \"DOWN\",\n");
                    json.append("    \"error\": \"NodeStore not initialized\"\n");
                    allHealthy = false;
                }
            } catch (Exception e) {
                json.append("    \"status\": \"DOWN\",\n");
                json.append("    \"error\": \"").append(e.getMessage()).append("\"\n");
                allHealthy = false;
            }
            json.append("  },\n");
            
            // 3. Check disk space
            json.append("  \"diskSpace\": {\n");
            try {
                java.nio.file.FileStore fs = Files.getFileStore(storeDirectory);
                long totalSpace = fs.getTotalSpace();
                long usableSpace = fs.getUsableSpace();
                double usagePercent = ((totalSpace - usableSpace) * 100.0) / totalSpace;
                
                boolean diskHealthy = usagePercent < 90.0;  // Alert if > 90% full
                json.append("    \"status\": \"").append(diskHealthy ? "UP" : "WARN").append("\",\n");
                json.append("    \"totalGb\": ").append(String.format("%.2f", totalSpace / (1024.0 * 1024.0 * 1024.0))).append(",\n");
                json.append("    \"usableGb\": ").append(String.format("%.2f", usableSpace / (1024.0 * 1024.0 * 1024.0))).append(",\n");
                json.append("    \"usagePercent\": ").append(String.format("%.1f", usagePercent)).append("\n");
                
                if (!diskHealthy) {
                    allHealthy = false;
                }
            } catch (Exception e) {
                json.append("    \"status\": \"DOWN\",\n");
                json.append("    \"error\": \"").append(e.getMessage()).append("\"\n");
                allHealthy = false;
            }
            json.append("  },\n");
            
            // 4. Check consensus engine (if configured)
            if (leaderConsensusEngine != null) {
                json.append("  \"consensus\": {\n");
                try {
                    json.append("    \"status\": \"UP\",\n");
                    json.append("    \"mode\": \"leader\",\n");
                    json.append("    \"role\": \"").append(leaderConsensusEngine.getCurrentRole()).append("\",\n");
                    json.append("    \"isLeader\": ").append(leaderConsensusEngine.isLeader()).append(",\n");
                    json.append("    \"epoch\": ").append(leaderConsensusEngine.getCurrentEpoch()).append(",\n");
                    json.append("    \"reachableValidators\": ").append(leaderConsensusEngine.getReachableValidatorCount()).append("\n");
                } catch (Exception e) {
                    json.append("    \"status\": \"DOWN\",\n");
                    json.append("    \"error\": \"").append(e.getMessage()).append("\"\n");
                    allHealthy = false;
                }
                json.append("  },\n");
            } else if (dagConsensusEngine != null) {
                json.append("  \"consensus\": {\n");
                json.append("    \"status\": \"UP\",\n");
                json.append("    \"mode\": \"dag\",\n");
                json.append("    \"chainHeight\": ").append(dagConsensusEngine.getChainHeight()).append(",\n");
                json.append("    \"pendingTransactions\": ").append(dagConsensusEngine.getPendingTransactionCount()).append("\n");
                json.append("  },\n");
            } else if (consensusEngine != null) {
                json.append("  \"consensus\": {\n");
                json.append("    \"status\": \"UP\",\n");
                json.append("    \"mode\": \"blockchain\",\n");
                json.append("    \"totalProposals\": ").append(consensusEngine.getTotalProposals()).append(",\n");
                json.append("    \"successfulProposals\": ").append(consensusEngine.getSuccessfulProposals()).append("\n");
                json.append("  },\n");
            }
            
            // 5. Check connected clients
            json.append("  \"clients\": {\n");
            json.append("    \"status\": \"UP\",\n");
            json.append("    \"registeredClients\": ").append(registeredClients.size()).append(",\n");
            json.append("    \"registeredValidators\": ").append(registeredValidators.size()).append("\n");
            json.append("  },\n");
            
            // 6. Overall health
            json.append("  \"overall\": {\n");
            json.append("    \"status\": \"").append(allHealthy ? "UP" : "DEGRADED").append("\",\n");
            json.append("    \"timestamp\": \"").append(new java.util.Date()).append("\"\n");
            json.append("  }\n");
            
            json.append("}\n");
            
            // Return 200 if healthy, 503 if degraded
            response.setStatus(allHealthy ? HttpServletResponse.SC_OK : HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            response.getWriter().write(json.toString());
        }
        
        /**
         * Handle serving a file from the segment store directory.
         */
        private void handleFile(HttpServletRequest request, HttpServletResponse response, String filename, String contentType) throws IOException {
            // Log requesting peer info
            String remoteAddr = request.getRemoteAddr();
            int remotePort = request.getRemotePort();
            
            Path filePath = storeDirectory.resolve(filename);
            
            if (!Files.exists(filePath)) {
                log.warn("File not found: {}", filePath);
                response.sendError(HttpServletResponse.SC_NOT_FOUND, "File not found: " + filename);
                return;
            }
            
            long fileSize = Files.size(filePath);
            
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType(contentType);
            response.setContentLengthLong(fileSize);
            
            try (InputStream in = Files.newInputStream(filePath);
                 OutputStream out = response.getOutputStream()) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }
            }
            
            log.info("📄 File GET: {} FROM {}:{} ({} bytes)", filename, remoteAddr, remotePort, fileSize);
        }
        
        /**
         * Handle blockchain explorer UI - Etherscan-like interface.
         */
        private void handleExplorerUI(HttpServletResponse response) throws IOException {
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType("text/html; charset=UTF-8");
            
            StringBuilder html = new StringBuilder();
            html.append("<!DOCTYPE html>\n<html>\n<head>\n");
            html.append("<meta charset='UTF-8'>\n");
            html.append("<title>🔗 Oak Segment Consensus Explorer</title>\n");
            html.append("<style>\n");
            html.append("* { margin: 0; padding: 0; box-sizing: border-box; }\n");
            html.append("body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; ");
            html.append("background: #0f172a; color: #e2e8f0; min-height: 100vh; }\n");
            html.append(".header { background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); padding: 20px; ");
            html.append("box-shadow: 0 4px 6px rgba(0,0,0,0.3); }\n");
            html.append(".header h1 { font-size: 2em; margin-bottom: 5px; }\n");
            html.append(".container { max-width: 1400px; margin: 0 auto; padding: 20px; }\n");
            html.append(".panel { background: #1e293b; border-radius: 10px; padding: 20px; margin: 20px 0; ");
            html.append("border: 1px solid #334155; }\n");
            html.append(".panel h2 { color: #a78bfa; margin-bottom: 15px; font-size: 1.3em; }\n");
            html.append(".tree-node { padding: 8px; margin: 4px 0; background: #0f172a; border-radius: 5px; ");
            html.append("cursor: pointer; transition: all 0.2s; }\n");
            html.append(".tree-node:hover { background: #1e293b; transform: translateX(5px); }\n");
            html.append(".node-name { color: #60a5fa; font-weight: 500; }\n");
            html.append(".node-type { color: #94a3b8; font-size: 0.9em; margin-left: 10px; }\n");
            html.append(".property { padding: 5px; margin: 3px 0; font-family: monospace; font-size: 0.9em; }\n");
            html.append(".prop-name { color: #fbbf24; }\n");
            html.append(".prop-value { color: #34d399; }\n");
            html.append(".breadcrumb { padding: 10px; background: #0f172a; border-radius: 5px; margin-bottom: 15px; }\n");
            html.append(".breadcrumb a { color: #60a5fa; text-decoration: none; margin: 0 5px; }\n");
            html.append(".breadcrumb a:hover { text-decoration: underline; }\n");
            html.append(".segment-entry { background: #0f172a; padding: 12px; margin: 8px 0; border-radius: 5px; ");
            html.append("border-left: 3px solid #8b5cf6; }\n");
            html.append(".segment-id { font-family: monospace; color: #60a5fa; }\n");
            html.append(".timestamp { color: #94a3b8; font-size: 0.9em; }\n");
            html.append(".tabs { display: flex; gap: 10px; margin-bottom: 20px; }\n");
            html.append(".tab { padding: 10px 20px; background: #1e293b; border-radius: 5px; cursor: pointer; ");
            html.append("border: 2px solid transparent; }\n");
            html.append(".tab.active { border-color: #8b5cf6; background: #2d3748; }\n");
            html.append(".loading { text-align: center; padding: 40px; color: #94a3b8; }\n");
            html.append("</style>\n");
            html.append("<script>\n");
            html.append("let currentPath = '/';\n\n");
            html.append("async function loadNode(path) {\n");
            html.append("  currentPath = path;\n");
            html.append("  document.getElementById('loading').style.display = 'block';\n");
            html.append("  document.getElementById('node-content').style.display = 'none';\n");
            html.append("  const response = await fetch('/api/explore?path=' + encodeURIComponent(path));\n");
            html.append("  const data = await response.json();\n");
            html.append("  displayNode(data);\n");
            html.append("  document.getElementById('loading').style.display = 'none';\n");
            html.append("  document.getElementById('node-content').style.display = 'block';\n");
            html.append("}\n\n");
            html.append("function displayNode(node) {\n");
            html.append("  const breadcrumb = document.getElementById('breadcrumb');\n");
            html.append("  const parts = currentPath.split('/').filter(p => p);\n");
            html.append("  let path = '';\n");
            html.append("  breadcrumb.innerHTML = '<a href=\"#\" onclick=\"loadNode(\\'/\\'); return false;\">root</a>';\n");
            html.append("  parts.forEach(part => {\n");
            html.append("    path += '/' + part;\n");
            html.append("    breadcrumb.innerHTML += ' / <a href=\"#\" onclick=\"loadNode(\\'' + path + '\\'); return false;\">' + part + '</a>';\n");
            html.append("  });\n\n");
            html.append("  const children = document.getElementById('children');\n");
            html.append("  children.innerHTML = '';\n");
            html.append("  node.children.forEach(child => {\n");
            html.append("    const div = document.createElement('div');\n");
            html.append("    div.className = 'tree-node';\n");
            html.append("    div.innerHTML = '<span class=\"node-name\">📁 ' + child + '</span>';\n");
            html.append("    div.onclick = () => loadNode(currentPath === '/' ? '/' + child : currentPath + '/' + child);\n");
            html.append("    children.appendChild(div);\n");
            html.append("  });\n\n");
            html.append("  const props = document.getElementById('properties');\n");
            html.append("  props.innerHTML = '';\n");
            html.append("  Object.entries(node.properties).forEach(([key, value]) => {\n");
            html.append("    const div = document.createElement('div');\n");
            html.append("    div.className = 'property';\n");
            html.append("    div.innerHTML = '<span class=\"prop-name\">' + key + ':</span> <span class=\"prop-value\">' + JSON.stringify(value) + '</span>';\n");
            html.append("    props.appendChild(div);\n");
            html.append("  });\n");
            html.append("}\n\n");
            html.append("async function loadTarFiles() {\n");
            html.append("  const response = await fetch('/api/segments/tars');\n");
            html.append("  const tars = await response.json();\n");
            html.append("  const container = document.getElementById('tar-files');\n");
            html.append("  container.innerHTML = '';\n");
            html.append("  if (tars.length === 0) {\n");
            html.append("    container.innerHTML = '<div style=\"color: #94a3b8; padding: 10px;\">No TAR files found</div>';\n");
            html.append("    return;\n");
            html.append("  }\n");
            html.append("  tars.forEach(tar => {\n");
            html.append("    const div = document.createElement('div');\n");
            html.append("    div.className = 'segment-entry';\n");
            html.append("    div.style.borderLeft = '3px solid #06b6d4';\n");
            html.append("    const segmentLabel = tar.estimatedCount ? tar.segmentCount + ' (est.)' : tar.segmentCount;\n");
            html.append("    div.innerHTML = '<div style=\"display: flex; justify-content: space-between; align-items: center;\">' +\n");
            html.append("      '<div>' +\n");
            html.append("        '<div class=\"segment-id\" style=\"margin-bottom: 5px;\">💾 ' + tar.name + '</div>' +\n");
            html.append("        '<div class=\"timestamp\">Size: ' + tar.sizeFormatted + ' • Segments: ' + segmentLabel + '</div>' +\n");
            html.append("      '</div>' +\n");
            html.append("      '<div style=\"text-align: right; font-size: 0.85em; color: #94a3b8;\">' +\n");
            html.append("        '<div>Created: ' + new Date(tar.created).toLocaleString() + '</div>' +\n");
            html.append("        '<div>Modified: ' + new Date(tar.modified).toLocaleString() + '</div>' +\n");
            html.append("      '</div>' +\n");
            html.append("    '</div>';\n");
            html.append("    container.appendChild(div);\n");
            html.append("  });\n");
            html.append("}\n\n");
            html.append("async function loadRecentSegments() {\n");
            html.append("  const response = await fetch('/api/segments/recent');\n");
            html.append("  const segments = await response.json();\n");
            html.append("  const container = document.getElementById('recent-segments');\n");
            html.append("  container.innerHTML = '';\n");
            html.append("  if (segments.length === 0) {\n");
            html.append("    container.innerHTML = '<div style=\"color: #94a3b8; padding: 10px;\">No recent segments</div>';\n");
            html.append("    return;\n");
            html.append("  }\n");
            html.append("  segments.forEach(seg => {\n");
            html.append("    const div = document.createElement('div');\n");
            html.append("    div.className = 'segment-entry';\n");
            html.append("    div.innerHTML = '<div class=\"segment-id\">Segment: ' + seg.id + '</div>' +\n");
            html.append("                    '<div class=\"timestamp\">' + seg.timestamp + '</div>';\n");
            html.append("    container.appendChild(div);\n");
            html.append("  });\n");
            html.append("}\n\n");
            html.append("window.onload = () => { loadNode('/'); loadTarFiles(); loadRecentSegments(); setInterval(loadRecentSegments, 5000); };\n");
            html.append("</script>\n");
            html.append("</head>\n<body>\n");
            html.append("<div class='header'>\n");
            html.append("<div class='container'><h1>🔗 Oak Segment Consensus Explorer</h1>\n");
            html.append("<div>Content Browser & Segment Inspector</div></div>\n");
            html.append("</div>\n");
            html.append("<div class='container'>\n");
            html.append("<div class='panel'>\n");
            html.append("<h2>🌳 Content Tree</h2>\n");
            html.append("<div class='breadcrumb' id='breadcrumb'>/</div>\n");
            html.append("<div id='loading' class='loading' style='display:none'>Loading...</div>\n");
            html.append("<div id='node-content'>\n");
            html.append("<div id='children'></div>\n");
            html.append("<h3 style='margin-top: 20px; color: #a78bfa;'>Properties</h3>\n");
            html.append("<div id='properties'></div>\n");
            html.append("</div></div>\n");
            html.append("<div class='panel'>\n");
            html.append("<h2>💾 TAR Files (Segment Storage Blocks)</h2>\n");
            html.append("<div id='tar-files'></div>\n");
            html.append("</div>\n");
            html.append("<div class='panel'>\n");
            html.append("<h2>📦 Recent Segments (Journal)</h2>\n");
            html.append("<div id='recent-segments'></div>\n");
            html.append("</div>\n");
            html.append("</div>\n</body>\n</html>");
            
            response.getWriter().write(html.toString());
        }
        
        /**
         * Handle interactive API Browser UI (HAL-style explorer).
         */
        private void handleApiBrowserUI(HttpServletResponse response) throws IOException {
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType("text/html; charset=UTF-8");
            
            StringBuilder html = new StringBuilder();
            html.append("<!DOCTYPE html>\n<html>\n<head>\n");
            html.append("<meta charset='UTF-8'>\n");
            html.append("<title>🧪 Oak Consensus API Browser</title>\n");
            html.append("<style>\n");
            html.append("* { margin: 0; padding: 0; box-sizing: border-box; }\n");
            html.append("body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; ");
            html.append("background: #0f172a; color: #e2e8f0; min-height: 100vh; }\n");
            html.append(".header { background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); padding: 20px; ");
            html.append("box-shadow: 0 4px 6px rgba(0,0,0,0.3); }\n");
            html.append(".header h1 { font-size: 2em; margin-bottom: 5px; }\n");
            html.append(".header p { opacity: 0.9; }\n");
            html.append(".container { max-width: 1600px; margin: 0 auto; padding: 20px; }\n");
            html.append(".api-categories { display: grid; grid-template-columns: repeat(auto-fit, minmax(300px, 1fr)); gap: 20px; margin: 20px 0; }\n");
            html.append(".category { background: #1e293b; border-radius: 10px; padding: 20px; border: 1px solid #334155; }\n");
            html.append(".category h2 { color: #a78bfa; margin-bottom: 15px; font-size: 1.2em; }\n");
            html.append(".endpoint { background: #0f172a; padding: 12px; margin: 8px 0; border-radius: 6px; ");
            html.append("cursor: pointer; border-left: 3px solid #8b5cf6; transition: all 0.2s; }\n");
            html.append(".endpoint:hover { background: #1e293b; transform: translateX(5px); }\n");
            html.append(".method { display: inline-block; padding: 3px 8px; border-radius: 4px; font-weight: 600; ");
            html.append("font-size: 0.75em; margin-right: 8px; }\n");
            html.append(".method-GET { background: #10b981; color: white; }\n");
            html.append(".method-POST { background: #3b82f6; color: white; }\n");
            html.append(".method-PUT { background: #f59e0b; color: white; }\n");
            html.append(".method-DELETE { background: #ef4444; color: white; }\n");
            html.append(".method-HEAD { background: #6b7280; color: white; }\n");
            html.append(".endpoint-path { font-family: monospace; color: #60a5fa; font-size: 0.9em; }\n");
            html.append(".endpoint-desc { color: #94a3b8; font-size: 0.85em; margin-top: 5px; }\n");
            html.append(".test-panel { background: #1e293b; border-radius: 10px; padding: 20px; margin: 20px 0; ");
            html.append("border: 1px solid #334155; display: none; }\n");
            html.append(".test-panel h3 { color: #a78bfa; margin-bottom: 15px; }\n");
            html.append(".form-group { margin: 15px 0; }\n");
            html.append(".form-group label { display: block; margin-bottom: 5px; color: #94a3b8; font-size: 0.9em; }\n");
            html.append("input, textarea { width: 100%; padding: 10px; background: #0f172a; border: 1px solid #334155; ");
            html.append("border-radius: 5px; color: #e2e8f0; font-family: monospace; }\n");
            html.append("textarea { min-height: 100px; font-size: 0.9em; }\n");
            html.append("button { padding: 10px 20px; background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); ");
            html.append("color: white; border: none; border-radius: 5px; cursor: pointer; font-weight: 600; }\n");
            html.append("button:hover { transform: scale(1.05); }\n");
            html.append(".response { background: #0f172a; border-radius: 5px; padding: 15px; margin: 15px 0; ");
            html.append("border-left: 3px solid #10b981; }\n");
            html.append(".response-header { color: #94a3b8; font-size: 0.85em; margin-bottom: 10px; }\n");
            html.append(".response-body { font-family: monospace; font-size: 0.85em; white-space: pre-wrap; ");
            html.append("word-wrap: break-word; color: #34d399; }\n");
            html.append(".back-link { display: inline-block; margin-bottom: 20px; color: #60a5fa; text-decoration: none; }\n");
            html.append(".back-link:hover { text-decoration: underline; }\n");
            html.append("</style>\n");
            html.append("</head>\n");
            html.append("<body>\n");
            
            html.append("<div class='header'>\n");
            html.append("<h1>🧪 Interactive API Browser</h1>\n");
            html.append("<p>Explore and test all Oak Segment Consensus APIs</p>\n");
            html.append("</div>\n");
            
            html.append("<div class='container'>\n");
            html.append("<a href='/' class='back-link'>← Back to Dashboard</a>\n");
            
            // Test Panel (hidden by default)
            html.append("<div id='test-panel' class='test-panel'>\n");
            html.append("<h3 id='test-title'></h3>\n");
            html.append("<div id='test-form'></div>\n");
            html.append("<div id='response-container'></div>\n");
            html.append("</div>\n");
            
            html.append("<div class='api-categories'>\n");
            
            // Explorer APIs
            html.append("<div class='category'>\n");
            html.append("<h2>📊 Explorer APIs</h2>\n");
            addApiEndpoint(html, "GET", "/explorer", "Blockchain content explorer UI", "explorer");
            addApiEndpoint(html, "GET", "/api/explore?path=/", "Browse node tree structure (JSON)", "explore");
            addApiEndpoint(html, "GET", "/api/segments/tars", "List all TAR files and storage blocks (JSON)", "tars");
            addApiEndpoint(html, "GET", "/api/segments/recent", "Recent segment writes from journal (JSON)", "recent");
            html.append("</div>\n");
            
            // Health & Monitoring
            html.append("<div class='category'>\n");
            html.append("<h2>💚 Health & Monitoring</h2>\n");
            addApiEndpoint(html, "GET", "/health", "Basic health check (JSON)", "health");
            addApiEndpoint(html, "GET", "/health/deep", "Comprehensive health validation (JSON)", "health_deep");
            addApiEndpoint(html, "GET", "/api/metrics", "Consensus & replication metrics (JSON)", "metrics");
            addApiEndpoint(html, "GET", "/metrics", "Prometheus metrics (text)", "prometheus");
            html.append("</div>\n");
            
            // Consensus APIs
            html.append("<div class='category'>\n");
            html.append("<h2>🔄 Consensus APIs</h2>\n");
            addApiEndpoint(html, "POST", "/v1/propose", "Propose write to consensus network", "propose");
            addApiEndpoint(html, "POST", "/v1/vote", "Submit vote for a proposal", "vote");
            addApiEndpoint(html, "POST", "/v1/test-write", "Test write with consensus (demo)", "test_write");
            addApiEndpoint(html, "GET", "/v1/head", "Get current HEAD record ID (text)", "head");
            html.append("</div>\n");
            
            // Registration & Discovery
            html.append("<div class='category'>\n");
            html.append("<h2>🌐 Registration & Discovery</h2>\n");
            addApiEndpoint(html, "POST", "/v1/register-client", "Register a Sling author client", "register_client");
            addApiEndpoint(html, "POST", "/v1/register-validator", "Register a validator node", "register_validator");
            addApiEndpoint(html, "GET", "/v1/peers", "List all known validators (JSON)", "peers");
            addApiEndpoint(html, "GET", "/v1/ngrok-url", "Get public ngrok URL (text)", "ngrok");
            html.append("</div>\n");
            
            // Leader Consensus
            html.append("<div class='category'>\n");
            html.append("<h2>👑 Leader Consensus</h2>\n");
            addApiEndpoint(html, "POST", "/v1/follower/head-update", "Leader broadcasts HEAD to follower", "follower_update");
            addApiEndpoint(html, "POST", "/v1/heartbeat", "Leader heartbeat signal", "heartbeat");
            html.append("</div>\n");
            
            // DAG Consensus
            html.append("<div class='category'>\n");
            html.append("<h2>🕸️ DAG Consensus</h2>\n");
            addApiEndpoint(html, "POST", "/v1/dag/head", "Receive HEAD update from peer (git-style)", "dag_head");
            html.append("</div>\n");
            
            // Oak Files
            html.append("<div class='category'>\n");
            html.append("<h2>📄 Oak Files</h2>\n");
            addApiEndpoint(html, "GET", "/journal.log", "Journal file (text)", "journal");
            addApiEndpoint(html, "GET", "/manifest", "Manifest file (text)", "manifest");
            addApiEndpoint(html, "GET", "/gc.log", "Garbage collection log (text)", "gc");
            addApiEndpoint(html, "GET", "/segments/{id}", "Fetch segment by ID (binary)", "segment_get");
            addApiEndpoint(html, "HEAD", "/segments/{id}", "Check segment existence", "segment_head");
            html.append("</div>\n");
            
            html.append("</div>\n"); // End api-categories
            
            // JavaScript for interactive testing
            html.append("<script>\n");
            html.append("function testEndpoint(method, path, id) {\n");
            html.append("  const panel = document.getElementById('test-panel');\n");
            html.append("  const title = document.getElementById('test-title');\n");
            html.append("  const form = document.getElementById('test-form');\n");
            html.append("  const responseContainer = document.getElementById('response-container');\n");
            html.append("  \n");
            html.append("  panel.style.display = 'block';\n");
            html.append("  title.textContent = method + ' ' + path;\n");
            html.append("  responseContainer.innerHTML = '';\n");
            html.append("  \n");
            html.append("  let formHtml = '';\n");
            html.append("  \n");
            html.append("  if (path.includes('{')) {\n");
            html.append("    formHtml += '<div class=\"form-group\">';\n");
            html.append("    formHtml += '<label>Path Parameters:</label>';\n");
            html.append("    formHtml += '<input type=\"text\" id=\"path-params\" placeholder=\"e.g., segment ID\" />';\n");
            html.append("    formHtml += '</div>';\n");
            html.append("  }\n");
            html.append("  \n");
            html.append("  if (path.includes('?')) {\n");
            html.append("    formHtml += '<div class=\"form-group\">';\n");
            html.append("    formHtml += '<label>Query Parameters:</label>';\n");
            html.append("    formHtml += '<input type=\"text\" id=\"query-params\" placeholder=\"e.g., path=/oak-chain\" value=\"' + (path.split('?')[1] || '') + '\" />';\n");
            html.append("    formHtml += '</div>';\n");
            html.append("  }\n");
            html.append("  \n");
            html.append("  if (method === 'POST' || method === 'PUT') {\n");
            html.append("    formHtml += '<div class=\"form-group\">';\n");
            html.append("    formHtml += '<label>Request Body (JSON):</label>';\n");
            html.append("    formHtml += '<textarea id=\"request-body\">' + getExampleBody(id) + '</textarea>';\n");
            html.append("    formHtml += '</div>';\n");
            html.append("  }\n");
            html.append("  \n");
            html.append("  formHtml += '<button onclick=\"sendRequest(\\'' + method + '\\', \\'' + path + '\\')\">Send Request</button>';\n");
            html.append("  form.innerHTML = formHtml;\n");
            html.append("  \n");
            html.append("  panel.scrollIntoView({ behavior: 'smooth' });\n");
            html.append("}\n");
            html.append("\n");
            html.append("function getExampleBody(id) {\n");
            html.append("  const examples = {\n");
            html.append("    'propose': JSON.stringify({proposalId: 'proposal-' + Date.now(), data: 'test content'}, null, 2),\n");
            html.append("    'vote': JSON.stringify({proposalId: 'proposal-123', vote: 'ACCEPT'}, null, 2),\n");
            html.append("    'test_write': JSON.stringify({walletAddress: '0x742d35Cc6634C0532925a3b844Bc9e7595f0bEb0', message: 'Hello Blockchain!'}, null, 2),\n");
            html.append("    'register_client': JSON.stringify({clientId: 'sling-author-1', clientUrl: 'http://localhost:8080', walletAddress: '0xabc...def'}, null, 2),\n");
            html.append("    'register_validator': JSON.stringify({validatorId: 'validator-4', validatorUrl: 'http://validator-4:8090'}, null, 2),\n");
            html.append("    'dag_head': JSON.stringify({validatorId: 'validator-2', headRecordId: 'abc123...xyz'}, null, 2)\n");
            html.append("  };\n");
            html.append("  return examples[id] || '{}';\n");
            html.append("}\n");
            html.append("\n");
            html.append("async function sendRequest(method, path) {\n");
            html.append("  const responseContainer = document.getElementById('response-container');\n");
            html.append("  responseContainer.innerHTML = '<div class=\"response\"><div class=\"response-header\">Sending request...</div></div>';\n");
            html.append("  \n");
            html.append("  try {\n");
            html.append("    let finalPath = path.split('?')[0];\n");
            html.append("    \n");
            html.append("    const pathParams = document.getElementById('path-params');\n");
            html.append("    if (pathParams && pathParams.value) {\n");
            html.append("      finalPath = finalPath.replace('{id}', pathParams.value);\n");
            html.append("    }\n");
            html.append("    \n");
            html.append("    const queryParams = document.getElementById('query-params');\n");
            html.append("    if (queryParams && queryParams.value) {\n");
            html.append("      finalPath += '?' + queryParams.value;\n");
            html.append("    }\n");
            html.append("    \n");
            html.append("    const options = { method };\n");
            html.append("    \n");
            html.append("    const bodyField = document.getElementById('request-body');\n");
            html.append("    if (bodyField && bodyField.value) {\n");
            html.append("      options.headers = { 'Content-Type': 'application/json' };\n");
            html.append("      options.body = bodyField.value;\n");
            html.append("    }\n");
            html.append("    \n");
            html.append("    const startTime = Date.now();\n");
            html.append("    const response = await fetch(finalPath, options);\n");
            html.append("    const duration = Date.now() - startTime;\n");
            html.append("    \n");
            html.append("    const contentType = response.headers.get('content-type');\n");
            html.append("    let body;\n");
            html.append("    \n");
            html.append("    if (contentType && contentType.includes('application/json')) {\n");
            html.append("      body = JSON.stringify(await response.json(), null, 2);\n");
            html.append("    } else {\n");
            html.append("      body = await response.text();\n");
            html.append("    }\n");
            html.append("    \n");
            html.append("    responseContainer.innerHTML = \n");
            html.append("      '<div class=\"response\">' +\n");
            html.append("      '<div class=\"response-header\">Status: ' + response.status + ' ' + response.statusText + ' (' + duration + 'ms)</div>' +\n");
            html.append("      '<div class=\"response-body\">' + body + '</div>' +\n");
            html.append("      '</div>';\n");
            html.append("    \n");
            html.append("  } catch (error) {\n");
            html.append("    responseContainer.innerHTML = \n");
            html.append("      '<div class=\"response\" style=\"border-left-color: #ef4444;\">' +\n");
            html.append("      '<div class=\"response-header\">Error</div>' +\n");
            html.append("      '<div class=\"response-body\" style=\"color: #f87171;\">' + error.message + '</div>' +\n");
            html.append("      '</div>';\n");
            html.append("  }\n");
            html.append("}\n");
            html.append("</script>\n");
            
            html.append("</div>\n"); // End container
            html.append("</body>\n</html>\n");
            
            response.getWriter().write(html.toString());
        }
        
        /**
         * Helper method to add an API endpoint to the browser UI.
         */
        private void addApiEndpoint(StringBuilder html, String method, String path, String description, String id) {
            html.append("<div class='endpoint' onclick='testEndpoint(\"").append(method).append("\", \"").append(path).append("\", \"").append(id).append("\")'>\n");
            html.append("<span class='method method-").append(method).append("'>").append(method).append("</span>\n");
            html.append("<span class='endpoint-path'>").append(escapeHtml(path)).append("</span>\n");
            html.append("<div class='endpoint-desc'>").append(escapeHtml(description)).append("</div>\n");
            html.append("</div>\n");
        }
        
        /**
         * Handle API request to explore a node in the repository.
         */
        private void handleExploreNode(HttpServletResponse response, String path) throws IOException {
            response.setContentType("application/json");
            
            try {
                // Get the head state from NodeStore
                NodeState root = nodeStore.getRoot();
                
                // Navigate to the requested path
                NodeState node = root;
                if (!"/".equals(path)) {
                    String[] parts = path.substring(1).split("/");
                    for (String part : parts) {
                        if (!part.isEmpty()) {
                            node = node.getChildNode(part);
                            if (!node.exists()) {
                                response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                                response.getWriter().write("{\"error\":\"Node not found\"}");
                                return;
                            }
                        }
                    }
                }
                
                // Build JSON response
                StringBuilder json = new StringBuilder();
                json.append("{");
                json.append("\"path\":\"").append(escapeJson(path)).append("\",");
                json.append("\"children\":[");
                boolean first = true;
                for (String childName : node.getChildNodeNames()) {
                    if (!first) json.append(",");
                    json.append("\"").append(escapeJson(childName)).append("\"");
                    first = false;
                }
                json.append("],");
                json.append("\"properties\":{");
                
                // Iterate through actual properties
                boolean firstProp = true;
                for (org.apache.jackrabbit.oak.api.PropertyState prop : node.getProperties()) {
                    if (!firstProp) json.append(",");
                    firstProp = false;
                    
                    String propName = prop.getName();
                    json.append("\"").append(escapeJson(propName)).append("\":");
                    
                    // Handle different property types
                    if (prop.isArray()) {
                        json.append("[");
                        boolean firstVal = true;
                        for (int i = 0; i < prop.count(); i++) {
                            if (!firstVal) json.append(",");
                            firstVal = false;
                            json.append("\"").append(escapeJson(String.valueOf(prop.getValue(org.apache.jackrabbit.oak.api.Type.STRING, i)))).append("\"");
                        }
                        json.append("]");
                    } else {
                        // Single value - handle different types
                        try {
                            String value;
                            if (prop.getType() == org.apache.jackrabbit.oak.api.Type.BINARY) {
                                value = "[Binary: " + prop.size() + " bytes]";
                            } else if (prop.getType() == org.apache.jackrabbit.oak.api.Type.BOOLEAN) {
                                value = String.valueOf(prop.getValue(org.apache.jackrabbit.oak.api.Type.BOOLEAN));
                            } else if (prop.getType() == org.apache.jackrabbit.oak.api.Type.LONG) {
                                value = String.valueOf(prop.getValue(org.apache.jackrabbit.oak.api.Type.LONG));
                            } else if (prop.getType() == org.apache.jackrabbit.oak.api.Type.DOUBLE) {
                                value = String.valueOf(prop.getValue(org.apache.jackrabbit.oak.api.Type.DOUBLE));
                            } else if (prop.getType() == org.apache.jackrabbit.oak.api.Type.DATE) {
                                value = String.valueOf(prop.getValue(org.apache.jackrabbit.oak.api.Type.DATE));
                            } else {
                                value = prop.getValue(org.apache.jackrabbit.oak.api.Type.STRING);
                            }
                            json.append("\"").append(escapeJson(value)).append("\"");
                        } catch (Exception e) {
                            json.append("\"[Error: ").append(escapeJson(e.getMessage())).append("]\"");
                        }
                    }
                }
                
                json.append("}}");
                
                response.setStatus(HttpServletResponse.SC_OK);
                response.getWriter().write(json.toString());
                
            } catch (Exception e) {
                log.error("Error exploring node: " + path, e);
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                response.getWriter().write("{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
            }
        }
        
        /**
         * Handle API request for recent segments.
         */
        private void handleRecentSegments(HttpServletResponse response) throws IOException {
            response.setContentType("application/json");
            
            try {
                java.util.List<String> segments = new java.util.ArrayList<>();
                Path journalPath = storeDirectory.resolve("journal.log");
                
                if (java.nio.file.Files.exists(journalPath)) {
                    java.util.List<String> lines = java.nio.file.Files.readAllLines(journalPath);
                    int start = Math.max(0, lines.size() - 20);
                    for (int i = lines.size() - 1; i >= start; i--) {
                        String line = lines.get(i);
                        if (line.contains(" ")) {
                            String[] parts = line.split(" ", 2);
                            segments.add("{\"id\":\"" + escapeJson(parts[0]) + "\",\"timestamp\":\"" + 
                                       (parts.length > 1 ? escapeJson(parts[1]) : "") + "\"}");
                        }
                    }
                }
                
                response.setStatus(HttpServletResponse.SC_OK);
                response.getWriter().write("[" + String.join(",", segments) + "]");
                
            } catch (Exception e) {
                log.error("Error reading recent segments", e);
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                response.getWriter().write("[]");
            }
        }
        
        /**
         * Handle API request for TAR files and their segments.
         */
        private void handleTarFiles(HttpServletResponse response) throws IOException {
            response.setContentType("application/json");
            
            try {
                java.util.List<String> tarEntries = new java.util.ArrayList<>();
                
                // Count total segments in journal
                int totalSegments = 0;
                Path journalPath = storeDirectory.resolve("journal.log");
                if (java.nio.file.Files.exists(journalPath)) {
                    java.util.List<String> journalLines = java.nio.file.Files.readAllLines(journalPath);
                    totalSegments = journalLines.size();
                }
                
                // List all .tar files and calculate total size
                java.util.List<Path> tarFiles = new java.util.ArrayList<>();
                long totalSize = 0;
                try (java.util.stream.Stream<Path> paths = java.nio.file.Files.list(storeDirectory)) {
                    tarFiles = paths
                        .filter(p -> p.toString().endsWith(".tar"))
                        .sorted(java.util.Comparator.comparing(Path::toString))
                        .collect(java.util.stream.Collectors.toList());
                    for (Path tarFile : tarFiles) {
                        totalSize += java.nio.file.Files.size(tarFile);
                    }
                }
                
                // Build JSON entries
                for (Path tarFile : tarFiles) {
                    String fileName = tarFile.getFileName().toString();
                    long fileSize = java.nio.file.Files.size(tarFile);
                    java.nio.file.attribute.BasicFileAttributes attrs = 
                        java.nio.file.Files.readAttributes(tarFile, java.nio.file.attribute.BasicFileAttributes.class);
                    
                    // Estimate segment count based on proportional file size
                    int estimatedSegments = totalSize > 0 ? (int)((fileSize * totalSegments) / totalSize) : 0;
                    
                    StringBuilder entry = new StringBuilder();
                    entry.append("{");
                    entry.append("\"name\":\"").append(escapeJson(fileName)).append("\",");
                    entry.append("\"size\":").append(fileSize).append(",");
                    entry.append("\"sizeFormatted\":\"").append(formatBytes(fileSize)).append("\",");
                    entry.append("\"segmentCount\":").append(estimatedSegments).append(",");
                    entry.append("\"estimatedCount\":true,");
                    entry.append("\"created\":\"").append(attrs.creationTime().toString()).append("\",");
                    entry.append("\"modified\":\"").append(attrs.lastModifiedTime().toString()).append("\"");
                    entry.append("}");
                    
                    tarEntries.add(entry.toString());
                }
                
                response.setStatus(HttpServletResponse.SC_OK);
                response.getWriter().write("[" + String.join(",", tarEntries) + "]");
                
            } catch (Exception e) {
                log.error("Error reading TAR files", e);
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                response.getWriter().write("[]");
            }
        }
        
        /**
         * Escape JSON string.
         */
        private String escapeJson(String text) {
            if (text == null) return "";
            return text.replace("\\", "\\\\")
                       .replace("\"", "\\\"")
                       .replace("\n", "\\n")
                       .replace("\r", "\\r")
                       .replace("\t", "\\t");
        }
        
        /**
         * Handle HEAD request for a segment (check existence).
         */
        private void handleSegmentHead(HttpServletResponse response, String segmentId) throws IOException {
            Path segmentPath = findSegmentInTarFiles(segmentId);
            
            if (segmentPath == null) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND, "Segment not found: " + segmentId);
                return;
            }
            
            response.setStatus(HttpServletResponse.SC_OK);
            response.setHeader("X-Segment-Found", "true");
        }
        
        /**
         * Handle GET request for a segment (fetch segment data).
         */
        private void handleSegmentGet(HttpServletRequest request, HttpServletResponse response, String segmentId) throws IOException {
            // Log requesting peer info
            String remoteAddr = request.getRemoteAddr();
            int remotePort = request.getRemotePort();
            String userAgent = request.getHeader("User-Agent");
            
            log.info("📦 Segment GET: {} FROM {}:{} [UA: {}]", 
                     segmentId, remoteAddr, remotePort, userAgent != null ? userAgent : "unknown");
            
            // Track connected peer (Sling Author mounting this store)
            if (!"localhost".equals(remoteAddr) && !"127.0.0.1".equals(remoteAddr)) {
                connectedPeers.add(remoteAddr + ":" + remotePort);
            }
            
            // Convert UUID string to msb/lsb
            java.util.UUID uuid;
            try {
                uuid = java.util.UUID.fromString(segmentId);
            } catch (IllegalArgumentException e) {
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid segment UUID: " + segmentId);
                return;
            }
            
            // Read segment from TAR files
            byte[] segmentData = readSegmentFromTar(uuid);
            
            if (segmentData == null) {
                log.warn("Segment not found in TAR files: {}", segmentId);
                response.sendError(HttpServletResponse.SC_NOT_FOUND, "Segment not found: " + segmentId);
                return;
            }
            
            // Return segment data
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType("application/octet-stream");
            response.setContentLength(segmentData.length);
            response.setHeader("X-Segment-Length", String.valueOf(segmentData.length));
            
            try (OutputStream out = response.getOutputStream()) {
                out.write(segmentData);
                out.flush();
            }
            
            log.info("Served segment {} ({} bytes)", segmentId, segmentData.length);
        }
        
        /**
         * Read a segment from TAR files.
         */
        private byte[] readSegmentFromTar(java.util.UUID segmentId) {
            // Look for data*.tar files
            File[] tarFiles = storeDirectory.toFile().listFiles((dir, name) -> 
                name.startsWith("data") && name.endsWith(".tar"));
            
            if (tarFiles == null || tarFiles.length == 0) {
                log.warn("No TAR files found in {}", storeDirectory);
                return null;
            }
            
            // Try each TAR file
            for (File tarFile : tarFiles) {
                try {
                    byte[] segment = readSegmentFromTarFile(tarFile, segmentId);
                    if (segment != null) {
                        return segment;
                    }
                } catch (IOException e) {
                    log.warn("Error reading from TAR file {}: {}", tarFile.getName(), e.getMessage());
                }
            }
            
            return null;
        }
        
        /**
         * Read a specific segment from TAR files using Oak's FileStore.
         * Uses the same pattern as Cold Standby segment replication.
         */
        private byte[] readSegmentFromTarFile(File tarFile, UUID segmentId) throws IOException {
            try {
                long msb = segmentId.getMostSignificantBits();
                long lsb = segmentId.getLeastSignificantBits();
                
                // Create SegmentId using FileStore's provider (Cold Standby pattern)
                SegmentId sid = fileStore.getSegmentIdProvider().newSegmentId(msb, lsb);
                
                // Check if segment exists
                if (!fileStore.containsSegment(sid)) {
                    log.debug("Segment {} not found in store", segmentId);
                    return null;
                }
                
                // Read segment and serialize to bytes (Cold Standby pattern)
                Segment segment = fileStore.readSegment(sid);
                try (ByteArrayOutputStream stream = new ByteArrayOutputStream()) {
                    segment.writeTo(stream);
                    byte[] data = stream.toByteArray();
                    log.info("Read segment {} from TAR ({} bytes)", segmentId, data.length);
                    return data;
                }
            } catch (Exception e) {
                log.warn("Error reading segment {}: {}", segmentId, e.getMessage());
                return null;
            }
        }
        
        /**
         * Find a segment in TAR files (simplified - just check if any TAR files exist).
         */
        private Path findSegmentInTarFiles(String segmentId) throws IOException {
            // Look for data*.tar files
            File[] tarFiles = storeDirectory.toFile().listFiles((dir, name) -> 
                name.startsWith("data") && name.endsWith(".tar"));
            
            if (tarFiles != null && tarFiles.length > 0) {
                // Segment might exist - return the first TAR file
                // Full implementation would need to parse TAR internals
                return tarFiles[0].toPath();
            }
            
            return null;
        }
        
        /**
         * Handle POST /v1/propose - Receive write proposal from peer
         */
        private void handleWriteProposal(HttpServletRequest request, HttpServletResponse response) throws IOException {
            if (consensusEngine == null) {
                response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Consensus engine not configured");
                return;
            }
            
            // Read JSON body
            String json = request.getReader().lines().collect(Collectors.joining());
            
            try {
                // Parse proposal (simple JSON parsing for Phase 1)
                WriteProposal proposal = parseProposal(json);
                
                // Process proposal and vote
                Vote vote = consensusEngine.handleProposal(proposal);
                
                // Return vote immediately
                response.setContentType("application/json");
                response.setStatus(HttpServletResponse.SC_OK);
                response.getWriter().write(voteToJson(vote));
                
            } catch (Exception e) {
                log.error("Error processing proposal", e);
                response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
            }
        }
        
        /**
         * Handle POST /v1/vote - Receive vote from peer
         */
        private void handleVote(HttpServletRequest request, HttpServletResponse response) throws IOException {
            if (consensusEngine == null) {
                response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Consensus engine not configured");
                return;
            }
            
            // Read JSON body
            String json = request.getReader().lines().collect(Collectors.joining());
            
            try {
                // Parse vote
                Vote vote = parseVote(json);
                
                // Process vote
                consensusEngine.handleVote(vote);
                
                // Return OK
                response.setContentType("application/json");
                response.setStatus(HttpServletResponse.SC_OK);
                response.getWriter().write("{\"status\":\"accepted\"}");
                
            } catch (Exception e) {
                log.error("Error processing vote", e);
                response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
            }
        }
        
        // OLD handleListPeers() REMOVED - was returning hardcoded empty list
        // Use handlePeerList() instead which returns actual validator registry
        
        /**
         * Handle GET /api/metrics - Return consensus and replication metrics
         */
        private void handleMetrics(HttpServletResponse response) throws IOException {
            response.setContentType("application/json");
            response.setStatus(HttpServletResponse.SC_OK);
            
            StringBuilder json = new StringBuilder();
            json.append("{\n");
            
            // Consensus metrics
            if (consensusEngine != null) {
                json.append("  \"consensus\": {\n");
                json.append("    \"totalProposals\": ").append(consensusEngine.getTotalProposals()).append(",\n");
                json.append("    \"successfulProposals\": ").append(consensusEngine.getSuccessfulProposals()).append(",\n");
                json.append("    \"failedProposals\": ").append(consensusEngine.getFailedProposals()).append(",\n");
                json.append("    \"successRate\": ").append(String.format("%.1f", consensusEngine.getConsensusSuccessRate())).append(",\n");
                json.append("    \"averageConsensusTimeMs\": ").append(consensusEngine.getAverageConsensusTimeMs()).append(",\n");
                json.append("    \"totalVotesReceived\": ").append(consensusEngine.getTotalVotesReceived()).append("\n");
                json.append("  },\n");
                
                json.append("  \"replication\": {\n");
                json.append("    \"totalSegments\": ").append(consensusEngine.getTotalSegmentsReplicated()).append(",\n");
                json.append("    \"totalBytes\": ").append(consensusEngine.getTotalBytesReplicated()).append(",\n");
                json.append("    \"totalMb\": ").append(String.format("%.2f", consensusEngine.getTotalBytesReplicated() / (1024.0 * 1024.0))).append("\n");
                json.append("  },\n");
                
                json.append("  \"validator\": {\n");
                json.append("    \"url\": \"").append(consensusEngine.getSelfUrl()).append("\",\n");
                json.append("    \"peers\": ").append(consensusEngine.getPeerCount()).append("\n");
                json.append("  }\n");
            } else {
                json.append("  \"consensus\": null,\n");
                json.append("  \"replication\": null,\n");
                json.append("  \"validator\": null\n");
            }
            
            json.append("}\n");
            
            response.getWriter().write(json.toString());
        }
        
        /**
         * Handle GET /metrics - Prometheus metrics endpoint.
         * 
         * Returns metrics in Prometheus text format for scraping by Prometheus server.
         * Includes:
         * - JVM metrics (memory, GC, threads) from DefaultExports
         * - Custom Oak consensus metrics from ConsensusMetrics
         */
        private void handlePrometheusMetrics(HttpServletResponse response) throws IOException {
            response.setContentType(TextFormat.CONTENT_TYPE_004);
            response.setStatus(HttpServletResponse.SC_OK);
            
            // Update dynamic metrics before exporting
            updateDynamicMetrics();
            
            // Export all registered metrics in Prometheus format
            try (Writer writer = response.getWriter()) {
                TextFormat.write004(writer, CollectorRegistry.defaultRegistry.metricFamilySamples());
            }
        }
        
        /**
         * Update dynamic Prometheus metrics from consensus engine state.
         * Called before each metrics scrape to reflect current state.
         */
        private void updateDynamicMetrics() {
            // Update DAG chain height
            if (dagConsensusEngine != null) {
                ConsensusMetrics.dagChainHeight.set(dagConsensusEngine.getChainHeight());
                ConsensusMetrics.dagPendingTransactions.set(dagConsensusEngine.getPendingTransactionCount());
            }
            
            // Update leader status
            if (leaderConsensusEngine != null) {
                ConsensusMetrics.updateLeaderStatus(
                    leaderConsensusEngine.isLeader(),
                    leaderConsensusEngine.getCurrentEpoch()
                );
                ConsensusMetrics.validatorsReachable.set(leaderConsensusEngine.getReachableValidatorCount());
                ConsensusMetrics.timeSinceLastHeartbeat.set(
                    (System.currentTimeMillis() - leaderConsensusEngine.getLastHeartbeatTime()) / 1000.0
                );
            }
            
            // Update storage metrics
            try {
                // Count TAR files directly in storeDirectory (Oak's segment files are here)
                if (Files.exists(storeDirectory)) {
                    long segmentCount = Files.list(storeDirectory)
                        .filter(p -> p.toString().endsWith(".tar"))
                        .count();
                    ConsensusMetrics.segmentsStoredTotal.set(segmentCount);
                    
                    long diskUsage = Files.walk(storeDirectory)
                        .filter(Files::isRegularFile)
                        .mapToLong(p -> {
                            try {
                                return Files.size(p);
                            } catch (IOException e) {
                                return 0;
                            }
                        })
                        .sum();
                    ConsensusMetrics.segmentsDiskUsageBytes.set(diskUsage);
                }
            } catch (IOException e) {
                log.debug("Failed to update storage metrics: {}", e.getMessage());
            }
            
            // Update active connections (approximation via registered clients)
            ConsensusMetrics.activeConnections.set(registeredClients.size() + registeredValidators.size());
        }
        
        /**
         * Handle POST /v1/test-write - Write endpoint with wallet-based storage
         * 
         * Parameters:
         *   - wallet: Ethereum address (e.g., 0x1234...)
         *   - signature: Message signature (mock for now, real Web3j verification later)
         *   - message: Content to write
         *   - contentType: Type of content (default: "page")
         */
        private void handleTestWrite(HttpServletRequest request, HttpServletResponse response) throws IOException {
            // Check if any consensus engine is configured
            if (consensusEngine == null && dagConsensusEngine == null && leaderConsensusEngine == null) {
                response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Consensus engine not configured");
                return;
            }
            
            // LEADER CHECK: If using leader-based consensus, proxy to leader if we're a follower
            if (leaderConsensusEngine != null) {
                if (!leaderConsensusEngine.isLeader()) {
                    String currentLeader = leaderConsensusEngine.getCurrentLeader();
                    log.info("📡 FOLLOWER: Proxying write request to leader: {}", currentLeader);
                    
                    // SMART PROXY FAILOVER: Try current leader, then next validator
                    String proxyTarget = currentLeader;
                    Exception firstFailure = null;
                    
                    for (int attempt = 0; attempt < 2; attempt++) {
                        try {
                            // Build the full URL with query parameters
                            StringBuilder targetUrl = new StringBuilder(proxyTarget);
                            targetUrl.append("/v1/test-write");
                            String queryString = request.getQueryString();
                            if (queryString != null && !queryString.isEmpty()) {
                                targetUrl.append("?").append(queryString);
                            }
                            
                            log.info("   Attempt {}: Trying {}", attempt + 1, proxyTarget);
                            
                            // Forward request to target
                            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) 
                                new java.net.URL(targetUrl.toString()).openConnection();
                            conn.setRequestMethod("POST");
                            conn.setConnectTimeout(3000); // Shorter timeout for faster failover
                            conn.setReadTimeout(5000);
                            
                            // Forward headers
                            String clientId = request.getHeader("X-Client-Id");
                            if (clientId != null) {
                                conn.setRequestProperty("X-Client-Id", clientId);
                            }
                            conn.setRequestProperty("X-Proxied-By", selfUrl);
                            
                            // Get response from target
                            int targetStatus = conn.getResponseCode();
                            
                            // Read target's response
                            java.io.InputStream inputStream = targetStatus >= 400 
                                ? conn.getErrorStream() 
                                : conn.getInputStream();
                            
                            if (inputStream != null) {
                                java.io.BufferedReader reader = new java.io.BufferedReader(
                                    new java.io.InputStreamReader(inputStream)
                                );
                                StringBuilder targetResponse = new StringBuilder();
                                String line;
                                while ((line = reader.readLine()) != null) {
                                    targetResponse.append(line);
                                }
                                reader.close();
                                
                                // Forward target's response to client
                                response.setStatus(targetStatus);
                                response.setContentType("application/json");
                                response.setHeader("X-Proxied-From", proxyTarget);
                                response.setHeader("X-Failover-Attempt", String.valueOf(attempt + 1));
                                response.getWriter().write(targetResponse.toString());
                                
                                log.info("✅ Proxied write to {} - Status: {}", proxyTarget, targetStatus);
                                return; // SUCCESS!
                            }
                            
                        } catch (Exception e) {
                            if (attempt == 0) {
                                // First attempt failed
                                firstFailure = e;
                                log.warn("⚠️  Primary leader unreachable: {} - {}", currentLeader, e.getMessage());
                                
                                // Check if leader appears dead via health monitoring
                                if (leaderConsensusEngine.getHealthMonitor().isLeaderDead()) {
                                    log.warn("💀 Health monitor confirms leader is dead");
                                }
                                
                                // Try next validator in rotation
                                proxyTarget = getNextValidatorInRotation();
                                log.warn("🔄 Attempting failover to next validator: {}", proxyTarget);
                                
                            } else {
                                // Second attempt also failed - give up
                                log.error("❌ Both proxy attempts failed");
                                log.error("   Primary: {} - {}", currentLeader, firstFailure.getMessage());
                                log.error("   Failover: {} - {}", proxyTarget, e.getMessage());
                                
                                response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                                response.setContentType("application/json");
                                response.getWriter().write(String.format(
                                    "{\"error\":\"All validators unreachable\",\"attempted\":[\"%s\",\"%s\"],\"message\":\"Consensus network unavailable\"}",
                                    currentLeader, proxyTarget
                                ));
                                return;
                            }
                        }
                    }
                    
                    // Shouldn't reach here, but just in case
                    return;
                }
                log.debug("✅ Leader check passed - I am the leader");
            }
            
            boolean usingDagMode = (dagConsensusEngine != null);
            boolean usingLeaderMode = (leaderConsensusEngine != null);
            
            try {
                // Read wallet-based write parameters
                String wallet = request.getParameter("wallet");
                String signature = request.getParameter("signature");
                String message = request.getParameter("message");
                String contentType = request.getParameter("contentType");
                
                // Default values
                if (wallet == null || wallet.isEmpty()) {
                    wallet = "0x742d35Cc6634C0532925a3b844Bc9e7595f0bEb"; // Mock default wallet
                }
                if (message == null || message.isEmpty()) {
                    message = "Test content at " + System.currentTimeMillis();
                }
                if (contentType == null || contentType.isEmpty()) {
                    contentType = "page";
                }
                if (signature == null || signature.isEmpty()) {
                    signature = "0xMOCK" + System.currentTimeMillis(); // Mock signature
                }
                
                // Validate Ethereum address format (basic check)
                if (!wallet.startsWith("0x") || wallet.length() < 10) {
                    response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid Ethereum address format");
                    return;
                }
                
                // PATH ENFORCEMENT: Verify client is registered and wallet matches
                String clientId = request.getHeader("X-Client-Id");
                if (clientId == null || clientId.isEmpty()) {
                    clientId = request.getParameter("clientId");
                }
                // Fallback to remote address if no client ID provided
                if (clientId == null || clientId.isEmpty()) {
                    String remoteAddr = request.getRemoteAddr();
                    int remotePort = request.getRemotePort();
                    clientId = remoteAddr + ":" + remotePort;
                }
                
                log.debug("Path enforcement check: clientId={}, registeredClients.size()={}", clientId, registeredClients.size());
                
                // Normalize wallet addresses for comparison (case-insensitive)
                String normalizedWallet = wallet.toLowerCase();
                
                // Look up registered client
                ClientRegistration clientReg = registeredClients.get(clientId);
                
                if (clientReg == null) {
                    // Client not registered - reject write
                    log.warn("🚫 Write rejected: Client {} not registered", clientId);
                    log.warn("   Available registered clients: {}", registeredClients.keySet());
                    response.sendError(HttpServletResponse.SC_FORBIDDEN, 
                        "Client not registered. Please register via /v1/register-client before writing.");
                    return;
                }
                
                log.debug("Client {} found in registry, wallet: {}", clientId, clientReg.walletAddress);
                
                // Verify wallet matches registered client's wallet
                if (clientReg.walletAddress != null && !clientReg.walletAddress.isEmpty()) {
                    String registeredWallet = clientReg.walletAddress.toLowerCase();
                    if (!normalizedWallet.equals(registeredWallet)) {
                        log.warn("🚫 Write rejected: Wallet mismatch for client {}", clientId);
                        log.warn("   Requested wallet: {}", normalizedWallet);
                        log.warn("   Registered wallet: {}", registeredWallet);
                        response.sendError(HttpServletResponse.SC_FORBIDDEN, 
                            String.format("Path enforcement violation: Client %s can only write to /oak-chain/content/%s/, " +
                                         "but attempted to write to /oak-chain/content/%s/", 
                                         clientId, registeredWallet, normalizedWallet));
                        return;
                    }
                } else {
                    // Client registered but no wallet address - allow write but log warning
                    log.warn("⚠️  Client {} registered without wallet address - allowing write but path enforcement not possible", clientId);
                }
                
                log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                log.info("🔐 WALLET-BASED WRITE INITIATED");
                log.info("   Client: {} (registered)", clientId);
                log.info("   Wallet: {} (verified)", wallet);
                log.info("   Content Type: {}", contentType);
                log.info("   Message: {}", message);
                log.info("   Signature: {}...{}", signature.substring(0, Math.min(10, signature.length())), 
                         signature.length() > 10 ? signature.substring(signature.length() - 4) : "");
                log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                
                // TODO: Real signature verification with Web3j
                // For now, we accept all signatures starting with "0x"
                if (!signature.startsWith("0x")) {
                    log.warn("🚫 Write rejected: Invalid signature format (must start with '0x')");
                    response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid signature format");
                    return;
                }
                log.info("✅ Signature verification: MOCK (accepted)");
                
                // Get current HEAD
                String previousHead = fileStore.getHead().getRecordId().toString();
                log.info("📍 Previous HEAD: {}", previousHead.substring(0, Math.min(20, previousHead.length())));
                
                // Make a write to the repository using SHARDED path
                org.apache.jackrabbit.oak.spi.state.NodeBuilder rootBuilder = nodeStore.getRoot().builder();
                
                // Get sharded path: /oak-chain/content/{L1}/{L2}/{L3}/0x{wallet}/
                String shardedPath = org.apache.jackrabbit.oak.segment.consensus.util.WalletPathUtil
                    .toShardedPath(wallet.toLowerCase());
                log.info("🪣 Using sharded path: {}", shardedPath);
                
                // Navigate through sharded structure
                // Example: /oak-chain/content/74/2d/35/0x742d35cc.../
                // (normalizedWallet already defined earlier in method)
                String addr = normalizedWallet.replace("0x", "");
                
                org.apache.jackrabbit.oak.spi.state.NodeBuilder walletPath = rootBuilder
                    .child("oak-chain")
                    .child("content")
                    .child(addr.substring(0, 2))  // L1: 74
                    .child(addr.substring(2, 4))  // L2: 2d
                    .child(addr.substring(4, 6))  // L3: 35
                    .child(normalizedWallet);      // Wallet: 0x742d35cc...
                
                // Create content node under wallet path
                String contentId = contentType + "-" + System.currentTimeMillis();
                org.apache.jackrabbit.oak.spi.state.NodeBuilder contentNode = walletPath.child(contentId);
                
                // Set properties
                contentNode.setProperty("jcr:primaryType", "nt:unstructured");
                contentNode.setProperty("contentType", contentType);
                contentNode.setProperty("message", message);
                contentNode.setProperty("timestamp", System.currentTimeMillis());
                contentNode.setProperty("wallet", wallet);
                contentNode.setProperty("signature", signature);
                contentNode.setProperty("source", "consensus-write");
                
                // Commit the change (this creates new segments!)
                org.apache.jackrabbit.oak.spi.commit.CommitInfo commitInfo = 
                    new org.apache.jackrabbit.oak.spi.commit.CommitInfo(
                        "consensus-test", 
                        null, 
                        java.util.Collections.singletonMap("test", "true")
                    );
                
                nodeStore.merge(rootBuilder, org.apache.jackrabbit.oak.spi.commit.EmptyHook.INSTANCE, commitInfo);
                
                // CRITICAL: Flush FileStore to ensure all segments are persisted to disk
                // BEFORE broadcasting proposal to peers!
                fileStore.flush();
                log.info("✅ FileStore flushed - segments persisted to disk");
                
                // Get new HEAD
                String newHead = fileStore.getHead().getRecordId().toString();
                log.info("📍 New HEAD: {}", newHead.substring(0, Math.min(20, newHead.length())));
                
                // Create write proposal with wallet metadata
                WriteProposal proposal = new WriteProposal(
                    selfUrl, // Use actual self URL (set by GlobalStoreServer)
                    previousHead,
                    newHead
                );
                proposal.setAuthor(wallet); // Wallet address as author
                proposal.setCommitMessage("Wallet write: " + contentType + " - " + message);
                proposal.setMockPaymentVerified(true); // TODO: Verify payment from smart contract
                proposal.setMockSignature(signature);
                
                // TODO: Add actual segments to proposal
                // For Phase 1, we'll rely on validators fetching via HTTP
                
                String mode = usingLeaderMode ? "Leader" : (usingDagMode ? "DAG" : "Blockchain");
                log.info("📤 Processing write via {} mode...", mode);
                log.info("   Storage path: {}/{}", shardedPath, contentId);
                
                boolean success = false;
                String consensusMode = "";
                
                if (usingLeaderMode) {
                    // LEADER MODE: Write succeeds immediately (we're the leader), broadcast HEAD to followers
                    log.info("🎖️  LEADER MODE: Write succeeds (I am leader), broadcasting to followers...");
                    
                    // Broadcast new HEAD to all followers
                    String newHeadStr = fileStore.getHead().getRecordId().toString10();
                    leaderConsensusEngine.broadcastHeadToFollowers(newHeadStr);
                    
                    success = true;
                    consensusMode = "leader-accepted";
                    log.info("✅ Leader write complete, HEAD broadcasted to followers");
                    
                    // Track write metadata for dashboard (Leader mode)
                    String recordIdShort = newHead.length() > 20 ? newHead.substring(0, 20) : newHead;
                    recentWriteMetadata.put(recordIdShort, new WriteMetadata(
                        newHead,
                        "leader-accepted",
                        selfUrl,
                        System.currentTimeMillis(),
                        "Leader write: " + contentType + " - " + message
                    ));
                    
                } else if (usingDagMode) {
                    // DAG MODE: Write succeeds immediately, broadcast HEAD update
                    log.info("🌳 DAG MODE: Write succeeds locally (no immediate consensus needed)");
                    dagConsensusEngine.proposeWrite(newHead, "Wallet write: " + contentType + " - " + message);
                    success = true;
                    consensusMode = "dag-local";
                    log.info("✅ Local write complete, HEAD update broadcasted to peers");
                    
                    // Track write metadata for dashboard (DAG mode)
                    String recordIdShort = newHead.length() > 20 ? newHead.substring(0, 20) : newHead;
                    recentWriteMetadata.put(recordIdShort, new WriteMetadata(
                        newHead,
                        "dag-local",
                        selfUrl,
                        System.currentTimeMillis(),
                        "Wallet write: " + contentType + " - " + message
                    ));
                } else {
                    // BLOCKCHAIN MODE: Requires consensus before committing
                    log.info("⛓️  BLOCKCHAIN MODE: Proposing to consensus network...");
                    success = consensusEngine.proposeWrite(proposal);
                    consensusMode = success ? "blockchain-consensus" : "blockchain-rejected";
                }
                
                // Return result
                response.setContentType("application/json");
                response.setStatus(HttpServletResponse.SC_OK);
                
                String result = "{" +
                    "\"success\":" + success + "," +
                    "\"proposalId\":\"" + proposal.getProposalId() + "\"," +
                    "\"wallet\":\"" + wallet + "\"," +
                    "\"contentId\":\"" + contentId + "\"," +
                    "\"storagePath\":\"" + shardedPath + "/" + contentId + "\"," +
                    "\"previousHead\":\"" + previousHead + "\"," +
                    "\"newHead\":\"" + newHead + "\"," +
                    "\"message\":\"" + message + "\"," +
                    "\"contentType\":\"" + contentType + "\"," +
                    "\"consensusMode\":\"" + consensusMode + "\"," +
                    "\"mode\":\"" + (usingDagMode ? "dag" : "blockchain") + "\"" +
                    "}";
                
                response.getWriter().write(result);
                
                if (success) {
                    log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                    if (usingDagMode) {
                        log.info("✅ DAG WRITE COMPLETE! Local HEAD updated, peers notified");
                    } else {
                        log.info("✅ CONSENSUS REACHED! Write committed across all validators");
                    }
                    log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                    
                    // Track write metadata for dashboard
                    String recordIdShort = newHead.length() > 20 ? newHead.substring(0, 20) : newHead;
                    recentWriteMetadata.put(recordIdShort, new WriteMetadata(
                        newHead,
                        "consensus",
                        proposal.getProposerUrl(),
                        System.currentTimeMillis(),
                        message
                    ));
                } else {
                    log.warn("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                    log.warn("❌ CONSENSUS FAILED! Write not replicated");
                    log.warn("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                }
                
            } catch (Exception e) {
                log.error("❌ Test write failed", e);
                response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Test write failed: " + e.getMessage());
            }
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
        private void handleClientRegistration(HttpServletRequest request, HttpServletResponse response) throws IOException {
            try {
                // Read JSON body if present
                StringBuilder json = new StringBuilder();
                java.io.BufferedReader reader = request.getReader();
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
                    clientId = extractJsonField(body, "clientId");
                    clientUrl = extractJsonField(body, "clientUrl");
                    walletAddress = extractJsonField(body, "walletAddress");
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
                ClientRegistration registration = registeredClients.get(clientId);
                if (registration == null) {
                    registration = new ClientRegistration(clientId, clientUrl, walletAddress);
                    registeredClients.put(clientId, registration);
                    log.info("✅ New client registered: {} ({})", clientId, clientUrl);
                } else {
                    registration.lastSeen = System.currentTimeMillis();
                    if (walletAddress != null && !walletAddress.isEmpty()) {
                        registration.walletAddress = walletAddress;
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
        private void handleValidatorRegistration(HttpServletRequest request, HttpServletResponse response) throws IOException {
            try {
                // Parse JSON body or query parameters
                String validatorId = null;
                String validatorUrl = null;
                
                // Try JSON body first
                StringBuilder json = new StringBuilder();
                java.io.BufferedReader reader = request.getReader();
                String line;
                while ((line = reader.readLine()) != null) {
                    json.append(line);
                }
                
                if (json.length() > 0) {
                    String body = json.toString();
                    validatorId = extractJsonField(body, "validatorId");
                    validatorUrl = extractJsonField(body, "validatorUrl");
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
                if (validatorUrl.equals(selfUrl)) {
                    response.setContentType("application/json");
                    response.setStatus(HttpServletResponse.SC_OK);
                    response.getWriter().write("{\"success\":true,\"message\":\"Self-registration ignored\"}");
                    return;
                }
                
                // Register or update validator
                ValidatorRegistration registration = registeredValidators.get(validatorId);
                if (registration == null) {
                    registration = new ValidatorRegistration(validatorId, validatorUrl);
                    registeredValidators.put(validatorId, registration);
                    log.info("✅ New validator peer registered: {} ({})", validatorId, validatorUrl);
                } else {
                    registration.lastSeen = System.currentTimeMillis();
                    if (!registration.validatorUrl.equals(validatorUrl)) {
                        registration.validatorUrl = validatorUrl;
                        log.info("🔄 Validator peer updated: {} ({})", validatorId, validatorUrl);
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
         * Handle GET /v1/peers - Return list of all known validators for organic peer discovery
         * 
         * Returns JSON array with enriched status:
         * [
         *   {"validatorId": "validator-1", "validatorUrl": "http://validator-1:8090", "lastSeen": 1234567890, "status": "READY"},
         *   {"validatorId": "validator-2", "validatorUrl": "http://validator-2:8090", "lastSeen": 1234567891, "status": "PROBATION"}
         * ]
         * 
         * Status values:
         * - READY: Voting member, fully participating in consensus
         * - PROBATION: Non-voting follower, must wait 1 epoch before joining electorate
         * - OFFLINE: Last seen > 2 epochs ago (10 minutes), likely disconnected
         */
        private void handlePeerList(HttpServletResponse response) throws IOException {
            try {
                response.setContentType("application/json");
                response.setStatus(HttpServletResponse.SC_OK);
                
                // Get non-voting followers (probationary validators)
                final java.util.List<String> nonVotingFollowers;
                if (leaderConsensusEngine != null) {
                    nonVotingFollowers = leaderConsensusEngine.getNonVotingFollowers();
                } else {
                    nonVotingFollowers = new java.util.ArrayList<>();
                }
                
                final long now = System.currentTimeMillis();
                // OFFLINE = missed 2 full epochs (2 x 300s = 600s = 10 minutes)
                // A validator should be sending heartbeats or receiving them every epoch
                int leaderTermSeconds = 300; // default
                if (leaderConsensusEngine != null) {
                    leaderTermSeconds = leaderConsensusEngine.getElection().getLeaderTermSeconds();
                }
                final long offlineThresholdMs = leaderTermSeconds * 2 * 1000L; // 2 epochs
                
                StringBuilder json = new StringBuilder();
                json.append("[\n");
                
                boolean first = true;
                for (ValidatorRegistration reg : registeredValidators.values()) {
                    if (!first) {
                        json.append(",\n");
                    }
                    first = false;
                    
                    // Determine status
                    String status;
                    long timeSinceLastSeen = now - reg.lastSeen;
                    
                    if (timeSinceLastSeen > offlineThresholdMs) {
                        status = "OFFLINE";  // Hasn't been seen in > 60s
                    } else if (nonVotingFollowers.contains(reg.validatorUrl)) {
                        status = "PROBATION";  // Non-voting, waiting for probation period
                    } else {
                        status = "READY";  // Voting member, fully participating
                    }
                    
                    json.append("  {");
                    json.append("\"validatorId\":\"").append(reg.validatorId.replace("\"", "\\\"")).append("\",");
                    json.append("\"validatorUrl\":\"").append(reg.validatorUrl.replace("\"", "\\\"")).append("\",");
                    json.append("\"lastSeen\":").append(reg.lastSeen).append(",");
                    json.append("\"status\":\"").append(status).append("\"");
                    json.append("}");
                }
                
                json.append("\n]");
                response.getWriter().write(json.toString());
                
                log.debug("Served peer list: {} validators (READY={}, PROBATION={}, OFFLINE={})", 
                    registeredValidators.size(),
                    registeredValidators.values().stream().filter(r -> 
                        (now - r.lastSeen <= offlineThresholdMs) && !nonVotingFollowers.contains(r.validatorUrl)
                    ).count(),
                    nonVotingFollowers.size(),
                    registeredValidators.values().stream().filter(r -> 
                        now - r.lastSeen > offlineThresholdMs
                    ).count()
                );
                
            } catch (Exception e) {
                log.error("Failed to serve peer list", e);
                response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Failed to get peer list: " + e.getMessage());
            }
        }
        
        /**
         * Handle POST /v1/dag/head - Receive HEAD update from peer (like git fetch)
         * 
         * Parameters (JSON body):
         *   - validatorUrl: URL of the validator sending HEAD
         *   - recordId: The HEAD RecordId
         *   - depth: Depth in DAG
         *   - timestamp: When this HEAD was created
         *   - parentIds: Parent HEADs (for merge commits)
         */
        private void handleDagHeadUpdate(HttpServletRequest request, HttpServletResponse response) throws IOException {
            if (dagConsensusEngine == null) {
                response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "DAG consensus not configured");
                return;
            }
            
            try {
                // Read JSON body
                StringBuilder json = new StringBuilder();
                java.io.BufferedReader reader = request.getReader();
                String line;
                while ((line = reader.readLine()) != null) {
                    json.append(line);
                }
                
                String body = json.toString();
                
                // Parse HEAD update (simple JSON parsing for Phase 1)
                String validatorUrl = extractJsonField(body, "validatorUrl");
                String recordId = extractJsonField(body, "recordId");
                String depthStr = extractJsonField(body, "depth");
                int depth = Integer.parseInt(depthStr);
                
                // Create DagHead from received data
                org.apache.jackrabbit.oak.segment.consensus.dag.DagHead peerHead = 
                    new org.apache.jackrabbit.oak.segment.consensus.dag.DagHead(recordId, validatorUrl);
                peerHead.setDepth(depth);
                
                // Parse parent IDs if present
                String parentsJson = extractJsonField(body, "parentIds");
                if (parentsJson != null && !parentsJson.isEmpty()) {
                    // Simple comma-separated parsing
                    String[] parents = parentsJson.split(",");
                    for (String parent : parents) {
                        if (!parent.trim().isEmpty()) {
                            peerHead.addParent(parent.trim());
                        }
                    }
                }
                
                log.info("📥 Received HEAD update from {}", validatorUrl);
                log.info("   HEAD: {} (depth={})", recordId.substring(0, Math.min(16, recordId.length())) + "...", depth);
                
                // Update DAG consensus engine with peer HEAD
                dagConsensusEngine.handlePeerHeadUpdate(validatorUrl, peerHead);
                
                // Return success
                response.setContentType("application/json");
                response.setStatus(HttpServletResponse.SC_OK);
                response.getWriter().write("{\"success\":true,\"message\":\"HEAD update received\"}");
                
            } catch (Exception e) {
                log.error("❌ Failed to process HEAD update", e);
                response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Failed to process HEAD update: " + e.getMessage());
            }
        }
        
        /**
         * Handle POST /v1/follower/head-update - Follower receives HEAD update from leader
         * 
         * Parameters (JSON body):
         *   - head: The new HEAD RecordId from leader
         *   - epoch: Current epoch number
         *   - leaderUrl: URL of the current leader
         */
        private void handleFollowerHeadUpdate(HttpServletRequest request, HttpServletResponse response) throws IOException {
            if (leaderConsensusEngine == null) {
                response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Leader consensus not configured");
                return;
            }
            
            // Only followers should receive HEAD updates
            if (leaderConsensusEngine.isLeader()) {
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, "I am the leader, not a follower");
                return;
            }
            
            try {
                // Read JSON body
                StringBuilder json = new StringBuilder();
                java.io.BufferedReader reader = request.getReader();
                String line;
                while ((line = reader.readLine()) != null) {
                    json.append(line);
                }
                
                String body = json.toString();
                
                // Parse HEAD update
                String head = extractJsonField(body, "head");
                String epochStr = extractJsonField(body, "epoch");
                String leaderUrl = extractJsonField(body, "leaderUrl");
                
                int epoch = Integer.parseInt(epochStr);
                
                // Verify this is from the legitimate leader
                if (!leaderUrl.equals(leaderConsensusEngine.getCurrentLeader())) {
                    log.warn("🚫 HEAD update from non-leader: {} (expected: {})", 
                        leaderUrl, leaderConsensusEngine.getCurrentLeader());
                    response.sendError(HttpServletResponse.SC_FORBIDDEN, "Not current leader");
                    return;
                }
                
                log.info("📥 Received HEAD update from leader: {}", leaderUrl);
                log.info("   HEAD: {}...", head.substring(0, Math.min(16, head.length())));
                log.info("   Epoch: {}", epoch);
                
                // Pull segments for this HEAD from leader
                try {
                    int segmentCount = leaderConsensusEngine.pullSegmentsForHead(head, leaderUrl);
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
         * Handle heartbeat from leader.
         * Followers receive these periodically to confirm leader is alive.
         */
        private void handleHeartbeat(HttpServletRequest request, HttpServletResponse response) throws IOException {
            if (leaderConsensusEngine == null) {
                response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Leader consensus not configured");
                return;
            }
            
            // Only followers should receive heartbeats
            if (leaderConsensusEngine.isLeader()) {
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, "I am the leader, not a follower");
                return;
            }
            
            try {
                // Read JSON body
                StringBuilder json = new StringBuilder();
                java.io.BufferedReader reader = request.getReader();
                String line;
                while ((line = reader.readLine()) != null) {
                    json.append(line);
                }
                
                String body = json.toString();
                
                // Parse heartbeat
                String leaderUrl = extractJsonField(body, "leaderUrl");
                
                // Verify this is from the legitimate leader
                if (!leaderUrl.equals(leaderConsensusEngine.getCurrentLeader())) {
                    log.debug("🚫 Heartbeat from non-leader: {} (expected: {})", 
                        leaderUrl, leaderConsensusEngine.getCurrentLeader());
                    response.sendError(HttpServletResponse.SC_FORBIDDEN, "Not current leader");
                    return;
                }
                
                // Record the heartbeat
                leaderConsensusEngine.getHealthMonitor().recordHeartbeat();
                
                // Register the leader in our validator registry (if not already registered)
                // This ensures followers learn about the leader via heartbeat
                String leaderId = leaderUrl.contains("validator-") 
                    ? leaderUrl.substring(leaderUrl.indexOf("validator-")).split(":")[0]
                    : "leader-" + leaderUrl.hashCode();
                
                if (!registeredValidators.containsKey(leaderId)) {
                    ValidatorRegistration leaderReg = new ValidatorRegistration(leaderId, leaderUrl);
                    leaderReg.updateStatus(ValidatorRegistration.Status.READY);
                    registeredValidators.put(leaderId, leaderReg);
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
        
        /**
         * Handle POST /v1/consensus/peer-joined - A validator broadcasts its presence
         * 
         * Called when a validator completes bootstrap and is ready to join consensus.
         * The receiving validator adds the new peer to its consensus engine.
         */
        private void handlePeerJoined(HttpServletRequest request, HttpServletResponse response) throws IOException {
            try {
                // Read JSON body
                StringBuilder json = new StringBuilder();
                java.io.BufferedReader reader = request.getReader();
                String line;
                while ((line = reader.readLine()) != null) {
                    json.append(line);
                }
                
                String body = json.toString();
                
                // Extract fields
                String validatorId = extractJsonField(body, "validatorId");
                String validatorUrl = extractJsonField(body, "validatorUrl");
                
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
                String proofJson = extractJsonObject(body, "proof");
                if (proofJson != null && !proofJson.isEmpty() && proofVerifier != null) {
                    try {
                        org.apache.jackrabbit.oak.segment.consensus.security.JoinProof proof = 
                            org.apache.jackrabbit.oak.segment.consensus.security.JoinProof.fromJson(proofJson);
                        
                        org.apache.jackrabbit.oak.segment.consensus.security.ProofVerifier.VerificationResult result = 
                            proofVerifier.verify(proof);
                        
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
                registeredValidators.putIfAbsent(validatorId, registration);
                log.info("✅ Validator registered in HTTP server (status: READY)");
                
                // PHASE 3: Exchange public keys for Byzantine fault tolerance
                String incomingPublicKey = extractJsonField(body, "publicKey");
                if (incomingPublicKey != null && leaderConsensusEngine != null) {
                    leaderConsensusEngine.getClaimVerifier().registerPublicKey(validatorUrl, incomingPublicKey);
                    log.info("🔑 Registered public key from {}", validatorUrl);
                }
                
                // Update consensus engine
                if (leaderConsensusEngine != null) {
                    leaderConsensusEngine.addPeer(validatorUrl);
                    log.info("✅ Peer added to leader consensus engine");
                } else if (dagConsensusEngine != null) {
                    log.warn("⚠️  DAG consensus doesn't support dynamic peers yet");
                } else {
                    log.warn("⚠️  No consensus engine to update");
                }
                
                log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                log.info("✅ Peer join processed successfully");
                log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                
                // PHASE 3: Send our public key back to the joining validator
                String ourPublicKey = leaderConsensusEngine != null ? 
                    leaderConsensusEngine.getPublicKeyHex() : "";
                
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
        private void handleLeadershipClaim(HttpServletRequest request, HttpServletResponse response) throws IOException {
            try {
                // Read JSON body
                StringBuilder json = new StringBuilder();
                java.io.BufferedReader reader = request.getReader();
                String line;
                while ((line = reader.readLine()) != null) {
                    json.append(line);
                }
                
                String body = json.toString();
                
                // Extract fields (PHASE 3: Now includes signature)
                String epochStr = extractJsonField(body, "epoch");
                String validatorId = extractJsonField(body, "validatorId");
                String validatorUrl = extractJsonField(body, "validatorUrl");
                String timestampStr = extractJsonField(body, "timestamp");
                String claimType = extractJsonField(body, "claimType");
                String signature = extractJsonField(body, "signature");  // PHASE 3
                
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
                
                // Delegate to consensus engine for validation (PHASE 3: includes signature)
                if (leaderConsensusEngine != null) {
                    boolean accepted = leaderConsensusEngine.handleLeadershipClaim(
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
        private void handleClaimAck(HttpServletRequest request, HttpServletResponse response) throws IOException {
            try {
                // Read JSON body
                StringBuilder json = new StringBuilder();
                java.io.BufferedReader reader = request.getReader();
                String line;
                while ((line = reader.readLine()) != null) {
                    json.append(line);
                }
                
                String body = json.toString();
                
                // Extract fields
                String epochStr = extractJsonField(body, "epoch");
                String claimantUrl = extractJsonField(body, "claimantUrl");
                String ackValidatorUrl = extractJsonField(body, "ackValidatorUrl");
                String timestampStr = extractJsonField(body, "timestamp");
                String signature = extractJsonField(body, "signature");
                
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
                if (leaderConsensusEngine != null) {
                    boolean accepted = leaderConsensusEngine.handleClaimAck(
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
        private void handlePublicKeyRegistration(HttpServletRequest request, HttpServletResponse response) throws IOException {
            try {
                // Read JSON body
                StringBuilder json = new StringBuilder();
                java.io.BufferedReader reader = request.getReader();
                String line;
                while ((line = reader.readLine()) != null) {
                    json.append(line);
                }
                
                String body = json.toString();
                
                // Extract fields
                String validatorUrl = extractJsonField(body, "validatorUrl");
                String publicKeyHex = extractJsonField(body, "publicKey");
                
                if (validatorUrl == null || publicKeyHex == null) {
                    response.sendError(HttpServletResponse.SC_BAD_REQUEST, 
                        "Missing required fields: validatorUrl, publicKey");
                    return;
                }
                
                log.info("🔑 PUBLIC KEY REGISTRATION");
                log.info("   Validator: {}", validatorUrl);
                log.info("   Key: {}...", publicKeyHex.substring(0, Math.min(18, publicKeyHex.length())));
                
                // Register with consensus engine
                if (leaderConsensusEngine != null) {
                    leaderConsensusEngine.getClaimVerifier().registerPublicKey(validatorUrl, publicKeyHex);
                    
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
        
        // Simple JSON parsing for Phase 1
        
        private WriteProposal parseProposal(String json) {
            WriteProposal proposal = new WriteProposal();
            
            // Extract fields (simple string parsing for Phase 1)
            proposal.setProposalId(extractJsonField(json, "proposalId"));
            proposal.setProposerUrl(extractJsonField(json, "proposerUrl"));
            proposal.setPreviousHead(extractJsonField(json, "previousHead"));
            proposal.setNewHead(extractJsonField(json, "newHead"));
            proposal.setAuthor(extractJsonField(json, "author"));
            
            String timestamp = extractJsonField(json, "timestamp");
            if (timestamp != null) {
                proposal.setTimestamp(Long.parseLong(timestamp));
            }
            
            String mockPayment = extractJsonField(json, "mockPaymentVerified");
            proposal.setMockPaymentVerified(mockPayment == null || "true".equals(mockPayment));
            
            // TODO: Parse segments array
            
            return proposal;
        }
        
        private Vote parseVote(String json) {
            Vote vote = new Vote();
            
            vote.setProposalId(extractJsonField(json, "proposalId"));
            vote.setValidatorUrl(extractJsonField(json, "validatorUrl"));
            
            String voteType = extractJsonField(json, "voteType");
            vote.setVoteType("ACCEPT".equals(voteType) ? Vote.VoteType.ACCEPT : Vote.VoteType.REJECT);
            
            vote.setReason(extractJsonField(json, "reason"));
            
            String timestamp = extractJsonField(json, "timestamp");
            if (timestamp != null) {
                vote.setTimestamp(Long.parseLong(timestamp));
            }
            
            return vote;
        }
        
        /**
         * Get the next validator in the rotation order for failover.
         * This is used when the current leader appears unreachable.
         * 
         * @return URL of the next validator that might be leader
         */
        private String getNextValidatorInRotation() {
            if (leaderConsensusEngine == null) {
                return selfUrl; // Fallback
            }
            
            // Get all validators in deterministic order
            java.util.List<String> allValidators = new java.util.ArrayList<>();
            allValidators.add(selfUrl);
            allValidators.addAll(leaderConsensusEngine.getElection().getPeerValidators());
            java.util.Collections.sort(allValidators);
            
            // Find current leader in the list
            String currentLeader = leaderConsensusEngine.getCurrentLeader();
            int leaderIndex = allValidators.indexOf(currentLeader);
            
            if (leaderIndex == -1) {
                // Leader not found, return first validator
                return allValidators.get(0);
            }
            
            // Return next validator in rotation (wrap around if needed)
            int nextIndex = (leaderIndex + 1) % allValidators.size();
            return allValidators.get(nextIndex);
        }
        
        private String extractJsonField(String json, String field) {
            int start = json.indexOf("\"" + field + "\"");
            if (start == -1) return null;
            
            start = json.indexOf(":", start) + 1;
            
            // Skip whitespace
            while (start < json.length() && Character.isWhitespace(json.charAt(start))) {
                start++;
            }
            
            // Check if value is quoted (string) or unquoted (number/boolean/null)
            if (json.charAt(start) == '"') {
                // Quoted string
                start++; // Skip opening quote
                int end = json.indexOf("\"", start);
                if (end == -1) return null;
                return json.substring(start, end);
            } else {
                // Unquoted value (number, boolean, or null) - read until comma, }, or ]
                int end = start;
                while (end < json.length()) {
                    char c = json.charAt(end);
                    if (c == ',' || c == '}' || c == ']' || Character.isWhitespace(c)) {
                        break;
                    }
                    end++;
                }
                return json.substring(start, end).trim();
            }
        }
        
        /**
         * Extract a JSON object (not a primitive) from a JSON string.
         * Used to extract nested objects like the proof.
         */
        private String extractJsonObject(String json, String field) {
            String pattern = "\"" + field + "\":";
            int start = json.indexOf(pattern);
            if (start == -1) return null;
            
            start = json.indexOf("{", start);
            if (start == -1) return null;
            
            // Find matching closing brace
            int depth = 0;
            int end = start;
            while (end < json.length()) {
                char c = json.charAt(end);
                if (c == '{') depth++;
                if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        return json.substring(start, end + 1);
                    }
                }
                end++;
            }
            
            return null;
        }
        
        private String voteToJson(Vote v) {
            return "{" +
                "\"proposalId\":\"" + v.getProposalId() + "\"," +
                "\"validatorUrl\":\"" + v.getValidatorUrl() + "\"," +
                "\"voteType\":\"" + v.getVoteType() + "\"," +
                "\"reason\":\"" + (v.getReason() != null ? v.getReason() : "") + "\"," +
                "\"timestamp\":" + v.getTimestamp() +
                "}";
        }
    }
}

