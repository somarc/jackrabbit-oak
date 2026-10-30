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

import org.apache.jackrabbit.oak.segment.consensus.service.MutationAuditMetadata;

import java.util.List;

/**
 * Appends verified proposals to the replicated (Raft) log.
 */
public interface RaftAppendCallback {

    /**
     * Append a verified write proposal.
     *
     * @return {@code true} if the proposal was offered to the log
     */
    boolean tryAppendProposalWithId(String proposalId, String walletAddress, String path, String contentType,
                                    String message, String signature, String blobId, String mimeType,
                                    String ipfsCid, MutationAuditMetadata auditMetadata);

    /**
     * Append a verified delete proposal.
     *
     * @return {@code true} if the proposal was offered to the log
     */
    default boolean tryAppendDeleteProposalWithId(String proposalId, String walletAddress, String path,
                                                  String signature, MutationAuditMetadata auditMetadata) {
        throw new UnsupportedOperationException("Delete proposals not supported by this Raft callback implementation");
    }

    /**
     * Append a batch; implementations with a native batch path should override this.
     *
     * @return number of proposals offered to the log
     */
    default int appendProposalBatch(List<QueuedProposal> proposals) {
        int sent = 0;
        for (QueuedProposal p : proposals) {
            boolean offered = p.getType() == QueuedProposal.ProposalType.DELETE
                ? tryAppendDeleteProposalWithId(p.getProposalId(), p.getWalletAddress(), p.getPath(),
                    p.getSignature(), p.toAuditMetadata())
                : tryAppendProposalWithId(p.getProposalId(), p.getWalletAddress(), p.getPath(), p.getContentType(),
                    p.getMessage(), p.getSignature(), p.getBlobId(), p.getMimeType(), p.getIpfsCid(),
                    p.toAuditMetadata());
            if (offered) {
                sent++;
            }
        }
        return sent;
    }
}
