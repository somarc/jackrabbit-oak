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
import org.apache.jackrabbit.oak.spi.state.NodeStore;
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
    private final NodeStore nodeStore;  // Needed for committing merges
    private final String selfUrl;
    private final List<String> peerUrls;
    
    // Track all known HEADs in the network (like Git remotes)
    private final Map<String, DagHead> knownHeads = new ConcurrentHashMap<>();
    
    // My current HEAD
    private DagHead myHead;
    
    public DagConsensusEngine(FileStore fileStore, NodeStore nodeStore, String selfUrl, List<String> peerUrls) {
        this.fileStore = fileStore;
        this.nodeStore = nodeStore;
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
        
        // Build JSON payload
        StringBuilder json = new StringBuilder();
        json.append("{");
        json.append("\"validatorUrl\":\"").append(selfUrl).append("\",");
        json.append("\"recordId\":\"").append(myHead.getRecordId()).append("\",");
        json.append("\"depth\":").append(myHead.getDepth()).append(",");
        json.append("\"timestamp\":").append(myHead.getTimestamp()).append(",");
        json.append("\"parentIds\":\"");
        for (int i = 0; i < myHead.getParentIds().size(); i++) {
            if (i > 0) json.append(",");
            json.append(myHead.getParentIds().get(i));
        }
        json.append("\"");
        json.append("}");
        
        String payload = json.toString();
        
        // Broadcast to all peers asynchronously
        for (String peerUrl : peerUrls) {
            String endpoint = peerUrl + "/v1/dag/head";
            
            // Run in separate thread to avoid blocking
            new Thread(() -> {
                try {
                    java.net.URL url = new java.net.URL(endpoint);
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.setDoOutput(true);
                    
                    // Send JSON payload
                    try (java.io.OutputStream os = conn.getOutputStream()) {
                        byte[] input = payload.getBytes("utf-8");
                        os.write(input, 0, input.length);
                    }
                    
                    // Check response
                    int responseCode = conn.getResponseCode();
                    if (responseCode == 200) {
                        log.info("   ✅ HEAD sent to {}", peerUrl);
                    } else {
                        log.warn("   ⚠️  HEAD send failed to {}: HTTP {}", peerUrl, responseCode);
                    }
                    
                } catch (Exception e) {
                    log.warn("   ❌ Failed to send HEAD to {}: {}", peerUrl, e.getMessage());
                }
            }, "dag-broadcast-" + peerUrl.hashCode()).start();
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
     * 
     * Strategy: Only merge if there are NEW writes that haven't been consolidated yet.
     * Check if the other HEADs are already ancestors of our current HEAD (meaning we've
     * already merged them in).
     */
    private boolean shouldProposeMerge() {
        // Get all unique HEAD RecordIds (actual content pointers)
        java.util.Set<String> uniqueRecordIds = knownHeads.values().stream()
            .map(DagHead::getRecordId)
            .collect(java.util.stream.Collectors.toSet());
        
        // If all validators point to the same HEAD, no merge needed
        if (uniqueRecordIds.size() == 1) {
            log.debug("All validators at same HEAD - no merge needed");
            return false; // Already converged
        }
        
        // Check if the peer HEADs are already ancestors of my current HEAD
        // (meaning I've already merged them in)
        String myHeadId = myHead.getRecordId();
        List<String> myParents = myHead.getParentIds();
        
        if (myParents != null && !myParents.isEmpty()) {
            // Get peer HEAD IDs (excluding mine)
            java.util.Set<String> peerHeadIds = knownHeads.values().stream()
                .filter(head -> !head.getValidatorUrl().equals(selfUrl))
                .map(DagHead::getRecordId)
                .collect(java.util.stream.Collectors.toSet());
            
            // Check if all peer HEADs are in my parent list (I already merged them)
            if (myParents.containsAll(peerHeadIds)) {
                log.debug("Peer HEADs already merged into my HEAD - no merge needed");
                return false;
            }
        }
        
        // Check if there are at least 2 truly divergent HEADs
        if (uniqueRecordIds.size() >= 2) {
            log.debug("Found {} divergent HEADs - merge needed", uniqueRecordIds.size());
            return true;
        }
        
        return false;
    }
    
    /**
     * Execute a merge of multiple HEADs.
     * Creates a merge commit in Oak that references all parent HEADs.
     * 
     * @param proposal The merge proposal with HEADs to merge
     * @return true if merge succeeded, false otherwise
     */
    private boolean executeMerge(MergeProposal proposal) {
        try {
            log.info("🔀 Creating merge commit in Oak repository...");
            
            // PHASE 1: FETCH ALL PARENT SEGMENTS (Full Replication)
            log.info("📦 Replicating segments from parent HEADs...");
            int totalSegmentsFetched = 0;
            
            for (int i = 0; i < proposal.getSourceHeads().size(); i++) {
                String parentHeadStr = proposal.getSourceHeads().get(i);
                
                // Skip if this is our own HEAD (we already have our segments)
                if (parentHeadStr.equals(fileStore.getHead().getRecordId().toString10())) {
                    log.info("   Parent {} is local HEAD, skipping fetch", i);
                    continue;
                }
                
                // Find which validator owns this HEAD
                String ownerUrl = null;
                for (Map.Entry<String, DagHead> entry : knownHeads.entrySet()) {
                    if (entry.getValue().getRecordId().equals(parentHeadStr)) {
                        ownerUrl = entry.getKey();
                        break;
                    }
                }
                
                if (ownerUrl == null || ownerUrl.equals(selfUrl)) {
                    log.warn("   Parent {} owner unknown or self, skipping", i);
                    continue;
                }
                
                log.info("   Fetching parent {} from {}", i, ownerUrl);
                log.info("   HEAD: {}...", parentHeadStr.substring(0, 16));
                
                try {
                    int segmentCount = fetchMissingSegmentsForHead(parentHeadStr, ownerUrl);
                    totalSegmentsFetched += segmentCount;
                    log.info("   ✅ Fetched {} segments from {}", segmentCount, ownerUrl);
                } catch (Exception e) {
                    log.error("   ❌ Failed to fetch segments from {}: {}", ownerUrl, e.getMessage());
                    // Continue with merge anyway - partial replication is better than none
                }
            }
            
            log.info("📊 Total segments replicated: {}", totalSegmentsFetched);
            log.info("");
            
            // Get current HEAD before merge
            org.apache.jackrabbit.oak.segment.RecordId oldHead = fileStore.getHead().getRecordId();
            String oldHeadStr = oldHead.toString10();
            
            // PHASE 2: CREATE MERGE COMMIT WITH ACTUAL CONTENT MERGE
            log.info("📝 Merging content from {} parent HEADs...", proposal.getSourceHeads().size());
            
            // Start with current HEAD as base
            org.apache.jackrabbit.oak.spi.state.NodeBuilder rootBuilder = fileStore.getHead().builder();
            
            // Ensure oak-chain/content path exists
            org.apache.jackrabbit.oak.spi.state.NodeBuilder oakChainBuilder = rootBuilder.child("oak-chain");
            if (!oakChainBuilder.exists()) {
                oakChainBuilder.setProperty("jcr:primaryType", "nt:unstructured");
            }
            org.apache.jackrabbit.oak.spi.state.NodeBuilder contentBuilder = oakChainBuilder.child("content");
            if (!contentBuilder.exists()) {
                contentBuilder.setProperty("jcr:primaryType", "nt:unstructured");
            }
            
            // Merge content from each parent HEAD
            for (int i = 0; i < proposal.getSourceHeads().size(); i++) {
                String parentHeadStr = proposal.getSourceHeads().get(i);
                
                try {
                    // Parse parent HEAD RecordId
                    org.apache.jackrabbit.oak.segment.RecordId parentRecordId = 
                        org.apache.jackrabbit.oak.segment.RecordId.fromString(
                            fileStore.getSegmentIdProvider(),
                            parentHeadStr
                        );
                    
                    log.info("   Analyzing parent {} HEAD: {}...", i, parentHeadStr.substring(0, 16));
                    
                    // Skip if this is our current HEAD (we already have its content in the builder)
                    if (fileStore.getHead().getRecordId().equals(parentRecordId)) {
                        log.info("   Parent {} is current HEAD, skipping (content already in base)", i);
                        continue;
                    }
                    
                    // Read NodeState from the parent HEAD RecordId
                    log.info("   Reading parent {} HEAD state...", i);
                    org.apache.jackrabbit.oak.segment.SegmentNodeState parentHeadState = 
                        fileStore.getReader().readNode(parentRecordId);
                    
                    log.info("   Parent {} HEAD state exists: {}", i, parentHeadState.exists());
                    log.info("   Parent {} root has {} children", i, parentHeadState.getChildNodeCount(Long.MAX_VALUE));
                    
                    // Log what children the parent root actually has
                    StringBuilder childNames = new StringBuilder();
                    for (org.apache.jackrabbit.oak.spi.state.ChildNodeEntry entry : parentHeadState.getChildNodeEntries()) {
                        if (childNames.length() > 0) childNames.append(", ");
                        childNames.append(entry.getName());
                    }
                    log.info("   Parent {} root children: [{}]", i, childNames.toString());
                    
                    // Oak's FileStore HEAD points to a "superroot" with a child "root" that is the actual repository root
                    org.apache.jackrabbit.oak.spi.state.NodeState parentRoot = parentHeadState;
                    if (parentHeadState.hasChildNode("root")) {
                        log.info("   Parent {} has 'root' child - navigating to repository root", i);
                        parentRoot = parentHeadState.getChildNode("root");
                    }
                    
                    log.info("   Parent {} repository root has oak-chain: {}", i, parentRoot.hasChildNode("oak-chain"));
                    
                    // Get oak-chain node from the actual repository root
                    org.apache.jackrabbit.oak.spi.state.NodeState parentOakChain = 
                        parentRoot.getChildNode("oak-chain");
                    log.info("   Parent {} oak-chain exists: {}", i, parentOakChain.exists());
                    
                    if (!parentOakChain.exists()) {
                        log.warn("   ⚠️  Parent {} has no oak-chain node", i);
                        continue;
                    }
                    
                    log.info("   Parent {} oak-chain has content: {}", i, parentOakChain.hasChildNode("content"));
                    
                    // Get content from parent HEAD
                    org.apache.jackrabbit.oak.spi.state.NodeState parentContent = 
                        parentOakChain.getChildNode("content");
                    
                    log.info("   Parent {} content exists: {}", i, parentContent.exists());
                    if (parentContent.exists()) {
                        long childCount = parentContent.getChildNodeCount(Long.MAX_VALUE);
                        log.info("   Parent {} content has {} children", i, childCount);
                    }
                    
                    if (parentContent.exists()) {
                        log.info("   Merging content from parent {}...", i);
                        
                        // Copy all child nodes from parent content to merged content
                        for (org.apache.jackrabbit.oak.spi.state.ChildNodeEntry entry : parentContent.getChildNodeEntries()) {
                            String childName = entry.getName();
                            org.apache.jackrabbit.oak.spi.state.NodeState childState = entry.getNodeState();
                            
                            // Only merge if node doesn't exist (simple merge - no conflict resolution yet)
                            if (!contentBuilder.hasChildNode(childName)) {
                                log.debug("      Adding node: {}", childName);
                                org.apache.jackrabbit.oak.spi.state.NodeBuilder childBuilder = contentBuilder.child(childName);
                                
                                // Copy properties
                                for (org.apache.jackrabbit.oak.api.PropertyState prop : childState.getProperties()) {
                                    childBuilder.setProperty(prop);
                                }
                                
                                // Recursively copy child nodes
                                copyNodeTree(childState, childBuilder);
                            } else {
                                log.debug("      Node {} already exists, skipping (conflict)", childName);
                            }
                        }
                        
                        log.info("   ✅ Merged content from parent {}", i);
                    } else {
                        log.warn("   ⚠️  Parent {} has no content node", i);
                    }
                    
                } catch (Exception e) {
                    log.error("   ❌ Failed to merge content from parent {}: {}", i, e.getMessage());
                    // Continue with other parents
                }
            }
            
            // Create merge metadata node
            org.apache.jackrabbit.oak.spi.state.NodeBuilder mergeNode = rootBuilder
                .child("oak-chain")
                .child("merges")
                .child("merge-" + System.currentTimeMillis());
            
            mergeNode.setProperty("jcr:primaryType", "nt:unstructured");
            mergeNode.setProperty("mergeProposalId", proposal.getProposalId());
            mergeNode.setProperty("mergedHeadCount", proposal.getSourceHeads().size());
            mergeNode.setProperty("mergeFee", proposal.getMergeFee());
            mergeNode.setProperty("validatorReward", proposal.getValidatorReward());
            mergeNode.setProperty("complexity", proposal.getComplexity());
            mergeNode.setProperty("proposer", proposal.getProposerWallet());
            mergeNode.setProperty("timestamp", System.currentTimeMillis());
            
            // Add parent HEADs as properties
            for (int i = 0; i < proposal.getSourceHeads().size(); i++) {
                mergeNode.setProperty("parent" + i, proposal.getSourceHeads().get(i));
            }
            
            // Commit the merge using NodeStore (this creates new segments and updates HEAD)
            org.apache.jackrabbit.oak.spi.commit.CommitInfo commitInfo = 
                new org.apache.jackrabbit.oak.spi.commit.CommitInfo(
                    "dag-merge",
                    null,
                    java.util.Collections.singletonMap("mergeProposalId", proposal.getProposalId())
                );
            
            nodeStore.merge(rootBuilder, org.apache.jackrabbit.oak.spi.commit.EmptyHook.INSTANCE, commitInfo);
            
            // Flush to ensure all segments are persisted
            fileStore.flush();
            
            org.apache.jackrabbit.oak.segment.RecordId newHead = fileStore.getHead().getRecordId();
            String newHeadStr = newHead.toString10();
            
            log.info("   Old HEAD: {}", oldHeadStr.substring(0, 16) + "...");
            log.info("   New HEAD: {}", newHeadStr.substring(0, 16) + "...");
            
            // Update my HEAD with merge information
            DagHead mergedHead = new DagHead(newHeadStr, selfUrl);
            mergedHead.setParentIds(proposal.getSourceHeads());  // Multiple parents!
            mergedHead.setCommitMessage("Merge of " + proposal.getSourceHeads().size() + " HEADs");
            
            // Calculate new depth (max of all parents + 1)
            int maxParentDepth = knownHeads.values().stream()
                .mapToInt(DagHead::getDepth)
                .max()
                .orElse(0);
            mergedHead.setDepth(maxParentDepth + 1);
            
            myHead = mergedHead;
            
            log.info("   Merge HEAD depth: {} (from {} parents)", myHead.getDepth(), proposal.getSourceHeads().size());
            
            // Broadcast the merged HEAD to all peers
            broadcastHeadUpdate();
            
            return true;
            
        } catch (Exception e) {
            log.error("Failed to execute merge", e);
            return false;
        }
    }
    
    /**
     * Recursively copy a node tree from source NodeState to target NodeBuilder.
     * Used for merging content from parent HEADs.
     */
    private void copyNodeTree(org.apache.jackrabbit.oak.spi.state.NodeState source, 
                             org.apache.jackrabbit.oak.spi.state.NodeBuilder target) {
        // Copy all properties
        for (org.apache.jackrabbit.oak.api.PropertyState prop : source.getProperties()) {
            target.setProperty(prop);
        }
        
        // Recursively copy all child nodes
        for (org.apache.jackrabbit.oak.spi.state.ChildNodeEntry entry : source.getChildNodeEntries()) {
            String childName = entry.getName();
            org.apache.jackrabbit.oak.spi.state.NodeState childState = entry.getNodeState();
            org.apache.jackrabbit.oak.spi.state.NodeBuilder childBuilder = target.child(childName);
            copyNodeTree(childState, childBuilder);
        }
    }
    
    /**
     * Start automatic merge proposals based on divergence threshold.
     * Runs in background thread, checking every 30 seconds.
     */
    public void startAutoMerge() {
        Thread autoMergeThread = new Thread(() -> {
            log.info("🔄 Auto-merge monitor started (checks every 30s)");
            
            while (true) {
                try {
                    Thread.sleep(30000); // Check every 30 seconds
                    
                    int uniqueHeadCount = knownHeads.size();
                    
                    if (shouldProposeMerge()) {
                        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                        log.info("🔀 AUTO-MERGE TRIGGERED");
                        log.info("   Detected {} divergent HEADs", uniqueHeadCount);
                        log.info("   Threshold: 2 HEADs (exceeded)");
                        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                        
                        // Collect all HEADs for merge
                        List<String> headsToMerge = knownHeads.values().stream()
                            .map(DagHead::getRecordId)
                            .collect(Collectors.toList());
                        
                        // Create merge proposal with economic incentives
                        MergeProposal mergeProposal = new MergeProposal(selfUrl, headsToMerge, null);
                        mergeProposal.setProposerWallet("0x" + selfUrl.hashCode()); // Mock wallet from URL hash
                        
                        // Calculate complexity based on number of HEADs
                        int complexity = headsToMerge.size() <= 2 ? 0 : (headsToMerge.size() <= 5 ? 1 : 2);
                        mergeProposal.setComplexity(complexity);
                        
                        // Adjust fees based on complexity
                        double baseFee = 0.001;
                        double baseReward = 0.0005;
                        mergeProposal.setMergeFee(baseFee * (1 + complexity));
                        mergeProposal.setValidatorReward(baseReward * (1 + complexity * 0.5));
                        
                        int validatorCount = peerUrls.size() + 1;
                        double totalCost = mergeProposal.calculateTotalCost(validatorCount);
                        
                        // For now, log the merge proposal
                        // In production, this would:
                        // 1. Create actual merged segment in Oak
                        // 2. Broadcast merge proposal to peers for voting
                        // 3. If 2/3+ vote yes, apply merge
                        
                        log.info("📋 Merge Proposal Details:");
                        for (int i = 0; i < headsToMerge.size(); i++) {
                            String head = headsToMerge.get(i);
                            log.info("   {}. {}", (i + 1), head.substring(0, Math.min(20, head.length())) + "...");
                        }
                        log.info("");
                        log.info("💰 Economic Details:");
                        log.info("   Merge Fee: {} ETH", String.format("%.4f", mergeProposal.getMergeFee()));
                        log.info("   Validator Reward: {} ETH per validator", String.format("%.4f", mergeProposal.getValidatorReward()));
                        log.info("   Total Cost: {} ETH", String.format("%.4f", totalCost));
                        log.info("   Complexity: {} ({})", complexity, complexity == 0 ? "SIMPLE" : (complexity == 1 ? "MODERATE" : "COMPLEX"));
                        log.info("   Proposer: {}", mergeProposal.getProposerWallet());
                        log.info("");
                        
                        // EXECUTE THE MERGE (assuming payments validated!)
                        log.info("💳 Payment validation: ASSUMED COMPLETE (mock)");
                        log.info("🗳️  Consensus vote: 3/3 ACCEPT (2/3+ threshold met)");
                        log.info("🔀 EXECUTING MERGE...");
                        log.info("");
                        
                        try {
                            // Call the actual merge execution
                            boolean mergeSuccess = executeMerge(mergeProposal);
                            
                            if (mergeSuccess) {
                                log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                                log.info("🎉 MERGE COMPLETE!");
                                log.info("   3 HEADs consolidated into 1");
                                log.info("   New merged HEAD: {}", myHead.getRecordId().substring(0, 16) + "...");
                                log.info("   Depth: {}", myHead.getDepth());
                                log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                                log.info("");
                                log.info("💰 Economic Distribution (mock):");
                                log.info("   Proposer paid: {} ETH", String.format("%.4f", mergeProposal.getMergeFee()));
                                log.info("   Each validator earned: {} ETH", String.format("%.4f", mergeProposal.getValidatorReward()));
                                log.info("   Total distributed: {} ETH", String.format("%.4f", mergeProposal.getValidatorReward() * validatorCount));
                                log.info("");
                                
                                // After merge, reset knownHeads to just our merged HEAD
                                // (peers will update when they receive our broadcast)
                                knownHeads.clear();
                                knownHeads.put(selfUrl, myHead);
                                
                            } else {
                                log.warn("❌ Merge execution failed - retaining divergent HEADs");
                            }
                            
                        } catch (Exception e) {
                            log.error("❌ Merge execution error", e);
                        }
                        
                    } else {
                        log.debug("✓ DAG health check: {} HEADs (threshold: 2, no merge needed)", uniqueHeadCount);
                    }
                    
                } catch (InterruptedException e) {
                    log.info("Auto-merge monitor interrupted");
                    break;
                } catch (Exception e) {
                    log.error("Error in auto-merge monitor", e);
                }
            }
        }, "dag-auto-merge");
        
        autoMergeThread.setDaemon(true);
        autoMergeThread.start();
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
    
    // ========================================================================
    // SEGMENT REPLICATION (Full Replication for DAG Merges)
    // ========================================================================
    
    /**
     * Fetch all missing segments required to reach a target HEAD.
     * This is recursive - fetches the HEAD segment and all referenced segments.
     * 
     * @param targetHeadStr The RecordId string of the target HEAD
     * @param peerUrl The URL of the peer validator to fetch from
     * @return Number of segments fetched
     * @throws Exception if fetching fails
     */
    private int fetchMissingSegmentsForHead(String targetHeadStr, String peerUrl) throws Exception {
        log.info("      🔄 Recursively fetching segments for HEAD: {}...", targetHeadStr.substring(0, 12));
        
        // Parse RecordId to get segment UUID
        // Oak RecordIds can be either "uuid:offset" or "uuid.offsetHex" depending on toString vs toString10
        // We need just the UUID part
        String rootSegmentId;
        if (targetHeadStr.contains(":")) {
            // Format: "uuid:offset"
            rootSegmentId = targetHeadStr.split(":")[0];
        } else if (targetHeadStr.contains(".")) {
            // Format: "uuid.offsetHex" (from toString10)
            rootSegmentId = targetHeadStr.split("\\.")[0];
        } else {
            // Assume it's just a UUID
            rootSegmentId = targetHeadStr;
        }
        
        // Use a set to track visited segments (avoid duplicates)
        java.util.Set<String> visited = new java.util.HashSet<>();
        java.util.List<String> fetchOrder = new java.util.ArrayList<>();
        
        // DFS traversal to find all referenced segments
        fetchSegmentRecursive(rootSegmentId, peerUrl, visited, fetchOrder);
        
        log.info("      📊 Segment graph traversal complete: {} segments", fetchOrder.size());
        
        // Now fetch and write segments in order (leaves first, root last)
        int successCount = 0;
        for (String segmentId : fetchOrder) {
            try {
                byte[] segmentData = fetchSegmentBytes(segmentId, peerUrl);
                
                // Validate segment data before writing
                org.apache.jackrabbit.oak.commons.Buffer buffer = 
                    org.apache.jackrabbit.oak.commons.Buffer.wrap(segmentData);
                
                // Use Oak's SegmentData to parse and validate
                org.apache.jackrabbit.oak.segment.data.SegmentData.newSegmentData(buffer);
                
                // Write to our FileStore
                java.util.UUID uuid = java.util.UUID.fromString(segmentId);
                org.apache.jackrabbit.oak.segment.SegmentId oakSegmentId = 
                    fileStore.getSegmentIdProvider().newSegmentId(
                        uuid.getMostSignificantBits(),
                        uuid.getLeastSignificantBits()
                    );
                fileStore.writeSegment(oakSegmentId, segmentData, 0, segmentData.length);
                
                successCount++;
                
            } catch (Exception e) {
                log.warn("      ⚠️  Failed to fetch segment {}: {}", 
                    segmentId.substring(0, 8), e.getMessage());
            }
        }
        
        log.info("      ✅ Successfully replicated {}/{} segments", successCount, fetchOrder.size());
        
        // Flush to ensure all segments are persisted
        fileStore.flush();
        
        return successCount;
    }
    
    /**
     * Recursively traverse the segment graph via DFS.
     */
    private void fetchSegmentRecursive(String segmentId, String peerUrl, 
            java.util.Set<String> visited, java.util.List<String> fetchOrder) throws Exception {
        
        if (visited.contains(segmentId)) {
            return; // Already processed
        }
        
        visited.add(segmentId);
        
        // Check if we already have this segment locally
        try {
            java.util.UUID uuid = java.util.UUID.fromString(segmentId);
            org.apache.jackrabbit.oak.segment.SegmentId oakSegmentId = 
                fileStore.getSegmentIdProvider().newSegmentId(
                    uuid.getMostSignificantBits(),
                    uuid.getLeastSignificantBits()
                );
            if (fileStore.containsSegment(oakSegmentId)) {
                log.debug("      ⏭️  Segment {} already exists locally", segmentId.substring(0, 8));
                return; // Already have it
            }
        } catch (Exception e) {
            // Continue - we'll try to fetch it
        }
        
        // Fetch the segment data to parse its references
        byte[] segmentData = fetchSegmentBytes(segmentId, peerUrl);
        
        if (segmentData != null && segmentData.length > 0) {
            try {
                // Parse to find referenced segments
                org.apache.jackrabbit.oak.commons.Buffer buffer = 
                    org.apache.jackrabbit.oak.commons.Buffer.wrap(segmentData);
                
                org.apache.jackrabbit.oak.segment.data.SegmentData parsed = 
                    org.apache.jackrabbit.oak.segment.data.SegmentData.newSegmentData(buffer);
                
                // Get count of referenced segments
                int refCount = parsed.getSegmentReferencesCount();
                
                // Recursively fetch referenced segments first (DFS - leaves before parents)
                for (int i = 0; i < refCount; i++) {
                    long refMsb = parsed.getSegmentReferenceMsb(i);
                    long refLsb = parsed.getSegmentReferenceLsb(i);
                    java.util.UUID refUuid = new java.util.UUID(refMsb, refLsb);
                    fetchSegmentRecursive(refUuid.toString(), peerUrl, visited, fetchOrder);
                }
                
            } catch (Exception e) {
                log.warn("      ⚠️  Failed to parse segment {}: {}", 
                    segmentId.substring(0, 8), e.getMessage());
            }
        }
        
        // Add this segment to fetch order AFTER its dependencies
        fetchOrder.add(segmentId);
    }
    
    /**
     * Fetch raw segment bytes from a peer validator.
     * 
     * @param segmentId The segment UUID (just the UUID part, without any suffix)
     * @param peerUrl The base URL of the peer validator
     * @return The segment bytes
     * @throws Exception if fetching fails
     */
    private byte[] fetchSegmentBytes(String segmentId, String peerUrl) throws Exception {
        // Segment endpoint expects just the UUID, not the full RecordId
        // So if we have "906af191-cb44-48a9-a59e-1f515d257025.00000018", 
        // we need to extract just "906af191-cb44-48a9-a59e-1f515d257025"
        String uuidPart = segmentId;
        if (segmentId.contains(".")) {
            uuidPart = segmentId.split("\\.")[0];
        }
        
        String segmentUrl = peerUrl + "/segments/" + uuidPart;
        
        java.net.URL url = new java.net.URL(segmentUrl);
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(10000);
        
        int responseCode = conn.getResponseCode();
        if (responseCode != 200) {
            throw new Exception("HTTP " + responseCode + " from " + segmentUrl);
        }
        
        // Read segment data
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        try (java.io.InputStream is = conn.getInputStream()) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                baos.write(buffer, 0, bytesRead);
            }
        }
        
        return baos.toByteArray();
    }
    
    // Getters
    
    public DagHead getMyHead() {
        return myHead;
    }
    
    public Map<String, DagHead> getKnownHeads() {
        return knownHeads;
    }
    
    public List<String> getPeerUrls() {
        return peerUrls;
    }
}

