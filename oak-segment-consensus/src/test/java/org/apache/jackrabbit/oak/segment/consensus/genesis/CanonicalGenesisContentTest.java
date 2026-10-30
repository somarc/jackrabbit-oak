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
package org.apache.jackrabbit.oak.segment.consensus.genesis;

import org.apache.jackrabbit.oak.api.Type;
import org.apache.jackrabbit.oak.api.PropertyState;
import org.apache.jackrabbit.oak.plugins.memory.MemoryNodeStore;
import org.apache.jackrabbit.oak.spi.commit.CommitInfo;
import org.apache.jackrabbit.oak.spi.commit.EmptyHook;
import org.apache.jackrabbit.oak.spi.state.ChildNodeEntry;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CanonicalGenesisContentTest {

    @Test
    public void populateWritesCanonicalWalletScopedGenesisContract() throws Exception {
        MemoryNodeStore nodeStore = new MemoryNodeStore();
        CanonicalGenesisContent content = new CanonicalGenesisContent(nodeStore, null);

        NodeBuilder rootBuilder = nodeStore.getRoot().builder();
        content.populate(rootBuilder, 1700000000000L, "https://validator.example:8090");
        nodeStore.merge(rootBuilder, EmptyHook.INSTANCE, CommitInfo.EMPTY);

        NodeState genesis = CanonicalGenesisContent.getGenesisNode(nodeStore.getRoot());
        NodeState contract = genesis.getChildNode("content-contract");
        NodeState writeFlow = genesis.getChildNode("getting-started").getChildNode("5-write-flow");
        NodeState explorer = genesis.getChildNode("api").getChildNode("explorer");
        NodeState economics = genesis.getChildNode("economics");
        NodeState settlementModel = economics.getChildNode("settlement-model");

        assertTrue(genesis.exists());
        assertEquals("DO IT LIVE!", genesis.getProperty("message").getValue(Type.STRING));
        assertEquals(CanonicalGenesisContent.getGenesisPath(), genesis.getProperty("canonicalGenesisPath").getValue(Type.STRING));
        assertEquals("The wallet namespace boundary is the only enforced content contract today.",
            genesis.getProperty("walletNamespaceContract").getValue(Type.STRING));
        assertEquals("Below /content, records follow the shapes published at genesis/shapes. The validator enforces only the wallet namespace today.",
            contract.getProperty("contentShapeStatus").getValue(Type.STRING));
        assertEquals(CanonicalGenesisContent.getGenesisPath() + "/shapes", contract.getProperty("shapes").getValue(Type.STRING));
        assertEquals("POST walletAddress, signature, message, contentType, and proposalId to /v1/propose-write.",
            writeFlow.getProperty("step-2").getValue(Type.STRING));
        assertEquals("Cluster-scoped node detail",
            explorer.getProperty("GET_v1_explorer_content_node").getValue(Type.STRING));
        assertEquals("Settlement and Resource Model", economics.getProperty("jcr:title").getValue(Type.STRING));
        assertEquals("Oak Segment Consensus publishes runtime economics as settlement and accountability facts, not commercial packaging.",
            settlementModel.getProperty("positioning").getValue(Type.STRING));
        assertTrue(!economics.getChildNode("pricing-tiers").exists());
    }

    /** The shapes are documentation as data; every example must be a well-formed record of its own shape. */
    @Test
    public void everyShapeExampleIsAWellFormedRecordOfItsShape() throws Exception {
        MemoryNodeStore nodeStore = new MemoryNodeStore();
        NodeBuilder rootBuilder = nodeStore.getRoot().builder();
        new CanonicalGenesisContent(nodeStore, null).populate(rootBuilder, 1700000000000L, "https://validator.example:8090");
        nodeStore.merge(rootBuilder, EmptyHook.INSTANCE, CommitInfo.EMPTY);

        NodeState shapes = CanonicalGenesisContent.getGenesisNode(nodeStore.getRoot()).getChildNode("shapes");
        NodeState record = shapes.getChildNode("record");
        List<String> shapeNames = List.of("claim", "attestation", "asset", "receipt");
        assertEquals(shapeNames, strings(record.getChildNode("fields").getChildNode("contentType").getProperty("allowedValues")));

        for (String name : shapeNames) {
            NodeState shape = shapes.getChildNode(name);
            NodeState example = shape.getChildNode("example");
            assertTrue(name + " shape and example exist", shape.exists() && example.exists());
            assertEquals(name, example.getProperty("contentType").getValue(Type.STRING));
            assertTrue(name + " examplePath sits in its collection", shape.getProperty("examplePath").getValue(Type.STRING)
                .contains("/content/" + shape.getProperty("collection").getValue(Type.STRING) + "/"));

            Map<String, NodeState> declared = new HashMap<>();
            for (NodeState fields : List.of(record.getChildNode("fields"), shape.getChildNode("fields"))) {
                for (ChildNodeEntry entry : fields.getChildNodeEntries()) {
                    assertTrue(name + " redeclares " + entry.getName(), declared.put(entry.getName(), entry.getNodeState()) == null);
                }
            }
            for (String reserved : record.getProperty("reservedNames").getValue(Type.STRINGS)) {
                assertFalse(name + " declares reserved " + reserved, declared.containsKey(reserved));
            }

            for (Map.Entry<String, NodeState> entry : declared.entrySet()) {
                NodeState field = entry.getValue();
                PropertyState value = example.getProperty(entry.getKey());
                if (value == null) {
                    assertFalse(name + " example lacks required " + entry.getKey(), field.getProperty("required").getValue(Type.BOOLEAN));
                    continue;
                }
                assertEquals(name + "." + entry.getKey(), typeOf(field.getProperty("type").getValue(Type.STRING)), value.getType());
                if (field.hasProperty("allowedValues")) {
                    assertTrue(name + "." + entry.getKey() + " uses an allowed value",
                        strings(field.getProperty("allowedValues")).contains(value.getValue(Type.STRING)));
                }
                if (entry.getKey().toLowerCase().endsWith("sha256") || entry.getKey().endsWith("ProposalId")) {
                    String hex = value.getValue(Type.STRING).replaceFirst("^0x", "");
                    assertTrue(name + "." + entry.getKey() + " is 64 lowercase hex", hex.matches("[0-9a-f]{64}"));
                }
            }
            for (PropertyState property : example.getProperties()) {
                assertTrue(name + " example has undeclared " + property.getName(),
                    "jcr:primaryType".equals(property.getName()) || declared.containsKey(property.getName()));
            }
        }
    }

    private static List<String> strings(PropertyState property) {
        List<String> values = new ArrayList<>();
        property.getValue(Type.STRINGS).forEach(values::add);
        return values;
    }

    private static Type<?> typeOf(String declared) {
        switch (declared) {
            case "String": return Type.STRING;
            case "String[]": return Type.STRINGS;
            case "Long": return Type.LONG;
            case "Boolean": return Type.BOOLEAN;
            case "Date": return Type.DATE;
            default: throw new AssertionError("Unknown field type " + declared);
        }
    }
}
