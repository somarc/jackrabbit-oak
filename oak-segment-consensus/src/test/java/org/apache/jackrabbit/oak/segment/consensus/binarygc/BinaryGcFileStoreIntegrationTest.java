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
package org.apache.jackrabbit.oak.segment.consensus.binarygc;

import org.apache.jackrabbit.oak.plugins.blob.datastore.DataStoreBlobStore;
import org.apache.jackrabbit.oak.plugins.blob.datastore.OakFileDataStore;
import org.apache.jackrabbit.oak.segment.SegmentNodeStore;
import org.apache.jackrabbit.oak.segment.SegmentNodeStoreBuilders;
import org.apache.jackrabbit.oak.segment.compaction.SegmentGCOptions;
import org.apache.jackrabbit.oak.segment.consensus.service.AppliedLogPosition;
import org.apache.jackrabbit.oak.segment.consensus.service.BinaryReleaseApplicationService;
import org.apache.jackrabbit.oak.segment.consensus.service.FileStoreFlushService;
import org.apache.jackrabbit.oak.segment.consensus.service.MutationAuditMetadata;
import org.apache.jackrabbit.oak.segment.consensus.service.ReplicatedBinaryIndex;
import org.apache.jackrabbit.oak.segment.consensus.service.WriteApplicationService;
import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.apache.jackrabbit.oak.segment.file.FileStoreBuilder;
import org.apache.jackrabbit.oak.spi.blob.data.DataIdentifier;
import org.apache.jackrabbit.oak.spi.commit.CommitInfo;
import org.apache.jackrabbit.oak.spi.commit.EmptyHook;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Binary GC on a real segment store and DataStore: a deleted binary stays referenced until two full compactions
 * and cleanups have dropped the generations that held it; the mark then proposes it alone, the release removes
 * its index entry, and the member deletes its record while the live binary keeps its own.
 */
public class BinaryGcFileStoreIntegrationTest {

    private static final String WALLET = "0x1234567890abcdef1234567890abcdef12345678";
    private static final String CONTENT = "/oak-chain/aa/bb/cc/" + WALLET + "/Acme/content/";

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder(new File("target"));

    private OakFileDataStore dataStore;
    private DataStoreBlobStore blobStore;
    private FileStore fileStore;
    private SegmentNodeStore nodeStore;

    @Before
    public void open() throws Exception {
        dataStore = new OakFileDataStore();
        dataStore.setMinRecordLength(16);
        dataStore.init(folder.newFolder("datastore").getAbsolutePath());
        blobStore = new DataStoreBlobStore(dataStore);
        fileStore = FileStoreBuilder.fileStoreBuilder(folder.newFolder("segmentstore"))
            .withBlobStore(blobStore)
            .withMaxFileSize(1)
            .withMemoryMapping(false)
            .withGCOptions(SegmentGCOptions.defaultGCOptions().setEstimationDisabled(true).setRetainedGenerations(2))
            .build();
        nodeStore = SegmentNodeStoreBuilders.builder(fileStore).build();
    }

    @After
    public void close() {
        fileStore.close();
    }

    @Test
    public void onlyTheDeletedBinaryIsReleasedAndItsRecordDeleted() throws Exception {
        WriteApplicationService writes = new WriteApplicationService(fileStore, () -> nodeStore, () -> blobStore,
            mock(FileStoreFlushService.class));
        String deletedBlob = upload(1);
        String liveBlob = upload(2);
        write(writes, "deleted", deletedBlob, "Qm" + "a".repeat(44), 100, 1_000L);
        write(writes, "live", liveBlob, "Qm" + "b".repeat(44), 200, 2_000L);
        String deleted = ReplicatedBinaryIndex.identifier(deletedBlob);
        String live = ReplicatedBinaryIndex.identifier(liveBlob);

        NodeBuilder root = nodeStore.getRoot().builder();
        child(root, CONTENT + "deleted").remove();
        new AppliedLogPosition(300, 0, 1).writeTo(root);
        nodeStore.merge(root, EmptyHook.INSTANCE, CommitInfo.EMPTY);

        BinaryGcStatus status = new BinaryGcStatus(new BinaryGcConfig(true, 1000, 0, 0), false);
        List<String> proposals = new ArrayList<>();
        BinaryGcCoordinator coordinator = new BinaryGcCoordinator(new BinaryGcConfig(true, 1000, 0, 0),
            nodeStore::getRoot,
            collector -> {
                fileStore.flush();
                fileStore.collectBlobReferences(collector);
            },
            new BinaryGcCoordinator.Proposer() {
                @Override
                public boolean canPropose() {
                    return true;
                }

                @Override
                public boolean propose(long markedAtPosition, long maxLastModified, List<String> blobIds) {
                    proposals.add(markedAtPosition + "|" + maxLastModified + "|" + blobIds);
                    return true;
                }
            }, () -> 10_000L + System.currentTimeMillis(), status);

        coordinator.mark();
        assertTrue("the deleted binary is still referenced by the retained generation", proposals.isEmpty());

        for (int i = 0; i < 2; i++) {
            assertTrue(fileStore.compactFull());
            fileStore.cleanup();
        }
        coordinator.mark();
        assertEquals(1, proposals.size());
        String[] proposal = proposals.get(0).split("\\|");
        assertEquals("300", proposal[0]);
        assertEquals(List.of(deleted).toString(), proposal[2]);

        BinaryReleaseApplicationService.Result result = new BinaryReleaseApplicationService(() -> nodeStore).apply(
            Map.of("markedAtPosition", 300L, "maxLastModified", Long.parseLong(proposal[1]),
                "blobIds", List.of(deleted)), new AppliedLogPosition(400, 0, 1));
        assertEquals(List.of(deleted), result.released());
        assertFalse(ReplicatedBinaryIndex.contains(nodeStore.getRoot(), deleted));
        assertTrue(ReplicatedBinaryIndex.contains(nodeStore.getRoot(), live));

        BinaryRecordDeleter.RecordStore records = BinaryRecordDeleter.recordsOf(blobStore);
        assertNotNull(records);
        List<String> deleteCalls = new CopyOnWriteArrayList<>();
        BinaryRecordDeleter deleter = new BinaryRecordDeleter(new BinaryRecordDeleter.RecordStore() {
            @Override
            public long lastModified(String identifier) throws Exception {
                return records.lastModified(identifier);
            }

            @Override
            public void delete(String identifier) throws Exception {
                deleteCalls.add(identifier);
                records.delete(identifier);
            }
        }, nodeStore::getRoot, status, 0, null, System::currentTimeMillis);
        try {
            deleter.onReleased(result);
            long deadline = System.currentTimeMillis() + 10_000;
            while (deleteCalls.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
        } finally {
            deleter.close();
        }

        assertEquals(List.of(deleted), deleteCalls);
        assertNull(dataStore.getRecordIfStored(new DataIdentifier(deleted)));
        assertNotNull(dataStore.getRecordIfStored(new DataIdentifier(live)));
        assertEquals(new AppliedLogPosition(400, 0, 1), AppliedLogPosition.read(nodeStore.getRoot()));
    }

    private String upload(int seed) throws Exception {
        byte[] bytes = new byte[32 * 1024];
        new Random(seed).nextBytes(bytes);
        return blobStore.writeBlob(new ByteArrayInputStream(bytes));
    }

    private static void write(WriteApplicationService writes, String name, String blobId, String cid,
                              long position, long time) {
        writes.applyWriteWithAuditMetadata(WALLET, CONTENT + name, "file", "m", "0xsig", null, blobId,
            "application/octet-stream", cid, MutationAuditMetadata.write(null, null, "p-" + name, null, null, null,
                null).withAppliedAt(time).withAppliedLogPosition(new AppliedLogPosition(position, 0, 1)));
    }

    private static NodeBuilder child(NodeBuilder root, String path) {
        NodeBuilder current = root;
        for (String part : path.substring(1).split("/")) {
            current = current.getChildNode(part);
        }
        return current;
    }
}
