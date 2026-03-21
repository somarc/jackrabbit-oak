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

import org.apache.jackrabbit.oak.segment.consensus.bootstrap.ValidatorBootstrap;
import org.apache.jackrabbit.oak.segment.consensus.bootstrap.ValidatorBootstrap.BootstrapMode;
import org.apache.jackrabbit.oak.segment.consensus.config.RuntimeConfigValueResolver;
import org.apache.jackrabbit.oak.segment.consensus.eth.EpochListener;
import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.apache.jackrabbit.oak.segment.file.InvalidFileStoreVersionException;
import org.apache.jackrabbit.oak.segment.http.server.SegmentHttpServer;
import org.apache.jackrabbit.oak.spi.state.NodeStore;

import java.util.ArrayList;
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
    private org.apache.jackrabbit.oak.spi.blob.BlobStore blobStore;
    private SegmentHttpServer httpServer;
    private EpochListener epochListener;
    private ValidatorBootstrap bootstrap;
    private org.apache.jackrabbit.oak.segment.consensus.security.EthereumWallet wallet;
    private org.apache.jackrabbit.oak.segment.consensus.aeron.AeronClusterService aeronClusterService;
    private GlobalStoreServerComponentFactory componentFactory;
    private org.apache.jackrabbit.oak.segment.consensus.aeron.AeronClusterLauncher aeronClusterLauncher;
    private org.apache.jackrabbit.oak.segment.consensus.gc.GCCostEstimator gcCostEstimator;
    private final StandbyPromotionCoordinator standbyPromotionCoordinator = new StandbyPromotionCoordinator();
    private final ConsensusStartupCoordinator consensusStartupCoordinator = new ConsensusStartupCoordinator();
    private final GenesisStartupCoordinator genesisStartupCoordinator = new GenesisStartupCoordinator();
    private final ServerInfrastructureInitializer serverInfrastructureInitializer = new ServerInfrastructureInitializer();
    
    // Bootstrap configuration (for organic peer discovery after promotion)
    private String bootstrapPrimaryHost;
    private int bootstrapPrimaryPort;
    
    // Aeron Cluster initialization state (for deferred startup after bootstrap)
    @SuppressWarnings("unused") // Used to track bootstrap state, read by bootstrap callback
    private volatile boolean aeronClusterDeferred = false;
    private String aeronSelfUrl; // Stored for bootstrap callback
    private List<String> aeronPeerUrls; // Stored for bootstrap callback
    
    public GlobalStoreServer(int port, String storeDirectory) {
        this.port = port;
        this.storeDirectory = storeDirectory;
    }

    public void setAeronClusterService(org.apache.jackrabbit.oak.segment.consensus.aeron.AeronClusterService service) {
        this.aeronClusterService = service;
    }

    public void setComponentFactory(GlobalStoreServerComponentFactory componentFactory) {
        this.componentFactory = componentFactory;
    }

    private GlobalStoreServerComponentFactory components() {
        return componentFactory != null ? componentFactory : DefaultGlobalStoreServerComponentFactory.INSTANCE;
    }
    
    /**
     * Start the global store server.
     */
    public void start() throws IOException {
        startRuntime();
        awaitStop();
    }

    /**
     * Start runtime components without blocking the calling thread.
     */
    protected void startRuntime() throws IOException {
        // Create store directory if it doesn't exist
        Path storePath = Paths.get(storeDirectory);
        if (!Files.exists(storePath)) {
            Files.createDirectories(storePath);
            System.out.println("Created store directory: " + storePath);
        }
        
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // ETHEREUM WALLETS (ADR 046)
        // - Node wallet: This validator's identity (signing, consensus)
        // - Cluster wallet: Payment destination (read-only awareness)
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        
        // 1. Load/create NODE wallet (this validator's identity for signing)
        String nodeKeystorePath = GlobalStoreRuntimeConfigUtil.resolveNodeKeystorePath(storeDirectory);
        try {
            this.wallet = components().createEthereumWallet(nodeKeystorePath);
            System.out.println("🔑 Node wallet: " + this.wallet.getWalletAddress());
        } catch (Exception e) {
            System.err.println("❌ FATAL: Failed to load/generate node wallet");
            System.err.println("   Keystore path: " + nodeKeystorePath);
            System.err.println("   Error: " + e.getMessage());
            throw new IOException("Node wallet initialization failed", e);
        }
        
        // 2. Read CLUSTER wallet address (ADR 046: all payments go here)
        // Validators only need awareness of the public address, not control
        Path nodeStorePath = Paths.get(storeDirectory);
        Path clusterPath = nodeStorePath.getParent();
        String clusterWalletAddress = null;
        if (clusterPath != null) {
            Path clusterKeystorePath = clusterPath.resolve("cluster-keystore.properties");
            if (Files.exists(clusterKeystorePath)) {
                try {
                    java.util.Properties props = new java.util.Properties();
                    try (java.io.FileInputStream fis = new java.io.FileInputStream(clusterKeystorePath.toFile())) {
                        props.load(fis);
                    }
                    clusterWalletAddress = props.getProperty("walletAddress");
                    if (clusterWalletAddress != null) {
                        System.out.println("💎 Cluster wallet: " + clusterWalletAddress + " (payments go here)");
                    }
                } catch (Exception e) {
                    System.out.println("⚠️  Could not read cluster wallet: " + e.getMessage());
                }
            }
        }
        if (clusterWalletAddress == null) {
            System.out.println("ℹ️  No cluster wallet configured - using node wallet for payments");
            clusterWalletAddress = this.wallet.getWalletAddress();
        }
        // Store cluster wallet address for payment routing
        final String finalClusterWallet = clusterWalletAddress;
        
        // Bootstrap mode (needs to be accessible throughout method)
        BootstrapMode detectedMode = BootstrapMode.PRIMARY;  // Default
        int standbyPort = port + 1;  // Standby port = HTTP port + 1 (used for Oak FileStore bootstrap)
        
        // ✈️ AERON-ONLY POC: This POC uses Aeron Cluster Raft consensus exclusively
        // Check if Aeron mode is enabled (default: true for POC)
        String consensusModeProp = RuntimeConfigValueResolver.readString("consensus.mode", "aeron");
        boolean isAeronMode = "aeron".equalsIgnoreCase(consensusModeProp);
        
        if (!isAeronMode) {
            throw new IllegalArgumentException("This POC only supports Aeron Cluster consensus. Set consensus.mode=aeron or omit it (defaults to aeron).");
        }
        
        // Check if store directory is empty BEFORE building FileStore
        // This prevents Oak from creating a new HEAD before we can bootstrap
        File storeDir = new File(storeDirectory);
        boolean directoryIsEmpty = false;
        if (storeDir.exists() && storeDir.isDirectory()) {
            File[] files = storeDir.listFiles((dir, name) -> 
                name.startsWith("data") && name.endsWith(".tar") || 
                name.equals("journal.log") || 
                name.startsWith("journal.log"));
            directoryIsEmpty = (files == null || files.length == 0);
        } else {
            directoryIsEmpty = true; // Directory doesn't exist = empty
        }
        
        // Check if standby bootstrap is needed before FileStore build.
        // NOTE: In Aeron mode, standby bootstrap is disabled by default to avoid
        // deadlocks where peers are healthy on HTTP but standby sync port is not serving yet.
        // Explicit opt-in is required via: -Dconsensus.aeron.standby.bootstrap.enabled=true
        boolean needsBootstrapBeforeBuild = false;
        boolean hasVerifiedReachablePeers = false;
        boolean standbyBootstrapEnabled = RuntimeConfigValueResolver.readBoolean(
            "consensus.aeron.standby.bootstrap.enabled",
            false
        );
        String verifiedBootstrapPrimaryHost = "";
        int verifiedBootstrapPrimaryPort = 0;
        
        if (isAeronMode && directoryIsEmpty) {
            List<String> aeronPeers = GlobalStoreRuntimeConfigUtil.resolvePeerUrls(currentAeronConfig());
            String bootstrapPrimaryHost = RuntimeConfigValueResolver.readString("bootstrap.primary.host", "");
            String bootstrapPrimaryPortStr = RuntimeConfigValueResolver.readString("bootstrap.primary.port", "");
            BootstrapPreflightPlanner.Decision preflightDecision = new BootstrapPreflightPlanner().plan(
                isAeronMode,
                directoryIsEmpty,
                aeronPeers,
                bootstrapPrimaryHost,
                bootstrapPrimaryPortStr,
                standbyBootstrapEnabled,
                port + 1
            );
            needsBootstrapBeforeBuild = preflightDecision.needsBootstrapBeforeBuild();
            hasVerifiedReachablePeers = preflightDecision.hasVerifiedReachablePeers();
            verifiedBootstrapPrimaryHost = preflightDecision.verifiedBootstrapPrimaryHost();
            verifiedBootstrapPrimaryPort = preflightDecision.verifiedBootstrapPrimaryPort();

            if (hasVerifiedReachablePeers && !verifiedBootstrapPrimaryHost.isEmpty()) {
                boolean matchedPeerUrl = false;
                for (String peerUrl : aeronPeers) {
                    try {
                        java.net.URL url = new java.net.URL(peerUrl);
                        if (verifiedBootstrapPrimaryHost.equals(url.getHost())) {
                            System.out.println("✅ Verified reachable peer: " + peerUrl);
                            matchedPeerUrl = true;
                            break;
                        }
                    } catch (Exception e) {
                        // Ignore malformed peer URL in startup logging.
                    }
                }
                if (!matchedPeerUrl && !bootstrapPrimaryHost.isEmpty()) {
                    System.out.println("✅ Verified bootstrap primary: " + verifiedBootstrapPrimaryHost + ":" + verifiedBootstrapPrimaryPort);
                }
            } else if (!bootstrapPrimaryHost.isEmpty()) {
                System.out.println("⚠️  Bootstrap primary configured but not reachable: " + bootstrapPrimaryHost);
                System.out.println("   Will fall back to GENESIS mode if store is empty");
            }

            if (needsBootstrapBeforeBuild) {
                System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                System.out.println("⚠️  CRITICAL: Empty store directory detected");
                System.out.println("   Bootstrap needed - verified peer is reachable");
                System.out.println("   This ensures all validators start with same genesis HEAD");
                System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                System.out.println("   Bootstrap primary: " + verifiedBootstrapPrimaryHost + ":" + verifiedBootstrapPrimaryPort);
            } else if (hasVerifiedReachablePeers) {
                System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                System.out.println("ℹ️  Empty store directory with reachable peers detected");
                System.out.println("   Aeron standby bootstrap disabled (default)");
                System.out.println("   Starting Aeron cluster directly; consensus leader will create canonical genesis");
                System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            } else if (directoryIsEmpty) {
                System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                System.out.println("⚠️  Empty store directory detected, but no reachable peers");
                System.out.println("   Will create genesis state (this node becomes genesis)");
                System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            }
        }
        
        // Initialize Oak FileStore
        System.out.println("Initializing Oak FileStore...");
        try {
            
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            // BOOTSTRAP ARCHITECTURE: Genesis via Aeron Consensus
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            // PROBLEM: Oak's fileStore.build() automatically creates local genesis
            //          When 3 validators start simultaneously, each creates
            //          slightly different genesis (due to timing/threading)
            //          → Divergent DAGs from T0!
            //
            // SOLUTION: Aeron leader creates canonical genesis via consensus
            //   1. All validators call fileStore.build() → creates local genesis
            //   2. Aeron cluster forms and elects leader (requires quorum)
            //   3. Leader creates /oak-chain structure via consensus
            //   4. Followers receive leader's genesis write
            //   5. Result: All HEADs converge to leader's genesis
            //
            // KNOWN TRADE-OFF: Followers have 1-2 extra journal entries
            //   (their local genesis + leader's consensus genesis)
            //   These are harmless and get cleaned up by:
            //   - First Aeron snapshot (overwrites entire FileStore)
            //   - Normal garbage collection
            //
            // FUTURE: Could be eliminated by modifying Oak core to support
            //         "deferred genesis" mode, but out of scope for POC.
            
            ServerInfrastructureInitializer.InitializationResult infrastructure =
                serverInfrastructureInitializer.initialize(
                    storeDir,
                    port,
                    currentAeronConfig(),
                    components()
                );
            org.apache.jackrabbit.oak.spi.blob.BlobStore blobStore = infrastructure.getBlobStore();
            this.blobStore = blobStore; // Store reference for genesis image upload
            this.fileStore = infrastructure.getFileStore();
            this.nodeStore = infrastructure.getNodeStore();
            this.httpServer = infrastructure.getHttpServer();
            this.gcCostEstimator = infrastructure.getGcCostEstimator();
            String selfUrl = this.httpServer.getContext().selfUrl;
            
            // If bootstrap is needed, mark for immediate sync (before any other initialization)
            if (needsBootstrapBeforeBuild) {
                System.out.println("   ⚠️  Initial HEAD created (will be replaced by bootstrap sync)");
            }
            
            
            // ✈️ AERON-ONLY: Bootstrap logic is handled above in Aeron mode detection
            // No separate mode-specific bootstrap logic needed
            
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
                // Use directory emptiness check (done BEFORE FileStore build) instead of fileStore.size()
                // This is more reliable - fileStore.size() might be > 0 even for a fresh FileStore
                // if Oak creates initial segments, but directory emptiness is definitive
                boolean storeIsEmpty = directoryIsEmpty;
                
                // Parse peer URLs for bootstrap
                List<String> aeronPeers = GlobalStoreRuntimeConfigUtil.resolvePeerUrls(currentAeronConfig());
                
                // If bootstrap was needed before build, ensure it runs NOW (immediately after FileStore build)
                // CRITICAL: Re-verify peer reachability AFTER FileStore build (peer might have come online)
                // But use verified peer info from before build to avoid getting stuck
                if (needsBootstrapBeforeBuild) {
                    // ✈️ AERON MODE: Empty store + verified peers exist → Bootstrap Oak FileStore FIRST
                    // This ensures all validators start with same genesis HEAD
                    System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                    System.out.println("✈️  AERON MODE: Empty store detected");
                    System.out.println("   Bootstrapping Oak FileStore from verified peer BEFORE Aeron Cluster join");
                    System.out.println("   This ensures deterministic genesis (all validators have same HEAD)");
                    System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                    
                    // Store Aeron Cluster config for bootstrap callback
                    // (Will be set later in start() method, but we need to mark as deferred)
                    this.aeronClusterDeferred = true;
                    // Store peer URLs for later use in bootstrap callback
                    this.aeronPeerUrls = aeronPeers;
                    
                    // Use ValidatorBootstrap to sync Oak FileStore
                    bootstrap = components().createValidatorBootstrap(fileStore, standbyPort);
                    
                    // Use verified bootstrap primary (from before FileStore build)
                    String primaryHost = verifiedBootstrapPrimaryHost;
                    int primaryPort = verifiedBootstrapPrimaryPort;
                    
                    // Fallback: If verified info not available, try system properties
                    if (primaryHost.isEmpty()) {
                        primaryHost = RuntimeConfigValueResolver.readString("bootstrap.primary.host", "");
                        String bootstrapPrimaryPortStr = RuntimeConfigValueResolver.readString("bootstrap.primary.port", String.valueOf(port + 1));
                        try {
                            primaryPort = Integer.parseInt(bootstrapPrimaryPortStr);
                        } catch (NumberFormatException e) {
                            primaryPort = port + 1;
                        }
                    }
                    
                    // Final fallback: Use first peer from consensus.peers
                    if (primaryHost.isEmpty() && !aeronPeers.isEmpty()) {
                        String firstPeer = aeronPeers.get(0);
                        primaryHost = firstPeer.replace("http://", "").replace("https://", "").split(":")[0];
                        try {
                            int httpPort = Integer.parseInt(firstPeer.split(":")[2]);
                            primaryPort = httpPort + 1; // Standby port = HTTP port + 1
                        } catch (Exception e) {
                            primaryPort = port + 1; // Fallback
                        }
                        System.out.println("   Using first peer as bootstrap primary: " + primaryHost + ":" + primaryPort);
                    }
                    
                    if (primaryHost.isEmpty()) {
                        // CRITICAL: Don't get stuck - fall back to GENESIS mode
                        System.err.println("❌ ERROR: Bootstrap needed but no primary host available");
                        System.err.println("   Falling back to GENESIS mode (this node will create genesis state)");
                        detectedMode = BootstrapMode.GENESIS;
                        bootstrap = components().createValidatorBootstrap(fileStore, standbyPort);
                    } else {
                        // Store verified primary info for bootstrap
                        this.bootstrapPrimaryHost = primaryHost;
                        this.bootstrapPrimaryPort = primaryPort;
                        detectedMode = BootstrapMode.STANDBY; // Will bootstrap Oak FileStore
                        System.out.println("   Bootstrap mode: STANDBY (will sync Oak FileStore, then start Aeron Cluster)");
                        System.out.println("   Bootstrap primary: " + primaryHost + ":" + primaryPort);
                    }
                } else if (storeIsEmpty) {
                    // ✈️ AERON MODE (PARALLEL LAUNCH): Empty store → Start Aeron cluster, genesis created by elected leader
                    System.out.println("✈️  AERON MODE: Empty store detected");
                    System.out.println("   Starting Aeron Cluster in parallel with peers");
                    System.out.println("   Genesis will be created by elected leader via consensus");
                    System.out.println("   All validators will replicate genesis → identical HEADs");
                    detectedMode = BootstrapMode.PRIMARY; // Start Aeron directly, let consensus handle genesis
                    // Initialize bootstrap for StandbyServerSync (so late-joining validators can sync)
                    bootstrap = components().createValidatorBootstrap(fileStore, standbyPort);
                } else {
                    // ✈️ AERON MODE: Store has data → Start Aeron directly
                    // Aeron will handle Raft log bootstrap/replay
                    System.out.println("✈️  AERON MODE: Existing store found");
                    System.out.println("   Starting Aeron Cluster (will replay Raft log if needed)");
                    detectedMode = BootstrapMode.PRIMARY;
                    // Initialize bootstrap for StandbyServerSync (so late-joining validators can sync)
                    bootstrap = components().createValidatorBootstrap(fileStore, standbyPort);
                }
            }
            
            // ✈️ AERON-ONLY: Handle STANDBY bootstrap (sync FileStore, then start Aeron Cluster)
            if (detectedMode == BootstrapMode.STANDBY) {
                // STANDBY MODE: Bootstrap Oak FileStore from existing validator
                // After bootstrap completes, start Aeron Cluster
                
                // CRITICAL: Store Aeron configuration BEFORE bootstrap starts (needed for callback)
                // Get self URL from system property, or resolve localhost to IP
                String selfUrlStandy = GlobalStoreRuntimeConfigUtil.resolveSelfUrl(port, currentAeronConfig());
                List<String> peerUrlsStandy = GlobalStoreRuntimeConfigUtil.resolvePeerUrls(currentAeronConfig());
                
                // Store for bootstrap callback
                this.aeronSelfUrl = selfUrlStandy;
                this.aeronPeerUrls = peerUrlsStandy;
                
                // Determine which peer to bootstrap from
                String primaryHostInitial = bootstrapPrimaryHost;
                int primaryPortInitial = bootstrapPrimaryPort;
                
                // Use Aeron peer URLs (already verified earlier)
                List<String> bootstrapPeers = this.aeronPeerUrls != null ? this.aeronPeerUrls : new ArrayList<>();
                
                StandbyPromotionCoordinator.BootstrapTarget bootstrapTarget =
                    standbyPromotionCoordinator.resolveBootstrapTarget(
                        primaryHostInitial,
                        primaryPortInitial,
                        bootstrapPeers,
                        port
                    );

                if (bootstrapTarget == null) {
                    throw new IOException("STANDBY mode requires bootstrap.primary.host or consensus.peers");
                }

                if ((primaryHostInitial == null || primaryHostInitial.isEmpty()) && !bootstrapPeers.isEmpty()) {
                    System.out.println("🔍 Using first peer as bootstrap primary: "
                        + bootstrapTarget.getHost() + ":" + bootstrapTarget.getPort());
                }
                
                // Bootstrap from primary (this will block until initial sync, then schedule periodic sync)
                standbyPromotionCoordinator.bootstrapAndPromote(bootstrap, bootstrapTarget, () -> {
                    System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                    System.out.println("🎖️  PROMOTED TO PRIMARY - Oak FileStore bootstrap complete");
                    System.out.println("   Local HEAD: " + fileStore.getHead().getRecordId());
                    System.out.println("   Starting Aeron Cluster...");
                    System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                    try {
                        // ✈️ AERON-ONLY: Start Aeron Cluster after Oak FileStore bootstrap
                        System.out.println("✈️  Starting Aeron Cluster (Oak FileStore already synced)");
                        StandbyPromotionCoordinator.DeferredAeronStartup deferredStartup =
                            standbyPromotionCoordinator.startDeferredCluster(
                                components(),
                                aeronClusterService,
                                new StandbyPromotionCoordinator.DeferredAeronStartupContext(
                                    fileStore,
                                    nodeStore,
                                    httpServer,
                                    wallet,
                                    storeDirectory,
                                    blobStore,
                                    aeronSelfUrl,
                                    aeronPeerUrls
                                )
                            );
                        aeronClusterService = deferredStartup.getAeronClusterService();
                        aeronClusterLauncher = deferredStartup.getStartupResult().getLauncher();
                        
                        // Start HTTP server (was deferred in STANDBY mode)
                        System.out.println("Starting HTTP server (deferred from STANDBY mode)...");
                        httpServer.start();
                        System.out.println("✅ HTTP server started on port " + port);
                    } catch (Exception e) {
                        System.err.println("❌ Failed to start Aeron Cluster after promotion: " + e.getMessage());
                        e.printStackTrace();
                    }
                });
                
            } else if (detectedMode == BootstrapMode.GENESIS) {
                // GENESIS MODE: DEFER genesis creation until Aeron cluster reaches quorum
                // NEW ARCHITECTURE: Genesis should be the FIRST consensus write, not a pre-consensus local write
                // This ensures all validators have identical segment history from genesis
                System.out.println("🌍 GENESIS MODE DEFERRED: Will create genesis AFTER Aeron cluster forms");
                System.out.println("   Genesis will be created as the first replicated write through consensus");
                System.out.println("   This ensures all validators start with identical state");
            } else {
                // PRIMARY MODE: Check if genesis already exists before initializing.
                // Empty stores (no genesis) will have genesis created by elected leader via consensus.
            }

            if (detectedMode == BootstrapMode.GENESIS || detectedMode == BootstrapMode.PRIMARY) {
                genesisStartupCoordinator.initialize(
                    new GenesisStartupCoordinator.StartupContext(
                        detectedMode,
                        bootstrap,
                        standbyPort,
                        nodeStore,
                        fileStore,
                        blobStore,
                        selfUrl,
                        components()
                    )
                );
            }
            
        } catch (InvalidFileStoreVersionException e) {
            throw new IOException("Invalid FileStore version", e);
        }
        
        // Start HTTP server (deferred in STANDBY mode until bootstrap completes)
        // Note: In real-world deployments, all validators use standardized ports (HTTP=8090, Standby=8091)
        //       because they run on different hosts. For local dev, we use different ports (8091, 8092, 8093)
        //       to avoid conflicts on the same machine.
        if (detectedMode == BootstrapMode.STANDBY) {
            // STANDBY mode: HTTP server starts after bootstrap completes (prevents port conflicts in local dev)
            System.out.println("⏸️  HTTP server startup deferred (STANDBY mode - will start after bootstrap completes)");
        } else {
            // PRIMARY or GENESIS mode: Start HTTP server immediately
            System.out.println("Starting HTTP server...");
            try {
                httpServer.start();
                System.out.println("✅ HTTP server started on port " + port);
            } catch (Exception e) {
                throw new IOException("Failed to start HTTP server", e);
            }
        }
        
        org.apache.jackrabbit.oak.segment.consensus.aeron.AeronClusterConfig aeronConfig =
            aeronClusterService != null ? aeronClusterService.getConfig() : null;
        boolean isStandbyMode = (detectedMode == BootstrapMode.STANDBY);

        ConsensusStartupCoordinator.StartupOutcome consensusStartup = consensusStartupCoordinator.initialize(
            new ConsensusStartupCoordinator.StartupContext(
                port,
                isAeronMode,
                isStandbyMode,
                fileStore,
                nodeStore,
                httpServer,
                wallet,
                storeDirectory,
                this.blobStore,
                aeronClusterService,
                components(),
                finalClusterWallet,
                aeronConfig
            )
        );

        if (isAeronMode && !isStandbyMode) {
            this.aeronSelfUrl = consensusStartup.getSelfUrl();
            this.aeronPeerUrls = consensusStartup.getPeerUrls();
        }
        this.aeronClusterService = consensusStartup.getAeronClusterService();
        this.aeronClusterLauncher = consensusStartup.getLauncher();

        if (consensusStartup.getDisposition() == ConsensusStartupCoordinator.StartupDisposition.DISABLED) {
            // Only print this if NOT in standby mode (standby will init via callback)
            System.out.println();
            System.out.println("ℹ️  Consensus disabled (single-validator mode)");
        } else if (consensusStartup.getDisposition() == ConsensusStartupCoordinator.StartupDisposition.DEFERRED) {
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
        
        // SEPOLIA_PHASE: Smart Contract Event Listener
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
    }

    /**
     * Block until the server is stopped.
     */
    protected void awaitStop() {
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
        if (aeronClusterService != null) {
            try {
                aeronClusterService.shutdown();
                System.out.println("✅ Aeron Cluster stopped");
            } catch (Exception e) {
                System.err.println("Error stopping Aeron Cluster: " + e.getMessage());
            }
        } else if (aeronClusterLauncher != null) {
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

    private org.apache.jackrabbit.oak.segment.consensus.aeron.AeronClusterConfig currentAeronConfig() {
        return aeronClusterService != null ? aeronClusterService.getConfig() : null;
    }

    private static IOException startupFailure(String message) {
        return new IOException(message);
    }

    private static IOException startupFailure(String message, Exception cause) {
        return new IOException(message, cause);
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
