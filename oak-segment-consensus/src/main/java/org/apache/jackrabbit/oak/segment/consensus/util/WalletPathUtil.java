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
 * <p><strong>Path Architecture (Updated Nov 21, 2024):</strong>
 * <pre>
 * /oak-chain/{shard}/              ← Wallet-scoped root
 *   ├── content/                   ← Standard AEM content paths
 *   │   └── dam/fragments/...
 *   ├── conf/                      ← Wallet's own CF models & config
 *   │   └── settings/dam/cfm/models/...
 *   └── apps/ (future)             ← Custom components
 * </pre>
 * 
 * <p><strong>Sharding Pattern:</strong>
 * - Shard ID: {L1}-{L2}-{L3} (e.g., "74-2d-35")
 * - L1: First 2 hex chars (00-ff) → 256 buckets
 * - L2: Next 2 hex chars (00-ff) → 256 buckets per L1
 * - L3: Next 2 hex chars (00-ff) → 256 buckets per L2
 * - Total: 16,777,216 shards
 * 
 * <p><strong>Example:</strong>
 * <pre>
 *   Wallet:       0x742d35cc6634c0532925a3b844bc9e7595f0beb
 *   Shard ID:     74-2d-35
 *   Shard Root:   /oak-chain/74-2d-35/
 *   Content Path: /oak-chain/74-2d-35/content/dam/fragments/product-widget-x
 *   Config Path:  /oak-chain/74-2d-35/conf/settings/dam/cfm/models/product
 * </pre>
 * 
 * <p><strong>Benefits:</strong>
 * - Complete wallet isolation (DELETE entire shard in one operation)
 * - Standard AEM paths within each shard
 * - Wallet-scoped CF models and configuration
 * - Accurate fragmentation tracking per wallet
 * - Prevents copy-on-write amplification
 * - Fault isolation (SNFE contained to single shard)
 */
public class WalletPathUtil {
    
    private static final String OAK_CHAIN_ROOT = "/oak-chain";
    
    /**
     * Get the shard ID for a wallet address.
     * 
     * <p>DEPRECATED: Use getShardLevels() for path construction.
     * Shard ID format: "{L1}-{L2}-{L3}" (e.g., "74-2d-35")
     * 
     * @param walletAddress Ethereum wallet address (0x... format)
     * @return Shard ID
     * @throws IllegalArgumentException if wallet address is invalid
     * @deprecated Use getShardLevels() for proper path construction
     */
    @Deprecated
    public static String getShardId(String walletAddress) {
        String addr = normalizeWalletAddress(walletAddress);
        
        // Extract sharding levels (first 6 hex chars)
        String level1 = addr.substring(0, 2);  // 00-ff
        String level2 = addr.substring(2, 4);  // 00-ff
        String level3 = addr.substring(4, 6);  // 00-ff
        
        return String.format("%s-%s-%s", level1, level2, level3);
    }
    
    /**
     * Get the shard levels for path construction.
     * 
     * <p>Returns array: [level1, level2, level3] (e.g., ["74", "2d", "35"])
     * 
     * @param walletAddress Ethereum wallet address (0x... format)
     * @return Array of 3 shard levels
     */
    public static String[] getShardLevels(String walletAddress) {
        String addr = normalizeWalletAddress(walletAddress);
        return new String[] {
            addr.substring(0, 2),  // Level 1
            addr.substring(2, 4),  // Level 2
            addr.substring(4, 6)   // Level 3
        };
    }
    
    /**
     * Get the wallet-scoped root path.
     * 
     * <p>Structure: /oak-chain/XX/YY/ZZ/0xWALLETADDRESS
     * 
     * @param walletAddress Ethereum wallet address (0x... format)
     * @return Wallet root path (e.g., "/oak-chain/74/2d/35/0x742d35Cc...")
     */
    public static String getShardRoot(String walletAddress) {
        String normalized = normalizeWalletAddress(walletAddress);
        String[] levels = getShardLevels(walletAddress);
        
        return String.format("%s/%s/%s/%s/0x%s",
            OAK_CHAIN_ROOT,
            levels[0],
            levels[1],
            levels[2],
            normalized);
    }
    
    /**
     * Get the content root path for a wallet.
     * 
     * <p>Structure: /oak-chain/XX/YY/ZZ/0xWALLETADDRESS/content
     * 
     * @param walletAddress Ethereum wallet address (0x... format)
     * @return Content path (e.g., "/oak-chain/74/2d/35/0x742d35Cc.../content")
     */
    public static String getContentPath(String walletAddress) {
        return getShardRoot(walletAddress) + "/content";
    }
    
    /**
     * Get the content root path for a wallet with optional organization.
     * 
     * <p>Structure: /oak-chain/XX/YY/ZZ/0xWALLETADDRESS/{organization}/content
     * 
     * <p>A single wallet can own multiple organizations/brands:
     * <pre>
     *   /oak-chain/74/2d/35/0x742d35Cc.../
     *       ├── PixelPirates/content/       ← Gaming NFT brand
     *       ├── CryptoKitchenware/content/  ← eCommerce brand
     *       └── PersonalBlog/content/       ← Personal content
     * </pre>
     * 
     * @param walletAddress Ethereum wallet address (0x... format)
     * @param organization Optional organization/brand name (null = no org folder)
     * @return Content path with organization
     */
    public static String getContentPath(String walletAddress, String organization) {
        String shardRoot = getShardRoot(walletAddress);
        if (organization == null || organization.isEmpty()) {
            return shardRoot + "/content";
        }
        return shardRoot + "/" + organization + "/content";
    }
    
    /**
     * Validate an organization name.
     * 
     * <p>Organization names must be:
     * - Non-empty
     * - Alphanumeric with hyphens and underscores only
     * - Max 64 characters
     * - No path traversal characters (/, ..)
     * 
     * @param organization The organization name to validate
     * @return null if valid, error message if invalid
     */
    public static String validateOrganization(String organization) {
        if (organization == null || organization.isEmpty()) {
            return null; // Optional - empty is valid
        }
        if (organization.length() > 64) {
            return "Organization name too long (max 64 chars)";
        }
        if (!organization.matches("^[a-zA-Z0-9_-]+$")) {
            return "Organization name must be alphanumeric, hyphens, underscores only";
        }
        return null; // Valid
    }
    
    /**
     * Get the config root path for a wallet.
     * 
     * <p>Structure: /oak-chain/XX/YY/ZZ/0xWALLETADDRESS/conf
     * 
     * @param walletAddress Ethereum wallet address (0x... format)
     * @return Config path (e.g., "/oak-chain/74/2d/35/0x742d35Cc.../conf")
     */
    public static String getConfPath(String walletAddress) {
        return getShardRoot(walletAddress) + "/conf";
    }
    
    /**
     * Get the apps root path for a wallet (future use).
     * 
     * <p>Structure: /oak-chain/XX/YY/ZZ/0xWALLETADDRESS/apps
     * 
     * @param walletAddress Ethereum wallet address (0x... format)
     * @return Apps path (e.g., "/oak-chain/74/2d/35/0x742d35Cc.../apps")
     */
    public static String getAppsPath(String walletAddress) {
        return getShardRoot(walletAddress) + "/apps";
    }
    
    /**
     * Convert wallet address to sharded Oak path (DEPRECATED).
     * 
     * <p><strong>DEPRECATED:</strong> Use {@link #getShardRoot(String)} instead.
     * This method is kept for backward compatibility.
     * 
     * @param walletAddress Ethereum wallet address (0x... format)
     * @return Sharded path for Oak storage
     * @throws IllegalArgumentException if wallet address is invalid
     * @deprecated Use {@link #getShardRoot(String)} or {@link #getContentPath(String)}
     */
    @Deprecated
    public static String toShardedPath(String walletAddress) {
        return getContentPath(walletAddress);
    }
    
    /**
     * Normalize a wallet address (lowercase, remove 0x prefix, validate).
     * 
     * @param walletAddress Ethereum wallet address
     * @return Normalized address (lowercase, no 0x prefix)
     * @throws IllegalArgumentException if address is invalid
     */
    private static String normalizeWalletAddress(String walletAddress) {
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
        
        return addr;
    }
    
    /**
     * Extract shard ID from a path.
     * 
     * @param path Oak path (e.g., "/oak-chain/74-2d-35/content/...")
     * @return Shard ID (e.g., "74-2d-35")
     * @throws IllegalArgumentException if path is invalid
     */
    public static String extractShardId(String path) {
        if (path == null || !path.startsWith(OAK_CHAIN_ROOT + "/")) {
            throw new IllegalArgumentException("Invalid oak-chain path: " + path);
        }
        
        String[] parts = path.split("/");
        
        // Path should be: ["", "oak-chain", "74-2d-35", ...]
        if (parts.length < 3) {
            throw new IllegalArgumentException("Invalid sharded path: " + path);
        }
        
        return parts[2]; // Shard ID (e.g., "74-2d-35")
    }
    
    /**
     * Extract wallet address from sharded path (DEPRECATED).
     * 
     * <p><strong>DEPRECATED:</strong> Use {@link #extractShardId(String)} instead.
     * 
     * @param shardedPath Sharded Oak path
     * @return Shard ID (format changed - now returns "74-2d-35" instead of wallet)
     * @throws IllegalArgumentException if path is invalid
     * @deprecated Use {@link #extractShardId(String)}
     */
    @Deprecated
    public static String fromShardedPath(String shardedPath) {
        return extractShardId(shardedPath);
    }
    
    /**
     * Get the bucket path (DEPRECATED - use {@link #getShardRoot(String)}).
     * 
     * @param walletAddress Ethereum wallet address
     * @return Shard root path
     * @deprecated Use {@link #getShardRoot(String)}
     */
    @Deprecated
    public static String toBucketPath(String walletAddress) {
        return getShardRoot(walletAddress);
    }
    
    /**
     * Get bucket identifier for quarantine/metrics.
     * 
     * <p>Alias for {@link #getShardId(String)}.
     * 
     * @param walletAddress Ethereum wallet address
     * @return Shard ID (e.g., "74-2d-35")
     */
    public static String toBucketId(String walletAddress) {
        return getShardId(walletAddress);
    }
    
    /**
     * Check if a path is a sharded wallet path.
     * 
     * @param path Oak path to check
     * @return true if path follows sharded wallet pattern
     */
    public static boolean isShardedWalletPath(String path) {
        if (path == null || !path.startsWith(OAK_CHAIN_ROOT + "/")) {
            return false;
        }
        
        String[] parts = path.split("/");
        
        // Should have at least: ["", "oak-chain", "74-2d-35", ...]
        if (parts.length < 3) {
            return false;
        }
        
        // Check if shard ID follows pattern: XX-XX-XX
        String shardId = parts[2];
        return shardId.matches("^[0-9a-f]{2}-[0-9a-f]{2}-[0-9a-f]{2}$");
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
     * Get the oak-chain root path.
     * 
     * @return "/oak-chain"
     */
    public static String getOakChainRoot() {
        return OAK_CHAIN_ROOT;
    }
    
    /**
     * Get the content root path (DEPRECATED - use wallet-specific paths).
     * 
     * @return "/oak-chain" (global root)
     * @deprecated Use {@link #getContentPath(String)} for wallet-specific content paths
     */
    @Deprecated
    public static String getContentRoot() {
        return OAK_CHAIN_ROOT;
    }
}

