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

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.Security;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * Ethereum signature verifier for MetaMask personal_sign signatures.
 * 
 * <p>Verifies signatures created by MetaMask's personal_sign method, which uses
 * the Ethereum standard message prefix and secp256k1 ECDSA. Signer recovery uses web3j.
 * 
 * <p>Signature format (65 bytes):
 * <ul>
 *   <li>r: 32 bytes, {@code 0 < r < n}</li>
 *   <li>s: 32 bytes, {@code 0 < s <= n/2} (EIP-2 low-s; the malleated high-s twin is rejected)</li>
 *   <li>v: 1 byte (recovery id, 27 or 28; 0 and 1 are accepted as 27 and 28)</li>
 * </ul>
 * 
 * <p>Message format (Ethereum personal_sign):
 * <pre>
 * "\x19Ethereum Signed Message:\n" + utf8ByteLength(message) + message
 * </pre>
 * 
 * @see <a href="https://eips.ethereum.org/EIPS/eip-191">EIP-191: Signed Data Standard</a>
 * @see <a href="https://eips.ethereum.org/EIPS/eip-2">EIP-2: low-s signatures</a>
 */
public class EthereumSignatureVerifier {
    
    private static final Logger log = LoggerFactory.getLogger(EthereumSignatureVerifier.class);
    
    // secp256k1 curve order
    private static final BigInteger SECP256K1_N = new BigInteger(
        "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16);
    private static final BigInteger SECP256K1_HALF_N = SECP256K1_N.shiftRight(1);
    
    // Bouncy Castle provider name
    private static final String BC_PROVIDER = "BC";
    
    // Flag to track if Bouncy Castle is available
    private static boolean bouncyCastleAvailable = false;
    private static String availabilityReason = "Bouncy Castle provider not initialized";
    
    static {
        try {
            if (Security.getProvider(BC_PROVIDER) == null) {
                Security.addProvider(new BouncyCastleProvider());
            }
            bouncyCastleAvailable = true;
            availabilityReason = null;
            log.info("✅ Bouncy Castle provider loaded for secp256k1 signature verification");
        } catch (Throwable t) {
            availabilityReason = "Bouncy Castle provider unavailable: " + t.getMessage();
            log.error("❌ {}", availabilityReason, t);
            bouncyCastleAvailable = false;
        }
    }
    
    /**
     * Verify an Ethereum personal_sign signature.
     * 
     * @param message The original message that was signed
     * @param signatureHex The signature in hex format (0x + 130 hex chars = 65 bytes)
     * @param expectedAddress The expected signer's Ethereum address (0x + 40 hex chars)
     * @return true if signature is valid and signer matches expected address
     */
    public static boolean verifySignature(String message, String signatureHex, String expectedAddress) {
        if (message == null || signatureHex == null || expectedAddress == null) {
            log.warn("❌ Signature verification failed: null parameter");
            return false;
        }

        if (!bouncyCastleAvailable) {
            log.error("❌ Full Ethereum signature verification unavailable: {}", getAvailabilityReason());
            return false;
        }
        
        try {
            // Normalize inputs
            String normalizedSig = signatureHex.toLowerCase().startsWith("0x") 
                ? signatureHex.substring(2) : signatureHex;
            String normalizedAddress = expectedAddress.toLowerCase().startsWith("0x")
                ? expectedAddress.substring(2).toLowerCase() : expectedAddress.toLowerCase();
            
            // Validate signature length (65 bytes = 130 hex chars)
            if (normalizedSig.length() != 130) {
                log.warn("❌ Invalid signature length: {} (expected 130 hex chars)", normalizedSig.length());
                return false;
            }
            if (!normalizedSig.chars().allMatch(c -> Character.digit(c, 16) >= 0)) {
                log.warn("❌ Invalid signature: not hex");
                return false;
            }
            
            // Parse signature components (r, s, v)
            byte[] signatureBytes = HexFormat.of().parseHex(normalizedSig);
            byte[] r = Arrays.copyOfRange(signatureBytes, 0, 32);
            byte[] s = Arrays.copyOfRange(signatureBytes, 32, 64);
            int v = signatureBytes[64] & 0xFF;
            
            // Normalize v (MetaMask uses 27/28, some use 0/1)
            if (v < 27) {
                v += 27;
            }
            if (v != 27 && v != 28) {
                log.warn("❌ Invalid recovery id v={}", v);
                return false;
            }

            // Only canonical signatures: 0 < r < n and 0 < s <= n/2. Recovery alone would also accept the
            // malleated twin (r, n - s, v ^ 1) of a valid signature.
            BigInteger rValue = new BigInteger(1, r);
            BigInteger sValue = new BigInteger(1, s);
            if (rValue.signum() == 0 || rValue.compareTo(SECP256K1_N) >= 0) {
                log.warn("❌ Invalid signature: r out of range");
                return false;
            }
            if (sValue.signum() == 0 || sValue.compareTo(SECP256K1_HALF_N) > 0) {
                log.warn("❌ Invalid signature: s is zero or in the upper half of the curve order (EIP-2)");
                return false;
            }

            BigInteger publicKey = Sign.signedPrefixedMessageToKey(
                message.getBytes(StandardCharsets.UTF_8), new Sign.SignatureData((byte) v, r, s));
            String recoveredAddress = Keys.getAddress(publicKey);
            
            // Compare addresses (case-insensitive)
            boolean matches = recoveredAddress.equalsIgnoreCase(normalizedAddress);
            
            if (matches) {
                log.debug("✅ Signature verified: recovered address {} matches expected {}", 
                    recoveredAddress, normalizedAddress);
            } else {
                log.warn("❌ Signature mismatch: recovered {} but expected {}", 
                    recoveredAddress, normalizedAddress);
            }
            
            return matches;
            
        } catch (Exception e) {
            log.warn("❌ Signature verification error: {}", e.toString());
            return false;
        }
    }
    
    /**
     * Hash a message using Ethereum's personal_sign format.
     * 
     * @param message The message to hash
     * @return Keccak-256 hash of the prefixed message
     */
    public static byte[] hashMessage(String message) {
        return Sign.getEthereumMessageHash(message.getBytes(StandardCharsets.UTF_8));
    }
    
    /**
     * Check if full Ethereum signature verification is available.
     * 
     * @return true if Bouncy Castle is loaded and secp256k1 verification works
     */
    public static boolean isFullVerificationAvailable() {
        return bouncyCastleAvailable;
    }

    /**
     * Describe why full verification is unavailable.
     */
    public static String getAvailabilityReason() {
        return availabilityReason != null ? availabilityReason : "Bouncy Castle provider is available";
    }
}
