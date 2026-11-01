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
import org.apache.jackrabbit.oak.segment.consensus.validation.MutationRejectedException;
import org.junit.Test;

import static org.apache.jackrabbit.oak.segment.consensus.federation.FederationFixtures.NAMESPACE_A;
import static org.apache.jackrabbit.oak.segment.consensus.federation.FederationFixtures.NAMESPACE_A2;
import static org.apache.jackrabbit.oak.segment.consensus.federation.FederationFixtures.NAMESPACE_B;
import static org.apache.jackrabbit.oak.segment.consensus.federation.FederationFixtures.STRANGER;
import static org.apache.jackrabbit.oak.segment.consensus.federation.FederationFixtures.WRITER_A;
import static org.apache.jackrabbit.oak.segment.consensus.federation.FederationFixtures.charterA;
import static org.apache.jackrabbit.oak.segment.consensus.federation.FederationFixtures.directory;
import static org.apache.jackrabbit.oak.segment.consensus.federation.FederationFixtures.entryA;
import static org.apache.jackrabbit.oak.segment.consensus.federation.FederationFixtures.entryB;
import static org.apache.jackrabbit.oak.segment.consensus.federation.FederationFixtures.pathUnder;
import static org.apache.jackrabbit.oak.segment.consensus.federation.FederationFixtures.store;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

public class NamespaceAuthorityTest {

    private static NamespaceAuthority.Verdict decide(DirectorySnapshot directory, String signer, String namespace) {
        return NamespaceAuthority.decide(charterA(), directory, signer, namespace).getVerdict();
    }

    @Test
    public void decidesEveryVerdict() {
        DirectorySnapshot directory = directory(1, entryA("ACTIVE"), entryB());
        assertEquals(NamespaceAuthority.Verdict.UNFEDERATED, decide(null, STRANGER, STRANGER));
        assertEquals(NamespaceAuthority.Verdict.ALLOW, decide(directory, NAMESPACE_A, NAMESPACE_A));
        assertEquals(NamespaceAuthority.Verdict.ALLOW, decide(directory, WRITER_A.toUpperCase().replace("0X", "0x"),
            NAMESPACE_A2));
        assertEquals(NamespaceAuthority.Verdict.NOT_WRITER, decide(directory, NAMESPACE_A2, NAMESPACE_A));
        assertEquals(NamespaceAuthority.Verdict.REMOTE, decide(directory, NAMESPACE_B, NAMESPACE_B));
        assertEquals(NamespaceAuthority.Verdict.UNCLAIMED, decide(directory, STRANGER, STRANGER));
        assertEquals(NamespaceAuthority.Verdict.INACTIVE,
            decide(directory(2, entryA("SUSPENDED")), NAMESPACE_A, NAMESPACE_A));
        assertEquals(NamespaceAuthority.Verdict.NO_CHARTER,
            NamespaceAuthority.decide(null, directory, NAMESPACE_A, NAMESPACE_A).getVerdict());
    }

    @Test
    public void namespaceOfRequiresDerivedLevels() {
        assertEquals(NAMESPACE_A, NamespaceAuthority.namespaceOf(pathUnder(NAMESPACE_A)));
        assertEquals(NAMESPACE_A, NamespaceAuthority.namespaceOf("/oak-chain/1a/1a/1a/" + NAMESPACE_A));
        assertThrows(MutationRejectedException.class,
            () -> NamespaceAuthority.namespaceOf("/oak-chain/aa/bb/cc/" + NAMESPACE_A + "/content/x"));
        assertThrows(MutationRejectedException.class, () -> NamespaceAuthority.namespaceOf("/oak-chain/1a/1a/1a"));
        assertThrows(MutationRejectedException.class,
            () -> NamespaceAuthority.namespaceOf("/other/1a/1a/1a/" + NAMESPACE_A + "/content/x"));
        assertThrows(MutationRejectedException.class,
            () -> NamespaceAuthority.namespaceOf("/oak-chain/1a/1a/1a/" + NAMESPACE_A.toUpperCase() + "/content"));
    }

    @Test
    public void requireWritableIsANoOpWhenUnfederated() throws Exception {
        MemoryNodeStore store = store(charterA(), null);
        assertNull(NamespaceAuthority.requireWritable(store.getRoot(), STRANGER, "/anything/at/all"));
    }

    @Test
    public void requireWritableEnforcesTheCommittedDirectory() throws Exception {
        MemoryNodeStore store = store(charterA(), directory(1, entryA("ACTIVE"), entryB()));
        assertEquals(NAMESPACE_A2, NamespaceAuthority.requireWritable(store.getRoot(), WRITER_A, pathUnder(NAMESPACE_A2)));
        assertThrows(MutationRejectedException.class,
            () -> NamespaceAuthority.requireWritable(store.getRoot(), NAMESPACE_B, pathUnder(NAMESPACE_B)));
        assertThrows(MutationRejectedException.class,
            () -> NamespaceAuthority.requireWritable(store.getRoot(), STRANGER, pathUnder(NAMESPACE_A)));
        assertThrows(MutationRejectedException.class,
            () -> NamespaceAuthority.requireWritable(store.getRoot(), STRANGER, pathUnder(STRANGER)));
    }
}
