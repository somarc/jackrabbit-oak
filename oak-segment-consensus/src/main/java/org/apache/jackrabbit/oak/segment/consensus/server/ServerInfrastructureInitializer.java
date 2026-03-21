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
import java.math.BigDecimal;
import java.util.List;
import java.util.function.Supplier;

import org.apache.jackrabbit.oak.segment.consensus.aeron.AeronClusterConfig;
import org.apache.jackrabbit.oak.segment.consensus.aeron.AeronConsensusEngine;
import org.apache.jackrabbit.oak.segment.consensus.config.RuntimeConfigValueResolver;
import org.apache.jackrabbit.oak.segment.consensus.fragmentation.FragmentationTracker;
import org.apache.jackrabbit.oak.segment.consensus.fragmentation.WalletStorageMetrics;
import org.apache.jackrabbit.oak.segment.consensus.gc.GCAccountManager;
import org.apache.jackrabbit.oak.segment.consensus.gc.GCCostEstimator;
import org.apache.jackrabbit.oak.segment.consensus.gc.GCProposalManager;
import org.apache.jackrabbit.oak.segment.consensus.gc.PeriodicGCJob;
import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.apache.jackrabbit.oak.segment.file.InvalidFileStoreVersionException;
import org.apache.jackrabbit.oak.segment.http.server.SegmentHttpServer;
import org.apache.jackrabbit.oak.segment.http.server.ServerContext;
import org.apache.jackrabbit.oak.segment.http.server.binary.CidMappingService;
import org.apache.jackrabbit.oak.spi.blob.BlobStore;
import org.apache.jackrabbit.oak.spi.state.NodeStore;

final class ServerInfrastructureInitializer {

    private final BlobStoreStartupCoordinator blobStoreStartupCoordinator = new BlobStoreStartupCoordinator();

    InitializationResult initialize(File storeDir,
                                    int port,
                                    AeronClusterConfig aeronConfig,
                                    GlobalStoreServerComponentFactory componentFactory)
            throws IOException, InvalidFileStoreVersionException {
        BlobStoreStartupCoordinator.StartupResult blobStoreStartup =
            blobStoreStartupCoordinator.initialize(storeDir, componentFactory);
        BlobStore blobStore = blobStoreStartup.getBlobStore();
        String blobStoreType = blobStoreStartup.getBlobStoreType();

        ServerStorageRuntime storageRuntime = componentFactory.createStorageRuntime(storeDir, blobStore);
        FileStore fileStore = storageRuntime.getFileStore();
        NodeStore nodeStore = storageRuntime.getNodeStore();

        System.out.println("✅ Oak FileStore initialized");
        System.out.println("   - Store version: " + fileStore.getHead().getRecordId());
        System.out.println("   - Segments: " + storeDir.getAbsolutePath());

        GCCostEstimator gcCostEstimator = initializeGCCostEstimator(fileStore, componentFactory);
        SegmentHttpServer httpServer = initializeHttpServer(storeDir, port, aeronConfig, componentFactory,
            blobStore, blobStoreType, fileStore, nodeStore, gcCostEstimator);
        FragmentationTracker fragmentationTracker = initializeFragmentationTracker(httpServer, componentFactory);
        initializeWalletStorageMetrics(httpServer, fileStore, componentFactory);
        initializeGcConsensusSupport(httpServer, aeronConfig, componentFactory, fileStore, gcCostEstimator,
            fragmentationTracker);

        System.out.println("✅ HTTP server initialized (not yet started)");
        return new InitializationResult(blobStoreType, blobStore, fileStore, nodeStore, httpServer, gcCostEstimator);
    }

    private GCCostEstimator initializeGCCostEstimator(FileStore fileStore,
                                                      GlobalStoreServerComponentFactory componentFactory) {
        System.out.println("Initializing GC Cost Estimator...");
        try {
            BigDecimal usdcPerMB = new BigDecimal(RuntimeConfigValueResolver.readString("gc.usdc.per.mb", "0.10"));
            GCCostEstimator gcCostEstimator = componentFactory.createGCCostEstimator(
                fileStore,
                componentFactory.extractTarFiles(fileStore),
                usdcPerMB
            );

            System.out.println("✅ GC Cost Estimator initialized");
            System.out.println("   - USDC rate: $" + usdcPerMB + " per MB");
            return gcCostEstimator;
        } catch (Exception e) {
            System.err.println("⚠️  Failed to initialize GC Cost Estimator: " + e.getMessage());
            System.err.println("   GC cost estimation will not be available");
            return null;
        }
    }

    private SegmentHttpServer initializeHttpServer(File storeDir,
                                                   int port,
                                                   AeronClusterConfig aeronConfig,
                                                   GlobalStoreServerComponentFactory componentFactory,
                                                   BlobStore blobStore,
                                                   String blobStoreType,
                                                   FileStore fileStore,
                                                   NodeStore nodeStore,
                                                   GCCostEstimator gcCostEstimator) {
        System.out.println("Initializing HTTP server on port " + port + "...");
        SegmentHttpServer httpServer = componentFactory.createHttpServer(storeDir, port, fileStore, nodeStore);
        ServerContext context = httpServer.getContext();
        String selfUrl = GlobalStoreRuntimeConfigUtil.resolveSelfUrl(port, aeronConfig);

        if (GlobalStoreRuntimeConfigUtil.isConfiguredSelfUrl(aeronConfig)) {
            System.out.println("   Using configured self URL: " + selfUrl);
        } else {
            System.out.println("   Resolved self URL to IP: " + selfUrl);
        }

        httpServer.setSelfUrl(selfUrl);
        if (gcCostEstimator != null) {
            context.setGCCostEstimator(gcCostEstimator);
        }

        context.blobStoreType = blobStoreType;
        context.blobStore = blobStore;

        if ("ipfs".equalsIgnoreCase(blobStoreType)) {
            initializeCidMappingService(storeDir, componentFactory, context);
        }

        return httpServer;
    }

    private void initializeCidMappingService(File storeDir,
                                             GlobalStoreServerComponentFactory componentFactory,
                                             ServerContext context) {
        System.out.println("Initializing CID Mapping Service...");
        try {
            CidMappingService cidMappingService = componentFactory.createCidMappingService(storeDir.toPath());
            context.cidMappingService = cidMappingService;
            System.out.println("✅ CID Mapping Service initialized");
            System.out.println("   - Maps Oak blob IDs ↔ IPFS CIDs");
            System.out.println("   - Persistence: " + storeDir.getAbsolutePath() + "/cid-mappings.properties");
            System.out.println("   - API: /api/cid/{oakBlobId} → IPFS CID lookup");
        } catch (Exception e) {
            System.err.println("⚠️  Failed to initialize CID Mapping Service: " + e.getMessage());
        }
    }

    private FragmentationTracker initializeFragmentationTracker(SegmentHttpServer httpServer,
                                                                GlobalStoreServerComponentFactory componentFactory) {
        System.out.println("Initializing Fragmentation Tracker...");
        try {
            FragmentationTracker fragmentationTracker = componentFactory.createFragmentationTracker();
            httpServer.getContext().setFragmentationTracker(fragmentationTracker);
            System.out.println("✅ Fragmentation Tracker initialized");
            System.out.println("   - Tracks TAR file creation per entity");
            System.out.println("   - Calculates fragmentation scores and taxes");
            return fragmentationTracker;
        } catch (Exception e) {
            System.err.println("⚠️  Failed to initialize Fragmentation Tracker: " + e.getMessage());
            System.err.println("   Fragmentation tracking will not be available");
            return null;
        }
    }

    private void initializeWalletStorageMetrics(SegmentHttpServer httpServer,
                                                FileStore fileStore,
                                                GlobalStoreServerComponentFactory componentFactory) {
        System.out.println("Initializing Wallet Storage Metrics...");
        try {
            WalletStorageMetrics walletStorageMetrics = componentFactory.createWalletStorageMetrics(fileStore);
            httpServer.getContext().setWalletStorageMetrics(walletStorageMetrics);
            System.out.println("✅ Wallet Storage Metrics initialized");
            System.out.println("   - Tracks per-wallet storage ownership %");
            System.out.println("   - Calculates storage tax and delete tax");
            System.out.println("   - Monitors capacity (2 TB upper bound)");
        } catch (Exception e) {
            System.err.println("⚠️  Failed to initialize Wallet Storage Metrics: " + e.getMessage());
            System.err.println("   Storage metrics will not be available");
        }
    }

    private void initializeGcConsensusSupport(SegmentHttpServer httpServer,
                                              AeronClusterConfig aeronConfig,
                                              GlobalStoreServerComponentFactory componentFactory,
                                              FileStore fileStore,
                                              GCCostEstimator gcCostEstimator,
                                              FragmentationTracker fragmentationTracker) {
        System.out.println("Initializing GC Proposal Manager...");
        try {
            ServerContext context = httpServer.getContext();
            List<String> configuredPeers = GlobalStoreRuntimeConfigUtil.resolvePeerUrls(aeronConfig);
            int totalValidators = configuredPeers.isEmpty() ? 1 : configuredPeers.size() + 1;
            Supplier<Integer> executorIdSupplier = createExecutorIdSupplier(context);
            Supplier<Boolean> isLeaderSupplier = createLeaderSupplier(context);

            GCProposalManager gcProposalManager = componentFactory.createGCProposalManager(
                fileStore,
                gcCostEstimator,
                fragmentationTracker,
                context.evmBridge,
                totalValidators,
                executorIdSupplier,
                isLeaderSupplier
            );

            context.setGCProposalManager(gcProposalManager);

            System.out.println("✅ GC Proposal Manager initialized");
            System.out.println("   - Total validators: " + totalValidators);
            System.out.println("   - Quorum required: " + ((totalValidators * 2 / 3) + 1) + "/" + totalValidators);
            System.out.println("   - Tracks GC proposals, voting, and execution");

            GCAccountManager gcAccountManager = componentFactory.createGCAccountManager();
            context.gcAccountManager = gcAccountManager;

            System.out.println("✅ GC Account Manager initialized");
            System.out.println("   - Tracks GC debt per entity (wallet address)");
            System.out.println("   - Default debt limit: $100.00");
            System.out.println("   - Enforces write blocking when debt exceeds limit");

            PeriodicGCJob periodicGCJob = componentFactory.createPeriodicGCJob(gcAccountManager);
            periodicGCJob.start();
            context.periodicGCJob = periodicGCJob;

            System.out.println("✅ Periodic GC Job started");
            System.out.println("   - Interval: " + periodicGCJob.getIntervalSeconds() + "s");
            System.out.println("   - Initial delay: " + periodicGCJob.getInitialDelaySeconds() + "s");
            System.out.println("   - Action: Converts pending debt → executed debt");
            System.out.println("   - Blocks writes when executed debt > limit");
        } catch (Exception e) {
            System.err.println("⚠️  Failed to initialize GC Proposal Manager: " + e.getMessage());
            System.err.println("   GC consensus will not be available");
        }
    }

    private Supplier<Integer> createExecutorIdSupplier(ServerContext context) {
        return () -> {
            AeronConsensusEngine aeronEngine = context.aeronConsensusEngine;
            if (aeronEngine != null && aeronEngine.getCluster() != null) {
                try {
                    return aeronEngine.getCluster().memberId();
                } catch (Exception e) {
                    // Fall back to the standalone default below.
                }
            }
            return 0;
        };
    }

    private Supplier<Boolean> createLeaderSupplier(ServerContext context) {
        return () -> {
            AeronConsensusEngine aeronEngine = context.aeronConsensusEngine;
            if (aeronEngine != null) {
                return aeronEngine.isLeader();
            }
            return true;
        };
    }

    static final class InitializationResult {
        private final String blobStoreType;
        private final BlobStore blobStore;
        private final FileStore fileStore;
        private final NodeStore nodeStore;
        private final SegmentHttpServer httpServer;
        private final GCCostEstimator gcCostEstimator;

        InitializationResult(String blobStoreType,
                             BlobStore blobStore,
                             FileStore fileStore,
                             NodeStore nodeStore,
                             SegmentHttpServer httpServer,
                             GCCostEstimator gcCostEstimator) {
            this.blobStoreType = blobStoreType;
            this.blobStore = blobStore;
            this.fileStore = fileStore;
            this.nodeStore = nodeStore;
            this.httpServer = httpServer;
            this.gcCostEstimator = gcCostEstimator;
        }

        String getBlobStoreType() {
            return blobStoreType;
        }

        BlobStore getBlobStore() {
            return blobStore;
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

        GCCostEstimator getGcCostEstimator() {
            return gcCostEstimator;
        }
    }
}
