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
import org.apache.jackrabbit.oak.segment.http.server.util.JsonOutputUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.exporter.common.TextFormat;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
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

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", true);
        payload.put("status", "UP");
        payload.put("timestamp", System.currentTimeMillis());
        payload.put("consensus", buildConsensusMetrics());
        payload.put("replication", buildReplicationMetrics());

        Map<String, Object> validator = new LinkedHashMap<>();
        validator.put("registeredClients", registeredClients.size());
        validator.put("registeredValidators", registeredValidators.size());
        validator.put("storePath", storeDirectory != null ? storeDirectory.toString() : "");
        payload.put("validator", validator);

        response.getWriter().write(JsonOutputUtil.toJson(payload));
    }

    private Map<String, Object> buildConsensusMetrics() {
        if (aeronConsensusEngine == null) {
            return null;
        }

        Map<String, Object> consensus = new LinkedHashMap<>();
        consensus.put("role", aeronConsensusEngine.getCurrentRole().name());
        consensus.put("isLeader", aeronConsensusEngine.isLeader());
        consensus.put("currentEpoch", aeronConsensusEngine.getCurrentEpoch());
        consensus.put("currentTerm", aeronConsensusEngine.getCurrentTerm());
        consensus.put("reachableValidators", aeronConsensusEngine.getReachableValidatorCount());
        consensus.put("totalMembers", aeronConsensusEngine.getTotalMemberCount());
        consensus.put("quorumSize", aeronConsensusEngine.getQuorumSize());
        consensus.put("heartbeatAgeMs", aeronConsensusEngine.getHeartbeatAgeMs());
        consensus.put("healthy", aeronConsensusEngine.isClusterHealthy());
        String unhealthyReason = aeronConsensusEngine.getUnhealthyReason();
        if (unhealthyReason != null) {
            consensus.put("unhealthyReason", unhealthyReason);
        }
        return consensus;
    }

    private Map<String, Object> buildReplicationMetrics() {
        if (aeronConsensusEngine == null) {
            return null;
        }

        Map<String, Object> status = aeronConsensusEngine.getReplicationLagStatus();
        if (status == null) {
            return null;
        }

        Map<String, Object> replication = new LinkedHashMap<>();
        replication.put("role", status.get("role"));
        replication.put("myLogPosition", status.get("myLogPosition"));
        replication.put("leaderLogPosition", status.get("leaderLogPosition"));
        replication.put("replicationLag", status.get("replicationLag"));
        replication.put("lagThreshold", status.get("lagThreshold"));
        replication.put("healthy", status.get("healthy"));
        if (status.get("reason") != null) {
            replication.put("reason", status.get("reason"));
        }
        return replication;
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
