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
 * PRODUCTION: Delegates to EthereumWallet for real persistent keys
 * - Byzantine fault tolerance via cryptographic proof
 * - Prevents claim forgery and impersonation
 * - Uses ECDSA (Ethereum-compatible secp256k1)
 */
public class ClaimSigner {
    
    private static final Logger log = LoggerFactory.getLogger(ClaimSigner.class);
    
    private final EthereumWallet wallet;
    private final String validatorUrl;
    
    /**
     * Create a new claim signer using an Ethereum wallet.
     * 
     * PRODUCTION: Uses persistent wallet keys (no more ephemeral keys)
     * 
     * @param validatorUrl The validator's URL (for logging)
     * @param wallet       The Ethereum wallet containing persistent keys
     */
    public ClaimSigner(String validatorUrl, EthereumWallet wallet) {
        this.validatorUrl = validatorUrl;
        this.wallet = wallet;
        
        log.info("🔐 Claim signer initialized for {}", validatorUrl);
        log.info("   Wallet address: {}", wallet.getWalletAddress());
        log.info("   Public key: {}...", wallet.getPublicKeyHex().substring(0, Math.min(32, wallet.getPublicKeyHex().length())));
    }
    
    /**
     * Sign a leadership claim.
     * 
     * PRODUCTION: Delegates to EthereumWallet for signing with persistent keys.
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
            
            // Delegate to wallet for signing
            String signature = wallet.sign(message);
            
            log.debug("✍️  Signed claim: epoch={}, validator={}", epoch, validatorUrl);
            log.debug("   Wallet: {}", wallet.getWalletAddress());
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
     * 
     * PRODUCTION: Delegates to EthereumWallet.
     */
    public String signAck(int epoch, String claimantUrl, String ackValidatorUrl, long timestamp) {
        try {
            String message = epoch + "|ACK|" + claimantUrl + "|" + ackValidatorUrl + "|" + timestamp;
            
            // Delegate to wallet
            return wallet.sign(message);
            
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
     * 
     * PRODUCTION: Delegates to EthereumWallet.
     */
    public PublicKey getPublicKey() {
        return wallet.getPublicKey();
    }
    
    /**
     * Get hex-encoded public key for network transmission.
     * 
     * PRODUCTION: Delegates to EthereumWallet.
     */
    public String getPublicKeyHex() {
        return wallet.getPublicKeyHex();
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

