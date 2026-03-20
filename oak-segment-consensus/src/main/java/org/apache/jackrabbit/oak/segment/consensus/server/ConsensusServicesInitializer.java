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

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.apache.jackrabbit.oak.segment.consensus.aeron.AeronConsensusEngine;
import org.apache.jackrabbit.oak.segment.consensus.config.BlockchainConfig;
import org.apache.jackrabbit.oak.segment.consensus.config.RuntimeConfigValueResolver;
import org.apache.jackrabbit.oak.segment.consensus.economics.ValidatorEarningsTracker;
import org.apache.jackrabbit.oak.segment.consensus.evm.EvmBridge;
import org.apache.jackrabbit.oak.segment.consensus.evm.impl.EventDrivenEvmBridge;
import org.apache.jackrabbit.oak.segment.consensus.evm.impl.SimpleEvmBridge;
import org.apache.jackrabbit.oak.segment.consensus.queue.BackpressureManager;
import org.apache.jackrabbit.oak.segment.consensus.queue.ProposalQueueManagerOptimized;
import org.apache.jackrabbit.oak.segment.consensus.queue.QueuedProposal;
import org.apache.jackrabbit.oak.segment.consensus.queue.RaftAppendCallback;
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
    private final BeaconChainClientFactory beaconChainClientFactory;
    private final ProposalQueueManagerFactory proposalQueueManagerFactory;
    private final ValidatorEarningsTrackerFactory validatorEarningsTrackerFactory;
    private final RuntimeConfigReader runtimeConfigReader;

    ConsensusServicesInitializer() {
        this(BlockchainConfig::getInstance,
            blockchainConfig -> blockchainConfig.isMockMode()
                ? new SimpleEvmBridge(blockchainConfig.getNetwork(), blockchainConfig.getContractAddress())
                : new EventDrivenEvmBridge(blockchainConfig.getNetwork(), blockchainConfig.getContractAddress(), false),
            BeaconChainClient::new,
            ProposalQueueManagerOptimized::new,
            ValidatorEarningsTracker::new,
            RuntimeConfigValueResolver::readString);
    }

    ConsensusServicesInitializer(
            Supplier<BlockchainConfig> blockchainConfigSupplier,
            EvmBridgeFactory evmBridgeFactory,
            BeaconChainClientFactory beaconChainClientFactory,
            ProposalQueueManagerFactory proposalQueueManagerFactory,
            ValidatorEarningsTrackerFactory validatorEarningsTrackerFactory,
            RuntimeConfigReader runtimeConfigReader) {
        this.blockchainConfigSupplier = blockchainConfigSupplier;
        this.evmBridgeFactory = evmBridgeFactory;
        this.beaconChainClientFactory = beaconChainClientFactory;
        this.proposalQueueManagerFactory = proposalQueueManagerFactory;
        this.validatorEarningsTrackerFactory = validatorEarningsTrackerFactory;
        this.runtimeConfigReader = runtimeConfigReader;
    }

    void initialize(AeronConsensusEngine aeronEngine,
                    SegmentHttpServer httpServer,
                    EthereumWallet wallet,
                    String storeDirectory,
                    String beaconApiUrl,
                    String finalClusterWallet,
                    List<String> hostnamesList) {
        // Initialize Proposal Queue Manager (for Ethereum confirmation tracking)
        BlockchainConfig blockchainConfig = blockchainConfigSupplier.get();
        EvmBridge evmBridge = evmBridgeFactory.create(blockchainConfig);
        if (blockchainConfig.isMockMode()) {
            log.info("🎭 Using SimpleEvmBridge (mock simulation)");
        } else {
            log.info("🌐 Using EventDrivenEvmBridge (real blockchain event verification)");
        }
        evmBridge.start();

        RaftAppendCallback raftCallback = createRaftAppendCallback(aeronEngine);
        BackpressureManager backpressureManager = resolveBackpressureManager(aeronEngine);
        BeaconChainClient beaconClient = beaconChainClientFactory.create(beaconApiUrl);
        beaconClient.startBackgroundPolling();
        log.info("✅ Beacon Chain client initialized (tracking Ethereum epochs from {})", beaconApiUrl);

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
        proposalQueueManager.start();
        ServerContext context = httpServer.getContext();
        context.setProposalQueueManager(proposalQueueManager);
        context.evmBridge = evmBridge;
        log.info("✅ Proposal Queue Manager initialized (Ethereum epoch-based batching + 3-checkpoint security)");

        // Initialize Validator Earnings Tracker (economic simulation)
        List<String> validatorWallets = buildValidatorWallets(wallet.getWalletAddress(), hostnamesList);
        ValidatorEarningsTracker earningsTracker = validatorEarningsTrackerFactory.create(validatorWallets);
        context.setValidatorEarningsTracker(earningsTracker);
        System.out.println("   ✅ Validator Earnings Tracker initialized (" + validatorWallets.size() + " validators)");
        System.out.println("   - Self wallet: " + wallet.getWalletAddress());

        context.validatorWalletAddress = wallet.getWalletAddress();
        context.clusterWalletAddress = finalClusterWallet;
        System.out.println("   - Payments routed to cluster wallet: " + finalClusterWallet);

        // Aeron Cluster handles membership via Raft consensus.
        // Keep only local self-registration for compatibility state (health/metrics/peer views).
        String validatorId = wallet.getWalletAddress();
        httpServer.registerSelfValidator(validatorId);
        System.out.println("   - Self registered (local context only): " + validatorId);
    }

    static List<String> buildValidatorWallets(String selfWalletAddress, List<String> hostnamesList) {
        List<String> validatorWallets = new ArrayList<>();
        validatorWallets.add(selfWalletAddress);

        int expectedValidators = hostnamesList != null ? hostnamesList.size() : 1;
        for (int i = 1; i < expectedValidators; i++) {
            validatorWallets.add("0x" + String.format("%040x", i));
        }
        return validatorWallets;
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
            public void appendProposal(String walletAddress, String path, String contentType, String message, String signature) {
                if (aeronEngine == null) {
                    log.error("❌ aeronEngine is NULL in appendProposal!");
                    return;
                }
                log.debug("📤 appendProposal() called - forwarding to Aeron (role: {})", aeronEngine.getCurrentRole());
                boolean success = aeronEngine.sendWriteThroughIngress(walletAddress, path, contentType, message, signature);
                if (!success) {
                    log.error("❌ sendWriteThroughIngress() returned false!");
                }
            }

            @Override
            public void appendProposalWithId(String proposalId, String walletAddress, String path, String contentType,
                                             String message, String signature) {
                if (aeronEngine == null) {
                    log.error("❌ aeronEngine is NULL in appendProposalWithId!");
                    return;
                }
                boolean success = aeronEngine.sendWriteThroughIngressWithId(
                    walletAddress, path, contentType, message, signature, null, proposalId);
                if (!success) {
                    log.error("❌ sendWriteThroughIngress() returned false!");
                }
            }

            @Override
            public void appendProposal(String walletAddress, String path, String contentType, String message,
                                       String signature, String blobId, String mimeType) {
                if (aeronEngine == null) {
                    log.error("❌ aeronEngine is NULL in appendProposal!");
                    return;
                }
                log.debug("📤 appendProposal() with binary - blobId={} (role: {})", blobId, aeronEngine.getCurrentRole());
                boolean success = aeronEngine.sendWriteThroughIngress(walletAddress, path, contentType, message, signature, blobId, mimeType);
                if (!success) {
                    log.error("❌ sendWriteThroughIngress() with binary returned false!");
                }
            }

            @Override
            public void appendProposalWithId(String proposalId, String walletAddress, String path, String contentType,
                                             String message, String signature, String blobId, String mimeType, String ipfsCid) {
                if (aeronEngine == null) {
                    log.error("❌ aeronEngine is NULL in appendProposalWithId!");
                    return;
                }
                boolean success = aeronEngine.sendWriteThroughIngress(
                    walletAddress, path, contentType, message, signature, blobId, mimeType, ipfsCid, proposalId);
                if (!success) {
                    log.error("❌ sendWriteThroughIngress() with binary returned false!");
                }
            }

            @Override
            public void appendDeleteProposal(String walletAddress, String path, String signature) {
                if (aeronEngine == null) {
                    log.error("❌ aeronEngine is NULL in appendDeleteProposal!");
                    return;
                }
                log.debug("🗑️  appendDeleteProposal() called - forwarding to Aeron (role: {})", aeronEngine.getCurrentRole());
                boolean success = aeronEngine.sendDeleteThroughIngress(walletAddress, path, signature);
                if (!success) {
                    log.error("❌ sendDeleteThroughIngress() returned false!");
                }
            }

            @Override
            public void appendDeleteProposalWithId(String proposalId, String walletAddress, String path, String signature) {
                if (aeronEngine == null) {
                    log.error("❌ aeronEngine is NULL in appendDeleteProposalWithId!");
                    return;
                }
                boolean success = aeronEngine.sendDeleteThroughIngress(walletAddress, path, signature, proposalId);
                if (!success) {
                    log.error("❌ sendDeleteThroughIngress() returned false!");
                }
            }

            @Override
            public int appendProposalBatch(List<QueuedProposal> proposals) {
                log.debug("🔥🔥🔥 OVERRIDE CALLED: appendProposalBatch() - batch size: {}, class: {}",
                    proposals.size(), this.getClass().getName());

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

    interface BeaconChainClientFactory {
        BeaconChainClient create(String beaconApiUrl);
    }

    interface ProposalQueueManagerFactory {
        ProposalQueueManagerOptimized create(EvmBridge evmBridge,
                                             RaftAppendCallback raftCallback,
                                             BackpressureManager backpressureManager,
                                             BeaconChainClient beaconClient,
                                             String proposalPersistenceDir);
    }

    interface ValidatorEarningsTrackerFactory {
        ValidatorEarningsTracker create(List<String> validatorWallets);
    }

    interface RuntimeConfigReader {
        String readString(String propertyName, String environmentVariable, String defaultValue);
    }
}
