package org.apache.jackrabbit.oak.segment.consensus.gc;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Represents the estimated cost and reclaimable space for garbage collection in an Oak repository.
 * 
 * <p>Includes breakdowns by TAR file and USDC cost for economic integration with Ethereum smart contracts.
 * Used to estimate GC costs before executing cleanup operations on TB-scale repositories.
 * 
 * <p>Example usage:
 * <pre>
 * GCCostEstimate estimate = gcCostEstimator.estimateCost(null);
 * System.out.println("Reclaimable: " + estimate.getReclaimableSizeMB() + " MB");
 * System.out.println("Cost: " + estimate.getEstimatedCostUSDC() + " USDC");
 * </pre>
 */
public class GCCostEstimate {
    private final long reclaimableSegmentCount;
    private final long reclaimableSizeBytes;
    private final long totalSegmentCount;
    private final long totalSizeBytes;
    private final BigDecimal estimatedCostUSDC;
    private final Map<String, Long> reclaimableByTarFile;  // fileName -> bytes
    private final double reclaimablePercentage;
    
    /**
     * Create a GC cost estimate.
     * 
     * @param reclaimableSegmentCount Number of reclaimable segments
     * @param reclaimableSizeBytes Total size of reclaimable segments in bytes
     * @param totalSegmentCount Total number of segments in repository
     * @param totalSizeBytes Total size of repository in bytes
     * @param estimatedCostUSDC Estimated cost in USDC for GC operation
     * @param reclaimableByTarFile Breakdown of reclaimable size by TAR file
     */
    public GCCostEstimate(long reclaimableSegmentCount, long reclaimableSizeBytes,
                          long totalSegmentCount, long totalSizeBytes,
                          BigDecimal estimatedCostUSDC, Map<String, Long> reclaimableByTarFile) {
        this.reclaimableSegmentCount = reclaimableSegmentCount;
        this.reclaimableSizeBytes = reclaimableSizeBytes;
        this.totalSegmentCount = totalSegmentCount;
        this.totalSizeBytes = totalSizeBytes;
        this.estimatedCostUSDC = estimatedCostUSDC;
        this.reclaimableByTarFile = reclaimableByTarFile;
        
        // Calculate percentage
        this.reclaimablePercentage = totalSizeBytes > 0 
            ? (reclaimableSizeBytes * 100.0) / totalSizeBytes 
            : 0.0;
    }
    
    /**
     * Get the number of reclaimable segments.
     */
    public long getReclaimableSegmentCount() { 
        return reclaimableSegmentCount; 
    }
    
    /**
     * Get the total size of reclaimable segments in bytes.
     */
    public long getReclaimableSizeBytes() { 
        return reclaimableSizeBytes; 
    }
    
    /**
     * Get the total size of reclaimable segments in MB.
     */
    public long getReclaimableSizeMB() { 
        return reclaimableSizeBytes / (1024 * 1024); 
    }
    
    /**
     * Get the total number of segments in the repository.
     */
    public long getTotalSegmentCount() { 
        return totalSegmentCount; 
    }
    
    /**
     * Get the total size of the repository in bytes.
     */
    public long getTotalSizeBytes() { 
        return totalSizeBytes; 
    }
    
    /**
     * Get the total size of the repository in MB.
     */
    public long getTotalSizeMB() { 
        return totalSizeBytes / (1024 * 1024); 
    }
    
    /**
     * Get the estimated cost in USDC for the GC operation.
     */
    public BigDecimal getEstimatedCostUSDC() { 
        return estimatedCostUSDC; 
    }
    
    /**
     * Get breakdown of reclaimable size by TAR file.
     * 
     * @return Map of TAR file name to reclaimable size in bytes
     */
    public Map<String, Long> getReclaimableByTarFile() { 
        return reclaimableByTarFile; 
    }
    
    /**
     * Get the percentage of repository that is reclaimable.
     * 
     * @return Percentage (0.0 to 100.0)
     */
    public double getReclaimablePercentage() { 
        return reclaimablePercentage; 
    }
    
    @Override
    public String toString() {
        return String.format(
            "GCCostEstimate{reclaimable=%d segments (%.2f%%), size=%d MB, cost=%.2f USDC}",
            reclaimableSegmentCount, reclaimablePercentage, getReclaimableSizeMB(), estimatedCostUSDC
        );
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        
        GCCostEstimate that = (GCCostEstimate) o;
        
        if (reclaimableSegmentCount != that.reclaimableSegmentCount) return false;
        if (reclaimableSizeBytes != that.reclaimableSizeBytes) return false;
        if (totalSegmentCount != that.totalSegmentCount) return false;
        if (totalSizeBytes != that.totalSizeBytes) return false;
        if (Double.compare(that.reclaimablePercentage, reclaimablePercentage) != 0) return false;
        if (!estimatedCostUSDC.equals(that.estimatedCostUSDC)) return false;
        return reclaimableByTarFile.equals(that.reclaimableByTarFile);
    }
    
    @Override
    public int hashCode() {
        int result;
        long temp;
        result = (int) (reclaimableSegmentCount ^ (reclaimableSegmentCount >>> 32));
        result = 31 * result + (int) (reclaimableSizeBytes ^ (reclaimableSizeBytes >>> 32));
        result = 31 * result + (int) (totalSegmentCount ^ (totalSegmentCount >>> 32));
        result = 31 * result + (int) (totalSizeBytes ^ (totalSizeBytes >>> 32));
        result = 31 * result + estimatedCostUSDC.hashCode();
        result = 31 * result + reclaimableByTarFile.hashCode();
        temp = Double.doubleToLongBits(reclaimablePercentage);
        result = 31 * result + (int) (temp ^ (temp >>> 32));
        return result;
    }
}

