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
package org.apache.jackrabbit.oak.segment.consensus.leader;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link ValidatorRole} enum.
 * 
 * <p>Tests the validator role states in the consensus network:
 * <ul>
 *   <li>LEADER - Source of truth, accepts writes</li>
 *   <li>FOLLOWER - Replicates state, serves reads</li>
 * </ul>
 */
public class ValidatorRoleTest {
    
    /**
     * Test that all expected roles exist.
     */
    @Test
    public void testAllRolesExist() {
        assertEquals(2, ValidatorRole.values().length);
        
        assertNotNull(ValidatorRole.LEADER);
        assertNotNull(ValidatorRole.FOLLOWER);
    }
    
    /**
     * Test valueOf for all roles.
     */
    @Test
    public void testValueOf() {
        assertEquals(ValidatorRole.LEADER, ValidatorRole.valueOf("LEADER"));
        assertEquals(ValidatorRole.FOLLOWER, ValidatorRole.valueOf("FOLLOWER"));
    }
    
    /**
     * Test ordinal values.
     */
    @Test
    public void testOrdinalValues() {
        assertEquals(0, ValidatorRole.LEADER.ordinal());
        assertEquals(1, ValidatorRole.FOLLOWER.ordinal());
    }
    
    /**
     * Test that invalid role name throws exception.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testInvalidValueOf() {
        ValidatorRole.valueOf("CANDIDATE");
    }
    
    /**
     * Test role name strings.
     */
    @Test
    public void testRoleNames() {
        assertEquals("LEADER", ValidatorRole.LEADER.name());
        assertEquals("FOLLOWER", ValidatorRole.FOLLOWER.name());
    }
    
    /**
     * Test role toString.
     */
    @Test
    public void testRoleToString() {
        assertEquals("LEADER", ValidatorRole.LEADER.toString());
        assertEquals("FOLLOWER", ValidatorRole.FOLLOWER.toString());
    }
    
    /**
     * Test role equality.
     */
    @Test
    public void testRoleEquality() {
        // Same role should be equal
        assertEquals(ValidatorRole.LEADER, ValidatorRole.LEADER);
        assertEquals(ValidatorRole.FOLLOWER, ValidatorRole.FOLLOWER);
        
        // Different roles should not be equal
        assertNotEquals(ValidatorRole.LEADER, ValidatorRole.FOLLOWER);
    }
    
    /**
     * Test role in switch statement (common usage pattern).
     */
    @Test
    public void testRoleInSwitch() {
        ValidatorRole role = ValidatorRole.LEADER;
        
        String result;
        switch (role) {
            case LEADER:
                result = "accepts writes";
                break;
            case FOLLOWER:
                result = "serves reads";
                break;
            default:
                result = "unknown";
        }
        
        assertEquals("accepts writes", result);
    }
    
    /**
     * Test role comparison.
     */
    @Test
    public void testRoleComparison() {
        // LEADER comes before FOLLOWER in ordinal
        assertTrue(ValidatorRole.LEADER.compareTo(ValidatorRole.FOLLOWER) < 0);
        assertTrue(ValidatorRole.FOLLOWER.compareTo(ValidatorRole.LEADER) > 0);
        assertEquals(0, ValidatorRole.LEADER.compareTo(ValidatorRole.LEADER));
    }
}
