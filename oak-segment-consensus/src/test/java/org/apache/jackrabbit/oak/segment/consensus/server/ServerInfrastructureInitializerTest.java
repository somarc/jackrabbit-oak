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

import org.apache.jackrabbit.oak.segment.RecordId;
import org.apache.jackrabbit.oak.segment.consensus.aeron.AeronClusterConfig;
import org.apache.jackrabbit.oak.segment.consensus.gc.RevisionGcConfig;
import org.apache.jackrabbit.oak.segment.consensus.gc.RevisionGcMonitor;
import org.apache.jackrabbit.oak.segment.consensus.mount.RemoteMounts;
import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.apache.jackrabbit.oak.segment.http.server.SegmentHttpServer;
import org.apache.jackrabbit.oak.segment.http.server.ServerContext;
import org.apache.jackrabbit.oak.spi.blob.BlobStore;
import org.apache.jackrabbit.oak.spi.state.NodeStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedStatic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
public class ServerInfrastructureInitializerTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();
    private MockedStatic<StorageBackendFactory> storageFactory;

    @Before
    public void stubCurrentStorageFactoryBoundary() {
        storageFactory = mockStatic(StorageBackendFactory.class);
    }

    @After
    public void tearDown() {
        storageFactory.close();
        System.clearProperty("blobstore.type");
    }

    @Test
    public void testInitializeBuildsRuntimeAndWiresContext() throws Exception {
        System.setProperty("blobstore.type", "ipfs");

        Fixture fixture = newFixture();

        ServerInfrastructureInitializer initializer = new ServerInfrastructureInitializer();
        ServerInfrastructureInitializer.InitializationResult result = initializer.initialize(
            tempFolder.getRoot(),
            8090,
            aeronConfig("http://validator-0:8090", new String[] {"http://validator-1:8090", "http://validator-2:8090"}),
            fixture.factory
        );

        assertSame(fixture.blobStore, result.blobStore());
        assertSame(fixture.fileStore, result.fileStore());
        assertSame(fixture.nodeStore, result.nodeStore());
        assertSame(fixture.httpServer, result.httpServer());
        assertEquals("ipfs", result.blobStoreType());
        assertEquals("http://validator-0:8090", fixture.context.selfUrl);
        assertSame(fixture.blobStore, fixture.context.blobStore);
        assertEquals("ipfs", fixture.context.blobStoreType);
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

        assertSame(fixture.httpServer, result.httpServer());
        assertNull(fixture.context.cidMappingService);
    }

    private Fixture newFixture() throws Exception {
        FileStore fileStore = mock(FileStore.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        NodeStore nodeStore = mock(NodeStore.class);
        BlobStore blobStore = mock(BlobStore.class);
        SegmentHttpServer httpServer = mock(SegmentHttpServer.class);
        RecordId recordId = mock(RecordId.class);
        ServerContext context = new ServerContext(fileStore, nodeStore, tempFolder.getRoot().toPath(), "http://localhost:8090");
        GlobalStoreServerComponentFactory factory = mock(GlobalStoreServerComponentFactory.class);

        when(recordId.toString()).thenReturn("record-1");
        when(fileStore.getHead().getRecordId()).thenReturn(recordId);
        when(factory.createIpfsBlobStore(any(), eq(tempFolder.getRoot()))).thenReturn(blobStore);
        storageFactory.when(() -> StorageBackendFactory.createStorageRuntime(eq(tempFolder.getRoot()), eq(blobStore),
            any(org.apache.jackrabbit.oak.segment.consensus.config.StorageBackendConfig.class)))
            .thenReturn(new ServerStorageRuntime(fileStore, nodeStore, nodeStore, null, RemoteMounts.NONE,
                RevisionGcConfig.load(), new RevisionGcMonitor()));
        when(factory.createHttpServer(tempFolder.getRoot(), 8090, fileStore, nodeStore)).thenReturn(httpServer);
        when(factory.createCidMappingService(tempFolder.getRoot().toPath())).thenReturn(
            mock(org.apache.jackrabbit.oak.segment.http.server.binary.CidMappingService.class)
        );
        when(httpServer.getContext()).thenReturn(context);
        doAnswer(invocation -> {
            context.setSelfUrl(invocation.getArgument(0));
            return null;
        }).when(httpServer).setSelfUrl(anyString());

        return new Fixture(factory, fileStore, nodeStore, blobStore, httpServer, context);
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
        private final SegmentHttpServer httpServer;
        private final ServerContext context;

        private Fixture(GlobalStoreServerComponentFactory factory,
                        FileStore fileStore,
                        NodeStore nodeStore,
                        BlobStore blobStore,
                        SegmentHttpServer httpServer,
                        ServerContext context) {
            this.factory = factory;
            this.fileStore = fileStore;
            this.nodeStore = nodeStore;
            this.blobStore = blobStore;
            this.httpServer = httpServer;
            this.context = context;
        }
    }

    @Test
    public void testIpfsDataStoreGetsTheReplicatedBinaryIndex() {
        org.apache.jackrabbit.oak.blob.cloud.ipfs.IPFSDataStore ipfs =
            mock(org.apache.jackrabbit.oak.blob.cloud.ipfs.IPFSDataStore.class);
        org.apache.jackrabbit.oak.plugins.blob.datastore.DataStoreBlobStore blobStore =
            mock(org.apache.jackrabbit.oak.plugins.blob.datastore.DataStoreBlobStore.class);
        when(blobStore.getDataStore()).thenReturn(ipfs);

        ServerInfrastructureInitializer.attachReplicatedBinaryIndex(blobStore, mock(NodeStore.class));
        ServerInfrastructureInitializer.attachReplicatedBinaryIndex(mock(BlobStore.class), mock(NodeStore.class));

        verify(ipfs).setCidResolver(any(org.apache.jackrabbit.oak.segment.consensus.service.ReplicatedBinaryIndex.class));
    }
}
