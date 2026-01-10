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
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * Value object representing a content write proposal from the HTTP API.
 * 
 * <p>This is distinct from {@link org.apache.jackrabbit.oak.segment.consensus.WriteProposal}
 * which represents segment-level replication proposals. This class represents
 * the HTTP API layer proposal before it's queued for consensus.
 * 
 * <p><strong>Immutable:</strong> All fields are final and set via builder.
 * 
 * <p><strong>Usage:</strong>
 * <pre>{@code
 * ContentWriteProposal proposal = ContentWriteProposal.builder()
 *     .walletAddress("0x1234...")
 *     .path("/oak-chain/00/00/00/0x1234.../content/page1")
 *     .contentType("page")
 *     .message("Hello World")
 *     .signature("0xabc...")
 *     .build();
 * }</pre>
 * 
 * @see ContentDeleteProposal
 */
public final class ContentWriteProposal {
    
    private final String proposalId;
    private final String walletAddress;
    private final String path;
    private final String contentType;
    private final String message;
    private final String signature;
    private final String intentToken;
    private final String blobId;
    private final String mimeType;
    private final String ipfsCid;
    private final String organization;
    private final String paymentTier;
    private final String ethereumTxHash;
    private final long timestamp;
    
    private ContentWriteProposal(Builder builder) {
        this.proposalId = builder.proposalId != null ? builder.proposalId : UUID.randomUUID().toString();
        this.walletAddress = Objects.requireNonNull(builder.walletAddress, "walletAddress is required");
        this.path = Objects.requireNonNull(builder.path, "path is required");
        this.contentType = builder.contentType != null ? builder.contentType : "page";
        this.message = builder.message;
        this.signature = Objects.requireNonNull(builder.signature, "signature is required");
        this.intentToken = builder.intentToken;
        this.blobId = builder.blobId;
        this.mimeType = builder.mimeType;
        this.ipfsCid = builder.ipfsCid;
        this.organization = builder.organization;
        this.paymentTier = builder.paymentTier;
        this.ethereumTxHash = builder.ethereumTxHash;
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
    
    /** Full Oak path for the content */
    @NotNull
    public String getPath() {
        return path;
    }
    
    /** Content type (e.g., "page", "asset", "fragment") */
    @NotNull
    public String getContentType() {
        return contentType;
    }
    
    /** Content message/body (may be null for binary-only writes) */
    @Nullable
    public String getMessage() {
        return message;
    }
    
    /** Cryptographic signature */
    @NotNull
    public String getSignature() {
        return signature;
    }
    
    /** Intent token for tracking (optional) */
    @Nullable
    public String getIntentToken() {
        return intentToken;
    }
    
    /** Blob ID for binary content (optional) */
    @Nullable
    public String getBlobId() {
        return blobId;
    }
    
    /** MIME type for binary content (optional) */
    @Nullable
    public String getMimeType() {
        return mimeType;
    }
    
    /** IPFS CID for client-side uploaded content (ADR 016) */
    @Nullable
    public String getIpfsCid() {
        return ipfsCid;
    }
    
    /** Organization scope (ADR 037) */
    @Nullable
    public String getOrganization() {
        return organization;
    }
    
    /** Payment tier (standard, express, priority) */
    @Nullable
    public String getPaymentTier() {
        return paymentTier;
    }
    
    /** Ethereum transaction hash for payment verification */
    @Nullable
    public String getEthereumTxHash() {
        return ethereumTxHash;
    }
    
    /** Proposal timestamp */
    public long getTimestamp() {
        return timestamp;
    }
    
    /** Check if this proposal includes binary content */
    public boolean hasBinary() {
        return blobId != null && !blobId.isEmpty();
    }
    
    /** Check if this proposal has an IPFS CID (client-side upload) */
    public boolean hasIpfsCid() {
        return ipfsCid != null && !ipfsCid.isEmpty();
    }
    
    @Override
    public String toString() {
        return String.format(
            "ContentWriteProposal{id=%s, wallet=%s, path=%s, type=%s, hasBinary=%s, hasIpfs=%s}",
            proposalId,
            walletAddress.substring(0, Math.min(10, walletAddress.length())) + "...",
            path,
            contentType,
            hasBinary(),
            hasIpfsCid()
        );
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ContentWriteProposal that = (ContentWriteProposal) o;
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
     * Builder for ContentWriteProposal.
     */
    public static final class Builder {
        private String proposalId;
        private String walletAddress;
        private String path;
        private String contentType;
        private String message;
        private String signature;
        private String intentToken;
        private String blobId;
        private String mimeType;
        private String ipfsCid;
        private String organization;
        private String paymentTier;
        private String ethereumTxHash;
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
        
        public Builder path(String path) {
            this.path = path;
            return this;
        }
        
        public Builder contentType(String contentType) {
            this.contentType = contentType;
            return this;
        }
        
        public Builder message(String message) {
            this.message = message;
            return this;
        }
        
        public Builder signature(String signature) {
            this.signature = signature;
            return this;
        }
        
        public Builder intentToken(String intentToken) {
            this.intentToken = intentToken;
            return this;
        }
        
        public Builder blobId(String blobId) {
            this.blobId = blobId;
            return this;
        }
        
        public Builder mimeType(String mimeType) {
            this.mimeType = mimeType;
            return this;
        }
        
        public Builder ipfsCid(String ipfsCid) {
            this.ipfsCid = ipfsCid;
            return this;
        }
        
        public Builder organization(String organization) {
            this.organization = organization;
            return this;
        }
        
        public Builder paymentTier(String paymentTier) {
            this.paymentTier = paymentTier;
            return this;
        }
        
        public Builder ethereumTxHash(String ethereumTxHash) {
            this.ethereumTxHash = ethereumTxHash;
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
        public ContentWriteProposal build() {
            return new ContentWriteProposal(this);
        }
    }
}
