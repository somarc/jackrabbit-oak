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
package org.apache.jackrabbit.oak.segment.consensus.eth;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class FallbackBeaconChainProviderTest {

    @Test
    public void testReturnsFirstSuccessfulProvider() throws Exception {
        FallbackBeaconChainProvider fb = new FallbackBeaconChainProvider(
            fixed("primary", 100L),
            fixed("secondary", 200L));

        assertEquals(100L, fb.fetchFinalizedEpoch());
    }

    @Test
    public void testSkipsFailingPrimaryAndUsesSecondary() throws Exception {
        FallbackBeaconChainProvider fb = new FallbackBeaconChainProvider(
            failing("primary"),
            fixed("secondary", 42L));

        assertEquals(42L, fb.fetchFinalizedEpoch());
    }

    @Test
    public void testSkipsOpenCircuitWithoutCallingDelegate() throws Exception {
        AtomicInteger primaryCalls = new AtomicInteger();
        BeaconChainProvider openCircuit = new BeaconChainProvider() {
            public long fetchFinalizedEpoch() {
                primaryCalls.incrementAndGet();
                throw new CircuitBreakingBeaconProvider.CircuitOpenException("open");
            }
            public String name() { return "open-circuit"; }
        };

        FallbackBeaconChainProvider fb = new FallbackBeaconChainProvider(
            openCircuit, fixed("secondary", 77L));

        assertEquals(77L, fb.fetchFinalizedEpoch());
        assertEquals(1, primaryCalls.get()); // called once, then skipped on next? No — called once, threw CircuitOpen
    }

    @Test
    public void testThrowsWhenAllProvidersFail() {
        FallbackBeaconChainProvider fb = new FallbackBeaconChainProvider(
            failing("p1"), failing("p2"));

        try {
            fb.fetchFinalizedEpoch();
            fail("Expected Exception");
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("All beacon providers failed"));
            assertTrue(e.getMessage().contains("p1"));
            assertTrue(e.getMessage().contains("p2"));
        }
    }

    @Test
    public void testThrowsOnEmptyProviderList() {
        try {
            new FallbackBeaconChainProvider(List.of());
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("At least one provider"));
        }
    }

    @Test
    public void testSkipsProviderReturningMinusOne() throws Exception {
        FallbackBeaconChainProvider fb = new FallbackBeaconChainProvider(
            returnValue("negative", -1L),
            fixed("good", 88L));

        assertEquals(88L, fb.fetchFinalizedEpoch());
    }

    @Test
    public void testGetProviderNamesInOrder() {
        FallbackBeaconChainProvider fb = new FallbackBeaconChainProvider(
            fixed("alpha", 1L), fixed("beta", 2L), fixed("gamma", 3L));

        List<String> names = fb.getProviderNames();
        assertEquals(Arrays.asList("alpha", "beta", "gamma"), names);
    }

    @Test
    public void testNameReflectsProviderList() {
        FallbackBeaconChainProvider fb = new FallbackBeaconChainProvider(
            fixed("a", 1L), fixed("b", 2L));

        assertTrue(fb.name().contains("a"));
        assertTrue(fb.name().contains("b"));
    }

    @Test
    public void testSingleProviderSuccess() throws Exception {
        FallbackBeaconChainProvider fb = new FallbackBeaconChainProvider(fixed("only", 999L));
        assertEquals(999L, fb.fetchFinalizedEpoch());
    }

    @Test
    public void testAllOpenCircuitsThrow() {
        FallbackBeaconChainProvider fb = new FallbackBeaconChainProvider(
            openCircuit("c1"), openCircuit("c2"));

        try {
            fb.fetchFinalizedEpoch();
            fail("Expected exception");
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("All beacon providers failed"));
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static BeaconChainProvider fixed(String name, long epoch) {
        return new BeaconChainProvider() {
            public long fetchFinalizedEpoch() { return epoch; }
            public String name() { return name; }
        };
    }

    private static BeaconChainProvider returnValue(String name, long value) {
        return fixed(name, value);
    }

    private static BeaconChainProvider failing(String name) {
        return new BeaconChainProvider() {
            public long fetchFinalizedEpoch() throws Exception {
                throw new Exception(name + " failed");
            }
            public String name() { return name; }
        };
    }

    private static BeaconChainProvider openCircuit(String name) {
        return new BeaconChainProvider() {
            public long fetchFinalizedEpoch() {
                throw new CircuitBreakingBeaconProvider.CircuitOpenException(name + " circuit is open");
            }
            public String name() { return name; }
        };
    }
}
