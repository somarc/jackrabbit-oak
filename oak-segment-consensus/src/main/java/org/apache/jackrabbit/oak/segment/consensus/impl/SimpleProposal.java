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
package org.apache.jackrabbit.oak.segment.consensus.impl;

import org.apache.jackrabbit.oak.segment.consensus.Proposal;
import org.apache.jackrabbit.oak.segment.consensus.wallet.Wallet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SignatureException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Simple implementation of a write proposal.
 */
public class SimpleProposal implements Proposal {

    private final UUID proposalId;
    private final UUID walletId;
    private final String targetPath;
    private final List<UUID> segmentIds;
    private final String paymentProof;
    private final long timestamp;
    private final byte[] signature;
    private final long estimatedSize;
    private final List<String> binaryUris;
    private final String protocolVersion;

    private SimpleProposal(Builder builder) {
        this.proposalId = builder.proposalId;
        this.walletId = builder.walletId;
        this.targetPath = builder.targetPath;
        this.segmentIds = Collections.unmodifiableList(new ArrayList<>(builder.segmentIds));
        this.paymentProof = builder.paymentProof;
        this.timestamp = builder.timestamp;
        this.signature = builder.signature;
        this.estimatedSize = builder.estimatedSize;
        this.binaryUris = Collections.unmodifiableList(new ArrayList<>(builder.binaryUris));
        this.protocolVersion = builder.protocolVersion;
    }

    @Override
    @NotNull
    public UUID getProposalId() {
        return proposalId;
    }

    @Override
    @NotNull
    public UUID getWalletId() {
        return walletId;
    }

    @Override
    @NotNull
    public String getTargetPath() {
        return targetPath;
    }

    @Override
    @NotNull
    public List<UUID> getSegmentIds() {
        return segmentIds;
    }

    @Override
    @NotNull
    public String getPaymentProof() {
        return paymentProof;
    }

    @Override
    public long getTimestamp() {
        return timestamp;
    }

    @Override
    @NotNull
    public byte[] getSignature() {
        return signature;
    }

    @Override
    public long getEstimatedSize() {
        return estimatedSize;
    }

    @Override
    @NotNull
    public List<String> getBinaryUris() {
        return binaryUris;
    }

    @Override
    @NotNull
    public String getProtocolVersion() {
        return protocolVersion;
    }

    @Override
    @Nullable
    public String validate() {
        // Check required fields
        if (proposalId == null) {
            return "Proposal ID is required";
        }
        if (walletId == null) {
            return "Wallet ID is required";
        }
        if (targetPath == null || targetPath.isEmpty()) {
            return "Target path is required";
        }
        if (segmentIds == null || segmentIds.isEmpty()) {
            return "At least one segment ID is required";
        }
        if (paymentProof == null || paymentProof.isEmpty()) {
            return "Payment proof is required";
        }
        if (signature == null || signature.length == 0) {
            return "Signature is required";
        }

        // Validate path is within wallet namespace
        String expectedPrefix = "/oak-chain/content/" + walletId + "/";
        if (!targetPath.startsWith(expectedPrefix)) {
            return "Target path must be within wallet namespace: " + expectedPrefix;
        }

        // Validate timestamp is reasonable (not too far in future/past)
        long now = System.currentTimeMillis();
        long fiveMinutes = 5 * 60 * 1000;
        if (timestamp > now + fiveMinutes) {
            return "Timestamp is too far in the future";
        }
        if (timestamp < now - fiveMinutes) {
            return "Timestamp is too old";
        }

        return null; // Valid
    }

    /**
     * Computes the signable bytes for this proposal.
     * <p>
     * This is what gets signed by the wallet to prove ownership.
     *
     * @return the bytes to sign
     */
    @NotNull
    public byte[] getSignableBytes() {
        // Calculate total size needed
        int size = 16 + 16 + // proposalId + walletId
                   4 + targetPath.getBytes(StandardCharsets.UTF_8).length +
                   4 + (segmentIds.size() * 16) +
                   4 + paymentProof.getBytes(StandardCharsets.UTF_8).length +
                   8 + // timestamp
                   8;  // estimatedSize

        ByteBuffer buffer = ByteBuffer.allocate(size);
        
        // Pack all fields into bytes
        buffer.putLong(proposalId.getMostSignificantBits());
        buffer.putLong(proposalId.getLeastSignificantBits());
        buffer.putLong(walletId.getMostSignificantBits());
        buffer.putLong(walletId.getLeastSignificantBits());
        
        byte[] pathBytes = targetPath.getBytes(StandardCharsets.UTF_8);
        buffer.putInt(pathBytes.length);
        buffer.put(pathBytes);
        
        buffer.putInt(segmentIds.size());
        for (UUID segmentId : segmentIds) {
            buffer.putLong(segmentId.getMostSignificantBits());
            buffer.putLong(segmentId.getLeastSignificantBits());
        }
        
        byte[] proofBytes = paymentProof.getBytes(StandardCharsets.UTF_8);
        buffer.putInt(proofBytes.length);
        buffer.put(proofBytes);
        
        buffer.putLong(timestamp);
        buffer.putLong(estimatedSize);
        
        return buffer.array();
    }

    @Override
    public String toString() {
        return "Proposal{" +
                "id=" + proposalId +
                ", wallet=" + walletId +
                ", path='" + targetPath + '\'' +
                ", segments=" + segmentIds.size() +
                ", size=" + estimatedSize +
                '}';
    }

    /**
     * Builder for creating proposals.
     */
    public static class Builder {
        private UUID proposalId = UUID.randomUUID();
        private UUID walletId;
        private String targetPath;
        private List<UUID> segmentIds = new ArrayList<>();
        private String paymentProof;
        private long timestamp = System.currentTimeMillis();
        private byte[] signature;
        private long estimatedSize;
        private List<String> binaryUris = new ArrayList<>();
        private String protocolVersion = "1.0";

        public Builder walletId(UUID walletId) {
            this.walletId = walletId;
            return this;
        }

        public Builder targetPath(String targetPath) {
            this.targetPath = targetPath;
            return this;
        }

        public Builder addSegmentId(UUID segmentId) {
            this.segmentIds.add(segmentId);
            return this;
        }

        public Builder segmentIds(List<UUID> segmentIds) {
            this.segmentIds = new ArrayList<>(segmentIds);
            return this;
        }

        public Builder paymentProof(String paymentProof) {
            this.paymentProof = paymentProof;
            return this;
        }

        public Builder estimatedSize(long estimatedSize) {
            this.estimatedSize = estimatedSize;
            return this;
        }

        public Builder addBinaryUri(String uri) {
            this.binaryUris.add(uri);
            return this;
        }

        /**
         * Signs and builds the proposal using the provided wallet.
         *
         * @param wallet the wallet to sign with
         * @return the signed proposal
         * @throws SignatureException if signing fails
         */
        public SimpleProposal signAndBuild(Wallet wallet) throws SignatureException {
            this.walletId = wallet.getWalletId();
            
            // Create temporary proposal to get signable bytes
            this.signature = new byte[0]; // Placeholder
            SimpleProposal temp = new SimpleProposal(this);
            
            // Sign it
            this.signature = wallet.sign(temp.getSignableBytes());
            
            // Build final proposal
            return new SimpleProposal(this);
        }
    }
}

