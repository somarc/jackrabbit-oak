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
package org.apache.jackrabbit.oak.segment.http.server;

import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.apache.jackrabbit.oak.segment.consensus.ConsensusEngine;
import org.apache.jackrabbit.oak.segment.consensus.leader.EpochLeaderEngine;
import org.apache.jackrabbit.oak.segment.consensus.dag.DagConsensusEngine;
import org.apache.jackrabbit.oak.segment.consensus.aeron.AeronConsensusEngine;
import org.apache.jackrabbit.oak.segment.consensus.security.ProofVerifier;
import org.apache.jackrabbit.oak.segment.consensus.state.ConsensusStateService;
import org.apache.jackrabbit.oak.spi.state.NodeStore;
import org.apache.jackrabbit.oak.segment.http.server.model.ClientRegistration;
import org.apache.jackrabbit.oak.segment.http.server.model.ValidatorRegistration;
import org.apache.jackrabbit.oak.segment.http.server.model.WriteMetadata;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared context for HTTP server handlers.
 * 
 * <p>Holds all shared dependencies and state that handlers need access to.
 * This avoids passing many individual parameters to each handler.</p>
 */
public class ServerContext {
    public final FileStore fileStore;
    public final NodeStore nodeStore;
    public final Path storeDirectory;
    public volatile ConsensusEngine consensusEngine;
    public volatile DagConsensusEngine dagConsensusEngine;
    public volatile EpochLeaderEngine epochLeaderEngine;
    public volatile AeronConsensusEngine aeronConsensusEngine;
    public volatile ProofVerifier proofVerifier;
    public volatile String selfUrl;
    public volatile ConsensusStateService consensusStateService;
    
    // Shared state
    public final Map<String, ClientRegistration> registeredClients;
    public final Map<String, ValidatorRegistration> registeredValidators;
    public final Set<String> connectedPeers;
    public final Map<String, WriteMetadata> recentWriteMetadata;
    
    // Validator identity for rejoin
    public volatile String myValidatorId;
    public volatile String myValidatorUrl;
    public volatile java.util.List<String> myPeerUrls;
    
    public ServerContext(
            FileStore fileStore,
            NodeStore nodeStore,
            Path storeDirectory,
            String selfUrl) {
        this.fileStore = fileStore;
        this.nodeStore = nodeStore;
        this.storeDirectory = storeDirectory;
        this.selfUrl = selfUrl;
        
        // Initialize shared state
        this.registeredClients = new ConcurrentHashMap<>();
        this.registeredValidators = new ConcurrentHashMap<>();
        this.connectedPeers = java.util.concurrent.ConcurrentHashMap.newKeySet();
        this.recentWriteMetadata = new ConcurrentHashMap<>();
    }
    
    // Setters for consensus engines (can be set after construction)
    public void setConsensusEngine(ConsensusEngine consensusEngine) {
        this.consensusEngine = consensusEngine;
    }
    
    public void setDagConsensusEngine(DagConsensusEngine dagConsensusEngine) {
        this.dagConsensusEngine = dagConsensusEngine;
    }
    
    public void setEpochLeaderEngine(EpochLeaderEngine epochLeaderEngine) {
        this.epochLeaderEngine = epochLeaderEngine;
        // Create ConsensusStateService when epochLeaderEngine is set
        if (epochLeaderEngine != null && selfUrl != null) {
            this.consensusStateService = new ConsensusStateService(epochLeaderEngine, selfUrl);
        }
    }
    
    public void setProofVerifier(ProofVerifier proofVerifier) {
        this.proofVerifier = proofVerifier;
    }
    
    public void setSelfUrl(String selfUrl) {
        this.selfUrl = selfUrl;
        // Create ConsensusStateService if epochLeaderEngine is already set
        if (epochLeaderEngine != null && selfUrl != null) {
            this.consensusStateService = new ConsensusStateService(epochLeaderEngine, selfUrl);
        }
    }
    
    public void setConsensusStateService(ConsensusStateService consensusStateService) {
        this.consensusStateService = consensusStateService;
    }
    
    public void setAeronConsensusEngine(AeronConsensusEngine aeronConsensusEngine) {
        this.aeronConsensusEngine = aeronConsensusEngine;
    }
}

