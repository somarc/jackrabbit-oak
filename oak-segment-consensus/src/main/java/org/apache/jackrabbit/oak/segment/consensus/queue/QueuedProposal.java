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
    private final String proposalId;
    private final String ethereumTxHash;
    private final long timestamp;
    private final long timeoutTimestamp;
    private volatile ProposalState state;
    private volatile Long confirmedBlock;
    private volatile String rejectionReason;
    
    // Wallet-based write fields
    private volatile String walletAddress;
    private volatile String path;
    private volatile String contentType;
    private volatile String message;
    private volatile String signature;
    private volatile long epoch; // Ethereum epoch when transaction was seen
    
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
}

