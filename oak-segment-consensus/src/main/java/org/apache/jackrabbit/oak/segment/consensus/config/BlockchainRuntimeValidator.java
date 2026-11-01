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
package org.apache.jackrabbit.oak.segment.consensus.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Static checks of the blockchain runtime configuration, run before storage, HTTP and Aeron start. A failure is a
 * startup failure: a chain-backed validator must not come up with a configuration it cannot verify payments with.
 */
public final class BlockchainRuntimeValidator {

    static final String ZERO_ADDRESS = "0x0000000000000000000000000000000000000000";
    static final String EXAMPLE_SEPOLIA_CONTRACT = "0x742d35Cc6634C0532925a3b844Bc9e7595f0bEb0";

    private static final Pattern ADDRESS = Pattern.compile("^0x[0-9a-fA-F]{40}$");
    private static final Pattern HASH = Pattern.compile("^0x[0-9a-fA-F]{64}$");
    private static final Set<String> RPC_SCHEMES = Set.of("http", "https", "ws", "wss");

    private BlockchainRuntimeValidator() {
    }

    public static void validate(BlockchainConfig config) {
        validate(config.getMode(), config.getRpcUrl(), config.getContractAddress(), config.getContractCodeHash());
    }

    public static void validate(BlockchainConfig.Mode mode, String rpcUrl, String contractAddress, String codeHash) {
        if (mode == null) {
            throw new IllegalStateException("Blockchain mode is not configured; allowed values: "
                + BlockchainConfig.Mode.allowedValues());
        }
        if (!mode.isChainBacked()) {
            return;
        }
        if (mode == BlockchainConfig.Mode.MAINNET) {
            throw new IllegalStateException(
                "MAINNET mode is disabled for oak-chain v1. Use MOCK for local simulation, LOCAL for a local dev chain "
                    + "or SEPOLIA for testnet validation."
            );
        }

        if (rpcUrl == null || rpcUrl.trim().isEmpty()) {
            throw new IllegalStateException(
                "Chain-backed modes require OAK_BLOCKCHAIN_RPC_URL/oak.blockchain.rpcUrl to be configured."
            );
        }
        requireRpcUrlShape(rpcUrl.trim());

        if (contractAddress == null || contractAddress.trim().isEmpty()) {
            throw new IllegalStateException(
                "Chain-backed modes require an explicit Oak payment contract address."
            );
        }
        String address = contractAddress.trim();
        if (ZERO_ADDRESS.equalsIgnoreCase(address) || EXAMPLE_SEPOLIA_CONTRACT.equalsIgnoreCase(address)) {
            throw new IllegalStateException(
                "Chain-backed modes require a deployed Oak payment contract address; example and zero-address defaults are not valid for v1."
            );
        }
        if (!ADDRESS.matcher(address).matches()) {
            throw new IllegalStateException("Oak payment contract address must be a 0x-prefixed 20-byte hex address: "
                + address);
        }

        if (codeHash != null && !codeHash.trim().isEmpty() && !HASH.matcher(codeHash.trim()).matches()) {
            throw new IllegalStateException("OAK_BLOCKCHAIN_CONTRACT_CODEHASH/oak.blockchain.contract.codehash must be "
                + "a 0x-prefixed 32-byte keccak256 hex hash: " + codeHash.trim());
        }
    }

    private static void requireRpcUrlShape(String rpcUrl) {
        try {
            URI uri = new URI(rpcUrl);
            String scheme = uri.getScheme() != null ? uri.getScheme().toLowerCase(Locale.ROOT) : "";
            if (!RPC_SCHEMES.contains(scheme) || uri.getHost() == null || uri.getHost().isEmpty()) {
                throw new IllegalStateException("Blockchain RPC URL must be an absolute http(s) or ws(s) URL with a "
                    + "host (OAK_BLOCKCHAIN_RPC_URL/oak.blockchain.rpcUrl)");
            }
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Blockchain RPC URL is not a valid URI "
                + "(OAK_BLOCKCHAIN_RPC_URL/oak.blockchain.rpcUrl): " + e.getMessage(), e);
        }
    }
}
