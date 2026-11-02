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

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class ProposalPersistenceStoreTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    /**
     * The fixture was written by the store before the never-set audit fields (transactionId, correlationId,
     * observedEpoch, finalizedEpoch) were removed; Java serialization skips them and keeps everything else.
     */
    @Test
    public void loadsFileWrittenBeforeAuditFieldsWereRemoved() throws Exception {
        Path dir = folder.newFolder().toPath();
        try (InputStream in = getClass().getResourceAsStream("legacy-queued-proposals.bin")) {
            Files.copy(in, dir.resolve("queued-proposals.bin"));
        }

        List<QueuedProposal> loaded = new ProposalPersistenceStore(dir).load();

        assertEquals(1, loaded.size());
        QueuedProposal proposal = loaded.get(0);
        assertEquals("legacy-proposal-1", proposal.getProposalId());
        assertEquals(ProposalState.VERIFIED, proposal.getState());
        assertEquals("0x1111111111111111111111111111111111111111", proposal.getWalletAddress());
        assertEquals(42L, proposal.getEpoch());
        assertEquals(Long.valueOf(7L), proposal.getConfirmedBlock());
        assertNull(proposal.toAuditMetadata().getTransactionId());
    }

    /** The fixture was written while proposals still carried the ADR 020 intent token; loading skips that field. */
    @Test
    public void loadsFileWrittenWithAnIntentToken() throws Exception {
        Path dir = folder.newFolder().toPath();
        try (InputStream in = getClass().getResourceAsStream("queued-proposals-with-intent-token.bin")) {
            Files.copy(in, dir.resolve("queued-proposals.bin"));
        }

        List<QueuedProposal> loaded = new ProposalPersistenceStore(dir).load();

        assertEquals(1, loaded.size());
        QueuedProposal proposal = loaded.get(0);
        assertEquals("intent-proposal-1", proposal.getProposalId());
        assertEquals(ProposalState.VERIFIED, proposal.getState());
        assertEquals("0x2222222222222222222222222222222222222222", proposal.getWalletAddress());
        assertEquals("/oak-chain/22/22/22/0x2222222222222222222222222222222222222222/content/doc", proposal.getPath());
        assertEquals("image/png", proposal.getMimeType());
        assertEquals("0xsig", proposal.getSignature());
    }
}
