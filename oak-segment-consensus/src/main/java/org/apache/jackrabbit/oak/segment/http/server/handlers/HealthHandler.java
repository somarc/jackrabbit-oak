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

import org.apache.jackrabbit.oak.segment.consensus.config.IpfsGatewayUrls;
import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.apache.jackrabbit.oak.segment.consensus.aeron.AeronConsensusEngine;
import org.apache.jackrabbit.oak.segment.consensus.aeron.AeronClusterLauncher;
import org.apache.jackrabbit.oak.segment.consensus.aeron.CrashHandler;
import org.apache.jackrabbit.oak.segment.http.server.ServerContext;
import org.apache.jackrabbit.oak.segment.http.server.util.JsonOutputUtil;
import org.apache.jackrabbit.oak.spi.state.NodeStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Handler for health check endpoints.
 * 
 * <p>Extracted from SegmentHttpServer to separate health check concerns.</p>
 */
public class HealthHandler {
    private static final Logger log = LoggerFactory.getLogger(HealthHandler.class);
    private static final long OPS_HEALTH_SNAPSHOT_TTL_MS = 1000L;
    
    private final FileStore fileStore;
    private final NodeStore nodeStore;
    private final Path storeDirectory;
    private final ServerContext context;
    private final Map<String, ?> registeredClients;
    private final Map<String, ?> registeredValidators;
    private final Object opsHealthSnapshotLock = new Object();
    private volatile Map<String, Object> cachedOpsHealthSnapshotData;
    private volatile long cachedOpsHealthSnapshotSourceTimestampMs;
    
    public HealthHandler(
            FileStore fileStore,
            NodeStore nodeStore,
            Path storeDirectory,
            AeronConsensusEngine aeronConsensusEngine,
            Map<String, ?> registeredClients,
            Map<String, ?> registeredValidators,
            ServerContext context) {
        this.fileStore = fileStore;
        this.nodeStore = nodeStore;
        this.storeDirectory = storeDirectory;
        this.context = context;
        this.registeredClients = registeredClients;
        this.registeredValidators = registeredValidators;
    }
    
    /**
     * Handle simple health check endpoint.
     * 
     * <p>🔄 FINALITY-AWARE: Includes committedHead vs latestHead for clients to know what is safe.</p>
     * 
     * <p>ADR 028: Returns 503 if cluster is unhealthy (no leader, session timeout, etc.)</p>
     */
    public void handleHealth(HttpServletResponse response) throws IOException {
        response.setContentType("application/json");
        
        // ADR 028: Check cluster health for status code
        boolean isClusterHealthy = true;
        String unhealthyReason = null;
        
        if (context != null && context.aeronConsensusEngine != null) {
            isClusterHealthy = context.aeronConsensusEngine.isClusterHealthy();
            if (!isClusterHealthy) {
                unhealthyReason = context.aeronConsensusEngine.getUnhealthyReason();
            }
        }
        
        // Return 503 if cluster unhealthy, 200 otherwise
        response.setStatus(isClusterHealthy ? HttpServletResponse.SC_OK : HttpServletResponse.SC_SERVICE_UNAVAILABLE);

        Map<String, Object> payload = new HashMap<>();
        payload.put("success", isClusterHealthy);
        payload.put("status", isClusterHealthy ? "UP" : "UNHEALTHY");
        payload.put("timestamp", System.currentTimeMillis());
        if (!isClusterHealthy && unhealthyReason != null) {
            payload.put("unhealthyReason", unhealthyReason);
        }
        payload.put("store", String.valueOf(storeDirectory));

        if (context != null && context.blobStoreType != null) {
            payload.put("blobStoreType", context.blobStoreType);
            payload.put("blobStoreActive", context.blobStore != null);
        }

        if (context != null && context.aeronConsensusEngine != null) {
            payload.put("clusterHealthy", isClusterHealthy);
            payload.put("reachableCount", context.aeronConsensusEngine.getReachableValidatorCount());
            payload.put("totalMembers", context.aeronConsensusEngine.getTotalMemberCount());
            payload.put("quorumSize", context.aeronConsensusEngine.getQuorumSize());
            payload.put("currentRole", context.aeronConsensusEngine.getCurrentRole().name());

            String committedHead = context.aeronConsensusEngine.getCommittedHead();
            String latestHead = context.aeronConsensusEngine.getLatestHead();
            int latestEpochSeen = context.aeronConsensusEngine.getLatestEpochSeen();
            int committedEpoch = context.aeronConsensusEngine.getLastCommittedEpoch();

            if (committedHead != null && !committedHead.isEmpty()) {
                payload.put("committedHead", committedHead);
            }
            if (latestHead != null && !latestHead.isEmpty()) {
                payload.put("latestHead", latestHead);
            }
            if (latestEpochSeen >= 0) {
                payload.put("latestEpochSeen", latestEpochSeen);
            }
            if (committedEpoch >= 0) {
                payload.put("committedEpoch", committedEpoch);
            }
        }

        payload.put("sharding", buildShardingPayload());

        response.getWriter().write(JsonOutputUtil.toJson(payload));
    }
    
    /**
     * Handle comprehensive health check - validates all system components.
     */
    public void handleDeepHealth(HttpServletResponse response) throws IOException {
        response.setContentType("application/json");
        boolean allHealthy = true;
        Map<String, Object> payload = new HashMap<>();

        Map<String, Object> fileStoreHealth = new HashMap<>();
        try {
            if (fileStore != null) {
                String headId = fileStore.getHead().getRecordId().toString10();
                fileStoreHealth.put("status", "UP");
                fileStoreHealth.put("head", headId.substring(0, Math.min(16, headId.length())) + "...");
                AeronConsensusEngine aeronEngine = (context != null) ? context.aeronConsensusEngine : null;
                if (aeronEngine != null) {
                    if (aeronEngine.getCommittedHead() != null && !aeronEngine.getCommittedHead().isEmpty()) {
                        fileStoreHealth.put("committedHead", aeronEngine.getCommittedHead());
                    }
                    if (aeronEngine.getLatestHead() != null && !aeronEngine.getLatestHead().isEmpty()) {
                        fileStoreHealth.put("latestHead", aeronEngine.getLatestHead());
                    }
                    if (aeronEngine.getLatestEpochSeen() >= 0) {
                        fileStoreHealth.put("latestEpochSeen", aeronEngine.getLatestEpochSeen());
                    }
                    if (aeronEngine.getLastCommittedEpoch() >= 0) {
                        fileStoreHealth.put("committedEpoch", aeronEngine.getLastCommittedEpoch());
                    }
                }
            } else {
                fileStoreHealth.put("status", "DOWN");
                fileStoreHealth.put("error", "FileStore not initialized");
                allHealthy = false;
            }
        } catch (Exception e) {
            fileStoreHealth.put("status", "DOWN");
            fileStoreHealth.put("error", e.getMessage());
            allHealthy = false;
        }
        payload.put("fileStore", fileStoreHealth);

        Map<String, Object> cluster = new HashMap<>();
        try {
            AeronConsensusEngine aeronEngine = (context != null) ? context.aeronConsensusEngine : null;
            if (aeronEngine != null) {
                boolean clusterHealthy = aeronEngine.isClusterHealthy();
                cluster.put("status", clusterHealthy ? "UP" : "UNHEALTHY");
                cluster.put("reachableCount", aeronEngine.getReachableValidatorCount());
                cluster.put("totalMembers", aeronEngine.getTotalMemberCount());
                cluster.put("quorumSize", aeronEngine.getQuorumSize());
                cluster.put("currentRole", aeronEngine.getCurrentRole().name());
                cluster.put("heartbeatAgeMs", aeronEngine.getHeartbeatAgeMs());
                if (!clusterHealthy && aeronEngine.getUnhealthyReason() != null) {
                    cluster.put("unhealthyReason", aeronEngine.getUnhealthyReason());
                }
                if (!clusterHealthy) {
                    allHealthy = false;
                }
            } else {
                cluster.put("status", "UNKNOWN");
                cluster.put("error", "Aeron consensus engine not initialized");
                allHealthy = false;
            }
        } catch (Exception e) {
            cluster.put("status", "DOWN");
            cluster.put("error", e.getMessage());
            allHealthy = false;
        }
        payload.put("cluster", cluster);

        Map<String, Object> nodeStoreHealth = new HashMap<>();
        try {
            if (nodeStore != null) {
                nodeStoreHealth.put("status", "UP");
                nodeStoreHealth.put("rootExists", nodeStore.getRoot() != null);
            } else {
                nodeStoreHealth.put("status", "DOWN");
                nodeStoreHealth.put("error", "NodeStore not initialized");
                allHealthy = false;
            }
        } catch (Exception e) {
            nodeStoreHealth.put("status", "DOWN");
            nodeStoreHealth.put("error", e.getMessage());
            allHealthy = false;
        }
        payload.put("nodeStore", nodeStoreHealth);

        Map<String, Object> diskSpace = new HashMap<>();
        try {
            java.nio.file.FileStore fs = Files.getFileStore(storeDirectory);
            long totalSpace = fs.getTotalSpace();
            long usableSpace = fs.getUsableSpace();
            double usagePercent = ((totalSpace - usableSpace) * 100.0) / totalSpace;
            boolean diskHealthy = usagePercent < 90.0;
            diskSpace.put("status", diskHealthy ? "UP" : "WARN");
            diskSpace.put("totalGb", String.format("%.2f", totalSpace / (1024.0 * 1024.0 * 1024.0)));
            diskSpace.put("usableGb", String.format("%.2f", usableSpace / (1024.0 * 1024.0 * 1024.0)));
            diskSpace.put("usagePercent", String.format("%.1f", usagePercent));
            if (!diskHealthy) {
                allHealthy = false;
            }
        } catch (Exception e) {
            diskSpace.put("status", "DOWN");
            diskSpace.put("error", e.getMessage());
            allHealthy = false;
        }
        payload.put("diskSpace", diskSpace);

        AeronClusterLauncher aeronLauncher = (context != null) ? context.aeronClusterLauncher : null;
        if (aeronLauncher != null) {
            Map<String, Object> mediaDriver = new HashMap<>();
            try {
                CrashHandler crashHandler = aeronLauncher.getCrashHandler();
                org.apache.jackrabbit.oak.segment.consensus.aeron.MediaDriverHealthMonitor healthMonitor = aeronLauncher.getHealthMonitor();
                boolean mediaDriverHealthy = true;
                if (crashHandler != null) {
                    mediaDriver.put("crashState", crashHandler.getState());
                    mediaDriver.put("hasCrashed", crashHandler.hasCrashed());
                    mediaDriver.put("forceBootstrap", crashHandler.shouldForceBootstrap());
                    if (crashHandler.hasCrashed()) {
                        mediaDriverHealthy = false;
                    }
                } else {
                    mediaDriver.put("crashHandler", "not_initialized");
                }
                if (healthMonitor != null) {
                    mediaDriver.put("status", healthMonitor.isHealthy() ? "UP" : "DEGRADED");
                    mediaDriver.put("healthStatus", healthMonitor.getHealthStatus());
                    mediaDriver.put("errorCount", healthMonitor.getErrorCount());
                    mediaDriver.put("timeoutCount", healthMonitor.getTimeoutCount());
                    mediaDriver.put("backpressureCount", healthMonitor.getBackpressureCount());
                    mediaDriver.put("freeSpaceMB", healthMonitor.getFreeSpaceMB());
                    if (!healthMonitor.isHealthy()) {
                        mediaDriverHealthy = false;
                    }
                } else {
                    mediaDriver.put("status", "UP");
                    mediaDriver.put("healthMonitor", "not_initialized");
                }
                if (!mediaDriverHealthy) {
                    allHealthy = false;
                }
            } catch (Exception e) {
                mediaDriver.put("status", "DOWN");
                mediaDriver.put("error", e.getMessage());
                allHealthy = false;
            }
            payload.put("mediaDriver", mediaDriver);
        }

        AeronConsensusEngine aeronEngine = (context != null) ? context.aeronConsensusEngine : null;
        if (aeronEngine != null) {
            Map<String, Object> consensus = new HashMap<>();
            try {
                consensus.put("status", "UP");
                consensus.put("mode", "aeron-cluster");
                consensus.put("role", aeronEngine.getCurrentRole().toString());
                consensus.put("isLeader", aeronEngine.isLeader());
                consensus.put("epoch", aeronEngine.getCurrentEpoch());
                consensus.put("term", aeronEngine.getCurrentTerm());
                consensus.put("reachableValidators", aeronEngine.getReachableValidatorCount());
                String currentLeader = aeronEngine.getCurrentLeaderHint();
                consensus.put("currentLeader", currentLeader != null ? currentLeader : "none");
            } catch (Exception e) {
                consensus.put("status", "DOWN");
                consensus.put("error", e.getMessage());
                allHealthy = false;
            }
            payload.put("consensus", consensus);
        }

        Map<String, Object> clients = new HashMap<>();
        clients.put("status", "UP");
        clients.put("registeredClients", registeredClients.size());
        clients.put("registeredValidators", registeredValidators.size());
        payload.put("clients", clients);

        Map<String, Object> blobStore = new HashMap<>();
        if (context != null && context.blobStoreType != null) {
            String blobStoreType = context.blobStoreType;
            blobStore.put("type", blobStoreType);
            if (context.blobStore != null) {
                blobStore.put("status", "UP");
                if ("ipfs".equalsIgnoreCase(blobStoreType)) {
                    blobStore.put("cidMappingAvailable", context.cidMappingService != null);
                    blobStore.put("ipfsGateway", IpfsGatewayUrls.gatewayBase());
                    blobStore.put("ipfsLocalGateway", IpfsGatewayUrls.localGatewayBase());
                } else {
                    blobStore.put("note", blobStoreType + " storage configured");
                }
            } else {
                blobStore.put("status", "DEGRADED");
                blobStore.put("error", "BlobStore not initialized");
            }
        } else {
            blobStore.put("type", "default");
            blobStore.put("status", "UP");
            blobStore.put("note", "FileDataStore (embedded)");
        }
        payload.put("blobStore", blobStore);
        payload.put("sharding", buildShardingPayload());

        Map<String, Object> overall = new HashMap<>();
        overall.put("status", allHealthy ? "UP" : "DEGRADED");
        overall.put("timestamp", new java.util.Date().toString());
        payload.put("overall", overall);
        payload.put("timestamp", System.currentTimeMillis());
        payload.put("success", allHealthy);

        response.setStatus(allHealthy ? HttpServletResponse.SC_OK : HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.getWriter().write(JsonOutputUtil.toJson(payload));
    }
    
    /**
     * Handle cluster-only health check endpoint.
     * 
     * <p>ADR 028: Exposes quorum + heartbeat + leader status for pre-flight checks.</p>
     */
    public void handleClusterHealth(HttpServletResponse response) throws IOException {
        response.setContentType("application/json");
        
        if (context == null || context.aeronConsensusEngine == null) {
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            Map<String, Object> payload = new HashMap<>();
            payload.put("success", false);
            payload.put("status", "UNAVAILABLE");
            payload.put("reason", "cluster_not_initialized");
            payload.put("timestamp", System.currentTimeMillis());
            response.getWriter().write(JsonOutputUtil.toJson(payload));
            return;
        }
        
        boolean healthy = context.aeronConsensusEngine.isClusterHealthy();
        String reason = healthy ? null : context.aeronConsensusEngine.getUnhealthyReason();
        
        response.setStatus(healthy ? HttpServletResponse.SC_OK : HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        
        Map<String, Object> payload = new HashMap<>();
        payload.put("success", healthy);
        payload.put("status", healthy ? "UP" : "UNHEALTHY");
        payload.put("timestamp", System.currentTimeMillis());
        if (!healthy && reason != null) {
            payload.put("unhealthyReason", reason);
        }
        payload.put("reachableCount", context.aeronConsensusEngine.getReachableValidatorCount());
        payload.put("totalMembers", context.aeronConsensusEngine.getTotalMemberCount());
        payload.put("quorumSize", context.aeronConsensusEngine.getQuorumSize());
        payload.put("hasQuorum", context.aeronConsensusEngine.hasQuorum());
        payload.put("lastHeartbeatTime", context.aeronConsensusEngine.getLastHeartbeatTime());
        payload.put("heartbeatAgeMs", context.aeronConsensusEngine.getHeartbeatAgeMs());
        payload.put("leaderUrl", context.aeronConsensusEngine.getCurrentLeaderHint());
        response.getWriter().write(JsonOutputUtil.toJson(payload));
    }

    /**
     * Handle lightweight ops.v1 health snapshot endpoint.
     * GET /v1/ops/snapshots/health
     */
    public void handleGetOpsHealthSnapshot(HttpServletResponse response) throws IOException {
        response.setContentType("application/json");
        long servedAtMs = System.currentTimeMillis();

        try {
            Map<String, Object> data;
            long sourceTimestampMs;
            boolean fromCache = false;

            synchronized (opsHealthSnapshotLock) {
                long now = System.currentTimeMillis();
                boolean cacheValid = cachedOpsHealthSnapshotData != null
                    && cachedOpsHealthSnapshotSourceTimestampMs > 0
                    && (now - cachedOpsHealthSnapshotSourceTimestampMs) <= OPS_HEALTH_SNAPSHOT_TTL_MS;

                if (cacheValid) {
                    data = cachedOpsHealthSnapshotData;
                    sourceTimestampMs = cachedOpsHealthSnapshotSourceTimestampMs;
                    fromCache = true;
                } else {
                    data = buildOpsHealthData();
                    sourceTimestampMs = now;
                    cachedOpsHealthSnapshotData = data;
                    cachedOpsHealthSnapshotSourceTimestampMs = sourceTimestampMs;
                }
            }

            long stalenessMs = Math.max(0L, servedAtMs - sourceTimestampMs);
            response.setStatus(HttpServletResponse.SC_OK);
            response.getWriter().write(buildOpsEnvelope(
                data, sourceTimestampMs, servedAtMs, stalenessMs, false, null, fromCache));
        } catch (Exception e) {
            log.warn("Error building ops health snapshot, attempting stale fallback: {}", e.getMessage());
            if (cachedOpsHealthSnapshotData != null && cachedOpsHealthSnapshotSourceTimestampMs > 0) {
                long stalenessMs = Math.max(0L, servedAtMs - cachedOpsHealthSnapshotSourceTimestampMs);
                response.setStatus(HttpServletResponse.SC_OK);
                response.getWriter().write(buildOpsEnvelope(
                    cachedOpsHealthSnapshotData,
                    cachedOpsHealthSnapshotSourceTimestampMs,
                    servedAtMs,
                    stalenessMs,
                    true,
                    "STALE_CACHE_FALLBACK",
                    true));
                return;
            }

            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            Map<String, Object> payload = new HashMap<>();
            payload.put("contractVersion", "ops.v1");
            payload.put("degraded", true);
            payload.put("degradedReason", "UPSTREAM_UNAVAILABLE");
            response.getWriter().write(JsonOutputUtil.toJson(payload));
        }
    }

    private Map<String, Object> buildOpsHealthData() {
        boolean clusterHealthy = true;
        String unhealthyReason = null;
        int reachableCount = -1;
        int totalMembers = -1;
        int quorumSize = -1;
        String currentRole = "UNKNOWN";
        String leaderUrl = null;

        if (context != null && context.aeronConsensusEngine != null) {
            clusterHealthy = context.aeronConsensusEngine.isClusterHealthy();
            if (!clusterHealthy) {
                unhealthyReason = context.aeronConsensusEngine.getUnhealthyReason();
            }
            reachableCount = context.aeronConsensusEngine.getReachableValidatorCount();
            totalMembers = context.aeronConsensusEngine.getTotalMemberCount();
            quorumSize = context.aeronConsensusEngine.getQuorumSize();
            currentRole = context.aeronConsensusEngine.getCurrentRole().name();
            leaderUrl = context.aeronConsensusEngine.getCurrentLeaderHint();
        }

        boolean blobStoreActive = context != null && context.blobStore != null;
        String blobStoreType = context != null ? context.blobStoreType : null;

        Map<String, Object> payload = new HashMap<>();
        payload.put("status", clusterHealthy ? "UP" : "UNHEALTHY");
        payload.put("clusterHealthy", clusterHealthy);
        payload.put("unhealthyReason", unhealthyReason);
        payload.put("blobStoreType", blobStoreType);
        payload.put("blobStoreActive", blobStoreActive);
        payload.put("reachableCount", reachableCount);
        payload.put("totalMembers", totalMembers);
        payload.put("quorumSize", quorumSize);
        payload.put("currentRole", currentRole);
        payload.put("leaderUrl", leaderUrl);
        payload.put("registeredClients", registeredClients != null ? registeredClients.size() : 0);
        payload.put("registeredValidators", registeredValidators != null ? registeredValidators.size() : 0);
        payload.put("sharding", buildShardingPayload());
        return payload;
    }

    private Map<String, Object> buildShardingPayload() {
        Map<String, Object> sharding = new HashMap<>();
        if (context == null || context.shardingRuntimeConfig == null) {
            sharding.put("enabled", false);
            sharding.put("localPrefixes", "none");
            sharding.put("remoteMountCount", 0);
            sharding.put("authoritativeStoreSeparated", false);
            return sharding;
        }

        sharding.put("enabled", context.shardingRuntimeConfig.isEnabled());
        sharding.put("localPrefixes", context.shardingRuntimeConfig.describeLocalRanges());
        sharding.put("remoteMountCount", context.shardingRuntimeConfig.expandRemoteReadOnlyMounts().size());
        sharding.put(
            "authoritativeStoreSeparated",
            context.authoritativeNodeStore != null && context.authoritativeNodeStore != context.nodeStore
        );
        return sharding;
    }

    private String buildOpsEnvelope(Object data,
                                    long sourceTimestampMs,
                                    long servedAtMs,
                                    long stalenessMs,
                                    boolean degraded,
                                    String degradedReason,
                                    boolean cacheHit) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("contractVersion", "ops.v1");
        payload.put("sourceTimestampMs", sourceTimestampMs);
        payload.put("servedAtMs", servedAtMs);
        payload.put("stalenessMs", stalenessMs);
        payload.put("degraded", degraded);
        payload.put("degradedReason", degradedReason);
        Map<String, Object> cache = new HashMap<>();
        cache.put("hit", cacheHit);
        cache.put("ttlMs", OPS_HEALTH_SNAPSHOT_TTL_MS);
        payload.put("cache", cache);
        payload.put("data", data);
        return JsonOutputUtil.toJson(payload);
    }

}
