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

import org.apache.jackrabbit.oak.segment.consensus.bootstrap.ValidatorBootstrap;
import org.apache.jackrabbit.oak.segment.consensus.bootstrap.ValidatorBootstrap.BootstrapMode;
import org.apache.jackrabbit.oak.segment.consensus.eth.EpochListener;
import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.apache.jackrabbit.oak.segment.file.InvalidFileStoreVersionException;
import org.apache.jackrabbit.oak.segment.http.server.SegmentHttpServer;
import org.apache.jackrabbit.oak.spi.state.NodeStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private final Logger log;
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
    private final WalletStartupCoordinator walletStartupCoordinator = new WalletStartupCoordinator();
    private final StartupPreflightCoordinator startupPreflightCoordinator = new StartupPreflightCoordinator();
    private final StandbyModeStartupCoordinator standbyModeStartupCoordinator = new StandbyModeStartupCoordinator();
    private final BootstrapModeCoordinator bootstrapModeCoordinator = new BootstrapModeCoordinator();
    private final GenesisStartupCoordinator genesisStartupCoordinator = new GenesisStartupCoordinator();
    private final ServerInfrastructureInitializer serverInfrastructureInitializer = new ServerInfrastructureInitializer();
    private final ServerActivationCoordinator serverActivationCoordinator = new ServerActivationCoordinator();

    public GlobalStoreServer(int port, String storeDirectory) {
        this.port = port;
        this.storeDirectory = storeDirectory;
        this.log = LoggerFactory.getLogger(GlobalStoreServer.class);
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
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // ETHEREUM WALLETS (ADR 046)
        // - Node wallet: This validator's identity (signing, consensus)
        // - Cluster wallet: Payment destination (read-only awareness)
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        
        WalletStartupCoordinator.StartupResult walletStartup =
            walletStartupCoordinator.initialize(storeDirectory, components());
        this.wallet = walletStartup.getWallet();
        final String finalClusterWallet = walletStartup.getClusterWalletAddress();
        
        StartupPreflightCoordinator.PreflightResult preflight =
            startupPreflightCoordinator.prepare(storeDirectory, port, currentAeronConfig());
        File storeDir = preflight.getStoreDir();
        boolean isAeronMode = preflight.isAeronMode();
        boolean directoryIsEmpty = preflight.isDirectoryEmpty();
        boolean needsBootstrapBeforeBuild = preflight.needsBootstrapBeforeBuild();
        String verifiedBootstrapPrimaryHost = preflight.getVerifiedBootstrapPrimaryHost();
        int verifiedBootstrapPrimaryPort = preflight.getVerifiedBootstrapPrimaryPort();
        
        // Bootstrap mode (needs to be accessible throughout method)
        BootstrapMode detectedMode = BootstrapMode.PRIMARY;  // Default
        int standbyPort = port + 1;  // Standby port = HTTP port + 1 (used for Oak FileStore bootstrap)
        
        // Initialize Oak FileStore
        log.info("Initializing Oak FileStore...");
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
                log.warn("   ⚠️  Initial HEAD created (will be replaced by bootstrap sync)");
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
            BootstrapModeCoordinator.Resolution bootstrapResolution = bootstrapModeCoordinator.resolve(
                new BootstrapModeCoordinator.StartupContext(
                    isAeronMode,
                    directoryIsEmpty,
                    needsBootstrapBeforeBuild,
                    fileStore,
                    port,
                    standbyPort,
                    verifiedBootstrapPrimaryHost,
                    verifiedBootstrapPrimaryPort,
                    currentAeronConfig(),
                    components()
                )
            );
            detectedMode = bootstrapResolution.getDetectedMode();
            bootstrap = bootstrapResolution.getBootstrap();
            
            // ✈️ AERON-ONLY: Handle STANDBY bootstrap (sync FileStore, then start Aeron Cluster)
            if (detectedMode == BootstrapMode.STANDBY) {
                standbyModeStartupCoordinator.initialize(
                    new StandbyModeStartupCoordinator.StartupContext(
                        port,
                        storeDirectory,
                        bootstrapResolution.getBootstrapPrimaryHost(),
                        bootstrapResolution.getBootstrapPrimaryPort(),
                        currentAeronConfig(),
                        bootstrap,
                        fileStore,
                        nodeStore,
                        httpServer,
                        wallet,
                        blobStore,
                        aeronClusterService,
                        components(),
                        deferredStartup -> {
                            aeronClusterService = deferredStartup.getAeronClusterService();
                            aeronClusterLauncher = deferredStartup.getStartupResult().getLauncher();
                        }
                    )
                );
            } else if (detectedMode == BootstrapMode.GENESIS) {
                // GENESIS MODE: DEFER genesis creation until Aeron cluster reaches quorum
                // NEW ARCHITECTURE: Genesis should be the FIRST consensus write, not a pre-consensus local write
                // This ensures all validators have identical segment history from genesis
                log.info("🌍 GENESIS MODE DEFERRED: Will create genesis AFTER Aeron cluster forms");
                log.info("   Genesis will be created as the first replicated write through consensus");
                log.info("   This ensures all validators start with identical state");
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
        
        ServerActivationCoordinator.ActivationResult activation =
            serverActivationCoordinator.activate(
                new ServerActivationCoordinator.ActivationContext(
                    port,
                    isAeronMode,
                    detectedMode,
                    fileStore,
                    nodeStore,
                    httpServer,
                    wallet,
                    storeDirectory,
                    this.blobStore,
                    aeronClusterService,
                    aeronClusterLauncher,
                    components(),
                    finalClusterWallet,
                    bootstrap
                )
            );

        this.aeronClusterService = activation.getAeronClusterService();
        this.aeronClusterLauncher = activation.getLauncher();
        
        // SEPOLIA_PHASE: Smart Contract Event Listener
        // This is where we'll listen to OakNetwork.sol contract events:
        //   - WriteProposed(address indexed wallet, bytes32 indexed writeId, uint256 payment)
        //   - WriteFinalized(bytes32 indexed writeId, bool approved)
        // 
        // Use the /v1/propose-write API for signed write transactions.
        log.info(
            "📝 Smart Contract Listener: NOT IMPLEMENTED\n"
                + "   Future: Listen to OakNetwork.sol events\n"
                + "   Current: Use /v1/propose-write API for signed write transactions\n"
                + "   Write Pattern: Wallet-based storage at /oak-chain/content/<address>/"
        );
        
        running = true;
        
        log.info(
            "===========================================\n"
                + "  Blockchain AEM - Global Store Server\n"
                + "===========================================\n"
                + "\n"
                + "Port:           {} (HTTP)\n"
                + "Store:          {}\n"
                + "Mount Path:     /oak-chain\n"
                + "Access:         READ-WRITE (for consensus)\n"
                + "Protocol:       HTTP segment transfer (Cold Standby pattern)\n"
                + "\n"
                + "Server started successfully!\n"
                + "Waiting for client connections...",
            port,
            storeDirectory
        );
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
        log.info("Shutting down global store server...");
        running = false;
        
        // Stop bootstrap (StandbyClientSync + StandbyServerSync)
        if (bootstrap != null) {
            try {
                bootstrap.shutdown();
                log.info("✅ Bootstrap services stopped");
            } catch (Exception e) {
                log.warn("Error stopping bootstrap: {}", e.getMessage());
            }
        }
        
        // Stop Aeron Cluster launcher
        if (aeronClusterService != null) {
            try {
                aeronClusterService.shutdown();
                log.info("✅ Aeron Cluster stopped");
            } catch (Exception e) {
                log.warn("Error stopping Aeron Cluster: {}", e.getMessage());
            }
        } else if (aeronClusterLauncher != null) {
            try {
                aeronClusterLauncher.shutdown();
                log.info("✅ Aeron Cluster stopped");
            } catch (Exception e) {
                log.warn("Error stopping Aeron Cluster: {}", e.getMessage());
            }
        }
        
        // Stop Ethereum epoch listener
        if (epochListener != null) {
            try {
                epochListener.stop();
                log.info("✅ Epoch listener stopped");
            } catch (Exception e) {
                log.warn("Error stopping epoch listener: {}", e.getMessage());
            }
        }
        
        // Stop HTTP server
        if (httpServer != null) {
            try {
                httpServer.stop();
                log.info("✅ HTTP server stopped");
            } catch (Exception e) {
                log.warn("Error stopping HTTP server: {}", e.getMessage());
            }
        }
        
        // Close FileStore
        if (fileStore != null) {
            try {
                fileStore.close();
                log.info("✅ FileStore closed");
            } catch (Exception e) {
                log.warn("Error closing FileStore: {}", e.getMessage());
            }
        }
    }

    private org.apache.jackrabbit.oak.segment.consensus.aeron.AeronClusterConfig currentAeronConfig() {
        return aeronClusterService != null ? aeronClusterService.getConfig() : null;
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

        try {
            new ValidatorLoggingBootstrap().initialize(port, storeDir);
        } catch (IOException e) {
            System.err.println("Failed to initialize validator logging: " + e.getMessage());
            e.printStackTrace();
        }
        
        final GlobalStoreServer server = new GlobalStoreServer(port, storeDir);
        
        // Shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop();
        }));
        
        try {
            server.start();
        } catch (IOException e) {
            LoggerFactory.getLogger(GlobalStoreServer.class).error("Failed to start server", e);
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
