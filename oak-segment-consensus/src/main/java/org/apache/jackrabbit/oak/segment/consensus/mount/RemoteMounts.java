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

import org.apache.jackrabbit.oak.spi.mount.Mount;
import org.apache.jackrabbit.oak.spi.mount.MountInfoProvider;
import org.apache.jackrabbit.oak.spi.mount.Mounts;
import org.apache.jackrabbit.oak.spi.state.NodeStore;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The remote endpoints mounted into the validator read view, and which read
 * paths each one serves. Lets HTTP handlers tell "remote unavailable" apart
 * from "content absent".
 */
public final class RemoteMounts {

    public static final RemoteMounts NONE =
        new RemoteMounts(Mounts.defaultMountInfoProvider(), Map.of(), List.of());

    private final MountInfoProvider mountInfoProvider;
    private final Map<String, Endpoint> endpointsByMountName;
    private final List<Endpoint> endpoints;

    RemoteMounts(@NotNull MountInfoProvider mountInfoProvider,
                 @NotNull Map<String, Endpoint> endpointsByMountName,
                 @NotNull List<Endpoint> endpoints) {
        this.mountInfoProvider = mountInfoProvider;
        this.endpointsByMountName = Map.copyOf(endpointsByMountName);
        this.endpoints = List.copyOf(endpoints);
    }

    @NotNull
    public List<RemoteMountStatus> statuses() {
        List<RemoteMountStatus> statuses = new ArrayList<>(endpoints.size());
        for (Endpoint endpoint : endpoints) {
            statuses.add(endpoint.status());
        }
        return statuses;
    }

    /**
     * Returns the status of the remote mount serving {@code path} when that
     * mount is unavailable, or {@code null} when the path is local or its
     * mount is available.
     */
    @Nullable
    public RemoteMountStatus unavailableFor(@NotNull String path) {
        Mount mount = mountInfoProvider.getMountByPath(path);
        if (mount.isDefault()) {
            return null;
        }
        Endpoint endpoint = endpointsByMountName.get(mount.getName());
        if (endpoint == null) {
            return null;
        }
        RemoteMountStatus status = endpoint.status();
        return status.available() ? null : status;
    }

    record Endpoint(@NotNull String url, @NotNull String prefixes, @NotNull NodeStore store) {
        RemoteMountStatus status() {
            if (store instanceof LazyHttpNodeStore lazy) {
                return lazy.status(prefixes);
            }
            return new RemoteMountStatus(url, prefixes, true, true, CircuitBreaker.State.CLOSED.name(),
                null, null, null);
        }
    }
}
