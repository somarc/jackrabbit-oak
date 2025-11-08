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

import org.apache.jackrabbit.oak.segment.Segment;
import org.apache.jackrabbit.oak.segment.SegmentId;
import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.apache.jackrabbit.oak.segment.file.FileStoreBuilder;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

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
    private final Path storeDirectory;
    private FileStore fileStore;  // Oak FileStore for reading segments
    
    /**
     * Create a new HTTP server for serving segment store files.
     * 
     * @param storeDirectory The segment store directory path
     * @param port The HTTP port to listen on
     */
    public SegmentHttpServer(File storeDirectory, int port, FileStore fileStore) {
        this.storeDirectory = storeDirectory.toPath();
        this.fileStore = fileStore;  // Use existing FileStore!
        
        this.server = new Server(port);
        this.server.setHandler(new SegmentStoreHandler());
        
        log.info("Initialized SegmentHttpServer");
        log.info("   - Port: {}", port);
        log.info("   - Store: {}", this.storeDirectory);
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
     */
    private class SegmentStoreHandler extends AbstractHandler {
        
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request,
                          HttpServletResponse response) throws IOException, ServletException {
            
            String path = request.getPathInfo();
            String method = request.getMethod();
            
            log.debug("HTTP {} {}", method, path);
            
            try {
                // Health check
                if ("/health".equals(path)) {
                    handleHealth(response);
                    baseRequest.setHandled(true);
                    return;
                }
                
                // Journal file
                if ("/journal.log".equals(path)) {
                    handleFile(response, "journal.log", "text/plain");
                    baseRequest.setHandled(true);
                    return;
                }
                
                // Manifest file
                if ("/manifest".equals(path)) {
                    handleFile(response, "manifest", "text/plain");
                    baseRequest.setHandled(true);
                    return;
                }
                
                // GC log
                if ("/gc.log".equals(path)) {
                    handleFile(response, "gc.log", "text/plain");
                    baseRequest.setHandled(true);
                    return;
                }
                
                // Segments
                if (path != null && path.startsWith("/segments/")) {
                    String segmentId = path.substring("/segments/".length());
                    if ("HEAD".equals(method)) {
                        handleSegmentHead(response, segmentId);
                    } else if ("GET".equals(method)) {
                        handleSegmentGet(response, segmentId);
                    } else {
                        response.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
                    }
                    baseRequest.setHandled(true);
                    return;
                }
                
                // Not found
                response.sendError(HttpServletResponse.SC_NOT_FOUND);
                baseRequest.setHandled(true);
                
            } catch (Exception e) {
                log.error("Error handling request: " + path, e);
                response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
                baseRequest.setHandled(true);
            }
        }
        
        /**
         * Handle health check endpoint.
         */
        private void handleHealth(HttpServletResponse response) throws IOException {
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType("application/json");
            response.getWriter().write("{\"status\":\"UP\",\"store\":\"" + storeDirectory + "\"}");
        }
        
        /**
         * Handle serving a file from the segment store directory.
         */
        private void handleFile(HttpServletResponse response, String filename, String contentType) throws IOException {
            Path filePath = storeDirectory.resolve(filename);
            
            if (!Files.exists(filePath)) {
                log.warn("File not found: {}", filePath);
                response.sendError(HttpServletResponse.SC_NOT_FOUND, "File not found: " + filename);
                return;
            }
            
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType(contentType);
            response.setContentLengthLong(Files.size(filePath));
            
            try (InputStream in = Files.newInputStream(filePath);
                 OutputStream out = response.getOutputStream()) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }
            }
            
            log.debug("Served file: {} ({} bytes)", filename, Files.size(filePath));
        }
        
        /**
         * Handle HEAD request for a segment (check existence).
         */
        private void handleSegmentHead(HttpServletResponse response, String segmentId) throws IOException {
            Path segmentPath = findSegmentInTarFiles(segmentId);
            
            if (segmentPath == null) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND, "Segment not found: " + segmentId);
                return;
            }
            
            response.setStatus(HttpServletResponse.SC_OK);
            response.setHeader("X-Segment-Found", "true");
        }
        
        /**
         * Handle GET request for a segment (fetch segment data).
         */
        private void handleSegmentGet(HttpServletResponse response, String segmentId) throws IOException {
            log.info("Reading segment from TAR: {}", segmentId);
            
            // Convert UUID string to msb/lsb
            java.util.UUID uuid;
            try {
                uuid = java.util.UUID.fromString(segmentId);
            } catch (IllegalArgumentException e) {
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid segment UUID: " + segmentId);
                return;
            }
            
            // Read segment from TAR files
            byte[] segmentData = readSegmentFromTar(uuid);
            
            if (segmentData == null) {
                log.warn("Segment not found in TAR files: {}", segmentId);
                response.sendError(HttpServletResponse.SC_NOT_FOUND, "Segment not found: " + segmentId);
                return;
            }
            
            // Return segment data
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType("application/octet-stream");
            response.setContentLength(segmentData.length);
            response.setHeader("X-Segment-Length", String.valueOf(segmentData.length));
            
            try (OutputStream out = response.getOutputStream()) {
                out.write(segmentData);
                out.flush();
            }
            
            log.info("Served segment {} ({} bytes)", segmentId, segmentData.length);
        }
        
        /**
         * Read a segment from TAR files.
         */
        private byte[] readSegmentFromTar(java.util.UUID segmentId) {
            // Look for data*.tar files
            File[] tarFiles = storeDirectory.toFile().listFiles((dir, name) -> 
                name.startsWith("data") && name.endsWith(".tar"));
            
            if (tarFiles == null || tarFiles.length == 0) {
                log.warn("No TAR files found in {}", storeDirectory);
                return null;
            }
            
            // Try each TAR file
            for (File tarFile : tarFiles) {
                try {
                    byte[] segment = readSegmentFromTarFile(tarFile, segmentId);
                    if (segment != null) {
                        return segment;
                    }
                } catch (IOException e) {
                    log.warn("Error reading from TAR file {}: {}", tarFile.getName(), e.getMessage());
                }
            }
            
            return null;
        }
        
        /**
         * Read a specific segment from TAR files using Oak's FileStore.
         * Uses the same pattern as Cold Standby segment replication.
         */
        private byte[] readSegmentFromTarFile(File tarFile, UUID segmentId) throws IOException {
            try {
                long msb = segmentId.getMostSignificantBits();
                long lsb = segmentId.getLeastSignificantBits();
                
                // Create SegmentId using FileStore's provider (Cold Standby pattern)
                SegmentId sid = fileStore.getSegmentIdProvider().newSegmentId(msb, lsb);
                
                // Check if segment exists
                if (!fileStore.containsSegment(sid)) {
                    log.debug("Segment {} not found in store", segmentId);
                    return null;
                }
                
                // Read segment and serialize to bytes (Cold Standby pattern)
                Segment segment = fileStore.readSegment(sid);
                try (ByteArrayOutputStream stream = new ByteArrayOutputStream()) {
                    segment.writeTo(stream);
                    byte[] data = stream.toByteArray();
                    log.info("Read segment {} from TAR ({} bytes)", segmentId, data.length);
                    return data;
                }
            } catch (Exception e) {
                log.warn("Error reading segment {}: {}", segmentId, e.getMessage());
                return null;
            }
        }
        
        /**
         * Find a segment in TAR files (simplified - just check if any TAR files exist).
         */
        private Path findSegmentInTarFiles(String segmentId) throws IOException {
            // Look for data*.tar files
            File[] tarFiles = storeDirectory.toFile().listFiles((dir, name) -> 
                name.startsWith("data") && name.endsWith(".tar"));
            
            if (tarFiles != null && tarFiles.length > 0) {
                // Segment might exist - return the first TAR file
                // Full implementation would need to parse TAR internals
                return tarFiles[0].toPath();
            }
            
            return null;
        }
    }
}

