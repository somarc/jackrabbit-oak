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

import org.apache.jackrabbit.oak.segment.http.server.handlers.*;
import org.eclipse.jetty.server.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Central request router that delegates HTTP requests to appropriate handlers.
 * 
 * <p>This class replaces the large if-else chain in SegmentHttpServer with
 * a cleaner routing mechanism that delegates to specialized handler classes.</p>
 */
public class RequestRouter {

    private static final Logger log = LoggerFactory.getLogger(RequestRouter.class);

    private final HealthHandler healthHandler;
    private final MetricsHandler metricsHandler;
    private final FileHandler fileHandler;
    private final ExplorerApiHandler explorerApiHandler;
    private final DashboardHandler dashboardHandler;
    private final ConsensusApiHandler consensusApiHandler;
    private final RegistrationHandler registrationHandler;
    private final PeerDiscoveryHandler peerDiscoveryHandler;
    private final AeronApiHandler aeronApiHandler;
    private final FragmentationApiHandler fragmentationApiHandler;
    private final LeaderConsensusHandler leaderConsensusHandler;
    private final BinaryUploadHandler binaryUploadHandler;
    private final CidApiHandler cidApiHandler;
    private volatile Object chatHandler; // Optional - from oak-segment-agentic module (lazy initialized)
    private final AuthTokenValidator authValidator;
    
    private final ServerContext context;

    public RequestRouter(ServerContext context) {
        this.context = context;
        this.authValidator = new AuthTokenValidator();
        
        // Initialize all handlers
        this.healthHandler = new HealthHandler(
            context.fileStore,
            context.nodeStore,
            context.storeDirectory,
            context.epochLeaderEngine,
            context.aeronConsensusEngine,
            context.registeredClients,
            context.registeredValidators,
            context
        );
        this.metricsHandler = new MetricsHandler(
            context.epochLeaderEngine,
            context.aeronConsensusEngine,
            context.storeDirectory,
            context.registeredClients,
            context.registeredValidators,
            context
        );
        this.fileHandler = new FileHandler(
            context.fileStore,
            context.storeDirectory,
            context.connectedPeers
        );
        this.explorerApiHandler = new ExplorerApiHandler(
            context.nodeStore,
            context.storeDirectory
        );
        this.dashboardHandler = new DashboardHandler(context);
        this.consensusApiHandler = new ConsensusApiHandler(context);
        this.registrationHandler = new RegistrationHandler(context);
        this.peerDiscoveryHandler = new PeerDiscoveryHandler(context);
        this.aeronApiHandler = new AeronApiHandler(context);
        this.fragmentationApiHandler = new FragmentationApiHandler(context);
        this.leaderConsensusHandler = new LeaderConsensusHandler(context);
        
        // Binary upload handler (ADR 020 - lazy upload on confirmation)
        org.apache.jackrabbit.oak.segment.http.server.binary.UploadSessionManager sessionManager = 
            new org.apache.jackrabbit.oak.segment.http.server.binary.UploadSessionManager();
        this.binaryUploadHandler = new BinaryUploadHandler(sessionManager);
        
        // Make session manager available in context for dashboard metrics
        context.setUploadSessionManager(sessionManager);
        
        // CID API handler (Oak ↔ IPFS CID mapping)
        this.cidApiHandler = new CidApiHandler(context);
        
        // Chat handler will be initialized lazily on first use (after selfUrl is set)
        this.chatHandler = null;
    }
    
    /**
     * Initialize chat handler if oak-segment-agentic module is available.
     * Returns null if module is not available (graceful degradation).
     */
    private Object initializeChatHandler(ServerContext context) {
        try {
            // Use reflection to avoid hard dependency on oak-segment-agentic
            Class<?> llmServiceImplClass = Class.forName("org.apache.jackrabbit.oak.segment.agentic.llm.OllamaLLMService");
            Class<?> ragServiceClass = Class.forName("org.apache.jackrabbit.oak.segment.agentic.rag.RAGService");
            Class<?> chatHandlerClass = Class.forName("org.apache.jackrabbit.oak.segment.agentic.chat.ChatHandler");
            
            // Get the LLMService interface (parent of OllamaLLMService)
            Class<?> llmServiceInterface = Class.forName("org.apache.jackrabbit.oak.segment.agentic.llm.LLMService");
            
            // Create LLM service instance
            Object llmService = llmServiceImplClass.getDeclaredConstructor().newInstance();
            
            // Create RAG service instance
            Object ragService = ragServiceClass.getDeclaredConstructor().newInstance();
            
            // Create chat handler - use interface type for constructor lookup
            // Try to get selfUrl from context, or infer from system properties
            String baseUrl = context.selfUrl;
            if (baseUrl == null || baseUrl.isEmpty()) {
                // Try to get from system property (set by validator startup script)
                baseUrl = System.getProperty("consensus.self.url");
                if (baseUrl == null || baseUrl.isEmpty()) {
                    // Last resort: default to localhost:8090
                    baseUrl = "http://localhost:8090";
                }
            }
            
            // Set wallet address system property for agent identification (if available)
            // Validators use wallet address (0x...) as their agent ID for provable identity
            if (context.myValidatorId != null && !context.myValidatorId.isEmpty()) {
                System.setProperty("wallet.address", context.myValidatorId);
            }
            
            Object chatHandler = chatHandlerClass.getConstructor(
                llmServiceInterface,
                ragServiceClass,
                String.class
            ).newInstance(llmService, ragService, baseUrl);
            
            String walletInfo = context.myValidatorId != null ? " (wallet: " + context.myValidatorId + ")" : "";
            log.info("✅ LLM Chat handler initialized (oak-segment-agentic module available) with baseUrl: {}{}", baseUrl, walletInfo);
            return chatHandler;
        } catch (ClassNotFoundException e) {
            log.debug("oak-segment-agentic module not available - chat endpoint disabled");
            return null;
        } catch (Exception e) {
            log.warn("Failed to initialize chat handler", e);
            return null;
        }
    }

    /**
     * Get the ConsensusApiHandler instance.
     */
    public ConsensusApiHandler getConsensusApiHandler() {
        return consensusApiHandler;
    }
    
    /**
     * Route a request to the appropriate handler based on path and method.
     * 
     * @param baseRequest Jetty base request
     * @param request HTTP servlet request
     * @param response HTTP servlet response
     * @throws IOException if an I/O error occurs
     */
    public void route(Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException {
        String path = request.getRequestURI();
        String method = request.getMethod();
        
        try {
            // Health checks (always public - needed for monitoring/load balancers)
            if ("/health".equals(path) && "GET".equals(method)) {
                healthHandler.handleHealth(response);
                baseRequest.setHandled(true);
                return;
            }
            
            if ("/health/deep".equals(path) && "GET".equals(method)) {
                healthHandler.handleDeepHealth(response);
                baseRequest.setHandled(true);
                return;
            }
            
            // Validate authentication for all other endpoints (if auth is enabled)
            // If auth is disabled (no token configured), this allows all requests (POC mode)
            if (!authValidator.validateRequest(request, response)) {
                baseRequest.setHandled(true);
                return; // Response already sent by validateRequest
            }
            
            // Dashboard and UI
            if ("/".equals(path) || "/dashboard".equals(path)) {
                if ("GET".equals(method)) {
                    dashboardHandler.handleDashboard(response);
                    baseRequest.setHandled(true);
                    return;
                }
            }
            
            if ("/explorer".equals(path) && "GET".equals(method)) {
                dashboardHandler.handleExplorerUI(response);
                baseRequest.setHandled(true);
                return;
            }
            
            if ("/api-browser".equals(path) && "GET".equals(method)) {
                dashboardHandler.handleApiBrowserUI(response);
                baseRequest.setHandled(true);
                return;
            }
            
            if ("/chat".equals(path) && "GET".equals(method)) {
                dashboardHandler.handleChatUI(response);
                baseRequest.setHandled(true);
                return;
            }
            
            // File serving
            if ("/journal.log".equals(path) && "GET".equals(method)) {
                fileHandler.handleFile(request, response, "journal.log", "text/plain");
                baseRequest.setHandled(true);
                return;
            }
            
            if ("/manifest".equals(path)) {
                if ("HEAD".equals(method)) {
                    fileHandler.handleFileHead(response, "manifest", "text/plain");
                } else if ("GET".equals(method)) {
                    fileHandler.handleFile(request, response, "manifest", "text/plain");
                } else {
                    response.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
                }
                baseRequest.setHandled(true);
                return;
            }
            
            if ("/gc.log".equals(path) && "GET".equals(method)) {
                fileHandler.handleFile(request, response, "gc.log", "text/plain");
                baseRequest.setHandled(true);
                return;
            }
            
            // Segments
            if (path != null && path.startsWith("/segments/")) {
                String segmentId = path.substring("/segments/".length());
                if ("HEAD".equals(method)) {
                    fileHandler.handleSegmentHead(response, segmentId);
                } else if ("GET".equals(method)) {
                    fileHandler.handleSegmentGet(request, response, segmentId);
                } else {
                    response.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
                }
                baseRequest.setHandled(true);
                return;
            }
            
            // Explorer API
            if ("/api/explore".equals(path) && "GET".equals(method)) {
                String nodePath = request.getParameter("path");
                explorerApiHandler.handleExploreNode(response, nodePath != null ? nodePath : "/");
                baseRequest.setHandled(true);
                return;
            }
            
            if ("/api/segments/recent".equals(path) && "GET".equals(method)) {
                explorerApiHandler.handleRecentSegments(response);
                baseRequest.setHandled(true);
                return;
            }
            
            if ("/api/segments/tars".equals(path) && "GET".equals(method)) {
                explorerApiHandler.handleTarFiles(response);
                baseRequest.setHandled(true);
                return;
            }
            
            // CID API (Oak blob ID ↔ IPFS CID mapping)
            if ("/api/cid/stats".equals(path) && "GET".equals(method)) {
                cidApiHandler.handleStats(request, response);
                baseRequest.setHandled(true);
                return;
            }
            if (path.startsWith("/api/cid/gateway/") && "GET".equals(method)) {
                cidApiHandler.handleGatewayRedirect(request, response);
                baseRequest.setHandled(true);
                return;
            }
            if (path.startsWith("/api/cid/reverse/") && "GET".equals(method)) {
                cidApiHandler.handleReverseLookup(request, response);
                baseRequest.setHandled(true);
                return;
            }
            if (path.startsWith("/api/cid/") && "GET".equals(method)) {
                cidApiHandler.handleGetCid(request, response);
                baseRequest.setHandled(true);
                return;
            }
            
            // Metrics
            if ("/api/metrics".equals(path) && "GET".equals(method)) {
                metricsHandler.handleMetrics(response);
                baseRequest.setHandled(true);
                return;
            }
            
            if ("/metrics".equals(path) && "GET".equals(method)) {
                metricsHandler.handlePrometheusMetrics(response);
                baseRequest.setHandled(true);
                return;
            }
            
            // Consensus API
            if ("/v1/propose-write".equals(path) && "POST".equals(method)) {
                // Phase 1: Optional shard routing logging (for demonstration)
                // Phase 2: Will actually forward requests to correct shard
                if (context.shardRouter != null) {
                    String walletAddress = request.getParameter("walletAddress");
                    if (walletAddress == null || walletAddress.isEmpty()) {
                        walletAddress = request.getParameter("wallet"); // Fallback
                    }
                    if (walletAddress != null && !walletAddress.isEmpty()) {
                        try {
                            String leaderUrl = context.shardRouter.routeRequest(walletAddress);
                            if (leaderUrl != null) {
                                log.debug("🔀 Shard routing: wallet {} → leader {}", walletAddress, leaderUrl);
                                // Phase 1: Log routing decision (all requests still process locally)
                                // Phase 2: Forward to leaderUrl if different from selfUrl
                            }
                        } catch (Exception e) {
                            log.debug("Shard routing check failed: {}", e.getMessage());
                        }
                    }
                }
                consensusApiHandler.handleProposeWrite(request, response);
                baseRequest.setHandled(true);
                return;
            }
            
            // Delete Proposal API
            if ("/v1/propose-delete".equals(path) && "POST".equals(method)) {
                consensusApiHandler.handleDeleteProposal(request, response);
                baseRequest.setHandled(true);
                return;
            }
            
            // Binary Upload API (ADR 020 - Lazy upload on confirmation)
            if ("/v1/binary/declare-intent".equals(path) && "POST".equals(method)) {
                binaryUploadHandler.handleDeclareIntent(request, response);
                baseRequest.setHandled(true);
                return;
            }
            
            if (path.startsWith("/v1/binary/check-intent/") && "GET".equals(method)) {
                binaryUploadHandler.handleCheckIntent(request, response);
                baseRequest.setHandled(true);
                return;
            }
            
            if ("/v1/binary/complete-upload".equals(path) && "POST".equals(method)) {
                binaryUploadHandler.handleCompleteUpload(request, response);
                baseRequest.setHandled(true);
                return;
            }
            
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            // Mock Epoch Control API (only works in MOCK mode)
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            if ("/api/mock/advance-epoch".equals(path) && "POST".equals(method)) {
                handleMockAdvanceEpoch(request, response);
                baseRequest.setHandled(true);
                return;
            }
            
            if ("/api/mock/set-epoch-offset".equals(path) && "POST".equals(method)) {
                handleMockSetEpochOffset(request, response);
                baseRequest.setHandled(true);
                return;
            }
            
            if ("/api/mock/epoch-status".equals(path) && "GET".equals(method)) {
                handleMockEpochStatus(request, response);
                baseRequest.setHandled(true);
                return;
            }
            
            // HEAD endpoint - returns JSON with committedHead vs latestHead
            if ("/v1/head".equals(path) && "GET".equals(method)) {
                response.setContentType("application/json");
                response.setStatus(HttpServletResponse.SC_OK);
                
                StringBuilder json = new StringBuilder();
                json.append("{\n");
                
                // 🔄 CRITICAL: Get HEAD from AeronConsensusEngine first (tracks latest HEAD correctly)
                // Fallback to FileStore only if Aeron engine not available
                String latestHead = null;
                String committedHead = null;
                int latestEpochSeen = -1;
                int committedEpoch = -1;
                
                if (context.aeronConsensusEngine != null) {
                    // Get tracked HEAD values from AeronConsensusEngine (most accurate)
                    latestHead = context.aeronConsensusEngine.getLatestHead();
                    committedHead = context.aeronConsensusEngine.getCommittedHead();
                    latestEpochSeen = context.aeronConsensusEngine.getLatestEpochSeen();
                    committedEpoch = context.aeronConsensusEngine.getLastCommittedEpoch();
                }
                
                // Fallback to FileStore HEAD if Aeron engine not available or latestHead not set
                if (latestHead == null || latestHead.isEmpty()) {
                    latestHead = context.fileStore.getHead().getRecordId().toString10();
                }
                
                // Use latestHead as committedHead fallback if no committedHead set
                if (committedHead == null || committedHead.isEmpty()) {
                    committedHead = latestHead;
                }
                
                json.append("  \"latestHead\": \"").append(latestHead).append("\",\n");
                json.append("  \"committedHead\": \"").append(committedHead).append("\"");
                
                if (latestEpochSeen >= 0) {
                    json.append(",\n  \"latestEpochSeen\": ").append(latestEpochSeen);
                }
                if (committedEpoch >= 0) {
                    json.append(",\n  \"committedEpoch\": ").append(committedEpoch);
                }
                
                json.append("\n}\n");
                response.getWriter().write(json.toString());
                baseRequest.setHandled(true);
                return;
            }
            
            if ("/v1/consensus/status".equals(path) && "GET".equals(method)) {
                consensusApiHandler.handleGetConsensusStatus(response);
                baseRequest.setHandled(true);
                return;
            }
            
            // Query APIs
            if ("/v1/wallets/stats".equals(path) && "GET".equals(method)) {
                consensusApiHandler.handleWalletStats(request, response);
                baseRequest.setHandled(true);
                return;
            }
            
            if ("/v1/wallets/content".equals(path) && "GET".equals(method)) {
                consensusApiHandler.handleWalletContent(request, response);
                baseRequest.setHandled(true);
                return;
            }
            
            // Follower HEAD update endpoint (used by leader to broadcast HEAD to followers)
            if ("/v1/follower/head-update".equals(path) && "POST".equals(method)) {
                leaderConsensusHandler.handleFollowerHeadUpdate(request, response);
                baseRequest.setHandled(true);
                return;
            }
            
            // GC Cost Estimation
            if ("/v1/gc/estimate".equals(path) && "GET".equals(method)) {
                consensusApiHandler.handleGCCostEstimate(request, response);
                baseRequest.setHandled(true);
                return;
            }
            
            // Proposal Queue Status
            if (path.startsWith("/v1/proposals/") && path.endsWith("/status") && "GET".equals(method)) {
                consensusApiHandler.handleGetProposalStatus(request, response);
                baseRequest.setHandled(true);
                return;
            }
            
            if ("/v1/proposals/pending/count".equals(path) && "GET".equals(method)) {
                consensusApiHandler.handleGetPendingCount(response);
                baseRequest.setHandled(true);
                return;
            }
            
            // Registration
            if ("/v1/register-client".equals(path) && ("POST".equals(method) || "PUT".equals(method))) {
                registrationHandler.handleClientRegistration(request, response);
                baseRequest.setHandled(true);
                return;
            }
            
            // Peer discovery
            if ("/v1/peers".equals(path) && "GET".equals(method)) {
                peerDiscoveryHandler.handlePeerList(response);
                baseRequest.setHandled(true);
                return;
            }
            
            if ("/v1/ngrok-url".equals(path) && "GET".equals(method)) {
                peerDiscoveryHandler.handleNgrokUrl(response);
                baseRequest.setHandled(true);
                return;
            }
            
            // Blockchain configuration endpoint
            if ("/v1/blockchain/config".equals(path) && "GET".equals(method)) {
                new BlockchainConfigApiHandler(context).handle(response);
                baseRequest.setHandled(true);
                return;
            }
            
            // Aeron Cluster-specific endpoints
            if ("/v1/aeron/cluster-state".equals(path) && "GET".equals(method)) {
                aeronApiHandler.handleClusterState(response);
                baseRequest.setHandled(true);
                return;
            }
            
            if ("/v1/aeron/raft-metrics".equals(path) && "GET".equals(method)) {
                aeronApiHandler.handleRaftMetrics(response);
                baseRequest.setHandled(true);
                return;
            }
            
            if ("/v1/aeron/node-status".equals(path) && "GET".equals(method)) {
                aeronApiHandler.handleNodeStatus(request, response);
                baseRequest.setHandled(true);
                return;
            }
            
            if ("/v1/aeron/leadership-history".equals(path) && "GET".equals(method)) {
                aeronApiHandler.handleLeadershipHistory(request, response);
                baseRequest.setHandled(true);
                return;
            }
            
            // Fragmentation & GC Metrics API
            if ("/v1/fragmentation/metrics".equals(path) && "GET".equals(method)) {
                fragmentationApiHandler.handleGetAllMetrics(request, response);
                baseRequest.setHandled(true);
                return;
            }
            
            if (path != null && path.startsWith("/v1/fragmentation/metrics/") && "GET".equals(method)) {
                String walletAddress = path.substring("/v1/fragmentation/metrics/".length());
                fragmentationApiHandler.handleGetEntityMetrics(request, response, walletAddress);
                baseRequest.setHandled(true);
                return;
            }
            
            if ("/v1/fragmentation/top".equals(path) && "GET".equals(method)) {
                fragmentationApiHandler.handleGetTopFragmented(request, response);
                baseRequest.setHandled(true);
                return;
            }
            
            if ("/v1/gc/status".equals(path) && "GET".equals(method)) {
                fragmentationApiHandler.handleGetGcStatus(request, response);
                baseRequest.setHandled(true);
                return;
            }
            
            if ("/v1/compaction/proposals".equals(path) && "GET".equals(method)) {
                fragmentationApiHandler.handleGetCompactionProposals(request, response);
                baseRequest.setHandled(true);
                return;
            }
            
            if ("/v1/propose-gc".equals(path) && "POST".equals(method)) {
                fragmentationApiHandler.handleProposeGC(request, response);
                baseRequest.setHandled(true);
                return;
            }
            
            if ("/v1/gc/execute".equals(path) && "POST".equals(method)) {
                fragmentationApiHandler.handleExecuteGC(request, response);
                baseRequest.setHandled(true);
                return;
            }
            
            // GC Account Management
            if (path != null && path.startsWith("/v1/gc/account/")) {
                // Extract wallet address from path
                String remaining = path.substring("/v1/gc/account/".length());
                
                // Check for sub-paths
                if (remaining.contains("/pay") && "POST".equals(method)) {
                    String walletAddress = remaining.substring(0, remaining.indexOf("/pay"));
                    fragmentationApiHandler.handlePayGCDebt(request, response, walletAddress);
                    baseRequest.setHandled(true);
                    return;
                } else if (remaining.contains("/set-limit") && "POST".equals(method)) {
                    String walletAddress = remaining.substring(0, remaining.indexOf("/set-limit"));
                    fragmentationApiHandler.handleSetDebtLimit(request, response, walletAddress);
                    baseRequest.setHandled(true);
                    return;
                } else if (remaining.contains("/execute-pending") && "POST".equals(method)) {
                    String walletAddress = remaining.substring(0, remaining.indexOf("/execute-pending"));
                    fragmentationApiHandler.handleExecutePendingDebt(request, response, walletAddress);
                    baseRequest.setHandled(true);
                    return;
                } else if ("GET".equals(method) && !remaining.contains("/")) {
                    // GET /v1/gc/account/{walletAddress}
                    fragmentationApiHandler.handleGetGCAccount(request, response, remaining);
                    baseRequest.setHandled(true);
                    return;
                }
            }
            
            // Manual GC trigger endpoint (for testing)
            if ("/v1/gc/trigger".equals(path) && "POST".equals(method)) {
                fragmentationApiHandler.handleTriggerGC(request, response);
                baseRequest.setHandled(true);
                return;
            }
            
            // LLM Chat endpoint (optional - requires oak-segment-agentic module)
            if ("/v1/chat".equals(path) && "POST".equals(method)) {
                // Lazy initialization - chat handler is created on first use (after selfUrl is set)
                if (chatHandler == null) {
                    synchronized (this) {
                        if (chatHandler == null) {
                            chatHandler = initializeChatHandler(context);
                        }
                    }
                }
                
                if (chatHandler != null) {
                    try {
                        // Use reflection to call handleChat method
                        java.lang.reflect.Method handleMethod = chatHandler.getClass()
                            .getMethod("handleChat", HttpServletRequest.class, HttpServletResponse.class);
                        handleMethod.invoke(chatHandler, request, response);
                        baseRequest.setHandled(true);
                        return;
                    } catch (Exception e) {
                        log.error("Error invoking chat handler", e);
                        response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Chat handler error: " + e.getMessage());
                        baseRequest.setHandled(true);
                        return;
                    }
                } else {
                    response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"error\":\"Chat endpoint not available. Install oak-segment-agentic module.\"}");
                    baseRequest.setHandled(true);
                    return;
                }
            }
            
            // Not found - log with context
            String remoteAddr = request.getRemoteAddr();
            String userAgent = request.getHeader("User-Agent");
            
            // Filter out known invalid requests (Composum Browser, etc.) - log at debug level
            if (path != null && (path.startsWith("/bin/") || path.startsWith("/system/") || path.startsWith("/content/"))) {
                // These are Sling/AEM endpoints, not validator endpoints - suppress noise
                log.debug("⚠️  Invalid request (Sling/AEM endpoint on validator): {} {} FROM {} [UA: {}]", 
                    method, path, remoteAddr, userAgent != null ? userAgent : "unknown");
            } else {
                // Unknown endpoint - log at info level
                log.info("⚠️  404 Not Found: {} {} FROM {} [UA: {}]", 
                    method, path, remoteAddr, userAgent != null ? userAgent : "unknown");
            }
            
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            baseRequest.setHandled(true);
            
        } catch (Exception e) {
            log.error("Error routing request: " + path, e);
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
            baseRequest.setHandled(true);
        }
    }
    
    /**
     * Get the binary upload handler (for integration with other components).
     * 
     * @return the binary upload handler
     */
    public BinaryUploadHandler getBinaryUploadHandler() {
        return binaryUploadHandler;
    }
    
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // MOCK EPOCH CONTROL HANDLERS
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    
    /**
     * Advance mock epoch by N epochs (POST /api/mock/advance-epoch?epochs=N)
     */
    private void handleMockAdvanceEpoch(javax.servlet.http.HttpServletRequest request, 
                                        javax.servlet.http.HttpServletResponse response) throws java.io.IOException {
        org.apache.jackrabbit.oak.segment.consensus.config.BlockchainConfig config = 
            org.apache.jackrabbit.oak.segment.consensus.config.BlockchainConfig.getInstance();
        
        if (config.getMode() != org.apache.jackrabbit.oak.segment.consensus.config.BlockchainConfig.Mode.MOCK) {
            response.setStatus(400);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Mock epoch control only available in MOCK mode. Current mode: " + config.getMode() + "\"}");
            return;
        }
        
        int epochs = 1; // Default: advance by 1
        String epochsParam = request.getParameter("epochs");
        if (epochsParam != null) {
            try {
                epochs = Integer.parseInt(epochsParam);
            } catch (NumberFormatException e) {
                response.setStatus(400);
                response.setContentType("application/json");
                response.getWriter().write("{\"error\":\"Invalid epochs parameter: " + epochsParam + "\"}");
                return;
            }
        }
        
        // Get BeaconChainClient from EpochQueue
        if (context.proposalQueueManager != null && context.proposalQueueManager.getEpochQueue() != null) {
            org.apache.jackrabbit.oak.segment.consensus.eth.BeaconChainClient beaconClient = 
                context.proposalQueueManager.getEpochQueue().getBeaconClient();
            if (beaconClient != null) {
                boolean success = beaconClient.advanceMockEpoch(epochs);
                if (success) {
                    response.setContentType("application/json");
                    response.getWriter().write("{\"success\":true,\"advanced\":" + epochs + 
                        ",\"currentEpoch\":" + beaconClient.getCachedCurrentEpoch() + 
                        ",\"finalizedEpoch\":" + beaconClient.getCachedFinalizedEpoch() + "}");
                    return;
                }
            }
        }
        
        response.setStatus(500);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"Failed to advance epoch - BeaconChainClient not available\"}");
    }
    
    /**
     * Set mock epoch offset (POST /api/mock/set-epoch-offset?offset=N)
     */
    private void handleMockSetEpochOffset(javax.servlet.http.HttpServletRequest request, 
                                          javax.servlet.http.HttpServletResponse response) throws java.io.IOException {
        org.apache.jackrabbit.oak.segment.consensus.config.BlockchainConfig config = 
            org.apache.jackrabbit.oak.segment.consensus.config.BlockchainConfig.getInstance();
        
        if (config.getMode() != org.apache.jackrabbit.oak.segment.consensus.config.BlockchainConfig.Mode.MOCK) {
            response.setStatus(400);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Mock epoch control only available in MOCK mode. Current mode: " + config.getMode() + "\"}");
            return;
        }
        
        String offsetParam = request.getParameter("offset");
        if (offsetParam == null) {
            response.setStatus(400);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Missing required parameter: offset\"}");
            return;
        }
        
        long offset;
        try {
            offset = Long.parseLong(offsetParam);
        } catch (NumberFormatException e) {
            response.setStatus(400);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Invalid offset parameter: " + offsetParam + "\"}");
            return;
        }
        
        // Get BeaconChainClient from EpochQueue
        if (context.proposalQueueManager != null && context.proposalQueueManager.getEpochQueue() != null) {
            org.apache.jackrabbit.oak.segment.consensus.eth.BeaconChainClient beaconClient = 
                context.proposalQueueManager.getEpochQueue().getBeaconClient();
            if (beaconClient != null) {
                boolean success = beaconClient.setMockEpochOffset(offset);
                if (success) {
                    response.setContentType("application/json");
                    response.getWriter().write("{\"success\":true,\"offset\":" + offset + 
                        ",\"currentEpoch\":" + beaconClient.getCachedCurrentEpoch() + 
                        ",\"finalizedEpoch\":" + beaconClient.getCachedFinalizedEpoch() + "}");
                    return;
                }
            }
        }
        
        response.setStatus(500);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"Failed to set epoch offset - BeaconChainClient not available\"}");
    }
    
    /**
     * Get mock epoch status (GET /api/mock/epoch-status)
     */
    private void handleMockEpochStatus(javax.servlet.http.HttpServletRequest request, 
                                       javax.servlet.http.HttpServletResponse response) throws java.io.IOException {
        org.apache.jackrabbit.oak.segment.consensus.config.BlockchainConfig config = 
            org.apache.jackrabbit.oak.segment.consensus.config.BlockchainConfig.getInstance();
        
        response.setContentType("application/json");
        
        StringBuilder json = new StringBuilder();
        json.append("{");
        json.append("\"mode\":\"").append(config.getMode()).append("\",");
        
        if (context.proposalQueueManager != null && context.proposalQueueManager.getEpochQueue() != null) {
            org.apache.jackrabbit.oak.segment.consensus.eth.BeaconChainClient beaconClient = 
                context.proposalQueueManager.getEpochQueue().getBeaconClient();
            if (beaconClient != null) {
                java.util.Map<String, Object> health = beaconClient.getHealthStatus();
                json.append("\"currentEpoch\":").append(beaconClient.getCachedCurrentEpoch()).append(",");
                json.append("\"finalizedEpoch\":").append(beaconClient.getCachedFinalizedEpoch()).append(",");
                json.append("\"fresh\":").append(beaconClient.isEpochDataFresh()).append(",");
                json.append("\"timeSinceUpdateMs\":").append(beaconClient.getMillisSinceLastUpdate());
                
                if (config.getMode() == org.apache.jackrabbit.oak.segment.consensus.config.BlockchainConfig.Mode.MOCK) {
                    json.append(",\"mockEpochOffset\":").append(beaconClient.getMockEpochOffset());
                    json.append(",\"mockControls\":{");
                    json.append("\"advanceEpoch\":\"POST /api/mock/advance-epoch?epochs=N\",");
                    json.append("\"setOffset\":\"POST /api/mock/set-epoch-offset?offset=N\"");
                    json.append("}");
                }
            } else {
                json.append("\"error\":\"BeaconChainClient not available\"");
            }
        } else {
            json.append("\"error\":\"ProposalQueueManager or EpochQueue not available\"");
        }
        
        json.append("}");
        response.getWriter().write(json.toString());
    }
}

