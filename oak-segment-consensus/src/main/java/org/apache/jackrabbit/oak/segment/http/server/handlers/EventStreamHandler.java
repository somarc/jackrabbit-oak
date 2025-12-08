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
import org.apache.jackrabbit.oak.segment.http.server.sse.ContentEvent;
import org.apache.jackrabbit.oak.segment.http.server.sse.EventBroadcaster;
import org.apache.jackrabbit.oak.segment.http.server.sse.SSEClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.AsyncContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Handler for Server-Sent Events (SSE) streaming endpoint.
 * 
 * <p>Implements ADR 036 - Real-time content discovery via SSE.
 * 
 * <p>Endpoint: GET /v1/events/stream
 * <p>Query parameters:
 * <ul>
 *   <li>types - Event types to include (content,binary,wallet,consensus)</li>
 *   <li>wallets - Filter by wallet addresses (comma-separated)</li>
 *   <li>organizations - Filter by organization names (comma-separated)</li>
 *   <li>since - Events after timestamp</li>
 *   <li>path - Filter by path prefix</li>
 * </ul>
 */
public class EventStreamHandler {

    private static final Logger log = LoggerFactory.getLogger(EventStreamHandler.class);

    private final ServerContext context;
    private final EventBroadcaster broadcaster;

    public EventStreamHandler(ServerContext context, EventBroadcaster broadcaster) {
        this.context = context;
        this.broadcaster = broadcaster;
    }

    /**
     * Handle SSE stream connection.
     * GET /v1/events/stream
     */
    public void handleEventStream(HttpServletRequest request, HttpServletResponse response) throws IOException {
        // Parse filter parameters
        Set<String> types = parseSet(request.getParameter("types"));
        Set<String> wallets = parseSet(request.getParameter("wallets"));
        Set<String> organizations = parseSet(request.getParameter("organizations"));
        String pathPrefix = request.getParameter("path");
        Long since = parseLong(request.getParameter("since"));

        // Set up SSE response
        response.setContentType("text/event-stream");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Connection", "keep-alive");
        response.setHeader("X-Accel-Buffering", "no"); // Disable nginx buffering
        
        // CORS headers for EDS
        response.setHeader("Access-Control-Allow-Origin", "*");
        response.setHeader("Access-Control-Allow-Methods", "GET");
        response.setHeader("Access-Control-Allow-Headers", "Content-Type");

        // Start async context for long-lived connection
        AsyncContext asyncContext = request.startAsync();
        asyncContext.setTimeout(0); // No timeout - connection stays open

        PrintWriter writer;
        try {
            writer = response.getWriter();
        } catch (IOException e) {
            log.warn("Failed to get writer for SSE stream", e);
            return;
        }

        // Create SSE client
        SSEClient client = new SSEClient(
            asyncContext,
            writer,
            types,
            wallets,
            organizations,
            pathPrefix,
            since
        );

        // Register client with broadcaster
        broadcaster.addClient(client);

        String clientInfo = String.format("types=%s, wallets=%d, orgs=%d", 
            types.isEmpty() ? "all" : types,
            wallets.size(),
            organizations.size());
        log.info("📡 SSE client connected: {} [{}]", request.getRemoteAddr(), clientInfo);

        // Send initial comment to establish connection
        try {
            writer.write(": connected to oak-chain event stream\n");
            writer.write(": filters: " + clientInfo + "\n\n");
            writer.flush();
        } catch (Exception e) {
            log.warn("Failed to send SSE connection message", e);
            broadcaster.removeClient(client);
            return;
        }

        // Send recent events if 'since' not specified (initial load)
        if (since == null) {
            List<ContentEvent> recentEvents = broadcaster.getRecentEvents(50);
            for (ContentEvent event : recentEvents) {
                if (client.matches(event)) {
                    try {
                        client.send(event);
                    } catch (Exception e) {
                        log.debug("Failed to send recent event to client", e);
                        broadcaster.removeClient(client);
                        return;
                    }
                }
            }
        }

        // Set up cleanup on disconnect
        asyncContext.addListener(new javax.servlet.AsyncListener() {
            @Override
            public void onComplete(javax.servlet.AsyncEvent event) {
                broadcaster.removeClient(client);
                log.info("📡 SSE client disconnected (complete): {}", request.getRemoteAddr());
            }

            @Override
            public void onTimeout(javax.servlet.AsyncEvent event) {
                broadcaster.removeClient(client);
                log.info("📡 SSE client disconnected (timeout): {}", request.getRemoteAddr());
            }

            @Override
            public void onError(javax.servlet.AsyncEvent event) {
                broadcaster.removeClient(client);
                log.info("📡 SSE client disconnected (error): {}", request.getRemoteAddr());
            }

            @Override
            public void onStartAsync(javax.servlet.AsyncEvent event) {
                // Not used
            }
        });
    }

    /**
     * Handle recent events request.
     * GET /v1/events/recent
     */
    public void handleRecentEvents(HttpServletRequest request, HttpServletResponse response) throws IOException {
        int limit = parseInt(request.getParameter("limit"), 50);
        Long since = parseLong(request.getParameter("since"));
        Set<String> types = parseSet(request.getParameter("types"));
        Set<String> wallets = parseSet(request.getParameter("wallets"));
        Set<String> organizations = parseSet(request.getParameter("organizations"));

        List<ContentEvent> events = broadcaster.getRecentEvents(limit * 2); // Get extra, then filter

        // Apply filters
        List<ContentEvent> filteredEvents = events.stream()
            .filter(e -> since == null || e.getTimestamp() > since)
            .filter(e -> types.isEmpty() || types.contains(e.getType()))
            .filter(e -> wallets.isEmpty() || wallets.contains(e.getWallet()))
            .filter(e -> organizations.isEmpty() || organizations.contains(e.getOrganization()))
            .limit(limit)
            .collect(Collectors.toList());

        // Build JSON response
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Access-Control-Allow-Origin", "*");

        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"events\": [\n");

        for (int i = 0; i < filteredEvents.size(); i++) {
            ContentEvent event = filteredEvents.get(i);
            json.append("    ").append(event.toJson());
            if (i < filteredEvents.size() - 1) {
                json.append(",");
            }
            json.append("\n");
        }

        json.append("  ],\n");
        json.append("  \"count\": ").append(filteredEvents.size()).append(",\n");
        json.append("  \"hasMore\": ").append(events.size() > filteredEvents.size()).append(",\n");
        
        if (!filteredEvents.isEmpty()) {
            json.append("  \"lastId\": \"").append(filteredEvents.get(filteredEvents.size() - 1).getId()).append("\"\n");
        } else {
            json.append("  \"lastId\": null\n");
        }
        
        json.append("}\n");

        response.getWriter().write(json.toString());
    }

    /**
     * Handle SSE stats request.
     * GET /v1/events/stats
     */
    public void handleStats(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Access-Control-Allow-Origin", "*");

        int clientCount = broadcaster.getClientCount();
        int bufferSize = broadcaster.getBufferSize();
        long totalEvents = broadcaster.getTotalEventsBroadcast();

        String json = String.format(
            "{\"connectedClients\":%d,\"eventBufferSize\":%d,\"totalEventsBroadcast\":%d}",
            clientCount, bufferSize, totalEvents
        );

        response.getWriter().write(json);
    }

    // Helper methods

    private Set<String> parseSet(String param) {
        if (param == null || param.isEmpty()) {
            return new HashSet<>();
        }
        return new HashSet<>(Arrays.asList(param.split(",")));
    }

    private Long parseLong(String param) {
        if (param == null || param.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(param);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private int parseInt(String param, int defaultValue) {
        if (param == null || param.isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(param);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}

