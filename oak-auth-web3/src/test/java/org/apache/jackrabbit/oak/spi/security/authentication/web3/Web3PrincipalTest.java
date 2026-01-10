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

import java.security.Principal;

import static org.junit.Assert.*;

/**
 * Tests for {@link Web3Principal}.
 */
public class Web3PrincipalTest {
    
    // Valid Ethereum addresses for testing
    private static final String VALID_ADDRESS_LOWER = "0x1234567890abcdef1234567890abcdef12345678";
    private static final String VALID_ADDRESS_UPPER = "0x1234567890ABCDEF1234567890ABCDEF12345678";
    private static final String VALID_ADDRESS_MIXED = "0x1234567890AbCdEf1234567890aBcDeF12345678";
    private static final String ANOTHER_ADDRESS = "0xabcdef1234567890abcdef1234567890abcdef12";
    
    // ========================================================================
    // Constructor Tests
    // ========================================================================
    
    @Test
    public void testConstructorWithValidLowercaseAddress() {
        Web3Principal principal = new Web3Principal(VALID_ADDRESS_LOWER);
        assertNotNull(principal);
        assertEquals(VALID_ADDRESS_LOWER, principal.getWalletAddress());
    }
    
    @Test
    public void testConstructorWithValidUppercaseAddress() {
        Web3Principal principal = new Web3Principal(VALID_ADDRESS_UPPER);
        assertNotNull(principal);
        assertEquals(VALID_ADDRESS_UPPER, principal.getWalletAddress());
    }
    
    @Test
    public void testConstructorWithValidMixedCaseAddress() {
        Web3Principal principal = new Web3Principal(VALID_ADDRESS_MIXED);
        assertNotNull(principal);
        assertEquals(VALID_ADDRESS_MIXED, principal.getWalletAddress());
    }
    
    @Test(expected = NullPointerException.class)
    public void testConstructorWithNullAddress() {
        new Web3Principal(null);
    }
    
    @Test(expected = IllegalArgumentException.class)
    public void testConstructorWithEmptyAddress() {
        new Web3Principal("");
    }
    
    @Test(expected = IllegalArgumentException.class)
    public void testConstructorWithoutPrefix() {
        new Web3Principal("1234567890abcdef1234567890abcdef12345678");
    }
    
    @Test(expected = IllegalArgumentException.class)
    public void testConstructorWithTooShortAddress() {
        new Web3Principal("0x1234567890abcdef");
    }
    
    @Test(expected = IllegalArgumentException.class)
    public void testConstructorWithTooLongAddress() {
        new Web3Principal("0x1234567890abcdef1234567890abcdef1234567890");
    }
    
    @Test(expected = IllegalArgumentException.class)
    public void testConstructorWithInvalidHexCharacters() {
        new Web3Principal("0x1234567890abcdef1234567890abcdef1234567g");
    }
    
    @Test(expected = IllegalArgumentException.class)
    public void testConstructorWithWrongPrefix() {
        new Web3Principal("1x1234567890abcdef1234567890abcdef12345678");
    }
    
    // ========================================================================
    // getName() Tests
    // ========================================================================
    
    @Test
    public void testGetNameReturnsLowercase() {
        Web3Principal principal = new Web3Principal(VALID_ADDRESS_UPPER);
        assertEquals(VALID_ADDRESS_UPPER.toLowerCase(), principal.getName());
    }
    
    @Test
    public void testGetNameConsistentWithPrincipalInterface() {
        Principal principal = new Web3Principal(VALID_ADDRESS_LOWER);
        assertEquals(VALID_ADDRESS_LOWER.toLowerCase(), principal.getName());
    }
    
    // ========================================================================
    // getWalletAddress() Tests
    // ========================================================================
    
    @Test
    public void testGetWalletAddressPreservesCase() {
        Web3Principal principal = new Web3Principal(VALID_ADDRESS_MIXED);
        assertEquals(VALID_ADDRESS_MIXED, principal.getWalletAddress());
    }
    
    // ========================================================================
    // equals() Tests
    // ========================================================================
    
    @Test
    public void testEqualsWithSameInstance() {
        Web3Principal principal = new Web3Principal(VALID_ADDRESS_LOWER);
        assertTrue(principal.equals(principal));
    }
    
    @Test
    public void testEqualsWithSameAddress() {
        Web3Principal p1 = new Web3Principal(VALID_ADDRESS_LOWER);
        Web3Principal p2 = new Web3Principal(VALID_ADDRESS_LOWER);
        assertTrue(p1.equals(p2));
        assertTrue(p2.equals(p1));
    }
    
    @Test
    public void testEqualsIsCaseInsensitive() {
        Web3Principal lower = new Web3Principal(VALID_ADDRESS_LOWER);
        Web3Principal upper = new Web3Principal(VALID_ADDRESS_UPPER);
        Web3Principal mixed = new Web3Principal(VALID_ADDRESS_MIXED);
        
        assertTrue(lower.equals(upper));
        assertTrue(upper.equals(lower));
        assertTrue(lower.equals(mixed));
        assertTrue(mixed.equals(lower));
        assertTrue(upper.equals(mixed));
        assertTrue(mixed.equals(upper));
    }
    
    @Test
    public void testEqualsWithDifferentAddress() {
        Web3Principal p1 = new Web3Principal(VALID_ADDRESS_LOWER);
        Web3Principal p2 = new Web3Principal(ANOTHER_ADDRESS);
        assertFalse(p1.equals(p2));
        assertFalse(p2.equals(p1));
    }
    
    @Test
    public void testEqualsWithNull() {
        Web3Principal principal = new Web3Principal(VALID_ADDRESS_LOWER);
        assertFalse(principal.equals(null));
    }
    
    @Test
    public void testEqualsWithDifferentType() {
        Web3Principal principal = new Web3Principal(VALID_ADDRESS_LOWER);
        assertFalse(principal.equals("not a principal"));
        assertFalse(principal.equals(42));
    }
    
    // ========================================================================
    // hashCode() Tests
    // ========================================================================
    
    @Test
    public void testHashCodeConsistentWithEquals() {
        Web3Principal p1 = new Web3Principal(VALID_ADDRESS_LOWER);
        Web3Principal p2 = new Web3Principal(VALID_ADDRESS_LOWER);
        
        assertEquals(p1.hashCode(), p2.hashCode());
    }
    
    @Test
    public void testHashCodeCaseInsensitive() {
        Web3Principal lower = new Web3Principal(VALID_ADDRESS_LOWER);
        Web3Principal upper = new Web3Principal(VALID_ADDRESS_UPPER);
        Web3Principal mixed = new Web3Principal(VALID_ADDRESS_MIXED);
        
        assertEquals(lower.hashCode(), upper.hashCode());
        assertEquals(lower.hashCode(), mixed.hashCode());
    }
    
    @Test
    public void testHashCodeDifferentForDifferentAddresses() {
        Web3Principal p1 = new Web3Principal(VALID_ADDRESS_LOWER);
        Web3Principal p2 = new Web3Principal(ANOTHER_ADDRESS);
        
        // Note: Different objects CAN have same hashCode, but SHOULD be different
        // This test documents expected behavior but isn't strictly required
        assertNotEquals(p1.hashCode(), p2.hashCode());
    }
    
    // ========================================================================
    // toString() Tests
    // ========================================================================
    
    @Test
    public void testToStringContainsClassName() {
        Web3Principal principal = new Web3Principal(VALID_ADDRESS_LOWER);
        assertTrue(principal.toString().contains("Web3Principal"));
    }
    
    @Test
    public void testToStringContainsAddress() {
        Web3Principal principal = new Web3Principal(VALID_ADDRESS_LOWER);
        assertTrue(principal.toString().contains(VALID_ADDRESS_LOWER));
    }
    
    // ========================================================================
    // Serialization Tests (Principal implements Serializable)
    // ========================================================================
    
    @Test
    public void testSerializable() throws Exception {
        Web3Principal original = new Web3Principal(VALID_ADDRESS_LOWER);
        
        // Serialize
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        java.io.ObjectOutputStream oos = new java.io.ObjectOutputStream(baos);
        oos.writeObject(original);
        oos.close();
        
        // Deserialize
        java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(baos.toByteArray());
        java.io.ObjectInputStream ois = new java.io.ObjectInputStream(bais);
        Web3Principal deserialized = (Web3Principal) ois.readObject();
        ois.close();
        
        // Verify
        assertEquals(original, deserialized);
        assertEquals(original.getWalletAddress(), deserialized.getWalletAddress());
        assertEquals(original.getName(), deserialized.getName());
    }
    
    // ========================================================================
    // Edge Case Tests
    // ========================================================================
    
    @Test
    public void testZeroAddress() {
        // Zero address is technically valid format
        Web3Principal principal = new Web3Principal("0x0000000000000000000000000000000000000000");
        assertEquals("0x0000000000000000000000000000000000000000", principal.getWalletAddress());
    }
    
    @Test
    public void testMaxAddress() {
        // All F's is technically valid format
        Web3Principal principal = new Web3Principal("0xffffffffffffffffffffffffffffffffffffffff");
        assertEquals("0xffffffffffffffffffffffffffffffffffffffff", principal.getWalletAddress());
    }
}
