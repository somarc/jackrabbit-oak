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
package org.apache.jackrabbit.oak.segment.consensus.server;

import java.nio.file.Paths;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.apache.jackrabbit.oak.segment.consensus.aeron.AeronClusterLauncher;
import org.apache.jackrabbit.oak.segment.consensus.aeron.AeronPrometheusMetrics;
import org.apache.jackrabbit.oak.segment.consensus.aeron.AeronWriteClient;
import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.apache.jackrabbit.oak.segment.http.server.SegmentHttpServer;
import org.apache.jackrabbit.oak.segment.http.server.ServerContext;
import org.apache.jackrabbit.oak.spi.state.NodeStore;
import org.junit.Test;
import org.mockito.InOrder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class AeronClusterRuntimeAttacherTest {

    @Test
    public void attachConnectsWriteClientAndPublishesItToHttpServer() {
        ServerContext context = new ServerContext(
            mock(FileStore.class),
            mock(NodeStore.class),
            Paths.get("target"),
            "http://node-a:8080"
        );
        context.aeronPrometheusMetrics = mock(AeronPrometheusMetrics.class);

        SegmentHttpServer httpServer = mock(SegmentHttpServer.class);
        when(httpServer.getContext()).thenReturn(context);

        AeronClusterLauncher launcher = mock(AeronClusterLauncher.class);
        when(launcher.getAeronDirectoryName()).thenReturn("aeron-dir");

        AeronWriteClient writeClient = mock(AeronWriteClient.class);
        AeronClusterRuntimeAttacher attacher = new AeronClusterRuntimeAttacher(
            (clientId, aeronDirectoryName, clusterHostnames, clusterBasePort, clientHostname) -> {
                assertEquals(0, clientId);
                assertEquals("aeron-dir", aeronDirectoryName);
                assertEquals(Arrays.asList("node-a", "node-b"), clusterHostnames);
                assertEquals(AeronClusterLauncher.getPortBase(), clusterBasePort);
                assertEquals("node-a", clientHostname);
                return writeClient;
            },
            mock(AeronClusterRuntimeAttacher.MetricsFactory.class),
            (task, delay, unit) -> {
                throw new AssertionError("scheduler should not run when metrics already exist");
            }
        );

        AeronWriteClient attachedClient =
            attacher.attach(httpServer, launcher, Arrays.asList("node-a", "node-b"), "node-a");

        assertSame(writeClient, attachedClient);
        InOrder inOrder = inOrder(httpServer, writeClient);
        inOrder.verify(httpServer).setAeronClusterLauncher(launcher);
        inOrder.verify(writeClient).connect();
        inOrder.verify(httpServer).setAeronWriteClient(writeClient);
    }

    @Test
    public void attachInitializesDelayedMetricsWhenAeronAvailable() {
        ServerContext context = new ServerContext(
            mock(FileStore.class),
            mock(NodeStore.class),
            Paths.get("target"),
            "http://node-a:8080"
        );
        SegmentHttpServer httpServer = mock(SegmentHttpServer.class);
        when(httpServer.getContext()).thenReturn(context);

        AeronClusterLauncher launcher = mock(AeronClusterLauncher.class);
        io.aeron.Aeron aeron = mock(io.aeron.Aeron.class);
        when(launcher.getAeronDirectoryName()).thenReturn("aeron-dir");
        when(launcher.getAeron()).thenReturn(aeron);

        AeronWriteClient writeClient = mock(AeronWriteClient.class);
        AeronPrometheusMetrics metrics = mock(AeronPrometheusMetrics.class);

        AtomicInteger scheduled = new AtomicInteger();
        AeronClusterRuntimeAttacher attacher = new AeronClusterRuntimeAttacher(
            (clientId, aeronDirectoryName, clusterHostnames, clusterBasePort, clientHostname) -> writeClient,
            ignored -> metrics,
            (task, delay, unit) -> {
                scheduled.incrementAndGet();
                assertEquals(5L, delay);
                assertSame(TimeUnit.SECONDS, unit);
                task.run();
            }
        );

        attacher.attach(httpServer, launcher, Arrays.asList("node-a"), "node-a");

        assertEquals(1, scheduled.get());
        assertSame(metrics, context.aeronPrometheusMetrics);
        InOrder inOrder = inOrder(httpServer, launcher);
        inOrder.verify(httpServer).setAeronClusterLauncher(launcher);
        inOrder.verify(launcher).getAeron();
    }

    @Test
    public void attachLeavesMetricsUnsetWhenAeronMissing() {
        ServerContext context = new ServerContext(
            mock(FileStore.class),
            mock(NodeStore.class),
            Paths.get("target"),
            "http://node-a:8080"
        );
        SegmentHttpServer httpServer = mock(SegmentHttpServer.class);
        when(httpServer.getContext()).thenReturn(context);

        AeronClusterLauncher launcher = mock(AeronClusterLauncher.class);
        when(launcher.getAeronDirectoryName()).thenReturn("aeron-dir");
        when(launcher.getAeron()).thenReturn(null);

        AeronWriteClient writeClient = mock(AeronWriteClient.class);
        AtomicInteger metricsFactoryCalls = new AtomicInteger();

        AeronClusterRuntimeAttacher attacher = new AeronClusterRuntimeAttacher(
            (clientId, aeronDirectoryName, clusterHostnames, clusterBasePort, clientHostname) -> writeClient,
            aeron -> {
                metricsFactoryCalls.incrementAndGet();
                return mock(AeronPrometheusMetrics.class);
            },
            (task, delay, unit) -> task.run()
        );

        attacher.attach(httpServer, launcher, Arrays.asList("node-a"), "node-a");

        assertNull(context.aeronPrometheusMetrics);
        assertEquals(0, metricsFactoryCalls.get());
    }

    @Test
    public void attachSwallowsWriteClientConnectFailuresButStillAttachesClient() {
        ServerContext context = new ServerContext(
            mock(FileStore.class),
            mock(NodeStore.class),
            Paths.get("target"),
            "http://node-a:8080"
        );
        context.aeronPrometheusMetrics = mock(AeronPrometheusMetrics.class);

        SegmentHttpServer httpServer = mock(SegmentHttpServer.class);
        when(httpServer.getContext()).thenReturn(context);

        AeronClusterLauncher launcher = mock(AeronClusterLauncher.class);
        when(launcher.getAeronDirectoryName()).thenReturn("aeron-dir");

        AeronWriteClient writeClient = mock(AeronWriteClient.class);
        doThrow(new RuntimeException("connect failed")).when(writeClient).connect();

        AeronClusterRuntimeAttacher attacher = new AeronClusterRuntimeAttacher(
            (clientId, aeronDirectoryName, clusterHostnames, clusterBasePort, clientHostname) -> writeClient,
            mock(AeronClusterRuntimeAttacher.MetricsFactory.class),
            (task, delay, unit) -> {
                throw new AssertionError("scheduler should not run when metrics already exist");
            }
        );

        ListAppender<ILoggingEvent> appender = TestLogAppenderSupport.attach(AeronClusterRuntimeAttacher.class);
        try {
            AeronWriteClient attachedClient =
                attacher.attach(httpServer, launcher, Arrays.asList("node-a"), "node-a");

            assertSame(writeClient, attachedClient);
            verify(httpServer).setAeronWriteClient(writeClient);
            assertTrue(context.aeronPrometheusMetrics != null);
            assertTrue(TestLogAppenderSupport.contains(appender, "Failed to connect AeronWriteClient"));
        } finally {
            TestLogAppenderSupport.detach(AeronClusterRuntimeAttacher.class, appender);
        }
    }
}
