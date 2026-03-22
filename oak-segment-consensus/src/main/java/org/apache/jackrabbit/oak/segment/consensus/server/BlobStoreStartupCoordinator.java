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

import org.apache.jackrabbit.oak.segment.consensus.config.RuntimeConfigValueResolver;
import org.apache.jackrabbit.oak.spi.blob.BlobStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class BlobStoreStartupCoordinator {

    private static final Logger log = LoggerFactory.getLogger(BlobStoreStartupCoordinator.class);

    StartupResult initialize(File storeDir, GlobalStoreServerComponentFactory componentFactory) throws IOException {
        String blobStoreType = RuntimeConfigValueResolver.readString("blobstore.type", "BLOBSTORE_TYPE", "");

        if (blobStoreType.isEmpty()) {
            log.error(
                "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n"
                    + "❌ FATAL: blobstore.type is not configured\n"
                    + "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n"
                    + "\n"
                    + "Blockchain AEM requires IPFS DataStore for binary storage.\n"
                    + "FileDataStore is NOT supported (no fallback).\n"
                    + "\n"
                    + "FIX:\n"
                    + "  1. Ensure IPFS daemon is running:\n"
                    + "     $ ipfs daemon\n"
                    + "\n"
                    + "  2. Set blobstore.type=ipfs:\n"
                    + "     $ export BLOBSTORE_TYPE=ipfs\n"
                    + "     OR\n"
                    + "     $ java -Dblobstore.type=ipfs -jar oak-segment-consensus.jar\n"
                    + "\n"
                    + "  3. (Optional) Configure IPFS API endpoint:\n"
                    + "     $ export IPFS_API_ENDPOINT=/ip4/127.0.0.1/tcp/5001\n"
                    + "\n"
                    + "See: oak-segment-consensus/IPFS-DATASTORE.md\n"
                    + "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
            );
            throw startupFailure("blobstore.type is not configured");
        }

        if (!"ipfs".equalsIgnoreCase(blobStoreType)) {
            log.error(
                "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n"
                    + "❌ FATAL: Invalid blobstore.type = \"{}\"\n"
                    + "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n"
                    + "\n"
                    + "Only \"ipfs\" is supported.\n"
                    + "FileDataStore is NOT supported (blockchain-native storage required).\n"
                    + "\n"
                    + "FIX: Set blobstore.type=ipfs\n"
                    + "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━",
                blobStoreType
            );
            throw startupFailure("Invalid blobstore.type: " + blobStoreType);
        }

        log.info("📦 Configuring IPFS BlobStore for binaries...");

        try {
            String ipfsEndpoint = RuntimeConfigValueResolver.readString(
                "ipfs.api.endpoint",
                "IPFS_API_ENDPOINT",
                "/ip4/127.0.0.1/tcp/5001"
            );

            BlobStore blobStore = componentFactory.createIpfsBlobStore(ipfsEndpoint, storeDir);

            log.info("✅ IPFS BlobStore initialized");
            log.info("   - IPFS API: {}", ipfsEndpoint);
            log.info("   - Min size: 16 KB (smaller binaries inline in segments)");
            log.info("   - Storage: Decentralized (P2P replication)");
            log.info("   - Strategy: Oak segments (AEM compatible) + IPFS binaries (blockchain-native)");

            return new StartupResult("ipfs", blobStore);
        } catch (Exception e) {
            log.error(
                "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n"
                    + "❌ FATAL: Failed to initialize IPFS BlobStore\n"
                    + "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n"
                    + "\n"
                    + "Error: {}\n"
                    + "\n"
                    + "Common causes:\n"
                    + "  1. IPFS daemon not running\n"
                    + "     FIX: $ ipfs daemon\n"
                    + "\n"
                    + "  2. Wrong IPFS API endpoint\n"
                    + "     FIX: $ export IPFS_API_ENDPOINT=/ip4/127.0.0.1/tcp/5001\n"
                    + "\n"
                    + "  3. IPFS not initialized\n"
                    + "     FIX: $ ipfs init --profile server\n"
                    + "\n"
                    + "Verify IPFS:\n"
                    + "  $ ipfs version\n"
                    + "  $ ipfs id\n"
                    + "\n"
                    + "See: oak-segment-consensus/IPFS-DATASTORE.md\n"
                    + "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━",
                e.getMessage(),
                e
            );
            throw startupFailure("IPFS BlobStore initialization failed", e);
        }
    }

    private static IOException startupFailure(String message) {
        return new IOException(message);
    }

    private static IOException startupFailure(String message, Exception cause) {
        return new IOException(message, cause);
    }

    static final class StartupResult {
        private final String blobStoreType;
        private final BlobStore blobStore;

        StartupResult(String blobStoreType, BlobStore blobStore) {
            this.blobStoreType = blobStoreType;
            this.blobStore = blobStore;
        }

        String getBlobStoreType() {
            return blobStoreType;
        }

        BlobStore getBlobStore() {
            return blobStore;
        }
    }
}
