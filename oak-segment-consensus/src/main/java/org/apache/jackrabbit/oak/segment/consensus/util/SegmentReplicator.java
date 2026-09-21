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
package org.apache.jackrabbit.oak.segment.consensus.util;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.apache.jackrabbit.oak.segment.SegmentId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility for replicating segments from remote validators.
 * Used by both DAG and Leader consensus engines.
 */
public class SegmentReplicator {
    
    private static final Logger log = LoggerFactory.getLogger(SegmentReplicator.class);
    
    private final FileStore fileStore;
    
    public SegmentReplicator(FileStore fileStore) {
        this.fileStore = fileStore;
    }
    
    /**
     * Fetch all missing segments for a given HEAD from a remote validator.
     * 
     * @param targetHeadStr The RecordId string of the target HEAD
     * @param sourceUrl The URL of the source validator to fetch from
     * @return Number of segments fetched
     * @throws Exception if fetching fails
     */
    public int fetchMissingSegmentsForHead(String targetHeadStr, String sourceUrl) throws Exception {
        log.info("      🔄 Recursively fetching segments for HEAD: {}...", targetHeadStr.substring(0, 12));
        
        // Parse RecordId to get segment UUID
        String rootSegmentId = parseSegmentId(targetHeadStr);
        
        // Use a set to track visited segments (avoid duplicates)
        Set<String> visited = new HashSet<>();
        List<String> fetchOrder = new ArrayList<>();
        
        // DFS traversal to find all referenced segments
        fetchSegmentRecursive(rootSegmentId, sourceUrl, visited, fetchOrder);
        
        log.info("      📊 Segment graph traversal complete: {} segments", fetchOrder.size());
        
        // Now fetch and write segments in order (leaves first, root last)
        int successCount = 0;
        for (String segmentId : fetchOrder) {
            try {
                byte[] segmentData = fetchSegmentBytes(segmentId, sourceUrl);
                
                // Validate segment data before writing
                org.apache.jackrabbit.oak.commons.Buffer buffer = 
                    org.apache.jackrabbit.oak.commons.Buffer.wrap(segmentData);
                
                // Use Oak's SegmentData to parse and validate
                org.apache.jackrabbit.oak.segment.data.SegmentData.newSegmentData(buffer);
                
                // Write to our FileStore
                UUID uuid = UUID.fromString(segmentId);
                SegmentId oakSegmentId = 
                    fileStore.getSegmentIdProvider().newSegmentId(
                        uuid.getMostSignificantBits(),
                        uuid.getLeastSignificantBits()
                    );
                fileStore.writeSegment(oakSegmentId, segmentData, 0, segmentData.length);
                
                successCount++;
                
            } catch (Exception e) {
                log.warn("      ⚠️  Failed to fetch segment {}: {}", 
                    segmentId.substring(0, 8), e.getMessage());
            }
        }
        
        log.info("      ✅ Successfully replicated {}/{} segments", successCount, fetchOrder.size());
        
        // Flush to ensure all segments are persisted
        fileStore.flush();
        
        return successCount;
    }
    
    /**
     * Recursively fetch segment and its dependencies.
     */
    private void fetchSegmentRecursive(String segmentId, String sourceUrl, 
            Set<String> visited, List<String> fetchOrder) throws Exception {
        
        if (visited.contains(segmentId)) {
            return; // Already processed
        }
        
        visited.add(segmentId);
        
        // Check if we already have this segment locally
        try {
            UUID uuid = UUID.fromString(segmentId);
            SegmentId oakSegmentId = 
                fileStore.getSegmentIdProvider().newSegmentId(
                    uuid.getMostSignificantBits(),
                    uuid.getLeastSignificantBits()
                );
            if (fileStore.containsSegment(oakSegmentId)) {
                log.debug("      ⏭️  Segment {} already exists locally", segmentId.substring(0, 8));
                return; // Already have it
            }
        } catch (Exception e) {
            // Continue - we'll try to fetch it
        }
        
        // Fetch the segment data to parse its references
        byte[] segmentData = fetchSegmentBytes(segmentId, sourceUrl);
        
        if (segmentData != null && segmentData.length > 0) {
            try {
                // Parse to find referenced segments
                org.apache.jackrabbit.oak.commons.Buffer buffer = 
                    org.apache.jackrabbit.oak.commons.Buffer.wrap(segmentData);
                
                org.apache.jackrabbit.oak.segment.data.SegmentData parsed = 
                    org.apache.jackrabbit.oak.segment.data.SegmentData.newSegmentData(buffer);
                
                // Get count of referenced segments
                int refCount = parsed.getSegmentReferencesCount();
                
                // Recursively fetch referenced segments first (DFS - leaves before parents)
                for (int i = 0; i < refCount; i++) {
                    long msb = parsed.getSegmentReferenceMsb(i);
                    long lsb = parsed.getSegmentReferenceLsb(i);
                    UUID refUuid = new UUID(msb, lsb);
                    fetchSegmentRecursive(refUuid.toString(), sourceUrl, visited, fetchOrder);
                }
                
            } catch (Exception e) {
                log.warn("      ⚠️  Failed to parse segment {}: {}", 
                    segmentId.substring(0, 8), e.getMessage());
            }
        }
        
        // Add this segment to fetch order AFTER its dependencies
        fetchOrder.add(segmentId);
    }
    
    /**
     * Fetch raw segment bytes from a remote validator.
     */
    private byte[] fetchSegmentBytes(String segmentId, String sourceUrl) throws Exception {
        // Extract just the UUID part if needed
        String uuidPart = segmentId;
        if (segmentId.contains(".")) {
            uuidPart = segmentId.split("\\.")[0];
        }
        
        String segmentUrl = sourceUrl + "/segments/" + uuidPart;
        
        URL url = new URL(segmentUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        // Bypass ngrok warning page (free tier requirement)
        if (sourceUrl.contains("ngrok")) {
            conn.setRequestProperty("ngrok-skip-browser-warning", "true");
        }
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(10000);
        
        int responseCode = conn.getResponseCode();
        if (responseCode != 200) {
            throw new Exception("HTTP " + responseCode + " from " + segmentUrl);
        }
        
        // Read segment data
        InputStream in = conn.getInputStream();
        byte[] buffer = new byte[256 * 1024]; // Max segment size
        int bytesRead = 0;
        int totalRead = 0;
        
        while ((bytesRead = in.read(buffer, totalRead, buffer.length - totalRead)) != -1) {
            totalRead += bytesRead;
            if (totalRead >= buffer.length) {
                break; // Segment too large
            }
        }
        
        in.close();
        
        // Trim to actual size
        byte[] result = new byte[totalRead];
        System.arraycopy(buffer, 0, result, 0, totalRead);
        
        return result;
    }
    
    /**
     * Parse segment ID from various RecordId formats.
     */
    private String parseSegmentId(String recordId) {
        if (recordId.contains(":")) {
            // Format: "uuid:offset"
            return recordId.split(":")[0];
        } else if (recordId.contains(".")) {
            // Format: "uuid.offsetHex" (from toString10)
            return recordId.split("\\.")[0];
        } else {
            // Assume it's just a UUID
            return recordId;
        }
    }
}

