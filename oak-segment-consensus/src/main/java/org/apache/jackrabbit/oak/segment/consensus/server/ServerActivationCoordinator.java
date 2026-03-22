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

import org.apache.jackrabbit.oak.segment.consensus.aeron.AeronClusterConfig;
import org.apache.jackrabbit.oak.segment.consensus.aeron.AeronClusterLauncher;
import org.apache.jackrabbit.oak.segment.consensus.aeron.AeronClusterService;
import org.apache.jackrabbit.oak.segment.consensus.bootstrap.ValidatorBootstrap;
import org.apache.jackrabbit.oak.segment.consensus.bootstrap.ValidatorBootstrap.BootstrapMode;
import org.apache.jackrabbit.oak.segment.consensus.security.EthereumWallet;
import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.apache.jackrabbit.oak.segment.http.server.SegmentHttpServer;
import org.apache.jackrabbit.oak.spi.blob.BlobStore;
import org.apache.jackrabbit.oak.spi.state.NodeStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class ServerActivationCoordinator {

    private static final Logger log = LoggerFactory.getLogger(ServerActivationCoordinator.class);
    private final ConsensusStartupStarter consensusStartupStarter;

    ServerActivationCoordinator() {
        this(context -> new ConsensusStartupCoordinator().initialize(context));
    }

    ServerActivationCoordinator(ConsensusStartupStarter consensusStartupStarter) {
        this.consensusStartupStarter = consensusStartupStarter;
    }

    ActivationResult activate(ActivationContext context) throws IOException {
        startHttpServer(context.getDetectedMode(), context.getHttpServer(), context.getPort());

        AeronClusterConfig aeronConfig = context.getAeronClusterService() != null
            ? context.getAeronClusterService().getConfig()
            : null;
        boolean isStandbyMode = context.getDetectedMode() == BootstrapMode.STANDBY;

        ConsensusStartupCoordinator.StartupOutcome consensusStartup = consensusStartupStarter.initialize(
            new ConsensusStartupCoordinator.StartupContext(
                context.getPort(),
                context.isAeronMode(),
                isStandbyMode,
                context.getFileStore(),
                context.getNodeStore(),
                context.getHttpServer(),
                context.getWallet(),
                context.getStoreDirectory(),
                context.getBlobStore(),
                context.getAeronClusterService(),
                context.getComponentFactory(),
                context.getClusterWalletAddress(),
                aeronConfig
            )
        );

        if (consensusStartup.getDisposition() == ConsensusStartupCoordinator.StartupDisposition.DISABLED) {
            log.info("ℹ️  Consensus disabled (single-validator mode)");
        } else if (consensusStartup.getDisposition() == ConsensusStartupCoordinator.StartupDisposition.DEFERRED) {
            log.info("ℹ️  Consensus initialization deferred (STANDBY mode -> callback)");
        }

        startStandbyServer(context.getDetectedMode(), context.getBootstrap());

        return new ActivationResult(
            consensusStartup.getAeronClusterService(),
            resolveLauncher(context.getExistingLauncher(), consensusStartup)
        );
    }

    private static void startHttpServer(BootstrapMode detectedMode,
                                        SegmentHttpServer httpServer,
                                        int port) throws IOException {
        if (detectedMode == BootstrapMode.STANDBY) {
            log.info("⏸️  HTTP server startup deferred (STANDBY mode - will start after bootstrap completes)");
            return;
        }

        log.info("Starting HTTP server...");
        try {
            httpServer.start();
            log.info("✅ HTTP server started on port {}", port);
        } catch (Exception e) {
            throw new IOException("Failed to start HTTP server", e);
        }
    }

    private static void startStandbyServer(BootstrapMode detectedMode, ValidatorBootstrap bootstrap) {
        if ((detectedMode != BootstrapMode.PRIMARY && detectedMode != BootstrapMode.GENESIS) || bootstrap == null) {
            return;
        }

        try {
            bootstrap.startStandbyServer();
        } catch (Exception e) {
            log.warn("⚠️  Failed to start StandbyServerSync: {}", e.getMessage());
        }
    }

    private static AeronClusterLauncher resolveLauncher(AeronClusterLauncher existingLauncher,
                                                        ConsensusStartupCoordinator.StartupOutcome consensusStartup) {
        if (consensusStartup.getDisposition() == ConsensusStartupCoordinator.StartupDisposition.DEFERRED
                && consensusStartup.getLauncher() == null) {
            return existingLauncher;
        }
        return consensusStartup.getLauncher();
    }

    @FunctionalInterface
    interface ConsensusStartupStarter {
        ConsensusStartupCoordinator.StartupOutcome initialize(ConsensusStartupCoordinator.StartupContext context)
            throws IOException;
    }

    static final class ActivationContext {
        private final int port;
        private final boolean aeronMode;
        private final BootstrapMode detectedMode;
        private final FileStore fileStore;
        private final NodeStore nodeStore;
        private final SegmentHttpServer httpServer;
        private final EthereumWallet wallet;
        private final String storeDirectory;
        private final BlobStore blobStore;
        private final AeronClusterService aeronClusterService;
        private final AeronClusterLauncher existingLauncher;
        private final GlobalStoreServerComponentFactory componentFactory;
        private final String clusterWalletAddress;
        private final ValidatorBootstrap bootstrap;

        ActivationContext(int port,
                          boolean aeronMode,
                          BootstrapMode detectedMode,
                          FileStore fileStore,
                          NodeStore nodeStore,
                          SegmentHttpServer httpServer,
                          EthereumWallet wallet,
                          String storeDirectory,
                          BlobStore blobStore,
                          AeronClusterService aeronClusterService,
                          AeronClusterLauncher existingLauncher,
                          GlobalStoreServerComponentFactory componentFactory,
                          String clusterWalletAddress,
                          ValidatorBootstrap bootstrap) {
            this.port = port;
            this.aeronMode = aeronMode;
            this.detectedMode = detectedMode;
            this.fileStore = fileStore;
            this.nodeStore = nodeStore;
            this.httpServer = httpServer;
            this.wallet = wallet;
            this.storeDirectory = storeDirectory;
            this.blobStore = blobStore;
            this.aeronClusterService = aeronClusterService;
            this.existingLauncher = existingLauncher;
            this.componentFactory = componentFactory;
            this.clusterWalletAddress = clusterWalletAddress;
            this.bootstrap = bootstrap;
        }

        int getPort() {
            return port;
        }

        boolean isAeronMode() {
            return aeronMode;
        }

        BootstrapMode getDetectedMode() {
            return detectedMode;
        }

        FileStore getFileStore() {
            return fileStore;
        }

        NodeStore getNodeStore() {
            return nodeStore;
        }

        SegmentHttpServer getHttpServer() {
            return httpServer;
        }

        EthereumWallet getWallet() {
            return wallet;
        }

        String getStoreDirectory() {
            return storeDirectory;
        }

        BlobStore getBlobStore() {
            return blobStore;
        }

        AeronClusterService getAeronClusterService() {
            return aeronClusterService;
        }

        AeronClusterLauncher getExistingLauncher() {
            return existingLauncher;
        }

        GlobalStoreServerComponentFactory getComponentFactory() {
            return componentFactory;
        }

        String getClusterWalletAddress() {
            return clusterWalletAddress;
        }

        ValidatorBootstrap getBootstrap() {
            return bootstrap;
        }
    }

    static final class ActivationResult {
        private final AeronClusterService aeronClusterService;
        private final AeronClusterLauncher launcher;

        ActivationResult(AeronClusterService aeronClusterService, AeronClusterLauncher launcher) {
            this.aeronClusterService = aeronClusterService;
            this.launcher = launcher;
        }

        AeronClusterService getAeronClusterService() {
            return aeronClusterService;
        }

        AeronClusterLauncher getLauncher() {
            return launcher;
        }
    }
}
