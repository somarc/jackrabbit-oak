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

import org.apache.jackrabbit.oak.api.CommitFailedException;
import org.apache.jackrabbit.oak.plugins.memory.MemoryNodeStore;
import org.apache.jackrabbit.oak.segment.consensus.genesis.CanonicalGenesisContent;
import org.apache.jackrabbit.oak.segment.consensus.util.WalletPathUtil;
import org.apache.jackrabbit.oak.spi.commit.CommitInfo;
import org.apache.jackrabbit.oak.spi.commit.EmptyHook;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Charters, directory entries and committed stores shared by the federation tests. */
public final class FederationFixtures {

    public static final String ENTERPRISE_A = "0x" + "a".repeat(64);
    public static final String ENTERPRISE_B = "0x" + "b".repeat(64);
    public static final String NAMESPACE_A = "0x" + "1a".repeat(20);
    public static final String NAMESPACE_A2 = "0x" + "2a".repeat(20);
    public static final String NAMESPACE_B = "0x" + "1b".repeat(20);
    public static final String WRITER_A = "0x" + "3a".repeat(20);
    public static final String STRANGER = "0x" + "4c".repeat(20);
    public static final String GENESIS_DIGEST = "c0ffee";

    private FederationFixtures() {
    }

    public static Charter charterA() {
        return Charter.of(ENTERPRISE_A, "mock", 0L, Charter.STATIC_REGISTRY);
    }

    public static Map<String, Object> entry(String enterpriseId, String status, List<String> endpoints,
                                            List<String> namespaces, List<String> writers) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("enterpriseId", enterpriseId);
        entry.put("status", status);
        entry.put("endpoints", endpoints);
        entry.put("namespaces", namespaces);
        entry.put("writers", writers);
        return entry;
    }

    public static Map<String, Object> entryA(String status) {
        return entry(ENTERPRISE_A, status, List.of("http://a0:8090", "http://a1:8092"),
            List.of(NAMESPACE_A2, NAMESPACE_A), List.of(WRITER_A));
    }

    public static Map<String, Object> entryB() {
        return entry(ENTERPRISE_B, "ACTIVE", List.of("http://b0:8190/"), List.of(NAMESPACE_B), List.of());
    }

    public static Map<String, Object> directoryMap(long epoch, String prevDigest, Map<?, ?>... entries) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("chainId", 0L);
        json.put("registry", Charter.STATIC_REGISTRY);
        json.put("directoryEpoch", epoch);
        json.put("prevDigest", prevDigest);
        json.put("entries", new ArrayList<>(List.of(entries)));
        return json;
    }

    public static DirectorySnapshot directory(long epoch, Map<?, ?>... entries) {
        return DirectorySnapshot.fromMap(directoryMap(epoch, "", entries));
    }

    /** Path of a content node under {@code namespace}, levels derived from the address. */
    public static String pathUnder(String namespace) {
        return WalletPathUtil.getContentPath(namespace) + "/doc-1";
    }

    /** A memory store holding a genesis digest and, when given, the charter and directory. */
    public static MemoryNodeStore store(Charter charter, DirectorySnapshot directory) throws CommitFailedException {
        MemoryNodeStore store = new MemoryNodeStore();
        NodeBuilder root = store.getRoot().builder();
        writeGenesisDigest(root);
        if (charter != null) {
            charter.writeTo(root);
        }
        if (directory != null) {
            directory.writeTo(root);
        }
        store.merge(root, EmptyHook.INSTANCE, CommitInfo.EMPTY);
        return store;
    }

    public static void writeGenesisDigest(NodeBuilder root) {
        NodeBuilder node = root;
        for (String part : (WalletPathUtil.getContentPath(CanonicalGenesisContent.GENESIS_ADDRESS) + "/genesis")
                .substring(1).split("/")) {
            node = node.child(part);
        }
        node.setProperty("integrityDigest", GENESIS_DIGEST);
    }
}
