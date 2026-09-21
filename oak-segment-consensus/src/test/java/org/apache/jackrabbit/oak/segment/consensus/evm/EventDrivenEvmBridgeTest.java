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
import org.junit.Test;

import java.math.BigInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * Test EventDrivenEvmBridge in mock mode.
 */
public class EventDrivenEvmBridgeTest {
    
    @Test
    public void testMockEventProcessing() throws InterruptedException {
        EventDrivenEvmBridge bridge = new EventDrivenEvmBridge(
            "sepolia",
            "0x742d35Cc6634C0532925a3b844Bc9e7595f0bEb0",
            true  // mock mode
        );
        bridge.start();
        
        try {
            String proposalId = "0xabc123def456";
            String payer = "0x742d35cc6634c0532925a3b844bc9e7595f0beb0";
            String shardHash = "0xdef456abc123";
            BigInteger amount = BigInteger.valueOf(1_000_000); // 1 USDC (6 decimals)
            long blockNumber = 12345L;
            String txHash = "0xtx123456789";
            
            // Simulate WriteAuthorized event
            bridge.simulateWriteAuthorizedEvent(
                proposalId,
                payer,
                shardHash,
                amount,
                blockNumber,
                txHash
            );
            
            // Wait for event processing (mock processor polls every 1 second)
            Thread.sleep(1500);
            
            // Verify payment proof stored
            PaymentProof proof = bridge.verifyPayment(proposalId);
            assertNotNull("Payment proof should be stored", proof);
            assertEquals("Proposal ID should match", proposalId, proof.getProposalId());
            assertEquals("Payer should match", payer, proof.getFromAddress());
            assertEquals("Amount should match", amount.toString(), proof.getAmountWei());
            assertEquals("Block number should match", blockNumber, proof.getBlockNumber());
            assertTrue("Should be confirmed", proof.isConfirmed(1));
            
        } finally {
            bridge.stop();
        }
    }
    
    @Test
    public void testEventListeners() throws InterruptedException {
        EventDrivenEvmBridge bridge = new EventDrivenEvmBridge();
        bridge.start();
        
        try {
            CountDownLatch latch = new CountDownLatch(1);
            
            // Add event listener
            bridge.addEventListener(event -> {
                assertEquals("0xabc123", event.proposalId);
                latch.countDown();
            });
            
            // Simulate event
            bridge.simulateWriteAuthorizedEvent(
                "0xabc123",
                "0x742d35cc...",
                "0xdef456",
                BigInteger.valueOf(1_000_000),
                12345L,
                "0xtx123"
            );
            
            // Wait for listener to be called
            assertTrue("Listener should be called", latch.await(2, TimeUnit.SECONDS));
            
        } finally {
            bridge.stop();
        }
    }
    
    @Test
    public void testPaymentNotVerified() {
        EventDrivenEvmBridge bridge = new EventDrivenEvmBridge();
        bridge.start();
        
        try {
            // Query non-existent proposal
            PaymentProof proof = bridge.verifyPayment("0xnonexistent");
            assertNull("Payment proof should not exist", proof);
            
        } finally {
            bridge.stop();
        }
    }

    @Test
    public void testSettlementDetailsByTransactionHashUseCachedEventProof() throws InterruptedException {
        EventDrivenEvmBridge bridge = new EventDrivenEvmBridge(
            "sepolia",
            "0x742d35Cc6634C0532925a3b844Bc9e7595f0bEb0",
            true
        );
        bridge.start();

        try {
            String proposalId = "0xfeed1234";
            String payer = "0x742d35cc6634c0532925a3b844bc9e7595f0beb0";
            String txHash = "0xtxsettled";

            bridge.simulateWriteAuthorizedEvent(
                proposalId,
                payer,
                "0xdef456abc123",
                BigInteger.valueOf(500_000),
                54321L,
                txHash
            );

            Thread.sleep(1500);

            SettlementDetails details = bridge.getSettlementDetailsByTransactionHash(txHash);
            assertNotNull("Settlement details should be available from cached event proof", details);
            assertEquals("sepolia", details.getNetworkName());
            assertEquals(proposalId, details.getProposalId());
            assertEquals(txHash, details.getTransactionHash());
            assertEquals(payer, details.getFromAddress());
        } finally {
            bridge.stop();
        }
    }
}
