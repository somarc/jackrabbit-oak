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

import org.apache.jackrabbit.oak.segment.consensus.config.RuntimeConfigValueResolver;
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
        AeronClusterRuntimeRegistry.update(config);
        AeronClusterTuningSourceRegistry.markOsgiSource();
        logConfiguration("Activated");
    }

    @Modified
    protected void modified(AeronClusterConfig config) {
        this.config = config;
        AeronClusterRuntimeRegistry.update(config);
        AeronClusterTuningSourceRegistry.markOsgiSource();
        logConfiguration("Modified");
    }

    @Deactivate
    protected void deactivate() {
        shutdown();
        AeronClusterRuntimeRegistry.clear();
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
        log.info("  clusterEnvironment: {}", config.clusterEnvironment());
        log.info("  sessionTimeoutMinutes: {}", config.sessionTimeoutMinutes());
        log.info("  mediaDriverTimeoutMs: {}", config.mediaDriverTimeoutMs());
        log.info("  socketSendBufferBytes: {}", config.socketSendBufferBytes());
        log.info("  socketReceiveBufferBytes: {}", config.socketReceiveBufferBytes());
        log.info("  publicationTermBufferLengthBytes: {}", config.publicationTermBufferLengthBytes());
        log.info("  clusterTermLengthBytes: {}", config.clusterTermLengthBytes());
        log.info("  heartbeatMaxAgeMs: {}", config.heartbeatMaxAgeMs());
        log.info("  reachabilityCacheMs: {}", config.reachabilityCacheMs());
        log.info("  reachabilityConnectTimeoutMs: {}", config.reachabilityConnectTimeoutMs());
        log.info("  reachabilityReadTimeoutMs: {}", config.reachabilityReadTimeoutMs());
        log.info("  reconnectMaxAttempts: {}", config.reconnectMaxAttempts());
        log.info("  peerProbeMode: {}", config.peerProbeMode());
        log.info("  deleteAeronDirsOnStartup: {}", config.deleteAeronDirsOnStartup());
    }

    private void applyConfigToSystemProperties(String selfUrl, List<String> peerUrls) {
        if (config == null) {
            return;
        }

        System.setProperty("aeron.cluster.nodeId", Integer.toString(config.nodeId()));

        if (config.beaconApiUrl() != null && !config.beaconApiUrl().isEmpty()) {
            System.setProperty("ethereum.beacon.api.url", config.beaconApiUrl());
        }
        applyOptionalConfigProperties();

        String existingHostnames = RuntimeConfigValueResolver.readString("aeron.cluster.hostnames", "");
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

    private void applyOptionalConfigProperties() {
        setIfNotBlank("oak.cluster.environment", config.clusterEnvironment());
        setIfPositive("oak.cluster.session.timeout.minutes", config.sessionTimeoutMinutes());
        setIfPositive("oak.cluster.media.driver.timeout.ms", config.mediaDriverTimeoutMs());
        setIfPositive("aeron.socket.so_sndbuf", config.socketSendBufferBytes());
        setIfPositive("aeron.socket.so_rcvbuf", config.socketReceiveBufferBytes());
        setIfPositive("oak.cluster.publication.term.buffer.length.bytes", config.publicationTermBufferLengthBytes());
        setIfPositive("oak.cluster.term.length.bytes", config.clusterTermLengthBytes());
        setIfPositive("oak.cluster.heartbeat.maxAgeMs", config.heartbeatMaxAgeMs());
        setIfPositive("oak.cluster.reachability.cacheMs", config.reachabilityCacheMs());
        setIfPositive("oak.cluster.reachability.connectTimeoutMs", config.reachabilityConnectTimeoutMs());
        setIfPositive("oak.cluster.reachability.readTimeoutMs", config.reachabilityReadTimeoutMs());
        setIfPositive("oak.cluster.reconnect.maxAttempts", config.reconnectMaxAttempts());
        setIfNotBlank("oak.health.peerProbeMode", config.peerProbeMode());
        if (config.deleteAeronDirsOnStartup()) {
            System.setProperty("aeron.delete.dirs.on.startup", "true");
        }
    }

    private static void setIfNotBlank(String key, String value) {
        if (value != null && !value.trim().isEmpty()) {
            System.setProperty(key, value.trim());
        }
    }

    private static void setIfPositive(String key, int value) {
        if (value > 0) {
            System.setProperty(key, Integer.toString(value));
        }
    }

    private static void setIfPositive(String key, long value) {
        if (value > 0) {
            System.setProperty(key, Long.toString(value));
        }
    }
}
