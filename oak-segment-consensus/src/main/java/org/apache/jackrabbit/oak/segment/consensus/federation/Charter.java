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

import org.apache.jackrabbit.oak.api.PropertyState;
import org.apache.jackrabbit.oak.api.Type;
import org.apache.jackrabbit.oak.segment.consensus.validation.MutationRejectedException;
import org.apache.jackrabbit.oak.segment.http.server.util.JsonParser;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The enterprise cluster's founding record: which enterprise it serves and which registry on which chain
 * governs it. Committed once through consensus under {@value #NODE_NAME} and never changed; a different
 * charter is a different cluster lineage.
 */
public final class Charter {

    public static final String NODE_NAME = ":charter";
    public static final String STATIC_REGISTRY = "static";
    static final long VERSION = 1L;
    static final String JSON = "json";

    private final String enterpriseId;
    private final String mode;
    private final long chainId;
    private final String registry;
    private final String canonicalJson;

    private Charter(String enterpriseId, String mode, long chainId, String registry) {
        this.enterpriseId = enterpriseId;
        this.mode = mode;
        this.chainId = chainId;
        this.registry = registry;
        this.canonicalJson = FederationJson.canonical(toMap());
    }

    @NotNull
    public static Charter of(String enterpriseId, String mode, long chainId, String registry) {
        Map<String, Object> json = new TreeMap<>();
        json.put("charterVersion", VERSION);
        json.put("enterpriseId", enterpriseId);
        json.put("mode", mode);
        json.put("chainId", chainId);
        json.put("registry", registry);
        return fromMap(json);
    }

    /** @throws MutationRejectedException if the record is malformed or inconsistent with its chain */
    @NotNull
    public static Charter fromMap(Map<String, Object> json) {
        if (FederationJson.nonNegativeLong(json, "charterVersion") != VERSION) {
            throw new MutationRejectedException("Unsupported charterVersion: " + json.get("charterVersion"));
        }
        String enterpriseId = FederationJson.string(json, "enterpriseId").toLowerCase(Locale.ROOT);
        if (!FederationJson.BYTES32.matcher(enterpriseId).matches()) {
            throw new MutationRejectedException("enterpriseId must be 0x-prefixed 32-byte hex: " + enterpriseId);
        }
        String mode = FederationJson.string(json, "mode");
        long chainId = FederationJson.nonNegativeLong(json, "chainId");
        String registry = FederationJson.string(json, "registry");
        switch (mode) {
            case "mock":
            case "local":
                if (!STATIC_REGISTRY.equals(registry)) {
                    registry = FederationJson.address(registry, "registry");
                }
                break;
            case "sepolia":
                requireChain(mode, chainId, 11155111L);
                registry = FederationJson.address(registry, "registry");
                break;
            case "mainnet":
                requireChain(mode, chainId, 1L);
                registry = FederationJson.address(registry, "registry");
                break;
            default:
                throw new MutationRejectedException("Unknown charter mode: " + mode);
        }
        return new Charter(enterpriseId, mode, chainId, registry);
    }

    private static void requireChain(String mode, long chainId, long expected) {
        if (chainId != expected) {
            throw new MutationRejectedException("Charter mode " + mode + " requires chainId " + expected
                + ", got " + chainId);
        }
    }

    @Nullable
    public static Charter read(@Nullable NodeState root) {
        if (root == null) {
            return null;
        }
        PropertyState json = root.getChildNode(NODE_NAME).getProperty(JSON);
        return json == null ? null : fromMap(JsonParser.parseObject(json.getValue(Type.STRING)));
    }

    public void writeTo(@NotNull NodeBuilder rootBuilder) {
        NodeBuilder node = rootBuilder.child(NODE_NAME);
        node.setProperty(JSON, canonicalJson);
        node.setProperty("digest", digest());
        node.setProperty("enterpriseId", enterpriseId);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> json = new TreeMap<>();
        json.put("charterVersion", VERSION);
        json.put("enterpriseId", enterpriseId);
        json.put("mode", mode);
        json.put("chainId", chainId);
        json.put("registry", registry);
        return json;
    }

    public String canonicalJson() {
        return canonicalJson;
    }

    public String digest() {
        return FederationJson.keccak(canonicalJson);
    }

    /** The cluster's identity: this charter bound to the genesis it was founded on. */
    public String clusterId(@NotNull String genesisIntegrityDigest) {
        return FederationJson.keccak(canonicalJson + "\n" + genesisIntegrityDigest);
    }

    public String getEnterpriseId() {
        return enterpriseId;
    }

    public String getMode() {
        return mode;
    }

    public long getChainId() {
        return chainId;
    }

    public String getRegistry() {
        return registry;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Charter && canonicalJson.equals(((Charter) o).canonicalJson);
    }

    @Override
    public int hashCode() {
        return canonicalJson.hashCode();
    }

    @Override
    public String toString() {
        return canonicalJson;
    }
}
