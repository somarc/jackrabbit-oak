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

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.apache.jackrabbit.oak.segment.consensus.aeron.AeronClusterLauncher;
import org.apache.jackrabbit.oak.segment.consensus.aeron.AeronPrometheusMetrics;
import org.apache.jackrabbit.oak.segment.consensus.aeron.AeronClusterService;
import org.apache.jackrabbit.oak.segment.consensus.bootstrap.ValidatorBootstrap;
import org.apache.jackrabbit.oak.segment.consensus.eth.BeaconChainClient;
import org.apache.jackrabbit.oak.segment.consensus.eth.EpochListener;
import org.apache.jackrabbit.oak.segment.consensus.evm.EvmBridge;
import org.apache.jackrabbit.oak.segment.consensus.gc.GCProposalManager;
import org.apache.jackrabbit.oak.segment.consensus.gc.PeriodicGCJob;
import org.apache.jackrabbit.oak.segment.consensus.queue.ProposalQueueManagerOptimized;
import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.apache.jackrabbit.oak.segment.http.server.ServerContext;
import org.apache.jackrabbit.oak.segment.http.server.SegmentHttpServer;
import org.apache.jackrabbit.oak.spi.state.NodeStore;
import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

public class GlobalStoreServerLifecycleTest {

    @Test
    public void testStartDelegatesToRuntimeThenWaitPhase() throws Exception {
        RecordingServer server = new RecordingServer();

        server.start();

        assertEquals(Arrays.asList("startRuntime", "awaitStop"), server.calls);
    }

    @Test
    public void testStartSkipsWaitPhaseWhenRuntimeStartFails() throws Exception {
        RecordingServer server = new RecordingServer();
        server.failOnStart = true;

        try {
            server.start();
            fail("Expected IOException");
        } catch (IOException e) {
            assertEquals("boom", e.getMessage());
        }

        assertEquals(Arrays.asList("startRuntime"), server.calls);
    }

    @Test
    public void testStopShutsDownManagedComponents() throws Exception {
        GlobalStoreServer server = new GlobalStoreServer(8090, "/tmp/test-store");
        ValidatorBootstrap bootstrap = mock(ValidatorBootstrap.class);
        AeronClusterService aeronClusterService = mock(AeronClusterService.class);
        EpochListener epochListener = mock(EpochListener.class);
        SegmentHttpServer httpServer = mock(SegmentHttpServer.class);
        FileStore fileStore = mock(FileStore.class);

        setField(server, "bootstrap", bootstrap);
        setField(server, "aeronClusterService", aeronClusterService);
        setField(server, "epochListener", epochListener);
        setField(server, "httpServer", httpServer);
        setField(server, "fileStore", fileStore);

        ListAppender<ILoggingEvent> appender = TestLogAppenderSupport.attach(GlobalStoreServer.class);
        try {
            server.stop();

            verify(bootstrap).shutdown();
            verify(aeronClusterService).shutdown();
            verify(epochListener).stop();
            verify(httpServer).stop();
            verify(fileStore).close();
            assertTrue(TestLogAppenderSupport.contains(appender, "Shutting down global store server"));
            assertTrue(TestLogAppenderSupport.contains(appender, "Bootstrap services stopped"));
        } finally {
            TestLogAppenderSupport.detach(GlobalStoreServer.class, appender);
        }
    }

    @Test
    public void testStopUsesLauncherFallbackWhenServiceIsAbsent() throws Exception {
        GlobalStoreServer server = new GlobalStoreServer(8090, "/tmp/test-store");
        AeronClusterLauncher launcher = mock(AeronClusterLauncher.class);

        setField(server, "aeronClusterLauncher", launcher);
        setField(server, "fileStore", mock(FileStore.class));

        server.stop();

        verify(launcher).shutdown();
    }

    @Test
    public void testStopDoesNotUseLauncherWhenServiceIsPresent() throws Exception {
        GlobalStoreServer server = new GlobalStoreServer(8090, "/tmp/test-store");
        AeronClusterService aeronClusterService = mock(AeronClusterService.class);
        AeronClusterLauncher launcher = mock(AeronClusterLauncher.class);

        setField(server, "aeronClusterService", aeronClusterService);
        setField(server, "aeronClusterLauncher", launcher);
        setField(server, "fileStore", mock(FileStore.class));

        server.stop();

        verify(aeronClusterService).shutdown();
        verifyNoInteractions(launcher);
    }

    @Test
    public void testStopShutsDownManagedContextComponents() throws Exception {
        GlobalStoreServer server = new GlobalStoreServer(8090, "/tmp/test-store");
        SegmentHttpServer httpServer = mock(SegmentHttpServer.class);
        FileStore fileStore = mock(FileStore.class);
        ProposalQueueManagerOptimized proposalQueueManager = mock(ProposalQueueManagerOptimized.class);
        BeaconChainClient beaconChainClient = mock(BeaconChainClient.class);
        EvmBridge evmBridge = mock(EvmBridge.class);
        GCProposalManager gcProposalManager = mock(GCProposalManager.class);
        PeriodicGCJob periodicGCJob = mock(PeriodicGCJob.class);
        AeronPrometheusMetrics aeronPrometheusMetrics = mock(AeronPrometheusMetrics.class);
        ServerContext context = new ServerContext(fileStore, mock(NodeStore.class), Paths.get("/tmp/test-store"), "http://self:8090");
        context.proposalQueueManager = proposalQueueManager;
        context.evmBridge = evmBridge;
        context.gcProposalManager = gcProposalManager;
        context.periodicGCJob = periodicGCJob;
        context.aeronPrometheusMetrics = aeronPrometheusMetrics;
        when(proposalQueueManager.getBeaconClient()).thenReturn(beaconChainClient);
        when(httpServer.getContext()).thenReturn(context);

        setField(server, "httpServer", httpServer);
        setField(server, "fileStore", fileStore);

        server.stop();

        verify(proposalQueueManager).stop();
        verify(beaconChainClient).stopBackgroundPolling();
        verify(evmBridge).stop();
        verify(gcProposalManager).shutdown();
        verify(periodicGCJob).stop();
        verify(aeronPrometheusMetrics).close();
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = GlobalStoreServer.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static final class RecordingServer extends GlobalStoreServer {
        private final List<String> calls = new ArrayList<>();
        private boolean failOnStart;

        private RecordingServer() {
            super(8090, "/tmp/test-store");
        }

        @Override
        protected void startRuntime() throws IOException {
            calls.add("startRuntime");
            if (failOnStart) {
                throw new IOException("boom");
            }
        }

        @Override
        protected void awaitStop() {
            calls.add("awaitStop");
        }
    }
}
