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

import org.apache.jackrabbit.oak.segment.consensus.aeron.AeronConsensusEngine;
import org.apache.jackrabbit.oak.segment.consensus.aeron.CrashHandler;
import org.apache.jackrabbit.oak.segment.consensus.metrics.ConsensusMetrics;
import org.apache.jackrabbit.oak.segment.http.server.ServerContext;
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
    
    private final AeronConsensusEngine aeronConsensusEngine;
    private final Path storeDirectory;
    private final Map<String, ?> registeredClients;
    private final Map<String, ?> registeredValidators;
    private final ServerContext context;
    
    public MetricsHandler(
            AeronConsensusEngine aeronConsensusEngine,
            Path storeDirectory,
            Map<String, ?> registeredClients,
            Map<String, ?> registeredValidators,
            ServerContext context) {
        this.aeronConsensusEngine = aeronConsensusEngine;
        this.storeDirectory = storeDirectory;
        this.registeredClients = registeredClients;
        this.registeredValidators = registeredValidators;
        this.context = context;
    }
    
    /**
     * Handle GET /api/metrics - Return consensus and replication metrics (JSON format).
     */
    public void handleMetrics(HttpServletResponse response) throws IOException {
        response.setContentType("application/json");
        response.setStatus(HttpServletResponse.SC_OK);
        
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"consensus\": ");
        if (aeronConsensusEngine != null) {
            String role = aeronConsensusEngine.getCurrentRole().name();
            int reachable = aeronConsensusEngine.getReachableValidatorCount();
            int total = aeronConsensusEngine.getTotalMemberCount();
            int quorum = aeronConsensusEngine.getQuorumSize();
            long heartbeatAgeMs = aeronConsensusEngine.getHeartbeatAgeMs();
            String unhealthyReason = aeronConsensusEngine.getUnhealthyReason();
            json.append("{\n");
            json.append("    \"role\": \"").append(role).append("\",\n");
            json.append("    \"isLeader\": ").append(aeronConsensusEngine.isLeader()).append(",\n");
            json.append("    \"currentEpoch\": ").append(aeronConsensusEngine.getCurrentEpoch()).append(",\n");
            json.append("    \"currentTerm\": ").append(aeronConsensusEngine.getCurrentTerm()).append(",\n");
            json.append("    \"reachableValidators\": ").append(reachable).append(",\n");
            json.append("    \"totalMembers\": ").append(total).append(",\n");
            json.append("    \"quorumSize\": ").append(quorum).append(",\n");
            json.append("    \"heartbeatAgeMs\": ").append(heartbeatAgeMs).append(",\n");
            json.append("    \"healthy\": ").append(aeronConsensusEngine.isClusterHealthy());
            if (unhealthyReason != null) {
                json.append(",\n    \"unhealthyReason\": \"").append(unhealthyReason).append("\"\n");
            } else {
                json.append("\n");
            }
            json.append("  }");
        } else {
            json.append("null");
        }
        json.append(",\n  \"replication\": ");
        if (aeronConsensusEngine != null) {
            java.util.Map<String, Object> status = aeronConsensusEngine.getReplicationLagStatus();
            if (status == null) {
                json.append("null");
            } else {
                json.append("{\n");
                json.append("    \"role\": \"").append(status.get("role")).append("\",\n");
                json.append("    \"myLogPosition\": ").append(status.get("myLogPosition")).append(",\n");
                json.append("    \"leaderLogPosition\": ").append(status.get("leaderLogPosition")).append(",\n");
                json.append("    \"replicationLag\": ").append(status.get("replicationLag")).append(",\n");
                json.append("    \"lagThreshold\": ").append(status.get("lagThreshold")).append(",\n");
                json.append("    \"healthy\": ").append(status.get("healthy"));
                Object reason = status.get("reason");
                if (reason != null) {
                    json.append(",\n    \"reason\": \"").append(reason).append("\"\n");
                } else {
                    json.append("\n");
                }
                json.append("  }");
            }
        } else {
            json.append("null");
        }
        json.append(",\n  \"validator\": {\n");
        json.append("    \"registeredClients\": ").append(registeredClients.size()).append(",\n");
        json.append("    \"registeredValidators\": ").append(registeredValidators.size()).append(",\n");
        json.append("    \"storePath\": \"").append(storeDirectory != null ? storeDirectory.toString() : "").append("\"\n");
        json.append("  }\n");
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
        
        // Update MediaDriver crash metrics
        if (context != null && context.aeronClusterLauncher != null) {
            try {
                CrashHandler crashHandler = context.aeronClusterLauncher.getCrashHandler();
                if (crashHandler != null) {
                    int crashCount = crashHandler.getCrashCount();
                    boolean hasCrashed = crashHandler.hasCrashed();
                    boolean shouldBootstrap = crashHandler.shouldForceBootstrap();
                    
                    ConsensusMetrics.mediaDriverCrashCount.set(crashCount);
                    ConsensusMetrics.mediaDriverHasCrashed.set(hasCrashed ? 1 : 0);
                    ConsensusMetrics.mediaDriverForceBootstrap.set(shouldBootstrap ? 1 : 0);
                } else {
                    // CrashHandler not initialized - assume healthy
                    ConsensusMetrics.mediaDriverCrashCount.set(0);
                    ConsensusMetrics.mediaDriverHasCrashed.set(0);
                    ConsensusMetrics.mediaDriverForceBootstrap.set(0);
                }
            } catch (Exception e) {
                log.debug("Failed to update MediaDriver crash metrics: {}", e.getMessage());
            }
        } else {
            // No Aeron Cluster - reset metrics
            ConsensusMetrics.mediaDriverCrashCount.set(0);
            ConsensusMetrics.mediaDriverHasCrashed.set(0);
            ConsensusMetrics.mediaDriverForceBootstrap.set(0);
        }
        
        // Update Aeron metrics (system counters and stream counters)
        if (context != null && context.aeronPrometheusMetrics != null) {
            try {
                context.aeronPrometheusMetrics.updateGaugeValues();
            } catch (Exception e) {
                log.debug("Failed to update Aeron Prometheus metrics: {}", e.getMessage());
            }
        }
    }
}
