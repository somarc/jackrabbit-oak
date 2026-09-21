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

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.jackrabbit.oak.segment.consensus.aeron.AeronClusterConfig;
import org.apache.jackrabbit.oak.segment.consensus.aeron.AeronClusterService;
import org.apache.jackrabbit.oak.segment.consensus.bootstrap.ValidatorBootstrap;
import org.apache.jackrabbit.oak.segment.consensus.security.EthereumWallet;
import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.apache.jackrabbit.oak.segment.http.server.SegmentHttpServer;
import org.apache.jackrabbit.oak.spi.blob.BlobStore;
import org.apache.jackrabbit.oak.spi.state.NodeStore;

final class StandbyPromotionCoordinator {

    BootstrapTarget resolveBootstrapTarget(String configuredHost,
                                           int configuredPort,
                                           List<String> peerUrls,
                                           int httpPort) {
        String host = configuredHost != null ? configuredHost.trim() : "";
        if (!host.isEmpty()) {
            return new BootstrapTarget(host, configuredPort > 0 ? configuredPort : httpPort + 1);
        }

        if (peerUrls == null || peerUrls.isEmpty()) {
            return null;
        }

        String firstPeer = peerUrls.get(0);
        try {
            URL url = new URL(firstPeer);
            String peerHost = url.getHost();
            int peerPort = url.getPort() > 0 ? url.getPort() + 1 : httpPort + 1;
            if (peerHost != null && !peerHost.isEmpty()) {
                return new BootstrapTarget(peerHost, peerPort);
            }
        } catch (Exception ignored) {
            // Fall through to null; caller decides how to handle unresolved bootstrap peers.
        }

        return null;
    }

    void bootstrapAndPromote(ValidatorBootstrap bootstrap,
                             BootstrapTarget target,
                             Runnable onPromoted) throws IOException {
        bootstrap.bootstrapFromPrimary(target.getHost(), target.getPort(), onPromoted);
    }

    DeferredAeronStartup startDeferredCluster(GlobalStoreServerComponentFactory components,
                                              AeronClusterService existingService,
                                              DeferredAeronStartupContext context) throws IOException {
        if (context.getSelfUrl() == null) {
            throw new IOException("Aeron Cluster bootstrap: selfUrl not stored");
        }

        AeronClusterService clusterService = existingService;
        if (clusterService == null) {
            clusterService = components.createAeronClusterService();
        }

        AeronClusterConfig config = clusterService != null ? clusterService.getConfig() : null;
        boolean observeElections = config != null && config.observeElections();
        boolean logClusterStateDetails = config != null && config.logClusterStateDetails();
        List<String> peerUrls = context.getPeerUrls() != null
            ? new ArrayList<>(context.getPeerUrls())
            : Collections.<String>emptyList();

        AeronClusterStartupResult startupResult = clusterService.startCluster(
            context.getFileStore(),
            context.getNodeStore(),
            context.getHttpServer(),
            context.getWallet(),
            context.getStoreDirectory(),
            context.getBlobStore(),
            context.getSelfUrl(),
            peerUrls,
            observeElections,
            logClusterStateDetails
        );

        return new DeferredAeronStartup(clusterService, startupResult);
    }

    static final class BootstrapTarget {
        private final String host;
        private final int port;

        BootstrapTarget(String host, int port) {
            this.host = host;
            this.port = port;
        }

        String getHost() {
            return host;
        }

        int getPort() {
            return port;
        }
    }

    static final class DeferredAeronStartup {
        private final AeronClusterService aeronClusterService;
        private final AeronClusterStartupResult startupResult;

        private DeferredAeronStartup(AeronClusterService aeronClusterService,
                                     AeronClusterStartupResult startupResult) {
            this.aeronClusterService = aeronClusterService;
            this.startupResult = startupResult;
        }

        AeronClusterService getAeronClusterService() {
            return aeronClusterService;
        }

        AeronClusterStartupResult getStartupResult() {
            return startupResult;
        }
    }

    static final class DeferredAeronStartupContext {
        private final FileStore fileStore;
        private final NodeStore nodeStore;
        private final SegmentHttpServer httpServer;
        private final EthereumWallet wallet;
        private final String storeDirectory;
        private final BlobStore blobStore;
        private final String selfUrl;
        private final List<String> peerUrls;

        DeferredAeronStartupContext(FileStore fileStore,
                                    NodeStore nodeStore,
                                    SegmentHttpServer httpServer,
                                    EthereumWallet wallet,
                                    String storeDirectory,
                                    BlobStore blobStore,
                                    String selfUrl,
                                    List<String> peerUrls) {
            this.fileStore = fileStore;
            this.nodeStore = nodeStore;
            this.httpServer = httpServer;
            this.wallet = wallet;
            this.storeDirectory = storeDirectory;
            this.blobStore = blobStore;
            this.selfUrl = selfUrl;
            this.peerUrls = peerUrls;
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

        EthereumWallet getWallet() {
            return wallet;
        }

        String getStoreDirectory() {
            return storeDirectory;
        }

        BlobStore getBlobStore() {
            return blobStore;
        }

        String getSelfUrl() {
            return selfUrl;
        }

        List<String> getPeerUrls() {
            return peerUrls;
        }
    }
}
