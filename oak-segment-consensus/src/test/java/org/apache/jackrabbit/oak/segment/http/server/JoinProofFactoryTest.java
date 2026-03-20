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

import org.apache.jackrabbit.oak.segment.consensus.aeron.AeronConsensusEngine;
import org.apache.jackrabbit.oak.segment.consensus.security.JoinProof;
import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.apache.jackrabbit.oak.spi.state.NodeStore;
import org.junit.Test;

import java.nio.file.Paths;
import java.util.regex.Pattern;
import java.util.function.LongSupplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class JoinProofFactoryTest {

    private static final Pattern HEX_64 = Pattern.compile("^[a-f0-9]{64}$");

    @Test
    public void testCreateBuildsJoinProofWithHeadEpochAndSingleSampleWhenGenesisMatches() {
        ServerContext context = newContext();
        context.selfUrl = "http://validator-2:8090";
        context.aeronConsensusEngine = mock(AeronConsensusEngine.class);
        when(context.aeronConsensusEngine.getCurrentEpoch()).thenReturn(17);
        LongSupplier clock = () -> 1000L;
        JoinProofFactory factory = new JoinProofFactory(fileStoreWithHead("head-123"), context, clock);

        JoinProof proof = factory.create("validator-2", "http://validator-2:8090");

        assertEquals(1000L, proof.getProofGeneratedAt());
        assertEquals("head-123", proof.getHeadSegmentId());
        assertEquals(1000L, proof.getHeadCapturedAt());
        assertEquals(17, proof.getCurrentEpoch());
        assertEquals(1000L, proof.getEpochCalculatedAt());
        assertEquals("head-123", proof.getGenesisSegmentId());
        assertEquals("validator-2", proof.getValidatorId());
        assertEquals("http://validator-2:8090", proof.getValidatorUrl());
        assertEquals(1, proof.getSampleSegmentIds().size());
        assertEquals("head-123", proof.getSampleSegmentIds().get(0));
        assertTrue(HEX_64.matcher(proof.getGenesisHash()).matches());
        assertTrue(HEX_64.matcher(proof.getChallengeNonce()).matches());
        assertTrue(HEX_64.matcher(proof.getNonceSignature()).matches());
        assertTrue(HEX_64.matcher(proof.getSampleSegmentHashes().get(0)).matches());
    }

    @Test
    public void testGetOrInitializeGenesisCachesInitialHead() {
        ServerContext context = newContext();
        JoinProofFactory factory = new JoinProofFactory(fileStoreWithHead("head-a"), context, () -> 2222L);

        String first = factory.getOrInitializeGenesis("head-a");
        String second = factory.getOrInitializeGenesis("head-b");

        assertEquals("head-a", first);
        assertEquals("head-a", second);
        assertEquals("head-a", context.genesisSegmentId);
        assertEquals(2222L, context.genesisTimestamp);
    }

    @Test
    public void testComputeGenesisHashIsStableForSameContext() {
        ServerContext context = newContext();
        context.selfUrl = "http://validator-2:8090";
        context.genesisTimestamp = 4321L;
        JoinProofFactory factory = new JoinProofFactory(fileStoreWithHead("head-a"), context, () -> 9999L);

        String first = factory.computeGenesisHash("genesis-1");
        String second = factory.computeGenesisHash("genesis-1");

        assertEquals(first, second);
        assertTrue(HEX_64.matcher(first).matches());
    }

    @Test
    public void testCreateAddsGenesisSampleWhenExistingGenesisDiffersFromCurrentHead() {
        ServerContext context = newContext();
        context.selfUrl = "http://validator-2:8090";
        context.genesisSegmentId = "genesis-1";
        context.genesisTimestamp = 1234L;
        JoinProofFactory factory = new JoinProofFactory(fileStoreWithHead("head-b"), context, () -> 5678L);

        JoinProof proof = factory.create("validator-2", "http://validator-2:8090");

        assertEquals(2, proof.getSampleSegmentIds().size());
        assertEquals("head-b", proof.getSampleSegmentIds().get(0));
        assertEquals("genesis-1", proof.getSampleSegmentIds().get(1));
        assertEquals(proof.getGenesisHash(), proof.getSampleSegmentHashes().get(1));
    }

    @Test
    public void testComputeSegmentHashUsesClockAndSignNonceIsDeterministic() {
        ServerContext context = newContext();
        JoinProofFactory firstFactory = new JoinProofFactory(fileStoreWithHead("head-a"), context, () -> 1111L);
        JoinProofFactory secondFactory = new JoinProofFactory(fileStoreWithHead("head-a"), context, () -> 2222L);

        String firstHash = firstFactory.computeSegmentHash("segment-1");
        String secondHash = secondFactory.computeSegmentHash("segment-1");
        String firstSignature = firstFactory.signNonce("nonce-1", "validator-2");
        String secondSignature = secondFactory.signNonce("nonce-1", "validator-2");

        assertNotEquals(firstHash, secondHash);
        assertTrue(HEX_64.matcher(firstHash).matches());
        assertTrue(HEX_64.matcher(secondHash).matches());
        assertEquals(firstSignature, secondSignature);
        assertTrue(HEX_64.matcher(firstSignature).matches());
    }

    @Test
    public void testGenerateSecureNonceAndBytesToHexProduceHex() {
        JoinProofFactory factory = new JoinProofFactory(fileStoreWithHead("head-a"), newContext(), () -> 1111L);

        String nonce = factory.generateSecureNonce();
        String hex = JoinProofFactory.bytesToHex(new byte[] {0x00, 0x0f, (byte) 0xff});

        assertTrue(HEX_64.matcher(nonce).matches());
        assertEquals("000fff", hex);
    }

    private static ServerContext newContext() {
        return new ServerContext(
            mock(FileStore.class),
            mock(NodeStore.class),
            Paths.get("/tmp/store"),
            "http://localhost:8090"
        );
    }

    private static FileStore fileStoreWithHead(String head) {
        FileStore fileStore = mock(FileStore.class, RETURNS_DEEP_STUBS);
        when(fileStore.getHead().getRecordId().toString()).thenReturn(head);
        return fileStore;
    }
}
