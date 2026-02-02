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
package org.apache.jackrabbit.oak.segment.consensus.aeron;

import org.apache.jackrabbit.oak.segment.consensus.server.AeronClusterBootstrapper;
import org.apache.jackrabbit.oak.segment.consensus.server.AeronClusterStartupResult;
import org.apache.jackrabbit.oak.segment.consensus.security.EthereumWallet;
import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.apache.jackrabbit.oak.segment.http.server.SegmentHttpServer;
import org.apache.jackrabbit.oak.spi.blob.BlobStore;
import org.apache.jackrabbit.oak.spi.state.NodeStore;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;

/**
 * OSGi-managed Aeron cluster service placeholder.
 *
 * <p>Initializes configuration and exposes a lifecycle hook for future
 * AeronClusterBootstrapper integration.</p>
 */
@Component(service = AeronClusterService.class, configurationPolicy = ConfigurationPolicy.OPTIONAL)
@Designate(ocd = AeronClusterConfig.class)
public class AeronClusterService {

    private static final Logger log = LoggerFactory.getLogger(AeronClusterService.class);

    private volatile AeronClusterConfig config;
    private volatile AeronClusterStartupResult startupResult;

    @Activate
    protected void activate(AeronClusterConfig config) {
        this.config = config;
        logConfiguration("Activated");
    }

    @Modified
    protected void modified(AeronClusterConfig config) {
        this.config = config;
        logConfiguration("Modified");
    }

    @Deactivate
    protected void deactivate() {
        shutdown();
        log.info("AeronClusterService deactivated");
    }

    public boolean isEnabled() {
        return config != null && config.enabled();
    }

    public AeronClusterConfig getConfig() {
        return config;
    }

    public AeronClusterStartupResult startCluster(FileStore fileStore,
                                                  NodeStore nodeStore,
                                                  SegmentHttpServer httpServer,
                                                  EthereumWallet wallet,
                                                  String storeDirectory,
                                                  BlobStore blobStore,
                                                  String selfUrl,
                                                  java.util.List<String> peerUrls,
                                                  boolean observeElections,
                                                  boolean logClusterStateDetails) throws java.io.IOException {
        applyConfigToSystemProperties(selfUrl, peerUrls);
        AeronClusterBootstrapper bootstrapper = new AeronClusterBootstrapper(
            fileStore, nodeStore, httpServer, wallet, storeDirectory, blobStore
        );
        this.startupResult = bootstrapper.startCluster(
            selfUrl, peerUrls, observeElections, logClusterStateDetails
        );
        return this.startupResult;
    }

    public AeronClusterStartupResult getStartupResult() {
        return startupResult;
    }

    public void shutdown() {
        if (startupResult != null && startupResult.getLauncher() != null) {
            try {
                startupResult.getLauncher().shutdown();
                log.info("AeronClusterService shutdown completed");
            } catch (Exception e) {
                log.warn("Error shutting down Aeron cluster", e);
            }
        }
    }

    private void logConfiguration(String event) {
        if (config == null) {
            log.warn("AeronClusterService {} with null config", event);
            return;
        }

        log.info("AeronClusterService {}", event);
        log.info("  enabled: {}", config.enabled());
        log.info("  nodeId: {}", config.nodeId());
        log.info("  selfUrl: {}", config.selfUrl());
        log.info("  peerUrls: {}", Arrays.toString(config.peerUrls()));
        log.info("  observeElections: {}", config.observeElections());
        log.info("  logClusterStateDetails: {}", config.logClusterStateDetails());
        log.info("  beaconApiUrl: {}", config.beaconApiUrl());
    }

    private void applyConfigToSystemProperties(String selfUrl, List<String> peerUrls) {
        if (config == null) {
            return;
        }

        System.setProperty("aeron.cluster.nodeId", Integer.toString(config.nodeId()));

        if (config.beaconApiUrl() != null && !config.beaconApiUrl().isEmpty()) {
            System.setProperty("ethereum.beacon.api.url", config.beaconApiUrl());
        }

        String existingHostnames = System.getProperty("aeron.cluster.hostnames", "");
        if ((existingHostnames == null || existingHostnames.isEmpty())
                && config.peerUrls() != null && config.peerUrls().length > 0) {
            List<String> hostnames = new ArrayList<>();
            String selfHost = extractHostname(selfUrl);
            if (selfHost != null && !selfHost.isEmpty()) {
                hostnames.add(selfHost);
            }
            for (String peerUrl : peerUrls) {
                String host = extractHostname(peerUrl);
                if (host != null && !host.isEmpty() && !hostnames.contains(host)) {
                    hostnames.add(host);
                }
            }
            if (!hostnames.isEmpty()) {
                System.setProperty("aeron.cluster.hostnames", String.join(",", hostnames));
            }
        }
    }

    private String extractHostname(String url) {
        if (url == null || url.isEmpty()) {
            return "";
        }
        try {
            return new java.net.URL(url).getHost();
        } catch (Exception e) {
            return "";
        }
    }
}
