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

final class ConsensusServicesInitializer {

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
                    System.err.println("❌ aeronEngine is NULL in appendProposal!");
                    return;
                }
                System.out.println("📤 appendProposal() called - forwarding to Aeron (role: " + aeronEngine.getCurrentRole() + ")");
                boolean success = aeronEngine.sendWriteThroughIngress(walletAddress, path, contentType, message, signature);
                if (!success) {
                    System.err.println("❌ sendWriteThroughIngress() returned false!");
                }
            }

            @Override
            public void appendProposalWithId(String proposalId, String walletAddress, String path, String contentType,
                                             String message, String signature) {
                if (aeronEngine == null) {
                    System.err.println("❌ aeronEngine is NULL in appendProposalWithId!");
                    return;
                }
                boolean success = aeronEngine.sendWriteThroughIngressWithId(
                    walletAddress, path, contentType, message, signature, null, proposalId);
                if (!success) {
                    System.err.println("❌ sendWriteThroughIngress() returned false!");
                }
            }

            @Override
            public void appendProposal(String walletAddress, String path, String contentType, String message,
                                       String signature, String blobId, String mimeType) {
                if (aeronEngine == null) {
                    System.err.println("❌ aeronEngine is NULL in appendProposal!");
                    return;
                }
                System.out.println("📤 appendProposal() with binary - blobId=" + blobId + " (role: " + aeronEngine.getCurrentRole() + ")");
                boolean success = aeronEngine.sendWriteThroughIngress(walletAddress, path, contentType, message, signature, blobId, mimeType);
                if (!success) {
                    System.err.println("❌ sendWriteThroughIngress() with binary returned false!");
                }
            }

            @Override
            public void appendProposalWithId(String proposalId, String walletAddress, String path, String contentType,
                                             String message, String signature, String blobId, String mimeType, String ipfsCid) {
                if (aeronEngine == null) {
                    System.err.println("❌ aeronEngine is NULL in appendProposalWithId!");
                    return;
                }
                boolean success = aeronEngine.sendWriteThroughIngress(
                    walletAddress, path, contentType, message, signature, blobId, mimeType, ipfsCid, proposalId);
                if (!success) {
                    System.err.println("❌ sendWriteThroughIngress() with binary returned false!");
                }
            }

            @Override
            public void appendDeleteProposal(String walletAddress, String path, String signature) {
                if (aeronEngine == null) {
                    System.err.println("❌ aeronEngine is NULL in appendDeleteProposal!");
                    return;
                }
                System.out.println("🗑️  appendDeleteProposal() called - forwarding to Aeron (role: " + aeronEngine.getCurrentRole() + ")");
                boolean success = aeronEngine.sendDeleteThroughIngress(walletAddress, path, signature);
                if (!success) {
                    System.err.println("❌ sendDeleteThroughIngress() returned false!");
                }
            }

            @Override
            public void appendDeleteProposalWithId(String proposalId, String walletAddress, String path, String signature) {
                if (aeronEngine == null) {
                    System.err.println("❌ aeronEngine is NULL in appendDeleteProposalWithId!");
                    return;
                }
                boolean success = aeronEngine.sendDeleteThroughIngress(walletAddress, path, signature, proposalId);
                if (!success) {
                    System.err.println("❌ sendDeleteThroughIngress() returned false!");
                }
            }

            @Override
            public int appendProposalBatch(List<QueuedProposal> proposals) {
                System.out.println("🔥🔥🔥 OVERRIDE CALLED: appendProposalBatch() - batch size: " + proposals.size() +
                    ", class: " + this.getClass().getName());

                if (aeronEngine == null) {
                    System.err.println("❌ aeronEngine is NULL in appendProposalBatch!");
                    return 0;
                }
                System.out.println("📤 appendProposalBatch() forwarding to aeronEngine.sendWriteBatchThroughIngress() - role: " + aeronEngine.getCurrentRole());
                int sent = aeronEngine.sendWriteBatchThroughIngress(proposals);
                System.out.println("📤 appendProposalBatch() result: " + sent + " proposals sent");
                if (sent == 0) {
                    System.err.println("❌ sendWriteBatchThroughIngress() returned 0 (failed)!");
                }
                return sent;
            }
        };

        BackpressureManager backpressureManager =
            aeronEngine != null ? aeronEngine.getBackpressureManager() : null;

        if (backpressureManager == null) {
            System.out.println("   ⚠️  WARNING: BackpressureManager not available - using fallback");
            backpressureManager = new BackpressureManager();
        }

        BeaconChainClient beaconClient = new BeaconChainClient(beaconApiUrl);
        beaconClient.startBackgroundPolling();
        System.out.println("   ✅ Beacon Chain client initialized (tracking Ethereum epochs from " + beaconApiUrl + ")");

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
        proposalQueueManager.start();
        System.out.println("   ✅ Proposal Queue Manager initialized (Ethereum epoch-based batching + 3-checkpoint security)");

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

        // Aeron Cluster handles membership via Raft consensus - HTTP registration is legacy
        String validatorId = wallet.getWalletAddress();
        httpServer.registerWithPeers(validatorId, java.util.Collections.emptyList());
        System.out.println("   - Self registered: " + validatorId + " (Aeron Cluster handles peer membership via Raft)");
    }
}
