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

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.jackrabbit.oak.segment.consensus.config.BlockchainConfig;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class BeaconChainClientTest {

    private static final String PROP_MODE = "oak.blockchain.mode";

    @Before
    public void setUp() {
        clearProps();
        BlockchainConfig.reset();
    }

    @After
    public void tearDown() {
        clearProps();
        BlockchainConfig.reset();
    }

    @Test
    public void testConstructsInMockModeAndReportsSepoliaBackedHealth() {
        BeaconChainClient client = clientWithFinalizedEpoch(BlockchainConfig.Mode.MOCK, 12345L);

        Map<String, Object> health = client.getHealthStatus();

        assertEquals(BlockchainConfig.Mode.MOCK, client.getNetworkMode());
        assertEquals(12347L, client.getCachedCurrentEpoch());
        assertEquals(12345L, client.getCachedFinalizedEpoch());
        assertTrue(client.isEpochDataFresh());
        assertEquals("MOCK", health.get("mode"));
        assertEquals("https://sepolia.beaconcha.in/api/v1", health.get("apiUrl"));
        assertEquals("sepolia", health.get("chainContext"));
        assertNull(health.get("mockEpochOffset"));
        assertNull(health.get("mockEpochDurationMs"));
    }

    @Test
    public void testMockEpochControlsAreDisabled() {
        BeaconChainClient client = clientWithFinalizedEpoch(BlockchainConfig.Mode.MOCK, 222L);
        long initialCurrent = client.getCachedCurrentEpoch();
        long initialFinalized = client.getCachedFinalizedEpoch();

        assertFalse(client.setMockEpochOffset(42L));
        assertFalse(client.advanceMockEpoch(3));
        assertEquals(0L, client.getMockEpochOffset());
        assertEquals(initialCurrent, client.getCachedCurrentEpoch());
        assertEquals(initialFinalized, client.getCachedFinalizedEpoch());
    }

    @Test
    public void testUsesLatestEndpointFallbackWhenFinalizedEndpointFails() {
        AtomicInteger fetchCount = new AtomicInteger();
        BeaconChainClient client = new BeaconChainClient(BlockchainConfig.Mode.MOCK, endpoint -> {
            fetchCount.incrementAndGet();
            if (endpoint.endsWith("/epoch/finalized")) {
                throw new IllegalStateException("forced-finalized-failure");
            }
            if (endpoint.endsWith("/epoch/latest")) {
                return "{\"status\":\"OK\",\"data\":{\"epoch\":150}}";
            }
            throw new IllegalArgumentException("Unexpected endpoint: " + endpoint);
        });

        assertEquals(150L, client.getCachedCurrentEpoch());
        assertEquals(148L, client.getCachedFinalizedEpoch());
        assertEquals("/epoch/latest", client.getHealthStatus().get("lastEndpointUsed"));
        assertEquals(2, fetchCount.get());
    }

    @Test
    public void testStaleEpochDataDetectedAndFreshnessCheckThrows() throws Exception {
        BeaconChainClient client = clientWithFinalizedEpoch(BlockchainConfig.Mode.MOCK, 200L);

        setField(client, "lastUpdateTime", System.currentTimeMillis() - 301000L);

        assertFalse(client.isEpochDataFresh());
        try {
            client.checkEpochFreshness();
            fail("Expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertEquals("Epoch data is stale!", e.getMessage());
        }
    }

    @Test
    public void testParseEpochFromResponseHandlesStandardAlternateAndInvalidPayloads() throws Exception {
        BeaconChainClient client = clientWithFinalizedEpoch(BlockchainConfig.Mode.MOCK, 99L);
        Method method = BeaconChainClient.class.getDeclaredMethod("parseEpochFromResponse", String.class);
        method.setAccessible(true);

        assertEquals(12345L, ((Long) method.invoke(client, "{\"status\":\"OK\",\"data\":{\"epoch\":12345}}")).longValue());
        assertEquals(12345L, ((Long) method.invoke(client, "{\"status\":\"OK\",\"data\":12345}")).longValue());
        assertEquals(-1L, ((Long) method.invoke(client, "{\"status\":\"OK\",\"data\":{\"finalized\":12345}}")).longValue());
        assertEquals(-1L, ((Long) method.invoke(client, "{\"status\":\"OK\",\"data\":oops}")).longValue());
    }

    @Test
    public void testEpochDetailsAndLatestFinalizedEpochReflectCachedState() throws Exception {
        BeaconChainClient client = clientWithFinalizedEpoch(BlockchainConfig.Mode.MOCK, 88L);

        long currentEpoch = client.getCachedCurrentEpoch();
        long finalizedEpoch = client.getCachedFinalizedEpoch();
        EpochData finalized = client.getEpochDetails(finalizedEpoch);
        EpochData future = client.getEpochDetails(currentEpoch + 1);
        EpochData latest = client.getLatestFinalizedEpoch();

        assertEquals(finalizedEpoch, finalized.epochNumber);
        assertTrue(finalized.finalized);
        assertEquals(2, finalized.epochsBehindCurrent);
        assertEquals(currentEpoch + 1, future.epochNumber);
        assertFalse(future.finalized);
        assertEquals(-1, future.epochsBehindCurrent);
        assertEquals(finalizedEpoch, latest.epochNumber);
        assertTrue(latest.finalized);
    }

    @Test
    public void testBackgroundPollingIsIdempotentAndStopClearsExecutor() throws Exception {
        BeaconChainClient client = clientWithFinalizedEpoch(BlockchainConfig.Mode.MOCK, 300L);

        try {
            client.startBackgroundPolling();
            ScheduledExecutorService first = readExecutor(client);
            assertNotNull(first);

            client.startBackgroundPolling();
            ScheduledExecutorService second = readExecutor(client);
            assertSame(first, second);
        } finally {
            client.stopBackgroundPolling();
        }

        assertNull(readExecutor(client));
    }

    private void clearProps() {
        System.clearProperty(PROP_MODE);
    }

    private static BeaconChainClient clientWithFinalizedEpoch(BlockchainConfig.Mode mode, long finalizedEpoch) {
        return new BeaconChainClient(mode, endpoint -> {
            if (endpoint.endsWith("/epoch/finalized")) {
                return "{\"status\":\"OK\",\"data\":{\"epoch\":" + finalizedEpoch + "}}";
            }
            if (endpoint.endsWith("/epoch/latest")) {
                return "{\"status\":\"OK\",\"data\":{\"epoch\":" + (finalizedEpoch + 2L) + "}}";
            }
            throw new IllegalArgumentException("Unexpected endpoint: " + endpoint);
        });
    }

    private static void setField(Object target, String fieldName, long value) throws Exception {
        Field field = BeaconChainClient.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setLong(target, value);
    }

    private static ScheduledExecutorService readExecutor(BeaconChainClient client) throws Exception {
        Field field = BeaconChainClient.class.getDeclaredField("pollingExecutor");
        field.setAccessible(true);
        return (ScheduledExecutorService) field.get(client);
    }
}
