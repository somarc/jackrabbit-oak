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
package org.apache.jackrabbit.oak.segment.consensus.gc;

import org.apache.jackrabbit.oak.segment.RecordId;
import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.apache.jackrabbit.oak.segment.file.tar.TarFiles;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Estimates GC cost by calculating reclaimable segment size.
 * 
 * <p>Traverses segment graph from HEAD to identify reachable segments,
 * then calculates size of unreachable segments and estimates cost in USDC.
 * Designed for TB-scale repositories with efficient memory usage.
 * 
 * <p>Example usage:
 * <pre>
 * GCCostEstimator estimator = new GCCostEstimator(fileStore, tarFiles);
 * GCCostEstimate estimate = estimator.estimateCost(null); // Use HEAD
 * System.out.println("Reclaimable: " + estimate.getReclaimableSizeMB() + " MB");
 * System.out.println("Cost: " + estimate.getEstimatedCostUSDC() + " USDC");
 * </pre>
 */
public class GCCostEstimator {
    private static final Logger log = LoggerFactory.getLogger(GCCostEstimator.class);
    
    private final FileStore fileStore;
    private final TarFiles tarFiles;
    private BigDecimal usdcPerMB;
    
    /**
     * Create estimator with default USDC rate ($0.10 per MB).
     */
    public GCCostEstimator(FileStore fileStore, TarFiles tarFiles) {
        this(fileStore, tarFiles, new BigDecimal("0.10")); // Default: $0.10 per MB
    }
    
    /**
     * Create estimator with custom USDC rate.
     * 
     * @param fileStore FileStore instance for accessing HEAD and segments
     * @param tarFiles TarFiles instance for graph traversal
     * @param usdcPerMB USDC cost per MB (configurable, future: pull from gas oracle)
     */
    public GCCostEstimator(FileStore fileStore, TarFiles tarFiles, BigDecimal usdcPerMB) {
        this.fileStore = fileStore;
        this.tarFiles = tarFiles;
        this.usdcPerMB = usdcPerMB;
        log.debug("GCCostEstimator initialized with USDC rate: {} per MB", usdcPerMB);
    }
    
    /**
     * Estimate GC cost for reclaiming segments not reachable from HEAD.
     * 
     * @param targetRevision Optional target revision string (null = use HEAD)
     * @return GC cost estimate
     * @throws IOException if graph traversal fails
     * @throws IllegalArgumentException if targetRevision format is invalid
     */
    public GCCostEstimate estimateCost(@Nullable String targetRevision) throws IOException {
        log.info("Starting GC cost estimation (targetRevision: {})", targetRevision != null ? targetRevision : "HEAD");
        long startTime = System.currentTimeMillis();
        
        try {
            // 1. Get HEAD revision
            RecordId head = getHeadRevision(targetRevision);
            UUID headUuid = head.asUUID();
            log.debug("HEAD revision UUID: {}", headUuid);
            
            // 2. Traverse segment graph from HEAD
            log.info("Traversing segment graph from HEAD...");
            Set<UUID> reachableSegments = findReachableSegments(head);
            log.info("Found {} reachable segments", reachableSegments.size());
            
            // 3. Get all segments
            log.debug("Collecting all segment UUIDs...");
            Set<UUID> allSegments = getAllSegments();
            log.info("Total segments in repository: {}", allSegments.size());
            
            // 4. Calculate reclaimable segments
            Set<UUID> reclaimableSegments = new HashSet<>(allSegments);
            reclaimableSegments.removeAll(reachableSegments);
            log.info("Reclaimable segments: {} ({}%)", 
                reclaimableSegments.size(),
                allSegments.isEmpty() ? 0.0 : (reclaimableSegments.size() * 100.0 / allSegments.size()));
            
            // 5. Calculate sizes
            long totalSizeBytes = calculateTotalSize();
            long reclaimableSizeBytes = calculateReclaimableSize(reclaimableSegments);
            
            // 6. Estimate cost
            BigDecimal reclaimableSizeMB = BigDecimal.valueOf(reclaimableSizeBytes)
                .divide(BigDecimal.valueOf(1024 * 1024), 2, RoundingMode.HALF_UP);
            BigDecimal estimatedCostUSDC = reclaimableSizeMB.multiply(usdcPerMB);
            
            // 7. Group by TAR file
            log.debug("Calculating reclaimable size by TAR file...");
            Map<String, Long> reclaimableByTarFile = calculateReclaimableByFile(reclaimableSegments);
            
            long elapsed = System.currentTimeMillis() - startTime;
            log.info("GC cost estimation complete in {} ms: {} MB reclaimable, {} USDC estimated",
                elapsed, reclaimableSizeMB, estimatedCostUSDC);
            
            return new GCCostEstimate(
                reclaimableSegments.size(),
                reclaimableSizeBytes,
                allSegments.size(),
                totalSizeBytes,
                estimatedCostUSDC,
                reclaimableByTarFile
            );
            
        } catch (IllegalArgumentException e) {
            log.error("Invalid revision format: {}", targetRevision, e);
            throw e;
        } catch (IOException e) {
            log.error("GC cost estimation failed", e);
            throw e;
        }
    }
    
    /**
     * Get HEAD revision, optionally parsing targetRevision string.
     * 
     * @param targetRevision Optional revision string (null = use HEAD)
     * @return RecordId for HEAD or target revision
     * @throws IllegalArgumentException if targetRevision format is invalid
     */
    private RecordId getHeadRevision(@Nullable String targetRevision) {
        if (targetRevision == null) {
            // getHead() returns SegmentNodeState, which extends Record and has getRecordId()
            return fileStore.getHead().getRecordId();
        }
        
        // Parse targetRevision string (format: "uuid:offset" or "uuid.offsetHex")
        try {
            return RecordId.fromString(fileStore.getSegmentIdProvider(), targetRevision);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid revision format: " + targetRevision, e);
        }
    }
    
    /**
     * Traverse segment graph from HEAD using BFS to find all reachable segments.
     * 
     * <p>Uses HashSet for visited tracking (built-in deduplication).
     * Memory: ~200-300 MB for 4M segments (UUID ~16 bytes + overhead).
     * 
     * <p>Key implementation detail: getGraph() takes fileName, not UUID.
     * We iterate over all TAR files from getIndices() to build complete graph.
     * 
     * @param head HEAD revision RecordId
     * @return Set of all reachable segment UUIDs
     * @throws IOException if graph traversal fails
     */
    private Set<UUID> findReachableSegments(RecordId head) throws IOException {
        Set<UUID> reachable = new HashSet<>();
        Queue<UUID> queue = new LinkedList<>();
        
        // Start from HEAD segment UUID
        UUID headUuid = head.asUUID();
        queue.add(headUuid);
        reachable.add(headUuid);
        log.debug("Starting BFS traversal from HEAD segment: {}", headUuid);
        
        // Get all TAR file names and load their graphs
        Map<String, Set<UUID>> indices = tarFiles.getIndices();
        Map<String, Map<UUID, Set<UUID>>> tarGraphs = new HashMap<>();
        
        log.debug("Loading graphs for {} TAR files...", indices.size());
        int loadedCount = 0;
        for (String fileName : indices.keySet()) {
            try {
                Map<UUID, Set<UUID>> graph = tarFiles.getGraph(fileName);
                tarGraphs.put(fileName, graph);
                loadedCount++;
                if (log.isDebugEnabled() && loadedCount % 100 == 0) {
                    log.debug("Loaded graphs for {} TAR files...", loadedCount);
                }
            } catch (IOException e) {
                log.warn("Failed to load graph for {}: {}", fileName, e.getMessage());
                // Continue with other TAR files
            }
        }
        log.info("Loaded graphs for {}/{} TAR files", loadedCount, indices.size());
        
        // BFS traversal
        int visitedCount = 0;
        while (!queue.isEmpty()) {
            UUID current = queue.poll();
            visitedCount++;
            
            if (log.isDebugEnabled() && visitedCount % 10000 == 0) {
                log.debug("BFS traversal: visited {} segments, queue size: {}", visitedCount, queue.size());
            }
            
            // Find references in all TAR files
            for (Map<UUID, Set<UUID>> graph : tarGraphs.values()) {
                Set<UUID> references = graph.get(current);
                if (references != null) {
                    for (UUID ref : references) {
                        if (reachable.add(ref)) { // HashSet.add() returns true if new (deduplication)
                            queue.add(ref);
                        }
                    }
                }
            }
        }
        
        log.debug("BFS traversal complete: visited {} segments, found {} reachable", visitedCount, reachable.size());
        return reachable;
    }
    
    /**
     * Get all segment UUIDs from all TAR files.
     * 
     * @return Set of all segment UUIDs in repository
     * @throws IOException if segment enumeration fails
     */
    private Set<UUID> getAllSegments() throws IOException {
        Set<UUID> allSegments = new HashSet<>();
        Iterable<UUID> segmentIds = tarFiles.getSegmentIds();
        for (UUID uuid : segmentIds) {
            allSegments.add(uuid);
        }
        return allSegments;
    }
    
    /**
     * Calculate total size of all segments.
     * 
     * <p>Note: This is an approximation based on average segment size.
     * Full implementation would sum actual segment sizes from TAR indices.
     * 
     * @return Total size in bytes
     * @throws IOException if size calculation fails
     */
    private long calculateTotalSize() throws IOException {
        // Approximate: 256 KB per segment (average)
        // Full implementation would iterate TAR files and sum actual segment sizes
        Map<String, Set<UUID>> indices = tarFiles.getIndices();
        long totalSegments = 0;
        for (Set<UUID> segments : indices.values()) {
            totalSegments += segments.size();
        }
        return totalSegments * 256L * 1024L; // Approximate: 256 KB per segment
    }
    
    /**
     * Calculate total size of reclaimable segments.
     * 
     * <p>Note: This is an approximation based on average segment size.
     * Full implementation would look up actual segment sizes from TAR indices.
     * 
     * @param reclaimableSegments Set of reclaimable segment UUIDs
     * @return Total reclaimable size in bytes
     */
    private long calculateReclaimableSize(Set<UUID> reclaimableSegments) {
        // Approximate: 256 KB per segment (average)
        // Full implementation would look up actual segment sizes from TAR indices
        return reclaimableSegments.size() * 256L * 1024L;
    }
    
    /**
     * Group reclaimable segments by TAR file.
     * 
     * @param reclaimableSegments Set of reclaimable segment UUIDs
     * @return Map of TAR file name to reclaimable size in bytes
     * @throws IOException if TAR file enumeration fails
     */
    private Map<String, Long> calculateReclaimableByFile(Set<UUID> reclaimableSegments) throws IOException {
        Map<String, Long> byFile = new HashMap<>();
        Map<String, Set<UUID>> indices = tarFiles.getIndices();
        
        for (Map.Entry<String, Set<UUID>> entry : indices.entrySet()) {
            String fileName = entry.getKey();
            Set<UUID> fileSegments = entry.getValue();
            
            long reclaimableInFile = fileSegments.stream()
                .filter(reclaimableSegments::contains)
                .count();
            
            if (reclaimableInFile > 0) {
                // Approximate: 256 KB per segment
                byFile.put(fileName, reclaimableInFile * 256L * 1024L);
            }
        }
        
        return byFile;
    }
    
    /**
     * Set USDC per MB rate (for dynamic pricing).
     * 
     * <p>Future: Pull from Ethereum gas oracle via Web3j.
     * 
     * @param usdcPerMB New USDC cost per MB
     */
    public void setUsdcPerMB(BigDecimal usdcPerMB) {
        this.usdcPerMB = usdcPerMB;
        log.info("Updated USDC rate to {} per MB", usdcPerMB);
    }
    
    /**
     * Get current USDC per MB rate.
     * 
     * @return Current USDC cost per MB
     */
    public BigDecimal getUsdcPerMB() {
        return usdcPerMB;
    }
}

