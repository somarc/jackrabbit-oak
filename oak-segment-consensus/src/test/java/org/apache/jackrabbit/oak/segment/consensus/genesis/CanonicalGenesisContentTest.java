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
import org.apache.jackrabbit.oak.plugins.memory.MemoryNodeStore;
import org.apache.jackrabbit.oak.spi.commit.CommitInfo;
import org.apache.jackrabbit.oak.spi.commit.EmptyHook;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
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
        assertEquals("Below /content the shape is intentionally open and may evolve.",
            contract.getProperty("contentShapeStatus").getValue(Type.STRING));
        assertEquals("POST walletAddress, signature, message, contentType, and proposalId to /v1/propose-write.",
            writeFlow.getProperty("step-2").getValue(Type.STRING));
        assertEquals("Cluster-scoped node detail",
            explorer.getProperty("GET_v1_explorer_content_node").getValue(Type.STRING));
        assertEquals("Settlement and Resource Model", economics.getProperty("jcr:title").getValue(Type.STRING));
        assertEquals("Oak Segment Consensus publishes runtime economics as settlement and accountability facts, not commercial packaging.",
            settlementModel.getProperty("positioning").getValue(Type.STRING));
        assertTrue(!economics.getChildNode("pricing-tiers").exists());
    }
}
