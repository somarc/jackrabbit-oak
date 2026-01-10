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

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.*;

/**
 * Tests for {@link Web3BiometricCredentials}.
 */
public class Web3BiometricCredentialsTest {
    
    // Test data
    private static final String VALID_CREDENTIAL_ID = "AQIDBAUGBwgJCgsMDQ4PEA";
    private static final String VALID_WALLET = "0x1234567890abcdef1234567890abcdef12345678";
    private static final String ANOTHER_WALLET = "0xabcdef1234567890abcdef1234567890abcdef12";
    
    // Sample P-256 signature (DER-encoded, 70-72 bytes typical)
    private static final byte[] SAMPLE_SIGNATURE = new byte[] {
        0x30, 0x44, 0x02, 0x20, // SEQUENCE, length, INTEGER, length
        0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
        0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10,
        0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18,
        0x19, 0x1a, 0x1b, 0x1c, 0x1d, 0x1e, 0x1f, 0x20,
        0x02, 0x20, // INTEGER, length
        0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27, 0x28,
        0x29, 0x2a, 0x2b, 0x2c, 0x2d, 0x2e, 0x2f, 0x30,
        0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38,
        0x39, 0x3a, 0x3b, 0x3c, 0x3d, 0x3e, 0x3f, 0x40
    };
    
    // Sample P-256 public key (65 bytes: 0x04 + 32 bytes x + 32 bytes y)
    private static final byte[] SAMPLE_PUBLIC_KEY = createSamplePublicKey();
    
    // Sample challenge (32 bytes)
    private static final byte[] SAMPLE_CHALLENGE = new byte[] {
        0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
        0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10,
        0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18,
        0x19, 0x1a, 0x1b, 0x1c, 0x1d, 0x1e, 0x1f, 0x20
    };
    
    private static byte[] createSamplePublicKey() {
        byte[] key = new byte[65];
        key[0] = 0x04; // Uncompressed point marker
        // Fill with sample x and y coordinates
        for (int i = 1; i < 65; i++) {
            key[i] = (byte) i;
        }
        return key;
    }
    
    // ========================================================================
    // Constructor Tests
    // ========================================================================
    
    @Test
    public void testConstructorWithValidParameters() {
        Web3BiometricCredentials creds = new Web3BiometricCredentials(
            VALID_CREDENTIAL_ID,
            SAMPLE_SIGNATURE,
            SAMPLE_PUBLIC_KEY,
            SAMPLE_CHALLENGE,
            VALID_WALLET
        );
        
        assertNotNull(creds);
        assertEquals(VALID_CREDENTIAL_ID, creds.getCredentialId());
        assertEquals(VALID_WALLET, creds.getWalletAddress());
        assertEquals(VALID_WALLET, creds.getUserId());
    }
    
    @Test(expected = NullPointerException.class)
    public void testConstructorWithNullCredentialId() {
        new Web3BiometricCredentials(
            null,
            SAMPLE_SIGNATURE,
            SAMPLE_PUBLIC_KEY,
            SAMPLE_CHALLENGE,
            VALID_WALLET
        );
    }
    
    @Test(expected = NullPointerException.class)
    public void testConstructorWithNullSignature() {
        new Web3BiometricCredentials(
            VALID_CREDENTIAL_ID,
            null,
            SAMPLE_PUBLIC_KEY,
            SAMPLE_CHALLENGE,
            VALID_WALLET
        );
    }
    
    @Test(expected = NullPointerException.class)
    public void testConstructorWithNullPublicKey() {
        new Web3BiometricCredentials(
            VALID_CREDENTIAL_ID,
            SAMPLE_SIGNATURE,
            null,
            SAMPLE_CHALLENGE,
            VALID_WALLET
        );
    }
    
    @Test(expected = NullPointerException.class)
    public void testConstructorWithNullChallenge() {
        new Web3BiometricCredentials(
            VALID_CREDENTIAL_ID,
            SAMPLE_SIGNATURE,
            SAMPLE_PUBLIC_KEY,
            null,
            VALID_WALLET
        );
    }
    
    @Test(expected = NullPointerException.class)
    public void testConstructorWithNullWalletAddress() {
        new Web3BiometricCredentials(
            VALID_CREDENTIAL_ID,
            SAMPLE_SIGNATURE,
            SAMPLE_PUBLIC_KEY,
            SAMPLE_CHALLENGE,
            null
        );
    }
    
    @Test(expected = IllegalArgumentException.class)
    public void testConstructorWithInvalidWalletAddress() {
        new Web3BiometricCredentials(
            VALID_CREDENTIAL_ID,
            SAMPLE_SIGNATURE,
            SAMPLE_PUBLIC_KEY,
            SAMPLE_CHALLENGE,
            "invalid-wallet"
        );
    }
    
    @Test(expected = IllegalArgumentException.class)
    public void testConstructorWithWalletMissingPrefix() {
        new Web3BiometricCredentials(
            VALID_CREDENTIAL_ID,
            SAMPLE_SIGNATURE,
            SAMPLE_PUBLIC_KEY,
            SAMPLE_CHALLENGE,
            "1234567890abcdef1234567890abcdef12345678"
        );
    }
    
    // ========================================================================
    // Getter Tests - Defensive Copying
    // ========================================================================
    
    @Test
    public void testGetSignatureReturnsDefensiveCopy() {
        Web3BiometricCredentials creds = createValidCredentials();
        
        byte[] sig1 = creds.getSignature();
        byte[] sig2 = creds.getSignature();
        
        // Should be equal content
        assertArrayEquals(sig1, sig2);
        
        // But different instances (defensive copy)
        assertNotSame(sig1, sig2);
        
        // Modifying returned array should not affect internal state
        sig1[0] = (byte) 0xFF;
        assertFalse(Arrays.equals(sig1, creds.getSignature()));
    }
    
    @Test
    public void testGetPublicKeyReturnsDefensiveCopy() {
        Web3BiometricCredentials creds = createValidCredentials();
        
        byte[] key1 = creds.getPublicKey();
        byte[] key2 = creds.getPublicKey();
        
        assertArrayEquals(key1, key2);
        assertNotSame(key1, key2);
        
        key1[0] = (byte) 0xFF;
        assertFalse(Arrays.equals(key1, creds.getPublicKey()));
    }
    
    @Test
    public void testGetChallengeReturnsDefensiveCopy() {
        Web3BiometricCredentials creds = createValidCredentials();
        
        byte[] ch1 = creds.getChallenge();
        byte[] ch2 = creds.getChallenge();
        
        assertArrayEquals(ch1, ch2);
        assertNotSame(ch1, ch2);
        
        ch1[0] = (byte) 0xFF;
        assertFalse(Arrays.equals(ch1, creds.getChallenge()));
    }
    
    @Test
    public void testConstructorMakesDefensiveCopy() {
        byte[] signature = SAMPLE_SIGNATURE.clone();
        byte[] publicKey = SAMPLE_PUBLIC_KEY.clone();
        byte[] challenge = SAMPLE_CHALLENGE.clone();
        
        Web3BiometricCredentials creds = new Web3BiometricCredentials(
            VALID_CREDENTIAL_ID,
            signature,
            publicKey,
            challenge,
            VALID_WALLET
        );
        
        // Modify original arrays
        signature[0] = (byte) 0xFF;
        publicKey[0] = (byte) 0xFF;
        challenge[0] = (byte) 0xFF;
        
        // Internal state should be unchanged
        assertNotEquals((byte) 0xFF, creds.getSignature()[0]);
        assertNotEquals((byte) 0xFF, creds.getPublicKey()[0]);
        assertNotEquals((byte) 0xFF, creds.getChallenge()[0]);
    }
    
    // ========================================================================
    // getUserId() Tests
    // ========================================================================
    
    @Test
    public void testGetUserIdReturnsWalletAddress() {
        Web3BiometricCredentials creds = createValidCredentials();
        assertEquals(VALID_WALLET, creds.getUserId());
    }
    
    // ========================================================================
    // equals() Tests
    // ========================================================================
    
    @Test
    public void testEqualsWithSameInstance() {
        Web3BiometricCredentials creds = createValidCredentials();
        assertTrue(creds.equals(creds));
    }
    
    @Test
    public void testEqualsWithEqualCredentials() {
        Web3BiometricCredentials creds1 = createValidCredentials();
        Web3BiometricCredentials creds2 = createValidCredentials();
        
        assertTrue(creds1.equals(creds2));
        assertTrue(creds2.equals(creds1));
    }
    
    @Test
    public void testEqualsWithDifferentCredentialId() {
        Web3BiometricCredentials creds1 = createValidCredentials();
        Web3BiometricCredentials creds2 = new Web3BiometricCredentials(
            "different-credential-id",
            SAMPLE_SIGNATURE,
            SAMPLE_PUBLIC_KEY,
            SAMPLE_CHALLENGE,
            VALID_WALLET
        );
        
        assertFalse(creds1.equals(creds2));
    }
    
    @Test
    public void testEqualsWithDifferentWallet() {
        Web3BiometricCredentials creds1 = createValidCredentials();
        Web3BiometricCredentials creds2 = new Web3BiometricCredentials(
            VALID_CREDENTIAL_ID,
            SAMPLE_SIGNATURE,
            SAMPLE_PUBLIC_KEY,
            SAMPLE_CHALLENGE,
            ANOTHER_WALLET
        );
        
        assertFalse(creds1.equals(creds2));
    }
    
    @Test
    public void testEqualsWalletIsCaseInsensitive() {
        // Note: 0x prefix must stay lowercase per Ethereum convention
        // Only the hex digits can vary in case
        Web3BiometricCredentials creds1 = new Web3BiometricCredentials(
            VALID_CREDENTIAL_ID,
            SAMPLE_SIGNATURE,
            SAMPLE_PUBLIC_KEY,
            SAMPLE_CHALLENGE,
            "0x1234567890abcdef1234567890abcdef12345678"
        );
        Web3BiometricCredentials creds2 = new Web3BiometricCredentials(
            VALID_CREDENTIAL_ID,
            SAMPLE_SIGNATURE,
            SAMPLE_PUBLIC_KEY,
            SAMPLE_CHALLENGE,
            "0x1234567890ABCDEF1234567890ABCDEF12345678"
        );
        
        assertTrue(creds1.equals(creds2));
    }
    
    @Test
    public void testEqualsWithDifferentSignature() {
        byte[] differentSig = SAMPLE_SIGNATURE.clone();
        differentSig[0] = (byte) 0xFF;
        
        Web3BiometricCredentials creds1 = createValidCredentials();
        Web3BiometricCredentials creds2 = new Web3BiometricCredentials(
            VALID_CREDENTIAL_ID,
            differentSig,
            SAMPLE_PUBLIC_KEY,
            SAMPLE_CHALLENGE,
            VALID_WALLET
        );
        
        assertFalse(creds1.equals(creds2));
    }
    
    @Test
    public void testEqualsWithNull() {
        Web3BiometricCredentials creds = createValidCredentials();
        assertFalse(creds.equals(null));
    }
    
    @Test
    public void testEqualsWithDifferentType() {
        Web3BiometricCredentials creds = createValidCredentials();
        assertFalse(creds.equals("not credentials"));
    }
    
    // ========================================================================
    // hashCode() Tests
    // ========================================================================
    
    @Test
    public void testHashCodeConsistentWithEquals() {
        Web3BiometricCredentials creds1 = createValidCredentials();
        Web3BiometricCredentials creds2 = createValidCredentials();
        
        assertEquals(creds1.hashCode(), creds2.hashCode());
    }
    
    @Test
    public void testHashCodeCaseInsensitiveForWallet() {
        // Note: 0x prefix must stay lowercase per Ethereum convention
        // Only the hex digits can vary in case
        Web3BiometricCredentials creds1 = new Web3BiometricCredentials(
            VALID_CREDENTIAL_ID,
            SAMPLE_SIGNATURE,
            SAMPLE_PUBLIC_KEY,
            SAMPLE_CHALLENGE,
            "0x1234567890abcdef1234567890abcdef12345678"
        );
        Web3BiometricCredentials creds2 = new Web3BiometricCredentials(
            VALID_CREDENTIAL_ID,
            SAMPLE_SIGNATURE,
            SAMPLE_PUBLIC_KEY,
            SAMPLE_CHALLENGE,
            "0x1234567890ABCDEF1234567890ABCDEF12345678"
        );
        
        assertEquals(creds1.hashCode(), creds2.hashCode());
    }
    
    // ========================================================================
    // toString() Tests
    // ========================================================================
    
    @Test
    public void testToStringContainsClassName() {
        Web3BiometricCredentials creds = createValidCredentials();
        assertTrue(creds.toString().contains("Web3BiometricCredentials"));
    }
    
    @Test
    public void testToStringContainsWallet() {
        Web3BiometricCredentials creds = createValidCredentials();
        assertTrue(creds.toString().contains(VALID_WALLET));
    }
    
    @Test
    public void testToStringContainsCredentialId() {
        Web3BiometricCredentials creds = createValidCredentials();
        assertTrue(creds.toString().contains(VALID_CREDENTIAL_ID));
    }
    
    @Test
    public void testToStringDoesNotContainSignatureBytes() {
        Web3BiometricCredentials creds = createValidCredentials();
        String str = creds.toString();
        
        // Should contain length, not actual bytes (security)
        assertTrue(str.contains("signatureLen="));
        // Should not contain raw hex of signature
        assertFalse(str.contains("0x30"));
    }
    
    // ========================================================================
    // Edge Cases
    // ========================================================================
    
    @Test
    public void testEmptyCredentialId() {
        // Empty credential ID is technically valid (though unusual)
        Web3BiometricCredentials creds = new Web3BiometricCredentials(
            "",
            SAMPLE_SIGNATURE,
            SAMPLE_PUBLIC_KEY,
            SAMPLE_CHALLENGE,
            VALID_WALLET
        );
        assertEquals("", creds.getCredentialId());
    }
    
    @Test
    public void testEmptyArrays() {
        // Empty arrays are technically valid (though would fail verification)
        Web3BiometricCredentials creds = new Web3BiometricCredentials(
            VALID_CREDENTIAL_ID,
            new byte[0],
            new byte[0],
            new byte[0],
            VALID_WALLET
        );
        
        assertEquals(0, creds.getSignature().length);
        assertEquals(0, creds.getPublicKey().length);
        assertEquals(0, creds.getChallenge().length);
    }
    
    // ========================================================================
    // Helper Methods
    // ========================================================================
    
    private Web3BiometricCredentials createValidCredentials() {
        return new Web3BiometricCredentials(
            VALID_CREDENTIAL_ID,
            SAMPLE_SIGNATURE,
            SAMPLE_PUBLIC_KEY,
            SAMPLE_CHALLENGE,
            VALID_WALLET
        );
    }
}
