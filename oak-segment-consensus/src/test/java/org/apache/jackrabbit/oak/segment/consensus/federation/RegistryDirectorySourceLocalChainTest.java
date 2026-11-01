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
package org.apache.jackrabbit.oak.segment.consensus.federation;

import org.apache.jackrabbit.oak.plugins.memory.MemoryNodeStore;
import org.apache.jackrabbit.oak.segment.consensus.service.AppliedLogPosition;
import org.apache.jackrabbit.oak.segment.consensus.service.FederationApplicationService;
import org.junit.Assume;
import org.junit.Test;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.http.HttpService;

import java.io.IOException;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Reads a deployed EnterpriseRegistry on a local chain and commits what it read. Opt-in: set
 * {@code oak.federation.it.rpcUrl}, {@code oak.federation.it.registry} and optionally
 * {@code oak.federation.it.codehash} (see oak-chain-smart-contracts {@code deployments/31337.json}).
 */
public class RegistryDirectorySourceLocalChainTest {

    private static final String RPC = System.getProperty("oak.federation.it.rpcUrl", "");
    private static final String REGISTRY = System.getProperty("oak.federation.it.registry", "");
    private static final String CODE_HASH = System.getProperty("oak.federation.it.codehash", "");

    private static RegistryDirectorySource source(Charter charter, String codeHash) {
        return new RegistryDirectorySource(RegistryDirectorySource.web3j(Web3j.build(new HttpService(RPC)), true),
            charter, codeHash);
    }

    @Test
    public void readsTheRegistryAndCommitsWhatItRead() throws Exception {
        Assume.assumeFalse("set oak.federation.it.rpcUrl to run", RPC.isEmpty() || REGISTRY.isEmpty());
        Charter probe = Charter.of("0x" + "00".repeat(31) + "01", "local", 31337L, REGISTRY);
        DirectorySnapshot read = source(probe, CODE_HASH).load();
        assertTrue(read.getDirectoryEpoch() > 0);
        assertFalse(read.getEntries().isEmpty());
        for (DirectoryEntry entry : read.getEntries()) {
            if (entry.getStatus() == DirectoryEntry.Status.ACTIVE) {
                assertFalse(entry.getNamespaces().isEmpty());
                assertTrue(entry.getThreshold() * 2 > entry.getSigners().size());
            }
        }

        DirectoryEntry local = read.getEntries().get(0);
        Charter charter = Charter.of(local.getEnterpriseId(), "local", 31337L, REGISTRY);
        RegistryDirectorySource source = source(charter, CODE_HASH);
        DirectorySnapshot snapshot = source.load();
        assertSame(snapshot, source.load());
        assertEquals(read.digest(), snapshot.digest());

        MemoryNodeStore store = FederationFixtures.store(charter, null);
        new FederationApplicationService(() -> store).applyDirectory(
            Map.of("directory", snapshot.chainedAfter(null).toMap()), new AppliedLogPosition(64, 0, 1));
        DirectorySnapshot committed = DirectorySnapshot.read(store.getRoot());
        assertTrue(committed.sameContent(snapshot));
        for (DirectoryEntry entry : committed.getEntries()) {
            for (String namespace : entry.getNamespaces()) {
                NamespaceAuthority.Verdict verdict = NamespaceAuthority.decide(charter, committed, namespace, namespace)
                    .getVerdict();
                boolean mine = entry.getEnterpriseId().equals(charter.getEnterpriseId());
                assertEquals(namespace, mine && entry.getStatus() == DirectoryEntry.Status.ACTIVE
                    ? NamespaceAuthority.Verdict.ALLOW
                    : mine ? NamespaceAuthority.Verdict.INACTIVE : NamespaceAuthority.Verdict.REMOTE, verdict);
            }
        }
    }

    @Test
    public void refusesAWrongChainOrCodeHash() {
        Assume.assumeFalse("set oak.federation.it.rpcUrl to run", RPC.isEmpty() || REGISTRY.isEmpty());
        Charter sepolia = Charter.of("0x" + "00".repeat(31) + "01", "sepolia", 11155111L, REGISTRY);
        assertTrue(assertThrows(IOException.class, () -> source(sepolia, "").load()).getMessage().contains("31337"));
        Charter local = Charter.of("0x" + "00".repeat(31) + "01", "local", 31337L, REGISTRY);
        assertThrows(IOException.class, () -> source(local, "0x" + "00".repeat(32)).load());
    }
}
