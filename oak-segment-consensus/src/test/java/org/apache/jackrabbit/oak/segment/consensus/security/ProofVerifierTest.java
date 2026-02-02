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
package org.apache.jackrabbit.oak.segment.consensus.security;

import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.apache.jackrabbit.oak.segment.SegmentNodeState;
import org.apache.jackrabbit.oak.segment.RecordId;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Tests for ProofVerifier join proof verification.
 * 
 * <p>These tests verify the security checks performed when a validator
 * attempts to join the consensus network.
 */
public class ProofVerifierTest {
    
    private FileStore mockFileStore;
    private SegmentNodeState mockHead;
    private RecordId mockRecordId;
    private ProofVerifier verifier;
    
    // Test constants
    private static final int LEADER_TERM_SECONDS = 30;
    private static final String VALID_SEGMENT_ID = "12345678-1234-1234-1234-123456789abc";
    private static final String VALID_HASH = "a".repeat(64); // SHA-256 = 64 hex chars
    private static final String VALID_WALLET = "0x742d35Cc6634C0532925a3b844Bc9e7595f1b3E8";
    
    @Before
    public void setUp() {
        // Create mocks
        mockFileStore = mock(FileStore.class);
        mockHead = mock(SegmentNodeState.class);
        mockRecordId = mock(RecordId.class);
        
        // Configure mock behavior
        when(mockFileStore.getHead()).thenReturn(mockHead);
        when(mockHead.getRecordId()).thenReturn(mockRecordId);
        when(mockRecordId.toString()).thenReturn(VALID_SEGMENT_ID);
        
        // Create verifier
        verifier = new ProofVerifier(mockFileStore, LEADER_TERM_SECONDS);
    }
    
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Proof Freshness Tests
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    
    @Test
    public void testFreshProofAccepted() {
        JoinProof proof = createValidProof();
        proof.setProofGeneratedAt(System.currentTimeMillis() - 10_000); // 10 seconds ago
        
        ProofVerifier.VerificationResult result = verifier.verify(proof);
        
        // Should pass freshness check (might fail on other checks, but not freshness)
        // We check by ensuring it doesn't fail with PROOF_STALE
        if (!result.isValid()) {
            assertNotEquals("PROOF_STALE", result.getErrorCode());
        }
    }
    
    @Test
    public void testStaleProofRejected() {
        JoinProof proof = createValidProof();
        proof.setProofGeneratedAt(System.currentTimeMillis() - 120_000); // 2 minutes ago
        
        ProofVerifier.VerificationResult result = verifier.verify(proof);
        
        assertFalse("Stale proof should be rejected", result.isValid());
        assertEquals("PROOF_STALE", result.getErrorCode());
    }
    
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // HEAD Match Tests
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    
    @Test
    public void testHeadMatchAccepted() {
        JoinProof proof = createValidProof();
        proof.setHeadSegmentId(VALID_SEGMENT_ID); // Matches mock
        
        ProofVerifier.VerificationResult result = verifier.verify(proof);
        
        // Should pass HEAD check
        if (!result.isValid()) {
            assertNotEquals("HEAD_MISMATCH", result.getErrorCode());
        }
    }
    
    @Test
    public void testHeadMismatchRejected() {
        JoinProof proof = createValidProof();
        proof.setHeadSegmentId("different-segment-id-that-doesnt-match");
        
        ProofVerifier.VerificationResult result = verifier.verify(proof);
        
        assertFalse("HEAD mismatch should be rejected", result.isValid());
        assertEquals("HEAD_MISMATCH", result.getErrorCode());
    }
    
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Epoch Alignment Tests
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    
    @Test
    public void testEpochAlignedAccepted() {
        JoinProof proof = createValidProof();
        int currentEpoch = (int) (System.currentTimeMillis() / 1000 / LEADER_TERM_SECONDS);
        proof.setCurrentEpoch(currentEpoch);
        
        ProofVerifier.VerificationResult result = verifier.verify(proof);
        
        // Should pass epoch check
        if (!result.isValid()) {
            assertNotEquals("EPOCH_DRIFT", result.getErrorCode());
        }
    }
    
    @Test
    public void testEpochDriftRejected() {
        JoinProof proof = createValidProof();
        int currentEpoch = (int) (System.currentTimeMillis() / 1000 / LEADER_TERM_SECONDS);
        proof.setCurrentEpoch(currentEpoch + 100); // Way off
        
        ProofVerifier.VerificationResult result = verifier.verify(proof);
        
        assertFalse("Large epoch drift should be rejected", result.isValid());
        assertEquals("EPOCH_DRIFT", result.getErrorCode());
    }
    
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Genesis Verification Tests
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    
    @Test
    public void testValidGenesisFormatAccepted() {
        JoinProof proof = createValidProof();
        proof.setGenesisSegmentId(VALID_SEGMENT_ID);
        proof.setGenesisHash(VALID_HASH);
        
        ProofVerifier.VerificationResult result = verifier.verify(proof);
        
        // Should pass genesis format check
        if (!result.isValid()) {
            assertFalse("Should not fail on genesis format", 
                result.getErrorCode().startsWith("GENESIS_"));
        }
    }
    
    @Test
    public void testEmptyGenesisRejected() {
        JoinProof proof = createValidProof();
        proof.setGenesisSegmentId("");
        
        ProofVerifier.VerificationResult result = verifier.verify(proof);
        
        assertFalse("Empty genesis should be rejected", result.isValid());
        assertEquals("GENESIS_EMPTY", result.getErrorCode());
    }
    
    @Test
    public void testInvalidGenesisFormatRejected() {
        JoinProof proof = createValidProof();
        proof.setGenesisSegmentId("not-a-valid-uuid-format");
        
        ProofVerifier.VerificationResult result = verifier.verify(proof);
        
        assertFalse("Invalid genesis format should be rejected", result.isValid());
        assertEquals("GENESIS_INVALID_FORMAT", result.getErrorCode());
    }
    
    @Test
    public void testInvalidGenesisHashFormatRejected() {
        JoinProof proof = createValidProof();
        proof.setGenesisSegmentId(VALID_SEGMENT_ID);
        proof.setGenesisHash("not-a-valid-sha256-hash");
        
        ProofVerifier.VerificationResult result = verifier.verify(proof);
        
        assertFalse("Invalid genesis hash format should be rejected", result.isValid());
        assertEquals("GENESIS_INVALID_HASH", result.getErrorCode());
    }
    
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Sample Segment Tests
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    
    @Test
    public void testSufficientSamplesAccepted() {
        JoinProof proof = createValidProof();
        // Add 5 valid samples (minimum required)
        for (int i = 0; i < 5; i++) {
            proof.getSampleSegmentIds().add(VALID_SEGMENT_ID);
            proof.getSampleSegmentHashes().add(VALID_HASH);
        }
        
        ProofVerifier.VerificationResult result = verifier.verify(proof);
        
        // Should pass sample check
        if (!result.isValid()) {
            assertFalse("Should not fail on sample count", 
                result.getErrorCode().contains("SAMPLE"));
        }
    }
    
    @Test
    public void testInsufficientSamplesRejected() {
        JoinProof proof = createValidProof();
        proof.getSampleSegmentIds().clear();
        proof.getSampleSegmentHashes().clear();
        // Add only 2 samples (less than required 5)
        for (int i = 0; i < 2; i++) {
            proof.getSampleSegmentIds().add(VALID_SEGMENT_ID);
            proof.getSampleSegmentHashes().add(VALID_HASH);
        }
        
        ProofVerifier.VerificationResult result = verifier.verify(proof);
        
        assertFalse("Insufficient samples should be rejected", result.isValid());
        assertEquals("INSUFFICIENT_SAMPLES", result.getErrorCode());
    }
    
    @Test
    public void testSampleCountMismatchRejected() {
        JoinProof proof = createValidProof();
        // Add 5 IDs but only 3 hashes
        for (int i = 0; i < 5; i++) {
            proof.getSampleSegmentIds().add(VALID_SEGMENT_ID);
        }
        for (int i = 0; i < 3; i++) {
            proof.getSampleSegmentHashes().add(VALID_HASH);
        }
        
        ProofVerifier.VerificationResult result = verifier.verify(proof);
        
        assertFalse("Sample count mismatch should be rejected", result.isValid());
        assertEquals("SAMPLE_MISMATCH", result.getErrorCode());
    }
    
    @Test
    public void testInvalidSampleSegmentIdRejected() {
        JoinProof proof = createValidProof();
        // Add 4 valid samples and 1 invalid
        for (int i = 0; i < 4; i++) {
            proof.getSampleSegmentIds().add(VALID_SEGMENT_ID);
            proof.getSampleSegmentHashes().add(VALID_HASH);
        }
        proof.getSampleSegmentIds().add("invalid-segment-id");
        proof.getSampleSegmentHashes().add(VALID_HASH);
        
        ProofVerifier.VerificationResult result = verifier.verify(proof);
        
        assertFalse("Invalid segment ID format should be rejected", result.isValid());
        assertEquals("INVALID_SEGMENT_ID", result.getErrorCode());
    }
    
    @Test
    public void testInvalidSampleHashRejected() {
        JoinProof proof = createValidProof();
        // Add 4 valid samples and 1 with invalid hash
        for (int i = 0; i < 4; i++) {
            proof.getSampleSegmentIds().add(VALID_SEGMENT_ID);
            proof.getSampleSegmentHashes().add(VALID_HASH);
        }
        proof.getSampleSegmentIds().add(VALID_SEGMENT_ID);
        proof.getSampleSegmentHashes().add("invalid-hash");
        
        ProofVerifier.VerificationResult result = verifier.verify(proof);
        
        assertFalse("Invalid hash format should be rejected", result.isValid());
        assertEquals("INVALID_SEGMENT_HASH", result.getErrorCode());
    }
    
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Signature Verification Tests
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    
    @Test
    public void testNoNonceSkipsSignatureCheck() {
        JoinProof proof = createValidProof();
        proof.setChallengeNonce(null);
        proof.setNonceSignature(null);
        
        ProofVerifier.VerificationResult result = verifier.verify(proof);
        
        // Should pass (signature check skipped when no nonce)
        // Might fail on other checks, but not signature
        if (!result.isValid()) {
            assertFalse("Should not fail on signature when no nonce", 
                result.getErrorCode().contains("SIGNATURE"));
        }
    }
    
    @Test
    public void testNonceWithoutSignatureRejected() {
        JoinProof proof = createValidProof();
        proof.setChallengeNonce("test-nonce-12345");
        proof.setNonceSignature(null); // Nonce provided but no signature
        
        ProofVerifier.VerificationResult result = verifier.verify(proof);
        
        assertFalse("Nonce without signature should be rejected", result.isValid());
        assertEquals("SIGNATURE_MISSING", result.getErrorCode());
    }
    
    @Test
    public void testInvalidValidatorIdRejected() {
        JoinProof proof = createValidProof();
        proof.setChallengeNonce("test-nonce-12345");
        proof.setNonceSignature("0x" + "a".repeat(130)); // Valid format
        proof.setValidatorId("not-a-valid-wallet-address");
        
        ProofVerifier.VerificationResult result = verifier.verify(proof);
        
        assertFalse("Invalid validator ID should be rejected", result.isValid());
        assertEquals("INVALID_VALIDATOR_ID", result.getErrorCode());
    }
    
    @Test
    public void testInvalidSignatureFormatRejected() {
        JoinProof proof = createValidProof();
        proof.setChallengeNonce("test-nonce-12345");
        proof.setNonceSignature("0x1234"); // Too short
        proof.setValidatorId(VALID_WALLET);
        
        // This test depends on whether Bouncy Castle is available
        // If BC is available, it will try full verification and fail
        // If BC is not available, it will do format validation and fail
        ProofVerifier.VerificationResult result = verifier.verify(proof);
        
        assertFalse("Invalid signature format should be rejected", result.isValid());
        // Error code depends on BC availability
        assertTrue("Should fail on signature", 
            result.getErrorCode().contains("SIGNATURE") || 
            result.getErrorCode().contains("INVALID"));
    }
    
    @Test
    public void testSignatureMismatchRejected() {
        // This test only runs if Bouncy Castle is available
        if (!EthereumSignatureVerifier.isFullVerificationAvailable()) {
            System.out.println("Skipping signature mismatch test - Bouncy Castle not available");
            return;
        }
        
        JoinProof proof = createValidProof();
        proof.setChallengeNonce("test-nonce-12345");
        // Valid format but garbage signature that won't match any address
        proof.setNonceSignature("0x" + 
            "1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef" +
            "1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef" +
            "1b");
        proof.setValidatorId(VALID_WALLET);
        
        ProofVerifier.VerificationResult result = verifier.verify(proof);
        
        assertFalse("Signature mismatch should be rejected", result.isValid());
        assertEquals("SIGNATURE_MISMATCH", result.getErrorCode());
    }
    
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Full Verification Tests
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    
    @Test
    public void testFullyValidProofAccepted() {
        JoinProof proof = createValidProof();
        
        ProofVerifier.VerificationResult result = verifier.verify(proof);
        
        assertTrue("Fully valid proof should be accepted: " + result.getMessage(), 
            result.isValid());
    }
    
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Challenge Nonce Generation Tests
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    
    @Test
    public void testGenerateChallengeNonce() {
        String nonce1 = verifier.generateChallengeNonce();
        String nonce2 = verifier.generateChallengeNonce();
        
        assertNotNull("Nonce should not be null", nonce1);
        assertNotNull("Nonce should not be null", nonce2);
        assertFalse("Nonces should be unique", nonce1.equals(nonce2));
        assertTrue("Nonce should contain timestamp", nonce1.contains("-"));
    }
    
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Helper Methods
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    
    /**
     * Create a valid JoinProof for testing.
     * All fields are set to valid values that should pass verification.
     */
    private JoinProof createValidProof() {
        JoinProof proof = new JoinProof();
        
        // Proof freshness
        proof.setProofGeneratedAt(System.currentTimeMillis());
        
        // HEAD match
        proof.setHeadSegmentId(VALID_SEGMENT_ID);
        proof.setHeadCapturedAt(System.currentTimeMillis());
        
        // Epoch alignment
        int currentEpoch = (int) (System.currentTimeMillis() / 1000 / LEADER_TERM_SECONDS);
        proof.setCurrentEpoch(currentEpoch);
        proof.setEpochCalculatedAt(System.currentTimeMillis());
        
        // Genesis
        proof.setGenesisSegmentId(VALID_SEGMENT_ID);
        proof.setGenesisHash(VALID_HASH);
        
        // Validator identity
        proof.setValidatorId(VALID_WALLET);
        proof.setValidatorUrl("http://localhost:8090");
        
        // Sample segments (5 required)
        for (int i = 0; i < 5; i++) {
            proof.getSampleSegmentIds().add(VALID_SEGMENT_ID);
            proof.getSampleSegmentHashes().add(VALID_HASH);
        }
        
        // No challenge-response (optional in POC mode)
        proof.setChallengeNonce(null);
        proof.setNonceSignature(null);
        
        return proof;
    }
}
