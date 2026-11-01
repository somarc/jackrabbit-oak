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
package org.apache.jackrabbit.oak.segment.consensus.mount;

import org.apache.jackrabbit.oak.plugins.memory.MemoryNodeStore;
import org.apache.jackrabbit.oak.segment.consensus.federation.Charter;
import org.apache.jackrabbit.oak.segment.consensus.federation.DirectoryEntry;
import org.apache.jackrabbit.oak.segment.consensus.federation.DirectorySnapshot;
import org.apache.jackrabbit.oak.segment.consensus.sharding.ShardingRuntimeConfig;
import org.apache.jackrabbit.oak.spi.commit.CommitInfo;
import org.apache.jackrabbit.oak.spi.commit.EmptyHook;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.apache.jackrabbit.oak.spi.state.NodeStore;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.apache.jackrabbit.oak.segment.consensus.federation.FederationFixtures.ENTERPRISE_A;
import static org.apache.jackrabbit.oak.segment.consensus.federation.FederationFixtures.ENTERPRISE_B;
import static org.apache.jackrabbit.oak.segment.consensus.federation.FederationFixtures.NAMESPACE_A;
import static org.apache.jackrabbit.oak.segment.consensus.federation.FederationFixtures.NAMESPACE_B;
import static org.apache.jackrabbit.oak.segment.consensus.federation.FederationFixtures.STRANGER;
import static org.apache.jackrabbit.oak.segment.consensus.federation.FederationFixtures.charterA;
import static org.apache.jackrabbit.oak.segment.consensus.federation.FederationFixtures.directory;
import static org.apache.jackrabbit.oak.segment.consensus.federation.FederationFixtures.entry;
import static org.apache.jackrabbit.oak.segment.consensus.federation.FederationFixtures.entryA;
import static org.apache.jackrabbit.oak.segment.consensus.federation.FederationFixtures.pathUnder;
import static org.apache.jackrabbit.oak.segment.consensus.federation.FederationFixtures.store;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class FederatedReadViewTest {

    private static final String ENTERPRISE_C = "0x" + "c".repeat(64);
    private static final String NAMESPACE_B2 = "0x" + "2b".repeat(20);
    private static final String NAMESPACE_C = "0x" + "1c".repeat(20);
    private static final String B0 = "http://b0:8190";
    private static final String B1 = "http://b1:8190";
    private static final String C0 = "http://c0:8290";

    private final FakeRemotes remotes = new FakeRemotes().serve(B0, "b0").serve(B1, "b1").serve(C0, "c0");

    @Test
    public void testOnlyActiveRemoteEnterprisesAreMounted() {
        DirectorySnapshot snapshot = directory(1L, entryA("ACTIVE"), entryB("ACTIVE", B0),
            entry(ENTERPRISE_C, "SUSPENDED", List.of(C0), List.of(NAMESPACE_C), List.of()));

        List<DirectoryEntry> remote = FederatedReadView.remoteEntries(charterA(), snapshot);

        assertEquals(1, remote.size());
        assertEquals(ENTERPRISE_B, remote.get(0).getEnterpriseId());
        assertTrue("no charter: the local enterprise is unknown, nothing is mounted",
            FederatedReadView.remoteEntries(null, snapshot).isEmpty());
        assertTrue(FederatedReadView.remoteEntries(Charter.of(ENTERPRISE_C, "mock", 0L, Charter.STATIC_REGISTRY),
            directory(1L, entry(ENTERPRISE_B, "PENDING", List.of(B0), List.of(NAMESPACE_B), List.of())))
            .isEmpty());
    }

    @Test
    public void testWithoutADirectoryTheStaticRoutesAreMountedAsBefore() throws Exception {
        MemoryNodeStore local = store(charterA(), null);
        MemoryNodeStore staticRemote = new MemoryNodeStore();
        seed(staticRemote, "/oak-chain/90/static-wallet/content");

        try (FederatedReadView view = new FederatedReadView(local,
            ShardingRuntimeConfig.fromSpecs(true, "00-7f", "80-ff=http://cluster-b:8190"),
            new ValidatorReadViewBuilder((endpoint, mountName) -> staticRemote), remotes::mount)) {

            assertFalse(view.remoteMounts().fromDirectory());
            assertEquals(1, view.remoteMounts().statuses().size());
            assertEquals("http://cluster-b:8190", view.remoteMounts().statuses().get(0).endpoint());
            assertTrue(exists(view.nodeStore().getRoot(), "/oak-chain/90/static-wallet/content"));
            assertFalse("no directory change, no rebuild", view.refresh());
        }
    }

    @Test
    public void testWithoutADirectoryOrStaticRoutesTheViewIsTheLocalStore() throws Exception {
        MemoryNodeStore local = store(charterA(), null);
        seed(local, pathUnder(NAMESPACE_A));

        try (FederatedReadView view = new FederatedReadView(local, ShardingRuntimeConfig.disabled())) {
            assertSame(RemoteMounts.NONE, view.remoteMounts());
            assertTrue(exists(view.nodeStore().getRoot(), pathUnder(NAMESPACE_A)));
        }
    }

    @Test
    public void testDirectoryMountsServeRemoteNamespacesOnlyOnceAnEndpointIsVerified() throws Exception {
        MemoryNodeStore local = store(charterA(), directory(3L, entryA("ACTIVE"), entryB("ACTIVE", B0, B1)));
        seed(local, pathUnder(NAMESPACE_A));
        seed(local, pathUnder(STRANGER));
        remotes.seed(B0, pathUnder(NAMESPACE_B));
        remotes.seed(B0, pathUnder(STRANGER) + "-remote");

        try (FederatedReadView view = new FederatedReadView(local, null, new ValidatorReadViewBuilder(),
            remotes::mount)) {
            assertEquals("building the view touches no remote", 0, remotes.connects(B0) + remotes.probes(B0));
            RemoteMounts mounts = view.remoteMounts();
            assertTrue(mounts.fromDirectory());
            assertEquals(3L, mounts.directoryEpoch());
            assertNotNull("unverified remote is unavailable, not empty", mounts.unavailableFor(pathUnder(NAMESPACE_B)));
            assertNull(mounts.unavailableFor(pathUnder(NAMESPACE_A)));
            assertNull(mounts.unavailableFor(pathUnder(STRANGER)));
            assertFalse(exists(view.nodeStore().getRoot(), pathUnder(NAMESPACE_B)));

            view.refresh();

            NodeState root = view.nodeStore().getRoot();
            assertTrue(exists(root, pathUnder(NAMESPACE_B)));
            assertTrue(exists(root, pathUnder(NAMESPACE_A)));
            assertTrue("unclaimed paths stay local", exists(root, pathUnder(STRANGER)));
            assertFalse("remote content outside its namespaces is not mounted",
                exists(root, pathUnder(STRANGER) + "-remote"));
            assertNull(mounts.unavailableFor(pathUnder(NAMESPACE_B)));

            RemoteMountStatus status = mounts.statuses().get(0);
            Map<String, Object> health = status.toMap();
            assertEquals(ENTERPRISE_B, health.get("enterpriseId"));
            assertEquals(List.of(NAMESPACE_B), health.get("namespaces"));
            assertEquals(B0, health.get("activeEndpoint"));
            assertEquals(true, health.get("verified"));
            assertEquals(3L, health.get("directoryEpoch"));
            assertEquals(B0, health.get("endpoint"));
            assertEquals(true, health.get("available"));
            List<String> urls = new ArrayList<>();
            status.federation().endpoints().forEach(state -> urls.add(state.url()));
            assertEquals(List.of(B0, B1), urls);
        }
    }

    @Test
    public void testDirectoryChangeSwapsTheViewAndClosesRetiredMountsOneTickLater() throws Exception {
        MemoryNodeStore local = store(charterA(), directory(1L, entryA("ACTIVE"), entryB("ACTIVE", B0),
            entry(ENTERPRISE_C, "ACTIVE", List.of(C0), List.of(NAMESPACE_C), List.of())));
        remotes.seed(B0, pathUnder(NAMESPACE_B2));
        remotes.seed(C0, pathUnder(NAMESPACE_C));
        List<RemoteMounts> published = new ArrayList<>();

        try (FederatedReadView view = new FederatedReadView(local, null, new ValidatorReadViewBuilder(),
            remotes::mount)) {
            view.listen(published::add);
            NodeStore readView = view.nodeStore();
            view.refresh();
            assertTrue(exists(readView.getRoot(), pathUnder(NAMESPACE_C)));
            assertFalse(exists(readView.getRoot(), pathUnder(NAMESPACE_B2)));
            assertEquals(2, remotes.mountsCreated.get());

            // epoch 2: B claims another namespace on the same endpoints, C retires
            commit(local, directory(2L, entryA("ACTIVE"), entry(ENTERPRISE_B, "ACTIVE", List.of(B0),
                List.of(NAMESPACE_B, NAMESPACE_B2), List.of()),
                entry(ENTERPRISE_C, "RETIRED", List.of(C0), List.of(NAMESPACE_C), List.of())));

            assertTrue(view.refresh());
            assertSame("callers keep the same read view", readView, view.nodeStore());
            assertSame(view.remoteMounts(), published.get(published.size() - 1));
            assertEquals(2L, view.remoteMounts().directoryEpoch());
            assertEquals("B's store carries over", 2, remotes.mountsCreated.get());
            assertTrue(exists(readView.getRoot(), pathUnder(NAMESPACE_B2)));
            assertFalse("C is no longer mounted", exists(readView.getRoot(), pathUnder(NAMESPACE_C)));
            assertFalse("retired mounts stay open for reads in flight", remotes.closed(C0));

            assertFalse(view.refresh());
            assertTrue(remotes.closed(C0));
            assertFalse(remotes.closed(B0));
        }
        assertTrue("closing the view closes its current mounts", remotes.closed(B0));
    }

    @Test
    public void testChangedEndpointsReplaceTheEnterpriseStore() throws Exception {
        MemoryNodeStore local = store(charterA(), directory(1L, entryA("ACTIVE"), entryB("ACTIVE", B0)));
        remotes.seed(B1, pathUnder(NAMESPACE_B));

        try (FederatedReadView view = new FederatedReadView(local, null, new ValidatorReadViewBuilder(),
            remotes::mount)) {
            view.refresh();
            commit(local, directory(2L, entryA("ACTIVE"), entryB("ACTIVE", B1)));

            assertTrue(view.refresh());
            view.refresh();

            assertEquals(2, remotes.mountsCreated.get());
            assertTrue(remotes.closed(B0));
            assertTrue(exists(view.nodeStore().getRoot(), pathUnder(NAMESPACE_B)));
            assertEquals(B1, view.remoteMounts().statuses().get(0).federation().activeEndpoint());
        }
    }

    @Test
    public void testAFirstDirectoryReplacesTheStaticRoutes() throws Exception {
        MemoryNodeStore local = store(charterA(), null);
        CloseableStaticRemote staticRemote = new CloseableStaticRemote();

        try (FederatedReadView view = new FederatedReadView(local,
            ShardingRuntimeConfig.fromSpecs(true, "00-7f", "80-ff=http://cluster-b:8190"),
            new ValidatorReadViewBuilder((endpoint, mountName) -> staticRemote), remotes::mount)) {
            commit(local, directory(1L, entryA("ACTIVE"), entryB("ACTIVE", B0)));

            assertTrue(view.refresh());
            view.refresh();

            assertTrue(view.remoteMounts().fromDirectory());
            assertTrue(staticRemote.closed);
        }
    }

    private static Map<String, Object> entryB(String status, String... endpoints) {
        return entry(ENTERPRISE_B, status, List.of(endpoints), List.of(NAMESPACE_B), List.of());
    }

    private static void commit(NodeStore store, DirectorySnapshot snapshot) throws Exception {
        NodeBuilder root = store.getRoot().builder();
        snapshot.writeTo(root);
        store.merge(root, EmptyHook.INSTANCE, CommitInfo.EMPTY);
    }

    private static void seed(NodeStore store, String path) throws Exception {
        NodeBuilder root = store.getRoot().builder();
        NodeBuilder node = root;
        for (String part : path.substring(1).split("/")) {
            node = node.child(part);
        }
        store.merge(root, EmptyHook.INSTANCE, CommitInfo.EMPTY);
    }

    private static boolean exists(NodeState root, String path) {
        NodeState node = root;
        for (String part : path.substring(1).split("/")) {
            node = node.getChildNode(part);
        }
        return node.exists();
    }

    private static final class CloseableStaticRemote extends MemoryNodeStore implements java.io.Closeable {
        private boolean closed;

        @Override
        public void close() {
            closed = true;
        }
    }
}
