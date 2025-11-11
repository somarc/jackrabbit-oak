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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A proposal to merge multiple DAG HEADs into a single HEAD.
 * Like a Git merge commit, this creates a new segment with multiple parents.
 * 
 * <p>In distributed DAG consensus, validators periodically propose merges
 * to consolidate parallel branches into a canonical state.
 */
public class MergeProposal {
    
    private String proposalId;
    private String proposerUrl;
    private List<String> sourceHeads;     // HEADs being merged
    private String mergeResultHead;       // Resulting merged HEAD
    private long timestamp;
    private String mergeStrategy;         // "auto", "manual", "fast-forward"
    private boolean hasConflicts;
    
    // Mock fields for Phase 1
    private String mockSignature;
    private boolean mockPaymentVerified = true;
    
    public MergeProposal() {
        this.proposalId = UUID.randomUUID().toString();
        this.sourceHeads = new ArrayList<>();
        this.timestamp = System.currentTimeMillis();
    }
    
    public MergeProposal(String proposerUrl, List<String> sourceHeads, String mergeResultHead) {
        this();
        this.proposerUrl = proposerUrl;
        this.sourceHeads = new ArrayList<>(sourceHeads);
        this.mergeResultHead = mergeResultHead;
    }
    
    /**
     * Check if this is a fast-forward merge (single parent, no actual merge needed).
     */
    public boolean isFastForward() {
        return "fast-forward".equals(mergeStrategy);
    }
    
    /**
     * Check if this merge has conflicts that require manual resolution.
     */
    public boolean requiresManualResolution() {
        return hasConflicts;
    }
    
    // Getters and setters
    
    public String getProposalId() {
        return proposalId;
    }
    
    public void setProposalId(String proposalId) {
        this.proposalId = proposalId;
    }
    
    public String getProposerUrl() {
        return proposerUrl;
    }
    
    public void setProposerUrl(String proposerUrl) {
        this.proposerUrl = proposerUrl;
    }
    
    public List<String> getSourceHeads() {
        return sourceHeads;
    }
    
    public void setSourceHeads(List<String> sourceHeads) {
        this.sourceHeads = sourceHeads;
    }
    
    public void addSourceHead(String headId) {
        if (!this.sourceHeads.contains(headId)) {
            this.sourceHeads.add(headId);
        }
    }
    
    public String getMergeResultHead() {
        return mergeResultHead;
    }
    
    public void setMergeResultHead(String mergeResultHead) {
        this.mergeResultHead = mergeResultHead;
    }
    
    public long getTimestamp() {
        return timestamp;
    }
    
    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }
    
    public String getMergeStrategy() {
        return mergeStrategy;
    }
    
    public void setMergeStrategy(String mergeStrategy) {
        this.mergeStrategy = mergeStrategy;
    }
    
    public boolean isHasConflicts() {
        return hasConflicts;
    }
    
    public void setHasConflicts(boolean hasConflicts) {
        this.hasConflicts = hasConflicts;
    }
    
    public String getMockSignature() {
        return mockSignature;
    }
    
    public void setMockSignature(String mockSignature) {
        this.mockSignature = mockSignature;
    }
    
    public boolean isMockPaymentVerified() {
        return mockPaymentVerified;
    }
    
    public void setMockPaymentVerified(boolean mockPaymentVerified) {
        this.mockPaymentVerified = mockPaymentVerified;
    }
    
    @Override
    public String toString() {
        String mergeResult = mergeResultHead != null && mergeResultHead.length() > 8 
            ? mergeResultHead.substring(0, 8) 
            : mergeResultHead;
            
        return String.format("MergeProposal{id=%s, proposer=%s, sources=%d HEADs, result=%s, strategy=%s, conflicts=%s}",
            proposalId,
            proposerUrl,
            sourceHeads.size(),
            mergeResult,
            mergeStrategy,
            hasConflicts ? "YES" : "NO"
        );
    }
}

