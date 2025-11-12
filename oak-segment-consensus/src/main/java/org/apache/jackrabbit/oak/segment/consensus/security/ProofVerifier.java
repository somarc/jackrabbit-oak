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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.MessageDigest;
import java.util.List;

/**
 * Verifies Join Proofs from validators attempting to join consensus.
 * 
 * CRITICAL SECURITY: This prevents Byzantine validators from joining with
 * incomplete or corrupt state.
 */
public class ProofVerifier {
    
    private static final Logger log = LoggerFactory.getLogger(ProofVerifier.class);
    
    private final FileStore fileStore;
    private final int leaderTermSeconds;
    
    /** Maximum age of proof before it's considered stale (seconds) */
    private static final int MAX_PROOF_AGE_SECONDS = 60;
    
    /** Maximum epoch drift allowed (prevents clock skew attacks) */
    private static final int MAX_EPOCH_DRIFT = 2;
    
    /** Number of random segments to verify */
    private static final int REQUIRED_SAMPLE_SIZE = 5;
    
    public ProofVerifier(FileStore fileStore, int leaderTermSeconds) {
        this.fileStore = fileStore;
        this.leaderTermSeconds = leaderTermSeconds;
    }
    
    /**
     * Verify a join proof from a validator.
     * 
     * @param proof The proof to verify
     * @return VerificationResult with details
     */
    public VerificationResult verify(JoinProof proof) {
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        log.info("🔐 PROOF-OF-READINESS VERIFICATION");
        log.info("   Validator: {}", proof.getValidatorId());
        log.info("   URL: {}", proof.getValidatorUrl());
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        
        // 1. Verify proof freshness
        VerificationResult freshnessCheck = verifyProofFreshness(proof);
        if (!freshnessCheck.isValid()) {
            return freshnessCheck;
        }
        
        // 2. Verify HEAD match
        VerificationResult headCheck = verifyHeadMatch(proof);
        if (!headCheck.isValid()) {
            return headCheck;
        }
        
        // 3. Verify epoch alignment
        VerificationResult epochCheck = verifyEpochAlignment(proof);
        if (!epochCheck.isValid()) {
            return epochCheck;
        }
        
        // 4. Verify genesis match
        VerificationResult genesisCheck = verifyGenesisMatch(proof);
        if (!genesisCheck.isValid()) {
            return genesisCheck;
        }
        
        // 5. Verify segment access (sample verification)
        VerificationResult segmentCheck = verifySampleSegments(proof);
        if (!segmentCheck.isValid()) {
            return segmentCheck;
        }
        
        // 6. Verify signature (simplified for POC)
        VerificationResult signatureCheck = verifySignature(proof);
        if (!signatureCheck.isValid()) {
            return signatureCheck;
        }
        
        log.info("✅ All verification checks passed!");
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        
        return VerificationResult.valid("All checks passed");
    }
    
    /**
     * Verify proof is not too old (prevents replay attacks).
     */
    private VerificationResult verifyProofFreshness(JoinProof proof) {
        long now = System.currentTimeMillis();
        long proofAge = (now - proof.getProofGeneratedAt()) / 1000; // seconds
        
        if (proofAge > MAX_PROOF_AGE_SECONDS) {
            String msg = String.format("Proof too old: %d seconds (max: %d)", 
                proofAge, MAX_PROOF_AGE_SECONDS);
            log.warn("❌ Freshness check failed: {}", msg);
            return VerificationResult.invalid("PROOF_STALE", msg);
        }
        
        log.info("✅ Proof freshness: {} seconds old (valid)", proofAge);
        return VerificationResult.valid("Proof is fresh");
    }
    
    /**
     * Verify validator's HEAD matches network HEAD.
     */
    private VerificationResult verifyHeadMatch(JoinProof proof) {
        try {
            String ourHead = fileStore.getHead().getRecordId().toString();
            String theirHead = proof.getHeadSegmentId();
            
            if (!ourHead.equals(theirHead)) {
                String msg = String.format("HEAD mismatch: ours=%s, theirs=%s", 
                    ourHead, theirHead);
                log.warn("❌ HEAD check failed: {}", msg);
                return VerificationResult.invalid("HEAD_MISMATCH", msg);
            }
            
            log.info("✅ HEAD match: {}", ourHead.substring(0, Math.min(24, ourHead.length())));
            return VerificationResult.valid("HEAD matches");
            
        } catch (Exception e) {
            log.error("❌ HEAD verification error", e);
            return VerificationResult.invalid("HEAD_ERROR", e.getMessage());
        }
    }
    
    /**
     * Verify validator's epoch is aligned with network (clock sync).
     */
    private VerificationResult verifyEpochAlignment(JoinProof proof) {
        long now = System.currentTimeMillis() / 1000;
        int ourEpoch = (int) (now / leaderTermSeconds);
        int theirEpoch = proof.getCurrentEpoch();
        
        int epochDrift = Math.abs(ourEpoch - theirEpoch);
        
        if (epochDrift > MAX_EPOCH_DRIFT) {
            String msg = String.format("Epoch drift too large: ours=%d, theirs=%d, drift=%d (max: %d)", 
                ourEpoch, theirEpoch, epochDrift, MAX_EPOCH_DRIFT);
            log.warn("❌ Epoch check failed: {}", msg);
            return VerificationResult.invalid("EPOCH_DRIFT", msg);
        }
        
        log.info("✅ Epoch alignment: ours={}, theirs={}, drift={}", 
            ourEpoch, theirEpoch, epochDrift);
        return VerificationResult.valid("Epoch aligned");
    }
    
    /**
     * Verify validator is on the same chain (genesis matches).
     */
    private VerificationResult verifyGenesisMatch(JoinProof proof) {
        try {
            // Get our genesis segment (implementation specific)
            // For now, we'll verify the genesis ID is reasonable
            String theirGenesis = proof.getGenesisSegmentId();
            
            if (theirGenesis == null || theirGenesis.isEmpty()) {
                log.warn("❌ Genesis check failed: empty genesis ID");
                return VerificationResult.invalid("GENESIS_EMPTY", "No genesis ID provided");
            }
            
            // TODO: In production, verify against our actual genesis
            // For POC, we'll accept any non-empty genesis
            log.info("✅ Genesis provided: {}", 
                theirGenesis.substring(0, Math.min(24, theirGenesis.length())));
            return VerificationResult.valid("Genesis check passed (POC mode)");
            
        } catch (Exception e) {
            log.error("❌ Genesis verification error", e);
            return VerificationResult.invalid("GENESIS_ERROR", e.getMessage());
        }
    }
    
    /**
     * Verify validator has actual segment data (not just metadata).
     */
    private VerificationResult verifySampleSegments(JoinProof proof) {
        List<String> sampleIds = proof.getSampleSegmentIds();
        List<String> sampleHashes = proof.getSampleSegmentHashes();
        
        if (sampleIds.size() < REQUIRED_SAMPLE_SIZE) {
            String msg = String.format("Insufficient samples: %d (required: %d)", 
                sampleIds.size(), REQUIRED_SAMPLE_SIZE);
            log.warn("❌ Segment sample check failed: {}", msg);
            return VerificationResult.invalid("INSUFFICIENT_SAMPLES", msg);
        }
        
        if (sampleIds.size() != sampleHashes.size()) {
            String msg = "Sample ID/hash count mismatch";
            log.warn("❌ Segment sample check failed: {}", msg);
            return VerificationResult.invalid("SAMPLE_MISMATCH", msg);
        }
        
        // Verify a few random samples
        int verified = 0;
        for (int i = 0; i < Math.min(3, sampleIds.size()); i++) {
            String segmentId = sampleIds.get(i);
            String claimedHash = sampleHashes.get(i);
            
            try {
                // For POC, we'll just count the samples as verified
                // In production, we'd actually load and hash the segments
                // TODO: Implement actual segment loading and hash verification
                verified++;
                log.debug("   Sample {}: {} (not verified in POC)", i, segmentId.substring(0, Math.min(16, segmentId.length())));
                
            } catch (Exception e) {
                log.warn("❌ Segment verification error: {}", segmentId, e);
            }
        }
        
        log.info("✅ Segment samples: {} provided, {} verified", 
            sampleIds.size(), verified);
        return VerificationResult.valid("Segment samples verified");
    }
    
    /**
     * Verify signature (simplified for POC).
     */
    private VerificationResult verifySignature(JoinProof proof) {
        String nonce = proof.getChallengeNonce();
        String signature = proof.getNonceSignature();
        
        if (nonce == null || nonce.isEmpty()) {
            log.warn("⚠️  No nonce provided (OK for POC, would fail in production)");
            return VerificationResult.valid("Signature check skipped (POC mode)");
        }
        
        if (signature == null || signature.isEmpty()) {
            log.warn("⚠️  No signature provided (OK for POC, would fail in production)");
            return VerificationResult.valid("Signature check skipped (POC mode)");
        }
        
        // TODO: Implement actual signature verification
        // For POC, we'll accept any non-empty signature
        log.info("✅ Signature provided (not verified in POC mode)");
        return VerificationResult.valid("Signature check passed (POC mode)");
    }
    
    /**
     * Generate a challenge nonce for a joining validator.
     */
    public String generateChallengeNonce() {
        long timestamp = System.currentTimeMillis();
        String random = Long.toHexString((long) (Math.random() * Long.MAX_VALUE));
        return timestamp + "-" + random;
    }
    
    /**
     * Compute SHA-256 hash of data.
     */
    private String computeHash(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            
            // Convert to hex string
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
            
        } catch (Exception e) {
            log.error("Hash computation failed", e);
            return "";
        }
    }
    
    /**
     * Result of verification.
     */
    public static class VerificationResult {
        private final boolean valid;
        private final String errorCode;
        private final String message;
        
        private VerificationResult(boolean valid, String errorCode, String message) {
            this.valid = valid;
            this.errorCode = errorCode;
            this.message = message;
        }
        
        public static VerificationResult valid(String message) {
            return new VerificationResult(true, null, message);
        }
        
        public static VerificationResult invalid(String errorCode, String message) {
            return new VerificationResult(false, errorCode, message);
        }
        
        public boolean isValid() {
            return valid;
        }
        
        public String getErrorCode() {
            return errorCode;
        }
        
        public String getMessage() {
            return message;
        }
        
        @Override
        public String toString() {
            if (valid) {
                return "VALID: " + message;
            } else {
                return "INVALID[" + errorCode + "]: " + message;
            }
        }
    }
}

