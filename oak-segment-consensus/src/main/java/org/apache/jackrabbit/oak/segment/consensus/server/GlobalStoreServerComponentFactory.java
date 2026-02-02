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

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.function.Supplier;

import org.apache.jackrabbit.oak.segment.consensus.evm.EvmBridge;
import org.apache.jackrabbit.oak.segment.consensus.fragmentation.FragmentationTracker;
import org.apache.jackrabbit.oak.segment.consensus.fragmentation.WalletStorageMetrics;
import org.apache.jackrabbit.oak.segment.consensus.gc.GCAccountManager;
import org.apache.jackrabbit.oak.segment.consensus.gc.GCCostEstimator;
import org.apache.jackrabbit.oak.segment.consensus.gc.GCProposalManager;
import org.apache.jackrabbit.oak.segment.consensus.gc.PeriodicGCJob;
import org.apache.jackrabbit.oak.segment.consensus.security.EthereumWallet;
import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.apache.jackrabbit.oak.segment.file.tar.TarFiles;
import org.apache.jackrabbit.oak.segment.http.server.SegmentHttpServer;
import org.apache.jackrabbit.oak.segment.http.server.binary.CidMappingService;
import org.apache.jackrabbit.oak.spi.blob.BlobStore;
import org.apache.jackrabbit.oak.spi.state.NodeStore;

public interface GlobalStoreServerComponentFactory {

    EthereumWallet createEthereumWallet(String keystorePath) throws Exception;

    org.apache.jackrabbit.oak.segment.consensus.aeron.AeronClusterService createAeronClusterService();

    BlobStore createIpfsBlobStore(String ipfsEndpoint, java.io.File storeDir) throws Exception;

    GCCostEstimator createGCCostEstimator(FileStore fileStore, TarFiles tarFiles, BigDecimal usdcPerMB);

    SegmentHttpServer createHttpServer(java.io.File storeDir, int port, FileStore fileStore, NodeStore nodeStore);

    CidMappingService createCidMappingService(Path storeDir) throws Exception;

    FragmentationTracker createFragmentationTracker();

    WalletStorageMetrics createWalletStorageMetrics(FileStore fileStore);

    GCProposalManager createGCProposalManager(FileStore fileStore,
                                              GCCostEstimator gcCostEstimator,
                                              FragmentationTracker fragmentationTracker,
                                              EvmBridge evmBridge,
                                              int totalValidators,
                                              Supplier<Integer> executorIdSupplier,
                                              Supplier<Boolean> isLeaderSupplier);

    GCAccountManager createGCAccountManager();

    PeriodicGCJob createPeriodicGCJob(GCAccountManager accountManager);

    ValidatorBootstrap createValidatorBootstrap(FileStore fileStore, int standbyPort);

    GenesisInitializer createGenesisInitializer(NodeStore nodeStore, FileStore fileStore, BlobStore blobStore);

    ConsensusServicesInitializer createConsensusServicesInitializer();
}
