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

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;

import org.apache.jackrabbit.oak.segment.consensus.aeron.AeronClusterConfig;
import org.apache.jackrabbit.oak.segment.consensus.aeron.AeronClusterLauncher;
import org.apache.jackrabbit.oak.segment.consensus.aeron.AeronClusterService;
import org.apache.jackrabbit.oak.segment.consensus.security.EthereumWallet;
import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.apache.jackrabbit.oak.segment.http.server.SegmentHttpServer;
import org.apache.jackrabbit.oak.spi.blob.BlobStore;
import org.apache.jackrabbit.oak.spi.state.NodeStore;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

public class GlobalStoreServerDeferredStartupTest {

    @Test
    public void testDeferredStartupUsesStoredConfigAndCapturesLauncher() throws Exception {
        GlobalStoreServer server = new GlobalStoreServer(8090, "/tmp/test-store");
        FileStore fileStore = mock(FileStore.class);
        NodeStore nodeStore = mock(NodeStore.class);
        SegmentHttpServer httpServer = mock(SegmentHttpServer.class);
        EthereumWallet wallet = mock(EthereumWallet.class);
        BlobStore blobStore = mock(BlobStore.class);
        AeronClusterService clusterService = mock(AeronClusterService.class);
        AeronClusterConfig config = mock(AeronClusterConfig.class);
        AeronClusterLauncher launcher = mock(AeronClusterLauncher.class);
        AeronClusterStartupResult startupResult = new AeronClusterStartupResult(
            null,
            launcher,
            null,
            Collections.singletonList("validator-0"),
            7
        );

        when(clusterService.getConfig()).thenReturn(config);
        when(config.observeElections()).thenReturn(true);
        when(config.logClusterStateDetails()).thenReturn(true);
        when(clusterService.startCluster(
            fileStore,
            nodeStore,
            httpServer,
            wallet,
            "/tmp/test-store",
            blobStore,
            "http://validator-0:8090",
            Arrays.asList("http://validator-1:8090", "http://validator-2:8090"),
            true,
            true
        )).thenReturn(startupResult);

        setField(server, "fileStore", fileStore);
        setField(server, "nodeStore", nodeStore);
        setField(server, "httpServer", httpServer);
        setField(server, "wallet", wallet);
        setField(server, "blobStore", blobStore);
        setField(server, "aeronClusterService", clusterService);
        setField(server, "aeronSelfUrl", "http://validator-0:8090");
        setField(server, "aeronPeerUrls", Arrays.asList("http://validator-1:8090", "http://validator-2:8090"));

        invokeDeferredStartup(server);

        verify(clusterService).startCluster(
            fileStore,
            nodeStore,
            httpServer,
            wallet,
            "/tmp/test-store",
            blobStore,
            "http://validator-0:8090",
            Arrays.asList("http://validator-1:8090", "http://validator-2:8090"),
            true,
            true
        );
        assertSame(launcher, getField(server, "aeronClusterLauncher"));
    }

    @Test
    public void testDeferredStartupBuildsStandaloneServiceWhenOsgiServiceIsMissing() throws Exception {
        GlobalStoreServer server = new GlobalStoreServer(8090, "/tmp/test-store");
        FileStore fileStore = mock(FileStore.class);
        NodeStore nodeStore = mock(NodeStore.class);
        SegmentHttpServer httpServer = mock(SegmentHttpServer.class);
        EthereumWallet wallet = mock(EthereumWallet.class);
        BlobStore blobStore = mock(BlobStore.class);
        GlobalStoreServerComponentFactory componentFactory = mock(GlobalStoreServerComponentFactory.class);
        AeronClusterService clusterService = mock(AeronClusterService.class);
        AeronClusterLauncher launcher = mock(AeronClusterLauncher.class);
        AeronClusterStartupResult startupResult = new AeronClusterStartupResult(
            null,
            launcher,
            null,
            Collections.singletonList("validator-0"),
            11
        );

        when(componentFactory.createAeronClusterService()).thenReturn(clusterService);
        when(clusterService.startCluster(
            fileStore,
            nodeStore,
            httpServer,
            wallet,
            "/tmp/test-store",
            blobStore,
            "http://validator-0:8090",
            Collections.<String>emptyList(),
            false,
            false
        )).thenReturn(startupResult);

        server.setComponentFactory(componentFactory);
        setField(server, "fileStore", fileStore);
        setField(server, "nodeStore", nodeStore);
        setField(server, "httpServer", httpServer);
        setField(server, "wallet", wallet);
        setField(server, "blobStore", blobStore);
        setField(server, "aeronSelfUrl", "http://validator-0:8090");

        invokeDeferredStartup(server);

        verify(componentFactory).createAeronClusterService();
        verify(clusterService).startCluster(
            fileStore,
            nodeStore,
            httpServer,
            wallet,
            "/tmp/test-store",
            blobStore,
            "http://validator-0:8090",
            Collections.<String>emptyList(),
            false,
            false
        );
        assertSame(clusterService, getField(server, "aeronClusterService"));
        assertSame(launcher, getField(server, "aeronClusterLauncher"));
    }

    @Test
    public void testDeferredStartupRejectsMissingSelfUrl() throws Exception {
        GlobalStoreServer server = new GlobalStoreServer(8090, "/tmp/test-store");
        AeronClusterService clusterService = mock(AeronClusterService.class);

        setField(server, "aeronClusterService", clusterService);

        try {
            invokeDeferredStartup(server);
            fail("Expected IOException");
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            assertEquals(IOException.class, cause.getClass());
            assertEquals("Aeron Cluster bootstrap: selfUrl not stored", cause.getMessage());
        }

        verifyNoInteractions(clusterService);
    }

    private static void invokeDeferredStartup(GlobalStoreServer server) throws Exception {
        Method method = GlobalStoreServer.class.getDeclaredMethod("startAeronClusterAfterBootstrap");
        method.setAccessible(true);
        method.invoke(server);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = GlobalStoreServer.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object getField(Object target, String name) throws Exception {
        Field field = GlobalStoreServer.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
