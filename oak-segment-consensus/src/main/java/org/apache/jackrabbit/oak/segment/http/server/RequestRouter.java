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
    
    private final ServerContext context;

    public RequestRouter(ServerContext context) {
        this.context = context;
        
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
        this.aeronApiHandler = new AeronApiHandler(context);
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
                consensusApiHandler.handleProposeWrite(request, response);
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

