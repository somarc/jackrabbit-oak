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
import org.apache.jackrabbit.oak.segment.consensus.aeron.SimpleMessageHeader;
import org.apache.jackrabbit.oak.segment.http.server.util.JsonParser;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.apache.jackrabbit.oak.segment.consensus.federation.FederationFixtures.ENTERPRISE_A;
import static org.apache.jackrabbit.oak.segment.consensus.federation.FederationFixtures.GENESIS_DIGEST;
import static org.apache.jackrabbit.oak.segment.consensus.federation.FederationFixtures.charterA;
import static org.apache.jackrabbit.oak.segment.consensus.federation.FederationFixtures.directory;
import static org.apache.jackrabbit.oak.segment.consensus.federation.FederationFixtures.entryA;
import static org.apache.jackrabbit.oak.segment.consensus.federation.FederationFixtures.entryB;
import static org.apache.jackrabbit.oak.segment.consensus.federation.FederationFixtures.store;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class FederationCoordinatorTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private final List<String> proposals = new ArrayList<>();
    private final AtomicLong clock = new AtomicLong(1_000L);
    private boolean leader = true;

    private FederationCoordinator coordinator(Charter configured, DirectorySource source, MemoryNodeStore store) {
        return new FederationCoordinator(configured, source, store::getRoot, new FederationCoordinator.Proposer() {
            @Override
            public boolean canPropose() {
                return leader;
            }

            @Override
            public boolean propose(int templateId, String json) {
                proposals.add(templateId + " " + json);
                return true;
            }
        }, clock::get, 30_000L);
    }

    private DirectorySource file(Map<?, ?>... entries) throws IOException {
        Path path = folder.newFile().toPath();
        rewrite(path, 1, entries);
        return new StaticFileDirectorySource(path, charterA());
    }

    private static void rewrite(Path path, long epoch, Map<?, ?>... entries) throws IOException {
        Map<String, Object> json = FederationFixtures.directoryMap(epoch, "", entries);
        json.remove("chainId");
        json.remove("registry");
        Files.writeString(path, JsonParser.toJson(json), StandardCharsets.UTF_8);
    }

    @Test
    public void leaderProposesTheConfiguredCharterOnce() throws Exception {
        MemoryNodeStore store = store(null, null);
        leader = false;
        coordinator(charterA(), null, store).tick();
        assertTrue(proposals.isEmpty());

        leader = true;
        FederationCoordinator coordinator = coordinator(charterA(), null, store);
        FederationCoordinator.Status status = coordinator.tick();
        assertEquals(List.of(SimpleMessageHeader.TEMPLATE_ID_CHARTER + " "
            + JsonParser.toJson(Map.of("charter", charterA().toMap()))), proposals);
        assertTrue(status.isCharterMatching());
        assertNull(status.getClusterId());

        coordinator.tick();
        assertEquals("an identical in-flight proposal is not resent", 1, proposals.size());
        clock.addAndGet(30_000L);
        coordinator.tick();
        assertEquals(2, proposals.size());
    }

    @Test
    public void aMismatchedCharterRefusesWritesAndProposesNothing() throws Exception {
        MemoryNodeStore store = store(charterA(), null);
        Charter other = Charter.of(ENTERPRISE_A, "local", 0L, Charter.STATIC_REGISTRY);
        FederationCoordinator.Status status = coordinator(other, file(entryA("ACTIVE")), store).tick();
        assertFalse(status.isCharterMatching());
        assertTrue(proposals.isEmpty());
        assertEquals(false, status.toMap().get("charterMatchesConfig"));
    }

    @Test
    public void proposesNewerDirectoriesChainedAfterTheCommittedOne() throws Exception {
        DirectorySnapshot committed = directory(1, entryA("ACTIVE"));
        MemoryNodeStore store = store(charterA(), committed);
        Path path = folder.newFile().toPath();
        rewrite(path, 1, entryA("ACTIVE"));
        FederationCoordinator coordinator = coordinator(charterA(), new StaticFileDirectorySource(path, charterA()), store);

        FederationCoordinator.Status status = coordinator.tick();
        assertTrue(proposals.isEmpty());
        assertEquals(charterA().clusterId(GENESIS_DIGEST), status.getClusterId());
        assertEquals(1L, status.toMap().get("directoryEpoch"));
        assertEquals("ACTIVE", status.toMap().get("localStatus"));

        rewrite(path, 1, entryA("ACTIVE"), entryB());
        assertTrue(coordinator.tick().toMap().get("sourceError").toString().contains("without raising directoryEpoch"));
        assertTrue(proposals.isEmpty());

        rewrite(path, 2, entryA("ACTIVE"), entryB());
        coordinator.tick();
        assertEquals(1, proposals.size());
        String proposal = proposals.get(0);
        assertTrue(proposal.startsWith(SimpleMessageHeader.TEMPLATE_ID_DIRECTORY_UPDATE + " "));
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) JsonParser.parseObject(
            proposal.substring(proposal.indexOf(' ') + 1)).get("directory");
        DirectorySnapshot proposed = DirectorySnapshot.fromMap(body);
        assertEquals(committed.digest(), proposed.getPrevDigest());
        assertEquals(2L, proposed.getDirectoryEpoch());
    }

    @Test
    public void reportsAnUnreadableSource() throws Exception {
        MemoryNodeStore store = store(charterA(), null);
        DirectorySource missing = new StaticFileDirectorySource(folder.getRoot().toPath().resolve("absent.json"),
            charterA());
        FederationCoordinator.Status status = coordinator(charterA(), missing, store).tick();
        assertTrue(status.toMap().get("sourceError").toString().startsWith("file:"));
        assertTrue(proposals.isEmpty());
    }

    @Test
    public void anUnconfiguredMemberStillReportsCommittedState() throws Exception {
        MemoryNodeStore store = store(charterA(), directory(3, entryA("ACTIVE")));
        FederationCoordinator.Status status = coordinator(null, null, store).tick();
        assertTrue(status.isCharterMatching());
        assertEquals(3L, status.toMap().get("directoryEpoch"));
        assertEquals(true, status.toMap().get("federated"));
        assertTrue(proposals.isEmpty());
    }
}
