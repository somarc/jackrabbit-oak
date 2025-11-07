/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
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
import org.apache.jackrabbit.oak.segment.SegmentNotFoundException;
import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;

/**
 * HTTP server that exposes Oak segments over HTTP.
 * 
 * <p>This server wraps a FileStore and provides HTTP access to its segments.
 * Used by HttpSegmentStore clients to fetch segments remotely.</p>
 * 
 * <p><strong>Endpoints:</strong></p>
 * <ul>
 *   <li>GET /segments/{segmentId} - Fetch segment bytes</li>
 *   <li>HEAD /segments/{segmentId} - Check if segment exists</li>
 *   <li>GET /health - Health check</li>
 * </ul>
 */
public class SegmentHttpServer {
    
    private static final Logger LOG = LoggerFactory.getLogger(SegmentHttpServer.class);
    
    private final FileStore fileStore;
    private final Server server;
    private final int port;
    
    /**
     * Creates a new segment HTTP server.
     * 
     * @param fileStore The FileStore to expose via HTTP
     * @param port The port to listen on
     */
    public SegmentHttpServer(FileStore fileStore, int port) {
        this.fileStore = fileStore;
        this.port = port;
        this.server = new Server(port);
        this.server.setHandler(new SegmentHandler());
    }
    
    /**
     * Starts the HTTP server.
     */
    public void start() throws Exception {
        LOG.info("===========================================");
        LOG.info("  Segment HTTP Server Starting");
        LOG.info("  Port: {}", port);
        LOG.info("===========================================");
        
        server.start();
        
        LOG.info("✅ Segment HTTP Server started");
        LOG.info("   Endpoints:");
        LOG.info("   - GET  http://localhost:{}/segments/{{segmentId}}", port);
        LOG.info("   - HEAD http://localhost:{}/segments/{{segmentId}}", port);
        LOG.info("   - GET  http://localhost:{}/health", port);
    }
    
    /**
     * Stops the HTTP server.
     */
    public void stop() throws Exception {
        LOG.info("Stopping Segment HTTP Server...");
        server.stop();
        LOG.info("✅ Segment HTTP Server stopped");
    }
    
    /**
     * HTTP request handler for segment operations.
     */
    private class SegmentHandler extends AbstractHandler {
        
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, 
                          HttpServletResponse response) throws IOException, ServletException {
            
            String method = request.getMethod();
            String path = request.getPathInfo();
            
            LOG.debug("HTTP {} {}", method, path);
            
            baseRequest.setHandled(true);
            
            // Health check endpoint
            if ("/health".equals(path) && "GET".equals(method)) {
                handleHealthCheck(response);
                return;
            }
            
            // Segment endpoints: /segments/{segmentId}
            if (path != null && path.startsWith("/segments/")) {
                String segmentIdStr = path.substring("/segments/".length());
                
                if ("HEAD".equals(method)) {
                    handleSegmentExists(segmentIdStr, response);
                } else if ("GET".equals(method)) {
                    handleSegmentFetch(segmentIdStr, response);
                } else {
                    response.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
                }
                return;
            }
            
            // Unknown endpoint
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
        }
        
        /**
         * Handles health check requests.
         */
        private void handleHealthCheck(HttpServletResponse response) throws IOException {
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType("application/json");
            response.getWriter().write("{\"status\":\"ok\",\"store\":\"ready\"}");
        }
        
        /**
         * Handles HEAD requests to check if a segment exists.
         */
        private void handleSegmentExists(String segmentIdStr, HttpServletResponse response) {
            try {
                // Parse segment ID
                SegmentId segmentId = parseSegmentId(segmentIdStr);
                
                // Check if segment exists
                boolean exists = fileStore.containsSegment(segmentId);
                
                if (exists) {
                    response.setStatus(HttpServletResponse.SC_OK);
                    LOG.debug("Segment {} exists", segmentIdStr);
                } else {
                    response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                    LOG.debug("Segment {} not found", segmentIdStr);
                }
                
            } catch (Exception e) {
                LOG.error("Error checking segment existence: " + segmentIdStr, e);
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            }
        }
        
        /**
         * Handles GET requests to fetch segment data.
         */
        private void handleSegmentFetch(String segmentIdStr, HttpServletResponse response) throws IOException {
            try {
                // Parse segment ID
                SegmentId segmentId = parseSegmentId(segmentIdStr);
                
                // Read segment from FileStore
                Segment segment = fileStore.readSegment(segmentId);
                
                // Serialize segment to bytes
                byte[] segmentBytes = serializeSegment(segment);
                
                // Send response
                response.setStatus(HttpServletResponse.SC_OK);
                response.setContentType("application/octet-stream");
                response.setContentLength(segmentBytes.length);
                
                try (OutputStream out = response.getOutputStream()) {
                    out.write(segmentBytes);
                }
                
                LOG.info("✅ Served segment {} ({} bytes)", segmentIdStr, segmentBytes.length);
                
            } catch (SegmentNotFoundException e) {
                LOG.warn("Segment {} not found", segmentIdStr);
                response.sendError(HttpServletResponse.SC_NOT_FOUND, "Segment not found");
                
            } catch (Exception e) {
                LOG.error("Error fetching segment: " + segmentIdStr, e);
                response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
            }
        }
        
        /**
         * Parses a segment ID from its string representation.
         * 
         * <p>TODO: Implement proper SegmentId parsing. This requires access to
         * the SegmentStore's SegmentIdFactory.</p>
         */
        private SegmentId parseSegmentId(String segmentIdStr) {
            // TODO: Implement proper segment ID parsing
            // For now, this is a placeholder
            throw new UnsupportedOperationException("SegmentId parsing not yet implemented");
        }
        
        /**
         * Serializes a segment to bytes for HTTP transmission.
         * 
         * <p>TODO: Implement proper segment serialization. This may involve
         * accessing the segment's underlying byte buffer.</p>
         */
        private byte[] serializeSegment(Segment segment) {
            // TODO: Implement proper segment serialization
            // For now, this is a placeholder
            throw new UnsupportedOperationException("Segment serialization not yet implemented");
        }
    }
}

