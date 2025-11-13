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

import org.apache.jackrabbit.oak.segment.consensus.leader.EpochLeaderEngine;
import org.apache.jackrabbit.oak.segment.consensus.aeron.AeronConsensusEngine;
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
    
    private final EpochLeaderEngine epochLeaderEngine;
    private final AeronConsensusEngine aeronConsensusEngine;
    private final Path storeDirectory;
    private final Map<String, ?> registeredClients;
    private final Map<String, ?> registeredValidators;
    
    public MetricsHandler(
            EpochLeaderEngine epochLeaderEngine,
            AeronConsensusEngine aeronConsensusEngine,
            Path storeDirectory,
            Map<String, ?> registeredClients,
            Map<String, ?> registeredValidators) {
        this.epochLeaderEngine = epochLeaderEngine;
        this.aeronConsensusEngine = aeronConsensusEngine;
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
        json.append("  \"consensus\": null,\n");
        json.append("  \"replication\": null,\n");
        json.append("  \"validator\": null\n");
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
        // Update leader status - check Aeron first, then Leader
        if (aeronConsensusEngine != null) {
            ConsensusMetrics.updateLeaderStatus(
                aeronConsensusEngine.isLeader(),
                aeronConsensusEngine.getCurrentEpoch()
            );
            ConsensusMetrics.validatorsReachable.set(aeronConsensusEngine.getReachableValidatorCount());
            ConsensusMetrics.timeSinceLastHeartbeat.set(
                (System.currentTimeMillis() - aeronConsensusEngine.getLastHeartbeatTime()) / 1000.0
            );
        } else if (epochLeaderEngine != null) {
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

