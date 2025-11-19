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
import org.apache.jackrabbit.oak.segment.consensus.leader.EpochLeaderEngine;
import org.apache.jackrabbit.oak.segment.consensus.aeron.AeronConsensusEngine;
import org.apache.jackrabbit.oak.segment.consensus.security.ProofVerifier;
import org.apache.jackrabbit.oak.segment.consensus.state.ConsensusStateService;
import org.apache.jackrabbit.oak.segment.consensus.gc.GCCostEstimator;
import org.apache.jackrabbit.oak.segment.consensus.gc.GCProposalManager;
import org.apache.jackrabbit.oak.segment.consensus.queue.ProposalQueueManager;
import org.apache.jackrabbit.oak.segment.consensus.fragmentation.FragmentationTracker;
import org.apache.jackrabbit.oak.segment.consensus.sharding.ShardRouter;
import org.apache.jackrabbit.oak.spi.state.NodeStore;
import org.apache.jackrabbit.oak.segment.http.server.model.ClientRegistration;
import org.apache.jackrabbit.oak.segment.http.server.model.ValidatorRegistration;
import org.apache.jackrabbit.oak.segment.http.server.model.WriteMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private static final Logger log = LoggerFactory.getLogger(ServerContext.class);
    
    public final FileStore fileStore;
    public final NodeStore nodeStore;
    public final Path storeDirectory;
    public volatile EpochLeaderEngine epochLeaderEngine;
    public volatile AeronConsensusEngine aeronConsensusEngine;
    public volatile org.apache.jackrabbit.oak.segment.consensus.aeron.AeronWriteClient aeronWriteClient;
    public volatile org.apache.jackrabbit.oak.segment.consensus.aeron.AeronClusterLauncher aeronClusterLauncher;
    public volatile org.apache.jackrabbit.oak.segment.consensus.aeron.AeronPrometheusMetrics aeronPrometheusMetrics;
    public volatile ProofVerifier proofVerifier;
    public volatile String selfUrl;
    public volatile ConsensusStateService consensusStateService;
    public volatile GCCostEstimator gcCostEstimator;
    public volatile GCProposalManager gcProposalManager;
    public volatile ProposalQueueManager proposalQueueManager;
    public volatile FragmentationTracker fragmentationTracker;
    public volatile org.apache.jackrabbit.oak.segment.consensus.evm.EvmBridge evmBridge;
    public volatile ShardRouter shardRouter; // Optional - for sharded routing
    
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
    
    public void setAeronWriteClient(org.apache.jackrabbit.oak.segment.consensus.aeron.AeronWriteClient aeronWriteClient) {
        log.info("🔧 ServerContext.setAeronWriteClient() called - client: {}", aeronWriteClient != null ? "present" : "NULL");
        this.aeronWriteClient = aeronWriteClient;
        log.info("✅ ServerContext.aeronWriteClient field set - value: {}", this.aeronWriteClient != null ? "present" : "NULL");
    }
    
    public void setAeronClusterLauncher(org.apache.jackrabbit.oak.segment.consensus.aeron.AeronClusterLauncher aeronClusterLauncher) {
        this.aeronClusterLauncher = aeronClusterLauncher;
        log.info("✅ ServerContext.aeronClusterLauncher field set");
        
        // Initialize Aeron Prometheus metrics if Aeron is available
        if (aeronClusterLauncher != null) {
            try {
                io.aeron.Aeron aeron = aeronClusterLauncher.getAeron();
                if (aeron != null) {
                    this.aeronPrometheusMetrics = new org.apache.jackrabbit.oak.segment.consensus.aeron.AeronPrometheusMetrics(aeron);
                    log.info("✅ Aeron Prometheus metrics initialized");
                } else {
                    log.debug("Aeron instance not yet available - metrics will be initialized later");
                }
            } catch (Exception e) {
                log.warn("Failed to initialize Aeron Prometheus metrics", e);
            }
        }
    }
    
    public void setAeronPrometheusMetrics(org.apache.jackrabbit.oak.segment.consensus.aeron.AeronPrometheusMetrics aeronPrometheusMetrics) {
        this.aeronPrometheusMetrics = aeronPrometheusMetrics;
    }
    
    public void setGCCostEstimator(GCCostEstimator gcCostEstimator) {
        this.gcCostEstimator = gcCostEstimator;
        log.info("✅ GC Cost Estimator initialized");
    }
    
    public void setProposalQueueManager(ProposalQueueManager proposalQueueManager) {
        this.proposalQueueManager = proposalQueueManager;
        log.info("✅ Proposal Queue Manager initialized");
    }
    
    public void setFragmentationTracker(FragmentationTracker fragmentationTracker) {
        this.fragmentationTracker = fragmentationTracker;
        log.info("✅ Fragmentation Tracker initialized");
    }
    
    public void setGCProposalManager(GCProposalManager gcProposalManager) {
        this.gcProposalManager = gcProposalManager;
        log.info("✅ GC Proposal Manager initialized");
    }
    
    public void setShardRouter(ShardRouter shardRouter) {
        this.shardRouter = shardRouter;
        log.info("✅ Shard Router initialized");
    }
}

