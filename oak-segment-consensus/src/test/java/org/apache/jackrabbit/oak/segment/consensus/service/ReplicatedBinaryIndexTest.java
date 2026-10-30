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
package org.apache.jackrabbit.oak.segment.consensus.service;

import org.apache.jackrabbit.oak.blob.cloud.ipfs.CidResolver;
import org.apache.jackrabbit.oak.plugins.memory.MemoryNodeStore;
import org.apache.jackrabbit.oak.spi.commit.CommitInfo;
import org.apache.jackrabbit.oak.spi.commit.EmptyHook;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ReplicatedBinaryIndexTest {

    private static final String ID = "0123456789abcdef".repeat(4);
    private static final String CID_V0 = "Qma7P9KW6iSj5e9J6b4HSzAtzmy9WS69YsxNKktQX88H4i";
    private static final String CID_V1 = "bafybeigdyrzt5sfp7udm7hu76uh7y26nf3efuylqabf3oclgtqy55fbzdi";

    @Test
    public void testRecordedEntryResolves() throws Exception {
        MemoryNodeStore store = new MemoryNodeStore();
        record(store, ID + "#762228", CID_V0);

        ReplicatedBinaryIndex index = new ReplicatedBinaryIndex(store);
        assertEquals(new CidResolver.Resolution(CID_V0, 762228), index.resolve(ID));
        assertTrue(store.getRoot().getChildNode(ReplicatedBinaryIndex.ROOT).getChildNode("01").getChildNode("23")
            .hasChildNode(ID));
        assertNull(index.resolve("f".repeat(64)));
        assertNull(index.resolve("../escape"));
    }

    @Test
    public void testLaterEntryReplacesEarlierAndLengthIsOptional() throws Exception {
        MemoryNodeStore store = new MemoryNodeStore();
        record(store, ID + "#5", CID_V0);
        record(store, ID, CID_V1);

        assertEquals(new CidResolver.Resolution(CID_V1, -1), new ReplicatedBinaryIndex(store).resolve(ID));
    }

    @Test
    public void testOnlySha256IdentifiersAndWellFormedCidsAreRecorded() throws Exception {
        NodeBuilder root = new MemoryNodeStore().getRoot().builder();

        ReplicatedBinaryIndex.record(root, "blob-1#3", CID_V0);
        ReplicatedBinaryIndex.record(root, "0x0102030405", CID_V0);
        ReplicatedBinaryIndex.record(root, ID.toUpperCase() + "#3", CID_V0);
        ReplicatedBinaryIndex.record(root, ID + "#3", "");
        ReplicatedBinaryIndex.record(root, ID + "#3", "../../etc");
        assertFalse(root.hasChildNode(ReplicatedBinaryIndex.ROOT));

        ReplicatedBinaryIndex.record(root, ID + "#notanumber", CID_V0);
        assertTrue(root.getChildNode(ReplicatedBinaryIndex.ROOT).getChildNode("01").getChildNode("23")
            .getChildNode(ID).hasProperty(ReplicatedBinaryIndex.CID));
    }

    private static void record(MemoryNodeStore store, String blobId, String cid) throws Exception {
        NodeBuilder root = store.getRoot().builder();
        ReplicatedBinaryIndex.record(root, blobId, cid);
        store.merge(root, EmptyHook.INSTANCE, CommitInfo.EMPTY);
    }
}
