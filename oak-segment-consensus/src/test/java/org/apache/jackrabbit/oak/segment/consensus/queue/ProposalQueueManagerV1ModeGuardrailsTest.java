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

import org.apache.jackrabbit.oak.segment.consensus.eth.BeaconChainClient;
import org.apache.jackrabbit.oak.segment.consensus.economics.ValidatorEarningsTracker;
import org.apache.jackrabbit.oak.segment.consensus.evm.EvmBridge;
import org.apache.jackrabbit.oak.segment.consensus.evm.impl.SimplePaymentProof;
import org.junit.After;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

public class ProposalQueueManagerV1ModeGuardrailsTest {

    @After
    public void tearDown() {
        System.clearProperty("oak.blockchain.mode");
        org.apache.jackrabbit.oak.segment.consensus.config.BlockchainConfig.reset();
    }

    @Test
    public void testVerifierRejectsNonMockProposalIdThatIsNotBytes32() throws Exception {
        System.setProperty("oak.blockchain.mode", "sepolia");
        org.apache.jackrabbit.oak.segment.consensus.config.BlockchainConfig.reset();

        EvmBridge evmBridge = mock(EvmBridge.class);
        when(evmBridge.getContractAddress()).thenReturn("0x1111111111111111111111111111111111111111");
        when(evmBridge.getCurrentBlockNumber()).thenReturn(123L);

        BeaconChainClient beaconClient = mock(BeaconChainClient.class);
        when(beaconClient.getCachedCurrentEpoch()).thenReturn(10L);
        when(beaconClient.getCachedFinalizedEpoch()).thenReturn(8L);

        ProposalQueueManagerOptimized queueManager = new ProposalQueueManagerOptimized(
            evmBridge,
            new NoopRaftAppendCallback(),
            new BackpressureManager(),
            beaconClient
        );
        queueManager.start();
        try {
            queueManager.queueProposal(
                "123e4567-e89b-12d3-a456-426614174000",
                "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "0x1234567890abcdef1234567890abcdef12345678",
                "/oak-chain/12/34/56/0x1234567890abcdef1234567890abcdef12345678/content/page-1",
                "page",
                "message",
                "",
                ValidatorEarningsTracker.PaymentTier.STANDARD,
                null
            );

            assertTrue(waitForCondition(() -> rejectedCount(queueManager) == 1L, 5_000L));
            verifyNoInteractions(evmBridge);
        } finally {
            queueManager.stop();
        }
    }

    @Test
    public void testVerifierRejectsMismatchedDeclaredTransactionHashInChainBackedMode() throws Exception {
        System.setProperty("oak.blockchain.mode", "sepolia");
        org.apache.jackrabbit.oak.segment.consensus.config.BlockchainConfig.reset();

        String proposalId = "0x1111111111111111111111111111111111111111111111111111111111111111";
        String declaredTxHash = "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        String confirmedTxHash = "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
        String walletAddress = "0x1234567890abcdef1234567890abcdef12345678";

        EvmBridge evmBridge = mock(EvmBridge.class);
        when(evmBridge.getContractAddress()).thenReturn("0x1111111111111111111111111111111111111111");
        when(evmBridge.getCurrentBlockNumber()).thenReturn(123L);
        when(evmBridge.verifyPayment(proposalId)).thenReturn(new SimplePaymentProof(
            confirmedTxHash,
            123L,
            walletAddress,
            "0x1111111111111111111111111111111111111111",
            proposalId,
            "1",
            ValidatorEarningsTracker.PaymentTier.STANDARD,
            12
        ));

        BeaconChainClient beaconClient = mock(BeaconChainClient.class);
        when(beaconClient.getCachedCurrentEpoch()).thenReturn(10L);
        when(beaconClient.getCachedFinalizedEpoch()).thenReturn(8L);

        ProposalQueueManagerOptimized queueManager = new ProposalQueueManagerOptimized(
            evmBridge,
            new NoopRaftAppendCallback(),
            new BackpressureManager(),
            beaconClient
        );
        queueManager.start();
        try {
            queueManager.queueProposal(
                proposalId,
                declaredTxHash,
                walletAddress,
                "/oak-chain/12/34/56/0x1234567890abcdef1234567890abcdef12345678/content/page-2",
                "page",
                "message",
                "",
                ValidatorEarningsTracker.PaymentTier.STANDARD,
                null
            );

            assertTrue(waitForCondition(() -> rejectedCount(queueManager) == 1L, 5_000L));
            assertEquals(1L, rejectedCount(queueManager));
            ProposalStatus status = queueManager.getProposalStatus(proposalId);
            assertNotNull(status);
            assertEquals(ProposalState.REJECTED, status.getState());
            assertTrue(status.getRejectionReason().contains("does not match declared ethereumTxHash"));
        } finally {
            queueManager.stop();
        }
    }

    private static long rejectedCount(ProposalQueueManagerOptimized queueManager) {
        Map<String, Object> stats = queueManager.getQueueStats();
        Object value = stats.get("totalRejectedCount");
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    private static boolean waitForCondition(java.util.function.BooleanSupplier condition, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(25L);
        }
        return condition.getAsBoolean();
    }

    private static final class NoopRaftAppendCallback implements RaftAppendCallback {
        @Override
        public void appendProposal(String walletAddress, String path, String contentType, String message, String signature) {
        }

        @Override
        public void appendProposal(String walletAddress, String path, String contentType,
                                   String message, String signature, String blobId, String mimeType) {
        }

        @Override
        public void appendDeleteProposal(String walletAddress, String path, String signature) {
        }
    }
}
