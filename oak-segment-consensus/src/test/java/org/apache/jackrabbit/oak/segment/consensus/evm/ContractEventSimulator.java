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
package org.apache.jackrabbit.oak.segment.consensus.evm;

import org.apache.jackrabbit.oak.segment.consensus.evm.impl.EventDrivenEvmBridge;

import java.math.BigInteger;
import java.util.UUID;

/**
 * Utility for simulating Solidity contract events (for testing).
 * 
 * <p>This simulates WriteAuthorized events from OakWriteAuthorizationV5 contract.
 * In production, these events come from Web3j subscriptions to real blockchain.
 * 
 * <p>Usage:
 * <pre>
 * EventDrivenEvmBridge bridge = new EventDrivenEvmBridge();
 * bridge.start();
 * 
 * ContractEventSimulator simulator = new ContractEventSimulator(bridge);
 * 
 * // Simulate authorizeWrite() transaction
 * String proposalId = simulator.simulateAuthorizeWrite(
 *     "0x742d35cc...",  // payer
 *     "/oak-chain/content/74/2d/35/0x742d35cc.../",  // shardPath
 *     BigInteger.valueOf(1_000_000)  // 1 USDC (6 decimals)
 * );
 * 
 * // ProposalQueueManager will detect payment and append to Raft
 * </pre>
 */
public class ContractEventSimulator {
    
    private final EventDrivenEvmBridge bridge;
    private long currentBlock = 1000000;
    
    public ContractEventSimulator(EventDrivenEvmBridge bridge) {
        this.bridge = bridge;
    }
    
    /**
     * Simulate authorizeWrite() transaction from OakWriteAuthorizationV5 contract.
     * 
     * <p>This simulates the WriteAuthorized event that would be emitted when:
     * <pre>
     * contract.authorizeWrite(proposalId, shardPath, messageHash, signature, nonce, timestamp)
     * </pre>
     * 
     * @param payer Payer address (0x...)
     * @param shardPath Shard path (/oak-chain/content/{L1}/{L2}/{L3}/0x{wallet}/)
     * @param amount Amount paid in USDC (6 decimals, e.g., 1_000_000 = 1 USDC)
     * @return proposalId (bytes32 hex string)
     */
    public String simulateAuthorizeWrite(
            String payer,
            String shardPath,
            BigInteger amount) {
        
        // Generate proposal ID (matches Solidity bytes32)
        String proposalId = "0x" + UUID.randomUUID().toString().replace("-", "");
        
        // Calculate shard hash (matches Solidity keccak256(bytes(shardPath)))
        String shardHash = "0x" + Integer.toHexString(shardPath.hashCode());
        
        // Generate transaction hash
        String txHash = "0xtx" + UUID.randomUUID().toString().replace("-", "").substring(0, 60);
        
        // Simulate WriteAuthorized event
        bridge.simulateWriteAuthorizedEvent(
            proposalId,
            payer,
            shardHash,
            amount,
            currentBlock++,
            txHash
        );
        
        return proposalId;
    }
    
    /**
     * Simulate authorizeWrite() with explicit proposal ID (for testing specific scenarios).
     */
    public void simulateAuthorizeWrite(
            String proposalId,
            String payer,
            String shardPath,
            BigInteger amount,
            String txHash) {
        
        String shardHash = "0x" + Integer.toHexString(shardPath.hashCode());
        
        bridge.simulateWriteAuthorizedEvent(
            proposalId,
            payer,
            shardHash,
            amount,
            currentBlock++,
            txHash
        );
    }
    
    /**
     * Advance block number (simulates blockchain progression).
     */
    public void advanceBlock() {
        currentBlock++;
    }
    
    /**
     * Get current block number.
     */
    public long getCurrentBlock() {
        return currentBlock;
    }
}

