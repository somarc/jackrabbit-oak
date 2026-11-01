/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jackrabbit.oak.segment.consensus.mount;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.jackrabbit.oak.plugins.memory.MemoryNodeStore;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.apache.jackrabbit.oak.spi.state.NodeStore;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class LazyHttpNodeStoreTest {

    @Test
    public void testGetRootLazilyInitializesDelegateOnceAndForwardsConstructorContract() {
        RecordingFactory factory = new RecordingFactory(new CloseableMemoryNodeStore());
        CircuitBreaker circuitBreaker = new CircuitBreaker("shard-0x100");
        LazyHttpNodeStore store = new LazyHttpNodeStore(
            "http://cluster-a:8090",
            "shard-0x100",
            1234L,
            5678L,
            circuitBreaker,
            factory
        );

        assertFalse(store.isConnected());

        NodeState firstRoot = store.getRoot();
        NodeState secondRoot = store.getRoot();

        assertNotNull(firstRoot);
        assertTrue(firstRoot.exists());
        assertTrue(secondRoot.exists());
        assertEquals(1, factory.invocationCount.get());
        assertEquals("http://cluster-a:8090", factory.lastEndpoint);
        assertEquals("shard-0x100", factory.lastMountName);
        assertEquals(1234L, factory.lastConnectTimeoutMs);
        assertEquals(5678L, factory.lastReadTimeoutMs);
        assertTrue(store.isConnected());
        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getState());
    }

    @Test
    public void testFailedInitializationReturnsMissingRootAndDoesNotRetryWhileCircuitIsOpen() {
        AtomicInteger attempts = new AtomicInteger();
        CircuitBreaker circuitBreaker = new CircuitBreaker("shard-0x200", 1, 60_000L, 1);
        LazyHttpNodeStore store = new LazyHttpNodeStore(
            "http://cluster-b:8090",
            "shard-0x200",
            5000L,
            30000L,
            circuitBreaker,
            (endpoint, mountName, connectTimeoutMs, readTimeoutMs) -> {
                attempts.incrementAndGet();
                throw new IOException("remote unavailable");
            }
        );

        NodeState root = store.getRoot();

        assertFalse(root.exists());
        assertFalse(root.hasChildNode("oak-chain"));
        assertFalse(root.getChildNode("oak-chain").exists());
        assertEquals(1, attempts.get());
        assertFalse(store.isConnected());
        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getState());
        assertTrue(store.checkpointInfo("missing").isEmpty());
        assertNull(store.retrieve("missing"));

        NodeState secondRoot = store.getRoot();

        assertFalse(secondRoot.exists());
        assertEquals(1, attempts.get());
    }

    @Test
    public void testBurstInitializationFailuresAreThrottledBeforeOpeningCircuit() {
        AtomicInteger attempts = new AtomicInteger();
        CircuitBreaker circuitBreaker = new CircuitBreaker("shard-0x250", 5, 60_000L, 1);
        LazyHttpNodeStore store = new LazyHttpNodeStore(
            "http://cluster-b:8090",
            "shard-0x250",
            5000L,
            30000L,
            circuitBreaker,
            (endpoint, mountName, connectTimeoutMs, readTimeoutMs) -> {
                attempts.incrementAndGet();
                throw new IOException("remote unavailable");
            },
            60_000L
        );

        for (int i = 0; i < 5; i++) {
            NodeState root = store.getRoot();
            assertFalse(root.exists());
            assertFalse(root.hasChildNode("oak-chain"));
        }

        assertEquals(1, attempts.get());
        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getState());
    }

    @Test
    public void testInitializationRetriesAfterCircuitResetTimeout() {
        AtomicInteger attempts = new AtomicInteger();
        CloseableMemoryNodeStore recoveredStore = new CloseableMemoryNodeStore();
        CircuitBreaker circuitBreaker = new CircuitBreaker("shard-0x350", 1, 0L, 1);
        LazyHttpNodeStore store = new LazyHttpNodeStore(
            "http://cluster-c:8090",
            "shard-0x350",
            5000L,
            30000L,
            circuitBreaker,
            (endpoint, mountName, connectTimeoutMs, readTimeoutMs) -> {
                if (attempts.incrementAndGet() == 1) {
                    throw new IOException("first attempt fails");
                }
                return recoveredStore;
            }
        );

        NodeState failedRoot = store.getRoot();
        assertFalse(failedRoot.exists());
        assertEquals(1, attempts.get());
        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getState());

        NodeState recoveredRoot = store.getRoot();

        assertTrue(recoveredRoot.exists());
        assertEquals(2, attempts.get());
        assertTrue(store.isConnected());
    }

    @Test
    public void testCloseClosesDelegateAndAllowsFreshInitializationLater() throws Exception {
        CloseableMemoryNodeStore firstStore = new CloseableMemoryNodeStore();
        CloseableMemoryNodeStore secondStore = new CloseableMemoryNodeStore();
        RecordingFactory factory = new RecordingFactory(firstStore, secondStore);
        LazyHttpNodeStore store = new LazyHttpNodeStore(
            "http://cluster-d:8090",
            "shard-0x400",
            5000L,
            30000L,
            new CircuitBreaker("shard-0x400"),
            factory
        );

        store.getRoot();
        assertEquals(1, factory.invocationCount.get());
        assertTrue(store.isConnected());

        store.close();

        assertTrue(firstStore.closed);
        assertFalse(store.isConnected());

        store.getRoot();

        assertEquals(2, factory.invocationCount.get());
        assertTrue(store.isConnected());
        assertFalse(secondStore.closed);
    }

    @Test
    public void testGetRootRefreshesRefreshingDelegates() {
        RefreshTrackingNodeStore remoteStore = new RefreshTrackingNodeStore();
        LazyHttpNodeStore store = new LazyHttpNodeStore(
            "http://cluster-e:8090",
            "shard-0x500",
            5000L,
            30000L,
            new CircuitBreaker("shard-0x500"),
            (endpoint, mountName, connectTimeoutMs, readTimeoutMs) -> remoteStore,
            1_000L,
            0L
        );

        store.getRoot();
        store.getRoot();

        assertEquals(2, remoteStore.refreshCount.get());
    }

    @Test
    public void testRefreshFailureFallsBackToLastKnownRemoteState() {
        FlakyRefreshTrackingNodeStore remoteStore = new FlakyRefreshTrackingNodeStore();
        LazyHttpNodeStore store = new LazyHttpNodeStore(
            "http://cluster-f:8090",
            "shard-0x600",
            5000L,
            30000L,
            new CircuitBreaker("shard-0x600"),
            (endpoint, mountName, connectTimeoutMs, readTimeoutMs) -> remoteStore,
            1_000L,
            0L
        );

        NodeState firstRoot = store.getRoot();
        assertTrue(store.status("60").available());
        NodeState secondRoot = store.getRoot();

        assertTrue(firstRoot.exists());
        assertTrue(secondRoot.exists());
        assertEquals(2, remoteStore.refreshCount.get());
        RemoteMountStatus status = store.status("60");
        assertTrue(status.connected());
        assertFalse(status.available());
        assertEquals("transient refresh failure", status.lastError());
    }

    @Test
    public void testStatusIsUnavailableUntilConnectedAndAfterFailedConnect() {
        LazyHttpNodeStore store = new LazyHttpNodeStore(
            "http://cluster-g:8090",
            "shard-0x700",
            5000L,
            30000L,
            new CircuitBreaker("shard-0x700", 1, 60_000L, 1),
            (endpoint, mountName, connectTimeoutMs, readTimeoutMs) -> {
                throw new IOException("connection refused");
            }
        );

        RemoteMountStatus before = store.status("70-7f");
        assertFalse(before.connected());
        assertFalse(before.available());
        assertNull(before.lastRefreshAt());
        assertEquals("http://cluster-g:8090", before.endpoint());
        assertEquals("70-7f", before.prefixes());

        store.getRoot();

        RemoteMountStatus after = store.status("70-7f");
        assertFalse(after.available());
        assertEquals("connection refused", after.lastError());
        assertEquals("OPEN", after.circuit());
    }

    @Test
    public void testStatusIsAvailableAfterConnectWithRevisionAndRefreshTime() {
        RefreshTrackingNodeStore remoteStore = new RefreshTrackingNodeStore();
        LazyHttpNodeStore store = new LazyHttpNodeStore(
            "http://cluster-h:8090",
            "shard-0x800",
            5000L,
            30000L,
            new CircuitBreaker("shard-0x800"),
            (endpoint, mountName, connectTimeoutMs, readTimeoutMs) -> remoteStore
        );

        assertTrue(store.getRoot().exists());

        RemoteMountStatus status = store.status("80");
        assertTrue(status.connected());
        assertTrue(status.available());
        assertNull(status.lastError());
        assertNotNull(status.lastRefreshAt());
        assertEquals("rev-1", status.revision());
        assertEquals("CLOSED", status.circuit());
        assertEquals(Boolean.TRUE, status.toMap().get("available"));
    }

    @Test
    public void testRefreshFailuresAccumulateOpenCircuitAndStopRefreshing() {
        AlwaysFailingRefreshNodeStore remoteStore = new AlwaysFailingRefreshNodeStore();
        CircuitBreaker circuitBreaker = new CircuitBreaker("shard-0x900", 3, 60_000L, 1);
        LazyHttpNodeStore store = new LazyHttpNodeStore(
            "http://cluster-i:8090",
            "shard-0x900",
            5000L,
            30000L,
            circuitBreaker,
            (endpoint, mountName, connectTimeoutMs, readTimeoutMs) -> remoteStore,
            1_000L,
            0L
        );

        for (int i = 0; i < 5; i++) {
            assertTrue("last known root keeps being served", store.getRoot().exists());
        }

        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getState());
        assertEquals(3, remoteStore.refreshCount.get());
        assertFalse(store.status("90").available());
    }

    @Test
    public void testRefreshRecoversAvailability() {
        FlakyRefreshTrackingNodeStore remoteStore = new FlakyRefreshTrackingNodeStore();
        remoteStore.recoverAfter = 2;
        LazyHttpNodeStore store = new LazyHttpNodeStore(
            "http://cluster-j:8090",
            "shard-0xa00",
            5000L,
            30000L,
            new CircuitBreaker("shard-0xa00"),
            (endpoint, mountName, connectTimeoutMs, readTimeoutMs) -> remoteStore,
            1_000L,
            0L
        );

        store.getRoot();
        store.getRoot();
        assertFalse(store.status("a0").available());

        store.getRoot();
        assertTrue(store.status("a0").available());
    }

    @Test
    public void testConcurrentReadDuringSlowConnectSeesMissingRootInsteadOfWaiting() throws Exception {
        CountDownLatch connecting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        LazyHttpNodeStore store = new LazyHttpNodeStore(
            "http://cluster-k:8090",
            "shard-0xb00",
            5000L,
            30000L,
            new CircuitBreaker("shard-0xb00"),
            (endpoint, mountName, connectTimeoutMs, readTimeoutMs) -> {
                connecting.countDown();
                release.await();
                return new MemoryNodeStore();
            }
        );

        Thread connector = new Thread(store::getRoot);
        connector.start();
        assertTrue(connecting.await(5, TimeUnit.SECONDS));

        assertFalse(store.getRoot().exists());

        release.countDown();
        connector.join(5_000L);
        assertTrue(store.getRoot().exists());
    }

    private static final class RecordingFactory implements LazyHttpNodeStore.RemoteNodeStoreFactory {
        private final AtomicInteger invocationCount = new AtomicInteger();
        private final Deque<NodeStore> stores = new ArrayDeque<>();
        private String lastEndpoint;
        private String lastMountName;
        private long lastConnectTimeoutMs;
        private long lastReadTimeoutMs;

        private RecordingFactory(NodeStore... stores) {
            for (NodeStore store : stores) {
                this.stores.addLast(store);
            }
        }

        @Override
        public NodeStore create(String endpoint, String mountName, long connectTimeoutMs, long readTimeoutMs) {
            invocationCount.incrementAndGet();
            lastEndpoint = endpoint;
            lastMountName = mountName;
            lastConnectTimeoutMs = connectTimeoutMs;
            lastReadTimeoutMs = readTimeoutMs;
            NodeStore store = stores.pollFirst();
            if (store == null) {
                fail("No NodeStore queued for factory invocation");
            }
            return store;
        }
    }

    private static final class CloseableMemoryNodeStore extends MemoryNodeStore implements Closeable {
        private boolean closed;

        @Override
        public void close() throws IOException {
            closed = true;
        }
    }

    private static final class RefreshTrackingNodeStore extends MemoryNodeStore
        implements LazyHttpNodeStore.RefreshingNodeStore {

        private final AtomicInteger refreshCount = new AtomicInteger();

        @Override
        public void refresh() {
            refreshCount.incrementAndGet();
        }

        @Override
        public String revision() {
            return "rev-1";
        }
    }

    private static final class AlwaysFailingRefreshNodeStore extends MemoryNodeStore
        implements LazyHttpNodeStore.RefreshingNodeStore {

        private final AtomicInteger refreshCount = new AtomicInteger();

        @Override
        public void refresh() throws IOException {
            refreshCount.incrementAndGet();
            throw new IOException("journal unreachable");
        }
    }

    private static final class FlakyRefreshTrackingNodeStore extends MemoryNodeStore
        implements LazyHttpNodeStore.RefreshingNodeStore {

        private final AtomicInteger refreshCount = new AtomicInteger();
        private int recoverAfter = Integer.MAX_VALUE;

        @Override
        public void refresh() throws IOException {
            int count = refreshCount.incrementAndGet();
            if (count > 1 && count <= recoverAfter) {
                throw new IOException("transient refresh failure");
            }
        }
    }
}
