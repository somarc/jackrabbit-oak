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
package org.apache.jackrabbit.oak.segment.consensus.queue;

/**
 * Queued proposal waiting for Ethereum confirmation.
 */
public class QueuedProposal {
    
    /** Proposal type (WRITE or DELETE) */
    public enum ProposalType {
        WRITE,
        DELETE
    }
    
    private final String proposalId;
    private final String ethereumTxHash;
    private final long timestamp;
    private final long timeoutTimestamp;
    private volatile ProposalState state;
    private volatile Long confirmedBlock;
    private volatile String rejectionReason;
    
    // Wallet-based write/delete fields
    private volatile ProposalType type = ProposalType.WRITE; // Default to WRITE for backward compatibility
    private volatile String walletAddress;
    private volatile String path;
    private volatile String contentType;
    private volatile String message;
    private volatile String signature;
    private volatile long epoch; // Ethereum epoch when transaction was seen
    private volatile org.apache.jackrabbit.oak.segment.consensus.economics.ValidatorEarningsTracker.PaymentTier tier = 
        org.apache.jackrabbit.oak.segment.consensus.economics.ValidatorEarningsTracker.PaymentTier.STANDARD; // Payment tier for priority handling
    private volatile String intentToken; // Intent token for lazy binary upload (ADR 020)
    private volatile String blobId; // Oak blob ID for eager binary upload
    private volatile String mimeType; // MIME type for binary
    
    public QueuedProposal(
            String proposalId,
            String ethereumTxHash,
            Object unused, // For compatibility, not used
            long timestamp,
            long timeoutTimestamp,
            ProposalState state) {
        this.proposalId = proposalId;
        this.ethereumTxHash = ethereumTxHash;
        this.timestamp = timestamp;
        this.timeoutTimestamp = timeoutTimestamp;
        this.state = state;
    }
    
    public String getProposalId() {
        return proposalId;
    }
    
    public String getEthereumTxHash() {
        return ethereumTxHash;
    }
    
    public long getTimestamp() {
        return timestamp;
    }
    
    public String getWalletAddress() {
        return walletAddress;
    }
    
    public void setWalletAddress(String walletAddress) {
        this.walletAddress = walletAddress;
    }
    
    public String getPath() {
        return path;
    }
    
    public void setPath(String path) {
        this.path = path;
    }
    
    public String getContentType() {
        return contentType;
    }
    
    public void setContentType(String contentType) {
        this.contentType = contentType;
    }
    
    public String getMessage() {
        return message;
    }
    
    public void setMessage(String message) {
        this.message = message;
    }
    
    public String getSignature() {
        return signature;
    }
    
    public void setSignature(String signature) {
        this.signature = signature;
    }
    
    public long getEpoch() {
        return epoch;
    }
    
    public void setEpoch(long epoch) {
        this.epoch = epoch;
    }
    
    public long getTimeoutTimestamp() {
        return timeoutTimestamp;
    }
    
    public ProposalState getState() {
        return state;
    }
    
    public void setState(ProposalState state) {
        this.state = state;
    }
    
    public Long getConfirmedBlock() {
        return confirmedBlock;
    }
    
    public void setConfirmedBlock(Long confirmedBlock) {
        this.confirmedBlock = confirmedBlock;
    }
    
    public String getRejectionReason() {
        return rejectionReason;
    }
    
    public void setRejectionReason(String rejectionReason) {
        this.rejectionReason = rejectionReason;
    }
    
    public org.apache.jackrabbit.oak.segment.consensus.economics.ValidatorEarningsTracker.PaymentTier getTier() {
        return tier;
    }
    
    public void setTier(org.apache.jackrabbit.oak.segment.consensus.economics.ValidatorEarningsTracker.PaymentTier tier) {
        this.tier = tier;
    }
    
    public ProposalType getType() {
        return type;
    }
    
    public void setType(ProposalType type) {
        this.type = type;
    }
    
    public String getIntentToken() {
        return intentToken;
    }
    
    public void setIntentToken(String intentToken) {
        this.intentToken = intentToken;
    }
    
    public String getBlobId() {
        return blobId;
    }
    
    public void setBlobId(String blobId) {
        this.blobId = blobId;
    }
    
    public String getMimeType() {
        return mimeType;
    }
    
    public void setMimeType(String mimeType) {
        this.mimeType = mimeType;
    }
}

