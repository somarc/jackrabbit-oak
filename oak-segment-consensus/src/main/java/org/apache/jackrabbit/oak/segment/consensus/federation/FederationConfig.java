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
package org.apache.jackrabbit.oak.segment.consensus.federation;

import org.apache.jackrabbit.oak.segment.consensus.config.BlockchainConfig;
import org.apache.jackrabbit.oak.segment.consensus.config.RuntimeConfigValueResolver;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * This member's federation settings. The enterprise id, mode, chain and registry make up the charter the
 * cluster's leader proposes; a member whose settings differ from the committed charter refuses writes.
 * Nothing is proposed while {@code oak.federation.enterpriseId} is unset.
 */
public final class FederationConfig {

    public static final long DEFAULT_POLL_MS = 10_000L;

    private final Charter charter;
    private final Path directoryFile;
    private final long pollMs;

    FederationConfig(@Nullable Charter charter, @Nullable Path directoryFile, long pollMs) {
        this.charter = charter;
        this.directoryFile = directoryFile;
        this.pollMs = pollMs;
    }

    /**
     * Reads {@code oak.federation.*} system properties, falling back to {@code OAK_FEDERATION_*} variables.
     *
     * @throws IllegalArgumentException when an enterprise is configured with an invalid charter
     */
    public static FederationConfig fromRuntime() {
        String enterpriseId = read("oak.federation.enterpriseId", "OAK_FEDERATION_ENTERPRISE_ID", "");
        String file = read("oak.federation.directoryFile", "OAK_FEDERATION_DIRECTORY_FILE", "");
        long pollMs = pollMsFromRuntime();
        Charter charter = null;
        if (!enterpriseId.isEmpty()) {
            String mode = read("oak.federation.mode", "OAK_FEDERATION_MODE",
                BlockchainConfig.getInstance().getMode().getKey());
            long chainId = Long.parseLong(read("oak.federation.chainId", "OAK_FEDERATION_CHAIN_ID",
                Long.toString(defaultChainId(mode))));
            String registry = read("oak.federation.registry", "OAK_FEDERATION_REGISTRY",
                "mock".equals(mode) || "local".equals(mode) ? Charter.STATIC_REGISTRY : "");
            charter = Charter.of(enterpriseId, mode, chainId, registry);
        }
        return new FederationConfig(charter, file.isEmpty() ? null : Paths.get(file), pollMs);
    }

    /** {@code oak.federation.pollMs}, at least one second. */
    public static long pollMsFromRuntime() {
        return Math.max(1000L, RuntimeConfigValueResolver.readLong("oak.federation.pollMs", DEFAULT_POLL_MS));
    }

    static long defaultChainId(String mode) {
        switch (mode) {
            case "mainnet":
                return 1L;
            case "sepolia":
                return 11155111L;
            case "local":
                return 31337L;
            default:
                return 0L;
        }
    }

    private static String read(String property, String env, String defaultValue) {
        String value = RuntimeConfigValueResolver.readString(property, env, defaultValue);
        return value == null ? defaultValue : value.trim();
    }

    /** The charter this member expects, or null when no enterprise is configured. */
    @Nullable
    public Charter getCharter() {
        return charter;
    }

    @Nullable
    public Path getDirectoryFile() {
        return directoryFile;
    }

    public long getPollMs() {
        return pollMs;
    }
}
