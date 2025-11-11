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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A write proposal broadcast to all validators for consensus.
 * 
 * <p>Contains journal update (HEAD change) and list of new segments
 * that need to be replicated to all validators.
 */
public class WriteProposal {
    
    private String proposalId;
    private String proposerUrl;
    private String previousHead;
    private String newHead;
    private List<SegmentInfo> segments;
    private String author;
    private long timestamp;
    private String commitMessage;
    
    // Blockchain-inspired consensus fields
    private long height;  // Like Ethereum block number - ensures linear chain ordering
    
    // Mock fields (for Phase 1 - no smart contract yet)
    private String mockSignature;
    private boolean mockPaymentVerified = true;
    
    public WriteProposal() {
        this.proposalId = UUID.randomUUID().toString();
        this.segments = new ArrayList<>();
        this.timestamp = System.currentTimeMillis();
    }
    
    public WriteProposal(String proposerUrl, String previousHead, String newHead) {
        this();
        this.proposerUrl = proposerUrl;
        this.previousHead = previousHead;
        this.newHead = newHead;
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
    
    public String getPreviousHead() {
        return previousHead;
    }
    
    public void setPreviousHead(String previousHead) {
        this.previousHead = previousHead;
    }
    
    public String getNewHead() {
        return newHead;
    }
    
    public void setNewHead(String newHead) {
        this.newHead = newHead;
    }
    
    public List<SegmentInfo> getSegments() {
        return segments;
    }
    
    public void setSegments(List<SegmentInfo> segments) {
        this.segments = segments;
    }
    
    public void addSegment(SegmentInfo segment) {
        this.segments.add(segment);
    }
    
    public String getAuthor() {
        return author;
    }
    
    public void setAuthor(String author) {
        this.author = author;
    }
    
    public long getTimestamp() {
        return timestamp;
    }
    
    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }
    
    public String getCommitMessage() {
        return commitMessage;
    }
    
    public void setCommitMessage(String commitMessage) {
        this.commitMessage = commitMessage;
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
    
    public long getHeight() {
        return height;
    }
    
    public void setHeight(long height) {
        this.height = height;
    }
    
    /**
     * Information about a segment to be replicated
     */
    public static class SegmentInfo {
        private String segmentId;
        private long size;
        private String checksum;
        private int generation;
        
        public SegmentInfo() {
        }
        
        public SegmentInfo(String segmentId, long size, int generation) {
            this.segmentId = segmentId;
            this.size = size;
            this.generation = generation;
        }
        
        public String getSegmentId() {
            return segmentId;
        }
        
        public void setSegmentId(String segmentId) {
            this.segmentId = segmentId;
        }
        
        public long getSize() {
            return size;
        }
        
        public void setSize(long size) {
            this.size = size;
        }
        
        public String getChecksum() {
            return checksum;
        }
        
        public void setChecksum(String checksum) {
            this.checksum = checksum;
        }
        
        public int getGeneration() {
            return generation;
        }
        
        public void setGeneration(int generation) {
            this.generation = generation;
        }
    }
    
    @Override
    public String toString() {
        return String.format("WriteProposal{id=%s, height=%d, proposer=%s, segments=%d, %s → %s}",
            proposalId,
            height,
            proposerUrl,
            segments.size(),
            previousHead != null ? previousHead.substring(0, 8) : "null",
            newHead != null ? newHead.substring(0, 8) : "null"
        );
    }
}

