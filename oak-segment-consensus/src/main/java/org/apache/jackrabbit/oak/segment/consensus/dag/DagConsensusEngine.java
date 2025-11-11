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
package org.apache.jackrabbit.oak.segment.consensus.dag;

import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Distributed DAG Consensus Engine.
 * 
 * <p>Unlike traditional blockchain's linear chain, this implements a DAG-based
 * consensus model inspired by Git, IPFS, and Avalanche:
 * 
 * <ul>
 *   <li>Multiple parallel HEADs can exist (like Git branches)</li>
 *   <li>Non-conflicting writes proceed in parallel</li>
 *   <li>Validators vote on merge proposals to consolidate HEADs</li>
 *   <li>Eventually consistent through periodic merges</li>
 * </ul>
 * 
 * <h2>Architecture</h2>
 * <pre>
 * Git/Oak DAG              vs.     Blockchain (linear)
 * 
 *     H1   H2                        H1
 *      \  /                           |
 *       M    ← merge                  H2
 *       |                             |
 *      H3                            H3
 * 
 * Multiple heads OK!          Single canonical chain
 * </pre>
 * 
 * <h2>Consensus Process</h2>
 * <ol>
 *   <li>Validators write to their own HEADs independently</li>
 *   <li>Periodically, a validator proposes a merge</li>
 *   <li>Other validators check for conflicts</li>
 *   <li>If no conflicts: vote ACCEPT, perform merge</li>
 *   <li>If conflicts: vote REJECT, wait for manual resolution</li>
 * </ol>
 */
public class DagConsensusEngine {
    
    private static final Logger log = LoggerFactory.getLogger(DagConsensusEngine.class);
    
    private static final double CONSENSUS_THRESHOLD = 0.67; // 2/3+ majority
    
    private final FileStore fileStore;
    private final String selfUrl;
    private final List<String> peerUrls;
    
    // Track all known HEADs in the network (like Git remotes)
    private final Map<String, DagHead> knownHeads = new ConcurrentHashMap<>();
    
    // My current HEAD
    private DagHead myHead;
    
    public DagConsensusEngine(FileStore fileStore, String selfUrl, List<String> peerUrls) {
        this.fileStore = fileStore;
        this.selfUrl = selfUrl;
        this.peerUrls = peerUrls;
        
        // Initialize my HEAD
        String currentRecordId = fileStore.getHead().getRecordId().toString10();
        this.myHead = new DagHead(currentRecordId, selfUrl);
        this.myHead.setDepth(0); // Genesis
        
        knownHeads.put(selfUrl, myHead);
        
        log.info("🌳 DAG Consensus Engine initialized");
        log.info("   Mode: Distributed DAG (like Git)");
        log.info("   My HEAD: {}", myHead);
        log.info("   Peers: {}", peerUrls.size());
    }
    
    /**
     * Propose a write on my local HEAD.
     * Unlike blockchain, this doesn't require immediate consensus.
     * Write happens locally, then broadcasts HEAD update to peers.
     */
    public void proposeWrite(String newRecordId, String commitMessage) {
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        log.info("📝 LOCAL WRITE (DAG mode - no immediate consensus required)");
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        
        // Update my HEAD
        DagHead newHead = new DagHead(newRecordId, selfUrl);
        newHead.addParent(myHead.getRecordId());
        newHead.setDepth(myHead.getDepth() + 1);
        newHead.setCommitMessage(commitMessage);
        
        myHead = newHead;
        knownHeads.put(selfUrl, myHead);
        
        log.info("✅ Local write completed");
        log.info("   Previous HEAD: {}", myHead.getParentIds().get(0).substring(0, 8) + "...");
        log.info("   New HEAD: {}", myHead.getRecordId().substring(0, 8) + "...");
        log.info("   Depth: {}", myHead.getDepth());
        
        // Broadcast my new HEAD to peers
        broadcastHeadUpdate();
    }
    
    /**
     * Propose a merge of multiple HEADs.
     * This requires consensus from other validators.
     */
    public boolean proposeMerge(List<String> headIds, String mergeResultRecordId) {
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        log.info("🔀 PROPOSING MERGE");
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        log.info("   Merging {} HEADs", headIds.size());
        for (String headId : headIds) {
            log.info("     - {}", headId.substring(0, 16) + "...");
        }
        
        MergeProposal proposal = new MergeProposal(selfUrl, headIds, mergeResultRecordId);
        
        // Determine merge strategy
        if (headIds.size() == 1) {
            proposal.setMergeStrategy("fast-forward");
            log.info("   Strategy: FAST-FORWARD (single parent)");
        } else {
            // Check for conflicts
            boolean hasConflicts = checkMergeConflicts(headIds);
            proposal.setHasConflicts(hasConflicts);
            
            if (hasConflicts) {
                proposal.setMergeStrategy("manual");
                log.warn("   Strategy: MANUAL (conflicts detected!)");
                log.warn("   ❌ Merge requires conflict resolution");
                return false;
            } else {
                proposal.setMergeStrategy("auto");
                log.info("   Strategy: AUTO (no conflicts)");
            }
        }
        
        // Broadcast merge proposal to peers for voting
        // (Simplified for now - just log)
        log.info("✅ Merge proposal created: {}", proposal);
        
        // TODO: Implement actual voting mechanism
        // For now, assume consensus and perform merge locally
        performMerge(proposal);
        
        return true;
    }
    
    /**
     * Check if merging these HEADs would cause conflicts.
     */
    private boolean checkMergeConflicts(List<String> headIds) {
        // Simplified conflict check
        // In production, would use ConflictDetector to compare actual node states
        log.info("   🔍 Checking for conflicts between {} HEADs...", headIds.size());
        
        // For now, assume no conflicts
        // Real implementation would:
        // 1. Find common ancestor of all HEADs
        // 2. Check if same paths were modified
        // 3. Return true if conflicts found
        
        return false; // No conflicts
    }
    
    /**
     * Perform the merge locally after consensus.
     */
    private void performMerge(MergeProposal proposal) {
        log.info("🔀 Performing merge: {}", proposal);
        
        // Create new HEAD that references all source HEADs
        DagHead mergedHead = new DagHead(proposal.getMergeResultHead(), selfUrl);
        mergedHead.setParentIds(proposal.getSourceHeads());
        mergedHead.setCommitMessage("Merge of " + proposal.getSourceHeads().size() + " HEADs");
        
        // Calculate depth (max of all parents + 1)
        int maxDepth = proposal.getSourceHeads().stream()
            .map(id -> knownHeads.get(id))
            .filter(head -> head != null)
            .mapToInt(DagHead::getDepth)
            .max()
            .orElse(0);
        mergedHead.setDepth(maxDepth + 1);
        
        myHead = mergedHead;
        knownHeads.put(selfUrl, myHead);
        
        log.info("✅ Merge complete!");
        log.info("   New HEAD: {}", myHead);
    }
    
    /**
     * Broadcast my HEAD update to all peers.
     * This is like "git push" - telling others about my new state.
     */
    private void broadcastHeadUpdate() {
        log.info("📡 Broadcasting HEAD update to {} peers", peerUrls.size());
        
        // TODO: Implement HTTP broadcast
        // For now, just log
        for (String peerUrl : peerUrls) {
            log.info("   → {}", peerUrl);
        }
    }
    
    /**
     * Handle HEAD update from a peer.
     * This is like "git fetch" - learning about peer's new state.
     */
    public void handlePeerHeadUpdate(String peerUrl, DagHead peerHead) {
        log.info("📥 Received HEAD update from {}", peerUrl);
        log.info("   HEAD: {}", peerHead);
        
        knownHeads.put(peerUrl, peerHead);
        
        // Check if we should trigger a merge
        if (shouldProposeMerge()) {
            log.info("🔔 Multiple divergent HEADs detected - merge recommended");
            // TODO: Trigger automatic merge proposal
        }
    }
    
    /**
     * Decide if we should propose a merge.
     * Like Git, we don't merge constantly - only when divergence is significant.
     */
    private boolean shouldProposeMerge() {
        // Get all unique HEADs
        List<DagHead> uniqueHeads = knownHeads.values().stream()
            .collect(Collectors.toList());
        
        // If more than 2 divergent HEADs, consider merging
        return uniqueHeads.size() > 2;
    }
    
    /**
     * Get current DAG state summary.
     */
    public String getDagStatus() {
        StringBuilder status = new StringBuilder();
        status.append("🌳 DAG Status:\n");
        status.append("   My HEAD: ").append(myHead).append("\n");
        status.append("   Known HEADs: ").append(knownHeads.size()).append("\n");
        
        for (Map.Entry<String, DagHead> entry : knownHeads.entrySet()) {
            status.append("     - ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
        }
        
        return status.toString();
    }
    
    // Getters
    
    public DagHead getMyHead() {
        return myHead;
    }
    
    public Map<String, DagHead> getKnownHeads() {
        return knownHeads;
    }
}

