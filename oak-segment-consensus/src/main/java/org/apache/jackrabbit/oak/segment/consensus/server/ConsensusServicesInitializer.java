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

import org.apache.jackrabbit.oak.segment.consensus.aeron.AeronConsensusEngine;
import org.apache.jackrabbit.oak.segment.consensus.config.BlockchainConfig;
import org.apache.jackrabbit.oak.segment.consensus.economics.ValidatorEarningsTracker;
import org.apache.jackrabbit.oak.segment.consensus.evm.EvmBridge;
import org.apache.jackrabbit.oak.segment.consensus.evm.impl.SimpleEvmBridge;
import org.apache.jackrabbit.oak.segment.consensus.queue.BackpressureManager;
import org.apache.jackrabbit.oak.segment.consensus.queue.ProposalQueueManagerOptimized;
import org.apache.jackrabbit.oak.segment.consensus.queue.QueuedProposal;
import org.apache.jackrabbit.oak.segment.consensus.queue.RaftAppendCallback;
import org.apache.jackrabbit.oak.segment.consensus.security.EthereumWallet;
import org.apache.jackrabbit.oak.segment.consensus.eth.BeaconChainClient;
import org.apache.jackrabbit.oak.segment.http.server.SegmentHttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class ConsensusServicesInitializer {

    private static final Logger log = LoggerFactory.getLogger(ConsensusServicesInitializer.class);

    void initialize(AeronConsensusEngine aeronEngine,
                    SegmentHttpServer httpServer,
                    EthereumWallet wallet,
                    String storeDirectory,
                    String beaconApiUrl,
                    String finalClusterWallet,
                    List<String> hostnamesList) {
        // Initialize Proposal Queue Manager (for Ethereum confirmation tracking)
        BlockchainConfig blockchainConfig = BlockchainConfig.getInstance();

        EvmBridge evmBridge = new SimpleEvmBridge(
            blockchainConfig.getNetwork(),
            blockchainConfig.getContractAddress()
        );
        evmBridge.start();

        RaftAppendCallback raftCallback = new RaftAppendCallback() {
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

        BackpressureManager backpressureManager =
            aeronEngine != null ? aeronEngine.getBackpressureManager() : null;

        if (backpressureManager == null) {
            log.warn("⚠️  BackpressureManager not available - using fallback");
            backpressureManager = new BackpressureManager();
        }

        BeaconChainClient beaconClient = new BeaconChainClient(beaconApiUrl);
        beaconClient.startBackgroundPolling();
        log.info("✅ Beacon Chain client initialized (tracking Ethereum epochs from {})", beaconApiUrl);

        ProposalQueueManagerOptimized proposalQueueManager =
            new ProposalQueueManagerOptimized(
                evmBridge,
                raftCallback,
                backpressureManager,
                beaconClient,
                new java.io.File(storeDirectory, "proposal-queue").getAbsolutePath()
            );
        proposalQueueManager.start();
        httpServer.getContext().setProposalQueueManager(proposalQueueManager);
        httpServer.getContext().evmBridge = evmBridge;
        log.info("✅ Proposal Queue Manager initialized (Ethereum epoch-based batching + 3-checkpoint security)");

        // Initialize Validator Earnings Tracker (economic simulation)
        List<String> validatorWallets = new ArrayList<>();
        validatorWallets.add(wallet.getWalletAddress());

        int expectedValidators = hostnamesList != null ? hostnamesList.size() : 1;
        for (int i = 1; i < expectedValidators; i++) {
            validatorWallets.add("0x" + String.format("%040x", i));
        }

        ValidatorEarningsTracker earningsTracker = new ValidatorEarningsTracker(validatorWallets);
        httpServer.getContext().setValidatorEarningsTracker(earningsTracker);
        System.out.println("   ✅ Validator Earnings Tracker initialized (" + validatorWallets.size() + " validators)");
        System.out.println("   - Self wallet: " + wallet.getWalletAddress());

        httpServer.getContext().validatorWalletAddress = wallet.getWalletAddress();
        httpServer.getContext().clusterWalletAddress = finalClusterWallet;
        System.out.println("   - Payments routed to cluster wallet: " + finalClusterWallet);

        // Aeron Cluster handles membership via Raft consensus.
        // Keep only local self-registration for compatibility state (health/metrics/peer views).
        String validatorId = wallet.getWalletAddress();
        httpServer.registerSelfValidator(validatorId);
        System.out.println("   - Self registered (local context only): " + validatorId);
    }
}
