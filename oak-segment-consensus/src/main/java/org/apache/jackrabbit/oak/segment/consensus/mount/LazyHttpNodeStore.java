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

import org.apache.jackrabbit.oak.api.Blob;
import org.apache.jackrabbit.oak.api.CommitFailedException;
import org.apache.jackrabbit.oak.plugins.memory.EmptyNodeState;
import org.apache.jackrabbit.oak.segment.SegmentNodeStoreBuilders;
import org.apache.jackrabbit.oak.segment.file.JournalReader;
import org.apache.jackrabbit.oak.segment.file.ReadOnlyFileStore;
import org.apache.jackrabbit.oak.segment.consensus.mount.http.HttpPersistence;

import static org.apache.jackrabbit.oak.segment.file.FileStoreBuilder.fileStoreBuilder;
import org.apache.jackrabbit.oak.spi.commit.CommitHook;
import org.apache.jackrabbit.oak.spi.commit.CommitInfo;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.apache.jackrabbit.oak.spi.state.NodeStore;
import org.apache.jackrabbit.oak.spi.state.ProxyNodeStore;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Lazy-initialized HTTP-backed read-only NodeStore with circuit breaker protection.
 * <ul>
 *   <li>Connects on first access; startup succeeds even if the remote is unreachable.</li>
 *   <li>Until it has connected, the root is a non-existent node: absent, never a phantom empty node.</li>
 *   <li>After a failed refresh it keeps serving the last known root but reports itself unavailable
 *       through {@link #status(String)}, so callers can answer "unavailable" instead of stale or absent.</li>
 *   <li>At most one connect or refresh per endpoint is in flight; other readers never queue behind it.</li>
 * </ul>
 */
public class LazyHttpNodeStore implements NodeStore, Closeable {
    
    private static final Logger LOG = LoggerFactory.getLogger(LazyHttpNodeStore.class);
    private static final long DEFAULT_INITIALIZATION_RETRY_INTERVAL_MS = 1_000L;
    private static final long DEFAULT_REFRESH_INTERVAL_MS = 1_000L;

    @FunctionalInterface
    interface RemoteNodeStoreFactory {
        NodeStore create(String endpoint, String mountName, long connectTimeoutMs, long readTimeoutMs) throws Exception;
    }

    interface RefreshingNodeStore extends NodeStore {
        void refresh() throws IOException;

        @Nullable
        default String revision() {
            return null;
        }
    }
    
    private final String endpoint;
    private final String mountName;
    private final CircuitBreaker circuitBreaker;
    private final long connectTimeoutMs;
    private final long readTimeoutMs;
    private final RemoteNodeStoreFactory remoteNodeStoreFactory;
    private final long initializationRetryIntervalMs;
    private final long refreshIntervalMs;
    
    private final AtomicReference<NodeStore> delegate = new AtomicReference<>();
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final AtomicLong lastInitializationAttemptAt = new AtomicLong(0);
    private final ReentrantLock initializationLock = new ReentrantLock();
    private final AtomicLong lastRefreshAttemptAt = new AtomicLong(0);
    private final AtomicBoolean refreshInFlight = new AtomicBoolean(false);
    private volatile long lastRefreshAt;
    private volatile String lastError = "not connected yet";
    
    /**
     * Create a lazy HTTP-backed NodeStore.
     *
     * @param endpoint HTTP endpoint URL (e.g., "http://cluster-a:8090")
     * @param mountName Mount name for logging
     */
    public LazyHttpNodeStore(String endpoint, String mountName) {
        this(endpoint, mountName, 5000, 30000, new CircuitBreaker(mountName), LazyHttpNodeStore::createRemoteNodeStore);
    }

    LazyHttpNodeStore(String endpoint,
                      String mountName,
                      long connectTimeoutMs,
                      long readTimeoutMs,
                      CircuitBreaker circuitBreaker,
                      RemoteNodeStoreFactory remoteNodeStoreFactory) {
        this(endpoint, mountName, connectTimeoutMs, readTimeoutMs, circuitBreaker, remoteNodeStoreFactory,
            DEFAULT_INITIALIZATION_RETRY_INTERVAL_MS);
    }

    LazyHttpNodeStore(String endpoint,
                      String mountName,
                      long connectTimeoutMs,
                      long readTimeoutMs,
                      CircuitBreaker circuitBreaker,
                      RemoteNodeStoreFactory remoteNodeStoreFactory,
                      long initializationRetryIntervalMs) {
        this(endpoint, mountName, connectTimeoutMs, readTimeoutMs, circuitBreaker, remoteNodeStoreFactory,
            initializationRetryIntervalMs, DEFAULT_REFRESH_INTERVAL_MS);
    }

    LazyHttpNodeStore(String endpoint,
                      String mountName,
                      long connectTimeoutMs,
                      long readTimeoutMs,
                      CircuitBreaker circuitBreaker,
                      RemoteNodeStoreFactory remoteNodeStoreFactory,
                      long initializationRetryIntervalMs,
                      long refreshIntervalMs) {
        this.endpoint = endpoint;
        this.mountName = mountName;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.circuitBreaker = circuitBreaker;
        this.remoteNodeStoreFactory = remoteNodeStoreFactory;
        this.initializationRetryIntervalMs = initializationRetryIntervalMs;
        this.refreshIntervalMs = refreshIntervalMs;
        
        LOG.info("LazyHttpNodeStore[{}] created for {} (lazy init)", mountName, endpoint);
    }
    
    /**
     * Initialize the underlying NodeStore on first access. Returns {@code null}
     * while the remote is not connected, including while another thread is connecting.
     */
    @Nullable
    private NodeStore getDelegate() {
        NodeStore store = delegate.get();
        if (store != null) {
            return store;
        }
        
        if (!circuitBreaker.allowRequest()) {
            LOG.debug("LazyHttpNodeStore[{}] circuit open, remote treated as unavailable", mountName);
            return null;
        }
        boolean throttleInitializationAttempt = circuitBreaker.getState() == CircuitBreaker.State.CLOSED;
        
        if (!initializationLock.tryLock()) {
            return null;
        }
        try {
            store = delegate.get();
            if (store != null) {
                return store;
            }

            long now = System.currentTimeMillis();
            if (throttleInitializationAttempt && initializationRetryIntervalMs > 0L) {
                long lastAttempt = lastInitializationAttemptAt.get();
                if (lastAttempt > 0L && now - lastAttempt < initializationRetryIntervalMs) {
                    LOG.debug("LazyHttpNodeStore[{}] skipping reconnect attempt after {}ms backoff window",
                        mountName, initializationRetryIntervalMs);
                    return null;
                }
            }
            lastInitializationAttemptAt.set(now);
            
            try {
                LOG.info("LazyHttpNodeStore[{}] initializing connection to {}...", mountName, endpoint);

                store = remoteNodeStoreFactory.create(endpoint, mountName, connectTimeoutMs, readTimeoutMs);
                
                delegate.set(store);
                initialized.set(true);
                lastInitializationAttemptAt.set(0L);
                lastRefreshAttemptAt.set(System.currentTimeMillis());
                recordSuccess();
                
                LOG.info("✅ LazyHttpNodeStore[{}] connected to {}", mountName, endpoint);
                return store;
                
            } catch (Exception e) {
                if (initialized.get()) {
                    LOG.warn("❌ LazyHttpNodeStore[{}] failed to reconnect: {}", mountName, describe(e));
                } else {
                    LOG.info("⏳ LazyHttpNodeStore[{}] remote mount not ready yet: {}", mountName, describe(e));
                }
                recordFailure(e);
                return null;
            }
        } finally {
            initializationLock.unlock();
        }
    }

    private void refreshIfDue(NodeStore store) {
        if (!(store instanceof RefreshingNodeStore)) {
            return;
        }
        long now = System.currentTimeMillis();
        long previousAttempt = lastRefreshAttemptAt.get();
        if (now - previousAttempt < refreshIntervalMs || !lastRefreshAttemptAt.compareAndSet(previousAttempt, now)) {
            return;
        }
        if (!circuitBreaker.allowRequest() || !refreshInFlight.compareAndSet(false, true)) {
            return;
        }
        try {
            ((RefreshingNodeStore) store).refresh();
            recordSuccess();
        } catch (IOException | RuntimeException e) {
            LOG.warn("LazyHttpNodeStore[{}] refresh failed: {}", mountName, describe(e));
            recordFailure(e);
        } finally {
            refreshInFlight.set(false);
        }
    }

    private void recordSuccess() {
        circuitBreaker.recordSuccess();
        lastRefreshAt = System.currentTimeMillis();
        lastError = null;
    }

    private void recordFailure(Exception e) {
        circuitBreaker.recordFailure(e);
        lastError = describe(e);
    }

    private static String describe(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }
    
    @Override
    @NotNull
    public NodeState getRoot() {
        NodeStore store = getDelegate();
        if (store == null) {
            return EmptyNodeState.MISSING_NODE;
        }
        refreshIfDue(store);
        try {
            return store.getRoot();
        } catch (RuntimeException e) {
            LOG.warn("LazyHttpNodeStore[{}] getRoot failed: {}", mountName, describe(e));
            recordFailure(e);
            return EmptyNodeState.MISSING_NODE;
        }
    }
    
    @Override
    @NotNull
    public NodeState merge(
            @NotNull NodeBuilder builder,
            @NotNull CommitHook commitHook,
            @NotNull CommitInfo info) throws CommitFailedException {
        // Read-only - reject writes
        throw new CommitFailedException(
            CommitFailedException.UNSUPPORTED, 0,
            "LazyHttpNodeStore[" + mountName + "] is read-only"
        );
    }
    
    @Override
    @NotNull
    public NodeState rebase(@NotNull NodeBuilder builder) {
        NodeStore store = getDelegate();
        if (store == null) {
            return EmptyNodeState.MISSING_NODE;
        }
        return store.rebase(builder);
    }
    
    @Override
    public NodeState reset(@NotNull NodeBuilder builder) {
        NodeStore store = getDelegate();
        if (store == null) {
            return EmptyNodeState.MISSING_NODE;
        }
        return store.reset(builder);
    }
    
    @Override
    @NotNull
    public Blob createBlob(InputStream inputStream) throws IOException {
        throw new IOException("LazyHttpNodeStore[" + mountName + "] is read-only");
    }
    
    @Override
    @Nullable
    public Blob getBlob(@NotNull String reference) {
        NodeStore store = getDelegate();
        if (store == null) {
            return null;
        }
        return store.getBlob(reference);
    }
    
    @Override
    @NotNull
    public String checkpoint(long lifetime, @NotNull Map<String, String> properties) {
        throw new UnsupportedOperationException("LazyHttpNodeStore[" + mountName + "] is read-only");
    }
    
    @Override
    @NotNull
    public String checkpoint(long lifetime) {
        throw new UnsupportedOperationException("LazyHttpNodeStore[" + mountName + "] is read-only");
    }
    
    @Override
    @NotNull
    public Map<String, String> checkpointInfo(@NotNull String checkpoint) {
        NodeStore store = getDelegate();
        if (store == null) {
            return Map.of();
        }
        return store.checkpointInfo(checkpoint);
    }
    
    @Override
    @NotNull
    public Iterable<String> checkpoints() {
        NodeStore store = getDelegate();
        if (store == null) {
            return java.util.Collections.emptyList();
        }
        return store.checkpoints();
    }
    
    @Override
    @Nullable
    public NodeState retrieve(@NotNull String checkpoint) {
        NodeStore store = getDelegate();
        if (store == null) {
            return null;
        }
        return store.retrieve(checkpoint);
    }
    
    @Override
    public boolean release(@NotNull String checkpoint) {
        throw new UnsupportedOperationException("LazyHttpNodeStore[" + mountName + "] is read-only");
    }
    
    @Override
    public void close() throws IOException {
        NodeStore store = delegate.getAndSet(null);
        if (store instanceof Closeable) {
            ((Closeable) store).close();
        }
        initialized.set(false);
        lastInitializationAttemptAt.set(0L);
        lastError = "closed";
        LOG.info("LazyHttpNodeStore[{}] closed", mountName);
    }
    
    /**
     * Check if the store is connected.
     *
     * @return true if connected
     */
    public boolean isConnected() {
        return delegate.get() != null;
    }

    /**
     * Current state of this endpoint for health reporting and read gating.
     *
     * @param prefixes the wallet prefixes this endpoint serves, as reported
     */
    @NotNull
    public RemoteMountStatus status(@NotNull String prefixes) {
        NodeStore store = delegate.get();
        String error = lastError;
        long refreshedAt = lastRefreshAt;
        return new RemoteMountStatus(
            endpoint,
            prefixes,
            store != null,
            store != null && error == null,
            circuitBreaker.getState().name(),
            store instanceof RefreshingNodeStore ? ((RefreshingNodeStore) store).revision() : null,
            refreshedAt > 0L ? refreshedAt : null,
            error
        );
    }
    
    /**
     * Get the endpoint URL.
     *
     * @return Endpoint URL
     */
    public String getEndpoint() {
        return endpoint;
    }
    
    /**
     * Get the mount name.
     *
     * @return Mount name
     */
    public String getMountName() {
        return mountName;
    }
    
    @Override
    public String toString() {
        return String.format(
            "LazyHttpNodeStore[%s]{endpoint=%s, connected=%s, circuit=%s}",
            mountName, endpoint, isConnected(), circuitBreaker.getState()
        );
    }

    private static NodeStore createRemoteNodeStore(
            String endpoint,
            String mountName,
            long connectTimeoutMs,
            long readTimeoutMs) throws Exception {
        // Timeouts are reserved for future HTTP client wiring and retained as part of the constructor contract.
        return new RefreshingRemoteNodeStore(endpoint, mountName);
    }

    private static final class RefreshingRemoteNodeStore extends ProxyNodeStore implements RefreshingNodeStore, Closeable {
        private final HttpPersistence persistence;
        private final ReadOnlyFileStore fileStore;
        private final NodeStore nodeStore;
        private volatile String currentRevision;

        private RefreshingRemoteNodeStore(String endpoint, String mountName) throws Exception {
            this.persistence = new HttpPersistence(endpoint);

            java.io.File tempDir = java.nio.file.Files.createTempDirectory("oak-http-" + mountName).toFile();
            tempDir.deleteOnExit();

            this.fileStore = fileStoreBuilder(tempDir)
                .withCustomPersistence(persistence)
                .buildReadOnly();
            this.nodeStore = SegmentNodeStoreBuilders.builder(fileStore).build();
            this.currentRevision = readLatestRevision(persistence);
        }

        @Override
        public void refresh() throws IOException {
            String latestRevision = readLatestRevision(persistence);
            if (latestRevision == null || latestRevision.equals(currentRevision)) {
                return;
            }

            fileStore.setRevision(latestRevision);
            currentRevision = latestRevision;
        }

        @Override
        @Nullable
        public String revision() {
            return currentRevision;
        }

        @Override
        protected NodeStore getNodeStore() {
            return nodeStore;
        }

        @Override
        public void close() throws IOException {
            fileStore.close();
        }
    }

    @Nullable
    private static String readLatestRevision(HttpPersistence persistence) throws IOException {
        try (JournalReader journalReader = new JournalReader(persistence.getJournalFile())) {
            if (!journalReader.hasNext()) {
                return null;
            }
            return journalReader.next().getRevision();
        }
    }
}
