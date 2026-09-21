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
import org.agrona.concurrent.IdleStrategy;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class AeronInternalClusterClientConnectorTest {

    @Test
    public void ensureConnectedReturnsExistingHealthyClient() {
        AeronCluster existing = mock(AeronCluster.class);
        when(existing.isClosed()).thenReturn(false);
        AtomicInteger connections = new AtomicInteger(0);
        AeronInternalClusterClientConnector connector = new AeronInternalClusterClientConnector(
            (directory, ingressPlan, idleStrategy) -> {
                connections.incrementAndGet();
                return mock(AeronCluster.class);
            },
            millis -> { },
            3,
            attempt -> 5L
        );

        AeronCluster resolved = connector.ensureConnected(existing, "aeron-dir", plan(), mock(IdleStrategy.class));

        assertSame(existing, resolved);
        assertEquals(0, connections.get());
    }

    @Test
    public void ensureConnectedClosesStaleClientAndReconnects() {
        AeronCluster stale = mock(AeronCluster.class);
        when(stale.isClosed()).thenReturn(true);
        AeronCluster replacement = mock(AeronCluster.class);
        AeronInternalClusterClientConnector connector = new AeronInternalClusterClientConnector(
            (directory, ingressPlan, idleStrategy) -> replacement,
            millis -> { },
            3,
            attempt -> 5L
        );

        AeronCluster resolved = connector.ensureConnected(stale, "aeron-dir", plan(), mock(IdleStrategy.class));

        verify(stale).close();
        assertSame(replacement, resolved);
    }

    @Test
    public void ensureConnectedRetriesUntilConnectionSucceeds() {
        AtomicInteger attempts = new AtomicInteger(0);
        List<Long> sleeps = new ArrayList<>();
        AeronCluster replacement = mock(AeronCluster.class);
        AeronInternalClusterClientConnector connector = new AeronInternalClusterClientConnector(
            (directory, ingressPlan, idleStrategy) -> {
                if (attempts.incrementAndGet() < 3) {
                    throw new IllegalStateException("not ready");
                }
                return replacement;
            },
            sleeps::add,
            4,
            attempt -> attempt * 10L
        );

        AeronCluster resolved = connector.ensureConnected(null, "aeron-dir", plan(), mock(IdleStrategy.class));

        assertSame(replacement, resolved);
        assertEquals(3, attempts.get());
        assertEquals(List.of(10L, 20L), sleeps);
    }

    @Test
    public void ensureConnectedReturnsNullWhenRetriesExhausted() {
        AtomicInteger attempts = new AtomicInteger(0);
        List<Long> sleeps = new ArrayList<>();
        AeronInternalClusterClientConnector connector = new AeronInternalClusterClientConnector(
            (directory, ingressPlan, idleStrategy) -> {
                attempts.incrementAndGet();
                throw new IllegalStateException("still unavailable");
            },
            sleeps::add,
            3,
            attempt -> 7L
        );

        AeronCluster resolved = connector.ensureConnected(null, "aeron-dir", plan(), mock(IdleStrategy.class));

        assertNull(resolved);
        assertEquals(3, attempts.get());
        assertEquals(List.of(7L, 7L), sleeps);
    }

    private static AeronIngressEndpointPlanner.Plan plan() {
        return new AeronIngressEndpointPlanner.Plan("0=host:9002", "127.0.0.1");
    }
}
