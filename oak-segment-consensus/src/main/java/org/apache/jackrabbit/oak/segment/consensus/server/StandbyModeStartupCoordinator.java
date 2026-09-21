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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.jackrabbit.oak.segment.consensus.aeron.AeronClusterConfig;
import org.apache.jackrabbit.oak.segment.consensus.aeron.AeronClusterService;
import org.apache.jackrabbit.oak.segment.consensus.bootstrap.ValidatorBootstrap;
import org.apache.jackrabbit.oak.segment.consensus.security.EthereumWallet;
import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.apache.jackrabbit.oak.segment.http.server.SegmentHttpServer;
import org.apache.jackrabbit.oak.spi.blob.BlobStore;
import org.apache.jackrabbit.oak.spi.state.NodeStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class StandbyModeStartupCoordinator {

    private static final Logger log = LoggerFactory.getLogger(StandbyModeStartupCoordinator.class);
    private final StandbyPromotionCoordinator standbyPromotionCoordinator = new StandbyPromotionCoordinator();

    StartupResult initialize(StartupContext context) throws IOException {
        String selfUrl = GlobalStoreRuntimeConfigUtil.resolveSelfUrl(context.getPort(), context.getAeronConfig());
        List<String> peerUrls = Collections.unmodifiableList(
            new ArrayList<>(GlobalStoreRuntimeConfigUtil.resolvePeerUrls(context.getAeronConfig()))
        );

        StandbyPromotionCoordinator.BootstrapTarget bootstrapTarget =
            standbyPromotionCoordinator.resolveBootstrapTarget(
                context.getBootstrapPrimaryHost(),
                context.getBootstrapPrimaryPort(),
                peerUrls,
                context.getPort()
            );

        if (bootstrapTarget == null) {
            throw new IOException("STANDBY mode requires bootstrap.primary.host or consensus.peers");
        }

        if (!context.hasConfiguredBootstrapPrimary() && !peerUrls.isEmpty()) {
            log.info("🔍 Using first peer as bootstrap primary: {}:{}",
                bootstrapTarget.getHost(), bootstrapTarget.getPort());
        }

        standbyPromotionCoordinator.bootstrapAndPromote(
            context.getBootstrap(),
            bootstrapTarget,
            () -> promote(context, selfUrl, peerUrls)
        );

        return new StartupResult(selfUrl, peerUrls);
    }

    private void promote(StartupContext context, String selfUrl, List<String> peerUrls) {
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        log.info("🎖️  PROMOTED TO PRIMARY - Oak FileStore bootstrap complete");
        log.info("   Local HEAD: {}", context.getFileStore().getHead().getRecordId());
        log.info("   Starting Aeron Cluster...");
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        try {
            log.info("✈️  Starting Aeron Cluster (Oak FileStore already synced)");
            StandbyPromotionCoordinator.DeferredAeronStartup deferredStartup =
                standbyPromotionCoordinator.startDeferredCluster(
                    context.getComponentFactory(),
                    context.getAeronClusterService(),
                    new StandbyPromotionCoordinator.DeferredAeronStartupContext(
                        context.getFileStore(),
                        context.getNodeStore(),
                        context.getHttpServer(),
                        context.getWallet(),
                        context.getStoreDirectory(),
                        context.getBlobStore(),
                        selfUrl,
                        peerUrls
                    )
                );
            context.getDeferredStartupListener().onDeferredStartup(deferredStartup);

            log.info("Starting HTTP server (deferred from STANDBY mode)...");
            context.getHttpServer().start();
            log.info("✅ HTTP server started on port {}", context.getPort());
        } catch (Exception e) {
            log.error("❌ Failed to start Aeron Cluster after promotion: {}", e.getMessage(), e);
        }
    }

    interface DeferredStartupListener {
        void onDeferredStartup(StandbyPromotionCoordinator.DeferredAeronStartup deferredStartup);
    }

    static final class StartupContext {
        private final int port;
        private final String storeDirectory;
        private final String bootstrapPrimaryHost;
        private final int bootstrapPrimaryPort;
        private final AeronClusterConfig aeronConfig;
        private final ValidatorBootstrap bootstrap;
        private final FileStore fileStore;
        private final NodeStore nodeStore;
        private final SegmentHttpServer httpServer;
        private final EthereumWallet wallet;
        private final BlobStore blobStore;
        private final AeronClusterService aeronClusterService;
        private final GlobalStoreServerComponentFactory componentFactory;
        private final DeferredStartupListener deferredStartupListener;

        StartupContext(int port,
                       String storeDirectory,
                       String bootstrapPrimaryHost,
                       int bootstrapPrimaryPort,
                       AeronClusterConfig aeronConfig,
                       ValidatorBootstrap bootstrap,
                       FileStore fileStore,
                       NodeStore nodeStore,
                       SegmentHttpServer httpServer,
                       EthereumWallet wallet,
                       BlobStore blobStore,
                       AeronClusterService aeronClusterService,
                       GlobalStoreServerComponentFactory componentFactory,
                       DeferredStartupListener deferredStartupListener) {
            this.port = port;
            this.storeDirectory = storeDirectory;
            this.bootstrapPrimaryHost = bootstrapPrimaryHost;
            this.bootstrapPrimaryPort = bootstrapPrimaryPort;
            this.aeronConfig = aeronConfig;
            this.bootstrap = bootstrap;
            this.fileStore = fileStore;
            this.nodeStore = nodeStore;
            this.httpServer = httpServer;
            this.wallet = wallet;
            this.blobStore = blobStore;
            this.aeronClusterService = aeronClusterService;
            this.componentFactory = componentFactory;
            this.deferredStartupListener = deferredStartupListener;
        }

        int getPort() {
            return port;
        }

        String getStoreDirectory() {
            return storeDirectory;
        }

        String getBootstrapPrimaryHost() {
            return bootstrapPrimaryHost;
        }

        int getBootstrapPrimaryPort() {
            return bootstrapPrimaryPort;
        }

        boolean hasConfiguredBootstrapPrimary() {
            return bootstrapPrimaryHost != null && !bootstrapPrimaryHost.trim().isEmpty();
        }

        AeronClusterConfig getAeronConfig() {
            return aeronConfig;
        }

        ValidatorBootstrap getBootstrap() {
            return bootstrap;
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

        BlobStore getBlobStore() {
            return blobStore;
        }

        AeronClusterService getAeronClusterService() {
            return aeronClusterService;
        }

        GlobalStoreServerComponentFactory getComponentFactory() {
            return componentFactory;
        }

        DeferredStartupListener getDeferredStartupListener() {
            return deferredStartupListener;
        }
    }

    static final class StartupResult {
        private final String selfUrl;
        private final List<String> peerUrls;

        StartupResult(String selfUrl, List<String> peerUrls) {
            this.selfUrl = selfUrl;
            this.peerUrls = peerUrls;
        }

        String getSelfUrl() {
            return selfUrl;
        }

        List<String> getPeerUrls() {
            return peerUrls;
        }
    }
}
