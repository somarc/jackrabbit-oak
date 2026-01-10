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

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Base64;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link ChallengeService}.
 */
public class ChallengeServiceTest {
    
    private ChallengeService service;
    
    @Before
    public void setUp() {
        // Use short TTL for testing
        service = new ChallengeService(32, 1000); // 32 bytes, 1 second TTL
    }
    
    @After
    public void tearDown() {
        if (service != null) {
            service.shutdown();
        }
    }
    
    // ========================================================================
    // Challenge Generation Tests
    // ========================================================================
    
    @Test
    public void testGenerateChallengeReturnsNonNull() {
        ChallengeService.Challenge challenge = service.generateChallenge("client1");
        assertNotNull(challenge);
    }
    
    @Test
    public void testGenerateChallengeReturnsCorrectSize() {
        ChallengeService.Challenge challenge = service.generateChallenge("client1");
        assertEquals(32, challenge.getBytes().length);
    }
    
    @Test
    public void testGenerateChallengeReturnsUniqueValues() {
        ChallengeService.Challenge c1 = service.generateChallenge("client1");
        ChallengeService.Challenge c2 = service.generateChallenge("client2");
        
        assertFalse("Challenges should be unique", 
            java.util.Arrays.equals(c1.getBytes(), c2.getBytes()));
    }
    
    @Test
    public void testGenerateChallengeReplacesExisting() {
        ChallengeService.Challenge c1 = service.generateChallenge("client1");
        ChallengeService.Challenge c2 = service.generateChallenge("client1");
        
        // New challenge should replace old one
        ChallengeService.Challenge current = service.getChallenge("client1");
        assertArrayEquals(c2.getBytes(), current.getBytes());
    }
    
    @Test
    public void testChallengeHasCorrectClientId() {
        ChallengeService.Challenge challenge = service.generateChallenge("myClient");
        assertEquals("myClient", challenge.getClientId());
    }
    
    @Test
    public void testChallengeHasValidTimestamps() {
        long before = System.currentTimeMillis();
        ChallengeService.Challenge challenge = service.generateChallenge("client1");
        long after = System.currentTimeMillis();
        
        assertTrue(challenge.getCreatedAt() >= before);
        assertTrue(challenge.getCreatedAt() <= after);
        assertTrue(challenge.getExpiresAt() > challenge.getCreatedAt());
    }
    
    @Test
    public void testChallengeBase64Encoding() {
        ChallengeService.Challenge challenge = service.generateChallenge("client1");
        String base64 = challenge.getBase64();
        
        assertNotNull(base64);
        assertFalse(base64.isEmpty());
        
        // Should be valid Base64
        byte[] decoded = Base64.getUrlDecoder().decode(base64);
        assertArrayEquals(challenge.getBytes(), decoded);
    }
    
    // ========================================================================
    // Challenge Validation Tests
    // ========================================================================
    
    @Test
    public void testValidateAndConsumeValidChallenge() {
        ChallengeService.Challenge challenge = service.generateChallenge("client1");
        
        boolean valid = service.validateAndConsumeChallenge("client1", challenge.getBytes());
        assertTrue("Valid challenge should be accepted", valid);
    }
    
    @Test
    public void testValidateAndConsumeInvalidBytes() {
        service.generateChallenge("client1");
        
        byte[] wrongBytes = new byte[32];
        boolean valid = service.validateAndConsumeChallenge("client1", wrongBytes);
        assertFalse("Wrong bytes should be rejected", valid);
    }
    
    @Test
    public void testValidateAndConsumeWrongClient() {
        ChallengeService.Challenge challenge = service.generateChallenge("client1");
        
        boolean valid = service.validateAndConsumeChallenge("client2", challenge.getBytes());
        assertFalse("Wrong client should be rejected", valid);
    }
    
    @Test
    public void testValidateAndConsumeNonExistentChallenge() {
        byte[] randomBytes = new byte[32];
        boolean valid = service.validateAndConsumeChallenge("nonexistent", randomBytes);
        assertFalse("Non-existent challenge should be rejected", valid);
    }
    
    @Test
    public void testChallengeIsConsumedAfterValidation() {
        ChallengeService.Challenge challenge = service.generateChallenge("client1");
        
        // First validation should succeed
        assertTrue(service.validateAndConsumeChallenge("client1", challenge.getBytes()));
        
        // Second validation should fail (already consumed)
        assertFalse(service.validateAndConsumeChallenge("client1", challenge.getBytes()));
    }
    
    @Test
    public void testChallengeExpiration() throws InterruptedException {
        ChallengeService.Challenge challenge = service.generateChallenge("client1");
        
        // Wait for challenge to expire (TTL is 1 second)
        Thread.sleep(1100);
        
        assertTrue("Challenge should be expired", challenge.isExpired());
        assertFalse("Expired challenge should be rejected", 
            service.validateAndConsumeChallenge("client1", challenge.getBytes()));
    }
    
    // ========================================================================
    // getChallenge Tests
    // ========================================================================
    
    @Test
    public void testGetChallengeReturnsStoredChallenge() {
        ChallengeService.Challenge generated = service.generateChallenge("client1");
        ChallengeService.Challenge retrieved = service.getChallenge("client1");
        
        assertNotNull(retrieved);
        assertArrayEquals(generated.getBytes(), retrieved.getBytes());
    }
    
    @Test
    public void testGetChallengeReturnsNullForNonExistent() {
        ChallengeService.Challenge challenge = service.getChallenge("nonexistent");
        assertNull(challenge);
    }
    
    @Test
    public void testGetChallengeReturnsNullForExpired() throws InterruptedException {
        service.generateChallenge("client1");
        
        // Wait for expiration
        Thread.sleep(1100);
        
        ChallengeService.Challenge challenge = service.getChallenge("client1");
        assertNull("Expired challenge should return null", challenge);
    }
    
    // ========================================================================
    // revokeChallenge Tests
    // ========================================================================
    
    @Test
    public void testRevokeChallengeRemovesChallenge() {
        service.generateChallenge("client1");
        
        assertTrue(service.revokeChallenge("client1"));
        assertNull(service.getChallenge("client1"));
    }
    
    @Test
    public void testRevokeChallengeReturnsFalseForNonExistent() {
        assertFalse(service.revokeChallenge("nonexistent"));
    }
    
    @Test
    public void testRevokedChallengeCannotBeValidated() {
        ChallengeService.Challenge challenge = service.generateChallenge("client1");
        service.revokeChallenge("client1");
        
        assertFalse(service.validateAndConsumeChallenge("client1", challenge.getBytes()));
    }
    
    // ========================================================================
    // getActiveChallengeCount Tests
    // ========================================================================
    
    @Test
    public void testGetActiveChallengeCountInitiallyZero() {
        assertEquals(0, service.getActiveChallengeCount());
    }
    
    @Test
    public void testGetActiveChallengeCountIncrementsOnGenerate() {
        service.generateChallenge("client1");
        assertEquals(1, service.getActiveChallengeCount());
        
        service.generateChallenge("client2");
        assertEquals(2, service.getActiveChallengeCount());
    }
    
    @Test
    public void testGetActiveChallengeCountDecrementsOnConsume() {
        service.generateChallenge("client1");
        ChallengeService.Challenge c2 = service.generateChallenge("client2");
        
        assertEquals(2, service.getActiveChallengeCount());
        
        service.validateAndConsumeChallenge("client2", c2.getBytes());
        assertEquals(1, service.getActiveChallengeCount());
    }
    
    @Test
    public void testGetActiveChallengeCountExcludesExpired() throws InterruptedException {
        service.generateChallenge("client1");
        assertEquals(1, service.getActiveChallengeCount());
        
        // Wait for expiration
        Thread.sleep(1100);
        
        assertEquals(0, service.getActiveChallengeCount());
    }
    
    // ========================================================================
    // Defensive Copy Tests
    // ========================================================================
    
    @Test
    public void testGetBytesReturnsDefensiveCopy() {
        ChallengeService.Challenge challenge = service.generateChallenge("client1");
        
        byte[] bytes1 = challenge.getBytes();
        byte[] bytes2 = challenge.getBytes();
        
        assertNotSame(bytes1, bytes2);
        assertArrayEquals(bytes1, bytes2);
        
        // Modifying returned array should not affect stored challenge
        byte originalValue = bytes1[0];
        bytes1[0] = (byte) (originalValue ^ 0xFF); // XOR to ensure different value
        assertNotEquals(bytes1[0], challenge.getBytes()[0]);
    }
    
    // ========================================================================
    // Singleton Tests
    // ========================================================================
    
    @Test
    public void testGetInstanceReturnsSameInstance() {
        ChallengeService instance1 = ChallengeService.getInstance();
        ChallengeService instance2 = ChallengeService.getInstance();
        
        assertSame(instance1, instance2);
    }
    
    // ========================================================================
    // Edge Cases
    // ========================================================================
    
    @Test
    public void testValidateWithDifferentLengthBytes() {
        service.generateChallenge("client1");
        
        // Wrong length should fail
        byte[] shortBytes = new byte[16];
        assertFalse(service.validateAndConsumeChallenge("client1", shortBytes));
        
        byte[] longBytes = new byte[64];
        assertFalse(service.validateAndConsumeChallenge("client1", longBytes));
    }
    
    @Test
    public void testMultipleClientsIndependent() {
        ChallengeService.Challenge c1 = service.generateChallenge("client1");
        ChallengeService.Challenge c2 = service.generateChallenge("client2");
        
        // Validate client1's challenge
        assertTrue(service.validateAndConsumeChallenge("client1", c1.getBytes()));
        
        // client2's challenge should still be valid
        assertTrue(service.validateAndConsumeChallenge("client2", c2.getBytes()));
    }
    
    @Test
    public void testChallengeNotExpiredImmediately() {
        ChallengeService.Challenge challenge = service.generateChallenge("client1");
        assertFalse(challenge.isExpired());
        assertFalse(challenge.isConsumed());
    }
}
