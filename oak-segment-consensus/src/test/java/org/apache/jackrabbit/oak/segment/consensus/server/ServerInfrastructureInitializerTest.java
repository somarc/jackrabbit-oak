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

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.function.Supplier;

import org.apache.jackrabbit.oak.segment.RecordId;
import org.apache.jackrabbit.oak.segment.consensus.aeron.AeronClusterConfig;
import org.apache.jackrabbit.oak.segment.consensus.fragmentation.FragmentationTracker;
import org.apache.jackrabbit.oak.segment.consensus.gc.GCAccountManager;
import org.apache.jackrabbit.oak.segment.consensus.gc.GCCostEstimator;
import org.apache.jackrabbit.oak.segment.consensus.gc.GCProposalManager;
import org.apache.jackrabbit.oak.segment.consensus.gc.PeriodicGCJob;
import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.apache.jackrabbit.oak.segment.file.tar.TarFiles;
import org.apache.jackrabbit.oak.segment.http.server.SegmentHttpServer;
import org.apache.jackrabbit.oak.segment.http.server.ServerContext;
import org.apache.jackrabbit.oak.spi.blob.BlobStore;
import org.apache.jackrabbit.oak.spi.state.NodeStore;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.ArgumentCaptor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
public class ServerInfrastructureInitializerTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @After
    public void tearDown() {
        System.clearProperty("blobstore.type");
        System.clearProperty("gc.usdc.per.mb");
    }

    @Test
    public void testInitializeBuildsRuntimeAndWiresContext() throws Exception {
        System.setProperty("blobstore.type", "ipfs");
        System.setProperty("gc.usdc.per.mb", "0.25");

        Fixture fixture = newFixture();
        when(fixture.factory.createGCProposalManager(eq(fixture.fileStore), eq(fixture.gcCostEstimator),
            eq(fixture.fragmentationTracker), eq(null), eq(3), any(), any()))
            .thenReturn(fixture.gcProposalManager);

        ServerInfrastructureInitializer initializer = new ServerInfrastructureInitializer();
        ServerInfrastructureInitializer.InitializationResult result = initializer.initialize(
            tempFolder.getRoot(),
            8090,
            aeronConfig("http://validator-0:8090", new String[] {"http://validator-1:8090", "http://validator-2:8090"}),
            fixture.factory
        );

        assertSame(fixture.blobStore, result.getBlobStore());
        assertSame(fixture.fileStore, result.getFileStore());
        assertSame(fixture.nodeStore, result.getNodeStore());
        assertSame(fixture.httpServer, result.getHttpServer());
        assertSame(fixture.gcCostEstimator, result.getGcCostEstimator());
        assertEquals("ipfs", result.getBlobStoreType());
        assertEquals("http://validator-0:8090", fixture.context.selfUrl);
        assertSame(fixture.blobStore, fixture.context.blobStore);
        assertEquals("ipfs", fixture.context.blobStoreType);
        assertSame(fixture.gcProposalManager, fixture.context.gcProposalManager);
        assertSame(fixture.gcAccountManager, fixture.context.gcAccountManager);
        assertSame(fixture.periodicGCJob, fixture.context.periodicGCJob);

        ArgumentCaptor<Supplier<Integer>> executorCaptor = ArgumentCaptor.forClass(Supplier.class);
        ArgumentCaptor<Supplier<Boolean>> leaderCaptor = ArgumentCaptor.forClass(Supplier.class);
        verify(fixture.factory).createGCProposalManager(eq(fixture.fileStore), eq(fixture.gcCostEstimator),
            eq(fixture.fragmentationTracker), eq(null), eq(3), executorCaptor.capture(), leaderCaptor.capture());
        assertEquals(Integer.valueOf(0), executorCaptor.getValue().get());
        assertTrue(leaderCaptor.getValue().get());
        verify(fixture.periodicGCJob).start();
    }

    @Test
    public void testInitializeContinuesWhenCidMappingFails() throws Exception {
        System.setProperty("blobstore.type", "ipfs");

        Fixture fixture = newFixture();
        when(fixture.factory.createCidMappingService(tempFolder.getRoot().toPath()))
            .thenThrow(new RuntimeException("cid unavailable"));

        ServerInfrastructureInitializer initializer = new ServerInfrastructureInitializer();
        ServerInfrastructureInitializer.InitializationResult result = initializer.initialize(
            tempFolder.getRoot(),
            8090,
            aeronConfig("http://validator-0:8090", new String[0]),
            fixture.factory
        );

        assertSame(fixture.httpServer, result.getHttpServer());
        assertNull(fixture.context.cidMappingService);
        assertSame(fixture.gcProposalManager, fixture.context.gcProposalManager);
    }

    @Test
    public void testInitializeLeavesGcEstimatorUnsetWhenTarFilesExtractionFails() throws Exception {
        System.setProperty("blobstore.type", "ipfs");

        Fixture fixture = newFixture();
        when(fixture.factory.extractTarFiles(fixture.fileStore)).thenThrow(new RuntimeException("reflection blocked"));

        ServerInfrastructureInitializer initializer = new ServerInfrastructureInitializer();
        ServerInfrastructureInitializer.InitializationResult result = initializer.initialize(
            tempFolder.getRoot(),
            8090,
            aeronConfig("http://validator-0:8090", new String[0]),
            fixture.factory
        );

        assertNull(result.getGcCostEstimator());
        assertNull(fixture.context.gcCostEstimator);
        verify(fixture.factory, never()).createGCCostEstimator(any(), any(), any());
    }

    @Test
    public void testInitializeContinuesWhenGcConsensusSupportFails() throws Exception {
        System.setProperty("blobstore.type", "ipfs");

        Fixture fixture = newFixture();
        when(fixture.factory.createGCProposalManager(eq(fixture.fileStore), eq(fixture.gcCostEstimator),
            eq(fixture.fragmentationTracker), eq(null), eq(1), any(), any()))
            .thenThrow(new RuntimeException("gc unavailable"));

        ServerInfrastructureInitializer initializer = new ServerInfrastructureInitializer();
        ServerInfrastructureInitializer.InitializationResult result = initializer.initialize(
            tempFolder.getRoot(),
            8090,
            aeronConfig("http://validator-0:8090", new String[0]),
            fixture.factory
        );

        assertSame(fixture.httpServer, result.getHttpServer());
        assertNull(fixture.context.gcProposalManager);
        assertNull(fixture.context.gcAccountManager);
        assertNull(fixture.context.periodicGCJob);
        verify(fixture.periodicGCJob, never()).start();
    }

    private Fixture newFixture() throws Exception {
        FileStore fileStore = mock(FileStore.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        NodeStore nodeStore = mock(NodeStore.class);
        BlobStore blobStore = mock(BlobStore.class);
        TarFiles tarFiles = mock(TarFiles.class);
        GCCostEstimator gcCostEstimator = mock(GCCostEstimator.class);
        SegmentHttpServer httpServer = mock(SegmentHttpServer.class);
        FragmentationTracker fragmentationTracker = mock(FragmentationTracker.class);
        GCProposalManager gcProposalManager = mock(GCProposalManager.class);
        GCAccountManager gcAccountManager = mock(GCAccountManager.class);
        PeriodicGCJob periodicGCJob = mock(PeriodicGCJob.class);
        RecordId recordId = mock(RecordId.class);
        ServerContext context = new ServerContext(fileStore, nodeStore, tempFolder.getRoot().toPath(), "http://localhost:8090");
        GlobalStoreServerComponentFactory factory = mock(GlobalStoreServerComponentFactory.class);

        when(recordId.toString()).thenReturn("record-1");
        when(fileStore.getHead().getRecordId()).thenReturn(recordId);
        when(factory.createIpfsBlobStore(anyString(), eq(tempFolder.getRoot()))).thenReturn(blobStore);
        when(factory.createStorageRuntime(tempFolder.getRoot(), blobStore)).thenReturn(new ServerStorageRuntime(fileStore, nodeStore));
        when(factory.extractTarFiles(fileStore)).thenReturn(tarFiles);
        when(factory.createGCCostEstimator(eq(fileStore), eq(tarFiles), any(BigDecimal.class))).thenReturn(gcCostEstimator);
        when(factory.createHttpServer(tempFolder.getRoot(), 8090, fileStore, nodeStore)).thenReturn(httpServer);
        when(factory.createCidMappingService(tempFolder.getRoot().toPath())).thenReturn(
            mock(org.apache.jackrabbit.oak.segment.http.server.binary.CidMappingService.class)
        );
        when(factory.createFragmentationTracker()).thenReturn(fragmentationTracker);
        when(factory.createWalletStorageMetrics(fileStore)).thenReturn(
            mock(org.apache.jackrabbit.oak.segment.consensus.fragmentation.WalletStorageMetrics.class)
        );
        when(factory.createGCProposalManager(any(), any(), any(), any(), anyInt(), any(), any())).thenReturn(gcProposalManager);
        when(factory.createGCAccountManager()).thenReturn(gcAccountManager);
        when(factory.createPeriodicGCJob(gcAccountManager)).thenReturn(periodicGCJob);
        when(periodicGCJob.getIntervalSeconds()).thenReturn(60L);
        when(periodicGCJob.getInitialDelaySeconds()).thenReturn(5L);
        when(httpServer.getContext()).thenReturn(context);
        doAnswer(invocation -> {
            context.setSelfUrl(invocation.getArgument(0));
            return null;
        }).when(httpServer).setSelfUrl(anyString());

        return new Fixture(factory, fileStore, nodeStore, blobStore, gcCostEstimator, httpServer,
            context, fragmentationTracker, gcProposalManager, gcAccountManager, periodicGCJob);
    }

    private static AeronClusterConfig aeronConfig(String selfUrl, String[] peerUrls) {
        return (AeronClusterConfig) Proxy.newProxyInstance(
            AeronClusterConfig.class.getClassLoader(),
            new Class<?>[] {AeronClusterConfig.class},
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "selfUrl":
                        return selfUrl != null ? selfUrl : method.getDefaultValue();
                    case "peerUrls":
                        return peerUrls != null ? peerUrls : method.getDefaultValue();
                    case "annotationType":
                        return AeronClusterConfig.class;
                    default:
                        return method.getDefaultValue();
                }
            }
        );
    }

    private static final class Fixture {
        private final GlobalStoreServerComponentFactory factory;
        private final FileStore fileStore;
        private final NodeStore nodeStore;
        private final BlobStore blobStore;
        private final GCCostEstimator gcCostEstimator;
        private final SegmentHttpServer httpServer;
        private final ServerContext context;
        private final FragmentationTracker fragmentationTracker;
        private final GCProposalManager gcProposalManager;
        private final GCAccountManager gcAccountManager;
        private final PeriodicGCJob periodicGCJob;

        private Fixture(GlobalStoreServerComponentFactory factory,
                        FileStore fileStore,
                        NodeStore nodeStore,
                        BlobStore blobStore,
                        GCCostEstimator gcCostEstimator,
                        SegmentHttpServer httpServer,
                        ServerContext context,
                        FragmentationTracker fragmentationTracker,
                        GCProposalManager gcProposalManager,
                        GCAccountManager gcAccountManager,
                        PeriodicGCJob periodicGCJob) {
            this.factory = factory;
            this.fileStore = fileStore;
            this.nodeStore = nodeStore;
            this.blobStore = blobStore;
            this.gcCostEstimator = gcCostEstimator;
            this.httpServer = httpServer;
            this.context = context;
            this.fragmentationTracker = fragmentationTracker;
            this.gcProposalManager = gcProposalManager;
            this.gcAccountManager = gcAccountManager;
            this.periodicGCJob = periodicGCJob;
        }
    }
}
