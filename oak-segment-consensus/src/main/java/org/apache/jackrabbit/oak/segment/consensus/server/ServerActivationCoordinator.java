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
        startHttpServer(context.httpServer(), context.port());

        AeronClusterConfig aeronConfig = context.aeronClusterService() != null
            ? context.aeronClusterService().getConfig()
            : null;

        ConsensusStartupCoordinator.StartupOutcome consensusStartup = consensusStartupStarter.initialize(
            new ConsensusStartupCoordinator.StartupContext(
                context.port(),
                context.fileStore(),
                context.nodeStore(),
                context.httpServer(),
                context.wallet(),
                context.storeDirectory(),
                context.blobStore(),
                context.aeronClusterService(),
                context.componentFactory(),
                context.clusterWalletAddress(),
                aeronConfig
            )
        );

        if (consensusStartup.disposition() == ConsensusStartupCoordinator.StartupDisposition.DISABLED) {
            log.info("ℹ️  Consensus disabled (single-validator mode)");
        }

        return new ActivationResult(consensusStartup.aeronClusterService(), consensusStartup.launcher());
    }

    private static void startHttpServer(SegmentHttpServer httpServer, int port) throws IOException {
        log.info("Starting HTTP server...");
        try {
            httpServer.start();
            log.info("✅ HTTP server started on port {}", port);
        } catch (Exception e) {
            throw new IOException("Failed to start HTTP server", e);
        }
    }

    @FunctionalInterface
    interface ConsensusStartupStarter {
        ConsensusStartupCoordinator.StartupOutcome initialize(ConsensusStartupCoordinator.StartupContext context)
            throws IOException;
    }

    record ActivationContext(int port,
                             FileStore fileStore,
                             NodeStore nodeStore,
                             SegmentHttpServer httpServer,
                             EthereumWallet wallet,
                             String storeDirectory,
                             BlobStore blobStore,
                             AeronClusterService aeronClusterService,
                             GlobalStoreServerComponentFactory componentFactory,
                             String clusterWalletAddress) {
    }

    record ActivationResult(AeronClusterService aeronClusterService, AeronClusterLauncher launcher) {
    }
}
