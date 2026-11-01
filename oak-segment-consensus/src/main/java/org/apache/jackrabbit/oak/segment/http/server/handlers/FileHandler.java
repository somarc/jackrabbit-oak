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
    
    public FileHandler(FileStore fileStore, Path storeDirectory) {
        this.fileStore = fileStore;
        this.storeDirectory = storeDirectory;
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
            in.transferTo(out);
        }
        
        log.info("📄 File GET: {} FROM {}:{} ({} bytes)", filename, remoteAddr, remotePort, fileSize);
    }
    
    /**
     * Handle HEAD request for a segment: 200 only when the FileStore holds it, exactly as GET would serve it.
     */
    public void handleSegmentHead(HttpServletResponse response, String segmentId) throws IOException {
        SegmentId sid = parseSegmentId(response, segmentId);
        if (sid == null) {
            return;
        }
        if (!fileStore.containsSegment(sid)) {
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
        
        SegmentId sid = parseSegmentId(response, segmentId);
        if (sid == null) {
            return;
        }
        
        byte[] segmentData = readSegment(sid);
        
        if (segmentData == null) {
            log.warn("Segment not found: {}", segmentId);
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
    
    private SegmentId parseSegmentId(HttpServletResponse response, String segmentId) throws IOException {
        UUID uuid;
        try {
            uuid = UUID.fromString(segmentId);
        } catch (IllegalArgumentException e) {
            ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_BAD_REQUEST, "Invalid segment UUID: " + segmentId);
            return null;
        }
        return fileStore.getSegmentIdProvider()
            .newSegmentId(uuid.getMostSignificantBits(), uuid.getLeastSignificantBits());
    }
    
    /**
     * Serialize a segment from the live FileStore, as Cold Standby does; works for any segment persistence.
     */
    private byte[] readSegment(SegmentId sid) {
        try {
            if (!fileStore.containsSegment(sid)) {
                return null;
            }
            Segment segment = fileStore.readSegment(sid);
            try (ByteArrayOutputStream stream = new ByteArrayOutputStream()) {
                segment.writeTo(stream);
                return stream.toByteArray();
            }
        } catch (Exception e) {
            log.warn("Error reading segment {}: {}", sid, e.getMessage());
            return null;
        }
    }
}

