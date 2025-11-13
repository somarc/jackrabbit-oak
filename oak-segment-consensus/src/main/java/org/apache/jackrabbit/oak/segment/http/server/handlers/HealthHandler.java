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
import org.apache.jackrabbit.oak.segment.consensus.leader.EpochLeaderEngine;
import org.apache.jackrabbit.oak.segment.consensus.aeron.AeronConsensusEngine;
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
            EpochLeaderEngine epochLeaderEngine,
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
     */
    public void handleHealth(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json");
        response.getWriter().write("{\"status\":\"UP\",\"store\":\"" + storeDirectory + "\"}");
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
        
        // 4. Check consensus engine (if configured) - check Aeron first, then Leader
        // Use context fields directly (volatile) to get current state, not constructor snapshot
        AeronConsensusEngine aeronEngine = (context != null) ? context.aeronConsensusEngine : null;
        EpochLeaderEngine leaderEngine = (context != null) ? context.epochLeaderEngine : null;
        
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
        } else if (leaderEngine != null) {
            json.append("  \"consensus\": {\n");
            try {
                json.append("    \"status\": \"UP\",\n");
                json.append("    \"mode\": \"leader\",\n");
                json.append("    \"role\": \"").append(leaderEngine.getCurrentRole()).append("\",\n");
                json.append("    \"isLeader\": ").append(leaderEngine.isLeader()).append(",\n");
                json.append("    \"epoch\": ").append(leaderEngine.getCurrentEpoch()).append(",\n");
                json.append("    \"reachableValidators\": ").append(leaderEngine.getReachableValidatorCount()).append("\n");
            } catch (Exception e) {
                json.append("    \"status\": \"DOWN\",\n");
                json.append("    \"error\": \"").append(e.getMessage()).append("\"\n");
                allHealthy = false;
            }
            json.append("  },\n");
        }
        
        // 5. Check connected clients
        json.append("  \"clients\": {\n");
        json.append("    \"status\": \"UP\",\n");
        json.append("    \"registeredClients\": ").append(registeredClients.size()).append(",\n");
        json.append("    \"registeredValidators\": ").append(registeredValidators.size()).append("\n");
        json.append("  },\n");
        
        // 6. Overall health
        json.append("  \"overall\": {\n");
        json.append("    \"status\": \"").append(allHealthy ? "UP" : "DEGRADED").append("\",\n");
        json.append("    \"timestamp\": \"").append(new java.util.Date()).append("\"\n");
        json.append("  }\n");
        
        json.append("}\n");
        
        // Return 200 if healthy, 503 if degraded
        response.setStatus(allHealthy ? HttpServletResponse.SC_OK : HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.getWriter().write(json.toString());
    }
}

