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
        List<String> aeronPeers = GlobalStoreRuntimeConfigUtil.resolvePeerUrls(context.aeronConfig());
        ValidatorBootstrap bootstrap =
            context.componentFactory().createValidatorBootstrap(context.fileStore(), context.standbyPort());
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
            bootstrapPrimaryHost = context.verifiedBootstrapPrimaryHost();
            bootstrapPrimaryPort = context.verifiedBootstrapPrimaryPort();

            if (bootstrapPrimaryHost.isEmpty()) {
                bootstrapPrimaryHost = RuntimeConfigValueResolver.readString("bootstrap.primary.host", "");
                bootstrapPrimaryPort = resolveConfiguredStandbyPort(context.port());
            }

            if (bootstrapPrimaryHost.isEmpty() && !aeronPeers.isEmpty()) {
                BootstrapTarget firstPeer = resolveFirstPeer(aeronPeers.get(0), context.port());
                bootstrapPrimaryHost = firstPeer.host();
                bootstrapPrimaryPort = firstPeer.port();
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
        } else if (context.directoryEmpty()) {
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

    record StartupContext(boolean directoryEmpty,
                          boolean needsBootstrapBeforeBuild,
                          FileStore fileStore,
                          int port,
                          int standbyPort,
                          String verifiedBootstrapPrimaryHost,
                          int verifiedBootstrapPrimaryPort,
                          AeronClusterConfig aeronConfig,
                          GlobalStoreServerComponentFactory componentFactory) {
        StartupContext {
            verifiedBootstrapPrimaryHost = verifiedBootstrapPrimaryHost != null ? verifiedBootstrapPrimaryHost : "";
        }
    }

    record Resolution(BootstrapMode detectedMode,
                      ValidatorBootstrap bootstrap,
                      boolean aeronClusterDeferred,
                      List<String> aeronPeerUrls,
                      String bootstrapPrimaryHost,
                      int bootstrapPrimaryPort) {
    }

    private record BootstrapTarget(String host, int port) {
    }
}
