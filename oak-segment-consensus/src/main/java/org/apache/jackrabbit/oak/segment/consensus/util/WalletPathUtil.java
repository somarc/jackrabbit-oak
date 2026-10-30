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
 * Converts wallet addresses to wallet-scoped Oak paths.
 *
 * <pre>
 *   Wallet:       0x742d35cc6634c0532925a3b844bc9e7595f0beb0
 *   Shard root:   /oak-chain/74/2d/35/0x742d35cc6634c0532925a3b844bc9e7595f0beb0
 *   Content path: /oak-chain/74/2d/35/0x742d35cc.../content
 *   With org:     /oak-chain/74/2d/35/0x742d35cc.../{organization}/content
 * </pre>
 *
 * <p>The first three address bytes form a three-level hex fan-out (256^3 buckets).
 */
public class WalletPathUtil {
    
    private static final String OAK_CHAIN_ROOT = "/oak-chain";
    
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
    
}

