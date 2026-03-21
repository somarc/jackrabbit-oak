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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import io.aeron.Publication;

import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.IdleStrategy;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

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
}
