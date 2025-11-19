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
package org.apache.jackrabbit.oak.segment.http.server.handlers;

import org.apache.jackrabbit.oak.segment.consensus.fragmentation.FragmentationTracker;
import org.apache.jackrabbit.oak.segment.consensus.gc.GCProposalManager;
import org.apache.jackrabbit.oak.segment.http.server.ServerContext;
import org.apache.jackrabbit.oak.segment.http.server.util.FormatUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;

/**
 * Handler for fragmentation metrics and GC/compaction consensus APIs.
 * 
 * <p>Provides endpoints for:
 * - Querying fragmentation metrics per entity
 * - GC/compaction consensus status
 * - Fragmentation tax calculations
 * - Compaction proposals and status</p>
 */
public class FragmentationApiHandler {
    
    private static final Logger log = LoggerFactory.getLogger(FragmentationApiHandler.class);
    
    private final ServerContext context;
    
    public FragmentationApiHandler(ServerContext context) {
        this.context = context;
    }
    
    /**
     * Handle GET /v1/fragmentation/metrics - Get fragmentation metrics for all entities.
     */
    public void handleGetAllMetrics(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setContentType("application/json");
        
        try {
            FragmentationTracker tracker = getFragmentationTracker();
            if (tracker == null) {
                response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Fragmentation tracker not initialized");
                return;
            }
            
            Map<String, FragmentationTracker.EntityFragmentationMetrics> allMetrics = tracker.getAllMetrics();
            
            StringBuilder json = new StringBuilder();
            json.append("{\n");
            json.append("  \"totalEntities\": ").append(allMetrics.size()).append(",\n");
            json.append("  \"entities\": [\n");
            
            boolean first = true;
            for (FragmentationTracker.EntityFragmentationMetrics metrics : allMetrics.values()) {
                if (!first) json.append(",\n");
                first = false;
                appendMetricsJson(json, metrics, tracker);
            }
            
            json.append("\n  ]\n");
            json.append("}\n");
            
            response.setStatus(HttpServletResponse.SC_OK);
            response.getWriter().write(json.toString());
            
        } catch (Exception e) {
            log.error("Error getting fragmentation metrics", e);
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }
    
    /**
     * Handle GET /v1/fragmentation/metrics/{walletAddress} - Get fragmentation metrics for specific entity.
     */
    public void handleGetEntityMetrics(HttpServletRequest request, HttpServletResponse response, String walletAddress) throws IOException {
        response.setContentType("application/json");
        
        try {
            FragmentationTracker tracker = getFragmentationTracker();
            if (tracker == null) {
                response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Fragmentation tracker not initialized");
                return;
            }
            
            FragmentationTracker.EntityFragmentationMetrics metrics = tracker.getMetrics(walletAddress);
            if (metrics == null) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND, "No metrics found for wallet: " + walletAddress);
                return;
            }
            
            StringBuilder json = new StringBuilder();
            appendMetricsJson(json, metrics, tracker);
            
            response.setStatus(HttpServletResponse.SC_OK);
            response.getWriter().write(json.toString());
            
        } catch (Exception e) {
            log.error("Error getting entity fragmentation metrics", e);
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }
    
    /**
     * Handle GET /v1/fragmentation/top - Get top N most fragmented entities.
     */
    public void handleGetTopFragmented(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setContentType("application/json");
        
        try {
            FragmentationTracker tracker = getFragmentationTracker();
            if (tracker == null) {
                response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Fragmentation tracker not initialized");
                return;
            }
            
            int limit = 10;
            String limitParam = request.getParameter("limit");
            if (limitParam != null) {
                try {
                    limit = Integer.parseInt(limitParam);
                } catch (NumberFormatException e) {
                    // Use default
                }
            }
            
            List<FragmentationTracker.EntityFragmentationMetrics> topEntities = tracker.getTopFragmentedEntities(limit);
            
            StringBuilder json = new StringBuilder();
            json.append("{\n");
            json.append("  \"limit\": ").append(limit).append(",\n");
            json.append("  \"entities\": [\n");
            
            boolean first = true;
            for (FragmentationTracker.EntityFragmentationMetrics metrics : topEntities) {
                if (!first) json.append(",\n");
                first = false;
                appendMetricsJson(json, metrics, tracker);
            }
            
            json.append("\n  ]\n");
            json.append("}\n");
            
            response.setStatus(HttpServletResponse.SC_OK);
            response.getWriter().write(json.toString());
            
        } catch (Exception e) {
            log.error("Error getting top fragmented entities", e);
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }
    
    /**
     * Handle GET /v1/gc/status - Get GC/compaction consensus status.
     */
    public void handleGetGcStatus(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setContentType("application/json");
        
        try {
            GCProposalManager gcManager = getGCProposalManager();
            if (gcManager == null) {
                response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "GC Proposal Manager not initialized");
                return;
            }
            
            // Get pending proposals
            List<org.apache.jackrabbit.oak.segment.consensus.gc.GCProposal> pending = gcManager.getPendingProposals();
            
            // Get last GC execution
            List<org.apache.jackrabbit.oak.segment.consensus.gc.GCExecutionResult> history = gcManager.getGCHistory(1);
            org.apache.jackrabbit.oak.segment.consensus.gc.GCExecutionResult lastGC = history.isEmpty() ? null : history.get(0);
            
            StringBuilder json = new StringBuilder();
            json.append("{\n");
            json.append("  \"gcEnabled\": true,\n");
            json.append("  \"pendingProposals\": ").append(pending.size()).append(",\n");
            json.append("  \"lastGcRun\": ").append(lastGC != null ? lastGC.timestamp : "null").append(",\n");
            json.append("  \"lastGcReclaimedMB\": ").append(lastGC != null ? lastGC.actualReclaimedSizeMB : "null").append(",\n");
            json.append("  \"lastGcCostUSDC\": ").append(lastGC != null ? "\"" + lastGC.actualCostUSDC.toString() + "\"" : "null").append(",\n");
            json.append("  \"gcConsensusRequired\": true\n");
            json.append("}\n");
            
            response.setStatus(HttpServletResponse.SC_OK);
            response.getWriter().write(json.toString());
            
        } catch (Exception e) {
            log.error("Error getting GC status", e);
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }
    
    /**
     * Handle GET /v1/compaction/proposals - Get pending compaction proposals.
     */
    public void handleGetCompactionProposals(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setContentType("application/json");
        
        try {
            GCProposalManager gcManager = getGCProposalManager();
            if (gcManager == null) {
                response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "GC Proposal Manager not initialized");
                return;
            }
            
            List<org.apache.jackrabbit.oak.segment.consensus.gc.GCProposal> proposals = gcManager.getPendingProposals();
            
            StringBuilder json = new StringBuilder();
            json.append("{\n");
            json.append("  \"proposals\": [\n");
            
            boolean first = true;
            for (org.apache.jackrabbit.oak.segment.consensus.gc.GCProposal proposal : proposals) {
                if (!first) json.append(",\n");
                first = false;
                appendProposalJson(json, proposal);
            }
            
            json.append("\n  ]\n");
            json.append("}\n");
            
            response.setStatus(HttpServletResponse.SC_OK);
            response.getWriter().write(json.toString());
            
        } catch (Exception e) {
            log.error("Error getting compaction proposals", e);
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }
    
    /**
     * Handle POST /v1/propose-gc - Create a new GC proposal.
     * 
     * <p>Request Body (JSON):</p>
     * <pre>
     * {
     *   "walletAddress": "0x742d35Cc6634C0532925a3b844Bc9e7595f0bEb",  // Required: Ethereum wallet address of proposer
     *   "targetRevision": "abc123..."                                    // Optional: Target revision ID (null = use HEAD)
     * }
     * </pre>
     * 
     * <p>Alternative: Query parameters (for form-encoded requests):</p>
     * <ul>
     *   <li><code>walletAddress</code> (required) - Ethereum wallet address</li>
     *   <li><code>targetRevision</code> (optional) - Target revision ID</li>
     * </ul>
     * 
     * <p>Response: JSON with GC proposal details including proposalId, estimated costs, fragmentation overhead</p>
     */
    public void handleProposeGC(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setContentType("application/json");
        
        try {
            GCProposalManager gcManager = getGCProposalManager();
            if (gcManager == null) {
                response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "GC Proposal Manager not initialized");
                return;
            }
            
            String proposerWallet = null;
            String targetRevision = null;
            
            // Try to read from JSON body first (preferred)
            String contentType = request.getContentType();
            if (contentType != null && contentType.contains("application/json")) {
                try {
                    java.io.BufferedReader reader = request.getReader();
                    StringBuilder body = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        body.append(line);
                    }
                    
                    if (body.length() > 0) {
                        String jsonBody = body.toString();
                        // Simple JSON parsing (no Gson dependency)
                        // Extract walletAddress
                        int walletStart = jsonBody.indexOf("\"walletAddress\"");
                        if (walletStart >= 0) {
                            int colonIndex = jsonBody.indexOf(":", walletStart);
                            int quoteStart = jsonBody.indexOf("\"", colonIndex);
                            if (quoteStart >= 0) {
                                int quoteEnd = jsonBody.indexOf("\"", quoteStart + 1);
                                if (quoteEnd > quoteStart) {
                                    proposerWallet = jsonBody.substring(quoteStart + 1, quoteEnd);
                                }
                            }
                        }
                        
                        // Extract targetRevision (optional)
                        int revisionStart = jsonBody.indexOf("\"targetRevision\"");
                        if (revisionStart >= 0) {
                            int colonIndex = jsonBody.indexOf(":", revisionStart);
                            // Check if null
                            int nullIndex = jsonBody.indexOf("null", colonIndex);
                            if (nullIndex >= 0 && nullIndex < colonIndex + 10) {
                                targetRevision = null; // Explicitly null
                            } else {
                                int quoteStart = jsonBody.indexOf("\"", colonIndex);
                                if (quoteStart >= 0) {
                                    int quoteEnd = jsonBody.indexOf("\"", quoteStart + 1);
                                    if (quoteEnd > quoteStart) {
                                        targetRevision = jsonBody.substring(quoteStart + 1, quoteEnd);
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    log.debug("Failed to parse JSON body, falling back to query parameters", e);
                }
            }
            
            // Fallback to query parameters (for form-encoded or query string)
            if (proposerWallet == null || proposerWallet.isEmpty()) {
                proposerWallet = request.getParameter("walletAddress");
                if (proposerWallet == null || proposerWallet.isEmpty()) {
                    proposerWallet = request.getParameter("wallet"); // Alternative parameter name
                }
            }
            
            if (targetRevision == null) {
                String paramRevision = request.getParameter("targetRevision");
                if (paramRevision != null && !paramRevision.isEmpty() && !"null".equals(paramRevision)) {
                    targetRevision = paramRevision;
                }
            }
            
            // Validate required parameter
            if (proposerWallet == null || proposerWallet.isEmpty()) {
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, 
                    "walletAddress parameter required. Provide as JSON body: {\"walletAddress\":\"0x...\"} or query parameter: ?walletAddress=0x...");
                return;
            }
            
            // Create proposal
            org.apache.jackrabbit.oak.segment.consensus.gc.GCProposal proposal = gcManager.proposeGC(proposerWallet, targetRevision);
            
            // TODO: Send through Aeron for replication
            // For now, proposal is created locally
            
            // Return proposal
            StringBuilder json = new StringBuilder();
            json.append("{\n");
            appendProposalJson(json, proposal);
            json.append("\n}\n");
            
            response.setStatus(HttpServletResponse.SC_OK);
            response.getWriter().write(json.toString());
            
        } catch (Exception e) {
            log.error("Error proposing GC", e);
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }
    
    /**
     * Handle POST /v1/gc/execute - Manually execute an approved GC proposal.
     * 
     * <p>Request Body (JSON):</p>
     * <pre>
     * {
     *   "proposalId": "uuid-here"  // Required: GC proposal ID to execute
     * }
     * </pre>
     * 
     * <p>Alternative: Query parameter</p>
     * <ul>
     *   <li><code>proposalId</code> (required) - GC proposal ID</li>
     * </ul>
     * 
     * <p>Note: GC proposals are automatically executed when they reach APPROVED state.
     * This endpoint allows manual execution if needed.</p>
     */
    public void handleExecuteGC(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setContentType("application/json");
        
        try {
            GCProposalManager gcManager = getGCProposalManager();
            if (gcManager == null) {
                response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "GC Proposal Manager not initialized");
                return;
            }
            
            String proposalId = null;
            
            // Try to read from JSON body first
            String contentType = request.getContentType();
            if (contentType != null && contentType.contains("application/json")) {
                try {
                    java.io.BufferedReader reader = request.getReader();
                    StringBuilder body = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        body.append(line);
                    }
                    
                    if (body.length() > 0) {
                        String jsonBody = body.toString();
                        // Simple JSON parsing
                        int proposalStart = jsonBody.indexOf("\"proposalId\"");
                        if (proposalStart >= 0) {
                            int colonIndex = jsonBody.indexOf(":", proposalStart);
                            int quoteStart = jsonBody.indexOf("\"", colonIndex);
                            if (quoteStart >= 0) {
                                int quoteEnd = jsonBody.indexOf("\"", quoteStart + 1);
                                if (quoteEnd > quoteStart) {
                                    proposalId = jsonBody.substring(quoteStart + 1, quoteEnd);
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    log.debug("Failed to parse JSON body, falling back to query parameters", e);
                }
            }
            
            // Fallback to query parameter
            if (proposalId == null || proposalId.isEmpty()) {
                proposalId = request.getParameter("proposalId");
            }
            
            if (proposalId == null || proposalId.isEmpty()) {
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, 
                    "proposalId parameter required. Provide as JSON body: {\"proposalId\":\"uuid\"} or query parameter: ?proposalId=uuid");
                return;
            }
            
            // Get executor ID (validator/node ID)
            int executorId = 0;
            if (context.aeronConsensusEngine != null && context.aeronConsensusEngine.getCluster() != null) {
                executorId = context.aeronConsensusEngine.getCluster().memberId();
            }
            
            // Execute GC
            org.apache.jackrabbit.oak.segment.consensus.gc.GCExecutionResult result = gcManager.executeGC(proposalId, executorId);
            
            // Return execution result
            StringBuilder json = new StringBuilder();
            json.append("{\n");
            json.append("  \"proposalId\": \"").append(FormatUtils.escapeJson(result.proposalId)).append("\",\n");
            json.append("  \"executorId\": ").append(result.executorId).append(",\n");
            json.append("  \"success\": ").append(result.success).append(",\n");
            json.append("  \"timestamp\": ").append(result.timestamp).append(",\n");
            if (result.success) {
                json.append("  \"filesRemoved\": ").append(result.filesRemoved != null ? result.filesRemoved.size() : 0).append(",\n");
                json.append("  \"actualReclaimedSizeMB\": ").append(result.actualReclaimedSizeMB).append(",\n");
                json.append("  \"actualCostUSDC\": \"").append(result.actualCostUSDC != null ? result.actualCostUSDC.toString() : "0").append("\"\n");
            } else {
                json.append("  \"errorMessage\": \"").append(FormatUtils.escapeJson(result.errorMessage != null ? result.errorMessage : "Unknown error")).append("\"\n");
            }
            json.append("}\n");
            
            response.setStatus(HttpServletResponse.SC_OK);
            response.getWriter().write(json.toString());
            
        } catch (IllegalStateException e) {
            // Proposal not approved or already executed
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
        } catch (IllegalArgumentException e) {
            // Proposal not found
            response.sendError(HttpServletResponse.SC_NOT_FOUND, e.getMessage());
        } catch (Exception e) {
            log.error("Error executing GC", e);
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }
    
    /**
     * Append proposal JSON.
     */
    private void appendProposalJson(StringBuilder json, org.apache.jackrabbit.oak.segment.consensus.gc.GCProposal proposal) {
        json.append("    {\n");
        json.append("      \"proposalId\": \"").append(FormatUtils.escapeJson(proposal.proposalId)).append("\",\n");
        json.append("      \"proposerWallet\": \"").append(FormatUtils.escapeJson(proposal.proposerWallet)).append("\",\n");
        json.append("      \"targetRevision\": ").append(proposal.targetRevision != null ? "\"" + FormatUtils.escapeJson(proposal.targetRevision) + "\"" : "null").append(",\n");
        json.append("      \"state\": \"").append(proposal.state.toString()).append("\",\n");
        json.append("      \"estimatedReclaimableSizeMB\": ").append(proposal.estimatedReclaimableSizeMB).append(",\n");
        json.append("      \"estimatedCostUSDC\": \"").append(proposal.estimatedCostUSDC.toString()).append("\",\n");
        json.append("      \"fragmentationOverheadMB\": ").append(proposal.fragmentationOverheadMB).append(",\n");
        json.append("      \"fragmentationCostUSDC\": \"").append(proposal.fragmentationCostUSDC.toString()).append("\",\n");
        json.append("      \"createdAt\": ").append(proposal.createdAt).append(",\n");
        json.append("      \"expiresAt\": ").append(proposal.expiresAt).append(",\n");
        json.append("      \"votes\": {\n");
        
        boolean firstVote = true;
        for (java.util.Map.Entry<Integer, org.apache.jackrabbit.oak.segment.consensus.gc.GCVote> entry : proposal.votes.entrySet()) {
            if (!firstVote) json.append(",\n");
            firstVote = false;
            org.apache.jackrabbit.oak.segment.consensus.gc.GCVote vote = entry.getValue();
            json.append("        \"").append(entry.getKey()).append("\": {\n");
            json.append("          \"vote\": \"").append(vote.approve ? "APPROVE" : "REJECT").append("\",\n");
            json.append("          \"reason\": \"").append(FormatUtils.escapeJson(vote.reason != null ? vote.reason : "")).append("\",\n");
            json.append("          \"timestamp\": ").append(vote.timestamp).append("\n");
            json.append("        }");
        }
        
        json.append("\n      },\n");
        json.append("      \"approveVotes\": ").append(proposal.getApproveVoteCount()).append(",\n");
        json.append("      \"rejectVotes\": ").append(proposal.getRejectVoteCount()).append(",\n");
        json.append("      \"totalVotes\": ").append(proposal.getTotalVoteCount()).append("\n");
        json.append("    }");
    }
    
    /**
     * Get GC Proposal Manager from context.
     */
    private GCProposalManager getGCProposalManager() {
        if (context.gcProposalManager == null) {
            log.debug("GCProposalManager not initialized in ServerContext");
            return null;
        }
        return context.gcProposalManager;
    }
    
    /**
     * Append metrics JSON for a single entity.
     */
    private void appendMetricsJson(StringBuilder json, FragmentationTracker.EntityFragmentationMetrics metrics, FragmentationTracker tracker) {
        BigInteger tax = tracker.calculateFragmentationTax(metrics.walletAddress);
        
        json.append("    {\n");
        json.append("      \"walletAddress\": \"").append(FormatUtils.escapeJson(metrics.walletAddress)).append("\",\n");
        json.append("      \"tarFilesCreated\": ").append(metrics.tarFilesCreated).append(",\n");
        json.append("      \"totalBytesWritten\": ").append(metrics.totalBytesWritten).append(",\n");
        json.append("      \"totalBytesWrittenFormatted\": \"").append(FormatUtils.formatBytes(metrics.totalBytesWritten)).append("\",\n");
        json.append("      \"averageTarFileSize\": ").append(metrics.averageTarFileSize).append(",\n");
        json.append("      \"averageTarFileSizeFormatted\": \"").append(FormatUtils.formatBytes(metrics.averageTarFileSize)).append("\",\n");
        json.append("      \"packingEfficiency\": ").append(String.format("%.2f", metrics.packingEfficiency)).append(",\n");
        json.append("      \"smallTarFileCount\": ").append(metrics.smallTarFileCount).append(",\n");
        json.append("      \"fragmentationScore\": ").append(metrics.fragmentationScore).append(",\n");
        json.append("      \"fragmentationTax\": \"").append(tax.toString()).append("\",\n");
        json.append("      \"fragmentationTaxFormatted\": \"").append(formatWeiToEth(tax)).append(" ETH\",\n");
        json.append("      \"lastWriteTimestamp\": ").append(metrics.lastWriteTimestamp).append(",\n");
        json.append("      \"tarFiles\": [\n");
        
        List<String> tarFiles = tracker.getTarFilesForEntity(metrics.walletAddress);
        boolean first = true;
        for (String tarFile : tarFiles) {
            if (!first) json.append(",\n");
            first = false;
            json.append("        \"").append(FormatUtils.escapeJson(tarFile)).append("\"");
        }
        
        json.append("\n      ]\n");
        json.append("    }");
    }
    
    /**
     * Format Wei to ETH (simplified - assumes 18 decimals).
     */
    private String formatWeiToEth(BigInteger wei) {
        if (wei.equals(BigInteger.ZERO)) {
            return "0";
        }
        // Simple formatting: divide by 10^18
        BigInteger eth = wei.divide(BigInteger.valueOf(10).pow(18));
        BigInteger remainder = wei.remainder(BigInteger.valueOf(10).pow(18));
        if (remainder.equals(BigInteger.ZERO)) {
            return eth.toString();
        }
        return eth.toString() + "." + remainder.toString().substring(0, Math.min(6, remainder.toString().length()));
    }
    
    /**
     * Get fragmentation tracker from context.
     */
    private FragmentationTracker getFragmentationTracker() {
        if (context.fragmentationTracker == null) {
            log.debug("FragmentationTracker not initialized in ServerContext");
            return null;
        }
        return context.fragmentationTracker;
    }
}

