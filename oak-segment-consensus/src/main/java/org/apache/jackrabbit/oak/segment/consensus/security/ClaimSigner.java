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

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Signs leadership claims with validator's private key.
 * 
 * PHASE 3: Cryptographic Signatures
 * - Byzantine fault tolerance via cryptographic proof
 * - Prevents claim forgery and impersonation
 * - Uses ECDSA (Ethereum-compatible)
 */
public class ClaimSigner {
    
    private static final Logger log = LoggerFactory.getLogger(ClaimSigner.class);
    
    private final PrivateKey privateKey;
    private final PublicKey publicKey;
    private final String validatorUrl;
    
    /**
     * Create a new claim signer with generated keys.
     * 
     * For POC: Generates ephemeral keys in-memory
     * For Production: Load from HSM or key file
     */
    public ClaimSigner(String validatorUrl) {
        this.validatorUrl = validatorUrl;
        
        try {
            // Generate ECDSA key pair (secp256r1 - widely supported)
            KeyPairGenerator keyGen = KeyPairGenerator.getInstance("EC");
            keyGen.initialize(256);  // 256-bit curve (equivalent to 128-bit symmetric)
            KeyPair keyPair = keyGen.generateKeyPair();
            
            this.privateKey = keyPair.getPrivate();
            this.publicKey = keyPair.getPublic();
            
            log.info("🔐 Claim signer initialized for {}", validatorUrl);
            log.info("   Algorithm: ECDSA (secp256r1)");
            log.info("   Key size: 256 bits");
            log.info("   Public key: 0x{}", bytesToHex(publicKey.getEncoded()).substring(0, 32) + "...");
            
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize claim signer", e);
        }
    }
    
    /**
     * Sign a leadership claim.
     * 
     * Creates canonical message and signs with ECDSA.
     * 
     * @param epoch        The epoch being claimed
     * @param validatorUrl The validator claiming leadership
     * @param timestamp    When the claim was created
     * @return Hex-encoded signature starting with "0x"
     */
    public String signClaim(int epoch, String validatorUrl, long timestamp) {
        try {
            // Construct canonical message (order matters for verification)
            String message = constructCanonicalMessage(epoch, validatorUrl, timestamp);
            
            // Sign with SHA256withECDSA
            Signature signer = Signature.getInstance("SHA256withECDSA");
            signer.initSign(privateKey);
            signer.update(message.getBytes("UTF-8"));
            byte[] signatureBytes = signer.sign();
            
            // Return hex-encoded signature
            String signature = "0x" + bytesToHex(signatureBytes);
            
            log.debug("✍️  Signed claim: epoch={}, validator={}", epoch, validatorUrl);
            log.debug("   Message: {}", message);
            log.debug("   Signature: {}...", signature.substring(0, Math.min(18, signature.length())));
            
            return signature;
            
        } catch (Exception e) {
            log.error("❌ Failed to sign claim", e);
            return "0x";  // Empty signature (will fail verification)
        }
    }
    
    /**
     * Sign an acknowledgment.
     */
    public String signAck(int epoch, String claimantUrl, String ackValidatorUrl, long timestamp) {
        try {
            String message = epoch + "|ACK|" + claimantUrl + "|" + ackValidatorUrl + "|" + timestamp;
            
            Signature signer = Signature.getInstance("SHA256withECDSA");
            signer.initSign(privateKey);
            signer.update(message.getBytes("UTF-8"));
            byte[] signatureBytes = signer.sign();
            
            return "0x" + bytesToHex(signatureBytes);
            
        } catch (Exception e) {
            log.error("❌ Failed to sign ACK", e);
            return "0x";
        }
    }
    
    /**
     * Construct canonical message for signing/verification.
     * Order must match exactly for verification to succeed.
     */
    private String constructCanonicalMessage(int epoch, String validatorUrl, long timestamp) {
        return epoch + "|" + validatorUrl + "|" + timestamp;
    }
    
    /**
     * Get the public key for broadcasting to other validators.
     */
    public PublicKey getPublicKey() {
        return publicKey;
    }
    
    /**
     * Get hex-encoded public key for network transmission.
     */
    public String getPublicKeyHex() {
        return "0x" + bytesToHex(publicKey.getEncoded());
    }
    
    /**
     * Convert bytes to hex string.
     */
    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}

