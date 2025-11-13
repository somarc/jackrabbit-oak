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
 * Standalone server for the global Blockchain AEM repository.
 * <p>
 * This server hosts the read-only global segment store that contains
 * all wallet-owned content at /oak-chain/content/<wallet-uuid>/*
 * <p>
 * Other AEM instances connect to this server and mount the content
 * as a read-only Composite NodeStore mount.
 * <p>
 * Usage:
 * <pre>
 * java -jar oak-segment-consensus.jar \
 *   --port 8090 \
 *   --store /var/oak-chain/segmentstore
 * </pre>
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
    
    // Bootstrap configuration (for organic peer discovery after promotion)
    private String bootstrapPrimaryHost;
    private int bootstrapPrimaryPort;
    
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
            System.out.println("✅ HTTP server initialized (not yet started)");
            
            // Check consensus mode FIRST to determine if bootstrap is needed
            String consensusMode = System.getProperty("consensus.mode", "leader");
            boolean isAeronMode = "aeron".equalsIgnoreCase(consensusMode);
            
            // Declare variables for bootstrap logic (needed for EpochLeaderEngine mode)
            List<String> peers = new java.util.ArrayList<>();
            int standbyPort = port + 1;
            
            // BOOTSTRAP: Only needed for EpochLeaderEngine, NOT for Aeron Cluster
            // ===========================================================================
            if (isAeronMode) {
                // ✈️ AERON MODE: Skip ValidatorBootstrap entirely
                // Aeron Cluster handles its own bootstrap via AeronBackup.restore()
                // and its own membership via Raft consensus
                System.out.println("✈️  AERON MODE: Skipping ValidatorBootstrap (Aeron Cluster handles bootstrap & membership)");
                detectedMode = BootstrapMode.PRIMARY;
                bootstrap = null; // Don't initialize bootstrap for Aeron
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
            
            if (!isAeronMode) {
                // Only run bootstrap logic for EpochLeaderEngine mode
                if (detectedMode == BootstrapMode.STANDBY) {
                    // STANDBY MODE: Bootstrap from existing validator
                    
                    // Determine which peer to bootstrap from
                    String primaryHost = bootstrapPrimaryHost;
                    int primaryPort = bootstrapPrimaryPort;
                    
                    if (primaryHost.isEmpty() && !peers.isEmpty()) {
                        // Use first peer as primary
                        String firstPeer = peers.get(0);
                        // Parse URL (e.g., "http://validator-1:8090")
                        primaryHost = firstPeer.replace("http://", "").replace("https://", "").split(":")[0];
                        primaryPort = standbyPort;  // Assume same standby port offset
                        System.out.println("🔍 Using first peer as primary: " + primaryHost + ":" + primaryPort);
                    }
                    
                    if (primaryHost.isEmpty()) {
                        throw new IOException("STANDBY mode requires bootstrap.primary.host or consensus.peers");
                    }
                    
                    // Bootstrap from primary (this will block until initial sync, then schedule periodic sync)
                    bootstrap.bootstrapFromPrimary(primaryHost, primaryPort, () -> {
                        System.out.println("🎖️  PROMOTED TO PRIMARY - starting consensus...");
                        try {
                            startConsensusPrimary();
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
            } else {
                // Aeron mode: Just initialize genesis content (if needed)
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
        
        // Allow leader consensus even with no peers (single validator = leader of 1)
        boolean enableConsensus = "true".equalsIgnoreCase(consensusEnabled) && 
                                 ("leader".equalsIgnoreCase(consensusMode) || !peersConfig.isEmpty());
        
        // CRITICAL: Don't initialize consensus here if we're in STANDBY mode
        // The bootstrap promotion callback (startConsensusPrimary) will initialize it
        boolean isStandbyMode = (detectedMode == BootstrapMode.STANDBY);
        
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
                    System.exit(1); // Exit with error code (triggers container restart)
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
                
                System.out.println("✅ Aeron Cluster Consensus engine initialized");
                System.out.println("   - Model: Raft-based consensus (Aeron Cluster)");
                System.out.println("   - Node ID: " + nodeId);
                System.out.println("   - Total validators: " + hostnamesList.size());
                System.out.println("   - Current role: " + aeronEngine.getCurrentRole());
                System.out.println("   - Current leader: " + aeronEngine.getCurrentLeader());
                System.out.println("   - Ethereum epoch: " + aeronEngine.getCurrentEthereumEpoch());
                
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

