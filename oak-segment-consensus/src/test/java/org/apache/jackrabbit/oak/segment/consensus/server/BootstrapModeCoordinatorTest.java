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

import org.apache.jackrabbit.oak.segment.consensus.aeron.AeronClusterConfig;
import org.apache.jackrabbit.oak.segment.consensus.bootstrap.ValidatorBootstrap;
import org.apache.jackrabbit.oak.segment.consensus.bootstrap.ValidatorBootstrap.BootstrapMode;
import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class BootstrapModeCoordinatorTest {

    @After
    public void tearDown() {
        System.clearProperty("bootstrap.primary.host");
        System.clearProperty("bootstrap.primary.port");
    }

    @Test
    public void testResolveUsesVerifiedPeerAndDefersClusterWhenBootstrapNeeded() {
        FileStore fileStore = mock(FileStore.class);
        ValidatorBootstrap bootstrap = mock(ValidatorBootstrap.class);
        GlobalStoreServerComponentFactory componentFactory = mock(GlobalStoreServerComponentFactory.class);
        when(componentFactory.createValidatorBootstrap(fileStore, 8091)).thenReturn(bootstrap);

        BootstrapModeCoordinator.Resolution resolution = new BootstrapModeCoordinator().resolve(
            new BootstrapModeCoordinator.StartupContext(
                true,
                true,
                true,
                fileStore,
                8090,
                8091,
                "validator-1",
                8091,
                aeronConfig(new String[] {"http://validator-1:8090", "http://validator-2:8090"}),
                componentFactory
            )
        );

        assertEquals(BootstrapMode.STANDBY, resolution.getDetectedMode());
        assertSame(bootstrap, resolution.getBootstrap());
        assertTrue(resolution.isAeronClusterDeferred());
        assertEquals("validator-1", resolution.getBootstrapPrimaryHost());
        assertEquals(8091, resolution.getBootstrapPrimaryPort());
        assertEquals(2, resolution.getAeronPeerUrls().size());
    }

    @Test
    public void testResolveUsesConfiguredBootstrapPrimaryWhenVerifiedHostMissing() {
        System.setProperty("bootstrap.primary.host", "bootstrap-node");
        System.setProperty("bootstrap.primary.port", "9001");

        FileStore fileStore = mock(FileStore.class);
        ValidatorBootstrap bootstrap = mock(ValidatorBootstrap.class);
        GlobalStoreServerComponentFactory componentFactory = mock(GlobalStoreServerComponentFactory.class);
        when(componentFactory.createValidatorBootstrap(fileStore, 8091)).thenReturn(bootstrap);

        BootstrapModeCoordinator.Resolution resolution = new BootstrapModeCoordinator().resolve(
            new BootstrapModeCoordinator.StartupContext(
                true,
                true,
                true,
                fileStore,
                8090,
                8091,
                "",
                0,
                aeronConfig(new String[0]),
                componentFactory
            )
        );

        assertEquals(BootstrapMode.STANDBY, resolution.getDetectedMode());
        assertEquals("bootstrap-node", resolution.getBootstrapPrimaryHost());
        assertEquals(9001, resolution.getBootstrapPrimaryPort());
    }

    @Test
    public void testResolveFallsBackToFirstPeerWhenNoExplicitPrimaryExists() {
        FileStore fileStore = mock(FileStore.class);
        ValidatorBootstrap bootstrap = mock(ValidatorBootstrap.class);
        GlobalStoreServerComponentFactory componentFactory = mock(GlobalStoreServerComponentFactory.class);
        when(componentFactory.createValidatorBootstrap(fileStore, 8091)).thenReturn(bootstrap);

        BootstrapModeCoordinator.Resolution resolution = new BootstrapModeCoordinator().resolve(
            new BootstrapModeCoordinator.StartupContext(
                true,
                true,
                true,
                fileStore,
                8090,
                8091,
                "",
                0,
                aeronConfig(new String[] {"http://validator-7:8090"}),
                componentFactory
            )
        );

        assertEquals(BootstrapMode.STANDBY, resolution.getDetectedMode());
        assertEquals("validator-7", resolution.getBootstrapPrimaryHost());
        assertEquals(8091, resolution.getBootstrapPrimaryPort());
    }

    @Test
    public void testResolveFallsBackToGenesisWhenNoPrimaryAvailable() {
        FileStore fileStore = mock(FileStore.class);
        ValidatorBootstrap bootstrap = mock(ValidatorBootstrap.class);
        GlobalStoreServerComponentFactory componentFactory = mock(GlobalStoreServerComponentFactory.class);
        when(componentFactory.createValidatorBootstrap(fileStore, 8091)).thenReturn(bootstrap);

        BootstrapModeCoordinator.Resolution resolution = new BootstrapModeCoordinator().resolve(
            new BootstrapModeCoordinator.StartupContext(
                true,
                true,
                true,
                fileStore,
                8090,
                8091,
                "",
                0,
                aeronConfig(new String[0]),
                componentFactory
            )
        );

        assertEquals(BootstrapMode.GENESIS, resolution.getDetectedMode());
        assertSame(bootstrap, resolution.getBootstrap());
        assertTrue(resolution.isAeronClusterDeferred());
        assertEquals("", resolution.getBootstrapPrimaryHost());
        assertEquals(8091, resolution.getBootstrapPrimaryPort());
    }

    @Test
    public void testResolveUsesPrimaryModeForParallelLaunchAndExistingStore() {
        FileStore fileStore = mock(FileStore.class);
        ValidatorBootstrap bootstrap = mock(ValidatorBootstrap.class);
        GlobalStoreServerComponentFactory componentFactory = mock(GlobalStoreServerComponentFactory.class);
        when(componentFactory.createValidatorBootstrap(fileStore, 8091)).thenReturn(bootstrap);

        BootstrapModeCoordinator coordinator = new BootstrapModeCoordinator();
        BootstrapModeCoordinator.Resolution emptyStoreResolution = coordinator.resolve(
            new BootstrapModeCoordinator.StartupContext(
                true,
                true,
                false,
                fileStore,
                8090,
                8091,
                "",
                0,
                aeronConfig(new String[] {"http://validator-1:8090"}),
                componentFactory
            )
        );
        BootstrapModeCoordinator.Resolution existingStoreResolution = coordinator.resolve(
            new BootstrapModeCoordinator.StartupContext(
                true,
                false,
                false,
                fileStore,
                8090,
                8091,
                "",
                0,
                aeronConfig(new String[] {"http://validator-1:8090"}),
                componentFactory
            )
        );

        assertEquals(BootstrapMode.PRIMARY, emptyStoreResolution.getDetectedMode());
        assertSame(bootstrap, emptyStoreResolution.getBootstrap());
        assertFalse(emptyStoreResolution.isAeronClusterDeferred());
        assertEquals(BootstrapMode.PRIMARY, existingStoreResolution.getDetectedMode());
        assertSame(bootstrap, existingStoreResolution.getBootstrap());
        assertFalse(existingStoreResolution.isAeronClusterDeferred());
        verify(componentFactory, times(2)).createValidatorBootstrap(fileStore, 8091);
    }

    private static AeronClusterConfig aeronConfig(String[] peerUrls) {
        return (AeronClusterConfig) Proxy.newProxyInstance(
            AeronClusterConfig.class.getClassLoader(),
            new Class<?>[] {AeronClusterConfig.class},
            (proxy, method, args) -> {
                switch (method.getName()) {
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
}
