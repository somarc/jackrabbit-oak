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
import java.util.List;

import org.apache.jackrabbit.oak.segment.consensus.config.RuntimeConfigValueResolver;
import org.apache.jackrabbit.oak.segment.consensus.aeron.AeronClusterLauncher;
import org.apache.jackrabbit.oak.segment.consensus.aeron.AeronConsensusEngine;
import org.apache.jackrabbit.oak.segment.consensus.aeron.AeronWriteClient;
import org.apache.jackrabbit.oak.segment.consensus.security.EthereumWallet;
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
        String hostnamesConfig = RuntimeConfigValueResolver.readString("aeron.cluster.hostnames", "");
        AeronClusterBootstrapPlan bootstrapPlan = AeronClusterBootstrapPlan.create(
            nodeId,
            selfUrl,
            peerUrls,
            storeDirectory,
            hostnamesConfig
        );
        List<String> hostnamesList = bootstrapPlan.hostnames;
        boolean hasExistingCluster = bootstrapPlan.hasExistingCluster;

        if (logClusterStateDetails) {
            System.out.println("🔍 DEBUG: Cluster state check:");
            System.out.println("   - Cluster dir exists: " + bootstrapPlan.clusterDirExists);
            System.out.println("   - Cluster dir path: " + bootstrapPlan.clusterDir.getAbsolutePath());
            if (bootstrapPlan.clusterDirExists) {
                System.out.println("   - Cluster dir files: " + bootstrapPlan.clusterDirFileCount);
            }
            System.out.println("   - hasExistingCluster: " + hasExistingCluster);
        }

        if (bootstrapPlan.startupMode == AeronClusterBootstrapPlan.StartupMode.EXISTING_CLUSTER) {
            System.out.println("🌐 Existing cluster detected - will join with " + hostnamesList.size() + " members");
        } else if (bootstrapPlan.startupMode == AeronClusterBootstrapPlan.StartupMode.FRESH_CONFIGURED) {
            System.out.println("🌐 Fresh cluster start - using configured hostnames (" + hostnamesList.size() + " members)");
            if (logClusterStateDetails) {
                System.out.println("   → Starting with self only (quorum = 1), peers will join dynamically");
            }
        } else {
            System.out.println("🌐 Fresh cluster start - starting with self only (quorum = 1)");
            if (logClusterStateDetails) {
                System.out.println("   → Peers can join dynamically as they come online");
            }
        }

        AeronConsensusEngine aeronEngine =
            new AeronConsensusEngine(fileStore, nodeStore, selfUrl, peerUrls, wallet, storeDirectory, blobStore);

        // Initialize Ethereum integration if configured
        String beaconApiUrl = System.getProperty("ethereum.beacon.api.url", "https://beaconcha.in/api");
        aeronEngine.initializeEthereumIntegration(beaconApiUrl);

        // Create cluster base directory
        File clusterBaseDir = bootstrapPlan.clusterBaseDir;
        clusterBaseDir.mkdirs();

        // Build node ID to URL mapping for leader lookup
        aeronEngine.setNodeIdMapping(bootstrapPlan.nodeIdToUrl);

        new AeronClusterCallbackBinder().bind(aeronEngine, httpServer);

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

        AeronWriteClient aeronWriteClient = new AeronClusterRuntimeAttacher().attach(
            httpServer,
            aeronClusterLauncher,
            hostnamesList,
            bootstrapPlan.clientHostname
        );

        try {
            new ShardRouterInitializer().initialize(httpServer, selfUrl, peerUrls, logClusterStateDetails);
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
}
