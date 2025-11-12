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

/**
 * Utility for converting wallet addresses to sharded Oak paths.
 * 
 * Implements Git/DataStore-style sharding to prevent flat structure bottlenecks
 * and enable fault isolation.
 * 
 * Pattern: /content/{L1}/{L2}/{L3}/{wallet}/
 * - L1: First 2 hex chars (00-ff) → 256 buckets
 * - L2: Next 2 hex chars (00-ff) → 256 buckets per L1
 * - L3: Next 2 hex chars (00-ff) → 256 buckets per L2
 * - Total: 16,777,216 buckets
 * 
 * Example:
 *   Wallet:  0x742d35cc6634c0532925a3b844bc9e7595f0beb
 *   Path:    /oak-chain/content/74/2d/35/0x742d35cc6634c0532925a3b844bc9e7595f0beb
 * 
 * Benefits:
 * - Prevents copy-on-write amplification (max 256 children per node)
 * - Enables fault isolation (SNFE contained to single bucket)
 * - Supports oak:index queries at bucket levels
 * - Scales to billions of wallets
 */
public class WalletPathUtil {
    
    private static final String CONTENT_ROOT = "/oak-chain/content";
    
    /**
     * Convert wallet address to sharded Oak path.
     * 
     * @param walletAddress Ethereum wallet address (0x... format)
     * @return Sharded path for Oak storage
     * @throws IllegalArgumentException if wallet address is invalid
     */
    public static String toShardedPath(String walletAddress) {
        if (walletAddress == null || walletAddress.isEmpty()) {
            throw new IllegalArgumentException("Wallet address cannot be null or empty");
        }
        
        // Normalize: lowercase, remove 0x prefix
        String addr = walletAddress.toLowerCase();
        if (addr.startsWith("0x")) {
            addr = addr.substring(2);
        }
        
        // Validate length (Ethereum addresses are 40 hex chars)
        if (addr.length() < 6) {
            throw new IllegalArgumentException(
                "Invalid wallet address (too short): " + walletAddress);
        }
        
        // Extract sharding levels (first 6 hex chars)
        String level1 = addr.substring(0, 2);  // 00-ff
        String level2 = addr.substring(2, 4);  // 00-ff
        String level3 = addr.substring(4, 6);  // 00-ff
        
        // Build sharded path
        return String.format("%s/%s/%s/%s/0x%s",
            CONTENT_ROOT,
            level1,
            level2,
            level3,
            addr
        );
    }
    
    /**
     * Extract wallet address from sharded path.
     * 
     * @param shardedPath Sharded Oak path
     * @return Wallet address (0x... format)
     * @throws IllegalArgumentException if path is invalid
     */
    public static String fromShardedPath(String shardedPath) {
        if (shardedPath == null || shardedPath.isEmpty()) {
            throw new IllegalArgumentException("Path cannot be null or empty");
        }
        
        String[] parts = shardedPath.split("/");
        
        // Path should be: ["", "oak-chain", "content", "74", "2d", "35", "0x742d35cc..."]
        if (parts.length < 7) {
            throw new IllegalArgumentException("Invalid sharded path: " + shardedPath);
        }
        
        // Last segment is the wallet address
        String walletSegment = parts[parts.length - 1];
        
        // Ensure it has 0x prefix
        if (!walletSegment.startsWith("0x")) {
            walletSegment = "0x" + walletSegment;
        }
        
        return walletSegment;
    }
    
    /**
     * Get the bucket path (without wallet address).
     * Useful for bucket-level operations and quarantine.
     * 
     * @param walletAddress Ethereum wallet address
     * @return Bucket path (e.g., /oak-chain/content/74/2d/35)
     */
    public static String toBucketPath(String walletAddress) {
        String addr = walletAddress.toLowerCase().replace("0x", "");
        
        if (addr.length() < 6) {
            throw new IllegalArgumentException("Invalid wallet address: " + walletAddress);
        }
        
        return String.format("%s/%s/%s/%s",
            CONTENT_ROOT,
            addr.substring(0, 2),
            addr.substring(2, 4),
            addr.substring(4, 6)
        );
    }
    
    /**
     * Get bucket identifier for quarantine/metrics.
     * 
     * @param walletAddress Ethereum wallet address
     * @return Bucket ID (e.g., "74-2d-35")
     */
    public static String toBucketId(String walletAddress) {
        String addr = walletAddress.toLowerCase().replace("0x", "");
        
        if (addr.length() < 6) {
            throw new IllegalArgumentException("Invalid wallet address: " + walletAddress);
        }
        
        return String.format("%s-%s-%s",
            addr.substring(0, 2),
            addr.substring(2, 4),
            addr.substring(4, 6)
        );
    }
    
    /**
     * Check if a path is a sharded wallet path.
     * 
     * @param path Oak path to check
     * @return true if path follows sharded wallet pattern
     */
    public static boolean isShardedWalletPath(String path) {
        if (path == null || !path.startsWith(CONTENT_ROOT)) {
            return false;
        }
        
        String[] parts = path.split("/");
        
        // Should have at least: ["", "oak-chain", "content", "74", "2d", "35", "0x..."]
        if (parts.length < 7) {
            return false;
        }
        
        // Check if last segment looks like a wallet (0x...)
        String lastSegment = parts[parts.length - 1];
        return lastSegment.startsWith("0x") && lastSegment.length() > 10;
    }
    
    /**
     * Get total number of possible buckets.
     * 
     * @return 256^3 = 16,777,216
     */
    public static int getTotalBuckets() {
        return 256 * 256 * 256; // 16,777,216
    }
    
    /**
     * Estimate wallets per bucket (assuming uniform distribution).
     * 
     * @param totalWallets Total number of wallets in the network
     * @return Average wallets per bucket
     */
    public static int estimateWalletsPerBucket(long totalWallets) {
        return (int) (totalWallets / getTotalBuckets());
    }
    
    /**
     * Get the content root path.
     * 
     * @return "/oak-chain/content"
     */
    public static String getContentRoot() {
        return CONTENT_ROOT;
    }
}

