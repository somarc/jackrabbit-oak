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
    private org.apache.jackrabbit.oak.spi.blob.BlobStore blobStore;
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
    @SuppressWarnings("unused") // Used to track bootstrap state, read by bootstrap callback
    private volatile boolean aeronClusterDeferred = false;
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
        // ETHEREUM WALLETS (ADR 046)
        // - Node wallet: This validator's identity (signing, consensus)
        // - Cluster wallet: Payment destination (read-only awareness)
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        
        // 1. Load/create NODE wallet (this validator's identity for signing)
        String nodeKeystorePath = System.getProperty("wallet.keystore.path", 
            storeDirectory + "/validator-keystore.properties");
        try {
            this.wallet = new org.apache.jackrabbit.oak.segment.consensus.security.EthereumWallet(nodeKeystorePath);
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
        String consensusModeProp = System.getProperty("consensus.mode", "aeron");
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
        
        // Check if bootstrap is needed (empty directory + peers exist)
        // CRITICAL: Only mark for bootstrap if we've VERIFIED peers are reachable
        // This prevents getting stuck if peers are configured but not actually available
        boolean needsBootstrapBeforeBuild = false;
        boolean hasVerifiedReachablePeers = false;
        String verifiedBootstrapPrimaryHost = "";
        int verifiedBootstrapPrimaryPort = 0;
        
        if (isAeronMode && directoryIsEmpty) {
            String peersConfig = System.getProperty("consensus.peers", "");
            List<String> aeronPeers = parsePeerUrls(peersConfig);
            String bootstrapPrimaryHost = System.getProperty("bootstrap.primary.host", "");
            String bootstrapPrimaryPortStr = System.getProperty("bootstrap.primary.port", "");
            
            // First, try to verify peers from consensus.peers
            if (!aeronPeers.isEmpty()) {
                for (String peerUrl : aeronPeers) {
                    try {
                        java.net.URL url = new java.net.URL(peerUrl + "/health");
                        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                        conn.setRequestMethod("GET");
                        conn.setConnectTimeout(2000);
                        conn.setReadTimeout(2000);
                        if (conn.getResponseCode() == 200) {
                            hasVerifiedReachablePeers = true;
                            // Extract host from peer URL for bootstrap
                            verifiedBootstrapPrimaryHost = peerUrl.replace("http://", "").replace("https://", "").split(":")[0];
                            // Standby port = HTTP port + 1
                            try {
                                int httpPort = Integer.parseInt(peerUrl.split(":")[2]);
                                verifiedBootstrapPrimaryPort = httpPort + 1;
                            } catch (Exception e) {
                                verifiedBootstrapPrimaryPort = port + 1; // Fallback
                            }
                            System.out.println("✅ Verified reachable peer: " + peerUrl);
                            break;
                        }
                    } catch (Exception e) {
                        // Try next peer
                    }
                }
            }
            
            // If no peers from consensus.peers, try bootstrap.primary.host
            if (!hasVerifiedReachablePeers && !bootstrapPrimaryHost.isEmpty()) {
                try {
                    // Try to reach bootstrap primary (use HTTP port, not standby port)
                    int httpPort = 8090; // Default
                    if (!bootstrapPrimaryPortStr.isEmpty()) {
                        try {
                            int parsedStandbyPort = Integer.parseInt(bootstrapPrimaryPortStr);
                            httpPort = parsedStandbyPort - 1; // Standby port - 1 = HTTP port
                        } catch (NumberFormatException e) {
                            // Use default
                        }
                    }
                    String primaryUrl = "http://" + bootstrapPrimaryHost + ":" + httpPort;
                    java.net.URL url = new java.net.URL(primaryUrl + "/health");
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(2000);
                    conn.setReadTimeout(2000);
                    if (conn.getResponseCode() == 200) {
                        hasVerifiedReachablePeers = true;
                        verifiedBootstrapPrimaryHost = bootstrapPrimaryHost;
                        verifiedBootstrapPrimaryPort = !bootstrapPrimaryPortStr.isEmpty() ? 
                            Integer.parseInt(bootstrapPrimaryPortStr) : (httpPort + 1);
                        System.out.println("✅ Verified bootstrap primary: " + bootstrapPrimaryHost + ":" + verifiedBootstrapPrimaryPort);
                    }
                } catch (Exception e) {
                    System.out.println("⚠️  Bootstrap primary configured but not reachable: " + bootstrapPrimaryHost);
                    System.out.println("   Will fall back to GENESIS mode if store is empty");
                }
            }
            
            // Only mark for bootstrap if we've VERIFIED a peer is reachable
            needsBootstrapBeforeBuild = hasVerifiedReachablePeers;
            
            if (needsBootstrapBeforeBuild) {
                System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                System.out.println("⚠️  CRITICAL: Empty store directory detected");
                System.out.println("   Bootstrap needed - verified peer is reachable");
                System.out.println("   This ensures all validators start with same genesis HEAD");
                System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                System.out.println("   Bootstrap primary: " + verifiedBootstrapPrimaryHost + ":" + verifiedBootstrapPrimaryPort);
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
            String blobStoreType = System.getProperty("blobstore.type", 
                System.getenv().getOrDefault("BLOBSTORE_TYPE", ""));
            
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
                System.exit(1);
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
                System.exit(1);
            }
            
            // Initialize IPFS DataStore (REQUIRED)
            System.out.println("📦 Configuring IPFS BlobStore for binaries...");
            org.apache.jackrabbit.oak.spi.blob.BlobStore blobStore;
            String activeBlobStoreType = "ipfs";
            
            try {
                // Get IPFS API endpoint from environment (default: localhost:5001)
                String ipfsEndpoint = System.getProperty("ipfs.api.endpoint",
                    System.getenv().getOrDefault("IPFS_API_ENDPOINT", "/ip4/127.0.0.1/tcp/5001"));
                
                // Create IPFS DataStore
                org.apache.jackrabbit.oak.blob.cloud.ipfs.IPFSDataStore ipfsDataStore = 
                    new org.apache.jackrabbit.oak.blob.cloud.ipfs.IPFSDataStore();
                ipfsDataStore.setIpfsApiEndpoint(ipfsEndpoint);
                ipfsDataStore.setMinRecordLength(16 * 1024); // 16KB threshold
                ipfsDataStore.init(storeDir.getAbsolutePath()); // HomeDir for local cache
                
                // Wrap DataStore in DataStoreBlobStore (Oak pattern for DataStore -> BlobStore conversion)
                blobStore = new org.apache.jackrabbit.oak.plugins.blob.datastore.DataStoreBlobStore(ipfsDataStore);
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
                System.exit(1);
                throw new RuntimeException("IPFS BlobStore initialization failed", e); // Never reached
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
                // Use configured URL (can be ngrok/cloud URL, IP, or hostname)
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
            
            // Set BlobStore type and reference for binary uploads
            httpServer.getContext().blobStoreType = activeBlobStoreType;
            httpServer.getContext().blobStore = blobStore; // For eager binary uploads
            
            // ===========================================================================
            // Initialize CID Mapping Service (Oak blob ID ↔ IPFS CID coordination)
            if ("ipfs".equalsIgnoreCase(activeBlobStoreType)) {
                System.out.println("Initializing CID Mapping Service...");
                try {
                    org.apache.jackrabbit.oak.segment.http.server.binary.CidMappingService cidMappingService = 
                        new org.apache.jackrabbit.oak.segment.http.server.binary.CidMappingService(storeDir.toPath());
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
            // Initialize Wallet Storage Metrics (for tokenomics)
            System.out.println("Initializing Wallet Storage Metrics...");
            org.apache.jackrabbit.oak.segment.consensus.fragmentation.WalletStorageMetrics walletStorageMetrics = null;
            try {
                walletStorageMetrics = new org.apache.jackrabbit.oak.segment.consensus.fragmentation.WalletStorageMetrics(fileStore);
                
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
                
                // Initialize GC Account Manager (Account Tax Model)
                org.apache.jackrabbit.oak.segment.consensus.gc.GCAccountManager gcAccountManager = 
                    new org.apache.jackrabbit.oak.segment.consensus.gc.GCAccountManager();
                
                httpServer.getContext().gcAccountManager = gcAccountManager;
                
                System.out.println("✅ GC Account Manager initialized");
                System.out.println("   - Tracks GC debt per entity (wallet address)");
                System.out.println("   - Default debt limit: $100.00");
                System.out.println("   - Enforces write blocking when debt exceeds limit");
                
                // Initialize Periodic GC Job (Account Tax Model)
                org.apache.jackrabbit.oak.segment.consensus.gc.PeriodicGCJob periodicGCJob = 
                    new org.apache.jackrabbit.oak.segment.consensus.gc.PeriodicGCJob(gcAccountManager);
                
                periodicGCJob.start();
                httpServer.getContext().periodicGCJob = periodicGCJob;
                
                System.out.println("✅ Periodic GC Job started");
                System.out.println("   - Interval: 5 minutes");
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
                String peersConfig = System.getProperty("consensus.peers", "");
                List<String> aeronPeers = parsePeerUrls(peersConfig);
                
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
                    bootstrap = new ValidatorBootstrap(fileStore, standbyPort);
                    
                    // Use verified bootstrap primary (from before FileStore build)
                    String primaryHost = verifiedBootstrapPrimaryHost;
                    int primaryPort = verifiedBootstrapPrimaryPort;
                    
                    // Fallback: If verified info not available, try system properties
                    if (primaryHost.isEmpty()) {
                        primaryHost = System.getProperty("bootstrap.primary.host", "");
                        String bootstrapPrimaryPortStr = System.getProperty("bootstrap.primary.port", String.valueOf(port + 1));
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
                        bootstrap = new ValidatorBootstrap(fileStore, standbyPort);
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
                    bootstrap = new ValidatorBootstrap(fileStore, standbyPort);
                } else {
                    // ✈️ AERON MODE: Store has data → Start Aeron directly
                    // Aeron will handle Raft log bootstrap/replay
                    System.out.println("✈️  AERON MODE: Existing store found");
                    System.out.println("   Starting Aeron Cluster (will replay Raft log if needed)");
                    detectedMode = BootstrapMode.PRIMARY;
                    // Initialize bootstrap for StandbyServerSync (so late-joining validators can sync)
                    bootstrap = new ValidatorBootstrap(fileStore, standbyPort);
                }
            }
            
            // ✈️ AERON-ONLY: Handle STANDBY bootstrap (sync FileStore, then start Aeron Cluster)
            if (detectedMode == BootstrapMode.STANDBY) {
                // STANDBY MODE: Bootstrap Oak FileStore from existing validator
                // After bootstrap completes, start Aeron Cluster
                
                // CRITICAL: Store Aeron configuration BEFORE bootstrap starts (needed for callback)
                // Get self URL from system property, or resolve localhost to IP
                String selfUrlConfigStandy = System.getProperty("consensus.self.url");
                String selfUrlStandy;
                if (selfUrlConfigStandy != null && !selfUrlConfigStandy.isEmpty()) {
                    selfUrlStandy = selfUrlConfigStandy; // Use configured URL (can be ngrok/Ethos URL, IP, or hostname)
                } else {
                    selfUrlStandy = resolveUrlToIP("http://localhost:" + port); // Default: resolve to IP
                }
                String peersConfigStandy = System.getProperty("consensus.peers", "");
                List<String> peerUrlsStandy = parsePeerUrls(peersConfigStandy);
                
                // Store for bootstrap callback
                this.aeronSelfUrl = selfUrlStandy;
                this.aeronPeerUrls = peerUrlsStandy;
                
                // Determine which peer to bootstrap from
                String primaryHostInitial = bootstrapPrimaryHost;
                int primaryPortInitial = bootstrapPrimaryPort;
                
                // Use Aeron peer URLs (already verified earlier)
                List<String> bootstrapPeers = this.aeronPeerUrls != null ? this.aeronPeerUrls : new java.util.ArrayList<>();
                
                String primaryHost = primaryHostInitial;
                int primaryPort = primaryPortInitial;
                
                if (primaryHost.isEmpty() && !bootstrapPeers.isEmpty()) {
                    // Use first peer as primary
                    String firstPeer = bootstrapPeers.get(0);
                    // Parse URL (e.g., "http://validator-1:8090")
                    primaryHost = firstPeer.replace("http://", "").replace("https://", "").split(":")[0];
                    try {
                        int httpPort = Integer.parseInt(firstPeer.split(":")[2]);
                        primaryPort = httpPort + 1; // Standby port = HTTP port + 1
                    } catch (Exception e) {
                        primaryPort = port + 1; // Fallback
                    }
                    System.out.println("🔍 Using first peer as bootstrap primary: " + primaryHost + ":" + primaryPort);
                }
                
                if (primaryHost.isEmpty()) {
                    throw new IOException("STANDBY mode requires bootstrap.primary.host or consensus.peers");
                }
                
                // Make final for lambda
                final String finalPrimaryHost = primaryHost;
                final int finalPrimaryPort = primaryPort;
                
                // Bootstrap from primary (this will block until initial sync, then schedule periodic sync)
                bootstrap.bootstrapFromPrimary(finalPrimaryHost, finalPrimaryPort, () -> {
                    System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                    System.out.println("🎖️  PROMOTED TO PRIMARY - Oak FileStore bootstrap complete");
                    System.out.println("   Local HEAD: " + fileStore.getHead().getRecordId());
                    System.out.println("   Starting Aeron Cluster...");
                    System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                    try {
                        // ✈️ AERON-ONLY: Start Aeron Cluster after Oak FileStore bootstrap
                        System.out.println("✈️  Starting Aeron Cluster (Oak FileStore already synced)");
                        startAeronClusterAfterBootstrap();
                        
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
                        initializeGenesisContent();
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
                            initializeGenesisContent();
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
        String consensusEnabled = System.getProperty("consensus.enabled", "false");
        // Reuse consensusMode and isAeronMode variables declared earlier (before FileStore build)
        // Get self URL from system property, or resolve localhost to IP
        String selfUrlConfig = System.getProperty("consensus.self.url");
        String selfUrl;
        if (selfUrlConfig != null && !selfUrlConfig.isEmpty()) {
            selfUrl = selfUrlConfig; // Use configured URL (can be ngrok/Ethos URL, IP, or hostname)
        } else {
            selfUrl = resolveUrlToIP("http://localhost:" + port); // Default: resolve to IP
        }
        String peersConfig = System.getProperty("consensus.peers", "");
        
        // ✈️ AERON-ONLY: Allow consensus even with no peers (single validator can start cluster as genesis node)
        boolean enableConsensus = "true".equalsIgnoreCase(consensusEnabled) && 
                                 (isAeronMode || !peersConfig.isEmpty());
        
        // CRITICAL: Don't initialize consensus here if we're in STANDBY mode
        // The bootstrap promotion callback (startAeronClusterAfterBootstrap) will initialize it
        boolean isStandbyMode = (detectedMode == BootstrapMode.STANDBY);
        
        // Store selfUrl and peerUrls for bootstrap callback (if Aeron mode with bootstrap)
        // Note: For STANDBY mode, these are already stored in the STANDBY block above
        // This block handles other modes (PRIMARY/GENESIS) that also need Aeron config stored
        if (isAeronMode && !isStandbyMode) {
            List<String> peerUrlsForStorage = parsePeerUrls(peersConfig);
            this.aeronSelfUrl = selfUrl;
            this.aeronPeerUrls = peerUrlsForStorage;
        }
        
        if (enableConsensus && !isStandbyMode) {
            System.out.println();
            System.out.println("Initializing Consensus Engine...");
            System.out.println("   Mode: AERON (Aeron Cluster Raft)");
            
            List<String> peerUrls = parsePeerUrls(peersConfig);
            
            // ✈️ AERON-ONLY POC: Initialize Aeron Cluster Consensus Engine
            if (isAeronMode) {
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
                
                // Create Aeron Consensus Engine (pass this.blobStore for genesis image upload)
                org.apache.jackrabbit.oak.segment.consensus.aeron.AeronConsensusEngine aeronEngine = 
                    new org.apache.jackrabbit.oak.segment.consensus.aeron.AeronConsensusEngine(
                        fileStore, nodeStore, selfUrl, peerUrls, wallet, storeDirectory, this.blobStore
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
                // Set write callback BEFORE launching cluster to ensure it's ready for incoming messages
                // IMPORTANT: Callback must be set before ClusteredServiceContainer.launch()
                aeronEngine.setWriteApplicationCallback(new org.apache.jackrabbit.oak.segment.consensus.aeron.AeronConsensusEngine.WriteApplicationCallback() {
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
                System.out.println("   ✅ Write/Delete application callback configured (before cluster launch)");
                
                // Wire Aeron engine to HTTP server context (needed for callback to access ConsensusApiHandler)
                // This must be done before setting callback so callback can access httpServer
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
                
                // ✈️ AERON WRITE CLIENT: Create client for sending writes through cluster ingress
                // Client is separate from ClusteredService but shares same MediaDriver (same process)
                // This allows external components to send writes through the Raft consensus layer
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
                
                // Create AeronWriteClient for external write submissions
                // NOTE: ConsensusApiHandler now uses internal cluster client for writes
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
                // Use SimpleEvmBridge for POC testing (supports auto-simulation of payments)
                // Configuration via OAK_BLOCKCHAIN_MOCK_MODE env var or oak.blockchain.mockMode system property
                org.apache.jackrabbit.oak.segment.consensus.config.BlockchainConfig blockchainConfig = 
                    org.apache.jackrabbit.oak.segment.consensus.config.BlockchainConfig.getInstance();
                
                org.apache.jackrabbit.oak.segment.consensus.evm.EvmBridge evmBridge = 
                    new org.apache.jackrabbit.oak.segment.consensus.evm.impl.SimpleEvmBridge(
                        blockchainConfig.getNetwork(),
                        blockchainConfig.getContractAddress()
                    );
                evmBridge.start();
                
                // Note: aeronEngine null checks are defensive - aeronEngine is captured from outer scope
                // and could theoretically be null if callback is invoked before initialization completes
                @SuppressWarnings("all") // Suppress false positive dead code warnings
                org.apache.jackrabbit.oak.segment.consensus.queue.RaftAppendCallback raftCallback = 
                    new org.apache.jackrabbit.oak.segment.consensus.queue.RaftAppendCallback() {
                        @Override
                        public void appendProposal(String walletAddress, String path, String contentType, String message, String signature) {
                            // Append single write proposal to Raft via AeronConsensusEngine (no binary)
                            if (aeronEngine == null) {
                                System.err.println("❌ aeronEngine is NULL in appendProposal!");
                                return;
                            }
                            System.out.println("📤 appendProposal() called - forwarding to Aeron (role: " + aeronEngine.getCurrentRole() + ")");
                            boolean success = aeronEngine.sendWriteThroughIngress(walletAddress, path, contentType, message, signature);
                            if (!success) {
                                System.err.println("❌ sendWriteThroughIngress() returned false!");
                            }
                        }

                        @Override
                        public void appendProposalWithId(String proposalId, String walletAddress, String path, String contentType,
                                                         String message, String signature) {
                            if (aeronEngine == null) {
                                System.err.println("❌ aeronEngine is NULL in appendProposalWithId!");
                                return;
                            }
                            boolean success = aeronEngine.sendWriteThroughIngressWithId(
                                walletAddress, path, contentType, message, signature, null, proposalId);
                            if (!success) {
                                System.err.println("❌ sendWriteThroughIngress() returned false!");
                            }
                        }
                        
                        @Override
                        public void appendProposal(String walletAddress, String path, String contentType, String message, 
                                                  String signature, String blobId, String mimeType) {
                            // Append single write proposal WITH BINARY to Raft via AeronConsensusEngine
                            if (aeronEngine == null) {
                                System.err.println("❌ aeronEngine is NULL in appendProposal!");
                                return;
                            }
                            System.out.println("📤 appendProposal() with binary - blobId=" + blobId + " (role: " + aeronEngine.getCurrentRole() + ")");
                            boolean success = aeronEngine.sendWriteThroughIngress(walletAddress, path, contentType, message, signature, blobId, mimeType);
                            if (!success) {
                                System.err.println("❌ sendWriteThroughIngress() with binary returned false!");
                            }
                        }

                        @Override
                        public void appendProposalWithId(String proposalId, String walletAddress, String path, String contentType,
                                                         String message, String signature, String blobId, String mimeType, String ipfsCid) {
                            if (aeronEngine == null) {
                                System.err.println("❌ aeronEngine is NULL in appendProposalWithId!");
                                return;
                            }
                            boolean success = aeronEngine.sendWriteThroughIngress(
                                walletAddress, path, contentType, message, signature, blobId, mimeType, ipfsCid, proposalId);
                            if (!success) {
                                System.err.println("❌ sendWriteThroughIngress() with binary returned false!");
                            }
                        }
                        
                        @Override
                        public void appendDeleteProposal(String walletAddress, String path, String signature) {
                            // Append delete proposal to Raft via AeronConsensusEngine
                            if (aeronEngine == null) {
                                System.err.println("❌ aeronEngine is NULL in appendDeleteProposal!");
                                return;
                            }
                            System.out.println("🗑️  appendDeleteProposal() called - forwarding to Aeron (role: " + aeronEngine.getCurrentRole() + ")");
                            boolean success = aeronEngine.sendDeleteThroughIngress(walletAddress, path, signature);
                            if (!success) {
                                System.err.println("❌ sendDeleteThroughIngress() returned false!");
                            }
                        }

                        @Override
                        public void appendDeleteProposalWithId(String proposalId, String walletAddress, String path, String signature) {
                            if (aeronEngine == null) {
                                System.err.println("❌ aeronEngine is NULL in appendDeleteProposalWithId!");
                                return;
                            }
                            boolean success = aeronEngine.sendDeleteThroughIngress(walletAddress, path, signature, proposalId);
                            if (!success) {
                                System.err.println("❌ sendDeleteThroughIngress() returned false!");
                            }
                        }
                        
                        @Override
                        public int appendProposalBatch(java.util.List<org.apache.jackrabbit.oak.segment.consensus.queue.QueuedProposal> proposals) {
                            // THE FIX VERIFICATION: Prove this override is actually being called
                            System.out.println("🔥🔥🔥 OVERRIDE CALLED: appendProposalBatch() - batch size: " + proposals.size() + 
                                ", class: " + this.getClass().getName());
                            
                            // Append batch of proposals to Raft via AeronConsensusEngine
                            if (aeronEngine == null) {
                                System.err.println("❌ aeronEngine is NULL in appendProposalBatch!");
                                return 0;
                            }
                            System.out.println("📤 appendProposalBatch() forwarding to aeronEngine.sendWriteBatchThroughIngress() - role: " + aeronEngine.getCurrentRole());
                            int sent = aeronEngine.sendWriteBatchThroughIngress(proposals);
                            System.out.println("📤 appendProposalBatch() result: " + sent + " proposals sent");
                            if (sent == 0) {
                                System.err.println("❌ sendWriteBatchThroughIngress() returned 0 (failed)!");
                            }
                            return sent;
                        }
                    };
                
                // Get backpressure manager from Aeron consensus engine for write flow control
                org.apache.jackrabbit.oak.segment.consensus.queue.BackpressureManager backpressureManager =
                    aeronEngine != null ? aeronEngine.getBackpressureManager() : null;
                
                if (backpressureManager == null) {
                    System.out.println("   ⚠️  WARNING: BackpressureManager not available - using fallback");
                    backpressureManager = new org.apache.jackrabbit.oak.segment.consensus.queue.BackpressureManager();
                }
                
                // Initialize Beacon Chain client for real-time Ethereum epoch tracking (reuse beaconApiUrl from earlier)
                org.apache.jackrabbit.oak.segment.consensus.eth.BeaconChainClient beaconClient = 
                    new org.apache.jackrabbit.oak.segment.consensus.eth.BeaconChainClient(beaconApiUrl);
                beaconClient.startBackgroundPolling(); // CRITICAL: Start polling thread to update cached epochs
                System.out.println("   ✅ Beacon Chain client initialized (tracking Ethereum epochs from " + beaconApiUrl + ")");
                
                // Use optimized epoch-based batching queue manager
                org.apache.jackrabbit.oak.segment.consensus.queue.ProposalQueueManagerOptimized proposalQueueManager = 
                    new org.apache.jackrabbit.oak.segment.consensus.queue.ProposalQueueManagerOptimized(
                        evmBridge,
                        raftCallback,
                        backpressureManager,
                        beaconClient,
                        new java.io.File(storeDirectory, "proposal-queue").getAbsolutePath()
                    );
                proposalQueueManager.start();
                httpServer.getContext().setProposalQueueManager(proposalQueueManager);
                httpServer.getContext().evmBridge = evmBridge; // Store for GC Proposal Manager
                proposalQueueManager.start(); // Start tri-agent architecture (EVM verifier, Aeron sender, Epoch finalizer)
                System.out.println("   ✅ Proposal Queue Manager initialized (Ethereum epoch-based batching + 3-checkpoint security)");
                
                // Initialize Validator Earnings Tracker (economic simulation)
                // Build validator wallet list from hostnamesList (each node's wallet will be collected dynamically)
                // For POC: Use self wallet + peer count to simulate fair distribution
                java.util.List<String> validatorWallets = new java.util.ArrayList<>();
                
                // Add self wallet
                validatorWallets.add(wallet.getWalletAddress());
                
                // For POC: Generate placeholder wallets for expected validators based on cluster size
                // In production, these would be collected from actual validator registrations
                int expectedValidators = hostnamesList != null ? hostnamesList.size() : 1;
                for (int i = 1; i < expectedValidators; i++) {
                    // Placeholder wallet (will be replaced by real wallets as validators register)
                    validatorWallets.add("0x" + String.format("%040x", i));
                }
                
                org.apache.jackrabbit.oak.segment.consensus.economics.ValidatorEarningsTracker earningsTracker = 
                    new org.apache.jackrabbit.oak.segment.consensus.economics.ValidatorEarningsTracker(validatorWallets);
                httpServer.getContext().setValidatorEarningsTracker(earningsTracker);
                System.out.println("   ✅ Validator Earnings Tracker initialized (" + validatorWallets.size() + " validators)");
                System.out.println("   - Self wallet: " + wallet.getWalletAddress());
                
                // Set wallet addresses in ServerContext for dashboard display
                httpServer.getContext().validatorWalletAddress = wallet.getWalletAddress();
                httpServer.getContext().clusterWalletAddress = finalClusterWallet;
                System.out.println("   - Payments routed to cluster wallet: " + finalClusterWallet);
                
                // ✈️ AERON MODE: Skip HTTP peer registration
                // Aeron Cluster handles membership via Raft consensus - HTTP registration is legacy
                // Only register self for /v1/peers API visibility (Aeron membership is source of truth)
                // Note: registerWithPeers will detect aeronConsensusEngine and skip peer registration
                String validatorId = wallet.getWalletAddress();
                // Pass empty list - registerWithPeers will detect Aeron and skip peer registration
                httpServer.registerWithPeers(validatorId, java.util.Collections.emptyList());
                System.out.println("   - Self registered: " + validatorId + " (Aeron Cluster handles peer membership via Raft)");
                
            } else {
                // This should never happen - we validate Aeron mode at startup
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
     * Initialize the IMMORTAL GENESIS NODE.
     * 
     * Like Ethereum's Block 0, this is the birth certificate of the network.
     * All validators MUST sync from this genesis state to join the network.
     * 
     * Contains:
     * - Network identity (chainId, genesisHash)
     * - Consensus rules (Raft term duration, quorum requirements)
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
            String genesisShardedPath = org.apache.jackrabbit.oak.segment.consensus.util.WalletPathUtil.getContentPath(GENESIS_ADDRESS);
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
                                    // Check both old flat structure (backward compatibility) and new hierarchical structure
                                    org.apache.jackrabbit.oak.spi.state.NodeState genesisNode = genesisWallet.getChildNode("genesis");
                                    
                                    // Try new hierarchical structure first
                                    org.apache.jackrabbit.oak.spi.state.NodeState protocolNode = genesisNode.getChildNode("protocol");
                                    org.apache.jackrabbit.oak.api.PropertyState msgProp = null;
                                    
                                    if (protocolNode.exists()) {
                                        // New hierarchical structure
                                        msgProp = protocolNode.getProperty("message");
                                    } else {
                                        // Old flat structure (backward compatibility)
                                        msgProp = genesisNode.getProperty("protocol.message");
                                    }
                                    
                                    if (msgProp == null || !"DO IT LIVE!".equals(msgProp.getValue(org.apache.jackrabbit.oak.api.Type.STRING))) {
                                        throw new IllegalStateException("❌ GENESIS CORRUPTION! This node has invalid genesis state.");
                                    }
                                    
                                    System.out.println("   ✅ Genesis integrity verified");
                                    
                                    // Log genesis HEAD for script detection
                                    org.apache.jackrabbit.oak.segment.RecordId genesisHead = fileStore.getHead().getRecordId();
                                    System.out.println("   Genesis HEAD: " + genesisHead.toString10());
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
            org.apache.jackrabbit.oak.spi.state.NodeBuilder protocol = genesis.child("protocol");
            protocol.setProperty("jcr:primaryType", "nt:unstructured");
            protocol.setProperty("message", "DO IT LIVE!");
            protocol.setProperty("version", "1.0.0");
            protocol.setProperty("chainId", "oak-blockchain-aem-poc");
            protocol.setProperty("genesisTimestamp", timestamp);
            protocol.setProperty("genesisDate", genesisDate);
            protocol.setProperty("description", 
                "Decentralized content storage for Adobe Experience Manager using Oak + Blockchain consensus");
            
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            // CONSENSUS: Network Rules
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            org.apache.jackrabbit.oak.spi.state.NodeBuilder consensus = genesis.child("consensus");
            consensus.setProperty("jcr:primaryType", "nt:unstructured");
            consensus.setProperty("model", "aeron-raft");
            consensus.setProperty("quorumType", "majority");
            consensus.setProperty("quorumFormula", "(totalMembers / 2) + 1");
            // Note: Aeron/Raft handles terms and heartbeats internally - no configuration needed
            // Note: Aeron/Raft doesn't have probation - all cluster members are voting members
            
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            // NETWORK: Bootstrap Configuration
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            org.apache.jackrabbit.oak.spi.state.NodeBuilder network = genesis.child("network");
            network.setProperty("jcr:primaryType", "nt:unstructured");
            network.setProperty("genesisValidator", genesisValidator);
            network.setProperty("genesisHost", genesisHost);
            network.setProperty("bootstrapPort", 8091L);
            network.setProperty("consensusPort", 8090L);
            network.setProperty("metricsPort", 8090L);
            network.setProperty("metricsPath", "/metrics");
            
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            // INSTRUCTIONS: How to Join This Network
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            org.apache.jackrabbit.oak.spi.state.NodeBuilder join = genesis.child("join");
            join.setProperty("jcr:primaryType", "nt:unstructured");
            join.setProperty("title", "🚀 Welcome to Blockchain AEM Network");
            
            // Join steps as child nodes for better structure
            org.apache.jackrabbit.oak.spi.state.NodeBuilder steps = join.child("steps");
            steps.setProperty("jcr:primaryType", "nt:unstructured");
            
            org.apache.jackrabbit.oak.spi.state.NodeBuilder step1 = steps.child("step1");
            step1.setProperty("jcr:primaryType", "nt:unstructured");
            step1.setProperty("title", "Configure Bootstrap Primary");
            step1.setProperty("env", "BOOTSTRAP_PRIMARY_HOST=" + genesisHost);
            
            org.apache.jackrabbit.oak.spi.state.NodeBuilder step2 = steps.child("step2");
            step2.setProperty("jcr:primaryType", "nt:unstructured");
            step2.setProperty("title", "Set Bootstrap Port");
            step2.setProperty("env", "BOOTSTRAP_PRIMARY_PORT=8091");
            
            org.apache.jackrabbit.oak.spi.state.NodeBuilder step3 = steps.child("step3");
            step3.setProperty("jcr:primaryType", "nt:unstructured");
            step3.setProperty("title", "Enable Consensus");
            step3.setProperty("env", "CONSENSUS_ENABLED=true");
            
            org.apache.jackrabbit.oak.spi.state.NodeBuilder step4 = steps.child("step4");
            step4.setProperty("jcr:primaryType", "nt:unstructured");
            step4.setProperty("title", "Set Consensus Mode");
            step4.setProperty("env", "CONSENSUS_MODE=aeron");
            
            org.apache.jackrabbit.oak.spi.state.NodeBuilder step5 = steps.child("step5");
            step5.setProperty("jcr:primaryType", "nt:unstructured");
            step5.setProperty("title", "Set Your Validator URL");
            step5.setProperty("env", "CONSENSUS_SELF_URL=http://your-validator:8090");
            
            org.apache.jackrabbit.oak.spi.state.NodeBuilder step6 = steps.child("step6");
            step6.setProperty("jcr:primaryType", "nt:unstructured");
            step6.setProperty("title", "Configure Aeron Cluster (Optional)");
            step6.setProperty("env", "AERON_CLUSTER_NODE_ID=0");
            step6.setProperty("note", "Node ID must be unique per validator (0, 1, 2, ...)");
            
            // Notes as properties on join node
            join.setProperty("note", 
                "After bootstrap, you join as a voting member of the Aeron Cluster (Raft consensus)");
            
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            // SECURITY: Byzantine Fault Tolerance
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            org.apache.jackrabbit.oak.spi.state.NodeBuilder security = genesis.child("security");
            security.setProperty("jcr:primaryType", "nt:unstructured");
            security.setProperty("splitBrainDetection", true); // Raft quorum enforcement
            security.setProperty("genesisVerification", true);
            // Note: Aeron/Raft provides election safety, log matching, and leader completeness guarantees
            
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            // METADATA: Project Information
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            org.apache.jackrabbit.oak.spi.state.NodeBuilder meta = genesis.child("meta");
            meta.setProperty("jcr:primaryType", "nt:unstructured");
            meta.setProperty("author", "Oak Segment Consensus");
            meta.setProperty("repository", "Apache Jackrabbit Oak");
            meta.setProperty("documentation", "See /oak-chain/content/genesis");
            meta.setProperty("license", "Apache License 2.0");
            
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            // GENESIS IMAGE: "DO IT LIVE!" via IPFS (ADR 015)
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            // The genesis image is stored in IPFS and serves as:
            // 1. Proof that IPFS integration works from day 0
            // 2. A memorable visual for the network's birth
            // 3. Content-addressed storage demo (CID never changes)
            
            org.apache.jackrabbit.oak.spi.state.NodeBuilder genesisImage = genesis.child("do-it-live.jpeg");
            genesisImage.setProperty("jcr:primaryType", "nt:file");
            genesisImage.setProperty("jcr:created", timestamp);
            
            org.apache.jackrabbit.oak.spi.state.NodeBuilder imageContent = genesisImage.child("jcr:content");
            imageContent.setProperty("jcr:primaryType", "nt:resource");
            imageContent.setProperty("jcr:mimeType", "image/jpeg");
            imageContent.setProperty("jcr:lastModified", timestamp);
            
            // Load genesis image from resources and store in BlobStore (IPFS if configured)
            String ipfsCid = null;
            try {
                java.io.InputStream imageStream = getClass().getClassLoader()
                    .getResourceAsStream("genesis-assets/do-it-live.jpeg");
                
                if (imageStream != null && blobStore != null) {
                    // Read image bytes
                    java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = imageStream.read(buffer)) != -1) {
                        baos.write(buffer, 0, bytesRead);
                    }
                    imageStream.close();
                    byte[] imageBytes = baos.toByteArray();
                    
                    System.out.println("   📸 Storing genesis image in IPFS...");
                    System.out.println("      Size: " + imageBytes.length + " bytes");
                    
                    // Store via BlobStore (IPFS backend will pin it)
                    org.apache.jackrabbit.oak.spi.blob.BlobStore bStore = this.blobStore;
                    if (bStore != null) {
                        String blobId = bStore.writeBlob(new java.io.ByteArrayInputStream(imageBytes));
                        
                        // Create proper Binary from blobId
                        org.apache.jackrabbit.oak.api.Blob blob = 
                            new org.apache.jackrabbit.oak.plugins.blob.BlobStoreBlob(bStore, blobId);
                        imageContent.setProperty("jcr:data", blob);
                        imageContent.setProperty("jcr:blobId", blobId);
                        
                        // If IPFS, try to extract CID
                        if (blobId.startsWith("Qm") || blobId.startsWith("bafy")) {
                            ipfsCid = blobId.split("#")[0]; // Remove size suffix if present
                            imageContent.setProperty("ipfs:cid", ipfsCid);
                            System.out.println("      ✅ IPFS CID: " + ipfsCid);
                        } else {
                            // Use blobId as fallback for non-IPFS blobs
                            ipfsCid = blobId;
                            System.out.println("      ✅ Blob ID: " + blobId);
                        }
                    }
                } else if (imageStream != null) {
                    // No BlobStore, store as inline binary (not recommended for production)
                    System.out.println("   ⚠️  No BlobStore configured - storing image inline (demo mode)");
                    java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = imageStream.read(buffer)) != -1) {
                        baos.write(buffer, 0, bytesRead);
                    }
                    imageStream.close();
                    byte[] imageBytes = baos.toByteArray();
                    
                    // Store as inline binary (Oak will store in segment)
                    org.apache.jackrabbit.oak.api.Blob blob = 
                        nodeStore.createBlob(new java.io.ByteArrayInputStream(imageBytes));
                    imageContent.setProperty("jcr:data", blob);
                    System.out.println("      Size: " + imageBytes.length + " bytes (inline)");
                } else {
                    System.out.println("   ⚠️  Genesis image not found in resources");
                    imageContent.setProperty("jcr:data", "DO IT LIVE! (image placeholder)");
                }
            } catch (Exception e) {
                System.out.println("   ⚠️  Failed to store genesis image: " + e.getMessage());
                imageContent.setProperty("jcr:data", "DO IT LIVE! (image error: " + e.getMessage() + ")");
            }
            
            // Store IPFS info for display
            org.apache.jackrabbit.oak.spi.state.NodeBuilder ipfsInfo = genesis.child("ipfs");
            ipfsInfo.setProperty("jcr:primaryType", "nt:unstructured");
            ipfsInfo.setProperty("enabled", blobStore != null);
            ipfsInfo.setProperty("genesisImageCid", ipfsCid != null ? ipfsCid : "N/A (BlobStore fallback)");
            ipfsInfo.setProperty("gateway", "https://ipfs.io/ipfs/");
            ipfsInfo.setProperty("localGateway", "http://localhost:8080/ipfs/");
            ipfsInfo.setProperty("description", "Binaries stored via IPFS - content-addressed, decentralized, immutable");
            
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
            System.out.println("      Version: 1.0.0");
            System.out.println("      Birth: " + genesisDate);
            System.out.println("");
            System.out.println("   📦 IPFS (Decentralized Binary Storage):");
            if (ipfsCid != null) {
                System.out.println("      ✅ Genesis Image: do-it-live.jpeg");
                System.out.println("      ✅ IPFS CID: " + ipfsCid);
                System.out.println("      ✅ Public Gateway: https://ipfs.io/ipfs/" + ipfsCid);
                System.out.println("      ✅ Local Gateway: http://localhost:8080/ipfs/" + ipfsCid);
            } else if (blobStore != null) {
                System.out.println("      ✅ Genesis Image: do-it-live.jpeg (via BlobStore)");
            } else {
                System.out.println("      ⚠️ IPFS not configured (demo mode - inline binaries)");
            }
            System.out.println("");
            System.out.println("   🎖️  CONSENSUS:");
            System.out.println("      Model: aeron-raft");
            System.out.println("      Quorum: (totalMembers / 2) + 1");
            System.out.println("      Note: All cluster members are voting members (Raft consensus)");
            System.out.println("      Note: Terms and heartbeats handled internally by Aeron Cluster");
            System.out.println("");
            System.out.println("   🌐 NETWORK:");
            System.out.println("      Genesis Validator: " + genesisValidator);
            System.out.println("      Bootstrap Host: " + genesisHost);
            System.out.println("      Bootstrap Port: 8091");
            System.out.println("      Consensus Port: 8090");
            System.out.println("");
            System.out.println("   🛡️  SECURITY:");
            System.out.println("      ✅ Split-Brain Detection (Raft quorum enforcement)");
            System.out.println("      ✅ Genesis Verification (state integrity)");
            System.out.println("      ✅ Raft guarantees: Election safety, log matching, leader completeness");
            System.out.println("");
            System.out.println("   🚀 TO JOIN THIS NETWORK:");
            System.out.println("      1. BOOTSTRAP_PRIMARY_HOST=" + genesisHost);
            System.out.println("      2. BOOTSTRAP_PRIMARY_PORT=8091");
            System.out.println("      3. CONSENSUS_ENABLED=true");
            System.out.println("      4. CONSENSUS_MODE=aeron");
            System.out.println("      5. CONSENSUS_SELF_URL=http://your-validator:8090");
            System.out.println("      6. AERON_CLUSTER_NODE_ID=<unique-id> (0, 1, 2, ...)");
            System.out.println("");
            System.out.println("      → You'll join as a voting member of the Aeron Cluster");
            System.out.println("      → Raft consensus ensures safety and liveness guarantees");
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
            
            // Log genesis HEAD for script detection
            org.apache.jackrabbit.oak.segment.RecordId genesisHead = fileStore.getHead().getRecordId();
            System.out.println("   Genesis HEAD: " + genesisHead.toString10());
            System.out.println("");
            
        } catch (Exception e) {
            System.err.println("   ❌ FATAL: Failed to create genesis: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Genesis creation failed - cannot start network", e);
        }
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
        
        // Create Aeron Consensus Engine (pass this.blobStore for genesis image upload)
        org.apache.jackrabbit.oak.segment.consensus.aeron.AeronConsensusEngine aeronEngine = 
            new org.apache.jackrabbit.oak.segment.consensus.aeron.AeronConsensusEngine(
                fileStore, nodeStore, selfUrl, peerUrls, wallet, storeDirectory, this.blobStore
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
        
        // Set write/delete application callback BEFORE launching cluster
        aeronEngine.setWriteApplicationCallback(new org.apache.jackrabbit.oak.segment.consensus.aeron.AeronConsensusEngine.WriteApplicationCallback() {
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
        
        // 🔄 STARTUP ELECTION OBSERVATION: Verify cluster health before genesis
        // Watches leader elections for 15s to ensure all nodes can participate
        if (!hasExistingCluster && hostnamesList.size() >= 3) {
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
            org.apache.jackrabbit.oak.segment.consensus.sharding.ShardingStrategy shardingStrategy = 
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
        } catch (Exception e) {
            System.err.println("⚠️  WARNING: Failed to initialize Shard Router: " + e.getMessage());
            System.err.println("   → Shard routing disabled, requests will route directly");
            e.printStackTrace();
        }
        
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
     * <p>For production deployments (ngrok, cloud environments), set `consensus.self.url` 
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
    
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // REMOVED: syncGenesisFromPeer() and fetchMissingSegmentsForGenesis()
    // 
    // These methods were pre-Aeron legacy code for manual HTTP-based genesis sync.
    // With Aeron Cluster, genesis is handled automatically:
    //   1. Leader creates genesis via Raft consensus
    //   2. Followers receive genesis via Aeron's replicated log (onSessionMessage)
    //   3. Late-joining nodes receive state via Aeron snapshots
    //
    // See: AeronConsensusEngine.java lines 548-551
    // Removed: January 2026 (tech debt cleanup)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    
    // REMOVED: fetchSegmentBytesFromUrl() - only used by removed syncGenesisFromPeer()
    
    /**
     * Observe elections for a period to verify cluster health.
     * Passively watches leadership changes to ensure all nodes can participate.
     * 
     * @param engine Aeron consensus engine
     * @param observationMs Observation period in milliseconds
     */
    private void observeElections(
            org.apache.jackrabbit.oak.segment.consensus.aeron.AeronConsensusEngine engine,
            long observationMs) throws Exception {
        
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
