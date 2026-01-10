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
package org.apache.jackrabbit.oak.segment.consensus.validation;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.UUID;

/**
 * Value object representing a content delete proposal from the HTTP API.
 * 
 * <p><strong>Immutable:</strong> All fields are final and set via builder.
 * 
 * <p><strong>Usage:</strong>
 * <pre>{@code
 * ContentDeleteProposal proposal = ContentDeleteProposal.builder()
 *     .walletAddress("0x1234...")
 *     .contentPath("/oak-chain/00/00/00/0x1234.../content/page1")
 *     .signature("0xabc...")
 *     .build();
 * }</pre>
 * 
 * @see ContentWriteProposal
 */
public final class ContentDeleteProposal {
    
    private final String proposalId;
    private final String walletAddress;
    private final String contentPath;
    private final String signature;
    private final long timestamp;
    
    private ContentDeleteProposal(Builder builder) {
        this.proposalId = builder.proposalId != null ? builder.proposalId : UUID.randomUUID().toString();
        this.walletAddress = Objects.requireNonNull(builder.walletAddress, "walletAddress is required");
        this.contentPath = Objects.requireNonNull(builder.contentPath, "contentPath is required");
        this.signature = Objects.requireNonNull(builder.signature, "signature is required");
        this.timestamp = builder.timestamp > 0 ? builder.timestamp : System.currentTimeMillis();
    }
    
    /**
     * Create a new builder.
     * 
     * @return a new builder instance
     */
    @NotNull
    public static Builder builder() {
        return new Builder();
    }
    
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Getters (all fields are immutable)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    
    /** Unique proposal identifier */
    @NotNull
    public String getProposalId() {
        return proposalId;
    }
    
    /** Ethereum wallet address (normalized to lowercase) */
    @NotNull
    public String getWalletAddress() {
        return walletAddress;
    }
    
    /** Full Oak path of the content to delete */
    @NotNull
    public String getContentPath() {
        return contentPath;
    }
    
    /** Cryptographic signature */
    @NotNull
    public String getSignature() {
        return signature;
    }
    
    /** Proposal timestamp */
    public long getTimestamp() {
        return timestamp;
    }
    
    @Override
    public String toString() {
        return String.format(
            "ContentDeleteProposal{id=%s, wallet=%s, path=%s}",
            proposalId,
            walletAddress.substring(0, Math.min(10, walletAddress.length())) + "...",
            contentPath
        );
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ContentDeleteProposal that = (ContentDeleteProposal) o;
        return Objects.equals(proposalId, that.proposalId);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(proposalId);
    }
    
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Builder
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    
    /**
     * Builder for ContentDeleteProposal.
     */
    public static final class Builder {
        private String proposalId;
        private String walletAddress;
        private String contentPath;
        private String signature;
        private long timestamp;
        
        private Builder() {
        }
        
        public Builder proposalId(String proposalId) {
            this.proposalId = proposalId;
            return this;
        }
        
        public Builder walletAddress(String walletAddress) {
            this.walletAddress = walletAddress;
            return this;
        }
        
        public Builder contentPath(String contentPath) {
            this.contentPath = contentPath;
            return this;
        }
        
        public Builder signature(String signature) {
            this.signature = signature;
            return this;
        }
        
        public Builder timestamp(long timestamp) {
            this.timestamp = timestamp;
            return this;
        }
        
        /**
         * Build the proposal.
         * 
         * @return the immutable proposal
         * @throws NullPointerException if required fields are missing
         */
        @NotNull
        public ContentDeleteProposal build() {
            return new ContentDeleteProposal(this);
        }
    }
}
