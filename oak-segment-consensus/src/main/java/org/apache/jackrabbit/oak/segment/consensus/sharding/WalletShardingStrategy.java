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
package org.apache.jackrabbit.oak.segment.consensus.sharding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Wallet-based sharding strategy using keccak256-style hashing.
 * 
 * <p>Computes shard ID from wallet address using:
 * <pre>
 *   shardId = hash(walletAddress) % numShards
 * </pre>
 * 
 * <p>For optimal performance, numShards should be a power of 2.
 * When numShards is power-of-2, modulo operation becomes bitwise AND (faster).
 * 
 * <p>Note: Uses SHA-256 for hashing (consistent with existing codebase).
 * For production, consider using Keccak-256 (Ethereum standard).
 */
public class WalletShardingStrategy implements ShardingStrategy {
    
    private static final Logger log = LoggerFactory.getLogger(WalletShardingStrategy.class);
    
    private final int numShards;
    private final boolean isPowerOfTwo;
    
    /**
     * Create wallet-based sharding strategy.
     * 
     * @param numShards Number of shards (should be power-of-2 for optimal performance)
     * @throws IllegalArgumentException if numShards <= 0
     */
    public WalletShardingStrategy(int numShards) {
        if (numShards <= 0) {
            throw new IllegalArgumentException("Number of shards must be positive: " + numShards);
        }
        this.numShards = numShards;
        this.isPowerOfTwo = (numShards & (numShards - 1)) == 0;
        
        if (!isPowerOfTwo) {
            log.warn("⚠️  numShards ({}) is not a power of 2. Consider using power-of-2 for optimal consistent hashing.", numShards);
        } else {
            log.info("✅ WalletShardingStrategy initialized with {} shards (power-of-2)", numShards);
        }
    }
    
    @Override
    public int getShardId(String walletAddress) {
        if (walletAddress == null || walletAddress.isEmpty()) {
            throw new IllegalArgumentException("Wallet address cannot be null or empty");
        }
        
        // Normalize wallet address (lowercase, ensure 0x prefix)
        String normalized = walletAddress.toLowerCase().trim();
        if (!normalized.startsWith("0x")) {
            normalized = "0x" + normalized;
        }
        
        // Hash wallet address
        byte[] hash = hashWalletAddress(normalized);
        
        // Convert hash bytes to integer
        int hashInt = bytesToInt(hash);
        
        // Compute shard ID
        int shardId;
        if (isPowerOfTwo) {
            // Power-of-2: Use bitwise AND (faster than modulo)
            shardId = hashInt & (numShards - 1);
        } else {
            // Non-power-of-2: Use modulo
            shardId = Math.abs(hashInt) % numShards;
        }
        
        log.debug("Wallet {} → Shard {}", normalized, shardId);
        return shardId;
    }
    
    @Override
    public int getNumShards() {
        return numShards;
    }
    
    /**
     * Hash wallet address using SHA-256.
     * 
     * <p>Note: For production, consider using Keccak-256 (Ethereum standard).
     * Currently using SHA-256 for consistency with existing codebase.
     * 
     * @param walletAddress Normalized wallet address (0x...)
     * @return Hash bytes
     */
    private byte[] hashWalletAddress(String walletAddress) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(walletAddress.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is always available in Java
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }
    
    /**
     * Convert hash bytes to integer.
     * Uses first 4 bytes of hash for shard ID computation.
     * 
     * @param hash Hash bytes
     * @return Integer value
     */
    private int bytesToInt(byte[] hash) {
        if (hash.length < 4) {
            throw new IllegalArgumentException("Hash must be at least 4 bytes");
        }
        
        // Use first 4 bytes (big-endian)
        int result = 0;
        for (int i = 0; i < 4; i++) {
            result = (result << 8) | (hash[i] & 0xFF);
        }
        return result;
    }
    
    /**
     * Check if numShards is power-of-2.
     * 
     * @return true if numShards is power-of-2
     */
    public boolean isPowerOfTwo() {
        return isPowerOfTwo;
    }
}

