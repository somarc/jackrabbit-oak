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

import org.apache.jackrabbit.oak.api.CommitFailedException;
import org.apache.jackrabbit.oak.api.Type;
import org.apache.jackrabbit.oak.plugins.memory.MemoryNodeStore;
import org.apache.jackrabbit.oak.segment.consensus.federation.DirectoryEntry;
import org.apache.jackrabbit.oak.spi.commit.CommitInfo;
import org.apache.jackrabbit.oak.spi.commit.EmptyHook;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;
import org.apache.jackrabbit.oak.spi.state.NodeState;

import java.io.Closeable;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** In-memory remote clusters keyed by endpoint URL, with a scriptable identity check and outages. */
final class FakeRemotes {

    static final long RESET_MS = 80L;

    private final Map<String, Remote> remotes = new ConcurrentHashMap<>();
    private final Map<String, IOException> rejections = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> probes = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> connects = new ConcurrentHashMap<>();
    final AtomicInteger mountsCreated = new AtomicInteger();

    /** A remote at {@code url} whose root carries {@code servedBy = name}. */
    FakeRemotes serve(String url, String name) {
        Remote remote = new Remote();
        try {
            NodeBuilder root = remote.getRoot().builder();
            root.setProperty("servedBy", name);
            remote.merge(root, EmptyHook.INSTANCE, CommitInfo.EMPTY);
        } catch (CommitFailedException e) {
            throw new IllegalStateException(e);
        }
        remotes.put(url, remote);
        return this;
    }

    FakeRemotes reject(String url, IOException reason) {
        rejections.put(url, reason);
        return this;
    }

    void down(String url, boolean down) {
        remotes.get(url).down = down;
    }

    void seed(String url, String path) throws CommitFailedException {
        Remote remote = remotes.get(url);
        NodeBuilder root = remote.getRoot().builder();
        NodeBuilder node = root;
        for (String part : path.substring(1).split("/")) {
            node = node.child(part);
        }
        node.setProperty("path", path);
        remote.merge(root, EmptyHook.INSTANCE, CommitInfo.EMPTY);
    }

    int probes(String url) {
        return probes.computeIfAbsent(url, u -> new AtomicInteger()).get();
    }

    int connects(String url) {
        return connects.computeIfAbsent(url, u -> new AtomicInteger()).get();
    }

    boolean closed(String url) {
        return remotes.get(url).closed;
    }

    FederatedMountStore.IdentityProbe probe() {
        return (endpoint, enterpriseId, clusterId) -> {
            probes.computeIfAbsent(endpoint, u -> new AtomicInteger()).incrementAndGet();
            IOException rejection = rejections.get(endpoint);
            if (rejection != null) {
                throw rejection;
            }
            Remote remote = remotes.get(endpoint);
            if (remote == null || remote.down) {
                throw new IOException("Connection refused");
            }
        };
    }

    FederatedMountStore.EndpointStoreFactory endpoints() {
        return (url, mountName, breaker) -> new LazyHttpNodeStore(url, mountName, breaker,
            (endpoint, name) -> {
                connects.computeIfAbsent(endpoint, u -> new AtomicInteger()).incrementAndGet();
                Remote remote = remotes.get(endpoint);
                if (remote == null || remote.down) {
                    throw new IOException("Connection refused");
                }
                return remote;
            }, 0L, 0L);
    }

    FederatedMountStore mount(DirectoryEntry entry) {
        mountsCreated.incrementAndGet();
        return new FederatedMountStore(entry, FederatedReadView.mountName(entry), probe(), endpoints(),
            name -> new CircuitBreaker(name, 1, RESET_MS, 1));
    }

    static String servedBy(NodeState root) {
        return root.hasProperty("servedBy") ? root.getProperty("servedBy").getValue(Type.STRING) : null;
    }

    private static final class Remote extends MemoryNodeStore implements LazyHttpNodeStore.RefreshingNodeStore, Closeable {
        private volatile boolean down;
        private volatile boolean closed;

        @Override
        public void refresh() throws IOException {
            if (down) {
                throw new IOException("Connection refused");
            }
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
