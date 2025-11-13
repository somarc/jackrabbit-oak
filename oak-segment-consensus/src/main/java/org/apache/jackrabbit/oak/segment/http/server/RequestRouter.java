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
    private final LeaderConsensusHandler leaderConsensusHandler;
    
    private final ServerContext context;

    public RequestRouter(ServerContext context) {
        this.context = context;
        
        // Initialize all handlers
        this.healthHandler = new HealthHandler(
            context.fileStore,
            context.nodeStore,
            context.storeDirectory,
            context.consensusEngine,
            context.dagConsensusEngine,
            context.epochLeaderEngine,
            context.registeredClients,
            context.registeredValidators
        );
        this.metricsHandler = new MetricsHandler(
            context.consensusEngine,
            context.dagConsensusEngine,
            context.epochLeaderEngine,
            context.storeDirectory,
            context.registeredClients,
            context.registeredValidators
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
        this.leaderConsensusHandler = new LeaderConsensusHandler(context);
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
            // Health checks
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
            
            // File serving
            if ("/journal.log".equals(path) && "GET".equals(method)) {
                fileHandler.handleFile(request, response, "journal.log", "text/plain");
                baseRequest.setHandled(true);
                return;
            }
            
            if ("/manifest".equals(path) && "GET".equals(method)) {
                fileHandler.handleFile(request, response, "manifest", "text/plain");
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
            if ("/v1/propose".equals(path) && "POST".equals(method)) {
                consensusApiHandler.handleWriteProposal(request, response);
                baseRequest.setHandled(true);
                return;
            }
            
            if ("/v1/vote".equals(path) && "POST".equals(method)) {
                consensusApiHandler.handleVote(request, response);
                baseRequest.setHandled(true);
                return;
            }
            
            if ("/v1/test-write".equals(path) && "POST".equals(method)) {
                consensusApiHandler.handleTestWrite(request, response);
                baseRequest.setHandled(true);
                return;
            }
            
            // HEAD endpoint
            if ("/v1/head".equals(path) && "GET".equals(method)) {
                response.setContentType("text/plain");
                response.setStatus(HttpServletResponse.SC_OK);
                String headId = context.fileStore.getHead().getRecordId().toString();
                response.getWriter().write(headId);
                baseRequest.setHandled(true);
                return;
            }
            
            if ("/v1/consensus/status".equals(path) && "GET".equals(method)) {
                consensusApiHandler.handleGetConsensusStatus(response);
                baseRequest.setHandled(true);
                return;
            }
            
            // Registration
            if ("/v1/register-client".equals(path) && ("POST".equals(method) || "PUT".equals(method))) {
                registrationHandler.handleClientRegistration(request, response);
                baseRequest.setHandled(true);
                return;
            }
            
            if ("/v1/register-validator".equals(path) && ("POST".equals(method) || "PUT".equals(method))) {
                registrationHandler.handleValidatorRegistration(request, response);
                baseRequest.setHandled(true);
                return;
            }
            
            if ("/v1/heartbeat".equals(path) && "POST".equals(method)) {
                registrationHandler.handleHeartbeat(request, response);
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
            
            // Leader consensus
            if ("/v1/follower/head-update".equals(path) && "POST".equals(method)) {
                leaderConsensusHandler.handleFollowerHeadUpdate(request, response);
                baseRequest.setHandled(true);
                return;
            }
            
            if ("/v1/consensus/peer-joined".equals(path) && "POST".equals(method)) {
                leaderConsensusHandler.handlePeerJoined(request, response);
                baseRequest.setHandled(true);
                return;
            }
            
            if ("/v1/consensus/claim-leadership".equals(path) && "POST".equals(method)) {
                leaderConsensusHandler.handleLeadershipClaim(request, response);
                baseRequest.setHandled(true);
                return;
            }
            
            if ("/v1/consensus/claim-ack".equals(path) && "POST".equals(method)) {
                leaderConsensusHandler.handleClaimAck(request, response);
                baseRequest.setHandled(true);
                return;
            }
            
            if ("/v1/consensus/register-public-key".equals(path) && "POST".equals(method)) {
                leaderConsensusHandler.handlePublicKeyRegistration(request, response);
                baseRequest.setHandled(true);
                return;
            }
            
            // DAG endpoints (deprecated - kept for backward compatibility)
            if ("/v1/dag/head".equals(path) && "POST".equals(method)) {
                // DAG consensus is deprecated technical debt
                // This endpoint can remain in main handler or be removed
                response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, 
                    "DAG consensus is deprecated");
                baseRequest.setHandled(true);
                return;
            }
            
            // Not found
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            baseRequest.setHandled(true);
            
        } catch (Exception e) {
            log.error("Error routing request: " + path, e);
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
            baseRequest.setHandled(true);
        }
    }
}

