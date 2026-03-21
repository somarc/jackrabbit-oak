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

final class BlobStoreStartupCoordinator {

    StartupResult initialize(File storeDir, GlobalStoreServerComponentFactory componentFactory) throws IOException {
        String blobStoreType = RuntimeConfigValueResolver.readString("blobstore.type", "BLOBSTORE_TYPE", "");

        if (blobStoreType.isEmpty()) {
            System.err.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            System.err.println("❌ FATAL: blobstore.type is not configured");
            System.err.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            System.err.println("");
            System.err.println("Blockchain AEM requires IPFS DataStore for binary storage.");
            System.err.println("FileDataStore is NOT supported (no fallback).");
            System.err.println("");
            System.err.println("FIX:");
            System.err.println("  1. Ensure IPFS daemon is running:");
            System.err.println("     $ ipfs daemon");
            System.err.println("");
            System.err.println("  2. Set blobstore.type=ipfs:");
            System.err.println("     $ export BLOBSTORE_TYPE=ipfs");
            System.err.println("     OR");
            System.err.println("     $ java -Dblobstore.type=ipfs -jar oak-segment-consensus.jar");
            System.err.println("");
            System.err.println("  3. (Optional) Configure IPFS API endpoint:");
            System.err.println("     $ export IPFS_API_ENDPOINT=/ip4/127.0.0.1/tcp/5001");
            System.err.println("");
            System.err.println("See: oak-segment-consensus/IPFS-DATASTORE.md");
            System.err.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            throw startupFailure("blobstore.type is not configured");
        }

        if (!"ipfs".equalsIgnoreCase(blobStoreType)) {
            System.err.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            System.err.println("❌ FATAL: Invalid blobstore.type = \"" + blobStoreType + "\"");
            System.err.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            System.err.println("");
            System.err.println("Only \"ipfs\" is supported.");
            System.err.println("FileDataStore is NOT supported (blockchain-native storage required).");
            System.err.println("");
            System.err.println("FIX: Set blobstore.type=ipfs");
            System.err.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            throw startupFailure("Invalid blobstore.type: " + blobStoreType);
        }

        System.out.println("📦 Configuring IPFS BlobStore for binaries...");

        try {
            String ipfsEndpoint = RuntimeConfigValueResolver.readString(
                "ipfs.api.endpoint",
                "IPFS_API_ENDPOINT",
                "/ip4/127.0.0.1/tcp/5001"
            );

            BlobStore blobStore = componentFactory.createIpfsBlobStore(ipfsEndpoint, storeDir);

            System.out.println("✅ IPFS BlobStore initialized");
            System.out.println("   - IPFS API: " + ipfsEndpoint);
            System.out.println("   - Min size: 16 KB (smaller binaries inline in segments)");
            System.out.println("   - Storage: Decentralized (P2P replication)");
            System.out.println("   - Strategy: Oak segments (AEM compatible) + IPFS binaries (blockchain-native)");

            return new StartupResult("ipfs", blobStore);
        } catch (Exception e) {
            System.err.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            System.err.println("❌ FATAL: Failed to initialize IPFS BlobStore");
            System.err.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            System.err.println("");
            System.err.println("Error: " + e.getMessage());
            System.err.println("");
            System.err.println("Common causes:");
            System.err.println("  1. IPFS daemon not running");
            System.err.println("     FIX: $ ipfs daemon");
            System.err.println("");
            System.err.println("  2. Wrong IPFS API endpoint");
            System.err.println("     FIX: $ export IPFS_API_ENDPOINT=/ip4/127.0.0.1/tcp/5001");
            System.err.println("");
            System.err.println("  3. IPFS not initialized");
            System.err.println("     FIX: $ ipfs init --profile server");
            System.err.println("");
            System.err.println("Verify IPFS:");
            System.err.println("  $ ipfs version");
            System.err.println("  $ ipfs id");
            System.err.println("");
            System.err.println("See: oak-segment-consensus/IPFS-DATASTORE.md");
            System.err.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            e.printStackTrace();
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
