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
import org.web3j.protocol.Web3j;
import org.web3j.protocol.http.HttpService;

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
    private final String rpcUrl;
    private final String registryCodeHash;

    FederationConfig(@Nullable Charter charter, @Nullable Path directoryFile, long pollMs) {
        this(charter, directoryFile, pollMs, "", "");
    }

    FederationConfig(@Nullable Charter charter, @Nullable Path directoryFile, long pollMs, String rpcUrl,
                     String registryCodeHash) {
        this.charter = charter;
        this.directoryFile = directoryFile;
        this.pollMs = pollMs;
        this.rpcUrl = rpcUrl;
        this.registryCodeHash = registryCodeHash;
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
        String rpcUrl = read("oak.federation.rpcUrl", "OAK_FEDERATION_RPC_URL",
            read("oak.blockchain.rpcUrl", "OAK_BLOCKCHAIN_RPC_URL", ""));
        String codeHash = read("oak.federation.registry.codehash", "OAK_FEDERATION_REGISTRY_CODEHASH", "");
        FederationConfig config = new FederationConfig(charter, file.isEmpty() ? null : Paths.get(file), pollMs,
            rpcUrl, codeHash);
        config.validate();
        return config;
    }

    /** A registry charter needs an RPC endpoint, and on mainnet a pinned registry code hash. */
    void validate() {
        if (charter == null || Charter.STATIC_REGISTRY.equals(charter.getRegistry())) {
            return;
        }
        if (rpcUrl.isEmpty()) {
            throw new IllegalArgumentException("oak.federation.rpcUrl (or oak.blockchain.rpcUrl) is required to read"
                + " registry " + charter.getRegistry());
        }
        if (directoryFile != null) {
            throw new IllegalArgumentException("oak.federation.directoryFile applies only to a static registry");
        }
        if ("mainnet".equals(charter.getMode()) && registryCodeHash.isEmpty()) {
            throw new IllegalArgumentException("oak.federation.registry.codehash is required on mainnet");
        }
    }

    /** Where the leader reads the directory: the charter's registry, a static file, or nowhere. */
    @Nullable
    public DirectorySource directorySource() {
        if (charter == null) {
            return null;
        }
        if (!Charter.STATIC_REGISTRY.equals(charter.getRegistry())) {
            Web3j web3j = Web3j.build(new HttpService(rpcUrl));
            return new RegistryDirectorySource(RegistryDirectorySource.web3j(web3j, "local".equals(charter.getMode())),
                charter, registryCodeHash);
        }
        return directoryFile != null ? new StaticFileDirectorySource(directoryFile, charter) : null;
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
