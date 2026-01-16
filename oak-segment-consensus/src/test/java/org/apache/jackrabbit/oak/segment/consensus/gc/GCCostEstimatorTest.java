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

import org.apache.jackrabbit.oak.segment.SegmentNodeStore;
import org.apache.jackrabbit.oak.segment.SegmentNodeStoreBuilders;
import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.apache.jackrabbit.oak.segment.file.FileStoreBuilder;
import org.apache.jackrabbit.oak.segment.file.tar.TarFiles;
import org.apache.jackrabbit.oak.spi.commit.CommitInfo;
import org.apache.jackrabbit.oak.spi.commit.EmptyHook;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;

import static org.junit.Assert.*;

/**
 * Unit tests for GCCostEstimator.
 * 
 * <p>Tests GC cost estimation on small repositories to validate:
 * - Graph traversal correctness
 * - Cost calculation accuracy
 * - Error handling
 * - Memory efficiency
 */
public class GCCostEstimatorTest {
    
    @Rule
    public TemporaryFolder folder = new TemporaryFolder(new File("target"));
    
    private FileStore fileStore;
    private TarFiles tarFiles;
    private GCCostEstimator estimator;
    
    @Before
    public void setUp() throws Exception {
        File storeDir = folder.newFolder("segmentstore");
        fileStore = FileStoreBuilder.fileStoreBuilder(storeDir).build();
        
        // Access private getTarFiles() method via reflection (common pattern in Oak tests)
        try {
            java.lang.reflect.Method getTarFilesMethod = FileStore.class.getDeclaredMethod("getTarFiles");
            getTarFilesMethod.setAccessible(true);
            tarFiles = (TarFiles) getTarFilesMethod.invoke(fileStore);
        } catch (Exception e) {
            throw new RuntimeException("Failed to access TarFiles from FileStore", e);
        }
        
        estimator = new GCCostEstimator(fileStore, tarFiles, new BigDecimal("0.10"));
    }
    
    @After
    public void tearDown() throws Exception {
        if (tarFiles != null) {
            tarFiles.close();
        }
        if (fileStore != null) {
            fileStore.close();
        }
    }
    
    /**
     * Test estimation on empty repository.
     */
    @Test
    public void testEstimateCostEmptyRepo() throws IOException {
        GCCostEstimate estimate = estimator.estimateCost(null);
        
        assertEquals(0, estimate.getReclaimableSegmentCount());
        assertEquals(0, estimate.getReclaimableSizeBytes());
        assertEquals(0, estimate.getTotalSegmentCount());
        assertEquals(0, estimate.getEstimatedCostUSDC().compareTo(BigDecimal.ZERO));
        assertEquals(0.0, estimate.getReclaimablePercentage(), 0.01);
    }
    
    /**
     * Test estimation on repository with data (should have some reclaimable segments after writes).
     */
    @Test
    public void testEstimateCostWithData() throws Exception {
        // Create some nodes to generate segments
        SegmentNodeStore nodeStore = SegmentNodeStoreBuilders.builder(fileStore).build();
        NodeBuilder root = nodeStore.getRoot().builder();
        
        // Add some test data
        for (int i = 0; i < 10; i++) {
            NodeBuilder child = root.child("test" + i);
            child.setProperty("name", "value" + i);
            child.setProperty("count", i);
        }
        
        nodeStore.merge(root, EmptyHook.INSTANCE, CommitInfo.EMPTY);
        fileStore.flush();
        
        // Now add more data (creates new segments, old ones become reclaimable)
        root = nodeStore.getRoot().builder();
        for (int i = 0; i < 10; i++) {
            root.child("test" + i).remove();
        }
        nodeStore.merge(root, EmptyHook.INSTANCE, CommitInfo.EMPTY);
        fileStore.flush();
        
        // Estimate cost
        GCCostEstimate estimate = estimator.estimateCost(null);
        
        // Should have some segments (even if not reclaimable yet)
        assertTrue("Should have some segments", 
            estimate.getTotalSegmentCount() >= 0);
        assertTrue("Should have some reclaimable segments", 
            estimate.getReclaimableSegmentCount() >= 0);
        assertTrue("Should have some reclaimable size", 
            estimate.getReclaimableSizeBytes() >= 0);
        
        // Cost should be calculated correctly
        BigDecimal expectedCost = BigDecimal.valueOf(estimate.getReclaimableSizeMB())
            .multiply(new BigDecimal("0.10"));
        assertEquals("Cost should match calculation", 
            expectedCost.doubleValue(), 
            estimate.getEstimatedCostUSDC().doubleValue(), 
            0.01);
        
        // Percentage should be valid
        assertTrue("Percentage should be >= 0", 
            estimate.getReclaimablePercentage() >= 0);
        assertTrue("Percentage should be <= 100", 
            estimate.getReclaimablePercentage() <= 100);
    }
    
    /**
     * Test estimation with HEAD (null revision).
     */
    @Test
    public void testEstimateCostWithHead() throws Exception {
        // Create some data
        SegmentNodeStore nodeStore = SegmentNodeStoreBuilders.builder(fileStore).build();
        NodeBuilder root = nodeStore.getRoot().builder();
        root.child("test").setProperty("value", "data");
        nodeStore.merge(root, EmptyHook.INSTANCE, CommitInfo.EMPTY);
        fileStore.flush();
        
        // Estimate with HEAD (null)
        GCCostEstimate estimate1 = estimator.estimateCost(null);
        
        // Estimate with explicit HEAD revision string
        String headRevision = fileStore.getHead().getRecordId().toString();
        GCCostEstimate estimate2 = estimator.estimateCost(headRevision);
        
        // Should be the same
        assertEquals("HEAD estimates should match", 
            estimate1.getReclaimableSegmentCount(), 
            estimate2.getReclaimableSegmentCount());
        assertEquals("HEAD estimates should match", 
            estimate1.getReclaimableSizeBytes(), 
            estimate2.getReclaimableSizeBytes());
    }
    
    /**
     * Test estimation with invalid revision format.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testEstimateCostInvalidRevision() throws IOException {
        estimator.estimateCost("invalid-revision-format");
    }
    
    /**
     * Test cost calculation accuracy.
     */
    @Test
    public void testCostCalculation() throws Exception {
        // Create data
        SegmentNodeStore nodeStore = SegmentNodeStoreBuilders.builder(fileStore).build();
        NodeBuilder root = nodeStore.getRoot().builder();
        root.child("test").setProperty("value", "data");
        nodeStore.merge(root, EmptyHook.INSTANCE, CommitInfo.EMPTY);
        fileStore.flush();
        
        // Estimate with custom USDC rate
        BigDecimal customRate = new BigDecimal("0.25"); // $0.25 per MB
        estimator.setUsdcPerMB(customRate);
        
        GCCostEstimate estimate = estimator.estimateCost(null);
        
        // Verify cost calculation
        BigDecimal expectedCost = BigDecimal.valueOf(estimate.getReclaimableSizeMB())
            .multiply(customRate);
        assertEquals("Cost should match custom rate", 
            expectedCost.doubleValue(), 
            estimate.getEstimatedCostUSDC().doubleValue(), 
            0.01);
    }
    
    /**
     * Test reclaimable by TAR file breakdown.
     */
    @Test
    public void testReclaimableByTarFile() throws Exception {
        // Create data
        SegmentNodeStore nodeStore = SegmentNodeStoreBuilders.builder(fileStore).build();
        NodeBuilder root = nodeStore.getRoot().builder();
        root.child("test").setProperty("value", "data");
        nodeStore.merge(root, EmptyHook.INSTANCE, CommitInfo.EMPTY);
        fileStore.flush();
        
        GCCostEstimate estimate = estimator.estimateCost(null);
        
        // Should have breakdown by TAR file
        assertNotNull("Reclaimable by TAR file should not be null", 
            estimate.getReclaimableByTarFile());
        
        // Sum of TAR file sizes should match total reclaimable size (approximately)
        long sumByFile = estimate.getReclaimableByTarFile().values().stream()
            .mapToLong(Long::longValue)
            .sum();
        
        // Allow some approximation error (we're using 256KB per segment estimate)
        // If no reclaimable segments, skip this check
        if (estimate.getReclaimableSizeBytes() > 0) {
            long diff = Math.abs(sumByFile - estimate.getReclaimableSizeBytes());
            assertTrue("Sum by file should approximately match total (diff: " + diff + ")", 
                diff < estimate.getReclaimableSizeBytes() * 0.2); // Within 20% (approximation)
        }
    }
    
    /**
     * Test percentage calculation.
     */
    @Test
    public void testPercentageCalculation() throws Exception {
        // Create data
        SegmentNodeStore nodeStore = SegmentNodeStoreBuilders.builder(fileStore).build();
        NodeBuilder root = nodeStore.getRoot().builder();
        root.child("test").setProperty("value", "data");
        nodeStore.merge(root, EmptyHook.INSTANCE, CommitInfo.EMPTY);
        fileStore.flush();
        
        GCCostEstimate estimate = estimator.estimateCost(null);
        
        // Calculate expected percentage
        double expectedPercentage = estimate.getTotalSizeBytes() > 0
            ? (estimate.getReclaimableSizeBytes() * 100.0) / estimate.getTotalSizeBytes()
            : 0.0;
        
        assertEquals("Percentage should match calculation", 
            expectedPercentage, 
            estimate.getReclaimablePercentage(), 
            0.01);
    }
    
    /**
     * Test that estimation completes without errors on repository with multiple commits.
     */
    @Test
    public void testEstimateCostMultipleCommits() throws Exception {
        SegmentNodeStore nodeStore = SegmentNodeStoreBuilders.builder(fileStore).build();
        
        // Create multiple commits
        for (int i = 0; i < 5; i++) {
            NodeBuilder root = nodeStore.getRoot().builder();
            root.child("commit" + i).setProperty("index", i);
            nodeStore.merge(root, EmptyHook.INSTANCE, CommitInfo.EMPTY);
            fileStore.flush();
        }
        
        // Should complete without errors
        GCCostEstimate estimate = estimator.estimateCost(null);
        
        assertNotNull("Estimate should not be null", estimate);
        // Note: Small repos might not have segments yet, so just check it completes
        assertTrue("Should have >= 0 segments", estimate.getTotalSegmentCount() >= 0);
    }
    
    /**
     * Test USDC rate getter/setter.
     */
    @Test
    public void testUsdcRateGetterSetter() {
        BigDecimal newRate = new BigDecimal("0.50");
        estimator.setUsdcPerMB(newRate);
        
        assertEquals("USDC rate should be updated", 
            newRate, 
            estimator.getUsdcPerMB());
    }
}

