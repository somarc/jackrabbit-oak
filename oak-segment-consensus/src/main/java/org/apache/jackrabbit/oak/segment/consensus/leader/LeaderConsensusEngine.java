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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.apache.jackrabbit.oak.spi.state.NodeStore;
import org.apache.jackrabbit.oak.segment.consensus.util.SegmentReplicator;
import org.apache.jackrabbit.oak.segment.consensus.metrics.ConsensusMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Leader-based consensus engine for Oak validators.
 * 
 * Single leader at any time accepts and sequences all writes.
 * Followers replicate state from leader and serve reads.
 * Leadership rotates automatically based on time epochs.
 * 
 * SECURITY: Enforces probationary period for new validators to prevent
 * join/leave manipulation attacks on leadership.
 */
public class LeaderConsensusEngine {
    
    private static final Logger log = LoggerFactory.getLogger(LeaderConsensusEngine.class);
    
    private final FileStore fileStore;
    private final NodeStore nodeStore;
    private final String selfUrl;
    private volatile LeaderElection election;  // Changed to volatile for dynamic peer updates
    private final SegmentReplicator replicator;
    private final LeaderHealthMonitor healthMonitor;
    
    /**
     * Track when each validator joined the network (URL -> timestamp).
     * Used to enforce probationary period before leadership eligibility.
     */
    private final Map<String, Long> validatorJoinTimes = new ConcurrentHashMap<>();
    
    /**
     * ALL followers (voting + non-voting). Used for heartbeats and replication.
     * New validators join here first as NON-VOTING followers.
     */
    private final List<String> allFollowers = new java.util.concurrent.CopyOnWriteArrayList<>();
    
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
        this.replicator = new SegmentReplicator(fileStore);
        this.healthMonitor = new LeaderHealthMonitor(selfUrl);
        
        // Record join times for self and all initial peers
        long now = System.currentTimeMillis();
        validatorJoinTimes.put(selfUrl, now);
        for (String peerUrl : peerUrls) {
            // Initial peers are assumed to have joined at the same time (genesis or config)
            validatorJoinTimes.put(peerUrl, now);
            allFollowers.add(peerUrl);  // Add to follower list for heartbeats
        }
        
        // Create election with join times (initial peers are part of electorate)
        this.election = new LeaderElection(selfUrl, peerUrls, leaderTermSeconds, validatorJoinTimes);
        
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
        log.info("   🛡️  Probationary period: {} seconds (new validators must be followers)", leaderTermSeconds);
    }
    
    /**
     * Dynamically add a new peer to the consensus network.
     * Triggers leader election refresh and role recalculation.
     * 
     * This is called when a new validator joins the network and broadcasts
     * its presence. Enables true dynamic peer discovery.
     * 
     * @param peerUrl The URL of the new peer validator
     */
    public synchronized void addPeer(String peerUrl) {
        if (peerUrl == null || peerUrl.trim().isEmpty()) {
            log.warn("Attempted to add empty peer URL");
            return;
        }
        
        if (peerUrl.equals(selfUrl)) {
            log.debug("Ignoring self-registration: {}", peerUrl);
            return;
        }
        
        // Check if peer already exists in followers
        if (allFollowers.contains(peerUrl)) {
            log.debug("Peer already registered as follower: {}", peerUrl);
            return;
        }
        
        // Check if peer is already in electorate
        List<String> currentElectorate = election.getAllValidators();
        if (currentElectorate.contains(peerUrl)) {
            log.debug("Peer already in electorate: {}", peerUrl);
            return;
        }
        
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        log.info("👥 NEW VALIDATOR JOINING AS NON-VOTING FOLLOWER");
        log.info("   Peer URL: {}", peerUrl);
        log.info("   Current electorate: {} validators", currentElectorate.size());
        log.info("   Total followers (voting + non-voting): {}", allFollowers.size() + 1);
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        
        // Record join time for new peer (NOW - they just joined)
        long joinTime = System.currentTimeMillis();
        validatorJoinTimes.put(peerUrl, joinTime);
        
        // Add to followers list (for heartbeats and replication)
        allFollowers.add(peerUrl);
        
        int leaderTermSeconds = election.getLeaderTermSeconds();
        long probationEndTime = joinTime + (leaderTermSeconds * 1000L);
        
        log.info("🛡️  Probationary period: {} seconds", leaderTermSeconds);
        log.info("   Promotion to electorate after: {}", new java.util.Date(probationEndTime));
        log.info("   Status: NON-VOTING FOLLOWER");
        log.info("   - Receives heartbeats ✅");
        log.info("   - Replicates data ✅");
        log.info("   - Can vote for leader ❌");
        log.info("   - Can become leader ❌");
        
        // DO NOT rebuild election - electorate stays the same!
        // The leader does NOT change just because a follower joined
        
        log.info("📊 Network status:");
        log.info("   Voting members (electorate): {}", currentElectorate.size());
        log.info("   Non-voting followers: {}", allFollowers.size() - currentElectorate.size() + 1);
        log.info("   Total validators: {}", allFollowers.size() + 1);
        log.info("   Current leader: {} (UNCHANGED)", currentLeader);
        log.info("   My role: {} (UNCHANGED)", currentRole);
        
        // If we're the leader, add this new follower to our heartbeat list
        if (currentRole == ValidatorRole.LEADER) {
            // Create combined list of electorate peers + non-voting followers
            List<String> allFollowersForHeartbeat = new java.util.ArrayList<>(allFollowers);
            int electorateSizeForQuorum = currentElectorate.size();
            healthMonitor.updateFollowerList(allFollowersForHeartbeat);
            healthMonitor.updateElectorateSize(electorateSizeForQuorum);
            log.info("💓 Updated heartbeat list: {} total followers (voting + non-voting)", allFollowersForHeartbeat.size());
            log.info("🗳️  Electorate size (for quorum): {}", electorateSizeForQuorum);
        }
        
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        log.info("✅ Follower added successfully (NON-VOTING until probation ends)");
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    }
    
    /**
     * Start the leader rotation monitor.
     * Checks every 10 seconds if epoch has changed (leader should rotate).
     */
    public void startRotationMonitor() {
        // Start health monitoring based on initial role
        if (currentRole == ValidatorRole.LEADER) {
            // Set up split-brain detection callback
            healthMonitor.setDemotionCallback(() -> demoteToFollowerOnQuorumLoss());
            
            // Use allFollowers (electorate + non-voting) for heartbeats
            List<String> followers = new java.util.ArrayList<>(allFollowers);
            int electorateSizeForQuorum = election.getAllValidators().size();
            healthMonitor.startHeartbeatBroadcast(followers, () -> currentEpoch, electorateSizeForQuorum);
            log.info("💓 Started heartbeat broadcast to {} followers (voting + non-voting)", followers.size());
            log.info("🗳️  Quorum based on electorate size: {}", electorateSizeForQuorum);
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
        
        // Record leader election in metrics
        ConsensusMetrics.recordLeaderElection();
        ConsensusMetrics.updateLeaderStatus(true, currentEpoch);
        
        // Stop monitoring followers (we don't monitor ourselves)
        healthMonitor.stopMonitoring();
        
        // Set up split-brain detection callback
        healthMonitor.setDemotionCallback(() -> demoteToFollowerOnQuorumLoss());
        
        // Start broadcasting heartbeats to ALL followers (voting + non-voting)
        List<String> followers = new java.util.ArrayList<>(allFollowers);
        int electorateSizeForQuorum = election.getAllValidators().size();
        healthMonitor.startHeartbeatBroadcast(followers, () -> currentEpoch, electorateSizeForQuorum);
        log.info("💓 Started heartbeat broadcast to {} followers (voting + non-voting)", followers.size());
        log.info("🗳️  Quorum based on electorate size: {}", electorateSizeForQuorum);
    }
    
    /**
     * Demote this validator to FOLLOWER when quorum is lost (split-brain prevention).
     */
    private synchronized void demoteToFollowerOnQuorumLoss() {
        log.error("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        log.error("🧠 DEMOTING TO FOLLOWER DUE TO QUORUM LOSS");
        log.error("   This prevents split-brain scenarios");
        log.error("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        
        // Change role
        currentRole = ValidatorRole.FOLLOWER;
        
        // Update metrics
        ConsensusMetrics.updateLeaderStatus(false, currentEpoch);
        
        // Stop broadcasting heartbeats
        healthMonitor.stopMonitoring();
        
        // Enter read-only mode
        log.warn("⚠️  Entering read-only mode until quorum restored");
        log.warn("⚠️  Will attempt to rejoin consensus when possible");
    }
    
    /**
     * Transition this validator to FOLLOWER role.
     */
    private void transitionToFollower() {
        log.info("📥 TRANSITIONING TO FOLLOWER");
        log.info("   Current leader: {}", currentLeader);
        log.info("   Will replicate from leader");
        
        // Update metrics to reflect follower status
        ConsensusMetrics.updateLeaderStatus(false, currentEpoch);
        
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
                long startTime = System.nanoTime();
                String targetValidator = peerUrl.replaceAll("https?://", "").split(":")[0];
                
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
                    double latencySeconds = (System.nanoTime() - startTime) / 1_000_000_000.0;
                    
                    if (responseCode == 200) {
                        log.debug("   ✅ HEAD broadcast to {}", peerUrl);
                        ConsensusMetrics.recordReplication(targetValidator, "success", latencySeconds);
                    } else {
                        log.warn("   ⚠️  HEAD broadcast to {}: HTTP {}", peerUrl, responseCode);
                        ConsensusMetrics.recordReplication(targetValidator, "failure", latencySeconds);
                    }
                    
                } catch (java.net.SocketTimeoutException e) {
                    double latencySeconds = (System.nanoTime() - startTime) / 1_000_000_000.0;
                    log.warn("   ❌ Failed to broadcast to {}: {}", peerUrl, e.getMessage());
                    ConsensusMetrics.recordReplication(targetValidator, "timeout", latencySeconds);
                } catch (Exception e) {
                    double latencySeconds = (System.nanoTime() - startTime) / 1_000_000_000.0;
                    log.warn("   ❌ Failed to broadcast to {}: {}", peerUrl, e.getMessage());
                    ConsensusMetrics.recordReplication(targetValidator, "failure", latencySeconds);
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
    
    /**
     * Force this validator into FOLLOWER mode.
     * 
     * Used when a validator joins the network after bootstrap.
     * The validator must not immediately claim leadership based on epoch calculation.
     * Instead, it must listen for heartbeats from the existing leader.
     * 
     * This prevents split-brain scenarios where a newly joined validator
     * incorrectly thinks it's the leader for the current epoch.
     */
    public void forceFollowerMode() {
        log.warn("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        log.warn("⚠️  FORCING FOLLOWER MODE (post-bootstrap join)");
        log.warn("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        
        // Stop any leader behavior we might have started
        if (currentRole == ValidatorRole.LEADER) {
            healthMonitor.stopMonitoring();
            log.info("   Stopped leader heartbeat broadcasting");
        }
        
        // Set role to FOLLOWER
        currentRole = ValidatorRole.FOLLOWER;
        
        // Set leader to first known peer (bootstrap primary)
        if (!allFollowers.isEmpty()) {
            currentLeader = allFollowers.get(0);
            log.info("   Expected leader: {}", currentLeader);
        } else {
            currentLeader = "unknown";
            log.warn("   No known leader yet - will discover via heartbeat");
        }
        
        // Start listening for heartbeats
        healthMonitor.startMonitoring();
        
        log.info("✅ Now in FOLLOWER mode, listening for leader heartbeats");
        log.warn("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
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
    
    /**
     * Get the number of reachable validators (for metrics).
     * Returns the number of peer validators we can communicate with.
     */
    public int getReachableValidatorCount() {
        List<String> peers = election.getPeerValidators();
        return peers != null ? peers.size() : 0;
    }
    
    /**
     * Get the time of the last heartbeat (for metrics).
     * Returns the last time we received or sent a heartbeat.
     */
    public long getLastHeartbeatTime() {
        return healthMonitor.getLastHeartbeatTime();
    }
    
    /**
     * Get all followers (voting + non-voting).
     */
    public List<String> getAllFollowers() {
        return new java.util.ArrayList<>(allFollowers);
    }
    
    /**
     * Get only the electorate (voting members).
     */
    public List<String> getElectorate() {
        return election.getAllValidators();
    }
    
    /**
     * Get only non-voting followers (those still on probation).
     */
    public List<String> getNonVotingFollowers() {
        List<String> electorate = election.getAllValidators();
        List<String> nonVoting = new java.util.ArrayList<>();
        for (String follower : allFollowers) {
            if (!electorate.contains(follower)) {
                nonVoting.add(follower);
            }
        }
        return nonVoting;
    }
}

