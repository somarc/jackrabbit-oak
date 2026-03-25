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

import java.util.List;

/**
 * Callback interface for appending verified proposals to Raft log.
 */
public interface RaftAppendCallback {
    /**
     * Append a verified write proposal to Raft log (without binary).
     * 
     * @param walletAddress Ethereum wallet address
     * @param path Shard path
     * @param contentType Content type
     * @param message Content message
     * @param signature Transaction signature
     */
    void appendProposal(String walletAddress, String path, String contentType, String message, String signature);

    /**
     * Append a verified write proposal with proposalId (ADR 026).
     */
    default void appendProposalWithId(String proposalId, String walletAddress, String path, String contentType,
                                     String message, String signature) {
        appendProposal(walletAddress, path, contentType, message, signature);
    }

    /**
     * Try to append a verified write proposal with proposalId and report whether ingress accepted it.
     */
    default boolean tryAppendProposalWithId(String proposalId, String walletAddress, String path, String contentType,
                                            String message, String signature) {
        appendProposalWithId(proposalId, walletAddress, path, contentType, message, signature);
        return true;
    }
    
    /**
     * Append a verified write proposal with binary to Raft log.
     * 
     * @param walletAddress Ethereum wallet address
     * @param path Shard path
     * @param contentType Content type
     * @param message Content message
     * @param signature Transaction signature
     * @param blobId Oak blob ID for attached binary
     * @param mimeType MIME type of binary
     */
    default void appendProposal(String walletAddress, String path, String contentType, String message, 
                               String signature, String blobId, String mimeType) {
        // Default: call overload with null ipfsCid
        appendProposal(walletAddress, path, contentType, message, signature, blobId, mimeType, null);
    }

    /**
     * Append a verified write proposal with proposalId and binary.
     */
    default void appendProposalWithId(String proposalId, String walletAddress, String path, String contentType,
                                     String message, String signature, String blobId, String mimeType) {
        appendProposal(walletAddress, path, contentType, message, signature, blobId, mimeType);
    }
    
    /**
     * Append a verified write proposal with binary and IPFS CID to Raft log (ADR 016).
     * 
     * @param walletAddress Ethereum wallet address
     * @param path Shard path
     * @param contentType Content type
     * @param message Content message
     * @param signature Transaction signature
     * @param blobId Oak blob ID for attached binary
     * @param mimeType MIME type of binary
     * @param ipfsCid IPFS CID from client-side upload (may be null)
     */
    default void appendProposal(String walletAddress, String path, String contentType, String message, 
                               String signature, String blobId, String mimeType, String ipfsCid) {
        // Default: ignore binary metadata and call base method
        appendProposal(walletAddress, path, contentType, message, signature);
    }

    /**
     * Append a verified write proposal with proposalId, binary, and IPFS CID.
     */
    default void appendProposalWithId(String proposalId, String walletAddress, String path, String contentType,
                                     String message, String signature, String blobId, String mimeType, String ipfsCid) {
        appendProposal(walletAddress, path, contentType, message, signature, blobId, mimeType, ipfsCid);
    }

    /**
     * Try to append a verified write proposal with proposalId, binary, and IPFS CID.
     */
    default boolean tryAppendProposalWithId(String proposalId, String walletAddress, String path, String contentType,
                                            String message, String signature, String blobId, String mimeType,
                                            String ipfsCid) {
        appendProposalWithId(proposalId, walletAddress, path, contentType, message, signature, blobId, mimeType, ipfsCid);
        return true;
    }
    
    /**
     * Append a verified delete proposal to Raft log.
     * Same flow as writes, but simpler (no content/message).
     * 
     * @param walletAddress Ethereum wallet address
     * @param path Content path to delete
     * @param signature Transaction signature
     */
    default void appendDeleteProposal(String walletAddress, String path, String signature) {
        // Default implementation: not implemented
        // Implementations should override this to support deletes
        throw new UnsupportedOperationException("Delete proposals not supported by this Raft callback implementation");
    }

    /**
     * Append a verified delete proposal with proposalId.
     */
    default void appendDeleteProposalWithId(String proposalId, String walletAddress, String path, String signature) {
        appendDeleteProposal(walletAddress, path, signature);
    }

    /**
     * Try to append a verified delete proposal with proposalId and report whether ingress accepted it.
     */
    default boolean tryAppendDeleteProposalWithId(String proposalId, String walletAddress, String path, String signature) {
        appendDeleteProposalWithId(proposalId, walletAddress, path, signature);
        return true;
    }
    
    /**
     * Append a batch of verified proposals to Raft log as a single message.
     * This is more efficient than individual appends as Aeron can optimize batched messages.
     * 
     * @param proposals List of proposals to append as a batch
     * @return number of proposals successfully sent
     */
    default int appendProposalBatch(List<QueuedProposal> proposals) {
        // Default implementation: fall back to individual appends based on type
        // Implementations should override this for true batch support
        int sent = 0;
        for (QueuedProposal proposal : proposals) {
            if (proposal.getType() == QueuedProposal.ProposalType.DELETE) {
                if (tryAppendDeleteProposalWithId(
                    proposal.getProposalId(),
                    proposal.getWalletAddress(),
                    proposal.getPath(),
                    proposal.getSignature()
                )) {
                    sent++;
                }
            } else {
                if (tryAppendProposalWithId(
                    proposal.getProposalId(),
                    proposal.getWalletAddress(),
                    proposal.getPath(),
                    proposal.getContentType(),
                    proposal.getMessage(),
                    proposal.getSignature()
                )) {
                    sent++;
                }
            }
        }
        return sent;
    }
}
