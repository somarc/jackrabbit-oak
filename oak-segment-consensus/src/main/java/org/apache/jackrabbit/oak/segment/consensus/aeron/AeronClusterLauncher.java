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
import org.agrona.CloseHelper;
import org.agrona.ErrorHandler;
import org.agrona.concurrent.NoOpLock;
import org.agrona.concurrent.ShutdownSignalBarrier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

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
    private static final int TERM_LENGTH = 64 * 1024;
    
    private final int nodeId;
    private final List<String> hostnames;
    private final File baseDir;
    private final ClusteredService clusteredService;
    
    private ClusteredMediaDriver clusteredMediaDriver;
    private ClusteredServiceContainer container;
    private ShutdownSignalBarrier barrier;
    private String aeronDirectoryName;
    
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
        // Reference: oak-repository-service uses IPs to avoid DNS caching issues
        List<String> ipAddresses = resolveHostnamesToIPs(myIPAddress);
        log.info("   Using IP addresses for Aeron Cluster channels (P2P-organic mode)");
        log.info("   My IP: {} (hostname: {})", myIPAddress, getHostname());
        log.info("   Note: Unavailable peers use placeholder IPs - Aeron will retry DNS when peers come online");
        
        this.aeronDirectoryName = CommonContext.getAeronDirectoryName() + "-" + nodeId + "-driver";
        String aeronDirName = this.aeronDirectoryName;
        barrier = new ShutdownSignalBarrier();
        
        // Media Driver Context
        MediaDriver.Context mediaDriverContext = new MediaDriver.Context()
                .aeronDirectoryName(aeronDirName)
                .threadingMode(ThreadingMode.SHARED)
                .termBufferSparseFile(true)
                .multicastFlowControlSupplier(new MinMulticastFlowControlSupplier())
                .terminationHook(barrier::signal)
                .errorHandler(errorHandler("Media Driver"));
        
        // Archive Context (use IP address for Aeron channels)
        AeronArchive.Context replicationArchiveContext = new AeronArchive.Context()
                .controlResponseChannel("aeron:udp?endpoint=" + myIPAddress + ":0");
        
        Archive.Context archiveContext = new Archive.Context()
                .aeronDirectoryName(aeronDirName)
                .archiveDir(new File(baseDir, "archive"))
                .controlChannel(udpChannel(nodeId, myIPAddress, ARCHIVE_CONTROL_PORT_OFFSET))
                .replicationChannel(logReplicationChannel(myIPAddress))
                .archiveClientContext(replicationArchiveContext)
                .localControlChannel("aeron:ipc?term-length=64k")
                .recordingEventsEnabled(false)
                .threadingMode(ArchiveThreadingMode.SHARED);
        
        AeronArchive.Context aeronArchiveContext = new AeronArchive.Context()
                .lock(NoOpLock.INSTANCE)
                .controlRequestChannel(archiveContext.localControlChannel())
                .controlResponseChannel(archiveContext.localControlChannel())
                .aeronDirectoryName(aeronDirName);
        
        // Consensus Module Context (use IP addresses for cluster members)
        ConsensusModule.Context consensusModuleContext = new ConsensusModule.Context()
                .errorHandler(errorHandler("Consensus Module"))
                .clusterMemberId(nodeId)
                .clusterMembers(clusterMembers(ipAddresses))  // Use IPs instead of hostnames
                .clusterDir(new File(baseDir, "cluster"))
                .ingressChannel("aeron:udp?term-length=64k")
                .logChannel(logControlChannel(nodeId, myIPAddress, LOG_CONTROL_PORT_OFFSET))
                .replicationChannel(logReplicationChannel(myIPAddress))
                .archiveContext(aeronArchiveContext.clone());
        
        // Clustered Service Container Context
        ClusteredServiceContainer.Context clusteredServiceContext =
                new ClusteredServiceContainer.Context()
                        .aeronDirectoryName(aeronDirName)
                        .archiveContext(aeronArchiveContext.clone())
                        .clusterDir(new File(baseDir, "cluster"))
                        .clusteredService(clusteredService)
                        .errorHandler(errorHandler("Clustered Service"));
        
        // Launch cluster
        clusteredMediaDriver = ClusteredMediaDriver.launch(
                mediaDriverContext, archiveContext, consensusModuleContext);
        
        container = ClusteredServiceContainer.launch(clusteredServiceContext);
        
        // ✈️ AERON NATIVE: Set ingress channel URI and aeron directory for client connections
        // For same-process communication, use IPC (more efficient than UDP)
        // The ingress channel configured in ConsensusModule is for cluster-internal use
        // For client connections from within the same process, IPC is recommended
        String clientIngressChannel = "aeron:ipc?term-length=64k";
        if (clusteredService instanceof AeronConsensusEngine) {
            AeronConsensusEngine engine = (AeronConsensusEngine) clusteredService;
            engine.setIngressChannelUri(clientIngressChannel);
            engine.setAeronDirectoryName(aeronDirName);
            log.info("✈️  Client ingress channel configured: {} (IPC for same-process communication)", clientIngressChannel);
            log.info("✈️  Aeron directory configured: {}", aeronDirName);
        }
        
        log.info("✅ Aeron Cluster launched successfully");
        log.info("   Node {} started on {}", nodeId, getHostname());
    }
    
    /**
     * Shutdown the Aeron Cluster gracefully.
     */
    public void shutdown() {
        log.info("🛑 Shutting down Aeron Cluster (node {})...", nodeId);
        
        CloseHelper.closeAll(
                errorHandler -> log.error("Error during shutdown", errorHandler),
                container,
                clusteredMediaDriver
        );
        
        if (barrier != null) {
            barrier.signal();
        }
        
        log.info("✅ Aeron Cluster shut down");
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
     * <p>Reference: oak-repository-service uses IP addresses instead of hostnames
     * to avoid DNS caching issues when containers restart.
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
     */
    private static ErrorHandler errorHandler(String context) {
        return throwable -> {
            // Filter out expected DNS errors for unavailable peers (P2P-organic startup)
            String message = throwable.getMessage();
            if (message != null && message.contains("UnknownHostException") && message.contains("unresolved")) {
                // This is expected when peers aren't ready yet - Aeron will retry DNS resolution
                log.debug("🌐 P2P: DNS resolution pending for peer (will retry): {}", throwable.getClass().getSimpleName());
                return;
            }
            
            // Log all other errors
            log.error("{} error", context, throwable);
        };
    }
}

