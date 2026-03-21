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
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.jackrabbit.oak.segment.consensus.security.EthereumWallet;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class WalletStartupCoordinatorTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @After
    public void tearDown() {
        System.clearProperty("wallet.keystore.path");
    }

    @Test
    public void testInitializeUsesClusterWalletWhenConfigured() throws Exception {
        Path storeDir = createStoreDir("validator-0");
        Files.writeString(
            storeDir.getParent().resolve("cluster-keystore.properties"),
            "walletAddress=0xcluster000000000000000000000000000000000000\n"
        );

        EthereumWallet wallet = mock(EthereumWallet.class);
        when(wallet.getWalletAddress()).thenReturn("0xnode000000000000000000000000000000000000");
        GlobalStoreServerComponentFactory componentFactory = mock(GlobalStoreServerComponentFactory.class);
        when(componentFactory.createEthereumWallet(storeDir.resolve("validator-keystore.properties").toString())).thenReturn(wallet);

        WalletStartupCoordinator.StartupResult result = new WalletStartupCoordinator().initialize(
            storeDir.toString(),
            componentFactory
        );

        assertSame(wallet, result.getWallet());
        assertEquals("0xcluster000000000000000000000000000000000000", result.getClusterWalletAddress());
        verify(componentFactory).createEthereumWallet(storeDir.resolve("validator-keystore.properties").toString());
    }

    @Test
    public void testInitializeFallsBackToNodeWalletWhenClusterWalletMissing() throws Exception {
        Path storeDir = createStoreDir("validator-1");
        EthereumWallet wallet = mock(EthereumWallet.class);
        when(wallet.getWalletAddress()).thenReturn("0xnode111111111111111111111111111111111111");
        GlobalStoreServerComponentFactory componentFactory = mock(GlobalStoreServerComponentFactory.class);
        when(componentFactory.createEthereumWallet(storeDir.resolve("validator-keystore.properties").toString())).thenReturn(wallet);

        WalletStartupCoordinator.StartupResult result = new WalletStartupCoordinator().initialize(
            storeDir.toString(),
            componentFactory
        );

        assertEquals("0xnode111111111111111111111111111111111111", result.getClusterWalletAddress());
    }

    @Test
    public void testInitializeFallsBackToNodeWalletWhenClusterWalletUnreadable() throws Exception {
        Path storeDir = createStoreDir("validator-2");
        Files.createDirectory(storeDir.getParent().resolve("cluster-keystore.properties"));

        EthereumWallet wallet = mock(EthereumWallet.class);
        when(wallet.getWalletAddress()).thenReturn("0xnode222222222222222222222222222222222222");
        GlobalStoreServerComponentFactory componentFactory = mock(GlobalStoreServerComponentFactory.class);
        when(componentFactory.createEthereumWallet(storeDir.resolve("validator-keystore.properties").toString())).thenReturn(wallet);

        WalletStartupCoordinator.StartupResult result = new WalletStartupCoordinator().initialize(
            storeDir.toString(),
            componentFactory
        );

        assertEquals("0xnode222222222222222222222222222222222222", result.getClusterWalletAddress());
    }

    @Test
    public void testInitializeUsesConfiguredNodeKeystoreOverride() throws Exception {
        Path storeDir = createStoreDir("validator-3");
        System.setProperty("wallet.keystore.path", "/secure/node-wallet.properties");

        EthereumWallet wallet = mock(EthereumWallet.class);
        when(wallet.getWalletAddress()).thenReturn("0xnode333333333333333333333333333333333333");
        GlobalStoreServerComponentFactory componentFactory = mock(GlobalStoreServerComponentFactory.class);
        when(componentFactory.createEthereumWallet("/secure/node-wallet.properties")).thenReturn(wallet);

        WalletStartupCoordinator.StartupResult result = new WalletStartupCoordinator().initialize(
            storeDir.toString(),
            componentFactory
        );

        assertSame(wallet, result.getWallet());
        verify(componentFactory).createEthereumWallet("/secure/node-wallet.properties");
    }

    @Test
    public void testInitializeWrapsNodeWalletFailure() throws Exception {
        Path storeDir = createStoreDir("validator-4");
        RuntimeException cause = new RuntimeException("keystore unavailable");
        GlobalStoreServerComponentFactory componentFactory = mock(GlobalStoreServerComponentFactory.class);
        when(componentFactory.createEthereumWallet(storeDir.resolve("validator-keystore.properties").toString()))
            .thenThrow(cause);

        try {
            new WalletStartupCoordinator().initialize(storeDir.toString(), componentFactory);
            fail("Expected IOException");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("Node wallet initialization failed"));
            assertSame(cause, e.getCause());
        }
    }

    private Path createStoreDir(String validatorDir) throws IOException {
        Path clusterDir = tempFolder.getRoot().toPath().resolve("cluster");
        Path storeDir = clusterDir.resolve(validatorDir);
        Files.createDirectories(storeDir);
        return storeDir;
    }
}
