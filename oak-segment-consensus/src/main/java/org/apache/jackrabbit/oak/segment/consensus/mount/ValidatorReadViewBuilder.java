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

import org.apache.jackrabbit.oak.composite.CompositeNodeStore;
import org.apache.jackrabbit.oak.segment.consensus.sharding.ShardingRuntimeConfig;
import org.apache.jackrabbit.oak.spi.mount.MountInfoProvider;
import org.apache.jackrabbit.oak.spi.mount.Mounts;
import org.apache.jackrabbit.oak.spi.state.NodeStore;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the validator read view as a composite of the local authoritative
 * store plus remote read-only shard mounts.
 */
public final class ValidatorReadViewBuilder {

    private static final Logger LOG = LoggerFactory.getLogger(ValidatorReadViewBuilder.class);

    @FunctionalInterface
    interface RemoteNodeStoreFactory {
        NodeStore create(String endpoint, String mountName) throws Exception;
    }

    private final RemoteNodeStoreFactory remoteNodeStoreFactory;

    public ValidatorReadViewBuilder() {
        this((endpoint, mountName) -> new LazyHttpNodeStore(endpoint, mountName));
    }

    ValidatorReadViewBuilder(RemoteNodeStoreFactory remoteNodeStoreFactory) {
        this.remoteNodeStoreFactory = remoteNodeStoreFactory;
    }

    @NotNull
    public BuildResult build(@NotNull NodeStore authoritativeNodeStore,
                             ShardingRuntimeConfig shardingRuntimeConfig) {
        if (shardingRuntimeConfig == null || !shardingRuntimeConfig.isEnabled()) {
            return BuildResult.local(authoritativeNodeStore);
        }

        List<ShardingRuntimeConfig.ReadOnlyMount> configuredMounts =
            shardingRuntimeConfig.expandRemoteReadOnlyMounts();
        if (configuredMounts.isEmpty()) {
            return BuildResult.local(authoritativeNodeStore);
        }

        Map<String, EndpointMountGroup> groupsByEndpoint = new LinkedHashMap<>();
        for (ShardingRuntimeConfig.ReadOnlyMount mount : configuredMounts) {
            groupsByEndpoint
                .computeIfAbsent(mount.getEndpoint(), EndpointMountGroup::new)
                .mounts
                .add(mount);
        }

        Map<String, NodeStore> remoteStoresByEndpoint = new LinkedHashMap<>();
        List<Closeable> closeables = new ArrayList<>();
        List<ShardingRuntimeConfig.ReadOnlyMount> mountedEntries = new ArrayList<>();

        for (EndpointMountGroup group : groupsByEndpoint.values()) {
            ShardingRuntimeConfig.ReadOnlyMount firstMount = group.mounts.get(0);
            try {
                NodeStore remoteStore = remoteNodeStoreFactory.create(group.endpoint, firstMount.getMountName());
                remoteStoresByEndpoint.put(group.endpoint, remoteStore);
                if (remoteStore instanceof Closeable) {
                    closeables.add((Closeable) remoteStore);
                }
                mountedEntries.addAll(group.mounts);
            } catch (Exception e) {
                LOG.warn("Skipping remote shard endpoint {}: {}", group.endpoint, e.getMessage());
            }
        }

        if (mountedEntries.isEmpty()) {
            return BuildResult.local(authoritativeNodeStore);
        }

        Mounts.Builder mountBuilder = Mounts.newBuilder();
        for (ShardingRuntimeConfig.ReadOnlyMount mount : mountedEntries) {
            mountBuilder.readOnlyMount(mount.getMountName(), mount.getMountPath());
        }

        MountInfoProvider mountInfoProvider = mountBuilder.build();
        CompositeNodeStore.Builder compositeBuilder =
            new CompositeNodeStore.Builder(mountInfoProvider, authoritativeNodeStore);

        for (ShardingRuntimeConfig.ReadOnlyMount mount : mountedEntries) {
            compositeBuilder.addMount(mount.getMountName(), remoteStoresByEndpoint.get(mount.getEndpoint()));
        }

        NodeStore readViewNodeStore = compositeBuilder.build();
        LOG.info("✅ Validator read-view composite enabled with {} remote shard mounts", mountedEntries.size());
        return new BuildResult(readViewNodeStore, closeables, mountedEntries.size());
    }

    public static final class BuildResult implements Closeable {
        private final NodeStore readViewNodeStore;
        private final List<Closeable> closeables;
        private final int remoteMountCount;

        private BuildResult(@NotNull NodeStore readViewNodeStore,
                            @NotNull List<Closeable> closeables,
                            int remoteMountCount) {
            this.readViewNodeStore = readViewNodeStore;
            this.closeables = new ArrayList<>(closeables);
            this.remoteMountCount = remoteMountCount;
        }

        private static BuildResult local(NodeStore authoritativeNodeStore) {
            return new BuildResult(authoritativeNodeStore, List.of(), 0);
        }

        @NotNull
        public NodeStore getReadViewNodeStore() {
            return readViewNodeStore;
        }

        public int getRemoteMountCount() {
            return remoteMountCount;
        }

        @Override
        public void close() throws IOException {
            IOException first = null;
            for (Closeable closeable : closeables) {
                try {
                    closeable.close();
                } catch (IOException e) {
                    if (first == null) {
                        first = e;
                    }
                }
            }
            if (first != null) {
                throw first;
            }
        }
    }

    private static final class EndpointMountGroup {
        private final String endpoint;
        private final List<ShardingRuntimeConfig.ReadOnlyMount> mounts = new ArrayList<>();

        private EndpointMountGroup(String endpoint) {
            this.endpoint = endpoint;
        }
    }
}
