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

import org.apache.jackrabbit.oak.segment.consensus.ConsensusEngine;
import org.apache.jackrabbit.oak.segment.consensus.leader.EpochLeaderEngine;
import org.apache.jackrabbit.oak.segment.consensus.dag.DagConsensusEngine;
import org.apache.jackrabbit.oak.segment.consensus.metrics.ConsensusMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.exporter.common.TextFormat;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Handler for metrics endpoints.
 * 
 * <p>Extracted from SegmentHttpServer to separate metrics concerns.</p>
 */
public class MetricsHandler {
    private static final Logger log = LoggerFactory.getLogger(MetricsHandler.class);
    
    private final ConsensusEngine consensusEngine;
    private final DagConsensusEngine dagConsensusEngine;
    private final EpochLeaderEngine epochLeaderEngine;
    private final Path storeDirectory;
    private final Map<String, ?> registeredClients;
    private final Map<String, ?> registeredValidators;
    
    public MetricsHandler(
            ConsensusEngine consensusEngine,
            DagConsensusEngine dagConsensusEngine,
            EpochLeaderEngine epochLeaderEngine,
            Path storeDirectory,
            Map<String, ?> registeredClients,
            Map<String, ?> registeredValidators) {
        this.consensusEngine = consensusEngine;
        this.dagConsensusEngine = dagConsensusEngine;
        this.epochLeaderEngine = epochLeaderEngine;
        this.storeDirectory = storeDirectory;
        this.registeredClients = registeredClients;
        this.registeredValidators = registeredValidators;
    }
    
    /**
     * Handle GET /api/metrics - Return consensus and replication metrics (JSON format).
     */
    public void handleMetrics(HttpServletResponse response) throws IOException {
        response.setContentType("application/json");
        response.setStatus(HttpServletResponse.SC_OK);
        
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        
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
     * Handle GET /metrics - Prometheus metrics endpoint.
     * 
     * Returns metrics in Prometheus text format for scraping by Prometheus server.
     * Includes:
     * - JVM metrics (memory, GC, threads) from DefaultExports
     * - Custom Oak consensus metrics from ConsensusMetrics
     */
    public void handlePrometheusMetrics(HttpServletResponse response) throws IOException {
        response.setContentType(TextFormat.CONTENT_TYPE_004);
        response.setStatus(HttpServletResponse.SC_OK);
        
        // Update dynamic metrics before exporting
        updateDynamicMetrics();
        
        // Export all registered metrics in Prometheus format
        try (Writer writer = response.getWriter()) {
            TextFormat.write004(writer, CollectorRegistry.defaultRegistry.metricFamilySamples());
        }
    }
    
    /**
     * Update dynamic Prometheus metrics from consensus engine state.
     * Called before each metrics scrape to reflect current state.
     */
    private void updateDynamicMetrics() {
        // Update DAG chain height
        if (dagConsensusEngine != null) {
            ConsensusMetrics.dagChainHeight.set(dagConsensusEngine.getChainHeight());
            ConsensusMetrics.dagPendingTransactions.set(dagConsensusEngine.getPendingTransactionCount());
        }
        
        // Update leader status
        if (epochLeaderEngine != null) {
            ConsensusMetrics.updateLeaderStatus(
                epochLeaderEngine.isLeader(),
                epochLeaderEngine.getCurrentEpoch()
            );
            ConsensusMetrics.validatorsReachable.set(epochLeaderEngine.getReachableValidatorCount());
            ConsensusMetrics.timeSinceLastHeartbeat.set(
                (System.currentTimeMillis() - epochLeaderEngine.getLastHeartbeatTime()) / 1000.0
            );
        }
        
        // Update storage metrics
        try {
            // Count TAR files directly in storeDirectory (Oak's segment files are here)
            if (Files.exists(storeDirectory)) {
                long segmentCount = Files.list(storeDirectory)
                    .filter(p -> p.toString().endsWith(".tar"))
                    .count();
                ConsensusMetrics.segmentsStoredTotal.set(segmentCount);
                
                long diskUsage = Files.walk(storeDirectory)
                    .filter(Files::isRegularFile)
                    .mapToLong(p -> {
                        try {
                            return Files.size(p);
                        } catch (IOException e) {
                            return 0;
                        }
                    })
                    .sum();
                ConsensusMetrics.segmentsDiskUsageBytes.set(diskUsage);
            }
        } catch (IOException e) {
            log.debug("Failed to update storage metrics: {}", e.getMessage());
        }
        
        // Update active connections (approximation via registered clients)
        ConsensusMetrics.activeConnections.set(registeredClients.size() + registeredValidators.size());
    }
}

