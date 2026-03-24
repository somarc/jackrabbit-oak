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
package org.apache.jackrabbit.oak.segment.consensus.aeron;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import io.aeron.Publication;
import io.aeron.cluster.client.AeronCluster;

import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.IdleStrategy;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

public class AeronWriteClientTest {

    @Test
    public void connectRetriesBeforeSuccess() {
        AtomicInteger attempts = new AtomicInteger();
        List<Long> sleeps = new ArrayList<>();
        RecordingIdleStrategy idleStrategy = new RecordingIdleStrategy();
        AeronWriteClient client = new AeronWriteClient(
            7,
            "aeron-dir",
            Collections.singletonList("self"),
            0,
            "self",
            () -> new AeronIngressEndpointPlanner.Plan("0=127.0.0.1:1234", "127.0.0.1"),
            (plan, strategy) -> {
                if (attempts.incrementAndGet() < 3) {
                    throw new RuntimeException("boom");
                }
                return new FakeClusterClient(17L);
            },
            idleStrategy,
            sleeps::add,
            3,
            1000L
        );

        client.connect();

        assertTrue(client.isConnected());
        assertEquals(3, attempts.get());
        assertEquals(List.of(1000L, 2000L), sleeps);
        assertEquals(0, idleStrategy.resetCalls);
    }

    @Test
    public void defaultConstructorCreatesDisconnectedClientAndCloseIsNoOp() {
        AeronWriteClient client = new AeronWriteClient(
            7,
            "aeron-dir",
            Collections.singletonList("self"),
            0,
            "self"
        );

        assertFalse(client.isConnected());
        client.close();
        assertFalse(client.isConnected());
    }

    @Test
    public void connectReturnsImmediatelyWhenAlreadyConnected() {
        AtomicInteger attempts = new AtomicInteger();
        AeronWriteClient client = new AeronWriteClient(
            7,
            "aeron-dir",
            Collections.singletonList("self"),
            0,
            "self",
            () -> new AeronIngressEndpointPlanner.Plan("0=127.0.0.1:1234", "127.0.0.1"),
            (plan, strategy) -> {
                attempts.incrementAndGet();
                return new FakeClusterClient(17L);
            },
            new RecordingIdleStrategy(),
            millis -> { },
            3,
            1000L
        );

        client.connect();
        client.connect();

        assertEquals(1, attempts.get());
        assertTrue(client.isConnected());
    }

    @Test
    public void connectFailsAfterConfiguredRetries() {
        List<Long> sleeps = new ArrayList<>();
        AeronWriteClient client = new AeronWriteClient(
            7,
            "aeron-dir",
            Collections.singletonList("self"),
            0,
            "self",
            () -> new AeronIngressEndpointPlanner.Plan("0=127.0.0.1:1234", "127.0.0.1"),
            (plan, strategy) -> {
                throw new RuntimeException("boom");
            },
            new RecordingIdleStrategy(),
            sleeps::add,
            2,
            1000L
        );

        boolean failed = false;
        try {
            client.connect();
        } catch (RuntimeException e) {
            failed = true;
            assertEquals("Failed to connect to Aeron cluster", e.getMessage());
        }

        assertTrue(failed);
        assertFalse(client.isConnected());
        assertEquals(List.of(1000L), sleeps);
    }

    @Test
    public void connectPropagatesInterruptedSleep() {
        AeronWriteClient client = new AeronWriteClient(
            7,
            "aeron-dir",
            Collections.singletonList("self"),
            0,
            "self",
            () -> new AeronIngressEndpointPlanner.Plan("0=127.0.0.1:1234", "127.0.0.1"),
            (plan, strategy) -> {
                throw new RuntimeException("boom");
            },
            new RecordingIdleStrategy(),
            millis -> {
                throw new InterruptedException("stop");
            },
            3,
            1000L
        );

        boolean failed = false;
        try {
            client.connect();
        } catch (RuntimeException e) {
            failed = true;
            assertEquals("Connection interrupted", e.getMessage());
        }

        assertTrue(failed);
        assertTrue(Thread.interrupted());
    }

    @Test
    public void offerConnectsOnDemandAndRetriesBackpressure() {
        RecordingIdleStrategy idleStrategy = new RecordingIdleStrategy();
        FakeClusterClient clusterClient = new FakeClusterClient(Publication.BACK_PRESSURED, 42L);
        AeronWriteClient client = new AeronWriteClient(
            7,
            "aeron-dir",
            Collections.singletonList("self"),
            0,
            "self",
            () -> new AeronIngressEndpointPlanner.Plan("0=127.0.0.1:1234", "127.0.0.1"),
            (plan, strategy) -> clusterClient,
            idleStrategy,
            millis -> { },
            1,
            1000L
        );

        long result = client.offer(mock(MutableDirectBuffer.class), 0, 16);

        assertEquals(42L, result);
        assertTrue(client.isConnected());
        assertEquals(1, idleStrategy.resetCalls);
        assertEquals(1, idleStrategy.idleCalls);
    }

    @Test
    public void offerReturnsNotConnectedWhenConnectFails() {
        RecordingIdleStrategy idleStrategy = new RecordingIdleStrategy();
        AeronWriteClient client = new AeronWriteClient(
            7,
            "aeron-dir",
            Collections.singletonList("self"),
            0,
            "self",
            () -> new AeronIngressEndpointPlanner.Plan("0=127.0.0.1:1234", "127.0.0.1"),
            (plan, strategy) -> {
                throw new RuntimeException("boom");
            },
            idleStrategy,
            millis -> { },
            1,
            1000L
        );

        long result = client.offer(mock(MutableDirectBuffer.class), 0, 16);

        assertEquals(Publication.NOT_CONNECTED, result);
        assertFalse(client.isConnected());
        assertEquals(0, idleStrategy.resetCalls);
    }

    @Test
    public void offerReturnsNotConnectedAfterRetryExhaustion() {
        RecordingIdleStrategy idleStrategy = new RecordingIdleStrategy();
        long[] results = new long[101];
        Arrays.fill(results, Publication.NOT_CONNECTED);
        FakeClusterClient clusterClient = new FakeClusterClient(results);
        AeronWriteClient client = new AeronWriteClient(
            7,
            "aeron-dir",
            Collections.singletonList("self"),
            0,
            "self",
            () -> new AeronIngressEndpointPlanner.Plan("0=127.0.0.1:1234", "127.0.0.1"),
            (plan, strategy) -> clusterClient,
            idleStrategy,
            millis -> { },
            1,
            1000L
        );

        long result = client.offer(mock(MutableDirectBuffer.class), 0, 16);

        assertEquals(Publication.NOT_CONNECTED, result);
        assertEquals(1, idleStrategy.resetCalls);
        assertEquals(101, idleStrategy.idleCalls);
    }

    @Test
    public void offerReturnsUnexpectedNegativeResultWithoutRetry() {
        RecordingIdleStrategy idleStrategy = new RecordingIdleStrategy();
        FakeClusterClient clusterClient = new FakeClusterClient(-55L);
        AeronWriteClient client = new AeronWriteClient(
            7,
            "aeron-dir",
            Collections.singletonList("self"),
            0,
            "self",
            () -> new AeronIngressEndpointPlanner.Plan("0=127.0.0.1:1234", "127.0.0.1"),
            (plan, strategy) -> clusterClient,
            idleStrategy,
            millis -> { },
            1,
            1000L
        );

        long result = client.offer(mock(MutableDirectBuffer.class), 0, 16);

        assertEquals(-55L, result);
        assertEquals(1, idleStrategy.resetCalls);
        assertEquals(0, idleStrategy.idleCalls);
    }

    @Test
    public void offerReturnsBackPressureWhenRetryBudgetIsExhausted() {
        RecordingIdleStrategy idleStrategy = new RecordingIdleStrategy();
        long[] results = new long[101];
        Arrays.fill(results, Publication.BACK_PRESSURED);
        FakeClusterClient clusterClient = new FakeClusterClient(results);
        AeronWriteClient client = new AeronWriteClient(
            7,
            "aeron-dir",
            Collections.singletonList("self"),
            0,
            "self",
            () -> new AeronIngressEndpointPlanner.Plan("0=127.0.0.1:1234", "127.0.0.1"),
            (plan, strategy) -> clusterClient,
            idleStrategy,
            millis -> { },
            1,
            1000L
        );

        long result = client.offer(mock(MutableDirectBuffer.class), 0, 16);

        assertEquals(Publication.BACK_PRESSURED, result);
        assertEquals(1, idleStrategy.resetCalls);
        assertEquals(101, idleStrategy.idleCalls);
    }

    @Test
    public void closeUpdatesConnectionStateAndSwallowsCloseErrors() {
        AeronWriteClient client = new AeronWriteClient(
            7,
            "aeron-dir",
            Collections.singletonList("self"),
            0,
            "self",
            () -> new AeronIngressEndpointPlanner.Plan("0=127.0.0.1:1234", "127.0.0.1"),
            (plan, strategy) -> new FakeClusterClient(17L),
            new RecordingIdleStrategy(),
            millis -> { },
            1,
            1000L
        );
        client.connect();

        client.close();
        assertFalse(client.isConnected());

        AeronWriteClient failingClient = new AeronWriteClient(
            7,
            "aeron-dir",
            Collections.singletonList("self"),
            0,
            "self",
            () -> new AeronIngressEndpointPlanner.Plan("0=127.0.0.1:1234", "127.0.0.1"),
            (plan, strategy) -> new FailingCloseClusterClient(),
            new RecordingIdleStrategy(),
            millis -> { },
            1,
            1000L
        );
        failingClient.connect();
        failingClient.close();

        assertTrue(failingClient.isConnected());
    }

    @Test
    public void aeronClusterHandleDelegatesOfferAndClose() throws Exception {
        AeronCluster cluster = mock(AeronCluster.class);
        Constructor<?> ctor = Class.forName(
            "org.apache.jackrabbit.oak.segment.consensus.aeron.AeronWriteClient$AeronClusterHandle"
        ).getDeclaredConstructor(AeronCluster.class);
        ctor.setAccessible(true);
        AeronWriteClient.ClusterClient handle = (AeronWriteClient.ClusterClient) ctor.newInstance(cluster);

        handle.offer(mock(MutableDirectBuffer.class), 0, 16);
        handle.close();

        verify(cluster).offer(org.mockito.ArgumentMatchers.any(MutableDirectBuffer.class), org.mockito.ArgumentMatchers.eq(0), org.mockito.ArgumentMatchers.eq(16));
        verify(cluster).close();
    }

    private static final class RecordingIdleStrategy implements IdleStrategy {
        private int idleCalls;
        private int resetCalls;

        @Override
        public void idle(int workCount) {
            if (workCount <= 0) {
                idleCalls++;
            }
        }

        @Override
        public void idle() {
            idleCalls++;
        }

        @Override
        public void reset() {
            resetCalls++;
        }
    }

    private static final class FakeClusterClient implements AeronWriteClient.ClusterClient {
        private final long[] results;
        private int index;

        private FakeClusterClient(long... results) {
            this.results = results;
        }

        @Override
        public long offer(MutableDirectBuffer buffer, int offset, int length) {
            long result = results[Math.min(index, results.length - 1)];
            index++;
            return result;
        }

        @Override
        public void close() {
        }
    }

    private static final class FailingCloseClusterClient implements AeronWriteClient.ClusterClient {
        @Override
        public long offer(MutableDirectBuffer buffer, int offset, int length) {
            return 1L;
        }

        @Override
        public void close() {
            throw new IllegalStateException("boom");
        }
    }
}
