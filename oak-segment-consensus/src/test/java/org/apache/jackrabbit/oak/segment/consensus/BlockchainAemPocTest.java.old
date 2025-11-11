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
package org.apache.jackrabbit.oak.segment.consensus;

import org.apache.jackrabbit.oak.segment.consensus.impl.BasicValidator;
import org.apache.jackrabbit.oak.segment.consensus.impl.SimpleProposal;
import org.apache.jackrabbit.oak.segment.consensus.wallet.Wallet;
import org.junit.Test;

import java.security.SignatureException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.*;

/**
 * Proof of Concept test for Blockchain AEM consensus protocol.
 * <p>
 * Demonstrates:
 * - Wallet creation and signing
 * - Proposal creation and validation
 * - Multi-validator consensus
 * - Path ownership enforcement
 */
public class BlockchainAemPocTest {

    @Test
    public void testWalletCreationAndSigning() throws Exception {
        System.out.println("\n=== Test: Wallet Creation and Signing ===");
        
        // Create a wallet
        Wallet wallet = new Wallet();
        System.out.println("Created wallet: " + wallet);
        System.out.println("Wallet ID: " + wallet.getWalletId());
        System.out.println("Base path: " + wallet.getBasePath());
        
        // Test signing
        byte[] data = "Test data to sign".getBytes();
        byte[] signature = wallet.sign(data);
        
        System.out.println("Signature length: " + signature.length + " bytes");
        
        // Verify signature
        assertTrue("Signature should be valid", wallet.verify(data, signature));
        
        // Test with wrong data
        byte[] wrongData = "Wrong data".getBytes();
        assertFalse("Signature should be invalid for wrong data", 
                   wallet.verify(wrongData, signature));
        
        System.out.println("✓ Wallet signing/verification works!");
    }

    @Test
    public void testProposalCreationAndValidation() throws Exception {
        System.out.println("\n=== Test: Proposal Creation and Validation ===");
        
        // Create a wallet
        Wallet authorWallet = new Wallet();
        System.out.println("Author wallet: " + authorWallet.getWalletId());
        
        // Create a proposal
        String targetPath = authorWallet.getBasePath() + "/mysite/pages/homepage";
        System.out.println("Target path: " + targetPath);
        
        SimpleProposal proposal = new SimpleProposal.Builder()
                .targetPath(targetPath)
                .addSegmentId(UUID.randomUUID())
                .addSegmentId(UUID.randomUUID())
                .paymentProof("0xabc123def456...") // Mock EVM tx hash
                .estimatedSize(1024 * 1024) // 1 MB
                .signAndBuild(authorWallet);
        
        System.out.println("Created proposal: " + proposal);
        System.out.println("Proposal ID: " + proposal.getProposalId());
        System.out.println("Segments: " + proposal.getSegmentIds().size());
        
        // Validate proposal structure
        String validationError = proposal.validate();
        assertNull("Proposal should be valid", validationError);
        System.out.println("✓ Proposal structure is valid");
        
        // Verify signature
        assertTrue("Signature should be valid", 
                  authorWallet.verify(proposal.getSignableBytes(), proposal.getSignature()));
        System.out.println("✓ Proposal signature is valid");
    }

    @Test
    public void testPathOwnershipEnforcement() throws Exception {
        System.out.println("\n=== Test: Path Ownership Enforcement ===");
        
        Wallet wallet = new Wallet();
        String ownedPath = wallet.getBasePath() + "/mysite/pages";
        String notOwnedPath = "/oak-chain/content/" + UUID.randomUUID() + "/mysite/pages";
        
        System.out.println("Owned path: " + ownedPath);
        System.out.println("Not owned path: " + notOwnedPath);
        
        assertTrue("Should own its own path", wallet.ownsPath(ownedPath));
        assertFalse("Should not own someone else's path", wallet.ownsPath(notOwnedPath));
        
        System.out.println("✓ Path ownership enforced correctly");
    }

    @Test
    public void testValidatorApproval() throws Exception {
        System.out.println("\n=== Test: Validator Approval ===");
        
        // Create author and validator
        Wallet authorWallet = new Wallet();
        Wallet validatorWallet = new Wallet();
        BasicValidator validator = new BasicValidator(validatorWallet);
        
        System.out.println("Author: " + authorWallet.getWalletId());
        System.out.println("Validator: " + validator.getValidatorId());
        
        // Create a valid proposal
        SimpleProposal proposal = new SimpleProposal.Builder()
                .targetPath(authorWallet.getBasePath() + "/content/page1")
                .addSegmentId(UUID.randomUUID())
                .paymentProof("0x123abc...")
                .estimatedSize(512 * 1024)
                .signAndBuild(authorWallet);
        
        System.out.println("Proposal: " + proposal);
        
        // Validate
        Vote vote = validator.validate(proposal);
        System.out.println("Vote: " + vote);
        System.out.println("Decision: " + vote.getDecision());
        
        assertEquals("Validator should approve valid proposal", 
                    Vote.Decision.APPROVE, vote.getDecision());
        assertEquals("Vote should be from the validator", 
                    validator.getValidatorId(), vote.getValidatorId());
        
        System.out.println("✓ Validator approved valid proposal");
    }

    @Test
    public void testValidatorRejection() throws Exception {
        System.out.println("\n=== Test: Validator Rejection ===");
        
        // Create author and validator
        Wallet authorWallet = new Wallet();
        Wallet validatorWallet = new Wallet();
        BasicValidator validator = new BasicValidator(validatorWallet);
        
        // Create a proposal with WRONG path (not owned by author)
        String wrongPath = "/oak-chain/content/" + UUID.randomUUID() + "/content/page1";
        
        SimpleProposal proposal = new SimpleProposal.Builder()
                .targetPath(wrongPath)
                .addSegmentId(UUID.randomUUID())
                .paymentProof("0x123abc...")
                .estimatedSize(512 * 1024)
                .signAndBuild(authorWallet);
        
        System.out.println("Proposal with wrong path: " + wrongPath);
        System.out.println("Author's base path: " + authorWallet.getBasePath());
        
        // Validate
        Vote vote = validator.validate(proposal);
        System.out.println("Vote: " + vote);
        System.out.println("Decision: " + vote.getDecision());
        System.out.println("Reason: " + vote.getReason());
        
        assertEquals("Validator should reject invalid proposal", 
                    Vote.Decision.REJECT, vote.getDecision());
        assertNotNull("Should have a rejection reason", vote.getReason());
        assertTrue("Reason should mention namespace", 
                  vote.getReason().contains("namespace"));
        
        System.out.println("✓ Validator rejected invalid proposal");
    }

    @Test
    public void testMultiValidatorConsensus() throws Exception {
        System.out.println("\n=== Test: Multi-Validator Consensus ===");
        
        // Create author
        Wallet authorWallet = new Wallet();
        System.out.println("Author: " + authorWallet.getWalletId());
        
        // Create 3 validators
        List<BasicValidator> validators = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Wallet validatorWallet = new Wallet();
            BasicValidator validator = new BasicValidator(validatorWallet);
            validators.add(validator);
            System.out.println("Validator " + (i+1) + ": " + validator.getValidatorId());
        }
        
        // Create proposal
        SimpleProposal proposal = new SimpleProposal.Builder()
                .targetPath(authorWallet.getBasePath() + "/site/component/hero")
                .addSegmentId(UUID.randomUUID())
                .addSegmentId(UUID.randomUUID())
                .addSegmentId(UUID.randomUUID())
                .paymentProof("0xdeadbeef...")
                .estimatedSize(2 * 1024 * 1024) // 2 MB
                .signAndBuild(authorWallet);
        
        System.out.println("\nProposal: " + proposal);
        System.out.println("Segments: " + proposal.getSegmentIds().size());
        System.out.println("Size: " + proposal.getEstimatedSize() + " bytes");
        
        // Collect votes
        List<Vote> votes = new ArrayList<>();
        int approvalCount = 0;
        int rejectionCount = 0;
        double totalWeight = 0;
        double approvalWeight = 0;
        
        System.out.println("\nValidation results:");
        for (BasicValidator validator : validators) {
            Vote vote = validator.validate(proposal);
            votes.add(vote);
            
            System.out.println("  " + validator.getValidatorId() + ": " + 
                             vote.getDecision() + " (weight: " + vote.getStakeWeight() + ")");
            
            totalWeight += vote.getStakeWeight();
            if (vote.getDecision() == Vote.Decision.APPROVE) {
                approvalCount++;
                approvalWeight += vote.getStakeWeight();
            } else if (vote.getDecision() == Vote.Decision.REJECT) {
                rejectionCount++;
            }
        }
        
        // Calculate consensus
        double approvalPercentage = (approvalWeight / totalWeight) * 100;
        boolean hasConsensus = approvalPercentage >= 66.67; // Supermajority
        
        System.out.println("\nConsensus results:");
        System.out.println("  Total validators: " + validators.size());
        System.out.println("  Approvals: " + approvalCount);
        System.out.println("  Rejections: " + rejectionCount);
        System.out.println("  Approval weight: " + approvalWeight + "/" + totalWeight + 
                         " (" + String.format("%.1f", approvalPercentage) + "%)");
        System.out.println("  Consensus: " + (hasConsensus ? "APPROVED ✓" : "REJECTED ✗"));
        
        assertTrue("Should reach supermajority consensus", hasConsensus);
        assertEquals("All validators should approve valid proposal", 
                    3, approvalCount);
        
        System.out.println("\n✓ Multi-validator consensus achieved!");
    }

    @Test
    public void testWalletPersistence() throws Exception {
        System.out.println("\n=== Test: Wallet Persistence ===");
        
        // Create a wallet
        Wallet wallet1 = new Wallet();
        UUID originalId = wallet1.getWalletId();
        byte[] privateKey = wallet1.getPrivateKey();
        byte[] publicKey = wallet1.getPublicKey();
        
        System.out.println("Original wallet ID: " + originalId);
        
        // Sign some data
        byte[] data = "Important data".getBytes();
        byte[] signature = wallet1.sign(data);
        
        // Restore wallet from keys
        Wallet wallet2 = new Wallet(privateKey, publicKey);
        UUID restoredId = wallet2.getWalletId();
        
        System.out.println("Restored wallet ID: " + restoredId);
        
        // Verify IDs match
        assertEquals("Wallet IDs should match after restoration", 
                    originalId, restoredId);
        
        // Verify signature still works
        assertTrue("Should verify signature with restored wallet", 
                  wallet2.verify(data, signature));
        
        // Sign new data with restored wallet
        byte[] newSignature = wallet2.sign(data);
        assertTrue("Original wallet should verify signature from restored wallet", 
                  wallet1.verify(data, newSignature));
        
        System.out.println("✓ Wallet persistence works correctly");
    }
}

