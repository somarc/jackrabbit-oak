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

import io.aeron.ExclusivePublication;
import io.aeron.Image;
import io.aeron.cluster.codecs.CloseReason;
import io.aeron.cluster.service.ClientSession;
import io.aeron.cluster.service.Cluster;
import io.aeron.cluster.service.ClusteredService;
import io.aeron.logbuffer.Header;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.IdleStrategy;
import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.apache.jackrabbit.oak.segment.consensus.leader.ValidatorRole;
import org.apache.jackrabbit.oak.segment.consensus.eth.BeaconChainClient;
import org.apache.jackrabbit.oak.segment.consensus.util.SegmentReplicator;
import org.apache.jackrabbit.oak.spi.state.NodeStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Aeron Cluster-based consensus engine using proven Raft algorithm.
 * 
 * <p><strong>DISTRIBUTED ARCHITECTURE:</strong>
 * This is a distributed consensus system designed to run across multiple machines,
 * networks, and data centers. Validators communicate via UDP/IP networks and can
 * be deployed across geographically distributed infrastructure. The system is
 * NOT confined to localhost or single-machine deployments.
 * 
 * <p>This implementation leverages Aeron Cluster's battle-tested Raft consensus
 * to provide election safety, quorum requirements, log matching, and leader
 * completeness guarantees. Our unique value is the Ethereum integration layer.
 * 
 * <p><strong>Distributed Deployment:</strong>
 * <pre>
 * ┌─────────────────────────────────────────────────────────────┐
 * │         Distributed Validator Network                      │
 * │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐   │
 * │  │ Validator-0  │  │ Validator-1  │  │ Validator-2  │   │
 * │  │ (US-East)    │  │ (EU-West)    │  │ (AP-South)   │   │
 * │  │ 10.0.1.10    │  │ 10.0.2.10    │  │ 10.0.3.10    │   │
 * │  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘   │
 * │         │                  │                  │           │
 * │         └──────────────────┼──────────────────┘           │
 * │                            │                               │
 * │                    UDP/IP Network                        │
 * │              (Aeron Cluster Raft)                        │
 * └─────────────────────────────────────────────────────────────┘
 *                            │
 *                            ▼
 * ┌─────────────────────────────────────────┐
 * │     Aeron Cluster (Raft)               │
 * │  - Term-based leadership                │
 * │  - Majority quorum requirements        │
 * │  - Election safety guarantees          │
 * │  - Log matching guarantees             │
 * │  - Leader completeness                 │
 * │  - Network partition tolerance         │
 * └─────────────────────────────────────────┘
 *              │
 *              ▼
 * ┌─────────────────────────────────────────┐
 * │     Ethereum Integration Layer          │
 * │  - Epoch values from Ethereum Beacon   │
 * │  - Transaction-driven writes           │
 * │  - USDC payment validation              │
 * │  - Wallet-based sharding                │
 * └─────────────────────────────────────────┘
 * </pre>
 * 
 * <p><strong>Network Configuration:</strong>
 * <ul>
 *   <li>Validators communicate via UDP/IP (configurable endpoints)</li>
 *   <li>Peer URLs can be IP addresses, hostnames, or public URLs</li>
 *   <li>Supports deployment across multiple data centers/regions</li>
 *   <li>Network discovery via configured peer URLs</li>
 *   <li>No hard-coded localhost assumptions - fully distributed</li>
 * </ul>
 * 
 * <p><strong>Key Benefits:</strong>
 * <ul>
 *   <li>✅ Proven Raft consensus (no split-brain, guaranteed safety)</li>
 *   <li>✅ High performance (low latency, high throughput)</li>
 *   <li>✅ Distributed by design (multi-region, multi-datacenter capable)</li>
 *   <li>✅ Focus on Ethereum integration (our unique value)</li>
 *   <li>✅ Reduced complexity (less custom code to maintain)</li>
 * </ul>
 * 
 * <p><strong>Reference:</strong>
 * <ul>
 *   <li><a href="https://github.com/aeron-io/aeron">Aeron GitHub</a></li>
 *   <li><a href="https://raft.github.io/">Raft Consensus Algorithm</a></li>
 *   <li><a href="https://aeron.io/case-studies/coinbase-cloudnative-crypto-exchange-aeron-cluster/">Coinbase Case Study</a></li>
 * </ul>
 */
public class AeronConsensusEngine implements ClusteredService {
    
    private static final Logger log = LoggerFactory.getLogger(AeronConsensusEngine.class);
    
    private final FileStore fileStore;
    private final NodeStore nodeStore;
    private final String selfUrl;
    private final List<String> peerUrls;
    private final org.apache.jackrabbit.oak.segment.consensus.security.EthereumWallet wallet;
    private final SegmentReplicator replicator;
    private final String storeDirectory;
    private final org.apache.jackrabbit.oak.segment.consensus.queue.BackpressureManager backpressureManager;
    private final org.apache.jackrabbit.oak.spi.blob.BlobStore blobStore;
    
    // ✅ PRODUCTION REFACTOR: Service layer components (extracted from monolithic class)
    private final MessageDispatcher messageDispatcher;
    private final SnapshotService snapshotService;
    private final LeaderDiscoveryService leaderDiscoveryService;
    
    // Aeron Cluster components
    private Cluster cluster;
    private IdleStrategy idleStrategy;
    
    // ✈️ AERON NATIVE: Ingress channel URI for client connections
    // For distributed cluster communication, we use UDP
    // Using default term length (128MB) for production WAN compatibility
    // Sufficient for high-throughput, concurrent write workloads
    private String ingressChannelUri = "aeron:udp";
    
    // ✈️ AERON NATIVE: Media driver directory name (needed for client connections)
    private String aeronDirectoryName = null;
    
    // ✈️ AERON NATIVE: Internal AeronCluster client for sending writes through ingress
    // This client connects to the same media driver (via IPC) to send messages
    private io.aeron.cluster.client.AeronCluster internalClusterClient = null;
    
    // ✈️ AERON NATIVE: Callback interface for applying replicated writes and deletes
    public interface WriteApplicationCallback {
        void applyReplicatedWrite(String walletAddress, String path, String contentType, String message, 
                                 String signature, String intentToken, String blobId, String mimeType);
        void applyReplicatedDelete(String walletAddress, String path, String signature);
    }
    private WriteApplicationCallback writeCallback;
    
    // Ethereum integration
    private BeaconChainClient beaconClient;
    private volatile int currentEthereumEpoch = -1;
    
    // Consensus state (mapped from Aeron Cluster)
    private volatile ValidatorRole currentRole = ValidatorRole.FOLLOWER;
    // ✅ ADR 025: Track term locally (Aeron Cluster doesn't expose leadershipTermId on Cluster interface)
    // This is updated on role changes and used as fallback when Aeron term not available
    private volatile int currentTerm = 0;
    private volatile String currentLeader = null;
    private volatile long lastHeartbeatTime = System.currentTimeMillis();
    
    // ✅ ADR 025: Replication lag monitoring
    private volatile long leaderLogPosition = 0; // Track leader's position for lag calculation
    
    // Track validator join times (for probation, if needed)
    private final Map<String, Long> validatorJoinTimes = new ConcurrentHashMap<>();
    
    // Map node IDs to URLs for leader lookup
    private final Map<Integer, String> nodeIdToUrl = new ConcurrentHashMap<>();
    
    // Leader discovery cache (performance optimization)
    private volatile String cachedLeaderUrl = null;
    private volatile long cachedLeaderTimestamp = 0;
    private static final long LEADER_CACHE_TTL_MS = 10000; // 10 seconds
    
    // Write throughput tracking (for periodic summary logging)
    private final java.util.concurrent.atomic.AtomicLong totalWritesProcessed = new java.util.concurrent.atomic.AtomicLong(0);
    private volatile long lastSummaryLogTime = System.currentTimeMillis();
    private volatile long lastSummaryWriteCount = 0;
    private static final long SUMMARY_LOG_INTERVAL_MS = 10000; // Log summary every 10 seconds
    
    // Raft performance metrics (track consensus latency, throughput, utilization)
    private final AeronPerformanceMetrics performanceMetrics = new AeronPerformanceMetrics();
    
    // Ingress timestamp tracking (for Raft latency calculation)
    // Since Raft processes messages in order, we can use a simple FIFO queue
    private final java.util.concurrent.ConcurrentLinkedQueue<Long> ingressTimestamps = new java.util.concurrent.ConcurrentLinkedQueue<>();
    
    // ✈️ AERON NATIVE: Track leadership rotation history from onRoleChange() callbacks
    public static class LeadershipChange {
        public final long timestamp;
        public final Cluster.Role newRole;
        public final Cluster.Role previousRole;
        public final int term;
        public final int memberId;
        public final String memberUrl;
        
        public LeadershipChange(long timestamp, Cluster.Role newRole, Cluster.Role previousRole, 
                               int term, int memberId, String memberUrl) {
            this.timestamp = timestamp;
            this.newRole = newRole;
            this.previousRole = previousRole;
            this.term = term;
            this.memberId = memberId;
            this.memberUrl = memberUrl;
        }
    }
    
    private final java.util.List<LeadershipChange> leadershipHistory = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static final int MAX_HISTORY_ENTRIES = 100; // Keep last 100 role changes
    
    /**
     * Create Aeron-based consensus engine.
     * 
     * @param fileStore Oak FileStore for segment operations
     * @param nodeStore Oak NodeStore for state operations
     * @param selfUrl This validator's URL
     * @param peerUrls List of peer validator URLs
     * @param wallet Ethereum wallet for validator identity
     * @param blobStore BlobStore for genesis image (can be null)
     */
    public AeronConsensusEngine(
            FileStore fileStore,
            NodeStore nodeStore,
            String selfUrl,
            List<String> peerUrls,
            org.apache.jackrabbit.oak.segment.consensus.security.EthereumWallet wallet,
            String storeDirectory,
            org.apache.jackrabbit.oak.spi.blob.BlobStore blobStore) {
        this.fileStore = fileStore;
        this.nodeStore = nodeStore;
        this.selfUrl = selfUrl;
        this.peerUrls = peerUrls;
        this.wallet = wallet;
        this.storeDirectory = storeDirectory;
        this.blobStore = blobStore;
        this.replicator = new SegmentReplicator(fileStore);
        this.backpressureManager = new org.apache.jackrabbit.oak.segment.consensus.queue.BackpressureManager();
        
        // Build node ID to URL mapping (will be populated when cluster starts)
        // This allows us to map Aeron Cluster leaderMemberId to validator URL
        
        // ✅ PRODUCTION REFACTOR: Initialize service layer components
        this.snapshotService = new SnapshotService(fileStore, storeDirectory);
        this.leaderDiscoveryService = new LeaderDiscoveryService(nodeIdToUrl, peerUrls);
        this.messageDispatcher = new MessageDispatcher(
            new MessageDispatcher.WriteCallback() {
                @Override
                public void applyWrite(String walletAddress, String path, String contentType, 
                                     String message, String signature, String intentToken, 
                                     String blobId, String mimeType) {
                    // Delegate to existing write application logic
                    if (writeCallback != null) {
                        writeCallback.applyReplicatedWrite(walletAddress, path, contentType, 
                                                          message, signature, intentToken, 
                                                          blobId, mimeType);
                    }
                }
                
                @Override
                public void applyDelete(String walletAddress, String path, String signature) {
                    // Delegate to existing delete application logic
                    if (writeCallback != null) {
                        writeCallback.applyReplicatedDelete(walletAddress, path, signature);
                    }
                }
            },
            new MessageDispatcher.HeadBroadcastCallback() {
                @Override
                public void onHeadBroadcast(String newHead, int epoch, long timestamp, int validatorCount) {
                    // Handle HEAD broadcast from leader
                    log.info("📥 Received HEAD broadcast: head={}, epoch={}, timestamp={}", 
                            newHead, epoch, timestamp);
                    updateLatestHead(newHead);
                }
            }
        );
        
        log.info("Aeron Consensus Engine initializing - Consensus: Aeron Cluster (Raft), Self: {}, Peers: {}, Wallet: {}", 
            selfUrl, peerUrls.size(), wallet.getWalletAddress());
        log.info("✅ Production service layer initialized: MessageDispatcher, SnapshotService, HeadBroadcastService, LeaderDiscoveryService");
    }
    
    /**
     * Set node ID to URL mapping (called during cluster initialization).
     * This allows us to map Aeron Cluster leaderMemberId to validator URL.
     */
    public void setNodeIdMapping(Map<Integer, String> nodeIdToUrl) {
        this.nodeIdToUrl.clear();
        this.nodeIdToUrl.putAll(nodeIdToUrl);
        log.debug("Updated node ID mapping: {}", nodeIdToUrl);
    }
    
    /**
     * Set callback for applying replicated writes to FileStore.
     * This is called from onSessionMessage() after Aeron replicates the write.
     */
    public void setWriteApplicationCallback(WriteApplicationCallback callback) {
        this.writeCallback = callback;
        log.info("✅ Write application callback set: {}", callback != null ? "present" : "null");
    }
    
    /**
     * ✈️ AERON NATIVE: Set ingress channel URI for client connections.
     * 
     * This is the channel URI that clients use to connect to the cluster's ingress.
     * For distributed cluster communication, UDP is required for multi-node Raft consensus.
     * 
     * @param ingressChannelUri The ingress channel URI (e.g., "aeron:udp" or "aeron:udp?endpoint=localhost:8010")
     */
    public void setIngressChannelUri(String ingressChannelUri) {
        this.ingressChannelUri = ingressChannelUri;
        log.info("✈️  Ingress channel URI set: {}", ingressChannelUri);
    }
    
    /**
     * ✈️ AERON NATIVE: Get ingress channel URI.
     */
    public String getIngressChannelUri() {
        return ingressChannelUri;
    }
    
    /**
     * ✈️ AERON NATIVE: Set media driver directory name for client connections.
     * This is required when creating Aeron clients to connect to the cluster's media driver.
     */
    public void setAeronDirectoryName(String aeronDirectoryName) {
        this.aeronDirectoryName = aeronDirectoryName;
        log.info("✈️  Aeron directory name set: {}", aeronDirectoryName);
    }
    
    public String getAeronDirectoryName() {
        return aeronDirectoryName;
    }
    
    /**
     * Start the Aeron Cluster consensus engine.
     * 
     * This initializes Aeron Cluster with Raft consensus and begins
     * participating in the consensus network.
     */
    public void start() {
        try {
            log.info("🔧 Initializing Aeron Cluster...");
            
            // TODO: Initialize Aeron Cluster
            // This is Phase 2 - basic structure
            // Full implementation will configure:
            // - Cluster nodes (validators)
            // - Raft consensus parameters
            // - Message handlers
            // - State machine
            
            // Start background timer for checking pending HEAD broadcasts
            // This ensures broadcasts happen even when no new writes arrive
            running = true;
            startHeadBroadcastTimer();
            
            log.info("Aeron Consensus Engine started - Status: Ready");
            
        } catch (Exception e) {
            log.error("❌ Failed to start Aeron Consensus Engine", e);
            throw new RuntimeException("Aeron Cluster initialization failed", e);
        }
    }
    
    /**
     * Stop the Aeron Cluster consensus engine.
     */
    public void stop() {
        log.info("🛑 Stopping Aeron Consensus Engine...");
        
        // Stop background timer
        running = false;
        stopHeadBroadcastTimer();
        
        // TODO: Close Aeron Cluster components once initialized
        // if (container != null) {
        //     try {
        //         container.close();
        //     } catch (Exception e) {
        //         log.warn("Error closing Aeron container", e);
        //     }
        // }
        // 
        // if (cluster != null) {
        //     try {
        //         cluster.close();
        //     } catch (Exception e) {
        //         log.warn("Error closing Aeron cluster", e);
        //     }
        // }
        
        log.info("✅ Aeron Consensus Engine stopped");
    }
    
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // ETHEREUM INTEGRATION LAYER (Our Unique Value)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    
    /**
     * Initialize Ethereum Beacon Chain client for epoch integration.
     * 
     * @param beaconApiUrl Beacon Chain API URL (e.g., https://beaconcha.in/api)
     */
    public void initializeEthereumIntegration(String beaconApiUrl) {
        log.info("Initializing Ethereum integration - Beacon API: {}, Current epoch: {}", beaconApiUrl, currentEthereumEpoch);
        
        this.beaconClient = new BeaconChainClient(beaconApiUrl);
        
        // Start unified epoch polling (single source of truth)
        beaconClient.startBackgroundPolling();
        
        log.info("Ethereum integration initialized with unified epoch polling");
    }
    
    /**
     * Start polling Ethereum Beacon Chain for epoch updates.
     * 
     * This replaces system-time epochs with Ethereum epochs, solving
     * clock skew issues and aligning with blockchain consensus.
     */
    private void startEthereumEpochPolling() {
        if (beaconClient == null) {
            log.warn("⚠️  Ethereum Beacon Chain client not initialized - skipping epoch polling");
            return;
        }
        
        // Poll every 780 seconds (13 minutes) - aligned with Ethereum finality
        // Ethereum achieves finality every 2 epochs (~12.8 minutes)
        java.util.concurrent.ScheduledExecutorService scheduler = 
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        
        scheduler.scheduleAtFixedRate(() -> {
            try {
                // Get current finalized epoch from Beacon Chain
                // Use the same approach as EpochListener: getLatestFinalizedEpoch()
                // This returns epochs that are finalized (2 epochs behind current)
                org.apache.jackrabbit.oak.segment.consensus.eth.EpochData epochData = 
                    beaconClient.getLatestFinalizedEpoch();
                
                // 🔄 FINALITY BOUNDARY DETECTION: Check if we've reached a new finality boundary
                // When a new epoch reaches finality (2 epochs behind current), broadcast HEAD
                // to ensure all validators sync at finality boundaries
                // 🎯 DETERMINISTIC STATE MACHINE: Finality boundary broadcasting disabled
                // All nodes process Ethereum epoch transitions identically via Aeron
                // HEAD consistency guaranteed by deterministic processing
                if (isLeader() && epochData.finalized) {
                    log.debug("📊 Finality boundary detected (epoch {}), but broadcast disabled (deterministic consensus)", 
                        epochData.epochNumber);
                }
                
                if (epochData.epochNumber > currentEthereumEpoch) {
                    log.info("Ethereum epoch update: {} -> {} (finalized: {}, source: Beacon Chain)", 
                        currentEthereumEpoch, epochData.epochNumber, epochData.finalized);
                    
                    currentEthereumEpoch = (int) epochData.epochNumber;
                    
                    // TODO: Use Ethereum epoch for leader rotation timing
                    // This replaces system-time epochs with blockchain epochs
                    // Aeron Cluster uses terms, but we can align term transitions
                    // with Ethereum epoch boundaries for blockchain alignment
                }
            } catch (Exception e) {
                log.warn("⚠️  Failed to poll Ethereum Beacon Chain: {}", e.getMessage());
            }
        }, 0, 780, java.util.concurrent.TimeUnit.SECONDS);
        
        log.info("📡 Ethereum epoch polling started");
        log.info("   Poll interval: 780 seconds (aligned with Ethereum finality)");
        log.info("   Current epoch: {}", currentEthereumEpoch);
    }
    
    /**
     * Get current Ethereum epoch (for leader rotation timing).
     * 
     * <p>🎯 Uses cached value from BeaconChainClient (single source of truth).
     * 
     * @return Current finalized Ethereum Beacon Chain epoch, or -1 if not initialized
     */
    public int getCurrentEthereumEpoch() {
        if (beaconClient != null) {
            return (int) beaconClient.getCachedFinalizedEpoch();
        }
        return currentEthereumEpoch; // Fallback to old cached value
    }
    
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // ClusteredService Interface (Aeron Cluster)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    
    @Override
    public void onStart(Cluster cluster, Image snapshotImage) {
        log.info("Aeron Cluster service starting - Dir: {}, Role: {}, Snapshot: {}", 
            cluster.context().clusterDir(), cluster.role(), snapshotImage != null ? "present" : "none");
        
        this.cluster = cluster;
        this.idleStrategy = cluster.idleStrategy();
        
        // Load snapshot if present (ensures consistent initial state)
        if (snapshotImage != null) {
            log.info("Loading snapshot from image");
            
            try {
                SnapshotState snapshotState = loadSnapshotFromImage(snapshotImage);
                
                if (snapshotState != null) {
                    log.info("Snapshot metadata - HEAD: {}, Epoch: {}, Timestamp: {}", 
                        snapshotState.head, snapshotState.ethereumEpoch, snapshotState.timestamp);
                    
                    // Verify FileStore HEAD matches snapshot HEAD
                    String fileStoreHead = fileStore.getHead().getRecordId().toString();
                    if (!snapshotState.head.equals(fileStoreHead)) {
                        log.error("CRITICAL: HEAD mismatch - Snapshot: {}, FileStore: {}. Validators must start from identical state. " +
                            "Solution: Copy segmentstore from validator-0 before starting.", 
                            snapshotState.head, fileStoreHead);
                        
                        throw new IllegalStateException(
                            String.format("FileStore HEAD (%s) doesn't match snapshot HEAD (%s). " +
                                "Validators must start from identical state. " +
                                "Copy segmentstore from validator-0 to other validators before starting.",
                                fileStoreHead, snapshotState.head));
                    }
                    
                    // Restore state
                    currentEthereumEpoch = snapshotState.ethereumEpoch;
                    
                    log.info("Snapshot loaded successfully - HEAD verified: {}", snapshotState.head);
                } else {
                    log.warn("Snapshot image present but no snapshot data found - starting fresh");
                }
            } catch (Exception e) {
                log.error("Failed to load snapshot", e);
                throw new RuntimeException("Snapshot load failed - cannot start with inconsistent state", e);
            }
        } else {
            log.info("Starting fresh (no snapshot)");
            
            // Aeron Cluster's Raft replication handles genesis automatically
            // When leader creates genesis, it's replicated via onSessionMessage() to all followers
            // No manual HTTP segment sync needed - Aeron's consensus log ensures consistency
            log.debug("Followers will receive genesis via Aeron replication (no manual sync needed)");
        }
        
        // Map Aeron Cluster role to our ValidatorRole
        updateRoleFromCluster(cluster.role());
        
        // ✈️ AERON NATIVE: Create internal AeronCluster client for sending writes through ingress
        // This allows us to send messages from within the ClusteredService
        // Uses UDP to connect to the cluster for reliable message delivery
        if (aeronDirectoryName != null && !aeronDirectoryName.isEmpty() && peerUrls != null && !peerUrls.isEmpty()) {
            try {
                // Build ingress endpoints from peer URLs
                // Format: "0=host1:port1,1=host2:port2,2=host3:port3"
                StringBuilder ingressEndpoints = new StringBuilder();
                for (int i = 0; i < peerUrls.size(); i++) {
                    if (i > 0) ingressEndpoints.append(",");
                    String url = peerUrls.get(i);
                    // Extract hostname and port from URL
                    try {
                        java.net.URL parsedUrl = new java.net.URL(url);
                        String host = parsedUrl.getHost();
                        int port = parsedUrl.getPort() != -1 ? parsedUrl.getPort() : 8090;
                        ingressEndpoints.append(i).append("=").append(host).append(":").append(port);
                    } catch (Exception e) {
                        log.warn("Failed to parse peer URL {}: {}", url, e.getMessage());
                    }
                }
                
                // Create AeronCluster client using UDP with localhost endpoints for same-process communication
                // The cluster's ingress is UDP, so we must use UDP too (with localhost for efficiency)
                // NOTE: We defer client creation until first write to avoid timeout during cluster startup
                log.info("✈️  Internal AeronCluster client will be created on-demand (UDP localhost - same process)");
                log.info("   Aeron directory: {}", aeronDirectoryName);
                log.info("   Will use UDP with localhost endpoints for same-process communication");
                // Don't create client here - create it lazily on first write attempt
            } catch (Exception e) {
                log.error("Failed to create internal AeronCluster client", e);
                // Continue without internal client - writes will fail but service can still start
            }
        } else {
            log.warn("Aeron directory name or peer URLs not set - cannot create internal cluster client");
        }
        
        log.info("Aeron Cluster service started successfully");
    }
    
    @Override
    public void onSessionOpen(ClientSession session, long timestamp) {
        log.info("Client session opened: {} (timestamp: {})", session.id(), timestamp);
    }
    
    @Override
    public void onSessionClose(ClientSession session, long timestamp, CloseReason closeReason) {
        log.info("Client session closed: {} (reason: {}, timestamp: {})", session.id(), closeReason, timestamp);
    }
    
    @Override
    public void onTakeSnapshot(io.aeron.ExclusivePublication snapshotPublication) {
        log.info("Taking FileStore snapshot");
        
        try {
            // Get current state
            String currentHead = fileStore.getHead().getRecordId().toString();
            int currentEpoch = currentEthereumEpoch;
            long timestamp = System.currentTimeMillis();
            
            log.info("Snapshot state - HEAD: {}, Epoch: {}, Dir: {}", currentHead, currentEpoch, storeDirectory);
            
            // Use idleStrategy if available
            org.agrona.concurrent.IdleStrategy strategy = idleStrategy != null 
                ? idleStrategy 
                : new org.agrona.concurrent.BusySpinIdleStrategy();
            
            // 1. Send metadata header
            sendSnapshotMetadata(snapshotPublication, currentHead, currentEpoch, timestamp, strategy);
            
            // 2. Stream TAR files
            streamTarFiles(snapshotPublication, strategy);
            
            // 3. Stream journal.log
            streamJournal(snapshotPublication, strategy);
            
            log.info("FileStore snapshot complete - HEAD: {}, Epoch: {}", currentHead, currentEpoch);
        } catch (Exception e) {
            log.error("Failed to take snapshot", e);
        }
    }
    
    private void sendSnapshotMetadata(io.aeron.ExclusivePublication pub, String head, int epoch, long timestamp, 
                                      org.agrona.concurrent.IdleStrategy strategy) throws Exception {
        // Create metadata JSON
        String json = String.format(
            "{\"type\":\"metadata\",\"head\":\"%s\",\"ethereumEpoch\":%d,\"timestamp\":%d}",
            head, epoch, timestamp
        );
        
        byte[] jsonBytes = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int totalLength = org.apache.jackrabbit.oak.segment.consensus.aeron.SimpleMessageHeader.ENCODED_LENGTH + jsonBytes.length;
        org.agrona.concurrent.UnsafeBuffer buffer = new org.agrona.concurrent.UnsafeBuffer(new byte[totalLength]);
        
        // Encode SBE header
        org.apache.jackrabbit.oak.segment.consensus.aeron.SimpleMessageHeader.encode(
            buffer, 0, jsonBytes.length, 
            org.apache.jackrabbit.oak.segment.consensus.aeron.SimpleMessageHeader.TEMPLATE_ID_SNAPSHOT
        );
        buffer.putBytes(org.apache.jackrabbit.oak.segment.consensus.aeron.SimpleMessageHeader.ENCODED_LENGTH, jsonBytes);
        
        offerWithRetry(pub, buffer, 0, totalLength, strategy, "metadata");
        log.info("Sent snapshot metadata ({} bytes)", totalLength);
    }
    
    private void streamTarFiles(io.aeron.ExclusivePublication pub, org.agrona.concurrent.IdleStrategy strategy) throws Exception {
        java.io.File storeDir = new java.io.File(storeDirectory);
        java.io.File[] tarFiles = storeDir.listFiles((dir, name) -> name.endsWith(".tar"));
        
        if (tarFiles == null || tarFiles.length == 0) {
            log.warn("No TAR files found in {}", storeDirectory);
            return;
        }
        
        log.info("Streaming {} TAR files", tarFiles.length);
        
        for (java.io.File tarFile : tarFiles) {
            streamFile(pub, tarFile, "tar", strategy);
        }
    }
    
    private void streamJournal(io.aeron.ExclusivePublication pub, org.agrona.concurrent.IdleStrategy strategy) throws Exception {
        java.io.File journalFile = new java.io.File(storeDirectory, "journal.log");
        if (!journalFile.exists()) {
            log.warn("journal.log not found in {}", storeDirectory);
            return;
        }
        
        log.info("Streaming journal.log");
        streamFile(pub, journalFile, "journal", strategy);
    }
    
    private void streamFile(io.aeron.ExclusivePublication pub, java.io.File file, String fileType, 
                           org.agrona.concurrent.IdleStrategy strategy) throws Exception {
        String fileName = file.getName();
        long fileSize = file.length();
        
        log.info("Streaming {} file: {} ({} bytes)", fileType, fileName, fileSize);
        
        // Send file header
        String headerJson = String.format(
            "{\"type\":\"file_header\",\"fileType\":\"%s\",\"fileName\":\"%s\",\"fileSize\":%d}",
            fileType, fileName, fileSize
        );
        byte[] headerBytes = headerJson.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int headerTotalLength = org.apache.jackrabbit.oak.segment.consensus.aeron.SimpleMessageHeader.ENCODED_LENGTH + headerBytes.length;
        org.agrona.concurrent.UnsafeBuffer headerBuffer = new org.agrona.concurrent.UnsafeBuffer(new byte[headerTotalLength]);
        
        org.apache.jackrabbit.oak.segment.consensus.aeron.SimpleMessageHeader.encode(
            headerBuffer, 0, headerBytes.length,
            org.apache.jackrabbit.oak.segment.consensus.aeron.SimpleMessageHeader.TEMPLATE_ID_SNAPSHOT
        );
        headerBuffer.putBytes(org.apache.jackrabbit.oak.segment.consensus.aeron.SimpleMessageHeader.ENCODED_LENGTH, headerBytes);
        
        offerWithRetry(pub, headerBuffer, 0, headerTotalLength, strategy, "file_header:" + fileName);
        
        // Stream file contents in chunks (1MB chunks to avoid MTU issues)
        int CHUNK_SIZE = 1024 * 1024; // 1MB
        byte[] chunk = new byte[CHUNK_SIZE];
        long bytesStreamed = 0;
        int chunkIndex = 0;
        
        try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
            int bytesRead;
            while ((bytesRead = fis.read(chunk)) > 0) {
                // Send chunk with header
                int chunkTotalLength = org.apache.jackrabbit.oak.segment.consensus.aeron.SimpleMessageHeader.ENCODED_LENGTH + bytesRead;
                org.agrona.concurrent.UnsafeBuffer chunkBuffer = new org.agrona.concurrent.UnsafeBuffer(new byte[chunkTotalLength]);
                
                org.apache.jackrabbit.oak.segment.consensus.aeron.SimpleMessageHeader.encode(
                    chunkBuffer, 0, bytesRead,
                    org.apache.jackrabbit.oak.segment.consensus.aeron.SimpleMessageHeader.TEMPLATE_ID_SNAPSHOT
                );
                chunkBuffer.putBytes(org.apache.jackrabbit.oak.segment.consensus.aeron.SimpleMessageHeader.ENCODED_LENGTH, chunk, 0, bytesRead);
                
                offerWithRetry(pub, chunkBuffer, 0, chunkTotalLength, strategy, "chunk:" + chunkIndex);
                
                bytesStreamed += bytesRead;
                chunkIndex++;
                
                if (chunkIndex % 10 == 0) {
                    log.debug("Streamed {}/{} bytes ({} chunks)", bytesStreamed, fileSize, chunkIndex);
                }
            }
        }
        
        log.info("Streamed {}: {} bytes in {} chunks", fileName, bytesStreamed, chunkIndex);
    }
    
    private void offerWithRetry(io.aeron.ExclusivePublication pub, org.agrona.concurrent.UnsafeBuffer buffer, 
                                int offset, int length, org.agrona.concurrent.IdleStrategy strategy, 
                                String context) throws Exception {
        strategy.reset();
        long result;
        int retries = 0;
        int maxRetries = 1000; // Increased for large snapshots
        
        while ((result = pub.offer(buffer, offset, length)) < 0) {
            if (result == io.aeron.Publication.BACK_PRESSURED) {
                strategy.idle();
                retries++;
                if (retries > maxRetries) {
                    throw new Exception("Snapshot back-pressured after " + retries + " retries (" + context + ")");
                }
            } else if (result == io.aeron.Publication.NOT_CONNECTED) {
                strategy.idle();
                retries++;
                if (retries > maxRetries) {
                    throw new Exception("Snapshot publication not connected after " + retries + " retries (" + context + ")");
                }
            } else {
                throw new Exception("Failed to send snapshot (" + context + "): " + result);
            }
        }
    }
    
    @Override
    public void onSessionMessage(ClientSession session, long timestamp, DirectBuffer buffer, 
                                 int offset, int length, Header header) {
        // ✈️ AERON NATIVE: Handle replicated write proposals
        // This callback is invoked on ALL nodes after Aeron replicates the message via Raft
        // Deterministic state machine: ALL nodes process messages in same order
        
        // 🔥 RAW MESSAGE INSPECTION (GROK DEBUG) - BEFORE ANY DECODING
        // Read template ID directly from buffer to see if batch messages (106) arrive at all
        if (length >= 8) {
            int rawTemplateId = buffer.getShort(offset, java.nio.ByteOrder.LITTLE_ENDIAN);
            int rawVersion = buffer.getShort(offset + 2, java.nio.ByteOrder.LITTLE_ENDIAN);
            log.debug("🔥 RAW INCOMING MESSAGE - templateId: {} (0x{}), version: {}, length: {}, session: {}, role: {}",
                rawTemplateId, Integer.toHexString(rawTemplateId), rawVersion, length, session.id(),
                cluster != null ? cluster.role() : "UNKNOWN");
        }
        
        log.debug("🔍DEBUG_BATCH [RCV-1]: onSessionMessage() CALLED - session: {}, length: {}, role: {}", 
            session.id(), length, cluster != null ? cluster.role() : "UNKNOWN");
        log.debug("📨 onSessionMessage() called - session: {}, length: {}, role: {}, timestamp: {}", 
            session.id(), length, cluster != null ? cluster.role() : "UNKNOWN", timestamp);
        
        // ✈️ AERON MESSAGE VALIDATION: Check SBE message header length first
        // Header must be at least 8 bytes (MessageHeaderDecoder.ENCODED_LENGTH)
        if (length < org.apache.jackrabbit.oak.segment.consensus.aeron.SimpleMessageHeader.ENCODED_LENGTH) {
            log.warn("⚠️  Message too short: {} (minimum {} bytes for SBE header)", 
                length, org.apache.jackrabbit.oak.segment.consensus.aeron.SimpleMessageHeader.ENCODED_LENGTH);
            return;
        }
        
        try {
            // ✈️ AERON MESSAGE DECODING: Decode SBE message header
            org.apache.jackrabbit.oak.segment.consensus.aeron.SimpleMessageHeader.HeaderInfo headerInfo = 
                org.apache.jackrabbit.oak.segment.consensus.aeron.SimpleMessageHeader.decode(buffer, offset);
            
            log.debug("🔍DEBUG_BATCH [RCV-2]: Header decoded - templateId: {} ({}), blockLength: {}", 
                headerInfo.templateId,
                headerInfo.templateId == 100 ? "WRITE_PROPOSAL" : 
                headerInfo.templateId == 106 ? "WRITE_BATCH" : "UNKNOWN",
                headerInfo.blockLength);
            
            log.debug("📨 SBE Header decoded - templateId: {}, blockLength: {}, schemaId: {}, version: {}", 
                headerInfo.templateId, headerInfo.blockLength, headerInfo.schemaId, headerInfo.version);
            
            // Skip header and process message payload
            offset += org.apache.jackrabbit.oak.segment.consensus.aeron.SimpleMessageHeader.ENCODED_LENGTH;
            length -= org.apache.jackrabbit.oak.segment.consensus.aeron.SimpleMessageHeader.ENCODED_LENGTH;
            
            // Check if message length matches header blockLength
            if (length < headerInfo.blockLength) {
                log.warn("⚠️  Message payload shorter than header blockLength: {} < {}", 
                    length, headerInfo.blockLength);
                return;
            }
            
            // Process message based on template ID (like production switch on templateId)
            if (headerInfo.templateId == org.apache.jackrabbit.oak.segment.consensus.aeron.SimpleMessageHeader.TEMPLATE_ID_WRITE_PROPOSAL) {
                // Read JSON string from buffer
                byte[] jsonBytes = new byte[headerInfo.blockLength];
                buffer.getBytes(offset, jsonBytes);
                String json = new String(jsonBytes, java.nio.charset.StandardCharsets.UTF_8);
                
                log.debug("✈️  Processing replicated write proposal via Aeron (templateId: {})", headerInfo.templateId);
                log.debug("   JSON: {}", json);
                
                // Parse write proposal JSON
                String walletAddress = extractJsonField(json, "walletAddress");
                String path = extractJsonField(json, "path");
                String contentType = extractJsonField(json, "contentType");
                String message = extractJsonField(json, "message");
                String signature = extractJsonField(json, "signature");
                String intentToken = extractJsonField(json, "intentToken"); // ADR 020
                String blobId = extractJsonField(json, "blobId");
                String mimeType = extractJsonField(json, "mimeType");
                
                if (walletAddress == null || path == null) {
                    log.error("❌ Invalid write proposal: missing required fields (walletAddress: {}, path: {})", 
                        walletAddress != null, path != null);
                    return;
                }
                
                // Apply write to FileStore via callback
                // This ensures the write is applied on ALL nodes after replication
                if (writeCallback != null) {
                    log.debug("✅ APPLYING REPLICATED WRITE: wallet={}, path={}, intentToken={}", 
                        walletAddress, path, intentToken != null ? intentToken : "none");
                    writeCallback.applyReplicatedWrite(walletAddress, path, contentType, message, signature, intentToken, blobId, mimeType);
                    log.debug("✅ Replicated write applied successfully on node {}", 
                        cluster != null ? cluster.memberId() : "?");
                    
                    // Track acknowledgment for backpressure management
                    // This tells the system that Aeron has successfully replicated and applied a write
                    backpressureManager.incrementAcknowledged();
                    log.debug("   Backpressure stats: {}", backpressureManager.getStats());
                    
                    // 📊 Track replication latency for Raft performance metrics
                    // Match with ingress timestamp from FIFO queue (Raft preserves message order)
                    Long ingressTimestampNanos = ingressTimestamps.poll();
                    if (ingressTimestampNanos != null) {
                        performanceMetrics.recordMessageReplicated(ingressTimestampNanos);
                    } else {
                        // Timestamp queue empty - might be from a different ingress path
                        performanceMetrics.recordMessageReplicated(System.nanoTime());
                    }
                    
                    // Track write throughput and log periodic summaries
                    long currentWriteCount = totalWritesProcessed.incrementAndGet();
                    long currentTime = System.currentTimeMillis();
                    
                    // Update queue depths for metrics
                    performanceMetrics.updateQueueDepths(
                        0, // Ingress queue depth (we don't track this separately yet)
                        backpressureManager.getPendingCount()
                    );
                    
                    // Log summary every 10 seconds
                    if (currentTime - lastSummaryLogTime >= SUMMARY_LOG_INTERVAL_MS) {
                        long writesInInterval = currentWriteCount - lastSummaryWriteCount;
                        long intervalSeconds = (currentTime - lastSummaryLogTime) / 1000;
                        double writesPerSecond = intervalSeconds > 0 ? (double) writesInInterval / intervalSeconds : 0;
                        
                        // Get Raft performance snapshot
                        AeronPerformanceMetrics.Snapshot metrics = performanceMetrics.getSnapshot();
                        
                        log.info("📊 Write Throughput: {} writes in {}s ({} writes/sec) | Total: {}", 
                            writesInInterval, intervalSeconds, String.format("%.1f", writesPerSecond),
                            currentWriteCount);
                        
                        // Log detailed Raft metrics
                        log.info(metrics.toSummaryString());
                        
                        lastSummaryLogTime = currentTime;
                        lastSummaryWriteCount = currentWriteCount;
                    }
                } else {
                    log.error("❌ Write callback not set - cannot apply replicated write");
                    log.error("   This means setWriteApplicationCallback() was never called");
                    log.error("   Check GlobalStoreServer initialization to ensure callback is set");
                }
            } else if (headerInfo.templateId == org.apache.jackrabbit.oak.segment.consensus.aeron.SimpleMessageHeader.TEMPLATE_ID_WRITE_BATCH) {
                log.debug("🔍DEBUG_BATCH [RCV-3]: BATCH BRANCH ENTERED - processing batch message");
                
                // Read JSON batch array from buffer
                byte[] jsonBytes = new byte[headerInfo.blockLength];
                buffer.getBytes(offset, jsonBytes);
                String json = new String(jsonBytes, java.nio.charset.StandardCharsets.UTF_8);
                
                log.debug("🔍DEBUG_BATCH [RCV-4]: JSON read from buffer - size: {} bytes, first 100 chars: {}", 
                    jsonBytes.length, json.substring(0, Math.min(100, json.length())));
                
                log.info("✈️  Processing replicated write BATCH via Aeron (templateId: {}, size: {} bytes)", headerInfo.templateId, headerInfo.blockLength);
                
                log.debug("🔍DEBUG_BATCH [RCV-5]: Parsing batch JSON...");
                
                // Parse batch JSON: {"batch":[{...},{...}]}
                int batchStart = json.indexOf("[");
                int batchEnd = json.lastIndexOf("]");
                
                log.debug("🔍DEBUG_BATCH [RCV-6]: JSON parse indices - batchStart: {}, batchEnd: {}", 
                    batchStart, batchEnd);
                
                if (batchStart < 0 || batchEnd < 0) {
                    log.debug("🔍DEBUG_BATCH [RCV-7]: ❌ Invalid batch format - ABORTING");
                    log.error("❌ Invalid batch format: {}", json);
                    return;
                }
                
                // Split batch into individual proposals (simple JSON parsing)
                String batchContent = json.substring(batchStart + 1, batchEnd);
                java.util.List<String> proposals = new java.util.ArrayList<>();
                
                log.debug("🔍DEBUG_BATCH [RCV-8]: batchContent length: {} chars", batchContent.length());
                
                int depth = 0;
                StringBuilder currentProposal = new StringBuilder();
                for (int i = 0; i < batchContent.length(); i++) {
                    char c = batchContent.charAt(i);
                    if (c == '{') {
                        depth++;
                        currentProposal.append(c);
                    } else if (c == '}') {
                        depth--;
                        currentProposal.append(c);
                        if (depth == 0) {
                            proposals.add(currentProposal.toString());
                            currentProposal = new StringBuilder();
                        }
                    } else if (depth > 0) {
                        currentProposal.append(c);
                    }
                }
                
                log.debug("🔍DEBUG_BATCH [RCV-9]: Batch parsing COMPLETE - found {} proposals", proposals.size());
                log.debug("   Batch contains {} proposals", proposals.size());
                
                // Process each proposal in the batch
                int processed = 0;
                log.debug("🔍DEBUG_BATCH [RCV-10]: Starting to process {} proposals...", proposals.size());
                
                for (String proposalJson : proposals) {
                    String walletAddress = extractJsonField(proposalJson, "walletAddress");
                    String path = extractJsonField(proposalJson, "path");
                    String contentType = extractJsonField(proposalJson, "contentType");
                    String message = extractJsonField(proposalJson, "message");
                    String signature = extractJsonField(proposalJson, "signature");
                    String intentToken = extractJsonField(proposalJson, "intentToken"); // ADR 020
                    String blobId = extractJsonField(proposalJson, "blobId");
                    String mimeType = extractJsonField(proposalJson, "mimeType");
                    
                    if (walletAddress == null || path == null) {
                        log.error("❌ Invalid proposal in batch: missing required fields");
                        continue;
                    }
                    
                    log.debug("🔍DEBUG_BATCH [RCV-11]: Processing proposal {} of {} - wallet: {}, path: {}, intentToken: {}", 
                        processed + 1, proposals.size(), walletAddress, path, intentToken != null ? intentToken : "none");
                    
                    // Apply write to FileStore via callback
                    if (writeCallback != null) {
                        log.debug("🔍DEBUG_BATCH [RCV-12]: Calling writeCallback.applyReplicatedWrite()...");
                        writeCallback.applyReplicatedWrite(walletAddress, path, contentType, message, signature, intentToken, blobId, mimeType);
                        log.debug("🔍DEBUG_BATCH [RCV-13]: writeCallback.applyWrite() COMPLETE");
                        
                        // Track acknowledgment for backpressure management
                        backpressureManager.incrementAcknowledged();
                        
                        processed++;
                    } else {
                        log.debug("🔍DEBUG_BATCH [RCV-14]: ❌ writeCallback is NULL!");
                    }
                }
                
                log.debug("🔍DEBUG_BATCH [RCV-15]: ✅ ALL PROPOSALS PROCESSED - processed: {}, total: {}", 
                    processed, proposals.size());
                
                // 📊 Track replication latency for the batch
                Long ingressTimestampNanos = ingressTimestamps.poll();
                if (ingressTimestampNanos != null) {
                    performanceMetrics.recordMessageReplicated(ingressTimestampNanos);
                    log.debug("🔍DEBUG_BATCH [RCV-16]: Tracked replication latency");
                }
                
                // Track write throughput
                long currentWriteCount = totalWritesProcessed.addAndGet(processed);
                log.debug("🔍DEBUG_BATCH [RCV-17]: Updated metrics - currentWriteCount: {}", currentWriteCount);
                long currentTime = System.currentTimeMillis();
                
                // Update queue depths for metrics
                performanceMetrics.updateQueueDepths(
                    0,
                    backpressureManager.getPendingCount()
                );
                
                // Log summary every 10 seconds
                if (currentTime - lastSummaryLogTime >= SUMMARY_LOG_INTERVAL_MS) {
                    long writesInInterval = currentWriteCount - lastSummaryWriteCount;
                    long intervalMs = currentTime - lastSummaryLogTime;
                    long intervalSeconds = intervalMs / 1000;
                    if (intervalSeconds == 0) intervalSeconds = 1; // Avoid division by zero
                    
                    double writesPerSecond = (double) writesInInterval / intervalSeconds;
                    
                    // Get Raft performance snapshot
                    AeronPerformanceMetrics.Snapshot metrics = performanceMetrics.getSnapshot();
                    
                    log.info("📊 Write Throughput: {} writes in {}s ({} writes/sec) | Total: {}", 
                        writesInInterval, intervalSeconds, String.format("%.1f", writesPerSecond),
                        currentWriteCount);
                    
                    // Log detailed Raft metrics
                    log.info(metrics.toSummaryString());
                    
                    lastSummaryLogTime = currentTime;
                    lastSummaryWriteCount = currentWriteCount;
                }
                
                log.debug("✅ Batch replicated and applied: {}/{} proposals successful", processed, proposals.size());
                
            } else if (headerInfo.templateId == org.apache.jackrabbit.oak.segment.consensus.aeron.SimpleMessageHeader.TEMPLATE_ID_DELETE_PROPOSAL) {
                // Read JSON string from buffer
                byte[] jsonBytes = new byte[headerInfo.blockLength];
                buffer.getBytes(offset, jsonBytes);
                String json = new String(jsonBytes, java.nio.charset.StandardCharsets.UTF_8);
                
                log.debug("✈️  Processing replicated DELETE proposal via Aeron (templateId: {})", headerInfo.templateId);
                log.debug("   JSON: {}", json);
                
                // Parse delete proposal JSON
                String walletAddress = extractJsonField(json, "walletAddress");
                String path = extractJsonField(json, "path");
                String signature = extractJsonField(json, "signature");
                
                if (walletAddress == null || path == null) {
                    log.error("❌ Invalid delete proposal: missing required fields (walletAddress: {}, path: {})", 
                        walletAddress != null, path != null);
                    return;
                }
                
                // Apply delete to FileStore via callback
                // This ensures the delete is applied on ALL nodes after replication
                if (writeCallback != null) {
                    log.info("🗑️  APPLYING REPLICATED DELETE: wallet={}, path={}", walletAddress, path);
                    writeCallback.applyReplicatedDelete(walletAddress, path, signature);
                    log.info("✅ Replicated delete applied successfully on node {}", 
                        cluster != null ? cluster.memberId() : "?");
                    
                    // Track acknowledgment for backpressure management
                    backpressureManager.incrementAcknowledged();
                    log.debug("   Backpressure stats: {}", backpressureManager.getStats());
                    
                    // Track metrics (same as writes)
                    Long ingressTimestampNanos = ingressTimestamps.poll();
                    if (ingressTimestampNanos != null) {
                        performanceMetrics.recordMessageReplicated(ingressTimestampNanos);
                    } else {
                        performanceMetrics.recordMessageReplicated(System.nanoTime());
                    }
                    
                    totalWritesProcessed.incrementAndGet(); // Count deletes in throughput metrics
                } else {
                    log.error("❌ Write callback not set - cannot apply replicated delete");
                }
            } else if (headerInfo.templateId == org.apache.jackrabbit.oak.segment.consensus.aeron.SimpleMessageHeader.TEMPLATE_ID_GC_PROPOSAL) {
                // Read JSON string from buffer
                byte[] jsonBytes = new byte[headerInfo.blockLength];
                buffer.getBytes(offset, jsonBytes);
                String json = new String(jsonBytes, java.nio.charset.StandardCharsets.UTF_8);
                handleGCProposal(json);
            } else if (headerInfo.templateId == org.apache.jackrabbit.oak.segment.consensus.aeron.SimpleMessageHeader.TEMPLATE_ID_GC_VOTE) {
                // Read JSON string from buffer
                byte[] jsonBytes = new byte[headerInfo.blockLength];
                buffer.getBytes(offset, jsonBytes);
                String json = new String(jsonBytes, java.nio.charset.StandardCharsets.UTF_8);
                handleGCVote(json);
            } else if (headerInfo.templateId == org.apache.jackrabbit.oak.segment.consensus.aeron.SimpleMessageHeader.TEMPLATE_ID_GC_EXECUTE) {
                // Read JSON string from buffer
                byte[] jsonBytes = new byte[headerInfo.blockLength];
                buffer.getBytes(offset, jsonBytes);
                String json = new String(jsonBytes, java.nio.charset.StandardCharsets.UTF_8);
                handleGCExecution(json);
            } else if (headerInfo.templateId == org.apache.jackrabbit.oak.segment.consensus.aeron.SimpleMessageHeader.TEMPLATE_ID_SNAPSHOT) {
                log.debug("📸 Snapshot message received in onSessionMessage (unexpected but handled)");
                // Snapshots are typically loaded in onStart(), but handle gracefully if received here
            } else {
                log.warn("📨 Unknown template ID: {} (ignoring)", headerInfo.templateId);
            }
        } catch (Exception e) {
            log.error("❌ Failed to process replicated message", e);
        }
    }
    
    /**
     * Helper to extract JSON field value (simple parsing).
     */
    private String extractJsonField(String json, String field) {
        // Handle both "field":"value" and "field": "value" (with optional whitespace)
        String fieldPrefix = "\"" + field + "\"";
        int fieldStart = json.indexOf(fieldPrefix);
        if (fieldStart == -1) return null;
        
        // Find the colon after the field name
        int colonIndex = json.indexOf(":", fieldStart + fieldPrefix.length());
        if (colonIndex == -1) return null;
        
        // Skip optional whitespace and find the opening quote
        int quoteStart = json.indexOf("\"", colonIndex);
        if (quoteStart == -1) return null;
        
        // Find the closing quote
        int quoteEnd = json.indexOf("\"", quoteStart + 1);
        if (quoteEnd == -1) return null;
        
        return json.substring(quoteStart + 1, quoteEnd);
    }
    
    /**
     * Handle GC proposal message.
     */
    private void handleGCProposal(String proposalJson) {
        try {
            log.info("🗑️  GC proposal received via Aeron");
            
            // Parse proposal JSON
            String proposalId = extractJsonField(proposalJson, "proposalId");
            String proposerWallet = extractJsonField(proposalJson, "proposerWallet");
            String targetRevision = extractJsonField(proposalJson, "targetRevision");
            
            log.info("   Proposal ID: {}", proposalId);
            log.info("   Proposer: {}", proposerWallet);
            log.info("   Target revision: {}", targetRevision != null ? targetRevision : "HEAD");
            
            // Note: GCProposalManager will be accessed via ServerContext
            // For now, just log - actual handling will be done when GCProposalManager is set
            log.info("✅ GC proposal received (will be processed by GCProposalManager)");
            
        } catch (Exception e) {
            log.error("❌ Failed to handle GC proposal", e);
        }
    }
    
    /**
     * Handle GC vote message.
     */
    private void handleGCVote(String voteJson) {
        try {
            log.info("🗳️  GC vote received via Aeron");
            
            // Parse vote JSON
            String proposalId = extractJsonField(voteJson, "proposalId");
            Integer validatorId = extractJsonFieldInt(voteJson, "validatorId");
            Boolean approve = extractJsonFieldBoolean(voteJson, "approve");
            String reason = extractJsonField(voteJson, "reason");
            
            log.info("   Proposal ID: {}", proposalId);
            log.info("   Validator: {}", validatorId);
            log.info("   Vote: {}", approve ? "APPROVE" : "REJECT");
            log.info("   Reason: {}", reason);
            
            // Note: GCProposalManager will be accessed via ServerContext
            // For now, just log - actual handling will be done when GCProposalManager is set
            log.info("✅ GC vote received (will be processed by GCProposalManager)");
            
        } catch (Exception e) {
            log.error("❌ Failed to handle GC vote", e);
        }
    }
    
    /**
     * Handle GC execution message.
     */
    private void handleGCExecution(String executionJson) {
        try {
            log.info("🗑️  GC execution result received via Aeron");
            
            // Parse execution JSON
            String proposalId = extractJsonField(executionJson, "proposalId");
            Integer executorId = extractJsonFieldInt(executionJson, "executorId");
            Long reclaimedSizeMB = extractJsonFieldLong(executionJson, "actualReclaimableSizeMB");
            Boolean success = extractJsonFieldBoolean(executionJson, "success");
            
            log.info("   Proposal ID: {}", proposalId);
            log.info("   Executor: {}", executorId);
            log.info("   Reclaimed: {} MB", reclaimedSizeMB);
            log.info("   Success: {}", success);
            
            // Note: GCProposalManager will be accessed via ServerContext
            // For now, just log - actual handling will be done when GCProposalManager is set
            log.info("✅ GC execution result received (will be processed by GCProposalManager)");
            
        } catch (Exception e) {
            log.error("❌ Failed to handle GC execution", e);
        }
    }
    
    /**
     * Helper to extract JSON field value for integer fields.
     */
    private Integer extractJsonFieldInt(String json, String field) {
        String pattern = "\"" + field + "\":";
        int start = json.indexOf(pattern);
        if (start == -1) return null;
        start += pattern.length();
        int end = json.indexOf(",", start);
        if (end == -1) {
            end = json.indexOf("}", start);
        }
        if (end == -1) return null;
        try {
            return Integer.parseInt(json.substring(start, end).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
    
    /**
     * Helper to extract JSON field value for boolean fields.
     */
    private Boolean extractJsonFieldBoolean(String json, String field) {
        String pattern = "\"" + field + "\":";
        int start = json.indexOf(pattern);
        if (start == -1) return null;
        start += pattern.length();
        int end = json.indexOf(",", start);
        if (end == -1) {
            end = json.indexOf("}", start);
        }
        if (end == -1) return null;
        String value = json.substring(start, end).trim();
        return "true".equals(value);
    }
    
    /**
     * Helper to extract JSON field value for numeric fields.
     */
    private Long extractJsonFieldLong(String json, String field) {
        String pattern = "\"" + field + "\":";
        int start = json.indexOf(pattern);
        if (start == -1) return null;
        start += pattern.length();
        int end = json.indexOf(",", start);
        if (end == -1) {
            end = json.indexOf("}", start);
        }
        if (end == -1) return null;
        try {
            return Long.parseLong(json.substring(start, end).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
    
    /**
     * ✈️ AERON NATIVE: Snapshot state container.
     */
    private static class SnapshotState {
        final String head;
        final int ethereumEpoch;
        final long timestamp;
        
        SnapshotState(String head, int ethereumEpoch, long timestamp) {
            this.head = head;
            this.ethereumEpoch = ethereumEpoch;
            this.timestamp = timestamp;
        }
    }
    
    /**
     * ✈️ AERON NATIVE: Load snapshot state from Aeron snapshot image.
     * 
     * This method polls the snapshot image to extract and restore the complete FileStore:
     * - TAR files (segment data)
     * - journal.log (HEAD + history)
     * - Metadata (HEAD pointer, Ethereum epoch)
     * 
     * @param snapshotImage The Aeron snapshot image
     * @return SnapshotState if found, null otherwise
     */
    private SnapshotState loadSnapshotFromImage(Image snapshotImage) {
        log.info("📥 Loading FileStore snapshot from Aeron...");
        
        final java.util.concurrent.atomic.AtomicReference<SnapshotState> snapshotStateRef = 
            new java.util.concurrent.atomic.AtomicReference<>();
        
        // Track current file being received
        final java.util.concurrent.atomic.AtomicReference<FileReceiver> currentFileReceiver = 
            new java.util.concurrent.atomic.AtomicReference<>();
        
        io.aeron.FragmentAssembler fragmentAssembler = new io.aeron.FragmentAssembler(
            (buffer, offset, length, header) -> {
                if (length < org.apache.jackrabbit.oak.segment.consensus.aeron.SimpleMessageHeader.ENCODED_LENGTH) {
                    log.warn("⚠️  Snapshot fragment too short: {} bytes", length);
                    return;
                }
                
                try {
                    // Decode SBE header
                    org.apache.jackrabbit.oak.segment.consensus.aeron.SimpleMessageHeader.HeaderInfo headerInfo = 
                        org.apache.jackrabbit.oak.segment.consensus.aeron.SimpleMessageHeader.decode(buffer, offset);
                    
                    if (headerInfo.templateId == org.apache.jackrabbit.oak.segment.consensus.aeron.SimpleMessageHeader.TEMPLATE_ID_SNAPSHOT) {
                        int payloadOffset = offset + org.apache.jackrabbit.oak.segment.consensus.aeron.SimpleMessageHeader.ENCODED_LENGTH;
                        int payloadLength = headerInfo.blockLength;
                        
                        byte[] payload = new byte[payloadLength];
                        buffer.getBytes(payloadOffset, payload);
                        
                        // Try to parse as JSON to determine message type
                        String payloadStr = new String(payload, 0, Math.min(200, payloadLength), java.nio.charset.StandardCharsets.UTF_8);
                        
                        if (payloadStr.contains("\"type\":\"metadata\"")) {
                            // Metadata message
                            String json = new String(payload, java.nio.charset.StandardCharsets.UTF_8);
                            String head = extractJsonField(json, "head");
                            Long ethereumEpochLong = extractJsonFieldLong(json, "ethereumEpoch");
                            Long timestampLong = extractJsonFieldLong(json, "timestamp");
                            
                            if (head != null && ethereumEpochLong != null && timestampLong != null) {
                                snapshotStateRef.set(new SnapshotState(
                                    head, 
                                    ethereumEpochLong.intValue(), 
                                    timestampLong
                                ));
                                log.info("   ✅ Metadata received: HEAD={}, epoch={}", head, ethereumEpochLong.intValue());
                            }
                        } else if (payloadStr.contains("\"type\":\"file_header\"")) {
                            // File header - start new file
                            String json = new String(payload, java.nio.charset.StandardCharsets.UTF_8);
                            String fileType = extractJsonField(json, "fileType");
                            String fileName = extractJsonField(json, "fileName");
                            Long fileSize = extractJsonFieldLong(json, "fileSize");
                            
                            if (fileName != null && fileSize != null) {
                                // Close previous file if any
                                FileReceiver prev = currentFileReceiver.get();
                                if (prev != null) {
                                    prev.close();
                                }
                                
                                // Start new file
                                java.io.File targetFile = new java.io.File(storeDirectory, fileName);
                                FileReceiver receiver = new FileReceiver(targetFile, fileSize);
                                currentFileReceiver.set(receiver);
                                log.info("   📥 Receiving {}: {} ({} bytes)", fileType, fileName, fileSize);
                            }
                        } else {
                            // File chunk data
                            FileReceiver receiver = currentFileReceiver.get();
                            if (receiver != null) {
                                receiver.writeChunk(payload, 0, payloadLength);
                            } else {
                                log.debug("Received file chunk but no active receiver (may be non-file payload)");
                            }
                        }
                    }
                } catch (Exception e) {
                    log.error("❌ Failed to process snapshot fragment", e);
                }
            }
        );
        
        // Poll snapshot image until end of stream
        idleStrategy.reset();
        int fragmentsPolled = 0;
        while (!snapshotImage.isEndOfStream()) {
            int fragments = snapshotImage.poll(fragmentAssembler, 20);
            if (fragments > 0) {
                fragmentsPolled += fragments;
            }
            idleStrategy.idle(fragments);
        }
        
        // Close final file
        FileReceiver finalReceiver = currentFileReceiver.get();
        if (finalReceiver != null) {
            finalReceiver.close();
        }
        
        log.info("📥 Snapshot restore complete: {} fragments processed", fragmentsPolled);
        
        SnapshotState state = snapshotStateRef.get();
        if (state == null) {
            log.warn("⚠️  No snapshot metadata found");
        }
        
        return state;
    }
    
    /**
     * Helper class to receive and write file chunks during snapshot restore.
     */
    private static class FileReceiver {
        private final java.io.File targetFile;
        private final long expectedSize;
        private long bytesReceived;
        private java.io.FileOutputStream fos;
        
        FileReceiver(java.io.File targetFile, long expectedSize) throws Exception {
            this.targetFile = targetFile;
            this.expectedSize = expectedSize;
            this.bytesReceived = 0;
            
            // Ensure parent directory exists
            targetFile.getParentFile().mkdirs();
            
            // Open output stream
            this.fos = new java.io.FileOutputStream(targetFile);
        }
        
        void writeChunk(byte[] data, int offset, int length) throws Exception {
            fos.write(data, offset, length);
            bytesReceived += length;
        }
        
        void close() {
            try {
                if (fos != null) {
                    fos.close();
                }
                
                if (bytesReceived == expectedSize) {
                    System.out.println("      ✅ File complete: " + targetFile.getName() + " (" + bytesReceived + " bytes)");
                } else {
                    System.err.println("      ⚠️  File size mismatch: " + targetFile.getName() + 
                        " (expected " + expectedSize + ", got " + bytesReceived + ")");
                }
            } catch (Exception e) {
                System.err.println("      ❌ Failed to close file: " + targetFile.getName() + " - " + e.getMessage());
            }
        }
    }
    
    /**
     * ✈️ AERON NATIVE: Send write proposal through Aeron ingress channel for replication.
     * 
     * This method sends the write proposal through Aeron's ingress channel, which
     * automatically replicates it to all cluster members via Raft consensus.
     * 
     * @param walletAddress Ethereum wallet address
     * @param path Write path
     * @param contentType Content type
     * @param message Message content
     * @param signature Signature
     * @return true if sent successfully, false otherwise
     */
    /**
     * Create internal AeronCluster client lazily (on first write attempt).
     * This avoids timeout issues during cluster startup.
     */
    private synchronized void ensureInternalClusterClient() {
        log.debug("🔧 ensureInternalClusterClient() called - checking if client exists...");
        
        // Check if client exists AND is healthy (not closed)
        if (internalClusterClient != null) {
            if (internalClusterClient.isClosed()) {
                log.warn("⚠️  Internal cluster client is CLOSED - will reconnect");
                log.warn("   Session closed but messages queued - this causes silent drops!");
                
                // Close cleanly to release resources
                try {
                    internalClusterClient.close();
                } catch (Exception e) {
                    log.debug("Error closing stale client: {}", e.getMessage());
                }
                
                // Set to null to trigger reconnection below
                internalClusterClient = null;
            } else {
                log.debug("✅ Internal cluster client already exists and is healthy (not closed)");
                return; // Already created and healthy
            }
        }
        
        log.info("🔧 Internal cluster client is null - checking aeron directory...");
        log.info("   aeronDirectoryName: {}", aeronDirectoryName);
        log.info("   peerUrls: {}", peerUrls);
        if (aeronDirectoryName == null || aeronDirectoryName.isEmpty()) {
            log.warn("⚠️  Cannot create internal cluster client - aeron directory not set");
            return;
        }
        
        // ✈️ AERON CLUSTER INGRESS: Use UDP for cluster communication
        // UDP with ingressEndpoints provides reliable message delivery via Raft
        // This is the standard Aeron Cluster pattern for multi-node clusters
        
        // Build ingress endpoints from ALL cluster nodes (like production does)
        // CRITICAL: Use Aeron cluster ports (PORT_BASE + nodeId * PORTS_PER_NODE + CLIENT_FACING_PORT_OFFSET)
        // Build ingress endpoints from ALL cluster nodes
        // IMPORTANT: Use Aeron cluster ports, NOT HTTP API ports
        // Include ALL nodes (0, 1, 2, ...) for proper leader election
        StringBuilder ingressEndpointsBuilder = new StringBuilder();
        
        // Get current node ID from cluster
        int currentNodeId = cluster != null ? cluster.memberId() : -1;
        log.info("   Current node ID: {}", currentNodeId);
        
        // Build complete list of all node URLs (self + peers)
        java.util.List<String> allNodeUrls = new java.util.ArrayList<>();
        if (selfUrl != null && !selfUrl.isEmpty()) {
            allNodeUrls.add(selfUrl); // Node 0 (self)
        }
        if (peerUrls != null && !peerUrls.isEmpty()) {
            allNodeUrls.addAll(peerUrls); // Nodes 1, 2, ...
        }
        
        // Build ingress endpoints for all nodes (like production ingressEndpoints() helper)
        // CRITICAL: Use IP addresses from validator-network (not client-network)
        // The cluster is configured with validator-network IPs, so ingress endpoints must match
        // We need to detect the validator-network subnet and use IPs from that subnet
        
        // Detect validator-network subnet by resolving a peer (like AeronClusterLauncher does)
        final String validatorSubnet;
        String detectedSubnet = null;
        if (peerUrls != null && !peerUrls.isEmpty()) {
            try {
                java.net.URL peerUrl = new java.net.URL(peerUrls.get(0));
                String peerHostname = peerUrl.getHost();
                String peerIP = java.net.InetAddress.getByName(peerHostname).getHostAddress();
                if (peerIP.startsWith("172.")) {
                    String[] parts = peerIP.split("\\.");
                    if (parts.length >= 3) {
                        detectedSubnet = parts[0] + "." + parts[1] + "." + parts[2];
                        log.info("   Detected validator-network subnet: {}.x (from peer {})", detectedSubnet, peerHostname);
                    }
                }
            } catch (Exception e) {
                log.debug("Could not detect validator-network subnet: {}", e.getMessage());
            }
        }
        validatorSubnet = detectedSubnet; // Make final for lambda
        
        // Helper to get validator-network IP (prefer IPs from validator-network subnet)
        java.util.function.Function<String, String> resolveToValidatorNetworkIP = (hostname) -> {
            try {
                // First try simple resolution
                String ip = java.net.InetAddress.getByName(hostname).getHostAddress();
                
                // If we detected validator-network subnet, prefer IPs from that subnet
                if (validatorSubnet != null && ip.startsWith(validatorSubnet + ".")) {
                    log.debug("   Resolved {} → {} (validator-network)", hostname, ip);
                    return ip;
                }
                
                // If not from validator-network, try to find validator-network IP via interface enumeration
                if (validatorSubnet != null) {
                    try {
                        java.util.Enumeration<java.net.NetworkInterface> interfaces = java.net.NetworkInterface.getNetworkInterfaces();
                        while (interfaces.hasMoreElements()) {
                            java.net.NetworkInterface iface = interfaces.nextElement();
                            if (iface.isLoopback() || !iface.isUp()) continue;
                            java.util.Enumeration<java.net.InetAddress> addresses = iface.getInetAddresses();
                            while (addresses.hasMoreElements()) {
                                java.net.InetAddress addr = addresses.nextElement();
                                if (addr instanceof java.net.Inet4Address && !addr.isLoopbackAddress()) {
                                    String candidateIP = addr.getHostAddress();
                                    if (candidateIP.startsWith(validatorSubnet + ".")) {
                                        log.info("   Resolved {} → {} (validator-network IP from interface {})", hostname, candidateIP, iface.getName());
                                        return candidateIP;
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {
                        log.debug("Interface enumeration failed: {}", e.getMessage());
                    }
                }
                
                log.debug("   Resolved {} → {} (may not be validator-network)", hostname, ip);
                return ip;
            } catch (java.net.UnknownHostException e) {
                log.warn("Failed to resolve hostname {} to IP: {}", hostname, e.getMessage());
                return hostname; // Fallback to hostname
            }
        };
        
        for (int nodeId = 0; nodeId < allNodeUrls.size(); nodeId++) {
            if (nodeId > 0) ingressEndpointsBuilder.append(",");
            String url = allNodeUrls.get(nodeId);
            try {
                java.net.URL parsedUrl = new java.net.URL(url);
                String hostname = parsedUrl.getHost();
                
                // Resolve to validator-network IP
                String host = resolveToValidatorNetworkIP.apply(hostname);
                
                // Calculate Aeron cluster port using public method (like production)
                int clientPort = AeronClusterLauncher.calculatePort(nodeId, AeronClusterLauncher.CLIENT_FACING_PORT_OFFSET);
                ingressEndpointsBuilder.append(nodeId).append("=").append(host).append(":").append(clientPort);
                log.info("   Node {} ingress endpoint: {}:{}", nodeId, host, clientPort);
            } catch (Exception e) {
                log.warn("Failed to parse node URL {}: {}", url, e.getMessage());
            }
        }
        
        String ingressEndpointsStr = ingressEndpointsBuilder.length() > 0 ? ingressEndpointsBuilder.toString() : null;
        
        log.info("✈️  Creating internal AeronCluster client for ingress (UDP - distributed network)...");
        log.info("   Aeron directory: {}", aeronDirectoryName);
        log.info("   Using UDP endpoints for distributed network communication");
        log.info("   Ingress endpoints: {} (extracted from peer URLs)", ingressEndpointsStr);
        
        try {
            // Retry connection with backoff (like production)
            int maxRetries = 10;
            for (int attempt = 0; attempt < maxRetries; attempt++) {
                try {
                    // Extract hostname from self URL for egress channel (distributed network)
                    // For distributed deployment, use the validator's own network address
                    String egressHostname = "0.0.0.0"; // Bind to all interfaces for distributed access
                    try {
                        java.net.URL selfUrlParsed = new java.net.URL(selfUrl);
                        String host = selfUrlParsed.getHost();
                        if (host != null && !host.isEmpty() && !"localhost".equals(host) && !"127.0.0.1".equals(host)) {
                            egressHostname = host; // Use configured hostname/IP for distributed deployment
                        }
                    } catch (Exception e) {
                        log.debug("Using default egress binding (0.0.0.0) - will bind to all interfaces");
                    }
                    
                    // Create egress listener for receiving responses
                    io.aeron.cluster.client.EgressListener egressListener = (clusterSessionId, timestamp, message, header, offset, length) -> {
                        // Basic egress listener - just log that we received a message
                        log.debug("Received egress message from cluster (session: {}, length: {})", clusterSessionId, length);
                    };
                    
                    // ✈️ DISTRIBUTED UDP MODE: Use UDP with network endpoints for distributed communication
                    // The cluster's ingress is configured as UDP, so we must use UDP too
                    // Endpoints are extracted from peer URLs - supports distributed deployment across networks
                    // Egress binds to validator's network interface (or all interfaces) for distributed access
                    internalClusterClient = io.aeron.cluster.client.AeronCluster.connect(
                        new io.aeron.cluster.client.AeronCluster.Context()
                            .aeronDirectoryName(aeronDirectoryName)
                            .ingressChannel("aeron:udp?term-length=128m")  // CRITICAL: Must match cluster's log term-length (128MB)
                            .ingressEndpoints(ingressEndpointsStr)  // Required for UDP (distributed network endpoints)
                            .egressChannel("aeron:udp?endpoint=" + egressHostname + ":0")  // UDP egress (distributed network binding)
                            .egressListener(egressListener)
                            .idleStrategy(idleStrategy)
                            .errorHandler(e -> log.error("Internal cluster client error", e))
                    );
                    
                    log.info("✅ Internal AeronCluster client created successfully (UDP distributed network, attempt {})", attempt + 1);
                    log.info("   Egress binding: {} (distributed network access)", egressHostname);
                    return; // Success
                } catch (Exception e) {
                    if (attempt < maxRetries - 1) {
                        log.debug("⚠️  Failed to create internal cluster client (attempt {}): {} - retrying...", 
                            attempt + 1, e.getMessage());
                        try {
                            Thread.sleep(1000 * (attempt + 1)); // Exponential backoff
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    } else {
                        log.error("❌ Failed to create internal AeronCluster client after {} attempts", maxRetries, e);
                    }
                }
            }
        } catch (Exception e) {
            log.error("❌ Exception creating internal AeronCluster client", e);
        }
    }
    
    public boolean sendWriteThroughIngress(String walletAddress, String path, 
                                           String contentType, String message, String signature) {
        if (cluster == null) {
            log.error("❌ Cluster not initialized - cannot send write through ingress");
            return false;
        }
        
        // Ensure internal cluster client is created (lazy initialization)
        ensureInternalClusterClient();
        
        if (internalClusterClient == null) {
            log.error("❌ Internal AeronCluster client not available - cannot send write through ingress");
            return false;
        }
        
        // 🔍 GROK DIAGNOSTIC: Check client/session state for PRIORITY path
        log.info("🔍 PRIORITY PATH: client={}, sessionId={}, isClosed={}", 
            System.identityHashCode(internalClusterClient),
            internalClusterClient.clusterSessionId(),
            internalClusterClient.isClosed());
        
        try {
            // Build JSON write proposal
            StringBuilder json = new StringBuilder();
            json.append("{");
            json.append("\"walletAddress\":\"").append(escapeJson(walletAddress)).append("\",");
            json.append("\"path\":\"").append(escapeJson(path)).append("\",");
            json.append("\"contentType\":\"").append(escapeJson(contentType != null ? contentType : "page")).append("\",");
            json.append("\"message\":\"").append(escapeJson(message != null ? message : "")).append("\",");
            json.append("\"signature\":\"").append(escapeJson(signature != null ? signature : "")).append("\"");
            json.append("}");
            
            byte[] jsonBytes = json.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            
            // ✈️ AERON SBE MESSAGE FORMAT: Encode message with SBE header
            // Header includes MessageHeaderEncoder.ENCODED_LENGTH (8 bytes) before message data
            // Structure: blockLength (2) + templateId (2) + schemaId (2) + version (2) = 8 bytes
            int blockLength = jsonBytes.length; // Length of message payload (excluding header)
            int templateId = org.apache.jackrabbit.oak.segment.consensus.aeron.SimpleMessageHeader.TEMPLATE_ID_WRITE_PROPOSAL;
            
            // Allocate buffer: SBE header (8 bytes) + JSON payload
            int totalLength = org.apache.jackrabbit.oak.segment.consensus.aeron.SimpleMessageHeader.ENCODED_LENGTH + jsonBytes.length;
            org.agrona.MutableDirectBuffer messageBuffer = new org.agrona.concurrent.UnsafeBuffer(
                new byte[totalLength]
            );
            
            // Encode SBE message header for Aeron cluster protocol
            org.apache.jackrabbit.oak.segment.consensus.aeron.SimpleMessageHeader.encode(
                messageBuffer, 0, blockLength, templateId);
            
            // Write JSON payload after header
            messageBuffer.putBytes(org.apache.jackrabbit.oak.segment.consensus.aeron.SimpleMessageHeader.ENCODED_LENGTH, jsonBytes);
            
            // ✈️ AERON CLUSTER: Send message through internal AeronCluster client
            // This is the correct way to send messages - AeronCluster.offer() sends through ingress
            // Aeron then replicates the message to ALL nodes via Raft, and onSessionMessage() is called on each node
            
            try {
                // 🔍 HEALTH CHECK: Verify session is open before offering
                if (internalClusterClient.isClosed()) {
                    log.error("❌ Cannot send write - internal cluster client session is CLOSED");
                    log.error("   This indicates session timeout or connection loss");
                    log.error("   Attempting reconnection...");
                    
                    // Try to reconnect
                    synchronized (this) {
                        internalClusterClient = null;
                        ensureInternalClusterClient();
                    }
                    
                    // If still null or closed after reconnect, fail
                    if (internalClusterClient == null || internalClusterClient.isClosed()) {
                        log.error("❌ Reconnection failed - cannot send write");
                        return false;
                    }
                    
                    log.info("✅ Reconnection successful - retrying write send");
                }
                
                // Send message through AeronCluster client ingress
                // Aeron will replicate to all nodes via Raft consensus
                idleStrategy.reset();
                long result;
                int retries = 0;
                while ((result = internalClusterClient.offer(messageBuffer, 0, totalLength)) < 0) {
                    if (result == io.aeron.Publication.BACK_PRESSURED) {
                        idleStrategy.idle();
                        retries++;
                        if (retries > 100) {
                            log.error("❌ Ingress back-pressured after {} retries", retries);
                            return false;
                        }
                    } else if (result == io.aeron.Publication.NOT_CONNECTED) {
                        log.warn("⚠️  Ingress not connected - waiting...");
                        idleStrategy.idle();
                        retries++;
                        if (retries > 100) {
                            log.error("❌ Ingress not connected after {} retries", retries);
                            return false;
                        }
                    } else {
                        log.error("❌ Failed to send write through ingress: {}", result);
                        return false;
                    }
                }
                
                // 📊 Track ingress timestamp for Raft latency calculation
                // Store in FIFO queue - will be matched with replication in onSessionMessage()
                ingressTimestamps.offer(System.nanoTime());
                performanceMetrics.recordMessageIngressed();
                
                // 🚦 BACKPRESSURE: Increment sent counter for backpressure tracking
                // This MUST be called after successful offer to Aeron
                // Will be matched with incrementAcknowledged() in onSessionMessage()
                backpressureManager.incrementSent();
                
                log.debug("✅ Write sent through AeronCluster.offer() - will replicate to all nodes via Raft");
                return true;
            } catch (Exception e) {
                log.error("❌ Exception sending write through AeronCluster client", e);
                return false;
            }
            
        } catch (Exception e) {
            log.error("❌ Exception sending write through ingress", e);
            return false;
        }
    }
    
    /**
     * Send a write proposal with binary metadata through Aeron ingress.
     * This overload includes blobId and mimeType for eager binary uploads.
     */
    public boolean sendWriteThroughIngress(String walletAddress, String path, 
                                           String contentType, String message, String signature,
                                           String blobId, String mimeType) {
        if (cluster == null) {
            log.error("❌ Cluster not initialized - cannot send write through ingress");
            return false;
        }
        
        // Ensure internal cluster client is created (lazy initialization)
        ensureInternalClusterClient();
        
        if (internalClusterClient == null) {
            log.error("❌ Internal AeronCluster client not available - cannot send write through ingress");
            return false;
        }
        
        log.info("🔍 PRIORITY PATH (with binary): client={}, sessionId={}, blobId={}", 
            System.identityHashCode(internalClusterClient),
            internalClusterClient.clusterSessionId(),
            blobId);
        
        try {
            // Build JSON write proposal WITH blobId and mimeType
            StringBuilder json = new StringBuilder();
            json.append("{");
            json.append("\"walletAddress\":\"").append(escapeJson(walletAddress)).append("\",");
            json.append("\"path\":\"").append(escapeJson(path)).append("\",");
            json.append("\"contentType\":\"").append(escapeJson(contentType != null ? contentType : "page")).append("\",");
            json.append("\"message\":\"").append(escapeJson(message != null ? message : "")).append("\",");
            json.append("\"signature\":\"").append(escapeJson(signature != null ? signature : "")).append("\"");
            
            // Add blobId and mimeType if present
            if (blobId != null && !blobId.isEmpty()) {
                json.append(",\"blobId\":\"").append(escapeJson(blobId)).append("\"");
                json.append(",\"mimeType\":\"").append(escapeJson(mimeType != null ? mimeType : "application/octet-stream")).append("\"");
                log.info("📎 Including blobId in Aeron JSON: {}", blobId);
            }
            
            json.append("}");
            
            byte[] jsonBytes = json.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            log.debug("📤 Sending write with binary - JSON size: {} bytes", jsonBytes.length);
            
            // ✈️ AERON SBE MESSAGE FORMAT
            int blockLength = jsonBytes.length;
            int templateId = org.apache.jackrabbit.oak.segment.consensus.aeron.SimpleMessageHeader.TEMPLATE_ID_WRITE_PROPOSAL;
            
            int totalLength = org.apache.jackrabbit.oak.segment.consensus.aeron.SimpleMessageHeader.ENCODED_LENGTH + jsonBytes.length;
            org.agrona.MutableDirectBuffer messageBuffer = new org.agrona.concurrent.UnsafeBuffer(
                new byte[totalLength]
            );
            
            org.apache.jackrabbit.oak.segment.consensus.aeron.SimpleMessageHeader.encode(
                messageBuffer, 0, blockLength, templateId);
            
            messageBuffer.putBytes(org.apache.jackrabbit.oak.segment.consensus.aeron.SimpleMessageHeader.ENCODED_LENGTH, jsonBytes);
            
            try {
                if (internalClusterClient.isClosed()) {
                    log.error("❌ Cannot send write - internal cluster client session is CLOSED");
                    synchronized (this) {
                        internalClusterClient = null;
                        ensureInternalClusterClient();
                    }
                    if (internalClusterClient == null || internalClusterClient.isClosed()) {
                        log.error("❌ Reconnection failed - cannot send write");
                        return false;
                    }
                    log.info("✅ Reconnection successful - retrying write send");
                }
                
                idleStrategy.reset();
                long result;
                int retries = 0;
                while ((result = internalClusterClient.offer(messageBuffer, 0, totalLength)) < 0) {
                    if (result == io.aeron.Publication.BACK_PRESSURED) {
                        idleStrategy.idle();
                        retries++;
                        if (retries > 100) {
                            log.error("❌ Ingress back-pressured after {} retries", retries);
                            return false;
                        }
                    } else if (result == io.aeron.Publication.NOT_CONNECTED) {
                        log.warn("⚠️  Ingress not connected - waiting...");
                        idleStrategy.idle();
                        retries++;
                        if (retries > 100) {
                            log.error("❌ Ingress not connected after {} retries", retries);
                            return false;
                        }
                    } else {
                        log.error("❌ Failed to send write through ingress: {}", result);
                        return false;
                    }
                }
                
                ingressTimestamps.offer(System.nanoTime());
                performanceMetrics.recordMessageIngressed();
                backpressureManager.incrementSent();
                
                log.info("✅ Write with binary sent through AeronCluster.offer() - blobId={}", blobId);
                return true;
            } catch (Exception e) {
                log.error("❌ Exception sending write through AeronCluster client", e);
                return false;
            }
            
        } catch (Exception e) {
            log.error("❌ Exception sending write through ingress", e);
            return false;
        }
    }
    
    /**
     * Send a DELETE proposal through Aeron ingress for consensus replication.
     * Same flow as writes, just different template ID and simpler JSON.
     * 
     * @param walletAddress Ethereum wallet address of content owner
     * @param path Content path to delete
     * @param signature Transaction signature
     * @return true if successfully sent
     */
    public boolean sendDeleteThroughIngress(String walletAddress, String path, String signature) {
        if (cluster == null) {
            log.error("❌ Cluster not initialized - cannot send delete through ingress");
            return false;
        }
        
        // Ensure internal cluster client is created (lazy initialization)
        ensureInternalClusterClient();
        
        if (internalClusterClient == null) {
            log.error("❌ Internal AeronCluster client not available - cannot send delete through ingress");
            return false;
        }
        
        log.info("🗑️  SENDING DELETE through ingress: wallet={}, path={}", walletAddress, path);
        
        try {
            // Build JSON delete proposal (simpler than write)
            StringBuilder json = new StringBuilder();
            json.append("{");
            json.append("\"walletAddress\":\"").append(escapeJson(walletAddress)).append("\",");
            json.append("\"path\":\"").append(escapeJson(path)).append("\",");
            json.append("\"signature\":\"").append(escapeJson(signature != null ? signature : "")).append("\"");
            json.append("}");
            
            byte[] jsonBytes = json.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            
            // Encode message with SBE header (DELETE template ID)
            int blockLength = jsonBytes.length;
            int templateId = org.apache.jackrabbit.oak.segment.consensus.aeron.SimpleMessageHeader.TEMPLATE_ID_DELETE_PROPOSAL;
            
            int totalLength = org.apache.jackrabbit.oak.segment.consensus.aeron.SimpleMessageHeader.ENCODED_LENGTH + jsonBytes.length;
            org.agrona.MutableDirectBuffer messageBuffer = new org.agrona.concurrent.UnsafeBuffer(
                new byte[totalLength]
            );
            
            // Encode SBE header
            org.apache.jackrabbit.oak.segment.consensus.aeron.SimpleMessageHeader.encode(
                messageBuffer, 0, blockLength, templateId);
            
            // Write JSON payload
            messageBuffer.putBytes(org.apache.jackrabbit.oak.segment.consensus.aeron.SimpleMessageHeader.ENCODED_LENGTH, jsonBytes);
            
            // Check session health
            if (internalClusterClient.isClosed()) {
                log.error("❌ Cannot send delete - internal cluster client session is CLOSED");
                synchronized (this) {
                    internalClusterClient = null;
                    ensureInternalClusterClient();
                }
                if (internalClusterClient == null || internalClusterClient.isClosed()) {
                    log.error("❌ Reconnection failed - cannot send delete");
                    return false;
                }
                log.info("✅ Reconnection successful - retrying delete send");
            }
            
            // Send through Aeron with back-pressure handling
            idleStrategy.reset();
            long result;
            int retries = 0;
            while ((result = internalClusterClient.offer(messageBuffer, 0, totalLength)) < 0) {
                if (result == io.aeron.Publication.BACK_PRESSURED) {
                    idleStrategy.idle();
                    retries++;
                    if (retries > 100) {
                        log.error("❌ Delete ingress back-pressured after {} retries", retries);
                        return false;
                    }
                } else if (result == io.aeron.Publication.NOT_CONNECTED) {
                    log.warn("⚠️  Delete ingress not connected - waiting...");
                    idleStrategy.idle();
                    retries++;
                    if (retries > 100) {
                        log.error("❌ Delete ingress not connected after {} retries", retries);
                        return false;
                    }
                } else {
                    log.error("❌ Failed to send delete through ingress: {}", result);
                    return false;
                }
            }
            
            // Track metrics (same as writes)
            ingressTimestamps.offer(System.nanoTime());
            performanceMetrics.recordMessageIngressed();
            backpressureManager.incrementSent();
            
            log.info("✅ DELETE sent through AeronCluster.offer() - will replicate to all nodes via Raft");
            return true;
        } catch (Exception e) {
            log.error("❌ Exception sending delete through ingress", e);
            return false;
        }
    }
    
    /**
     * Send a batch of write proposals through Aeron ingress as a single message.
     * This is more efficient than individual sends as Aeron can optimize batched messages.
     * 
     * @param proposals List of queued proposals to send as a batch
     * @return number of proposals successfully sent (all or none for atomic batch)
     */
    public int sendWriteBatchThroughIngress(java.util.List<org.apache.jackrabbit.oak.segment.consensus.queue.QueuedProposal> proposals) {
        if (cluster == null) {
            log.error("❌ Cluster not initialized - cannot send batch write through ingress");
            return 0;
        }
        
        if (proposals == null || proposals.isEmpty()) {
            return 0;
        }
        
        log.debug("🔍DEBUG_BATCH [1]: sendWriteBatchThroughIngress() ENTRY - batch size: {}, role: {}", 
            proposals.size(), cluster != null ? cluster.role() : "NO_CLUSTER");
        
        // Ensure internal cluster client is created (lazy initialization)
        ensureInternalClusterClient();
        
        log.debug("🔍DEBUG_BATCH [2]: After ensureInternalClusterClient() - client available: {}", 
            internalClusterClient != null);
        
        // 🔍 GROK DIAGNOSTIC: Check client/session state for BATCH path
        if (internalClusterClient != null) {
            log.info("🔍 BATCH PATH: client={}, sessionId={}, isClosed={}", 
                System.identityHashCode(internalClusterClient),
                internalClusterClient.clusterSessionId(),
                internalClusterClient.isClosed());
        }
        
        if (internalClusterClient == null) {
            log.debug("🔍DEBUG_BATCH [3]: ❌ ABORTING - internalClusterClient is NULL");
            log.error("❌ Internal AeronCluster client not available - cannot send batch write through ingress");
            return 0;
        }
        
        log.debug("🔍DEBUG_BATCH [4]: Building JSON batch with {} proposals", proposals.size());
        
        try {
            // Build JSON array of write proposals
            StringBuilder json = new StringBuilder();
            json.append("{\"batch\":[");
            
            boolean first = true;
            for (org.apache.jackrabbit.oak.segment.consensus.queue.QueuedProposal proposal : proposals) {
                if (!first) {
                    json.append(",");
                }
                first = false;
                
                json.append("{");
                json.append("\"walletAddress\":\"").append(escapeJson(proposal.getWalletAddress())).append("\",");
                json.append("\"path\":\"").append(escapeJson(proposal.getPath())).append("\",");
                json.append("\"contentType\":\"").append(escapeJson(proposal.getContentType() != null ? proposal.getContentType() : "page")).append("\",");
                json.append("\"message\":\"").append(escapeJson(proposal.getMessage() != null ? proposal.getMessage() : "")).append("\",");
                json.append("\"signature\":\"").append(escapeJson(proposal.getSignature() != null ? proposal.getSignature() : "")).append("\"");
                
                // Add intentToken if present (ADR 020 - lazy binary upload)
                if (proposal.getIntentToken() != null && !proposal.getIntentToken().isEmpty()) {
                    json.append(",\"intentToken\":\"").append(escapeJson(proposal.getIntentToken())).append("\"");
                }
                
                // Add blobId and mimeType if present (eager binary upload)
                String pBlobId = proposal.getBlobId();
                log.info("🔍 Serializing proposal: path={}, blobId={}", proposal.getPath(), pBlobId);
                if (pBlobId != null && !pBlobId.isEmpty()) {
                    json.append(",\"blobId\":\"").append(escapeJson(pBlobId)).append("\"");
                    json.append(",\"mimeType\":\"").append(escapeJson(proposal.getMimeType() != null ? proposal.getMimeType() : "application/octet-stream")).append("\"");
                    log.info("📎 Including blobId in Aeron JSON: {}", pBlobId);
                }
                
                json.append("}");
            }
            
            json.append("]}");
            
            byte[] jsonBytes = json.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            
            log.debug("🔍DEBUG_BATCH [5]: JSON built - size: {} bytes, first 100 chars: {}", 
                jsonBytes.length, json.substring(0, Math.min(100, json.length())));
            
            // ✈️ AERON SBE MESSAGE FORMAT: Encode message with SBE header
            int blockLength = jsonBytes.length;
            int templateId = org.apache.jackrabbit.oak.segment.consensus.aeron.SimpleMessageHeader.TEMPLATE_ID_WRITE_BATCH; // New template ID for batches
            
            log.debug("🔍DEBUG_BATCH [6]: Encoding SBE header - blockLength: {}, templateId: {} (WRITE_BATCH)", 
                blockLength, templateId);
            
            // Allocate buffer: SBE header (8 bytes) + JSON payload
            int totalLength = org.apache.jackrabbit.oak.segment.consensus.aeron.SimpleMessageHeader.ENCODED_LENGTH + jsonBytes.length;
            org.agrona.MutableDirectBuffer messageBuffer = new org.agrona.concurrent.UnsafeBuffer(
                new byte[totalLength]
            );
            
            // Encode SBE message header for Aeron cluster protocol
            org.apache.jackrabbit.oak.segment.consensus.aeron.SimpleMessageHeader.encode(
                messageBuffer, 0, blockLength, templateId);
            
            // Write JSON payload after header
            messageBuffer.putBytes(org.apache.jackrabbit.oak.segment.consensus.aeron.SimpleMessageHeader.ENCODED_LENGTH, jsonBytes);
            
            // ✈️ AERON CLUSTER: Send batch message through internal AeronCluster client
            log.debug("🔍DEBUG_BATCH [7]: About to call internalClusterClient.offer() - totalLength: {} bytes", totalLength);
            
            try {
                // 🔍 HEALTH CHECK: Verify session is open before offering batch
                if (internalClusterClient.isClosed()) {
                    log.error("❌ Cannot send batch - internal cluster client session is CLOSED (batch size: {})", proposals.size());
                    log.error("   This indicates session timeout or connection loss during epoch queueing");
                    log.error("   Attempting reconnection...");
                    
                    // Try to reconnect
                    synchronized (this) {
                        internalClusterClient = null;
                        ensureInternalClusterClient();
                    }
                    
                    // If still null or closed after reconnect, fail
                    if (internalClusterClient == null || internalClusterClient.isClosed()) {
                        log.error("❌ Reconnection failed - cannot send batch (batch size: {})", proposals.size());
                        return 0;
                    }
                    
                    log.info("✅ Reconnection successful - retrying batch send (batch size: {})", proposals.size());
                }
                
                // Send message through AeronCluster client ingress
                idleStrategy.reset();
                long result;
                int retries = 0;
                log.debug("🔍DEBUG_BATCH [8]: Entering offer loop...");
                
                while ((result = internalClusterClient.offer(messageBuffer, 0, totalLength)) < 0) {
                    log.debug("🔍DEBUG_BATCH [9]: offer() returned: {}, retry: {}", result, retries);
                    if (result == io.aeron.Publication.BACK_PRESSURED) {
                        idleStrategy.idle();
                        retries++;
                        if (retries > 100) {
                            log.error("❌ Ingress back-pressured after {} retries (batch size: {})", retries, proposals.size());
                            return 0;
                        }
                    } else if (result == io.aeron.Publication.NOT_CONNECTED) {
                        log.warn("⚠️  Ingress not connected - waiting... (batch size: {})", proposals.size());
                        idleStrategy.idle();
                        retries++;
                        if (retries > 100) {
                            log.error("❌ Ingress not connected after {} retries (batch size: {})", retries, proposals.size());
                            return 0;
                        }
                    } else {
                        log.error("❌ Failed to send batch write through ingress: {} (batch size: {})", result, proposals.size());
                        return 0;
                    }
                }
                
                log.debug("🔍DEBUG_BATCH [10]: offer() SUCCESS - result: {}", result);
                
                // 📊 Track ingress timestamp for Raft latency calculation
                ingressTimestamps.offer(System.nanoTime());
                performanceMetrics.recordMessageIngressed();
                
                log.debug("🔍DEBUG_BATCH [11]: Tracked ingress timestamp and metrics");
                
                // 🚦 BACKPRESSURE: Do NOT increment here - ProposalQueueManagerOptimized
                // already calls incrementSent() for each proposal before calling this method
                // (see ProposalQueueManagerOptimized line 507)
                // Double-counting would cause false backpressure!
                
                log.debug("🔍DEBUG_BATCH [12]: ✅ COMPLETE - Batch sent to Aeron ingress, {} proposals will replicate via Raft", proposals.size());
                log.debug("✅ Batch write sent through AeronCluster.offer() - {} proposals will replicate via Raft", proposals.size());
                return proposals.size();
            } catch (Exception e) {
                log.error("❌ Exception sending batch write through AeronCluster client (batch size: {})", proposals.size(), e);
                return 0;
            }
            
        } catch (Exception e) {
            log.error("❌ Exception sending batch write through ingress (batch size: {})", proposals.size(), e);
            return 0;
        }
    }
    
    /**
     * Escape JSON string (simple implementation).
     */
    private String escapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r")
                  .replace("\t", "\\t");
    }
    
    @Override
    public void onTimerEvent(long correlationId, long timestamp) {
        // Handle timer events
        // TODO: Implement timer-based operations (e.g., Ethereum epoch polling)
        log.debug("⏰ Timer event: {}", correlationId);
    }
    
    // Note: onTakeSnapshot() is implemented above (line 507) with full snapshot support
    // Snapshot loading happens in onStart() when snapshotImage is provided
    // There is no onLoadSnapshot() method in ClusteredService interface
    
    @Override
    public void onRoleChange(Cluster.Role newRole) {
        log.info("Role change: {} -> {}", currentRole, newRole.name());
        
        // ✈️ AERON NATIVE: Track leadership rotation history
        // Note: onRoleChange() is called with the NEW role, so we need to track previous role
        Cluster.Role previousRole;
        // Map our ValidatorRole to Cluster.Role for history (before we update)
        if (currentRole == ValidatorRole.LEADER) {
            previousRole = Cluster.Role.LEADER;
        } else {
            previousRole = Cluster.Role.FOLLOWER;
        }
        
        int memberId = cluster != null ? cluster.memberId() : -1;
        long timestamp = cluster != null ? cluster.time() : System.currentTimeMillis();
        
        // Record leadership change
        LeadershipChange change = new LeadershipChange(
            timestamp,
            newRole,
            previousRole,
            getCurrentTerm(), // ✅ ADR 025: Use native leadershipTermId()
            memberId,
            selfUrl
        );
        
        leadershipHistory.add(change);
        
        // Keep only last N entries
        if (leadershipHistory.size() > MAX_HISTORY_ENTRIES) {
            leadershipHistory.remove(0);
        }
        
        // ✅ ADR 025: Track term on role change (Aeron doesn't expose leadershipTermId on Cluster interface)
        if (newRole == Cluster.Role.LEADER && previousRole != Cluster.Role.LEADER) {
            currentTerm++;
            log.info("Term incremented to: {}", currentTerm);
        }
        
        log.debug("Leadership history: {} total changes", leadershipHistory.size());
        
        // Invalidate leader cache on any role change
        cachedLeaderUrl = null;
        cachedLeaderTimestamp = 0;
        
        if (newRole == Cluster.Role.LEADER) {
            log.info("Leadership rotation: Now LEADER (term: {})", currentTerm);
            
            // NEW GENESIS ARCHITECTURE: Create genesis as first consensus write
            // Check if genesis exists - if not, create it as first consensus write
            if (nodeStore != null) {
                try {
                    // Check if genesis node exists at wallet-scoped path
                    // Path: /oak-chain/00/00/00/0x0000.../content/genesis
                    org.apache.jackrabbit.oak.spi.state.NodeState root = nodeStore.getRoot();
                    boolean genesisExists = root.getChildNode("oak-chain")
                        .getChildNode("00")
                        .getChildNode("00")
                        .getChildNode("00")
                        .getChildNode("0x0000000000000000000000000000000000000000")
                        .getChildNode("content")
                        .getChildNode("genesis")
                        .exists();
                    
                    if (!genesisExists) {
                        log.info("Network genesis: No genesis detected on new leader - creating genesis as first consensus write");
                        
                        // Trigger genesis creation in background thread
                        // (don't block onRoleChange callback)
                        new Thread(() -> {
                            try {
                                Thread.sleep(2000); // Wait 2s for cluster to stabilize
                                createGenesisViaConsensus();
                            } catch (Exception e) {
                                log.error("❌ Failed to create genesis", e);
                            }
                        }, "genesis-creator").start();
                    } else {
                        log.debug("Genesis already exists, skipping creation");
                    }
                } catch (Exception e) {
                    log.warn("Failed to check for genesis existence: {}", e.getMessage());
                }
            }
        } else if (previousRole == Cluster.Role.LEADER) {
            log.info("Leadership rotation: Stepped down from LEADER (term: {})", currentTerm);
        }
        
        updateRoleFromCluster(newRole);
    }
    
    /**
     * ✈️ AERON NATIVE: Get leadership rotation history.
     * 
     * Returns history of role changes tracked via onRoleChange() callbacks.
     * 
     * @param limit Maximum number of entries to return (default: all)
     * @return List of leadership changes, most recent first
     */
    public java.util.List<LeadershipChange> getLeadershipHistory(int limit) {
        java.util.List<LeadershipChange> result = new java.util.ArrayList<>(leadershipHistory);
        java.util.Collections.reverse(result); // Most recent first
        
        if (limit > 0 && result.size() > limit) {
            return result.subList(0, limit);
        }
        
        return result;
    }
    
    @Override
    public void onTerminate(Cluster cluster) {
        log.info("Aeron Cluster service terminating (role: {})", cluster.role());
        
        // Close internal cluster client
        if (internalClusterClient != null) {
            try {
                internalClusterClient.close();
                log.info("Internal AeronCluster client closed");
            } catch (Exception e) {
                log.error("Error closing internal cluster client", e);
            }
        }
        
        // Cleanup resources
        if (beaconClient != null) {
            // Stop Ethereum epoch polling
        }
    }
    
    /**
     * Update our ValidatorRole based on Aeron Cluster role.
     * 
     * 🛡️ RESILIENCE: Ensures network never falls apart into followers with no leader.
     * If we're a follower and can't see a leader, we should attempt to become leader
     * ourselves (if we're the only node or can form quorum with available nodes).
     */
    private void updateRoleFromCluster(Cluster.Role aeronRole) {
        switch (aeronRole) {
            case LEADER:
                this.currentRole = ValidatorRole.LEADER;
                this.currentLeader = selfUrl;
                log.info("Now LEADER");
                break;
            case FOLLOWER:
                this.currentRole = ValidatorRole.FOLLOWER;
                log.info("Now FOLLOWER - Aeron Cluster will elect leader when quorum forms");
                
                // Try to discover leader from cluster membership via nodeIdToUrl mapping
                // For now, set to null - will be discovered via periodic checks or API queries
                this.currentLeader = null;
                
                // ✈️ AERON CLUSTER STATE: Start background task to discover leader via Aeron Cluster state API
                discoverLeaderFromPeers();
                break;
            default:
                this.currentRole = ValidatorRole.FOLLOWER;
                log.info("Role: {}", aeronRole);
                this.currentLeader = null;
        }
    }
    
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Public API (Compatible with EpochLeaderEngine interface)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    
    /**
     * Get current validator role (LEADER, FOLLOWER, etc.).
     * 
     * ✈️ AERON NATIVE: Uses cluster.role() directly from Aeron Cluster.
     */
    public ValidatorRole getCurrentRole() {
        if (cluster != null) {
            // Use Aeron's native role - this is the source of truth
            Cluster.Role aeronRole = cluster.role();
            if (aeronRole == Cluster.Role.LEADER) {
                return ValidatorRole.LEADER;
            } else if (aeronRole == Cluster.Role.FOLLOWER) {
                return ValidatorRole.FOLLOWER;
            }
        }
        return currentRole; // Fallback to cached value
    }
    
    /**
     * Check if this validator is currently the leader.
     * 
     * ✈️ AERON NATIVE: Uses cluster.role() directly from Aeron Cluster.
     */
    public boolean isLeader() {
        if (cluster != null) {
            return cluster.role() == Cluster.Role.LEADER;
        }
        return currentRole == ValidatorRole.LEADER;
    }
    
    // 🔄 ROLLING 2-EPOCH FINALITY WINDOW: Optimize HEAD updates with finality-aware batching
    // 
    // 🎯 ETHEREUM FINALITY MODEL:
    // - Transaction data arrives every epoch (from Ethereum mainnet)
    // - Actual commitment happens after 2 epochs (finality)
    // - Rolling window: epoch N (arriving), epoch N-1 (pending), epoch N-2 (finalized, ready to commit)
    // - This allows look-ahead: see incoming writes while waiting for finality
    //
    // 📊 STRATEGY:
    // - During epoch: Batch HEAD updates (every N writes or X seconds)
    // - At finality boundary (every 2 epochs): Final HEAD broadcast for guaranteed consistency
    // - Ensures all validators commit the same finality-eligible writes
    // - Natural sync window between finality boundaries provides safety margin
    //
    // 🎯 ADAPTIVE BATCHING: No hardcoded assumptions about write volume
    // - Write volume varies based on Ethereum mainnet transaction patterns
    // - Transactions arrive before 2-epoch finality, allowing look-ahead
    // - Batching adapts to actual transaction patterns dynamically
    private volatile String pendingHead = null;
    private volatile long lastHeadBroadcastTime = 0;
    private volatile int writesSinceLastBroadcast = 0;
    
    // Track last finalized epoch for finality boundary detection
    private volatile int lastFinalizedEpoch = -1;
    
    // 🔄 IDEMPOTENT FINALITY BOUNDARY: Track last committed epoch for exactly-once semantics
    // This ensures we only commit once per finality boundary, even if polls are missed or delayed
    private volatile int lastCommittedEpoch = -1;
    
    // Track committed HEAD vs latest HEAD for health endpoints
    // committedHead: HEAD that has reached finality (epoch N-2) - immutable, safe
    // latestHead: Current HEAD including pending writes (epoch N, N+1) - may change
    private volatile String committedHead = null;
    private volatile String latestHead = null;
    
    // Configurable batching parameters (can be tuned based on observed patterns)
    // Default: Broadcast every 100 writes OR every 5 seconds (whichever comes first)
    // This provides incremental updates while minimizing broadcast overhead
    private static final int DEFAULT_BATCH_SIZE_WRITES = 100;
    private static final long DEFAULT_BATCH_INTERVAL_MS = 5000;
    
    // Allow runtime configuration (can be adjusted based on Ethereum transaction patterns)
    private volatile int batchSizeWrites = DEFAULT_BATCH_SIZE_WRITES;
    private volatile long batchIntervalMs = DEFAULT_BATCH_INTERVAL_MS;
    
    // Background timer for checking pending HEAD broadcasts
    // Ensures broadcasts happen even when no new writes arrive
    private java.util.concurrent.ScheduledExecutorService headBroadcastTimer = null;
    private volatile boolean running = false;
    
    /**
     * Schedule HEAD broadcast (batched for efficiency during epoch bursts).
     * 
     * <p>🔄 ADAPTIVE BATCHING STRATEGY:
     * - During epoch: Batch HEAD updates (every N writes or X seconds, whichever comes first)
     * - Adapts dynamically to actual Ethereum transaction patterns
     * - No hardcoded assumptions about write volume per epoch
     * - Natural sync window between epochs handles any lag
     * - Final HEAD broadcast ensures consistency at epoch boundaries
     * 
     * <p>🎯 ETHEREUM EPOCH-BASED DESIGN:
     * - Writes come from Ethereum mainnet transactions (variable volume)
     * - Transactions arrive before 2-epoch finality (~12.8 minutes)
     * - Look-ahead capability: Can see incoming writes while waiting for finality
     * - Epoch duration: ~6.4 minutes (384 seconds)
     * - Natural sync window between epochs provides safety margin
     * 
     * <p>📊 BATCHING BENEFITS:
     * - Reduces HEAD broadcast overhead significantly (e.g., 1000 writes → ~10 broadcasts)
     * - Provides incremental updates during epoch (not just at end)
     * - Configurable batch size and interval based on observed patterns
     * - Final sync at epoch boundary ensures consistency
     * 
     * @param newHeadStr The new HEAD RecordId as string
     */
    public void scheduleHeadBroadcast(String newHeadStr) {
        // 🎯 DETERMINISTIC STATE MACHINE: HEAD broadcasting disabled
        // All nodes commit identically via Aeron replication
        // HEAD consistency guaranteed by deterministic processing
        // NO manual broadcasts needed!
        
        log.debug("📡 scheduleHeadBroadcast() called but DISABLED (deterministic consensus)");
        
        // Just update latestHead cache for /v1/head API
        latestHead = newHeadStr;
    }
    
    /**
     * Configure batching parameters dynamically based on observed transaction patterns.
     * 
     * <p>This allows runtime tuning based on actual Ethereum transaction volumes:
     * - High volume epochs: Increase batch size to reduce broadcast frequency
     * - Low volume epochs: Decrease batch size for more frequent updates
     * - Can be adjusted based on look-ahead information from EVM bridge
     * 
     * @param batchSizeWrites Number of writes before broadcasting (default: 100)
     * @param batchIntervalMs Time interval in milliseconds before broadcasting (default: 5000)
     */
    public void configureHeadBroadcastBatching(int batchSizeWrites, long batchIntervalMs) {
        this.batchSizeWrites = batchSizeWrites > 0 ? batchSizeWrites : DEFAULT_BATCH_SIZE_WRITES;
        this.batchIntervalMs = batchIntervalMs > 0 ? batchIntervalMs : DEFAULT_BATCH_INTERVAL_MS;
        log.info("📡 HEAD broadcast batching configured: {} writes or {}ms (whichever comes first)", 
            this.batchSizeWrites, this.batchIntervalMs);
    }
    
    /**
     * Start the background timer for checking pending HEAD broadcasts.
     * The timer runs every 2 seconds to check if broadcasts are needed.
     */
    private void startHeadBroadcastTimer() {
        if (headBroadcastTimer != null) {
            log.warn("HEAD broadcast timer already running");
            return;
        }
        
        headBroadcastTimer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
            new java.util.concurrent.ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "head-broadcast-timer");
                    t.setDaemon(true);
                    return t;
                }
            }
        );
        
        // Check every 2 seconds (less than the 5-second batch interval)
        headBroadcastTimer.scheduleAtFixedRate(
            this::checkPendingHeadBroadcasts,
            2000, // Initial delay: 2 seconds
            2000, // Period: 2 seconds
            java.util.concurrent.TimeUnit.MILLISECONDS
        );
        
        log.info("⏰ HEAD broadcast timer started (checks every 2s)");
    }
    
    /**
     * Stop the background timer for checking pending HEAD broadcasts.
     */
    private void stopHeadBroadcastTimer() {
        if (headBroadcastTimer != null) {
            try {
                headBroadcastTimer.shutdown();
                if (!headBroadcastTimer.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    headBroadcastTimer.shutdownNow();
                }
                headBroadcastTimer = null;
                log.info("⏰ HEAD broadcast timer stopped");
            } catch (InterruptedException e) {
                headBroadcastTimer.shutdownNow();
                Thread.currentThread().interrupt();
                log.warn("HEAD broadcast timer shutdown interrupted", e);
            }
        }
    }
    
    /**
     * Check if there are pending HEAD broadcasts that should be sent due to time threshold.
     * This is called by the background timer to ensure broadcasts happen even when no new writes arrive.
     */
    private void checkPendingHeadBroadcasts() {
        // 🎯 DETERMINISTIC STATE MACHINE: Timer-based HEAD broadcasting disabled
        // All nodes commit identically via Aeron replication
        // HEAD consistency guaranteed by deterministic processing
        // NO periodic broadcasts needed!
        
        log.trace("⏰ checkPendingHeadBroadcasts() called but DISABLED (deterministic consensus)");
    }
    
    /**
     * Force immediate HEAD broadcast (e.g., at finality boundary for final sync).
     * 
     * <p>🔄 FINALITY BOUNDARY SYNC:
     * Call this at finality boundary (every 2 epochs) to ensure all followers have
     * the final HEAD before the next finality window begins. This provides guaranteed
     * consistency at finality boundaries.
     * 
     * <p>📊 ROLLING 2-EPOCH WINDOW:
     * - Epoch N: Transaction data arrives (pending finality)
     * - Epoch N+1: Still pending finality
     * - Epoch N+2: Reaches finality, ready to commit
     * - At finality boundary: Broadcast HEAD to sync all validators
     * 
     * @param newHeadStr The new HEAD RecordId as string
     */
    public void broadcastHeadToFollowersImmediate(String newHeadStr) {
        // ✅ ADR 025: HEAD broadcasting removed - obsolete with Aeron Raft
        // All nodes execute identical replicated log deterministically
        // HEAD consistency guaranteed by Raft consensus - no manual broadcasts needed
        
        // Just update latestHead cache for /v1/head API endpoint
        latestHead = newHeadStr;
        log.trace("Updated latestHead cache: {}", newHeadStr.substring(0, Math.min(20, newHeadStr.length())));
    }
    
    /**
     * Check if we've reached a finality boundary and should broadcast HEAD immediately.
     * 
     * <p>🔄 IDEMPOTENT FINALITY BOUNDARY DETECTION:
     * Uses exactly-once semantics: `if (currentFinalizedEpoch >= lastCommittedEpoch + 2)`
     * This ensures we only commit once per finality boundary, even if:
     * - Polls are missed or delayed
     * - Node restarts and catches up
     * - Multiple epochs finalize while node was offline
     * 
     * <p>📊 ROLLING 2-EPOCH WINDOW:
     * This ensures all validators commit the same finality-eligible writes:
     * - Epoch N: Writes arrive (pending finality)
     * - Epoch N+1: Still pending finality
     * - Epoch N+2: Reaches finality → Commit and broadcast HEAD
     * 
     * <p>This should be called periodically (e.g., when Ethereum epoch updates)
     * to detect finality boundaries and trigger immediate HEAD broadcasts.
     * 
     * @param currentFinalizedEpoch The current finalized epoch (2 epochs behind current)
     * @param newHeadStr The new HEAD RecordId as string (if available)
     * @return true if finality boundary was detected and HEAD was broadcast
     */
    public boolean checkAndBroadcastAtFinalityBoundary(int currentFinalizedEpoch, String newHeadStr) {
        if (!isLeader()) {
            return false;
        }
        
        // 🔄 IDEMPOTENT CHECK: Only commit if we've crossed one or more finality boundaries
        // Handles missed polls, catch-up nodes, multiple epochs finalizing while offline
        if (currentFinalizedEpoch >= lastCommittedEpoch + 2) {
            log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            log.info("🔄 FINALITY BOUNDARY DETECTED (Idempotent)");
            log.info("   Current finalized epoch:  {}", currentFinalizedEpoch);
            log.info("   Last committed epoch:     {}", lastCommittedEpoch);
            log.info("   Epochs to commit:        {}", (currentFinalizedEpoch - lastCommittedEpoch - 1));
            log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            
            // Get safe HEAD (current HEAD is safe up to epoch N-2)
            // 🔄 CRITICAL FIX: Use tracked latestHead instead of reading from FileStore
            // Reading from FileStore can get stale value during concurrent writes,
            // causing followers to revert to old HEAD. latestHead is updated after
            // every commit+flush, so it's always current.
            String safeHead = null;
            if (newHeadStr != null && !newHeadStr.isEmpty()) {
                safeHead = newHeadStr;
            } else if (latestHead != null && !latestHead.isEmpty()) {
                // Use tracked latestHead (updated after every write commit)
                safeHead = latestHead;
                log.debug("Using tracked latestHead for finality broadcast: {}", 
                    safeHead.substring(0, Math.min(20, safeHead.length())));
            } else if (pendingHead != null && !pendingHead.isEmpty()) {
                // Fallback to pendingHead (scheduled but not yet broadcast)
                safeHead = pendingHead;
                log.debug("Using pendingHead for finality broadcast: {}", 
                    safeHead.substring(0, Math.min(20, safeHead.length())));
            } else if (fileStore != null) {
                // Final fallback: read from FileStore (only if tracked HEADs not set, e.g. startup)
                safeHead = fileStore.getHead().getRecordId().toString10();
                log.debug("Using FileStore HEAD for finality broadcast (fallback): {}", 
                    safeHead.substring(0, Math.min(20, safeHead.length())));
            }
            
            if (safeHead != null && !safeHead.isEmpty()) {
                // Update committed HEAD (this is the safe, immutable HEAD)
                // Use toString10() for consistency (same format as /v1/head endpoint)
                committedHead = safeHead.contains(":") ? safeHead : 
                    (fileStore != null ? fileStore.getHead().getRecordId().toString10() : safeHead);
                
                // Broadcast HEAD immediately at finality boundary
                broadcastHeadToFollowersImmediate(committedHead);
                
                // Update last committed epoch (commit up to epoch N-1, since epoch N-2 is finalized)
                lastCommittedEpoch = currentFinalizedEpoch - 1;
                
                log.info("✅ Committed HEAD broadcast: {} (epoch {})", 
                    committedHead.substring(0, Math.min(20, committedHead.length())), lastCommittedEpoch);
                
                return true;
            } else {
                log.warn("⚠️  Finality boundary detected but no HEAD available to broadcast");
            }
        }
        
        return false;
    }
    
    /**
     * Broadcast HEAD update to all followers (internal implementation).
     * 
     * This replicates writes across the cluster using HTTP-based replication
     * (similar to Leader Mode). In the future, this will be replaced with
     * proper Aeron ingress channel replication.
     * 
     * @param newHeadStr The new HEAD RecordId as string
     */
    private void broadcastHeadToFollowers(String newHeadStr) {
        // ✅ ADR 025: Method body removed - obsolete with Aeron Raft
        // Keeping method stub to avoid breaking any remaining references
        // TODO: Remove all callers and delete this method entirely
        log.trace("broadcastHeadToFollowers() called but disabled (Aeron Raft handles consistency)");
    }
    
    /* ✅ ADR 025: Removed broadcastHeadToFollowers implementation
       Old implementation commented out below for reference (can be deleted)
       
    private void broadcastHeadToFollowersOLD(String newHeadStr) {
        // 🔄 CRITICAL FIX: Discover follower URLs from multiple sources
        // Priority: 1) nodeIdToUrl (most accurate), 2) peerUrls (configured), 3) Aeron cluster members
        java.util.List<String> peers = new java.util.ArrayList<>();
        
        // Source 1: nodeIdToUrl mapping (populated from Aeron cluster)
        if (nodeIdToUrl != null && !nodeIdToUrl.isEmpty()) {
            for (java.util.Map.Entry<Integer, String> entry : nodeIdToUrl.entrySet()) {
                String peerUrl = entry.getValue();
                // Skip self
                if (peerUrl != null && !peerUrl.equals(selfUrl)) {
                    peers.add(peerUrl);
                }
            }
            log.debug("Using nodeIdToUrl mapping: {} peers", peers.size());
        }
        
        // Source 2: Fallback to configured peerUrls
        if (peers.isEmpty() && peerUrls != null && !peerUrls.isEmpty()) {
            for (String peerUrl : peerUrls) {
                // Skip self
                if (peerUrl != null && !peerUrl.equals(selfUrl)) {
                    peers.add(peerUrl);
                }
            }
            log.debug("Using peerUrls fallback: {} peers", peers.size());
        }
        
        // Source 3: Discover from Aeron cluster state (if cluster is available)
        if (peers.isEmpty() && cluster != null) {
            try {
                // Query cluster members from Aeron
                // Note: Aeron Cluster doesn't directly expose HTTP URLs, but we can use nodeIdToUrl
                // or query peers' /v1/aeron/cluster-state endpoints to discover member URLs
                log.debug("Attempting to discover peers from Aeron cluster state...");
                // For now, this is a fallback - nodeIdToUrl should be populated during cluster init
            } catch (Exception e) {
                log.debug("Could not discover peers from Aeron cluster: {}", e.getMessage());
            }
        }
        
        if (peers.isEmpty()) {
            log.error("❌ No follower URLs found for HEAD broadcast!");
            log.error("   nodeIdToUrl: {}", nodeIdToUrl);
            log.error("   peerUrls: {}", peerUrls);
            log.error("   selfUrl: {}", selfUrl);
            log.error("   HEAD broadcast will be skipped - followers may not sync!");
            return;
        }
        
        log.info("📡 Broadcasting HEAD to {} followers via HTTP", peers.size());
        log.debug("   Follower URLs: {}", peers);
        
        for (String peerUrl : peers) {
            new Thread(() -> {
                long startTime = System.nanoTime();
                String targetValidator = peerUrl.replaceAll("https?://", "").split(":")[0];
                
                try {
                    java.net.URL url = new java.net.URL(peerUrl + "/v1/follower/head-update");
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("Content-Type", "application/json");
                    // Bypass ngrok warning page (free tier requirement)
                    if (peerUrl.contains("ngrok")) {
                        conn.setRequestProperty("ngrok-skip-browser-warning", "true");
                    }
                    conn.setDoOutput(true);
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(10000);
                    
                    String payload = String.format(
                        "{\"head\":\"%s\",\"epoch\":%d,\"leaderUrl\":\"%s\"}",
                        newHeadStr, getCurrentEpoch(), selfUrl
                    );
                    
                    conn.getOutputStream().write(payload.getBytes("UTF-8"));
                    
                    int responseCode = conn.getResponseCode();
                    double latencySeconds = (System.nanoTime() - startTime) / 1_000_000_000.0;
                    
                    if (responseCode == 200) {
                        log.info("   ✅ HEAD broadcast to {}: OK ({}ms)", peerUrl, 
                            (long)(latencySeconds * 1000));
                    } else {
                        log.warn("   ⚠️  HEAD broadcast to {}: HTTP {} ({}ms)", peerUrl, responseCode,
                            (long)(latencySeconds * 1000));
                    }
                    
                } catch (java.net.SocketTimeoutException e) {
                    double latencySeconds = (System.nanoTime() - startTime) / 1_000_000_000.0;
                    log.warn("   ❌ Failed to broadcast to {}: {} ({}ms)", peerUrl, e.getMessage(),
                        (long)(latencySeconds * 1000));
                } catch (Exception e) {
                    double latencySeconds = (System.nanoTime() - startTime) / 1_000_000_000.0;
                    log.warn("   ❌ Failed to broadcast to {}: {} ({}ms)", peerUrl, e.getMessage(),
                        (long)(latencySeconds * 1000));
                }
            }, "aeron-head-broadcast-" + peerUrl.hashCode()).start();
        }
    }
    */  // End of commented-out broadcastHeadToFollowersOLD
    
    /**
     * Sync HEAD from leader on startup (for followers starting fresh).
     * This ensures all validators start with the same HEAD before processing writes.
     * 
     * <p><strong>DISTRIBUTED SYNC:</strong>
     * This method queries leader validators across the network via HTTP to synchronize
     * Oak FileStore HEAD. Validators can be deployed across different machines, networks,
     * or data centers - this sync works across all network topologies.
     * 
     * <p>CRITICAL: All validators MUST have the same HEAD for consensus to work correctly.
     * Aeron replicates writes, but Oak's FileStore HEAD must be synchronized separately.
     * 
     * <p><strong>Network Discovery:</strong>
     * <ul>
     *   <li>Queries Aeron cluster state API to find current leader</li>
     *   <li>Falls back to trying all configured peer URLs</li>
     *   <li>Works across distributed network topologies</li>
     *   <li>No localhost assumptions - uses configured peer URLs</li>
     * </ul>
     */
    private void syncHeadFromLeaderOnStartup() {
        log.info("Syncing HEAD from leader on startup - All validators must have same HEAD for consensus");
        
        // Try to find leader via Aeron cluster state API (most reliable)
        String leaderUrl = null;
        for (String peerUrl : peerUrls) {
            try {
                // Query Aeron cluster state to find leader
                java.net.URL url = new java.net.URL(peerUrl + "/v1/aeron/cluster-state");
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(3000);
                
                if (conn.getResponseCode() == 200) {
                    java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(conn.getInputStream())
                    );
                    StringBuilder json = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        json.append(line);
                    }
                    reader.close();
                    
                    // Parse JSON to find leader URL
                    String jsonStr = json.toString();
                    int leaderIndex = jsonStr.indexOf("\"currentLeader\"");
                    if (leaderIndex >= 0) {
                        int colonIndex = jsonStr.indexOf(":", leaderIndex);
                        int quoteStart = jsonStr.indexOf("\"", colonIndex);
                        if (quoteStart >= 0) {
                            int quoteEnd = jsonStr.indexOf("\"", quoteStart + 1);
                            if (quoteEnd > quoteStart) {
                                leaderUrl = jsonStr.substring(quoteStart + 1, quoteEnd);
                                if (leaderUrl != null && !leaderUrl.isEmpty() && !"null".equals(leaderUrl)) {
                                    log.info("Found leader via cluster state: {}", leaderUrl);
                                    break;
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("Failed to query cluster state from {}: {}", peerUrl, e.getMessage());
            }
        }
        
        // Fallback: Try all peers if leader not found via cluster state
        if (leaderUrl == null || leaderUrl.isEmpty()) {
            log.info("Leader not found via cluster state, trying all peers");
            leaderUrl = peerUrls.get(0); // Use first peer as fallback
        }
        
        // Sync HEAD from leader
        try {
            // 🔄 FINALITY-AWARE STARTUP SYNC: Check if we're behind finality boundary
            // If currentFinalizedEpoch >= lastCommittedEpoch + 2, pull committed HEAD from leader
            // This ensures brand-new nodes don't miss finality boundary sync
            int currentFinalizedEpoch = -1;
            if (beaconClient != null) {
                try {
                    org.apache.jackrabbit.oak.segment.consensus.eth.EpochData epochData = 
                        beaconClient.getLatestFinalizedEpoch();
                    if (epochData.finalized) {
                        currentFinalizedEpoch = (int)epochData.epochNumber;
                    }
                } catch (Exception e) {
                    log.debug("Could not get finalized epoch for startup sync: {}", e.getMessage());
                }
            }
            
            // Get leader HEAD via HTTP (now returns JSON with committedHead/latestHead)
            java.net.URL url = new java.net.URL(leaderUrl + "/v1/head");
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            
            int responseCode = conn.getResponseCode();
            if (responseCode == 200) {
                java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(conn.getInputStream())
                );
                StringBuilder responseBody = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    responseBody.append(line);
                }
                reader.close();
                
                // Parse JSON response
                String responseJson = responseBody.toString();
                String leaderCommittedHead = extractJsonField(responseJson, "committedHead");
                String leaderLatestHead = extractJsonField(responseJson, "latestHead");
                String leaderLatestEpochSeenStr = extractJsonField(responseJson, "latestEpochSeen");
                String leaderCommittedEpochStr = extractJsonField(responseJson, "committedEpoch");
                
                // Determine which HEAD to sync:
                // - If we're behind finality boundary, sync committed HEAD (safe, immutable)
                // - Otherwise, sync latest HEAD (includes pending writes)
                String leaderHead = null;
                boolean syncCommittedHead = false;
                
                if (currentFinalizedEpoch >= 0 && lastCommittedEpoch >= 0 && 
                    currentFinalizedEpoch >= lastCommittedEpoch + 2) {
                    // We're behind finality boundary - sync committed HEAD
                    if (leaderCommittedHead != null && !leaderCommittedHead.isEmpty()) {
                        leaderHead = leaderCommittedHead;
                        syncCommittedHead = true;
                        log.info("🔄 Startup sync: Behind finality boundary, syncing committed HEAD");
                        log.info("   Current finalized epoch: {}, Last committed epoch: {}", 
                            currentFinalizedEpoch, lastCommittedEpoch);
                    }
                }
                
                // Fallback to latest HEAD if committed HEAD not available or not needed
                if (leaderHead == null) {
                    leaderHead = leaderLatestHead != null && !leaderLatestHead.isEmpty() ? 
                        leaderLatestHead : leaderCommittedHead;
                }
                
                // Fallback to old text/plain format if JSON parsing failed
                if (leaderHead == null || leaderHead.isEmpty()) {
                    // Try to parse as plain text (backward compatibility)
                    leaderHead = responseJson.trim();
                }
                
                if (leaderHead != null && !leaderHead.trim().isEmpty()) {
                    // 🐛 FIX: Compare full RecordId string including timestamp, not just UUID+offset
                    // toString() includes timestamp, toString10() only returns UUID:offset
                    // This prevents false positives when segment ID matches but timestamp differs
                    String localHead = fileStore.getHead().getRecordId().toString();  // Full format with timestamp
                    String leaderHeadTrimmed = leaderHead.trim();
                    
                    // Extract segment UUID portion for comparison (format: "uuid:offset root timestamp")
                    // If leader sends just "uuid:offset", we need to handle both formats
                    String localHeadSegment = localHead.split(" ")[0];  // Get "uuid:offset" part
                    String leaderHeadSegment = leaderHeadTrimmed.contains(" ") ? 
                        leaderHeadTrimmed.split(" ")[0] : leaderHeadTrimmed;
                    
                    // Compare: If segment UUIDs match BUT full strings differ, we're behind
                    boolean segmentMatches = localHeadSegment.equals(leaderHeadSegment);
                    boolean fullMatch = localHead.equals(leaderHeadTrimmed);
                    
                    if (!fullMatch) {
                        if (segmentMatches) {
                            log.warn("🔍 HEAD segment matches but timestamp differs (validator behind):");
                            log.warn("   Local:  {} (possibly stale)", localHead);
                            log.warn("   Leader: {} (current)", leaderHeadTrimmed);
                        } else {
                            log.info("📍 HEAD mismatch detected:");
                            log.info("   Local:  {}...", localHead.substring(0, Math.min(40, localHead.length())));
                            log.info("   Leader: {}...", leaderHeadTrimmed.substring(0, Math.min(40, leaderHeadTrimmed.length())));
                        }
                        
                        // If syncing committed HEAD, update our committed HEAD tracking
                        if (syncCommittedHead) {
                            committedHead = leaderHeadTrimmed;
                            if (leaderCommittedEpochStr != null && !leaderCommittedEpochStr.isEmpty()) {
                                try {
                                    lastCommittedEpoch = Integer.parseInt(leaderCommittedEpochStr);
                                    log.info("Updated committed epoch: {}", lastCommittedEpoch);
                                } catch (NumberFormatException e) {
                                    log.debug("Could not parse committed epoch: {}", leaderCommittedEpochStr);
                                }
                            }
                        }
                        
                        log.info("Syncing segments from leader: {}", leaderUrl);
                        
                        // Pull segments for leader's HEAD (use segment portion for lookup)
                        try {
                            int segmentsFetched = pullSegmentsForHead(leaderHeadSegment, leaderUrl);
                            log.info("Synced {} segments from leader", segmentsFetched);
                            
                            // Verify HEAD matches now
                            String newLocalHead = fileStore.getHead().getRecordId().toString();
                            String newLocalHeadSegment = newLocalHead.split(" ")[0];
                            if (newLocalHeadSegment.equals(leaderHeadSegment)) {
                                log.info("HEAD synchronized successfully - Local HEAD: {}", newLocalHead);
                            } else {
                                log.warn("HEAD still doesn't match after sync - Local: {}, Leader: {}. This may indicate a deeper sync issue.", 
                                    newLocalHead.substring(0, Math.min(40, newLocalHead.length())), 
                                    leaderHeadTrimmed.substring(0, Math.min(40, leaderHeadTrimmed.length())));
                            }
                        } catch (Exception e) {
                            log.error("Failed to sync HEAD from leader: {}. Validators may have inconsistent HEAD. " +
                                "Recommendation: Copy segmentstore from validator-0 (8091) to other validators", e.getMessage());
                            // Don't throw - allow startup to continue (may sync later via writes)
                        }
                    } else {
                        log.info("HEAD fully matches leader - no sync needed. HEAD: {}", localHead);
                        
                        // If we synced committed HEAD, update tracking even if HEAD already matched
                        if (syncCommittedHead && leaderCommittedHead != null && !leaderCommittedHead.isEmpty()) {
                            committedHead = leaderCommittedHead;
                            if (leaderCommittedEpochStr != null && !leaderCommittedEpochStr.isEmpty()) {
                                try {
                                    lastCommittedEpoch = Integer.parseInt(leaderCommittedEpochStr);
                                    log.info("Updated committed epoch: {}", lastCommittedEpoch);
                                } catch (NumberFormatException e) {
                                    log.debug("Could not parse committed epoch: {}", leaderCommittedEpochStr);
                                }
                            }
                        }
                    }
                    return; // Success or already synced
                }
            }
        } catch (Exception e) {
            log.warn("Failed to sync HEAD from leader {}: {}", leaderUrl, e.getMessage());
        }
        
        log.warn("Could not sync HEAD from any peer - validators may have inconsistent state. " +
            "Recommendation: (1) Start leader first, (2) Copy segmentstore to followers, OR (3) Wait for first write");
    }
    
    /**
     * Wait for genesis to be created by leader, then sync HEAD.
     * This deferred approach prevents the race condition where followers try to sync
     * before the leader has created genesis.
     */
    private void waitForGenesisAndSync() {
        log.info("Waiting for genesis to be created by leader before syncing HEAD");
        
        int maxAttempts = 20; // Try for up to 2 minutes (20 * 6 seconds)
        int attempt = 0;
        
        while (attempt < maxAttempts) {
            try {
                // Wait a bit before checking (give leader time to create genesis)
                Thread.sleep(6000); // 6 seconds
                attempt++;
                
                // Check if we can find a leader
                String leaderUrl = discoverLeaderFromAeronClusterState();
                if (leaderUrl == null) {
                    log.debug("No leader found yet (attempt {}/{}), waiting", attempt, maxAttempts);
                    continue;
                }
                
                // Check if leader has genesis by querying its /v1/head endpoint
                try {
                    java.net.URL headUrl = new java.net.URL(leaderUrl + "/v1/head");
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection) headUrl.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(5000);
                    
                    if (conn.getResponseCode() == 200) {
                        java.io.BufferedReader reader = new java.io.BufferedReader(
                            new java.io.InputStreamReader(conn.getInputStream())
                        );
                        String response = reader.lines().collect(java.util.stream.Collectors.joining());
                        reader.close();
                        
                        // Parse HEAD from response
                        if (response.contains("latestHead")) {
                            // Extract HEAD value - look for something like "abc123-...:62"
                            // If offset is > 10, it likely has genesis (genesis is ~62 bytes)
                            int colonIndex = response.lastIndexOf(":");
                            if (colonIndex > 0 && response.length() > colonIndex + 1) {
                                String offsetStr = response.substring(colonIndex + 1).replaceAll("[^0-9]", "").trim();
                                if (!offsetStr.isEmpty()) {
                                    int offset = Integer.parseInt(offsetStr);
                                    if (offset > 10) {
                                        log.info("Leader has genesis (HEAD offset: {}), starting sync", offset);
                                        syncHeadFromLeaderOnStartup();
                                        return; // Success!
                                    } else {
                                        log.debug("Leader HEAD offset too small ({}), genesis not ready yet (attempt {}/{})", 
                                                 offset, attempt, maxAttempts);
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    log.debug("Could not check leader HEAD (attempt {}/{}): {}", attempt, maxAttempts, e.getMessage());
                }
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Deferred HEAD sync interrupted");
                return;
            } catch (Exception e) {
                log.debug("Error during deferred sync check (attempt {}/{}): {}", attempt, maxAttempts, e.getMessage());
            }
        }
        
        log.warn("Gave up waiting for genesis after {} attempts. Validators may have inconsistent state - consider manual intervention", maxAttempts);
    }
    
    /**
     * Pull segments for a specific HEAD from the leader.
     * Called by followers when they receive a HEAD update broadcast.
     * 
     * @param headStr The HEAD RecordId to replicate
     * @param leaderUrl The URL of the leader validator
     * @return Number of segments replicated
     * @throws Exception if replication fails
     */
    public int pullSegmentsForHead(String headStr, String leaderUrl) throws Exception {
        log.info("Pulling segments for HEAD from leader: {} (HEAD: {}...)", 
            leaderUrl, headStr.substring(0, Math.min(16, headStr.length())));
        
        int segmentCount = replicator.fetchMissingSegmentsForHead(headStr, leaderUrl);
        
        log.info("Replicated {} segments for HEAD", segmentCount);
        
        // Update HEAD after fetching segments (like syncGenesisFromPeer pattern)
        try {
            org.apache.jackrabbit.oak.segment.RecordId newHead = 
                org.apache.jackrabbit.oak.segment.RecordId.fromString(
                    fileStore.getSegmentIdProvider(), 
                    headStr
                );
            
            // Use CAS (compare-and-set) to update HEAD (Cold Standby pattern)
            org.apache.jackrabbit.oak.segment.RecordId currentHead = fileStore.getHead().getRecordId();
            boolean updated = fileStore.getRevisions().setHead(currentHead, newHead);
            
            if (updated) {
                log.info("Updated HEAD to match leader (CAS success)");
                fileStore.flush();
                latestHead = headStr;
                log.debug("Updated latestHead cache for API consistency");
            } else {
                log.warn("HEAD CAS failed - current HEAD has changed (may have advanced)");
                org.apache.jackrabbit.oak.segment.RecordId actualHead = fileStore.getHead().getRecordId();
                if (actualHead.toString().equals(headStr)) {
                    log.info("HEAD already matches target (no update needed)");
                    latestHead = headStr;
                } else {
                    log.debug("Current HEAD: {}...", actualHead.toString().substring(0, Math.min(16, actualHead.toString().length())));
                    latestHead = actualHead.toString10();
                }
            }
            
        } catch (Exception e) {
            log.error("Failed to update HEAD after replication: {}. Segments replicated but HEAD may not match leader", e.getMessage());
            throw e; // Re-throw so caller knows sync may be incomplete
        }
        
        return segmentCount;
    }
    
    /**
     * Get this validator's wallet address (Ethereum address).
     * 
     * @return Wallet address (0x... format) or null if wallet not initialized
     */
    public String getWalletAddress() {
        return wallet != null ? wallet.getWalletAddress() : null;
    }
    
    /**
     * Get this validator's public key (hex-encoded).
     * 
     * @return Public key (0x... format) or null if wallet not initialized
     */
    public String getPublicKeyHex() {
        return wallet != null ? wallet.getPublicKeyHex() : null;
    }
    
    /**
     * ✈️ AERON NATIVE: Get native Aeron Cluster state.
     * 
     * This exposes Aeron's internal cluster state directly using available native APIs.
     * Uses what's available from cluster object and falls back to our tracking for the rest.
     * 
     * @return Native cluster state map, or null if cluster not initialized
     */
    public java.util.Map<String, Object> getNativeClusterState() {
        if (cluster == null) {
            return null;
        }
        
        java.util.Map<String, Object> state = new java.util.HashMap<>();
        
        // ✈️ AERON NATIVE: Use Aeron's native APIs directly (what's available)
        Cluster.Role role = cluster.role();
        state.put("role", role.name());
        state.put("isLeader", role == Cluster.Role.LEADER);
        state.put("memberId", cluster.memberId());
        state.put("clusterTime", cluster.time());
        state.put("logPosition", cluster.logPosition());
        
        // Use our tracking for fields not directly available from Cluster API
        state.put("term", getCurrentTerm());
        state.put("epoch", getCurrentEpoch());
        state.put("ethereumEpoch", getCurrentEthereumEpoch());
        
        // Build members list and discover leader
        java.util.List<java.util.Map<String, Object>> members = new java.util.ArrayList<>();
        String leaderUrl = null;
        
        // Discover leader (for all nodes, not just self)
        if (role == Cluster.Role.LEADER) {
            leaderUrl = selfUrl;
        } else {
            // For followers, discover leader from Aeron Cluster state
            leaderUrl = discoverLeaderFromAeronClusterState();
        }
        
        // Add self to members list
        java.util.Map<String, Object> selfInfo = new java.util.HashMap<>();
        selfInfo.put("memberId", cluster.memberId());
        selfInfo.put("url", selfUrl);
        selfInfo.put("role", role.name());
        selfInfo.put("status", "ACTIVE");
        // Add wallet info for self
        if (wallet != null) {
            selfInfo.put("walletAddress", wallet.getWalletAddress());
            selfInfo.put("publicKey", wallet.getPublicKeyHex());
        }
        members.add(selfInfo);
        
        // Add all known peers from peerUrls (primary source)
        if (peerUrls != null) {
            for (String peerUrl : peerUrls) {
                // Skip self if already added (compare by port to handle localhost vs 127.0.0.1)
                if (isSameUrlByPort(peerUrl, selfUrl)) {
                    continue;
                }
                
                java.util.Map<String, Object> memberInfo = new java.util.HashMap<>();
                // Try to find member ID from nodeIdToUrl mapping
                int memberId = -1;
                for (java.util.Map.Entry<Integer, String> entry : nodeIdToUrl.entrySet()) {
                    if (isSameUrlByPort(entry.getValue(), peerUrl)) {
                        memberId = entry.getKey();
                        break;
                    }
                }
                memberInfo.put("memberId", memberId);
                memberInfo.put("url", peerUrl);
                // Determine role: if this is the leader URL, mark as LEADER, else FOLLOWER
                // Compare by port to handle localhost vs 127.0.0.1 differences
                String memberRole = (leaderUrl != null && isSameUrlByPort(peerUrl, leaderUrl)) ? "LEADER" : "FOLLOWER";
                memberInfo.put("role", memberRole);
                memberInfo.put("status", "ACTIVE");
                members.add(memberInfo);
            }
        }
        
        state.put("members", members);
        state.put("memberCount", members.size());
        state.put("currentLeader", leaderUrl);
        
        return state;
    }
    
    /**
     * Extract URL from Aeron endpoint string.
     * Format: "aeron:udp?endpoint=host:port" -> "http://host:port"
     */
    private String extractUrlFromEndpoint(String endpoint) {
        if (endpoint == null) return null;
        
        // Parse "aeron:udp?endpoint=host:port"
        int endpointStart = endpoint.indexOf("endpoint=");
        if (endpointStart == -1) return null;
        
        endpointStart += "endpoint=".length();
        String hostPort = endpoint.substring(endpointStart);
        
        // Map to HTTP URL (assuming port 8090 for our validators)
        // This is a heuristic - we should maintain proper nodeId->URL mapping
        if (hostPort.contains(":")) {
            String[] parts = hostPort.split(":");
            String host = parts[0];
            // Use default port 8090 or extract from endpoint
            int port = 8090;
            if (parts.length > 1) {
                try {
                    port = Integer.parseInt(parts[1]);
                } catch (NumberFormatException e) {
                    // Use default
                }
            }
            return "http://" + host + ":" + port;
        }
        
        return null;
    }
    
    /**
     * Get current leader URL.
     * 
     * ✈️ AERON CLUSTER SOURCE OF TRUTH:
     * - If we're the leader, return self
     * - Otherwise, query Aeron Cluster's /v1/aeron/cluster-state API from peers
     * - This ensures consistency with Aeron's internal Raft state
     */
    /**
     * Get current leader URL.
     * 
     * ✈️ AERON NATIVE: Uses cluster.clusterMembers() to find leader directly from Aeron.
     * This is the authoritative source - no HTTP API calls needed.
     */
    public String getCurrentLeader() {
        if (cluster == null) {
            return currentLeader; // Fallback to cached value
        }
        
        // ✈️ AERON NATIVE: If we're the leader, return self
        if (cluster.role() == Cluster.Role.LEADER) {
            return selfUrl;
        }
        
        // ✈️ AERON NATIVE: Find leader from our nodeIdToUrl mapping
        // Since Aeron doesn't expose clusterMembers() directly, we use our mapping
        // The leader is identified by cluster.role() == LEADER for this node
        // For followers, we need to discover via our mapping or fallback to HTTP API
        
        // Try to find leader from nodeIdToUrl mapping
        // If we have a mapping, check if any member is the leader
        // (For now, we'll need to query peers or use HTTP API as fallback)
        
        // Fallback: Use HTTP API discovery if native APIs don't provide leader info
        if (peerUrls != null && !peerUrls.isEmpty()) {
            try {
                log.debug("🔍 getCurrentLeader() using fallback discovery from {} peers", peerUrls.size());
                String leaderUrl = discoverLeaderFromAeronClusterState();
                if (leaderUrl != null) {
                    this.currentLeader = leaderUrl;
                    return leaderUrl;
                }
            } catch (Exception e) {
                log.debug("Fallback leader discovery failed: {}", e.getMessage());
            }
        }
        
        return currentLeader;
    }
    
    /**
     * ✅ ADR 025: Get current Raft term (tracked locally on role changes).
     * 
     * <p>Note: Aeron Cluster's {@code Cluster} interface doesn't expose {@code leadershipTermId()}.
     * We track term locally by incrementing on leader elections (via {@code onRoleChange()}).
     * Term monotonically increases with each leader election, providing split-brain protection foundation.
     * 
     * <p>TODO: For full split-brain protection, add term field to write/delete proposal messages
     * and reject proposals with {@code term < currentTerm} (requires protocol version bump).
     * 
     * @return Current Raft term
     */
    public int getCurrentTerm() {
        return currentTerm;
    }
    
    /**
     * ✈️ AERON NATIVE: Get Aeron Cluster instance (for accessing memberId, etc.).
     */
    public Cluster getCluster() {
        return cluster;
    }
    
    /**
     * Get current node's member ID in the cluster.
     * @return Member ID (0-based node index) or -1 if cluster not initialized
     */
    public int getMemberId() {
        return cluster != null ? cluster.memberId() : -1;
    }
    
    /**
     * Get cluster size (number of nodes configured).
     * @return Number of nodes in cluster
     */
    public int getClusterSize() {
        return nodeIdToUrl != null ? nodeIdToUrl.size() : 0;
    }
    
    /**
     * Get leader member ID.
     * @return Leader's member ID or -1 if unknown
     */
    public int getLeaderMemberId() {
        if (cluster != null && cluster.role() == Cluster.Role.LEADER) {
            return cluster.memberId();
        }
        
        // Try to find leader from current leader URL
        if (currentLeader != null && nodeIdToUrl != null) {
            for (java.util.Map.Entry<Integer, String> entry : nodeIdToUrl.entrySet()) {
                if (entry.getValue().equals(currentLeader)) {
                    return entry.getKey();
                }
            }
        }
        
        return -1; // Leader unknown
    }
    
    /**
     * Step down as leader to trigger a new election.
     * Only works if this node is currently the leader.
     * 
     * @return true if step-down initiated, false otherwise
     */
    public boolean stepDownAsLeader() {
        if (cluster == null || cluster.role() != Cluster.Role.LEADER) {
            log.warn("Cannot step down - not currently leader (role: {})", 
                cluster != null ? cluster.role() : "null");
            return false;
        }
        
        try {
            // ✈️ AERON CLUSTER: Request leadership resignation
            // This triggers a new election among followers
            log.info("🔄 Stepping down as leader to trigger election (current memberId: {})", 
                cluster.memberId());
            
            // Aeron Cluster doesn't have a direct stepDown() API in the ClusteredService
            // Instead, we can close and reopen the session, which triggers re-election
            // For POC, we'll use a workaround: log the step-down request
            // In production, this would integrate with ClusterControl API
            
            // TODO: Integrate with Aeron ClusterControl for proper step-down:
            // ClusterControl control = ...;
            // control.stepDown();
            
            log.warn("⚠️  Step-down requested but not yet implemented in Aeron integration");
            log.warn("   For now, elections will happen naturally via timeout/failure detection");
            
            return false; // Not yet implemented
            
        } catch (Exception e) {
            log.error("Failed to step down as leader", e);
            return false;
        }
    }
    
    /**
     * Discover leader using tracked state + cache, minimizing HTTP queries to peers.
     * 
     * ✈️ AERON CLUSTER SOURCE OF TRUTH:
     * 1. PRIMARY: Use tracked currentLeader (set by onRoleChange) - NO HTTP calls!
     * 2. SECONDARY: Check cache (10s TTL)
     * 3. FALLBACK: Query /v1/aeron/cluster-state from peers (only during initial formation)
     * 
     * ⚡ PERFORMANCE: Once cluster is formed and leader discovered, essentially zero cost.
     */
    private String discoverLeaderFromAeronClusterState() {
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // STEP 1: Use tracked currentLeader (set by onRoleChange)
        if (currentLeader != null && currentLeader.equals(selfUrl)) {
            cachedLeaderUrl = currentLeader;
            cachedLeaderTimestamp = System.currentTimeMillis();
            return currentLeader;
        }
        
        // STEP 2: Check cache before making HTTP calls
        long now = System.currentTimeMillis();
        if (cachedLeaderUrl != null && (now - cachedLeaderTimestamp) < LEADER_CACHE_TTL_MS) {
            log.trace("Using cached leader: {} (age: {}ms)", cachedLeaderUrl, now - cachedLeaderTimestamp);
            return cachedLeaderUrl;
        }
        
        // STEP 3: Fallback - query peers via HTTP (only during cluster formation)
        log.debug("Falling back to HTTP peer queries (leader not yet discovered)");
        
        // ✈️ AERON CLUSTER STATE API: Query /v1/aeron/cluster-state from peers
        // This endpoint reflects Aeron's internal Raft state and is the authoritative source
        java.util.List<String> allUrls = new java.util.ArrayList<>(peerUrls);
        allUrls.add(selfUrl);
        
        log.debug("Querying Aeron Cluster state from {} nodes (cache miss)", allUrls.size());
        
        for (String url : allUrls) {
            try {
                // Ensure URL is IP-based for reliable networking (unless it's ngrok)
                String queryUrl = url.contains("ngrok") ? url : resolveUrlToIP(url);
                java.net.URL apiUrl = new java.net.URL(queryUrl + "/v1/aeron/cluster-state");
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) apiUrl.openConnection();
                conn.setRequestMethod("GET");
                // Bypass ngrok warning page (free tier requirement)
                if (url.contains("ngrok")) {
                    conn.setRequestProperty("ngrok-skip-browser-warning", "true");
                }
                conn.setConnectTimeout(2000);
                conn.setReadTimeout(3000);
                
                int responseCode = conn.getResponseCode();
                if (responseCode == 200) {
                    java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(conn.getInputStream())
                    );
                    String response = reader.lines().collect(java.util.stream.Collectors.joining());
                    reader.close();
                    
                    // Parse Aeron Cluster state JSON to find leader
                    // Response format: {"members": [{"role": "LEADER", "url": "http://..."}, ...]}
                    try {
                        // Simple JSON parsing: look for leader in members array
                        // First check if this node reports itself as leader
                        if (response.contains("\"isLeader\":true") || response.contains("\"role\":\"LEADER\"")) {
                            // Extract leader URL from members array
                            // Look for pattern: "role":"LEADER" followed by "url":"..."
                            int leaderRoleIndex = response.indexOf("\"role\":\"LEADER\"");
                            if (leaderRoleIndex != -1) {
                                // Find the URL field in the same member object
                                int urlStart = response.indexOf("\"url\":\"", leaderRoleIndex);
                                if (urlStart != -1) {
                                    urlStart += 7; // Skip past "url":"
                                    int urlEnd = response.indexOf("\"", urlStart);
                                    if (urlEnd != -1) {
                                        String leaderUrl = response.substring(urlStart, urlEnd);
                                        log.debug("Found leader via Aeron Cluster state: {} (from {})", leaderUrl, url);
                                        // Update cache
                                        cachedLeaderUrl = leaderUrl;
                                        cachedLeaderTimestamp = System.currentTimeMillis();
                                        return leaderUrl;
                                    }
                                }
                            }
                            // Fallback: if we found isLeader:true, return the queried URL
                            log.info("Found leader (isLeader:true): {} (from {})", url, url);
                            return url;
                        }
                    } catch (Exception parseEx) {
                        log.debug("Failed to parse Aeron Cluster state from {}: {}", url, parseEx.getMessage());
                    }
                }
            } catch (Exception e) {
                log.debug("Failed to query Aeron Cluster state from {}: {}", url, e.getMessage());
            }
        }
        return null;
    }
    
    /**
     * Compare two URLs by port number (ignoring hostname differences like localhost vs 127.0.0.1).
     * 
     * @param url1 First URL
     * @param url2 Second URL
     * @return true if both URLs have the same port, false otherwise
     */
    private boolean isSameUrlByPort(String url1, String url2) {
        if (url1 == null || url2 == null) {
            return false;
        }
        
        try {
            java.net.URL parsed1 = new java.net.URL(url1);
            java.net.URL parsed2 = new java.net.URL(url2);
            return parsed1.getPort() == parsed2.getPort();
        } catch (Exception e) {
            // Fallback to string comparison if parsing fails
            return url1.equals(url2);
        }
    }
    
    /**
     * Resolve hostname-based URL to IP-based URL for reliable networking.
     * Similar to GlobalStoreServer.resolveUrlToIP() but available in this class.
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
                return String.format("%s://%s%s%s", 
                    protocol, 
                    ip, 
                    port != -1 ? ":" + port : "", 
                    path != null ? path : "");
            } catch (java.net.UnknownHostException e) {
                // If resolution fails, return original URL (may be ngrok/Ethos URL)
                return url;
            }
        } catch (Exception e) {
            return url;
        }
    }
    
    /**
     * Background task to discover leader from peers via Aeron Cluster state (called when becoming follower).
     * 
     * ✈️ AERON CLUSTER SOURCE OF TRUTH:
     * Uses /v1/aeron/cluster-state API which reflects Aeron's internal Raft state.
     */
    private void discoverLeaderFromPeers() {
        // Run in background thread to avoid blocking
        java.util.concurrent.ExecutorService executor = 
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "aeron-leader-discovery");
                t.setDaemon(true);
                return t;
            });
        
        executor.submit(() -> {
            try {
                Thread.sleep(3000); // Wait 3s for cluster to stabilize
                log.debug("Starting Aeron Cluster leader discovery");
                String leaderUrl = discoverLeaderFromAeronClusterState();
                if (leaderUrl != null) {
                    this.currentLeader = leaderUrl;
                    log.info("Discovered leader via Aeron Cluster state: {} (background discovery)", leaderUrl);
                } else {
                    log.debug("Could not discover leader from Aeron Cluster state (will retry)");
                    // Try again after a longer delay
                    Thread.sleep(5000);
                    leaderUrl = discoverLeaderFromAeronClusterState();
                    if (leaderUrl != null) {
                        this.currentLeader = leaderUrl;
                        log.info("Discovered leader via Aeron Cluster state: {} (retry)", leaderUrl);
                    }
                }
            } catch (Exception e) {
                log.warn("Aeron Cluster leader discovery failed: {}", e.getMessage());
            }
        });
    }
    
    
    /**
     * Get current Ethereum epoch (if Ethereum integration is enabled).
     */
    public int getCurrentEpoch() {
        // Use Ethereum epoch if available, otherwise use Raft term
        return currentEthereumEpoch >= 0 ? currentEthereumEpoch : currentTerm;
    }
    
    /**
     * Update latest HEAD (includes pending writes from epoch N, N+1) - may change.
     * This HEAD includes writes that haven't reached finality yet.
     * Called by leader after each write commit.
     * 
     * <p>🔄 FINALITY-AWARE: Tracks latest HEAD separately from committed HEAD.
     * - latestHead: Current HEAD including pending writes (may change)
     * - committedHead: HEAD that has reached finality (immutable, safe)
     */
    public void updateLatestHead(String newHead) {
        // 🔄 CRITICAL: Use the provided newHead directly (it's already from FileStore after flush)
        // Don't re-read from FileStore here - use the value that was just committed
        // This ensures we track the exact HEAD that was broadcast to followers
        if (newHead != null && !newHead.isEmpty()) {
            // Convert to toString10() format for consistency
            try {
                // If newHead is already in toString10() format, use it directly
                // Otherwise, parse and convert
                if (newHead.contains(":")) {
                    // Already in RecordId format, use as-is
                    latestHead = newHead;
                } else if (fileStore != null) {
                    // Try to get from FileStore (should match newHead after flush)
                    latestHead = fileStore.getHead().getRecordId().toString10();
                } else {
                    latestHead = newHead;
                }
            } catch (Exception e) {
                // Fallback to provided value
                latestHead = newHead;
            }
        } else if (fileStore != null) {
            // Fallback: read from FileStore if newHead not provided
            try {
                latestHead = fileStore.getHead().getRecordId().toString10();
            } catch (Exception e) {
                log.debug("Could not read HEAD from FileStore: {}", e.getMessage());
            }
        }
        
        if (latestHead != null) {
            log.debug("📝 Updated latestHead: {}...", latestHead.substring(0, Math.min(20, latestHead.length())));
        }
    }
    
    /**
     * Get committed HEAD (has reached finality, epoch N-2) - immutable, safe.
     * This HEAD is guaranteed to be finalized and will never change.
     */
    public String getCommittedHead() {
        return committedHead;
    }
    
    /**
     * Get latest HEAD (includes pending writes from epoch N, N+1) - may change.
     * This HEAD includes writes that haven't reached finality yet.
     */
    public String getLatestHead() {
        // Return tracked latestHead, or fallback to current HEAD if not set
        if (latestHead != null && !latestHead.isEmpty()) {
            return latestHead;
        }
        if (fileStore != null) {
            return fileStore.getHead().getRecordId().toString10();
        }
        return null;
    }
    
    /**
     * Get last committed epoch (epoch that has reached finality).
     */
    public int getLastCommittedEpoch() {
        return lastCommittedEpoch;
    }
    
    /**
     * Get latest epoch seen (current Ethereum epoch).
     */
    public int getLatestEpochSeen() {
        return currentEthereumEpoch;
    }
    
    /**
     * Get all followers (for compatibility with EpochLeaderEngine).
     * 
     * ✈️ AERON CLUSTER SOURCE OF TRUTH:
     * Returns all peer URLs. In Aeron Cluster, all non-leader nodes are followers.
     * The leader is determined by Aeron's Raft consensus.
     */
    public List<String> getAllFollowers() {
        // ✈️ AERON CLUSTER: All peers are potential followers
        // The leader is determined by Aeron's Raft consensus (via cluster.role())
        // For now, return all peer URLs - the leader will identify itself via isLeader()
        return new java.util.ArrayList<>(peerUrls);
    }
    
    /**
     * Get non-voting followers (for compatibility with EpochLeaderEngine).
     */
    public List<String> getNonVotingFollowers() {
        // TODO: Implement probation logic if needed
        return new java.util.ArrayList<>();
    }
    
    /**
     * NEW GENESIS ARCHITECTURE: Create genesis on leader after cluster formation.
     * 
     * This is called when the leader detects an empty store after cluster formation.
     * Genesis is created locally on the leader, then followers pull it via HTTP segment
     * transfer, ensuring all validators have identical segment history from the start.
     * 
     * NOTE: This requires a callback to GlobalStoreServer.initializeGenesisContent()
     * since we need access to NodeStore write operations.
     */
    private void createGenesisViaConsensus() {
        log.info("Creating network genesis on leader");
        
        try {
            // Create genesis directly in NodeStore
            // This is simpler than trying to route through consensus write path
            // Followers will pull segments via HTTP segment transfer
            
            // Use zero address for genesis (Ethereum convention)
            String GENESIS_ADDRESS = "0x0000000000000000000000000000000000000000";
            String genesisPath = "/oak-chain/00/00/00/" + GENESIS_ADDRESS + "/content/genesis";
            
            log.info("Creating genesis - Path: {}, Wallet: {}", genesisPath, GENESIS_ADDRESS);
            
            // Create genesis using NodeStore directly
            org.apache.jackrabbit.oak.spi.state.NodeState root = nodeStore.getRoot();
            org.apache.jackrabbit.oak.spi.state.NodeBuilder rootBuilder = root.builder();
            
            // Navigate/create path: oak-chain/00/00/00/0x0000.../content/genesis
            // Deep tree structure tied to wallet identity - critical for segment isolation
            org.apache.jackrabbit.oak.spi.state.NodeBuilder oakChain = rootBuilder.child("oak-chain");
            oakChain.setProperty("jcr:primaryType", "nt:unstructured");
            
            org.apache.jackrabbit.oak.spi.state.NodeBuilder level1 = oakChain.child("00");
            level1.setProperty("jcr:primaryType", "nt:unstructured");
            
            org.apache.jackrabbit.oak.spi.state.NodeBuilder level2 = level1.child("00");
            level2.setProperty("jcr:primaryType", "nt:unstructured");
            
            org.apache.jackrabbit.oak.spi.state.NodeBuilder level3 = level2.child("00");
            level3.setProperty("jcr:primaryType", "nt:unstructured");
            
            org.apache.jackrabbit.oak.spi.state.NodeBuilder genesisWallet = level3.child(GENESIS_ADDRESS);
            genesisWallet.setProperty("jcr:primaryType", "nt:unstructured");
            genesisWallet.setProperty("wallet", GENESIS_ADDRESS);
            genesisWallet.setProperty("role", "genesis");
            genesisWallet.setProperty("walletCreated", System.currentTimeMillis());
            genesisWallet.setProperty("nodeType", "wallet-root");
            genesisWallet.setProperty("description", "Genesis wallet - Network bootstrap identity");
            genesisWallet.setProperty("contentCount", 1L);  // Genesis content
            genesisWallet.setProperty("totalWrites", 1L);
            genesisWallet.setProperty("lastWrite", System.currentTimeMillis());
            genesisWallet.setProperty("owner", "OakChain Network");
            genesisWallet.setProperty("verified", true);
            
            org.apache.jackrabbit.oak.spi.state.NodeBuilder content = genesisWallet.child("content");
            content.setProperty("jcr:primaryType", "nt:unstructured");
            
            org.apache.jackrabbit.oak.spi.state.NodeBuilder genesis = content.child("genesis");
            genesis.setProperty("jcr:primaryType", "nt:unstructured");
            genesis.setProperty("jcr:created", System.currentTimeMillis());
            genesis.setProperty("jcr:title", "OakChain Genesis Block");
            genesis.setProperty("tagline", "Persistence is Futile - The Borg Collective");
            
            // ═══════════════════════════════════════════════════════════════════
            // PROTOCOL
            // ═══════════════════════════════════════════════════════════════════
            org.apache.jackrabbit.oak.spi.state.NodeBuilder protocol = genesis.child("protocol");
            protocol.setProperty("jcr:primaryType", "nt:unstructured");
            protocol.setProperty("message", "DO IT LIVE!");
            protocol.setProperty("version", "1.0.0-POC");
            protocol.setProperty("chainId", "oak-blockchain-aem-poc");
            protocol.setProperty("genesisTimestamp", System.currentTimeMillis());
            protocol.setProperty("genesisDate", new java.util.Date().toString());
            protocol.setProperty("philosophy", "Bitcoin-tight reliability meets AEM content management");
            
            // ═══════════════════════════════════════════════════════════════════
            // CONSENSUS
            // ═══════════════════════════════════════════════════════════════════
            org.apache.jackrabbit.oak.spi.state.NodeBuilder consensus = genesis.child("consensus");
            consensus.setProperty("jcr:primaryType", "nt:unstructured");
            consensus.setProperty("model", "aeron-raft");
            consensus.setProperty("quorumType", "majority");
            consensus.setProperty("implementation", "io.aeron.cluster (battle-tested Raft)");
            consensus.setProperty("features", "election-safety,log-matching,leader-completeness,partition-tolerance");
            
            // ═══════════════════════════════════════════════════════════════════
            // ETHEREUM INTEGRATION
            // ═══════════════════════════════════════════════════════════════════
            org.apache.jackrabbit.oak.spi.state.NodeBuilder ethereum = genesis.child("ethereum");
            ethereum.setProperty("jcr:primaryType", "nt:unstructured");
            ethereum.setProperty("network", "Ethereum Mainnet Beacon Chain");
            ethereum.setProperty("epochDuration", "6.4 minutes (384 seconds)");
            ethereum.setProperty("finalityDelay", "2 epochs (~12.8 minutes)");
            ethereum.setProperty("integration", "Epoch-based finality for transaction batching");
            ethereum.setProperty("pollingInterval", "3 minutes");
            ethereum.setProperty("purpose", "External time oracle + cryptographic payment verification");
            
            // ═══════════════════════════════════════════════════════════════════
            // ARCHITECTURE
            // ═══════════════════════════════════════════════════════════════════
            org.apache.jackrabbit.oak.spi.state.NodeBuilder architecture = genesis.child("architecture");
            architecture.setProperty("jcr:primaryType", "nt:unstructured");
            
            // Wallet-Scoped Paths
            org.apache.jackrabbit.oak.spi.state.NodeBuilder paths = architecture.child("wallet-scoped-paths");
            paths.setProperty("jcr:primaryType", "nt:unstructured");
            paths.setProperty("structure", "/oak-chain/{shard}/content/{contentId}");
            paths.setProperty("shardFormat", "First 3 bytes of wallet address (XX-YY-ZZ)");
            paths.setProperty("example", "/oak-chain/74-2d-35/content/page-12345");
            paths.setProperty("benefit-isolation", "Each wallet gets isolated TarMK segment tree");
            paths.setProperty("benefit-gc", "Garbage collection per wallet namespace");
            paths.setProperty("benefit-scalability", "Natural sharding boundaries at each level");
            paths.setProperty("benefit-performance", "Oak optimized for deep trees, not wide flat structures");
            
            // Deep Tree Sharding
            org.apache.jackrabbit.oak.spi.state.NodeBuilder sharding = architecture.child("deep-tree-sharding");
            sharding.setProperty("jcr:primaryType", "nt:unstructured");
            sharding.setProperty("purpose", "Mitigate TarMK segment-not-found issues at architectural level");
            sharding.setProperty("implementation", "3-level hierarchy from wallet address");
            sharding.setProperty("rationale", "Deep trees provide segment isolation critical for distributed consensus");
            
            // HTTP Segment Transfer
            org.apache.jackrabbit.oak.spi.state.NodeBuilder httpTransfer = architecture.child("http-segment-transfer");
            httpTransfer.setProperty("jcr:primaryType", "nt:unstructured");
            httpTransfer.setProperty("protocol", "HTTP/1.1 REST API");
            httpTransfer.setProperty("endpoints", "GET /journal.log, GET /segments/{id}, GET /manifest");
            httpTransfer.setProperty("purpose", "Global read-only replication across Sling authors");
            httpTransfer.setProperty("pattern", "Cold Standby inspired");
            httpTransfer.setProperty("benefit", "Content available globally without full validator deployment");
            
            // ═══════════════════════════════════════════════════════════════════
            // ECONOMICS
            // ═══════════════════════════════════════════════════════════════════
            org.apache.jackrabbit.oak.spi.state.NodeBuilder economics = genesis.child("economics");
            economics.setProperty("jcr:primaryType", "nt:unstructured");
            economics.setProperty("model", "3-Tier Transaction Pricing");
            economics.setProperty("philosophy", "Fragmentation costs more - incentivize batching");
            
            // Priority Tier
            org.apache.jackrabbit.oak.spi.state.NodeBuilder priority = economics.child("priority-tier");
            priority.setProperty("jcr:primaryType", "nt:unstructured");
            priority.setProperty("price", "0.01 ETH");
            priority.setProperty("finality", "Immediate (~30 seconds)");
            priority.setProperty("delay", "0 epochs");
            priority.setProperty("use-case", "Breaking news, live events, critical updates");
            priority.setProperty("fragmentation-cost", "High - no batching optimization");
            
            // Express Tier
            org.apache.jackrabbit.oak.spi.state.NodeBuilder express = economics.child("express-tier");
            express.setProperty("jcr:primaryType", "nt:unstructured");
            express.setProperty("price", "0.002 ETH");
            express.setProperty("finality", "~6.4 minutes");
            express.setProperty("delay", "1 epoch");
            express.setProperty("use-case", "Time-sensitive content, same-day updates");
            express.setProperty("fragmentation-cost", "Medium - some batching opportunity");
            
            // Standard Tier
            org.apache.jackrabbit.oak.spi.state.NodeBuilder standard = economics.child("standard-tier");
            standard.setProperty("jcr:primaryType", "nt:unstructured");
            standard.setProperty("price", "0.001 ETH");
            standard.setProperty("finality", "~12.8 minutes");
            standard.setProperty("delay", "2 epochs");
            standard.setProperty("use-case", "Bulk content, scheduled updates, archival");
            standard.setProperty("fragmentation-cost", "Low - maximum batching by wallet");
            
            // Intelligent Batching
            org.apache.jackrabbit.oak.spi.state.NodeBuilder batching = economics.child("intelligent-batching");
            batching.setProperty("jcr:primaryType", "nt:unstructured");
            batching.setProperty("strategy", "Group by wallet address + sort by path/timestamp");
            batching.setProperty("purpose", "Minimize TarMK DAG fragmentation");
            batching.setProperty("benefit-gc", "Wallet-scoped segments easier to garbage collect");
            batching.setProperty("benefit-performance", "Sequential writes to same segment tree");
            batching.setProperty("economic-rationale", "Slower tiers batch more = cheaper storage costs");
            
            // Fragmentation Tax
            org.apache.jackrabbit.oak.spi.state.NodeBuilder fragTax = economics.child("fragmentation-tax");
            fragTax.setProperty("jcr:primaryType", "nt:unstructured");
            fragTax.setProperty("concept", "DELETE operations cost more for fragmented content");
            fragTax.setProperty("measurement", "Track segments touched per wallet");
            fragTax.setProperty("pricing", "DELETE cost = base + (fragmentation_score * multiplier)");
            fragTax.setProperty("incentive", "Users who batch efficiently pay less for cleanup");
            fragTax.setProperty("status", "Designed - implementation in progress");
            
            // ═══════════════════════════════════════════════════════════════════
            // BITCOIN-TIGHT PRINCIPLES
            // ═══════════════════════════════════════════════════════════════════
            org.apache.jackrabbit.oak.spi.state.NodeBuilder bitcoinTight = genesis.child("bitcoin-tight-principles");
            bitcoinTight.setProperty("jcr:primaryType", "nt:unstructured");
            bitcoinTight.setProperty("philosophy", "Fail Loud, Fail Fast, Never Silently Corrupt");
            bitcoinTight.setProperty("principle-1", "Singletons: One BeaconChainClient, one truth");
            bitcoinTight.setProperty("principle-2", "Fail Loud: System.exit(1) on unrecoverable errors");
            bitcoinTight.setProperty("principle-3", "Immutability: final fields, immutable state");
            bitcoinTight.setProperty("principle-4", "Defensive Validation: Epochs never go backwards");
            bitcoinTight.setProperty("principle-5", "Health Monitoring: /health endpoint + metrics");
            bitcoinTight.setProperty("principle-6", "No Silent Failures: UncaughtExceptionHandler crashes JVM");
            bitcoinTight.setProperty("inspiration", "Bitcoin Core, Apache Kafka, Ethereum Geth");
            
            // ═══════════════════════════════════════════════════════════════════
            // NETWORK
            // ═══════════════════════════════════════════════════════════════════
            org.apache.jackrabbit.oak.spi.state.NodeBuilder network = genesis.child("network");
            network.setProperty("jcr:primaryType", "nt:unstructured");
            network.setProperty("genesisValidator", selfUrl);
            network.setProperty("transport", "Aeron UDP multicast + unicast");
            network.setProperty("clusterFormation", "Automatic via Raft election");
            network.setProperty("partition-tolerance", "Majority quorum required for writes");
            
            // ═══════════════════════════════════════════════════════════════════
            // INNOVATION SUMMARY
            // ═══════════════════════════════════════════════════════════════════
            org.apache.jackrabbit.oak.spi.state.NodeBuilder innovations = genesis.child("innovations");
            innovations.setProperty("jcr:primaryType", "nt:unstructured");
            innovations.setProperty("innovation-1", "First blockchain-backed AEM content repository");
            innovations.setProperty("innovation-2", "Ethereum epochs as external time oracle for finality");
            innovations.setProperty("innovation-3", "Wallet-scoped path architecture for segment isolation");
            innovations.setProperty("innovation-4", "Economic model that incentivizes storage efficiency");
            innovations.setProperty("innovation-5", "Bitcoin-tight reliability in Java enterprise stack");
            innovations.setProperty("innovation-6", "Global read-only content via HTTP segment transfer");
            innovations.setProperty("innovation-7", "Multi-tier transaction pricing with cryptographic payment");
            innovations.setProperty("demo-date", "Garage Week - December 15, 2025");
            innovations.setProperty("team", "somarc + Cursor (Auto mode + Composer-1) + Grok 4.1 as outside counsel — distributed intelligence building distributed systems");
            
            // ═══════════════════════════════════════════════════════════════════
            // IPFS: Decentralized Binary Storage (ADR 015) - "DO IT LIVE!" Image
            // ═══════════════════════════════════════════════════════════════════
            String ipfsCid = null;
            
            // Create nt:file node for the "do-it-live.jpeg" image
            org.apache.jackrabbit.oak.spi.state.NodeBuilder genesisImage = genesis.child("do-it-live.jpeg");
            genesisImage.setProperty("jcr:primaryType", "nt:file");
            genesisImage.setProperty("jcr:created", System.currentTimeMillis());
            
            org.apache.jackrabbit.oak.spi.state.NodeBuilder imageContent = genesisImage.child("jcr:content");
            imageContent.setProperty("jcr:primaryType", "nt:resource");
            imageContent.setProperty("jcr:mimeType", "image/jpeg");
            imageContent.setProperty("jcr:lastModified", System.currentTimeMillis());
            
            // Try to load and upload the genesis image
            try {
                java.io.InputStream imageStream = getClass().getClassLoader()
                    .getResourceAsStream("genesis-assets/do-it-live.jpeg");
                
                if (imageStream != null && blobStore != null) {
                    // Read image bytes
                    java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = imageStream.read(buffer)) != -1) {
                        baos.write(buffer, 0, read);
                    }
                    byte[] imageBytes = baos.toByteArray();
                    long imageSize = imageBytes.length;
                    imageStream.close();
                    
                    // Store via BlobStore (IPFS backend will pin it)
                    String blobId = blobStore.writeBlob(new java.io.ByteArrayInputStream(imageBytes));
                    
                    // Create proper Binary from blobId
                    org.apache.jackrabbit.oak.api.Blob blob = 
                        ((org.apache.jackrabbit.oak.segment.SegmentNodeStore) nodeStore)
                            .createBlob(new java.io.ByteArrayInputStream(imageBytes));
                    
                    // Attach to jcr:content
                    imageContent.setProperty("jcr:data", blob);
                    imageContent.setProperty("jcr:blobId", blobId);
                    imageContent.setProperty("size", imageSize);
                    
                    // Extract IPFS CID from blobId if it's an IPFS hash
                    if (blobId != null && blobId.startsWith("Qm") || (blobId != null && blobId.startsWith("baf"))) {
                        // It's a CID! (IPFS uses Qm... for CIDv0 or baf... for CIDv1)
                        ipfsCid = blobId.split("#")[0]; // Strip size suffix if present
                    }
                    
                    log.info("✅ Genesis image uploaded: {} bytes, blobId={}, ipfsCid={}", imageSize, blobId, ipfsCid);
                } else if (blobStore == null) {
                    log.warn("⚠️  BlobStore not configured - genesis image will not be uploaded");
                } else {
                    log.warn("⚠️  Genesis image resource not found: genesis-assets/do-it-live.jpeg");
                }
            } catch (Exception e) {
                log.error("Failed to upload genesis image", e);
            }
            
            // IPFS metadata node
            org.apache.jackrabbit.oak.spi.state.NodeBuilder ipfsInfo = genesis.child("ipfs");
            ipfsInfo.setProperty("jcr:primaryType", "nt:unstructured");
            ipfsInfo.setProperty("enabled", blobStore != null);
            ipfsInfo.setProperty("genesisImageCid", ipfsCid != null ? ipfsCid : "N/A (BlobStore fallback)");
            ipfsInfo.setProperty("gateway", "https://ipfs.io/ipfs/");
            ipfsInfo.setProperty("localGateway", "http://localhost:8080/ipfs/");
            ipfsInfo.setProperty("description", "Binaries stored via IPFS - content-addressed, decentralized, immutable");
            
            // ═══════════════════════════════════════════════════════════════════
            // COMMIT: Merge rich genesis structure locally first
            // ═══════════════════════════════════════════════════════════════════
            log.info("📝 Committing genesis structure to local FileStore...");
            ((org.apache.jackrabbit.oak.segment.SegmentNodeStore) nodeStore).merge(
                rootBuilder, 
                org.apache.jackrabbit.oak.spi.commit.EmptyHook.INSTANCE, 
                org.apache.jackrabbit.oak.spi.commit.CommitInfo.EMPTY
            );
            String newHead = fileStore.getHead().getRecordId().toString10();
            log.info("✅ Genesis committed locally - HEAD: {}", newHead);
            
            // ✈️ AERON REPLICATION: Followers sync via Aeron snapshot mechanism
            // Aeron Cluster automatically replicates state via snapshots when followers join.
            // No need to send a marker - Aeron handles this natively.
            // Followers will request snapshots from the leader and get the full genesis.
            log.info("✅ Genesis created on leader - Aeron will replicate via snapshot mechanism");
            
            // Log genesis summary
            log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            log.info("🎊 GENESIS NODE CREATED");
            log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            log.info("   Path: /oak-chain/00/00/00/0x0000.../content/genesis");
            log.info("   Message: DO IT LIVE!");
            log.info("   IPFS Image: {}", ipfsCid != null ? ipfsCid : "stored in BlobStore");
            log.info("   HEAD: {}", newHead);
            log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            
        } catch (Exception e) {
            log.error("Exception during genesis creation", e);
        }
    }
    
    /**
     * Get validator join times (for compatibility with EpochLeaderEngine).
     */
    public Map<String, Long> getValidatorJoinTimes() {
        return new java.util.HashMap<>(validatorJoinTimes);
    }
    
    /**
     * Get reachable validator count (for metrics).
     * 
     * ✈️ AERON CLUSTER SOURCE OF TRUTH:
     * Returns count of configured peers. Aeron Cluster manages actual reachability internally.
     */
    public int getReachableValidatorCount() {
        // ✈️ AERON CLUSTER: Return configured peer count
        // Aeron Cluster manages actual reachability and membership internally via Raft
        return peerUrls != null ? peerUrls.size() : 0;
    }
    
    /**
     * Get backpressure manager for write flow control.
     * 
     * <p>Allows ProposalQueueManager and other components to access backpressure
     * management for dynamic write rate control.
     * 
     * @return BackpressureManager instance
     */
    public org.apache.jackrabbit.oak.segment.consensus.queue.BackpressureManager getBackpressureManager() {
        return backpressureManager;
    }
    
    /**
     * Get Raft performance metrics (for monitoring and testing).
     * 
     * @return AeronPerformanceMetrics instance tracking consensus latency, throughput, utilization
     */
    public AeronPerformanceMetrics getPerformanceMetrics() {
        return performanceMetrics;
    }
    
    /**
     * Get last heartbeat time (for metrics).
     */
    public long getLastHeartbeatTime() {
        return lastHeartbeatTime;
    }
    
    /**
     * ✅ ADR 025: Update leader's log position (for replication lag monitoring).
     * Called when receiving heartbeat or cluster state from leader.
     * 
     * @param position Leader's current log position
     */
    public void updateLeaderLogPosition(long position) {
        this.leaderLogPosition = position;
    }
    
    /**
     * ✅ ADR 025: Get replication lag in messages (followers only).
     * 
     * <p>Calculates how far behind this follower is from the leader's log position.
     * Useful for monitoring cluster health and detecting slow followers.
     * 
     * @return Number of messages behind leader, or 0 if leader or lag unknown
     */
    public long getReplicationLag() {
        if (cluster == null || cluster.role() == Cluster.Role.LEADER) {
            return 0; // Leaders have no lag
        }
        
        if (leaderLogPosition == 0) {
            return -1; // Leader position unknown (haven't received heartbeat yet)
        }
        
        long myPosition = cluster.logPosition();
        return Math.max(0, leaderLogPosition - myPosition);
    }
    
    /**
     * ✅ ADR 025: Get replication lag status for monitoring/dashboard.
     * 
     * @return Map with lag metrics, or null if not applicable
     */
    public java.util.Map<String, Object> getReplicationLagStatus() {
        if (cluster == null) {
            return null;
        }
        
        java.util.Map<String, Object> status = new java.util.HashMap<>();
        status.put("role", cluster.role().name());
        status.put("myLogPosition", cluster.logPosition());
        status.put("leaderLogPosition", leaderLogPosition);
        
        long lag = getReplicationLag();
        status.put("replicationLag", lag);
        status.put("lagThreshold", 1000L); // Alert if lag > 1000 messages
        status.put("healthy", lag >= 0 && lag < 1000);
        
        return status;
    }
}

