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
package org.apache.jackrabbit.oak.segment.consensus.sharding;

import org.apache.jackrabbit.oak.segment.consensus.config.RuntimeConfigValueResolver;
import org.apache.jackrabbit.oak.segment.consensus.util.WalletPathUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Static runtime sharding configuration for standalone validators.
 *
 * <p>The current v1 proof models ownership in terms of wallet L1 prefixes,
 * which already align with the live {@code /oak-chain/{L1}/{L2}/{L3}} path
 * structure.</p>
 */
public final class ShardingRuntimeConfig {

    private static final Logger LOG = LoggerFactory.getLogger(ShardingRuntimeConfig.class);

    private static final String PROP_ENABLED = "oak.sharding.enabled";
    private static final String PROP_LOCAL_PREFIXES = "oak.sharding.local.prefixes";
    private static final String PROP_REMOTE_ROUTES = "oak.sharding.remote.routes";
    private static final String ENV_LOCAL_PREFIXES = "OAK_SHARDING_LOCAL_PREFIXES";
    private static final String ENV_REMOTE_ROUTES = "OAK_SHARDING_REMOTE_ROUTES";

    private final boolean enabled;
    private final List<PrefixRange> localRanges;
    private final List<RemoteRoute> remoteRoutes;

    private ShardingRuntimeConfig(boolean enabled,
                                  @NotNull List<PrefixRange> localRanges,
                                  @NotNull List<RemoteRoute> remoteRoutes) {
        this.enabled = enabled;
        this.localRanges = Collections.unmodifiableList(new ArrayList<>(localRanges));
        this.remoteRoutes = Collections.unmodifiableList(new ArrayList<>(remoteRoutes));
    }

    @NotNull
    public static ShardingRuntimeConfig disabled() {
        return new ShardingRuntimeConfig(false, Collections.<PrefixRange>emptyList(), Collections.<RemoteRoute>emptyList());
    }

    @NotNull
    public static ShardingRuntimeConfig load() {
        boolean enabled = RuntimeConfigValueResolver.readBoolean(PROP_ENABLED, false);
        String localPrefixes = RuntimeConfigValueResolver.readString(PROP_LOCAL_PREFIXES, ENV_LOCAL_PREFIXES, "");
        String remoteRoutes = RuntimeConfigValueResolver.readString(PROP_REMOTE_ROUTES, ENV_REMOTE_ROUTES, "");
        return fromSpecs(enabled, localPrefixes, remoteRoutes);
    }

    @NotNull
    public static ShardingRuntimeConfig fromSpecs(boolean enabled,
                                                  @Nullable String localPrefixesSpec,
                                                  @Nullable String remoteRoutesSpec) {
        return new ShardingRuntimeConfig(
            enabled,
            parseLocalRanges(localPrefixesSpec),
            parseRemoteRoutes(remoteRoutesSpec)
        );
    }

    public boolean isEnabled() {
        return enabled;
    }

    @NotNull
    public ResolvedWallet resolveWallet(@NotNull String walletAddress) {
        String l1Prefix = resolveL1Prefix(walletAddress);
        if (!enabled) {
            return new ResolvedWallet(Ownership.DISABLED, l1Prefix, null);
        }

        int prefixValue = Integer.parseInt(l1Prefix, 16);
        for (PrefixRange range : localRanges) {
            if (range.contains(prefixValue)) {
                return new ResolvedWallet(Ownership.LOCAL, l1Prefix, null);
            }
        }

        for (RemoteRoute route : remoteRoutes) {
            if (route.range.contains(prefixValue)) {
                return new ResolvedWallet(Ownership.REMOTE, l1Prefix, route.endpoint);
            }
        }

        return new ResolvedWallet(Ownership.UNCLAIMED, l1Prefix, null);
    }

    @NotNull
    public String describeLocalRanges() {
        return describeRanges(localRanges);
    }

    @NotNull
    public String describeRemoteRoutes() {
        if (remoteRoutes.isEmpty()) {
            return "none";
        }
        List<String> parts = new ArrayList<>();
        for (RemoteRoute route : remoteRoutes) {
            parts.add(route.range.asSpec() + " -> " + route.endpoint);
        }
        return String.join(", ", parts);
    }

    @NotNull
    public List<ReadOnlyMount> expandRemoteReadOnlyMounts() {
        if (!enabled || remoteRoutes.isEmpty()) {
            return Collections.emptyList();
        }

        List<ReadOnlyMount> mounts = new ArrayList<>();
        for (RemoteRoute route : remoteRoutes) {
            for (int value = route.range.start; value <= route.range.end; value++) {
                String prefix = String.format("%02x", value);
                mounts.add(new ReadOnlyMount(
                    prefix,
                    "oak-chain-remote-" + prefix,
                    "/oak-chain/" + prefix,
                    route.endpoint
                ));
            }
        }
        return Collections.unmodifiableList(mounts);
    }

    @NotNull
    private static List<PrefixRange> parseLocalRanges(@Nullable String spec) {
        List<PrefixRange> ranges = new ArrayList<>();
        if (!RuntimeConfigValueResolver.hasText(spec)) {
            return ranges;
        }
        for (String token : spec.split(",")) {
            PrefixRange range = parseRange(token);
            if (range != null) {
                ranges.add(range);
            }
        }
        return ranges;
    }

    @NotNull
    private static List<RemoteRoute> parseRemoteRoutes(@Nullable String spec) {
        List<RemoteRoute> routes = new ArrayList<>();
        if (!RuntimeConfigValueResolver.hasText(spec)) {
            return routes;
        }

        for (String entry : spec.split(";")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int separator = trimmed.indexOf('=');
            if (separator <= 0 || separator == trimmed.length() - 1) {
                LOG.warn("Ignoring invalid remote shard route '{}'; expected <range>=<endpoint>", trimmed);
                continue;
            }

            PrefixRange range = parseRange(trimmed.substring(0, separator));
            String endpoint = trimmed.substring(separator + 1).trim();
            if (range == null || endpoint.isEmpty()) {
                LOG.warn("Ignoring invalid remote shard route '{}'", trimmed);
                continue;
            }
            routes.add(new RemoteRoute(range, endpoint));
        }

        return routes;
    }

    @Nullable
    private static PrefixRange parseRange(@Nullable String token) {
        if (!RuntimeConfigValueResolver.hasText(token)) {
            return null;
        }

        String normalized = token.trim().toLowerCase(Locale.ROOT);
        try {
            if (normalized.contains("-")) {
                String[] parts = normalized.split("-", 2);
                int start = parsePrefix(parts[0]);
                int end = parsePrefix(parts[1]);
                if (end < start) {
                    LOG.warn("Ignoring invalid prefix range '{}': end before start", token);
                    return null;
                }
                return new PrefixRange(start, end);
            }
            int value = parsePrefix(normalized);
            return new PrefixRange(value, value);
        } catch (IllegalArgumentException e) {
            LOG.warn("Ignoring invalid prefix range '{}': {}", token, e.getMessage());
            return null;
        }
    }

    private static int parsePrefix(@NotNull String token) {
        String trimmed = token.trim().toLowerCase(Locale.ROOT);
        if (trimmed.length() != 2) {
            throw new IllegalArgumentException("expected 2 hex chars");
        }
        return Integer.parseInt(trimmed, 16);
    }

    @NotNull
    private static String resolveL1Prefix(@NotNull String walletAddress) {
        String[] levels = WalletPathUtil.getShardLevels(walletAddress);
        return levels[0].toLowerCase(Locale.ROOT);
    }

    @NotNull
    private static String describeRanges(@NotNull List<PrefixRange> ranges) {
        if (ranges.isEmpty()) {
            return "none";
        }
        List<String> parts = new ArrayList<>();
        for (PrefixRange range : ranges) {
            parts.add(range.asSpec());
        }
        return String.join(", ", parts);
    }

    public enum Ownership {
        DISABLED,
        LOCAL,
        REMOTE,
        UNCLAIMED
    }

    public static final class ResolvedWallet {
        private final Ownership ownership;
        private final String l1Prefix;
        private final String redirectBaseUrl;

        private ResolvedWallet(@NotNull Ownership ownership,
                               @NotNull String l1Prefix,
                               @Nullable String redirectBaseUrl) {
            this.ownership = ownership;
            this.l1Prefix = l1Prefix;
            this.redirectBaseUrl = redirectBaseUrl;
        }

        @NotNull
        public Ownership getOwnership() {
            return ownership;
        }

        @NotNull
        public String getL1Prefix() {
            return l1Prefix;
        }

        @Nullable
        public String getRedirectBaseUrl() {
            return redirectBaseUrl;
        }

        @Nullable
        public String buildRedirectUrl(@NotNull String apiPath) {
            if (redirectBaseUrl == null || redirectBaseUrl.isEmpty()) {
                return null;
            }
            String base = redirectBaseUrl.endsWith("/") ? redirectBaseUrl.substring(0, redirectBaseUrl.length() - 1) : redirectBaseUrl;
            String path = apiPath.startsWith("/") ? apiPath : "/" + apiPath;
            return base + path;
        }
    }

    public static final class ReadOnlyMount {
        private final String l1Prefix;
        private final String mountName;
        private final String mountPath;
        private final String endpoint;

        private ReadOnlyMount(@NotNull String l1Prefix,
                              @NotNull String mountName,
                              @NotNull String mountPath,
                              @NotNull String endpoint) {
            this.l1Prefix = l1Prefix;
            this.mountName = mountName;
            this.mountPath = mountPath;
            this.endpoint = endpoint;
        }

        @NotNull
        public String getL1Prefix() {
            return l1Prefix;
        }

        @NotNull
        public String getMountName() {
            return mountName;
        }

        @NotNull
        public String getMountPath() {
            return mountPath;
        }

        @NotNull
        public String getEndpoint() {
            return endpoint;
        }
    }

    private static final class RemoteRoute {
        private final PrefixRange range;
        private final String endpoint;

        private RemoteRoute(@NotNull PrefixRange range, @NotNull String endpoint) {
            this.range = range;
            this.endpoint = endpoint;
        }
    }

    private static final class PrefixRange {
        private final int start;
        private final int end;

        private PrefixRange(int start, int end) {
            this.start = start;
            this.end = end;
        }

        private boolean contains(int value) {
            return value >= start && value <= end;
        }

        @NotNull
        private String asSpec() {
            if (start == end) {
                return String.format("%02x", start);
            }
            return String.format("%02x-%02x", start, end);
        }
    }
}
