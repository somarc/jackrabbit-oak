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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class BeaconchainDotInProviderTest {

    private static final String BASE = "https://sepolia.beaconcha.in/api/v1";

    @Test
    public void testFetchesFromFinalizedEndpointFirst() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        BeaconchainDotInProvider p = new BeaconchainDotInProvider(BASE, url -> {
            calls.incrementAndGet();
            if (url.endsWith("/epoch/finalized")) {
                return "{\"status\":\"OK\",\"data\":{\"epoch\":12345}}";
            }
            throw new AssertionError("should not reach fallback");
        });

        assertEquals(12345L, p.fetchFinalizedEpoch());
        assertEquals(1, calls.get());
    }

    @Test
    public void testFallsBackToLatestWhenFinalizedFails() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        BeaconchainDotInProvider p = new BeaconchainDotInProvider(BASE, url -> {
            calls.incrementAndGet();
            if (url.endsWith("/epoch/finalized")) {
                throw new RuntimeException("503 Service Unavailable");
            }
            // latest - 2 = finalized
            return "{\"status\":\"OK\",\"data\":{\"epoch\":150}}";
        });

        assertEquals(148L, p.fetchFinalizedEpoch());
        assertEquals(2, calls.get());
    }

    @Test
    public void testLatestEpochFloorAtZero() throws Exception {
        BeaconchainDotInProvider p = new BeaconchainDotInProvider(BASE, url -> {
            if (url.endsWith("/epoch/finalized")) throw new RuntimeException("fail");
            return "{\"status\":\"OK\",\"data\":{\"epoch\":1}}";
        });

        assertEquals(0L, p.fetchFinalizedEpoch()); // max(0, 1-2) = 0
    }

    @Test
    public void testThrowsWhenBothEndpointsFail() {
        BeaconchainDotInProvider p = new BeaconchainDotInProvider(BASE, url -> {
            throw new RuntimeException("network error");
        });

        try {
            p.fetchFinalizedEpoch();
            fail("Expected Exception");
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("all endpoints failed"));
        }
    }

    @Test
    public void testThrowsWhenBothEndpointsReturnUnparseableResponse() {
        BeaconchainDotInProvider p = new BeaconchainDotInProvider(BASE, url -> "{\"garbage\":true}");

        try {
            p.fetchFinalizedEpoch();
            fail("Expected Exception");
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("all endpoints failed"));
        }
    }

    @Test
    public void testNameIncludesApiUrl() {
        BeaconchainDotInProvider p = new BeaconchainDotInProvider(BASE, url -> "");
        assertTrue(p.name().contains(BASE));
    }

    // ── parseEpochFromResponse ────────────────────────────────────────────────

    @Test
    public void testParseStandardObjectShape() {
        BeaconchainDotInProvider p = provider();
        assertEquals(12345L, p.parseEpochFromResponse("{\"status\":\"OK\",\"data\":{\"epoch\":12345}}"));
    }

    @Test
    public void testParseAlternateScalarShape() {
        BeaconchainDotInProvider p = provider();
        assertEquals(12345L, p.parseEpochFromResponse("{\"status\":\"OK\",\"data\":12345}"));
    }

    @Test
    public void testParseReturnsMinusOneWhenFieldMissing() {
        BeaconchainDotInProvider p = provider();
        assertEquals(-1L, p.parseEpochFromResponse("{\"status\":\"OK\",\"data\":{\"finalized\":12345}}"));
    }

    @Test
    public void testParseReturnsMinusOneOnGarbage() {
        BeaconchainDotInProvider p = provider();
        assertEquals(-1L, p.parseEpochFromResponse("{\"data\":oops}"));
    }

    @Test
    public void testParseNullAndEmptyReturnMinusOne() {
        BeaconchainDotInProvider p = provider();
        assertEquals(-1L, p.parseEpochFromResponse(null));
        assertEquals(-1L, p.parseEpochFromResponse(""));
    }

    private static BeaconchainDotInProvider provider() {
        return new BeaconchainDotInProvider(BASE, url -> "");
    }
}
