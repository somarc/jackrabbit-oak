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

import org.apache.jackrabbit.oak.api.Blob;
import org.apache.jackrabbit.oak.api.CommitFailedException;
import org.apache.jackrabbit.oak.plugins.memory.EmptyNodeState;
import org.apache.jackrabbit.oak.segment.consensus.federation.DirectoryEntry;
import org.apache.jackrabbit.oak.spi.commit.CommitHook;
import org.apache.jackrabbit.oak.spi.commit.CommitInfo;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.apache.jackrabbit.oak.spi.state.NodeStore;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Read-only view of one remote enterprise, served by the ordered endpoints of its directory entry with one
 * circuit breaker each. Reads use the first endpoint that passed the identity check, whose breaker is not open
 * and that is connected and current, and fall over in order. Identity checks and connects happen only in
 * {@link #maintain()}, so neither a read nor a read-view rebuild waits on an endpoint that was never reached.
 * An endpoint whose breaker opens loses its verification and is checked again once the breaker lets it through.
 */
final class FederatedMountStore implements NodeStore, Closeable {

    private static final Logger LOG = LoggerFactory.getLogger(FederatedMountStore.class);

    @FunctionalInterface
    interface IdentityProbe {
        /** @throws IOException with the reason when {@code endpoint} is unreachable or serves another cluster */
        void verify(@NotNull String endpoint, @NotNull String enterpriseId, @NotNull String clusterId)
            throws IOException;
    }

    @FunctionalInterface
    interface EndpointStoreFactory {
        LazyHttpNodeStore create(String url, String mountName, CircuitBreaker breaker);
    }

    static final EndpointStoreFactory HTTP_ENDPOINTS = (url, mountName, breaker) ->
        new LazyHttpNodeStore(url, mountName, breaker, LazyHttpNodeStore::createRemoteNodeStore);

    private final String enterpriseId;
    private final String clusterId;
    private final List<Endpoint> endpoints;
    private final IdentityProbe probe;

    FederatedMountStore(@NotNull DirectoryEntry entry, @NotNull String mountName, @NotNull IdentityProbe probe,
                        @NotNull EndpointStoreFactory factory) {
        this(entry, mountName, probe, factory, CircuitBreaker::new);
    }

    FederatedMountStore(@NotNull DirectoryEntry entry, @NotNull String mountName, @NotNull IdentityProbe probe,
                        @NotNull EndpointStoreFactory factory, @NotNull Function<String, CircuitBreaker> breakers) {
        this.enterpriseId = entry.getEnterpriseId();
        this.clusterId = entry.getClusterId();
        this.probe = probe;
        List<Endpoint> list = new ArrayList<>();
        for (String url : entry.getEndpoints()) {
            CircuitBreaker breaker = breakers.apply(mountName + "@" + url);
            list.add(new Endpoint(url, breaker, factory.create(url, mountName, breaker)));
        }
        this.endpoints = List.copyOf(list);
    }

    /** Stores are reused across directory changes only while the entry's identity and endpoints are unchanged. */
    static String reuseKey(DirectoryEntry entry) {
        return entry.getEnterpriseId() + "|" + entry.getClusterId() + "|" + entry.getEndpoints();
    }

    /**
     * Verifies, connects and refreshes endpoints in order until one is usable. Runs on the read-view watcher,
     * never on a read; each identity check is bounded by the probe's timeouts.
     */
    void maintain() {
        for (Endpoint endpoint : endpoints) {
            endpoint.usable();
            if (!endpoint.verified && !verify(endpoint)) {
                continue;
            }
            endpoint.store.getRoot();
            if (endpoint.usable() && endpoint.store.isAvailable()) {
                return;
            }
        }
    }

    private boolean verify(Endpoint endpoint) {
        if (!endpoint.breaker.allowRequest()) {
            return false;
        }
        try {
            probe.verify(endpoint.url, enterpriseId, clusterId);
            endpoint.identityError = null;
            endpoint.verified = true;
            endpoint.breaker.recordSuccess();
            LOG.info("Federated mount {}: endpoint {} verified", enterpriseId, endpoint.url);
            return true;
        } catch (IOException | RuntimeException e) {
            endpoint.identityError = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            endpoint.breaker.recordFailure(e);
            LOG.warn("Federated mount {}: endpoint {} not used: {}", enterpriseId, endpoint.url, endpoint.identityError);
            return false;
        }
    }

    @Nullable
    private Endpoint active() {
        for (Endpoint endpoint : endpoints) {
            if (endpoint.usable() && endpoint.store.isAvailable()) {
                return endpoint;
            }
        }
        return null;
    }

    @Override
    @NotNull
    public NodeState getRoot() {
        NodeState stale = null;
        for (Endpoint endpoint : endpoints) {
            if (!endpoint.usable()) {
                continue;
            }
            NodeState root = endpoint.store.getRoot();
            if (endpoint.store.isAvailable()) {
                return root;
            }
            if (stale == null) {
                stale = root;
            }
        }
        // like a single lazy mount: keep the last known content, reported unavailable through status()
        return stale != null ? stale : EmptyNodeState.MISSING_NODE;
    }

    @NotNull
    RemoteMountStatus status(@NotNull List<String> namespaces, long directoryEpoch) {
        Endpoint active = active();
        Endpoint shown = active != null ? active : endpoints.get(0);
        RemoteMountStatus base = shown.store.status(String.join(",", namespaces));
        List<RemoteMountStatus.EndpointState> states = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        boolean anyVerified = false;
        for (Endpoint endpoint : endpoints) {
            RemoteMountStatus status = endpoint.store.status("");
            boolean verified = endpoint.verified;
            anyVerified |= verified;
            String error = !verified ? endpoint.identityError
                : endpoint.breaker.getState() == CircuitBreaker.State.OPEN ? "circuit open"
                : status.lastError();
            states.add(new RemoteMountStatus.EndpointState(endpoint.url, verified,
                endpoint.breaker.getState().name(), status.connected(), verified && status.available(), error));
            if (error != null) {
                errors.add(endpoint.url + ": " + error);
            }
        }
        return new RemoteMountStatus(shown.url, base.prefixes(), base.connected(), active != null,
            shown.breaker.getState().name(), base.revision(), base.lastRefreshAt(),
            active != null ? null : String.join("; ", errors),
            new RemoteMountStatus.Federation(enterpriseId, List.copyOf(namespaces), List.copyOf(states),
                active != null ? active.url : null, anyVerified, directoryEpoch));
    }

    @Override
    @NotNull
    public NodeState merge(@NotNull NodeBuilder builder, @NotNull CommitHook commitHook, @NotNull CommitInfo info)
            throws CommitFailedException {
        throw new CommitFailedException(CommitFailedException.UNSUPPORTED, 0,
            "Federated mount " + enterpriseId + " is read-only");
    }

    @Override
    @NotNull
    public NodeState rebase(@NotNull NodeBuilder builder) {
        throw new UnsupportedOperationException("Federated mount " + enterpriseId + " is read-only");
    }

    @Override
    public NodeState reset(@NotNull NodeBuilder builder) {
        throw new UnsupportedOperationException("Federated mount " + enterpriseId + " is read-only");
    }

    @Override
    @NotNull
    public Blob createBlob(InputStream inputStream) throws IOException {
        throw new IOException("Federated mount " + enterpriseId + " is read-only");
    }

    @Override
    @Nullable
    public Blob getBlob(@NotNull String reference) {
        Endpoint active = active();
        return active != null ? active.store.getBlob(reference) : null;
    }

    @Override
    @NotNull
    public String checkpoint(long lifetime, @NotNull Map<String, String> properties) {
        throw new UnsupportedOperationException("Federated mount " + enterpriseId + " is read-only");
    }

    @Override
    @NotNull
    public String checkpoint(long lifetime) {
        throw new UnsupportedOperationException("Federated mount " + enterpriseId + " is read-only");
    }

    @Override
    @NotNull
    public Map<String, String> checkpointInfo(@NotNull String checkpoint) {
        return Map.of();
    }

    @Override
    @NotNull
    public Iterable<String> checkpoints() {
        return List.of();
    }

    @Override
    @Nullable
    public NodeState retrieve(@NotNull String checkpoint) {
        return null;
    }

    @Override
    public boolean release(@NotNull String checkpoint) {
        throw new UnsupportedOperationException("Federated mount " + enterpriseId + " is read-only");
    }

    @Override
    public void close() throws IOException {
        IOException first = null;
        for (Endpoint endpoint : endpoints) {
            try {
                endpoint.store.close();
            } catch (IOException e) {
                first = first == null ? e : first;
            }
        }
        if (first != null) {
            throw first;
        }
    }

    /** Forwards a failed segment read to the endpoint that made it; true when it was one of this mount's. */
    boolean onReadFailure(@NotNull MountReadFailures.Failure failure) {
        for (Endpoint endpoint : endpoints) {
            if (endpoint.store.onReadFailure(failure)) {
                return true;
            }
        }
        return false;
    }

    private static final class Endpoint {
        private final String url;
        private final CircuitBreaker breaker;
        private final LazyHttpNodeStore store;
        private volatile boolean verified;
        private volatile String identityError = "identity not verified yet";

        private Endpoint(String url, CircuitBreaker breaker, LazyHttpNodeStore store) {
            this.url = url;
            this.breaker = breaker;
            this.store = store;
        }

        /** Verified, connected and not tripped. A tripped breaker drops the verification. */
        private boolean usable() {
            if (breaker.getState() == CircuitBreaker.State.OPEN) {
                if (verified) {
                    verified = false;
                    identityError = "circuit opened; identity is checked again once it resets";
                }
                return false;
            }
            return verified && store.isConnected();
        }
    }
}
