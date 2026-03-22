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

import org.apache.jackrabbit.oak.segment.consensus.bootstrap.ValidatorBootstrap;
import org.apache.jackrabbit.oak.segment.consensus.bootstrap.ValidatorBootstrap.BootstrapMode;
import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.apache.jackrabbit.oak.spi.blob.BlobStore;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.apache.jackrabbit.oak.spi.state.NodeStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class GenesisStartupCoordinator {

    private static final long GENESIS_FALLBACK_SIZE_THRESHOLD_BYTES = 1024L * 1024L;
    private static final Logger log = LoggerFactory.getLogger(GenesisStartupCoordinator.class);

    void initialize(StartupContext context) {
        if (context.mode == BootstrapMode.GENESIS) {
            startStandbyServer(context.bootstrap, context.standbyPort);
            return;
        }
        if (context.mode == BootstrapMode.PRIMARY) {
            verifyOrDeferGenesis(context);
        }
    }

    private void verifyOrDeferGenesis(StartupContext context) {
        try {
            if (genesisExists(context.nodeStore)) {
                log.info("   ℹ️  Genesis exists - verifying integrity...");
                context.componentFactory
                    .createGenesisInitializer(context.nodeStore, context.fileStore, context.blobStore, context.selfUrl)
                    .initializeGenesisContent();
            } else {
                log.info("   ⏭️  Genesis does not exist - will be created by elected leader via consensus");
                log.info("   ⏭️  Skipping genesis initialization at startup");
            }
        } catch (Exception e) {
            fallbackToStoreSizeHeuristic(context);
        }
    }

    private static boolean genesisExists(NodeStore nodeStore) {
        NodeState root = nodeStore.getRoot();
        return root.getChildNode("oak-chain")
            .getChildNode("content")
            .getChildNode("00")
            .getChildNode("00")
            .getChildNode("00")
            .getChildNode("0x0000000000000000000000000000000000000000")
            .getChildNode("genesis")
            .exists();
    }

    private static void startStandbyServer(ValidatorBootstrap bootstrap, int standbyPort) {
        if (bootstrap == null) {
            return;
        }
        try {
            bootstrap.startStandbyServer();
            log.info("✅ StandbyServerSync started on port {}", standbyPort);
            log.info("   Other validators can bootstrap from empty store (will sync genesis after creation)");
        } catch (Exception e) {
            log.warn("⚠️  Failed to start StandbyServerSync: {}", e.getMessage());
            log.warn("   Other validators will not be able to bootstrap from this node");
        }
    }

    private void fallbackToStoreSizeHeuristic(StartupContext context) {
        try {
            long storeSize = context.fileStore.size();
            if (storeSize > GENESIS_FALLBACK_SIZE_THRESHOLD_BYTES) {
                log.info("   ℹ️  Store has data ({} MB) - verifying genesis...", storeSize / (1024 * 1024));
                context.componentFactory
                    .createGenesisInitializer(context.nodeStore, context.fileStore, context.blobStore, context.selfUrl)
                    .initializeGenesisContent();
            } else {
                log.info("   ⏭️  Store is empty or minimal - skipping genesis (will be created by consensus)");
            }
        } catch (Exception ignored) {
            log.warn("   ⚠️  Could not check store state, skipping genesis init (will be created by consensus)");
        }
    }

    static final class StartupContext {
        private final BootstrapMode mode;
        private final ValidatorBootstrap bootstrap;
        private final int standbyPort;
        private final NodeStore nodeStore;
        private final FileStore fileStore;
        private final BlobStore blobStore;
        private final String selfUrl;
        private final GlobalStoreServerComponentFactory componentFactory;

        StartupContext(BootstrapMode mode,
                       ValidatorBootstrap bootstrap,
                       int standbyPort,
                       NodeStore nodeStore,
                       FileStore fileStore,
                       BlobStore blobStore,
                       String selfUrl,
                       GlobalStoreServerComponentFactory componentFactory) {
            this.mode = mode;
            this.bootstrap = bootstrap;
            this.standbyPort = standbyPort;
            this.nodeStore = nodeStore;
            this.fileStore = fileStore;
            this.blobStore = blobStore;
            this.selfUrl = selfUrl;
            this.componentFactory = componentFactory;
        }
    }
}
