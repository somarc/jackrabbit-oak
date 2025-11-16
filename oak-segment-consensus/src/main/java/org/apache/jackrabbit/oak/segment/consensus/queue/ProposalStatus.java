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
 * Status response for a queued proposal.
 */
public class ProposalStatus {
    private final String proposalId;
    private final ProposalState state;
    private final String ethereumTxHash;
    private final long timeoutTimestamp;
    private final Long confirmedBlock;
    private final String rejectionReason;
    
    public ProposalStatus(
            String proposalId,
            ProposalState state,
            String ethereumTxHash,
            long timeoutTimestamp,
            Long confirmedBlock,
            String rejectionReason) {
        this.proposalId = proposalId;
        this.state = state;
        this.ethereumTxHash = ethereumTxHash;
        this.timeoutTimestamp = timeoutTimestamp;
        this.confirmedBlock = confirmedBlock;
        this.rejectionReason = rejectionReason;
    }
    
    public String getProposalId() {
        return proposalId;
    }
    
    public ProposalState getState() {
        return state;
    }
    
    public String getEthereumTxHash() {
        return ethereumTxHash;
    }
    
    public long getTimeoutTimestamp() {
        return timeoutTimestamp;
    }
    
    public Long getConfirmedBlock() {
        return confirmedBlock;
    }
    
    public String getRejectionReason() {
        return rejectionReason;
    }
}

