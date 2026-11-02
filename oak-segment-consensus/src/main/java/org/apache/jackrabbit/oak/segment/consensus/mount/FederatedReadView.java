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

import org.apache.jackrabbit.oak.composite.CompositeNodeStore;
import org.apache.jackrabbit.oak.segment.consensus.federation.Charter;
import org.apache.jackrabbit.oak.segment.consensus.federation.DirectoryEntry;
import org.apache.jackrabbit.oak.segment.consensus.federation.DirectorySnapshot;
import org.apache.jackrabbit.oak.segment.consensus.sharding.ShardingRuntimeConfig;
import org.apache.jackrabbit.oak.segment.consensus.util.WalletPathUtil;
import org.apache.jackrabbit.oak.spi.mount.MountInfoProvider;
import org.apache.jackrabbit.oak.spi.mount.Mounts;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.apache.jackrabbit.oak.spi.state.NodeStore;
import org.apache.jackrabbit.oak.spi.state.ProxyNodeStore;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The validator read view: the local authoritative store composed with remote read-only mounts.
 * <p>
 * Without a committed federation directory the mounts are the static {@code oak.sharding.*} routes, exactly as
 * {@link ValidatorReadViewBuilder} builds them. Once a directory is committed, each namespace of every ACTIVE
 * enterprise other than the committed charter's is mounted at its address root and served by that enterprise's
 * endpoints; local and unclaimed paths stay on the local store. Each remote enterprise is one Oak mount listing
 * all of its namespace roots, so path resolution scales with the number of remote enterprises, not namespaces.
 * <p>
 * Composite mounts are fixed at construction, so a change of the committed directory or charter rebuilds the
 * composite and swaps it in behind {@link #nodeStore()}, which callers hold for good. Remote stores whose
 * enterprise, cluster and endpoints are unchanged carry over; the rest are closed one watcher tick after the
 * swap, so reads already holding the old view can finish. Rebuilds run on the watcher, never in replicated apply.
 */
public final class FederatedReadView implements Closeable {

    private static final Logger LOG = LoggerFactory.getLogger(FederatedReadView.class);
    private static final String STATIC_ROUTES = "static";

    private final NodeStore authoritative;
    private final ShardingRuntimeConfig staticRoutes;
    private final ValidatorReadViewBuilder staticBuilder;
    private final Function<DirectoryEntry, FederatedMountStore> mountFactory;
    private final NodeStore nodeStore = new ProxyNodeStore() {
        @Override
        protected NodeStore getNodeStore() {
            return current.store;
        }
    };
    private volatile View current;
    private volatile Consumer<RemoteMounts> listener = mounts -> { };
    private List<Closeable> retired = List.of();
    private ScheduledExecutorService scheduler;
    private boolean closed;

    public FederatedReadView(@NotNull NodeStore authoritative, @Nullable ShardingRuntimeConfig staticRoutes) {
        this(authoritative, staticRoutes, new ValidatorReadViewBuilder(), httpMounts(new FederationIdentityProbe()));
    }

    FederatedReadView(@NotNull NodeStore authoritative, @Nullable ShardingRuntimeConfig staticRoutes,
                      @NotNull ValidatorReadViewBuilder staticBuilder,
                      @NotNull Function<DirectoryEntry, FederatedMountStore> mountFactory) {
        this.authoritative = authoritative;
        this.staticRoutes = staticRoutes;
        this.staticBuilder = staticBuilder;
        this.mountFactory = mountFactory;
        NodeState root = authoritative.getRoot();
        this.current = build(DirectorySnapshot.read(root), Charter.read(root), View.EMPTY);
    }

    static Function<DirectoryEntry, FederatedMountStore> httpMounts(FederatedMountStore.IdentityProbe probe) {
        return entry -> new FederatedMountStore(entry, mountName(entry), probe, FederatedMountStore.HTTP_ENDPOINTS);
    }

    /** The read view; always delegates to the current composite. */
    @NotNull
    public NodeStore nodeStore() {
        return nodeStore;
    }

    @NotNull
    public RemoteMounts remoteMounts() {
        return current.mounts;
    }

    /** Polls the committed directory every {@code pollMs}; {@code onSwap} receives the mounts of each new view. */
    public synchronized void start(long pollMs, @NotNull Consumer<RemoteMounts> onSwap) {
        listen(onSwap);
        if (scheduler != null || closed) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "federated-read-view");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(this::refreshQuietly, 0L, pollMs, TimeUnit.MILLISECONDS);
    }

    synchronized void listen(@NotNull Consumer<RemoteMounts> onSwap) {
        listener = onSwap;
        onSwap.accept(current.mounts);
    }

    private void refreshQuietly() {
        try {
            refresh();
        } catch (RuntimeException e) {
            LOG.warn("Read view refresh failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Closes what the previous swap retired, rebuilds and swaps the view if the committed directory or charter
     * changed, then lets each remote enterprise verify and connect its endpoints.
     *
     * @return true when the view was swapped
     */
    synchronized boolean refresh() {
        if (closed) {
            return false;
        }
        closeAll(retired);
        retired = List.of();
        NodeState root = authoritative.getRoot();
        DirectorySnapshot directory = DirectorySnapshot.read(root);
        Charter charter = Charter.read(root);
        View previous = current;
        boolean swapped = !keyOf(directory, charter).equals(previous.key);
        if (swapped) {
            View next = build(directory, charter, previous);
            current = next;
            listener.accept(next.mounts);
            List<Closeable> unused = new ArrayList<>(previous.resources);
            unused.removeIf(resource -> next.resources.stream().anyMatch(kept -> kept == resource));
            retired = unused;
            LOG.info("Read view rebuilt for {} with {} remote enterprise mount(s)", next.key, next.remotes.size());
        }
        current.remotes.values().forEach(FederatedMountStore::maintain);
        return swapped;
    }

    private static String keyOf(@Nullable DirectorySnapshot directory, @Nullable Charter charter) {
        return directory == null ? STATIC_ROUTES
            : "directory " + directory.getDirectoryEpoch() + " " + directory.digest()
                + " for " + (charter != null ? charter.getEnterpriseId() : "no charter");
    }

    /** The entries whose namespaces are mounted remotely: ACTIVE and not the local enterprise. */
    static List<DirectoryEntry> remoteEntries(@Nullable Charter charter, @NotNull DirectorySnapshot directory) {
        List<DirectoryEntry> remote = new ArrayList<>();
        if (charter == null) {
            return remote;
        }
        for (DirectoryEntry entry : directory.getEntries()) {
            if (entry.getStatus() == DirectoryEntry.Status.ACTIVE && !entry.getNamespaces().isEmpty()
                && !entry.getEnterpriseId().equals(charter.getEnterpriseId())) {
                remote.add(entry);
            }
        }
        return remote;
    }

    static String mountName(DirectoryEntry entry) {
        return "enterprise-" + entry.getEnterpriseId();
    }

    private View build(@Nullable DirectorySnapshot directory, @Nullable Charter charter, View previous) {
        String key = keyOf(directory, charter);
        if (directory == null) {
            ValidatorReadViewBuilder.BuildResult result = staticBuilder.build(authoritative, staticRoutes);
            return new View(key, result.getReadViewNodeStore(), result.getRemoteMounts(), Map.of(), List.of(result));
        }
        long epoch = directory.getDirectoryEpoch();
        List<DirectoryEntry> entries = remoteEntries(charter, directory);
        if (entries.isEmpty()) {
            return new View(key, authoritative,
                new RemoteMounts(Mounts.defaultMountInfoProvider(), Map.of(), List.of(), epoch), Map.of(), List.of());
        }
        Mounts.Builder mountBuilder = Mounts.newBuilder();
        Map<String, FederatedMountStore> stores = new LinkedHashMap<>();
        Map<String, FederatedMountStore> storesByName = new LinkedHashMap<>();
        Map<String, RemoteMounts.StatusSource> sourcesByName = new LinkedHashMap<>();
        for (DirectoryEntry entry : entries) {
            List<String> roots = new ArrayList<>();
            entry.getNamespaces().forEach(namespace -> roots.add(WalletPathUtil.getShardRoot(namespace)));
            // no fragment support: otherwise the composite lists remote children at every level to look for them
            mountBuilder.mount(mountName(entry), true, List.of(), roots);
            String reuseKey = FederatedMountStore.reuseKey(entry);
            FederatedMountStore store = previous.remotes.get(reuseKey);
            FederatedMountStore mounted = store != null ? store : mountFactory.apply(entry);
            stores.put(reuseKey, mounted);
            storesByName.put(mountName(entry), mounted);
            List<String> namespaces = entry.getNamespaces();
            sourcesByName.put(mountName(entry), new RemoteMounts.StatusSource() {
                @Override
                public RemoteMountStatus status() {
                    return mounted.status(namespaces, epoch);
                }

                @Override
                public boolean onReadFailure(@NotNull MountReadFailures.Failure failure) {
                    return mounted.onReadFailure(failure);
                }
            });
        }
        MountInfoProvider mountInfoProvider = mountBuilder.build();
        CompositeNodeStore.Builder composite = new CompositeNodeStore.Builder(mountInfoProvider,
            ValidatorReadViewBuilder.withoutObservation(authoritative));
        storesByName.forEach(composite::addMount);
        return new View(key, composite.build(),
            new RemoteMounts(mountInfoProvider, sourcesByName, new ArrayList<>(sourcesByName.values()), epoch),
            stores, new ArrayList<>(stores.values()));
    }

    @Override
    public void close() {
        ScheduledExecutorService running;
        synchronized (this) {
            closed = true;
            running = scheduler;
            scheduler = null;
        }
        if (running != null) {
            running.shutdownNow();
        }
        synchronized (this) {
            closeAll(retired);
            retired = List.of();
            closeAll(current.resources);
        }
    }

    private static void closeAll(List<? extends Closeable> resources) {
        for (Closeable resource : resources) {
            try {
                resource.close();
            } catch (IOException | RuntimeException e) {
                LOG.warn("Failed to close read view resource {}: {}", resource, e.getMessage());
            }
        }
    }

    private static final class View {
        static final View EMPTY = new View("", null, RemoteMounts.NONE, Map.of(), List.of());

        final String key;
        final NodeStore store;
        final RemoteMounts mounts;
        final Map<String, FederatedMountStore> remotes;
        final List<Closeable> resources;

        View(String key, NodeStore store, RemoteMounts mounts, Map<String, FederatedMountStore> remotes,
             List<? extends Closeable> resources) {
            this.key = key;
            this.store = store;
            this.mounts = mounts;
            this.remotes = remotes;
            this.resources = List.copyOf(resources);
        }
    }
}
