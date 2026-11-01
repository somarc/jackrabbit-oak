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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The federation directory as committed to the cluster under {@value #NODE_NAME}: every enterprise's
 * entry as read from the registry at one block, the registry's directory epoch and the digest of the
 * snapshot it replaces. Namespaces are disjoint, so each address root has at most one owner.
 */
public final class DirectorySnapshot {

    public static final String NODE_NAME = ":directory";
    static final String JSON = "json";
    static final String DIGEST = "digest";

    private static volatile DirectorySnapshot lastRead;

    private final long chainId;
    private final String registry;
    private final long blockNumber;
    private final String blockHash;
    private final long directoryEpoch;
    private final String prevDigest;
    private final List<DirectoryEntry> entries;
    private final Map<String, DirectoryEntry> byEnterprise = new HashMap<>();
    private final Map<String, DirectoryEntry> byNamespace = new HashMap<>();
    private final String canonicalJson;
    private final String digest;

    private DirectorySnapshot(Map<String, Object> json) {
        chainId = FederationJson.nonNegativeLong(json, "chainId");
        registry = FederationJson.string(json, "registry");
        blockNumber = json.containsKey("blockNumber") ? FederationJson.nonNegativeLong(json, "blockNumber") : 0L;
        blockHash = FederationJson.optionalBytes32(json, "blockHash");
        directoryEpoch = FederationJson.nonNegativeLong(json, "directoryEpoch");
        prevDigest = FederationJson.optionalBytes32(json, "prevDigest");
        Object rawEntries = json.get("entries");
        if (!(rawEntries instanceof List)) {
            throw new MutationRejectedException("Directory snapshot has no entries list");
        }
        List<DirectoryEntry> parsed = new ArrayList<>();
        for (Object raw : (List<?>) rawEntries) {
            if (!(raw instanceof Map)) {
                throw new MutationRejectedException("Directory entry is not an object");
            }
            @SuppressWarnings("unchecked")
            DirectoryEntry entry = DirectoryEntry.fromMap((Map<String, Object>) raw);
            if (byEnterprise.put(entry.getEnterpriseId(), entry) != null) {
                throw new MutationRejectedException("Duplicate enterprise in directory: " + entry.getEnterpriseId());
            }
            for (String namespace : entry.getNamespaces()) {
                DirectoryEntry previous = byNamespace.put(namespace, entry);
                if (previous != null) {
                    throw new MutationRejectedException("Namespace " + namespace + " claimed by both "
                        + previous.getEnterpriseId() + " and " + entry.getEnterpriseId());
                }
            }
            parsed.add(entry);
        }
        parsed.sort(Comparator.comparing(DirectoryEntry::getEnterpriseId));
        entries = List.copyOf(parsed);
        canonicalJson = FederationJson.canonical(toMap());
        digest = FederationJson.keccak(canonicalJson);
    }

    /** @throws MutationRejectedException if the snapshot is malformed or two enterprises claim a namespace */
    @NotNull
    public static DirectorySnapshot fromMap(Map<String, Object> json) {
        return new DirectorySnapshot(json);
    }

    @Nullable
    public static DirectorySnapshot read(@Nullable NodeState root) {
        if (root == null) {
            return null;
        }
        NodeState node = root.getChildNode(NODE_NAME);
        PropertyState json = node.getProperty(JSON);
        if (json == null) {
            return null;
        }
        // writeTo stores the digest beside the JSON in the same commit, so it identifies the snapshot
        PropertyState storedDigest = node.getProperty(DIGEST);
        DirectorySnapshot cached = lastRead;
        if (cached != null && storedDigest != null && cached.digest.equals(storedDigest.getValue(Type.STRING))) {
            return cached;
        }
        DirectorySnapshot snapshot = fromMap(JsonParser.parseObject(json.getValue(Type.STRING)));
        lastRead = snapshot;
        return snapshot;
    }

    public void writeTo(@NotNull NodeBuilder rootBuilder) {
        NodeBuilder node = rootBuilder.child(NODE_NAME);
        node.setProperty(JSON, canonicalJson);
        node.setProperty(DIGEST, digest);
        node.setProperty("directoryEpoch", directoryEpoch);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> json = new TreeMap<>();
        json.put("chainId", chainId);
        json.put("registry", registry);
        json.put("blockNumber", blockNumber);
        json.put("blockHash", blockHash);
        json.put("directoryEpoch", directoryEpoch);
        json.put("prevDigest", prevDigest);
        List<Object> list = new ArrayList<>();
        entries.forEach(entry -> list.add(entry.toMap()));
        json.put("entries", list);
        return json;
    }

    /** This snapshot chained after {@code previous} (empty when it is the first). */
    @NotNull
    public DirectorySnapshot chainedAfter(@Nullable DirectorySnapshot previous) {
        Map<String, Object> json = toMap();
        json.put("prevDigest", previous == null ? "" : previous.digest());
        return fromMap(json);
    }

    /**
     * True when both snapshots describe the same directory, whatever they were chained after and whichever
     * block they were read at.
     */
    public boolean sameContent(@NotNull DirectorySnapshot other) {
        return contentJson().equals(other.contentJson());
    }

    private String contentJson() {
        Map<String, Object> json = toMap();
        json.remove("prevDigest");
        json.remove("blockNumber");
        json.remove("blockHash");
        return FederationJson.canonical(json);
    }

    public String canonicalJson() {
        return canonicalJson;
    }

    public String digest() {
        return digest;
    }

    @Nullable
    public DirectoryEntry entry(String enterpriseId) {
        return byEnterprise.get(enterpriseId);
    }

    @Nullable
    public DirectoryEntry ownerOf(String namespace) {
        return byNamespace.get(namespace);
    }

    public List<DirectoryEntry> getEntries() {
        return entries;
    }

    public long getChainId() {
        return chainId;
    }

    public String getRegistry() {
        return registry;
    }

    public long getBlockNumber() {
        return blockNumber;
    }

    public String getBlockHash() {
        return blockHash;
    }

    public long getDirectoryEpoch() {
        return directoryEpoch;
    }

    public String getPrevDigest() {
        return prevDigest;
    }
}
