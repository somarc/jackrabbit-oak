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

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class CircuitBreakingBeaconProviderTest {

    @Test
    public void testPassesThroughWhenClosed() throws Exception {
        CircuitBreakingBeaconProvider cb = wrap(() -> 100L, 3, 60_000L);
        assertEquals(100L, cb.fetchFinalizedEpoch());
        assertFalse(cb.isCircuitOpen());
    }

    @Test
    public void testOpensAfterThresholdConsecutiveFailures() {
        CircuitBreakingBeaconProvider cb = wrap(() -> { throw new RuntimeException("fail"); }, 3, 60_000L);

        for (int i = 0; i < 3; i++) {
            try {
                cb.fetchFinalizedEpoch();
            } catch (Exception ignored) { }
        }

        assertTrue(cb.isCircuitOpen());
        assertEquals(3, cb.getConsecutiveFailures());
    }

    @Test
    public void testCircuitOpenThrowsCircuitOpenException() throws Exception {
        // Force circuit open by exceeding threshold
        CircuitBreakingBeaconProvider cb = wrap(() -> { throw new RuntimeException("fail"); }, 2, 60_000L);
        try { cb.fetchFinalizedEpoch(); } catch (Exception ignored) { }
        try { cb.fetchFinalizedEpoch(); } catch (Exception ignored) { }

        assertTrue(cb.isCircuitOpen());
        try {
            cb.fetchFinalizedEpoch();
            fail("Expected CircuitOpenException");
        } catch (CircuitBreakingBeaconProvider.CircuitOpenException e) {
            assertTrue(e.getMessage().contains("circuit is open"));
        }
    }

    @Test
    public void testDelegateNotCalledWhenCircuitOpen() throws Exception {
        AtomicInteger delegateCalls = new AtomicInteger();
        CircuitBreakingBeaconProvider cb = wrap(() -> {
            delegateCalls.incrementAndGet();
            throw new RuntimeException("fail");
        }, 2, 60_000L);

        // Open the circuit
        try { cb.fetchFinalizedEpoch(); } catch (Exception ignored) { }
        try { cb.fetchFinalizedEpoch(); } catch (Exception ignored) { }
        int callsAtOpen = delegateCalls.get();

        // Circuit is now open — delegate must not be called
        try { cb.fetchFinalizedEpoch(); } catch (Exception ignored) { }
        assertEquals(callsAtOpen, delegateCalls.get());
    }

    @Test
    public void testSuccessfulProbeAfterCooldownClosesCircuit() throws Exception {
        AtomicInteger callCount = new AtomicInteger();
        // Use very short cooldown so the test doesn't sleep
        CircuitBreakingBeaconProvider cb = wrap(() -> {
            int call = callCount.incrementAndGet();
            if (call <= 2) throw new RuntimeException("fail");
            return 42L; // probe succeeds
        }, 2, 20L); // 20 ms cooldown

        try { cb.fetchFinalizedEpoch(); } catch (Exception ignored) { }
        try { cb.fetchFinalizedEpoch(); } catch (Exception ignored) { }
        assertTrue(cb.isCircuitOpen());

        Thread.sleep(25); // let cooldown elapse

        long result = cb.fetchFinalizedEpoch(); // probe succeeds → circuit closes
        assertEquals(42L, result);
        assertFalse(cb.isCircuitOpen());
        assertEquals(0, cb.getConsecutiveFailures());
    }

    @Test
    public void testFailedProbeRefreshesCooldown() throws Exception {
        // Use a short-but-not-zero cooldown: 20ms lets the half-open window open
        // for the probe, but the assertion runs before the refreshed 20ms elapses.
        CircuitBreakingBeaconProvider cb = wrap(
            () -> { throw new RuntimeException("still broken"); }, 2, 20L);

        try { cb.fetchFinalizedEpoch(); } catch (Exception ignored) { }
        try { cb.fetchFinalizedEpoch(); } catch (Exception ignored) { }
        assertTrue(cb.isCircuitOpen());

        Thread.sleep(25); // let cooldown elapse so half-open probe is allowed
        // Half-open probe — fails again, refreshes openedAt
        try { cb.fetchFinalizedEpoch(); } catch (Exception ignored) { }

        // Immediately after probe failure: cooldown was just refreshed — circuit is open
        assertTrue(cb.isCircuitOpen());
        assertEquals(3, cb.getConsecutiveFailures());
    }

    @Test
    public void testPartialFailuresBelowThresholdDoNotOpenCircuit() {
        AtomicInteger call = new AtomicInteger();
        CircuitBreakingBeaconProvider cb = wrap(() -> {
            if (call.incrementAndGet() % 2 == 0) throw new RuntimeException("intermittent");
            return 77L;
        }, 3, 60_000L);

        // 1=success, 2=fail, 3=success — resets counter; never reaches threshold
        try { cb.fetchFinalizedEpoch(); } catch (Exception ignored) { }
        try { cb.fetchFinalizedEpoch(); } catch (Exception ignored) { }
        try { cb.fetchFinalizedEpoch(); } catch (Exception ignored) { }

        assertFalse(cb.isCircuitOpen());
    }

    @Test
    public void testSuccessResetsConsecutiveFailureCounter() throws Exception {
        AtomicInteger call = new AtomicInteger();
        CircuitBreakingBeaconProvider cb = wrap(() -> {
            int n = call.incrementAndGet();
            if (n < 3) throw new RuntimeException("not yet");
            return 55L;
        }, 5, 60_000L);

        try { cb.fetchFinalizedEpoch(); } catch (Exception ignored) { }
        try { cb.fetchFinalizedEpoch(); } catch (Exception ignored) { }
        assertEquals(2, cb.getConsecutiveFailures());

        cb.fetchFinalizedEpoch(); // succeeds
        assertEquals(0, cb.getConsecutiveFailures());
        assertFalse(cb.isCircuitOpen());
    }

    @Test
    public void testNameDelegatesToDelegate() {
        BeaconChainProvider delegate = new BeaconChainProvider() {
            public long fetchFinalizedEpoch() { return 0; }
            public String name() { return "my-provider"; }
        };
        CircuitBreakingBeaconProvider cb = new CircuitBreakingBeaconProvider(delegate, 3, 60_000L);
        assertEquals("my-provider", cb.name());
    }

    private static CircuitBreakingBeaconProvider wrap(
            ThrowingSupplier delegate, int threshold, long cooldown) {
        BeaconChainProvider provider = new BeaconChainProvider() {
            public long fetchFinalizedEpoch() throws Exception { return delegate.get(); }
            public String name() { return "test-delegate"; }
        };
        return new CircuitBreakingBeaconProvider(provider, threshold, cooldown);
    }

    @FunctionalInterface
    interface ThrowingSupplier {
        long get() throws Exception;
    }
}
