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
import java.util.Collections;

import org.apache.jackrabbit.oak.segment.consensus.aeron.AeronClusterLauncher;
import org.apache.jackrabbit.oak.segment.consensus.aeron.AeronClusterService;
import org.apache.jackrabbit.oak.segment.consensus.bootstrap.ValidatorBootstrap;
import org.apache.jackrabbit.oak.segment.consensus.bootstrap.ValidatorBootstrap.BootstrapMode;
import org.apache.jackrabbit.oak.segment.consensus.security.EthereumWallet;
import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.apache.jackrabbit.oak.segment.http.server.SegmentHttpServer;
import org.apache.jackrabbit.oak.spi.blob.BlobStore;
import org.apache.jackrabbit.oak.spi.state.NodeStore;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

public class ServerActivationCoordinatorTest {

    @Test
    public void testActivatePreservesDeferredLauncherInStandbyMode() throws Exception {
        AeronClusterService clusterService = mock(AeronClusterService.class);
        AeronClusterLauncher existingLauncher = mock(AeronClusterLauncher.class);
        SegmentHttpServer httpServer = mock(SegmentHttpServer.class);
        ValidatorBootstrap bootstrap = mock(ValidatorBootstrap.class);
        ServerActivationCoordinator coordinator = new ServerActivationCoordinator(
            context -> new ConsensusStartupCoordinator.StartupOutcome(
                ConsensusStartupCoordinator.StartupDisposition.DEFERRED,
                clusterService,
                null,
                "http://validator-0:8090",
                Collections.singletonList("http://validator-1:8090")
            )
        );

        ServerActivationCoordinator.ActivationResult activation = coordinator.activate(
            newContext(BootstrapMode.STANDBY, httpServer, bootstrap, clusterService, existingLauncher)
        );

        verify(httpServer, never()).start();
        verifyNoInteractions(bootstrap);
        assertSame(clusterService, activation.getAeronClusterService());
        assertSame(existingLauncher, activation.getLauncher());
    }

    @Test
    public void testActivateStartsHttpServerAndStandbyServerForPrimary() throws Exception {
        AeronClusterService clusterService = mock(AeronClusterService.class);
        AeronClusterLauncher launcher = mock(AeronClusterLauncher.class);
        SegmentHttpServer httpServer = mock(SegmentHttpServer.class);
        ValidatorBootstrap bootstrap = mock(ValidatorBootstrap.class);
        ServerActivationCoordinator coordinator = new ServerActivationCoordinator(
            context -> new ConsensusStartupCoordinator.StartupOutcome(
                ConsensusStartupCoordinator.StartupDisposition.INITIALIZED,
                clusterService,
                launcher,
                "http://validator-0:8090",
                Collections.singletonList("http://validator-1:8090")
            )
        );

        ServerActivationCoordinator.ActivationResult activation = coordinator.activate(
            newContext(BootstrapMode.PRIMARY, httpServer, bootstrap, clusterService, null)
        );

        verify(httpServer).start();
        verify(bootstrap).startStandbyServer();
        assertSame(clusterService, activation.getAeronClusterService());
        assertSame(launcher, activation.getLauncher());
    }

    @Test
    public void testActivateWrapsImmediateHttpStartupFailure() throws Exception {
        SegmentHttpServer httpServer = mock(SegmentHttpServer.class);
        ValidatorBootstrap bootstrap = mock(ValidatorBootstrap.class);
        final boolean[] invoked = {false};
        ServerActivationCoordinator coordinator = new ServerActivationCoordinator(context -> {
            invoked[0] = true;
            return new ConsensusStartupCoordinator.StartupOutcome(
                ConsensusStartupCoordinator.StartupDisposition.DISABLED,
                null,
                null,
                "http://validator-0:8090",
                Collections.<String>emptyList()
            );
        });

        doThrow(new IllegalStateException("bind failed")).when(httpServer).start();

        try {
            coordinator.activate(newContext(BootstrapMode.PRIMARY, httpServer, bootstrap, null, null));
            fail("Expected IOException");
        } catch (IOException e) {
            assertEquals("Failed to start HTTP server", e.getMessage());
            assertEquals("bind failed", e.getCause().getMessage());
        }

        if (invoked[0]) {
            fail("Consensus startup should not run when HTTP startup fails");
        }
        verifyNoInteractions(bootstrap);
    }

    @Test
    public void testActivateSwallowsStandbyServerStartFailure() throws Exception {
        AeronClusterService clusterService = mock(AeronClusterService.class);
        SegmentHttpServer httpServer = mock(SegmentHttpServer.class);
        ValidatorBootstrap bootstrap = mock(ValidatorBootstrap.class);
        ServerActivationCoordinator coordinator = new ServerActivationCoordinator(
            context -> new ConsensusStartupCoordinator.StartupOutcome(
                ConsensusStartupCoordinator.StartupDisposition.DISABLED,
                clusterService,
                null,
                "http://validator-0:8090",
                Collections.<String>emptyList()
            )
        );

        doThrow(new IOException("standby unavailable")).when(bootstrap).startStandbyServer();

        ServerActivationCoordinator.ActivationResult activation = coordinator.activate(
            newContext(BootstrapMode.GENESIS, httpServer, bootstrap, clusterService, null)
        );

        verify(httpServer).start();
        verify(bootstrap).startStandbyServer();
        assertSame(clusterService, activation.getAeronClusterService());
    }

    private static ServerActivationCoordinator.ActivationContext newContext(BootstrapMode detectedMode,
                                                                            SegmentHttpServer httpServer,
                                                                            ValidatorBootstrap bootstrap,
                                                                            AeronClusterService clusterService,
                                                                            AeronClusterLauncher existingLauncher) {
        return new ServerActivationCoordinator.ActivationContext(
            8090,
            true,
            detectedMode,
            mock(FileStore.class),
            mock(NodeStore.class),
            httpServer,
            mock(EthereumWallet.class),
            "/tmp/test-store",
            mock(BlobStore.class),
            clusterService,
            existingLauncher,
            mock(GlobalStoreServerComponentFactory.class),
            "0xabc",
            bootstrap
        );
    }
}
