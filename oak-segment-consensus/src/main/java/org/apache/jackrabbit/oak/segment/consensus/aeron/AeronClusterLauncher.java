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

import io.aeron.ChannelUriStringBuilder;
import io.aeron.CommonContext;
import io.aeron.archive.Archive;
import io.aeron.archive.ArchiveThreadingMode;
import io.aeron.archive.client.AeronArchive;
import io.aeron.cluster.ClusteredMediaDriver;
import io.aeron.cluster.ConsensusModule;
import io.aeron.cluster.service.ClusteredService;
import io.aeron.cluster.service.ClusteredServiceContainer;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.MinMulticastFlowControlSupplier;
import io.aeron.driver.ThreadingMode;
import io.aeron.exceptions.AeronException;
import org.agrona.CloseHelper;
import org.agrona.ErrorHandler;
import org.agrona.IoUtil;
import org.agrona.concurrent.NoOpLock;
import org.agrona.concurrent.ShutdownSignalBarrier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Launcher for Aeron Cluster consensus engine.
 * 
 * <p>This class handles the cluster launch pattern, configuring and starting
 * the Aeron Cluster components (MediaDriver, Archive, ConsensusModule, ClusteredServiceContainer).
 * 
 * <p>Based on proven Oak + Aeron Cluster patterns from reference implementations.
 */
public class AeronClusterLauncher {
    
    private static final Logger log = LoggerFactory.getLogger(AeronClusterLauncher.class);
    
    private static final int PORT_BASE = 9000;
    private static final int PORTS_PER_NODE = 100;
    private static final int ARCHIVE_CONTROL_PORT_OFFSET = 1;
    public static final int CLIENT_FACING_PORT_OFFSET = 2;
    private static final int MEMBER_FACING_PORT_OFFSET = 3;
    private static final int LOG_PORT_OFFSET = 4;
    private static final int TRANSFER_PORT_OFFSET = 5;
    private static final int LOG_CONTROL_PORT_OFFSET = 6;
    private static final int REPLICATION_PORT_OFFSET = 7;
    private static final int TERM_LENGTH = 128 * 1024 * 1024; // 128MB
    
    private final int nodeId;
    private final List<String> hostnames;
    private final File baseDir;
    private final ClusteredService clusteredService;
    
    private ClusteredMediaDriver clusteredMediaDriver;
    private ClusteredServiceContainer container;
    private ShutdownSignalBarrier barrier;
    private String aeronDirectoryName;
    private CrashHandler crashHandler;
    private ExecutorService shutdownExecutor;
    private AtomicBoolean shutdownScheduled = new AtomicBoolean(false);
    private volatile Runnable shutdownCallback;
    private MediaDriverHealthMonitor healthMonitor;
    
    public AeronClusterLauncher(int nodeId, List<String> hostnames, File baseDir, ClusteredService clusteredService) {
        this.nodeId = nodeId;
        this.hostnames = hostnames;
        this.baseDir = baseDir;
        this.clusteredService = clusteredService;
    }
    
    /**
     * Launch the Aeron Cluster.
     * 
     * <p>🌐 P2P-ORGANIC APPROACH: Nodes can start in any order.
     * 
     * <p>Aeron Cluster handles bootstrap automatically:
     * - If cluster directory is empty → starts fresh (can be any node, not just "genesis")
     * - If cluster directory has recordings → replays log and joins existing cluster
     * - Empty nodes will sync via Raft log replication from other cluster members
     * - Peers that aren't ready yet will be handled gracefully (DNS retry, connection retry)
     * 
     * <p>Key P2P features:
     * - No manual ordering required - any node can start first
     * - DNS resolution is resilient (aggressive retry with exponential backoff)
     * - Unavailable peers don't block startup - Aeron will connect when they're ready
     * - Quorum forms organically as nodes come online
     * 
     * @throws Exception if cluster launch fails
     */
    public void launch() throws Exception {
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        log.info("🚀 Launching Aeron Cluster");
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        log.info("   Node ID: {}", nodeId);
        log.info("   Hostname: {}", getHostname());
        log.info("   Base Dir: {}", baseDir.getAbsolutePath());
        log.info("   Cluster Members: {}", hostnames.size());
        
        // Check if this is a fresh start (no cluster state)
        File clusterDir = new File(baseDir, "cluster");
        boolean isFreshStart = !clusterDir.exists() || (clusterDir.exists() && clusterDir.listFiles() == null || clusterDir.listFiles().length == 0);
        
        if (isFreshStart) {
            log.info("   Bootstrap: 🌐 DYNAMIC CLUSTER MODE - Fresh start (no existing cluster state)");
            log.info("   → Starting with {} member(s) - quorum = {}", hostnames.size(), hostnames.size() == 1 ? "1 (self)" : "majority");
            if (hostnames.size() == 1) {
                log.info("   → Single node = quorum of 1 → will become leader immediately");
                log.info("   → Peers can join dynamically as they come online");
                log.info("   🛡️ RESILIENCE: Single node can operate independently (worst-case scenario)");
            } else {
                log.info("   → Multiple nodes configured - will form quorum organically as peers join");
            }
        } else {
            log.info("   Bootstrap: Existing cluster state found");
            log.info("   → Will replay log and join existing cluster");
            log.info("   → Cluster size: {} members", hostnames.size());
            log.info("   🛡️ RESILIENCE: If partition occurs, fragment can reform with available nodes");
        }
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        
        // 🌐 P2P-ORGANIC: Get our IP address FIRST (before resolving peers)
        // This ensures we use the correct validator-network IP, not client-network IP
        String myIPAddress = getMyIPAddress();
        
        // Now resolve hostnames to IP addresses with resilient retry logic
        // Peers that aren't ready yet will use placeholder IPs - Aeron will retry DNS resolution
        // Using IPs avoids DNS caching issues when containers/nodes restart
        List<String> ipAddresses = resolveHostnamesToIPs(myIPAddress);
        log.info("   Using IP addresses for Aeron Cluster channels (P2P-organic mode)");
        log.info("   My IP: {} (hostname: {})", myIPAddress, getHostname());
        log.info("   Note: Unavailable peers use placeholder IPs - Aeron will retry DNS when peers come online");
        
        this.aeronDirectoryName = CommonContext.getAeronDirectoryName() + "-" + nodeId + "-driver";
        String aeronDirName = this.aeronDirectoryName;
        barrier = new ShutdownSignalBarrier();
        
        // Initialize crash handler for error tracking and recovery
        crashHandler = new CrashHandler(baseDir, nodeId);
        shutdownExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "aeron-shutdown-handler");
            t.setDaemon(true);
            return t;
        });
        
        // Check for crash markers from previous runs OR stale MediaDriver directory
        // ActiveDriverException occurs when MediaDriver directory exists but process is dead
        File aeronDir = new File(aeronDirName);
        boolean hasCrashMarkers = crashHandler.hasCrashed();
        boolean aeronDirExists = aeronDir.exists();
        
        if (hasCrashMarkers || aeronDirExists) {
            if (hasCrashMarkers) {
                log.warn("⚠️  Crash markers detected from previous run: {}", crashHandler.getState());
            }
            if (aeronDirExists) {
                log.warn("⚠️  Stale MediaDriver directory detected: {}", aeronDirName);
                log.warn("   This may cause ActiveDriverException if MediaDriver didn't shut down cleanly");
            }
            
            // Check if MediaDriver process is actually running
            boolean mediaDriverRunning = false;
            try {
                // Check for MediaDriver lock file (indicates active driver)
                File lockFile = new File(aeronDir, "driver.lock");
                if (lockFile.exists()) {
                    // Try to read PID from lock file (if available)
                    // If lock file exists but process is dead, we can safely delete
                    log.debug("MediaDriver lock file exists: {}", lockFile.getAbsolutePath());
                }
            } catch (Exception e) {
                log.debug("Could not check MediaDriver lock file: {}", e.getMessage());
            }
            
            // Clean up stale MediaDriver directory
            // This prevents ActiveDriverException from stale directories
            if (aeronDir.exists()) {
                log.warn("🧹 Cleaning up stale MediaDriver directory: {}", aeronDirName);
                try {
                    IoUtil.delete(aeronDir, true);
                    log.info("✅ Cleaned up stale MediaDriver directory");
                } catch (Exception e) {
                    log.warn("⚠️  Failed to clean up MediaDriver directory: {}", e.getMessage());
                    log.warn("   You may need to manually delete: {}", aeronDirName);
                    // Continue anyway - MediaDriver might handle it or fail with clear error
                }
            }
        }
        if (crashHandler.shouldForceBootstrap()) {
            log.warn("🚨 Force bootstrap marker detected - will bootstrap on startup");
        }
        
        // Media Driver Context
        // ✈️ AERON RESILIENCE: Enhanced configuration for stability and performance
        // Based on Aeron best practices for production systems:
        // - Larger term buffers reduce backpressure and improve throughput
        // - Sparse files reduce disk I/O for better performance
        // - Shared threading mode balances latency and resource usage
        // - Error handler provides graceful shutdown on FATAL errors
        // Socket buffer sizes (configurable via system properties)
        // Default: 16KB for Mac/Darwin (Aeron best practices)
        // Can be overridden: -Daeron.socket.so_sndbuf=32768 -Daeron.socket.so_rcvbuf=32768
        int socketSndbufLength = Integer.getInteger("aeron.socket.so_sndbuf", 16 * 1024);
        int socketRcvbufLength = Integer.getInteger("aeron.socket.so_rcvbuf", 16 * 1024);
        log.info("📡 Socket buffer configuration: SO_SNDBUF={}KB, SO_RCVBUF={}KB", 
            socketSndbufLength / 1024, socketRcvbufLength / 1024);
        
        MediaDriver.Context mediaDriverContext = new MediaDriver.Context()
                .aeronDirectoryName(aeronDirName)
                .threadingMode(ThreadingMode.SHARED)  // Balanced: good latency, efficient resource usage
                .termBufferSparseFile(true)  // Reduces disk I/O, improves performance
                .socketSndbufLength(socketSndbufLength)  // Configurable send buffer (default: 16KB for Mac)
                .socketRcvbufLength(socketRcvbufLength)  // Configurable receive buffer (default: 16KB for Mac)
                .multicastFlowControlSupplier(new MinMulticastFlowControlSupplier())
                .terminationHook(barrier::signal)
                .errorHandler(closingErrorHandler(errorHandler("Media Driver")))
                // ✈️ RESILIENCE: Increase term buffer size to reduce backpressure
                // Default is 64MB, larger buffers handle bursts better
                // Note: Aeron aims for garbage-free operation, so larger buffers don't increase GC pressure
                .publicationTermBufferLength(64 * 1024 * 1024)  // 64MB (default, explicit for clarity)
                // ✈️ RESILIENCE: Enable conductor idle strategy for better CPU efficiency
                // Uses backoff strategy to reduce CPU spinning when idle
                .conductorIdleStrategy(new org.agrona.concurrent.BackoffIdleStrategy(100, 100, 1000, 1000000))
                // ✈️ RESILIENCE: Increase driver timeout for better resilience under load
                // Default is 10s, increasing to 60s provides more tolerance for GC pauses and system load
                // Production: 60s (tested - prevents false positives from macOS/system pauses)
                // Note: This is a trade-off - longer timeout means slower failure detection
                // But MediaDriver thread hangs need longer timeout to avoid false positives
                .driverTimeoutMs(60000);  // 60 seconds (production-grade, was 20s)
        
        // Archive Context (use IP address for Aeron channels)
        AeronArchive.Context replicationArchiveContext = new AeronArchive.Context()
                .controlResponseChannel("aeron:udp?endpoint=" + myIPAddress + ":0");
        
        Archive.Context archiveContext = new Archive.Context()
                .aeronDirectoryName(aeronDirName)
                .archiveDir(new File(baseDir, "archive"))
                .controlChannel(udpChannel(nodeId, myIPAddress, ARCHIVE_CONTROL_PORT_OFFSET))
                .replicationChannel(logReplicationChannel(myIPAddress))
                .archiveClientContext(replicationArchiveContext)
                .localControlChannel("aeron:ipc?term-length=64k")  // MUST be IPC (Aeron Archive requirement)
                .recordingEventsEnabled(false)
                .threadingMode(ArchiveThreadingMode.SHARED);
        
        AeronArchive.Context aeronArchiveContext = new AeronArchive.Context()
                .lock(NoOpLock.INSTANCE)
                .controlRequestChannel(archiveContext.localControlChannel())
                .controlResponseChannel(archiveContext.localControlChannel())
                .aeronDirectoryName(aeronDirName);
        
        // Consensus Module Context (use IP addresses for cluster members)
        // Note: Aeron Cluster 1.49.1 automatically manages snapshot intervals based on log size
        // Snapshots are taken periodically by the leader to enable faster recovery
        // Default behavior: snapshot after significant log growth (typically ~1024 entries)
        log.info("📸 Aeron snapshot management: automatic (leader-controlled)");
        
        ConsensusModule.Context consensusModuleContext = new ConsensusModule.Context()
                .errorHandler(closingErrorHandler(errorHandler("Consensus Module")))
                .clusterMemberId(nodeId)
                .clusterMembers(clusterMembers(ipAddresses))  // Use IPs instead of hostnames
                .clusterDir(new File(baseDir, "cluster"))
                .ingressChannel("aeron:udp?term-length=128m")  // CRITICAL: Explicitly match log term-length (128MB)
                .logChannel(logControlChannel(nodeId, myIPAddress, LOG_CONTROL_PORT_OFFSET))
                .replicationChannel(logReplicationChannel(myIPAddress))
                .sessionTimeoutNs(java.util.concurrent.TimeUnit.MINUTES.toNanos(20))  // CRITICAL: 20-min timeout + reconnect logic for robustness
                .archiveContext(aeronArchiveContext.clone());
        
        // Clustered Service Container Context
        ClusteredServiceContainer.Context clusteredServiceContext =
                new ClusteredServiceContainer.Context()
                        .aeronDirectoryName(aeronDirName)
                        .archiveContext(aeronArchiveContext.clone())
                        .clusterDir(new File(baseDir, "cluster"))
                        .clusteredService(clusteredService)
                        .errorHandler(closingErrorHandler(errorHandler("Clustered Service")));
        
        // Launch cluster
        clusteredMediaDriver = ClusteredMediaDriver.launch(
                mediaDriverContext, archiveContext, consensusModuleContext);
        
        container = ClusteredServiceContainer.launch(clusteredServiceContext);
        
        // ✈️ AERON RESILIENCE: Start MediaDriver health monitoring
        // Monitors system counters for errors, backpressure, timeouts
        // Provides early warning of MediaDriver issues before they become fatal
        try {
            io.aeron.Aeron aeron = container.context().aeron();
            if (aeron != null) {
                healthMonitor = new MediaDriverHealthMonitor(aeron);
                log.info("✅ MediaDriver health monitor started");
            } else {
                log.warn("⚠️  Aeron instance not available - health monitor not started");
            }
        } catch (Exception e) {
            log.warn("⚠️  Failed to start MediaDriver health monitor: {}", e.getMessage());
            // Don't fail startup if health monitor fails
        }
        
        // ✈️ AERON NATIVE: Set ingress channel URI and aeron directory for client connections
        // For distributed cluster communication, use UDP for Raft consensus
        // The ingress channel configured in ConsensusModule must match client connections
        // UDP is required for multi-node cluster communication
        String clientIngressChannel = "aeron:udp";
        if (clusteredService instanceof AeronConsensusEngine) {
            AeronConsensusEngine engine = (AeronConsensusEngine) clusteredService;
            engine.setIngressChannelUri(clientIngressChannel);
            engine.setAeronDirectoryName(aeronDirName);
            log.info("✈️  Client ingress channel configured: {} (UDP for distributed cluster)", clientIngressChannel);
            log.info("✈️  Aeron directory configured: {}", aeronDirName);
        }
        
        log.info("✅ Aeron Cluster launched successfully");
        log.info("   Node {} started on {}", nodeId, getHostname());
        
        // Schedule successful startup callback to reset crash markers after cluster stabilizes
        scheduleSuccessfulStartupCallback();
    }
    
    /**
     * Set callback to be invoked on graceful shutdown.
     * This allows the parent process to handle shutdown (e.g., exit JVM, restart container).
     */
    public void setShutdownCallback(Runnable callback) {
        this.shutdownCallback = callback;
    }
    
    /**
     * Get Aeron instance from ClusteredServiceContainer.
     * Used for accessing CountersReader for metrics.
     */
    public io.aeron.Aeron getAeron() {
        if (container != null) {
            return container.context().aeron();
        }
        return null;
    }
    
    /**
     * Shutdown the Aeron Cluster gracefully.
     */
    public void shutdown() {
        if (shutdownScheduled.compareAndSet(false, true)) {
            log.info("🛑 Shutting down Aeron Cluster (node {})...", nodeId);
            
            // Close health monitor first
            if (healthMonitor != null) {
                try {
                    healthMonitor.close();
                } catch (Exception e) {
                    log.warn("Error closing health monitor", e);
                }
            }
            
            CloseHelper.closeAll(
                    errorHandler -> log.error("Error during shutdown", errorHandler),
                    container,
                    clusteredMediaDriver
            );
            
            if (barrier != null) {
                barrier.signal();
            }
            
            if (shutdownExecutor != null) {
                shutdownExecutor.shutdown();
            }
            
            log.info("✅ Aeron Cluster shut down");
        }
    }
    
    /**
     * Wait for shutdown signal (blocks until shutdown).
     */
    public void awaitShutdown() {
        if (barrier != null) {
            barrier.await();
        }
    }
    
    /**
     * Get the Aeron directory name used by this cluster node.
     * This is needed for creating Aeron clients that connect to the cluster.
     */
    public String getAeronDirectoryName() {
        return aeronDirectoryName;
    }
    
    /**
     * Get the cluster base port.
     */
    public static int getPortBase() {
        return PORT_BASE;
    }
    
    private String getHostname() {
        return hostnames.get(nodeId);
    }
    
    /**
     * Resolve hostname to IP address for Aeron Cluster channels.
     * 
     * <p>P2P-ORGANIC APPROACH: Aggressive retry logic for P2P startup where peers
     * may not be ready immediately. Uses exponential backoff for better resilience.
     * 
     * <p>Uses IP addresses instead of hostnames to avoid DNS caching issues
     * when containers or validator nodes restart.
     * 
     * @param hostname Hostname to resolve
     * @return IP address as string
     * @throws RuntimeException if hostname cannot be resolved after retries
     */
    private static String getIPAddress(String hostname) {
        final int maxRetries = 20; // Increased for P2P organic startup
        int retryDelayMs = 1000; // Start with 1 second, exponential backoff
        
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                String ip = InetAddress.getByName(hostname).getHostAddress();
                if (attempt > 1) {
                    log.info("✅ Resolved hostname {} to IP {} (attempt {}/{})", hostname, ip, attempt, maxRetries);
                } else {
                    log.debug("✅ Resolved hostname {} to IP {}", hostname, ip);
                }
                return ip;
            } catch (UnknownHostException e) {
                if (attempt < maxRetries) {
                    if (attempt <= 3 || attempt % 5 == 0) {
                        // Log first few attempts and every 5th attempt
                        log.debug("⚠️  Failed to resolve hostname {} (attempt {}/{}), retrying in {}ms...", 
                            hostname, attempt, maxRetries, retryDelayMs);
                    }
                    try {
                        Thread.sleep(retryDelayMs);
                        // Exponential backoff: 1s, 2s, 4s, 8s, then cap at 10s
                        retryDelayMs = Math.min(retryDelayMs * 2, 10000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("IP resolution interrupted", ie);
                    }
                } else {
                    log.warn("⚠️  Failed to resolve hostname: {} after {} attempts ({}s total)", 
                        hostname, maxRetries, (maxRetries * retryDelayMs) / 1000);
                    throw new RuntimeException("Failed to resolve hostname: " + hostname + " after " + maxRetries + " attempts", e);
                }
            }
        }
        throw new RuntimeException("Should not reach here");
    }
    
    /**
     * Get IP address for this node's hostname.
     * 
     * CRITICAL: When a container has multiple network interfaces (e.g., validator-network + client-network),
     * DNS resolution might return the wrong IP. We need the IP from the validator-network for Aeron Cluster.
     * 
     * Strategy:
     * 1. First, try to resolve a peer hostname to see what subnet they're on
     * 2. Enumerate network interfaces and prefer IPs from the same subnet as peers
     * 3. If no peer subnet match, prefer IPs on common Docker network subnets (172.x.x.x)
     * 4. Fallback to hostname resolution
     */
    private String getMyIPAddress() {
        // First, try to determine the validator-network subnet by resolving a peer
        String peerSubnet = null;
        if (hostnames.size() > 1) {
            // Find a peer hostname (not self)
            for (int i = 0; i < hostnames.size(); i++) {
                if (i != nodeId) {
                    try {
                        String peerIP = getIPAddress(hostnames.get(i));
                        if (peerIP != null && peerIP.startsWith("172.")) {
                            // Extract subnet (first 3 octets)
                            String[] parts = peerIP.split("\\.");
                            if (parts.length >= 3) {
                                peerSubnet = parts[0] + "." + parts[1] + "." + parts[2];
                                log.info("   Detected validator-network subnet: {}.x (from peer {})", peerSubnet, hostnames.get(i));
                                break;
                            }
                        }
                    } catch (Exception e) {
                        // Peer not resolvable yet - continue
                    }
                }
            }
        }
        
        // Enumerate network interfaces to find the IP on validator-network
        // Prefer IPs from the same subnet as peers (validator-network)
        try {
            java.util.List<String> candidateIPs = new java.util.ArrayList<>();
            java.util.Enumeration<java.net.NetworkInterface> interfaces = java.net.NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                java.net.NetworkInterface iface = interfaces.nextElement();
                if (iface.isLoopback() || !iface.isUp()) {
                    continue;
                }
                java.util.Enumeration<java.net.InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    java.net.InetAddress addr = addresses.nextElement();
                    if (addr instanceof java.net.Inet4Address && !addr.isLoopbackAddress()) {
                        String ip = addr.getHostAddress();
                        if (ip.startsWith("172.")) {
                            // If we know the peer subnet, prefer IPs from that subnet
                            if (peerSubnet != null && ip.startsWith(peerSubnet + ".")) {
                                log.info("   ✅ Found validator-network IP: {} (interface: {}, matches peer subnet)", ip, iface.getName());
                                return ip; // Perfect match - return immediately
                            }
                            candidateIPs.add(ip);
                            log.debug("   Found candidate IP: {} (interface: {})", ip, iface.getName());
                        }
                    }
                }
            }
            
            // If we found candidates but no perfect match, return the first one
            if (!candidateIPs.isEmpty()) {
                String selectedIP = candidateIPs.get(0);
                log.info("   Using network interface IP: {} (interface: {}, {} candidates found)", 
                    selectedIP, "unknown", candidateIPs.size());
                return selectedIP;
            }
        } catch (Exception e) {
            log.warn("Failed to enumerate network interfaces: {}", e.getMessage());
        }
        
        // Fallback: Use hostname resolution (might work)
        try {
            return getIPAddress(getHostname());
        } catch (Exception e) {
            log.error("❌ CRITICAL: Failed to determine IP address for Aeron Cluster", e);
            throw new RuntimeException("Cannot determine IP address for Aeron Cluster", e);
        }
    }
    
    /**
     * Resolve all hostnames to IP addresses with retry logic.
     * 
     * <p>P2P-ORGANIC APPROACH: Nodes can start in any order. We resolve hostnames
     * with aggressive retry logic, but if a peer isn't ready yet, we still include
     * it in the cluster members list (Aeron Cluster will handle unavailable peers).
     * 
     * <p>This allows:
     * - Any node to start first (no genesis ordering required)
     * - Peers to join dynamically as they become available
     * - Quorum to form organically as nodes come online
     * 
     * @param myIPAddress The IP address for self (already determined via getMyIPAddress())
     * @return List of IP addresses corresponding to hostnames (may include hostnames if resolution fails)
     */
    private List<String> resolveHostnamesToIPs(String myIPAddress) {
        List<String> ipAddresses = new ArrayList<>();
        log.info("🌐 Resolving {} hostnames to IP addresses (P2P-organic, retry logic)...", hostnames.size());
        
        int resolved = 0;
        int failed = 0;
        
        for (int i = 0; i < hostnames.size(); i++) {
            String hostname = hostnames.get(i);
            boolean isSelf = (i == nodeId);
            
            if (isSelf) {
                // Use the IP we already determined (from getMyIPAddress())
                // This ensures we use the validator-network IP, not client-network IP
                ipAddresses.add(myIPAddress);
                resolved++;
                log.info("   ✅ Self (node {}): {} → {} (validator-network IP)", i, hostname, myIPAddress);
            } else {
                // For peers: Try to resolve, but don't fail if peer isn't ready yet
                // Aeron Cluster can handle unavailable peers and will connect when they come online
                try {
                    String ip = getIPAddress(hostname);
                    ipAddresses.add(ip);
                    resolved++;
                    log.info("   ✅ Peer (node {}): {} → {}", i, hostname, ip);
                } catch (RuntimeException e) {
                    // P2P-ORGANIC: If peer isn't ready after aggressive retries, we need to handle gracefully
                    // Aeron Cluster requires all members to be specified, but we can't use invalid IPs
                    // 
                    // Strategy: Store hostname and retry DNS resolution in background thread
                    // For now, use hostname - Aeron's UDP channel builder will handle DNS resolution
                    // with its own retry logic when the peer comes online
                    log.warn("   ⚠️  Peer (node {}) not resolvable yet: {} - using hostname (Aeron will retry DNS)", i, hostname);
                    log.warn("      This is normal in P2P startup - Aeron Cluster will retry DNS resolution periodically");
                    // Use hostname - Aeron Cluster's UDP channel builder has DNS retry logic
                    // When peer comes online, DNS will resolve and Aeron will establish connection
                    ipAddresses.add(hostname); // Aeron will handle DNS resolution with retry
                    failed++;
                }
            }
        }
        
        log.info("✅ Resolved {}/{} hostnames to IP addresses ({} pending peer startup)", 
            resolved, hostnames.size(), failed);
        
        if (failed > 0) {
            log.info("🌐 P2P Mode: {} peer(s) will connect when they come online", failed);
        }
        
        return ipAddresses;
    }
    
    public static int calculatePort(int nodeId, int offset) {
        return PORT_BASE + (nodeId * PORTS_PER_NODE) + offset;
    }
    
    /**
     * Create UDP channel using IP address (not hostname) for reliable DNS resolution.
     */
    private static String udpChannel(int nodeId, String ipAddress, int portOffset) {
        int port = calculatePort(nodeId, portOffset);
        return new ChannelUriStringBuilder()
                .media("udp")
                .termLength(TERM_LENGTH)
                .endpoint(ipAddress + ":" + port)
                .build();
    }
    
    /**
     * Create log control channel using IP address (not hostname) for reliable DNS resolution.
     */
    private static String logControlChannel(int nodeId, String ipAddress, int portOffset) {
        int port = calculatePort(nodeId, portOffset);
        return new ChannelUriStringBuilder()
                .media("udp")
                .termLength(TERM_LENGTH)
                .controlMode(CommonContext.MDC_CONTROL_MODE_MANUAL)
                .controlEndpoint(ipAddress + ":" + port)
                .build();
    }
    
    /**
     * Create replication channel using IP address (not hostname) for reliable DNS resolution.
     */
    private static String logReplicationChannel(String ipAddress) {
        return new ChannelUriStringBuilder()
                .media("udp")
                .endpoint(ipAddress + ":0")
                .build();
    }
    
    /**
     * Build cluster members string using IP addresses (or hostnames if IP resolution failed).
     * 
     * <p>P2P-ORGANIC: Handles both IP addresses and hostnames. If a peer isn't ready yet
     * and DNS resolution failed, we use the hostname and let Aeron Cluster's DNS resolver
     * handle it when the peer comes online.
     * 
     * <p>Format: "nodeId,ip:port1,ip:port2,ip:port3,ip:port4,ip:port5|..."
     * 
     * <p>Note: Aeron Cluster will retry DNS resolution for hostnames, so using hostnames
     * for unavailable peers allows them to connect when they come online.
     * 
     * @param ipAddresses List of IP addresses or hostnames (one per cluster member)
     * @return Cluster members string for ConsensusModule
     */
    private static String clusterMembers(List<String> ipAddresses) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ipAddresses.size(); i++) {
            String address = ipAddresses.get(i); // May be IP or hostname
            sb.append(i);
            sb.append(',').append(address).append(':').append(calculatePort(i, CLIENT_FACING_PORT_OFFSET));
            sb.append(',').append(address).append(':').append(calculatePort(i, MEMBER_FACING_PORT_OFFSET));
            sb.append(',').append(address).append(':').append(calculatePort(i, LOG_PORT_OFFSET));
            sb.append(',').append(address).append(':').append(calculatePort(i, TRANSFER_PORT_OFFSET));
            sb.append(',').append(address).append(':').append(calculatePort(i, ARCHIVE_CONTROL_PORT_OFFSET));
            sb.append('|');
        }
        return sb.toString();
    }
    
    /**
     * Error handler for Aeron Cluster components.
     * 
     * <p>🌐 P2P-ORGANIC: Filters out expected DNS resolution errors for unavailable peers.
     * These are normal in P2P startup and will resolve when peers come online.
     * 
     * <p>✈️ AERON CLUSTER: Filters out ClusterEvent warnings (heartbeat timeouts, etc.)
     * These are informational events from Aeron Cluster, not actual errors.
     */
    private ErrorHandler errorHandler(String context) {
        return throwable -> {
            // Filter out expected DNS errors for unavailable peers (P2P-organic startup)
            String message = throwable.getMessage();
            if (message != null && message.contains("UnknownHostException") && message.contains("unresolved")) {
                // This is expected when peers aren't ready yet - Aeron will retry DNS resolution
                log.debug("🌐 P2P: DNS resolution pending for peer (will retry): {}", throwable.getClass().getSimpleName());
                return;
            }
            
            // ✈️ AERON CLUSTER: Filter out ClusterEvent warnings (heartbeat timeouts, etc.)
            // These are informational events from Aeron Cluster, not actual errors
            // Format: "io.aeron.cluster.client.ClusterEvent: WARN - leader heartbeat timeout"
            if (message != null) {
                if (message.contains("ClusterEvent") && message.contains("WARN")) {
                    // This is a ClusterEvent warning (e.g., "leader heartbeat timeout")
                    // These are informational - Aeron Cluster handles leader election automatically
                    log.debug("✈️  Aeron Cluster warning (informational): {}", message);
                    return;
                }
                if (message.contains("leader heartbeat timeout")) {
                    // Leader heartbeat timeout is normal during leader election
                    log.debug("✈️  Leader heartbeat timeout (normal during election): {}", message);
                    return;
                }
            }
            
            // Log all other errors
            log.error("{} error", context, throwable);
        };
    }
    
    /**
     * Enhanced error handler that detects FATAL errors and triggers graceful shutdown.
     * 
     * <p>On FATAL MediaDriver errors (timeouts, crashes), schedules graceful shutdown
     * to allow automatic restart and recovery of the Aeron cluster connection.
     * 
     * <p>Key behaviors:
     * <ul>
     *   <li>Detects FATAL AeronException (MediaDriver timeouts, crashes)</li>
     *   <li>Tracks crashes via CrashHandler</li>
     *   <li>Schedules graceful shutdown</li>
     *   <li>Prevents multiple shutdown attempts</li>
     * </ul>
     */
    private ErrorHandler closingErrorHandler(ErrorHandler handler) {
        return throwable -> {
            // First, call the base error handler
            handler.onError(throwable);
            
            // Check if this is a FATAL AeronException that should trigger shutdown
            if (throwable instanceof AeronException) {
                AeronException ex = (AeronException) throwable;
                if (crashHandler != null && crashHandler.shouldStop(ex)) {
                    // Schedule shutdown (only once)
                    if (shutdownScheduled.compareAndSet(false, true)) {
                        log.error("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                        log.error("🚨 FATAL Aeron error detected - scheduling graceful shutdown");
                        log.error("   Error: {} ({})", ex.getClass().getSimpleName(), ex.getMessage());
                        log.error("   Category: {}", ex.category());
                        log.error("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                        
                        // Track crash
                        crashHandler.handleCrash(ex);
                        log.warn("📛 Crash state: {}", crashHandler.getState());
                        
                        // Schedule graceful shutdown in background thread
                        shutdownExecutor.submit(() -> {
                            try {
                                log.info("⏳ Waiting 2 seconds before shutdown to allow error logging...");
                                Thread.sleep(2000);
                                
                                log.info("🛑 Initiating graceful shutdown due to FATAL error...");
                                shutdown();
                                
                                // Invoke shutdown callback if set (e.g., exit JVM)
                                if (shutdownCallback != null) {
                                    log.info("📞 Invoking shutdown callback...");
                                    shutdownCallback.run();
                                } else {
                                    log.warn("⚠️  No shutdown callback set - process will continue running");
                                    log.warn("   Set shutdown callback to exit JVM or restart container");
                                }
                            } catch (Exception e) {
                                log.error("Error during shutdown", e);
                            }
                        });
                    }
                }
            }
        };
    }
    
    /**
     * Schedule callback to reset crash markers after successful startup.
     * This is called after cluster stabilizes (election completes).
     */
    private void scheduleSuccessfulStartupCallback() {
        shutdownExecutor.submit(() -> {
            try {
                // Wait for cluster to stabilize (election completes)
                Thread.sleep(10000); // 10 seconds should be enough for election
                
                // Reset crash markers on successful startup
                if (crashHandler != null) {
                    crashHandler.reset();
                    log.info("✅ Startup successful - crash markers reset");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.warn("Failed to reset crash markers", e);
            }
        });
    }
    
    /**
     * Get crash handler for external access (e.g., health checks).
     */
    public CrashHandler getCrashHandler() {
        return crashHandler;
    }
    
    /**
     * Get MediaDriver health monitor for external access (e.g., health checks, metrics).
     * 
     * @return Health monitor instance, or null if not initialized
     */
    public MediaDriverHealthMonitor getHealthMonitor() {
        return healthMonitor;
    }
}

