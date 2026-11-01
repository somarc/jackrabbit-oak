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

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Point-in-time state of one remote endpoint that backs read-only mounts.
 * {@code available} is false until the endpoint has connected, and whenever
 * its most recent connect or refresh attempt failed. {@code federation} is set for mounts derived from the
 * committed federation directory and null for static routes.
 */
public record RemoteMountStatus(@NotNull String endpoint,
                                @NotNull String prefixes,
                                boolean connected,
                                boolean available,
                                @NotNull String circuit,
                                @Nullable String revision,
                                @Nullable Long lastRefreshAt,
                                @Nullable String lastError,
                                @Nullable Federation federation) {

    public RemoteMountStatus(@NotNull String endpoint, @NotNull String prefixes, boolean connected, boolean available,
                             @NotNull String circuit, @Nullable String revision, @Nullable Long lastRefreshAt,
                             @Nullable String lastError) {
        this(endpoint, prefixes, connected, available, circuit, revision, lastRefreshAt, lastError, null);
    }

    /** The remote enterprise behind a directory-derived mount and the directory epoch the mount set was built from. */
    public record Federation(@NotNull String enterpriseId,
                             @NotNull List<String> namespaces,
                             @NotNull List<EndpointState> endpoints,
                             @Nullable String activeEndpoint,
                             boolean verified,
                             long directoryEpoch) {
    }

    /** One of the enterprise's endpoints, in failover order. */
    public record EndpointState(@NotNull String url, boolean verified, @NotNull String circuit, boolean connected,
                                boolean available, @Nullable String lastError) {

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("url", url);
            map.put("verified", verified);
            map.put("circuit", circuit);
            map.put("connected", connected);
            map.put("available", available);
            map.put("lastError", lastError);
            return map;
        }
    }

    @NotNull
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("endpoint", endpoint);
        map.put("prefixes", prefixes);
        map.put("connected", connected);
        map.put("available", available);
        map.put("circuit", circuit);
        map.put("revision", revision);
        map.put("lastRefreshAt", lastRefreshAt);
        map.put("lastError", lastError);
        if (federation != null) {
            map.put("enterpriseId", federation.enterpriseId());
            map.put("namespaces", federation.namespaces());
            List<Map<String, Object>> endpoints = new ArrayList<>();
            federation.endpoints().forEach(state -> endpoints.add(state.toMap()));
            map.put("endpoints", endpoints);
            map.put("activeEndpoint", federation.activeEndpoint());
            map.put("verified", federation.verified());
            map.put("directoryEpoch", federation.directoryEpoch());
        }
        return map;
    }
}
