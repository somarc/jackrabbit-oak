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

/**
 * A vote cast by a validator on a write proposal.
 * 
 * <p>Validators vote ACCEPT or REJECT based on:
 * - Payment verification (Ethereum finality)
 * - Segment integrity (checksums match)
 * - Journal reachability (can reach new HEAD)
 */
public class Vote {
    
    public enum VoteType {
        ACCEPT,
        REJECT
    }
    
    private String proposalId;
    private String validatorUrl;
    private VoteType voteType;
    private String reason;
    private long timestamp;
    
    // Mock fields (for Phase 1)
    private String mockSignature;
    
    public Vote() {
        this.timestamp = System.currentTimeMillis();
    }
    
    public Vote(String proposalId, String validatorUrl, VoteType voteType) {
        this();
        this.proposalId = proposalId;
        this.validatorUrl = validatorUrl;
        this.voteType = voteType;
    }
    
    // Getters and setters
    
    public String getProposalId() {
        return proposalId;
    }
    
    public void setProposalId(String proposalId) {
        this.proposalId = proposalId;
    }
    
    public String getValidatorUrl() {
        return validatorUrl;
    }
    
    public void setValidatorUrl(String validatorUrl) {
        this.validatorUrl = validatorUrl;
    }
    
    public VoteType getVoteType() {
        return voteType;
    }
    
    public void setVoteType(VoteType voteType) {
        this.voteType = voteType;
    }
    
    public String getReason() {
        return reason;
    }
    
    public void setReason(String reason) {
        this.reason = reason;
    }
    
    public long getTimestamp() {
        return timestamp;
    }
    
    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }
    
    public String getMockSignature() {
        return mockSignature;
    }
    
    public void setMockSignature(String mockSignature) {
        this.mockSignature = mockSignature;
    }
    
    @Override
    public String toString() {
        return String.format("Vote{proposal=%s, validator=%s, vote=%s%s}",
            proposalId,
            validatorUrl,
            voteType,
            reason != null ? ", reason=" + reason : ""
        );
    }
}
