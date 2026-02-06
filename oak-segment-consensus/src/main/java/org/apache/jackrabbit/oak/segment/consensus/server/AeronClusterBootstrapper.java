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
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.jackrabbit.oak.segment.consensus.aeron.AeronClusterLauncher;
import org.apache.jackrabbit.oak.segment.consensus.aeron.AeronConsensusEngine;
import org.apache.jackrabbit.oak.segment.consensus.aeron.AeronPrometheusMetrics;
import org.apache.jackrabbit.oak.segment.consensus.aeron.AeronWriteClient;
import org.apache.jackrabbit.oak.segment.consensus.security.EthereumWallet;
import org.apache.jackrabbit.oak.segment.consensus.sharding.ShardDirectory;
import org.apache.jackrabbit.oak.segment.consensus.sharding.ShardRouter;
import org.apache.jackrabbit.oak.segment.consensus.sharding.ShardingStrategy;
import org.apache.jackrabbit.oak.segment.consensus.sharding.WalletShardingStrategy;
import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.apache.jackrabbit.oak.segment.http.server.SegmentHttpServer;
import org.apache.jackrabbit.oak.spi.blob.BlobStore;
import org.apache.jackrabbit.oak.spi.state.NodeStore;

public final class AeronClusterBootstrapper {

    private final FileStore fileStore;
    private final NodeStore nodeStore;
    private final SegmentHttpServer httpServer;
    private final EthereumWallet wallet;
    private final String storeDirectory;
    private final BlobStore blobStore;

    public AeronClusterBootstrapper(FileStore fileStore,
                                    NodeStore nodeStore,
                                    SegmentHttpServer httpServer,
                                    EthereumWallet wallet,
                                    String storeDirectory,
                                    BlobStore blobStore) {
        this.fileStore = fileStore;
        this.nodeStore = nodeStore;
        this.httpServer = httpServer;
        this.wallet = wallet;
        this.storeDirectory = storeDirectory;
        this.blobStore = blobStore;
    }

    public AeronClusterStartupResult startCluster(String selfUrl,
                                                  List<String> peerUrls,
                                                  boolean observeElections,
                                                  boolean logClusterStateDetails) throws IOException {
        // Get node ID from system property (default: 0)
        int nodeId = Integer.parseInt(System.getProperty("aeron.cluster.nodeId", "0"));

        // 🌐 DYNAMIC CLUSTER SIZE: Start with just self, discover peers organically
        String hostnamesConfig = System.getProperty("aeron.cluster.hostnames", "");
        List<String> hostnamesList;

        // Check if cluster state already exists (discover existing cluster members)
        File clusterStateCheckDir = new File(storeDirectory, "aeron-cluster-node-" + nodeId);
        File clusterDir = new File(clusterStateCheckDir, "cluster");
        boolean hasExistingCluster = clusterDir.exists() && clusterDir.listFiles() != null && clusterDir.listFiles().length > 0;

        if (logClusterStateDetails) {
            System.out.println("🔍 DEBUG: Cluster state check:");
            System.out.println("   - Cluster dir exists: " + clusterDir.exists());
            System.out.println("   - Cluster dir path: " + clusterDir.getAbsolutePath());
            if (clusterDir.exists()) {
                System.out.println("   - Cluster dir files: " + (clusterDir.listFiles() != null ? clusterDir.listFiles().length : "null"));
            }
            System.out.println("   - hasExistingCluster: " + hasExistingCluster);
        }

        if (hasExistingCluster) {
            // Existing cluster: Use self + discovered peers (cluster state will have member info)
            hostnamesList = new ArrayList<>();
            hostnamesList.add(ServerNetworkUtil.extractHostname(selfUrl));
            for (String peerUrl : peerUrls) {
                String hostname = ServerNetworkUtil.extractHostname(peerUrl);
                if (!hostnamesList.contains(hostname)) {
                    hostnamesList.add(hostname);
                }
            }
            if (!hostnamesConfig.isEmpty()) {
                hostnamesList = new ArrayList<>(Arrays.asList(hostnamesConfig.split(",")));
            }
            System.out.println("🌐 Existing cluster detected - will join with " + hostnamesList.size() + " members");
        } else {
            if (!hostnamesConfig.isEmpty()) {
                hostnamesList = new ArrayList<>(Arrays.asList(hostnamesConfig.split(",")));
                System.out.println("🌐 Fresh cluster start - using configured hostnames (" + hostnamesList.size() + " members)");
                if (logClusterStateDetails) {
                    System.out.println("   → Starting with self only (quorum = 1), peers will join dynamically");
                }
            } else {
                hostnamesList = new ArrayList<>();
                hostnamesList.add(ServerNetworkUtil.extractHostname(selfUrl));
                System.out.println("🌐 Fresh cluster start - starting with self only (quorum = 1)");
                if (logClusterStateDetails) {
                    System.out.println("   → Peers can join dynamically as they come online");
                }
            }
        }

        AeronConsensusEngine aeronEngine =
            new AeronConsensusEngine(fileStore, nodeStore, selfUrl, peerUrls, wallet, storeDirectory, blobStore);

        // Initialize Ethereum integration if configured
        String beaconApiUrl = System.getProperty("ethereum.beacon.api.url", "https://beaconcha.in/api");
        aeronEngine.initializeEthereumIntegration(beaconApiUrl);

        // Create cluster base directory
        File clusterBaseDir = new File(storeDirectory, "aeron-cluster-node-" + nodeId);
        clusterBaseDir.mkdirs();

        // Build node ID to URL mapping for leader lookup
        java.util.Map<Integer, String> nodeIdToUrl = new java.util.HashMap<>();
        java.util.List<String> allUrls = new java.util.ArrayList<>();
        allUrls.add(selfUrl);
        allUrls.addAll(peerUrls);
        // Sort by port first so mapping stays stable across localhost vs 127.0.0.1 host formatting.
        // Fallback to full URL comparison for deterministic ordering when ports are equal.
        java.util.Collections.sort(allUrls, (a, b) -> {
            int portA = extractPort(a);
            int portB = extractPort(b);
            if (portA != portB) {
                return Integer.compare(portA, portB);
            }
            return a.compareTo(b);
        });
        for (int i = 0; i < allUrls.size(); i++) {
            nodeIdToUrl.put(i, allUrls.get(i));
        }
        aeronEngine.setNodeIdMapping(nodeIdToUrl);

        // Set write/delete application callback BEFORE launching cluster
        aeronEngine.setWriteApplicationCallback(new AeronConsensusEngine.WriteApplicationCallback() {
            @Override
            public void applyReplicatedWrite(String walletAddress, String path, String contentType, String message,
                                             String signature, String intentToken, String blobId, String mimeType, String ipfsCid,
                                             String proposalId) {
                httpServer.getConsensusApiHandler().applyReplicatedWrite(
                    walletAddress, path, contentType, message, signature, intentToken, blobId, mimeType, ipfsCid,
                    proposalId
                );
            }

            @Override
            public void applyReplicatedDelete(String walletAddress, String path, String signature, String proposalId) {
                httpServer.getConsensusApiHandler().applyReplicatedDelete(
                    walletAddress, path, signature, proposalId
                );
            }
        });
        System.out.println("   ✅ Write application callback configured");

        // Wire Aeron engine to HTTP server context
        httpServer.setAeronConsensusEngine(aeronEngine);

        AeronClusterLauncher aeronClusterLauncher =
            new AeronClusterLauncher(nodeId, hostnamesList, clusterBaseDir, aeronEngine);

        // Set shutdown callback to exit JVM on FATAL MediaDriver errors
        aeronClusterLauncher.setShutdownCallback(() -> {
            System.err.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            System.err.println("🚨 FATAL MediaDriver error - exiting JVM for restart");
            System.err.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

            System.exit(1);

            new Thread(() -> {
                try {
                    Thread.sleep(5000);
                    System.err.println("⚠️  JVM still running after System.exit() - forcing halt");
                    Runtime.getRuntime().halt(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "force-exit-thread").start();
        });

        try {
            aeronClusterLauncher.launch();
        } catch (Exception e) {
            throw new IOException("Failed to launch Aeron Cluster", e);
        }

        if (observeElections && !hasExistingCluster && hostnamesList.size() >= 3) {
            System.out.println();
            System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            System.out.println("🔄 STARTUP ELECTION OBSERVATION");
            System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            System.out.println("Purpose: Observe elections for 15s to verify all " + hostnamesList.size() +
                " nodes can participate");
            System.out.println("         before performing critical genesis writes");
            System.out.println();

            try {
                observeElections(aeronEngine, 15000);
                System.out.println("✅ Election observation complete - cluster verified healthy");
                System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                System.out.println();
            } catch (Exception e) {
                System.err.println("⚠️  Election observation failed: " + e.getMessage());
                System.err.println("   Proceeding with genesis, but cluster health uncertain");
                System.err.println();
            }
        }

        // Create AeronWriteClient for external write submissions
        String aeronDirectoryName = aeronClusterLauncher.getAeronDirectoryName();
        int clusterBasePort = AeronClusterLauncher.getPortBase();

        String clientHostname;
        try {
            URL selfUrlParsed = new URL(selfUrl);
            clientHostname = selfUrlParsed.getHost();
        } catch (Exception e) {
            clientHostname = "localhost";
        }

        AeronWriteClient aeronWriteClient =
            new AeronWriteClient(0, aeronDirectoryName, hostnamesList, clusterBasePort, clientHostname);

        httpServer.setAeronClusterLauncher(aeronClusterLauncher);

        if (httpServer.getContext().aeronPrometheusMetrics == null) {
            java.util.concurrent.ScheduledExecutorService delayedInit =
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
            delayedInit.schedule(() -> {
                try {
                    io.aeron.Aeron aeron = aeronClusterLauncher.getAeron();
                    if (aeron != null && httpServer.getContext().aeronPrometheusMetrics == null) {
                        AeronPrometheusMetrics metrics = new AeronPrometheusMetrics(aeron);
                        httpServer.getContext().setAeronPrometheusMetrics(metrics);
                        System.out.println("✅ Aeron Prometheus metrics initialized (delayed)");
                    }
                } catch (Exception e) {
                    System.err.println("⚠️  Failed to initialize Aeron Prometheus metrics: " + e.getMessage());
                } finally {
                    delayedInit.shutdown();
                }
            }, 5, java.util.concurrent.TimeUnit.SECONDS);
        }

        try {
            aeronWriteClient.connect();
        } catch (Exception e) {
            System.err.println("   ⚠️  WARNING: Failed to connect AeronWriteClient: " + e.getMessage());
        }

        httpServer.setAeronWriteClient(aeronWriteClient);

        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // SHARD ROUTER: Initialize shard routing (Phase 1)
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        try {
            // Get number of shards from configuration (default: 1 for single-shard mode)
            String numShardsConfig = System.getProperty("sharding.numShards", System.getenv("NUM_SHARDS"));
            int numShards = 1; // Default: single shard
            if (numShardsConfig != null && !numShardsConfig.isEmpty()) {
                try {
                    numShards = Integer.parseInt(numShardsConfig);
                    if (numShards <= 0) {
                        System.err.println("⚠️  Invalid NUM_SHARDS: " + numShardsConfig + ", using default: 1");
                        numShards = 1;
                    }
                } catch (NumberFormatException e) {
                    System.err.println("⚠️  Invalid NUM_SHARDS format: " + numShardsConfig + ", using default: 1");
                    numShards = 1;
                }
            }

            // Create shard directory: single shard (shard 0) with all peers
            java.util.List<String> allPeerUrls = new java.util.ArrayList<>();
            allPeerUrls.add(selfUrl);
            allPeerUrls.addAll(peerUrls);

            ShardDirectory shardDirectory = new ShardDirectory(allPeerUrls);

            ShardingStrategy shardingStrategy = new WalletShardingStrategy(numShards);

            ShardRouter shardRouter = new ShardRouter(shardDirectory, shardingStrategy);

            httpServer.getContext().setShardRouter(shardRouter);

            System.out.println("✅ Shard Router initialized");
            System.out.println("   - Number of shards: " + numShards);
            System.out.println("   - Shard directory: " + shardDirectory.getNumShards() + " shard(s)");
            System.out.println("   - Sharding strategy: Wallet-based");
            if (shardingStrategy instanceof WalletShardingStrategy
                && ((WalletShardingStrategy) shardingStrategy).isPowerOfTwo()) {
                System.out.println("   - Power-of-2: Yes (optimal)");
            } else if (logClusterStateDetails) {
                System.out.println("   - Power-of-2: No (consider using power-of-2 for optimal performance)");
            }
        } catch (Exception e) {
            System.err.println("⚠️  WARNING: Failed to initialize Shard Router: " + e.getMessage());
            System.err.println("   → Shard routing disabled, requests will route directly");
            e.printStackTrace();
        }

        return new AeronClusterStartupResult(aeronEngine, aeronClusterLauncher, aeronWriteClient, hostnamesList, nodeId);
    }

    /**
     * Observe elections for a period to verify cluster health.
     * Passively watches leadership changes to ensure all nodes can participate.
     */
    private void observeElections(AeronConsensusEngine engine, long observationMs) throws Exception {
        java.util.Set<Integer> observedLeaders = new java.util.HashSet<>();
        long startTime = System.currentTimeMillis();
        int lastLeaderId = -1;
        int changeCount = 0;

        System.out.println("   Observing elections for " + (observationMs / 1000) + " seconds...");
        System.out.println();

        while (System.currentTimeMillis() - startTime < observationMs) {
            int currentLeaderId = engine.getLeaderMemberId();

            if (currentLeaderId >= 0) {
                observedLeaders.add(currentLeaderId);

                if (currentLeaderId != lastLeaderId) {
                    String role = engine.isLeader() ? "LEADER (this node)" : "FOLLOWER";
                    System.out.println("   " + new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date()) +
                        " - Leader is node " + currentLeaderId +
                        " (role: " + role + ", changes: " + ++changeCount + ")");
                    lastLeaderId = currentLeaderId;
                }
            }

            Thread.sleep(1000); // Check every second
        }

        System.out.println();
        System.out.println("   Observation Results:");
        System.out.println("   - Duration: " + (observationMs / 1000) + " seconds");
        System.out.println("   - Leadership changes: " + changeCount);
        System.out.println("   - Unique leaders observed: " + observedLeaders.size() + " of " + engine.getClusterSize() + " nodes");
        System.out.println("   - Final leader: node " + lastLeaderId);
        System.out.println();

        if (observedLeaders.isEmpty()) {
            throw new Exception("No leader elected during observation period");
        }

        if (observedLeaders.size() == 1 && changeCount == 0) {
            System.out.println("   ℹ️  Single stable leader throughout observation (healthy)");
        } else if (changeCount > 3) {
            System.out.println("   ⚠️  WARNING: " + changeCount + " leadership changes detected");
            System.out.println("              This may indicate network instability");
        }
    }

    private static int extractPort(String url) {
        try {
            return new URL(url).getPort();
        } catch (Exception e) {
            return -1;
        }
    }
}
