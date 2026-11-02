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

import java.io.File;
import java.io.IOException;
import java.util.List;

import org.apache.jackrabbit.oak.segment.consensus.config.RuntimeConfigValueResolver;
import org.apache.jackrabbit.oak.segment.consensus.aeron.AeronClusterLauncher;
import org.apache.jackrabbit.oak.segment.consensus.aeron.AeronConsensusEngine;
import org.apache.jackrabbit.oak.segment.consensus.binarygc.BinaryGcConfig;
import org.apache.jackrabbit.oak.segment.consensus.binarygc.BinaryGcCoordinator;
import org.apache.jackrabbit.oak.segment.consensus.binarygc.BinaryGcStatus;
import org.apache.jackrabbit.oak.segment.consensus.binarygc.BinaryRecordDeleter;
import org.apache.jackrabbit.oak.segment.consensus.config.StorageBackendConfig;
import org.apache.jackrabbit.oak.segment.consensus.federation.FederationConfig;
import org.apache.jackrabbit.oak.segment.consensus.federation.FederationCoordinator;
import org.apache.jackrabbit.oak.segment.consensus.security.EthereumWallet;
import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.apache.jackrabbit.oak.segment.http.server.SegmentHttpServer;
import org.apache.jackrabbit.oak.segment.http.server.ServerContext;
import org.apache.jackrabbit.oak.segment.http.server.binary.CidMappingService;
import org.apache.jackrabbit.oak.spi.blob.BlobStore;
import org.apache.jackrabbit.oak.spi.state.NodeStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AeronClusterBootstrapper {

    private static final Logger log = LoggerFactory.getLogger(AeronClusterBootstrapper.class);

    private final FileStore fileStore;
    private final NodeStore nodeStore;
    private final SegmentHttpServer httpServer;
    private final EthereumWallet wallet;
    private final String storeDirectory;
    private final BlobStore blobStore;

    public AeronClusterBootstrapper(FileStore fileStore,
                                    NodeStore nodeStore,
                                    SegmentHttpServer httpServer,
                                    EthereumWallet wallet,
                                    String storeDirectory,
                                    BlobStore blobStore) {
        this.fileStore = fileStore;
        this.nodeStore = nodeStore;
        this.httpServer = httpServer;
        this.wallet = wallet;
        this.storeDirectory = storeDirectory;
        this.blobStore = blobStore;
    }

    public AeronClusterStartupResult startCluster(String selfUrl,
                                                  List<String> peerUrls,
                                                  boolean observeElections,
                                                  boolean logClusterStateDetails) throws IOException {
        // Get node ID from system property (default: 0)
        int nodeId = Integer.parseInt(System.getProperty("aeron.cluster.nodeId", "0"));

        // 🌐 DYNAMIC CLUSTER SIZE: Start with just self, discover peers organically
        String hostnamesConfig = RuntimeConfigValueResolver.readString("aeron.cluster.hostnames", "");
        AeronClusterBootstrapPlan bootstrapPlan = AeronClusterBootstrapPlan.create(
            nodeId,
            selfUrl,
            peerUrls,
            storeDirectory,
            hostnamesConfig
        );
        List<String> hostnamesList = bootstrapPlan.hostnames;
        boolean hasExistingCluster = bootstrapPlan.hasExistingCluster;

        if (logClusterStateDetails) {
            log.info("🔍 DEBUG: Cluster state check:");
            log.info("   - Cluster dir exists: {}", bootstrapPlan.clusterDirExists);
            log.info("   - Cluster dir path: {}", bootstrapPlan.clusterDir.getAbsolutePath());
            if (bootstrapPlan.clusterDirExists) {
                log.info("   - Cluster dir files: {}", bootstrapPlan.clusterDirFileCount);
            }
            log.info("   - hasExistingCluster: {}", hasExistingCluster);
        }

        if (bootstrapPlan.startupMode == AeronClusterBootstrapPlan.StartupMode.EXISTING_CLUSTER) {
            log.info("🌐 Existing cluster detected - will join with {} members", hostnamesList.size());
        } else if (bootstrapPlan.startupMode == AeronClusterBootstrapPlan.StartupMode.FRESH_CONFIGURED) {
            log.info("🌐 Fresh cluster start - using configured hostnames ({} members)", hostnamesList.size());
            if (logClusterStateDetails) {
                log.info("   -> Starting with self only (quorum = 1), peers will join dynamically");
            }
        } else {
            log.info("🌐 Fresh cluster start - starting with self only (quorum = 1)");
            if (logClusterStateDetails) {
                log.info("   -> Peers can join dynamically as they come online");
            }
        }

        AeronConsensusEngine aeronEngine =
            new AeronConsensusEngine(fileStore, nodeStore, selfUrl, peerUrls, wallet, storeDirectory, blobStore);
        aeronEngine.setClusterMemberCount(hostnamesList.size());

        aeronEngine.initializeEthereumIntegration();

        // Create cluster base directory
        File clusterBaseDir = bootstrapPlan.clusterBaseDir;
        clusterBaseDir.mkdirs();

        // Build node ID to URL mapping for leader lookup
        aeronEngine.setNodeIdMapping(bootstrapPlan.nodeIdToUrl);

        new AeronClusterCallbackBinder().bind(aeronEngine, httpServer);

        // Wire Aeron engine to HTTP server context
        httpServer.setAeronConsensusEngine(aeronEngine);
        startFederationCoordinator(aeronEngine, httpServer.getContext());
        startBinaryGc(aeronEngine, httpServer.getContext(), fileStore, blobStore);

        AeronClusterLauncher aeronClusterLauncher =
            new AeronClusterLaunchCoordinator().launch(nodeId, hostnamesList, clusterBaseDir, aeronEngine);

        if (observeElections && !hasExistingCluster && hostnamesList.size() >= 3) {
            log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            log.info("🔄 STARTUP ELECTION OBSERVATION");
            log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            log.info("Purpose: Observe elections for 15s to verify all {} nodes can participate",
                hostnamesList.size());
            log.info("         before performing critical genesis writes");

            try {
                new AeronClusterElectionObserver().observe(aeronEngine, 15000);
                log.info("✅ Election observation complete - cluster verified healthy");
                log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            } catch (Exception e) {
                log.warn("⚠️  Election observation failed: {}", e.getMessage());
                log.warn("   Proceeding with genesis, but cluster health uncertain");
            }
        }

        new AeronClusterRuntimeAttacher().attach(httpServer, aeronClusterLauncher);

        return new AeronClusterStartupResult(aeronEngine, aeronClusterLauncher, hostnamesList, nodeId);
    }

    /**
     * Runs the charter/directory coordinator beside the engine. Invalid federation settings fail startup:
     * a member must not join a cluster expecting a charter it cannot express.
     */
    private static void startFederationCoordinator(AeronConsensusEngine aeronEngine, ServerContext context) {
        FederationConfig config;
        try {
            config = FederationConfig.fromRuntime();
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Invalid federation configuration: " + e.getMessage(), e);
        }
        FederationCoordinator coordinator = FederationCoordinator.fromConfig(config, context::authoritativeRoot,
            new FederationCoordinator.Proposer() {
                @Override
                public boolean canPropose() {
                    return aeronEngine.canProposeClusterCommands();
                }

                @Override
                public boolean propose(int templateId, String json) {
                    return aeronEngine.sendFederationThroughIngress(templateId, json);
                }
            });
        context.federationCoordinator = coordinator;
        coordinator.start(config.getPollMs());
        log.info("Federation coordinator started (enterprise: {}, directory source: {})",
            config.getCharter() != null ? config.getCharter().getEnterpriseId() : "none",
            config.getDirectoryFile() != null ? config.getDirectoryFile() : "none");
    }

    /**
     * Runs binary GC beside the engine: the leader marks and proposes releases, and every member deletes the
     * records it releases. Applying a release does not depend on this; invalid settings fail startup.
     */
    static void startBinaryGc(AeronConsensusEngine aeronEngine, ServerContext context, FileStore fileStore,
                              BlobStore blobStore) {
        BinaryGcConfig config;
        try {
            config = BinaryGcConfig.fromRuntime();
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Invalid binary GC configuration: " + e.getMessage(), e);
        }
        if (!config.enabled()) {
            context.binaryGcStatus = null;
            log.info("Binary GC disabled: releases are applied, but this member neither proposes nor deletes");
            return;
        }
        BinaryGcStatus status = new BinaryGcStatus(config, StorageBackendConfig.ipfsClusterUnpinOnDeleteFromRuntime());
        CidMappingService cids = context.cidMappingService;
        BinaryRecordDeleter deleter = new BinaryRecordDeleter(BinaryRecordDeleter.recordsOf(blobStore),
            context::authoritativeRoot, status, config.deleteDelayMs(), cids != null ? cids::removeMapping : null,
            System::currentTimeMillis);
        BinaryGcCoordinator coordinator = new BinaryGcCoordinator(config, context::authoritativeRoot,
            collector -> {
                // References in segments still being written are only visible after a flush.
                fileStore.flush();
                fileStore.collectBlobReferences(collector);
            },
            new BinaryGcCoordinator.Proposer() {
                @Override
                public boolean canPropose() {
                    return aeronEngine.canProposeClusterCommands();
                }

                @Override
                public boolean propose(long markedAtPosition, long maxLastModified, List<String> blobIds) {
                    return aeronEngine.sendBinaryReleaseThroughIngress(markedAtPosition, maxLastModified, blobIds);
                }
            },
            System::currentTimeMillis, status);
        aeronEngine.setBinaryGc(deleter::onReleased, () -> {
            coordinator.close();
            deleter.close();
        });
        coordinator.start();
        context.binaryGcStatus = status::toMap;
        log.info("Binary GC started (interval {} ms, grace {} ms, delete delay {} ms)", config.intervalMs(),
            config.graceMs(), config.deleteDelayMs());
    }
}
