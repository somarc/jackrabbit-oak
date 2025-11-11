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
package org.apache.jackrabbit.oak.segment.http.server;

import org.apache.jackrabbit.oak.segment.Segment;
import org.apache.jackrabbit.oak.segment.SegmentId;
import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.apache.jackrabbit.oak.segment.file.FileStoreBuilder;
import org.apache.jackrabbit.oak.segment.consensus.ConsensusEngine;
import org.apache.jackrabbit.oak.segment.consensus.Vote;
import org.apache.jackrabbit.oak.segment.consensus.WriteProposal;
import org.apache.jackrabbit.oak.spi.state.NodeStore;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * HTTP server for serving Oak segment store files over HTTP.
 * 
 * <p>This server exposes Oak segment store persistence files to remote clients
 * following the HTTP Segment Transfer pattern. It serves:</p>
 * <ul>
 *   <li>/journal.log - The revision journal</li>
 *   <li>/manifest - The manifest file</li>
 *   <li>/gc.log - The garbage collection log</li>
 *   <li>/segments/{id} - Individual segment files</li>
 *   <li>/health - Health check endpoint</li>
 * </ul>
 * 
 * <p>This implementation uses Jetty for HTTP serving and reads files directly
 * from the segment store directory, avoiding dependencies on internal Oak classes.</p>
 */
public class SegmentHttpServer {
    
    private static final Logger log = LoggerFactory.getLogger(SegmentHttpServer.class);
    
    private final Server server;
    private final Path storeDirectory;
    private FileStore fileStore;  // Oak FileStore for reading segments
    private NodeStore nodeStore;  // Oak NodeStore for content browsing
    private ConsensusEngine consensusEngine;  // Linear blockchain consensus
    private org.apache.jackrabbit.oak.segment.consensus.dag.DagConsensusEngine dagConsensusEngine;  // Distributed DAG consensus
    
    // Track connected peers (Sling Author instances mounting this store)
    private final java.util.Set<String> connectedPeers = java.util.concurrent.ConcurrentHashMap.newKeySet();
    
    // Track recent writes with metadata (recordId -> WriteMetadata)
    private final java.util.Map<String, WriteMetadata> recentWriteMetadata = new java.util.concurrent.ConcurrentHashMap<>();
    
    /**
     * Metadata about a write for dashboard display
     */
    private static class WriteMetadata {
        String recordId;
        String source;      // "consensus", "epoch-sync", or "local"
        String validator;   // URL of validator that wrote this
        long timestamp;
        String message;     // Optional message (for test writes)
        
        WriteMetadata(String recordId, String source, String validator, long timestamp, String message) {
            this.recordId = recordId;
            this.source = source;
            this.validator = validator;
            this.timestamp = timestamp;
            this.message = message;
        }
    }
    
    /**
     * Create a new HTTP server for serving segment store files.
     * 
     * @param storeDirectory The segment store directory path
     * @param port The HTTP port to listen on
     * @param fileStore The Oak FileStore instance
     * @param nodeStore The Oak NodeStore instance
     */
    public SegmentHttpServer(File storeDirectory, int port, FileStore fileStore, NodeStore nodeStore) {
        this.storeDirectory = storeDirectory.toPath();
        this.fileStore = fileStore;  // Use existing FileStore!
        this.nodeStore = nodeStore;  // Use existing NodeStore!
        
        this.server = new Server(port);
        this.server.setHandler(new SegmentStoreHandler());
        
        log.info("Initialized SegmentHttpServer");
        log.info("   - Port: {}", port);
        log.info("   - Store: {}", this.storeDirectory);
    }
    
    /**
     * Set the consensus engine for coordinating writes (linear blockchain mode).
     * Must be called before start() if consensus is needed.
     */
    public void setConsensusEngine(ConsensusEngine engine) {
        this.consensusEngine = engine;
        log.info("Linear Blockchain consensus engine configured");
    }
    
    /**
     * Set the DAG consensus engine (distributed DAG mode).
     * Must be called before start() if DAG consensus is needed.
     */
    public void setDagConsensusEngine(org.apache.jackrabbit.oak.segment.consensus.dag.DagConsensusEngine engine) {
        this.dagConsensusEngine = engine;
        log.info("🌳 DAG consensus engine configured");
    }
    
    /**
     * Start the HTTP server.
     * FileStore is already provided via constructor - no need to open again!
     */
    public void start() throws Exception {
        log.info("Starting HTTP server (using existing FileStore)...");
        
        server.start();
        log.info("SegmentHttpServer started on port {}", server.getURI().getPort());
        log.info("   - GET  /journal.log");
        log.info("   - GET  /manifest");
        log.info("   - GET  /gc.log");
        log.info("   - GET  /segments/{{segmentId}}");
        log.info("   - HEAD /segments/{{segmentId}}");
        log.info("   - GET  /health");
    }
    
    /**
     * Stop the HTTP server.
     * Note: FileStore is owned by GlobalStoreServer, don't close it here!
     */
    public void stop() throws Exception {
        if (server != null) {
            server.stop();
            log.info("SegmentHttpServer stopped");
        }
    }
    
    /**
     * Join the server thread (for standalone operation).
     */
    public void join() throws InterruptedException {
        server.join();
    }
    
    /**
     * Jetty handler for serving segment store files.
     */
    private class SegmentStoreHandler extends AbstractHandler {
        
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request,
                          HttpServletResponse response) throws IOException, ServletException {
            
            String path = request.getPathInfo();
            String method = request.getMethod();
            
            log.debug("HTTP {} {}", method, path);
            
            try {
                // Dashboard homepage
                if ("/".equals(path) || path == null) {
                    handleDashboard(response);
                    baseRequest.setHandled(true);
                    return;
                }
                
                // Health check
                if ("/health".equals(path)) {
                    handleHealth(response);
                    baseRequest.setHandled(true);
                    return;
                }
                
                // Journal file
                if ("/journal.log".equals(path)) {
                    handleFile(request, response, "journal.log", "text/plain");
                    baseRequest.setHandled(true);
                    return;
                }
                
                // Manifest file
                if ("/manifest".equals(path)) {
                    handleFile(request, response, "manifest", "text/plain");
                    baseRequest.setHandled(true);
                    return;
                }
                
                // GC log
                if ("/gc.log".equals(path)) {
                    handleFile(request, response, "gc.log", "text/plain");
                    baseRequest.setHandled(true);
                    return;
                }
                
                // Segments
                if (path != null && path.startsWith("/segments/")) {
                    String segmentId = path.substring("/segments/".length());
                    if ("HEAD".equals(method)) {
                        handleSegmentHead(response, segmentId);
                    } else if ("GET".equals(method)) {
                        handleSegmentGet(request, response, segmentId);
                    } else {
                        response.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
                    }
                    baseRequest.setHandled(true);
                    return;
                }
                
                // Explorer API - Browse nodes
                if ("/api/explore".equals(path)) {
                    String nodePath = request.getParameter("path");
                    handleExploreNode(response, nodePath != null ? nodePath : "/");
                    baseRequest.setHandled(true);
                    return;
                }
                
                // Explorer API - Recent segments
                if ("/api/segments/recent".equals(path)) {
                    handleRecentSegments(response);
                    baseRequest.setHandled(true);
                    return;
                }
                
                // Explorer API - TAR files
                if ("/api/segments/tars".equals(path)) {
                    handleTarFiles(response);
                    baseRequest.setHandled(true);
                    return;
                }
                
                // Explorer UI
                if ("/explorer".equals(path)) {
                    handleExplorerUI(response);
                    baseRequest.setHandled(true);
                    return;
                }
                
                // Consensus API - Propose write
                if ("/v1/propose".equals(path) && "POST".equals(method)) {
                    handleWriteProposal(request, response);
                    baseRequest.setHandled(true);
                    return;
                }
                
                // Consensus API - Vote
                if ("/v1/vote".equals(path) && "POST".equals(method)) {
                    handleVote(request, response);
                    baseRequest.setHandled(true);
                    return;
                }
                
                // Consensus API - List peers
                if ("/v1/peers".equals(path) && "GET".equals(method)) {
                    handleListPeers(response);
                    baseRequest.setHandled(true);
                    return;
                }
                
                // Metrics API - Consensus and replication metrics
                if ("/api/metrics".equals(path) && "GET".equals(method)) {
                    handleMetrics(response);
                    baseRequest.setHandled(true);
                    return;
                }
                
                // TEST ENDPOINT - Simulate a write with consensus
                if ("/v1/test-write".equals(path) && "POST".equals(method)) {
                    handleTestWrite(request, response);
                    baseRequest.setHandled(true);
                    return;
                }
                
                // Not found
                response.sendError(HttpServletResponse.SC_NOT_FOUND);
                baseRequest.setHandled(true);
                
            } catch (Exception e) {
                log.error("Error handling request: " + path, e);
                response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
                baseRequest.setHandled(true);
            }
        }
        
        /**
         * Handle dashboard homepage with live statistics.
         */
        private void handleDashboard(HttpServletResponse response) throws IOException {
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType("text/html; charset=UTF-8");
            
            // Get FileStore statistics
            long storeSize = fileStore.size();
            int segmentCount = fileStore.getSegmentCount();
            
            // Read journal for latest writes (last 10 lines)
            java.util.List<String> recentWrites = new java.util.ArrayList<>();
            try {
                Path journalPath = storeDirectory.resolve("journal.log");
                if (java.nio.file.Files.exists(journalPath)) {
                    java.util.List<String> allLines = java.nio.file.Files.readAllLines(journalPath);
                    int start = Math.max(0, allLines.size() - 10);
                    recentWrites = allLines.subList(start, allLines.size());
                    java.util.Collections.reverse(recentWrites); // Most recent first
                }
            } catch (Exception e) {
                log.warn("Failed to read journal for dashboard", e);
            }
            
            StringBuilder html = new StringBuilder();
            html.append("<!DOCTYPE html>\n");
            html.append("<html>\n<head>\n");
            html.append("<meta charset='UTF-8'>\n");
            html.append("<meta name='viewport' content='width=device-width, initial-scale=1.0'>\n");
            html.append("<title>🔗 Oak Segment Consensus - Global Store</title>\n");
            html.append("<style>\n");
            html.append("* { margin: 0; padding: 0; box-sizing: border-box; }\n");
            html.append("body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Oxygen, Ubuntu, sans-serif; ");
            html.append("background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); color: #fff; min-height: 100vh; padding: 20px; }\n");
            html.append(".container { max-width: 1200px; margin: 0 auto; }\n");
            html.append("header { text-align: center; padding: 40px 0; }\n");
            html.append("h1 { font-size: 3em; margin-bottom: 10px; text-shadow: 2px 2px 4px rgba(0,0,0,0.3); }\n");
            html.append(".subtitle { font-size: 1.2em; opacity: 0.9; }\n");
            html.append(".grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 20px; margin: 30px 0; max-width: 1400px; }\n");
            html.append(".card { background: rgba(255,255,255,0.1); backdrop-filter: blur(10px); border-radius: 15px; ");
            html.append("padding: 25px; box-shadow: 0 8px 32px rgba(0,0,0,0.1); border: 1px solid rgba(255,255,255,0.2); }\n");
            html.append(".card h2 { font-size: 1.5em; margin-bottom: 15px; display: flex; align-items: center; gap: 10px; }\n");
            html.append(".stat { font-size: 2.5em; font-weight: bold; margin: 10px 0; }\n");
            html.append(".label { font-size: 0.9em; opacity: 0.8; text-transform: uppercase; letter-spacing: 1px; }\n");
            html.append(".journal-entry { background: rgba(0,0,0,0.2); padding: 10px; margin: 8px 0; border-radius: 5px; ");
            html.append("font-family: 'Courier New', monospace; font-size: 0.85em; word-break: break-all; }\n");
            html.append(".pulse { animation: pulse 2s ease-in-out infinite; }\n");
            html.append("@keyframes pulse { 0%, 100% { opacity: 1; } 50% { opacity: 0.6; } }\n");
            html.append(".status { display: inline-block; width: 12px; height: 12px; background: #4ade80; border-radius: 50%; ");
            html.append("animation: pulse 2s ease-in-out infinite; margin-right: 8px; }\n");
            html.append(".endpoints { display: grid; gap: 10px; margin-top: 15px; }\n");
            html.append(".endpoint { background: rgba(0,0,0,0.2); padding: 10px; border-radius: 5px; font-size: 0.9em; }\n");
            html.append(".endpoint code { background: rgba(255,255,255,0.1); padding: 2px 6px; border-radius: 3px; }\n");
            html.append("</style>\n");
            html.append("<script>\n");
            html.append("// Auto-refresh every 10 seconds\n");
            html.append("setTimeout(() => window.location.reload(), 10000);\n");
            html.append("</script>\n");
            html.append("</head>\n<body>\n");
            html.append("<div class='container'>\n");
            
            // Header
            html.append("<header>\n");
            html.append("<h1>🔗 Oak Segment Consensus</h1>\n");
            html.append("<div class='subtitle'>Global P2P Oak Repository</div>\n");
            html.append("</header>\n");
            
            // Stats Grid
            html.append("<div class='grid'>\n");
            
            // Status Card
            html.append("<div class='card'>\n");
            html.append("<h2><span class='status'></span>Server Status</h2>\n");
            html.append("<div class='stat pulse'>LIVE</div>\n");
            html.append("<div class='label'>HTTP Segment Transfer Active</div>\n");
            html.append("</div>\n");
            
            // Store Size Card
            html.append("<div class='card'>\n");
            html.append("<h2>📦 Store Size</h2>\n");
            html.append("<div class='stat'>").append(formatBytes(storeSize)).append("</div>\n");
            html.append("<div class='label'>").append(segmentCount).append(" Segments</div>\n");
            html.append("</div>\n");
            
            // Validator Network Card (works for both blockchain and DAG mode)
            int validatorCount = 1; // Self
            String consensusType = "Single";
            if (consensusEngine != null) {
                validatorCount = 1 + consensusEngine.getPeerCount();
                consensusType = "Blockchain PoA";
            } else if (dagConsensusEngine != null) {
                validatorCount = 1 + dagConsensusEngine.getKnownHeads().size() - 1; // Self + peers
                consensusType = "Distributed DAG";
            }
            html.append("<div class='card'>\n");
            html.append("<h2>🗳️  Validator Network</h2>\n");
            html.append("<div class='stat'>").append(validatorCount).append("</div>\n");
            html.append("<div class='label'>").append(consensusType).append("</div>\n");
            html.append("</div>\n");
            
            // Connected Peers Card (dynamic - tracks actual Sling mounts)
            int peerCount = connectedPeers.size();
            html.append("<div class='card'>\n");
            html.append("<h2>🌐 Connected Peers</h2>\n");
            html.append("<div class='stat'>").append(peerCount).append("</div>\n");
            html.append("<div class='label'>Sling Author Instances</div>\n");
            html.append("</div>\n");
            
            // Add dynamic metrics cards via JavaScript
            html.append("<div id='dynamic-metrics'></div>\n");
            
            html.append("</div>\n"); // End grid
            
            // Recent Writes
            html.append("<div class='card'>\n");
            html.append("<h2>📝 Recent Segment Writes</h2>\n");
            if (recentWrites.isEmpty()) {
                html.append("<div class='journal-entry'>No recent writes</div>\n");
            } else {
                for (String entry : recentWrites) {
                    // Extract recordId (first part before colon)
                    String recordIdShort = entry.split(":")[0];
                    if (recordIdShort.length() > 20) {
                        recordIdShort = recordIdShort.substring(0, 20);
                    }
                    
                    // Check if we have metadata for this write
                    WriteMetadata meta = recentWriteMetadata.get(recordIdShort);
                    
                    if (meta != null) {
                        // Enhanced display with validator info
                        String badge = "";
                        String badgeColor = "";
                        if ("consensus".equals(meta.source)) {
                            badge = "CONSENSUS";
                            badgeColor = "#10b981"; // green
                        } else if ("epoch-sync".equals(meta.source)) {
                            badge = "EPOCH";
                            badgeColor = "#3b82f6"; // blue
                        }
                        
                        // Extract validator name (e.g., "validator-1" from "http://validator-1:8090")
                        String validatorName = meta.validator;
                        if (validatorName.contains("validator-")) {
                            validatorName = validatorName.substring(validatorName.indexOf("validator-"));
                            validatorName = validatorName.split(":")[0];
                        }
                        
                        html.append("<div class='journal-entry' style='border-left: 4px solid " + badgeColor + ";'>");
                        html.append("<div style='display: flex; justify-content: space-between; align-items: center;'>");
                        html.append("<code style='flex: 1;'>").append(escapeHtml(entry)).append("</code>");
                        html.append("<div style='display: flex; gap: 8px; margin-left: 12px;'>");
                        html.append("<span style='background: " + badgeColor + "; padding: 2px 8px; border-radius: 4px; font-size: 0.75em; font-weight: 600;'>");
                        html.append(badge).append("</span>");
                        html.append("<span style='background: rgba(255,255,255,0.1); padding: 2px 8px; border-radius: 4px; font-size: 0.75em;'>");
                        html.append("🗳️ ").append(validatorName).append("</span>");
                        html.append("</div></div>");
                        if (meta.message != null && !meta.message.isEmpty()) {
                            html.append("<div style='margin-top: 4px; font-size: 0.85em; opacity: 0.8;'>💬 ").append(escapeHtml(meta.message)).append("</div>");
                        }
                        html.append("</div>\n");
                    } else {
                        // Plain display (no metadata)
                        html.append("<div class='journal-entry'>").append(escapeHtml(entry)).append("</div>\n");
                    }
                }
            }
            html.append("</div>\n");
            
            // DAG Visualization (only in DAG mode)
            if (dagConsensusEngine != null) {
                html.append("<div class='card'>\n");
                html.append("<h2>🌳 Distributed DAG State</h2>\n");
                html.append("<div style='background: rgba(0,0,0,0.2); padding: 20px; border-radius: 8px; margin-top: 15px;'>\n");
                
                // Get DAG state
                java.util.Map<String, org.apache.jackrabbit.oak.segment.consensus.dag.DagHead> knownHeads = 
                    dagConsensusEngine.getKnownHeads();
                org.apache.jackrabbit.oak.segment.consensus.dag.DagHead myHead = dagConsensusEngine.getMyHead();
                
                // Show current state
                html.append("<div style='margin-bottom: 20px;'>\n");
                html.append("<div style='font-size: 0.9em; opacity: 0.7; margin-bottom: 10px;'>Current Network State:</div>\n");
                html.append("<div style='font-family: monospace; font-size: 0.9em;'>\n");
                
                for (java.util.Map.Entry<String, org.apache.jackrabbit.oak.segment.consensus.dag.DagHead> entry : knownHeads.entrySet()) {
                    org.apache.jackrabbit.oak.segment.consensus.dag.DagHead head = entry.getValue();
                    String validatorUrl = entry.getKey();
                    boolean isSelf = validatorUrl.equals(myHead.getValidatorUrl());
                    
                    String shortRecordId = head.getRecordId() != null && head.getRecordId().length() > 12 
                        ? head.getRecordId().substring(0, 12) + "..." 
                        : head.getRecordId();
                    
                    String style = isSelf 
                        ? "background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); padding: 10px 15px; margin: 5px 0; border-radius: 6px;" 
                        : "background: rgba(255,255,255,0.05); padding: 10px 15px; margin: 5px 0; border-radius: 6px; border-left: 3px solid #667eea;";
                    
                    html.append("<div style='").append(style).append("'>\n");
                    html.append("<div style='display: flex; justify-content: space-between; align-items: center;'>\n");
                    
                    // Validator name
                    String validatorName = validatorUrl.contains("validator") 
                        ? validatorUrl.substring(validatorUrl.indexOf("validator")) 
                        : "validator";
                    if (validatorName.contains(":")) {
                        validatorName = validatorName.split(":")[0];
                    }
                    
                    html.append("<div>\n");
                    html.append("<span style='font-weight: 600;'>").append(isSelf ? "👑 " : "🗳️  ").append(validatorName);
                    if (isSelf) html.append(" (ME)");
                    html.append("</span>\n");
                    html.append("<div style='font-size: 0.85em; opacity: 0.8; margin-top: 4px;'>HEAD: <code>").append(shortRecordId).append("</code></div>\n");
                    html.append("</div>\n");
                    
                    // Depth badge
                    html.append("<div style='text-align: right;'>\n");
                    html.append("<div style='background: rgba(0,0,0,0.3); padding: 4px 12px; border-radius: 12px; font-size: 0.85em;'>\n");
                    html.append("Depth: <span style='font-weight: 600;'>").append(head.getDepth()).append("</span>\n");
                    html.append("</div>\n");
                    
                    // Show if merge HEAD
                    if (head.isMerge()) {
                        html.append("<div style='margin-top: 4px; font-size: 0.75em; color: #fbbf24;'>🔀 MERGE</div>\n");
                    }
                    html.append("</div>\n");
                    
                    html.append("</div>\n");
                    html.append("</div>\n");
                }
                
                html.append("</div>\n");
                html.append("</div>\n");
                
                // DAG Status Summary
                html.append("<div style='margin-top: 15px; padding: 15px; background: rgba(16, 185, 129, 0.1); border-radius: 8px; border-left: 4px solid #10b981;'>\n");
                html.append("<div style='font-size: 0.9em;'>\n");
                html.append("<div>✅ <strong>DAG Mode Active</strong></div>\n");
                html.append("<div style='margin-top: 8px; opacity: 0.8;'>• ").append(knownHeads.size()).append(" active HEADs in network</div>\n");
                html.append("<div style='opacity: 0.8;'>• Parallel non-conflicting writes proceed independently</div>\n");
                html.append("<div style='opacity: 0.8;'>• Periodic merges consolidate divergent branches</div>\n");
                html.append("</div>\n");
                html.append("</div>\n");
                
                html.append("</div>\n");
                html.append("</div>\n");
            }
            
            // Explorer Link
            html.append("<div class='card' style='text-align: center; padding: 40px;'>\n");
            html.append("<h2>🔍 Content Explorer</h2>\n");
            html.append("<p style='margin: 20px 0; opacity: 0.9;'>Browse the global repository content tree and inspect segments</p>\n");
            html.append("<a href='/explorer' style='display: inline-block; padding: 15px 40px; background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); ");
            html.append("color: white; text-decoration: none; border-radius: 8px; font-weight: 600; font-size: 1.1em; ");
            html.append("box-shadow: 0 4px 12px rgba(102, 126, 234, 0.4); transition: transform 0.2s;' ");
            html.append("onmouseover='this.style.transform=\"scale(1.05)\"' onmouseout='this.style.transform=\"scale(1)\"'>");
            html.append("Launch Explorer →</a>\n");
            html.append("</div>\n");
            
            // API Endpoints
            html.append("<div class='card'>\n");
            html.append("<h2>🔌 API Endpoints</h2>\n");
            html.append("<div class='endpoints'>\n");
            html.append("<div class='endpoint'><code>GET /explorer</code> - Blockchain content explorer UI</div>\n");
            html.append("<div class='endpoint'><code>GET /api/explore?path={path}</code> - Browse node tree with properties (JSON)</div>\n");
            html.append("<div class='endpoint'><code>GET /api/segments/tars</code> - TAR files and storage blocks (JSON)</div>\n");
            html.append("<div class='endpoint'><code>GET /api/segments/recent</code> - Recent segment writes from journal (JSON)</div>\n");
            html.append("<div class='endpoint'><code>GET /api/metrics</code> - Consensus, replication, and system metrics (JSON)</div>\n");
            html.append("<div class='endpoint'><code>GET /health</code> - Health check</div>\n");
            html.append("<div class='endpoint'><code>GET /journal.log</code> - Journal file</div>\n");
            html.append("<div class='endpoint'><code>GET /manifest</code> - Manifest file</div>\n");
            html.append("<div class='endpoint'><code>GET /segments/{id}</code> - Fetch segment by ID</div>\n");
            html.append("</div>\n");
            html.append("</div>\n");
            
            html.append("</div>\n"); // End container
            
            // Add JavaScript for dynamic metrics
            html.append("<script>\n");
            html.append("async function loadMetrics() {\n");
            html.append("  try {\n");
            html.append("    const response = await fetch('/api/metrics');\n");
            html.append("    const data = await response.json();\n");
            html.append("    \n");
            html.append("    let html = '';\n");
            html.append("    \n");
            html.append("    // Consensus Performance Card\n");
            html.append("    if (data.consensus) {\n");
            html.append("      html += '<div class=\"card\">';\n");
            html.append("      html += '<h2>🎯 Consensus Performance</h2>';\n");
            html.append("      html += '<div class=\"stat\">' + data.consensus.successRate.toFixed(1) + '%</div>';\n");
            html.append("      html += '<div class=\"label\">Success Rate (' + data.consensus.successfulProposals + '/' + data.consensus.totalProposals + ')</div>';\n");
            html.append("      html += '<div style=\"margin-top: 10px; font-size: 0.9em; opacity: 0.8;\">⏱️  Avg Consensus: ' + data.consensus.averageConsensusTimeMs + 'ms</div>';\n");
            html.append("      html += '</div>';\n");
            html.append("    }\n");
            html.append("    \n");
            html.append("    // Replication Metrics Card\n");
            html.append("    if (data.replication) {\n");
            html.append("      html += '<div class=\"card\">';\n");
            html.append("      html += '<h2>🔄 Replication</h2>';\n");
            html.append("      html += '<div class=\"stat\">' + data.replication.totalSegments + '</div>';\n");
            html.append("      html += '<div class=\"label\">Segments Replicated (' + data.replication.totalMb + ' MB)</div>';\n");
            html.append("      html += '</div>';\n");
            html.append("    }\n");
            html.append("    \n");
            html.append("    // System Health Card\n");
            html.append("    if (data.system) {\n");
            html.append("      const uptimeSec = Math.floor(data.system.uptimeMs / 1000);\n");
            html.append("      const uptimeMin = Math.floor(uptimeSec / 60);\n");
            html.append("      const uptimeHour = Math.floor(uptimeMin / 60);\n");
            html.append("      const uptimeStr = uptimeHour > 0 ? uptimeHour + 'h ' + (uptimeMin % 60) + 'm' : uptimeMin + 'm';\n");
            html.append("      html += '<div class=\"card\">';\n");
            html.append("      html += '<h2>💚 System Health</h2>';\n");
            html.append("      html += '<div class=\"stat\">' + uptimeStr + '</div>';\n");
            html.append("      html += '<div class=\"label\">Uptime</div>';\n");
            html.append("      html += '<div style=\"margin-top: 10px; font-size: 0.9em; opacity: 0.8;\">💾 Memory: ' + data.system.memoryUsedMb + '/' + data.system.memoryMaxMb + ' MB</div>';\n");
            html.append("      html += '</div>';\n");
            html.append("    }\n");
            html.append("    \n");
            html.append("    // Validator Identity Card\n");
            html.append("    if (data.validator) {\n");
            html.append("      html += '<div class=\"card\">';\n");
            html.append("      html += '<h2>🪪 Validator Identity</h2>';\n");
            html.append("      html += '<div style=\"font-family: monospace; font-size: 0.85em; word-break: break-all; margin: 10px 0;\">' + data.validator.url + '</div>';\n");
            html.append("      html += '<div class=\"label\">My Address</div>';\n");
            html.append("      html += '</div>';\n");
            html.append("    }\n");
            html.append("    \n");
            html.append("    document.getElementById('dynamic-metrics').innerHTML = html;\n");
            html.append("  } catch (e) {\n");
            html.append("    console.error('Failed to load metrics:', e);\n");
            html.append("  }\n");
            html.append("}\n");
            html.append("\n");
            html.append("// Load metrics on page load and refresh every 5 seconds\n");
            html.append("loadMetrics();\n");
            html.append("setInterval(loadMetrics, 5000);\n");
            html.append("</script>\n");
            
            html.append("</body>\n</html>");
            
            response.getWriter().write(html.toString());
        }
        
        /**
         * Format bytes to human-readable string.
         */
        private String formatBytes(long bytes) {
            if (bytes < 1024) return bytes + " B";
            if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
            if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
            return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
        }
        
        /**
         * Escape HTML to prevent XSS.
         */
        private String escapeHtml(String text) {
            return text.replace("&", "&amp;")
                       .replace("<", "&lt;")
                       .replace(">", "&gt;")
                       .replace("\"", "&quot;")
                       .replace("'", "&#39;");
        }
        
        /**
         * Handle health check endpoint.
         */
        private void handleHealth(HttpServletResponse response) throws IOException {
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType("application/json");
            response.getWriter().write("{\"status\":\"UP\",\"store\":\"" + storeDirectory + "\"}");
        }
        
        /**
         * Handle serving a file from the segment store directory.
         */
        private void handleFile(HttpServletRequest request, HttpServletResponse response, String filename, String contentType) throws IOException {
            // Log requesting peer info
            String remoteAddr = request.getRemoteAddr();
            int remotePort = request.getRemotePort();
            
            Path filePath = storeDirectory.resolve(filename);
            
            if (!Files.exists(filePath)) {
                log.warn("File not found: {}", filePath);
                response.sendError(HttpServletResponse.SC_NOT_FOUND, "File not found: " + filename);
                return;
            }
            
            long fileSize = Files.size(filePath);
            
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType(contentType);
            response.setContentLengthLong(fileSize);
            
            try (InputStream in = Files.newInputStream(filePath);
                 OutputStream out = response.getOutputStream()) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }
            }
            
            log.info("📄 File GET: {} FROM {}:{} ({} bytes)", filename, remoteAddr, remotePort, fileSize);
        }
        
        /**
         * Handle blockchain explorer UI - Etherscan-like interface.
         */
        private void handleExplorerUI(HttpServletResponse response) throws IOException {
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType("text/html; charset=UTF-8");
            
            StringBuilder html = new StringBuilder();
            html.append("<!DOCTYPE html>\n<html>\n<head>\n");
            html.append("<meta charset='UTF-8'>\n");
            html.append("<title>🔗 Oak Segment Consensus Explorer</title>\n");
            html.append("<style>\n");
            html.append("* { margin: 0; padding: 0; box-sizing: border-box; }\n");
            html.append("body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; ");
            html.append("background: #0f172a; color: #e2e8f0; min-height: 100vh; }\n");
            html.append(".header { background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); padding: 20px; ");
            html.append("box-shadow: 0 4px 6px rgba(0,0,0,0.3); }\n");
            html.append(".header h1 { font-size: 2em; margin-bottom: 5px; }\n");
            html.append(".container { max-width: 1400px; margin: 0 auto; padding: 20px; }\n");
            html.append(".panel { background: #1e293b; border-radius: 10px; padding: 20px; margin: 20px 0; ");
            html.append("border: 1px solid #334155; }\n");
            html.append(".panel h2 { color: #a78bfa; margin-bottom: 15px; font-size: 1.3em; }\n");
            html.append(".tree-node { padding: 8px; margin: 4px 0; background: #0f172a; border-radius: 5px; ");
            html.append("cursor: pointer; transition: all 0.2s; }\n");
            html.append(".tree-node:hover { background: #1e293b; transform: translateX(5px); }\n");
            html.append(".node-name { color: #60a5fa; font-weight: 500; }\n");
            html.append(".node-type { color: #94a3b8; font-size: 0.9em; margin-left: 10px; }\n");
            html.append(".property { padding: 5px; margin: 3px 0; font-family: monospace; font-size: 0.9em; }\n");
            html.append(".prop-name { color: #fbbf24; }\n");
            html.append(".prop-value { color: #34d399; }\n");
            html.append(".breadcrumb { padding: 10px; background: #0f172a; border-radius: 5px; margin-bottom: 15px; }\n");
            html.append(".breadcrumb a { color: #60a5fa; text-decoration: none; margin: 0 5px; }\n");
            html.append(".breadcrumb a:hover { text-decoration: underline; }\n");
            html.append(".segment-entry { background: #0f172a; padding: 12px; margin: 8px 0; border-radius: 5px; ");
            html.append("border-left: 3px solid #8b5cf6; }\n");
            html.append(".segment-id { font-family: monospace; color: #60a5fa; }\n");
            html.append(".timestamp { color: #94a3b8; font-size: 0.9em; }\n");
            html.append(".tabs { display: flex; gap: 10px; margin-bottom: 20px; }\n");
            html.append(".tab { padding: 10px 20px; background: #1e293b; border-radius: 5px; cursor: pointer; ");
            html.append("border: 2px solid transparent; }\n");
            html.append(".tab.active { border-color: #8b5cf6; background: #2d3748; }\n");
            html.append(".loading { text-align: center; padding: 40px; color: #94a3b8; }\n");
            html.append("</style>\n");
            html.append("<script>\n");
            html.append("let currentPath = '/';\n\n");
            html.append("async function loadNode(path) {\n");
            html.append("  currentPath = path;\n");
            html.append("  document.getElementById('loading').style.display = 'block';\n");
            html.append("  document.getElementById('node-content').style.display = 'none';\n");
            html.append("  const response = await fetch('/api/explore?path=' + encodeURIComponent(path));\n");
            html.append("  const data = await response.json();\n");
            html.append("  displayNode(data);\n");
            html.append("  document.getElementById('loading').style.display = 'none';\n");
            html.append("  document.getElementById('node-content').style.display = 'block';\n");
            html.append("}\n\n");
            html.append("function displayNode(node) {\n");
            html.append("  const breadcrumb = document.getElementById('breadcrumb');\n");
            html.append("  const parts = currentPath.split('/').filter(p => p);\n");
            html.append("  let path = '';\n");
            html.append("  breadcrumb.innerHTML = '<a href=\"#\" onclick=\"loadNode(\\'/\\'); return false;\">root</a>';\n");
            html.append("  parts.forEach(part => {\n");
            html.append("    path += '/' + part;\n");
            html.append("    breadcrumb.innerHTML += ' / <a href=\"#\" onclick=\"loadNode(\\'' + path + '\\'); return false;\">' + part + '</a>';\n");
            html.append("  });\n\n");
            html.append("  const children = document.getElementById('children');\n");
            html.append("  children.innerHTML = '';\n");
            html.append("  node.children.forEach(child => {\n");
            html.append("    const div = document.createElement('div');\n");
            html.append("    div.className = 'tree-node';\n");
            html.append("    div.innerHTML = '<span class=\"node-name\">📁 ' + child + '</span>';\n");
            html.append("    div.onclick = () => loadNode(currentPath === '/' ? '/' + child : currentPath + '/' + child);\n");
            html.append("    children.appendChild(div);\n");
            html.append("  });\n\n");
            html.append("  const props = document.getElementById('properties');\n");
            html.append("  props.innerHTML = '';\n");
            html.append("  Object.entries(node.properties).forEach(([key, value]) => {\n");
            html.append("    const div = document.createElement('div');\n");
            html.append("    div.className = 'property';\n");
            html.append("    div.innerHTML = '<span class=\"prop-name\">' + key + ':</span> <span class=\"prop-value\">' + JSON.stringify(value) + '</span>';\n");
            html.append("    props.appendChild(div);\n");
            html.append("  });\n");
            html.append("}\n\n");
            html.append("async function loadTarFiles() {\n");
            html.append("  const response = await fetch('/api/segments/tars');\n");
            html.append("  const tars = await response.json();\n");
            html.append("  const container = document.getElementById('tar-files');\n");
            html.append("  container.innerHTML = '';\n");
            html.append("  if (tars.length === 0) {\n");
            html.append("    container.innerHTML = '<div style=\"color: #94a3b8; padding: 10px;\">No TAR files found</div>';\n");
            html.append("    return;\n");
            html.append("  }\n");
            html.append("  tars.forEach(tar => {\n");
            html.append("    const div = document.createElement('div');\n");
            html.append("    div.className = 'segment-entry';\n");
            html.append("    div.style.borderLeft = '3px solid #06b6d4';\n");
            html.append("    const segmentLabel = tar.estimatedCount ? tar.segmentCount + ' (est.)' : tar.segmentCount;\n");
            html.append("    div.innerHTML = '<div style=\"display: flex; justify-content: space-between; align-items: center;\">' +\n");
            html.append("      '<div>' +\n");
            html.append("        '<div class=\"segment-id\" style=\"margin-bottom: 5px;\">💾 ' + tar.name + '</div>' +\n");
            html.append("        '<div class=\"timestamp\">Size: ' + tar.sizeFormatted + ' • Segments: ' + segmentLabel + '</div>' +\n");
            html.append("      '</div>' +\n");
            html.append("      '<div style=\"text-align: right; font-size: 0.85em; color: #94a3b8;\">' +\n");
            html.append("        '<div>Created: ' + new Date(tar.created).toLocaleString() + '</div>' +\n");
            html.append("        '<div>Modified: ' + new Date(tar.modified).toLocaleString() + '</div>' +\n");
            html.append("      '</div>' +\n");
            html.append("    '</div>';\n");
            html.append("    container.appendChild(div);\n");
            html.append("  });\n");
            html.append("}\n\n");
            html.append("async function loadRecentSegments() {\n");
            html.append("  const response = await fetch('/api/segments/recent');\n");
            html.append("  const segments = await response.json();\n");
            html.append("  const container = document.getElementById('recent-segments');\n");
            html.append("  container.innerHTML = '';\n");
            html.append("  if (segments.length === 0) {\n");
            html.append("    container.innerHTML = '<div style=\"color: #94a3b8; padding: 10px;\">No recent segments</div>';\n");
            html.append("    return;\n");
            html.append("  }\n");
            html.append("  segments.forEach(seg => {\n");
            html.append("    const div = document.createElement('div');\n");
            html.append("    div.className = 'segment-entry';\n");
            html.append("    div.innerHTML = '<div class=\"segment-id\">Segment: ' + seg.id + '</div>' +\n");
            html.append("                    '<div class=\"timestamp\">' + seg.timestamp + '</div>';\n");
            html.append("    container.appendChild(div);\n");
            html.append("  });\n");
            html.append("}\n\n");
            html.append("window.onload = () => { loadNode('/'); loadTarFiles(); loadRecentSegments(); setInterval(loadRecentSegments, 5000); };\n");
            html.append("</script>\n");
            html.append("</head>\n<body>\n");
            html.append("<div class='header'>\n");
            html.append("<div class='container'><h1>🔗 Oak Segment Consensus Explorer</h1>\n");
            html.append("<div>Content Browser & Segment Inspector</div></div>\n");
            html.append("</div>\n");
            html.append("<div class='container'>\n");
            html.append("<div class='panel'>\n");
            html.append("<h2>🌳 Content Tree</h2>\n");
            html.append("<div class='breadcrumb' id='breadcrumb'>/</div>\n");
            html.append("<div id='loading' class='loading' style='display:none'>Loading...</div>\n");
            html.append("<div id='node-content'>\n");
            html.append("<div id='children'></div>\n");
            html.append("<h3 style='margin-top: 20px; color: #a78bfa;'>Properties</h3>\n");
            html.append("<div id='properties'></div>\n");
            html.append("</div></div>\n");
            html.append("<div class='panel'>\n");
            html.append("<h2>💾 TAR Files (Segment Storage Blocks)</h2>\n");
            html.append("<div id='tar-files'></div>\n");
            html.append("</div>\n");
            html.append("<div class='panel'>\n");
            html.append("<h2>📦 Recent Segments (Journal)</h2>\n");
            html.append("<div id='recent-segments'></div>\n");
            html.append("</div>\n");
            html.append("</div>\n</body>\n</html>");
            
            response.getWriter().write(html.toString());
        }
        
        /**
         * Handle API request to explore a node in the repository.
         */
        private void handleExploreNode(HttpServletResponse response, String path) throws IOException {
            response.setContentType("application/json");
            
            try {
                // Get the head state from NodeStore
                NodeState root = nodeStore.getRoot();
                
                // Navigate to the requested path
                NodeState node = root;
                if (!"/".equals(path)) {
                    String[] parts = path.substring(1).split("/");
                    for (String part : parts) {
                        if (!part.isEmpty()) {
                            node = node.getChildNode(part);
                            if (!node.exists()) {
                                response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                                response.getWriter().write("{\"error\":\"Node not found\"}");
                                return;
                            }
                        }
                    }
                }
                
                // Build JSON response
                StringBuilder json = new StringBuilder();
                json.append("{");
                json.append("\"path\":\"").append(escapeJson(path)).append("\",");
                json.append("\"children\":[");
                boolean first = true;
                for (String childName : node.getChildNodeNames()) {
                    if (!first) json.append(",");
                    json.append("\"").append(escapeJson(childName)).append("\"");
                    first = false;
                }
                json.append("],");
                json.append("\"properties\":{");
                
                // Iterate through actual properties
                boolean firstProp = true;
                for (org.apache.jackrabbit.oak.api.PropertyState prop : node.getProperties()) {
                    if (!firstProp) json.append(",");
                    firstProp = false;
                    
                    String propName = prop.getName();
                    json.append("\"").append(escapeJson(propName)).append("\":");
                    
                    // Handle different property types
                    if (prop.isArray()) {
                        json.append("[");
                        boolean firstVal = true;
                        for (int i = 0; i < prop.count(); i++) {
                            if (!firstVal) json.append(",");
                            firstVal = false;
                            json.append("\"").append(escapeJson(String.valueOf(prop.getValue(org.apache.jackrabbit.oak.api.Type.STRING, i)))).append("\"");
                        }
                        json.append("]");
                    } else {
                        // Single value - handle different types
                        try {
                            String value;
                            if (prop.getType() == org.apache.jackrabbit.oak.api.Type.BINARY) {
                                value = "[Binary: " + prop.size() + " bytes]";
                            } else if (prop.getType() == org.apache.jackrabbit.oak.api.Type.BOOLEAN) {
                                value = String.valueOf(prop.getValue(org.apache.jackrabbit.oak.api.Type.BOOLEAN));
                            } else if (prop.getType() == org.apache.jackrabbit.oak.api.Type.LONG) {
                                value = String.valueOf(prop.getValue(org.apache.jackrabbit.oak.api.Type.LONG));
                            } else if (prop.getType() == org.apache.jackrabbit.oak.api.Type.DOUBLE) {
                                value = String.valueOf(prop.getValue(org.apache.jackrabbit.oak.api.Type.DOUBLE));
                            } else if (prop.getType() == org.apache.jackrabbit.oak.api.Type.DATE) {
                                value = String.valueOf(prop.getValue(org.apache.jackrabbit.oak.api.Type.DATE));
                            } else {
                                value = prop.getValue(org.apache.jackrabbit.oak.api.Type.STRING);
                            }
                            json.append("\"").append(escapeJson(value)).append("\"");
                        } catch (Exception e) {
                            json.append("\"[Error: ").append(escapeJson(e.getMessage())).append("]\"");
                        }
                    }
                }
                
                json.append("}}");
                
                response.setStatus(HttpServletResponse.SC_OK);
                response.getWriter().write(json.toString());
                
            } catch (Exception e) {
                log.error("Error exploring node: " + path, e);
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                response.getWriter().write("{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
            }
        }
        
        /**
         * Handle API request for recent segments.
         */
        private void handleRecentSegments(HttpServletResponse response) throws IOException {
            response.setContentType("application/json");
            
            try {
                java.util.List<String> segments = new java.util.ArrayList<>();
                Path journalPath = storeDirectory.resolve("journal.log");
                
                if (java.nio.file.Files.exists(journalPath)) {
                    java.util.List<String> lines = java.nio.file.Files.readAllLines(journalPath);
                    int start = Math.max(0, lines.size() - 20);
                    for (int i = lines.size() - 1; i >= start; i--) {
                        String line = lines.get(i);
                        if (line.contains(" ")) {
                            String[] parts = line.split(" ", 2);
                            segments.add("{\"id\":\"" + escapeJson(parts[0]) + "\",\"timestamp\":\"" + 
                                       (parts.length > 1 ? escapeJson(parts[1]) : "") + "\"}");
                        }
                    }
                }
                
                response.setStatus(HttpServletResponse.SC_OK);
                response.getWriter().write("[" + String.join(",", segments) + "]");
                
            } catch (Exception e) {
                log.error("Error reading recent segments", e);
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                response.getWriter().write("[]");
            }
        }
        
        /**
         * Handle API request for TAR files and their segments.
         */
        private void handleTarFiles(HttpServletResponse response) throws IOException {
            response.setContentType("application/json");
            
            try {
                java.util.List<String> tarEntries = new java.util.ArrayList<>();
                
                // Count total segments in journal
                int totalSegments = 0;
                Path journalPath = storeDirectory.resolve("journal.log");
                if (java.nio.file.Files.exists(journalPath)) {
                    java.util.List<String> journalLines = java.nio.file.Files.readAllLines(journalPath);
                    totalSegments = journalLines.size();
                }
                
                // List all .tar files and calculate total size
                java.util.List<Path> tarFiles = new java.util.ArrayList<>();
                long totalSize = 0;
                try (java.util.stream.Stream<Path> paths = java.nio.file.Files.list(storeDirectory)) {
                    tarFiles = paths
                        .filter(p -> p.toString().endsWith(".tar"))
                        .sorted(java.util.Comparator.comparing(Path::toString))
                        .collect(java.util.stream.Collectors.toList());
                    for (Path tarFile : tarFiles) {
                        totalSize += java.nio.file.Files.size(tarFile);
                    }
                }
                
                // Build JSON entries
                for (Path tarFile : tarFiles) {
                    String fileName = tarFile.getFileName().toString();
                    long fileSize = java.nio.file.Files.size(tarFile);
                    java.nio.file.attribute.BasicFileAttributes attrs = 
                        java.nio.file.Files.readAttributes(tarFile, java.nio.file.attribute.BasicFileAttributes.class);
                    
                    // Estimate segment count based on proportional file size
                    int estimatedSegments = totalSize > 0 ? (int)((fileSize * totalSegments) / totalSize) : 0;
                    
                    StringBuilder entry = new StringBuilder();
                    entry.append("{");
                    entry.append("\"name\":\"").append(escapeJson(fileName)).append("\",");
                    entry.append("\"size\":").append(fileSize).append(",");
                    entry.append("\"sizeFormatted\":\"").append(formatBytes(fileSize)).append("\",");
                    entry.append("\"segmentCount\":").append(estimatedSegments).append(",");
                    entry.append("\"estimatedCount\":true,");
                    entry.append("\"created\":\"").append(attrs.creationTime().toString()).append("\",");
                    entry.append("\"modified\":\"").append(attrs.lastModifiedTime().toString()).append("\"");
                    entry.append("}");
                    
                    tarEntries.add(entry.toString());
                }
                
                response.setStatus(HttpServletResponse.SC_OK);
                response.getWriter().write("[" + String.join(",", tarEntries) + "]");
                
            } catch (Exception e) {
                log.error("Error reading TAR files", e);
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                response.getWriter().write("[]");
            }
        }
        
        /**
         * Escape JSON string.
         */
        private String escapeJson(String text) {
            if (text == null) return "";
            return text.replace("\\", "\\\\")
                       .replace("\"", "\\\"")
                       .replace("\n", "\\n")
                       .replace("\r", "\\r")
                       .replace("\t", "\\t");
        }
        
        /**
         * Handle HEAD request for a segment (check existence).
         */
        private void handleSegmentHead(HttpServletResponse response, String segmentId) throws IOException {
            Path segmentPath = findSegmentInTarFiles(segmentId);
            
            if (segmentPath == null) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND, "Segment not found: " + segmentId);
                return;
            }
            
            response.setStatus(HttpServletResponse.SC_OK);
            response.setHeader("X-Segment-Found", "true");
        }
        
        /**
         * Handle GET request for a segment (fetch segment data).
         */
        private void handleSegmentGet(HttpServletRequest request, HttpServletResponse response, String segmentId) throws IOException {
            // Log requesting peer info
            String remoteAddr = request.getRemoteAddr();
            int remotePort = request.getRemotePort();
            String userAgent = request.getHeader("User-Agent");
            
            log.info("📦 Segment GET: {} FROM {}:{} [UA: {}]", 
                     segmentId, remoteAddr, remotePort, userAgent != null ? userAgent : "unknown");
            
            // Track connected peer (Sling Author mounting this store)
            if (!"localhost".equals(remoteAddr) && !"127.0.0.1".equals(remoteAddr)) {
                connectedPeers.add(remoteAddr + ":" + remotePort);
            }
            
            // Convert UUID string to msb/lsb
            java.util.UUID uuid;
            try {
                uuid = java.util.UUID.fromString(segmentId);
            } catch (IllegalArgumentException e) {
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid segment UUID: " + segmentId);
                return;
            }
            
            // Read segment from TAR files
            byte[] segmentData = readSegmentFromTar(uuid);
            
            if (segmentData == null) {
                log.warn("Segment not found in TAR files: {}", segmentId);
                response.sendError(HttpServletResponse.SC_NOT_FOUND, "Segment not found: " + segmentId);
                return;
            }
            
            // Return segment data
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType("application/octet-stream");
            response.setContentLength(segmentData.length);
            response.setHeader("X-Segment-Length", String.valueOf(segmentData.length));
            
            try (OutputStream out = response.getOutputStream()) {
                out.write(segmentData);
                out.flush();
            }
            
            log.info("Served segment {} ({} bytes)", segmentId, segmentData.length);
        }
        
        /**
         * Read a segment from TAR files.
         */
        private byte[] readSegmentFromTar(java.util.UUID segmentId) {
            // Look for data*.tar files
            File[] tarFiles = storeDirectory.toFile().listFiles((dir, name) -> 
                name.startsWith("data") && name.endsWith(".tar"));
            
            if (tarFiles == null || tarFiles.length == 0) {
                log.warn("No TAR files found in {}", storeDirectory);
                return null;
            }
            
            // Try each TAR file
            for (File tarFile : tarFiles) {
                try {
                    byte[] segment = readSegmentFromTarFile(tarFile, segmentId);
                    if (segment != null) {
                        return segment;
                    }
                } catch (IOException e) {
                    log.warn("Error reading from TAR file {}: {}", tarFile.getName(), e.getMessage());
                }
            }
            
            return null;
        }
        
        /**
         * Read a specific segment from TAR files using Oak's FileStore.
         * Uses the same pattern as Cold Standby segment replication.
         */
        private byte[] readSegmentFromTarFile(File tarFile, UUID segmentId) throws IOException {
            try {
                long msb = segmentId.getMostSignificantBits();
                long lsb = segmentId.getLeastSignificantBits();
                
                // Create SegmentId using FileStore's provider (Cold Standby pattern)
                SegmentId sid = fileStore.getSegmentIdProvider().newSegmentId(msb, lsb);
                
                // Check if segment exists
                if (!fileStore.containsSegment(sid)) {
                    log.debug("Segment {} not found in store", segmentId);
                    return null;
                }
                
                // Read segment and serialize to bytes (Cold Standby pattern)
                Segment segment = fileStore.readSegment(sid);
                try (ByteArrayOutputStream stream = new ByteArrayOutputStream()) {
                    segment.writeTo(stream);
                    byte[] data = stream.toByteArray();
                    log.info("Read segment {} from TAR ({} bytes)", segmentId, data.length);
                    return data;
                }
            } catch (Exception e) {
                log.warn("Error reading segment {}: {}", segmentId, e.getMessage());
                return null;
            }
        }
        
        /**
         * Find a segment in TAR files (simplified - just check if any TAR files exist).
         */
        private Path findSegmentInTarFiles(String segmentId) throws IOException {
            // Look for data*.tar files
            File[] tarFiles = storeDirectory.toFile().listFiles((dir, name) -> 
                name.startsWith("data") && name.endsWith(".tar"));
            
            if (tarFiles != null && tarFiles.length > 0) {
                // Segment might exist - return the first TAR file
                // Full implementation would need to parse TAR internals
                return tarFiles[0].toPath();
            }
            
            return null;
        }
        
        /**
         * Handle POST /v1/propose - Receive write proposal from peer
         */
        private void handleWriteProposal(HttpServletRequest request, HttpServletResponse response) throws IOException {
            if (consensusEngine == null) {
                response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Consensus engine not configured");
                return;
            }
            
            // Read JSON body
            String json = request.getReader().lines().collect(Collectors.joining());
            
            try {
                // Parse proposal (simple JSON parsing for Phase 1)
                WriteProposal proposal = parseProposal(json);
                
                // Process proposal and vote
                Vote vote = consensusEngine.handleProposal(proposal);
                
                // Return vote immediately
                response.setContentType("application/json");
                response.setStatus(HttpServletResponse.SC_OK);
                response.getWriter().write(voteToJson(vote));
                
            } catch (Exception e) {
                log.error("Error processing proposal", e);
                response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
            }
        }
        
        /**
         * Handle POST /v1/vote - Receive vote from peer
         */
        private void handleVote(HttpServletRequest request, HttpServletResponse response) throws IOException {
            if (consensusEngine == null) {
                response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Consensus engine not configured");
                return;
            }
            
            // Read JSON body
            String json = request.getReader().lines().collect(Collectors.joining());
            
            try {
                // Parse vote
                Vote vote = parseVote(json);
                
                // Process vote
                consensusEngine.handleVote(vote);
                
                // Return OK
                response.setContentType("application/json");
                response.setStatus(HttpServletResponse.SC_OK);
                response.getWriter().write("{\"status\":\"accepted\"}");
                
            } catch (Exception e) {
                log.error("Error processing vote", e);
                response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
            }
        }
        
        /**
         * Handle GET /v1/peers - List known peers
         */
        private void handleListPeers(HttpServletResponse response) throws IOException {
            response.setContentType("application/json");
            response.setStatus(HttpServletResponse.SC_OK);
            
            // Return empty list for now (will be populated when consensus engine is set)
            response.getWriter().write("{\"peers\":[]}");
        }
        
        /**
         * Handle GET /api/metrics - Return consensus and replication metrics
         */
        private void handleMetrics(HttpServletResponse response) throws IOException {
            response.setContentType("application/json");
            response.setStatus(HttpServletResponse.SC_OK);
            
            StringBuilder json = new StringBuilder();
            json.append("{\n");
            
            // System metrics
            Runtime runtime = Runtime.getRuntime();
            long totalMemory = runtime.totalMemory();
            long freeMemory = runtime.freeMemory();
            long usedMemory = totalMemory - freeMemory;
            long maxMemory = runtime.maxMemory();
            
            json.append("  \"system\": {\n");
            json.append("    \"uptimeMs\": ").append(consensusEngine != null ? consensusEngine.getUptimeMs() : 0).append(",\n");
            json.append("    \"memoryUsedMb\": ").append(usedMemory / (1024 * 1024)).append(",\n");
            json.append("    \"memoryTotalMb\": ").append(totalMemory / (1024 * 1024)).append(",\n");
            json.append("    \"memoryMaxMb\": ").append(maxMemory / (1024 * 1024)).append("\n");
            json.append("  },\n");
            
            // Consensus metrics
            if (consensusEngine != null) {
                json.append("  \"consensus\": {\n");
                json.append("    \"totalProposals\": ").append(consensusEngine.getTotalProposals()).append(",\n");
                json.append("    \"successfulProposals\": ").append(consensusEngine.getSuccessfulProposals()).append(",\n");
                json.append("    \"failedProposals\": ").append(consensusEngine.getFailedProposals()).append(",\n");
                json.append("    \"successRate\": ").append(String.format("%.1f", consensusEngine.getConsensusSuccessRate())).append(",\n");
                json.append("    \"averageConsensusTimeMs\": ").append(consensusEngine.getAverageConsensusTimeMs()).append(",\n");
                json.append("    \"totalVotesReceived\": ").append(consensusEngine.getTotalVotesReceived()).append("\n");
                json.append("  },\n");
                
                json.append("  \"replication\": {\n");
                json.append("    \"totalSegments\": ").append(consensusEngine.getTotalSegmentsReplicated()).append(",\n");
                json.append("    \"totalBytes\": ").append(consensusEngine.getTotalBytesReplicated()).append(",\n");
                json.append("    \"totalMb\": ").append(String.format("%.2f", consensusEngine.getTotalBytesReplicated() / (1024.0 * 1024.0))).append("\n");
                json.append("  },\n");
                
                json.append("  \"validator\": {\n");
                json.append("    \"url\": \"").append(consensusEngine.getSelfUrl()).append("\",\n");
                json.append("    \"peers\": ").append(consensusEngine.getPeerCount()).append("\n");
                json.append("  }\n");
            } else {
                json.append("  \"consensus\": null,\n");
                json.append("  \"replication\": null,\n");
                json.append("  \"validator\": null\n");
            }
            
            json.append("}\n");
            
            response.getWriter().write(json.toString());
        }
        
        /**
         * Handle POST /v1/test-write - Write endpoint with wallet-based storage
         * 
         * Parameters:
         *   - wallet: Ethereum address (e.g., 0x1234...)
         *   - signature: Message signature (mock for now, real Web3j verification later)
         *   - message: Content to write
         *   - contentType: Type of content (default: "page")
         */
        private void handleTestWrite(HttpServletRequest request, HttpServletResponse response) throws IOException {
            // Check if any consensus engine is configured
            if (consensusEngine == null && dagConsensusEngine == null) {
                response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Consensus engine not configured");
                return;
            }
            
            boolean usingDagMode = (dagConsensusEngine != null);
            
            try {
                // Read wallet-based write parameters
                String wallet = request.getParameter("wallet");
                String signature = request.getParameter("signature");
                String message = request.getParameter("message");
                String contentType = request.getParameter("contentType");
                
                // Default values
                if (wallet == null || wallet.isEmpty()) {
                    wallet = "0x742d35Cc6634C0532925a3b844Bc9e7595f0bEb"; // Mock default wallet
                }
                if (message == null || message.isEmpty()) {
                    message = "Test content at " + System.currentTimeMillis();
                }
                if (contentType == null || contentType.isEmpty()) {
                    contentType = "page";
                }
                if (signature == null || signature.isEmpty()) {
                    signature = "0xMOCK" + System.currentTimeMillis(); // Mock signature
                }
                
                // Validate Ethereum address format (basic check)
                if (!wallet.startsWith("0x") || wallet.length() < 10) {
                    response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid Ethereum address format");
                    return;
                }
                
                log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                log.info("🔐 WALLET-BASED WRITE INITIATED");
                log.info("   Wallet: {}", wallet);
                log.info("   Content Type: {}", contentType);
                log.info("   Message: {}", message);
                log.info("   Signature: {}...{}", signature.substring(0, Math.min(10, signature.length())), 
                         signature.length() > 10 ? signature.substring(signature.length() - 4) : "");
                log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                
                // TODO: Real signature verification with Web3j
                // For now, we accept all signatures starting with "0x"
                log.info("✅ Signature verification: MOCK (accepted)");
                
                // Get current HEAD
                String previousHead = fileStore.getHead().getRecordId().toString();
                log.info("📍 Previous HEAD: {}", previousHead.substring(0, Math.min(20, previousHead.length())));
                
                // Make a write to the repository at /oak-chain/content/<wallet>/
                org.apache.jackrabbit.oak.spi.state.NodeBuilder rootBuilder = nodeStore.getRoot().builder();
                
                // Create wallet-specific path: /oak-chain/content/<wallet>/
                org.apache.jackrabbit.oak.spi.state.NodeBuilder walletPath = rootBuilder.child("oak-chain")
                    .child("content")
                    .child(wallet.toLowerCase()); // Wallet addresses are case-insensitive
                
                // Create content node under wallet path
                String contentId = contentType + "-" + System.currentTimeMillis();
                org.apache.jackrabbit.oak.spi.state.NodeBuilder contentNode = walletPath.child(contentId);
                
                // Set properties
                contentNode.setProperty("jcr:primaryType", "nt:unstructured");
                contentNode.setProperty("contentType", contentType);
                contentNode.setProperty("message", message);
                contentNode.setProperty("timestamp", System.currentTimeMillis());
                contentNode.setProperty("wallet", wallet);
                contentNode.setProperty("signature", signature);
                contentNode.setProperty("source", "consensus-write");
                
                // Commit the change (this creates new segments!)
                org.apache.jackrabbit.oak.spi.commit.CommitInfo commitInfo = 
                    new org.apache.jackrabbit.oak.spi.commit.CommitInfo(
                        "consensus-test", 
                        null, 
                        java.util.Collections.singletonMap("test", "true")
                    );
                
                nodeStore.merge(rootBuilder, org.apache.jackrabbit.oak.spi.commit.EmptyHook.INSTANCE, commitInfo);
                
                // CRITICAL: Flush FileStore to ensure all segments are persisted to disk
                // BEFORE broadcasting proposal to peers!
                fileStore.flush();
                log.info("✅ FileStore flushed - segments persisted to disk");
                
                // Get new HEAD
                String newHead = fileStore.getHead().getRecordId().toString();
                log.info("📍 New HEAD: {}", newHead.substring(0, Math.min(20, newHead.length())));
                
                // Create write proposal with wallet metadata
                WriteProposal proposal = new WriteProposal(
                    "http://localhost:8090", // Will be overridden by consensus engine with self URL
                    previousHead,
                    newHead
                );
                proposal.setAuthor(wallet); // Wallet address as author
                proposal.setCommitMessage("Wallet write: " + contentType + " - " + message);
                proposal.setMockPaymentVerified(true); // TODO: Verify payment from smart contract
                proposal.setMockSignature(signature);
                
                // TODO: Add actual segments to proposal
                // For Phase 1, we'll rely on validators fetching via HTTP
                
                log.info("📤 Processing write via {} mode...", usingDagMode ? "DAG" : "Blockchain");
                log.info("   Storage path: /oak-chain/content/{}/{}", wallet.toLowerCase(), contentId);
                
                boolean success = false;
                String consensusMode = "";
                
                if (usingDagMode) {
                    // DAG MODE: Write succeeds immediately, broadcast HEAD update
                    log.info("🌳 DAG MODE: Write succeeds locally (no immediate consensus needed)");
                    dagConsensusEngine.proposeWrite(newHead, "Wallet write: " + contentType + " - " + message);
                    success = true;
                    consensusMode = "dag-local";
                    log.info("✅ Local write complete, HEAD update broadcasted to peers");
                } else {
                    // BLOCKCHAIN MODE: Requires consensus before committing
                    log.info("⛓️  BLOCKCHAIN MODE: Proposing to consensus network...");
                    success = consensusEngine.proposeWrite(proposal);
                    consensusMode = success ? "blockchain-consensus" : "blockchain-rejected";
                }
                
                // Return result
                response.setContentType("application/json");
                response.setStatus(HttpServletResponse.SC_OK);
                
                String result = "{" +
                    "\"success\":" + success + "," +
                    "\"proposalId\":\"" + proposal.getProposalId() + "\"," +
                    "\"wallet\":\"" + wallet + "\"," +
                    "\"contentId\":\"" + contentId + "\"," +
                    "\"storagePath\":\"/oak-chain/content/" + wallet.toLowerCase() + "/" + contentId + "\"," +
                    "\"previousHead\":\"" + previousHead + "\"," +
                    "\"newHead\":\"" + newHead + "\"," +
                    "\"message\":\"" + message + "\"," +
                    "\"contentType\":\"" + contentType + "\"," +
                    "\"consensusMode\":\"" + consensusMode + "\"," +
                    "\"mode\":\"" + (usingDagMode ? "dag" : "blockchain") + "\"" +
                    "}";
                
                response.getWriter().write(result);
                
                if (success) {
                    log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                    if (usingDagMode) {
                        log.info("✅ DAG WRITE COMPLETE! Local HEAD updated, peers notified");
                    } else {
                        log.info("✅ CONSENSUS REACHED! Write committed across all validators");
                    }
                    log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                    
                    // Track write metadata for dashboard
                    String recordIdShort = newHead.length() > 20 ? newHead.substring(0, 20) : newHead;
                    recentWriteMetadata.put(recordIdShort, new WriteMetadata(
                        newHead,
                        "consensus",
                        proposal.getProposerUrl(),
                        System.currentTimeMillis(),
                        message
                    ));
                } else {
                    log.warn("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                    log.warn("❌ CONSENSUS FAILED! Write not replicated");
                    log.warn("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                }
                
            } catch (Exception e) {
                log.error("❌ Test write failed", e);
                response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Test write failed: " + e.getMessage());
            }
        }
        
        // Simple JSON parsing for Phase 1
        
        private WriteProposal parseProposal(String json) {
            WriteProposal proposal = new WriteProposal();
            
            // Extract fields (simple string parsing for Phase 1)
            proposal.setProposalId(extractJsonField(json, "proposalId"));
            proposal.setProposerUrl(extractJsonField(json, "proposerUrl"));
            proposal.setPreviousHead(extractJsonField(json, "previousHead"));
            proposal.setNewHead(extractJsonField(json, "newHead"));
            proposal.setAuthor(extractJsonField(json, "author"));
            
            String timestamp = extractJsonField(json, "timestamp");
            if (timestamp != null) {
                proposal.setTimestamp(Long.parseLong(timestamp));
            }
            
            String mockPayment = extractJsonField(json, "mockPaymentVerified");
            proposal.setMockPaymentVerified(mockPayment == null || "true".equals(mockPayment));
            
            // TODO: Parse segments array
            
            return proposal;
        }
        
        private Vote parseVote(String json) {
            Vote vote = new Vote();
            
            vote.setProposalId(extractJsonField(json, "proposalId"));
            vote.setValidatorUrl(extractJsonField(json, "validatorUrl"));
            
            String voteType = extractJsonField(json, "voteType");
            vote.setVoteType("ACCEPT".equals(voteType) ? Vote.VoteType.ACCEPT : Vote.VoteType.REJECT);
            
            vote.setReason(extractJsonField(json, "reason"));
            
            String timestamp = extractJsonField(json, "timestamp");
            if (timestamp != null) {
                vote.setTimestamp(Long.parseLong(timestamp));
            }
            
            return vote;
        }
        
        private String extractJsonField(String json, String field) {
            int start = json.indexOf("\"" + field + "\"");
            if (start == -1) return null;
            
            start = json.indexOf(":", start) + 1;
            
            // Skip whitespace
            while (start < json.length() && Character.isWhitespace(json.charAt(start))) {
                start++;
            }
            
            // Check if value is quoted (string) or unquoted (number/boolean/null)
            if (json.charAt(start) == '"') {
                // Quoted string
                start++; // Skip opening quote
                int end = json.indexOf("\"", start);
                if (end == -1) return null;
                return json.substring(start, end);
            } else {
                // Unquoted value (number, boolean, or null) - read until comma, }, or ]
                int end = start;
                while (end < json.length()) {
                    char c = json.charAt(end);
                    if (c == ',' || c == '}' || c == ']' || Character.isWhitespace(c)) {
                        break;
                    }
                    end++;
                }
                return json.substring(start, end).trim();
            }
        }
        
        private String voteToJson(Vote v) {
            return "{" +
                "\"proposalId\":\"" + v.getProposalId() + "\"," +
                "\"validatorUrl\":\"" + v.getValidatorUrl() + "\"," +
                "\"voteType\":\"" + v.getVoteType() + "\"," +
                "\"reason\":\"" + (v.getReason() != null ? v.getReason() : "") + "\"," +
                "\"timestamp\":" + v.getTimestamp() +
                "}";
        }
    }
}

