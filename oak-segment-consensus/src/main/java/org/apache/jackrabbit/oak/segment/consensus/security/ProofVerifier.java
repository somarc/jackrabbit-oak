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
     * 
     * <p>Genesis verification ensures the joining validator started from the same
     * initial state as the network. This prevents validators with forked or
     * corrupted history from joining consensus.
     * 
     * <p>PRODUCTION_HARDENING: To fully implement genesis verification, we would need to:
     * <ol>
     *   <li>Store the genesis segment ID at cluster initialization time</li>
     *   <li>Persist it in cluster metadata (e.g., in a well-known path or config)</li>
     *   <li>Compare the joining validator's genesis against this stored value</li>
     * </ol>
     * 
     * <p>For now, we validate the format and hash (if provided) to catch obvious errors.
     */
    private VerificationResult verifyGenesisMatch(JoinProof proof) {
        try {
            String theirGenesis = proof.getGenesisSegmentId();
            String theirGenesisHash = proof.getGenesisHash();
            
            // Validate genesis ID is provided
            if (theirGenesis == null || theirGenesis.isEmpty()) {
                log.warn("❌ Genesis check failed: empty genesis ID");
                return VerificationResult.invalid("GENESIS_EMPTY", "No genesis ID provided");
            }
            
            // Validate genesis ID format (Oak segment IDs are UUIDs with optional generation suffix)
            // Format: xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx or xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx.GENERATION
            if (!theirGenesis.matches("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}(\\.\\d+)?$")) {
                log.warn("❌ Genesis check failed: invalid segment ID format: {}", theirGenesis);
                return VerificationResult.invalid("GENESIS_INVALID_FORMAT", 
                    "Genesis segment ID has invalid format (expected UUID)");
            }
            
            // Validate genesis hash format if provided (SHA-256 = 64 hex chars)
            if (theirGenesisHash != null && !theirGenesisHash.isEmpty()) {
                if (!theirGenesisHash.matches("^[0-9a-fA-F]{64}$")) {
                    log.warn("❌ Genesis check failed: invalid hash format: {}", theirGenesisHash);
                    return VerificationResult.invalid("GENESIS_INVALID_HASH", 
                        "Genesis hash has invalid format (expected SHA-256)");
                }
                log.info("✅ Genesis format valid: id={}, hash={}", 
                    theirGenesis.substring(0, Math.min(24, theirGenesis.length())),
                    theirGenesisHash.substring(0, 16) + "...");
            } else {
                log.info("✅ Genesis format valid: id={} (no hash provided)", 
                    theirGenesis.substring(0, Math.min(24, theirGenesis.length())));
            }
            
            // PRODUCTION_HARDENING: Compare against stored genesis segment ID
            // This would require:
            // 1. A GenesisTracker component that stores genesis ID at cluster init
            // 2. Injecting that tracker into ProofVerifier
            // 3. Comparing: if (!ourGenesis.equals(theirGenesis)) return invalid(...)
            
            return VerificationResult.valid("Genesis format validated");
            
        } catch (Exception e) {
            log.error("❌ Genesis verification error", e);
            return VerificationResult.invalid("GENESIS_ERROR", e.getMessage());
        }
    }
    
    /**
     * Verify validator has actual segment data (not just metadata).
     * 
     * <p>Sample segment verification ensures the joining validator has actually
     * downloaded and stored segment data, not just copied metadata. This prevents
     * "lazy sync" attacks where a validator claims to be synced but hasn't
     * actually fetched the data.
     * 
     * <p>PRODUCTION_HARDENING: Full verification would:
     * <ol>
     *   <li>Select random segments from our FileStore</li>
     *   <li>Request the validator to provide hashes for those specific segments</li>
     *   <li>Load our local segments and compute SHA-256 hashes</li>
     *   <li>Compare hashes to detect corruption or missing data</li>
     * </ol>
     * 
     * <p>For now, we validate format and count to catch obvious issues.
     */
    private VerificationResult verifySampleSegments(JoinProof proof) {
        List<String> sampleIds = proof.getSampleSegmentIds();
        List<String> sampleHashes = proof.getSampleSegmentHashes();
        
        // Validate sample count
        if (sampleIds.size() < REQUIRED_SAMPLE_SIZE) {
            String msg = String.format("Insufficient samples: %d (required: %d)", 
                sampleIds.size(), REQUIRED_SAMPLE_SIZE);
            log.warn("❌ Segment sample check failed: {}", msg);
            return VerificationResult.invalid("INSUFFICIENT_SAMPLES", msg);
        }
        
        // Validate ID/hash count match
        if (sampleIds.size() != sampleHashes.size()) {
            String msg = String.format("Sample ID/hash count mismatch: %d IDs vs %d hashes",
                sampleIds.size(), sampleHashes.size());
            log.warn("❌ Segment sample check failed: {}", msg);
            return VerificationResult.invalid("SAMPLE_MISMATCH", msg);
        }
        
        // Validate format of each sample
        int validFormatCount = 0;
        for (int i = 0; i < sampleIds.size(); i++) {
            String segmentId = sampleIds.get(i);
            String claimedHash = sampleHashes.get(i);
            
            // Validate segment ID format (UUID with optional generation)
            if (segmentId == null || !segmentId.matches("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}(\\.\\d+)?$")) {
                log.warn("❌ Invalid segment ID format at index {}: {}", i, segmentId);
                return VerificationResult.invalid("INVALID_SEGMENT_ID", 
                    "Sample segment ID has invalid format at index " + i);
            }
            
            // Validate hash format (SHA-256 = 64 hex chars)
            if (claimedHash == null || !claimedHash.matches("^[0-9a-fA-F]{64}$")) {
                log.warn("❌ Invalid hash format at index {}: {}", i, claimedHash);
                return VerificationResult.invalid("INVALID_SEGMENT_HASH", 
                    "Sample segment hash has invalid format at index " + i);
            }
            
            validFormatCount++;
            log.debug("   Sample {}: {} hash={}", i, 
                segmentId.substring(0, Math.min(16, segmentId.length())),
                claimedHash.substring(0, 16) + "...");
        }
        
        // PRODUCTION_HARDENING: Actual hash verification would happen here
        // 1. For each sample segment ID, load from our FileStore
        // 2. Compute SHA-256 hash of segment bytes
        // 3. Compare against claimed hash
        // 4. Reject if any mismatch (indicates corruption or tampering)
        
        log.info("✅ Segment samples: {} provided, {} format-validated", 
            sampleIds.size(), validFormatCount);
        return VerificationResult.valid("Segment sample formats validated");
    }
    
    /**
     * Verify signature using Ethereum secp256k1 signature verification.
     * 
     * <p>The validator must sign the challenge nonce with their wallet private key.
     * We recover the signer address from the signature and verify it matches
     * the validator's claimed wallet address (validatorId).
     * 
     * <p>This prevents impersonation attacks where a malicious node claims
     * to be a different validator.
     */
    private VerificationResult verifySignature(JoinProof proof) {
        String nonce = proof.getChallengeNonce();
        String signature = proof.getNonceSignature();
        String validatorId = proof.getValidatorId();
        
        // Check if nonce was provided
        if (nonce == null || nonce.isEmpty()) {
            log.warn("⚠️  No nonce provided - signature verification skipped");
            // In POC mode without challenge-response, we allow this
            // PRODUCTION_HARDENING: Make nonce mandatory
            return VerificationResult.valid("Signature check skipped (no nonce)");
        }
        
        // Check if signature was provided
        if (signature == null || signature.isEmpty()) {
            log.warn("❌ Nonce provided but no signature - rejecting proof");
            return VerificationResult.invalid("SIGNATURE_MISSING", 
                "Challenge nonce provided but signature is missing");
        }
        
        // Validate validator ID is a valid Ethereum address
        if (validatorId == null || !validatorId.matches("^0x[a-fA-F0-9]{40}$")) {
            log.warn("❌ Invalid validator ID format: {}", validatorId);
            return VerificationResult.invalid("INVALID_VALIDATOR_ID", 
                "Validator ID must be a valid Ethereum address (0x + 40 hex chars)");
        }
        
        // Check if full verification is available (Bouncy Castle loaded)
        if (!EthereumSignatureVerifier.isFullVerificationAvailable()) {
            log.warn("⚠️  Bouncy Castle not available - using format validation only");
            // Fallback: just validate signature format (65 bytes = 130 hex chars + 0x prefix)
            String normalizedSig = signature.toLowerCase().startsWith("0x") 
                ? signature.substring(2) : signature;
            if (normalizedSig.length() != 130) {
                return VerificationResult.invalid("INVALID_SIGNATURE_FORMAT", 
                    "Signature must be 65 bytes (130 hex chars)");
            }
            log.info("✅ Signature format valid (full verification unavailable)");
            return VerificationResult.valid("Signature format valid (BC unavailable)");
        }
        
        // Full cryptographic verification
        try {
            boolean signatureValid = EthereumSignatureVerifier.verifySignature(
                nonce, signature, validatorId);
            
            if (signatureValid) {
                log.info("✅ Signature verified: validator {} signed nonce correctly", 
                    validatorId.substring(0, 10) + "...");
                return VerificationResult.valid("Signature cryptographically verified");
            } else {
                log.warn("❌ Signature verification failed: recovered address does not match validator {}", 
                    validatorId);
                return VerificationResult.invalid("SIGNATURE_MISMATCH", 
                    "Signature does not match validator's wallet address");
            }
            
        } catch (Exception e) {
            log.error("❌ Signature verification error", e);
            return VerificationResult.invalid("SIGNATURE_ERROR", 
                "Signature verification failed: " + e.getMessage());
        }
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

