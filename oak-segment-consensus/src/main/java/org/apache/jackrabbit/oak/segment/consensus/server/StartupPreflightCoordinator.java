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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.apache.jackrabbit.oak.segment.consensus.aeron.AeronClusterConfig;
import org.apache.jackrabbit.oak.segment.consensus.config.RuntimeConfigValueResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class StartupPreflightCoordinator {

    private static final Logger log = LoggerFactory.getLogger(StartupPreflightCoordinator.class);

    private final BootstrapPreflightPlanner bootstrapPreflightPlanner;

    StartupPreflightCoordinator() {
        this(new BootstrapPreflightPlanner());
    }

    StartupPreflightCoordinator(BootstrapPreflightPlanner bootstrapPreflightPlanner) {
        this.bootstrapPreflightPlanner = bootstrapPreflightPlanner;
    }

    PreflightResult prepare(String storeDirectory, int port, AeronClusterConfig aeronConfig) throws IOException {
        Path storePath = Paths.get(storeDirectory);
        if (!Files.exists(storePath)) {
            Files.createDirectories(storePath);
            log.info("Created store directory: {}", storePath);
        }

        boolean isAeronMode = "aeron".equalsIgnoreCase(
            RuntimeConfigValueResolver.readString("consensus.mode", "aeron")
        );
        if (!isAeronMode) {
            throw new IllegalArgumentException(
                "This POC only supports Aeron Cluster consensus. Set consensus.mode=aeron or omit it (defaults to aeron)."
            );
        }

        File storeDir = new File(storeDirectory);
        boolean directoryIsEmpty = isStoreDirectoryEmpty(storeDir);
        boolean needsBootstrapBeforeBuild = false;
        boolean hasVerifiedReachablePeers = false;
        String verifiedBootstrapPrimaryHost = "";
        int verifiedBootstrapPrimaryPort = 0;

        if (directoryIsEmpty) {
            List<String> aeronPeers = GlobalStoreRuntimeConfigUtil.resolvePeerUrls(aeronConfig);
            String bootstrapPrimaryHost = RuntimeConfigValueResolver.readString("bootstrap.primary.host", "");
            String bootstrapPrimaryPortStr = RuntimeConfigValueResolver.readString("bootstrap.primary.port", "");
            boolean standbyBootstrapEnabled = RuntimeConfigValueResolver.readBoolean(
                "consensus.aeron.standby.bootstrap.enabled",
                false
            );
            BootstrapPreflightPlanner.Decision preflightDecision = bootstrapPreflightPlanner.plan(
                isAeronMode,
                directoryIsEmpty,
                aeronPeers,
                bootstrapPrimaryHost,
                bootstrapPrimaryPortStr,
                standbyBootstrapEnabled,
                port + 1
            );
            needsBootstrapBeforeBuild = preflightDecision.needsBootstrapBeforeBuild();
            hasVerifiedReachablePeers = preflightDecision.hasVerifiedReachablePeers();
            verifiedBootstrapPrimaryHost = preflightDecision.verifiedBootstrapPrimaryHost();
            verifiedBootstrapPrimaryPort = preflightDecision.verifiedBootstrapPrimaryPort();

            logPreflightOutcome(
                directoryIsEmpty,
                needsBootstrapBeforeBuild,
                hasVerifiedReachablePeers,
                verifiedBootstrapPrimaryHost,
                verifiedBootstrapPrimaryPort,
                aeronPeers,
                bootstrapPrimaryHost
            );
        }

        return new PreflightResult(
            storeDir,
            isAeronMode,
            directoryIsEmpty,
            needsBootstrapBeforeBuild,
            verifiedBootstrapPrimaryHost,
            verifiedBootstrapPrimaryPort
        );
    }

    private static boolean isStoreDirectoryEmpty(File storeDir) {
        if (storeDir.exists() && storeDir.isDirectory()) {
            File[] files = storeDir.listFiles((dir, name) ->
                name.startsWith("data") && name.endsWith(".tar")
                    || name.equals("journal.log")
                    || name.startsWith("journal.log"));
            return files == null || files.length == 0;
        }
        return true;
    }

    private static void logPreflightOutcome(boolean directoryIsEmpty,
                                            boolean needsBootstrapBeforeBuild,
                                            boolean hasVerifiedReachablePeers,
                                            String verifiedBootstrapPrimaryHost,
                                            int verifiedBootstrapPrimaryPort,
                                            List<String> aeronPeers,
                                            String bootstrapPrimaryHost) {
        if (!directoryIsEmpty) {
            return;
        }

        if (hasVerifiedReachablePeers && !verifiedBootstrapPrimaryHost.isEmpty()) {
            boolean matchedPeerUrl = false;
            for (String peerUrl : aeronPeers) {
                try {
                    java.net.URL url = new java.net.URL(peerUrl);
                    if (verifiedBootstrapPrimaryHost.equals(url.getHost())) {
                        log.info("✅ Verified reachable peer: {}", peerUrl);
                        matchedPeerUrl = true;
                        break;
                    }
                } catch (Exception e) {
                    // Ignore malformed peer URL in startup logging.
                }
            }
            if (!matchedPeerUrl && !bootstrapPrimaryHost.isEmpty()) {
                log.info("✅ Verified bootstrap primary: {}:{}",
                    verifiedBootstrapPrimaryHost, verifiedBootstrapPrimaryPort);
            }
        } else if (!bootstrapPrimaryHost.isEmpty()) {
            log.warn("⚠️  Bootstrap primary configured but not reachable: {}", bootstrapPrimaryHost);
            log.warn("   Will fall back to GENESIS mode if store is empty");
        }

        if (needsBootstrapBeforeBuild) {
            log.warn("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            log.warn("⚠️  CRITICAL: Empty store directory detected");
            log.warn("   Bootstrap needed - verified peer is reachable");
            log.warn("   This ensures all validators start with same genesis HEAD");
            log.warn("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            log.warn("   Bootstrap primary: {}:{}", verifiedBootstrapPrimaryHost, verifiedBootstrapPrimaryPort);
        } else if (hasVerifiedReachablePeers) {
            log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            log.info("ℹ️  Empty store directory with reachable peers detected");
            log.info("   Aeron standby bootstrap disabled (default)");
            log.info("   Starting Aeron cluster directly; consensus leader will create canonical genesis");
            log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        } else if (directoryIsEmpty) {
            log.warn("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            log.warn("⚠️  Empty store directory detected, but no reachable peers");
            log.warn("   Will create genesis state (this node becomes genesis)");
            log.warn("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        }
    }

    static final class PreflightResult {
        private final File storeDir;
        private final boolean aeronMode;
        private final boolean directoryEmpty;
        private final boolean needsBootstrapBeforeBuild;
        private final String verifiedBootstrapPrimaryHost;
        private final int verifiedBootstrapPrimaryPort;

        PreflightResult(File storeDir,
                        boolean aeronMode,
                        boolean directoryEmpty,
                        boolean needsBootstrapBeforeBuild,
                        String verifiedBootstrapPrimaryHost,
                        int verifiedBootstrapPrimaryPort) {
            this.storeDir = storeDir;
            this.aeronMode = aeronMode;
            this.directoryEmpty = directoryEmpty;
            this.needsBootstrapBeforeBuild = needsBootstrapBeforeBuild;
            this.verifiedBootstrapPrimaryHost = verifiedBootstrapPrimaryHost;
            this.verifiedBootstrapPrimaryPort = verifiedBootstrapPrimaryPort;
        }

        File getStoreDir() {
            return storeDir;
        }

        boolean isAeronMode() {
            return aeronMode;
        }

        boolean isDirectoryEmpty() {
            return directoryEmpty;
        }

        boolean needsBootstrapBeforeBuild() {
            return needsBootstrapBeforeBuild;
        }

        String getVerifiedBootstrapPrimaryHost() {
            return verifiedBootstrapPrimaryHost;
        }

        int getVerifiedBootstrapPrimaryPort() {
            return verifiedBootstrapPrimaryPort;
        }
    }
}
