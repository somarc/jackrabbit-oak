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
package org.apache.jackrabbit.oak.segment.consensus;

import org.apache.jackrabbit.oak.segment.RecordId;
import org.apache.jackrabbit.oak.segment.Segment;
import org.apache.jackrabbit.oak.segment.SegmentId;
import org.apache.jackrabbit.oak.segment.SegmentNodeState;
import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Consensus engine for coordinating writes across multiple validators.
 * 
 * <p>Implements a simple Proof-of-Authority consensus:
 * - 2/3+ majority required for writes
 * - Segment-level replication via HTTP
 * - Mock payment verification (Phase 1)
 */
public class ConsensusEngine {
    
    private static final Logger log = LoggerFactory.getLogger(ConsensusEngine.class);
    
    private static final double CONSENSUS_THRESHOLD = 0.67; // 2/3+ majority
    private static final int VOTE_TIMEOUT_SECONDS = 30;
    
    private final FileStore fileStore;
    private final String selfUrl;
    private final List<String> peerUrls;
    
    private final Map<String, ProposalState> activeProposals = new ConcurrentHashMap<>();
    
    public ConsensusEngine(FileStore fileStore, String selfUrl, List<String> peerUrls) {
        this.fileStore = fileStore;
        this.selfUrl = selfUrl;
        this.peerUrls = new ArrayList<>(peerUrls);
        
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        log.info("🗳️  Consensus Engine Initialized");
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        log.info("   Self URL: {}", selfUrl);
        log.info("   Peers: {}", peerUrls.size());
        for (String peer : peerUrls) {
            log.info("      - {}", peer);
        }
        log.info("   Consensus threshold: {}% (2/3+ majority)", (int)(CONSENSUS_THRESHOLD * 100));
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    }
    
    /**
     * Get the number of peer validators (excluding self).
     */
    public int getPeerCount() {
        return peerUrls.size();
    }
    
    /**
     * Propose a write to the network and wait for consensus.
     * 
     * @param proposal The write proposal
     * @return true if consensus reached, false otherwise
     */
    public boolean proposeWrite(WriteProposal proposal) {
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        log.info("📤 PROPOSING WRITE: {}", proposal);
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        
        // Set correct proposer URL (override whatever was passed in)
        proposal.setProposerUrl(selfUrl);
        
        // Create proposal state tracker
        int totalValidators = 1 + peerUrls.size(); // self + peers
        ProposalState state = new ProposalState(proposal, totalValidators);
        activeProposals.put(proposal.getProposalId(), state);
        
        // Vote ACCEPT ourselves (we already have the segments)
        state.addVote(new Vote(proposal.getProposalId(), selfUrl, Vote.VoteType.ACCEPT));
        log.info("✅ Self-voted ACCEPT");
        
        // Broadcast proposal to all peers
        for (String peerUrl : peerUrls) {
            try {
                broadcastProposal(peerUrl, proposal);
                log.info("📡 Sent proposal to: {}", peerUrl);
            } catch (Exception e) {
                log.error("❌ Failed to send proposal to {}: {}", peerUrl, e.getMessage());
            }
        }
        
        // Wait for consensus
        boolean consensusReached = state.awaitConsensus(VOTE_TIMEOUT_SECONDS);
        
        if (consensusReached) {
            log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            log.info("🎉 CONSENSUS REACHED!");
            log.info("   Votes: {}/{} ACCEPT ({}/{})",
                state.getAcceptCount(),
                state.getTotalValidators(),
                state.getAcceptCount(),
                state.getTotalValidators());
            log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        } else {
            log.warn("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            log.warn("❌ CONSENSUS FAILED (timeout)");
            log.warn("   Votes: {}/{} ACCEPT", state.getAcceptCount(), state.getTotalValidators());
            log.warn("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        }
        
        activeProposals.remove(proposal.getProposalId());
        return consensusReached;
    }
    
    /**
     * Handle incoming write proposal from peer.
     * 
     * @param proposal The proposal to evaluate
     * @return Vote result
     */
    public Vote handleProposal(WriteProposal proposal) {
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        log.info("📥 RECEIVED PROPOSAL: {}", proposal);
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        
        try {
            // 1. Mock payment verification (always true for Phase 1)
            if (!proposal.isMockPaymentVerified()) {
                log.warn("❌ Mock payment not verified");
                return createRejectVote(proposal, "Mock payment verification failed");
            }
            log.info("✅ Mock payment verified");
            
            // 2. Fetch all missing segments needed to reach new HEAD
            // This traverses from newHead backwards, fetching any segments we don't have
            log.info("🔄 Fetching missing segments to reach new HEAD...");
            int segmentsFetched = fetchMissingSegmentsForHead(proposal.getProposerUrl(), proposal.getNewHead());
            log.info("✅ Fetched {} missing segments", segmentsFetched);
            
            // 3. Verify we can now reach the new HEAD
            if (!canReachHead(proposal.getNewHead())) {
                log.warn("❌ Still cannot reach new HEAD after fetching segments: {}", proposal.getNewHead());
                return createRejectVote(proposal, "Cannot reach new HEAD");
            }
            log.info("✅ Can reach new HEAD");
            
            // 4. Update journal (CRITICAL: Persist the new HEAD!)
            // This is analogous to Cold Standby's StandbyClientSyncExecution line 77:
            //   store.getRevisions().setHead(before.getRecordId(), remoteHead);
            // 
            // IMPORTANT: We use OUR local HEAD as "before", not the proposal's previousHead!
            // This is because setHead() does CAS (compare-and-set) and will fail if "before" 
            // doesn't match our current HEAD.
            try {
                // Get OUR current local HEAD
                org.apache.jackrabbit.oak.segment.RecordId localCurrentHead = 
                    fileStore.getHead().getRecordId();
                
                // Get the new HEAD from the proposal
                org.apache.jackrabbit.oak.segment.RecordId newRecordId = 
                    org.apache.jackrabbit.oak.segment.RecordId.fromString(
                        fileStore.getSegmentIdProvider(),
                        proposal.getNewHead()
                    );
                
                // Atomic journal update (CAS: current local HEAD → new remote HEAD)
                boolean updated = fileStore.getRevisions().setHead(localCurrentHead, newRecordId);
                
                if (!updated) {
                    log.error("❌ Journal setHead() returned false - CAS failed!");
                    log.error("   Local HEAD: {}", localCurrentHead);
                    log.error("   Wanted to set to: {}", newRecordId);
                    return createRejectVote(proposal, "Journal CAS failed");
                }
                
                // CRITICAL: Flush to persist to disk (journal.log)
                fileStore.flush();
                
                log.info("✅ Journal updated & flushed: {} → {}", 
                    localCurrentHead.toString().substring(0, 8),
                    proposal.getNewHead().substring(0, 8));
            } catch (Exception e) {
                log.error("❌ Failed to update journal: {}", e.getMessage(), e);
                return createRejectVote(proposal, "Journal update failed: " + e.getMessage());
            }
            
            // 5. Vote ACCEPT
            Vote vote = new Vote(proposal.getProposalId(), selfUrl, Vote.VoteType.ACCEPT);
            log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            log.info("✅ VOTED ACCEPT");
            log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            
            // Send vote back to proposer
            sendVoteToProposer(proposal.getProposerUrl(), vote);
            
            return vote;
            
        } catch (Exception e) {
            log.error("❌ Error processing proposal: {}", e.getMessage(), e);
            return createRejectVote(proposal, "Error: " + e.getMessage());
        }
    }
    
    /**
     * Handle incoming vote from peer.
     */
    public void handleVote(Vote vote) {
        ProposalState state = activeProposals.get(vote.getProposalId());
        
        if (state != null) {
            state.addVote(vote);
            log.info("📊 Vote received: {} from {} ({}/{} votes)",
                vote.getVoteType(),
                vote.getValidatorUrl(),
                state.getAcceptCount(),
                state.getTotalValidators());
        } else {
            log.warn("⚠️  Received vote for unknown proposal: {}", vote.getProposalId());
        }
    }
    
    /**
     * Fetch all missing segments needed to reach a given HEAD.
     * 
     * This is the CRITICAL segment replication logic. It:
     * 1. Parses the HEAD RecordId to find the root segment
     * 2. Recursively fetches that segment and all referenced segments (DFS)
     * 3. Uses topological ordering (like Oak's Cold Standby)
     * 
     * This ensures we have the complete segment graph needed to read the HEAD.
     * 
     * @param peerUrl Base URL of peer validator
     * @param headRecordId HEAD RecordId string (format: "uuid:recordNum")
     * @return Number of segments fetched
     */
    private int fetchMissingSegmentsForHead(String peerUrl, String headRecordId) throws IOException {
        try {
            // Parse the segment UUID from the RecordId
            // Format: "550e8400-e29b-41d4-a716-446655440000:15" or "550e8400-e29b-41d4-a716-446655440000.0000000f"
            String segmentUuid = headRecordId.split("[:\\.]")[0];
            
            log.debug("Starting recursive segment fetch for HEAD: {}", segmentUuid);
            
            java.util.UUID uuid = java.util.UUID.fromString(segmentUuid);
            
            // Track visited segments to avoid cycles
            java.util.Set<java.util.UUID> visited = new java.util.HashSet<>();
            java.util.List<java.util.UUID> fetchOrder = new java.util.ArrayList<>();
            
            // DFS traversal to build topological fetch order
            deriveTopologicalFetchOrder(peerUrl, uuid, visited, fetchOrder);
            
            log.info("   Total segments to fetch: {}", fetchOrder.size());
            
            // Fetch all segments in topological order
            for (java.util.UUID segId : fetchOrder) {
                fetchSegmentFromPeer(peerUrl, segId.toString());
            }
            
            return fetchOrder.size();
            
        } catch (Exception e) {
            log.error("Failed to fetch segments for HEAD {}: {}", headRecordId, e.getMessage(), e);
            throw new IOException("Segment fetch failed", e);
        }
    }
    
    /**
     * Derive topological fetch order using DFS.
     * 
     * This is based on Oak's Cold Standby implementation (StandbyClientSyncExecution).
     * We traverse the segment graph depth-first, ensuring referenced segments are
     * fetched before the segments that reference them.
     * 
     * @param peerUrl Peer validator URL
     * @param segmentId Segment UUID to process
     * @param visited Set of already-visited segments
     * @param fetchOrder List to accumulate segments in fetch order
     * @throws IOException if fetch fails
     */
    private void deriveTopologicalFetchOrder(
            String peerUrl, 
            java.util.UUID segmentId, 
            java.util.Set<java.util.UUID> visited,
            java.util.List<java.util.UUID> fetchOrder) throws IOException {
        
        // Skip if already visited or already local
        if (visited.contains(segmentId)) {
            return;
        }
        
        org.apache.jackrabbit.oak.segment.SegmentId sid = 
            fileStore.getSegmentIdProvider().newSegmentId(
                segmentId.getMostSignificantBits(),
                segmentId.getLeastSignificantBits()
            );
        
        if (fileStore.containsSegment(sid)) {
            log.debug("Segment {} already exists locally, skipping", segmentId);
            return;
        }
        
        visited.add(segmentId);
        log.debug("Visiting segment {}", segmentId);
        
        // For data segments, we need to fetch referenced segments first
        if (org.apache.jackrabbit.oak.segment.SegmentId.isDataSegmentId(segmentId.getLeastSignificantBits())) {
            // Fetch segment temporarily to read its references
            byte[] segmentData = fetchSegmentBytes(peerUrl, segmentId.toString());
            
            // Parse segment to get referenced segment IDs
            org.apache.jackrabbit.oak.commons.Buffer buffer = 
                org.apache.jackrabbit.oak.commons.Buffer.wrap(segmentData);
            
            org.apache.jackrabbit.oak.segment.data.SegmentData data = 
                org.apache.jackrabbit.oak.segment.data.SegmentData.newSegmentData(buffer);
            
            int refCount = data.getSegmentReferencesCount();
            log.debug("Segment {} has {} references", segmentId, refCount);
            
            // Recursively fetch all referenced segments first (DFS)
            for (int i = 0; i < refCount; i++) {
                long refMsb = data.getSegmentReferenceMsb(i);
                long refLsb = data.getSegmentReferenceLsb(i);
                java.util.UUID refId = new java.util.UUID(refMsb, refLsb);
                
                log.debug("  Reference: {} -> {}", segmentId, refId);
                deriveTopologicalFetchOrder(peerUrl, refId, visited, fetchOrder);
            }
        }
        
        // Add this segment to fetch order (after its references)
        fetchOrder.add(segmentId);
    }
    
    /**
     * Fetch segment bytes from peer (without writing to TAR yet).
     * Used for parsing segment references during DFS traversal.
     */
    private byte[] fetchSegmentBytes(String peerUrl, String segmentUuid) throws IOException {
        String url = peerUrl + "/segments/" + segmentUuid;
        
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(10000);
        
        int responseCode = conn.getResponseCode();
        if (responseCode != 200) {
            throw new IOException("Failed to fetch segment " + segmentUuid + " from " + peerUrl + ": HTTP " + responseCode);
        }
        
        byte[] data = conn.getInputStream().readAllBytes();
        conn.disconnect();
        
        return data;
    }
    
    /**
     * Fetch a single segment from a peer via HTTP and write it to our TAR files.
     * 
     * This is the CRITICAL piece - we fetch the segment bytes and then call
     * FileStore.writeSegment() to persist them to TAR files, just like Oak
     * does when writing local segments.
     * 
     * @param peerUrl Base URL of peer
     * @param segmentUuid UUID of segment (without record number)
     * @throws IOException if fetch or write fails
     */
    private void fetchSegmentFromPeer(String peerUrl, String segmentUuid) throws IOException {
        String url = peerUrl + "/segments/" + segmentUuid;
        
        log.debug("Fetching segment from: {}", url);
        
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(10000);
        
        int responseCode = conn.getResponseCode();
        if (responseCode != 200) {
            throw new IOException("Failed to fetch segment " + segmentUuid + " from " + peerUrl + ": HTTP " + responseCode);
        }
        
        // Read segment bytes from peer
        byte[] segmentData = conn.getInputStream().readAllBytes();
        log.info("📥 Fetched segment {} from {} ({} bytes)", segmentUuid, peerUrl, segmentData.length);
        
        conn.disconnect();
        
        // CRITICAL: Write the segment to our TAR files!
        // This is the same path Oak uses when writing local segments.
        try {
            java.util.UUID uuid = java.util.UUID.fromString(segmentUuid);
            org.apache.jackrabbit.oak.segment.SegmentId segmentId = 
                fileStore.getSegmentIdProvider().newSegmentId(
                    uuid.getMostSignificantBits(),
                    uuid.getLeastSignificantBits()
                );
            
            // Write to FileStore - this persists to TAR files AND updates the cache
            fileStore.writeSegment(segmentId, segmentData, 0, segmentData.length);
            
            log.info("💾 Segment {} written to TAR files", segmentUuid);
            
        } catch (Exception e) {
            log.error("Failed to write segment {} to TAR: {}", segmentUuid, e.getMessage(), e);
            throw new IOException("Failed to write segment to TAR", e);
        }
    }
    
    /**
     * Check if we can reach (read from) the new HEAD.
     * 
     * This verifies that all segments needed to read the HEAD are available.
     */
    private boolean canReachHead(String headRecordId) {
        try {
            // Parse and create RecordId
            org.apache.jackrabbit.oak.segment.RecordId recordId = 
                org.apache.jackrabbit.oak.segment.RecordId.fromString(
                    fileStore.getSegmentIdProvider(),
                    headRecordId
                );
            
            // Try to read the node at this RecordId
            // If we can read it, we have all the segments we need
            org.apache.jackrabbit.oak.segment.SegmentNodeState nodeState = 
                fileStore.getReader().readNode(recordId);
            
            // Successfully read the node!
            log.debug("Successfully read node at HEAD: {}", headRecordId);
            return nodeState != null;
            
        } catch (Exception e) {
            log.debug("Cannot reach HEAD {}: {}", headRecordId, e.getMessage());
            return false;
        }
    }
    
    /**
     * Broadcast proposal to peer.
     */
    private void broadcastProposal(String peerUrl, WriteProposal proposal) throws IOException {
        String url = peerUrl + "/v1/propose";
        
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(10000);
        
        // Simple JSON serialization (for Phase 1)
        String json = proposalToJson(proposal);
        
        try (OutputStream os = conn.getOutputStream()) {
            os.write(json.getBytes(StandardCharsets.UTF_8));
        }
        
        int responseCode = conn.getResponseCode();
        if (responseCode != 200) {
            throw new IOException("Failed to send proposal: HTTP " + responseCode);
        }
    }
    
    /**
     * Send vote back to proposer.
     */
    private void sendVoteToProposer(String proposerUrl, Vote vote) {
        try {
            String url = proposerUrl + "/v1/vote";
            
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(10000);
            
            String json = voteToJson(vote);
            
            try (OutputStream os = conn.getOutputStream()) {
                os.write(json.getBytes(StandardCharsets.UTF_8));
            }
            
            int responseCode = conn.getResponseCode();
            if (responseCode == 200) {
                log.debug("Vote sent to proposer: {}", proposerUrl);
            } else {
                log.warn("Failed to send vote: HTTP {}", responseCode);
            }
            
        } catch (Exception e) {
            log.warn("Failed to send vote to proposer: {}", e.getMessage());
        }
    }
    
    private Vote createRejectVote(WriteProposal proposal, String reason) {
        Vote vote = new Vote(proposal.getProposalId(), selfUrl, Vote.VoteType.REJECT);
        vote.setReason(reason);
        
        log.warn("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        log.warn("❌ VOTED REJECT: {}", reason);
        log.warn("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        
        return vote;
    }
    
    // Simple JSON serialization (Phase 1 - will use Jackson later)
    
    private String proposalToJson(WriteProposal p) {
        StringBuilder json = new StringBuilder("{");
        json.append("\"proposalId\":\"").append(p.getProposalId()).append("\",");
        json.append("\"proposerUrl\":\"").append(p.getProposerUrl()).append("\",");
        json.append("\"previousHead\":\"").append(p.getPreviousHead()).append("\",");
        json.append("\"newHead\":\"").append(p.getNewHead()).append("\",");
        json.append("\"author\":\"").append(p.getAuthor() != null ? p.getAuthor() : "system").append("\",");
        json.append("\"timestamp\":").append(p.getTimestamp()).append(",");
        json.append("\"mockPaymentVerified\":").append(p.isMockPaymentVerified()).append(",");
        json.append("\"segments\":[");
        for (int i = 0; i < p.getSegments().size(); i++) {
            if (i > 0) json.append(",");
            WriteProposal.SegmentInfo seg = p.getSegments().get(i);
            json.append("{\"segmentId\":\"").append(seg.getSegmentId()).append("\",");
            json.append("\"size\":").append(seg.getSize()).append(",");
            json.append("\"generation\":").append(seg.getGeneration()).append("}");
        }
        json.append("]}");
        return json.toString();
    }
    
    private String voteToJson(Vote v) {
        StringBuilder json = new StringBuilder("{");
        json.append("\"proposalId\":\"").append(v.getProposalId()).append("\",");
        json.append("\"validatorUrl\":\"").append(v.getValidatorUrl()).append("\",");
        json.append("\"voteType\":\"").append(v.getVoteType()).append("\",");
        json.append("\"reason\":\"").append(v.getReason() != null ? v.getReason() : "").append("\",");
        json.append("\"timestamp\":").append(v.getTimestamp());
        json.append("}");
        return json.toString();
    }
    
    /**
     * Tracks state of an active proposal.
     */
    private static class ProposalState {
        private final WriteProposal proposal;
        private final int totalValidators;
        private final List<Vote> votes = new ArrayList<>();
        private final CountDownLatch consensusLatch;
        
        ProposalState(WriteProposal proposal, int totalValidators) {
            this.proposal = proposal;
            this.totalValidators = totalValidators;
            this.consensusLatch = new CountDownLatch(1);
        }
        
        synchronized void addVote(Vote vote) {
            votes.add(vote);
            
            // Check if consensus reached
            if (hasReachedConsensus()) {
                consensusLatch.countDown();
            }
        }
        
        boolean awaitConsensus(int timeoutSeconds) {
            try {
                return consensusLatch.await(timeoutSeconds, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        
        synchronized int getAcceptCount() {
            return (int) votes.stream().filter(v -> v.getVoteType() == Vote.VoteType.ACCEPT).count();
        }
        
        int getTotalValidators() {
            return totalValidators;
        }
        
        synchronized boolean hasReachedConsensus() {
            double acceptRatio = (double) getAcceptCount() / totalValidators;
            return acceptRatio >= CONSENSUS_THRESHOLD;
        }
    }
}
