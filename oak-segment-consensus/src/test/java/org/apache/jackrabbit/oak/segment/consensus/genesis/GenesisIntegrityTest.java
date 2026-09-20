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

import java.util.TimeZone;
import java.util.Arrays;
import java.io.File;

import org.apache.jackrabbit.oak.api.Type;
import org.apache.jackrabbit.oak.plugins.memory.ArrayBasedBlob;
import org.apache.jackrabbit.oak.plugins.memory.EmptyNodeState;
import org.apache.jackrabbit.oak.plugins.memory.MemoryNodeStore;
import org.apache.jackrabbit.core.data.FileDataStore;
import org.apache.jackrabbit.oak.plugins.blob.datastore.DataStoreBlobStore;
import org.apache.jackrabbit.oak.segment.SegmentNodeStoreBuilders;
import org.apache.jackrabbit.oak.segment.consensus.config.ConsensusSafety;
import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.apache.jackrabbit.oak.segment.file.FileStoreBuilder;
import org.apache.jackrabbit.oak.spi.commit.CommitInfo;
import org.apache.jackrabbit.oak.spi.commit.EmptyHook;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;
import org.apache.jackrabbit.oak.spi.state.NodeStore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

public class GenesisIntegrityTest {
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void validGenesisHasTypedMetadataAndVerifiableSeal() throws Exception {
        MemoryNodeStore store = populatedStore();
        new CanonicalGenesisContent(store, null).verifyExisting();
        assertEquals(Type.NAME, CanonicalGenesisContent.getGenesisNode(store.getRoot()).getProperty("jcr:primaryType").getType());
        assertEquals(Type.DATE, CanonicalGenesisContent.getGenesisNode(store.getRoot()).getProperty("jcr:created").getType());
        assertEquals(64, CanonicalGenesisContent.getGenesisNode(store.getRoot())
            .getProperty(GenesisDigest.PROPERTY).getValue(Type.STRING).length());
    }

    @Test
    public void genesisDateDoesNotDependOnJvmTimezone() throws Exception {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            MemoryNodeStore utc = populatedStore();
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
            MemoryNodeStore newYork = populatedStore();
            assertEquals(
                CanonicalGenesisContent.getGenesisNode(utc.getRoot()).getProperty("genesisDate").getValue(Type.STRING),
                CanonicalGenesisContent.getGenesisNode(newYork.getRoot()).getProperty("genesisDate").getValue(Type.STRING));
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    public void verificationRejectsChangedVersion() throws Exception {
        MemoryNodeStore store = populatedStore();
        NodeBuilder root = store.getRoot().builder();
        genesis(root).setProperty("version", "tampered");
        store.merge(root, EmptyHook.INSTANCE, CommitInfo.EMPTY);
        assertThrows(IllegalStateException.class, () -> new CanonicalGenesisContent(store, null).verifyExisting());
    }

    @Test
    public void verificationRejectsChangedNestedContent() throws Exception {
        MemoryNodeStore store = populatedStore();
        NodeBuilder root = store.getRoot().builder();
        genesis(root).getChildNode("protocol").setProperty("chainId", "tampered");
        store.merge(root, EmptyHook.INSTANCE, CommitInfo.EMPTY);
        assertThrows(IllegalStateException.class, () -> new CanonicalGenesisContent(store, null).verifyExisting());
    }

    @Test
    public void verificationRejectsMissingImage() throws Exception {
        MemoryNodeStore store = populatedStore();
        NodeBuilder root = store.getRoot().builder();
        genesis(root).getChildNode("do-it-live.jpeg").remove();
        store.merge(root, EmptyHook.INSTANCE, CommitInfo.EMPTY);
        assertThrows(IllegalStateException.class, () -> new CanonicalGenesisContent(store, null).verifyExisting());
    }

    @Test
    public void replacingBothContentAndStoredDigestDoesNotEvadeAuthoringCheck() throws Exception {
        MemoryNodeStore store = populatedStore();
        NodeBuilder root = store.getRoot().builder();
        genesis(root).getChildNode("protocol").setProperty("chainId", "tampered");
        NodeBuilder wallet = root.getChildNode("oak-chain").getChildNode("00").getChildNode("00")
            .getChildNode("00").getChildNode(CanonicalGenesisContent.GENESIS_ADDRESS);
        genesis(root).setProperty(GenesisDigest.PROPERTY, GenesisDigest.of(wallet.getNodeState()));
        store.merge(root, EmptyHook.INSTANCE, CommitInfo.EMPTY);
        assertThrows(IllegalStateException.class, () -> new CanonicalGenesisContent(store, null).verifyExisting());
    }

    @Test
    public void verificationCoversWalletMetadataAndBinaryBytes() throws Exception {
        MemoryNodeStore store = populatedStore();
        NodeBuilder root = store.getRoot().builder();
        root.getChildNode("oak-chain").getChildNode("00").getChildNode("00").getChildNode("00")
            .getChildNode(CanonicalGenesisContent.GENESIS_ADDRESS).setProperty("totalWrites", 2L);
        store.merge(root, EmptyHook.INSTANCE, CommitInfo.EMPTY);
        assertThrows(IllegalStateException.class, () -> new CanonicalGenesisContent(store, null).verifyExisting());

        MemoryNodeStore changedImage = populatedStore();
        root = changedImage.getRoot().builder();
        genesis(root).getChildNode("do-it-live.jpeg").getChildNode("jcr:content")
            .setProperty("jcr:data", new ArrayBasedBlob(new byte[] {1, 2, 3}), Type.BINARY);
        changedImage.merge(root, EmptyHook.INSTANCE, CommitInfo.EMPTY);
        assertThrows(IllegalStateException.class, () -> new CanonicalGenesisContent(changedImage, null).verifyExisting());
    }

    @Test
    public void canonicalDigestPreservesTypeArrayOrderAndFieldBoundaries() throws Exception {
        NodeBuilder first = EmptyNodeState.EMPTY_NODE.builder();
        NodeBuilder second = EmptyNodeState.EMPTY_NODE.builder();
        first.setProperty("value", Arrays.asList("a", "bc"), Type.STRINGS);
        second.setProperty("value", Arrays.asList("ab", "c"), Type.STRINGS);
        assertNotEquals(GenesisDigest.of(first.getNodeState()), GenesisDigest.of(second.getNodeState()));
        second.setProperty("value", Arrays.asList("bc", "a"), Type.STRINGS);
        assertNotEquals(GenesisDigest.of(first.getNodeState()), GenesisDigest.of(second.getNodeState()));
        first.setProperty("value", 123L);
        second.setProperty("value", "123");
        assertNotEquals(GenesisDigest.of(first.getNodeState()), GenesisDigest.of(second.getNodeState()));
        first.setProperty("value", new ArrayBasedBlob(new byte[] {1, 2}), Type.BINARY);
        second.setProperty("value", new ArrayBasedBlob(new byte[] {1, 2}), Type.BINARY);
        assertEquals(GenesisDigest.of(first.getNodeState()), GenesisDigest.of(second.getNodeState()));
    }

    @Test
    public void nativeSegmentGenesisCanBeReopenedAndVerified() throws Exception {
        File directory = temporary.newFolder();
        try (FileStore fileStore = FileStoreBuilder.fileStoreBuilder(directory).build()) {
            NodeStore store = SegmentNodeStoreBuilders.builder(fileStore).build();
            NodeBuilder root = store.getRoot().builder();
            new CanonicalGenesisContent(store, null).populate(root, 1700000000000L, "http://leader:8090");
            store.merge(root, EmptyHook.INSTANCE, CommitInfo.EMPTY);
            fileStore.flush();
        }
        try (FileStore fileStore = FileStoreBuilder.fileStoreBuilder(directory).build()) {
            NodeStore store = SegmentNodeStoreBuilders.builder(fileStore).build();
            new CanonicalGenesisContent(store, null).verifyExisting();
        }
    }

    @Test
    public void externalBlobGenesisReopensAndVerificationDoesNotWrite() throws Exception {
        File directory = temporary.newFolder();
        FileDataStore dataStore = new FileDataStore();
        dataStore.setPath(temporary.newFolder().getAbsolutePath());
        dataStore.init(null);
        DataStoreBlobStore blobs = org.mockito.Mockito.spy(new DataStoreBlobStore(dataStore));
        try {
            try (FileStore fileStore = FileStoreBuilder.fileStoreBuilder(directory).withBlobStore(blobs).build()) {
                NodeStore store = SegmentNodeStoreBuilders.builder(fileStore).build();
                NodeBuilder root = store.getRoot().builder();
                new CanonicalGenesisContent(store, blobs).populate(root, 1700000000000L, "http://seed:8090");
                store.merge(root, EmptyHook.INSTANCE, CommitInfo.EMPTY);
                fileStore.flush();
            }
            org.mockito.Mockito.verify(blobs).writeBlob(org.mockito.ArgumentMatchers.any(java.io.InputStream.class));
            org.mockito.Mockito.clearInvocations(blobs);
            try (FileStore fileStore = FileStoreBuilder.fileStoreBuilder(directory).withBlobStore(blobs).build()) {
                NodeStore store = SegmentNodeStoreBuilders.builder(fileStore).build();
                new CanonicalGenesisContent(store, blobs).verifyExisting();
            }
            org.mockito.Mockito.verify(blobs, org.mockito.Mockito.never()).writeBlob(org.mockito.ArgumentMatchers.any(java.io.InputStream.class));
        } finally {
            blobs.close();
        }
    }

    @Test
    public void zeroWalletDescendantsAndAncestorsAreReserved() {
        String zero = CanonicalGenesisContent.GENESIS_ADDRESS;
        assertTrue(CanonicalGenesisContent.isReservedMutation(zero, null));
        assertTrue(CanonicalGenesisContent.isReservedMutation(" " + zero.toUpperCase() + " ", null));
        assertTrue(CanonicalGenesisContent.isReservedMutation(null, CanonicalGenesisContent.getGenesisPath()));
        assertTrue(CanonicalGenesisContent.isReservedMutation(null, "/oak-chain//00/00/00/" + zero + "/content"));
        assertTrue(CanonicalGenesisContent.isReservedMutation(null, "/oak-chain/00"));
        assertTrue(CanonicalGenesisContent.isReservedMutation(null, "/"));
        assertFalse(CanonicalGenesisContent.isReservedMutation(null, null));
        assertFalse(CanonicalGenesisContent.isReservedMutation("0x12345678", "/oak-chain/12/34/56/0x12345678/content"));
        assertThrows(IllegalArgumentException.class, () -> CanonicalGenesisContent.requireMutable(zero, "/other"));
    }

    @Test
    public void disablingStartupRequiresExplicitFalseAndDoesNotDisableReservation() {
        String original = System.getProperty(ConsensusSafety.ENABLED_PROPERTY);
        try {
            System.setProperty(ConsensusSafety.ENABLED_PROPERTY, "false");
            assertFalse(ConsensusSafety.isEnabled());
            assertTrue(CanonicalGenesisContent.isReservedMutation(CanonicalGenesisContent.GENESIS_ADDRESS, null));
            System.setProperty(ConsensusSafety.ENABLED_PROPERTY, "misspelled");
            assertTrue(ConsensusSafety.isEnabled());
        } finally {
            if (original == null) {
                System.clearProperty(ConsensusSafety.ENABLED_PROPERTY);
            } else {
                System.setProperty(ConsensusSafety.ENABLED_PROPERTY, original);
            }
        }
    }

    private static MemoryNodeStore populatedStore() throws Exception {
        MemoryNodeStore store = new MemoryNodeStore();
        NodeBuilder root = store.getRoot().builder();
        new CanonicalGenesisContent(store, null).populate(root, 1700000000000L, "http://leader:8090");
        store.merge(root, EmptyHook.INSTANCE, CommitInfo.EMPTY);
        return store;
    }

    private static NodeBuilder genesis(NodeBuilder root) {
        NodeBuilder current = root;
        for (String part : CanonicalGenesisContent.getGenesisPath().substring(1).split("/")) {
            current = current.getChildNode(part);
        }
        return current;
    }
}
