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
package org.apache.jackrabbit.oak.segment.consensus.fragmentation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks TAR file fragmentation metrics per entity (wallet address).
 * 
 * <p>This service tracks which entities create which TAR files and calculates
 * fragmentation scores to identify entities that create excessive fragmentation.</p>
 * 
 * <p>Used for:
 * - Fragmentation tax calculation
 * - GC cost attribution
 * - Compaction cost attribution
 * - Economic externality mitigation</p>
 */
public class FragmentationTracker {
    
    private static final Logger log = LoggerFactory.getLogger(FragmentationTracker.class);
    
    private static final long MAX_TAR_FILE_SIZE = 256L * 1024 * 1024; // 256 MB
    private static final double SMALL_TAR_THRESHOLD = 0.10; // 10% of max size
    
    private final Map<String, EntityFragmentationMetrics> entityMetrics = new ConcurrentHashMap<>();
    private final Map<String, List<String>> entityTarFiles = new ConcurrentHashMap<>(); // wallet -> TAR file names
    private final Map<String, String> tarFileToEntity = new ConcurrentHashMap<>(); // TAR file -> wallet (for quick lookup)
    
    /**
     * Record a write operation that creates or appends to a TAR file.
     * 
     * @param walletAddress The wallet address of the entity performing the write
     * @param tarFileName The name of the TAR file (e.g., "data00001a.tar")
     * @param bytesWritten The number of bytes written to this TAR file
     */
    public void recordWrite(String walletAddress, String tarFileName, long bytesWritten) {
        if (walletAddress == null || walletAddress.isEmpty()) {
            log.warn("Cannot track fragmentation: wallet address is null or empty");
            return;
        }
        
        EntityFragmentationMetrics metrics = entityMetrics.computeIfAbsent(
            walletAddress,
            k -> new EntityFragmentationMetrics(walletAddress)
        );
        
        // Check if this is a new TAR file for this entity
        List<String> tarFiles = entityTarFiles.computeIfAbsent(walletAddress, k -> new ArrayList<>());
        boolean isNewTarFile = !tarFiles.contains(tarFileName);
        
        if (isNewTarFile) {
            metrics.tarFilesCreated++;
            tarFiles.add(tarFileName);
            tarFileToEntity.put(tarFileName, walletAddress);
            log.debug("📊 Entity {} created new TAR file: {} (total: {})", 
                walletAddress, tarFileName, metrics.tarFilesCreated);
        }
        
        // Update metrics
        metrics.totalBytesWritten += bytesWritten;
        metrics.averageTarFileSize = metrics.tarFilesCreated > 0 
            ? metrics.totalBytesWritten / metrics.tarFilesCreated 
            : 0;
        metrics.packingEfficiency = metrics.averageTarFileSize > 0
            ? (double) metrics.averageTarFileSize / MAX_TAR_FILE_SIZE * 100.0
            : 0.0;
        
        // Count small TAR files
        if (bytesWritten < (MAX_TAR_FILE_SIZE * SMALL_TAR_THRESHOLD)) {
            metrics.smallTarFileCount++;
        }
        
        // Calculate fragmentation score
        metrics.fragmentationScore = calculateFragmentationScore(metrics);
        metrics.lastWriteTimestamp = System.currentTimeMillis();
        
        log.debug("📊 Entity {} fragmentation: score={}, files={}, efficiency={:.1f}%", 
            walletAddress, metrics.fragmentationScore, metrics.tarFilesCreated, metrics.packingEfficiency);
    }
    
    /**
     * Calculate fragmentation score for an entity.
     * Higher score = more fragmentation.
     */
    private long calculateFragmentationScore(EntityFragmentationMetrics metrics) {
        // Base score: number of small TAR files
        long baseScore = metrics.smallTarFileCount * 100L;
        
        // Efficiency penalty: low packing efficiency
        double efficiencyPenalty = (1.0 - (metrics.packingEfficiency / 100.0)) * 50.0;
        
        // Scale penalty: many TAR files relative to data written
        double expectedTarFiles = metrics.totalBytesWritten > 0
            ? (double) metrics.totalBytesWritten / MAX_TAR_FILE_SIZE
            : 0.0;
        double scalePenalty = expectedTarFiles > 0
            ? ((double) metrics.tarFilesCreated / expectedTarFiles) * 100.0
            : metrics.tarFilesCreated * 10.0; // Penalty if no data but many files
        
        return baseScore + (long) efficiencyPenalty + (long) scalePenalty;
    }
    
    /**
     * Get fragmentation metrics for a specific entity.
     */
    public EntityFragmentationMetrics getMetrics(String walletAddress) {
        return entityMetrics.get(walletAddress);
    }
    
    /**
     * Get all entity metrics.
     */
    public Map<String, EntityFragmentationMetrics> getAllMetrics() {
        return new HashMap<>(entityMetrics);
    }
    
    /**
     * Get entities that created a specific TAR file.
     */
    public String getEntityForTarFile(String tarFileName) {
        return tarFileToEntity.get(tarFileName);
    }
    
    /**
     * Get all TAR files created by an entity.
     */
    public List<String> getTarFilesForEntity(String walletAddress) {
        return new ArrayList<>(entityTarFiles.getOrDefault(walletAddress, Collections.emptyList()));
    }
    
    /**
     * Calculate fragmentation tax for an entity.
     */
    public BigInteger calculateFragmentationTax(String walletAddress) {
        EntityFragmentationMetrics metrics = entityMetrics.get(walletAddress);
        if (metrics == null || metrics.fragmentationScore < 100) {
            return BigInteger.ZERO; // No tax for low fragmentation
        }
        
        return calculateFragmentationTax(metrics);
    }
    
    /**
     * Calculate fragmentation tax based on metrics.
     */
    private BigInteger calculateFragmentationTax(EntityFragmentationMetrics metrics) {
        // Base tax: proportional to fragmentation score
        BigInteger baseTax = BigInteger.valueOf(metrics.fragmentationScore)
            .multiply(BigInteger.valueOf(1_000_000)); // 1 score point = 0.001 ETH (wei)
        
        // Efficiency multiplier: higher tax for lower efficiency
        double efficiencyMultiplier = 1.0 + (1.0 - (metrics.packingEfficiency / 100.0));
        
        // Scale multiplier: higher tax for many small files
        double scaleMultiplier = 1.0 + (metrics.smallTarFileCount / 10.0);
        
        // Estimated GC cost increase (per TAR file overhead)
        BigInteger gcCostIncrease = BigInteger.valueOf(metrics.tarFilesCreated)
            .multiply(BigInteger.valueOf(100_000)); // 0.0001 ETH per TAR file
        
        BigInteger totalTax = baseTax
            .multiply(BigInteger.valueOf((long) (efficiencyMultiplier * 100)))
            .divide(BigInteger.valueOf(100))
            .multiply(BigInteger.valueOf((long) (scaleMultiplier * 100)))
            .divide(BigInteger.valueOf(100))
            .add(gcCostIncrease);
        
        return totalTax;
    }
    
    /**
     * Get top N most fragmented entities.
     */
    public List<EntityFragmentationMetrics> getTopFragmentedEntities(int limit) {
        List<EntityFragmentationMetrics> sorted = new ArrayList<>(entityMetrics.values());
        sorted.sort((a, b) -> Long.compare(b.fragmentationScore, a.fragmentationScore));
        return sorted.subList(0, Math.min(limit, sorted.size()));
    }
    
    /**
     * Reset metrics for an entity (e.g., after compaction).
     */
    public void resetMetrics(String walletAddress) {
        entityMetrics.remove(walletAddress);
        entityTarFiles.remove(walletAddress);
        // Note: tarFileToEntity is not cleared as TAR files still exist
        log.info("📊 Reset fragmentation metrics for entity: {}", walletAddress);
    }
    
    /**
     * Entity fragmentation metrics.
     */
    public static class EntityFragmentationMetrics {
        public final String walletAddress;
        public int tarFilesCreated;
        public long totalBytesWritten;
        public long averageTarFileSize;
        public double packingEfficiency; // Percentage of max file size (256 MB)
        public int smallTarFileCount; // TAR files < 10% of max size
        public long fragmentationScore; // Composite score
        public long lastWriteTimestamp;
        
        public EntityFragmentationMetrics(String walletAddress) {
            this.walletAddress = walletAddress;
        }
        
        // Note: Tax calculation requires FragmentationTracker instance
        // Use FragmentationTracker.calculateFragmentationTax(walletAddress) instead
    }
}

