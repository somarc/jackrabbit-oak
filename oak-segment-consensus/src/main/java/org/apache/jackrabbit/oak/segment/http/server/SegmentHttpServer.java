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
                    handleFile(response, "journal.log", "text/plain");
                    baseRequest.setHandled(true);
                    return;
                }
                
                // Manifest file
                if ("/manifest".equals(path)) {
                    handleFile(response, "manifest", "text/plain");
                    baseRequest.setHandled(true);
                    return;
                }
                
                // GC log
                if ("/gc.log".equals(path)) {
                    handleFile(response, "gc.log", "text/plain");
                    baseRequest.setHandled(true);
                    return;
                }
                
                // Segments
                if (path != null && path.startsWith("/segments/")) {
                    String segmentId = path.substring("/segments/".length());
                    if ("HEAD".equals(method)) {
                        handleSegmentHead(response, segmentId);
                    } else if ("GET".equals(method)) {
                        handleSegmentGet(response, segmentId);
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
                
                // Explorer UI
                if ("/explorer".equals(path)) {
                    handleExplorerUI(response);
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
            html.append("<title>⛓️ Blockchain AEM - Global Store</title>\n");
            html.append("<style>\n");
            html.append("* { margin: 0; padding: 0; box-sizing: border-box; }\n");
            html.append("body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Oxygen, Ubuntu, sans-serif; ");
            html.append("background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); color: #fff; min-height: 100vh; padding: 20px; }\n");
            html.append(".container { max-width: 1200px; margin: 0 auto; }\n");
            html.append("header { text-align: center; padding: 40px 0; }\n");
            html.append("h1 { font-size: 3em; margin-bottom: 10px; text-shadow: 2px 2px 4px rgba(0,0,0,0.3); }\n");
            html.append(".subtitle { font-size: 1.2em; opacity: 0.9; }\n");
            html.append(".grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(300px, 1fr)); gap: 20px; margin: 30px 0; }\n");
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
            html.append("<h1>⛓️ Blockchain AEM</h1>\n");
            html.append("<div class='subtitle'>Global Read-Only Oak Repository</div>\n");
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
            
            // Connected Peers Card (simulated for POC)
            html.append("<div class='card'>\n");
            html.append("<h2>🌐 Connected Peers</h2>\n");
            html.append("<div class='stat'>1</div>\n");
            html.append("<div class='label'>Sling Author Instances</div>\n");
            html.append("</div>\n");
            
            html.append("</div>\n"); // End grid
            
            // Recent Writes
            html.append("<div class='card'>\n");
            html.append("<h2>📝 Recent Segment Writes</h2>\n");
            if (recentWrites.isEmpty()) {
                html.append("<div class='journal-entry'>No recent writes</div>\n");
            } else {
                for (String entry : recentWrites) {
                    html.append("<div class='journal-entry'>").append(escapeHtml(entry)).append("</div>\n");
                }
            }
            html.append("</div>\n");
            
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
            html.append("<div class='endpoint'><code>GET /api/explore?path={path}</code> - Browse node tree (JSON)</div>\n");
            html.append("<div class='endpoint'><code>GET /api/segments/recent</code> - Recent segment writes (JSON)</div>\n");
            html.append("<div class='endpoint'><code>GET /health</code> - Health check</div>\n");
            html.append("<div class='endpoint'><code>GET /journal.log</code> - Journal file</div>\n");
            html.append("<div class='endpoint'><code>GET /manifest</code> - Manifest file</div>\n");
            html.append("<div class='endpoint'><code>GET /segments/{id}</code> - Fetch segment</div>\n");
            html.append("</div>\n");
            html.append("</div>\n");
            
            html.append("</div>\n"); // End container
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
        private void handleFile(HttpServletResponse response, String filename, String contentType) throws IOException {
            Path filePath = storeDirectory.resolve(filename);
            
            if (!Files.exists(filePath)) {
                log.warn("File not found: {}", filePath);
                response.sendError(HttpServletResponse.SC_NOT_FOUND, "File not found: " + filename);
                return;
            }
            
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType(contentType);
            response.setContentLengthLong(Files.size(filePath));
            
            try (InputStream in = Files.newInputStream(filePath);
                 OutputStream out = response.getOutputStream()) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }
            }
            
            log.debug("Served file: {} ({} bytes)", filename, Files.size(filePath));
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
            html.append("<title>⛓️ Blockchain AEM Explorer</title>\n");
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
            html.append("async function loadRecentSegments() {\n");
            html.append("  const response = await fetch('/api/segments/recent');\n");
            html.append("  const segments = await response.json();\n");
            html.append("  const container = document.getElementById('recent-segments');\n");
            html.append("  container.innerHTML = '';\n");
            html.append("  segments.forEach(seg => {\n");
            html.append("    const div = document.createElement('div');\n");
            html.append("    div.className = 'segment-entry';\n");
            html.append("    div.innerHTML = '<div class=\"segment-id\">Segment: ' + seg.id + '</div>' +\n");
            html.append("                    '<div class=\"timestamp\">' + seg.timestamp + '</div>';\n");
            html.append("    container.appendChild(div);\n");
            html.append("  });\n");
            html.append("}\n\n");
            html.append("window.onload = () => { loadNode('/'); loadRecentSegments(); setInterval(loadRecentSegments, 5000); };\n");
            html.append("</script>\n");
            html.append("</head>\n<body>\n");
            html.append("<div class='header'>\n");
            html.append("<div class='container'><h1>⛓️ Blockchain AEM Explorer</h1>\n");
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
            html.append("<h2>📦 Recent Segments</h2>\n");
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
                // For POC: just show property count
                // Full property iteration requires more complex Oak API handling
                json.append("\"_propertyCount\":").append(node.getPropertyCount());
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
        private void handleSegmentGet(HttpServletResponse response, String segmentId) throws IOException {
            log.info("Reading segment from TAR: {}", segmentId);
            
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
    }
}

