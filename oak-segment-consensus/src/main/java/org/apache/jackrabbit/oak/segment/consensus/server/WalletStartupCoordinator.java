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

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

import org.apache.jackrabbit.oak.segment.consensus.security.EthereumWallet;

final class WalletStartupCoordinator {

    StartupResult initialize(String storeDirectory, GlobalStoreServerComponentFactory componentFactory) throws IOException {
        String nodeKeystorePath = GlobalStoreRuntimeConfigUtil.resolveNodeKeystorePath(storeDirectory);
        EthereumWallet wallet = createNodeWallet(nodeKeystorePath, componentFactory);
        String clusterWalletAddress = resolveClusterWalletAddress(storeDirectory, wallet);
        return new StartupResult(wallet, clusterWalletAddress);
    }

    private static EthereumWallet createNodeWallet(String nodeKeystorePath,
                                                   GlobalStoreServerComponentFactory componentFactory) throws IOException {
        try {
            EthereumWallet wallet = componentFactory.createEthereumWallet(nodeKeystorePath);
            System.out.println("🔑 Node wallet: " + wallet.getWalletAddress());
            return wallet;
        } catch (Exception e) {
            System.err.println("❌ FATAL: Failed to load/generate node wallet");
            System.err.println("   Keystore path: " + nodeKeystorePath);
            System.err.println("   Error: " + e.getMessage());
            throw new IOException("Node wallet initialization failed", e);
        }
    }

    private static String resolveClusterWalletAddress(String storeDirectory, EthereumWallet wallet) {
        Path clusterPath = Paths.get(storeDirectory).getParent();
        String clusterWalletAddress = null;
        if (clusterPath != null) {
            Path clusterKeystorePath = clusterPath.resolve("cluster-keystore.properties");
            if (Files.exists(clusterKeystorePath)) {
                try {
                    Properties props = new Properties();
                    try (FileInputStream fis = new FileInputStream(clusterKeystorePath.toFile())) {
                        props.load(fis);
                    }
                    clusterWalletAddress = props.getProperty("walletAddress");
                    if (clusterWalletAddress != null) {
                        System.out.println("💎 Cluster wallet: " + clusterWalletAddress + " (payments go here)");
                    }
                } catch (Exception e) {
                    System.out.println("⚠️  Could not read cluster wallet: " + e.getMessage());
                }
            }
        }
        if (clusterWalletAddress == null) {
            System.out.println("ℹ️  No cluster wallet configured - using node wallet for payments");
            clusterWalletAddress = wallet.getWalletAddress();
        }
        return clusterWalletAddress;
    }

    static final class StartupResult {
        private final EthereumWallet wallet;
        private final String clusterWalletAddress;

        StartupResult(EthereumWallet wallet, String clusterWalletAddress) {
            this.wallet = wallet;
            this.clusterWalletAddress = clusterWalletAddress;
        }

        EthereumWallet getWallet() {
            return wallet;
        }

        String getClusterWalletAddress() {
            return clusterWalletAddress;
        }
    }
}
