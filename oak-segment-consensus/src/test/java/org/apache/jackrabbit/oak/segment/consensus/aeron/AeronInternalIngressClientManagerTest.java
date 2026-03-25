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

import io.aeron.cluster.client.AeronCluster;
import io.aeron.cluster.codecs.CloseReason;
import org.agrona.concurrent.IdleStrategy;
import org.junit.Test;

import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class AeronInternalIngressClientManagerTest {

    @Test
    public void foreignTimeoutDoesNotTriggerReconnect() throws Exception {
        AtomicInteger connectCalls = new AtomicInteger();
        AtomicReference<AeronCluster> current = new AtomicReference<>();
        AeronCluster client = mockBoundClient(77L, null, null);

        AeronInternalIngressClientManager manager = newManager(
            connectCalls,
            current,
            () -> client,
            25L,
            10L
        );
        try {
            assertTrue(manager.ensureAvailable("initial", 250L));
            assertEquals(1, connectCalls.get());

            manager.handleClusterSessionClose(999L, CloseReason.TIMEOUT);
            Thread.sleep(40L);

            assertEquals(1, connectCalls.get());
            assertEquals("TIMEOUT", manager.diagnostics().get("lastCloseReason"));
        } finally {
            manager.close();
        }
    }

    @Test
    public void ownedTimeoutAndDuplicateRebindRequestsCollapseToSingleReconnect() throws Exception {
        AtomicInteger connectCalls = new AtomicInteger();
        AtomicReference<AeronCluster> current = new AtomicReference<>();
        AeronCluster initial = mockBoundClient(77L, null, null);
        AeronCluster rebound = mockBoundClient(88L, null, null);
        AtomicInteger connectIndex = new AtomicInteger();

        AeronInternalIngressClientManager manager = newManager(
            connectCalls,
            current,
            () -> connectIndex.getAndIncrement() == 0 ? initial : rebound,
            15L,
            10L
        );
        try {
            assertTrue(manager.ensureAvailable("initial", 250L));
            assertEquals(initial, current.get());

            manager.handleClusterSessionClose(77L, CloseReason.TIMEOUT);
            manager.requestRebind("duplicate-one");
            manager.requestRebind("duplicate-two");

            assertTrue(waitUntil(() -> current.get() == rebound, 1000L));
            assertEquals(2, connectCalls.get());
            assertEquals(88L, ((Number) manager.diagnostics().get("sessionId")).longValue());
        } finally {
            manager.close();
        }
    }

    @Test
    public void sessionLimitFailureEntersCooldownWithoutTightLoop() throws Exception {
        AtomicInteger connectCalls = new AtomicInteger();
        AtomicReference<AeronCluster> current = new AtomicReference<>();

        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        AeronInternalIngressClientManager manager = new AeronInternalIngressClientManager(
            () -> new AeronInternalClusterClientConnector(
                (aeronDirectoryName, ingressPlan, idleStrategy) -> {
                    connectCalls.incrementAndGet();
                    throw new RuntimeException("ERROR - concurrent session limit");
                },
                Thread::sleep,
                1,
                attempt -> 0L
            ),
            AeronIngressEndpointPlanner.systemFromUrls("http://self:8080", java.util.List.of("http://peer:8082")),
            () -> "target/aeron-dir",
            () -> mock(IdleStrategy.class),
            current::get,
            current::set,
            executor,
            System::currentTimeMillis,
            25L,
            10L,
            10L,
            80L,
            5L
        );
        try {
            manager.ensureAvailable("limit", 0L);

            assertTrue(waitUntil(() -> "COOLDOWN".equals(manager.diagnostics().get("state")), 500L));
            assertEquals(1, connectCalls.get());
            Thread.sleep(30L);
            assertEquals(1, connectCalls.get());
            assertNotNull(manager.diagnostics().get("cooldownUntil"));
        } finally {
            manager.close();
            executor.shutdownNow();
        }
    }

    @Test
    public void boundClientIsServicedWithPollAndKeepAlive() throws Exception {
        AtomicInteger connectCalls = new AtomicInteger();
        AtomicReference<AeronCluster> current = new AtomicReference<>();
        AtomicInteger polls = new AtomicInteger();
        AtomicInteger keepAlives = new AtomicInteger();
        AeronCluster client = mockBoundClient(55L, polls, keepAlives);

        AeronInternalIngressClientManager manager = newManager(
            connectCalls,
            current,
            () -> client,
            20L,
            10L
        );
        try {
            assertTrue(manager.ensureAvailable("initial", 250L));
            assertTrue(waitUntil(() -> polls.get() > 0, 500L));
            assertTrue(waitUntil(() -> keepAlives.get() > 0, 500L));

            Map<String, Object> diagnostics = manager.diagnostics();
            assertEquals("BOUND", diagnostics.get("state"));
            assertEquals(55L, ((Number) diagnostics.get("sessionId")).longValue());
            assertEquals(1, connectCalls.get());
        } finally {
            manager.close();
        }
    }

    private static AeronInternalIngressClientManager newManager(AtomicInteger connectCalls,
                                                                AtomicReference<AeronCluster> current,
                                                                java.util.function.Supplier<AeronCluster> connectResultSupplier,
                                                                long keepAliveIntervalMs,
                                                                long pollIntervalMs) {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        return new AeronInternalIngressClientManager(
            () -> new AeronInternalClusterClientConnector(
                (aeronDirectoryName, ingressPlan, idleStrategy) -> {
                    connectCalls.incrementAndGet();
                    return connectResultSupplier.get();
                },
                Thread::sleep,
                1,
                attempt -> 0L
            ),
            AeronIngressEndpointPlanner.systemFromUrls("http://self:8080", java.util.List.of("http://peer:8082")),
            () -> "target/aeron-dir",
            () -> mock(IdleStrategy.class),
            current::get,
            current::set,
            executor,
            System::currentTimeMillis,
            keepAliveIntervalMs,
            pollIntervalMs,
            10L,
            80L,
            5L
        );
    }

    private static AeronCluster mockBoundClient(long sessionId,
                                                AtomicInteger polls,
                                                AtomicInteger keepAlives) {
        AeronCluster client = mock(AeronCluster.class);
        when(client.isClosed()).thenReturn(false);
        when(client.clusterSessionId()).thenReturn(sessionId);
        if (polls != null) {
            doAnswer(invocation -> {
                polls.incrementAndGet();
                return 0;
            }).when(client).pollEgress();
        }
        if (keepAlives != null) {
            when(client.sendKeepAlive()).thenAnswer(invocation -> {
                keepAlives.incrementAndGet();
                return true;
            });
        } else {
            when(client.sendKeepAlive()).thenReturn(true);
        }
        return client;
    }

    private static boolean waitUntil(java.util.concurrent.Callable<Boolean> condition, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (Boolean.TRUE.equals(condition.call())) {
                return true;
            }
            Thread.sleep(10L);
        }
        return Boolean.TRUE.equals(condition.call());
    }
}
