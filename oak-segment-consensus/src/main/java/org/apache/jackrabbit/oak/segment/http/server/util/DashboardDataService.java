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
package org.apache.jackrabbit.oak.segment.http.server.util;

import org.apache.jackrabbit.oak.segment.consensus.state.ConsensusState;
import org.apache.jackrabbit.oak.segment.http.server.ServerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Data service for dashboard that fetches data from the same sources as APIs.
 * 
 * BLOCKCHAIN CONSENSUS: This ensures dashboard uses single source of truth
 * by fetching data from the same sources as API endpoints.
 */
public class DashboardDataService {
    
    private static final Logger log = LoggerFactory.getLogger(DashboardDataService.class);
    
    private final ServerContext context;
    
    public DashboardDataService(ServerContext context) {
        this.context = context;
    }
    
    /**
     * Get FileStore statistics (same as /health/deep API).
     */
    public FileStoreStats getFileStoreStats() {
        FileStoreStats stats = new FileStoreStats();
        try {
            if (context.fileStore != null) {
                stats.size = context.fileStore.size();
                stats.segmentCount = context.fileStore.getSegmentCount();
                stats.status = "UP";
            } else {
                stats.status = "DOWN";
                stats.error = "FileStore not initialized";
            }
        } catch (Exception e) {
            stats.status = "DOWN";
            stats.error = e.getMessage();
            log.warn("Failed to get FileStore stats", e);
        }
        return stats;
    }
    
    /**
     * Get consensus state (same as /v1/consensus/status API).
     * 
     * CRITICAL: Must check all consensus engines in same order as ConsensusApiHandler
     * to ensure dashboard matches API output exactly.
     */
    public ConsensusState getConsensusState() {
        // Check for Aeron Cluster consensus first (newest, preferred)
        if (context.aeronConsensusEngine != null) {
            java.util.List<String> allFollowers = context.aeronConsensusEngine.getAllFollowers();
            java.util.List<String> allValidators = new java.util.ArrayList<>();
            allValidators.add(context.selfUrl);
            allValidators.addAll(allFollowers);
            java.util.Collections.sort(allValidators);
            
            // For Aeron, all followers are voting (Raft doesn't have probation concept)
            java.util.List<String> electorate = new java.util.ArrayList<>(allValidators);
            java.util.List<String> nonVotingFollowers = context.aeronConsensusEngine.getNonVotingFollowers();
            
            return new ConsensusState.Builder()
                .consensusType("aeron-cluster")
                .currentRole(context.aeronConsensusEngine.getCurrentRole().toString())
                .currentLeader(context.aeronConsensusEngine.getCurrentLeader())
                .currentEpoch(context.aeronConsensusEngine.getCurrentEpoch())
                .leaderTermSeconds(300) // Default for Aeron (Raft terms are dynamic)
                .secondsUntilRotation(0) // Not applicable for Aeron
                .electorateSize(electorate.size())
                .totalValidators(allValidators.size())
                .allValidators(allValidators)
                .electorate(electorate)
                .nonVotingFollowers(nonVotingFollowers)
                .nextLeader(null) // Not applicable for Aeron (Raft handles leader election)
                .selfUrl(context.selfUrl)
                .build();
        }
        
        // Check for ConsensusStateService (EpochLeaderEngine)
        if (context.consensusStateService != null) {
            return context.consensusStateService.getConsensusState();
        }
        
        // Check for EpochLeaderEngine directly (fallback)
        if (context.epochLeaderEngine != null) {
            java.util.List<String> allFollowers = context.epochLeaderEngine.getAllFollowers();
            java.util.List<String> allValidators = new java.util.ArrayList<>();
            allValidators.add(context.selfUrl);
            allValidators.addAll(allFollowers);
            java.util.Collections.sort(allValidators);
            
            java.util.List<String> electorate = context.epochLeaderEngine.getElection().getAllValidators();
            java.util.Collections.sort(electorate);
            java.util.List<String> nonVotingFollowers = context.epochLeaderEngine.getNonVotingFollowers();
            java.util.Collections.sort(nonVotingFollowers);
            
            // Calculate next leader
            int currentEpoch = context.epochLeaderEngine.getCurrentEpoch();
            String nextLeader = calculateNextLeader(currentEpoch, electorate);
            
            return new ConsensusState.Builder()
                .consensusType("leader-based")
                .currentRole(context.epochLeaderEngine.getCurrentRole().toString())
                .currentLeader(context.epochLeaderEngine.getCurrentLeader())
                .currentEpoch(currentEpoch)
                .leaderTermSeconds(context.epochLeaderEngine.getElection().getLeaderTermSeconds())
                .secondsUntilRotation(context.epochLeaderEngine.getElection().getSecondsUntilRotation())
                .electorateSize(electorate.size())
                .totalValidators(allValidators.size())
                .allValidators(allValidators)
                .electorate(electorate)
                .nonVotingFollowers(nonVotingFollowers)
                .nextLeader(nextLeader)
                .selfUrl(context.selfUrl)
                .build();
        }
        
        return null;
    }
    
    /**
     * Calculate next leader for next epoch (same logic as ConsensusStateService).
     */
    private String calculateNextLeader(int currentEpoch, java.util.List<String> electorate) {
        if (electorate.isEmpty()) {
            return null;
        }
        int nextEpoch = currentEpoch + 1;
        int nextLeaderIndex = nextEpoch % electorate.size();
        return electorate.get(nextLeaderIndex);
    }
    
    /**
     * Get recent segment writes (same as /api/segments/recent API).
     */
    public List<String> getRecentSegments() {
        List<String> recentWrites = new ArrayList<>();
        try {
            java.nio.file.Path journalPath = context.storeDirectory.resolve("journal.log");
            if (java.nio.file.Files.exists(journalPath)) {
                List<String> allLines = java.nio.file.Files.readAllLines(journalPath);
                int start = Math.max(0, allLines.size() - 10);
                recentWrites = allLines.subList(start, allLines.size());
                java.util.Collections.reverse(recentWrites); // Most recent first
            }
        } catch (Exception e) {
            log.warn("Failed to read journal for recent segments", e);
        }
        return recentWrites;
    }
    
    /**
     * FileStore statistics data class.
     */
    public static class FileStoreStats {
        public long size = 0;
        public int segmentCount = 0;
        public String status = "UNKNOWN";
        public String error = null;
    }
}

