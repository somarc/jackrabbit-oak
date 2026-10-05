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

import org.apache.jackrabbit.oak.segment.consensus.config.BlockchainConfig;
import org.apache.jackrabbit.oak.segment.consensus.evm.impl.SimpleEvmBridge;
import org.apache.jackrabbit.oak.segment.consensus.evm.impl.SimplePaymentProof;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for the EVM bridge payment verifier.
 */
public class EvmBridgeTest {
    
    private SimpleEvmBridge bridge;
    
    @Before
    public void setUp() {
        System.clearProperty("oak.blockchain.mode");
        BlockchainConfig.reset();
        bridge = new SimpleEvmBridge("sepolia", "0x742d35Cc6634C0532925a3b844Bc9e7595f0bEb0");
        bridge.start();
    }
    
    @After
    public void tearDown() {
        bridge.stop();
        System.clearProperty("oak.blockchain.mode");
        BlockchainConfig.reset();
    }
    
    @Test
    public void testBridgeConfiguration() {
        assertEquals("Network name should match", "sepolia", bridge.getNetworkName());
        assertEquals("Contract address should match", 
                "0x742d35Cc6634C0532925a3b844Bc9e7595f0bEb0", 
                bridge.getContractAddress());
    }
    
    @Test
    public void testPaymentVerification() {
        String proposalId = "proposal-123";
        String walletAddress = "0x1234567890123456789012345678901234567890";
        
        // Register wallet for proposal (required in MOCK mode before verifyPayment)
        bridge.registerProposalWallet(proposalId, walletAddress);
        
        // Simulate a payment
        PaymentProof payment = new SimplePaymentProof(
                "0xabc123",
                1000000,
                walletAddress,
                bridge.getContractAddress(),
                proposalId,
                "1000000000000000",
                12
        );
        bridge.simulatePayment(payment);
        
        // Verify payment
        PaymentProof proof2 = bridge.verifyPayment(proposalId);
        assertNotNull("Should find payment", proof2);
        assertEquals("Transaction hash should match", "0xabc123", proof2.getTransactionHash());
        assertEquals("Proposal ID should match", proposalId, proof2.getProposalId());
    }

    @Test
    public void testSettlementDetailsByProposalIdExposeBasicFields() {
        String proposalId = "proposal-settlement";
        String walletAddress = "0x1234567890123456789012345678901234567890";
        bridge.registerProposalWallet(proposalId, walletAddress);

        PaymentProof payment = new SimplePaymentProof(
            "0xsettlementtx",
            1001234,
            walletAddress,
            bridge.getContractAddress(),
            proposalId,
            "1000000000000000",
            12
        );
        bridge.simulatePayment(payment);

        SettlementDetails details = bridge.getSettlementDetailsByProposalId(proposalId);

        assertNotNull("Settlement details should be available by proposal id", details);
        assertEquals("sepolia", details.getNetworkName());
        assertEquals(proposalId, details.getProposalId());
        assertEquals("0xsettlementtx", details.getTransactionHash());
        assertEquals(walletAddress, details.getFromAddress());
    }

    @Test
    public void testSettlementDetailsByTransactionHashResolveFromCachedPayment() {
        PaymentProof payment = new SimplePaymentProof(
            "0xlookuptx",
            1002000,
            "0x1234567890123456789012345678901234567890",
            bridge.getContractAddress(),
            "proposal-lookup",
            "2000000000000000",
            6
        );
        bridge.simulatePayment(payment);

        SettlementDetails details = bridge.getSettlementDetailsByTransactionHash("0xlookuptx");

        assertNotNull("Settlement details should be available by transaction hash", details);
        assertEquals("proposal-lookup", details.getProposalId());
        assertEquals("0xlookuptx", details.getTransactionHash());
        assertEquals(1002000, details.getBlockNumber());
    }
    
    @Test
    public void testPaymentConfirmations() {
        PaymentProof payment = new SimplePaymentProof(
                "0xdef456",
                1000000,
                "0x1234567890123456789012345678901234567890",
                bridge.getContractAddress(),
                "proposal-456",
                "2000000000000000",
                6
        );
        
        // Should not be confirmed with 12 required
        assertFalse("Should not be confirmed with 6 confirmations", payment.isConfirmed(12));
        
        // Should be confirmed with 5 required
        assertTrue("Should be confirmed with 5 confirmations", payment.isConfirmed(5));
    }
    
    @Test
    public void testMultiplePayments() {
        // Simulate multiple payments
        for (int i = 0; i < 10; i++) {
            String proposalId = "proposal-" + i;
            PaymentProof payment = new SimplePaymentProof(
                    "0xtx" + i,
                    1000000 + i,
                    "0x1234567890123456789012345678901234567890",
                    bridge.getContractAddress(),
                    proposalId,
                    String.valueOf(1000000000000000L * (i + 1)),
                    12
            );
            bridge.simulatePayment(payment);
        }
        
        // Verify all payments
        for (int i = 0; i < 10; i++) {
            String proposalId = "proposal-" + i;
            PaymentProof proof = bridge.verifyPayment(proposalId);
            assertNotNull("Should find payment for " + proposalId, proof);
            assertEquals("Proposal ID should match", proposalId, proof.getProposalId());
            assertTrue("Payment should be confirmed", proof.isConfirmed(12));
        }
    }
    
    @Test
    public void testPaymentProofDetails() {
        PaymentProof payment = new SimplePaymentProof(
                "0x123abc456def",
                2000000,
                "0xFromAddress",
                "0xContractAddress",
                "proposal-789",
                "5000000000000000",
                20
        );
        
        assertEquals("TX hash should match", "0x123abc456def", payment.getTransactionHash());
        assertEquals("Block number should match", 2000000, payment.getBlockNumber());
        assertEquals("From address should match", "0xFromAddress", payment.getFromAddress());
        assertEquals("Contract address should match", "0xContractAddress", payment.getContractAddress());
        assertEquals("Proposal ID should match", "proposal-789", payment.getProposalId());
        assertEquals("Amount should match", "5000000000000000", payment.getAmountWei());
        assertEquals("Proposal kind should default to WRITE", PaymentProof.ProposalKind.WRITE, payment.getProposalKind());
        assertEquals("Payment token should default to UNKNOWN", PaymentProof.PaymentToken.UNKNOWN, payment.getPaymentToken());
        assertEquals("Capability flags should default to zero", 0, payment.getCapabilityFlags());
        assertEquals("Confirmations should match", 20, payment.getConfirmations());
    }

    @Test
    public void testMockModeAutoSimulatesBytes32ProposalIds() {
        bridge.stop();
        System.setProperty("oak.blockchain.mode", "mock");
        BlockchainConfig.reset();
        bridge = new SimpleEvmBridge("mock", "0x742d35Cc6634C0532925a3b844Bc9e7595f0bEb0");
        bridge.start();

        String proposalId = "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        String walletAddress = "0x1234567890123456789012345678901234567890";
        bridge.registerProposalWallet(proposalId, walletAddress);

        PaymentProof proof = bridge.verifyPayment(proposalId);
        assertNotNull("Mock mode should auto-simulate bytes32 proposal ids", proof);
        assertEquals("Proposal id should be preserved", proposalId, proof.getProposalId());
        assertEquals("Bytes32 proposal ids should also work as mock tx hashes", proposalId, proof.getTransactionHash());
        assertEquals("Registered wallet should flow into the mock proof", walletAddress, proof.getFromAddress());
    }

    @Test
    public void testMockModeSettlementLookupForUnknownProposalIsMissNotSecurityViolation() {
        bridge.stop();
        System.setProperty("oak.blockchain.mode", "mock");
        BlockchainConfig.reset();
        bridge = new SimpleEvmBridge("mock", "0x742d35Cc6634C0532925a3b844Bc9e7595f0bEb0");
        bridge.start();

        String unknownProposalId = "0x4cc359f9f42dbddc32d50823371578a70f59502e75f7aa41313bfcdb668affad";

        assertNull("Read lookup of an unregistered proposal should be a miss",
                bridge.getSettlementDetailsByProposalId(unknownProposalId));
        assertNull("Read lookup of an unknown transaction hash should be a miss",
                bridge.getSettlementDetailsByTransactionHash(unknownProposalId));

        try {
            bridge.verifyPayment(unknownProposalId);
            fail("verifyPayment must still enforce registerProposalWallet() for unregistered proposals");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("SECURITY VIOLATION"));
        }
    }

    @Test
    public void testMockModeSettlementLookupForRegisteredProposalStillResolves() {
        bridge.stop();
        System.setProperty("oak.blockchain.mode", "mock");
        BlockchainConfig.reset();
        bridge = new SimpleEvmBridge("mock", "0x742d35Cc6634C0532925a3b844Bc9e7595f0bEb0");
        bridge.start();

        String proposalId = "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
        String walletAddress = "0x1234567890123456789012345678901234567890";
        bridge.registerProposalWallet(proposalId, walletAddress);

        SettlementDetails details = bridge.getSettlementDetailsByProposalId(proposalId);
        assertNotNull("Registered proposal should still resolve settlement details in mock mode", details);
        assertEquals(proposalId, details.getProposalId());
        assertEquals(walletAddress, details.getFromAddress());
    }
}
