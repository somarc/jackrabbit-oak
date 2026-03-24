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
package org.apache.jackrabbit.oak.segment.agentic.tools;

import java.util.Map;

/**
 * Tool that provides comprehensive API documentation for Oak Segment Consensus and HTTP modules.
 * This is especially important for agent-to-agent communication where the LLM needs to know
 * what APIs are available without having to query them first.
 */
public class ApiDocumentationTool implements AgenticTool {
    
    private final boolean isSlingContext;
    private final String validatorBaseUrl;
    
    public ApiDocumentationTool(boolean isSlingContext, String validatorBaseUrl) {
        this.isSlingContext = isSlingContext;
        this.validatorBaseUrl = validatorBaseUrl != null ? validatorBaseUrl : "http://localhost:8091";
    }
    
    @Override
    public String getName() {
        return "api-documentation";
    }
    
    @Override
    public String getDescription() {
        return "Provides comprehensive API documentation for Oak Segment Consensus and HTTP modules";
    }
    
    @Override
    public boolean shouldUse(String query) {
        String lower = query.toLowerCase();
        // Always provide API docs in agent-to-agent mode, or when explicitly asked
        return lower.contains("api") || lower.contains("endpoint") || 
               lower.contains("capability") || lower.contains("what can") ||
               lower.contains("available") || lower.contains("documentation");
    }
    
    @Override
    public ToolResult execute(String query, Map<String, Object> context) {
        StringBuilder docs = new StringBuilder();
        
        // Check if this is agent-to-agent communication
        boolean isAgentToAgent = context != null && (
            Boolean.TRUE.equals(context.get("agentToAgent")) ||
            context.containsKey("agentId")
        );
        
        if (isAgentToAgent) {
            docs.append("🤖 AGENT-TO-AGENT API DOCUMENTATION\n");
            docs.append("=").append("=".repeat(50)).append("\n\n");
        }
        
        if (isSlingContext) {
            docs.append("📋 SLING AUTHOR CONTEXT APIs\n");
            docs.append("=").append("=".repeat(50)).append("\n\n");
            docs.append("As a Sling author, you can access validator APIs via HTTP.\n");
            docs.append("Validator Base URL: ").append(validatorBaseUrl).append("\n\n");
        } else {
            docs.append("📋 VALIDATOR CONTEXT APIs\n");
            docs.append("=").append("=".repeat(50)).append("\n\n");
            docs.append("You are running as a validator. These APIs are available locally.\n\n");
        }
        
        // Oak Segment Consensus APIs (served by validator)
        docs.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        docs.append("🌐 OAK SEGMENT CONSENSUS APIs (oak-segment-consensus)\n");
        docs.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n\n");
        
        docs.append("📊 EXPLORER APIs\n");
        docs.append("  GET /explorer\n");
        docs.append("    Description: Blockchain content explorer UI (HTML)\n");
        docs.append("    Response: HTML dashboard\n\n");
        
        docs.append("  GET /api/explore?path={path}\n");
        docs.append("    Description: Browse node tree structure (JSON)\n");
        docs.append("    Parameters: path (query param) - e.g., /oak-chain/content/ethereum\n");
        docs.append("    Response: JSON with node structure, properties, children\n");
        docs.append("    Example: GET /api/explore?path=/oak-chain\n\n");
        
        docs.append("  GET /api/segments/tars\n");
        docs.append("    Description: List all TAR files and storage blocks (JSON)\n");
        docs.append("    Response: JSON array of TAR file metadata\n\n");
        
        docs.append("  GET /api/segments/recent\n");
        docs.append("    Description: Recent segment writes from journal (JSON)\n");
        docs.append("    Response: JSON array of recent segment IDs and timestamps\n\n");
        
        docs.append("💚 HEALTH & MONITORING APIs\n");
        docs.append("  GET /health\n");
        docs.append("    Description: Basic health check (JSON)\n");
        docs.append("    Response: {\"status\": \"UP\", \"timestamp\": ...}\n\n");
        
        docs.append("  GET /health/deep\n");
        docs.append("    Description: Comprehensive health validation (JSON)\n");
        docs.append("    Response: Detailed health status including consensus, storage, network\n\n");
        
        docs.append("  GET /api/metrics\n");
        docs.append("    Description: Consensus & replication metrics (JSON)\n");
        docs.append("    Response: JSON with consensus metrics, replication stats\n\n");
        
        docs.append("  GET /metrics\n");
        docs.append("    Description: Prometheus metrics (text/plain)\n");
        docs.append("    Response: Prometheus text format for scraping\n\n");
        
        docs.append("🔄 CONSENSUS APIs\n");
        docs.append("  GET /v1/consensus/status\n");
        docs.append("    Description: Get consensus state (Aeron-aware)\n");
        docs.append("    Response: JSON with leader, term, cluster state\n");
        docs.append("    Example Response: {\"leader\": \"node-0\", \"term\": 5, \"mode\": \"aeron\"}\n\n");
        
        docs.append("  POST /v1/propose-write\n");
        docs.append("    Description: Propose signed write transaction\n");
        docs.append("    Request Body: {\n");
        docs.append("      \"wallet\": \"0x...\",\n");
        docs.append("      \"message\": \"content\",\n");
        docs.append("      \"contentType\": \"page\",\n");
        docs.append("      \"signature\": \"0x...\",\n");
        docs.append("      \"clientId\": \"sling-author-1\"\n");
        docs.append("    }\n");
        docs.append("    Response: JSON with proposal status\n\n");
        
        docs.append("  GET /v1/head\n");
        docs.append("    Description: Get current HEAD record ID (text/plain)\n");
        docs.append("    Response: Record ID string (e.g., \"abc123-def456-...\")\n\n");
        
        docs.append("✈️ AERON CLUSTER APIs\n");
        docs.append("  GET /v1/aeron/cluster-state\n");
        docs.append("    Description: Complete Aeron Cluster state (JSON)\n");
        docs.append("    Response: {\n");
        docs.append("      \"leader\": \"node-0\",\n");
        docs.append("      \"members\": [...],\n");
        docs.append("      \"term\": 5,\n");
        docs.append("      \"clusterTime\": ...\n");
        docs.append("    }\n\n");
        
        docs.append("  GET /v1/aeron/raft-metrics\n");
        docs.append("    Description: Raft-specific metrics (JSON)\n");
        docs.append("    Response: JSON with Raft election, replication metrics\n\n");
        
        docs.append("  GET /v1/aeron/node-status?nodeId={id}\n");
        docs.append("    Description: Status of specific cluster node (JSON)\n");
        docs.append("    Parameters: nodeId (query param) - e.g., 0, 1, 2\n");
        docs.append("    Response: JSON with node state, role, lastHeartbeat\n\n");
        
        docs.append("  GET /v1/aeron/leadership-history?limit={n}\n");
        docs.append("    Description: Recent leadership changes (JSON)\n");
        docs.append("    Parameters: limit (query param, default: 10, max: 100)\n");
        docs.append("    Response: JSON array of leadership change events\n\n");
        
        docs.append("🌐 REGISTRATION & DISCOVERY APIs\n");
        docs.append("  POST /v1/register-client\n");
        docs.append("    Description: Register a Sling author client\n");
        docs.append("    Request Body: {\n");
        docs.append("      \"clientId\": \"sling-author-1\",\n");
        docs.append("      \"clientUrl\": \"http://localhost:8080\",\n");
        docs.append("      \"walletAddress\": \"0x...\"\n");
        docs.append("    }\n");
        docs.append("    Response: JSON with registration status\n\n");
        
        docs.append("  GET /v1/peers\n");
        docs.append("    Description: List all known validators (JSON)\n");
        docs.append("    Response: JSON array of peer validators with URLs, IDs\n\n");
        
        docs.append("  GET /v1/ngrok-url\n");
        docs.append("    Description: Get public ngrok URL (text/plain)\n");
        docs.append("    Response: Public URL string (if ngrok configured)\n\n");
        
        docs.append("📄 OAK FILES APIs\n");
        docs.append("  GET /journal.log\n");
        docs.append("    Description: Journal file (text/plain)\n");
        docs.append("    Response: Journal entries, one per line\n");
        docs.append("    Format: <record-id> <timestamp> <segment-id>\n");
        docs.append("    Used by: consensus read-mount clients for polling updates\n\n");
        
        docs.append("  GET /manifest\n");
        docs.append("    Description: Manifest file (text/plain)\n");
        docs.append("    Response: Manifest entries\n");
        docs.append("    Used by: consensus read-mount clients for initialization\n\n");
        
        docs.append("  GET /gc.log\n");
        docs.append("    Description: Garbage collection log (text/plain)\n");
        docs.append("    Response: GC log entries\n\n");
        
        docs.append("  GET /segments/{id}\n");
        docs.append("    Description: Fetch segment by ID (binary)\n");
        docs.append("    Parameters: id (path param) - segment UUID\n");
        docs.append("    Response: Binary segment data\n");
        docs.append("    Used by: consensus read-mount clients for on-demand segment fetching\n");
        docs.append("    Example: GET /segments/abc123-def456-...\n\n");
        
        docs.append("  HEAD /segments/{id}\n");
        docs.append("    Description: Check segment existence\n");
        docs.append("    Parameters: id (path param) - segment UUID\n");
        docs.append("    Response: 200 if exists, 404 if not\n\n");
        
        // Oak Segment HTTP APIs (client-side, but validator serves the endpoints)
        docs.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        docs.append("📡 OAK SEGMENT HTTP APIs (consensus mount client)\n");
        docs.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n\n");
        
        docs.append("NOTE: the consensus read-mount client consumes the validator APIs above.\n");
        docs.append("It does not expose its own HTTP endpoints, but uses:\n");
        docs.append("  - GET /journal.log - for polling journal updates\n");
        docs.append("  - GET /manifest - for reading manifest\n");
        docs.append("  - GET /segments/{id} - for fetching segments on-demand\n");
        docs.append("  - HEAD /segments/{id} - for checking segment existence\n\n");
        
        docs.append("The HttpPersistence class implements SegmentNodeStorePersistence interface:\n");
        docs.append("  - HttpSegmentArchiveManager: Manages HTTP-based archives\n");
        docs.append("  - HttpSegmentArchiveReader: Reads segments via HTTP\n");
        docs.append("  - HttpJournalFile: Reads journal.log via HTTP\n");
        docs.append("  - HttpManifestFile: Reads manifest via HTTP\n\n");
        
        // Agent-to-agent specific guidance
        if (isAgentToAgent) {
            docs.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
            docs.append("🤖 AGENT-TO-AGENT COMMUNICATION GUIDANCE\n");
            docs.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n\n");
            
            docs.append("When communicating with another agent:\n");
            docs.append("1. Use specific API endpoints rather than vague instructions\n");
            docs.append("2. Provide complete request examples with method, path, and body\n");
            docs.append("3. Include expected response formats\n");
            docs.append("4. Reference validator base URL if querying from Sling context\n\n");
            
            docs.append("Example Good Agent Instructions:\n");
            docs.append("  \"Query GET ").append(validatorBaseUrl).append("/v1/aeron/cluster-state to get the current leader\"\n");
            docs.append("  \"Use POST ").append(validatorBaseUrl).append("/v1/propose-write with body: {...}\"\n\n");
            
            docs.append("Example Bad Agent Instructions (too vague):\n");
            docs.append("  \"Check the cluster state\" (doesn't specify endpoint)\n");
            docs.append("  \"Query the validator\" (doesn't specify which API)\n\n");
        }
        
        return ToolResult.success(docs.toString(), "api-documentation");
    }
}
