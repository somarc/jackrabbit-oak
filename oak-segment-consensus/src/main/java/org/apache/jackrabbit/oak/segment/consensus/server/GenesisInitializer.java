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

import org.apache.jackrabbit.oak.segment.RecordId;
import org.apache.jackrabbit.oak.segment.consensus.genesis.CanonicalGenesisContent;
import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.apache.jackrabbit.oak.spi.blob.BlobStore;
import org.apache.jackrabbit.oak.spi.commit.CommitInfo;
import org.apache.jackrabbit.oak.spi.commit.EmptyHook;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;
import org.apache.jackrabbit.oak.spi.state.NodeStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class GenesisInitializer {

    private static final Logger log = LoggerFactory.getLogger(GenesisInitializer.class);

    private final NodeStore nodeStore;
    private final FileStore fileStore;
    private final String genesisValidatorUrl;
    private final CanonicalGenesisContent canonicalGenesisContent;

    GenesisInitializer(NodeStore nodeStore, FileStore fileStore, BlobStore blobStore, String genesisValidatorUrl) {
        this.nodeStore = nodeStore;
        this.fileStore = fileStore;
        this.genesisValidatorUrl = genesisValidatorUrl;
        this.canonicalGenesisContent = new CanonicalGenesisContent(nodeStore, blobStore);
    }

    void initializeGenesisContent() {
        try {
            if (canonicalGenesisContent.exists()) {
                log.info("   ℹ️  Canonical genesis already exists - verifying integrity...");
                canonicalGenesisContent.verifyExisting();
                log.info("   ✅ Canonical genesis integrity verified at {}", CanonicalGenesisContent.getGenesisPath());
                logGenesisHead();
                return;
            }

            log.info("   🎂 Creating canonical wallet-scoped genesis at {}", CanonicalGenesisContent.getGenesisPath());

            NodeBuilder rootBuilder = nodeStore.getRoot().builder();
            canonicalGenesisContent.populate(rootBuilder, System.currentTimeMillis(), genesisValidatorUrl);
            nodeStore.merge(rootBuilder, EmptyHook.INSTANCE, CommitInfo.EMPTY);

            log.info("   ✅ Canonical genesis created");
            logGenesisHead();
        } catch (Exception e) {
            log.error("   ❌ FATAL: Failed to create or verify canonical genesis: {}", e.getMessage(), e);
            throw new RuntimeException("Genesis creation failed - cannot start network", e);
        }
    }

    private void logGenesisHead() {
        RecordId genesisHead = fileStore.getHead().getRecordId();
        log.info("   Genesis HEAD: {}", genesisHead.toString10());
    }
}
