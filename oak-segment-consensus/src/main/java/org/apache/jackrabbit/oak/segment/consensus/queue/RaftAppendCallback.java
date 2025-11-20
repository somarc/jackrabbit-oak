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
     * Append a verified proposal to Raft log.
     * 
     * @param walletAddress Ethereum wallet address
     * @param path Shard path
     * @param contentType Content type
     * @param message Content message
     * @param signature Transaction signature
     */
    void appendProposal(String walletAddress, String path, String contentType, String message, String signature);
    
    /**
     * Append a batch of verified proposals to Raft log as a single message.
     * This is more efficient than individual appends as Aeron can optimize batched messages.
     * 
     * @param proposals List of proposals to append as a batch
     * @return number of proposals successfully sent
     */
    default int appendProposalBatch(List<QueuedProposal> proposals) {
        // Default implementation: fall back to individual appends
        // Implementations should override this for true batch support
        int sent = 0;
        for (QueuedProposal proposal : proposals) {
            appendProposal(
                proposal.getWalletAddress(),
                proposal.getPath(),
                proposal.getContentType(),
                proposal.getMessage(),
                proposal.getSignature()
            );
            sent++;
        }
        return sent;
    }
}

