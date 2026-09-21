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

import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Calculates per-wallet storage metrics for tokenomics model.
 * 
 * <p>Implements proportional storage ownership model:
 * - Each wallet "owns" a % of total SegmentStore
 * - Delete operations charged based on % of storage deleted
 * - Prevents free-riding on GC/compaction costs
 * 
 * <p>Production Scale Assumptions (1-2 TB SegmentStore):
 * - Upper Bound: 1-2 TB total (pure metadata, no binary blobs)
 * - Active Wallets: ~100,000
 * - Average Wallet: 10-20 MB
 * - Large Wallets (top 1%): 100-500 MB
 * - Enterprise (top 0.1%): 1-5 GB
 * 
 * <p>Example:
 * - Total SegmentStore: 1.5 TB (1,500,000 MB)
 * - Wallet A owns: 500 MB (0.033%)
 * - Wallet A deletes 200 MB → Tax based on proportional GC cost
 */
public class WalletStorageMetrics {
    
    private static final Logger log = LoggerFactory.getLogger(WalletStorageMetrics.class);
    
    // Base GC cost per MB (in wei)
    // SegmentStore-only economics: 0.000001 ETH per MB = 1,000,000,000,000 wei per MB
    // Note: POC is pure SegmentStore (metadata, node structure, properties only)
    // Binary data via BYOD datastores is out of scope for POC
    private static final BigInteger BASE_GC_COST_PER_MB = BigInteger.valueOf(1_000_000_000_000L);
    
    // Production upper bound: 1-2 TB SegmentStore
    // Beyond this, system should shard or enforce mandatory archival
    private static final long UPPER_BOUND_BYTES = 2L * 1024 * 1024 * 1024 * 1024; // 2 TB
    private static final long WARNING_THRESHOLD_BYTES = (long) (UPPER_BOUND_BYTES * 0.75); // 1.5 TB
    private static final long PRESSURE_THRESHOLD_BYTES = (long) (UPPER_BOUND_BYTES * 0.90); // 1.8 TB
    
    private final FileStore fileStore;
    private final Map<String, WalletStorage> walletStorageCache = new ConcurrentHashMap<>();
    private volatile long lastFullScanTimestamp = 0;
    private volatile long totalSegmentStoreSize = 0;
    
    public WalletStorageMetrics(FileStore fileStore) {
        this.fileStore = fileStore;
    }
    
    /**
     * Get storage metrics for a specific wallet.
     * 
     * @param walletAddress Ethereum wallet address
     * @return Storage metrics or null if wallet not found
     */
    public WalletStorage getWalletStorage(String walletAddress) {
        // Check cache first
        WalletStorage cached = walletStorageCache.get(walletAddress);
        if (cached != null && !isCacheStale()) {
            return cached;
        }
        
        // Calculate from FileStore
        return calculateWalletStorage(walletAddress);
    }
    
    /**
     * Get all wallet storage metrics.
     * 
     * @return Map of wallet address to storage metrics
     */
    public Map<String, WalletStorage> getAllWalletStorage() {
        // If cache is fresh, return it
        if (!isCacheStale() && !walletStorageCache.isEmpty()) {
            return new HashMap<>(walletStorageCache);
        }
        
        // Otherwise, do full scan
        return scanAllWallets();
    }
    
    /**
     * Calculate delete tax for a wallet's delete operation.
     * 
     * @param walletAddress Wallet requesting delete
     * @param bytesToDelete Bytes being deleted
     * @param fragmentationMultiplier Multiplier based on wallet's fragmentation score (1.0 = no penalty)
     * @return Tax in wei
     */
    public BigInteger calculateDeleteTax(String walletAddress, long bytesToDelete, double fragmentationMultiplier) {
        if (bytesToDelete <= 0) {
            return BigInteger.ZERO;
        }
        
        // Get total SegmentStore size
        long totalSize = getTotalSegmentStoreSize();
        if (totalSize == 0) {
            return BigInteger.ZERO; // Edge case: empty store
        }
        
        // Calculate proportional cost
        // Tax = (Bytes Deleted / Total Size) × Base Cost Per MB × Fragmentation Multiplier
        
        double mbDeleted = bytesToDelete / (1024.0 * 1024.0);
        BigInteger baseCost = BASE_GC_COST_PER_MB.multiply(
            BigInteger.valueOf((long) (mbDeleted * 1000))
        ).divide(BigInteger.valueOf(1000)); // Multiply by 1000 and divide for precision
        
        // Apply fragmentation multiplier
        BigInteger tax = baseCost.multiply(
            BigInteger.valueOf((long) (fragmentationMultiplier * 100))
        ).divide(BigInteger.valueOf(100));
        
        log.info("💰 Delete tax calculated: wallet={}, bytes={}, tax={} wei ({} ETH)",
            walletAddress, bytesToDelete, tax, weiToEth(tax));
        
        return tax;
    }
    
    /**
     * Calculate storage tax for a wallet (ongoing storage maintenance).
     * 
     * <p>Used for ongoing storage costs, charged periodically (e.g., per epoch).
     * <p>Tax automatically escalates as SegmentStore approaches 2 TB upper bound.
     * 
     * @param walletAddress Wallet address
     * @return Tax in wei
     */
    public BigInteger calculateStorageTax(String walletAddress) {
        WalletStorage storage = getWalletStorage(walletAddress);
        if (storage == null) {
            return BigInteger.ZERO;
        }
        
        // Base model: charge per MB per epoch
        // 0.000001 ETH per MB per epoch (SegmentStore-only economics)
        double mbOwned = storage.bytesOwned / (1024.0 * 1024.0);
        BigInteger baseTax = BASE_GC_COST_PER_MB.multiply(
            BigInteger.valueOf((long) (mbOwned * 1000))
        ).divide(BigInteger.valueOf(1000));
        
        // Apply storage pressure multiplier (escalates as store approaches 2 TB)
        double pressureMultiplier = getStoragePressureMultiplier();
        BigInteger finalTax = baseTax.multiply(
            BigInteger.valueOf((long) (pressureMultiplier * 100))
        ).divide(BigInteger.valueOf(100));
        
        if (pressureMultiplier > 1.0) {
            log.debug("💰 Storage tax with {:.1f}x pressure multiplier: wallet={}, baseTax={}, finalTax={}",
                pressureMultiplier, walletAddress, baseTax, finalTax);
        }
        
        return finalTax;
    }
    
    /**
     * Get total SegmentStore size in bytes.
     */
    public long getTotalSegmentStoreSize() {
        if (totalSegmentStoreSize == 0 || isCacheStale()) {
            totalSegmentStoreSize = fileStore.size();
        }
        return totalSegmentStoreSize;
    }
    
    /**
     * Refresh all wallet storage metrics.
     * Should be called periodically (e.g., every epoch).
     */
    public void refreshMetrics() {
        log.info("📊 Refreshing wallet storage metrics...");
        scanAllWallets();
        lastFullScanTimestamp = System.currentTimeMillis();
        
        // Check capacity and warn if approaching limits
        long totalSize = getTotalSegmentStoreSize();
        double capacityPercent = (totalSize * 100.0) / UPPER_BOUND_BYTES;
        
        if (totalSize >= PRESSURE_THRESHOLD_BYTES) {
            log.warn("🚨 STORAGE PRESSURE: {} bytes ({:.1f}% of 2 TB upper bound) - Aggressive GC recommended",
                totalSize, capacityPercent);
        } else if (totalSize >= WARNING_THRESHOLD_BYTES) {
            log.warn("⚠️  Storage Warning: {} bytes ({:.1f}% of 2 TB upper bound) - Consider archival",
                totalSize, capacityPercent);
        } else {
            log.info("📊 Refresh complete: {} wallets tracked, {} bytes ({:.1f}% capacity)",
                walletStorageCache.size(), totalSize, capacityPercent);
        }
    }
    
    /**
     * Get storage capacity percentage (0-100%).
     * 
     * @return Percentage of 2 TB upper bound
     */
    public double getCapacityPercent() {
        long totalSize = getTotalSegmentStoreSize();
        return (totalSize * 100.0) / UPPER_BOUND_BYTES;
    }
    
    /**
     * Get storage pressure multiplier based on capacity.
     * Used to escalate tax rates as store approaches upper bound.
     * 
     * @return Tax multiplier (1.0x to 5.0x)
     */
    public double getStoragePressureMultiplier() {
        long totalSize = getTotalSegmentStoreSize();
        
        if (totalSize < WARNING_THRESHOLD_BYTES) {
            return 1.0; // 0-75%: Normal
        } else if (totalSize < (long) (UPPER_BOUND_BYTES * 0.85)) {
            return 1.5; // 75-85%: Moderate pressure
        } else if (totalSize < PRESSURE_THRESHOLD_BYTES) {
            return 2.0; // 85-90%: High pressure
        } else if (totalSize < (long) (UPPER_BOUND_BYTES * 0.95)) {
            return 3.0; // 90-95%: Severe pressure
        } else {
            return 5.0; // 95-100%: Emergency pricing
        }
    }
    
    /**
     * Check if SegmentStore is at or beyond upper bound.
     * Should reject new writes and enforce mandatory archival.
     * 
     * @return true if at or beyond 2 TB
     */
    public boolean isAtCapacity() {
        return getTotalSegmentStoreSize() >= UPPER_BOUND_BYTES;
    }
    
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // PRIVATE METHODS
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    
    private WalletStorage calculateWalletStorage(String walletAddress) {
        try {
            NodeState root = fileStore.getHead();
            NodeState oakChain = root.getChildNode("oak-chain");
            NodeState content = oakChain.getChildNode("content");
            
            if (!content.exists()) {
                return null;
            }
            
            // Navigate sharded path: content/{L1}/{L2}/{L3}/{wallet}
            String addr = walletAddress.toLowerCase().replace("0x", "");
            if (addr.length() < 6) {
                return null;
            }
            
            String l1 = addr.substring(0, 2);
            String l2 = addr.substring(2, 4);
            String l3 = addr.substring(4, 6);
            
            NodeState l1Node = content.getChildNode(l1);
            if (!l1Node.exists()) return null;
            
            NodeState l2Node = l1Node.getChildNode(l2);
            if (!l2Node.exists()) return null;
            
            NodeState l3Node = l2Node.getChildNode(l3);
            if (!l3Node.exists()) return null;
            
            NodeState walletNode = l3Node.getChildNode("0x" + addr);
            if (!walletNode.exists()) return null;
            
            // Calculate size (approximate - count nodes and properties)
            long bytesOwned = estimateNodeSize(walletNode);
            long totalSize = getTotalSegmentStoreSize();
            
            double percentageOfTotal = totalSize > 0 
                ? (bytesOwned * 100.0) / totalSize 
                : 0.0;
            
            WalletStorage storage = new WalletStorage(
                walletAddress,
                bytesOwned,
                percentageOfTotal,
                countNodes(walletNode)
            );
            
            // Update cache
            walletStorageCache.put(walletAddress, storage);
            
            return storage;
            
        } catch (Exception e) {
            log.warn("Failed to calculate storage for wallet {}: {}", walletAddress, e.getMessage());
            return null;
        }
    }
    
    private Map<String, WalletStorage> scanAllWallets() {
        Map<String, WalletStorage> result = new HashMap<>();
        
        try {
            NodeState root = fileStore.getHead();
            NodeState oakChain = root.getChildNode("oak-chain");
            NodeState content = oakChain.getChildNode("content");
            
            if (!content.exists()) {
                return result;
            }
            
            long totalSize = getTotalSegmentStoreSize();
            
            // Iterate through L1 buckets (00-ff)
            for (String l1Name : content.getChildNodeNames()) {
                NodeState l1 = content.getChildNode(l1Name);
                
                // Iterate through L2 buckets
                for (String l2Name : l1.getChildNodeNames()) {
                    NodeState l2 = l1.getChildNode(l2Name);
                    
                    // Iterate through L3 buckets
                    for (String l3Name : l2.getChildNodeNames()) {
                        NodeState l3 = l2.getChildNode(l3Name);
                        
                        // Iterate through wallets in this bucket
                        for (String walletName : l3.getChildNodeNames()) {
                            if (walletName.startsWith("0x")) {
                                NodeState walletNode = l3.getChildNode(walletName);
                                
                                long bytesOwned = estimateNodeSize(walletNode);
                                double percentageOfTotal = totalSize > 0 
                                    ? (bytesOwned * 100.0) / totalSize 
                                    : 0.0;
                                
                                WalletStorage storage = new WalletStorage(
                                    walletName,
                                    bytesOwned,
                                    percentageOfTotal,
                                    countNodes(walletNode)
                                );
                                
                                result.put(walletName, storage);
                            }
                        }
                    }
                }
            }
            
            // Update cache
            walletStorageCache.clear();
            walletStorageCache.putAll(result);
            
        } catch (Exception e) {
            log.error("Failed to scan wallets: {}", e.getMessage(), e);
        }
        
        return result;
    }
    
    private long estimateNodeSize(NodeState node) {
        // Rough estimate: count nodes and properties
        // Each node ≈ 1 KB, each property ≈ 100 bytes (average)
        long nodeCount = countNodes(node);
        long propertyCount = countProperties(node);
        
        return (nodeCount * 1024) + (propertyCount * 100);
    }
    
    private long countNodes(NodeState node) {
        long count = 1; // Current node
        
        for (String childName : node.getChildNodeNames()) {
            NodeState child = node.getChildNode(childName);
            count += countNodes(child);
        }
        
        return count;
    }
    
    private long countProperties(NodeState node) {
        long count = node.getPropertyCount();
        
        for (String childName : node.getChildNodeNames()) {
            NodeState child = node.getChildNode(childName);
            count += countProperties(child);
        }
        
        return count;
    }
    
    private boolean isCacheStale() {
        // Cache is stale if older than 5 minutes
        return (System.currentTimeMillis() - lastFullScanTimestamp) > (5 * 60 * 1000);
    }
    
    private String weiToEth(BigInteger wei) {
        BigInteger eth = wei.divide(BigInteger.valueOf(10).pow(18));
        BigInteger remainder = wei.remainder(BigInteger.valueOf(10).pow(18));
        if (remainder.equals(BigInteger.ZERO)) {
            return eth.toString();
        }
        String remainderStr = remainder.toString();
        while (remainderStr.length() < 18) {
            remainderStr = "0" + remainderStr;
        }
        return eth.toString() + "." + remainderStr.substring(0, 6);
    }
    
    /**
     * Wallet storage ownership data.
     */
    public static class WalletStorage {
        public final String walletAddress;
        public final long bytesOwned;
        public final double percentageOfTotal;
        public final long nodeCount;
        
        public WalletStorage(String walletAddress, long bytesOwned, double percentageOfTotal, long nodeCount) {
            this.walletAddress = walletAddress;
            this.bytesOwned = bytesOwned;
            this.percentageOfTotal = percentageOfTotal;
            this.nodeCount = nodeCount;
        }
    }
}

