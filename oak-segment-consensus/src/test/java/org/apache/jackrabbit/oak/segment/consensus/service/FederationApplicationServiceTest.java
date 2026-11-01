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
package org.apache.jackrabbit.oak.segment.consensus.service;

import org.apache.jackrabbit.oak.plugins.memory.MemoryNodeStore;
import org.apache.jackrabbit.oak.segment.consensus.federation.Charter;
import org.apache.jackrabbit.oak.segment.consensus.federation.DirectorySnapshot;
import org.apache.jackrabbit.oak.segment.consensus.validation.MutationRejectedException;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.junit.Test;

import java.util.Map;

import static org.apache.jackrabbit.oak.segment.consensus.federation.FederationFixtures.ENTERPRISE_A;
import static org.apache.jackrabbit.oak.segment.consensus.federation.FederationFixtures.GENESIS_DIGEST;
import static org.apache.jackrabbit.oak.segment.consensus.federation.FederationFixtures.charterA;
import static org.apache.jackrabbit.oak.segment.consensus.federation.FederationFixtures.directory;
import static org.apache.jackrabbit.oak.segment.consensus.federation.FederationFixtures.directoryMap;
import static org.apache.jackrabbit.oak.segment.consensus.federation.FederationFixtures.entryA;
import static org.apache.jackrabbit.oak.segment.consensus.federation.FederationFixtures.entryB;
import static org.apache.jackrabbit.oak.segment.consensus.federation.FederationFixtures.store;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

public class FederationApplicationServiceTest {

    private static final AppliedLogPosition POSITION = new AppliedLogPosition(4096, 0, 3);

    private static Map<String, Object> charterCommand(Charter charter) {
        return Map.of("charter", charter.toMap());
    }

    private static Map<String, Object> directoryCommand(DirectorySnapshot snapshot) {
        return Map.of("directory", snapshot.toMap());
    }

    @Test
    public void commitsTheCharterOnceWithItsWatermark() throws Exception {
        MemoryNodeStore store = store(null, null);
        FederationApplicationService service = new FederationApplicationService(() -> store);
        service.applyCharter(charterCommand(charterA()), POSITION);
        NodeState root = store.getRoot();
        assertEquals(charterA(), Charter.read(root));
        assertEquals(POSITION, AppliedLogPosition.read(root));
        assertEquals(GENESIS_DIGEST, FederationApplicationService.genesisDigest(root));

        service.applyCharter(charterCommand(charterA()), new AppliedLogPosition(5000, 0, 3));
        assertEquals(POSITION, AppliedLogPosition.read(store.getRoot()));
        Charter other = Charter.of(ENTERPRISE_A, "local", 0L, Charter.STATIC_REGISTRY);
        assertThrows(MutationRejectedException.class, () -> service.applyCharter(charterCommand(other), POSITION));
        assertEquals(charterA(), Charter.read(store.getRoot()));
    }

    @Test
    public void rejectsACharterBeforeGenesisOrWithoutABody() {
        MemoryNodeStore empty = new MemoryNodeStore();
        FederationApplicationService service = new FederationApplicationService(() -> empty);
        assertThrows(MutationRejectedException.class, () -> service.applyCharter(charterCommand(charterA()), POSITION));
        assertThrows(MutationRejectedException.class, () -> service.applyCharter(Map.of(), POSITION));
        assertNull(Charter.read(empty.getRoot()));
    }

    @Test
    public void commitsChainedDirectorySnapshotsInEpochOrder() throws Exception {
        MemoryNodeStore store = store(charterA(), null);
        FederationApplicationService service = new FederationApplicationService(() -> store);
        DirectorySnapshot first = directory(1, entryA("ACTIVE"));
        service.applyDirectory(directoryCommand(first), POSITION);
        assertEquals(first.digest(), DirectorySnapshot.read(store.getRoot()).digest());

        DirectorySnapshot second = directory(2, entryA("ACTIVE"), entryB()).chainedAfter(first);
        service.applyDirectory(directoryCommand(second), new AppliedLogPosition(8192, 0, 3));
        assertEquals(second.digest(), DirectorySnapshot.read(store.getRoot()).digest());
        assertEquals(8192, AppliedLogPosition.read(store.getRoot()).position());
    }

    @Test
    public void rejectsStaleUnchainedOrForeignDirectories() throws Exception {
        MemoryNodeStore store = store(charterA(), directory(5, entryA("ACTIVE")));
        DirectorySnapshot committed = DirectorySnapshot.read(store.getRoot());
        FederationApplicationService service = new FederationApplicationService(() -> store);

        assertThrows(MutationRejectedException.class,
            () -> service.applyDirectory(directoryCommand(directory(6, entryA("ACTIVE"))), POSITION));
        assertThrows(MutationRejectedException.class, () -> service.applyDirectory(
            directoryCommand(directory(5, entryA("ACTIVE"), entryB()).chainedAfter(committed)), POSITION));
        Map<String, Object> foreign = directoryMap(6, committed.digest(), entryA("ACTIVE"));
        foreign.put("chainId", 1L);
        assertThrows(MutationRejectedException.class,
            () -> service.applyDirectory(Map.of("directory", foreign), POSITION));
        assertEquals(committed.digest(), DirectorySnapshot.read(store.getRoot()).digest());
    }

    @Test
    public void rejectsADirectoryBeforeTheCharter() throws Exception {
        MemoryNodeStore store = store(null, null);
        FederationApplicationService service = new FederationApplicationService(() -> store);
        assertThrows(MutationRejectedException.class,
            () -> service.applyDirectory(directoryCommand(directory(1, entryA("ACTIVE"))), POSITION));
    }
}
