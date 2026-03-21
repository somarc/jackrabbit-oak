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

import org.apache.jackrabbit.oak.segment.SegmentNodeStoreBuilders;
import org.apache.jackrabbit.oak.segment.consensus.bootstrap.ValidatorBootstrap;
import org.apache.jackrabbit.oak.segment.consensus.bootstrap.ValidatorBootstrap.BootstrapMode;
import org.apache.jackrabbit.oak.segment.consensus.config.RuntimeConfigValueResolver;
import org.apache.jackrabbit.oak.segment.consensus.eth.EpochListener;
import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.apache.jackrabbit.oak.segment.file.FileStoreBuilder;
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

    private void ensureAeronClusterService() {
        if (aeronClusterService == null) {
            System.out.println("⚠️  AeronClusterService not configured (OSGi) - using standalone instance");
            aeronClusterService = components().createAeronClusterService();
        }
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
            
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            // IPFS BLOBSTORE: Decentralized Binary Storage (ADR 015)
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            // IPFS DataStore is REQUIRED - no FileDataStore fallback
            String blobStoreType = RuntimeConfigValueResolver.readString("blobstore.type", "BLOBSTORE_TYPE", "");
            
            // FAIL-FAST: blobstore.type MUST be set to "ipfs"
            if (blobStoreType.isEmpty()) {
                System.err.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                System.err.println("❌ FATAL: blobstore.type is not configured");
                System.err.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                System.err.println("");
                System.err.println("Blockchain AEM requires IPFS DataStore for binary storage.");
                System.err.println("FileDataStore is NOT supported (no fallback).");
                System.err.println("");
                System.err.println("FIX:");
                System.err.println("  1. Ensure IPFS daemon is running:");
                System.err.println("     $ ipfs daemon");
                System.err.println("");
                System.err.println("  2. Set blobstore.type=ipfs:");
                System.err.println("     $ export BLOBSTORE_TYPE=ipfs");
                System.err.println("     OR");
                System.err.println("     $ java -Dblobstore.type=ipfs -jar oak-segment-consensus.jar");
                System.err.println("");
                System.err.println("  3. (Optional) Configure IPFS API endpoint:");
                System.err.println("     $ export IPFS_API_ENDPOINT=/ip4/127.0.0.1/tcp/5001");
                System.err.println("");
                System.err.println("See: oak-segment-consensus/IPFS-DATASTORE.md");
                System.err.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                throw startupFailure("blobstore.type is not configured");
            }
            
            if (!"ipfs".equalsIgnoreCase(blobStoreType)) {
                System.err.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                System.err.println("❌ FATAL: Invalid blobstore.type = \"" + blobStoreType + "\"");
                System.err.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                System.err.println("");
                System.err.println("Only \"ipfs\" is supported.");
                System.err.println("FileDataStore is NOT supported (blockchain-native storage required).");
                System.err.println("");
                System.err.println("FIX: Set blobstore.type=ipfs");
                System.err.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                throw startupFailure("Invalid blobstore.type: " + blobStoreType);
            }
            
            // Initialize IPFS DataStore (REQUIRED)
            System.out.println("📦 Configuring IPFS BlobStore for binaries...");
            org.apache.jackrabbit.oak.spi.blob.BlobStore blobStore;
            String activeBlobStoreType = "ipfs";
            
            try {
                // Get IPFS API endpoint from environment (default: localhost:5001)
                String ipfsEndpoint = RuntimeConfigValueResolver.readString(
                    "ipfs.api.endpoint",
                    "IPFS_API_ENDPOINT",
                    "/ip4/127.0.0.1/tcp/5001"
                );
                
                // Create IPFS DataStore BlobStore
                blobStore = components().createIpfsBlobStore(ipfsEndpoint, storeDir);
                this.blobStore = blobStore; // Store reference for genesis image upload
                
                System.out.println("✅ IPFS BlobStore initialized");
                System.out.println("   - IPFS API: " + ipfsEndpoint);
                System.out.println("   - Min size: 16 KB (smaller binaries inline in segments)");
                System.out.println("   - Storage: Decentralized (P2P replication)");
                System.out.println("   - Strategy: Oak segments (AEM compatible) + IPFS binaries (blockchain-native)");
                
            } catch (Exception e) {
                System.err.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                System.err.println("❌ FATAL: Failed to initialize IPFS BlobStore");
                System.err.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                System.err.println("");
                System.err.println("Error: " + e.getMessage());
                System.err.println("");
                System.err.println("Common causes:");
                System.err.println("  1. IPFS daemon not running");
                System.err.println("     FIX: $ ipfs daemon");
                System.err.println("");
                System.err.println("  2. Wrong IPFS API endpoint");
                System.err.println("     FIX: $ export IPFS_API_ENDPOINT=/ip4/127.0.0.1/tcp/5001");
                System.err.println("");
                System.err.println("  3. IPFS not initialized");
                System.err.println("     FIX: $ ipfs init --profile server");
                System.err.println("");
                System.err.println("Verify IPFS:");
                System.err.println("  $ ipfs version");
                System.err.println("  $ ipfs id");
                System.err.println("");
                System.err.println("See: oak-segment-consensus/IPFS-DATASTORE.md");
                System.err.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                e.printStackTrace();
                throw startupFailure("IPFS BlobStore initialization failed", e);
            }
            
            // Build FileStore with IPFS BlobStore (REQUIRED)
            FileStoreBuilder fsBuilder = FileStoreBuilder.fileStoreBuilder(storeDir)
                .withMaxFileSize(256)  // 256 MB per TAR file
                .withMemoryMapping(false)  // Disable for Docker
                .withBlobStore(blobStore);  // IPFS BlobStore (always present)
            
            fileStore = fsBuilder.build();
            
            // Build SegmentNodeStore
            nodeStore = SegmentNodeStoreBuilders.builder(fileStore).build();
            
            System.out.println("✅ Oak FileStore initialized");
            System.out.println("   - Store version: " + fileStore.getHead().getRecordId());
            System.out.println("   - Segments: " + storeDir.getAbsolutePath());
            
            // If bootstrap is needed, mark for immediate sync (before any other initialization)
            if (needsBootstrapBeforeBuild) {
                System.out.println("   ⚠️  Initial HEAD created (will be replaced by bootstrap sync)");
            }
            
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
                String usdcRateStr = RuntimeConfigValueResolver.readString("gc.usdc.per.mb", "0.10");
                java.math.BigDecimal usdcPerMB = new java.math.BigDecimal(usdcRateStr);
                org.apache.jackrabbit.oak.segment.consensus.gc.GCCostEstimator gcCostEstimator =
                    components().createGCCostEstimator(fileStore, tarFiles, usdcPerMB);
                
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
            httpServer = components().createHttpServer(storeDir, port, fileStore, nodeStore);
            // Get self URL from system property, or resolve localhost to IP
            String selfUrl = GlobalStoreRuntimeConfigUtil.resolveSelfUrl(port, currentAeronConfig());
            if (GlobalStoreRuntimeConfigUtil.isConfiguredSelfUrl(currentAeronConfig())) {
                System.out.println("   Using configured self URL: " + selfUrl);
            } else {
                System.out.println("   Resolved self URL to IP: " + selfUrl);
            }
            httpServer.setSelfUrl(selfUrl);
            
            // Set GC Cost Estimator in ServerContext (if initialized)
            if (gcCostEstimator != null) {
                httpServer.getContext().setGCCostEstimator(gcCostEstimator);
            }
            
            // Set BlobStore type and reference for binary uploads
            httpServer.getContext().blobStoreType = activeBlobStoreType;
            httpServer.getContext().blobStore = blobStore; // For eager binary uploads
            
            // ===========================================================================
            // Initialize CID Mapping Service (Oak blob ID ↔ IPFS CID coordination)
            if ("ipfs".equalsIgnoreCase(activeBlobStoreType)) {
                System.out.println("Initializing CID Mapping Service...");
                try {
                    org.apache.jackrabbit.oak.segment.http.server.binary.CidMappingService cidMappingService =
                        components().createCidMappingService(storeDir.toPath());
                    httpServer.getContext().cidMappingService = cidMappingService;
                    System.out.println("✅ CID Mapping Service initialized");
                    System.out.println("   - Maps Oak blob IDs ↔ IPFS CIDs");
                    System.out.println("   - Persistence: " + storeDir.getAbsolutePath() + "/cid-mappings.properties");
                    System.out.println("   - API: /api/cid/{oakBlobId} → IPFS CID lookup");
                } catch (Exception e) {
                    System.err.println("⚠️  Failed to initialize CID Mapping Service: " + e.getMessage());
                }
            }
            
            // ===========================================================================
            // Initialize Fragmentation Tracker (for fragmentation metrics and tax)
            System.out.println("Initializing Fragmentation Tracker...");
            org.apache.jackrabbit.oak.segment.consensus.fragmentation.FragmentationTracker fragmentationTracker = null;
            try {
                fragmentationTracker = components().createFragmentationTracker();
                
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
            // Initialize Wallet Storage Metrics (for tokenomics)
            System.out.println("Initializing Wallet Storage Metrics...");
            org.apache.jackrabbit.oak.segment.consensus.fragmentation.WalletStorageMetrics walletStorageMetrics = null;
            try {
                walletStorageMetrics = components().createWalletStorageMetrics(fileStore);
                
                httpServer.getContext().setWalletStorageMetrics(walletStorageMetrics);
                
                System.out.println("✅ Wallet Storage Metrics initialized");
                System.out.println("   - Tracks per-wallet storage ownership %");
                System.out.println("   - Calculates storage tax and delete tax");
                System.out.println("   - Monitors capacity (2 TB upper bound)");
            } catch (Exception e) {
                System.err.println("⚠️  Failed to initialize Wallet Storage Metrics: " + e.getMessage());
                System.err.println("   Storage metrics will not be available");
                // Don't fail startup - storage metrics are optional
            }
            
            // ===========================================================================
            // Initialize GC Proposal Manager (for GC consensus)
            System.out.println("Initializing GC Proposal Manager...");
            try {
                // Determine total validators (from peers + self)
                int totalValidators = 1; // Default: just self
                List<String> configuredPeers = GlobalStoreRuntimeConfigUtil.resolvePeerUrls(currentAeronConfig());
                if (!configuredPeers.isEmpty()) {
                    totalValidators = configuredPeers.size() + 1; // Peers + self
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
                    components().createGCProposalManager(
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
                
                // Initialize GC Account Manager (Account Tax Model)
                org.apache.jackrabbit.oak.segment.consensus.gc.GCAccountManager gcAccountManager =
                    components().createGCAccountManager();
                
                httpServer.getContext().gcAccountManager = gcAccountManager;
                
                System.out.println("✅ GC Account Manager initialized");
                System.out.println("   - Tracks GC debt per entity (wallet address)");
                System.out.println("   - Default debt limit: $100.00");
                System.out.println("   - Enforces write blocking when debt exceeds limit");
                
                // Initialize Periodic GC Job (Account Tax Model)
                org.apache.jackrabbit.oak.segment.consensus.gc.PeriodicGCJob periodicGCJob =
                    components().createPeriodicGCJob(gcAccountManager);
                
                periodicGCJob.start();
                httpServer.getContext().periodicGCJob = periodicGCJob;
                
                System.out.println("✅ Periodic GC Job started");
                System.out.println("   - Interval: " + periodicGCJob.getIntervalSeconds() + "s");
                System.out.println("   - Initial delay: " + periodicGCJob.getInitialDelaySeconds() + "s");
                System.out.println("   - Action: Converts pending debt → executed debt");
                System.out.println("   - Blocks writes when executed debt > limit");
                
            } catch (Exception e) {
                System.err.println("⚠️  Failed to initialize GC Proposal Manager: " + e.getMessage());
                System.err.println("   GC consensus will not be available");
                // Don't fail startup - GC consensus is optional
            }
            
            System.out.println("✅ HTTP server initialized (not yet started)");
            
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
                
                // Start StandbyServerSync so other validators can bootstrap (empty-to-empty is valid)
                if (bootstrap != null) {
                    try {
                        bootstrap.startStandbyServer();
                        System.out.println("✅ StandbyServerSync started on port " + standbyPort);
                        System.out.println("   Other validators can bootstrap from empty store (will sync genesis after creation)");
                    } catch (Exception e) {
                        System.err.println("⚠️  Failed to start StandbyServerSync: " + e.getMessage());
                        System.err.println("   Other validators will not be able to bootstrap from this node");
                        // Don't fail startup - genesis node can still operate
                    }
                }
                
            } else {
                // PRIMARY MODE: Check if genesis already exists before initializing
                // Empty stores (no genesis) will have genesis created by elected leader via consensus
                try {
                    org.apache.jackrabbit.oak.spi.state.NodeState root = nodeStore.getRoot();
                    boolean genesisExists = root.getChildNode("oak-chain")
                        .getChildNode("content")
                        .getChildNode("00")
                        .getChildNode("00")
                        .getChildNode("00")
                        .getChildNode("0x0000000000000000000000000000000000000000")
                        .getChildNode("genesis")
                        .exists();
                    
                    if (genesisExists) {
                        // Genesis already exists - verify it
                        System.out.println("   ℹ️  Genesis exists - verifying integrity...");
                        components().createGenesisInitializer(nodeStore, fileStore, blobStore, selfUrl).initializeGenesisContent();
                    } else {
                        // No genesis - skip initialization (will be created by elected leader via consensus)
                        System.out.println("   ⏭️  Genesis does not exist - will be created by elected leader via consensus");
                        System.out.println("   ⏭️  Skipping genesis initialization at startup");
                    }
                } catch (Exception e) {
                    // Fallback: check store size as backup
                    try {
                        long storeSize = fileStore.size();
                        // Use a more meaningful threshold (empty stores have ~256KB of metadata)
                        if (storeSize > 1024 * 1024) { // > 1 MB means likely has content
                            System.out.println("   ℹ️  Store has data (" + (storeSize / (1024 * 1024)) + " MB) - verifying genesis...");
                            components().createGenesisInitializer(nodeStore, fileStore, blobStore, selfUrl).initializeGenesisContent();
                        } else {
                            System.out.println("   ⏭️  Store is empty or minimal - skipping genesis (will be created by consensus)");
                        }
                    } catch (Exception e2) {
                        System.out.println("   ⚠️  Could not check store state, skipping genesis init (will be created by consensus)");
                    }
                }
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
        
        // Initialize Consensus Engine (Multi-Validator)
        // CRITICAL: Skip this if we're in STANDBY mode (bootstrap will initialize via callback)
        String consensusEnabled = RuntimeConfigValueResolver.readString("consensus.enabled", "false");
        // Reuse consensusMode and isAeronMode variables declared earlier (before FileStore build)
        // Get self URL from system property, or resolve localhost to IP
        org.apache.jackrabbit.oak.segment.consensus.aeron.AeronClusterConfig aeronConfig =
            aeronClusterService != null ? aeronClusterService.getConfig() : null;
        String selfUrl = GlobalStoreRuntimeConfigUtil.resolveSelfUrl(port, currentAeronConfig());
        String peersConfig = RuntimeConfigValueResolver.readString("consensus.peers", "");
        List<String> peerUrlsFromConfig = new ArrayList<>();
        if (aeronConfig != null && aeronConfig.peerUrls() != null && aeronConfig.peerUrls().length > 0) {
            for (String peerUrl : aeronConfig.peerUrls()) {
                if (peerUrl != null && !peerUrl.trim().isEmpty()) {
                    peerUrlsFromConfig.add(peerUrl.trim());
                }
            }
        }
        
        // ✈️ AERON-ONLY: Allow consensus even with no peers (single validator can start cluster as genesis node)
        boolean enableConsensus = "true".equalsIgnoreCase(consensusEnabled) &&
                                 (isAeronMode || !peersConfig.isEmpty());
        if (aeronConfig != null && !aeronConfig.enabled()) {
            enableConsensus = false;
        }
        
        // CRITICAL: Don't initialize consensus here if we're in STANDBY mode
        // The standby promotion coordinator callback will initialize it after bootstrap promotion
        boolean isStandbyMode = (detectedMode == BootstrapMode.STANDBY);
        
        // Store selfUrl and peerUrls for bootstrap callback (if Aeron mode with bootstrap)
        // Note: For STANDBY mode, these are already stored in the STANDBY block above
        // This block handles other modes (PRIMARY/GENESIS) that also need Aeron config stored
        if (isAeronMode && !isStandbyMode) {
            List<String> peerUrlsForStorage = peerUrlsFromConfig.isEmpty()
                ? ServerNetworkUtil.parsePeerUrls(peersConfig)
                : new ArrayList<>(peerUrlsFromConfig);
            this.aeronSelfUrl = selfUrl;
            this.aeronPeerUrls = peerUrlsForStorage;
        }
        
        if (enableConsensus && !isStandbyMode) {
            System.out.println();
            System.out.println("Initializing Consensus Engine...");
            System.out.println("   Mode: AERON (Aeron Cluster Raft)");

            List<String> peerUrls = peerUrlsFromConfig.isEmpty()
                ? ServerNetworkUtil.parsePeerUrls(peersConfig)
                : new ArrayList<>(peerUrlsFromConfig);

            if (isAeronMode) {
                System.out.println("   ✈️  Using Aeron Cluster Consensus (Raft)");
                System.out.println("      - Proven Raft consensus algorithm");
                System.out.println("      - Election safety guarantees");
                System.out.println("      - Majority quorum requirements");
                System.out.println("      - High performance, low latency");

                String beaconApiUrl = GlobalStoreRuntimeConfigUtil.resolveBeaconApiUrl(currentAeronConfig());

                ensureAeronClusterService();
                boolean observeElections = aeronConfig != null && aeronConfig.observeElections();
                boolean logClusterStateDetails = aeronConfig != null && aeronConfig.logClusterStateDetails();
                AeronClusterStartupResult startupResult = aeronClusterService.startCluster(
                    fileStore, nodeStore, httpServer, wallet, storeDirectory, this.blobStore,
                    selfUrl, peerUrls, observeElections, logClusterStateDetails
                );

                this.aeronClusterLauncher = startupResult.getLauncher();

                org.apache.jackrabbit.oak.segment.consensus.aeron.AeronConsensusEngine aeronEngine = startupResult.getAeronEngine();
                List<String> hostnamesList = startupResult.getHostnames();
                int nodeId = startupResult.getNodeId();

                System.out.println("✅ Aeron Cluster Consensus engine initialized");
                System.out.println("   - Model: Raft-based consensus (Aeron Cluster)");
                System.out.println("   - Node ID: " + nodeId);
                System.out.println("   - Total validators: " + hostnamesList.size());
                System.out.println("   - Current role: " + aeronEngine.getCurrentRole());
                System.out.println("   - Current leader: " + aeronEngine.getCurrentLeader());
                System.out.println("   - Ethereum epoch: " + aeronEngine.getCurrentEthereumEpoch());

                components().createConsensusServicesInitializer().initialize(
                    aeronEngine,
                    httpServer,
                    wallet,
                    storeDirectory,
                    beaconApiUrl,
                    finalClusterWallet,
                    hostnamesList
                );
            } else {
                throw new IllegalStateException("Aeron mode validation failed - this should not happen");
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
