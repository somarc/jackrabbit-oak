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
import java.io.Closeable;
import java.io.IOException;

import org.apache.jackrabbit.oak.blob.cloud.ipfs.IPFSDataStore;
import org.apache.jackrabbit.oak.plugins.blob.datastore.DataStoreBlobStore;
import org.apache.jackrabbit.oak.segment.consensus.aeron.AeronClusterConfig;
import org.apache.jackrabbit.oak.segment.consensus.config.StorageBackendConfig;
import org.apache.jackrabbit.oak.segment.consensus.federation.FederationConfig;
import org.apache.jackrabbit.oak.segment.consensus.gc.RevisionGcScheduler;
import org.apache.jackrabbit.oak.segment.consensus.mount.FederatedReadView;
import org.apache.jackrabbit.oak.segment.consensus.mount.RemoteMounts;
import org.apache.jackrabbit.oak.segment.consensus.service.ReplicatedBinaryIndex;
import org.apache.jackrabbit.oak.segment.consensus.sharding.ShardingRuntimeConfig;
import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.apache.jackrabbit.oak.segment.file.InvalidFileStoreVersionException;
import org.apache.jackrabbit.oak.segment.http.server.SegmentHttpServer;
import org.apache.jackrabbit.oak.segment.http.server.ServerContext;
import org.apache.jackrabbit.oak.segment.http.server.binary.CidMappingService;
import org.apache.jackrabbit.oak.spi.blob.BlobStore;
import org.apache.jackrabbit.oak.spi.state.NodeStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class ServerInfrastructureInitializer {

    private static final Logger log = LoggerFactory.getLogger(ServerInfrastructureInitializer.class);
    private final BlobStoreStartupCoordinator blobStoreStartupCoordinator = new BlobStoreStartupCoordinator();

    InitializationResult initialize(File storeDir,
                                    int port,
                                    AeronClusterConfig aeronConfig,
                                    GlobalStoreServerComponentFactory componentFactory)
            throws IOException, InvalidFileStoreVersionException {
        StorageBackendConfig storageConfig = StorageBackendConfig.load();
        log.info("Storage: segment={}, blob={}", storageConfig.getSegmentBackend(), storageConfig.getBlobBackend());

        BlobStoreStartupCoordinator.StartupResult blobStoreStartup =
            blobStoreStartupCoordinator.initialize(storeDir, storageConfig, componentFactory);
        BlobStore blobStore = blobStoreStartup.blobStore();
        String blobStoreType = blobStoreStartup.blobStoreType();

        ServerStorageRuntime storageRuntime = StorageBackendFactory.createStorageRuntime(storeDir, blobStore, storageConfig);
        FileStore fileStore = storageRuntime.fileStore();
        NodeStore authoritativeNodeStore = storageRuntime.authoritativeNodeStore();
        NodeStore readViewNodeStore = storageRuntime.readViewNodeStore();
        Closeable readViewResources = storageRuntime.readViewResources();
        attachReplicatedBinaryIndex(blobStore, authoritativeNodeStore);

        log.info("✅ Oak FileStore initialized");
        log.info("   - Store version: {}", fileStore.getHead().getRecordId());
        log.info("   - Segments: {}", storeDir.getAbsolutePath());

        SegmentHttpServer httpServer = initializeHttpServer(storeDir, port, aeronConfig, componentFactory,
            blobStore, blobStoreType, fileStore, readViewNodeStore, authoritativeNodeStore, storageRuntime.remoteMounts());
        if (readViewResources instanceof FederatedReadView) {
            ServerContext context = httpServer.getContext();
            ((FederatedReadView) readViewResources).start(FederationConfig.pollMsFromRuntime(),
                mounts -> context.remoteMounts = mounts);
        }

        RevisionGcScheduler revisionGc = new RevisionGcScheduler(storageRuntime.gcConfig(), fileStore,
            storageRuntime.gcMonitor(), RevisionGcScheduler.genesisCommitted(authoritativeNodeStore));
        httpServer.getContext().segmentGcStatus = revisionGc::status;

        log.info("✅ HTTP server initialized (not yet started)");
        return new InitializationResult(blobStoreType, blobStore, fileStore, authoritativeNodeStore, readViewNodeStore,
            readViewResources, httpServer, revisionGc);
    }

    /**
     * Lets the IPFS DataStore resolve binaries stored through other validators' IPFS nodes from the
     * replicated binary index.
     */
    static void attachReplicatedBinaryIndex(BlobStore blobStore, NodeStore authoritativeNodeStore) {
        if (blobStore instanceof DataStoreBlobStore
                && ((DataStoreBlobStore) blobStore).getDataStore() instanceof IPFSDataStore) {
            ((IPFSDataStore) ((DataStoreBlobStore) blobStore).getDataStore())
                .setCidResolver(new ReplicatedBinaryIndex(authoritativeNodeStore));
            log.info("   - IPFS binaries stored by other validators resolve through the replicated binary index");
        }
    }

    private SegmentHttpServer initializeHttpServer(File storeDir,
                                                   int port,
                                                   AeronClusterConfig aeronConfig,
                                                   GlobalStoreServerComponentFactory componentFactory,
                                                   BlobStore blobStore,
                                                   String blobStoreType,
                                                   FileStore fileStore,
                                                   NodeStore readViewNodeStore,
                                                   NodeStore authoritativeNodeStore,
                                                   RemoteMounts remoteMounts) {
        log.info("Initializing HTTP server on port {}...", port);
        SegmentHttpServer httpServer = componentFactory.createHttpServer(storeDir, port, fileStore, readViewNodeStore);
        ServerContext context = httpServer.getContext();
        String selfUrl = GlobalStoreRuntimeConfigUtil.resolveSelfUrl(port, aeronConfig);

        if (GlobalStoreRuntimeConfigUtil.isConfiguredSelfUrl(aeronConfig)) {
            log.info("   Using configured self URL: {}", selfUrl);
        } else {
            log.info("   Resolved self URL to IP: {}", selfUrl);
        }

        httpServer.setSelfUrl(selfUrl);
        context.setAuthoritativeNodeStore(authoritativeNodeStore);
        context.remoteMounts = remoteMounts != null ? remoteMounts : RemoteMounts.NONE;
        context.setShardingRuntimeConfig(ShardingRuntimeConfig.load());

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
        log.info("Initializing CID Mapping Service...");
        try {
            CidMappingService cidMappingService = componentFactory.createCidMappingService(storeDir.toPath());
            context.cidMappingService = cidMappingService;
            log.info("✅ CID Mapping Service initialized");
            log.info("   - Maps Oak blob IDs ↔ IPFS CIDs");
            log.info("   - Persistence: {}/cid-mappings.properties", storeDir.getAbsolutePath());
            log.info("   - API: /api/cid/{oakBlobId} -> IPFS CID lookup");
        } catch (Exception e) {
            log.warn("⚠️  Failed to initialize CID Mapping Service: {}", e.getMessage());
        }
    }

    record InitializationResult(String blobStoreType,
                                BlobStore blobStore,
                                FileStore fileStore,
                                NodeStore nodeStore,
                                NodeStore readViewNodeStore,
                                Closeable readViewResources,
                                SegmentHttpServer httpServer,
                                RevisionGcScheduler revisionGc) {
    }
}
