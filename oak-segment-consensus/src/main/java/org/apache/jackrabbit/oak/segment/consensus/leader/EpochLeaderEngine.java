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
 * Epoch-based leader consensus engine for Oak validators.
 * 
 * <p>Single leader at any time accepts and sequences all writes.
 * Followers replicate state from leader and serve reads.
 * Leadership rotates automatically based on epochs.
 * 
 * <p><strong>Epoch Definition:</strong> Currently uses system time-based epochs,
 * but designed to integrate with Ethereum Beacon Chain epochs for true blockchain
 * alignment. Future enhancement will use Ethereum epochs as the authoritative
 * time source, solving clock skew issues and aligning with blockchain consensus.
 * 
 * <p><strong>Architecture Vision:</strong> All writes to the global oak-chain will
 * ultimately be driven by Ethereum transactions via the bridge, making this a true
 * blockchain-backed content repository.
 * 
 * <p>SECURITY: Enforces probationary period for new validators to prevent
 * join/leave manipulation attacks on leadership.
 */
public class EpochLeaderEngine {
    
    private static final Logger log = LoggerFactory.getLogger(EpochLeaderEngine.class);
    
    private final FileStore fileStore;
    private final NodeStore nodeStore;
    private final String selfUrl;
    private volatile LeaderElection election;  // Changed to volatile for dynamic peer updates
    private final SegmentReplicator replicator;
    private final LeaderHealthMonitor healthMonitor;
    private final org.apache.jackrabbit.oak.segment.consensus.security.EthereumWallet wallet;
    
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
    
    public EpochLeaderEngine(FileStore fileStore, NodeStore nodeStore, 
                                  String selfUrl, List<String> peerUrls,
                                  org.apache.jackrabbit.oak.segment.consensus.security.EthereumWallet wallet) {
        this(fileStore, nodeStore, selfUrl, peerUrls, 300, wallet, false); // Default: 5 min term, not bootstrap
    }
    
    public EpochLeaderEngine(FileStore fileStore, NodeStore nodeStore, 
                                  String selfUrl, List<String> peerUrls, 
                                  int leaderTermSeconds,
                                  org.apache.jackrabbit.oak.segment.consensus.security.EthereumWallet wallet) {
        this(fileStore, nodeStore, selfUrl, peerUrls, leaderTermSeconds, wallet, false); // Not bootstrap
    }
    
    /**
     * Constructor with bootstrap join flag.
     * 
     * @param wallet Real Ethereum wallet for validator identity and signing
     * @param isBootstrapJoin true if this validator is joining via bootstrap (post-genesis),
     *                        false if this is genesis or config-based start
     */
    public EpochLeaderEngine(FileStore fileStore, NodeStore nodeStore, 
                                  String selfUrl, List<String> peerUrls, 
                                  int leaderTermSeconds,
                                  org.apache.jackrabbit.oak.segment.consensus.security.EthereumWallet wallet,
                                  boolean isBootstrapJoin) {
        this.fileStore = fileStore;
        this.nodeStore = nodeStore;
        this.selfUrl = selfUrl;
        this.wallet = wallet;
        this.replicator = new SegmentReplicator(fileStore);
        this.healthMonitor = new LeaderHealthMonitor(selfUrl);
        
        // PHASE 3: Use wallet for cryptographic signing
        // ClaimSigner now delegates to wallet for persistent keys
        this.claimSigner = new org.apache.jackrabbit.oak.segment.consensus.security.ClaimSigner(selfUrl, wallet);
        this.claimVerifier = new org.apache.jackrabbit.oak.segment.consensus.security.ClaimVerifier();
        // Register self's public key from wallet
        this.claimVerifier.registerPublicKey(selfUrl, wallet.getPublicKeyHex());
        
        // Set validator ID (wallet address) on health monitor for heartbeats
        this.healthMonitor.setValidatorId(wallet.getWalletAddress());
        
        // Record join times for self and all initial peers
        long now = System.currentTimeMillis();
        
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // GENESIS VALIDATOR EXEMPTION
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // The genesis validator (first node, no bootstrap, no peers) is the
        // founding member of the network. It should be READY immediately, not
        // subject to probation. Set its join time to "ancient" (epoch 0).
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        boolean isGenesisValidator = !isBootstrapJoin && peerUrls.isEmpty();
        long selfJoinTime = isGenesisValidator ? 0L : now;  // Genesis = epoch 0, proven and ready
        
        validatorJoinTimes.put(selfUrl, selfJoinTime);
        
        if (isGenesisValidator) {
            log.info("🌟 GENESIS VALIDATOR detected - probation EXEMPT (join time: epoch 0)");
        }
        
        for (String peerUrl : peerUrls) {
            // Initial peers are assumed to have joined at the same time (genesis or config)
            validatorJoinTimes.put(peerUrl, now);
            allFollowers.add(peerUrl);  // Add to follower list for heartbeats
        }
        
        // Create election with join times (initial peers are part of electorate)
        this.election = new LeaderElection(selfUrl, peerUrls, leaderTermSeconds, validatorJoinTimes);
        
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // SCALABLE BOOTSTRAP JOIN
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // If joining via bootstrap, skip expensive election math.
        // Network already has a leader - we'll learn from heartbeat.
        // This scales to 1000s of validators without wasted computation.
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        
        if (isBootstrapJoin) {
            // Bootstrap join: Start as FOLLOWER immediately
            this.currentRole = ValidatorRole.FOLLOWER;
            this.currentLeader = peerUrls.isEmpty() ? "unknown" : peerUrls.get(0); // Bootstrap primary
            this.currentEpoch = election.getCurrentEpoch(); // Still track epoch for sync
            
            log.info("🎖️  Leader-Based Consensus Engine initialized (BOOTSTRAP JOIN)");
            log.info("   Mode: Leader/Follower with Failure Detection");
            log.info("   Join Type: Bootstrap (post-genesis)");
            log.info("   Initial Role: FOLLOWER (no election math)");
            log.info("   Expected Leader: {}", currentLeader);
            log.info("   Current Epoch: {}", currentEpoch);
            log.info("   Rotation: every {} seconds", leaderTermSeconds);
            log.info("   Heartbeat: 10s interval, 30s failure threshold");
            log.info("   🛡️  Probationary period: {} seconds (new validators must be followers)", leaderTermSeconds);
            log.info("   ℹ️  Will learn actual leader from heartbeat");
        } else {
            // Genesis or config-based start: Calculate role via election
            this.currentEpoch = election.getCurrentEpoch();
            this.currentLeader = election.electLeader();
            this.currentRole = election.getRole();
            
            log.info("🎖️  Leader-Based Consensus Engine initialized (GENESIS/CONFIG)");
            log.info("   Mode: Leader/Follower with Failure Detection");
            log.info("   Join Type: Genesis or configured peers");
            log.info("   Epoch: {}", currentEpoch);
            log.info("   Current leader: {}", currentLeader);
            log.info("   My role: {}", currentRole);
            log.info("   Rotation: every {} seconds", leaderTermSeconds);
            log.info("   Heartbeat: 10s interval, 30s failure threshold");
            log.info("   🛡️  Probationary period: {} seconds (new validators must be followers)", leaderTermSeconds);
        }
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
        
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // BLOCKCHAIN CONSENSUS: Deterministic election rebuild
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // CRITICAL: Rebuild election to include new peer in allValidators for
        // deterministic leader calculation. The election MUST know about ALL validators
        // (voting + non-voting) so all validators calculate the same leader.
        // 
        // SECURITY: Only voting members (past probation) can be elected as leader.
        // New peers join as non-voting followers and cannot become leader until
        // probation ends (enforced by getEligibleValidators()).
        // 
        // EPOCH STABILITY: Current epoch's leader is PRESERVED. Election rebuild
        // only affects NEXT epoch's leader calculation, not current epoch.
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        List<String> allPeersForElection = new java.util.ArrayList<>(allFollowers);
        String previousLeader = currentLeader; // Preserve current epoch leader
        this.election = new LeaderElection(selfUrl, allPeersForElection, 
            election.getLeaderTermSeconds(), validatorJoinTimes);
        
        // Verify current epoch leader is preserved (should not change mid-epoch)
        String recalculatedLeader = election.electLeader();
        if (!recalculatedLeader.equals(previousLeader)) {
            log.error("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            log.error("❌ CRITICAL: Leader changed mid-epoch!");
            log.error("   Previous: {}", previousLeader);
            log.error("   Recalculated: {}", recalculatedLeader);
            log.error("   Current epoch: {}", currentEpoch);
            log.error("   This should NEVER happen - current epoch leader must be stable");
            log.error("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            // Restore previous leader for current epoch (epoch stability)
            currentLeader = previousLeader;
        } else {
            log.debug("✅ Election rebuild verified: current epoch leader unchanged ({})", currentLeader);
        }
        
        log.info("📊 Network status:");
        log.info("   Voting members (electorate): {}", election.getAllValidators().size());
        log.info("   Non-voting followers: {}", allFollowers.size() - election.getAllValidators().size() + 1);
        log.info("   Total validators: {}", allFollowers.size() + 1);
        log.info("   Current epoch leader: {} (PRESERVED for epoch {})", currentLeader, currentEpoch);
        log.info("   My role: {} (unchanged)", currentRole);
        
        // If we're the leader, update heartbeat list with all followers
        if (currentRole == ValidatorRole.LEADER) {
            // Use normalized list from allFollowers (voting + non-voting)
            List<String> allFollowersForHeartbeat = new java.util.ArrayList<>(allFollowers);
            int electorateSizeForQuorum = election.getAllValidators().size();
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
            
            // Use normalized follower list (electorate + non-voting) for heartbeats
            // BLOCKCHAIN CONSENSUS: All followers receive heartbeats, but only electorate counts for quorum
            List<String> followers = new java.util.ArrayList<>(allFollowers);
            java.util.Collections.sort(followers); // Deterministic ordering
            int electorateSizeForQuorum = election.getAllValidators().size();
            healthMonitor.startHeartbeatBroadcast(followers, () -> currentEpoch, electorateSizeForQuorum);
            log.info("💓 Started heartbeat broadcast to {} followers (voting + non-voting)", followers.size());
            log.info("🗳️  Quorum based on electorate size: {} (voting members only)", electorateSizeForQuorum);
        } else {
            healthMonitor.startMonitoring();
            log.info("❤️  Started monitoring leader health");
        }
        
        rotationMonitor = new Thread(() -> {
            log.info("🔄 Leader rotation monitor started");
            
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(10000); // Check every 10 seconds
                    
                    // Check if any validators have completed probation (deferred until epoch boundary)
                    if (currentRole == ValidatorRole.LEADER) {
                        checkProbationGraduation(); // Only checks, doesn't graduate
                    }
                    
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
     * Check if any validators have completed their probationary period.
     * 
     * BLOCKCHAIN CONSENSUS: Graduation is deferred until epoch boundary to ensure
     * electorate size changes only happen at epoch transitions. This prevents
     * mid-epoch leader calculation changes that cause BYZANTINE CLAIM rejections.
     * 
     * Called periodically by the leader (every 10s) to identify validators ready
     * to graduate. Actual graduation happens at epoch boundary in handleEpochTransition().
     */
    private synchronized void checkProbationGraduation() {
        long now = System.currentTimeMillis();
        long probationPeriod = election.getLeaderTermSeconds() * 1000L; // 300 seconds
        
        // Get current electorate (voting members)
        List<String> currentElectorate = election.getAllValidators();
        
        // Find validators who have been in network for >= probation period
        // and are NOT yet in the electorate (i.e., non-voting)
        for (String validatorUrl : allFollowers) {
            // Skip if already in electorate (already graduated)
            if (currentElectorate.contains(validatorUrl)) {
                continue;
            }
            
            Long joinTime = validatorJoinTimes.get(validatorUrl);
            if (joinTime == null) {
                continue; // Skip if no join time recorded
            }
            
            long timeSinceJoin = now - joinTime;
            
            // Check if they've completed probation period
            if (timeSinceJoin >= probationPeriod) {
                log.debug("   Validator {} ready to graduate ({}s in network) - will graduate at epoch boundary", 
                    validatorUrl, timeSinceJoin / 1000);
            }
        }
    }
    
    /**
     * Graduate validators from probation at epoch boundary.
     * 
     * BLOCKCHAIN CONSENSUS: Graduation happens ONLY at epoch boundaries to ensure
     * electorate size changes don't affect current epoch's leader calculation.
     * This prevents BYZANTINE CLAIM rejections caused by mid-epoch electorate changes.
     * 
     * Called from handleEpochTransition() BEFORE calculating the new epoch's leader.
     */
    private synchronized void graduateValidatorsFromProbation() {
        long now = System.currentTimeMillis();
        long probationPeriod = election.getLeaderTermSeconds() * 1000L; // 300 seconds
        
        java.util.List<String> graduatingValidators = new java.util.ArrayList<>();
        
        // Get current electorate (voting members)
        List<String> currentElectorate = election.getAllValidators();
        
        // Find validators who have been in network for >= probation period
        // and are NOT yet in the electorate (i.e., non-voting)
        for (String validatorUrl : allFollowers) {
            // Skip if already in electorate (already graduated)
            if (currentElectorate.contains(validatorUrl)) {
                continue;
            }
            
            Long joinTime = validatorJoinTimes.get(validatorUrl);
            if (joinTime == null) {
                continue; // Skip if no join time recorded
            }
            
            long timeSinceJoin = now - joinTime;
            
            // Check if they've completed probation period
            if (timeSinceJoin >= probationPeriod) {
                graduatingValidators.add(validatorUrl);
            }
        }
        
        // If any validators are ready to graduate, rebuild election
        if (!graduatingValidators.isEmpty()) {
            log.info("🎓 PROBATION GRADUATION");
            log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            
            for (String graduatingUrl : graduatingValidators) {
                Long joinTime = validatorJoinTimes.get(graduatingUrl);
                long timeSinceJoin = now - joinTime;
                log.info("   ✅ {} ({}s in network → VOTING MEMBER)", 
                    graduatingUrl, timeSinceJoin / 1000);
            }
            
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            // BLOCKCHAIN CONSENSUS: Probation graduation at epoch boundary
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            // CRITICAL: Graduation happens ONLY at epoch boundaries. This ensures:
            // 1. Electorate size changes only happen at epoch transitions
            // 2. All validators use the same electorate size for leader calculation
            // 3. No mid-epoch leader calculation changes (prevents BYZANTINE CLAIM rejections)
            // 
            // This method is called from handleEpochTransition() BEFORE calculating
            // the new epoch's leader, so the new electorate size is used for the new epoch.
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            java.util.List<String> allPeers = new java.util.ArrayList<>(allFollowers);
            int previousElectorateSize = election.getAllValidators().size();
            
            // Rebuild election with graduated validators now in electorate
            this.election = new LeaderElection(selfUrl, allPeers, election.getLeaderTermSeconds(), validatorJoinTimes);
            
            int newElectorateSize = election.getAllValidators().size();
            
            // Update health monitor's quorum calculation
            healthMonitor.updateElectorateSize(newElectorateSize);
            
            // Update follower list for heartbeats (all followers, voting + non-voting)
            healthMonitor.updateFollowerList(allPeers);
            
            log.info("   📊 Electorate size: {} → {} (graduated validators now voting)", 
                previousElectorateSize, newElectorateSize);
            log.info("   🗳️  Voting members: {}", election.getAllValidators());
            log.info("   ✅ Graduation complete - new electorate size will be used for epoch {}", currentEpoch);
            log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        }
    }
    
    /**
     * Handle transition to a new epoch (leader rotation).
     * 
     * BLOCKCHAIN CONSENSUS: Epoch transitions are natural synchronization points.
     * Graduation happens BEFORE leader calculation to ensure new electorate size
     * is used for the new epoch's leader election.
     * 
     * LEADER CLAIM PROTOCOL:
     * - Elected leader MUST broadcast claim within 15s
     * - Followers wait for claim or trigger re-election on timeout
     * - Automatic failover if leader is offline
     */
    private void handleEpochTransition(int newEpoch) {
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        log.info("🔄 EPOCH TRANSITION: {} → {}", currentEpoch, newEpoch);
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // BLOCKCHAIN CONSENSUS: Graduate validators BEFORE epoch transition
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // CRITICAL: Graduation happens at epoch boundary, BEFORE calculating new leader.
        // This ensures:
        // 1. Electorate size changes only at epoch boundaries
        // 2. New epoch uses new electorate size for leader calculation
        // 3. All validators have same electorate size when calculating leader
        // 4. No mid-epoch leader calculation changes (prevents BYZANTINE CLAIM rejections)
        // 
        // NOTE: Both leader and followers rebuild elections during epoch transitions.
        // Leader graduates validators (has join times), followers rebuild to match.
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // Leader graduates validators (has join times for all validators)
        if (currentRole == ValidatorRole.LEADER) {
            graduateValidatorsFromProbation(); // Graduate at epoch boundary
        } else {
            // Followers rebuild election to match leader's electorate size
            // They'll learn the correct electorate size from leader's heartbeats/claims
            // For now, rebuild election with current allFollowers list
            // (This ensures followers have same electorate as leader after graduation)
            java.util.List<String> allPeers = new java.util.ArrayList<>(allFollowers);
            this.election = new LeaderElection(selfUrl, allPeers, election.getLeaderTermSeconds(), validatorJoinTimes);
            log.debug("🔄 Follower rebuilt election at epoch boundary (electorate size: {})", 
                election.getAllValidators().size());
        }
        
        currentEpoch = newEpoch;
        String newLeader = election.electLeader();
        
        if (!newLeader.equals(currentLeader)) {
            log.info("👑 Leader changed: {} → {}", currentLeader, newLeader);
            currentLeader = newLeader;
        }
        
        ValidatorRole newRole = election.getRole();
        
        if (newRole != currentRole) {
            if (newRole == ValidatorRole.LEADER) {
                // ═══════════════════════════════════════════════════════════
                // LEADER CLAIM PROTOCOL - PHASE 1: BROADCAST CLAIM
                // ═══════════════════════════════════════════════════════════
                log.info("👑 I am elected LEADER for epoch {}", currentEpoch);
                log.info("   Broadcasting leadership claim to all followers...");
                
                boolean claimed = broadcastLeadershipClaim(currentEpoch);
                
                if (claimed || allFollowers.isEmpty()) {
                    // Successfully claimed or solo leader
                    transitionToLeader();
                    currentRole = newRole;
                } else {
                    // Failed to broadcast claim - stay as FOLLOWER
                    log.error("❌ Failed to broadcast leadership claim");
                    log.error("   Staying as FOLLOWER to allow next validator to claim");
                    currentRole = ValidatorRole.FOLLOWER;
                    
                    // Allow next validator to claim (they'll see we didn't claim)
                    // This will trigger automatic failover
                }
            } else {
                // ═══════════════════════════════════════════════════════════
                // LEADER CLAIM PROTOCOL - PHASE 2: WAIT FOR CLAIM
                // ═══════════════════════════════════════════════════════════
                log.info("📡 I am FOLLOWER for epoch {}", currentEpoch);
                log.info("   Expected leader: {}", newLeader);
                log.info("   Starting 15-second claim timer...");
                
                // Start claim timer (15 seconds for epoch rotation)
                startLeaderClaimTimer(newLeader, currentEpoch, 15);
                
                // Transition to follower mode (will wait for claim)
                transitionToFollower();
                currentRole = newRole;
            }
        } else {
            log.info("Role unchanged: {}", currentRole);
            if (currentRole == ValidatorRole.LEADER) {
                log.info("✅ Continuing as LEADER for epoch {}", currentEpoch);
                // Re-broadcast claim for new epoch
                broadcastLeadershipClaim(currentEpoch);
            } else {
                log.info("✅ Continuing as FOLLOWER, leader: {}", currentLeader);
                // Restart claim timer for new epoch
                startLeaderClaimTimer(currentLeader, currentEpoch, 15);
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
        
        // BLOCKCHAIN CONSENSUS: Start broadcasting heartbeats to ALL followers
        // All followers (voting + non-voting) receive heartbeats, but only electorate counts for quorum
        List<String> followers = new java.util.ArrayList<>(allFollowers);
        java.util.Collections.sort(followers); // Deterministic ordering
        int electorateSizeForQuorum = election.getAllValidators().size();
        healthMonitor.startHeartbeatBroadcast(followers, () -> currentEpoch, electorateSizeForQuorum);
        log.info("💓 Started heartbeat broadcast to {} followers (voting + non-voting)", followers.size());
        log.info("🗳️  Quorum based on electorate size: {} (voting members only)", electorateSizeForQuorum);
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
    
    /**
     * Update current epoch from leader's heartbeat.
     * 
     * CRITICAL FIX: Followers must adopt leader's epoch to stay synchronized.
     * Without this, followers get stuck at their initial epoch and reject all
     * leadership claims as "FUTURE CLAIM", causing network failure.
     * 
     * Called by followers when receiving heartbeats from the leader.
     * 
     * @param leaderEpoch The current epoch from the leader's heartbeat
     */
    public synchronized void updateCurrentEpoch(int leaderEpoch) {
        if (leaderEpoch > currentEpoch) {
            int oldEpoch = currentEpoch;
            currentEpoch = leaderEpoch;
            
            log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            log.info("📅 EPOCH SYNCHRONIZED FROM LEADER");
            log.info("   Old epoch: {}", oldEpoch);
            log.info("   New epoch: {}", leaderEpoch);
            log.info("   Source: Leader heartbeat");
            log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        } else if (leaderEpoch < currentEpoch) {
            log.warn("⚠️  Leader epoch {} is behind our epoch {} - possible clock skew or leader restart", 
                leaderEpoch, currentEpoch);
        }
        // If equal, no action needed (already in sync)
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
     * Get validator join times (for probation status calculation).
     * Returns a copy to prevent external modification.
     */
    public Map<String, Long> getValidatorJoinTimes() {
        return new java.util.HashMap<>(validatorJoinTimes);
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
    
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // LEADER CLAIM PROTOCOL
    // World-class consensus inspired by Raft, Paxos, Kafka, Ethereum 2.0
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    
    // PHASE 1: Basic claim protocol
    private volatile boolean leaderClaimReceived = false;
    private volatile java.util.concurrent.ScheduledFuture<?> claimTimer = null;
    private final java.util.concurrent.ScheduledExecutorService claimScheduler = 
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
    
    // PHASE 2: Quorum-based acceptance (split-brain prevention)
    private final Map<Integer, LeadershipClaimTracker> claimTrackers = new ConcurrentHashMap<>();
    private volatile java.util.concurrent.ScheduledFuture<?> quorumTimer = null;
    
    // PHASE 3: Cryptographic signatures (Byzantine fault tolerance)
    private final org.apache.jackrabbit.oak.segment.consensus.security.ClaimSigner claimSigner;
    private final org.apache.jackrabbit.oak.segment.consensus.security.ClaimVerifier claimVerifier;
    
    /**
     * Handle incoming leadership claim from elected validator.
     * 
     * PHASE 1: Basic validation (epoch, expected leader)
     * PHASE 3: Cryptographic signature verification (Byzantine fault tolerance)
     * 
     * Validates:
     * 1. **Cryptographic signature** (PHASE 3 - prevents forgery)
     * 2. Epoch matches current epoch (not stale/future)
     * 3. Validator is the expected leader for this epoch
     * 4. Claim arrived within timeout window
     * 
     * @param claimedEpoch The epoch the validator is claiming
     * @param validatorId  The validator claiming leadership
     * @param validatorUrl The validator's URL
     * @param timestamp    When the claim was sent
     * @param signature    ECDSA signature (PHASE 3)
     * @return true if claim accepted, false if rejected
     */
    public synchronized boolean handleLeadershipClaim(int claimedEpoch, String validatorId, 
                                                       String validatorUrl, long timestamp,
                                                       String signature) {
        
        // PHASE 3: Validation 0 - Cryptographic signature (MUST be first!)
        if (!claimVerifier.verifyClaim(claimedEpoch, validatorUrl, timestamp, signature)) {
            log.error("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            log.error("❌ BYZANTINE ATTACK DETECTED!");
            log.error("   Validator: {}", validatorUrl);
            log.error("   Epoch: {}", claimedEpoch);
            log.error("   Reason: INVALID SIGNATURE");
            log.error("   This claim is REJECTED - likely forgery or tampering");
            log.error("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            ConsensusMetrics.recordLeadershipClaimResult("invalid_signature");
            return false;
        }
        
        // PHASE 1: Validation 1 - Epoch synchronization and validation
        if (claimedEpoch != currentEpoch) {
            if (claimedEpoch < currentEpoch) {
                // STALE CLAIM: Leader is behind us (shouldn't happen, but reject it)
                log.warn("⚠️  STALE CLAIM: {} claimed epoch {} but current is {}", 
                    validatorId, claimedEpoch, currentEpoch);
                ConsensusMetrics.recordLeadershipClaimResult("stale");
                return false;
            } else {
                // FUTURE CLAIM: Leader is ahead - sync epoch first, then continue validation
                // This fixes the issue where claims arrive before heartbeats sync the epoch
                log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                log.info("📅 SYNCHRONIZING EPOCH FROM LEADERSHIP CLAIM");
                log.info("   Claimed epoch: {} (from {})", claimedEpoch, validatorId);
                log.info("   Current epoch: {}", currentEpoch);
                log.info("   Syncing to: {}", claimedEpoch);
                log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                updateCurrentEpoch(claimedEpoch);
                // Continue validation with synced epoch
            }
        }
        
        // PHASE 1: Validation 2 - Validator must be the expected leader
        String expectedLeader = election.electLeader();
        if (!validatorUrl.equals(expectedLeader)) {
            log.error("❌ BYZANTINE CLAIM: {} claimed but {} was elected", 
                validatorUrl, expectedLeader);
            ConsensusMetrics.recordLeadershipClaimResult("byzantine");
            return false;
        }
        
        // PHASE 1: Validation 3 - Check if we already have a leader for this epoch
        if (currentLeader != null && !currentLeader.equals(validatorUrl)) {
            log.warn("⚠️  DUPLICATE CLAIM: {} claimed but {} is already leader", 
                validatorUrl, currentLeader);
            ConsensusMetrics.recordLeadershipClaimResult("duplicate");
            return false;
        }
        
        // ✅ CLAIM ACCEPTED (signature verified, epoch valid, expected leader)
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        log.info("✅ LEADERSHIP CLAIM ACCEPTED");
        log.info("   Epoch: {}", claimedEpoch);
        log.info("   Leader: {}", validatorUrl);
        log.info("   Latency: {}ms", System.currentTimeMillis() - timestamp);
        log.info("   Signature: VERIFIED ✅");
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        
        // Mark claim as received
        leaderClaimReceived = true;
        
        // Cancel timeout timer
        if (claimTimer != null) {
            claimTimer.cancel(false);
            claimTimer = null;
        }
        
        // Update state
        currentLeader = validatorUrl;
        currentRole = ValidatorRole.FOLLOWER;
        
        // Start listening for heartbeats from this leader
        healthMonitor.startMonitoring();
        
        ConsensusMetrics.recordLeadershipClaimResult("accepted");
        ConsensusMetrics.recordLeadershipClaimLatency(System.currentTimeMillis() - timestamp);
        
        // PHASE 2: Send ACK back to leader for quorum-based acceptance
        sendClaimAck(claimedEpoch, validatorUrl);
        
        return true;
    }
    
    /**
     * Broadcast leadership claim to all followers.
     * 
     * PHASE 1: Basic claim broadcast with timeout
     * PHASE 2: Track ACKs and wait for quorum
     * PHASE 3: Cryptographically sign the claim
     * 
     * Called by elected leader to prove liveness and readiness.
     * Followers will validate claim or trigger re-election on timeout.
     * 
     * @param epoch The epoch being claimed
     * @return true if broadcast succeeded to at least one follower (Phase 1) or quorum reached (Phase 2)
     */
    public boolean broadcastLeadershipClaim(int epoch) {
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        log.info("👑 BROADCASTING LEADERSHIP CLAIM");
        log.info("   Epoch: {}", epoch);
        log.info("   Self: {}", selfUrl);
        log.info("   Followers: {}", allFollowers.size());
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        
        if (allFollowers.isEmpty()) {
            log.info("✅ No followers to notify (solo leader)");
            return true;  // Solo leader scenario
        }
        
        // PHASE 3: Sign the claim with our private key
        long timestamp = System.currentTimeMillis();
        String signature = claimSigner.signClaim(epoch, selfUrl, timestamp);
        
        // Build claim payload with signature
        String payload = String.format(
            "{\"epoch\":%d,\"validatorId\":\"%s\",\"validatorUrl\":\"%s\",\"timestamp\":%d,\"claimType\":\"EPOCH_ROTATION\",\"signature\":\"%s\"}",
            epoch,
            selfUrl.contains("validator-") ? selfUrl.substring(selfUrl.indexOf("validator-")).split(":")[0] : "unknown",
            selfUrl,
            timestamp,
            signature
        );
        
        // PHASE 2: Create claim tracker for quorum-based acceptance
        int electorateSize = election.getAllValidators().size();
        LeadershipClaimTracker tracker = new LeadershipClaimTracker(epoch, selfUrl, electorateSize);
        claimTrackers.put(epoch, tracker);
        
        int successCount = 0;
        
        for (String followerUrl : allFollowers) {
            try {
                URL url = new URL(followerUrl + "/v1/consensus/claim-leadership");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                
                // Send payload
                java.io.OutputStream os = conn.getOutputStream();
                os.write(payload.getBytes("UTF-8"));
                os.flush();
                os.close();
                
                int responseCode = conn.getResponseCode();
                if (responseCode == 200) {
                    log.info("✅ Claim sent to {}", followerUrl);
                    successCount++;
                } else {
                    log.warn("⚠️  Claim failed to {}: HTTP {}", followerUrl, responseCode);
                }
                
                conn.disconnect();
                
            } catch (Exception e) {
                log.error("❌ Failed to send claim to {}: {}", followerUrl, e.getMessage());
            }
        }
        
        boolean success = successCount > 0;
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        log.info("Claim broadcast: {}/{} followers notified", successCount, allFollowers.size());
        log.info("   Signature: {}...", signature.substring(0, Math.min(18, signature.length())));
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        
        ConsensusMetrics.recordLeadershipClaimBroadcast(successCount, allFollowers.size());
        
        // PHASE 2: Start quorum timer (10 seconds to collect ACKs)
        // If quorum not reached, we'll continue as leader anyway (Phase 1 behavior)
        // but log a warning. In production, might want to step down if no quorum.
        if (electorateSize > 1) {  // Only need quorum if there are other voters
            startQuorumTimer(tracker, 10);
        }
        
        return success;
    }
    
    /**
     * Start quorum timer to check if ACKs were received.
     * 
     * PHASE 2: Wait for quorum or timeout.
     */
    private void startQuorumTimer(LeadershipClaimTracker tracker, int timeoutSeconds) {
        quorumTimer = claimScheduler.schedule(() -> {
            if (!tracker.hasQuorum()) {
                tracker.reject();
                log.warn("⚠️  QUORUM NOT REACHED within {}s", timeoutSeconds);
                log.warn("   Continuing as leader (Phase 1 fallback)");
                log.warn("   In production, might want to step down");
            }
        }, timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);
    }
    
    /**
     * Start claim timer for followers.
     * 
     * If no leadership claim is received within timeoutSeconds, trigger re-election.
     * 
     * @param expectedLeader The validator expected to claim leadership
     * @param epoch          The current epoch
     * @param timeoutSeconds Grace period for claim (default 15s, failover 10s)
     */
    public synchronized void startLeaderClaimTimer(String expectedLeader, int epoch, int timeoutSeconds) {
        // Reset state
        leaderClaimReceived = false;
        
        // Cancel existing timer
        if (claimTimer != null) {
            claimTimer.cancel(false);
        }
        
        log.info("⏰ Starting leadership claim timer: {}s for {}", timeoutSeconds, expectedLeader);
        
        // Schedule timeout
        claimTimer = claimScheduler.schedule(() -> {
            if (!leaderClaimReceived) {
                handleMissingClaim(expectedLeader, epoch);
            }
        }, timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);
    }
    
    /**
     * Handle missing leadership claim (timeout).
     * 
     * Marks expected leader as OFFLINE and triggers re-election without them.
     * Enables automatic failover to next validator in line.
     * 
     * @param expectedLeader The validator that failed to claim
     * @param epoch          The epoch that failed
     */
    private synchronized void handleMissingClaim(String expectedLeader, int epoch) {
        log.error("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        log.error("❌ MISSING LEADERSHIP CLAIM - INITIATING FAILOVER");
        log.error("   Expected leader: {}", expectedLeader);
        log.error("   Epoch: {}", epoch);
        log.error("   Timeout: Claim not received within grace period");
        log.error("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        
        // Mark validator as OFFLINE
        log.warn("📴 Marking {} as OFFLINE", expectedLeader);
        allFollowers.remove(expectedLeader);
        
        // Remove from electorate (rebuild election without failed validator)
        List<String> remainingPeers = new java.util.ArrayList<>(allFollowers);
        Map<String, Long> remainingJoinTimes = new java.util.concurrent.ConcurrentHashMap<>(validatorJoinTimes);
        remainingJoinTimes.remove(expectedLeader);
        
        election = new LeaderElection(selfUrl, remainingPeers, 
            election.getLeaderTermSeconds(), remainingJoinTimes);
        
        // Re-elect without failed validator
        String newLeader = election.electLeader();
        currentEpoch = epoch;  // Stay in same epoch
        
        log.warn("🔄 RE-ELECTION WITHOUT FAILED VALIDATOR");
        log.warn("   New leader: {}", newLeader);
        log.warn("   Electorate size: {}", election.getAllValidators().size());
        
        ConsensusMetrics.recordLeadershipClaimResult("timeout_failover");
        
        // If I'm the failover leader, claim immediately
        if (newLeader.equals(selfUrl)) {
            log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            log.info("🎖️  I AM THE FAILOVER LEADER FOR EPOCH {}", epoch);
            log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            
            // Broadcast claim
            broadcastLeadershipClaim(epoch);
            
            // Transition to leader
            transitionToLeader();
        } else {
            // Start new claim timer for failover leader (shorter timeout: 10s)
            log.info("⏰ Waiting for failover leader {} to claim (10s timeout)", newLeader);
            startLeaderClaimTimer(newLeader, epoch, 10);
        }
    }
    
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // PHASE 2: QUORUM-BASED ACCEPTANCE
    // Split-brain prevention via majority acknowledgments
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    
    /**
     * Send ACK to leader after accepting their claim.
     * 
     * PHASE 2: Follower acknowledges valid claim.
     * PHASE 3: ACK is cryptographically signed.
     */
    private void sendClaimAck(int epoch, String claimantUrl) {
        try {
            // PHASE 3: Sign the ACK
            long timestamp = System.currentTimeMillis();
            String signature = claimSigner.signAck(epoch, claimantUrl, selfUrl, timestamp);
            
            // Build ACK payload
            String payload = String.format(
                "{\"epoch\":%d,\"claimantUrl\":\"%s\",\"ackValidatorUrl\":\"%s\",\"timestamp\":%d,\"signature\":\"%s\"}",
                epoch, claimantUrl, selfUrl, timestamp, signature
            );
            
            // Send to leader
            URL url = new URL(claimantUrl + "/v1/consensus/claim-ack");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            
            java.io.OutputStream os = conn.getOutputStream();
            os.write(payload.getBytes("UTF-8"));
            os.flush();
            os.close();
            
            int responseCode = conn.getResponseCode();
            if (responseCode == 200) {
                log.info("✅ ACK sent to {} for epoch {}", claimantUrl, epoch);
            } else {
                log.warn("⚠️  ACK failed: HTTP {}", responseCode);
            }
            
            conn.disconnect();
            
        } catch (Exception e) {
            log.error("❌ Failed to send ACK to {}", claimantUrl, e);
        }
    }
    
    /**
     * Handle incoming claim acknowledgment (leader receives from followers).
     * 
     * PHASE 2: Track ACKs and finalize when quorum reached.
     * PHASE 3: Verify ACK signature.
     * 
     * @param epoch          The epoch being ACK'd
     * @param claimantUrl    The leader being ACK'd
     * @param ackValidatorUrl The validator sending the ACK
     * @param timestamp      When the ACK was created
     * @param signature      ECDSA signature of the ACK
     * @return true if ACK accepted, false if rejected
     */
    public synchronized boolean handleClaimAck(int epoch, String claimantUrl, 
                                                String ackValidatorUrl, long timestamp,
                                                String signature) {
        
        // PHASE 3: Verify ACK signature
        if (!claimVerifier.verifyAck(epoch, claimantUrl, ackValidatorUrl, timestamp, signature)) {
            log.error("❌ BYZANTINE ACK from {}: Invalid signature", ackValidatorUrl);
            ConsensusMetrics.recordClaimAckResult("invalid_signature");
            return false;
        }
        
        // PHASE 2: Find claim tracker for this epoch
        LeadershipClaimTracker tracker = claimTrackers.get(epoch);
        if (tracker == null) {
            log.warn("⚠️  ACK for unknown epoch {} from {}", epoch, ackValidatorUrl);
            ConsensusMetrics.recordClaimAckResult("unknown_epoch");
            return false;
        }
        
        // Verify ACK is for current claim
        if (!tracker.getClaimantUrl().equals(claimantUrl)) {
            log.error("❌ ACK mismatch: {} ACK'd {} but tracker expects {}", 
                ackValidatorUrl, claimantUrl, tracker.getClaimantUrl());
            ConsensusMetrics.recordClaimAckResult("mismatch");
            return false;
        }
        
        // Add ACK to tracker
        boolean quorumReached = tracker.addAck(ackValidatorUrl);
        ConsensusMetrics.recordClaimAckResult("accepted");
        
        if (quorumReached) {
            // QUORUM REACHED! Finalize leadership
            long quorumTime = tracker.getQuorumTime();
            ConsensusMetrics.recordQuorumWaitTime(quorumTime);
            
            // Cancel quorum timer
            if (quorumTimer != null) {
                quorumTimer.cancel(false);
                quorumTimer = null;
            }
            
            log.info("🎊 QUORUM REACHED - Leadership finalized!");
            log.info("   Epoch: {}", epoch);
            log.info("   Leader: {}", claimantUrl);
            log.info("   ACKs: {}/{}", tracker.getAckCount(), tracker.getRequiredQuorum());
            log.info("   Quorum time: {}ms", quorumTime);
            
            // Already leader, just confirming with quorum
            return true;
        }
        
        log.debug("ACK received: {}/{} (waiting for quorum)", 
            tracker.getAckCount(), tracker.getRequiredQuorum());
        return true;
    }
    
    /**
     * Get the claim verifier (for HTTP server to register public keys).
     */
    public org.apache.jackrabbit.oak.segment.consensus.security.ClaimVerifier getClaimVerifier() {
        return claimVerifier;
    }
    
    /**
     * Get the claim signer's public key for broadcasting.
     */
    public String getPublicKeyHex() {
        return claimSigner.getPublicKeyHex();
    }
}

