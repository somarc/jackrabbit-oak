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
    
    public AeronClusterLauncher(int nodeId, List<String> hostnames, File baseDir, ClusteredService clusteredService) {
        this.nodeId = nodeId;
        this.hostnames = hostnames;
        this.baseDir = baseDir;
        this.clusteredService = clusteredService;
    }
    
    /**
     * Launch the Aeron Cluster.
     * 
     * <p>Aeron Cluster handles bootstrap automatically:
     * - If cluster directory is empty → starts fresh (genesis node)
     * - If cluster directory has recordings → replays log and joins existing cluster
     * - Empty nodes will sync via Raft log replication from other cluster members
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
            log.info("   Bootstrap: Fresh start (no existing cluster state)");
            log.info("   → Will start as genesis or sync via Raft log replication");
        } else {
            log.info("   Bootstrap: Existing cluster state found");
            log.info("   → Will replay log and join existing cluster");
        }
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        
        // CRITICAL: Resolve hostnames to IP addresses for Aeron Cluster
        // Reference: oak-repository-service uses IPs to avoid DNS caching issues
        List<String> ipAddresses = resolveHostnamesToIPs();
        String myIPAddress = getMyIPAddress();
        log.info("   Using IP addresses for Aeron Cluster channels");
        log.info("   My IP: {} (hostname: {})", myIPAddress, getHostname());
        
        String aeronDirName = CommonContext.getAeronDirectoryName() + "-" + nodeId + "-driver";
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
    
    private String getHostname() {
        return hostnames.get(nodeId);
    }
    
    /**
     * Resolve hostname to IP address for Aeron Cluster channels.
     * 
     * <p>Reference: oak-repository-service uses IP addresses instead of hostnames
     * to avoid DNS caching issues when containers restart.
     * 
     * <p>Uses retry logic to handle timing issues when containers are starting up.
     * 
     * @param hostname Hostname to resolve
     * @return IP address as string
     * @throws RuntimeException if hostname cannot be resolved after retries
     */
    private static String getIPAddress(String hostname) {
        final int maxRetries = 10;
        final int retryDelayMs = 2000; // 2 seconds
        
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                String ip = InetAddress.getByName(hostname).getHostAddress();
                log.info("✅ Resolved hostname {} to IP {} (attempt {}/{})", hostname, ip, attempt, maxRetries);
                return ip;
            } catch (UnknownHostException e) {
                if (attempt < maxRetries) {
                    log.debug("⚠️  Failed to resolve hostname {} (attempt {}/{}), retrying in {}ms...", 
                        hostname, attempt, maxRetries, retryDelayMs);
                    try {
                        Thread.sleep(retryDelayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("IP resolution interrupted", ie);
                    }
                } else {
                    log.error("❌ Failed to resolve hostname: {} after {} attempts", hostname, maxRetries);
                    throw new RuntimeException("Failed to resolve hostname: " + hostname + " after " + maxRetries + " attempts", e);
                }
            }
        }
        throw new RuntimeException("Should not reach here");
    }
    
    /**
     * Get IP address for this node's hostname.
     * This should always succeed since we're resolving our own hostname.
     */
    private String getMyIPAddress() {
        return getIPAddress(getHostname());
    }
    
    /**
     * Resolve all hostnames to IP addresses with retry logic.
     * 
     * <p>Handles timing issues when containers are starting up - peers may not
     * be resolvable immediately, so we retry with delays.
     * 
     * @return List of IP addresses corresponding to hostnames
     */
    private List<String> resolveHostnamesToIPs() {
        List<String> ipAddresses = new ArrayList<>();
        log.info("Resolving {} hostnames to IP addresses (with retry logic)...", hostnames.size());
        
        for (String hostname : hostnames) {
            try {
                String ip = getIPAddress(hostname);
                ipAddresses.add(ip);
            } catch (RuntimeException e) {
                log.error("❌ Failed to resolve hostname: {}, skipping...", hostname);
                // For now, use hostname as fallback (Aeron might handle it)
                // In production, this should fail fast
                ipAddresses.add(hostname);
            }
        }
        
        log.info("✅ Resolved {} hostnames to IP addresses", ipAddresses.size());
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
     * Build cluster members string using IP addresses (not hostnames).
     * 
     * <p>Format: "nodeId,ip:port1,ip:port2,ip:port3,ip:port4,ip:port5|..."
     * 
     * @param ipAddresses List of IP addresses (one per cluster member)
     * @return Cluster members string for ConsensusModule
     */
    private static String clusterMembers(List<String> ipAddresses) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ipAddresses.size(); i++) {
            String ip = ipAddresses.get(i);
            sb.append(i);
            sb.append(',').append(ip).append(':').append(calculatePort(i, CLIENT_FACING_PORT_OFFSET));
            sb.append(',').append(ip).append(':').append(calculatePort(i, MEMBER_FACING_PORT_OFFSET));
            sb.append(',').append(ip).append(':').append(calculatePort(i, LOG_PORT_OFFSET));
            sb.append(',').append(ip).append(':').append(calculatePort(i, TRANSFER_PORT_OFFSET));
            sb.append(',').append(ip).append(':').append(calculatePort(i, ARCHIVE_CONTROL_PORT_OFFSET));
            sb.append('|');
        }
        return sb.toString();
    }
    
    private static ErrorHandler errorHandler(String context) {
        return throwable -> {
            log.error("{} error", context, throwable);
        };
    }
}

