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
package org.apache.jackrabbit.oak.segment.consensus.server;

import java.util.List;
import java.util.function.Supplier;

import org.apache.jackrabbit.oak.segment.consensus.aeron.AeronConsensusEngine;
import org.apache.jackrabbit.oak.segment.consensus.config.BlockchainConfig;
import org.apache.jackrabbit.oak.segment.consensus.config.BlockchainRuntimeValidator;
import org.apache.jackrabbit.oak.segment.consensus.config.RuntimeConfigValueResolver;
import org.apache.jackrabbit.oak.segment.consensus.evm.EvmBridge;
import org.apache.jackrabbit.oak.segment.consensus.evm.impl.EventDrivenEvmBridge;
import org.apache.jackrabbit.oak.segment.consensus.evm.impl.SimpleEvmBridge;
import org.apache.jackrabbit.oak.segment.consensus.queue.BackpressureManager;
import org.apache.jackrabbit.oak.segment.consensus.queue.ProposalQueueManagerOptimized;
import org.apache.jackrabbit.oak.segment.consensus.queue.QueuedProposal;
import org.apache.jackrabbit.oak.segment.consensus.queue.RaftAppendCallback;
import org.apache.jackrabbit.oak.segment.consensus.service.MutationAuditMetadata;
import org.apache.jackrabbit.oak.segment.consensus.security.EthereumWallet;
import org.apache.jackrabbit.oak.segment.consensus.eth.BeaconChainClient;
import org.apache.jackrabbit.oak.segment.http.server.SegmentHttpServer;
import org.apache.jackrabbit.oak.segment.http.server.ServerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class ConsensusServicesInitializer {

    private static final Logger log = LoggerFactory.getLogger(ConsensusServicesInitializer.class);

    private final Supplier<BlockchainConfig> blockchainConfigSupplier;
    private final EvmBridgeFactory evmBridgeFactory;
    private final Supplier<BeaconChainClient> beaconChainClientFactory;
    private final ProposalQueueManagerFactory proposalQueueManagerFactory;
    private final RuntimeConfigReader runtimeConfigReader;

    ConsensusServicesInitializer() {
        this(BlockchainConfig::getInstance,
            blockchainConfig -> blockchainConfig.isMockMode()
                ? new SimpleEvmBridge(blockchainConfig.getNetwork(), blockchainConfig.getContractAddress())
                : new EventDrivenEvmBridge(blockchainConfig.getNetwork(), blockchainConfig.getContractAddress()),
            BeaconChainClient::new,
            ProposalQueueManagerOptimized::new,
            RuntimeConfigValueResolver::readString);
    }

    ConsensusServicesInitializer(
            Supplier<BlockchainConfig> blockchainConfigSupplier,
            EvmBridgeFactory evmBridgeFactory,
            Supplier<BeaconChainClient> beaconChainClientFactory,
            ProposalQueueManagerFactory proposalQueueManagerFactory,
            RuntimeConfigReader runtimeConfigReader) {
        this.blockchainConfigSupplier = blockchainConfigSupplier;
        this.evmBridgeFactory = evmBridgeFactory;
        this.beaconChainClientFactory = beaconChainClientFactory;
        this.proposalQueueManagerFactory = proposalQueueManagerFactory;
        this.runtimeConfigReader = runtimeConfigReader;
    }

    void initialize(AeronConsensusEngine aeronEngine,
                    SegmentHttpServer httpServer,
                    EthereumWallet wallet,
                    String storeDirectory,
                    String finalClusterWallet) {
        // Initialize Proposal Queue Manager (for Ethereum confirmation tracking)
        BlockchainConfig blockchainConfig = blockchainConfigSupplier.get();
        if (blockchainConfig != null) {
            BlockchainRuntimeValidator.validate(blockchainConfig);
        }
        EvmBridge evmBridge = evmBridgeFactory.create(blockchainConfig);
        if (blockchainConfig.isMockMode()) {
            log.info("🎭 Using SimpleEvmBridge (mock simulation)");
        } else {
            log.info("🌐 Using EventDrivenEvmBridge (real blockchain event verification)");
        }
        evmBridge.start();

        RaftAppendCallback raftCallback = createRaftAppendCallback(aeronEngine);
        BackpressureManager backpressureManager = resolveBackpressureManager(aeronEngine);
        BeaconChainClient beaconClient = aeronEngine != null ? aeronEngine.getBeaconClient() : null;
        if (beaconClient == null) {
            beaconClient = beaconChainClientFactory.get();
            beaconClient.startBackgroundPolling();
            log.info("✅ Beacon Chain client initialized (providers={})", beaconClient.getHealthStatus().get("providers"));
        } else {
            log.info("✅ Reusing Aeron engine Beacon Chain client for proposal queue epoch telemetry");
        }

        String proposalPersistenceDir = runtimeConfigReader.readString(
            "oak.proposal.persistence.dir",
            "OAK_PROPOSAL_PERSISTENCE_DIR",
            new java.io.File(storeDirectory, "proposal-queue").getAbsolutePath()
        );

        ProposalQueueManagerOptimized proposalQueueManager = proposalQueueManagerFactory.create(
            evmBridge,
            raftCallback,
            backpressureManager,
            beaconClient,
            proposalPersistenceDir
        );
        if (aeronEngine != null) {
            proposalQueueManager.setReplicatedDurability(aeronEngine::durabilityDecision);
        }
        proposalQueueManager.start();
        ServerContext context = httpServer.getContext();
        context.setProposalQueueManager(proposalQueueManager);
        context.evmBridge = evmBridge;
        log.info("✅ Proposal Queue Manager initialized (adaptive packing/release + 3-checkpoint security)");

        context.validatorWalletAddress = wallet.getWalletAddress();
        context.clusterWalletAddress = finalClusterWallet;
        log.info("   - Payments routed to cluster wallet: {}", finalClusterWallet);

        // Aeron Cluster handles membership via Raft consensus.
        // Keep only local self-registration for compatibility state (health/metrics/peer views).
        String validatorId = wallet.getWalletAddress();
        httpServer.registerSelfValidator(validatorId);
        log.info("   - Self registered (local context only): {}", validatorId);
    }

    private static BackpressureManager resolveBackpressureManager(AeronConsensusEngine aeronEngine) {
        BackpressureManager backpressureManager =
            aeronEngine != null ? aeronEngine.getBackpressureManager() : null;

        if (backpressureManager == null) {
            log.warn("⚠️  BackpressureManager not available - using fallback");
            return new BackpressureManager();
        }
        return backpressureManager;
    }

    private static RaftAppendCallback createRaftAppendCallback(AeronConsensusEngine aeronEngine) {
        return new RaftAppendCallback() {
            @Override
            public boolean tryAppendProposalWithId(String proposalId, String walletAddress, String path, String contentType,
                                                   String message, String signature, String blobId, String mimeType,
                                                   String ipfsCid, MutationAuditMetadata auditMetadata) {
                if (aeronEngine == null) {
                    log.error("❌ aeronEngine is NULL in appendProposalWithId!");
                    return false;
                }
                boolean success = aeronEngine.sendWriteThroughIngress(
                    walletAddress, path, contentType, message, signature, blobId, mimeType, ipfsCid, auditMetadata);
                if (!success) {
                    log.error("❌ sendWriteThroughIngress() with binary returned false!");
                }
                return success;
            }

            @Override
            public boolean tryAppendDeleteProposalWithId(String proposalId, String walletAddress, String path,
                                                         String signature, MutationAuditMetadata auditMetadata) {
                if (aeronEngine == null) {
                    log.error("❌ aeronEngine is NULL in appendDeleteProposalWithId!");
                    return false;
                }
                boolean success = aeronEngine.sendDeleteThroughIngress(walletAddress, path, signature, auditMetadata);
                if (!success) {
                    log.error("❌ sendDeleteThroughIngress() returned false!");
                }
                return success;
            }

            @Override
            public int appendProposalBatch(List<QueuedProposal> proposals) {
                if (aeronEngine == null) {
                    log.error("❌ aeronEngine is NULL in appendProposalBatch!");
                    return 0;
                }
                log.debug("📤 appendProposalBatch() forwarding to aeronEngine.sendWriteBatchThroughIngress() - role: {}",
                    aeronEngine.getCurrentRole());
                int sent = aeronEngine.sendWriteBatchThroughIngress(proposals);
                log.debug("📤 appendProposalBatch() result: {} proposals sent", sent);
                if (sent == 0) {
                    log.error("❌ sendWriteBatchThroughIngress() returned 0 (failed)!");
                }
                return sent;
            }
        };
    }

    interface EvmBridgeFactory {
        EvmBridge create(BlockchainConfig blockchainConfig);
    }

    interface ProposalQueueManagerFactory {
        ProposalQueueManagerOptimized create(EvmBridge evmBridge,
                                             RaftAppendCallback raftCallback,
                                             BackpressureManager backpressureManager,
                                             BeaconChainClient beaconClient,
                                             String proposalPersistenceDir);
    }

    interface RuntimeConfigReader {
        String readString(String propertyName, String environmentVariable, String defaultValue);
    }
}
