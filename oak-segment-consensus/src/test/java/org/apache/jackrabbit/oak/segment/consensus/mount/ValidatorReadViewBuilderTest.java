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
import org.apache.jackrabbit.oak.segment.SegmentNodeStoreBuilders;
import org.apache.jackrabbit.oak.segment.consensus.sharding.ShardingRuntimeConfig;
import org.apache.jackrabbit.oak.segment.memory.MemoryStore;
import org.apache.jackrabbit.oak.spi.commit.CommitInfo;
import org.apache.jackrabbit.oak.spi.commit.EmptyHook;
import org.apache.jackrabbit.oak.spi.commit.Observable;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.apache.jackrabbit.oak.spi.state.NodeStore;
import org.junit.Test;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class ValidatorReadViewBuilderTest {

    @Test
    public void testBuildComposesLocalAuthoritativeAndRemoteReadOnlyPrefixes() throws Exception {
        MemoryNodeStore localStore = new MemoryNodeStore();
        seedPath(localStore, "/oak-chain/aa/local-wallet/content/doc-1");

        CloseableMemoryNodeStore remoteStore = new CloseableMemoryNodeStore();
        seedPath(remoteStore, "/oak-chain/10/remote-wallet/content/doc-2");
        seedPath(remoteStore, "/oak-chain/11/remote-wallet/content/doc-3");

        ValidatorReadViewBuilder builder = new ValidatorReadViewBuilder(
            (endpoint, mountName) -> remoteStore
        );

        ValidatorReadViewBuilder.BuildResult result = builder.build(
            localStore,
            ShardingRuntimeConfig.fromSpecs(true, "80-ff", "10-11=http://cluster-a:8090")
        );

        NodeState root = result.getReadViewNodeStore().getRoot();
        assertTrue(exists(root, "/oak-chain/aa/local-wallet/content/doc-1"));
        assertTrue(exists(root, "/oak-chain/10/remote-wallet/content/doc-2"));
        assertTrue(exists(root, "/oak-chain/11/remote-wallet/content/doc-3"));
        assertEquals(2, result.getRemoteMountCount());

        result.close();
        assertTrue(remoteStore.closed);
    }

    @Test
    public void testLocalCommitsDoNotReadRemoteMounts() throws Exception {
        NodeStore localStore = SegmentNodeStoreBuilders.builder(new MemoryStore()).build();
        assertTrue("precondition: the authoritative segment store is Observable", localStore instanceof Observable);
        RootCountingNodeStore remoteStore = new RootCountingNodeStore();

        ValidatorReadViewBuilder.BuildResult result = new ValidatorReadViewBuilder((endpoint, mountName) -> remoteStore)
            .build(localStore, ShardingRuntimeConfig.fromSpecs(true, "00-7f", "80-ff=http://cluster-b:8190"));
        int rootReadsAfterBuild = remoteStore.rootReads.get();

        seedPath(localStore, "/oak-chain/0a/local-wallet/content/doc-1");
        seedPath(localStore, "/oak-chain/0b/local-wallet/content/doc-2");

        assertEquals("local commits must not fetch remote roots", rootReadsAfterBuild, remoteStore.rootReads.get());
        assertTrue(exists(result.getReadViewNodeStore().getRoot(), "/oak-chain/0b/local-wallet/content/doc-2"));
    }

    @Test
    public void testRemoteMountsReportUnavailableEndpointForItsPathsOnly() {
        MemoryNodeStore localStore = new MemoryNodeStore();
        ValidatorReadViewBuilder builder = new ValidatorReadViewBuilder((endpoint, mountName) ->
            new LazyHttpNodeStore(endpoint, mountName, 1L, 1L, new CircuitBreaker(mountName, 1, 60_000L, 1),
                (url, name, connectTimeoutMs, readTimeoutMs) -> {
                    throw new IOException("connection refused");
                }));

        ValidatorReadViewBuilder.BuildResult result = builder.build(
            localStore,
            ShardingRuntimeConfig.fromSpecs(true, "00-0f", "10-11=http://cluster-a:8090")
        );
        RemoteMounts remoteMounts = result.getRemoteMounts();

        assertFalse(result.getReadViewNodeStore().getRoot()
            .getChildNode("oak-chain").getChildNode("10").exists());
        RemoteMountStatus unavailable = remoteMounts.unavailableFor("/oak-chain/11/22/33/0xabc/content");
        assertNotNull(unavailable);
        assertEquals("http://cluster-a:8090", unavailable.endpoint());
        assertEquals("10-11", unavailable.prefixes());
        assertEquals("connection refused", unavailable.lastError());
        assertNull(remoteMounts.unavailableFor("/oak-chain/0a/22/33/0xabc/content"));
        assertNull(remoteMounts.unavailableFor("/oak-chain"));
        assertEquals(1, remoteMounts.statuses().size());
        assertFalse(remoteMounts.statuses().get(0).available());
    }

    @Test
    public void testRemoteMountsDescribePrefixesPerEndpointAsCompactRanges() {
        ValidatorReadViewBuilder.BuildResult result = new ValidatorReadViewBuilder((endpoint, mountName) -> new MemoryNodeStore())
            .build(new MemoryNodeStore(),
                ShardingRuntimeConfig.fromSpecs(true, "01-7f", "00=http://a:8190;80-81=http://a:8190;83-ff=http://b:8290"));

        List<RemoteMountStatus> statuses = result.getRemoteMounts().statuses();

        assertEquals(2, statuses.size());
        assertEquals("http://a:8190", statuses.get(0).endpoint());
        assertEquals("00,80-81", statuses.get(0).prefixes());
        assertEquals("83-ff", statuses.get(1).prefixes());
        assertTrue(statuses.get(1).available());
        assertNull(result.getRemoteMounts().unavailableFor("/oak-chain/90/aa"));
    }

    @Test
    public void testBuildReturnsLocalStoreWhenShardingDisabled() {
        MemoryNodeStore localStore = new MemoryNodeStore();
        ValidatorReadViewBuilder builder = new ValidatorReadViewBuilder();

        ValidatorReadViewBuilder.BuildResult result = builder.build(localStore, ShardingRuntimeConfig.disabled());

        assertTrue(result.getReadViewNodeStore() == localStore);
        assertEquals(0, result.getRemoteMountCount());
        assertSame(RemoteMounts.NONE, result.getRemoteMounts());
        assertTrue(RemoteMounts.NONE.statuses().isEmpty());
        assertNull(RemoteMounts.NONE.unavailableFor("/oak-chain/10"));
    }

    private static void seedPath(NodeStore nodeStore, String path) throws Exception {
        String[] parts = path.substring(1).split("/");
        NodeBuilder rootBuilder = nodeStore.getRoot().builder();
        NodeBuilder current = rootBuilder;
        for (String part : parts) {
            current = current.child(part);
        }
        current.setProperty("path", path);
        nodeStore.merge(rootBuilder, EmptyHook.INSTANCE, CommitInfo.EMPTY);
    }

    private static boolean exists(NodeState root, String path) {
        NodeState current = root;
        for (String part : path.substring(1).split("/")) {
            current = current.getChildNode(part);
            if (!current.exists()) {
                return false;
            }
        }
        return current.exists();
    }

    private static final class RootCountingNodeStore extends MemoryNodeStore {
        private final AtomicInteger rootReads = new AtomicInteger();

        @Override
        public NodeState getRoot() {
            rootReads.incrementAndGet();
            return super.getRoot();
        }
    }

    private static final class CloseableMemoryNodeStore extends MemoryNodeStore implements Closeable {
        private boolean closed;

        @Override
        public void close() throws IOException {
            closed = true;
        }
    }
}
