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
package org.apache.jackrabbit.oak.segment.consensus.eth;

/**
 * Represents Ethereum Beacon Chain epoch data.
 * 
 * <p>An epoch in Ethereum 2.0 represents 32 slots (~6.4 minutes).
 * Each epoch contains block proposals, attestations, and validator activity.
 * 
 * <p>This data structure is used to bridge Ethereum blockchain data into
 * the Oak content repository, demonstrating real-world blockchain integration.
 */
public class EpochData {
    /** Epoch number (monotonically increasing) */
    public long epochNumber;
    
    /** Unix timestamp in milliseconds */
    public long timestamp;
    
    /** Number of blocks proposed in this epoch (max 32) */
    public int blocksProposed;
    
    /** Number of blocks that were skipped (missed) */
    public int blocksSkipped;
    
    /** Number of attestations (validator votes) */
    public int attestations;
    
    /** Total number of validators in the network */
    public long totalValidators;
    
    /** Number of active (participating) validators */
    public long activeValidators;
    
    /** Number of slashings (penalties) in this epoch */
    public int slashings;
    
    /** Number of new validator deposits */
    public int deposits;
    
    /** Number of voluntary validator exits */
    public int voluntaryExits;
    
    /** Whether this epoch has been finalized (irreversible) */
    public boolean finalized;
    
    /** Timestamp when this epoch was finalized (0 if not finalized) */
    public long finalizedAt;
    
    /** Number of epochs behind current (0 = current, 2 = finalized) */
    public int epochsBehindCurrent;
    
    /** Block root hash (for verification) */
    public String blockRoot;
    
    @Override
    public String toString() {
        return String.format(
            "Epoch{number=%d, blocks=%d/%d, attestations=%d, validators=%d/%d, finalized=%s}",
            epochNumber, 
            blocksProposed - blocksSkipped, 
            blocksProposed, 
            attestations, 
            activeValidators, 
            totalValidators, 
            finalized
        );
    }
}

