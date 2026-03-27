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
package org.apache.jackrabbit.oak.segment.consensus.server;

import org.apache.jackrabbit.oak.api.Blob;
import org.apache.jackrabbit.oak.api.Type;
import org.apache.jackrabbit.oak.plugins.memory.MemoryNodeStore;
import org.apache.jackrabbit.oak.segment.consensus.genesis.CanonicalGenesisContent;
import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.apache.jackrabbit.oak.spi.blob.BlobStore;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class GenesisInitializerTest {
    private static final String GENESIS_BLOB_ID = "QmYwAPJzv5CZsnAzt8auVZRnGi2C4gYQqbiZ9erjRzCQXD#1024";

    @Test
    public void testInitializeGenesisCreatesShardedContentWithDefaultNetworkInfo() throws Exception {
        MemoryNodeStore nodeStore = new MemoryNodeStore();
        FileStore fileStore = mock(FileStore.class, RETURNS_DEEP_STUBS);
        when(fileStore.getHead().getRecordId().toString10()).thenReturn("head-10");

        new GenesisInitializer(nodeStore, fileStore, null, null).initializeGenesisContent();

        NodeState genesis = getGenesisNode(nodeStore.getRoot());
        NodeState protocol = genesis.getChildNode("protocol");
        NodeState contract = genesis.getChildNode("content-contract");
        NodeState gettingStarted = genesis.getChildNode("getting-started");
        NodeState apiConsensus = genesis.getChildNode("api").getChildNode("consensus");
        NodeState imageContent = genesis.getChildNode("do-it-live.jpeg").getChildNode("jcr:content");
        NodeState ipfs = genesis.getChildNode("ipfs");

        assertTrue(genesis.exists());
        assertEquals("DO IT LIVE!", genesis.getProperty("message").getValue(Type.STRING));
        assertEquals("oak-blockchain-aem", genesis.getProperty("chainId").getValue(Type.STRING));
        assertEquals(CanonicalGenesisContent.getGenesisPath(), genesis.getProperty("canonicalGenesisPath").getValue(Type.STRING));
        assertEquals("http://localhost:8090", protocol.getProperty("genesisValidator").getValue(Type.STRING));
        assertEquals("localhost", protocol.getProperty("genesisHost").getValue(Type.STRING));
        assertEquals("Below /content the shape is intentionally open and may evolve.",
            contract.getProperty("contentShapeStatus").getValue(Type.STRING));
        assertEquals("GET /v1/explorer/content/nav and pick a clusterId.",
            gettingStarted.getChildNode("3-browse-genesis").getProperty("step-1").getValue(Type.STRING));
        assertEquals("Consensus status and cluster health",
            apiConsensus.getProperty("GET_v1_consensus_status").getValue(Type.STRING));
        assertTrue(imageContent.getProperty("jcr:data").getValue(Type.BINARY) instanceof Blob);
        assertEquals(false, ipfs.getProperty("enabled").getValue(Type.BOOLEAN));
    }

    @Test
    public void testInitializeGenesisUsesBlobStoreAndSkipsRecreatingExistingGenesis() throws Exception {
        MemoryNodeStore nodeStore = new MemoryNodeStore();
        FileStore fileStore = mock(FileStore.class, RETURNS_DEEP_STUBS);
        BlobStore blobStore = mock(BlobStore.class);
        when(fileStore.getHead().getRecordId().toString10()).thenReturn("head-10");
        when(blobStore.writeBlob(any())).thenReturn(GENESIS_BLOB_ID);

        GenesisInitializer initializer = new GenesisInitializer(
            nodeStore,
            fileStore,
            blobStore,
            "https://validator.example:8090"
        );

        initializer.initializeGenesisContent();
        initializer.initializeGenesisContent();

        NodeState genesis = getGenesisNode(nodeStore.getRoot());
        NodeState protocol = genesis.getChildNode("protocol");
        NodeState imageContent = genesis.getChildNode("do-it-live.jpeg").getChildNode("jcr:content");
        NodeState ipfs = genesis.getChildNode("ipfs");

        assertEquals("https://validator.example:8090", protocol.getProperty("genesisValidator").getValue(Type.STRING));
        assertEquals("validator.example", protocol.getProperty("genesisHost").getValue(Type.STRING));
        assertEquals(GENESIS_BLOB_ID, imageContent.getProperty("jcr:blobId").getValue(Type.STRING));
        assertEquals("QmYwAPJzv5CZsnAzt8auVZRnGi2C4gYQqbiZ9erjRzCQXD", ipfs.getProperty("genesisImageCid").getValue(Type.STRING));
        assertTrue(ipfs.getProperty("enabled").getValue(Type.BOOLEAN));
        verify(blobStore, times(1)).writeBlob(any());
    }

    private static NodeState getGenesisNode(NodeState root) {
        return CanonicalGenesisContent.getGenesisNode(root);
    }
}
