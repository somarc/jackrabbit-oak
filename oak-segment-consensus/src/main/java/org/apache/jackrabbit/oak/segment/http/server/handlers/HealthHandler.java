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

import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.apache.jackrabbit.oak.segment.consensus.aeron.AeronConsensusEngine;
import org.apache.jackrabbit.oak.segment.consensus.aeron.AeronClusterLauncher;
import org.apache.jackrabbit.oak.segment.consensus.aeron.CrashHandler;
import org.apache.jackrabbit.oak.segment.http.server.ServerContext;
import org.apache.jackrabbit.oak.spi.state.NodeStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Handler for health check endpoints.
 * 
 * <p>Extracted from SegmentHttpServer to separate health check concerns.</p>
 */
public class HealthHandler {
    private static final Logger log = LoggerFactory.getLogger(HealthHandler.class);
    
    private final FileStore fileStore;
    private final NodeStore nodeStore;
    private final Path storeDirectory;
    private final ServerContext context;
    private final Map<String, ?> registeredClients;
    private final Map<String, ?> registeredValidators;
    
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
        
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"status\": \"").append(isClusterHealthy ? "UP" : "UNHEALTHY").append("\",\n");
        
        // ADR 028: Include unhealthy reason if applicable
        if (!isClusterHealthy && unhealthyReason != null) {
            json.append("  \"unhealthyReason\": \"").append(unhealthyReason).append("\",\n");
        }
        
        json.append("  \"store\": \"").append(storeDirectory).append("\"");
        
        // Add BlobStore type for dashboard status checks
        if (context != null && context.blobStoreType != null) {
            json.append(",\n  \"blobStoreType\": \"").append(context.blobStoreType).append("\"");
            json.append(",\n  \"blobStoreActive\": ").append(context.blobStore != null);
        }
        
        // Add committedHead vs latestHead if Aeron engine is available
        if (context != null && context.aeronConsensusEngine != null) {
            // ADR 028: Add cluster health status
            json.append(",\n  \"clusterHealthy\": ").append(isClusterHealthy);
            json.append(",\n  \"reachableCount\": ").append(context.aeronConsensusEngine.getReachableValidatorCount());
            json.append(",\n  \"totalMembers\": ").append(context.aeronConsensusEngine.getTotalMemberCount());
            json.append(",\n  \"quorumSize\": ").append(context.aeronConsensusEngine.getQuorumSize());
            json.append(",\n  \"currentRole\": \"").append(context.aeronConsensusEngine.getCurrentRole().name()).append("\"");
            
            String committedHead = context.aeronConsensusEngine.getCommittedHead();
            String latestHead = context.aeronConsensusEngine.getLatestHead();
            int latestEpochSeen = context.aeronConsensusEngine.getLatestEpochSeen();
            int committedEpoch = context.aeronConsensusEngine.getLastCommittedEpoch();
            
            if (committedHead != null && !committedHead.isEmpty()) {
                json.append(",\n  \"committedHead\": \"").append(committedHead).append("\"");
            }
            if (latestHead != null && !latestHead.isEmpty()) {
                json.append(",\n  \"latestHead\": \"").append(latestHead).append("\"");
            }
            if (latestEpochSeen >= 0) {
                json.append(",\n  \"latestEpochSeen\": ").append(latestEpochSeen);
            }
            if (committedEpoch >= 0) {
                json.append(",\n  \"committedEpoch\": ").append(committedEpoch);
            }
        }
        
        json.append("\n}");
        response.getWriter().write(json.toString());
    }
    
    /**
     * Handle comprehensive health check - validates all system components.
     */
    public void handleDeepHealth(HttpServletResponse response) throws IOException {
        response.setContentType("application/json");
        
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        
        boolean allHealthy = true;
        
        // 1. Check FileStore health
        json.append("  \"fileStore\": {\n");
        try {
            if (fileStore != null) {
                String headId = fileStore.getHead().getRecordId().toString10();
                json.append("    \"status\": \"UP\",\n");
                json.append("    \"head\": \"").append(headId.substring(0, Math.min(16, headId.length()))).append("...\"\n");
                
                // 🔄 FINALITY-AWARE HEAD TRACKING: Expose committedHead vs latestHead
                // committedHead: HEAD that has reached finality (epoch N-2) - immutable, safe
                // latestHead: Current HEAD including pending writes (epoch N, N+1) - may change
                AeronConsensusEngine aeronEngine = (context != null) ? context.aeronConsensusEngine : null;
                if (aeronEngine != null) {
                    String committedHead = aeronEngine.getCommittedHead();
                    String latestHead = aeronEngine.getLatestHead();
                    int latestEpochSeen = aeronEngine.getLatestEpochSeen();
                    int committedEpoch = aeronEngine.getLastCommittedEpoch();
                    
                    boolean hasCommittedHead = committedHead != null && !committedHead.isEmpty();
                    boolean hasLatestHead = latestHead != null && !latestHead.isEmpty();
                    boolean hasLatestEpoch = latestEpochSeen >= 0;
                    boolean hasCommittedEpoch = committedEpoch >= 0;
                    
                    if (hasCommittedHead) {
                        json.append("    \"committedHead\": \"").append(committedHead).append("\"");
                        if (hasLatestHead || hasLatestEpoch || hasCommittedEpoch) {
                            json.append(",\n");
                        } else {
                            json.append("\n");
                        }
                    }
                    if (hasLatestHead) {
                        json.append("    \"latestHead\": \"").append(latestHead).append("\"");
                        if (hasLatestEpoch || hasCommittedEpoch) {
                            json.append(",\n");
                        } else {
                            json.append("\n");
                        }
                    }
                    if (hasLatestEpoch) {
                        json.append("    \"latestEpochSeen\": ").append(latestEpochSeen);
                        if (hasCommittedEpoch) {
                            json.append(",\n");
                        } else {
                            json.append("\n");
                        }
                    }
                    if (hasCommittedEpoch) {
                        json.append("    \"committedEpoch\": ").append(committedEpoch).append("\n");
                    }
                }
            } else {
                json.append("    \"status\": \"DOWN\",\n");
                json.append("    \"error\": \"FileStore not initialized\"\n");
                allHealthy = false;
            }
        } catch (Exception e) {
            json.append("    \"status\": \"DOWN\",\n");
            json.append("    \"error\": \"").append(e.getMessage()).append("\"\n");
            allHealthy = false;
        }
        json.append("  },\n");

        // 2.5. Check cluster health
        json.append("  \"cluster\": {\n");
        try {
            AeronConsensusEngine aeronEngine = (context != null) ? context.aeronConsensusEngine : null;
            if (aeronEngine != null) {
                boolean clusterHealthy = aeronEngine.isClusterHealthy();
                json.append("    \"status\": \"").append(clusterHealthy ? "UP" : "UNHEALTHY").append("\",\n");
                json.append("    \"reachableCount\": ").append(aeronEngine.getReachableValidatorCount()).append(",\n");
                json.append("    \"totalMembers\": ").append(aeronEngine.getTotalMemberCount()).append(",\n");
                json.append("    \"quorumSize\": ").append(aeronEngine.getQuorumSize()).append(",\n");
                json.append("    \"currentRole\": \"").append(aeronEngine.getCurrentRole().name()).append("\",\n");
                json.append("    \"heartbeatAgeMs\": ").append(aeronEngine.getHeartbeatAgeMs());
                String reason = aeronEngine.getUnhealthyReason();
                if (reason != null) {
                    json.append(",\n    \"unhealthyReason\": \"").append(reason).append("\"\n");
                } else {
                    json.append("\n");
                }
                if (!clusterHealthy) {
                    allHealthy = false;
                }
            } else {
                json.append("    \"status\": \"UNKNOWN\",\n");
                json.append("    \"error\": \"Aeron consensus engine not initialized\"\n");
                allHealthy = false;
            }
        } catch (Exception e) {
            json.append("    \"status\": \"DOWN\",\n");
            json.append("    \"error\": \"").append(e.getMessage()).append("\"\n");
            allHealthy = false;
        }
        json.append("  },\n");
        
        // 2. Check NodeStore health
        json.append("  \"nodeStore\": {\n");
        try {
            if (nodeStore != null) {
                org.apache.jackrabbit.oak.spi.state.NodeState root = nodeStore.getRoot();
                json.append("    \"status\": \"UP\",\n");
                json.append("    \"rootExists\": ").append(root != null).append("\n");
            } else {
                json.append("    \"status\": \"DOWN\",\n");
                json.append("    \"error\": \"NodeStore not initialized\"\n");
                allHealthy = false;
            }
        } catch (Exception e) {
            json.append("    \"status\": \"DOWN\",\n");
            json.append("    \"error\": \"").append(e.getMessage()).append("\"\n");
            allHealthy = false;
        }
        json.append("  },\n");
        
        // 3. Check disk space
        json.append("  \"diskSpace\": {\n");
        try {
            java.nio.file.FileStore fs = Files.getFileStore(storeDirectory);
            long totalSpace = fs.getTotalSpace();
            long usableSpace = fs.getUsableSpace();
            double usagePercent = ((totalSpace - usableSpace) * 100.0) / totalSpace;
            
            boolean diskHealthy = usagePercent < 90.0;  // Alert if > 90% full
            json.append("    \"status\": \"").append(diskHealthy ? "UP" : "WARN").append("\",\n");
            json.append("    \"totalGb\": ").append(String.format("%.2f", totalSpace / (1024.0 * 1024.0 * 1024.0))).append(",\n");
            json.append("    \"usableGb\": ").append(String.format("%.2f", usableSpace / (1024.0 * 1024.0 * 1024.0))).append(",\n");
            json.append("    \"usagePercent\": ").append(String.format("%.1f", usagePercent)).append("\n");
            
            if (!diskHealthy) {
                allHealthy = false;
            }
        } catch (Exception e) {
            json.append("    \"status\": \"DOWN\",\n");
            json.append("    \"error\": \"").append(e.getMessage()).append("\"\n");
            allHealthy = false;
        }
        json.append("  },\n");
        
        // 4. Check MediaDriver health (if Aeron Cluster is configured)
        AeronClusterLauncher aeronLauncher = (context != null) ? context.aeronClusterLauncher : null;
        if (aeronLauncher != null) {
            json.append("  \"mediaDriver\": {\n");
            try {
                CrashHandler crashHandler = aeronLauncher.getCrashHandler();
                org.apache.jackrabbit.oak.segment.consensus.aeron.MediaDriverHealthMonitor healthMonitor = 
                    aeronLauncher.getHealthMonitor();
                
                boolean mediaDriverHealthy = true;
                
                // Check crash handler
                if (crashHandler != null) {
                    String crashState = crashHandler.getState();
                    boolean hasCrashed = crashHandler.hasCrashed();
                    boolean shouldBootstrap = crashHandler.shouldForceBootstrap();
                    
                    json.append("    \"crashState\": \"").append(crashState).append("\",\n");
                    json.append("    \"hasCrashed\": ").append(hasCrashed).append(",\n");
                    json.append("    \"forceBootstrap\": ").append(shouldBootstrap).append(",\n");
                    
                    if (hasCrashed) {
                        mediaDriverHealthy = false;
                    }
                } else {
                    json.append("    \"crashHandler\": \"not_initialized\",\n");
                }
                
                // Check health monitor (if available)
                if (healthMonitor != null) {
                    boolean monitorHealthy = healthMonitor.isHealthy();
                    String monitorStatus = healthMonitor.getHealthStatus();
                    long errorCount = healthMonitor.getErrorCount();
                    long timeoutCount = healthMonitor.getTimeoutCount();
                    long backpressureCount = healthMonitor.getBackpressureCount();
                    long freeSpaceMB = healthMonitor.getFreeSpaceMB();
                    
                    json.append("    \"status\": \"").append(monitorHealthy ? "UP" : "DEGRADED").append("\",\n");
                    json.append("    \"healthStatus\": \"").append(monitorStatus).append("\",\n");
                    json.append("    \"errorCount\": ").append(errorCount).append(",\n");
                    json.append("    \"timeoutCount\": ").append(timeoutCount).append(",\n");
                    json.append("    \"backpressureCount\": ").append(backpressureCount).append(",\n");
                    json.append("    \"freeSpaceMB\": ").append(freeSpaceMB).append("\n");
                    
                    if (!monitorHealthy) {
                        mediaDriverHealthy = false;
                    }
                } else {
                    json.append("    \"status\": \"UP\",\n");
                    json.append("    \"healthMonitor\": \"not_initialized\"\n");
                }
                
                if (!mediaDriverHealthy) {
                    allHealthy = false;
                }
            } catch (Exception e) {
                json.append("    \"status\": \"DOWN\",\n");
                json.append("    \"error\": \"").append(e.getMessage().replace("\"", "\\\"")).append("\"\n");
                allHealthy = false;
            }
            json.append("  },\n");
        }
        
        // 5. Check consensus engine (Aeron Cluster only)
        // Use context fields directly (volatile) to get current state, not constructor snapshot
        AeronConsensusEngine aeronEngine = (context != null) ? context.aeronConsensusEngine : null;
        
        if (aeronEngine != null) {
            json.append("  \"consensus\": {\n");
            try {
                json.append("    \"status\": \"UP\",\n");
                json.append("    \"mode\": \"aeron-cluster\",\n");
                json.append("    \"role\": \"").append(aeronEngine.getCurrentRole()).append("\",\n");
                json.append("    \"isLeader\": ").append(aeronEngine.isLeader()).append(",\n");
                json.append("    \"epoch\": ").append(aeronEngine.getCurrentEpoch()).append(",\n");
                json.append("    \"term\": ").append(aeronEngine.getCurrentTerm()).append(",\n");
                json.append("    \"reachableValidators\": ").append(aeronEngine.getReachableValidatorCount()).append(",\n");
                json.append("    \"currentLeader\": \"").append(aeronEngine.getCurrentLeader() != null ? aeronEngine.getCurrentLeader() : "none").append("\"\n");
            } catch (Exception e) {
                json.append("    \"status\": \"DOWN\",\n");
                json.append("    \"error\": \"").append(e.getMessage()).append("\"\n");
                allHealthy = false;
            }
            json.append("  },\n");
        }
        
        // 6. Check connected clients
        json.append("  \"clients\": {\n");
        json.append("    \"status\": \"UP\",\n");
        json.append("    \"registeredClients\": ").append(registeredClients.size()).append(",\n");
        json.append("    \"registeredValidators\": ").append(registeredValidators.size()).append("\n");
        json.append("  },\n");
        
        // 7. BlobStore health
        json.append("  \"blobStore\": {\n");
        if (context != null && context.blobStoreType != null) {
            String blobStoreType = context.blobStoreType;
            json.append("    \"type\": \"").append(blobStoreType).append("\",\n");
            
            if (context.blobStore != null) {
                json.append("    \"status\": \"UP\",\n");
                
                // Check if IPFS and add gateway info
                if ("ipfs".equalsIgnoreCase(blobStoreType)) {
                    json.append("    \"cidMappingAvailable\": ").append(context.cidMappingService != null).append(",\n");
                    json.append("    \"ipfsGateway\": \"http://127.0.0.1:8080/ipfs/\"\n");
                } else {
                    json.append("    \"note\": \"").append(blobStoreType).append(" storage configured\"\n");
                }
            } else {
                json.append("    \"status\": \"DEGRADED\",\n");
                json.append("    \"error\": \"BlobStore not initialized\"\n");
            }
        } else {
            json.append("    \"type\": \"default\",\n");
            json.append("    \"status\": \"UP\",\n");
            json.append("    \"note\": \"FileDataStore (embedded)\"\n");
        }
        json.append("  },\n");
        
        // 8. Overall health
        json.append("  \"overall\": {\n");
        json.append("    \"status\": \"").append(allHealthy ? "UP" : "DEGRADED").append("\",\n");
        json.append("    \"timestamp\": \"").append(new java.util.Date()).append("\"\n");
        json.append("  }\n");
        
        json.append("}\n");
        
        // Return 200 if healthy, 503 if degraded
        response.setStatus(allHealthy ? HttpServletResponse.SC_OK : HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.getWriter().write(json.toString());
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
            response.getWriter().write("{\"status\":\"UNAVAILABLE\",\"reason\":\"cluster_not_initialized\"}");
            return;
        }
        
        boolean healthy = context.aeronConsensusEngine.isClusterHealthy();
        String reason = healthy ? null : context.aeronConsensusEngine.getUnhealthyReason();
        
        response.setStatus(healthy ? HttpServletResponse.SC_OK : HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"status\": \"").append(healthy ? "UP" : "UNHEALTHY").append("\",\n");
        if (!healthy && reason != null) {
            json.append("  \"unhealthyReason\": \"").append(reason).append("\",\n");
        }
        json.append("  \"reachableCount\": ").append(context.aeronConsensusEngine.getReachableValidatorCount()).append(",\n");
        json.append("  \"totalMembers\": ").append(context.aeronConsensusEngine.getTotalMemberCount()).append(",\n");
        json.append("  \"quorumSize\": ").append(context.aeronConsensusEngine.getQuorumSize()).append(",\n");
        json.append("  \"hasQuorum\": ").append(context.aeronConsensusEngine.hasQuorum()).append(",\n");
        json.append("  \"lastHeartbeatTime\": ").append(context.aeronConsensusEngine.getLastHeartbeatTime()).append(",\n");
        json.append("  \"heartbeatAgeMs\": ").append(context.aeronConsensusEngine.getHeartbeatAgeMs()).append(",\n");
        json.append("  \"leaderUrl\": \"").append(context.aeronConsensusEngine.getCurrentLeader()).append("\"\n");
        json.append("}");
        response.getWriter().write(json.toString());
    }
}
