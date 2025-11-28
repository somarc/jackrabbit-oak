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
import org.apache.jackrabbit.oak.segment.http.server.binary.CidMappingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Optional;

/**
 * HTTP API handler for CID (Content Identifier) operations.
 * 
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>GET /api/cid/{oakBlobId} - Get IPFS CID for Oak blob ID</li>
 *   <li>GET /api/cid/reverse/{ipfsCid} - Get Oak blob ID for IPFS CID</li>
 *   <li>GET /api/cid/gateway/{oakBlobId} - Get IPFS gateway URL</li>
 *   <li>GET /api/cid/stats - Get CID mapping statistics</li>
 * </ul>
 * 
 * <h2>Example Responses</h2>
 * <pre>
 * GET /api/cid/ed06f9cbf0fe878013ccb266170e6b3ba676933a6f065675cc0115c840bf1442
 * {
 *   "oakBlobId": "ed06f9cb...",
 *   "ipfsCid": "Qmf4F3CWU6Ly958TFiR8BRP18gwvW3Xsj2yXu5DqkonWc3",
 *   "gatewayUrl": "https://ipfs.io/ipfs/Qmf4F3...",
 *   "localUrl": "http://localhost:8080/ipfs/Qmf4F3..."
 * }
 * </pre>
 */
public class CidApiHandler {

    private static final Logger log = LoggerFactory.getLogger(CidApiHandler.class);

    private final ServerContext context;

    public CidApiHandler(ServerContext context) {
        this.context = context;
    }

    /**
     * Handle GET /api/cid/{oakBlobId}
     */
    public void handleGetCid(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String path = request.getPathInfo();
        if (path == null || path.length() < 10) {
            sendError(response, 400, "Missing Oak blob ID in path");
            return;
        }

        // Extract blob ID from path: /api/cid/{blobId}
        String oakBlobId = path.substring(path.lastIndexOf('/') + 1);

        if (context.cidMappingService == null) {
            sendError(response, 503, "CID mapping service not available");
            return;
        }

        Optional<String> cid = context.cidMappingService.getCid(oakBlobId);

        if (cid.isEmpty()) {
            sendError(response, 404, "No CID found for Oak blob ID: " + oakBlobId);
            return;
        }

        StringBuilder json = new StringBuilder();
        json.append("{");
        json.append("\"oakBlobId\":\"").append(escapeJson(oakBlobId)).append("\",");
        json.append("\"ipfsCid\":\"").append(escapeJson(cid.get())).append("\",");
        json.append("\"gatewayUrl\":\"https://ipfs.io/ipfs/").append(escapeJson(cid.get())).append("\",");
        json.append("\"localUrl\":\"http://localhost:8080/ipfs/").append(escapeJson(cid.get())).append("\"");
        json.append("}");

        sendJson(response, 200, json.toString());
    }

    /**
     * Handle GET /api/cid/reverse/{ipfsCid}
     */
    public void handleReverseLookup(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String path = request.getPathInfo();
        if (path == null || !path.contains("/reverse/")) {
            sendError(response, 400, "Missing IPFS CID in path");
            return;
        }

        String ipfsCid = path.substring(path.lastIndexOf('/') + 1);

        if (context.cidMappingService == null) {
            sendError(response, 503, "CID mapping service not available");
            return;
        }

        Optional<String> oakBlobId = context.cidMappingService.getOakBlobId(ipfsCid);

        if (oakBlobId.isEmpty()) {
            sendError(response, 404, "No Oak blob ID found for CID: " + ipfsCid);
            return;
        }

        StringBuilder json = new StringBuilder();
        json.append("{");
        json.append("\"ipfsCid\":\"").append(escapeJson(ipfsCid)).append("\",");
        json.append("\"oakBlobId\":\"").append(escapeJson(oakBlobId.get())).append("\"");
        json.append("}");

        sendJson(response, 200, json.toString());
    }

    /**
     * Handle GET /api/cid/gateway/{oakBlobId}
     * Returns a redirect to the IPFS gateway.
     */
    public void handleGatewayRedirect(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String path = request.getPathInfo();
        if (path == null || !path.contains("/gateway/")) {
            sendError(response, 400, "Missing Oak blob ID in path");
            return;
        }

        String oakBlobId = path.substring(path.lastIndexOf('/') + 1);

        if (context.cidMappingService == null) {
            sendError(response, 503, "CID mapping service not available");
            return;
        }

        Optional<String> gatewayUrl = context.cidMappingService.getGatewayUrl(oakBlobId);

        if (gatewayUrl.isEmpty()) {
            sendError(response, 404, "No CID found for Oak blob ID: " + oakBlobId);
            return;
        }

        // Redirect to IPFS gateway
        response.sendRedirect(gatewayUrl.get());
    }

    /**
     * Handle GET /api/cid/stats
     */
    public void handleStats(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (context.cidMappingService == null) {
            sendError(response, 503, "CID mapping service not available");
            return;
        }

        CidMappingService.CidMappingStats stats = context.cidMappingService.getStats();
        sendJson(response, 200, stats.toJson());
    }

    /**
     * Send JSON response.
     */
    private void sendJson(HttpServletResponse response, int statusCode, String json) throws IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setStatus(statusCode);

        try (PrintWriter writer = response.getWriter()) {
            writer.write(json);
        }
    }

    /**
     * Send error response.
     */
    private void sendError(HttpServletResponse response, int statusCode, String message) throws IOException {
        StringBuilder json = new StringBuilder();
        json.append("{");
        json.append("\"error\":\"").append(escapeJson(message)).append("\",");
        json.append("\"status\":").append(statusCode);
        json.append("}");

        sendJson(response, statusCode, json.toString());
    }

    /**
     * Escape JSON special characters.
     */
    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}

