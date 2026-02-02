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
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class EpochBasedBatchQueueTest {

    @BeforeClass
    public static void setUpClass() {
        System.setProperty("oak.blockchain.mode", "mock");
    }

    @Test
    public void testFinalityDelaysByTier() {
        BeaconChainClient beaconClient = new BeaconChainClient("ignored");
        beaconClient.setMockEpochOffset(0);

        EpochBasedBatchQueue queue = new EpochBasedBatchQueue(beaconClient);
        long epoch = queue.getCurrentEpoch();

        QueuedProposal express = buildProposal("express-001",
            ValidatorEarningsTracker.PaymentTier.EXPRESS, epoch, "0xexpress");
        QueuedProposal standard = buildProposal("standard-001",
            ValidatorEarningsTracker.PaymentTier.STANDARD, epoch, "0xstandard");

        queue.addProposal(express, epoch);
        queue.addProposal(standard, epoch);

        List<Long> initialFinalizable = queue.getFinalizableEpochs();
        assertTrue("No epochs should be finalizable at current epoch", initialFinalizable.isEmpty());

        // Advance one epoch: EXPRESS should be finalizable, STANDARD should remain pending.
        beaconClient.advanceMockEpoch(1);
        List<Long> afterOneEpoch = queue.getFinalizableEpochs();
        assertTrue("Epoch should be finalizable after 1 epoch (EXPRESS ready)",
            afterOneEpoch.contains(epoch));

        List<List<QueuedProposal>> batchesAfterOne = queue.finalizeEpoch(epoch);
        assertTrue("EXPRESS proposal should finalize after 1 epoch",
            containsProposal(batchesAfterOne, "express-001"));
        assertFalse("STANDARD proposal should not finalize after 1 epoch",
            containsProposal(batchesAfterOne, "standard-001"));

        // Advance second epoch: STANDARD should now be finalizable.
        beaconClient.advanceMockEpoch(1);
        List<Long> afterTwoEpochs = queue.getFinalizableEpochs();
        assertTrue("Epoch should be finalizable after 2 epochs (STANDARD ready)",
            afterTwoEpochs.contains(epoch));

        List<List<QueuedProposal>> batchesAfterTwo = queue.finalizeEpoch(epoch);
        assertTrue("STANDARD proposal should finalize after 2 epochs",
            containsProposal(batchesAfterTwo, "standard-001"));
    }

    private static QueuedProposal buildProposal(String proposalId,
                                                ValidatorEarningsTracker.PaymentTier tier,
                                                long epoch,
                                                String wallet) {
        long now = System.currentTimeMillis();
        QueuedProposal proposal = new QueuedProposal(
            proposalId,
            "0xtx",
            null,
            now,
            now + 30_000,
            ProposalState.PENDING
        );
        proposal.setWalletAddress(wallet);
        proposal.setPath("/oak-chain/aa/bb/cc/" + wallet + "/content/item");
        proposal.setTier(tier);
        proposal.setEpoch(epoch);
        return proposal;
    }

    private static boolean containsProposal(List<List<QueuedProposal>> batches, String proposalId) {
        for (List<QueuedProposal> batch : batches) {
            for (QueuedProposal proposal : batch) {
                if (proposalId.equals(proposal.getProposalId())) {
                    return true;
                }
            }
        }
        return false;
    }
}
