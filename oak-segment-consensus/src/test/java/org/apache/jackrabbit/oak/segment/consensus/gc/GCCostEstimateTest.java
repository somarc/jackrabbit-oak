package org.apache.jackrabbit.oak.segment.consensus.gc;

import org.junit.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Unit tests for GCCostEstimate.
 */
public class GCCostEstimateTest {
    
    @Test
    public void testConstructorAndGetters() {
        Map<String, Long> reclaimableByTarFile = new HashMap<>();
        reclaimableByTarFile.put("data00001a.tar", 26843545600L);
        reclaimableByTarFile.put("data00002a.tar", 26843545600L);
        
        GCCostEstimate estimate = new GCCostEstimate(
            125000L,
            53687091200L,
            500000L,
            107374182400L,
            new BigDecimal("5120.00"),
            reclaimableByTarFile
        );
        
        assertEquals(125000L, estimate.getReclaimableSegmentCount());
        assertEquals(53687091200L, estimate.getReclaimableSizeBytes());
        assertEquals(51200L, estimate.getReclaimableSizeMB());
        assertEquals(500000L, estimate.getTotalSegmentCount());
        assertEquals(107374182400L, estimate.getTotalSizeBytes());
        assertEquals(102400L, estimate.getTotalSizeMB());
        assertEquals(new BigDecimal("5120.00"), estimate.getEstimatedCostUSDC());
        assertEquals(2, estimate.getReclaimableByTarFile().size());
        assertEquals(50.0, estimate.getReclaimablePercentage(), 0.01);
    }
    
    @Test
    public void testReclaimablePercentage() {
        // 50% reclaimable
        GCCostEstimate estimate = new GCCostEstimate(
            100L,  // reclaimable segments
            50L,   // reclaimable bytes
            200L,  // total segments
            100L,  // total bytes
            BigDecimal.ZERO,
            new HashMap<>()
        );
        
        assertEquals(50.0, estimate.getReclaimablePercentage(), 0.01);
    }
    
    @Test
    public void testReclaimablePercentageZero() {
        // Empty repository
        GCCostEstimate estimate = new GCCostEstimate(
            0L,
            0L,
            0L,
            0L,
            BigDecimal.ZERO,
            new HashMap<>()
        );
        
        assertEquals(0.0, estimate.getReclaimablePercentage(), 0.01);
    }
    
    @Test
    public void testToString() {
        GCCostEstimate estimate = new GCCostEstimate(
            125000L,
            53687091200L,
            500000L,
            107374182400L,
            new BigDecimal("5120.00"),
            new HashMap<>()
        );
        
        String str = estimate.toString();
        assertTrue(str.contains("125000"));
        assertTrue(str.contains("50.00"));
        assertTrue(str.contains("51200"));
        assertTrue(str.contains("5120.00"));
    }
    
    @Test
    public void testEquals() {
        Map<String, Long> reclaimableByTarFile = new HashMap<>();
        reclaimableByTarFile.put("data00001a.tar", 26843545600L);
        
        GCCostEstimate estimate1 = new GCCostEstimate(
            100L, 100L, 200L, 200L, new BigDecimal("10.00"), reclaimableByTarFile
        );
        
        GCCostEstimate estimate2 = new GCCostEstimate(
            100L, 100L, 200L, 200L, new BigDecimal("10.00"), reclaimableByTarFile
        );
        
        GCCostEstimate estimate3 = new GCCostEstimate(
            200L, 200L, 200L, 200L, new BigDecimal("20.00"), reclaimableByTarFile
        );
        
        assertEquals(estimate1, estimate2);
        assertNotEquals(estimate1, estimate3);
        assertNotEquals(estimate1, null);
        assertNotEquals(estimate1, "not an estimate");
    }
    
    @Test
    public void testHashCode() {
        Map<String, Long> reclaimableByTarFile = new HashMap<>();
        reclaimableByTarFile.put("data00001a.tar", 26843545600L);
        
        GCCostEstimate estimate1 = new GCCostEstimate(
            100L, 100L, 200L, 200L, new BigDecimal("10.00"), reclaimableByTarFile
        );
        
        GCCostEstimate estimate2 = new GCCostEstimate(
            100L, 100L, 200L, 200L, new BigDecimal("10.00"), reclaimableByTarFile
        );
        
        assertEquals(estimate1.hashCode(), estimate2.hashCode());
    }
}

