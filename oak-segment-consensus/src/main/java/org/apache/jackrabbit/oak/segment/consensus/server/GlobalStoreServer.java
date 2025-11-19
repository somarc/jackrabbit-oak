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

import org.apache.jackrabbit.oak.segment.SegmentNodeStore;
import org.apache.jackrabbit.oak.segment.SegmentNodeStoreBuilders;
import org.apache.jackrabbit.oak.segment.consensus.bootstrap.ValidatorBootstrap;
import org.apache.jackrabbit.oak.segment.consensus.bootstrap.ValidatorBootstrap.BootstrapMode;
import org.apache.jackrabbit.oak.segment.consensus.eth.EpochListener;
import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.apache.jackrabbit.oak.segment.file.FileStoreBuilder;
import org.apache.jackrabbit.oak.segment.file.InvalidFileStoreVersionException;
import org.apache.jackrabbit.oak.segment.http.server.SegmentHttpServer;
import org.apache.jackrabbit.oak.spi.state.NodeStore;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Distributed validator server for the global Blockchain AEM repository.
 * 
 * <p><strong>DISTRIBUTED ARCHITECTURE:</strong>
 * This server is designed for distributed deployment across multiple machines,
 * networks, and data centers. Validators form a distributed consensus network
 * using Aeron Cluster Raft, communicating via UDP/IP across network boundaries.
 * The system is NOT confined to localhost or single-machine deployments.
 * 
 * <p>This server hosts the read-only global segment store that contains
 * all wallet-owned content at /oak-chain/content/<wallet-uuid>/*
 * 
 * <p>Other AEM instances (Sling authors) connect to validator servers via HTTP
 * and mount the content as a read-only Composite NodeStore mount.
 * 
 * <p><strong>Distributed Deployment Example:</strong>
 * <pre>
 * # Validator 0 (US-East data center)
 * java -jar oak-segment-consensus.jar \
 *   --port 8090 \
 *   --store /var/oak-chain/segmentstore \
 *   -Dconsensus.enabled=true \
 *   -Dconsensus.mode=aeron \
 *   -Dconsensus.self.url=http://validator-0.us-east.example.com:8090 \
 *   -Dconsensus.peers=http://validator-1.eu-west.example.com:8090,http://validator-2.ap-south.example.com:8090
 * 
 * # Validator 1 (EU-West data center)
 * java -jar oak-segment-consensus.jar \
 *   --port 8090 \
 *   --store /var/oak-chain/segmentstore \
 *   -Dconsensus.enabled=true \
 *   -Dconsensus.mode=aeron \
 *   -Dconsensus.self.url=http://validator-1.eu-west.example.com:8090 \
 *   -Dconsensus.peers=http://validator-0.us-east.example.com:8090,http://validator-2.ap-south.example.com:8090
 * 
 * # Validator 2 (AP-South data center)
 * java -jar oak-segment-consensus.jar \
 *   --port 8090 \
 *   --store /var/oak-chain/segmentstore \
 *   -Dconsensus.enabled=true \
 *   -Dconsensus.mode=aeron \
 *   -Dconsensus.self.url=http://validator-2.ap-south.example.com:8090 \
 *   -Dconsensus.peers=http://validator-0.us-east.example.com:8090,http://validator-1.eu-west.example.com:8090
 * </pre>
 * 
 * <p><strong>Network Requirements:</strong>
 * <ul>
 *   <li>UDP/IP connectivity between validators (Aeron Cluster)</li>
 *   <li>HTTP endpoints accessible to Sling authors (segment serving)</li>
 *   <li>Peer URLs can be IP addresses, hostnames, or public URLs</li>
 *   <li>No localhost assumptions - fully distributed</li>
 * </ul>
 */
public class GlobalStoreServer {
    
    private final int port;
    private final String storeDirectory;
    private volatile boolean running = false;
    private FileStore fileStore;
    private NodeStore nodeStore;
    private SegmentHttpServer httpServer;
    private EpochListener epochListener;
    private ValidatorBootstrap bootstrap;
    private org.apache.jackrabbit.oak.segment.consensus.security.EthereumWallet wallet;
    private org.apache.jackrabbit.oak.segment.consensus.aeron.AeronClusterLauncher aeronClusterLauncher;
    private org.apache.jackrabbit.oak.segment.consensus.gc.GCCostEstimator gcCostEstimator;
    
    // Bootstrap configuration (for organic peer discovery after promotion)
    private String bootstrapPrimaryHost;
    private int bootstrapPrimaryPort;
    
    // Aeron Cluster initialization state (for deferred startup after bootstrap)
    private volatile boolean aeronClusterDeferred = false; // Set to true if bootstrap is active
    private String aeronSelfUrl; // Stored for bootstrap callback
    private List<String> aeronPeerUrls; // Stored for bootstrap callback
    
    public GlobalStoreServer(int port, String storeDirectory) {
        this.port = port;
        this.storeDirectory = storeDirectory;
    }
    
    /**
     * Start the global store server.
     */
    public void start() throws IOException {
        // Create store directory if it doesn't exist
        Path storePath = Paths.get(storeDirectory);
        if (!Files.exists(storePath)) {
            Files.createDirectories(storePath);
            System.out.println("Created store directory: " + storePath);
        }
        
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // ETHEREUM WALLET: Load or generate validator identity
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        String keystorePath = System.getProperty("wallet.keystore.path", 
            storeDirectory + "/validator-keystore.properties");
        
        try {
            this.wallet = new org.apache.jackrabbit.oak.segment.consensus.security.EthereumWallet(keystorePath);
        } catch (Exception e) {
            System.err.println("❌ FATAL: Failed to load/generate Ethereum wallet");
            System.err.println("   Keystore path: " + keystorePath);
            System.err.println("   Error: " + e.getMessage());
            throw new IOException("Wallet initialization failed", e);
        }
        
        // Bootstrap mode (needs to be accessible throughout method)
        BootstrapMode detectedMode = BootstrapMode.PRIMARY;  // Default
        
        // Initialize Oak FileStore
        System.out.println("Initializing Oak FileStore...");
        File storeDir = new File(storeDirectory);
        try {
            
            // Build FileStore with read-write mode (so we can initialize /oak-chain structure)
            fileStore = FileStoreBuilder.fileStoreBuilder(storeDir)
                .withMaxFileSize(256)  // 256 MB per TAR file
                .withMemoryMapping(false)  // Disable for Docker
                .build();
            
            // Build SegmentNodeStore
            nodeStore = SegmentNodeStoreBuilders.builder(fileStore).build();
            
            System.out.println("✅ Oak FileStore initialized");
            System.out.println("   - Store version: " + fileStore.getHead().getRecordId());
            System.out.println("   - Segments: " + storeDir.getAbsolutePath());
            
            // ===========================================================================
            // Initialize GC Cost Estimator (for GC operations)
            System.out.println("Initializing GC Cost Estimator...");
            try {
                // Access TarFiles via reflection (getTarFiles() is not public)
                java.lang.reflect.Method getTarFilesMethod = FileStore.class.getDeclaredMethod("getTarFiles");
                getTarFilesMethod.setAccessible(true);
                org.apache.jackrabbit.oak.segment.file.tar.TarFiles tarFiles = 
                    (org.apache.jackrabbit.oak.segment.file.tar.TarFiles) getTarFilesMethod.invoke(fileStore);
                
                // Create GC Cost Estimator with default USDC rate ($0.10 per MB)
                // Can be configured via system property: gc.usdc.per.mb
                String usdcRateStr = System.getProperty("gc.usdc.per.mb", "0.10");
                java.math.BigDecimal usdcPerMB = new java.math.BigDecimal(usdcRateStr);
                org.apache.jackrabbit.oak.segment.consensus.gc.GCCostEstimator gcCostEstimator = 
                    new org.apache.jackrabbit.oak.segment.consensus.gc.GCCostEstimator(fileStore, tarFiles, usdcPerMB);
                
                System.out.println("✅ GC Cost Estimator initialized");
                System.out.println("   - USDC rate: $" + usdcPerMB + " per MB");
                
                // Store for later access (will be set in ServerContext after HTTP server is created)
                this.gcCostEstimator = gcCostEstimator;
            } catch (Exception e) {
                System.err.println("⚠️  Failed to initialize GC Cost Estimator: " + e.getMessage());
                System.err.println("   GC cost estimation will not be available");
                // Don't fail startup - GC estimation is optional
            }
            
            // ===========================================================================
            // Initialize HTTP server FIRST (needed for startConsensusPrimary callback)
            System.out.println("Initializing HTTP server on port " + port + "...");
            httpServer = new SegmentHttpServer(storeDir, port, fileStore, nodeStore);
            // Get self URL from system property, or resolve localhost to IP
            String selfUrlConfig = System.getProperty("consensus.self.url");
            String selfUrl;
            if (selfUrlConfig != null && !selfUrlConfig.isEmpty()) {
                // Use configured URL (can be ngrok/Ethos URL, IP, or hostname)
                selfUrl = selfUrlConfig;
                System.out.println("   Using configured self URL: " + selfUrl);
            } else {
                // Default: resolve localhost to IP for reliable networking
                selfUrl = resolveUrlToIP("http://localhost:" + port);
                System.out.println("   Resolved self URL to IP: " + selfUrl);
            }
            httpServer.setSelfUrl(selfUrl);
            
            // Set GC Cost Estimator in ServerContext (if initialized)
            if (gcCostEstimator != null) {
                httpServer.getContext().setGCCostEstimator(gcCostEstimator);
            }
            
            // ===========================================================================
            // Initialize Fragmentation Tracker (for fragmentation metrics and tax)
            System.out.println("Initializing Fragmentation Tracker...");
            org.apache.jackrabbit.oak.segment.consensus.fragmentation.FragmentationTracker fragmentationTracker = null;
            try {
                fragmentationTracker = new org.apache.jackrabbit.oak.segment.consensus.fragmentation.FragmentationTracker();
                
                httpServer.getContext().setFragmentationTracker(fragmentationTracker);
                
                System.out.println("✅ Fragmentation Tracker initialized");
                System.out.println("   - Tracks TAR file creation per entity");
                System.out.println("   - Calculates fragmentation scores and taxes");
            } catch (Exception e) {
                System.err.println("⚠️  Failed to initialize Fragmentation Tracker: " + e.getMessage());
                System.err.println("   Fragmentation tracking will not be available");
                // Don't fail startup - fragmentation tracking is optional
            }
            
            // ===========================================================================
            // Initialize GC Proposal Manager (for GC consensus)
            System.out.println("Initializing GC Proposal Manager...");
            try {
                // Determine total validators (from peers + self)
                int totalValidators = 1; // Default: just self
                String peersConfig = System.getProperty("consensus.peers", "");
                if (peersConfig != null && !peersConfig.isEmpty()) {
                    String[] peers = peersConfig.split(",");
                    totalValidators = peers.length + 1; // Peers + self
                }
                
                // Create executor ID supplier (gets current node ID from Aeron if available)
                // Access from ServerContext since aeronConsensusEngine is initialized later
                java.util.function.Supplier<Integer> executorIdSupplier = () -> {
                    // Try to get from Aeron cluster via ServerContext
                    org.apache.jackrabbit.oak.segment.consensus.aeron.AeronConsensusEngine aeronEngine = 
                        httpServer.getContext().aeronConsensusEngine;
                    if (aeronEngine != null && aeronEngine.getCluster() != null) {
                        try {
                            return aeronEngine.getCluster().memberId();
                        } catch (Exception e) {
                            // Fallback to 0
                        }
                    }
                    return 0; // Default fallback
                };
                
                // Create leader check supplier (only leader should execute GC)
                // Access from ServerContext since aeronConsensusEngine is initialized later
                java.util.function.Supplier<Boolean> isLeaderSupplier = () -> {
                    org.apache.jackrabbit.oak.segment.consensus.aeron.AeronConsensusEngine aeronEngine = 
                        httpServer.getContext().aeronConsensusEngine;
                    if (aeronEngine != null) {
                        return aeronEngine.isLeader();
                    }
                    return true; // Default: allow execution (for single-node setups)
                };
                
                // Get EvmBridge from ServerContext (set earlier during ProposalQueueManager initialization)
                org.apache.jackrabbit.oak.segment.consensus.evm.EvmBridge gcEvmBridge = httpServer.getContext().evmBridge;
                
                org.apache.jackrabbit.oak.segment.consensus.gc.GCProposalManager gcProposalManager = 
                    new org.apache.jackrabbit.oak.segment.consensus.gc.GCProposalManager(
                        fileStore,
                        gcCostEstimator,
                        fragmentationTracker,
                        gcEvmBridge, // 🔒 CRITICAL: Pass EvmBridge for payment verification (tokenomics)
                        totalValidators,
                        executorIdSupplier,
                        isLeaderSupplier
                    );
                
                httpServer.getContext().setGCProposalManager(gcProposalManager);
                
                System.out.println("✅ GC Proposal Manager initialized");
                System.out.println("   - Total validators: " + totalValidators);
                System.out.println("   - Quorum required: " + ((totalValidators * 2 / 3) + 1) + "/" + totalValidators);
                System.out.println("   - Tracks GC proposals, voting, and execution");
            } catch (Exception e) {
                System.err.println("⚠️  Failed to initialize GC Proposal Manager: " + e.getMessage());
                System.err.println("   GC consensus will not be available");
                // Don't fail startup - GC consensus is optional
            }
            
            System.out.println("✅ HTTP server initialized (not yet started)");
            
            // Check consensus mode FIRST to determine if bootstrap is needed
            String consensusMode = System.getProperty("consensus.mode", "leader");
            boolean isAeronMode = "aeron".equalsIgnoreCase(consensusMode);
            
            // Declare variables for bootstrap logic (needed for EpochLeaderEngine mode)
            List<String> peers = new java.util.ArrayList<>();
            int standbyPort = port + 1;
            
            // BOOTSTRAP: Hybrid approach for Aeron mode
            // ===========================================================================
            // CRITICAL: Aeron Cluster handles Raft log bootstrap (consensus state)
            // BUT: Oak FileStore segments must be synced separately (content state)
            // 
            // Strategy:
            // - If store is empty AND peers exist → Bootstrap Oak FileStore FIRST
            // - Then start Aeron Cluster (will handle Raft log bootstrap)
            // - If store is empty AND no peers → Create genesis, then start Aeron
            // - If store has data → Start Aeron directly (will replay Raft log)
            // ===========================================================================
            if (isAeronMode) {
                // Check if store is empty (needs Oak FileStore bootstrap)
                boolean storeIsEmpty = (fileStore.size() == 0);
                
                // Check if peers are reachable
                String peersConfig = System.getProperty("consensus.peers", "");
                List<String> aeronPeers = parsePeerUrls(peersConfig);
                boolean hasReachablePeers = false;
                
                if (!aeronPeers.isEmpty()) {
                    // Try to reach at least one peer
                    for (String peerUrl : aeronPeers) {
                        try {
                            java.net.URL url = new java.net.URL(peerUrl + "/health");
                            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                            conn.setRequestMethod("GET");
                            conn.setConnectTimeout(2000);
                            conn.setReadTimeout(2000);
                            
                            if (conn.getResponseCode() == 200) {
                                hasReachablePeers = true;
                                System.out.println("✅ Found reachable peer for Oak FileStore bootstrap: " + peerUrl);
                                break;
                            }
                        } catch (Exception e) {
                            // Try next peer
                        }
                    }
                }
                
                if (storeIsEmpty && hasReachablePeers) {
                    // ✈️ AERON MODE: Empty store + peers exist → Bootstrap Oak FileStore FIRST
                    // This ensures all validators start with same genesis HEAD
                    System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                    System.out.println("✈️  AERON MODE: Empty store detected");
                    System.out.println("   Bootstrapping Oak FileStore from peers BEFORE Aeron Cluster join");
                    System.out.println("   This ensures deterministic genesis (all validators have same HEAD)");
                    System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                    
                    // Store Aeron Cluster config for bootstrap callback
                    // (Will be set later in start() method, but we need to mark as deferred)
                    this.aeronClusterDeferred = true;
                    // Store peer URLs for later use in bootstrap callback
                    this.aeronPeerUrls = aeronPeers;
                    
                    // Use ValidatorBootstrap to sync Oak FileStore
                    String bootstrapMode = System.getProperty("bootstrap.mode", "auto");
                    this.bootstrapPrimaryHost = System.getProperty("bootstrap.primary.host", "");
                    this.bootstrapPrimaryPort = Integer.parseInt(System.getProperty("bootstrap.primary.port", String.valueOf(port + 1)));
                    standbyPort = port + 1;
                    
                    bootstrap = new ValidatorBootstrap(fileStore, standbyPort);
                    
                    // Determine bootstrap primary (first reachable peer)
                    String primaryHost = this.bootstrapPrimaryHost;
                    int primaryPort = this.bootstrapPrimaryPort;
                    
                    if (primaryHost.isEmpty() && !aeronPeers.isEmpty()) {
                        // Use first peer as bootstrap primary
                        String firstPeer = aeronPeers.get(0);
                        primaryHost = firstPeer.replace("http://", "").replace("https://", "").split(":")[0];
                        primaryPort = port + 1; // Standby port = HTTP port + 1
                        System.out.println("   Using first peer as bootstrap primary: " + primaryHost + ":" + primaryPort);
                    }
                    
                    if (primaryHost.isEmpty()) {
                        throw new IOException("AERON MODE: Empty store requires bootstrap.primary.host or consensus.peers for Oak FileStore bootstrap");
                    }
                    
                    detectedMode = BootstrapMode.STANDBY; // Will bootstrap Oak FileStore
                    System.out.println("   Bootstrap mode: STANDBY (will sync Oak FileStore, then start Aeron Cluster)");
                } else if (storeIsEmpty && !hasReachablePeers) {
                    // ✈️ AERON MODE: Empty store + no peers → Create genesis, then start Aeron
                    System.out.println("✈️  AERON MODE: Empty store + no peers → Creating genesis");
                    System.out.println("   This validator will become the genesis node");
                    System.out.println("   Initializing StandbyServerSync for Oak FileStore bootstrap (other validators will sync from this node)");
                    detectedMode = BootstrapMode.GENESIS;
                    // Initialize bootstrap for StandbyServerSync (needed for Oak FileStore bootstrap, not Raft replication)
                    standbyPort = port + 1;
                    bootstrap = new ValidatorBootstrap(fileStore, standbyPort);
                } else {
                    // ✈️ AERON MODE: Store has data → Start Aeron directly
                    // Aeron will handle Raft log bootstrap/replay
                    System.out.println("✈️  AERON MODE: Existing store found");
                    System.out.println("   Starting Aeron Cluster (will replay Raft log if needed)");
                    detectedMode = BootstrapMode.PRIMARY;
                    bootstrap = null;
                }
            } else {
                // EpochLeaderEngine mode: Initialize bootstrap
                String peersConfig = System.getProperty("consensus.peers", "");
                String bootstrapMode = System.getProperty("bootstrap.mode", "auto");  // auto, genesis, standby, primary
                this.bootstrapPrimaryHost = System.getProperty("bootstrap.primary.host", "");
                this.bootstrapPrimaryPort = Integer.parseInt(System.getProperty("bootstrap.primary.port", "8001"));
                standbyPort = port + 1;  // Standby port = HTTP port + 1
                
                bootstrap = new ValidatorBootstrap(fileStore, standbyPort);
                peers = parsePeerUrls(peersConfig);
                
                if ("auto".equalsIgnoreCase(bootstrapMode)) {
                    // Check if we have a bootstrap primary configured
                    boolean hasBootstrapPrimary = this.bootstrapPrimaryHost != null && !this.bootstrapPrimaryHost.isEmpty();
                    
                    if (hasBootstrapPrimary) {
                        // If bootstrap primary is configured, try to reach it and use STANDBY mode
                        String primaryUrl = "http://" + this.bootstrapPrimaryHost + ":8090";
                        try {
                            java.net.URL url = new java.net.URL(primaryUrl + "/health");
                            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                            conn.setRequestMethod("GET");
                            conn.setConnectTimeout(3000);
                            conn.setReadTimeout(3000);
                            
                            int responseCode = conn.getResponseCode();
                            if (responseCode == 200) {
                                System.out.println("🔍 AUTO MODE → STANDBY (bootstrap primary reachable)");
                                System.out.println("   Primary: " + primaryUrl);
                                detectedMode = BootstrapMode.STANDBY;
                            } else {
                                System.out.println("🔍 AUTO MODE → GENESIS (bootstrap primary not healthy)");
                                detectedMode = BootstrapMode.GENESIS;
                            }
                        } catch (Exception e) {
                            System.out.println("🔍 AUTO MODE → GENESIS (cannot reach bootstrap primary: " + e.getMessage() + ")");
                            detectedMode = BootstrapMode.GENESIS;
                        }
                    } else {
                        // Fall back to peer-based detection
                        detectedMode = ValidatorBootstrap.detectMode(fileStore, nodeStore, peers);
                        System.out.println("🔍 AUTO MODE → " + detectedMode);
                    }
                } else {
                    detectedMode = BootstrapMode.valueOf(bootstrapMode.toUpperCase());
                    System.out.println("📌 EXPLICIT MODE → " + detectedMode);
                }
            }
            
            // Handle bootstrap logic for both modes
            if (detectedMode == BootstrapMode.STANDBY) {
                // STANDBY MODE: Bootstrap Oak FileStore from existing validator
                // This applies to BOTH EpochLeaderEngine and Aeron modes
                
                // Determine which peer to bootstrap from
                String primaryHost = bootstrapPrimaryHost;
                int primaryPort = bootstrapPrimaryPort;
                
                // Get peers list based on mode
                List<String> bootstrapPeers;
                String consensusModeBootstrap = System.getProperty("consensus.mode", "leader");
                boolean isAeronModeLocal = "aeron".equalsIgnoreCase(consensusModeBootstrap);
                if (isAeronModeLocal) {
                    bootstrapPeers = this.aeronPeerUrls != null ? this.aeronPeerUrls : new java.util.ArrayList<>();
                } else {
                    bootstrapPeers = peers;
                }
                
                if (primaryHost.isEmpty() && !bootstrapPeers.isEmpty()) {
                    // Use first peer as primary
                    String firstPeer = bootstrapPeers.get(0);
                    // Parse URL (e.g., "http://validator-1:8090")
                    primaryHost = firstPeer.replace("http://", "").replace("https://", "").split(":")[0];
                    primaryPort = standbyPort;  // Standby port = HTTP port + 1
                    System.out.println("🔍 Using first peer as bootstrap primary: " + primaryHost + ":" + primaryPort);
                }
                
                if (primaryHost.isEmpty()) {
                    throw new IOException("STANDBY mode requires bootstrap.primary.host or consensus.peers");
                }
                
                // Bootstrap from primary (this will block until initial sync, then schedule periodic sync)
                bootstrap.bootstrapFromPrimary(primaryHost, primaryPort, () -> {
                    System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                    System.out.println("🎖️  PROMOTED TO PRIMARY - Oak FileStore bootstrap complete");
                    System.out.println("   Starting consensus engine...");
                    System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                    try {
                        if (isAeronMode) {
                            // Aeron mode: Start Aeron Cluster after Oak FileStore bootstrap
                            System.out.println("✈️  Starting Aeron Cluster (Oak FileStore already synced)");
                            // Start Aeron Cluster using stored configuration
                            startAeronClusterAfterBootstrap();
                        } else {
                            // EpochLeaderEngine mode: Start consensus engine
                            startConsensusPrimary();
                        }
                    } catch (Exception e) {
                        System.err.println("❌ Failed to start consensus after promotion: " + e.getMessage());
                        e.printStackTrace();
                    }
                });
                
            } else if (detectedMode == BootstrapMode.GENESIS) {
                // GENESIS MODE: Create deterministic genesis state
                System.out.println("🌍 GENESIS MODE: Creating network genesis state");
                initializeGenesisContent();
                
            } else {
                // PRIMARY MODE: Already has data, just init genesis if needed
                initializeGenesisContent();
            }
            
        } catch (InvalidFileStoreVersionException e) {
            throw new IOException("Invalid FileStore version", e);
        }
        
        // Start HTTP server (already initialized earlier for STANDBY mode support)
        System.out.println("Starting HTTP server...");
        try {
            httpServer.start();
            System.out.println("✅ HTTP server started");
            System.out.println("   - GET /journal.log - journal file");
            System.out.println("   - GET /manifest - manifest file");
            System.out.println("   - GET /gc.log - garbage collection log");
            System.out.println("   - GET /segments/{id} - fetch segment");
            System.out.println("   - HEAD /segments/{id} - check existence");
            System.out.println("   - GET /health - health check");
            System.out.println("   - POST /v1/propose - submit write proposal");
            System.out.println("   - POST /v1/vote - submit vote");
        } catch (Exception e) {
            throw new IOException("Failed to start HTTP server", e);
        }
        
        // Initialize Consensus Engine (Multi-Validator)
        // CRITICAL: Skip this if we're in STANDBY mode (bootstrap will initialize via callback)
        String consensusEnabled = System.getProperty("consensus.enabled", "false");
        String consensusMode = System.getProperty("consensus.mode", "leader"); // leader, dag, blockchain, or aeron
        // Get self URL from system property, or resolve localhost to IP
        String selfUrlConfig = System.getProperty("consensus.self.url");
        String selfUrl;
        if (selfUrlConfig != null && !selfUrlConfig.isEmpty()) {
            selfUrl = selfUrlConfig; // Use configured URL (can be ngrok/Ethos URL, IP, or hostname)
        } else {
            selfUrl = resolveUrlToIP("http://localhost:" + port); // Default: resolve to IP
        }
        String peersConfig = System.getProperty("consensus.peers", "");
        String genesisNode = System.getProperty("consensus.genesis.node", "");  // Boot node for genesis sync
        
        // Allow consensus even with no peers:
        // - Leader mode: single validator = leader of 1
        // - Aeron mode: single validator can start cluster (genesis node)
        boolean enableConsensus = "true".equalsIgnoreCase(consensusEnabled) && 
                                 ("leader".equalsIgnoreCase(consensusMode) || 
                                  "aeron".equalsIgnoreCase(consensusMode) || 
                                  !peersConfig.isEmpty());
        
        // CRITICAL: Don't initialize consensus here if we're in STANDBY mode
        // The bootstrap promotion callback (startConsensusPrimary or startAeronClusterAfterBootstrap) will initialize it
        boolean isStandbyMode = (detectedMode == BootstrapMode.STANDBY);
        
        // Store selfUrl and peerUrls for bootstrap callback (if Aeron mode with bootstrap)
        String consensusModeCheck = System.getProperty("consensus.mode", "leader");
        boolean isAeronModeCheck = "aeron".equalsIgnoreCase(consensusModeCheck);
        if (isAeronModeCheck && isStandbyMode) {
            this.aeronSelfUrl = selfUrl;
            // aeronPeerUrls already stored earlier in bootstrap detection
        }
        
        if (enableConsensus && !isStandbyMode) {
            System.out.println();
            System.out.println("Initializing Consensus Engine...");
            System.out.println("   Mode: " + consensusMode.toUpperCase());
            
            List<String> peerUrls = parsePeerUrls(peersConfig);
            
            if ("leader".equalsIgnoreCase(consensusMode)) {
                // LEADER-BASED CONSENSUS (Raft-style)
                System.out.println("   🎖️  Using Leader-Based Consensus");
                System.out.println("      - Single leader sequences all writes");
                System.out.println("      - Followers replicate from leader");
                System.out.println("      - Leader rotates every 5 minutes");
                
                // Get leader term from system property (default: 300 seconds = 5 minutes)
                int leaderTermSeconds = Integer.parseInt(
                    System.getProperty("consensus.leader.term.seconds", "300")
                );
                
                org.apache.jackrabbit.oak.segment.consensus.leader.EpochLeaderEngine epochEngine = 
                    new org.apache.jackrabbit.oak.segment.consensus.leader.EpochLeaderEngine(
                        fileStore, nodeStore, selfUrl, peerUrls, leaderTermSeconds, wallet
                    );
                
                // Wire epoch leader engine to HTTP server
                httpServer.setEpochLeaderEngine(epochEngine);
                
                // Start epoch rotation monitor
                epochEngine.startRotationMonitor();
                
                System.out.println("✅ Epoch Leader Consensus engine initialized");
                System.out.println("   - Model: Epoch-based Leader/Follower (Raft-style)");
                System.out.println("   - Total validators: " + (1 + peerUrls.size()));
                System.out.println("   - Leader term: " + leaderTermSeconds + " seconds");
                System.out.println("   - Current role: " + epochEngine.getCurrentRole());
                System.out.println("   - Current leader: " + epochEngine.getCurrentLeader());
                
                // Register with peer validators using wallet address as ID
                String validatorId = wallet.getWalletAddress();
                httpServer.registerWithPeers(validatorId, peerUrls);
                
            } else if ("aeron".equalsIgnoreCase(consensusMode)) {
                // AERON CLUSTER CONSENSUS (Raft-based)
                System.out.println("   ✈️  Using Aeron Cluster Consensus (Raft)");
                System.out.println("      - Proven Raft consensus algorithm");
                System.out.println("      - Election safety guarantees");
                System.out.println("      - Majority quorum requirements");
                System.out.println("      - High performance, low latency");
                
                // Get node ID from system property (default: 0)
                int nodeId = Integer.parseInt(System.getProperty("aeron.cluster.nodeId", "0"));
                
                // 🌐 DYNAMIC CLUSTER SIZE: Start with just self, discover peers organically
                // This allows single-node startup (quorum = 1) and dynamic peer discovery
                String hostnamesConfig = System.getProperty("aeron.cluster.hostnames", "");
                List<String> hostnamesList;
                
                // Check if cluster state already exists (discover existing cluster members)
                // Note: clusterBaseDir is created later, but we check for existing cluster state here
                File clusterStateCheckDir = new File(storeDirectory, "aeron-cluster-node-" + nodeId);
                File clusterDir = new File(clusterStateCheckDir, "cluster");
                boolean hasExistingCluster = clusterDir.exists() && clusterDir.listFiles() != null && clusterDir.listFiles().length > 0;
                
                System.out.println("🔍 DEBUG: Cluster state check:");
                System.out.println("   - Cluster dir exists: " + clusterDir.exists());
                System.out.println("   - Cluster dir path: " + clusterDir.getAbsolutePath());
                if (clusterDir.exists()) {
                    System.out.println("   - Cluster dir files: " + (clusterDir.listFiles() != null ? clusterDir.listFiles().length : "null"));
                }
                System.out.println("   - hasExistingCluster: " + hasExistingCluster);
                
                if (hasExistingCluster) {
                    // Existing cluster: Use self + discovered peers (cluster state will have member info)
                    // Include all known peers to join existing cluster
                    hostnamesList = new java.util.ArrayList<>();
                    hostnamesList.add(extractHostname(selfUrl));
                    for (String peerUrl : peerUrls) {
                        String hostname = extractHostname(peerUrl);
                        if (!hostnamesList.contains(hostname)) {
                            hostnamesList.add(hostname);
                        }
                    }
                    // If hostnames were explicitly configured, use those instead (they may include more nodes)
                    if (!hostnamesConfig.isEmpty()) {
                        hostnamesList = new java.util.ArrayList<>(Arrays.asList(hostnamesConfig.split(",")));
                    }
                    System.out.println("🌐 Existing cluster detected - will join with " + hostnamesList.size() + " members");
                } else {
                    // 🛡️ FRESH START: Use all configured hostnames (needed for correct nodeId indexing)
                    // Even though we start with just self, we need the full hostnames list for Aeron Cluster
                    // to correctly map nodeId to hostname
                    if (!hostnamesConfig.isEmpty()) {
                        hostnamesList = new java.util.ArrayList<>(Arrays.asList(hostnamesConfig.split(",")));
                        System.out.println("🌐 Fresh cluster start - using configured hostnames (" + hostnamesList.size() + " members)");
                        System.out.println("   → Starting with self only (quorum = 1), peers will join dynamically");
                    } else {
                        // Fallback: if no hostnames configured, use just self
                        hostnamesList = new java.util.ArrayList<>();
                        hostnamesList.add(extractHostname(selfUrl));
                        System.out.println("🌐 Fresh cluster start - starting with self only (quorum = 1)");
                        System.out.println("   → Peers can join dynamically as they come online");
                    }
                }
                
                // Create Aeron Consensus Engine
                org.apache.jackrabbit.oak.segment.consensus.aeron.AeronConsensusEngine aeronEngine = 
                    new org.apache.jackrabbit.oak.segment.consensus.aeron.AeronConsensusEngine(
                        fileStore, nodeStore, selfUrl, peerUrls, wallet
                    );
                
                // Initialize Ethereum integration if configured
                String beaconApiUrl = System.getProperty("ethereum.beacon.api.url", "https://beaconcha.in/api");
                aeronEngine.initializeEthereumIntegration(beaconApiUrl);
                
                // Create cluster base directory
                File clusterBaseDir = new File(storeDirectory, "aeron-cluster-node-" + nodeId);
                clusterBaseDir.mkdirs();
                
                // Build node ID to URL mapping for leader lookup
                java.util.Map<Integer, String> nodeIdToUrl = new java.util.HashMap<>();
                // Build sorted list of all URLs (self + peers)
                java.util.List<String> allUrls = new java.util.ArrayList<>();
                allUrls.add(selfUrl);
                allUrls.addAll(peerUrls);
                java.util.Collections.sort(allUrls);
                // Map node IDs (0-based) to URLs
                for (int i = 0; i < allUrls.size(); i++) {
                    nodeIdToUrl.put(i, allUrls.get(i));
                }
                aeronEngine.setNodeIdMapping(nodeIdToUrl);
                
                // ✈️ CRITICAL: Set write application callback BEFORE launching cluster
                // This ensures the callback is ready when messages start arriving after launch
                // Production pattern: Callbacks are set before ClusteredServiceContainer.launch()
                aeronEngine.setWriteApplicationCallback((walletAddress, path, contentType, message, signature) -> {
                    httpServer.getConsensusApiHandler().applyReplicatedWrite(
                        walletAddress, path, contentType, message, signature
                    );
                });
                System.out.println("   ✅ Write application callback configured (before cluster launch)");
                
                // Wire Aeron engine to HTTP server context (needed for callback to access ConsensusApiHandler)
                // This must be done before setting callback so callback can access httpServer
                httpServer.setEpochLeaderEngine(null); // Clear epoch leader engine
                httpServer.setAeronConsensusEngine(aeronEngine);
                
                // Launch Aeron Cluster
                // After launch, the ClusteredServiceContainer will start calling onSessionMessage()
                // which needs the callback to be already set
                aeronClusterLauncher = new org.apache.jackrabbit.oak.segment.consensus.aeron.AeronClusterLauncher(
                    nodeId, hostnamesList, clusterBaseDir, aeronEngine
                );
                
                // Set shutdown callback to exit JVM on FATAL MediaDriver errors
                // This allows graceful shutdown and restart (Kubernetes/systemd will restart)
                aeronClusterLauncher.setShutdownCallback(() -> {
                    System.err.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                    System.err.println("🚨 FATAL MediaDriver error - exiting JVM for restart");
                    System.err.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                    
                    // Force exit with multiple mechanisms (production-grade)
                    // System.exit() may not work if threads are hung, so use Runtime.halt() as backup
                    System.exit(1); // Exit with error code (triggers container restart)
                    
                    // If still running after 5 seconds, force kill (prevents zombie processes)
                    new Thread(() -> {
                        try {
                            Thread.sleep(5000);
                            System.err.println("⚠️  JVM still running after System.exit() - forcing halt");
                            Runtime.getRuntime().halt(1); // Force kill
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
                
                // ✈️ REPOSITORY-SERVICE PATTERN: Create AeronWriteClient (separate from ClusteredService)
                // This matches production architecture where AeronClient is created separately and injected
                // Both use the same MediaDriver directory (shared process)
                // NOTE: aeronDirectoryName is set by launcher.launch() - get it after launch
                String aeronDirectoryName = aeronClusterLauncher.getAeronDirectoryName();
                int clusterBasePort = org.apache.jackrabbit.oak.segment.consensus.aeron.AeronClusterLauncher.getPortBase();
                
                // Extract hostname from selfUrl for client hostname
                String clientHostname;
                try {
                    java.net.URL selfUrlParsed = new java.net.URL(selfUrl);
                    clientHostname = selfUrlParsed.getHost();
                } catch (Exception e) {
                    clientHostname = "localhost"; // Fallback
                }
                
                // Create AeronWriteClient (matches production pattern)
                // NOTE: This is the external UDP client - we're now using internal client in ConsensusApiHandler
                // Keeping this for backward compatibility, but ConsensusApiHandler uses internal client
                org.apache.jackrabbit.oak.segment.consensus.aeron.AeronWriteClient aeronWriteClient = 
                    new org.apache.jackrabbit.oak.segment.consensus.aeron.AeronWriteClient(
                        0, // clientId
                        aeronDirectoryName,
                        hostnamesList,
                        clusterBasePort,
                        clientHostname
                    );
                
                // Set AeronClusterLauncher in ServerContext for health checks and metrics
                // This will also initialize AeronPrometheusMetrics if Aeron is available
                httpServer.setAeronClusterLauncher(aeronClusterLauncher);
                
                // If Aeron wasn't available immediately, try to initialize metrics after a delay
                // (container might not be fully initialized yet)
                if (httpServer.getContext().aeronPrometheusMetrics == null) {
                    java.util.concurrent.ScheduledExecutorService delayedInit = 
                        java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
                    delayedInit.schedule(() -> {
                        try {
                            io.aeron.Aeron aeron = aeronClusterLauncher.getAeron();
                            if (aeron != null && httpServer.getContext().aeronPrometheusMetrics == null) {
                                org.apache.jackrabbit.oak.segment.consensus.aeron.AeronPrometheusMetrics metrics =
                                    new org.apache.jackrabbit.oak.segment.consensus.aeron.AeronPrometheusMetrics(aeron);
                                httpServer.getContext().setAeronPrometheusMetrics(metrics);
                                System.out.println("✅ Aeron Prometheus metrics initialized (delayed)");
                            }
                        } catch (Exception e) {
                            System.err.println("⚠️  Failed to initialize Aeron Prometheus metrics (delayed): " + e.getMessage());
                        } finally {
                            delayedInit.shutdown();
                        }
                    }, 5, java.util.concurrent.TimeUnit.SECONDS);
                }
                
                // Connect the client (will retry with backoff)
                try {
                    aeronWriteClient.connect();
                } catch (Exception e) {
                    System.err.println("   ⚠️  WARNING: Failed to connect AeronWriteClient: " + e.getMessage());
                    System.err.println("   → Client will retry on first write attempt");
                }
                
                // Inject into ServerContext (matches production dependency injection pattern)
                // NOTE: ConsensusApiHandler now uses internal client, but keeping this for API compatibility
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
                    // Phase 1: All shards route to the same cluster
                    // Phase 2+: Will support multiple shards
                    java.util.List<String> allPeerUrls = new java.util.ArrayList<>();
                    allPeerUrls.add(selfUrl);
                    allPeerUrls.addAll(peerUrls);
                    
                    org.apache.jackrabbit.oak.segment.consensus.sharding.ShardDirectory shardDirectory = 
                        new org.apache.jackrabbit.oak.segment.consensus.sharding.ShardDirectory(allPeerUrls);
                    
                    // Create wallet-based sharding strategy
                    org.apache.jackrabbit.oak.segment.consensus.sharding.WalletShardingStrategy shardingStrategy = 
                        new org.apache.jackrabbit.oak.segment.consensus.sharding.WalletShardingStrategy(numShards);
                    
                    // Create shard router
                    org.apache.jackrabbit.oak.segment.consensus.sharding.ShardRouter shardRouter = 
                        new org.apache.jackrabbit.oak.segment.consensus.sharding.ShardRouter(shardDirectory, shardingStrategy);
                    
                    // Set in ServerContext
                    httpServer.getContext().setShardRouter(shardRouter);
                    
                    System.out.println("✅ Shard Router initialized");
                    System.out.println("   - Number of shards: " + numShards);
                    System.out.println("   - Shard directory: " + shardDirectory.getNumShards() + " shard(s)");
                    System.out.println("   - Sharding strategy: Wallet-based");
                    if (shardingStrategy.isPowerOfTwo()) {
                        System.out.println("   - Power-of-2: Yes (optimal)");
                    } else {
                        System.out.println("   - Power-of-2: No (consider using power-of-2 for optimal performance)");
                    }
                } catch (Exception e) {
                    System.err.println("⚠️  WARNING: Failed to initialize Shard Router: " + e.getMessage());
                    System.err.println("   → Shard routing disabled, requests will route directly");
                    e.printStackTrace();
                }
                
                System.out.println("✅ Aeron Cluster Consensus engine initialized");
                System.out.println("   - Model: Raft-based consensus (Aeron Cluster)");
                System.out.println("   - Node ID: " + nodeId);
                System.out.println("   - Total validators: " + hostnamesList.size());
                System.out.println("   - Current role: " + aeronEngine.getCurrentRole());
                System.out.println("   - Current leader: " + aeronEngine.getCurrentLeader());
                System.out.println("   - Ethereum epoch: " + aeronEngine.getCurrentEthereumEpoch());
                
                // Initialize Proposal Queue Manager (for Ethereum confirmation tracking)
                // Use EventDrivenEvmBridge for event-driven architecture
                // Configuration via OAK_BLOCKCHAIN_MOCK_MODE env var or oak.blockchain.mockMode system property
                org.apache.jackrabbit.oak.segment.consensus.config.BlockchainConfig blockchainConfig = 
                    org.apache.jackrabbit.oak.segment.consensus.config.BlockchainConfig.getInstance();
                
                org.apache.jackrabbit.oak.segment.consensus.evm.EvmBridge evmBridge = 
                    new org.apache.jackrabbit.oak.segment.consensus.evm.impl.EventDrivenEvmBridge(
                        blockchainConfig.getNetwork(),
                        blockchainConfig.getContractAddress(),
                        blockchainConfig.isMockMode()
                    );
                evmBridge.start();
                
                org.apache.jackrabbit.oak.segment.consensus.queue.RaftAppendCallback raftCallback = 
                    (walletAddress, path, contentType, message, signature) -> {
                        // Append to Raft via AeronConsensusEngine
                        if (aeronEngine != null) {
                            aeronEngine.sendWriteThroughIngress(walletAddress, path, contentType, message, signature);
                        }
                    };
                
                org.apache.jackrabbit.oak.segment.consensus.queue.ProposalQueueManager proposalQueueManager = 
                    new org.apache.jackrabbit.oak.segment.consensus.queue.ProposalQueueManager(evmBridge, raftCallback);
                proposalQueueManager.start();
                httpServer.getContext().setProposalQueueManager(proposalQueueManager);
                httpServer.getContext().evmBridge = evmBridge; // Store for GC Proposal Manager
                System.out.println("   ✅ Proposal Queue Manager initialized (Ethereum confirmation tracking)");
                
                // ✈️ AERON MODE: Skip HTTP peer registration
                // Aeron Cluster handles membership via Raft consensus - HTTP registration is legacy
                // Only register self for /v1/peers API visibility (Aeron membership is source of truth)
                // Note: registerWithPeers will detect aeronConsensusEngine and skip peer registration
                String validatorId = wallet.getWalletAddress();
                // Pass empty list - registerWithPeers will detect Aeron and skip peer registration
                httpServer.registerWithPeers(validatorId, java.util.Collections.emptyList());
                System.out.println("   - Self registered: " + validatorId + " (Aeron Cluster handles peer membership via Raft)");
                
            } else {
                // Unknown consensus mode
                System.err.println("   ❌ ERROR: Unknown consensus mode: " + consensusMode);
                System.err.println("   ⚠️  Supported modes: 'leader' or 'aeron'");
                System.err.println("   ⚠️  Default mode is 'leader'");
                throw new IllegalArgumentException("Unsupported consensus mode: " + consensusMode + ". Use 'leader' or 'aeron'.");
            }
        } else if (!isStandbyMode) {
            // Only print this if NOT in standby mode (standby will init via callback)
            System.out.println();
            System.out.println("ℹ️  Consensus disabled (single-validator mode)");
        } else {
            // STANDBY mode - consensus will be initialized after bootstrap
            System.out.println();
            System.out.println("ℹ️  Consensus initialization deferred (STANDBY mode → callback)");
        }
        
        // Start StandbyServerSync for PRIMARY mode (serve other standbys)
        if (detectedMode == BootstrapMode.PRIMARY || detectedMode == BootstrapMode.GENESIS) {
            if (bootstrap != null) {
                try {
                    bootstrap.startStandbyServer();
                } catch (Exception e) {
                    System.err.println("⚠️  Failed to start StandbyServerSync: " + e.getMessage());
                    // Non-fatal, continue without standby server
                }
            }
        }
        
        // TODO: Smart Contract Event Listener (future implementation)
        // This is where we'll listen to OakNetwork.sol contract events:
        //   - WriteProposed(address indexed wallet, bytes32 indexed writeId, uint256 payment)
        //   - WriteFinalized(bytes32 indexed writeId, bool approved)
        // 
        // Use the /v1/propose-write API for signed write transactions.
        System.out.println();
        System.out.println("📝 Smart Contract Listener: NOT IMPLEMENTED");
        System.out.println("   Future: Listen to OakNetwork.sol events");
        System.out.println("   Current: Use /v1/propose-write API for signed write transactions");
        System.out.println("   Write Pattern: Wallet-based storage at /oak-chain/content/<address>/");
        
        running = true;
        
        System.out.println();
        System.out.println("===========================================");
        System.out.println("  Blockchain AEM - Global Store Server");
        System.out.println("===========================================");
        System.out.println();
        System.out.println("Port:           " + port + " (HTTP)");
        System.out.println("Store:          " + storeDirectory);
        System.out.println("Mount Path:     /oak-chain");
        System.out.println("Access:         READ-WRITE (for consensus)");
        System.out.println("Protocol:       HTTP segment transfer (Cold Standby pattern)");
        System.out.println();
        System.out.println("Server started successfully!");
        System.out.println("Waiting for client connections...");
        System.out.println();
        
        // Keep server running
        while (running) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
    
    /**
     * Initialize genesis content if it doesn't already exist.
     * Creates a simple "DO IT LIVE!" node at /oak-chain/content/genesis
     * following the BYOD model (no binary data, just node structure).
     */
    /**
     * Initialize the IMMORTAL GENESIS NODE.
     * 
     * Like Ethereum's Block 0, this is the birth certificate of the network.
     * All validators MUST sync from this genesis state to join the network.
     * 
     * Contains:
     * - Network identity (chainId, genesisHash)
     * - Consensus rules (leaderTerm, probationPeriod)
     * - Bootstrap instructions (how to join)
     * - Protocol parameters (ports, endpoints)
     */
    private void initializeGenesisContent() {
        try {
            org.apache.jackrabbit.oak.spi.state.NodeState root = nodeStore.getRoot();
            
            // Genesis address: Ethereum zero address (0x0...0)
            // This follows the standard sharded pattern for fault isolation
            String GENESIS_ADDRESS = "0x0000000000000000000000000000000000000000";
            
            // Use sharded path for genesis (00/00/00/0x0000.../)
            String genesisShardedPath = org.apache.jackrabbit.oak.segment.consensus.util.WalletPathUtil.toShardedPath(GENESIS_ADDRESS);
            // Result: /oak-chain/content/00/00/00/0x0000000000000000000000000000000000000000
            
            // Check if genesis content already exists
            org.apache.jackrabbit.oak.spi.state.NodeState oakChain = root.getChildNode("oak-chain");
            if (oakChain.exists()) {
                org.apache.jackrabbit.oak.spi.state.NodeState content = oakChain.getChildNode("content");
                if (content.exists()) {
                    // Navigate through sharded path: content → 00 → 00 → 00 → 0x0000...
                    org.apache.jackrabbit.oak.spi.state.NodeState level1 = content.getChildNode("00");
                    if (level1.exists()) {
                        org.apache.jackrabbit.oak.spi.state.NodeState level2 = level1.getChildNode("00");
                        if (level2.exists()) {
                            org.apache.jackrabbit.oak.spi.state.NodeState level3 = level2.getChildNode("00");
                            if (level3.exists()) {
                                org.apache.jackrabbit.oak.spi.state.NodeState genesisWallet = level3.getChildNode(GENESIS_ADDRESS);
                                if (genesisWallet.exists() && genesisWallet.getChildNode("genesis").exists()) {
                                    System.out.println("   ℹ️  Genesis already exists - verifying integrity...");
                                    
                                    // Verify genesis message (like Ethereum verifies Block 0 hash)
                                    org.apache.jackrabbit.oak.spi.state.NodeState genesisNode = genesisWallet.getChildNode("genesis");
                                    org.apache.jackrabbit.oak.api.PropertyState msgProp = genesisNode.getProperty("protocol.message");
                                    
                                    if (msgProp == null || !"DO IT LIVE!".equals(msgProp.getValue(org.apache.jackrabbit.oak.api.Type.STRING))) {
                                        throw new IllegalStateException("❌ GENESIS CORRUPTION! This node has invalid genesis state.");
                                    }
                                    
                                    System.out.println("   ✅ Genesis integrity verified");
                                    return;
                                }
                            }
                        }
                    }
                }
            }
            
            // Create IMMORTAL GENESIS with sharded path
            System.out.println("   🎂 Creating IMMORTAL GENESIS NODE...");
            System.out.println("      The Birth Certificate of This Network");
            System.out.println("      Address: " + GENESIS_ADDRESS + " (Zero Address)");
            System.out.println("      Sharded Path: " + genesisShardedPath);
            
            org.apache.jackrabbit.oak.spi.state.NodeBuilder rootBuilder = root.builder();
            org.apache.jackrabbit.oak.spi.state.NodeBuilder oakChainBuilder = rootBuilder.child("oak-chain");
            org.apache.jackrabbit.oak.spi.state.NodeBuilder contentBuilder = oakChainBuilder.child("content");
            
            // Create sharded structure: /content/00/00/00/0x0000.../
            org.apache.jackrabbit.oak.spi.state.NodeBuilder level1Builder = contentBuilder.child("00");
            level1Builder.setProperty("jcr:primaryType", "nt:unstructured");
            
            org.apache.jackrabbit.oak.spi.state.NodeBuilder level2Builder = level1Builder.child("00");
            level2Builder.setProperty("jcr:primaryType", "nt:unstructured");
            
            org.apache.jackrabbit.oak.spi.state.NodeBuilder level3Builder = level2Builder.child("00");
            level3Builder.setProperty("jcr:primaryType", "nt:unstructured");
            
            // Create wallet folder for genesis
            org.apache.jackrabbit.oak.spi.state.NodeBuilder genesisWalletBuilder = level3Builder.child(GENESIS_ADDRESS);
            genesisWalletBuilder.setProperty("jcr:primaryType", "nt:unstructured");
            genesisWalletBuilder.setProperty("wallet", GENESIS_ADDRESS);
            genesisWalletBuilder.setProperty("role", "genesis");
            genesisWalletBuilder.setProperty("description", "Network genesis - zero address owns protocol parameters");
            
            // Create genesis node under wallet
            org.apache.jackrabbit.oak.spi.state.NodeBuilder genesis = genesisWalletBuilder.child("genesis");
            
            long timestamp = System.currentTimeMillis();
            String genesisDate = new java.util.Date(timestamp).toString();
            String genesisValidator = System.getProperty("consensus.self.url", "http://localhost:8090");
            String genesisHost = genesisValidator.replace("http://", "").replace("https://", "").split(":")[0];
            
            // JCR Standard
            genesis.setProperty("jcr:primaryType", "nt:unstructured");
            genesis.setProperty("jcr:created", timestamp);
            
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            // PROTOCOL: Network Identity (Immutable)
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            genesis.setProperty("protocol.message", "DO IT LIVE!");
            genesis.setProperty("protocol.version", "1.0.0-POC");
            genesis.setProperty("protocol.chainId", "oak-blockchain-aem-poc");
            genesis.setProperty("protocol.genesisTimestamp", timestamp);
            genesis.setProperty("protocol.genesisDate", genesisDate);
            genesis.setProperty("protocol.description", 
                "Decentralized content storage for Adobe Experience Manager using Oak + Blockchain consensus");
            
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            // CONSENSUS: Network Rules
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            genesis.setProperty("consensus.model", "leader-based-raft");
            genesis.setProperty("consensus.leaderTermSeconds", 300L); // 5 minutes
            genesis.setProperty("consensus.probationSeconds", 300L); // 5 minutes
            genesis.setProperty("consensus.heartbeatIntervalMs", 10000L); // 10 seconds
            genesis.setProperty("consensus.quorumType", "voting-electorate-only");
            genesis.setProperty("consensus.quorumFormula", "(totalVotingMembers / 2) + 1");
            
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            // NETWORK: Bootstrap Configuration
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            genesis.setProperty("network.genesisValidator", genesisValidator);
            genesis.setProperty("network.genesisHost", genesisHost);
            genesis.setProperty("network.bootstrapPort", 8091L);
            genesis.setProperty("network.consensusPort", 8090L);
            genesis.setProperty("network.metricsPort", 8090L);
            genesis.setProperty("network.metricsPath", "/metrics");
            
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            // INSTRUCTIONS: How to Join This Network
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            genesis.setProperty("join.title", "🚀 Welcome to Blockchain AEM Network");
            genesis.setProperty("join.step1.title", "Configure Bootstrap Primary");
            genesis.setProperty("join.step1.env", "BOOTSTRAP_PRIMARY_HOST=" + genesisHost);
            genesis.setProperty("join.step2.title", "Set Bootstrap Port");
            genesis.setProperty("join.step2.env", "BOOTSTRAP_PRIMARY_PORT=8091");
            genesis.setProperty("join.step3.title", "Set Validator Mode");
            genesis.setProperty("join.step3.env", "VALIDATOR_MODE=auto");
            genesis.setProperty("join.step4.title", "Enable Consensus");
            genesis.setProperty("join.step4.env", "CONSENSUS_ENABLED=true");
            genesis.setProperty("join.step5.title", "Set Consensus Mode");
            genesis.setProperty("join.step5.env", "CONSENSUS_MODE=leader");
            genesis.setProperty("join.step6.title", "Set Your Validator URL");
            genesis.setProperty("join.step6.env", "CONSENSUS_SELF_URL=http://your-validator:8090");
            genesis.setProperty("join.step7.note", 
                "After bootstrap, you join as NON-VOTING follower for 300s probation");
            genesis.setProperty("join.step8.note", 
                "After probation, you're eligible for voting and leadership");
            
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            // SECURITY: Byzantine Fault Tolerance
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            genesis.setProperty("security.proofOfReadiness", true);
            genesis.setProperty("security.splitBrainDetection", true);
            genesis.setProperty("security.probationaryPeriod", true);
            genesis.setProperty("security.genesisVerification", true);
            
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            // METADATA: Project Information
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            genesis.setProperty("meta.author", "Blockchain AEM POC Team");
            genesis.setProperty("meta.repository", "Apache Jackrabbit Oak");
            genesis.setProperty("meta.documentation", "See /oak-chain/content/genesis");
            genesis.setProperty("meta.license", "Apache License 2.0");
            
            // BYOD Model (binaries external)
            genesis.setProperty("byod.imageUri", "https://participant-cdn.example.com/assets/do-it-live.jpeg");
            genesis.setProperty("byod.imageMimeType", "image/jpeg");
            genesis.setProperty("byod.note", "Binaries stored in participant-owned datastore, not in global chain");
            
            // Commit the IMMORTAL GENESIS
            nodeStore.merge(rootBuilder, org.apache.jackrabbit.oak.spi.commit.EmptyHook.INSTANCE, 
                           org.apache.jackrabbit.oak.spi.commit.CommitInfo.EMPTY);
            
            System.out.println("   ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            System.out.println("   🎊 IMMORTAL GENESIS NODE CREATED");
            System.out.println("   ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            System.out.println("");
            System.out.println("   📍 LOCATION:");
            System.out.println("      Path: " + genesisShardedPath + "/genesis");
            System.out.println("      Bucket: 00/00/00 (Genesis bucket - fault isolated)");
            System.out.println("      Address: " + GENESIS_ADDRESS + " (Ethereum Zero Address)");
            System.out.println("");
            System.out.println("   🔐 PROTOCOL:");
            System.out.println("      Chain ID: oak-blockchain-aem-poc");
            System.out.println("      Message: \"DO IT LIVE!\"");
            System.out.println("      Version: 1.0.0-POC");
            System.out.println("      Birth: " + genesisDate);
            System.out.println("");
            System.out.println("   🎖️  CONSENSUS:");
            System.out.println("      Model: leader-based-raft");
            System.out.println("      Leader Term: 300 seconds (5 minutes)");
            System.out.println("      Probation: 300 seconds (new validators)");
            System.out.println("      Heartbeat: 10 seconds");
            System.out.println("      Quorum: (totalVotingMembers / 2) + 1");
            System.out.println("");
            System.out.println("   🌐 NETWORK:");
            System.out.println("      Genesis Validator: " + genesisValidator);
            System.out.println("      Bootstrap Host: " + genesisHost);
            System.out.println("      Bootstrap Port: 8091");
            System.out.println("      Consensus Port: 8090");
            System.out.println("");
            System.out.println("   🛡️  SECURITY:");
            System.out.println("      ✅ Proof-of-Readiness (Byzantine protection)");
            System.out.println("      ✅ Split-Brain Detection (quorum enforcement)");
            System.out.println("      ✅ Probationary Period (manipulation prevention)");
            System.out.println("      ✅ Genesis Verification (state integrity)");
            System.out.println("");
            System.out.println("   🚀 TO JOIN THIS NETWORK:");
            System.out.println("      1. BOOTSTRAP_PRIMARY_HOST=" + genesisHost);
            System.out.println("      2. BOOTSTRAP_PRIMARY_PORT=8091");
            System.out.println("      3. VALIDATOR_MODE=auto");
            System.out.println("      4. CONSENSUS_ENABLED=true");
            System.out.println("      5. CONSENSUS_MODE=leader");
            System.out.println("      6. CONSENSUS_SELF_URL=http://your-validator:8090");
            System.out.println("");
            System.out.println("      → You'll join as NON-VOTING follower (300s probation)");
            System.out.println("      → After probation, eligible for voting & leadership");
            System.out.println("");
            System.out.println("   📊 QUERY GENESIS:");
            System.out.println("      GET /api/explore?path=" + genesisShardedPath + "/genesis");
            System.out.println("");
            System.out.println("   ℹ️  ARCHITECTURE:");
            System.out.println("      • Sharded paths for fault isolation");
            System.out.println("      • Max 256 children/node = stable DAG");
            System.out.println("      • Pattern: /content/{L1}/{L2}/{L3}/0x{wallet}/");
            System.out.println("      • SNFE contained to 0.0004% of chain");
            System.out.println("");
            System.out.println("   ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            System.out.println("   🎉 Network initialized and ready for validators!");
            System.out.println("   ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            System.out.println("");
            
        } catch (Exception e) {
            System.err.println("   ❌ FATAL: Failed to create genesis: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Genesis creation failed - cannot start network", e);
        }
    }
    
    /**
     * Start consensus engine after being promoted from STANDBY to PRIMARY.
     * This is called by the bootstrap promotion callback.
     */
    private void startConsensusPrimary() throws IOException {
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("🎯 PRIMARY MODE: Joining consensus network");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        
        String consensusEnabled = System.getProperty("consensus.enabled", "false");
        String consensusMode = System.getProperty("consensus.mode", "leader");
        // Get self URL from system property, or resolve localhost to IP
        String selfUrlConfig = System.getProperty("consensus.self.url");
        String selfUrl;
        if (selfUrlConfig != null && !selfUrlConfig.isEmpty()) {
            selfUrl = selfUrlConfig; // Use configured URL (can be ngrok/Ethos URL, IP, or hostname)
        } else {
            selfUrl = resolveUrlToIP("http://localhost:" + port); // Default: resolve to IP
        }
        String peersConfig = System.getProperty("consensus.peers", "");
        
        if (!"true".equalsIgnoreCase(consensusEnabled)) {
            System.out.println("⚠️  Consensus disabled - running as standalone");
            return;
        }
        
        // If peers are empty but we bootstrapped, use the bootstrap primary as initial peer
        if (peersConfig.isEmpty() && bootstrapPrimaryHost != null && !bootstrapPrimaryHost.isEmpty()) {
            // Derive primary's HTTP URL from bootstrap host
            // Bootstrap uses standby port (8091), consensus uses HTTP port (8090)
            String primaryUrl = "http://" + bootstrapPrimaryHost + ":8090";
            peersConfig = primaryUrl;
            System.out.println("🔗 No static peers configured, using bootstrap primary as initial peer:");
            System.out.println("   Bootstrap host: " + bootstrapPrimaryHost);
            System.out.println("   Consensus peer: " + primaryUrl);
        }
        
        List<String> peerUrls = parsePeerUrls(peersConfig);
        System.out.println("   📋 Parsed peer URLs: " + peerUrls);
        
        if ("leader".equalsIgnoreCase(consensusMode)) {
            int leaderTermSeconds = Integer.parseInt(
                System.getProperty("consensus.leader.term.seconds", "300")
            );
            
            // SCALABLE BOOTSTRAP JOIN
            // Pass isBootstrapJoin=true so constructor skips election math
            // and starts directly as FOLLOWER. Scales to 1000s of validators.
            org.apache.jackrabbit.oak.segment.consensus.leader.EpochLeaderEngine epochEngine = 
                new org.apache.jackrabbit.oak.segment.consensus.leader.EpochLeaderEngine(
                    fileStore, nodeStore, selfUrl, peerUrls, leaderTermSeconds, wallet,
                    true  // isBootstrapJoin = true (post-genesis join)
                );
            
            httpServer.setEpochLeaderEngine(epochEngine);
            
            System.out.println("✅ Epoch Leader Consensus engine initialized");
            System.out.println("   - Join type: BOOTSTRAP (post-genesis)");
            System.out.println("   - Initial role: " + epochEngine.getCurrentRole() + " (no election math)");
            System.out.println("   - Expected leader: " + epochEngine.getCurrentLeader());
            System.out.println("   - Will learn actual leader from heartbeat");
            System.out.println("");
            
            // Broadcast presence to network (Dynamic Peer Discovery)
            // Use wallet address as permanent validator identity
            String validatorId = wallet.getWalletAddress();
            httpServer.broadcastPresenceToNetwork(validatorId, selfUrl, peerUrls);
        }
        
        // Start StandbyServerSync (now a primary, serve other standbys)
        // ✈️ AERON MODE: Skip StandbyServerSync - Aeron Cluster handles replication
        if (bootstrap != null && !"aeron".equalsIgnoreCase(consensusMode)) {
            try {
                bootstrap.startStandbyServer();
            } catch (Exception e) {
                System.err.println("⚠️  Failed to start StandbyServerSync: " + e.getMessage());
            }
        } else if ("aeron".equalsIgnoreCase(consensusMode)) {
            System.out.println("✈️  AERON MODE: Skipping StandbyServerSync (Aeron Cluster handles replication)");
        }
        
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("🚀 Validator is now PRIMARY and participating in consensus!");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    }
    
    /**
     * Start Aeron Cluster after Oak FileStore bootstrap completes.
     * This is called by the bootstrap promotion callback when in Aeron mode.
     */
    private void startAeronClusterAfterBootstrap() throws IOException {
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("✈️  AERON MODE: Starting Aeron Cluster after Oak FileStore bootstrap");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        
        // Use stored configuration from bootstrap detection
        String selfUrl = this.aeronSelfUrl;
        List<String> peerUrls = this.aeronPeerUrls != null ? this.aeronPeerUrls : new java.util.ArrayList<>();
        
        if (selfUrl == null) {
            throw new IOException("Aeron Cluster bootstrap: selfUrl not stored");
        }
        
        // Get node ID from system property (default: 0)
        int nodeId = Integer.parseInt(System.getProperty("aeron.cluster.nodeId", "0"));
        
        // Get hostnames configuration
        String hostnamesConfig = System.getProperty("aeron.cluster.hostnames", "");
        List<String> hostnamesList;
        
        // Check if cluster state already exists
        File clusterStateCheckDir = new File(storeDirectory, "aeron-cluster-node-" + nodeId);
        File clusterDir = new File(clusterStateCheckDir, "cluster");
        boolean hasExistingCluster = clusterDir.exists() && clusterDir.listFiles() != null && clusterDir.listFiles().length > 0;
        
        if (hasExistingCluster) {
            // Existing cluster: Use self + discovered peers
            hostnamesList = new java.util.ArrayList<>();
            hostnamesList.add(extractHostname(selfUrl));
            for (String peerUrl : peerUrls) {
                String hostname = extractHostname(peerUrl);
                if (!hostnamesList.contains(hostname)) {
                    hostnamesList.add(hostname);
                }
            }
            if (!hostnamesConfig.isEmpty()) {
                hostnamesList = new java.util.ArrayList<>(Arrays.asList(hostnamesConfig.split(",")));
            }
            System.out.println("🌐 Existing cluster detected - will join with " + hostnamesList.size() + " members");
        } else {
            // Fresh start: Use configured hostnames
            if (!hostnamesConfig.isEmpty()) {
                hostnamesList = new java.util.ArrayList<>(Arrays.asList(hostnamesConfig.split(",")));
                System.out.println("🌐 Fresh cluster start - using configured hostnames (" + hostnamesList.size() + " members)");
            } else {
                hostnamesList = new java.util.ArrayList<>();
                hostnamesList.add(extractHostname(selfUrl));
                System.out.println("🌐 Fresh cluster start - starting with self only (quorum = 1)");
            }
        }
        
        // Create Aeron Consensus Engine
        org.apache.jackrabbit.oak.segment.consensus.aeron.AeronConsensusEngine aeronEngine = 
            new org.apache.jackrabbit.oak.segment.consensus.aeron.AeronConsensusEngine(
                fileStore, nodeStore, selfUrl, peerUrls, wallet
            );
        
        // Initialize Ethereum integration if configured
        String beaconApiUrl = System.getProperty("ethereum.beacon.api.url", "https://beaconcha.in/api");
        aeronEngine.initializeEthereumIntegration(beaconApiUrl);
        
        // Create cluster base directory
        File clusterBaseDir = new File(storeDirectory, "aeron-cluster-node-" + nodeId);
        clusterBaseDir.mkdirs();
        
        // Build node ID to URL mapping
        java.util.Map<Integer, String> nodeIdToUrl = new java.util.HashMap<>();
        java.util.List<String> allUrls = new java.util.ArrayList<>();
        allUrls.add(selfUrl);
        allUrls.addAll(peerUrls);
        java.util.Collections.sort(allUrls);
        for (int i = 0; i < allUrls.size(); i++) {
            nodeIdToUrl.put(i, allUrls.get(i));
        }
        aeronEngine.setNodeIdMapping(nodeIdToUrl);
        
        // Set write application callback BEFORE launching cluster
        aeronEngine.setWriteApplicationCallback((walletAddress, path, contentType, message, signature) -> {
            httpServer.getConsensusApiHandler().applyReplicatedWrite(
                walletAddress, path, contentType, message, signature
            );
        });
        System.out.println("   ✅ Write application callback configured");
        
        // Wire Aeron engine to HTTP server context
        httpServer.setEpochLeaderEngine(null);
        httpServer.setAeronConsensusEngine(aeronEngine);
        
        // Launch Aeron Cluster
        aeronClusterLauncher = new org.apache.jackrabbit.oak.segment.consensus.aeron.AeronClusterLauncher(
            nodeId, hostnamesList, clusterBaseDir, aeronEngine
        );
        
        // Set shutdown callback
        aeronClusterLauncher.setShutdownCallback(() -> {
            System.err.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            System.err.println("🚨 FATAL MediaDriver error - exiting JVM for restart");
            System.err.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            System.exit(1);
            new Thread(() -> {
                try {
                    Thread.sleep(5000);
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
        
        // Create AeronWriteClient
        String aeronDirectoryName = aeronClusterLauncher.getAeronDirectoryName();
        int clusterBasePort = org.apache.jackrabbit.oak.segment.consensus.aeron.AeronClusterLauncher.getPortBase();
        
        String clientHostname;
        try {
            java.net.URL selfUrlParsed = new java.net.URL(selfUrl);
            clientHostname = selfUrlParsed.getHost();
        } catch (Exception e) {
            clientHostname = "localhost";
        }
        
        org.apache.jackrabbit.oak.segment.consensus.aeron.AeronWriteClient aeronWriteClient = 
            new org.apache.jackrabbit.oak.segment.consensus.aeron.AeronWriteClient(
                0, aeronDirectoryName, hostnamesList, clusterBasePort, clientHostname
            );
        
        httpServer.setAeronClusterLauncher(aeronClusterLauncher);
        
        // Initialize metrics if needed
        if (httpServer.getContext().aeronPrometheusMetrics == null) {
            java.util.concurrent.ScheduledExecutorService delayedInit = 
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
            delayedInit.schedule(() -> {
                try {
                    io.aeron.Aeron aeron = aeronClusterLauncher.getAeron();
                    if (aeron != null && httpServer.getContext().aeronPrometheusMetrics == null) {
                        org.apache.jackrabbit.oak.segment.consensus.aeron.AeronPrometheusMetrics metrics =
                            new org.apache.jackrabbit.oak.segment.consensus.aeron.AeronPrometheusMetrics(aeron);
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
        
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("✈️  Aeron Cluster started successfully!");
        System.out.println("   - Node ID: " + nodeId);
        System.out.println("   - Self URL: " + selfUrl);
        System.out.println("   - Peers: " + peerUrls.size());
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    }
    
    /**
     * Stop the server.
     */
    public void stop() {
        System.out.println("Shutting down global store server...");
        running = false;
        
        // Stop bootstrap (StandbyClientSync + StandbyServerSync)
        if (bootstrap != null) {
            try {
                bootstrap.shutdown();
                System.out.println("✅ Bootstrap services stopped");
            } catch (Exception e) {
                System.err.println("Error stopping bootstrap: " + e.getMessage());
            }
        }
        
        // Stop Aeron Cluster launcher
        if (aeronClusterLauncher != null) {
            try {
                aeronClusterLauncher.shutdown();
                System.out.println("✅ Aeron Cluster stopped");
            } catch (Exception e) {
                System.err.println("Error stopping Aeron Cluster: " + e.getMessage());
            }
        }
        
        // Stop Ethereum epoch listener
        if (epochListener != null) {
            try {
                epochListener.stop();
                System.out.println("✅ Epoch listener stopped");
            } catch (Exception e) {
                System.err.println("Error stopping epoch listener: " + e.getMessage());
            }
        }
        
        // Stop HTTP server
        if (httpServer != null) {
            try {
                httpServer.stop();
                System.out.println("✅ HTTP server stopped");
            } catch (Exception e) {
                System.err.println("Error stopping HTTP server: " + e.getMessage());
            }
        }
        
        // Close FileStore
        if (fileStore != null) {
            try {
                fileStore.close();
                System.out.println("✅ FileStore closed");
            } catch (Exception e) {
                System.err.println("Error closing FileStore: " + e.getMessage());
            }
        }
    }
    
    /**
     * Get the NodeStore (for testing/debugging).
     */
    public NodeStore getNodeStore() {
        return nodeStore;
    }
    
    /**
     * Get the FileStore (for testing/debugging).
     */
    public FileStore getFileStore() {
        return fileStore;
    }
    
    /**
     * Resolve hostname-based URL to IP-based URL for reliable networking.
     * 
     * <p>If the URL contains a hostname (not an IP), resolves it to an IP address.
     * This ensures reliable networking in Docker environments where DNS can be unreliable.
     * 
     * <p>For production deployments (ngrok, Adobe Ethos), set `consensus.self.url` 
     * system property to override this behavior.
     * 
     * @param url URL with hostname (e.g., "http://localhost:8090" or "http://validator-1:8090")
     * @return URL with IP address (e.g., "http://127.0.0.1:8090" or "http://172.18.0.2:8090")
     */
    private String resolveUrlToIP(String url) {
        try {
            java.net.URL parsedUrl = new java.net.URL(url);
            String hostname = parsedUrl.getHost();
            int port = parsedUrl.getPort();
            String protocol = parsedUrl.getProtocol();
            String path = parsedUrl.getPath();
            
            // If already an IP address, return as-is
            if (hostname.matches("^\\d+\\.\\d+\\.\\d+\\.\\d+$")) {
                return url;
            }
            
            // Resolve hostname to IP
            try {
                String ip = java.net.InetAddress.getByName(hostname).getHostAddress();
                String ipUrl = String.format("%s://%s%s%s", 
                    protocol, 
                    ip, 
                    port != -1 ? ":" + port : "", 
                    path != null ? path : "");
                return ipUrl;
            } catch (java.net.UnknownHostException e) {
                // If resolution fails, return original URL (may be ngrok/Ethos URL)
                System.out.println("⚠️  Could not resolve hostname " + hostname + " to IP, using original URL");
                return url;
            }
        } catch (Exception e) {
            System.out.println("⚠️  Failed to parse URL " + url + ": " + e.getMessage() + ", using original");
            return url;
        }
    }
    
    /**
     * Parse comma-separated peer URLs from configuration string.
     * 
     * <p>Peer URLs are resolved to IPs for reliable networking, unless they're
     * explicitly configured as public URLs (ngrok/Ethos).
     */
    private List<String> parsePeerUrls(String peersConfig) {
        List<String> peers = new ArrayList<>();
        if (peersConfig != null && !peersConfig.trim().isEmpty()) {
            String[] urls = peersConfig.split(",");
            for (String url : urls) {
                String trimmed = url.trim();
                if (!trimmed.isEmpty()) {
                    // Resolve hostname to IP for reliable networking
                    // Note: If peer URL is a public URL (ngrok/Ethos), it will be preserved as-is
                    String resolvedUrl = resolveUrlToIP(trimmed);
                    peers.add(resolvedUrl);
                }
            }
        }
        return peers;
    }
    
    /**
     * Extract hostname from URL (e.g., "http://validator-1:8090" -> "validator-1").
     */
    private String extractHostname(String url) {
        try {
            java.net.URL parsedUrl = new java.net.URL(url);
            return parsedUrl.getHost();
        } catch (Exception e) {
            // Fallback: try to extract from URL string
            if (url.contains("://")) {
                String withoutProtocol = url.substring(url.indexOf("://") + 3);
                if (withoutProtocol.contains(":")) {
                    return withoutProtocol.substring(0, withoutProtocol.indexOf(":"));
                }
                return withoutProtocol;
            }
            return "localhost";
        }
    }
    
    /**
     * Sync genesis state from a peer (like downloading genesis block in Ethereum).
     * This ensures all validators start from the same initial HEAD.
     */
    private void syncGenesisFromPeer(String peerUrl) throws Exception {
        System.out.println("      Fetching genesis HEAD from: " + peerUrl);
        
        // Fetch peer's journal to get their HEAD
        String journalUrl = peerUrl + "/journal.log";
        java.net.URL url = new java.net.URL(journalUrl);
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(url.openStream()))) {
            String firstLine = reader.readLine();
            if (firstLine == null || firstLine.trim().isEmpty()) {
                throw new Exception("Peer journal is empty");
            }
            
            // Parse HEAD from journal (format: "segmentId:offset root timestamp")
            String genesisHead = firstLine.split("\\s+")[0];
            System.out.println("      Genesis HEAD: " + genesisHead.substring(0, 16) + "...");
            
            // Check if we already have this HEAD
            org.apache.jackrabbit.oak.segment.RecordId currentHead = fileStore.getHead().getRecordId();
            if (currentHead.toString10().equals(genesisHead)) {
                System.out.println("      ✅ Already at genesis HEAD, skipping");
                return;
            }
            
            System.out.println("      ⚠️  Genesis mismatch detected");
            System.out.println("         Local:  " + currentHead.toString10().substring(0, 16) + "...");
            System.out.println("         Remote: " + genesisHead.substring(0, 16) + "...");
            System.out.println("      🔄 Syncing all segments from genesis...");
            
            // Fetch all missing segments to reach genesis HEAD
            int segmentsFetched = fetchMissingSegmentsForGenesis(peerUrl, genesisHead);
            System.out.println("      ✅ Fetched " + segmentsFetched + " genesis segments");
            
            // Update our HEAD to match genesis
            org.apache.jackrabbit.oak.segment.RecordId genesisRecordId = 
                org.apache.jackrabbit.oak.segment.RecordId.fromString(
                    fileStore.getSegmentIdProvider(),
                    genesisHead
                );
            
            // Force update HEAD (not using CAS since we're syncing genesis)
            boolean updated = fileStore.getRevisions().setHead(currentHead, genesisRecordId);
            if (!updated) {
                throw new Exception("Failed to update HEAD to genesis");
            }
            
            // Flush to persist
            fileStore.flush();
            
            System.out.println("      ✅ Genesis sync complete! HEAD updated: " + genesisHead.substring(0, 16) + "...");
        }
    }
    
    /**
     * Fetch all missing segments needed to reach genesis HEAD.
     * This is similar to ConsensusEngine.fetchMissingSegmentsForHead but for genesis sync.
     */
    private int fetchMissingSegmentsForGenesis(String peerUrl, String targetHead) throws Exception {
        // Parse the target HEAD to get segment ID
        org.apache.jackrabbit.oak.segment.RecordId targetRecordId = 
            org.apache.jackrabbit.oak.segment.RecordId.fromString(
                fileStore.getSegmentIdProvider(),
                targetHead
            );
        
        java.util.UUID targetSegmentId = targetRecordId.getSegmentId().asUUID();
        
        // Use a simple approach: fetch segments working backwards from HEAD
        // For genesis, we expect relatively few segments
        java.util.Set<java.util.UUID> toFetch = new java.util.LinkedHashSet<>();
        java.util.Set<java.util.UUID> fetched = new java.util.HashSet<>();
        java.util.List<java.util.UUID> fetchOrder = new java.util.ArrayList<>();
        
        // Start with HEAD segment
        toFetch.add(targetSegmentId);
        
        // Recursively fetch referenced segments (DFS)
        while (!toFetch.isEmpty()) {
            java.util.Iterator<java.util.UUID> iter = toFetch.iterator();
            java.util.UUID segmentId = iter.next();
            iter.remove();
            
            if (fetched.contains(segmentId)) {
                continue;
            }
            
            // Check if we already have this segment locally
            try {
                fileStore.readSegment(fileStore.getSegmentIdProvider().newSegmentId(
                    segmentId.getMostSignificantBits(),
                    segmentId.getLeastSignificantBits()
                ));
                fetched.add(segmentId);
                continue; // We have it, skip fetching
            } catch (org.apache.jackrabbit.oak.segment.SegmentNotFoundException e) {
                // We don't have it, need to fetch
            }
            
            // Fetch segment data from peer
            String segmentUrl = peerUrl + "/segments/" + segmentId.toString();
            byte[] segmentData = fetchSegmentBytesFromUrl(segmentUrl);
            
            if (segmentData == null) {
                throw new Exception("Failed to fetch segment: " + segmentId);
            }
            
            // TODO: Parse segment to find references
            // For now, just fetch the segment without traversing references
            // This is a simplified approach - full implementation would parse SegmentData
            // org.apache.jackrabbit.oak.commons.Buffer buffer = org.apache.jackrabbit.oak.commons.Buffer.wrap(segmentData);
            // Then extract referenced segments and add to toFetch
            
            // Add to fetch order (will write after all references are written)
            fetchOrder.add(segmentId);
            fetched.add(segmentId);
        }
        
        // Now write all segments in correct order (references first)
        for (java.util.UUID segmentId : fetchOrder) {
            String segmentUrl = peerUrl + "/segments/" + segmentId.toString();
            byte[] segmentData = fetchSegmentBytesFromUrl(segmentUrl);
            
            // Write segment to our TAR files
            org.apache.jackrabbit.oak.segment.SegmentId oakSegmentId = 
                fileStore.getSegmentIdProvider().newSegmentId(
                    segmentId.getMostSignificantBits(),
                    segmentId.getLeastSignificantBits()
                );
            
            org.apache.jackrabbit.oak.commons.Buffer buffer = org.apache.jackrabbit.oak.commons.Buffer.wrap(segmentData);
            fileStore.writeSegment(oakSegmentId, buffer.array(), 0, buffer.remaining());
        }
        
        return fetchOrder.size();
    }
    
    /**
     * Fetch segment bytes from URL.
     */
    private byte[] fetchSegmentBytesFromUrl(String urlString) {
        try {
            java.net.URL url = new java.net.URL(urlString);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            
            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                return null;
            }
            
            try (java.io.InputStream in = conn.getInputStream();
                 java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }
                return out.toByteArray();
            }
        } catch (Exception e) {
            return null;
        }
    }
    
    /**
     * Main entry point.
     */
    public static void main(String[] args) {
        int port = 8090;
        String storeDir = "/var/oak-chain/segmentstore-composite-mount-oak-chain";
        
        // Parse arguments
        for (int i = 0; i < args.length; i++) {
            if ("--port".equals(args[i]) && i + 1 < args.length) {
                port = Integer.parseInt(args[i + 1]);
                i++;
            } else if ("--store".equals(args[i]) && i + 1 < args.length) {
                storeDir = args[i + 1];
                i++;
            } else if ("--help".equals(args[i]) || "-h".equals(args[i])) {
                printUsage();
                return;
            }
        }
        
        final GlobalStoreServer server = new GlobalStoreServer(port, storeDir);
        
        // Shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop();
        }));
        
        try {
            server.start();
        } catch (IOException e) {
            System.err.println("Failed to start server: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
    
    private static void printUsage() {
        System.out.println("Blockchain AEM - Global Store Server");
        System.out.println();
        System.out.println("Usage: java -jar oak-segment-consensus.jar [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --port <port>        Server port (default: 8090)");
        System.out.println("  --store <directory>  Segment store directory (default: /var/oak-chain/segmentstore-composite-mount-oak-chain)");
        System.out.println("  --help, -h           Show this help message");
        System.out.println();
        System.out.println("Example:");
        System.out.println("  java -jar oak-segment-consensus.jar --port 8090 --store /var/oak-chain");
    }
}

