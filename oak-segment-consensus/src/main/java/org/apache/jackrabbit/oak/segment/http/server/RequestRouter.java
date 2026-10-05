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

import org.apache.jackrabbit.oak.segment.consensus.aeron.LeaderDiscoveryService;
import org.apache.jackrabbit.oak.segment.consensus.config.RuntimeConfigValueResolver;
import org.apache.jackrabbit.oak.segment.http.server.handlers.AeronApiHandler;
import org.apache.jackrabbit.oak.segment.http.server.handlers.BinaryUploadHandler;
import org.apache.jackrabbit.oak.segment.http.server.handlers.BlockchainConfigApiHandler;
import org.apache.jackrabbit.oak.segment.http.server.handlers.CidApiHandler;
import org.apache.jackrabbit.oak.segment.http.server.handlers.ConsensusApiHandler;
import org.apache.jackrabbit.oak.segment.http.server.handlers.ConsensusStatusHandler;
import org.apache.jackrabbit.oak.segment.http.server.handlers.DashboardHandler;
import org.apache.jackrabbit.oak.segment.http.server.handlers.DeleteProposalHandler;
import org.apache.jackrabbit.oak.segment.http.server.handlers.EventStreamHandler;
import org.apache.jackrabbit.oak.segment.http.server.handlers.ExplorerApiHandler;
import org.apache.jackrabbit.oak.segment.http.server.handlers.ExplorerApiV1Handler;
import org.apache.jackrabbit.oak.segment.http.server.handlers.FileHandler;
import org.apache.jackrabbit.oak.segment.http.server.handlers.FragmentationApiHandler;
import org.apache.jackrabbit.oak.segment.http.server.handlers.GcCostHandler;
import org.apache.jackrabbit.oak.segment.http.server.handlers.HealthHandler;
import org.apache.jackrabbit.oak.segment.http.server.handlers.MetricsHandler;
import org.apache.jackrabbit.oak.segment.http.server.handlers.OsgiConfigApiHandler;
import org.apache.jackrabbit.oak.segment.http.server.handlers.PeerDiscoveryHandler;
import org.apache.jackrabbit.oak.segment.http.server.handlers.ProposalQueryHandler;
import org.apache.jackrabbit.oak.segment.http.server.handlers.RegistrationHandler;
import org.apache.jackrabbit.oak.segment.http.server.handlers.WalletQueryHandler;
import org.apache.jackrabbit.oak.segment.http.server.handlers.WriteProposalHandler;
import org.apache.jackrabbit.oak.segment.http.server.util.ApiErrorUtil;
import org.apache.jackrabbit.oak.segment.http.server.util.FormatUtils;
import org.apache.jackrabbit.oak.segment.http.server.sse.EventBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Central request router that delegates HTTP requests to appropriate handlers.
 * 
 * <p>Routes are declared once in the constructor. Exact routes are keyed by
 * {@code "METHOD path"} (or by bare path when any method is accepted); prefix
 * routes are tried in declaration order only when no exact route matches.
 * Public routes are served before rate limiting, authentication and the
 * quarantine check.</p>
 */
public class RequestRouter implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RequestRouter.class);
    static final String CONFIG_CONSOLE_PATH = "/console/configMgr";

    @FunctionalInterface
    private interface Handler {
        void handle(HttpServletRequest request, HttpServletResponse response, String path) throws Exception;
    }

    private record PrefixRoute(String method, String prefix, Handler handler) {
    }

    private final Map<String, Handler> publicRoutes = new HashMap<>();
    private final Map<String, Handler> routes = new HashMap<>();
    private final List<PrefixRoute> prefixRoutes = new ArrayList<>();

    private final ConsensusApiHandler consensusApiHandler;
    private final EventBroadcaster eventBroadcaster;
    private final org.apache.jackrabbit.oak.segment.http.server.binary.UploadSessionManager uploadSessionManager;
    private final BinaryUploadHandler binaryUploadHandler;
    private final AuthTokenValidator authValidator;
    private final RateLimiter rateLimiter;
    private final boolean browserUiEnabled;
    
    private final ServerContext context;

    public RequestRouter(ServerContext context) {
        this.context = context;
        this.authValidator = new AuthTokenValidator();
        this.rateLimiter = new RateLimiter();
        this.browserUiEnabled = RuntimeConfigValueResolver.readBoolean("oak.http.browser.ui.enabled", true);
        
        // Initialize all handlers
        HealthHandler healthHandler = new HealthHandler(
            context.fileStore,
            context.nodeStore,
            context.storeDirectory,
            context.registeredClients,
            context.registeredValidators,
            context
        );
        MetricsHandler metricsHandler = new MetricsHandler(
            context.aeronConsensusEngine,
            context.storeDirectory,
            context.registeredClients,
            context.registeredValidators,
            context
        );
        FileHandler fileHandler = new FileHandler(
            context.fileStore,
            context.storeDirectory
        );
        ExplorerApiHandler explorerApiHandler = ExplorerApiHandler.withBlobStoreSupplier(
            context.nodeStore,
            context.storeDirectory,
            () -> context.blobStore
        );
        DashboardHandler dashboardHandler = new DashboardHandler(context);
        ExplorerApiV1Handler explorerApiV1Handler = new ExplorerApiV1Handler(context);
        this.consensusApiHandler = new ConsensusApiHandler(context);
        WriteProposalHandler writeProposalHandler = new WriteProposalHandler(context);
        DeleteProposalHandler deleteProposalHandler = new DeleteProposalHandler(context);
        ConsensusStatusHandler consensusStatusHandler = new ConsensusStatusHandler(context);
        ProposalQueryHandler proposalQueryHandler = new ProposalQueryHandler(context);
        GcCostHandler gcCostHandler = new GcCostHandler(context);
        WalletQueryHandler walletQueryHandler = new WalletQueryHandler(context);
        RegistrationHandler registrationHandler = new RegistrationHandler(context);
        PeerDiscoveryHandler peerDiscoveryHandler = new PeerDiscoveryHandler(context);
        AeronApiHandler aeronApiHandler = new AeronApiHandler(context);
        FragmentationApiHandler fragmentationApiHandler = new FragmentationApiHandler(context);
        
        // Binary upload handler (ADR 020 - lazy upload on confirmation)
        this.uploadSessionManager = new org.apache.jackrabbit.oak.segment.http.server.binary.UploadSessionManager();
        this.binaryUploadHandler = new BinaryUploadHandler(uploadSessionManager);

        // Make session manager available in context for dashboard metrics
        context.setUploadSessionManager(uploadSessionManager);
        
        // CID API handler (Oak ↔ IPFS CID mapping)
        CidApiHandler cidApiHandler = new CidApiHandler(context);
        
        // SSE Event Broadcaster and Handler (ADR 036)
        this.eventBroadcaster = new EventBroadcaster();
        EventStreamHandler eventStreamHandler = new EventStreamHandler(context, eventBroadcaster);
        OsgiConfigApiHandler osgiConfigApiHandler = new OsgiConfigApiHandler();
        context.setEventBroadcaster(eventBroadcaster); // Make available to other components

        // Health checks: public (monitoring/load balancers), no rate limit, auth or quarantine gate
        publicGet("/health", (req, res, path) -> healthHandler.handleHealth(res));
        publicGet("/health/local", (req, res, path) -> healthHandler.handleLocalHealth(res));
        publicGet("/health/deep", (req, res, path) -> healthHandler.handleDeepHealth(res));
        publicGet("/health/cluster", (req, res, path) -> healthHandler.handleClusterHealth(res));
        publicGet("/v1/ops/snapshots/health", (req, res, path) -> healthHandler.handleGetOpsHealthSnapshot(res));
        publicGet("/v1/ops/snapshots/runtime", (req, res, path) -> healthHandler.handleGetOpsRuntimeSnapshot(res));
        publicGet("/v1/ops/snapshots/storage", (req, res, path) -> healthHandler.handleGetOpsStorageSnapshot(res));

        // Dashboard and UI
        get("/", (req, res, path) -> dashboardHandler.handleDashboard(res));
        get("/dashboard", (req, res, path) -> dashboardHandler.handleDashboard(res));
        get("/explorer", (req, res, path) -> dashboardHandler.handleExplorerUI(res));
        get("/api-browser", (req, res, path) -> dashboardHandler.handleApiBrowserUI(res));
        get(CONFIG_CONSOLE_PATH, (req, res, path) -> dashboardHandler.handleConfigConsole(res));
        get("/v1/index", (req, res, path) -> dashboardHandler.handleApiIndex(res));

        get("/v1/config/osgi", (req, res, path) -> osgiConfigApiHandler.handleEffectiveConfig(res));
        get("/v1/config/osgi/schema", (req, res, path) -> osgiConfigApiHandler.handleConfigSchema(res));
        get("/v1/config/osgi/sources", (req, res, path) -> osgiConfigApiHandler.handleConfigSources(res));
        get("/v1/config/osgi/coverage", (req, res, path) -> osgiConfigApiHandler.handleCoverage(res));
        get("/v1/config/osgi/delta", (req, res, path) -> osgiConfigApiHandler.handleDelta(res));

        // File serving and segments
        get("/journal.log", (req, res, path) -> fileHandler.handleFile(req, res, "journal.log", "text/plain"));
        any("/manifest", (req, res, path) -> {
            String method = req.getMethod();
            if ("HEAD".equals(method)) {
                fileHandler.handleFileHead(res, "manifest", "text/plain");
            } else if ("GET".equals(method)) {
                fileHandler.handleFile(req, res, "manifest", "text/plain");
            } else {
                sendMethodNotAllowed(res);
            }
        });
        get("/gc.log", (req, res, path) -> fileHandler.handleFile(req, res, "gc.log", "text/plain"));
        prefix(null, "/segments/", (req, res, path) -> {
            String segmentId = path.substring("/segments/".length());
            String method = req.getMethod();
            if ("HEAD".equals(method)) {
                fileHandler.handleSegmentHead(res, segmentId);
            } else if ("GET".equals(method)) {
                fileHandler.handleSegmentGet(req, res, segmentId);
            } else {
                sendMethodNotAllowed(res);
            }
        });

        // Explorer, blob and CID APIs
        get("/api/explore", (req, res, path) -> {
            String nodePath = req.getParameter("path");
            explorerApiHandler.handleExploreNode(res, nodePath != null ? nodePath : "/");
        });
        get("/api/segments/recent", (req, res, path) -> explorerApiHandler.handleRecentSegments(res));
        get("/api/segments/tars", (req, res, path) -> explorerApiHandler.handleTarFiles(res));
        prefix("GET", "/api/blob/", (req, res, path) ->
            explorerApiHandler.handleBlobStream(req, res, path.substring("/api/blob/".length())));
        get("/api/cid/stats", (req, res, path) -> cidApiHandler.handleStats(req, res));
        prefix("GET", "/api/cid/gateway/", (req, res, path) -> cidApiHandler.handleGatewayRedirect(req, res));
        prefix("GET", "/api/cid/reverse/", (req, res, path) -> cidApiHandler.handleReverseLookup(req, res));
        prefix("GET", "/api/cid/", (req, res, path) -> cidApiHandler.handleGetCid(req, res));

        // SSE Event Streaming API (ADR 036)
        get("/v1/ops/events/stream", (req, res, path) -> eventStreamHandler.handleOpsEventStream(req, res));
        get("/v1/events/stream", (req, res, path) -> eventStreamHandler.handleEventStream(req, res));
        get("/v1/events/recent", (req, res, path) -> eventStreamHandler.handleRecentEvents(req, res));
        get("/v1/events/stats", (req, res, path) -> eventStreamHandler.handleStats(req, res));

        get("/api/metrics", (req, res, path) -> metricsHandler.handleMetrics(res));
        get("/metrics", (req, res, path) -> metricsHandler.handlePrometheusMetrics(res));

        // Explorer v1
        get("/v1/explorer/summary", (req, res, path) -> explorerApiV1Handler.handleSummary(res));
        get("/v1/explorer/release-flow", (req, res, path) -> explorerApiV1Handler.handleReleaseFlow(res));
        prefix("GET", "/v1/explorer/proposals/", (req, res, path) ->
            explorerApiV1Handler.handleProposalById(res, path.substring("/v1/explorer/proposals/".length())));
        prefix("GET", "/v1/explorer/wallets/", (req, res, path) ->
            explorerApiV1Handler.handleWalletByAddress(res, path.substring("/v1/explorer/wallets/".length())));
        get("/v1/explorer/content/nav", (req, res, path) -> explorerApiV1Handler.handleContentNav(res));
        prefix("GET", "/v1/explorer/content/clusters/", (req, res, path) -> {
            if (!handleContentCluster(explorerApiV1Handler, req, res, path)) {
                sendNotFound(req, res, path);
            }
        });

        // Proposals and binary upload (ADR 020)
        post("/v1/propose-write", (req, res, path) -> {
            logShardRoute(req);
            writeProposalHandler.handleProposeWrite(req, res);
        });
        post("/v1/propose-delete", (req, res, path) -> deleteProposalHandler.handleDeleteProposal(req, res));
        post("/v1/binary/declare-intent", (req, res, path) -> binaryUploadHandler.handleDeclareIntent(req, res));
        prefix("GET", "/v1/binary/check-intent/", (req, res, path) -> binaryUploadHandler.handleCheckIntent(req, res));
        post("/v1/binary/complete-upload", (req, res, path) -> binaryUploadHandler.handleCompleteUpload(req, res));

        get("/v1/head", (req, res, path) -> handleHead(res));
        get("/v1/consensus/status", (req, res, path) -> consensusStatusHandler.handleGetConsensusStatus(res));
        get("/v1/consensus/leader", (req, res, path) -> consensusStatusHandler.handleGetConsensusLeader(
            "true".equals(req.getParameter(LeaderDiscoveryService.LOCAL_ONLY_PARAM)), res));

        get("/v1/wallets/stats", (req, res, path) -> walletQueryHandler.handleWalletStats(req, res));
        get("/v1/wallets/content", (req, res, path) -> walletQueryHandler.handleWalletContent(req, res));
        get("/v1/gc/estimate", (req, res, path) -> gcCostHandler.handleGCCostEstimate(req, res));

        prefix("GET", "/v1/ops/operations/", (req, res, path) -> proposalQueryHandler.handleGetOperationStatus(req, res));
        get("/v1/ops/snapshots/queue", (req, res, path) -> proposalQueryHandler.handleGetOpsQueueSnapshot(res));
        prefix("GET", "/v1/settlement/proposals/", (req, res, path) ->
            proposalQueryHandler.handleGetSettlementByProposalId(req, res));
        prefix("GET", "/v1/settlement/transactions/", (req, res, path) ->
            proposalQueryHandler.handleGetSettlementByTransactionHash(req, res));
        get("/v1/ops/snapshots/cluster", (req, res, path) -> aeronApiHandler.handleGetOpsClusterSnapshot(res));
        get("/v1/ops/snapshots/replication", (req, res, path) -> aeronApiHandler.handleGetOpsReplicationSnapshot(res));
        prefix("GET", "/v1/proposals/", (req, res, path) -> {
            if (path.endsWith("/status")) {
                proposalQueryHandler.handleGetProposalStatus(req, res);
            } else {
                sendNotFound(req, res, path);
            }
        });
        get("/v1/proposals/pending/count", (req, res, path) -> proposalQueryHandler.handleGetPendingCount(res));
        get("/v1/proposals/queue/stats", (req, res, path) -> proposalQueryHandler.handleGetQueueStats(res));
        get("/v1/proposals/release-flow", (req, res, path) -> proposalQueryHandler.handleGetProposalReleaseFlow(res));

        post("/v1/register-client", (req, res, path) -> registrationHandler.handleClientRegistration(req, res));
        route("PUT", "/v1/register-client", (req, res, path) -> registrationHandler.handleClientRegistration(req, res));
        get("/v1/peers", (req, res, path) -> peerDiscoveryHandler.handlePeerList(res));
        get("/v1/blockchain/config", (req, res, path) -> new BlockchainConfigApiHandler(context).handle(res));

        // Aeron Cluster-specific endpoints (ADR 025: replication lag)
        get("/v1/aeron/cluster-state", (req, res, path) -> aeronApiHandler.handleClusterState(res));
        get("/v1/aeron/validator-identities", (req, res, path) -> aeronApiHandler.handleValidatorIdentities(res));
        get("/v1/aeron/raft-metrics", (req, res, path) -> aeronApiHandler.handleRaftMetrics(res));
        get("/v1/aeron/node-status", (req, res, path) -> aeronApiHandler.handleNodeStatus(req, res));
        get("/v1/aeron/leadership-history", (req, res, path) -> aeronApiHandler.handleLeadershipHistory(req, res));
        get("/v1/aeron/replication-lag", (req, res, path) -> aeronApiHandler.handleReplicationLag(res));

        // Fragmentation, GC and GC accounts
        get("/v1/fragmentation/metrics", (req, res, path) -> fragmentationApiHandler.handleGetAllMetrics(req, res));
        prefix("GET", "/v1/fragmentation/metrics/", (req, res, path) -> fragmentationApiHandler.handleGetEntityMetrics(
            req, res, path.substring("/v1/fragmentation/metrics/".length())));
        get("/v1/fragmentation/top", (req, res, path) -> fragmentationApiHandler.handleGetTopFragmented(req, res));
        get("/v1/gc/status", (req, res, path) -> fragmentationApiHandler.handleGetGcStatus(req, res));
        get("/v1/compaction/proposals", (req, res, path) -> fragmentationApiHandler.handleGetCompactionProposals(req, res));
        post("/v1/propose-gc", (req, res, path) -> fragmentationApiHandler.handleProposeGC(req, res));
        post("/v1/gc/execute", (req, res, path) -> fragmentationApiHandler.handleExecuteGC(req, res));
        post("/v1/gc/vote", (req, res, path) -> fragmentationApiHandler.handleVoteGC(req, res));
        prefix(null, "/v1/gc/account/", (req, res, path) -> {
            if (!handleGcAccount(fragmentationApiHandler, req, res, path)) {
                sendNotFound(req, res, path);
            }
        });
        post("/v1/gc/trigger", (req, res, path) -> fragmentationApiHandler.handleTriggerGC(req, res));
    }

    private void publicGet(String path, Handler handler) {
        register(publicRoutes, "GET " + path, handler);
    }

    private void get(String path, Handler handler) {
        route("GET", path, handler);
    }

    private void post(String path, Handler handler) {
        route("POST", path, handler);
    }

    private void route(String method, String path, Handler handler) {
        register(routes, method + " " + path, handler);
    }

    private void any(String path, Handler handler) {
        register(routes, path, handler);
    }

    private void prefix(String method, String prefix, Handler handler) {
        prefixRoutes.add(new PrefixRoute(method, prefix, handler));
    }

    private static void register(Map<String, Handler> table, String key, Handler handler) {
        if (table.put(key, handler) != null) {
            throw new IllegalStateException("Duplicate route: " + key);
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
     * @param request HTTP servlet request
     * @param response HTTP servlet response
     * @throws IOException if an I/O error occurs
     */
    public void route(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String path = request.getRequestURI();
        String method = request.getMethod();
        
        try {
            Handler handler = publicRoutes.get(method + " " + path);
            if (handler != null) {
                handler.handle(request, response, path);
                return;
            }
            
            // Internal segment-transfer endpoints are part of cluster-to-cluster read fabric,
            // not public API traffic, so they must bypass public rate limiting.
            if (!isRateLimitExempt(path, method) && !rateLimiter.allowRequest(request, response)) {
                rateLimiter.sendRateLimitResponse(response);
                return;
            }
            
            // Validate authentication for all other endpoints (if auth is enabled)
            // If auth is disabled (no token configured), this allows all requests (POC mode)
            if (!authValidator.validateRequest(request, response)) {
                return; // Response already sent by validateRequest
            }

            if (context.aeronConsensusEngine != null && context.aeronConsensusEngine.hasApplicationFailure()) {
                ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "replicated_apply_failed", "This member is quarantined pending restart and repair.");
                return;
            }
            
            if (isBrowserUiRoute(path) && !browserUiEnabled) {
                ApiErrorUtil.sendJsonError(
                    response,
                    HttpServletResponse.SC_GONE,
                    "Browser UI routes are disabled. Use validator-native API surface (/v1/index) or an external gateway/UI. Set -Doak.http.browser.ui.enabled=true to enable."
                );
                return;
            }
            
            handler = findRoute(method, path);
            if (handler != null) {
                handler.handle(request, response, path);
            } else {
                sendNotFound(request, response, path);
            }
        } catch (Exception e) {
            log.error("Error routing request: " + path, e);
            if (isApiPath(path)) {
                ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
            } else {
                response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
            }
        }
    }

    private Handler findRoute(String method, String path) {
        Handler handler = routes.get(method + " " + path);
        if (handler == null) {
            handler = routes.get(path);
        }
        if (handler != null) {
            return handler;
        }
        for (PrefixRoute route : prefixRoutes) {
            if (path.startsWith(route.prefix()) && (route.method() == null || route.method().equals(method))) {
                return route.handler();
            }
        }
        return null;
    }

    private void sendNotFound(HttpServletRequest request, HttpServletResponse response, String path) throws IOException {
        String method = request.getMethod();
        String remoteAddr = request.getRemoteAddr();
        String userAgent = request.getHeader("User-Agent");

        // Sling/AEM endpoints are not validator endpoints - suppress noise
        if (path != null && (path.startsWith("/bin/") || path.startsWith("/system/") || path.startsWith("/content/"))) {
            log.debug("⚠️  Invalid request (Sling/AEM endpoint on validator): {} {} FROM {} [UA: {}]",
                method, path, remoteAddr, userAgent != null ? userAgent : "unknown");
        } else {
            log.info("⚠️  404 Not Found: {} {} FROM {} [UA: {}]",
                method, path, remoteAddr, userAgent != null ? userAgent : "unknown");
        }

        if (isApiPath(path)) {
            ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_NOT_FOUND, "Not found");
        } else {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
        }
    }

    private static void sendMethodNotAllowed(HttpServletResponse response) throws IOException {
        ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED, "Method not allowed");
    }

    private static boolean handleContentCluster(ExplorerApiV1Handler explorerApiV1Handler, HttpServletRequest request,
                                                HttpServletResponse response, String path) throws IOException {
        String clusterPath = path.substring("/v1/explorer/content/clusters/".length());
        int separator = clusterPath.indexOf('/');
        if (separator <= 0 || separator >= clusterPath.length() - 1) {
            return false;
        }
        String clusterId = clusterPath.substring(0, separator);
        String action = clusterPath.substring(separator + 1);
        String requestedPath = request.getParameter("path");
        if ("tree".equals(action)) {
            explorerApiV1Handler.handleContentTree(response, clusterId, requestedPath,
                parseIntParameter(request.getParameter("offset"), 0),
                parseIntParameter(request.getParameter("limit"), ExplorerApiV1Handler.DEFAULT_TREE_PAGE_SIZE));
            return true;
        }
        if ("node".equals(action)) {
            explorerApiV1Handler.handleContentNode(response, clusterId, requestedPath);
            return true;
        }
        if ("provenance".equals(action)) {
            explorerApiV1Handler.handleContentProvenance(response, clusterId, requestedPath);
            return true;
        }
        return false;
    }

    private static boolean handleGcAccount(FragmentationApiHandler fragmentationApiHandler, HttpServletRequest request,
                                           HttpServletResponse response, String path) throws IOException {
        String remaining = path.substring("/v1/gc/account/".length());
        String method = request.getMethod();
        if (remaining.contains("/pay") && "POST".equals(method)) {
            fragmentationApiHandler.handlePayGCDebt(request, response, remaining.substring(0, remaining.indexOf("/pay")));
        } else if (remaining.contains("/set-limit") && "POST".equals(method)) {
            fragmentationApiHandler.handleSetDebtLimit(request, response, remaining.substring(0, remaining.indexOf("/set-limit")));
        } else if (remaining.contains("/execute-pending") && "POST".equals(method)) {
            fragmentationApiHandler.handleExecutePendingDebt(request, response,
                remaining.substring(0, remaining.indexOf("/execute-pending")));
        } else if ("GET".equals(method) && !remaining.contains("/")) {
            fragmentationApiHandler.handleGetGCAccount(request, response, remaining);
        } else {
            return false;
        }
        return true;
    }

    // Phase 1: shard routing is logged only; all requests still process locally.
    private void logShardRoute(HttpServletRequest request) {
        if (context.shardRouter == null) {
            return;
        }
        String walletAddress = request.getParameter("walletAddress");
        if (walletAddress == null || walletAddress.isEmpty()) {
            walletAddress = request.getParameter("wallet");
        }
        if (walletAddress != null && !walletAddress.isEmpty()) {
            try {
                String leaderUrl = context.shardRouter.routeRequest(walletAddress);
                if (leaderUrl != null) {
                    log.debug("🔀 Shard routing: wallet {} → leader {}", walletAddress, leaderUrl);
                }
            } catch (Exception e) {
                log.debug("Shard routing check failed: {}", e.getMessage());
            }
        }
    }

    // HEAD from AeronConsensusEngine (tracks latest HEAD correctly), FileStore as fallback.
    private void handleHead(HttpServletResponse response) throws IOException {
        response.setContentType("application/json");
        response.setStatus(HttpServletResponse.SC_OK);

        String latestHead = null;
        String committedHead = null;
        int latestEpochSeen = -1;
        int committedEpoch = -1;
        if (context.aeronConsensusEngine != null) {
            latestHead = context.aeronConsensusEngine.getLatestHead();
            committedHead = context.aeronConsensusEngine.getCommittedHead();
            latestEpochSeen = context.aeronConsensusEngine.getLatestEpochSeen();
            committedEpoch = context.aeronConsensusEngine.getLastCommittedEpoch();
        }
        if (latestHead == null || latestHead.isEmpty()) {
            latestHead = context.fileStore.getHead().getRecordId().toString10();
        }

        StringBuilder json = new StringBuilder("{\n");
        json.append("  \"latestHead\": \"").append(FormatUtils.escapeJson(latestHead)).append("\",\n");
        if (committedHead == null || committedHead.isEmpty()) {
            json.append("  \"committedHead\": null");
        } else {
            json.append("  \"committedHead\": \"").append(FormatUtils.escapeJson(committedHead)).append("\"");
        }
        if (latestEpochSeen >= 0) {
            json.append(",\n  \"latestEpochSeen\": ").append(latestEpochSeen);
        }
        if (committedEpoch >= 0) {
            json.append(",\n  \"committedEpoch\": ").append(committedEpoch);
        }
        json.append("\n}\n");
        response.getWriter().write(json.toString());
    }

    private static int parseIntParameter(String value, int fallback) {
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private boolean isApiPath(String path) {
        if (path == null) {
            return false;
        }
        return path.startsWith("/v1/")
            || path.startsWith("/api/")
            || path.startsWith("/health")
            || path.startsWith("/metrics")
            || path.startsWith("/journal.log")
            || path.startsWith("/manifest")
            || path.startsWith("/segments/");
    }

    private boolean isBrowserUiRoute(String path) {
        if (path == null) {
            return false;
        }
        return "/explorer".equals(path)
            || "/api-browser".equals(path)
            || CONFIG_CONSOLE_PATH.equals(path);
    }

    private boolean isRateLimitExempt(String path, String method) {
        if (path == null || method == null) {
            return false;
        }
        if (path.startsWith("/health")) {
            return true;
        }
        if ("/v1/ops/snapshots/health".equals(path) && "GET".equals(method)) {
            return true;
        }
        if ("/journal.log".equals(path) && "GET".equals(method)) {
            return true;
        }
        if ("/manifest".equals(path) && ("GET".equals(method) || "HEAD".equals(method))) {
            return true;
        }
        if ("/gc.log".equals(path) && "GET".equals(method)) {
            return true;
        }
        return path.startsWith("/segments/")
            && ("GET".equals(method) || "HEAD".equals(method));
    }
    
    /**
     * Get the binary upload handler (for integration with other components).
     * 
     * @return the binary upload handler
     */
    public BinaryUploadHandler getBinaryUploadHandler() {
        return binaryUploadHandler;
    }
    
    /**
     * Get the event broadcaster (for emitting SSE events from other components).
     * 
     * @return the event broadcaster
     */
    public EventBroadcaster getEventBroadcaster() {
        return eventBroadcaster;
    }

    @Override
    public void close() {
        try {
            consensusApiHandler.close();
        } catch (RuntimeException e) {
            log.warn("Failed to close consensus API handler", e);
        }
        try {
            eventBroadcaster.shutdown();
        } catch (RuntimeException e) {
            log.warn("Failed to shutdown event broadcaster", e);
        }
        try {
            uploadSessionManager.shutdown();
        } catch (RuntimeException e) {
            log.warn("Failed to shutdown upload session manager", e);
        }
        if (context.cidMappingService != null) {
            try {
                context.cidMappingService.close();
            } catch (RuntimeException e) {
                log.warn("Failed to close CID mapping service", e);
            }
        }
        try {
            rateLimiter.shutdown();
        } catch (RuntimeException e) {
            log.warn("Failed to shutdown rate limiter", e);
        }
        context.eventBroadcaster = null;
        context.uploadSessionManager = null;
        context.cidMappingService = null;
    }
}
