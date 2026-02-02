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
package org.apache.jackrabbit.oak.segment.http.wallet;

import java.math.BigInteger;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Mock Ethereum contract for testing.
 * 
 * <p>Simulates OakWriteAuthorizationV5 contract calls without real blockchain.
 * 
 * <p>Usage:
 * <pre>
 * MockEthereumContract contract = new MockEthereumContract();
 * 
 * // Simulate authorizeWrite() call
 * CompletableFuture&lt;String&gt; txHash = contract.authorizeWrite(
 *     proposalId, shardPath, messageHash, signature, nonce, timestamp
 * );
 * 
 * // Get transaction hash
 * String txHashValue = txHash.get();
 * </pre>
 */
public class MockEthereumContract {
    
    private long currentBlock = 1000000;
    private final java.util.Map<String, TransactionResult> transactions = new java.util.concurrent.ConcurrentHashMap<>();
    
    /**
     * Mock authorizeWrite() contract call.
     * 
     * @param proposalId Proposal ID (bytes32)
     * @param shardPath Shard path
     * @param messageHash Message hash (bytes32)
     * @param signature Signature (bytes)
     * @param nonce Nonce
     * @param timestamp Timestamp
     * @return Transaction hash (CompletableFuture for async simulation)
     */
    public CompletableFuture<String> authorizeWrite(
            String proposalId,
            String shardPath,
            String messageHash,
            String signature,
            BigInteger nonce,
            long timestamp) {
        
        // Generate mock transaction hash
        String raw = UUID.randomUUID().toString().replace("-", "");
        while (raw.length() < 60) {
            raw = raw + UUID.randomUUID().toString().replace("-", "");
        }
        String txHash = "0xtx" + raw.substring(0, 60);
        
        // Store transaction result
        TransactionResult result = new TransactionResult(
            txHash,
            currentBlock++,
            true,  // Success
            null   // No revert reason
        );
        transactions.put(txHash, result);
        
        // Simulate async blockchain call
        CompletableFuture<String> future = new CompletableFuture<>();
        // Simulate network delay
        new Thread(() -> {
            try {
                Thread.sleep(100); // Simulate network latency
                future.complete(txHash);
            } catch (InterruptedException e) {
                future.completeExceptionally(e);
            }
        }).start();
        
        return future;
    }
    
    /**
     * Get transaction result (for testing).
     */
    public TransactionResult getTransaction(String txHash) {
        return transactions.get(txHash);
    }
    
    /**
     * Simulate transaction revert (for testing failure scenarios).
     */
    public void simulateRevert(String txHash, String reason) {
        TransactionResult result = transactions.get(txHash);
        if (result != null) {
            transactions.put(txHash, new TransactionResult(
                txHash,
                result.blockNumber,
                false,  // Reverted
                reason
            ));
        }
    }
    
    /**
     * Transaction result structure.
     */
    public static class TransactionResult {
        public final String txHash;
        public final long blockNumber;
        public final boolean success;
        public final String revertReason;
        
        public TransactionResult(String txHash, long blockNumber, boolean success, String revertReason) {
            this.txHash = txHash;
            this.blockNumber = blockNumber;
            this.success = success;
            this.revertReason = revertReason;
        }
    }
}
