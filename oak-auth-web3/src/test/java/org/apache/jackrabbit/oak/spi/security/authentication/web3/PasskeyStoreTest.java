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

import static org.junit.Assert.*;

/**
 * Unit tests for {@link PasskeyStore}.
 * 
 * <p>Note: Full integration tests require Oak repository setup.
 * These tests cover the RegisteredPasskey data class and basic logic.</p>
 */
public class PasskeyStoreTest {
    
    private static final String VALID_WALLET = "0x1234567890abcdef1234567890abcdef12345678";
    private static final String CREDENTIAL_ID = "test-credential-id-123";
    private static final byte[] SAMPLE_PUBLIC_KEY = createSamplePublicKey();
    
    private static byte[] createSamplePublicKey() {
        // P-256 uncompressed public key format: 0x04 + 32 bytes X + 32 bytes Y = 65 bytes
        byte[] key = new byte[65];
        key[0] = 0x04; // Uncompressed point indicator
        for (int i = 1; i < 65; i++) {
            key[i] = (byte) i;
        }
        return key;
    }
    
    // ========================================================================
    // RegisteredPasskey Tests
    // ========================================================================
    
    @Test
    public void testRegisteredPasskeyCreation() {
        long now = System.currentTimeMillis();
        
        PasskeyStore.RegisteredPasskey passkey = new PasskeyStore.RegisteredPasskey(
            CREDENTIAL_ID,
            SAMPLE_PUBLIC_KEY,
            VALID_WALLET,
            now,
            now,
            "iPhone 15",
            "P-256"
        );
        
        assertEquals(CREDENTIAL_ID, passkey.getCredentialId());
        assertEquals(VALID_WALLET, passkey.getWalletAddress());
        assertEquals(now, passkey.getCreatedAt());
        assertEquals(now, passkey.getLastUsedAt());
        assertEquals("iPhone 15", passkey.getDeviceName());
        assertEquals("P-256", passkey.getKeyAlgorithm());
    }
    
    @Test
    public void testRegisteredPasskeyPublicKeyDefensiveCopy() {
        long now = System.currentTimeMillis();
        byte[] originalKey = SAMPLE_PUBLIC_KEY.clone();
        
        PasskeyStore.RegisteredPasskey passkey = new PasskeyStore.RegisteredPasskey(
            CREDENTIAL_ID,
            originalKey,
            VALID_WALLET,
            now,
            now,
            null,
            "P-256"
        );
        
        // Modify original
        originalKey[0] = (byte) 0xFF;
        
        // Passkey should have original value
        assertNotEquals((byte) 0xFF, passkey.getPublicKey()[0]);
    }
    
    @Test
    public void testRegisteredPasskeyGetPublicKeyDefensiveCopy() {
        long now = System.currentTimeMillis();
        
        PasskeyStore.RegisteredPasskey passkey = new PasskeyStore.RegisteredPasskey(
            CREDENTIAL_ID,
            SAMPLE_PUBLIC_KEY,
            VALID_WALLET,
            now,
            now,
            null,
            "P-256"
        );
        
        byte[] key1 = passkey.getPublicKey();
        byte[] key2 = passkey.getPublicKey();
        
        // Should return different array instances
        assertNotSame(key1, key2);
        
        // But same content
        assertArrayEquals(key1, key2);
        
        // Modifying returned array should not affect stored key
        key1[0] = (byte) 0xFF;
        assertNotEquals((byte) 0xFF, passkey.getPublicKey()[0]);
    }
    
    @Test
    public void testRegisteredPasskeyNullDeviceName() {
        long now = System.currentTimeMillis();
        
        PasskeyStore.RegisteredPasskey passkey = new PasskeyStore.RegisteredPasskey(
            CREDENTIAL_ID,
            SAMPLE_PUBLIC_KEY,
            VALID_WALLET,
            now,
            now,
            null,
            "P-256"
        );
        
        assertNull(passkey.getDeviceName());
    }
    
    @Test
    public void testRegisteredPasskeyDifferentAlgorithms() {
        long now = System.currentTimeMillis();
        
        PasskeyStore.RegisteredPasskey p256Passkey = new PasskeyStore.RegisteredPasskey(
            "cred-1",
            SAMPLE_PUBLIC_KEY,
            VALID_WALLET,
            now,
            now,
            "Device 1",
            "P-256"
        );
        
        PasskeyStore.RegisteredPasskey secp256k1Passkey = new PasskeyStore.RegisteredPasskey(
            "cred-2",
            SAMPLE_PUBLIC_KEY,
            VALID_WALLET,
            now,
            now,
            "Device 2",
            "secp256k1"
        );
        
        assertEquals("P-256", p256Passkey.getKeyAlgorithm());
        assertEquals("secp256k1", secp256k1Passkey.getKeyAlgorithm());
    }
    
    @Test
    public void testRegisteredPasskeyTimestamps() {
        long createdAt = System.currentTimeMillis() - 86400000; // 1 day ago
        long lastUsedAt = System.currentTimeMillis();
        
        PasskeyStore.RegisteredPasskey passkey = new PasskeyStore.RegisteredPasskey(
            CREDENTIAL_ID,
            SAMPLE_PUBLIC_KEY,
            VALID_WALLET,
            createdAt,
            lastUsedAt,
            null,
            "P-256"
        );
        
        assertEquals(createdAt, passkey.getCreatedAt());
        assertEquals(lastUsedAt, passkey.getLastUsedAt());
        assertTrue(passkey.getLastUsedAt() > passkey.getCreatedAt());
    }
    
    @Test
    public void testRegisteredPasskeyPublicKeyLength() {
        long now = System.currentTimeMillis();
        
        PasskeyStore.RegisteredPasskey passkey = new PasskeyStore.RegisteredPasskey(
            CREDENTIAL_ID,
            SAMPLE_PUBLIC_KEY,
            VALID_WALLET,
            now,
            now,
            null,
            "P-256"
        );
        
        // P-256 uncompressed public key should be 65 bytes
        assertEquals(65, passkey.getPublicKey().length);
    }
    
    @Test
    public void testRegisteredPasskeyEmptyPublicKey() {
        long now = System.currentTimeMillis();
        
        PasskeyStore.RegisteredPasskey passkey = new PasskeyStore.RegisteredPasskey(
            CREDENTIAL_ID,
            new byte[0],
            VALID_WALLET,
            now,
            now,
            null,
            "P-256"
        );
        
        assertEquals(0, passkey.getPublicKey().length);
    }
    
    @Test
    public void testRegisteredPasskeyWalletAddressCase() {
        long now = System.currentTimeMillis();
        String upperCaseWallet = "0xABCDEF1234567890ABCDEF1234567890ABCDEF12";
        
        PasskeyStore.RegisteredPasskey passkey = new PasskeyStore.RegisteredPasskey(
            CREDENTIAL_ID,
            SAMPLE_PUBLIC_KEY,
            upperCaseWallet,
            now,
            now,
            null,
            "P-256"
        );
        
        assertEquals(upperCaseWallet, passkey.getWalletAddress());
    }
    
    // ========================================================================
    // Note: Full PasskeyStore integration tests require Oak repository setup
    // The following tests would be in a separate integration test class:
    // - testRegisterPasskey
    // - testGetPasskey
    // - testGetPasskeys
    // - testUpdateLastUsed
    // - testRemovePasskey
    // - testHasPasskey
    // - testGetPasskeyCount
    // ========================================================================
}
