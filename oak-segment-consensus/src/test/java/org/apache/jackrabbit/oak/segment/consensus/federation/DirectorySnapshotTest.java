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
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.apache.jackrabbit.oak.segment.consensus.federation.FederationFixtures.ENTERPRISE_A;
import static org.apache.jackrabbit.oak.segment.consensus.federation.FederationFixtures.ENTERPRISE_B;
import static org.apache.jackrabbit.oak.segment.consensus.federation.FederationFixtures.NAMESPACE_A;
import static org.apache.jackrabbit.oak.segment.consensus.federation.FederationFixtures.NAMESPACE_A2;
import static org.apache.jackrabbit.oak.segment.consensus.federation.FederationFixtures.NAMESPACE_B;
import static org.apache.jackrabbit.oak.segment.consensus.federation.FederationFixtures.directory;
import static org.apache.jackrabbit.oak.segment.consensus.federation.FederationFixtures.directoryMap;
import static org.apache.jackrabbit.oak.segment.consensus.federation.FederationFixtures.entry;
import static org.apache.jackrabbit.oak.segment.consensus.federation.FederationFixtures.entryA;
import static org.apache.jackrabbit.oak.segment.consensus.federation.FederationFixtures.entryB;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class DirectorySnapshotTest {

    @Test
    public void digestIsIndependentOfInputOrder() {
        DirectorySnapshot forward = directory(1, entryA("ACTIVE"), entryB());
        DirectorySnapshot reverse = directory(1, entryB(), entryA("ACTIVE"));
        assertEquals(forward.canonicalJson(), reverse.canonicalJson());
        assertEquals(forward.digest(), reverse.digest());
        assertEquals(ENTERPRISE_A, forward.getEntries().get(0).getEnterpriseId());
        assertEquals(List.of(NAMESPACE_A, NAMESPACE_A2), forward.entry(ENTERPRISE_A).getNamespaces());
        assertEquals("http://b0:8190", forward.entry(ENTERPRISE_B).getEndpoints().get(0));
    }

    @Test
    public void resolvesNamespaceOwners() {
        DirectorySnapshot snapshot = directory(1, entryA("ACTIVE"), entryB());
        assertEquals(ENTERPRISE_A, snapshot.ownerOf(NAMESPACE_A2).getEnterpriseId());
        assertEquals(ENTERPRISE_B, snapshot.ownerOf(NAMESPACE_B).getEnterpriseId());
        assertNull(snapshot.ownerOf("0x" + "99".repeat(20)));
    }

    @Test
    public void rejectsOverlappingNamespacesAndDuplicateEnterprises() {
        Map<String, Object> thief = entry(ENTERPRISE_B, "ACTIVE", List.of("http://b"), List.of(NAMESPACE_A), List.of());
        assertThrows(MutationRejectedException.class, () -> directory(1, entryA("ACTIVE"), thief));
        assertThrows(MutationRejectedException.class, () -> directory(1, entryA("ACTIVE"), entryA("PENDING")));
    }

    @Test
    public void rejectsInvalidEntries() {
        assertThrows(MutationRejectedException.class, () -> directory(1,
            entry(ENTERPRISE_A, "ACTIVE", List.of("ftp://a"), List.of(NAMESPACE_A), List.of())));
        assertThrows(MutationRejectedException.class, () -> directory(1,
            entry(ENTERPRISE_A, "UNKNOWN", List.of("http://a"), List.of(NAMESPACE_A), List.of())));
        assertThrows(MutationRejectedException.class, () -> directory(1,
            entry(ENTERPRISE_A, "ACTIVE", List.of("http://a"), List.of(NAMESPACE_A, NAMESPACE_A), List.of())));
        assertThrows(MutationRejectedException.class, () -> directory(1,
            entry(ENTERPRISE_A, "ACTIVE", List.of("http://a"), List.of(DirectoryEntry.GENESIS_NAMESPACE), List.of())));
        assertThrows(MutationRejectedException.class, () -> DirectorySnapshot.fromMap(Map.of("chainId", 0L)));
        assertEquals(DirectoryEntry.Status.PENDING, directory(1,
            entry(ENTERPRISE_A, "pending", List.of(), List.of(NAMESPACE_A), List.of())).entry(ENTERPRISE_A).getStatus());
        // An active enterprise between a config rotation and new endpoints is listed but not routable
        assertTrue(directory(1, entry(ENTERPRISE_A, "ACTIVE", List.of(), List.of(NAMESPACE_A), List.of()))
            .entry(ENTERPRISE_A).getEndpoints().isEmpty());
    }

    @Test
    public void signerThresholdMustBeAMajority() {
        Map<String, Object> entry = entryA("ACTIVE");
        entry.put("signers", List.of(NAMESPACE_A, NAMESPACE_A2, NAMESPACE_B));
        entry.put("threshold", 1L);
        assertThrows(MutationRejectedException.class, () -> directory(1, entry));
        entry.put("threshold", 4L);
        assertThrows(MutationRejectedException.class, () -> directory(1, entry));
        entry.put("threshold", 2L);
        assertEquals(2L, directory(1, entry).entry(ENTERPRISE_A).getThreshold());
    }

    @Test
    public void chainingChangesOnlyPrevDigest() {
        DirectorySnapshot first = directory(1, entryA("ACTIVE"));
        DirectorySnapshot second = directory(2, entryA("ACTIVE"), entryB()).chainedAfter(first);
        assertEquals(first.digest(), second.getPrevDigest());
        assertEquals("", second.chainedAfter(null).getPrevDigest());
        assertTrue(second.sameContent(second.chainedAfter(null)));
        Map<String, Object> laterBlock = second.toMap();
        laterBlock.put("blockNumber", 99L);
        laterBlock.put("blockHash", "0x" + "ef".repeat(32));
        assertTrue(second.sameContent(DirectorySnapshot.fromMap(laterBlock)));
        assertNotEquals(second.digest(), second.chainedAfter(null).digest());
        assertThrows(MutationRejectedException.class,
            () -> DirectorySnapshot.fromMap(directoryMap(1, "0x1234", entryA("ACTIVE"))));
    }

    @Test
    public void roundTripsThroughTheStoreAndCachesTheParse() {
        MemoryNodeStore store = new MemoryNodeStore();
        assertNull(DirectorySnapshot.read(store.getRoot()));
        NodeBuilder root = store.getRoot().builder();
        DirectorySnapshot snapshot = directory(7, entryA("ACTIVE"), entryB());
        snapshot.writeTo(root);
        DirectorySnapshot read = DirectorySnapshot.read(root.getNodeState());
        assertEquals(snapshot.digest(), read.digest());
        assertEquals(7L, read.getDirectoryEpoch());
        assertSame(read, DirectorySnapshot.read(root.getNodeState()));
    }
}
