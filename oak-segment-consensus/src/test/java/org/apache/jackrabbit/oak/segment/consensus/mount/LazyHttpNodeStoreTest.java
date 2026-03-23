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
        LazyHttpNodeStore store = new LazyHttpNodeStore(
            "http://cluster-a:8090",
            "shard-0x100",
            1234L,
            5678L,
            new CircuitBreaker("shard-0x100"),
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
        assertEquals(CircuitBreaker.State.CLOSED, store.getCircuitState());
    }

    @Test
    public void testFailedInitializationReturnsEmptyRootAndDoesNotRetryWhileCircuitIsOpen() {
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

        assertTrue(root.exists());
        assertFalse(root.hasChildNode("oak-chain"));
        assertEquals(1, attempts.get());
        assertFalse(store.isConnected());
        assertTrue(store.isCircuitOpen());
        assertEquals(CircuitBreaker.State.OPEN, store.getCircuitState());
        assertTrue(store.checkpointInfo("missing").isEmpty());
        assertNull(store.retrieve("missing"));

        NodeState secondRoot = store.getRoot();

        assertTrue(secondRoot.exists());
        assertEquals(1, attempts.get());
    }

    @Test
    public void testReconnectResetsCircuitAndAllowsNewInitializationAttempt() {
        AtomicInteger attempts = new AtomicInteger();
        CloseableMemoryNodeStore recoveredStore = new CloseableMemoryNodeStore();
        CircuitBreaker circuitBreaker = new CircuitBreaker("shard-0x300", 1, 60_000L, 1);
        LazyHttpNodeStore store = new LazyHttpNodeStore(
            "http://cluster-c:8090",
            "shard-0x300",
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
        assertTrue(failedRoot.exists());
        assertEquals(1, attempts.get());
        assertTrue(store.isCircuitOpen());

        store.reconnect();

        assertFalse(store.isCircuitOpen());
        assertEquals(CircuitBreaker.State.CLOSED, store.getCircuitState());

        NodeState recoveredRoot = store.getRoot();

        assertTrue(recoveredRoot.exists());
        assertEquals(2, attempts.get());
        assertTrue(store.isConnected());
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
            assertTrue(root.exists());
            assertFalse(root.hasChildNode("oak-chain"));
        }

        assertEquals(1, attempts.get());
        assertEquals(CircuitBreaker.State.CLOSED, store.getCircuitState());
        assertFalse(store.isCircuitOpen());
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
        assertTrue(failedRoot.exists());
        assertEquals(1, attempts.get());
        assertTrue(store.isCircuitOpen());

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
            (endpoint, mountName, connectTimeoutMs, readTimeoutMs) -> remoteStore
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
            (endpoint, mountName, connectTimeoutMs, readTimeoutMs) -> remoteStore
        );

        NodeState firstRoot = store.getRoot();
        NodeState secondRoot = store.getRoot();

        assertTrue(firstRoot.exists());
        assertTrue(secondRoot.exists());
        assertEquals(2, remoteStore.refreshCount.get());
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
    }

    private static final class FlakyRefreshTrackingNodeStore extends MemoryNodeStore
        implements LazyHttpNodeStore.RefreshingNodeStore {

        private final AtomicInteger refreshCount = new AtomicInteger();

        @Override
        public void refresh() throws IOException {
            if (refreshCount.incrementAndGet() > 1) {
                throw new IOException("transient refresh failure");
            }
        }
    }
}
