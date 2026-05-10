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

import org.apache.jackrabbit.oak.segment.Segment;
import org.apache.jackrabbit.oak.segment.SegmentId;
import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import org.apache.jackrabbit.oak.segment.http.server.util.ApiErrorUtil;

/**
 * Handler for file serving endpoints (journal.log, manifest, segments).
 * 
 * <p>Extracted from SegmentHttpServer to separate file serving concerns.</p>
 */
public class FileHandler {
    private static final Logger log = LoggerFactory.getLogger(FileHandler.class);
    
    private final FileStore fileStore;
    private final Path storeDirectory;
    private final Set<String> connectedPeers;  // For tracking peer connections
    
    public FileHandler(FileStore fileStore, Path storeDirectory, Set<String> connectedPeers) {
        this.fileStore = fileStore;
        this.storeDirectory = storeDirectory;
        this.connectedPeers = connectedPeers;
    }
    
    /**
     * Handle HEAD request for a file (returns headers only, no body).
     */
    public void handleFileHead(HttpServletResponse response, String filename, String contentType) throws IOException {
        Path filePath = storeDirectory.resolve(filename);
        
        if (!Files.exists(filePath)) {
            ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_NOT_FOUND, "File not found: " + filename);
            return;
        }
        
        long fileSize = Files.size(filePath);
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(contentType);
        response.setContentLengthLong(fileSize);
        // HEAD request - don't write body
    }
    
    /**
     * Handle serving a file from the segment store directory.
     */
    public void handleFile(HttpServletRequest request, HttpServletResponse response, 
                          String filename, String contentType) throws IOException {
        // Log requesting peer info
        String remoteAddr = request.getRemoteAddr();
        int remotePort = request.getRemotePort();
        
        Path filePath = storeDirectory.resolve(filename);
        
        if (!Files.exists(filePath)) {
            log.warn("File not found: {}", filePath);
            ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_NOT_FOUND, "File not found: " + filename);
            return;
        }
        
        long fileSize = Files.size(filePath);
        
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(contentType);
        response.setContentLengthLong(fileSize);
        
        try (InputStream in = Files.newInputStream(filePath);
             OutputStream out = response.getOutputStream()) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
        }
        
        log.info("📄 File GET: {} FROM {}:{} ({} bytes)", filename, remoteAddr, remotePort, fileSize);
    }
    
    /**
     * Handle HEAD request for a segment (check existence).
     */
    public void handleSegmentHead(HttpServletResponse response, String segmentId) throws IOException {
        Path segmentPath = findSegmentInTarFiles(segmentId);
        
        if (segmentPath == null) {
            ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_NOT_FOUND, "Segment not found: " + segmentId);
            return;
        }
        
        response.setStatus(HttpServletResponse.SC_OK);
        response.setHeader("X-Segment-Found", "true");
    }
    
    /**
     * Handle GET request for a segment (fetch segment data).
     */
    public void handleSegmentGet(HttpServletRequest request, HttpServletResponse response, 
                                 String segmentId) throws IOException {
        // Log requesting peer info
        String remoteAddr = request.getRemoteAddr();
        int remotePort = request.getRemotePort();
        String userAgent = request.getHeader("User-Agent");
        
        log.info("📦 Segment GET: {} FROM {}:{} [UA: {}]", 
                 segmentId, remoteAddr, remotePort, userAgent != null ? userAgent : "unknown");
        
        // Track connected peer (Sling Author mounting this store)
        if (!"localhost".equals(remoteAddr) && !"127.0.0.1".equals(remoteAddr)) {
            connectedPeers.add(remoteAddr + ":" + remotePort);
        }
        
        // Convert UUID string to msb/lsb
        UUID uuid;
        try {
            uuid = UUID.fromString(segmentId);
        } catch (IllegalArgumentException e) {
            ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_BAD_REQUEST, "Invalid segment UUID: " + segmentId);
            return;
        }
        
        // Read segment from TAR files
        byte[] segmentData = readSegmentFromTar(uuid);
        
        if (segmentData == null) {
            log.warn("Segment not found in TAR files: {}", segmentId);
            ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_NOT_FOUND, "Segment not found: " + segmentId);
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
    private byte[] readSegmentFromTar(UUID segmentId) {
        // Look for data*.tar files
        java.io.File[] tarFiles = storeDirectory.toFile().listFiles((dir, name) -> 
            name.startsWith("data") && name.endsWith(".tar"));
        
        if (tarFiles == null || tarFiles.length == 0) {
            log.warn("No TAR files found in {}", storeDirectory);
            return null;
        }
        
        // Try each TAR file
        for (java.io.File tarFile : tarFiles) {
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
    private byte[] readSegmentFromTarFile(java.io.File tarFile, UUID segmentId) throws IOException {
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
        java.io.File[] tarFiles = storeDirectory.toFile().listFiles((dir, name) -> 
            name.startsWith("data") && name.endsWith(".tar"));
        
        if (tarFiles != null && tarFiles.length > 0) {
            // Segment might exist - return the first TAR file
            // Full implementation would need to parse TAR internals
            return tarFiles[0].toPath();
        }
        
        return null;
    }
}

