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
package org.apache.jackrabbit.oak.spi.security.authentication.web3;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.*;
import java.security.interfaces.ECPublicKey;
import java.security.spec.*;
import java.math.BigInteger;

/**
 * Local P-256 (secp256r1 / NIST P-256) signature verifier using JVM's EC crypto.
 * 
 * <p>This class verifies ECDSA signatures over the P-256 elliptic curve, commonly used by:
 * <ul>
 *   <li>WebAuthn/FIDO2 (device biometrics: Face ID, Touch ID, Windows Hello)</li>
 *   <li>Apple Secure Enclave (iOS/macOS hardware-backed keys)</li>
 *   <li>Android Keystore (hardware-backed keys)</li>
 *   <li>EIP-7951 (Ethereum P-256 precompile for on-chain verification)</li>
 * </ul>
 * 
 * <h2>Why P-256?</h2>
 * <p>Unlike Ethereum's native secp256k1 curve, P-256 is supported natively by device secure
 * enclaves (Secure Enclave on Apple, Titan on Google, TPM on Windows). This enables true
 * hardware-backed biometric authentication without exposing private keys to software.</p>
 * 
 * <h2>Signature Format</h2>
 * <p>Expects DER-encoded ECDSA signatures (ASN.1 SEQUENCE of two INTEGERs: r and s).
 * This is the standard format output by WebAuthn's {@code navigator.credentials.get()}.
 * 
 * <pre>
 * Example DER signature (hex):
 * 304502207a3b8c9d...  (48 bytes = 0x30 + length + r + s)
 * </pre>
 * 
 * <h2>Public Key Format</h2>
 * <p>Accepts uncompressed EC public keys (65 bytes: 0x04 prefix + 32-byte x + 32-byte y):
 * <pre>
 * 04[32 bytes x coordinate][32 bytes y coordinate]
 * </pre>
 * 
 * <h2>Usage Example</h2>
 * <pre>
 * LocalP256Verifier verifier = new LocalP256Verifier();
 * 
 * byte[] message = "Login to Oak repository".getBytes();
 * byte[] signature = ... // From WebAuthn assertion.response.signature
 * byte[] publicKey = ... // From WebAuthn assertion.response.publicKey
 * 
 * boolean valid = verifier.verify(message, signature, publicKey);
 * if (valid) {
 *     // Authentication successful
 * }
 * </pre>
 * 
 * @see <a href="https://nvlpubs.nist.gov/nistpubs/FIPS/NIST.FIPS.186-4.pdf">FIPS 186-4: P-256 specification</a>
 * @see <a href="https://w3c.github.io/webauthn/#sctn-signature-attestation-types">WebAuthn signature formats</a>
 * @see <a href="https://eips.ethereum.org/EIPS/eip-7951">EIP-7951: secp256r1 Precompile</a>
 */
public final class LocalP256Verifier {
    
    private static final Logger log = LoggerFactory.getLogger(LocalP256Verifier.class);
    
    /**
     * P-256 curve name (also known as "prime256v1" or "secp256r1").
     */
    private static final String CURVE_NAME = "secp256r1";
    
    /**
     * Signature algorithm name for SHA-256 with ECDSA.
     */
    private static final String SIGNATURE_ALGORITHM = "SHA256withECDSA";
    
    /**
     * Creates a new LocalP256Verifier.
     */
    public LocalP256Verifier() {
        // Verify crypto provider supports P-256
        try {
            KeyPairGenerator.getInstance("EC");
            Signature.getInstance(SIGNATURE_ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
            log.error("JVM does not support EC crypto (P-256). Cannot verify biometric signatures.", e);
            throw new IllegalStateException("EC crypto not available", e);
        }
    }
    
    /**
     * Verifies a P-256 ECDSA signature over a message.
     * 
     * @param message message bytes that were signed
     * @param signatureDER DER-encoded ECDSA signature (r, s)
     * @param publicKeyRaw uncompressed P-256 public key (65 bytes: 0x04 + x + y)
     * @return true if signature is valid, false otherwise
     * @throws IllegalArgumentException if public key format is invalid
     */
    public boolean verify(
            @NotNull byte[] message,
            @NotNull byte[] signatureDER,
            @NotNull byte[] publicKeyRaw) {
        
        try {
            // Step 1: Parse raw public key bytes into ECPublicKey
            ECPublicKey publicKey = parsePublicKey(publicKeyRaw);
            
            // Step 2: Initialize signature verifier
            Signature sig = Signature.getInstance(SIGNATURE_ALGORITHM);
            sig.initVerify(publicKey);
            
            // Step 3: Feed message to verifier
            sig.update(message);
            
            // Step 4: Verify signature
            boolean valid = sig.verify(signatureDER);
            
            if (log.isDebugEnabled()) {
                log.debug("P-256 signature verification: {} (messageLen={}, sigLen={}, pubKeyLen={})",
                         valid ? "VALID" : "INVALID",
                         message.length,
                         signatureDER.length,
                         publicKeyRaw.length);
            }
            
            return valid;
            
        } catch (InvalidKeyException e) {
            log.warn("Invalid P-256 public key: {}", e.getMessage());
            return false;
        } catch (SignatureException e) {
            log.warn("Signature verification failed: {}", e.getMessage());
            return false;
        } catch (NoSuchAlgorithmException e) {
            log.error("EC crypto algorithm not available", e);
            return false;
        }
    }
    
    /**
     * Parses P-256 public key bytes into an ECPublicKey.
     * 
     * <p>Supports two formats:
     * <ul>
     *   <li><b>Raw uncompressed (65 bytes)</b>: 0x04 + 32-byte x + 32-byte y</li>
     *   <li><b>SPKI/SubjectPublicKeyInfo (91 bytes)</b>: ASN.1 wrapper from WebAuthn getPublicKey()</li>
     *   <li><b>COSE_Key fragment (71+ bytes)</b>: WebAuthn COSE format (extracts raw key)</li>
     * </ul>
     * 
     * @param publicKeyBytes public key bytes in any supported format
     * @return ECPublicKey for use in signature verification
     * @throws IllegalArgumentException if format is invalid
     */
    @NotNull
    private ECPublicKey parsePublicKey(@NotNull byte[] publicKeyBytes) {
        log.info("🔑 Parsing public key of {} bytes", publicKeyBytes.length);
        
        // Debug: log first 20 bytes as hex for debugging
        StringBuilder hexDump = new StringBuilder();
        for (int i = 0; i < Math.min(publicKeyBytes.length, 20); i++) {
            hexDump.append(String.format("%02x ", publicKeyBytes[i] & 0xFF));
        }
        log.info("🔑 First 20 bytes (hex): {}", hexDump.toString());
        
        try {
            // Try SPKI format first (from WebAuthn getPublicKey())
            if (publicKeyBytes.length > 65 && publicKeyBytes[0] == 0x30) {
                // This looks like ASN.1 SEQUENCE (SPKI format)
                log.debug("Attempting to parse as SPKI format");
                try {
                    KeyFactory keyFactory = KeyFactory.getInstance("EC");
                    X509EncodedKeySpec keySpec = new X509EncodedKeySpec(publicKeyBytes);
                    return (ECPublicKey) keyFactory.generatePublic(keySpec);
                } catch (InvalidKeySpecException e) {
                    log.debug("SPKI parsing failed, trying to extract raw key: {}", e.getMessage());
                }
            }
            
            // Try to find raw uncompressed key (0x04 prefix) within the bytes
            byte[] rawKey = extractRawPublicKey(publicKeyBytes);
            
            if (rawKey.length != 65) {
                // Log full hex dump for debugging
                StringBuilder fullHex = new StringBuilder();
                for (byte b : publicKeyBytes) {
                    fullHex.append(String.format("%02x", b & 0xFF));
                }
                log.error("🔑 Full public key hex ({}b): {}", publicKeyBytes.length, fullHex.toString());
                
                throw new IllegalArgumentException(
                    "Invalid P-256 public key length: " + rawKey.length + 
                    " (expected 65 bytes: 0x04 + 32-byte x + 32-byte y)"
                );
            }
            
            if (rawKey[0] != 0x04) {
                throw new IllegalArgumentException(
                    "Invalid P-256 public key format: first byte must be 0x04 (uncompressed)"
                );
            }
            
            // Extract x and y coordinates (32 bytes each)
            byte[] xBytes = new byte[32];
            byte[] yBytes = new byte[32];
            System.arraycopy(rawKey, 1, xBytes, 0, 32);
            System.arraycopy(rawKey, 33, yBytes, 0, 32);
            
            BigInteger x = new BigInteger(1, xBytes);
            BigInteger y = new BigInteger(1, yBytes);
            
            // Create EC point
            ECPoint point = new ECPoint(x, y);
            
            // Get P-256 curve parameters
            AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
            params.init(new ECGenParameterSpec(CURVE_NAME));
            ECParameterSpec ecParams = params.getParameterSpec(ECParameterSpec.class);
            
            // Create public key spec
            ECPublicKeySpec keySpec = new ECPublicKeySpec(point, ecParams);
            
            // Generate public key
            KeyFactory keyFactory = KeyFactory.getInstance("EC");
            return (ECPublicKey) keyFactory.generatePublic(keySpec);
            
        } catch (NoSuchAlgorithmException | InvalidParameterSpecException | InvalidKeySpecException e) {
            throw new IllegalArgumentException("Failed to parse P-256 public key", e);
        }
    }
    
    /**
     * Extracts raw uncompressed public key from various formats.
     * 
     * <p>Handles:
     * <ul>
     *   <li>Already raw (65 bytes starting with 0x04)</li>
     *   <li>COSE_Key format (71+ bytes, CBOR encoded with x/y coordinates)</li>
     *   <li>Other wrapped formats (searches for 0x04 marker)</li>
     * </ul>
     */
    @NotNull
    private byte[] extractRawPublicKey(@NotNull byte[] publicKeyBytes) {
        // Already in raw format
        if (publicKeyBytes.length == 65 && publicKeyBytes[0] == 0x04) {
            return publicKeyBytes;
        }
        
        // Try COSE_Key format (common from WebAuthn)
        // COSE_Key for P-256 is typically 71-77 bytes with CBOR structure:
        // { 1: 2 (EC2), 3: -7 (ES256), -1: 1 (P-256), -2: x (32 bytes), -3: y (32 bytes) }
        if (publicKeyBytes.length >= 65 && publicKeyBytes.length <= 100) {
            log.info("🔑 Attempting COSE_Key extraction for {}-byte key", publicKeyBytes.length);
            byte[] extracted = extractFromCOSEKey(publicKeyBytes);
            if (extracted != null) {
                log.info("✅ Extracted raw public key from COSE_Key format");
                return extracted;
            } else {
                log.info("⚠️ COSE_Key extraction failed, trying other methods");
            }
        }
        
        // Search for 0x04 marker followed by 64 bytes (x and y coordinates)
        // This handles other wrapped formats
        for (int i = 0; i < publicKeyBytes.length - 64; i++) {
            if (publicKeyBytes[i] == 0x04) {
                // Found potential uncompressed key marker
                // Verify we have enough bytes remaining
                if (i + 65 <= publicKeyBytes.length) {
                    byte[] rawKey = new byte[65];
                    System.arraycopy(publicKeyBytes, i, rawKey, 0, 65);
                    log.debug("Extracted raw public key from offset {} using 0x04 marker", i);
                    return rawKey;
                }
            }
        }
        
        // Couldn't find raw key, return original (will fail validation with helpful error)
        log.warn("Could not extract raw P-256 key from {} bytes, returning as-is", publicKeyBytes.length);
        return publicKeyBytes;
    }
    
    /**
     * Extracts x and y coordinates from a COSE_Key formatted public key.
     * 
     * <p>COSE_Key structure for EC2 (P-256):
     * <pre>
     * {
     *   1: 2,       // kty: EC2
     *   3: -7,      // alg: ES256 (optional)
     *   -1: 1,      // crv: P-256
     *   -2: bytes,  // x coordinate (32 bytes)
     *   -3: bytes   // y coordinate (32 bytes)
     * }
     * </pre>
     * 
     * <p>CBOR encoding notes:
     * <ul>
     *   <li>Negative keys (-1, -2, -3) are encoded as 0x20 + abs(n) - 1</li>
     *   <li>So -1 = 0x20, -2 = 0x21, -3 = 0x22</li>
     *   <li>Byte strings are prefixed with 0x58 0x20 (for 32-byte strings)</li>
     * </ul>
     * 
     * @param coseKey COSE_Key formatted bytes
     * @return raw 65-byte uncompressed key (0x04 + x + y), or null if parsing fails
     */
    @Nullable
    private byte[] extractFromCOSEKey(@NotNull byte[] coseKey) {
        try {
            byte[] xCoord = null;
            byte[] yCoord = null;
            
            // CBOR key markers for COSE_Key:
            // -2 (x coordinate) = 0x21 in CBOR
            // -3 (y coordinate) = 0x22 in CBOR
            // 32-byte byte string prefix = 0x58 0x20
            
            for (int i = 0; i < coseKey.length - 34; i++) {
                // Look for -2 key (x coordinate): 0x21 followed by 0x58 0x20 (32-byte bstr)
                if (coseKey[i] == 0x21 && i + 35 <= coseKey.length) {
                    if (coseKey[i + 1] == 0x58 && coseKey[i + 2] == 0x20) {
                        xCoord = new byte[32];
                        System.arraycopy(coseKey, i + 3, xCoord, 0, 32);
                        log.debug("Found x coordinate at offset {}", i);
                    } else if ((coseKey[i + 1] & 0xFF) >= 0x40 && (coseKey[i + 1] & 0xFF) <= 0x57) {
                        // Short form: 0x40-0x57 means 0-23 byte string (0x58 = 24+ bytes)
                        // For 32 bytes, we shouldn't see this, but handle edge case
                        int len = (coseKey[i + 1] & 0xFF) - 0x40;
                        if (len == 32 && i + 2 + len <= coseKey.length) {
                            xCoord = new byte[32];
                            System.arraycopy(coseKey, i + 2, xCoord, 0, 32);
                            log.debug("Found x coordinate (short form) at offset {}", i);
                        }
                    }
                }
                
                // Look for -3 key (y coordinate): 0x22 followed by 0x58 0x20 (32-byte bstr)
                if (coseKey[i] == 0x22 && i + 35 <= coseKey.length) {
                    if (coseKey[i + 1] == 0x58 && coseKey[i + 2] == 0x20) {
                        yCoord = new byte[32];
                        System.arraycopy(coseKey, i + 3, yCoord, 0, 32);
                        log.debug("Found y coordinate at offset {}", i);
                    } else if ((coseKey[i + 1] & 0xFF) >= 0x40 && (coseKey[i + 1] & 0xFF) <= 0x57) {
                        int len = (coseKey[i + 1] & 0xFF) - 0x40;
                        if (len == 32 && i + 2 + len <= coseKey.length) {
                            yCoord = new byte[32];
                            System.arraycopy(coseKey, i + 2, yCoord, 0, 32);
                            log.debug("Found y coordinate (short form) at offset {}", i);
                        }
                    }
                }
            }
            
            // If we found both coordinates, construct raw uncompressed key
            if (xCoord != null && yCoord != null) {
                byte[] rawKey = new byte[65];
                rawKey[0] = 0x04; // Uncompressed point marker
                System.arraycopy(xCoord, 0, rawKey, 1, 32);
                System.arraycopy(yCoord, 0, rawKey, 33, 32);
                return rawKey;
            }
            
            log.debug("Could not find x/y coordinates in COSE_Key (xFound={}, yFound={})", 
                     xCoord != null, yCoord != null);
            return null;
            
        } catch (Exception e) {
            log.debug("COSE_Key parsing failed: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * Alternative verification method accepting separate x and y coordinates.
     * Useful when public key is provided as two separate 32-byte values.
     * 
     * @param message message bytes that were signed
     * @param signatureDER DER-encoded ECDSA signature
     * @param xCoordinate P-256 x coordinate (32 bytes)
     * @param yCoordinate P-256 y coordinate (32 bytes)
     * @return true if signature is valid, false otherwise
     */
    public boolean verify(
            @NotNull byte[] message,
            @NotNull byte[] signatureDER,
            @NotNull byte[] xCoordinate,
            @NotNull byte[] yCoordinate) {
        
        // Reconstruct uncompressed public key format
        byte[] publicKeyRaw = new byte[65];
        publicKeyRaw[0] = 0x04; // Uncompressed marker
        System.arraycopy(xCoordinate, 0, publicKeyRaw, 1, 32);
        System.arraycopy(yCoordinate, 0, publicKeyRaw, 33, 32);
        
        return verify(message, signatureDER, publicKeyRaw);
    }
}

