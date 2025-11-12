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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Monitors the health of the current leader by tracking heartbeats.
 * 
 * <p>Leaders send heartbeat signals every 10 seconds.
 * Followers monitor these heartbeats and can detect leader failure
 * if no heartbeat is received within the timeout threshold (30 seconds).
 * 
 * <p>This allows the system to recover from leader failure much faster
 * than waiting for the next epoch rotation (5 minutes).
 */
public class LeaderHealthMonitor {
    
    private static final Logger log = LoggerFactory.getLogger(LeaderHealthMonitor.class);
    
    /**
     * How often leaders send heartbeats (10 seconds).
     */
    private static final long HEARTBEAT_INTERVAL_MS = 10_000;
    
    /**
     * How long to wait before considering leader dead (30 seconds).
     * This is 3x the heartbeat interval to avoid false positives.
     */
    private static final long FAILURE_THRESHOLD_MS = 30_000;
    
    private final String selfUrl;
    private volatile long lastHeartbeatTime;
    private volatile boolean leaderAppearsDead;
    private Thread heartbeatMonitorThread;
    private volatile boolean running;
    private volatile java.util.List<String> followerUrls = new java.util.ArrayList<>();
    private volatile java.util.function.IntSupplier epochSupplier;
    
    // Split-brain detection: Track heartbeat responses
    private volatile int successfulHeartbeats = 0;
    private volatile int totalValidators = 1; // Start with self
    private volatile int electorateSize = 1; // Voting members only (for quorum)
    private volatile Runnable demotionCallback;
    
    public LeaderHealthMonitor(String selfUrl) {
        this.selfUrl = selfUrl;
        this.lastHeartbeatTime = System.currentTimeMillis();
        this.leaderAppearsDead = false;
        this.running = false;
    }
    
    /**
     * Set callback for when leader should demote itself (split-brain detection).
     */
    public void setDemotionCallback(Runnable callback) {
        this.demotionCallback = callback;
    }
    
    /**
     * Record a heartbeat from the leader.
     * Called when follower receives heartbeat signal.
     */
    public void recordHeartbeat() {
        long now = System.currentTimeMillis();
        long timeSinceLastBeat = now - lastHeartbeatTime;
        
        lastHeartbeatTime = now;
        
        if (leaderAppearsDead) {
            log.info("💚 Leader is alive again (was considered dead)");
            leaderAppearsDead = false;
        } else {
            log.debug("💓 Heartbeat received ({}ms since last)", timeSinceLastBeat);
        }
    }
    
    /**
     * Check if the leader is healthy based on recent heartbeats.
     * 
     * @return true if leader sent heartbeat within threshold
     */
    public boolean isLeaderHealthy() {
        long timeSinceLastBeat = System.currentTimeMillis() - lastHeartbeatTime;
        return timeSinceLastBeat < FAILURE_THRESHOLD_MS;
    }
    
    /**
     * Check if leader appears to be dead.
     * This is a sticky flag that remains true until leader recovers.
     * 
     * @return true if leader has been unresponsive for too long
     */
    public boolean isLeaderDead() {
        return leaderAppearsDead;
    }
    
    /**
     * Get time in milliseconds since last heartbeat.
     */
    public long getTimeSinceLastHeartbeat() {
        return System.currentTimeMillis() - lastHeartbeatTime;
    }
    
    /**
     * Get the timestamp of the last heartbeat (for metrics).
     * @return The last heartbeat time in milliseconds since epoch
     */
    public long getLastHeartbeatTime() {
        return lastHeartbeatTime;
    }
    
    /**
     * Start monitoring thread (for followers).
     * This thread periodically checks if the leader is still alive.
     */
    public void startMonitoring() {
        if (running) {
            return;
        }
        
        running = true;
        lastHeartbeatTime = System.currentTimeMillis(); // Reset on start
        leaderAppearsDead = false;
        
        heartbeatMonitorThread = new Thread(() -> {
            log.info("❤️  Started leader health monitoring");
            
            while (running) {
                try {
                    Thread.sleep(5000); // Check every 5 seconds
                    
                    long timeSinceLastBeat = System.currentTimeMillis() - lastHeartbeatTime;
                    
                    if (timeSinceLastBeat > FAILURE_THRESHOLD_MS) {
                        if (!leaderAppearsDead) {
                            log.warn("💀 LEADER FAILURE DETECTED");
                            log.warn("   No heartbeat for {}ms (threshold: {}ms)", 
                                timeSinceLastBeat, FAILURE_THRESHOLD_MS);
                            leaderAppearsDead = true;
                        }
                    }
                    
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            
            log.info("❤️  Stopped leader health monitoring");
        }, "leader-health-monitor");
        
        heartbeatMonitorThread.setDaemon(true);
        heartbeatMonitorThread.start();
    }
    
    /**
     * Stop monitoring thread.
     */
    public void stopMonitoring() {
        running = false;
        if (heartbeatMonitorThread != null) {
            heartbeatMonitorThread.interrupt();
        }
    }
    
    /**
     * Send heartbeat to all followers (called by leader).
     * 
     * @param followerUrls List of follower validator URLs
     * @param currentEpoch Current epoch for validation
     */
    public void sendHeartbeatToFollowers(java.util.List<String> followerUrls, int currentEpoch, int electorateSize) {
        // Reset counter for this round (SPLIT-BRAIN DETECTION)
        successfulHeartbeats = 0;
        totalValidators = electorateSize; // ONLY voting members count for quorum!
        
        java.util.concurrent.CountDownLatch latch = 
            new java.util.concurrent.CountDownLatch(followerUrls.size());
        
        for (String followerUrl : followerUrls) {
            // Send asynchronously to avoid blocking
            new Thread(() -> {
                try {
                    URL url = new URL(followerUrl + "/v1/heartbeat");
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.setDoOutput(true);
                    conn.setConnectTimeout(2000);
                    conn.setReadTimeout(2000);
                    
                    String payload = String.format(
                        "{\"leaderUrl\":\"%s\",\"epoch\":%d,\"timestamp\":%d}",
                        selfUrl, currentEpoch, System.currentTimeMillis()
                    );
                    
                    conn.getOutputStream().write(payload.getBytes("UTF-8"));
                    
                    int responseCode = conn.getResponseCode();
                    if (responseCode == 200) {
                        synchronized (this) {
                            successfulHeartbeats++; // Count successful responses
                        }
                        log.debug("💓 Heartbeat ACK from {}", followerUrl);
                    } else {
                        log.debug("⚠️  Heartbeat to {} failed: HTTP {}", followerUrl, responseCode);
                    }
                    
                } catch (Exception e) {
                    log.debug("⚠️  Failed to send heartbeat to {}: {}", followerUrl, e.getMessage());
                } finally {
                    latch.countDown();
                }
            }, "heartbeat-sender-" + followerUrl.hashCode()).start();
        }
        
        // Wait for all responses (with timeout)
        try {
            latch.await(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        // Check quorum after all responses received
        checkQuorum();
    }
    
    /**
     * Check if leader has quorum (majority of validators responding).
     * 
     * SPLIT-BRAIN DETECTION: If leader can't reach majority, it should step down
     * to prevent dual-leader scenarios during network partitions.
     */
    private void checkQuorum() {
        int requiredQuorum = (totalValidators / 2) + 1; // Majority
        int responding = successfulHeartbeats + 1; // +1 for self
        
        if (responding < requiredQuorum) {
            log.warn("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            log.warn("🧠 SPLIT-BRAIN DETECTION: QUORUM LOST!");
            log.warn("   Total validators: {}", totalValidators);
            log.warn("   Required quorum: {}", requiredQuorum);
            log.warn("   Responding: {} (self + {} followers)", responding, successfulHeartbeats);
            log.warn("   Missing: {}", totalValidators - responding);
            log.warn("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            log.error("❌ LEADER MUST DEMOTE TO PREVENT SPLIT-BRAIN");
            
            // Trigger demotion callback
            if (demotionCallback != null) {
                try {
                    demotionCallback.run();
                } catch (Exception e) {
                    log.error("Failed to execute demotion callback", e);
                }
            } else {
                log.error("⚠️  No demotion callback configured!");
            }
        } else {
            log.debug("✅ Quorum maintained: {}/{} validators responding", responding, totalValidators);
        }
    }
    
    /**
     * Start heartbeat broadcast thread (for leaders).
     * Leaders continuously send heartbeats to all followers.
     */
    public void startHeartbeatBroadcast(java.util.List<String> followerUrls, 
                                       java.util.function.IntSupplier epochSupplier,
                                       int electorateSizeForQuorum) {
        if (running) {
            return;
        }
        
        this.followerUrls = new java.util.ArrayList<>(followerUrls);
        this.epochSupplier = epochSupplier;
        this.electorateSize = electorateSizeForQuorum;
        running = true;
        
        heartbeatMonitorThread = new Thread(() -> {
            log.info("💓 Started heartbeat broadcast (sending to {} followers)", this.followerUrls.size());
            log.info("🗳️  Electorate size (for quorum): {}", this.electorateSize);
            
            while (running) {
                try {
                    sendHeartbeatToFollowers(this.followerUrls, this.epochSupplier.getAsInt(), this.electorateSize);
                    Thread.sleep(HEARTBEAT_INTERVAL_MS);
                    
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            
            log.info("💓 Stopped heartbeat broadcast");
        }, "heartbeat-broadcaster");
        
        heartbeatMonitorThread.setDaemon(true);
        heartbeatMonitorThread.start();
    }
    
    /**
     * Update the list of followers to send heartbeats to.
     * Called when a new validator joins the network.
     * 
     * @param newFollowerUrls Updated list of follower URLs
     */
    public synchronized void updateFollowerList(java.util.List<String> newFollowerUrls) {
        this.followerUrls = new java.util.ArrayList<>(newFollowerUrls);
        log.debug("💓 Follower list updated: {} followers", newFollowerUrls.size());
    }
    
    /**
     * Update electorate size for split-brain quorum calculation.
     */
    public synchronized void updateElectorateSize(int newElectorateSize) {
        this.electorateSize = newElectorateSize;
        log.debug("🗳️  Electorate size updated: {}", newElectorateSize);
    }
    
    /**
     * Stop heartbeat broadcast thread.
     */
    public void stopHeartbeatBroadcast() {
        stopMonitoring(); // Reuse same stop logic
    }
}


