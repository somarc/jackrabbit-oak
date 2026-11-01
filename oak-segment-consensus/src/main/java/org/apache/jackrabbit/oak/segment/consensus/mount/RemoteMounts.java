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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The remote endpoints mounted into the validator read view, and which read
 * paths each one serves. Lets HTTP handlers tell "remote unavailable" apart
 * from "content absent".
 */
public final class RemoteMounts {

    public static final RemoteMounts NONE =
        new RemoteMounts(Mounts.defaultMountInfoProvider(), Map.of(), List.of());

    private final MountInfoProvider mountInfoProvider;
    private final Map<String, StatusSource> endpointsByMountName;
    private final List<StatusSource> endpoints;
    private final long directoryEpoch;

    RemoteMounts(@NotNull MountInfoProvider mountInfoProvider,
                 @NotNull Map<String, ? extends StatusSource> endpointsByMountName,
                 @NotNull List<? extends StatusSource> endpoints) {
        this(mountInfoProvider, endpointsByMountName, endpoints, -1L);
    }

    RemoteMounts(@NotNull MountInfoProvider mountInfoProvider,
                 @NotNull Map<String, ? extends StatusSource> endpointsByMountName,
                 @NotNull List<? extends StatusSource> endpoints,
                 long directoryEpoch) {
        this.mountInfoProvider = mountInfoProvider;
        this.endpointsByMountName = Map.copyOf(endpointsByMountName);
        this.endpoints = List.copyOf(endpoints);
        this.directoryEpoch = directoryEpoch;
    }

    /** True when these mounts were derived from a committed federation directory rather than static routes. */
    public boolean fromDirectory() {
        return directoryEpoch >= 0L;
    }

    /** The epoch of the directory these mounts were derived from, or -1 for static routes. */
    public long directoryEpoch() {
        return directoryEpoch;
    }

    @NotNull
    public List<RemoteMountStatus> statuses() {
        List<RemoteMountStatus> statuses = new ArrayList<>(endpoints.size());
        for (StatusSource endpoint : endpoints) {
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
        StatusSource endpoint = endpointsByMountName.get(mount.getName());
        if (endpoint == null) {
            return null;
        }
        RemoteMountStatus status = endpoint.status();
        return status.available() ? null : status;
    }

    /**
     * Reports remote segment reads that failed while serving a request to the mounts that made them. A missing
     * segment refreshes that mount's head now; a transport failure counts against the endpoint's circuit breaker,
     * so repeated failures trip it and a federated mount fails over.
     *
     * @return true when at least one failure came from one of these mounts
     */
    public boolean reportReadFailures(@NotNull List<MountReadFailures.Failure> failures) {
        boolean ours = false;
        Set<String> seen = new LinkedHashSet<>();
        for (MountReadFailures.Failure failure : failures) {
            if (!seen.add(failure.kind() + " " + failure.endpoint())) {
                continue;
            }
            for (StatusSource endpoint : endpoints) {
                if (endpoint.onReadFailure(failure)) {
                    ours = true;
                    break;
                }
            }
        }
        return ours;
    }

    @FunctionalInterface
    interface StatusSource {
        RemoteMountStatus status();

        /** Handles a failed read from {@code failure.endpoint()}; true when this source serves that endpoint. */
        default boolean onReadFailure(@NotNull MountReadFailures.Failure failure) {
            return false;
        }
    }

    record Endpoint(@NotNull String url, @NotNull String prefixes, @NotNull NodeStore store) implements StatusSource {
        @Override
        public RemoteMountStatus status() {
            if (store instanceof LazyHttpNodeStore lazy) {
                return lazy.status(prefixes);
            }
            return new RemoteMountStatus(url, prefixes, true, true, CircuitBreaker.State.CLOSED.name(),
                null, null, null);
        }

        @Override
        public boolean onReadFailure(@NotNull MountReadFailures.Failure failure) {
            return store instanceof LazyHttpNodeStore lazy && lazy.onReadFailure(failure);
        }
    }
}
