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
import org.apache.jackrabbit.oak.segment.consensus.ConsensusEngine;
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
            
            // Create genesis content if it doesn't exist
            initializeGenesisContent();
            
        } catch (InvalidFileStoreVersionException e) {
            throw new IOException("Invalid FileStore version", e);
        }
        
        // Initialize and start HTTP server to expose segments
        System.out.println("Starting HTTP server on port " + port + "...");
        try {
            httpServer = new SegmentHttpServer(storeDir, port, fileStore, nodeStore);
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
        String consensusEnabled = System.getProperty("consensus.enabled", "false");
        String consensusMode = System.getProperty("consensus.mode", "blockchain"); // blockchain or dag
        String selfUrl = System.getProperty("consensus.self.url", "http://localhost:" + port);
        String peersConfig = System.getProperty("consensus.peers", "");
        String genesisNode = System.getProperty("consensus.genesis.node", "");  // Boot node for genesis sync
        
        if ("true".equalsIgnoreCase(consensusEnabled) && !peersConfig.isEmpty()) {
            System.out.println();
            System.out.println("Initializing Consensus Engine...");
            System.out.println("   Mode: " + consensusMode.toUpperCase());
            
            List<String> peerUrls = parsePeerUrls(peersConfig);
            
            if ("dag".equalsIgnoreCase(consensusMode)) {
                // DISTRIBUTED DAG CONSENSUS (like Git)
                System.out.println("   🌳 Using Distributed DAG Consensus");
                System.out.println("      - Multiple parallel HEADs allowed");
                System.out.println("      - Non-conflicting writes proceed in parallel");
                System.out.println("      - Periodic merge consensus");
                
                org.apache.jackrabbit.oak.segment.consensus.dag.DagConsensusEngine dagEngine = 
                    new org.apache.jackrabbit.oak.segment.consensus.dag.DagConsensusEngine(
                        fileStore, selfUrl, peerUrls
                    );
                
                // Wire DAG engine to HTTP server
                httpServer.setDagConsensusEngine(dagEngine);
                
                System.out.println("✅ DAG Consensus engine initialized");
                System.out.println("   - Model: Git-like distributed DAG");
                System.out.println("   - Total validators: " + (1 + peerUrls.size()));
                System.out.println("   - Each validator maintains own HEAD");
                System.out.println("   - Merges require 2/3+ vote");
                
            } else {
                // LINEAR BLOCKCHAIN CONSENSUS (traditional)
                System.out.println("   ⛓️  Using Linear Blockchain Consensus");
                
                // BLOCKCHAIN GENESIS: Sync with genesis node if configured
                if (!genesisNode.isEmpty() && !genesisNode.equals(selfUrl)) {
                    System.out.println("   🔄 Syncing genesis state from: " + genesisNode);
                    try {
                        syncGenesisFromPeer(genesisNode);
                        System.out.println("   ✅ Genesis state synchronized");
                    } catch (Exception e) {
                        System.err.println("   ⚠️  Genesis sync failed: " + e.getMessage());
                        System.err.println("   Continuing with local genesis...");
                    }
                }
                
                ConsensusEngine consensusEngine = new ConsensusEngine(fileStore, selfUrl, peerUrls);
                
                // Wire consensus engine to HTTP server
                httpServer.setConsensusEngine(consensusEngine);
                
                System.out.println("✅ Blockchain Consensus engine initialized");
                System.out.println("   - Consensus: Proof-of-Authority");
                System.out.println("   - Threshold: 2/3+ majority");
                System.out.println("   - Total validators: " + (1 + peerUrls.size()));
            }
        } else {
            System.out.println();
            System.out.println("ℹ️  Consensus disabled (single-validator mode)");
        }
        
        // TODO: Smart Contract Event Listener (future implementation)
        // This is where we'll listen to OakNetwork.sol contract events:
        //   - WriteProposed(address indexed wallet, bytes32 indexed writeId, uint256 payment)
        //   - WriteFinalized(bytes32 indexed writeId, bool approved)
        // 
        // For now, we use the /v1/test-write API with mock wallet signatures.
        System.out.println();
        System.out.println("📝 Smart Contract Listener: NOT IMPLEMENTED");
        System.out.println("   Future: Listen to OakNetwork.sol events");
        System.out.println("   Current: Use /v1/test-write API for testing");
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
    private void initializeGenesisContent() {
        try {
            org.apache.jackrabbit.oak.spi.state.NodeState root = nodeStore.getRoot();
            
            // Check if genesis content already exists
            org.apache.jackrabbit.oak.spi.state.NodeState oakChain = root.getChildNode("oak-chain");
            if (oakChain.exists()) {
                org.apache.jackrabbit.oak.spi.state.NodeState content = oakChain.getChildNode("content");
                if (content.exists() && content.getChildNode("genesis").exists()) {
                    System.out.println("   ℹ️  Genesis content already exists, skipping initialization");
                    return;
                }
            }
            
            // Create genesis content
            System.out.println("   🔥 Creating DO IT LIVE! genesis content...");
            
            org.apache.jackrabbit.oak.spi.state.NodeBuilder rootBuilder = root.builder();
            
            // Create /oak-chain/content path
            org.apache.jackrabbit.oak.spi.state.NodeBuilder oakChainBuilder = rootBuilder.child("oak-chain");
            org.apache.jackrabbit.oak.spi.state.NodeBuilder contentBuilder = oakChainBuilder.child("content");
            
            // Create genesis node
            org.apache.jackrabbit.oak.spi.state.NodeBuilder genesis = contentBuilder.child("genesis");
            genesis.setProperty("jcr:primaryType", "nt:unstructured");
            genesis.setProperty("message", "DO IT LIVE!");
            genesis.setProperty("description", "Blockchain AEM - Genesis block of the global TarMK chain");
            genesis.setProperty("timestamp", System.currentTimeMillis());
            genesis.setProperty("author", "Blockchain AEM POC");
            genesis.setProperty("version", "1.0.0");
            
            // Add BYOD model reference (binary stored externally)
            genesis.setProperty("imageUri", "https://participant-cdn.example.com/assets/do-it-live.jpeg");
            genesis.setProperty("imageMimeType", "image/jpeg");
            genesis.setProperty("imageSize", 297L);
            genesis.setProperty("binaryDataNote", "Binaries stored in participant-owned datastore, not in global chain");
            
            // Add metadata child node
            org.apache.jackrabbit.oak.spi.state.NodeBuilder metadata = genesis.child("metadata");
            metadata.setProperty("jcr:primaryType", "nt:unstructured");
            metadata.setProperty("poc", true);
            metadata.setProperty("consensusProtocol", "HTTP Segment Transfer");
            metadata.setProperty("mountPath", "/oak-chain");
            metadata.setProperty("accessMode", "READ-ONLY (for participants)");
            
            // Commit the changes
            nodeStore.merge(rootBuilder, org.apache.jackrabbit.oak.spi.commit.EmptyHook.INSTANCE, 
                           org.apache.jackrabbit.oak.spi.commit.CommitInfo.EMPTY);
            
            System.out.println("   ✅ Genesis content created: /oak-chain/content/genesis");
            System.out.println("      message: \"DO IT LIVE!\"");
            System.out.println("      author: Blockchain AEM POC");
            System.out.println("      timestamp: " + System.currentTimeMillis());
            
        } catch (Exception e) {
            System.err.println("   ⚠️  Failed to create genesis content: " + e.getMessage());
            // Non-fatal - server can still run without genesis content
        }
    }
    
    /**
     * Stop the server.
     */
    public void stop() {
        System.out.println("Shutting down global store server...");
        running = false;
        
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
     * Parse peer URLs from comma-separated string.
     * Format: "http://validator1:8090,http://validator2:8090,http://validator3:8090"
     */
    private List<String> parsePeerUrls(String peersConfig) {
        List<String> peers = new ArrayList<>();
        if (peersConfig != null && !peersConfig.trim().isEmpty()) {
            String[] urls = peersConfig.split(",");
            for (String url : urls) {
                String trimmed = url.trim();
                if (!trimmed.isEmpty()) {
                    peers.add(trimmed);
                }
            }
        }
        return peers;
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

