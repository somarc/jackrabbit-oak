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
package org.apache.jackrabbit.oak.segment.http.wallet;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Mock validator HTTP server for testing Sling author write proposals.
 * 
 * <p>This server mocks the validator API endpoints, allowing tests to:
 * - Intercept write proposals from Sling
 * - Simulate Ethereum contract events
 * - Test full end-to-end flow without real blockchain
 * 
 * <p>Usage:
 * <pre>
 * MockValidatorServer server = new MockValidatorServer();
 * server.start();
 * 
 * // Configure mock responses
 * server.mockProposeWrite((request) -> {
 *     return new MockResponse(202, "{\"proposalId\":\"...\",\"state\":\"PENDING\"}");
 * });
 * 
 * // Use server URL in SlingWriteProposalService
 * String validatorUrl = server.getBaseUrl();
 * </pre>
 */
public class MockValidatorServer {
    
    private static final Logger log = LoggerFactory.getLogger(MockValidatorServer.class);
    
    private Server server;
    private ServerConnector connector;
    private int port;
    private String baseUrl;
    
    // Mock handlers
    private Function<HttpServletRequest, MockResponse> proposeWriteHandler;
    private Function<HttpServletRequest, MockResponse> proposeDeleteHandler;
    private Function<HttpServletRequest, MockResponse> proposalStatusHandler;
    private Function<HttpServletRequest, MockResponse> pendingCountHandler;
    
    // Request tracking
    private final Map<String, AtomicInteger> requestCounts = new ConcurrentHashMap<>();
    private final Map<String, HttpServletRequest> lastRequests = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String[]>> lastRequestParams = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> lastRequestHeaders = new ConcurrentHashMap<>();
    
    public MockValidatorServer() {
        this(0); // Random port
    }
    
    public MockValidatorServer(int port) {
        this.port = port;
    }
    
    /**
     * Start the mock server.
     */
    public void start() throws Exception {
        server = new Server(port);
        connector = new ServerConnector(server);
        server.addConnector(connector);
        
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");
        server.setHandler(context);
        
        // Register servlets
        context.addServlet(new ServletHolder(new ProposeWriteServlet()), "/v1/propose-write");
        context.addServlet(new ServletHolder(new ProposeDeleteServlet()), "/v1/propose-delete");
        context.addServlet(new ServletHolder(new ProposalStatusServlet()), "/v1/proposals/*");
        context.addServlet(new ServletHolder(new PendingCountServlet()), "/v1/proposals/pending/count");
        
        server.start();
        
        // Get actual port (if random)
        port = connector.getLocalPort();
        baseUrl = "http://localhost:" + port;
        
        log.info("Mock validator server started on {}", baseUrl);
    }
    
    /**
     * Stop the mock server.
     */
    public void stop() throws Exception {
        if (server != null) {
            server.stop();
            log.info("Mock validator server stopped");
        }
    }
    
    /**
     * Get base URL of the mock server.
     */
    public String getBaseUrl() {
        return baseUrl;
    }
    
    /**
     * Configure mock handler for POST /v1/propose-write.
     */
    public void mockProposeWrite(Function<HttpServletRequest, MockResponse> handler) {
        this.proposeWriteHandler = handler;
    }

    /**
     * Configure mock handler for POST /v1/propose-delete.
     */
    public void mockProposeDelete(Function<HttpServletRequest, MockResponse> handler) {
        this.proposeDeleteHandler = handler;
    }
    
    /**
     * Configure mock handler for GET /v1/proposals/{id}/status.
     */
    public void mockProposalStatus(Function<HttpServletRequest, MockResponse> handler) {
        this.proposalStatusHandler = handler;
    }
    
    /**
     * Configure mock handler for GET /v1/proposals/pending/count.
     */
    public void mockPendingCount(Function<HttpServletRequest, MockResponse> handler) {
        this.pendingCountHandler = handler;
    }
    
    /**
     * Get request count for an endpoint.
     */
    public int getRequestCount(String endpoint) {
        return requestCounts.getOrDefault(endpoint, new AtomicInteger(0)).get();
    }
    
    /**
     * Get last request for an endpoint.
     */
    public HttpServletRequest getLastRequest(String endpoint) {
        return lastRequests.get(endpoint);
    }

    /**
     * Get last request header value for an endpoint.
     */
    public String getLastRequestHeader(String endpoint, String name) {
        Map<String, String> headers = lastRequestHeaders.get(endpoint);
        return headers == null ? null : headers.get(name);
    }

    /**
     * Get last request parameters for an endpoint.
     */
    public Map<String, String[]> getLastRequestParams(String endpoint) {
        return lastRequestParams.get(endpoint);
    }

    /**
     * Get last request parameter value for an endpoint.
     */
    public String getLastRequestParam(String endpoint, String name) {
        Map<String, String[]> params = lastRequestParams.get(endpoint);
        if (params == null) {
            return null;
        }
        String[] values = params.get(name);
        return (values == null || values.length == 0) ? null : values[0];
    }
    
    /**
     * Reset request tracking.
     */
    public void reset() {
        requestCounts.clear();
        lastRequests.clear();
        lastRequestParams.clear();
        lastRequestHeaders.clear();
    }

    private Map<String, String> copyHeaders(HttpServletRequest request) {
        Map<String, String> headers = new HashMap<>();
        Enumeration<String> names = request.getHeaderNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            headers.put(name, request.getHeader(name));
        }
        return headers;
    }
    
    /**
     * Mock response structure.
     */
    public static class MockResponse {
        public final int statusCode;
        public final String body;
        public final Map<String, String> headers;
        
        public MockResponse(int statusCode, String body) {
            this(statusCode, body, null);
        }
        
        public MockResponse(int statusCode, String body, Map<String, String> headers) {
            this.statusCode = statusCode;
            this.body = body;
            this.headers = headers != null ? headers : new java.util.HashMap<>();
        }
    }
    
    /**
     * Servlet for POST /v1/propose-write.
     */
    private class ProposeWriteServlet extends HttpServlet {
        @Override
        protected void doPost(HttpServletRequest request, HttpServletResponse response) 
                throws ServletException, IOException {
            requestCounts.computeIfAbsent("/v1/propose-write", k -> new AtomicInteger(0)).incrementAndGet();
            lastRequests.put("/v1/propose-write", request);
            lastRequestParams.put("/v1/propose-write", new java.util.HashMap<>(request.getParameterMap()));
            lastRequestHeaders.put("/v1/propose-write", copyHeaders(request));
            
            MockResponse mockResponse;
            if (proposeWriteHandler != null) {
                mockResponse = proposeWriteHandler.apply(request);
            } else {
                // Default: Accept proposal
                String proposalId = java.util.UUID.randomUUID().toString();
                mockResponse = new MockResponse(202, String.format(
                    "{\"proposalId\":\"%s\",\"state\":\"PENDING\",\"message\":\"Proposal queued\"}",
                    proposalId
                ));
            }
            
            response.setStatus(mockResponse.statusCode);
            response.setContentType("application/json");
            mockResponse.headers.forEach(response::setHeader);
            response.getWriter().write(mockResponse.body);
        }
    }

    /**
     * Servlet for POST /v1/propose-delete.
     */
    private class ProposeDeleteServlet extends HttpServlet {
        @Override
        protected void doPost(HttpServletRequest request, HttpServletResponse response)
                throws ServletException, IOException {
            requestCounts.computeIfAbsent("/v1/propose-delete", k -> new AtomicInteger(0)).incrementAndGet();
            lastRequests.put("/v1/propose-delete", request);
            lastRequestParams.put("/v1/propose-delete", new java.util.HashMap<>(request.getParameterMap()));
            lastRequestHeaders.put("/v1/propose-delete", copyHeaders(request));

            MockResponse mockResponse;
            if (proposeDeleteHandler != null) {
                mockResponse = proposeDeleteHandler.apply(request);
            } else {
                String proposalId = java.util.UUID.randomUUID().toString();
                mockResponse = new MockResponse(200, String.format(
                    "{\"proposalId\":\"%s\",\"state\":\"PENDING\",\"message\":\"Delete proposal queued\"}",
                    proposalId
                ));
            }

            response.setStatus(mockResponse.statusCode);
            response.setContentType("application/json");
            mockResponse.headers.forEach(response::setHeader);
            response.getWriter().write(mockResponse.body);
        }
    }
    
    /**
     * Servlet for GET /v1/proposals/{id}/status.
     */
    private class ProposalStatusServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) 
                throws ServletException, IOException {
            requestCounts.computeIfAbsent("/v1/proposals/*/status", k -> new AtomicInteger(0)).incrementAndGet();
            lastRequests.put("/v1/proposals/*/status", request);
            lastRequestParams.put("/v1/proposals/*/status", new java.util.HashMap<>(request.getParameterMap()));
            lastRequestHeaders.put("/v1/proposals/*/status", copyHeaders(request));
            
            MockResponse mockResponse;
            if (!request.getRequestURI().endsWith("/status")) {
                mockResponse = new MockResponse(404, "{\"error\":\"Not found\"}");
            } else if (proposalStatusHandler != null) {
                mockResponse = proposalStatusHandler.apply(request);
            } else {
                // Default: Not found
                mockResponse = new MockResponse(404, "{\"error\":\"Proposal not found\"}");
            }
            
            response.setStatus(mockResponse.statusCode);
            response.setContentType("application/json");
            mockResponse.headers.forEach(response::setHeader);
            response.getWriter().write(mockResponse.body);
        }
    }
    
    /**
     * Servlet for GET /v1/proposals/pending/count.
     */
    private class PendingCountServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) 
                throws ServletException, IOException {
            requestCounts.computeIfAbsent("/v1/proposals/pending/count", k -> new AtomicInteger(0)).incrementAndGet();
            lastRequests.put("/v1/proposals/pending/count", request);
            lastRequestParams.put("/v1/proposals/pending/count", new java.util.HashMap<>(request.getParameterMap()));
            lastRequestHeaders.put("/v1/proposals/pending/count", copyHeaders(request));
            
            MockResponse mockResponse;
            if (pendingCountHandler != null) {
                mockResponse = pendingCountHandler.apply(request);
            } else {
                // Default: Zero pending
                mockResponse = new MockResponse(200, "{\"pendingCount\":0}");
            }
            
            response.setStatus(mockResponse.statusCode);
            response.setContentType("application/json");
            mockResponse.headers.forEach(response::setHeader);
            response.getWriter().write(mockResponse.body);
        }
    }
}
