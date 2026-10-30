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

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class AeronInternalClusterClientConnectorTest {

    @Test
    public void connectOnceReturnsExistingHealthyClient() {
        AeronCluster existing = mock(AeronCluster.class);
        when(existing.isClosed()).thenReturn(false);
        AtomicInteger connections = new AtomicInteger(0);
        AeronInternalClusterClientConnector connector = new AeronInternalClusterClientConnector(
            (directory, ingressPlan, idleStrategy) -> {
                connections.incrementAndGet();
                return mock(AeronCluster.class);
            }
        );

        AeronInternalClusterClientConnector.ConnectAttemptResult result =
            connector.connectOnce(existing, "aeron-dir", plan(), mock(IdleStrategy.class));

        assertTrue(result.isSuccess());
        assertSame(existing, result.client);
        assertEquals(0, connections.get());
    }

    @Test
    public void connectOnceClosesStaleClientAndReconnects() {
        AeronCluster stale = mock(AeronCluster.class);
        when(stale.isClosed()).thenReturn(true);
        AeronCluster replacement = mock(AeronCluster.class);
        AeronInternalClusterClientConnector connector = new AeronInternalClusterClientConnector(
            (directory, ingressPlan, idleStrategy) -> replacement
        );

        AeronInternalClusterClientConnector.ConnectAttemptResult result =
            connector.connectOnce(stale, "aeron-dir", plan(), mock(IdleStrategy.class));

        verify(stale).close();
        assertSame(replacement, result.client);
    }

    private static AeronIngressEndpointPlanner.Plan plan() {
        return new AeronIngressEndpointPlanner.Plan("0=host:9002", "127.0.0.1");
    }
}
