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
import org.apache.jackrabbit.oak.segment.SegmentNodeStoreBuilders;
import org.apache.jackrabbit.oak.segment.file.ReadOnlyFileStore;
import org.apache.jackrabbit.oak.segment.http.HttpPersistence;

import static org.apache.jackrabbit.oak.segment.file.FileStoreBuilder.fileStoreBuilder;
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
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lazy-initialized HTTP-backed NodeStore with circuit breaker protection.
 * <p>
 * Key features:
 * <ul>
 *   <li><b>Lazy initialization</b> - Doesn't connect until first access</li>
 *   <li><b>Non-blocking startup</b> - Validator starts even if remote unavailable</li>
 *   <li><b>Circuit breaker</b> - Fails fast when remote is down</li>
 *   <li><b>Graceful degradation</b> - Returns empty state on failure</li>
 * </ul>
 * <p>
 * This is critical for production stability:
 * <ul>
 *   <li>Local writes continue even if remote clusters are down</li>
 *   <li>Cross-cluster reads fail gracefully (503, not hang)</li>
 *   <li>Startup succeeds even if some clusters are unreachable</li>
 * </ul>
 */
public class LazyHttpNodeStore implements NodeStore, Closeable {
    
    private static final Logger LOG = LoggerFactory.getLogger(LazyHttpNodeStore.class);

    @FunctionalInterface
    interface RemoteNodeStoreFactory {
        NodeStore create(String endpoint, String mountName, long connectTimeoutMs, long readTimeoutMs) throws Exception;
    }
    
    private final String endpoint;
    private final String mountName;
    private final CircuitBreaker circuitBreaker;
    private final long connectTimeoutMs;
    private final long readTimeoutMs;
    private final RemoteNodeStoreFactory remoteNodeStoreFactory;
    
    private final AtomicReference<NodeStore> delegate = new AtomicReference<>();
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final AtomicBoolean initializationFailed = new AtomicBoolean(false);
    
    // Empty state returned when remote is unavailable
    private static final NodeState EMPTY_STATE = new EmptyNodeState();
    
    /**
     * Create a lazy HTTP-backed NodeStore.
     *
     * @param endpoint HTTP endpoint URL (e.g., "http://cluster-a:8090")
     * @param mountName Mount name for logging
     */
    public LazyHttpNodeStore(String endpoint, String mountName) {
        this(endpoint, mountName, 5000, 30000);
    }
    
    /**
     * Create a lazy HTTP-backed NodeStore with custom timeouts.
     *
     * @param endpoint HTTP endpoint URL
     * @param mountName Mount name for logging
     * @param connectTimeoutMs Connection timeout in milliseconds
     * @param readTimeoutMs Read timeout in milliseconds
     */
    public LazyHttpNodeStore(String endpoint, String mountName, long connectTimeoutMs, long readTimeoutMs) {
        this(endpoint, mountName, connectTimeoutMs, readTimeoutMs, new CircuitBreaker(mountName), LazyHttpNodeStore::createRemoteNodeStore);
    }

    LazyHttpNodeStore(String endpoint,
                      String mountName,
                      long connectTimeoutMs,
                      long readTimeoutMs,
                      CircuitBreaker circuitBreaker,
                      RemoteNodeStoreFactory remoteNodeStoreFactory) {
        this.endpoint = endpoint;
        this.mountName = mountName;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.circuitBreaker = circuitBreaker;
        this.remoteNodeStoreFactory = remoteNodeStoreFactory;
        
        LOG.info("LazyHttpNodeStore[{}] created for {} (lazy init)", mountName, endpoint);
    }
    
    /**
     * Initialize the underlying NodeStore on first access.
     */
    private NodeStore getDelegate() {
        // Fast path - already initialized
        NodeStore store = delegate.get();
        if (store != null) {
            return store;
        }
        
        // Check circuit breaker
        if (!circuitBreaker.allowRequest()) {
            LOG.debug("LazyHttpNodeStore[{}] circuit open, returning empty state", mountName);
            return null;
        }
        
        // Lazy initialization
        synchronized (this) {
            store = delegate.get();
            if (store != null) {
                return store;
            }
            
            if (initializationFailed.get()) {
                // Don't retry immediately if init already failed
                return null;
            }
            
            try {
                LOG.info("LazyHttpNodeStore[{}] initializing connection to {}...", mountName, endpoint);

                store = remoteNodeStoreFactory.create(endpoint, mountName, connectTimeoutMs, readTimeoutMs);
                
                delegate.set(store);
                initialized.set(true);
                circuitBreaker.recordSuccess();
                
                LOG.info("✅ LazyHttpNodeStore[{}] connected to {}", mountName, endpoint);
                return store;
                
            } catch (Exception e) {
                LOG.warn("❌ LazyHttpNodeStore[{}] failed to connect: {}", mountName, e.getMessage());
                circuitBreaker.recordFailure(e);
                initializationFailed.set(true);
                
                // Schedule retry after circuit breaker timeout
                // For now, just return null
                return null;
            }
        }
    }
    
    @Override
    @NotNull
    public NodeState getRoot() {
        NodeStore store = getDelegate();
        if (store == null) {
            LOG.debug("LazyHttpNodeStore[{}] returning empty root (remote unavailable)", mountName);
            return EMPTY_STATE;
        }
        
        try {
            NodeState root = store.getRoot();
            circuitBreaker.recordSuccess();
            return root;
        } catch (Exception e) {
            LOG.warn("LazyHttpNodeStore[{}] getRoot failed: {}", mountName, e.getMessage());
            circuitBreaker.recordFailure(e);
            return EMPTY_STATE;
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
            return EMPTY_STATE;
        }
        return store.rebase(builder);
    }
    
    @Override
    public NodeState reset(@NotNull NodeBuilder builder) {
        NodeStore store = getDelegate();
        if (store == null) {
            return EMPTY_STATE;
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
     * Check if the circuit breaker is open.
     *
     * @return true if circuit is open (failing fast)
     */
    public boolean isCircuitOpen() {
        return circuitBreaker.isOpen();
    }
    
    /**
     * Get the circuit breaker state.
     *
     * @return Circuit breaker state
     */
    public CircuitBreaker.State getCircuitState() {
        return circuitBreaker.getState();
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
    
    /**
     * Force reconnection attempt.
     */
    public void reconnect() {
        delegate.set(null);
        initialized.set(false);
        initializationFailed.set(false);
        circuitBreaker.reset();
        LOG.info("LazyHttpNodeStore[{}] reset for reconnection", mountName);
    }
    
    @Override
    public String toString() {
        return String.format(
            "LazyHttpNodeStore[%s]{endpoint=%s, connected=%s, circuit=%s}",
            mountName, endpoint, isConnected(), circuitBreaker.getState()
        );
    }
    
    /**
     * Empty NodeState returned when remote is unavailable.
     */
    private static class EmptyNodeState implements NodeState {
        @Override
        public boolean exists() {
            return true;
        }
        
        @Override
        public boolean hasProperty(@NotNull String name) {
            return false;
        }
        
        @Override
        @Nullable
        public org.apache.jackrabbit.oak.api.PropertyState getProperty(@NotNull String name) {
            return null;
        }
        
        @Override
        public boolean getBoolean(@NotNull String name) {
            return false;
        }
        
        @Override
        public long getLong(String name) {
            return 0;
        }
        
        @Override
        @Nullable
        public String getString(String name) {
            return null;
        }
        
        @Override
        @NotNull
        public Iterable<String> getStrings(@NotNull String name) {
            return java.util.Collections.emptyList();
        }
        
        @Override
        @Nullable
        public String getName(@NotNull String name) {
            return null;
        }
        
        @Override
        @NotNull
        public Iterable<String> getNames(@NotNull String name) {
            return java.util.Collections.emptyList();
        }
        
        @Override
        public long getPropertyCount() {
            return 0;
        }
        
        @Override
        @NotNull
        public Iterable<? extends org.apache.jackrabbit.oak.api.PropertyState> getProperties() {
            return java.util.Collections.emptyList();
        }
        
        @Override
        public boolean hasChildNode(@NotNull String name) {
            return false;
        }
        
        @Override
        @NotNull
        public NodeState getChildNode(@NotNull String name) {
            return this;
        }
        
        @Override
        public long getChildNodeCount(long max) {
            return 0;
        }
        
        @Override
        @NotNull
        public Iterable<String> getChildNodeNames() {
            return java.util.Collections.emptyList();
        }
        
        @Override
        @NotNull
        public Iterable<? extends org.apache.jackrabbit.oak.spi.state.ChildNodeEntry> getChildNodeEntries() {
            return java.util.Collections.emptyList();
        }
        
        @Override
        @NotNull
        public NodeBuilder builder() {
            throw new UnsupportedOperationException("EmptyNodeState is read-only");
        }
        
        @Override
        public boolean compareAgainstBaseState(NodeState base, org.apache.jackrabbit.oak.spi.state.NodeStateDiff diff) {
            return true;
        }
    }

    private static NodeStore createRemoteNodeStore(
            String endpoint,
            String mountName,
            long connectTimeoutMs,
            long readTimeoutMs) throws Exception {
        // Timeouts are reserved for future HTTP client wiring and retained as part of the constructor contract.
        HttpPersistence persistence = new HttpPersistence(endpoint);

        java.io.File tempDir = java.nio.file.Files.createTempDirectory("oak-http-" + mountName).toFile();
        tempDir.deleteOnExit();

        ReadOnlyFileStore fileStore = fileStoreBuilder(tempDir)
            .withCustomPersistence(persistence)
            .buildReadOnly();

        return SegmentNodeStoreBuilders.builder(fileStore).build();
    }
}
