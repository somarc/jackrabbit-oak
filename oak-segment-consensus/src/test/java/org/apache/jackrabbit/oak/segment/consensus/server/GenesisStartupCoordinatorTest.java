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

import org.apache.jackrabbit.oak.segment.consensus.bootstrap.ValidatorBootstrap;
import org.apache.jackrabbit.oak.segment.consensus.bootstrap.ValidatorBootstrap.BootstrapMode;
import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.apache.jackrabbit.oak.spi.blob.BlobStore;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.apache.jackrabbit.oak.spi.state.NodeStore;
import org.junit.Test;

import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

public class GenesisStartupCoordinatorTest {

    private final GenesisStartupCoordinator coordinator = new GenesisStartupCoordinator();

    @Test
    public void testGenesisModeStartsStandbyServerWhenBootstrapPresent() throws Exception {
        ValidatorBootstrap bootstrap = mock(ValidatorBootstrap.class);

        coordinator.initialize(new GenesisStartupCoordinator.StartupContext(
            BootstrapMode.GENESIS,
            bootstrap,
            8091,
            mock(NodeStore.class),
            mock(FileStore.class),
            mock(BlobStore.class),
            "http://validator-0:8090",
            mock(GlobalStoreServerComponentFactory.class)
        ));

        verify(bootstrap).startStandbyServer();
    }

    @Test
    public void testPrimaryModeVerifiesGenesisWhenPathExists() throws Exception {
        NodeStore nodeStore = mock(NodeStore.class);
        NodeState root = mock(NodeState.class, RETURNS_DEEP_STUBS);
        FileStore fileStore = mock(FileStore.class);
        BlobStore blobStore = mock(BlobStore.class);
        GlobalStoreServerComponentFactory componentFactory = mock(GlobalStoreServerComponentFactory.class);
        GenesisInitializer initializer = mock(GenesisInitializer.class);

        when(nodeStore.getRoot()).thenReturn(root);
        when(root.getChildNode("oak-chain")
            .getChildNode("00")
            .getChildNode("00")
            .getChildNode("00")
            .getChildNode("0x0000000000000000000000000000000000000000")
            .getChildNode("content")
            .getChildNode("genesis")
            .exists()).thenReturn(true);
        when(componentFactory.createGenesisInitializer(nodeStore, fileStore, blobStore, "http://validator-0:8090"))
            .thenReturn(initializer);

        coordinator.initialize(new GenesisStartupCoordinator.StartupContext(
            BootstrapMode.PRIMARY,
            null,
            8091,
            nodeStore,
            fileStore,
            blobStore,
            "http://validator-0:8090",
            componentFactory
        ));

        verify(initializer).initializeGenesisContent();
    }

    @Test
    public void testPrimaryModeSkipsInitializerWhenGenesisPathMissing() throws Exception {
        NodeStore nodeStore = mock(NodeStore.class);
        NodeState root = mock(NodeState.class, RETURNS_DEEP_STUBS);
        GlobalStoreServerComponentFactory componentFactory = mock(GlobalStoreServerComponentFactory.class);

        when(nodeStore.getRoot()).thenReturn(root);
        when(root.getChildNode("oak-chain")
            .getChildNode("00")
            .getChildNode("00")
            .getChildNode("00")
            .getChildNode("0x0000000000000000000000000000000000000000")
            .getChildNode("content")
            .getChildNode("genesis")
            .exists()).thenReturn(false);

        coordinator.initialize(new GenesisStartupCoordinator.StartupContext(
            BootstrapMode.PRIMARY,
            null,
            8091,
            nodeStore,
            mock(FileStore.class),
            mock(BlobStore.class),
            "http://validator-0:8090",
            componentFactory
        ));

        verifyNoInteractions(componentFactory);
    }

    @Test
    public void testPrimaryModeFallsBackToStoreSizeWhenRootProbeFails() throws Exception {
        NodeStore nodeStore = mock(NodeStore.class);
        FileStore fileStore = mock(FileStore.class);
        BlobStore blobStore = mock(BlobStore.class);
        GlobalStoreServerComponentFactory componentFactory = mock(GlobalStoreServerComponentFactory.class);
        GenesisInitializer initializer = mock(GenesisInitializer.class);

        when(nodeStore.getRoot()).thenThrow(new RuntimeException("probe failed"));
        when(fileStore.size()).thenReturn(2L * 1024 * 1024);
        when(componentFactory.createGenesisInitializer(nodeStore, fileStore, blobStore, "http://validator-0:8090"))
            .thenReturn(initializer);

        coordinator.initialize(new GenesisStartupCoordinator.StartupContext(
            BootstrapMode.PRIMARY,
            null,
            8091,
            nodeStore,
            fileStore,
            blobStore,
            "http://validator-0:8090",
            componentFactory
        ));

        verify(initializer).initializeGenesisContent();
    }

    @Test
    public void testPrimaryModeSkipsFallbackInitializerWhenStoreLooksEmpty() throws Exception {
        NodeStore nodeStore = mock(NodeStore.class);
        FileStore fileStore = mock(FileStore.class);
        GlobalStoreServerComponentFactory componentFactory = mock(GlobalStoreServerComponentFactory.class);

        when(nodeStore.getRoot()).thenThrow(new RuntimeException("probe failed"));
        when(fileStore.size()).thenReturn(512L * 1024);

        coordinator.initialize(new GenesisStartupCoordinator.StartupContext(
            BootstrapMode.PRIMARY,
            null,
            8091,
            nodeStore,
            fileStore,
            mock(BlobStore.class),
            "http://validator-0:8090",
            componentFactory
        ));

        verifyNoInteractions(componentFactory);
    }
}
