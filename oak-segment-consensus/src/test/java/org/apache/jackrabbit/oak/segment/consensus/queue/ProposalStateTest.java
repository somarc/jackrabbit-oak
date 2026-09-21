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
package org.apache.jackrabbit.oak.segment.consensus.queue;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link ProposalState} enum.
 * 
 * <p>Tests the proposal state machine transitions:
 * <pre>
 * PENDING → CONFIRMED → VERIFIED → PROCESSED
 *    ↓
 * REJECTED (on timeout or failure)
 * </pre>
 */
public class ProposalStateTest {
    
    /**
     * Test that all expected states exist.
     */
    @Test
    public void testAllStatesExist() {
        assertEquals(5, ProposalState.values().length);
        
        assertNotNull(ProposalState.PENDING);
        assertNotNull(ProposalState.CONFIRMED);
        assertNotNull(ProposalState.VERIFIED);
        assertNotNull(ProposalState.REJECTED);
        assertNotNull(ProposalState.PROCESSED);
    }
    
    /**
     * Test valueOf for all states.
     */
    @Test
    public void testValueOf() {
        assertEquals(ProposalState.PENDING, ProposalState.valueOf("PENDING"));
        assertEquals(ProposalState.CONFIRMED, ProposalState.valueOf("CONFIRMED"));
        assertEquals(ProposalState.VERIFIED, ProposalState.valueOf("VERIFIED"));
        assertEquals(ProposalState.REJECTED, ProposalState.valueOf("REJECTED"));
        assertEquals(ProposalState.PROCESSED, ProposalState.valueOf("PROCESSED"));
    }
    
    /**
     * Test ordinal values (order matters for state machine).
     */
    @Test
    public void testOrdinalOrder() {
        // PENDING should be first (initial state)
        assertEquals(0, ProposalState.PENDING.ordinal());
        
        // CONFIRMED comes after PENDING
        assertEquals(1, ProposalState.CONFIRMED.ordinal());
        
        // VERIFIED comes after CONFIRMED
        assertEquals(2, ProposalState.VERIFIED.ordinal());
        
        // REJECTED is a terminal state
        assertEquals(3, ProposalState.REJECTED.ordinal());
        
        // PROCESSED is the final success state
        assertEquals(4, ProposalState.PROCESSED.ordinal());
    }
    
    /**
     * Test that invalid state name throws exception.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testInvalidValueOf() {
        ProposalState.valueOf("INVALID_STATE");
    }
    
    /**
     * Test state name strings.
     */
    @Test
    public void testStateNames() {
        assertEquals("PENDING", ProposalState.PENDING.name());
        assertEquals("CONFIRMED", ProposalState.CONFIRMED.name());
        assertEquals("VERIFIED", ProposalState.VERIFIED.name());
        assertEquals("REJECTED", ProposalState.REJECTED.name());
        assertEquals("PROCESSED", ProposalState.PROCESSED.name());
    }
    
    /**
     * Test that PENDING is the initial state for new proposals.
     */
    @Test
    public void testPendingIsInitialState() {
        // PENDING should be the first state in the enum (ordinal 0)
        assertEquals(ProposalState.PENDING, ProposalState.values()[0]);
    }
    
    /**
     * Test terminal states (REJECTED and PROCESSED).
     */
    @Test
    public void testTerminalStates() {
        // REJECTED and PROCESSED are terminal states
        // They should be the last two states in the enum
        ProposalState[] states = ProposalState.values();
        
        // REJECTED is a terminal failure state
        assertTrue(ProposalState.REJECTED.ordinal() > ProposalState.VERIFIED.ordinal());
        
        // PROCESSED is the terminal success state
        assertTrue(ProposalState.PROCESSED.ordinal() > ProposalState.VERIFIED.ordinal());
    }
    
    /**
     * Test state transition validity (conceptual - states don't enforce transitions).
     */
    @Test
    public void testValidTransitions() {
        // Valid forward transitions:
        // PENDING → CONFIRMED (payment confirmed on-chain)
        assertTrue(ProposalState.CONFIRMED.ordinal() > ProposalState.PENDING.ordinal());
        
        // CONFIRMED → VERIFIED (on-chain verification complete)
        assertTrue(ProposalState.VERIFIED.ordinal() > ProposalState.CONFIRMED.ordinal());
        
        // VERIFIED → PROCESSED (appended to Raft log)
        assertTrue(ProposalState.PROCESSED.ordinal() > ProposalState.VERIFIED.ordinal());
        
        // PENDING → REJECTED (timeout or failure)
        assertTrue(ProposalState.REJECTED.ordinal() > ProposalState.PENDING.ordinal());
    }
}
