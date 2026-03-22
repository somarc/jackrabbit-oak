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

import java.util.Collections;
import java.util.List;

import org.apache.jackrabbit.oak.segment.consensus.aeron.AeronClusterConfig;
import org.apache.jackrabbit.oak.segment.consensus.bootstrap.ValidatorBootstrap;
import org.apache.jackrabbit.oak.segment.consensus.bootstrap.ValidatorBootstrap.BootstrapMode;
import org.apache.jackrabbit.oak.segment.consensus.config.RuntimeConfigValueResolver;
import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class BootstrapModeCoordinator {

    private static final Logger log = LoggerFactory.getLogger(BootstrapModeCoordinator.class);

    Resolution resolve(StartupContext context) {
        if (!context.isAeronMode()) {
            return new Resolution(BootstrapMode.PRIMARY, null, false, Collections.emptyList(), "", 0);
        }

        List<String> aeronPeers = GlobalStoreRuntimeConfigUtil.resolvePeerUrls(context.getAeronConfig());
        ValidatorBootstrap bootstrap =
            context.getComponentFactory().createValidatorBootstrap(context.getFileStore(), context.getStandbyPort());
        BootstrapMode detectedMode = BootstrapMode.PRIMARY;
        boolean aeronClusterDeferred = false;
        String bootstrapPrimaryHost = "";
        int bootstrapPrimaryPort = 0;

        if (context.needsBootstrapBeforeBuild()) {
            log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            log.info("✈️  AERON MODE: Empty store detected");
            log.info("   Bootstrapping Oak FileStore from verified peer BEFORE Aeron Cluster join");
            log.info("   This ensures deterministic genesis (all validators have same HEAD)");
            log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

            aeronClusterDeferred = true;
            bootstrapPrimaryHost = context.getVerifiedBootstrapPrimaryHost();
            bootstrapPrimaryPort = context.getVerifiedBootstrapPrimaryPort();

            if (bootstrapPrimaryHost.isEmpty()) {
                bootstrapPrimaryHost = RuntimeConfigValueResolver.readString("bootstrap.primary.host", "");
                bootstrapPrimaryPort = resolveConfiguredStandbyPort(context.getPort());
            }

            if (bootstrapPrimaryHost.isEmpty() && !aeronPeers.isEmpty()) {
                BootstrapTarget firstPeer = resolveFirstPeer(aeronPeers.get(0), context.getPort());
                bootstrapPrimaryHost = firstPeer.getHost();
                bootstrapPrimaryPort = firstPeer.getPort();
                log.info("   Using first peer as bootstrap primary: {}:{}",
                    bootstrapPrimaryHost, bootstrapPrimaryPort);
            }

            if (bootstrapPrimaryHost.isEmpty()) {
                log.error("❌ ERROR: Bootstrap needed but no primary host available");
                log.error("   Falling back to GENESIS mode (this node will create genesis state)");
                detectedMode = BootstrapMode.GENESIS;
            } else {
                detectedMode = BootstrapMode.STANDBY;
                log.info("   Bootstrap mode: STANDBY (will sync Oak FileStore, then start Aeron Cluster)");
                log.info("   Bootstrap primary: {}:{}", bootstrapPrimaryHost, bootstrapPrimaryPort);
            }
        } else if (context.isDirectoryEmpty()) {
            log.info("✈️  AERON MODE: Empty store detected");
            log.info("   Starting Aeron Cluster in parallel with peers");
            log.info("   Genesis will be created by elected leader via consensus");
            log.info("   All validators will replicate genesis -> identical HEADs");
            detectedMode = BootstrapMode.PRIMARY;
        } else {
            log.info("✈️  AERON MODE: Existing store found");
            log.info("   Starting Aeron Cluster (will replay Raft log if needed)");
            detectedMode = BootstrapMode.PRIMARY;
        }

        return new Resolution(
            detectedMode,
            bootstrap,
            aeronClusterDeferred,
            aeronPeers,
            bootstrapPrimaryHost,
            bootstrapPrimaryPort
        );
    }

    private static int resolveConfiguredStandbyPort(int port) {
        String bootstrapPrimaryPortStr = RuntimeConfigValueResolver.readString(
            "bootstrap.primary.port",
            String.valueOf(port + 1)
        );
        try {
            return Integer.parseInt(bootstrapPrimaryPortStr);
        } catch (NumberFormatException e) {
            return port + 1;
        }
    }

    private static BootstrapTarget resolveFirstPeer(String peerUrl, int port) {
        String host = peerUrl.replace("http://", "").replace("https://", "").split(":")[0];
        int standbyPort = port + 1;
        try {
            int httpPort = Integer.parseInt(peerUrl.split(":")[2]);
            standbyPort = httpPort + 1;
        } catch (Exception e) {
            // Fall back to the local standby default.
        }
        return new BootstrapTarget(host, standbyPort);
    }

    static final class StartupContext {
        private final boolean aeronMode;
        private final boolean directoryEmpty;
        private final boolean needsBootstrapBeforeBuild;
        private final FileStore fileStore;
        private final int port;
        private final int standbyPort;
        private final String verifiedBootstrapPrimaryHost;
        private final int verifiedBootstrapPrimaryPort;
        private final AeronClusterConfig aeronConfig;
        private final GlobalStoreServerComponentFactory componentFactory;

        StartupContext(boolean aeronMode,
                       boolean directoryEmpty,
                       boolean needsBootstrapBeforeBuild,
                       FileStore fileStore,
                       int port,
                       int standbyPort,
                       String verifiedBootstrapPrimaryHost,
                       int verifiedBootstrapPrimaryPort,
                       AeronClusterConfig aeronConfig,
                       GlobalStoreServerComponentFactory componentFactory) {
            this.aeronMode = aeronMode;
            this.directoryEmpty = directoryEmpty;
            this.needsBootstrapBeforeBuild = needsBootstrapBeforeBuild;
            this.fileStore = fileStore;
            this.port = port;
            this.standbyPort = standbyPort;
            this.verifiedBootstrapPrimaryHost = verifiedBootstrapPrimaryHost != null ? verifiedBootstrapPrimaryHost : "";
            this.verifiedBootstrapPrimaryPort = verifiedBootstrapPrimaryPort;
            this.aeronConfig = aeronConfig;
            this.componentFactory = componentFactory;
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

        FileStore getFileStore() {
            return fileStore;
        }

        int getPort() {
            return port;
        }

        int getStandbyPort() {
            return standbyPort;
        }

        String getVerifiedBootstrapPrimaryHost() {
            return verifiedBootstrapPrimaryHost;
        }

        int getVerifiedBootstrapPrimaryPort() {
            return verifiedBootstrapPrimaryPort;
        }

        AeronClusterConfig getAeronConfig() {
            return aeronConfig;
        }

        GlobalStoreServerComponentFactory getComponentFactory() {
            return componentFactory;
        }
    }

    static final class Resolution {
        private final BootstrapMode detectedMode;
        private final ValidatorBootstrap bootstrap;
        private final boolean aeronClusterDeferred;
        private final List<String> aeronPeerUrls;
        private final String bootstrapPrimaryHost;
        private final int bootstrapPrimaryPort;

        Resolution(BootstrapMode detectedMode,
                   ValidatorBootstrap bootstrap,
                   boolean aeronClusterDeferred,
                   List<String> aeronPeerUrls,
                   String bootstrapPrimaryHost,
                   int bootstrapPrimaryPort) {
            this.detectedMode = detectedMode;
            this.bootstrap = bootstrap;
            this.aeronClusterDeferred = aeronClusterDeferred;
            this.aeronPeerUrls = aeronPeerUrls;
            this.bootstrapPrimaryHost = bootstrapPrimaryHost;
            this.bootstrapPrimaryPort = bootstrapPrimaryPort;
        }

        BootstrapMode getDetectedMode() {
            return detectedMode;
        }

        ValidatorBootstrap getBootstrap() {
            return bootstrap;
        }

        boolean isAeronClusterDeferred() {
            return aeronClusterDeferred;
        }

        List<String> getAeronPeerUrls() {
            return aeronPeerUrls;
        }

        String getBootstrapPrimaryHost() {
            return bootstrapPrimaryHost;
        }

        int getBootstrapPrimaryPort() {
            return bootstrapPrimaryPort;
        }
    }

    private static final class BootstrapTarget {
        private final String host;
        private final int port;

        private BootstrapTarget(String host, int port) {
            this.host = host;
            this.port = port;
        }

        private String getHost() {
            return host;
        }

        private int getPort() {
            return port;
        }
    }
}
