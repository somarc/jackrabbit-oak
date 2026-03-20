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
import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.apache.jackrabbit.oak.segment.http.server.model.ValidatorRegistration;
import org.apache.jackrabbit.oak.segment.http.server.util.ApiErrorUtil;
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
    private final TlsConfiguration tlsConfig;
    private final JoinProofFactory joinProofFactory;
    private final PeerUrlResolver peerUrlResolver;
    private final PeerJsonHttpClient peerJsonHttpClient;
    
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
        this(storeDirectory, port, fileStore, nodeStore, new TlsConfiguration());
    }
    
    /**
     * Create a new HTTP server with TLS configuration.
     * 
     * @param storeDirectory The segment store directory path
     * @param port The HTTP port to listen on
     * @param fileStore The Oak FileStore instance
     * @param nodeStore The Oak NodeStore instance
     * @param tlsConfig TLS configuration (use TlsConfiguration.builder() to create)
     */
    public SegmentHttpServer(File storeDirectory, int port, FileStore fileStore, NodeStore nodeStore, 
                            TlsConfiguration tlsConfig) {
        this.storeDirectory = storeDirectory.toPath();
        this.fileStore = fileStore;  // Use existing FileStore!
        this.nodeStore = nodeStore;  // Use existing NodeStore!
        this.tlsConfig = tlsConfig;
        
        // Create ServerContext with initial values
        String scheme = tlsConfig.isEnabled() ? "https" : "http";
        this.context = new ServerContext(fileStore, nodeStore, this.storeDirectory, scheme + "://localhost:" + port);
        
        // Create RequestRouter (will be updated when consensus engines are set)
        this.router = new RequestRouter(context);
        this.joinProofFactory = new JoinProofFactory(fileStore, context, System::currentTimeMillis);
        this.peerUrlResolver = new PeerUrlResolver();
        this.peerJsonHttpClient = new PeerJsonHttpClient();
        
        // Create server - TLS will be configured in start() if enabled
        this.server = new Server();
        this.server.setHandler(new SegmentStoreHandler());
        
        // Configure connectors based on TLS settings
        try {
            if (tlsConfig.isEnabled()) {
                // TLS enabled - configure HTTPS
                int httpsPort = port;
                int httpPort = RuntimeConfigValueResolver.readInt("http.port", 0); // Optional HTTP port for health checks
                tlsConfig.configureServer(server, httpPort, httpsPort);
                log.info("🔒 TLS enabled - HTTPS on port {}", httpsPort);
            } else {
                // No TLS - configure HTTP only
                org.eclipse.jetty.server.ServerConnector connector = 
                    new org.eclipse.jetty.server.ServerConnector(server);
                connector.setPort(port);
                server.addConnector(connector);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to configure TLS", e);
        }
        
        // Initialize Prometheus metrics (JVM metrics: memory, GC, threads, etc.)
        DefaultExports.initialize();
        
        log.info("Initialized SegmentHttpServer");
        log.info("   - Port: {}", port);
        log.info("   - Store: {}", this.storeDirectory);
        log.info("   - TLS: {}", tlsConfig.isEnabled() ? "enabled" : "disabled");
        log.info("   - Prometheus metrics enabled at /metrics");
    }
    
    /**
     * Set the Aeron Cluster consensus engine (Raft-based mode).
     * Must be called before start() if Aeron Cluster consensus is needed.
     */
    public void setAeronConsensusEngine(org.apache.jackrabbit.oak.segment.consensus.aeron.AeronConsensusEngine engine) {
        context.setAeronConsensusEngine(engine);
        log.info("✈️  Aeron Cluster consensus engine configured");
    }
    
    public void setAeronWriteClient(org.apache.jackrabbit.oak.segment.consensus.aeron.AeronWriteClient aeronWriteClient) {
        context.setAeronWriteClient(aeronWriteClient);
        log.info("✈️  AeronWriteClient configured");
    }
    
    public void setAeronClusterLauncher(org.apache.jackrabbit.oak.segment.consensus.aeron.AeronClusterLauncher aeronClusterLauncher) {
        context.setAeronClusterLauncher(aeronClusterLauncher);
        log.info("✈️  AeronClusterLauncher configured (for health checks and metrics)");
    }
    
    /**
     * Get the ConsensusApiHandler instance (for setting up write callbacks).
     */
    public org.apache.jackrabbit.oak.segment.http.server.handlers.ConsensusApiHandler getConsensusApiHandler() {
        return router.getConsensusApiHandler();
    }
    
    /**
     * Get the ServerContext instance (for accessing shared state).
     */
    public ServerContext getContext() {
        return context;
    }
    
    /**
     * Set the self URL for this validator (used for correct display in dashboard).
     */
    public void setSelfUrl(String url) {
        context.setSelfUrl(url);
        log.info("Self URL set to: {}", url);
    }

    /**
     * Register only this validator in local HTTP context state.
     *
     * <p>Use this in Aeron mode where cluster membership is managed by Raft,
     * but local compatibility state (health/metrics/peer views) still needs
     * the self validator entry.
     */
    public void registerSelfValidator(String validatorId) {
        if (validatorId == null || validatorId.isEmpty()) {
            log.warn("⚠️  Skipping self registration: validatorId missing");
            return;
        }
        ValidatorRegistration selfReg = new ValidatorRegistration(validatorId, context.selfUrl);
        selfReg.updateStatus(ValidatorRegistration.Status.READY);
        context.registeredValidators.put(validatorId, selfReg);
        log.info("✅ Self registered (local context): {} ({})", validatorId, context.selfUrl);
    }
    
    /**
     * Register this validator with peer validators.
     * Called during startup to announce this validator's presence to the network.
     * 
     * <p>Uses retry logic with exponential backoff to handle timing issues when
     * peers aren't ready yet (common in Docker Compose startup scenarios).
     * 
     * <p>Uses IP-based URLs for reliable Docker networking (DNS can be unreliable).
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
        
        // ✈️ AERON MODE: Skip peer registration entirely - Aeron Cluster handles membership via Raft
        // This HTTP registration is legacy from EpochLeaderEngine and not needed for Aeron
        // Check if Aeron consensus engine is active (if so, skip peer registration)
        if (context.aeronConsensusEngine != null) {
            log.debug("✈️  Skipping HTTP peer registration (Aeron Cluster handles membership via Raft)");
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
            
            // Convert hostname URL to IP-based URL for reliable Docker networking
            String peerUrlIP = peerUrlResolver.resolve(peerUrl);
            
            boolean registered = false;
            for (int attempt = 1; attempt <= maxRetries; attempt++) {
                try {
                    // Build registration URL (using IP-based URL)
                    String registrationUrl = peerUrlIP + "/v1/register-validator";
                    
                    // Build JSON payload
                    String jsonPayload = String.format(
                        "{\"validatorId\":\"%s\",\"validatorUrl\":\"%s\"}",
                        validatorId.replace("\"", "\\\""),
                        context.selfUrl.replace("\"", "\\\"")
                    );
                    
                    int responseCode = peerJsonHttpClient.postJson(registrationUrl, jsonPayload).getResponseCode();
                    if (responseCode == 200) {
                        log.info("✅ Registered with peer validator: {} → {} (attempt {}/{})", peerUrl, peerUrlIP, attempt, maxRetries);
                        registered = true;
                        break; // Success - exit retry loop
                    } else {
                        log.debug("⚠️  Registration attempt {}/{} failed for {}: HTTP {}", attempt, maxRetries, peerUrlIP, responseCode);
                    }
                    
                } catch (Exception e) {
                    log.debug("⚠️  Registration attempt {}/{} failed for {}: {}", attempt, maxRetries, peerUrlIP, e.getMessage());
                }
                
                // Exponential backoff: 2s, 4s, 8s, 16s, 16s
                if (attempt < maxRetries) {
                    int delayMs = Math.min(initialDelayMs * (1 << (attempt - 1)), maxDelayMs);
                    try {
                        log.debug("⏳ Retrying registration with {} in {}ms...", peerUrlIP, delayMs);
                        Thread.sleep(delayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.warn("⚠️  Registration retry interrupted");
                        break;
                    }
                }
            }
            
            if (!registered) {
                // Note: Registration failure is non-critical - Aeron Cluster handles consensus independently
                // This is mainly for HTTP segment transfer coordination, which can retry later
                log.debug("⚠️  Failed to register with peer validator {} ({} → {}) after {} attempts (non-critical - Aeron Cluster handles consensus)", 
                    peerUrl, peerUrlIP, peerUrlIP, maxRetries);
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
            joinProofFactory.create(validatorId, validatorUrl);
        
        int successCount = 0;
        int failureCount = 0;
        
        for (String peerUrl : peerUrls) {
            try {
                // Skip self
                if (peerUrl.equals(validatorUrl)) {
                    log.debug("   Skipping self: {}", peerUrl);
                    continue;
                }
                
                // Convert hostname URL to IP-based URL for reliable Docker networking
                String peerUrlIP = peerUrlResolver.resolve(peerUrl);
                
                // Build peer-joined endpoint URL (using IP-based URL)
                String peerJoinedUrl = peerUrlIP + "/v1/consensus/peer-joined";
                
                // PHASE 3: Get public key for Byzantine fault tolerance
                // Note: Public key exchange now handled via Aeron cluster membership
                String publicKeyHex = "";
                
                // Build JSON payload with proof and public key (PHASE 3)
                String jsonPayload = String.format(
                    "{\"validatorId\":\"%s\",\"validatorUrl\":\"%s\",\"proof\":%s,\"publicKey\":\"%s\"}",
                    validatorId.replace("\"", "\\\""),
                    validatorUrl.replace("\"", "\\\""),
                    proof.toJson(),
                    publicKeyHex
                );
                
                log.info("   → Broadcasting to {} → {} (with public key)", peerUrl, peerUrlIP);
                
                PeerJsonHttpClient.PostResult result = peerJsonHttpClient.postJson(peerJoinedUrl, jsonPayload);
                int responseCode = result.getResponseCode();
                
                if (responseCode == 200) {
                    String response = result.getResponseBody();
                    
                    log.info("   ✅ Accepted by peer: {} → {}", peerUrl, peerUrlIP);
                    log.debug("      Response: {}", response);
                    
                    // Note: Public key exchange now handled via Aeron cluster membership
                    
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
     * 
     * Supports multipart/form-data for binary uploads (up to 100MB per file, 200MB total).
     */
    private class SegmentStoreHandler extends AbstractHandler {
        
        // Multipart config for file uploads: 100MB max file, 200MB max request, 1MB threshold
        private final javax.servlet.MultipartConfigElement multipartConfig = 
            new javax.servlet.MultipartConfigElement(
                System.getProperty("java.io.tmpdir"),  // temp dir
                100 * 1024 * 1024,  // maxFileSize: 100MB
                200 * 1024 * 1024,  // maxRequestSize: 200MB
                1024 * 1024         // fileSizeThreshold: 1MB (files larger go to disk)
            );
        
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request,
                          HttpServletResponse response) throws IOException, ServletException {
            
            String path = request.getPathInfo();
            String method = request.getMethod();
            
            // Enable multipart parsing for POST requests with multipart content
            String contentType = request.getContentType();
            if ("POST".equals(method) && contentType != null && 
                contentType.toLowerCase().startsWith("multipart/")) {
                // Set multipart config on the request for Jetty to parse multipart data
                request.setAttribute("org.eclipse.jetty.multipartConfig", multipartConfig);
            }
            
            log.debug("HTTP {} {}", method, path);
            
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            // CORS Headers - Permissive for local development and demos
            // NOTE: Wallet-based auth happens at proposal level (signature verification),
            // not at CORS level. Wildcard CORS is intentional for API accessibility.
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            response.setHeader("Access-Control-Allow-Origin", "*");
            response.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, OPTIONS");
            response.setHeader("Access-Control-Allow-Headers", "Content-Type, Authorization");
            response.setHeader("Access-Control-Max-Age", "3600");
            
            // Handle preflight OPTIONS requests
            if ("OPTIONS".equals(method)) {
                response.setStatus(HttpServletResponse.SC_OK);
                baseRequest.setHandled(true);
                return;
            }
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            
            try {
                // Delegate to RequestRouter
                router.route(baseRequest, request, response);
            } catch (Exception e) {
                log.error("Error handling request {} {}", method, path, e);
                if (!response.isCommitted()) {
                    ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                        "Internal server error: " + e.getMessage());
                }
            }
        }
    }
}
