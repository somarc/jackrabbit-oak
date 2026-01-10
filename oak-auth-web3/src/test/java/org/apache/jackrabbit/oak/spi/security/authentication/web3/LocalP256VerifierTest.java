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

import org.junit.Before;
import org.junit.Test;

import java.security.*;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;

import static org.junit.Assert.*;

/**
 * Tests for {@link LocalP256Verifier}.
 * 
 * <p>These tests use real P-256 cryptographic operations to verify the verifier
 * correctly validates signatures. Test keys are generated fresh for each test run.</p>
 */
public class LocalP256VerifierTest {
    
    private LocalP256Verifier verifier;
    private KeyPair testKeyPair;
    
    @Before
    public void setUp() throws Exception {
        verifier = new LocalP256Verifier();
        testKeyPair = generateP256KeyPair();
    }
    
    // ========================================================================
    // Constructor Tests
    // ========================================================================
    
    @Test
    public void testConstructorSucceeds() {
        LocalP256Verifier v = new LocalP256Verifier();
        assertNotNull(v);
    }
    
    // ========================================================================
    // Valid Signature Tests
    // ========================================================================
    
    @Test
    public void testVerifyValidSignature() throws Exception {
        byte[] message = "Hello, Oak Repository!".getBytes();
        byte[] signature = sign(message, (ECPrivateKey) testKeyPair.getPrivate());
        byte[] publicKey = encodePublicKey((ECPublicKey) testKeyPair.getPublic());
        
        assertTrue("Valid signature should verify", verifier.verify(message, signature, publicKey));
    }
    
    @Test
    public void testVerifyValidSignatureWithEmptyMessage() throws Exception {
        byte[] message = new byte[0];
        byte[] signature = sign(message, (ECPrivateKey) testKeyPair.getPrivate());
        byte[] publicKey = encodePublicKey((ECPublicKey) testKeyPair.getPublic());
        
        assertTrue("Valid signature over empty message should verify", 
                   verifier.verify(message, signature, publicKey));
    }
    
    @Test
    public void testVerifyValidSignatureWithLargeMessage() throws Exception {
        // 1MB message
        byte[] message = new byte[1024 * 1024];
        new SecureRandom().nextBytes(message);
        
        byte[] signature = sign(message, (ECPrivateKey) testKeyPair.getPrivate());
        byte[] publicKey = encodePublicKey((ECPublicKey) testKeyPair.getPublic());
        
        assertTrue("Valid signature over large message should verify", 
                   verifier.verify(message, signature, publicKey));
    }
    
    @Test
    public void testVerifyMultipleSignaturesWithSameKey() throws Exception {
        byte[] publicKey = encodePublicKey((ECPublicKey) testKeyPair.getPublic());
        ECPrivateKey privateKey = (ECPrivateKey) testKeyPair.getPrivate();
        
        for (int i = 0; i < 10; i++) {
            byte[] message = ("Message " + i).getBytes();
            byte[] signature = sign(message, privateKey);
            
            assertTrue("Signature " + i + " should verify", 
                       verifier.verify(message, signature, publicKey));
        }
    }
    
    // ========================================================================
    // Invalid Signature Tests
    // ========================================================================
    
    @Test
    public void testVerifyWithTamperedMessage() throws Exception {
        byte[] message = "Original message".getBytes();
        byte[] signature = sign(message, (ECPrivateKey) testKeyPair.getPrivate());
        byte[] publicKey = encodePublicKey((ECPublicKey) testKeyPair.getPublic());
        
        // Tamper with message
        byte[] tamperedMessage = "Tampered message".getBytes();
        
        assertFalse("Signature should not verify with tampered message", 
                    verifier.verify(tamperedMessage, signature, publicKey));
    }
    
    @Test
    public void testVerifyWithTamperedSignature() throws Exception {
        byte[] message = "Test message".getBytes();
        byte[] signature = sign(message, (ECPrivateKey) testKeyPair.getPrivate());
        byte[] publicKey = encodePublicKey((ECPublicKey) testKeyPair.getPublic());
        
        // Tamper with signature (flip a bit)
        byte[] tamperedSignature = signature.clone();
        tamperedSignature[tamperedSignature.length - 1] ^= 0x01;
        
        assertFalse("Signature should not verify when tampered", 
                    verifier.verify(message, tamperedSignature, publicKey));
    }
    
    @Test
    public void testVerifyWithWrongPublicKey() throws Exception {
        byte[] message = "Test message".getBytes();
        byte[] signature = sign(message, (ECPrivateKey) testKeyPair.getPrivate());
        
        // Use a different key pair
        KeyPair differentKeyPair = generateP256KeyPair();
        byte[] wrongPublicKey = encodePublicKey((ECPublicKey) differentKeyPair.getPublic());
        
        assertFalse("Signature should not verify with wrong public key", 
                    verifier.verify(message, signature, wrongPublicKey));
    }
    
    // ========================================================================
    // Malformed Input Tests
    // ========================================================================
    
    @Test
    public void testVerifyWithMalformedSignature() throws Exception {
        byte[] message = "Test message".getBytes();
        byte[] publicKey = encodePublicKey((ECPublicKey) testKeyPair.getPublic());
        
        // Completely invalid signature bytes
        byte[] malformedSignature = new byte[] { 0x00, 0x01, 0x02, 0x03 };
        
        assertFalse("Malformed signature should return false, not throw", 
                    verifier.verify(message, malformedSignature, publicKey));
    }
    
    @Test
    public void testVerifyWithEmptySignature() throws Exception {
        byte[] message = "Test message".getBytes();
        byte[] publicKey = encodePublicKey((ECPublicKey) testKeyPair.getPublic());
        
        assertFalse("Empty signature should return false", 
                    verifier.verify(message, new byte[0], publicKey));
    }
    
    @Test(expected = IllegalArgumentException.class)
    public void testVerifyWithMalformedPublicKey() throws Exception {
        byte[] message = "Test message".getBytes();
        byte[] signature = sign(message, (ECPrivateKey) testKeyPair.getPrivate());
        
        // Invalid public key (wrong length)
        byte[] malformedPublicKey = new byte[] { 0x04, 0x01, 0x02, 0x03 };
        
        verifier.verify(message, signature, malformedPublicKey);
    }
    
    @Test(expected = IllegalArgumentException.class)
    public void testVerifyWithWrongPublicKeyPrefix() throws Exception {
        byte[] message = "Test message".getBytes();
        byte[] signature = sign(message, (ECPrivateKey) testKeyPair.getPrivate());
        
        // Wrong prefix (should be 0x04 for uncompressed)
        byte[] wrongPrefixKey = new byte[65];
        wrongPrefixKey[0] = 0x02; // Compressed point marker (not supported)
        
        verifier.verify(message, signature, wrongPrefixKey);
    }
    
    // ========================================================================
    // Alternative verify() Method Tests (x, y coordinates)
    // ========================================================================
    
    @Test
    public void testVerifyWithSeparateCoordinates() throws Exception {
        byte[] message = "Test message".getBytes();
        byte[] signature = sign(message, (ECPrivateKey) testKeyPair.getPrivate());
        
        ECPublicKey publicKey = (ECPublicKey) testKeyPair.getPublic();
        byte[] xCoord = toBytes32(publicKey.getW().getAffineX());
        byte[] yCoord = toBytes32(publicKey.getW().getAffineY());
        
        assertTrue("Verification with separate x,y coordinates should work", 
                   verifier.verify(message, signature, xCoord, yCoord));
    }
    
    @Test
    public void testVerifyWithSeparateCoordinatesInvalid() throws Exception {
        byte[] message = "Test message".getBytes();
        byte[] signature = sign(message, (ECPrivateKey) testKeyPair.getPrivate());
        
        // Use wrong key's coordinates
        KeyPair wrongKeyPair = generateP256KeyPair();
        ECPublicKey wrongPublicKey = (ECPublicKey) wrongKeyPair.getPublic();
        byte[] xCoord = toBytes32(wrongPublicKey.getW().getAffineX());
        byte[] yCoord = toBytes32(wrongPublicKey.getW().getAffineY());
        
        assertFalse("Verification with wrong coordinates should fail", 
                    verifier.verify(message, signature, xCoord, yCoord));
    }
    
    // ========================================================================
    // SPKI Format Tests (SubjectPublicKeyInfo - ASN.1 wrapped)
    // ========================================================================
    
    @Test
    public void testVerifyWithSPKIFormat() throws Exception {
        byte[] message = "Test message".getBytes();
        byte[] signature = sign(message, (ECPrivateKey) testKeyPair.getPrivate());
        
        // Get SPKI-encoded public key (X.509 format)
        byte[] spkiPublicKey = testKeyPair.getPublic().getEncoded();
        
        assertTrue("Verification with SPKI format should work", 
                   verifier.verify(message, signature, spkiPublicKey));
    }
    
    // ========================================================================
    // Determinism Tests
    // ========================================================================
    
    @Test
    public void testVerifyIsDeterministic() throws Exception {
        byte[] message = "Test message".getBytes();
        byte[] signature = sign(message, (ECPrivateKey) testKeyPair.getPrivate());
        byte[] publicKey = encodePublicKey((ECPublicKey) testKeyPair.getPublic());
        
        // Verify multiple times - should always return same result
        for (int i = 0; i < 100; i++) {
            assertTrue("Verification should be deterministic", 
                       verifier.verify(message, signature, publicKey));
        }
    }
    
    // ========================================================================
    // Thread Safety Tests
    // ========================================================================
    
    @Test
    public void testVerifyIsThreadSafe() throws Exception {
        byte[] message = "Test message".getBytes();
        byte[] signature = sign(message, (ECPrivateKey) testKeyPair.getPrivate());
        byte[] publicKey = encodePublicKey((ECPublicKey) testKeyPair.getPublic());
        
        int threadCount = 10;
        int iterationsPerThread = 100;
        java.util.concurrent.atomic.AtomicInteger failures = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(threadCount);
        
        for (int t = 0; t < threadCount; t++) {
            new Thread(() -> {
                try {
                    for (int i = 0; i < iterationsPerThread; i++) {
                        if (!verifier.verify(message, signature, publicKey)) {
                            failures.incrementAndGet();
                        }
                    }
                } finally {
                    latch.countDown();
                }
            }).start();
        }
        
        latch.await();
        assertEquals("No verification failures in concurrent access", 0, failures.get());
    }
    
    // ========================================================================
    // Helper Methods
    // ========================================================================
    
    /**
     * Generates a fresh P-256 key pair for testing.
     */
    private KeyPair generateP256KeyPair() throws Exception {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("EC");
        keyGen.initialize(new ECGenParameterSpec("secp256r1"), new SecureRandom());
        return keyGen.generateKeyPair();
    }
    
    /**
     * Signs a message with a P-256 private key, returning DER-encoded signature.
     */
    private byte[] sign(byte[] message, ECPrivateKey privateKey) throws Exception {
        Signature sig = Signature.getInstance("SHA256withECDSA");
        sig.initSign(privateKey);
        sig.update(message);
        return sig.sign();
    }
    
    /**
     * Encodes an EC public key as uncompressed point (65 bytes: 0x04 + x + y).
     */
    private byte[] encodePublicKey(ECPublicKey publicKey) {
        byte[] x = toBytes32(publicKey.getW().getAffineX());
        byte[] y = toBytes32(publicKey.getW().getAffineY());
        
        byte[] encoded = new byte[65];
        encoded[0] = 0x04; // Uncompressed point marker
        System.arraycopy(x, 0, encoded, 1, 32);
        System.arraycopy(y, 0, encoded, 33, 32);
        return encoded;
    }
    
    /**
     * Converts a BigInteger to a 32-byte array (left-padded with zeros if needed).
     */
    private byte[] toBytes32(java.math.BigInteger value) {
        byte[] bytes = value.toByteArray();
        
        if (bytes.length == 32) {
            return bytes;
        } else if (bytes.length == 33 && bytes[0] == 0) {
            // Remove leading zero (sign byte)
            byte[] trimmed = new byte[32];
            System.arraycopy(bytes, 1, trimmed, 0, 32);
            return trimmed;
        } else if (bytes.length < 32) {
            // Pad with leading zeros
            byte[] padded = new byte[32];
            System.arraycopy(bytes, 0, padded, 32 - bytes.length, bytes.length);
            return padded;
        } else {
            throw new IllegalArgumentException("Value too large for 32 bytes: " + bytes.length);
        }
    }
}
