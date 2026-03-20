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
package org.apache.jackrabbit.oak.segment.consensus.gc;

import org.apache.jackrabbit.oak.segment.consensus.evm.EvmBridge;
import org.apache.jackrabbit.oak.segment.consensus.evm.impl.SimplePaymentProof;
import org.apache.jackrabbit.oak.segment.consensus.fragmentation.FragmentationTracker;
import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class GCProposalManagerTest {

    @After
    public void tearDown() {
        System.clearProperty("oak.proposal.confirmation.required");
    }

    @Test
    public void testVerifyPaymentHonorsConfiguredConfirmationDepth() {
        System.setProperty("oak.proposal.confirmation.required", "2");

        EvmBridge evmBridge = mock(EvmBridge.class);
        GCProposalManager manager = new GCProposalManager(
            mock(FileStore.class),
            mock(GCCostEstimator.class),
            mock(FragmentationTracker.class),
            evmBridge,
            3,
            () -> 0,
            () -> true
        );

        String proposalId = "gc-proposal-1";
        manager.applyReplicatedProposal(proposalId, "0xwallet", "HEAD", 10L, "1.25");
        when(evmBridge.verifyPayment(proposalId)).thenReturn(new SimplePaymentProof(
            "0xtx1",
            100L,
            "0xwallet",
            "0xcontract",
            proposalId,
            "1000000",
            1
        ));

        assertFalse(manager.verifyPayment(proposalId));
    }

    @Test
    public void testVerifyPaymentAcceptsProofOnceConfiguredDepthSatisfied() {
        System.setProperty("oak.proposal.confirmation.required", "2");

        EvmBridge evmBridge = mock(EvmBridge.class);
        GCProposalManager manager = new GCProposalManager(
            mock(FileStore.class),
            mock(GCCostEstimator.class),
            mock(FragmentationTracker.class),
            evmBridge,
            3,
            () -> 0,
            () -> true
        );

        String proposalId = "gc-proposal-2";
        manager.applyReplicatedProposal(proposalId, "0xwallet", "HEAD", 10L, "1.25");
        when(evmBridge.verifyPayment(proposalId)).thenReturn(new SimplePaymentProof(
            "0xtx2",
            100L,
            "0xwallet",
            "0xcontract",
            proposalId,
            "1000000",
            2
        ));

        assertTrue(manager.verifyPayment(proposalId));
    }
}
