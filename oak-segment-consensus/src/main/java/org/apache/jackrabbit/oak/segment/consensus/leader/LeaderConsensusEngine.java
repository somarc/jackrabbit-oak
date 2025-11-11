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
package org.apache.jackrabbit.oak.segment.consensus.leader;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.apache.jackrabbit.oak.spi.state.NodeStore;
import org.apache.jackrabbit.oak.segment.consensus.util.SegmentReplicator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Leader-based consensus engine for Oak validators.
 * 
 * Single leader at any time accepts and sequences all writes.
 * Followers replicate state from leader and serve reads.
 * Leadership rotates automatically based on time epochs.
 */
public class LeaderConsensusEngine {
    
    private static final Logger log = LoggerFactory.getLogger(LeaderConsensusEngine.class);
    
    private final FileStore fileStore;
    private final NodeStore nodeStore;
    private final String selfUrl;
    private final LeaderElection election;
    private final SegmentReplicator replicator;
    private final LeaderHealthMonitor healthMonitor;
    
    private volatile ValidatorRole currentRole;
    private volatile int currentEpoch;
    private volatile String currentLeader;
    
    private Thread rotationMonitor;
    
    public LeaderConsensusEngine(FileStore fileStore, NodeStore nodeStore, 
                                  String selfUrl, List<String> peerUrls) {
        this(fileStore, nodeStore, selfUrl, peerUrls, 300); // Default: 5 min term
    }
    
    public LeaderConsensusEngine(FileStore fileStore, NodeStore nodeStore, 
                                  String selfUrl, List<String> peerUrls, 
                                  int leaderTermSeconds) {
        this.fileStore = fileStore;
        this.nodeStore = nodeStore;
        this.selfUrl = selfUrl;
        this.election = new LeaderElection(selfUrl, peerUrls, leaderTermSeconds);
        this.replicator = new SegmentReplicator(fileStore);
        this.healthMonitor = new LeaderHealthMonitor(selfUrl);
        
        // Determine initial role
        this.currentEpoch = election.getCurrentEpoch();
        this.currentLeader = election.electLeader();
        this.currentRole = election.getRole();
        
        log.info("🎖️  Leader-Based Consensus Engine initialized");
        log.info("   Mode: Leader/Follower with Failure Detection");
        log.info("   Epoch: {}", currentEpoch);
        log.info("   Current leader: {}", currentLeader);
        log.info("   My role: {}", currentRole);
        log.info("   Rotation: every {} seconds", leaderTermSeconds);
        log.info("   Heartbeat: 10s interval, 30s failure threshold");
    }
    
    /**
     * Start the leader rotation monitor.
     * Checks every 10 seconds if epoch has changed (leader should rotate).
     */
    public void startRotationMonitor() {
        // Start health monitoring based on initial role
        if (currentRole == ValidatorRole.LEADER) {
            List<String> followers = election.getPeerValidators();
            healthMonitor.startHeartbeatBroadcast(followers, () -> currentEpoch);
            log.info("💓 Started heartbeat broadcast to {} followers", followers.size());
        } else {
            healthMonitor.startMonitoring();
            log.info("❤️  Started monitoring leader health");
        }
        
        rotationMonitor = new Thread(() -> {
            log.info("🔄 Leader rotation monitor started");
            
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(10000); // Check every 10 seconds
                    
                    int newEpoch = election.getCurrentEpoch();
                    
                    if (newEpoch != currentEpoch) {
                        handleEpochTransition(newEpoch);
                    }
                    
                } catch (InterruptedException e) {
                    log.info("Rotation monitor interrupted");
                    break;
                }
            }
        }, "leader-rotation-monitor");
        
        rotationMonitor.setDaemon(true);
        rotationMonitor.start();
    }
    
    /**
     * Handle transition to a new epoch (leader rotation).
     */
    private void handleEpochTransition(int newEpoch) {
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        log.info("🔄 EPOCH TRANSITION: {} → {}", currentEpoch, newEpoch);
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        
        currentEpoch = newEpoch;
        String newLeader = election.electLeader();
        
        if (!newLeader.equals(currentLeader)) {
            log.info("👑 Leader changed: {} → {}", currentLeader, newLeader);
            currentLeader = newLeader;
        }
        
        ValidatorRole newRole = election.getRole();
        
        if (newRole != currentRole) {
            if (newRole == ValidatorRole.LEADER) {
                transitionToLeader();
            } else {
                transitionToFollower();
            }
            currentRole = newRole;
        } else {
            log.info("Role unchanged: {}", currentRole);
            if (currentRole == ValidatorRole.LEADER) {
                log.info("✅ Continuing as LEADER for epoch {}", currentEpoch);
            } else {
                log.info("✅ Continuing as FOLLOWER, leader: {}", currentLeader);
            }
        }
        
        int secondsRemaining = election.getSecondsUntilRotation();
        log.info("⏱️  Next rotation in {} seconds", secondsRemaining);
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    }
    
    /**
     * Transition this validator to LEADER role.
     */
    private void transitionToLeader() {
        log.info("🎖️  TRANSITIONING TO LEADER");
        log.info("   I am now the source of truth");
        log.info("   Accepting writes for epoch {}", currentEpoch);
        log.info("   Term ends: {}", new java.util.Date(election.getEpochEndTime()));
        
        // Stop monitoring followers (we don't monitor ourselves)
        healthMonitor.stopMonitoring();
        
        // Start broadcasting heartbeats to followers
        List<String> followers = election.getPeerValidators();
        healthMonitor.startHeartbeatBroadcast(followers, () -> currentEpoch);
        log.info("💓 Started heartbeat broadcast to {} followers", followers.size());
    }
    
    /**
     * Transition this validator to FOLLOWER role.
     */
    private void transitionToFollower() {
        log.info("📥 TRANSITIONING TO FOLLOWER");
        log.info("   Current leader: {}", currentLeader);
        log.info("   Will replicate from leader");
        
        // Stop broadcasting heartbeats (only leaders broadcast)
        healthMonitor.stopMonitoring();
        
        // Start monitoring leader's heartbeats
        healthMonitor.startMonitoring();
        log.info("❤️  Started monitoring leader health");
        
        // Pull latest state from new leader
        pullLatestStateFromLeader();
    }
    
    /**
     * Pull the latest HEAD and segments from the current leader.
     */
    private void pullLatestStateFromLeader() {
        if (currentLeader.equals(selfUrl)) {
            log.warn("Cannot pull from self - I am the leader");
            return;
        }
        
        try {
            log.info("📥 Pulling latest state from leader: {}", currentLeader);
            
            // Fetch leader's current HEAD
            URL journalUrl = new URL(currentLeader + "/journal.log");
            HttpURLConnection conn = (HttpURLConnection) journalUrl.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(10000);
            
            if (conn.getResponseCode() == 200) {
                java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(conn.getInputStream())
                );
                
                String firstLine = reader.readLine();
                reader.close();
                
                if (firstLine != null) {
                    String leaderHeadStr = firstLine.split(" ")[0];
                    log.info("   Leader HEAD: {}...", leaderHeadStr.substring(0, 16));
                    
                    // Fetch missing segments for this HEAD
                    try {
                        int segmentCount = replicator.fetchMissingSegmentsForHead(
                            leaderHeadStr, currentLeader
                        );
                        log.info("✅ Replicated {} segments from leader", segmentCount);
                        log.info("✅ Follower state synchronized with leader");
                    } catch (Exception e) {
                        log.error("❌ Failed to replicate segments: {}", e.getMessage());
                    }
                } else {
                    log.warn("Leader journal is empty");
                }
            } else {
                log.warn("Failed to fetch leader journal: HTTP {}", conn.getResponseCode());
            }
            
        } catch (Exception e) {
            log.error("❌ Failed to pull state from leader: {}", e.getMessage());
        }
    }
    
    /**
     * Pull segments for a specific HEAD from the leader.
     * Called by followers when they receive a HEAD update broadcast.
     * 
     * @param headStr The HEAD RecordId to replicate
     * @param leaderUrl The URL of the leader validator
     * @return Number of segments replicated
     */
    public int pullSegmentsForHead(String headStr, String leaderUrl) throws Exception {
        log.info("📥 Pulling segments for HEAD from leader: {}", leaderUrl);
        log.info("   HEAD: {}...", headStr.substring(0, Math.min(16, headStr.length())));
        
        int segmentCount = replicator.fetchMissingSegmentsForHead(headStr, leaderUrl);
        
        log.info("✅ Replicated {} segments for HEAD", segmentCount);
        
        // Update our journal to point to this HEAD
        try {
            org.apache.jackrabbit.oak.segment.RecordId newHead = 
                org.apache.jackrabbit.oak.segment.RecordId.fromString(
                    fileStore.getSegmentIdProvider(), headStr
                );
            
            // Use CAS (compare-and-set) to update HEAD
            // This is the Cold Standby pattern from StandbyClientSyncExecution:77
            org.apache.jackrabbit.oak.segment.RecordId currentHead = fileStore.getHead().getRecordId();
            
            boolean updated = fileStore.getRevisions().setHead(currentHead, newHead);
            
            if (updated) {
                log.info("✅ Updated local HEAD to match leader (CAS success)");
                // Force flush to persist the journal update
                fileStore.flush();
            } else {
                log.warn("⚠️  HEAD CAS failed - current HEAD has changed");
                // This is okay - it means we've already advanced or another thread updated it
            }
        } catch (Exception e) {
            log.error("❌ Failed to update local HEAD: {}", e.getMessage());
            throw e;
        }
        
        return segmentCount;
    }
    
    /**
     * Broadcast HEAD update to all followers (called by leader after write).
     */
    public void broadcastHeadToFollowers(String newHeadStr) {
        if (currentRole != ValidatorRole.LEADER) {
            log.warn("Cannot broadcast - not the leader");
            return;
        }
        
        List<String> peers = election.getPeerValidators();
        
        log.debug("📡 Broadcasting HEAD to {} followers", peers.size());
        
        for (String peerUrl : peers) {
            new Thread(() -> {
                try {
                    URL url = new URL(peerUrl + "/v1/follower/head-update");
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.setDoOutput(true);
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(10000);
                    
                    String payload = String.format(
                        "{\"head\":\"%s\",\"epoch\":%d,\"leaderUrl\":\"%s\"}",
                        newHeadStr, currentEpoch, selfUrl
                    );
                    
                    conn.getOutputStream().write(payload.getBytes("UTF-8"));
                    
                    int responseCode = conn.getResponseCode();
                    if (responseCode == 200) {
                        log.debug("   ✅ HEAD broadcast to {}", peerUrl);
                    } else {
                        log.warn("   ⚠️  HEAD broadcast to {}: HTTP {}", peerUrl, responseCode);
                    }
                    
                } catch (Exception e) {
                    log.warn("   ❌ Failed to broadcast to {}: {}", peerUrl, e.getMessage());
                }
            }, "head-broadcast-" + peerUrl.hashCode()).start();
        }
    }
    
    // Getters
    
    public ValidatorRole getCurrentRole() {
        return currentRole;
    }
    
    public boolean isLeader() {
        return currentRole == ValidatorRole.LEADER;
    }
    
    public String getCurrentLeader() {
        return currentLeader;
    }
    
    public int getCurrentEpoch() {
        return currentEpoch;
    }
    
    public LeaderElection getElection() {
        return election;
    }
    
    public LeaderHealthMonitor getHealthMonitor() {
        return healthMonitor;
    }
}

