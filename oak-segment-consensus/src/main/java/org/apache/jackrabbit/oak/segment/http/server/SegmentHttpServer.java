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
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.ServerConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.prometheus.client.hotspot.DefaultExports;

import jakarta.servlet.MultipartConfigElement;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
     * @param tlsConfig TLS configuration
     */
    public SegmentHttpServer(File storeDirectory, int port, FileStore fileStore, NodeStore nodeStore, 
                            TlsConfiguration tlsConfig) {
        Path storePath = storeDirectory.toPath();
        String scheme = tlsConfig.isEnabled() ? "https" : "http";
        this.context = new ServerContext(fileStore, nodeStore, storePath, scheme + "://localhost:" + port);
        
        // Create RequestRouter (will be updated when consensus engines are set)
        this.router = new RequestRouter(context);
        
        // Create server - TLS will be configured in start() if enabled
        this.server = new Server();
        this.server.setHandler(createServerHandler());
        
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
        configureBindHost(server);
        
        // Initialize Prometheus metrics (JVM metrics: memory, GC, threads, etc.)
        DefaultExports.initialize();
        
        log.info("Initialized SegmentHttpServer");
        log.info("   - Port: {}", port);
        log.info("   - Store: {}", storePath);
        log.info("   - TLS: {}", tlsConfig.isEnabled() ? "enabled" : "disabled");
        log.info("   - Prometheus metrics enabled at /metrics");
    }

    static void configureBindHost(Server server) {
        String host = RuntimeConfigValueResolver.readString("http.bind.host", null);
        if (host != null) {
            for (Connector connector : server.getConnectors()) {
                if (connector instanceof ServerConnector) {
                    ((ServerConnector) connector).setHost(host);
                }
            }
        }
    }

    SegmentHttpServer(Server server, ServerContext context, RequestRouter router) {
        this.server = server;
        this.context = context;
        this.router = router;
    }

    private ServletContextHandler createServerHandler() {
        ServletContextHandler servletContextHandler = new ServletContextHandler();
        servletContextHandler.setContextPath("/");

        ServletHolder servletHolder = new ServletHolder(new SegmentStoreServlet());
        servletHolder.getRegistration().setMultipartConfig(new MultipartConfigElement(
            System.getProperty("java.io.tmpdir"),
            100L * 1024 * 1024,
            200L * 1024 * 1024,
            1024 * 1024
        ));
        servletContextHandler.addServlet(servletHolder, "/*");
        return servletContextHandler;
    }
    
    /**
     * Set the Aeron Cluster consensus engine (Raft-based mode).
     * Must be called before start() if Aeron Cluster consensus is needed.
     */
    public void setAeronConsensusEngine(org.apache.jackrabbit.oak.segment.consensus.aeron.AeronConsensusEngine engine) {
        context.setAeronConsensusEngine(engine);
        router.getConsensusApiHandler().refreshCallbacks();
        log.info("✈️  Aeron Cluster consensus engine configured");
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
        Exception failure = null;
        if (server != null) {
            try {
                server.stop();
                log.info("SegmentHttpServer stopped");
            } catch (Exception e) {
                failure = e;
            }
        }
        try {
            router.close();
        } catch (RuntimeException e) {
            if (failure == null) {
                throw e;
            }
            failure.addSuppressed(e);
        }
        if (failure != null) {
            throw failure;
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
    private class SegmentStoreServlet extends HttpServlet {

        @Override
        protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
            String path = request.getRequestURI();
            String method = request.getMethod();

            log.debug("HTTP {} {}", method, path);

            response.setHeader("Access-Control-Allow-Origin", "*");
            response.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, OPTIONS");
            response.setHeader("Access-Control-Allow-Headers", "Content-Type, Authorization");
            response.setHeader("Access-Control-Max-Age", "3600");

            if ("OPTIONS".equals(method)) {
                response.setStatus(HttpServletResponse.SC_OK);
                return;
            }

            try {
                router.route(request, response);
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
