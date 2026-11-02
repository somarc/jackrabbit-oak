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

import org.apache.jackrabbit.oak.blob.cloud.ipfs.IPFSDataStore;
import org.apache.jackrabbit.oak.plugins.blob.datastore.DataStoreBlobStore;
import org.apache.jackrabbit.oak.plugins.blob.datastore.OakFileDataStore;
import org.apache.jackrabbit.oak.plugins.memory.EmptyNodeState;
import org.apache.jackrabbit.oak.segment.consensus.service.AppliedLogPosition;
import org.apache.jackrabbit.oak.segment.consensus.service.BinaryReleaseApplicationService;
import org.apache.jackrabbit.oak.spi.blob.BlobStore;
import org.apache.jackrabbit.oak.spi.blob.SharedBackend;
import org.apache.jackrabbit.oak.spi.blob.data.DataIdentifier;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

public class BinaryRecordDeleterTest {

    private static final String DELETED = "aa".repeat(32);
    private static final String FAILING = "bb".repeat(32);
    private static final String RECENT = "cc".repeat(32);
    private static final String ABSENT = "dd".repeat(32);
    private static final String REFERENCED = "ee".repeat(32);

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void deletesOffTheApplyThreadAndSurvivesFailures() throws Exception {
        FakeRecords records = new FakeRecords();
        records.lastModified.put(DELETED, 10L);
        records.lastModified.put(FAILING, 10L);
        records.lastModified.put(RECENT, 101L);
        records.lastModified.put(REFERENCED, 10L);
        records.failing.add(FAILING);
        BinaryGcStatus status = status();
        List<String> forgotten = new CopyOnWriteArrayList<>();
        NodeState root = indexWith(REFERENCED);
        BinaryRecordDeleter deleter = new BinaryRecordDeleter(records, () -> root, status, 0, forgotten::add,
            () -> 77L);
        try {
            deleter.onReleased(result(100, FAILING, DELETED, RECENT, ABSENT, REFERENCED));
            assertTrue(records.done.await(10, TimeUnit.SECONDS));
            awaitIdle(status);
        } finally {
            deleter.close();
        }

        assertEquals(List.of(DELETED), records.deleted);
        assertEquals(List.of(DELETED), forgotten);
        assertTrue(records.threads.stream().allMatch("binary-gc-delete"::equals));
        Map<String, Object> deletes = deletes(status);
        assertEquals(1L, deletes.get("ok"));
        assertEquals(1L, deletes.get("failed"));
        assertEquals(1L, deletes.get("skippedRecent"));
        assertEquals(1L, deletes.get("absent"));
        assertEquals(1L, deletes.get("skippedReferenced"));
        assertEquals(0L, deletes.get("pending"));
        @SuppressWarnings("unchecked")
        Map<String, Object> release = (Map<String, Object>) status.toMap().get("lastRelease");
        assertEquals(77L, release.get("at"));
        assertEquals(900L, release.get("position"));
        assertEquals(5, release.get("released"));
        assertEquals(1, release.get("skipped"));
    }

    @Test
    public void withoutDeletableRecordsOrWithoutReleasesNothingIsScheduled() {
        BinaryGcStatus status = status();
        BinaryRecordDeleter noStore = new BinaryRecordDeleter(null, () -> EmptyNodeState.EMPTY_NODE, status, 0, null,
            () -> 1L);
        noStore.onReleased(result(100, DELETED));
        noStore.close();
        assertEquals(0L, deletes(status).get("pending"));

        FakeRecords records = new FakeRecords();
        BinaryRecordDeleter deleter = new BinaryRecordDeleter(records, () -> EmptyNodeState.EMPTY_NODE, status, 0, null,
            () -> 1L);
        deleter.onReleased(result(100));
        deleter.close();
        deleter.onReleased(result(100, DELETED));
        assertTrue(records.threads.isEmpty());
    }

    @Test
    public void unboundedReleasesIgnoreLastModified() throws Exception {
        FakeRecords records = new FakeRecords();
        records.lastModified.put(RECENT, Long.MAX_VALUE);
        BinaryGcStatus status = status();
        BinaryRecordDeleter deleter = new BinaryRecordDeleter(records, () -> EmptyNodeState.EMPTY_NODE, status, 0, null,
            () -> 1L);
        deleter.deleteAll(new BinaryReleaseApplicationService.Result(5, 0, List.of(RECENT), 0, null));
        deleter.close();
        assertEquals(List.of(RECENT), records.deleted);
    }

    @Test
    public void recordsOfADataStoreDeleteThroughTheDataStore() throws Exception {
        assertNull(BinaryRecordDeleter.recordsOf(null));
        assertNull(BinaryRecordDeleter.recordsOf(mock(BlobStore.class)));

        OakFileDataStore dataStore = new OakFileDataStore();
        dataStore.setMinRecordLength(16);
        dataStore.init(folder.newFolder("datastore").getAbsolutePath());
        DataStoreBlobStore blobStore = new DataStoreBlobStore(dataStore);
        byte[] bytes = new byte[4096];
        new Random(7).nextBytes(bytes);
        String identifier = blobStore.writeBlob(new ByteArrayInputStream(bytes)).split("#")[0];

        BinaryRecordDeleter.RecordStore records = BinaryRecordDeleter.recordsOf(blobStore);
        assertNotNull(records);
        assertTrue(records.lastModified(identifier) > 0);
        assertEquals(-1, records.lastModified(ABSENT));
        records.delete(identifier);
        assertEquals(-1, records.lastModified(identifier));
    }

    @Test
    public void ipfsRecordsAreProbedWithoutTheCachingDataStore() throws Exception {
        IPFSDataStore dataStore = mock(IPFSDataStore.class);
        SharedBackend backend = mock(SharedBackend.class);
        when(dataStore.getBackend()).thenReturn(backend);
        BinaryRecordDeleter.RecordStore records = BinaryRecordDeleter.recordsOf(new DataStoreBlobStore(dataStore));

        assertEquals(-1, records.lastModified(ABSENT));
        records.delete(DELETED);

        verify(backend).exists(new DataIdentifier(ABSENT));
        verify(dataStore).getBackend();
        verify(dataStore).deleteRecord(new DataIdentifier(DELETED));
        verifyNoMoreInteractions(dataStore);
    }

    private static BinaryGcStatus status() {
        return new BinaryGcStatus(new BinaryGcConfig(true, 1000, 0, 0), false);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deletes(BinaryGcStatus status) {
        return (Map<String, Object>) status.toMap().get("deletes");
    }

    private static void awaitIdle(BinaryGcStatus status) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while ((Long) deletes(status).get("pending") > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
    }

    private static BinaryReleaseApplicationService.Result result(long maxLastModified, String... released) {
        return new BinaryReleaseApplicationService.Result(500, maxLastModified, List.of(released), 1,
            new AppliedLogPosition(900, 0, 1));
    }

    private static NodeState indexWith(String identifier) {
        NodeBuilder root = EmptyNodeState.EMPTY_NODE.builder();
        root.child(":oak-chain-binaries").child(identifier.substring(0, 2)).child(identifier.substring(2, 4))
            .child(identifier).setProperty("cid", "Qm" + "a".repeat(44));
        return root.getNodeState();
    }

    private static final class FakeRecords implements BinaryRecordDeleter.RecordStore {
        final Map<String, Long> lastModified = new ConcurrentHashMap<>();
        final List<String> failing = new ArrayList<>();
        final List<String> deleted = Collections.synchronizedList(new ArrayList<>());
        final List<String> threads = new CopyOnWriteArrayList<>();
        final CountDownLatch done = new CountDownLatch(1);

        @Override
        public long lastModified(String identifier) {
            threads.add(Thread.currentThread().getName());
            if (identifier.equals(ABSENT)) {
                done.countDown();
            }
            return lastModified.getOrDefault(identifier, -1L);
        }

        @Override
        public void delete(String identifier) throws Exception {
            if (failing.contains(identifier)) {
                throw new Exception("IPFS unreachable");
            }
            deleted.add(identifier);
        }
    }
}
