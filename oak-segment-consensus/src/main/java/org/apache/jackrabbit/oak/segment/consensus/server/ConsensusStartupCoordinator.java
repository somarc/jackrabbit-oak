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
import org.apache.jackrabbit.oak.segment.consensus.aeron.AeronClusterLauncher;
import org.apache.jackrabbit.oak.segment.consensus.aeron.AeronClusterService;
import org.apache.jackrabbit.oak.segment.consensus.aeron.AeronConsensusEngine;
import org.apache.jackrabbit.oak.segment.consensus.config.RuntimeConfigValueResolver;
import org.apache.jackrabbit.oak.segment.consensus.security.EthereumWallet;
import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.apache.jackrabbit.oak.segment.http.server.SegmentHttpServer;
import org.apache.jackrabbit.oak.spi.blob.BlobStore;
import org.apache.jackrabbit.oak.spi.state.NodeStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class ConsensusStartupCoordinator {

    private static final Logger log = LoggerFactory.getLogger(ConsensusStartupCoordinator.class);

    StartupOutcome initialize(StartupContext context) throws IOException {
        String consensusEnabled = RuntimeConfigValueResolver.readString("consensus.enabled", "false");
        String peersConfig = RuntimeConfigValueResolver.readString("consensus.peers", "");
        AeronClusterConfig aeronConfig = context.aeronConfig;
        String selfUrl = GlobalStoreRuntimeConfigUtil.resolveSelfUrl(context.port, aeronConfig);
        List<String> peerUrls = resolvePeerUrls(aeronConfig, peersConfig);

        boolean enableConsensus = "true".equalsIgnoreCase(consensusEnabled)
            && (context.aeronMode || !peersConfig.isEmpty());
        if (aeronConfig != null && !aeronConfig.enabled()) {
            enableConsensus = false;
        }

        if (context.standbyMode) {
            return new StartupOutcome(
                StartupDisposition.DEFERRED,
                context.aeronClusterService,
                null,
                selfUrl,
                peerUrls
            );
        }

        if (!enableConsensus) {
            return new StartupOutcome(
                StartupDisposition.DISABLED,
                context.aeronClusterService,
                null,
                selfUrl,
                peerUrls
            );
        }

        if (!context.aeronMode) {
            throw new IllegalStateException("Aeron mode validation failed - this should not happen");
        }

        log.info("Initializing Consensus Engine...");
        log.info("   Mode: AERON (Aeron Cluster Raft)");
        log.info("   ✈️  Using Aeron Cluster Consensus (Raft)");
        log.info("      - Proven Raft consensus algorithm");
        log.info("      - Election safety guarantees");
        log.info("      - Majority quorum requirements");
        log.info("      - High performance, low latency");

        AeronClusterService clusterService = context.aeronClusterService;
        if (clusterService == null) {
            log.warn("⚠️  AeronClusterService not configured (OSGi) - using standalone instance");
            clusterService = context.componentFactory.createAeronClusterService();
        }

        boolean observeElections = aeronConfig != null && aeronConfig.observeElections();
        boolean logClusterStateDetails = aeronConfig != null && aeronConfig.logClusterStateDetails();
        String beaconApiUrl = GlobalStoreRuntimeConfigUtil.resolveBeaconApiUrl(aeronConfig);

        AeronClusterStartupResult startupResult = clusterService.startCluster(
            context.fileStore,
            context.nodeStore,
            context.httpServer,
            context.wallet,
            context.storeDirectory,
            context.blobStore,
            selfUrl,
            peerUrls,
            observeElections,
            logClusterStateDetails
        );

        AeronConsensusEngine aeronEngine = startupResult.getAeronEngine();
        context.componentFactory.createConsensusServicesInitializer().initialize(
            aeronEngine,
            context.httpServer,
            context.wallet,
            context.storeDirectory,
            beaconApiUrl,
            context.clusterWalletAddress,
            startupResult.getHostnames()
        );

        log.info("✅ Aeron Cluster Consensus engine initialized");
        log.info("   - Model: Raft-based consensus (Aeron Cluster)");
        log.info("   - Node ID: {}", startupResult.getNodeId());
        log.info("   - Total validators: {}", startupResult.getHostnames().size());
        log.info("   - Current role: {}", aeronEngine.getCurrentRole());
        log.info("   - Current leader: {}", aeronEngine.getCurrentLeader());
        log.info("   - Ethereum epoch: {}", aeronEngine.getCurrentEthereumEpoch());

        return new StartupOutcome(
            StartupDisposition.INITIALIZED,
            clusterService,
            startupResult.getLauncher(),
            selfUrl,
            peerUrls
        );
    }

    private static List<String> resolvePeerUrls(AeronClusterConfig aeronConfig, String peersConfig) {
        List<String> peerUrls = GlobalStoreRuntimeConfigUtil.resolvePeerUrls(aeronConfig);
        if (!peerUrls.isEmpty()) {
            return peerUrls;
        }
        return ServerNetworkUtil.parsePeerUrls(peersConfig);
    }

    static final class StartupContext {
        private final int port;
        private final boolean aeronMode;
        private final boolean standbyMode;
        private final FileStore fileStore;
        private final NodeStore nodeStore;
        private final SegmentHttpServer httpServer;
        private final EthereumWallet wallet;
        private final String storeDirectory;
        private final BlobStore blobStore;
        private final AeronClusterService aeronClusterService;
        private final GlobalStoreServerComponentFactory componentFactory;
        private final String clusterWalletAddress;
        private final AeronClusterConfig aeronConfig;

        StartupContext(int port,
                       boolean aeronMode,
                       boolean standbyMode,
                       FileStore fileStore,
                       NodeStore nodeStore,
                       SegmentHttpServer httpServer,
                       EthereumWallet wallet,
                       String storeDirectory,
                       BlobStore blobStore,
                       AeronClusterService aeronClusterService,
                       GlobalStoreServerComponentFactory componentFactory,
                       String clusterWalletAddress,
                       AeronClusterConfig aeronConfig) {
            this.port = port;
            this.aeronMode = aeronMode;
            this.standbyMode = standbyMode;
            this.fileStore = fileStore;
            this.nodeStore = nodeStore;
            this.httpServer = httpServer;
            this.wallet = wallet;
            this.storeDirectory = storeDirectory;
            this.blobStore = blobStore;
            this.aeronClusterService = aeronClusterService;
            this.componentFactory = componentFactory;
            this.clusterWalletAddress = clusterWalletAddress;
            this.aeronConfig = aeronConfig;
        }
    }

    enum StartupDisposition {
        INITIALIZED,
        DEFERRED,
        DISABLED
    }

    static final class StartupOutcome {
        private final StartupDisposition disposition;
        private final AeronClusterService aeronClusterService;
        private final AeronClusterLauncher launcher;
        private final String selfUrl;
        private final List<String> peerUrls;

        StartupOutcome(StartupDisposition disposition,
                       AeronClusterService aeronClusterService,
                       AeronClusterLauncher launcher,
                       String selfUrl,
                       List<String> peerUrls) {
            this.disposition = disposition;
            this.aeronClusterService = aeronClusterService;
            this.launcher = launcher;
            this.selfUrl = selfUrl;
            this.peerUrls = Collections.unmodifiableList(new ArrayList<>(peerUrls));
        }

        StartupDisposition getDisposition() {
            return disposition;
        }

        AeronClusterService getAeronClusterService() {
            return aeronClusterService;
        }

        AeronClusterLauncher getLauncher() {
            return launcher;
        }

        String getSelfUrl() {
            return selfUrl;
        }

        List<String> getPeerUrls() {
            return peerUrls;
        }
    }
}
