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
    private static final String PROP_MOCK_EPOCH_DURATION_SECONDS = "oak.mock.epoch.duration.seconds";

    @Before
    public void setUp() {
        clearProps();
        System.setProperty(PROP_MODE, "mock");
        BlockchainConfig.reset();
    }

    @After
    public void tearDown() {
        clearProps();
        BlockchainConfig.reset();
    }

    @Test
    public void testConstructsInMockModeAndReportsHealth() {
        BeaconChainClient client = new BeaconChainClient("ignored");

        Map<String, Object> health = client.getHealthStatus();

        assertEquals(BlockchainConfig.Mode.MOCK, client.getNetworkMode());
        assertTrue(client.getCachedCurrentEpoch() >= 1000L);
        assertEquals(client.getCachedCurrentEpoch() - 2L, client.getCachedFinalizedEpoch());
        assertTrue(client.isEpochDataFresh());
        assertEquals("MOCK", health.get("mode"));
        assertNull(health.get("apiUrl"));
        assertEquals(0L, ((Number) health.get("mockEpochOffset")).longValue());
        assertEquals(300000L, ((Number) health.get("mockEpochDurationMs")).longValue());
    }

    @Test
    public void testSetAndAdvanceMockEpochUpdateCacheImmediately() {
        BeaconChainClient client = new BeaconChainClient("ignored");
        long initialCurrent = client.getCachedCurrentEpoch();
        long initialFinalized = client.getCachedFinalizedEpoch();

        assertTrue(client.setMockEpochOffset(42L));
        assertEquals(42L, client.getMockEpochOffset());
        assertEquals(initialCurrent + 42L, client.getCachedCurrentEpoch());
        assertEquals(initialFinalized + 42L, client.getCachedFinalizedEpoch());

        assertTrue(client.advanceMockEpoch(3));
        assertEquals(45L, client.getMockEpochOffset());
        assertEquals(initialCurrent + 45L, client.getCachedCurrentEpoch());
        assertEquals(initialFinalized + 45L, client.getCachedFinalizedEpoch());
    }

    @Test
    public void testMockEpochDurationPropertyIsHonoredAndInvalidValuesFallBack() {
        System.setProperty(PROP_MOCK_EPOCH_DURATION_SECONDS, "7");
        BlockchainConfig.reset();
        BeaconChainClient configured = new BeaconChainClient("ignored");
        assertEquals(7000L, ((Number) configured.getHealthStatus().get("mockEpochDurationMs")).longValue());

        System.setProperty(PROP_MOCK_EPOCH_DURATION_SECONDS, "0");
        BlockchainConfig.reset();
        BeaconChainClient zero = new BeaconChainClient("ignored");
        assertEquals(300000L, ((Number) zero.getHealthStatus().get("mockEpochDurationMs")).longValue());

        System.setProperty(PROP_MOCK_EPOCH_DURATION_SECONDS, "bogus");
        BlockchainConfig.reset();
        BeaconChainClient bogus = new BeaconChainClient("ignored");
        assertEquals(300000L, ((Number) bogus.getHealthStatus().get("mockEpochDurationMs")).longValue());
    }

    @Test
    public void testStaleEpochDataDetectedAndFreshnessCheckThrows() throws Exception {
        BeaconChainClient client = new BeaconChainClient("ignored");

        setField(client, "lastUpdateTime", System.currentTimeMillis() - 61000L);

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
        BeaconChainClient client = new BeaconChainClient("ignored");
        Method method = BeaconChainClient.class.getDeclaredMethod("parseEpochFromResponse", String.class);
        method.setAccessible(true);

        assertEquals(12345L, ((Long) method.invoke(client, "{\"status\":\"OK\",\"data\":{\"epoch\":12345}}")).longValue());
        assertEquals(12345L, ((Long) method.invoke(client, "{\"status\":\"OK\",\"data\":12345}")).longValue());
        assertEquals(-1L, ((Long) method.invoke(client, "{\"status\":\"OK\",\"data\":{\"finalized\":12345}}")).longValue());
        assertEquals(-1L, ((Long) method.invoke(client, "{\"status\":\"OK\",\"data\":oops}")).longValue());
    }

    @Test
    public void testEpochDetailsAndLatestFinalizedEpochReflectCachedState() throws Exception {
        BeaconChainClient client = new BeaconChainClient("ignored");
        client.setMockEpochOffset(5);

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
        BeaconChainClient client = new BeaconChainClient("ignored");

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
        System.clearProperty(PROP_MOCK_EPOCH_DURATION_SECONDS);
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
