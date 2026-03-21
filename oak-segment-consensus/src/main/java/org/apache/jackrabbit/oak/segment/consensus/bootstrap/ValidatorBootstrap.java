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
package org.apache.jackrabbit.oak.segment.consensus.bootstrap;

import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.apache.jackrabbit.oak.segment.standby.client.StandbyClientSync;
import org.apache.jackrabbit.oak.segment.standby.server.StandbyServerSync;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Handles bootstrap synchronization for new validators using Oak's Cold Standby mechanism.
 * <p>
 * When a new validator starts with an empty FileStore, it enters STANDBY mode and syncs
 * all segments from an existing validator (primary). Once caught up, it automatically
 * promotes to PRIMARY mode and joins the consensus network.
 */
public class ValidatorBootstrap {
    
    private static final Logger log = LoggerFactory.getLogger(ValidatorBootstrap.class);
    
    private final FileStore fileStore;
    private final int standbyPort;
    private String primaryUrl;  // HTTP endpoint of primary (for HEAD comparison)
    private StandbyClientSync standbyClient;
    private StandbyServerSync standbyServer;
    private ScheduledExecutorService syncScheduler;
    private final AtomicBoolean promoted = new AtomicBoolean(false);
    private final BootstrapCatchupChecker catchupChecker = new BootstrapCatchupChecker();
    
    public ValidatorBootstrap(FileStore fileStore, int standbyPort) {
        this.fileStore = fileStore;
        this.standbyPort = standbyPort;
    }
    
    /**
     * Check if FileStore is empty (needs bootstrap).
     */
    public boolean needsBootstrap() {
        try {
            // Check if store has segments
            long size = fileStore.size();
            boolean isEmpty = (size == 0);
            
            if (isEmpty) {
                log.info("🌱 FileStore is empty ({} bytes) - bootstrap required", size);
            } else {
                log.info("✅ FileStore has data ({} MB) - skipping bootstrap", size / (1024 * 1024));
            }
            
            return isEmpty;
        } catch (Exception e) {
            log.warn("Failed to check FileStore size", e);
            return false;
        }
    }
    
    /**
     * Bootstrap from an existing validator (standby mode).
     * This method blocks until initial sync completes, then starts periodic sync.
     * 
     * @param primaryHost Hostname of existing validator
     * @param primaryPort Standby port of existing validator
     * @param onPromoted Callback when validator is promoted to PRIMARY
     */
    public void bootstrapFromPrimary(String primaryHost, int primaryPort, 
                                     Runnable onPromoted) throws IOException {
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        log.info("🌱 STANDBY MODE: Bootstrapping from primary");
        log.info("   Primary: {}:{}", primaryHost, primaryPort);
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        
        // Store primary HTTP URL for HEAD comparison (standby port - 1 = HTTP port)
        int httpPort = primaryPort - 1;
        this.primaryUrl = String.format("http://%s:%d", primaryHost, httpPort);
        log.info("   Primary HTTP endpoint: {}", primaryUrl);
        
        try {
            // Create StandbyClientSync
            standbyClient = StandbyClientSync.builder()
                .withHost(primaryHost)
                .withPort(primaryPort)
                .withFileStore(fileStore)
                .withReadTimeoutMs(30000)
                .withAutoClean(true)
                .withSpoolFolder(new File(System.getProperty("java.io.tmpdir"), "oak-standby"))
                .build();
            
            // Run initial sync (blocking)
            log.info("🔄 Starting initial sync...");
            standbyClient.run();
            
            long segmentCount = fileStore.size() / (256 * 1024);  // Rough estimate
            log.info("✅ Initial sync complete!");
            log.info("   Segments synced: ~{}", segmentCount);
            log.info("   HEAD: {}", fileStore.getHead().getRecordId());
            
            // Check if already caught up
            if (isCaughtUp()) {
                log.info("🎉 Already caught up! Promoting to PRIMARY...");
                promoteStandbyToPrimary(onPromoted);
                return;
            }
            
            // Schedule periodic sync
            log.info("⏱️  Scheduling periodic sync (every 5 seconds)...");
            syncScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "standby-sync");
                t.setDaemon(true);
                return t;
            });
            
            syncScheduler.scheduleAtFixedRate(() -> {
                try {
                    standbyClient.run();
                    
                    long currentSize = fileStore.size() / (1024 * 1024);
                    log.info("   Sync progress: {} MB", currentSize);
                    
                    if (isCaughtUp() && !promoted.get()) {
                        log.info("🎉 FULLY CAUGHT UP! Promoting to PRIMARY...");
                        promoteStandbyToPrimary(onPromoted);
                    }
                } catch (Exception e) {
                    log.error("Sync failed", e);
                }
            }, 5, 5, TimeUnit.SECONDS);
            
        } catch (Exception e) {
            throw new IOException("Failed to bootstrap from primary " + primaryHost + ":" + primaryPort, e);
        }
    }
    
    /**
     * Check if standby is caught up with primary by comparing HEAD record IDs.
     * The standby is caught up when its HEAD matches the primary's HEAD.
     * 
     * NEW GENESIS ARCHITECTURE:
     * - Empty-to-empty sync is VALID (both validators waiting for genesis creation)
     * - If both stores are empty, we're "caught up" (ready to join cluster)
     * - Genesis will be created as first consensus write after cluster forms
     */
    private boolean isCaughtUp() {
        return catchupChecker.isCaughtUp(fileStore, primaryUrl);
    }
    
    /**
     * Promote from STANDBY to PRIMARY mode.
     */
    private void promoteStandbyToPrimary(Runnable onPromoted) {
        if (!promoted.compareAndSet(false, true)) {
            log.debug("Already promoted, skipping");
            return;
        }
        
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        log.info("🎖️  PROMOTION: Standby → Primary");
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        
        // Stop standby client
        if (standbyClient != null) {
            try {
                standbyClient.close();
                standbyClient = null;
                log.info("   ✅ Stopped StandbyClientSync");
            } catch (Exception e) {
                log.warn("Error closing standby client", e);
            }
        }
        
        // Stop sync scheduler
        if (syncScheduler != null) {
            syncScheduler.shutdown();
            log.info("   ✅ Stopped sync scheduler");
        }
        
        log.info("   FileStore: {} MB", fileStore.size() / (1024 * 1024));
        log.info("   HEAD: {}", fileStore.getHead().getRecordId());
        
        // Trigger promotion callback (starts consensus, registers with peers, etc.)
        if (onPromoted != null) {
            onPromoted.run();
        }
    }
    
    /**
     * Start StandbyServerSync to serve other standbys.
     * This should be called when validator is in PRIMARY mode.
     */
    public void startStandbyServer() throws IOException {
        log.info("🔌 Starting StandbyServerSync on port {}...", standbyPort);
        
        try {
            standbyServer = StandbyServerSync.builder()
                .withPort(standbyPort)
                .withFileStore(fileStore)
                .withBlobChunkSize(1024 * 1024)  // 1 MB chunks
                .build();
            
            standbyServer.start();
            log.info("✅ StandbyServerSync started (serving other standbys)");
            
        } catch (Exception e) {
            throw new IOException("Failed to start StandbyServerSync on port " + standbyPort, e);
        }
    }
    
    /**
     * Stop all bootstrap services.
     */
    public void shutdown() {
        log.info("Shutting down ValidatorBootstrap...");
        
        if (standbyClient != null) {
            try {
                standbyClient.close();
            } catch (Exception e) {
                log.warn("Error closing standby client", e);
            }
        }
        
        if (standbyServer != null) {
            try {
                standbyServer.close();
            } catch (Exception e) {
                log.warn("Error closing standby server", e);
            }
        }
        
        if (syncScheduler != null) {
            syncScheduler.shutdown();
        }
        
        log.info("ValidatorBootstrap shut down");
    }
    
    /**
     * Detect startup mode based on FileStore state and peer reachability.
     * 
     * Detection logic:
     * - GENESIS: No genesis content + no reachable peers → Create genesis locally
     * - STANDBY: No genesis content + has reachable peers → Bootstrap from peer
     * - PRIMARY: Has genesis content → Join consensus immediately
     */
    public static BootstrapMode detectMode(FileStore fileStore, org.apache.jackrabbit.oak.spi.state.NodeStore nodeStore, List<String> peers) {
        // Check if genesis content exists (the definitive test!)
        boolean hasGenesisContent = false;
        try {
            org.apache.jackrabbit.oak.spi.state.NodeState root = nodeStore.getRoot();
            org.apache.jackrabbit.oak.spi.state.NodeState oakChain = root.getChildNode("oak-chain");
            if (oakChain.exists()) {
                org.apache.jackrabbit.oak.spi.state.NodeState content = oakChain.getChildNode("content");
                if (content.exists()) {
                    org.apache.jackrabbit.oak.spi.state.NodeState genesis = content.getChildNode("genesis");
                    hasGenesisContent = genesis.exists();
                }
            }
        } catch (Exception e) {
            log.warn("Failed to check for genesis content", e);
        }
        
        // Check if any peers are reachable (have /health endpoint responding)
        boolean hasReachablePeers = false;
        if (peers != null && !peers.isEmpty()) {
            for (String peerUrl : peers) {
                try {
                    java.net.URL url = new java.net.URL(peerUrl + "/health");
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(2000);  // 2 second timeout
                    conn.setReadTimeout(2000);
                    
                    int responseCode = conn.getResponseCode();
                    if (responseCode == 200) {
                        log.info("✅ Found reachable peer: {}", peerUrl);
                        hasReachablePeers = true;
                        break;  // Found at least one reachable peer
                    }
                } catch (Exception e) {
                    log.debug("Peer not reachable: {} - {}", peerUrl, e.getMessage());
                }
            }
        }
        
        if (!hasGenesisContent && !hasReachablePeers) {
            // Empty + no reachable peers = First validator (create genesis)
            return BootstrapMode.GENESIS;
        } else if (!hasGenesisContent && hasReachablePeers) {
            // Empty + has reachable peers = New validator (bootstrap from peers)
            return BootstrapMode.STANDBY;
        } else {
            // Has genesis content = Existing validator (resume)
            return BootstrapMode.PRIMARY;
        }
    }
    
    public enum BootstrapMode {
        /** First validator - create genesis state */
        GENESIS,
        /** New validator - bootstrap from peers */
        STANDBY,
        /** Existing validator - join consensus immediately */
        PRIMARY
    }
}
