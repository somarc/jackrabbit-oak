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

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Verifies cryptographic signatures on leadership claims.
 * 
 * PHASE 3: Byzantine Fault Tolerance
 * - Verifies claims are from legitimate validators
 * - Prevents claim forgery and impersonation attacks
 * - Maintains registry of validator public keys
 */
public class ClaimVerifier {
    
    private static final Logger log = LoggerFactory.getLogger(ClaimVerifier.class);
    
    // Registry of validator public keys (validatorUrl -> PublicKey)
    private final Map<String, PublicKey> validatorPublicKeys = new ConcurrentHashMap<>();
    
    /**
     * Register a validator's public key.
     * 
     * @param validatorUrl The validator's URL
     * @param publicKeyHex Hex-encoded public key (starting with "0x")
     */
    public void registerPublicKey(String validatorUrl, String publicKeyHex) {
        try {
            // Decode hex string to bytes
            byte[] keyBytes = hexToBytes(publicKeyHex);
            
            // Create PublicKey from encoded bytes
            KeyFactory keyFactory = KeyFactory.getInstance("EC");
            X509EncodedKeySpec keySpec = new X509EncodedKeySpec(keyBytes);
            PublicKey publicKey = keyFactory.generatePublic(keySpec);
            
            validatorPublicKeys.put(validatorUrl, publicKey);
            
            log.info("🔑 Public key registered for {}", validatorUrl);
            log.debug("   Key: {}...", publicKeyHex.substring(0, Math.min(18, publicKeyHex.length())));
            
        } catch (Exception e) {
            log.error("❌ Failed to register public key for {}", validatorUrl, e);
        }
    }
    
    /**
     * Register a public key object directly (for POC).
     */
    public void registerPublicKey(String validatorUrl, PublicKey publicKey) {
        validatorPublicKeys.put(validatorUrl, publicKey);
        log.info("🔑 Public key registered for {} (direct)", validatorUrl);
    }
    
    /**
     * Verify a leadership claim signature.
     * 
     * @param epoch        The epoch being claimed
     * @param validatorUrl The validator claiming leadership
     * @param timestamp    When the claim was created
     * @param signatureHex Hex-encoded signature to verify
     * @return true if signature is valid, false otherwise
     */
    public boolean verifyClaim(int epoch, String validatorUrl, long timestamp, String signatureHex) {
        // Check if we have the validator's public key
        PublicKey publicKey = validatorPublicKeys.get(validatorUrl);
        if (publicKey == null) {
            log.error("❌ VERIFICATION FAILED: No public key for {}", validatorUrl);
            log.error("   This validator is UNKNOWN or hasn't registered their public key");
            return false;
        }
        
        // Empty or null signature
        if (signatureHex == null || signatureHex.length() <= 2) {
            log.error("❌ VERIFICATION FAILED: Empty signature from {}", validatorUrl);
            return false;
        }
        
        try {
            // Reconstruct canonical message (must match signer's message)
            String message = epoch + "|" + validatorUrl + "|" + timestamp;
            
            // Decode signature
            byte[] signatureBytes = hexToBytes(signatureHex);
            
            // Verify with SHA256withECDSA
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(publicKey);
            verifier.update(message.getBytes("UTF-8"));
            
            boolean valid = verifier.verify(signatureBytes);
            
            if (valid) {
                log.debug("✅ Signature verified for {} epoch {}", validatorUrl, epoch);
            } else {
                log.error("❌ BYZANTINE ATTACK DETECTED!");
                log.error("   Validator: {}", validatorUrl);
                log.error("   Epoch: {}", epoch);
                log.error("   Signature: INVALID");
                log.error("   This is likely a forgery attempt or message tampering");
            }
            
            return valid;
            
        } catch (Exception e) {
            log.error("❌ Signature verification error for {}", validatorUrl, e);
            return false;
        }
    }
    
    /**
     * Verify an acknowledgment signature.
     */
    public boolean verifyAck(int epoch, String claimantUrl, String ackValidatorUrl, 
                             long timestamp, String signatureHex) {
        PublicKey publicKey = validatorPublicKeys.get(ackValidatorUrl);
        if (publicKey == null) {
            log.error("❌ VERIFICATION FAILED: No public key for {}", ackValidatorUrl);
            return false;
        }
        
        if (signatureHex == null || signatureHex.length() <= 2) {
            return false;
        }
        
        try {
            String message = epoch + "|ACK|" + claimantUrl + "|" + ackValidatorUrl + "|" + timestamp;
            byte[] signatureBytes = hexToBytes(signatureHex);
            
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(publicKey);
            verifier.update(message.getBytes("UTF-8"));
            
            boolean valid = verifier.verify(signatureBytes);
            
            if (!valid) {
                log.error("❌ BYZANTINE ACK DETECTED from {}", ackValidatorUrl);
            }
            
            return valid;
            
        } catch (Exception e) {
            log.error("❌ ACK verification error", e);
            return false;
        }
    }
    
    /**
     * Check if we have a public key for a validator.
     */
    public boolean hasPublicKey(String validatorUrl) {
        return validatorPublicKeys.containsKey(validatorUrl);
    }
    
    /**
     * Get number of registered validators.
     */
    public int getRegisteredValidatorCount() {
        return validatorPublicKeys.size();
    }
    
    /**
     * Convert hex string to bytes.
     * Handles both "0x..." and plain hex strings.
     */
    private byte[] hexToBytes(String hex) {
        String cleanHex = hex.startsWith("0x") || hex.startsWith("0X") 
            ? hex.substring(2) 
            : hex;
        
        int len = cleanHex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(cleanHex.charAt(i), 16) << 4)
                                 + Character.digit(cleanHex.charAt(i+1), 16));
        }
        return data;
    }
}

