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
package org.apache.jackrabbit.oak.blob.cloud.ipfs;

import io.ipfs.api.MerkleNode;
import io.ipfs.api.NamedStreamable;
import org.apache.jackrabbit.core.data.DataIdentifier;
import org.apache.jackrabbit.core.data.DataRecord;
import org.apache.jackrabbit.core.data.DataStoreException;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class IPFSBackendClientSeamTest {

    private static final String CID_ONE = "QmYwAPJzv5CZsnAzt8auVZRnGi2C4gYQqbiZ9erjRzCQXD";
    private static final String CID_TWO = "QmYwAPJzv5CZsnAzt8auVZRnGi2C4gYQqbiZ9erjRzCQXD";

    @Test
    public void testInitUsesDefaultEndpointAndToleratesVersionLookupFailure() throws Exception {
        RecordingIpfsClient client = new RecordingIpfsClient();
        client.versionFailure = new RuntimeException("no version");
        AtomicReference<String> endpointRef = new AtomicReference<>();
        IPFSBackend backend = new IPFSBackend(endpoint -> {
            endpointRef.set(endpoint);
            return client;
        });

        backend.init();

        assertEquals("/ip4/127.0.0.1/tcp/5001", endpointRef.get());
        assertEquals("/ip4/127.0.0.1/tcp/5001", backend.getIpfsApiEndpoint());
        backend.close();
    }

    @Test
    public void testWriteReadExistsRecordAndDeleteRoundTrip() throws Exception {
        RecordingIpfsClient client = new RecordingIpfsClient();
        client.addResults.add(new MerkleNode(CID_ONE));
        byte[] content = "hello-ipfs".getBytes(StandardCharsets.UTF_8);
        client.catResults.put(CID_ONE, content);
        client.blockStats.put(CID_ONE, Map.of("Size", content.length));
        IPFSBackend backend = new IPFSBackend(endpoint -> client);
        backend.setIpfsApiEndpoint("/ip4/10.0.0.9/tcp/5001");
        backend.init();

        File tempFile = File.createTempFile("oak-ipfs", ".bin");
        Files.write(tempFile.toPath(), content);
        DataIdentifier id = new DataIdentifier("blob-1");

        backend.write(id, tempFile);

        assertEquals(CID_ONE, backend.getCID(id));
        assertEquals(Map.of("blob-1", CID_ONE), backend.getAllCIDMappings());
        assertEquals(List.of(CID_ONE), client.pinAddCalls);
        assertTrue(backend.exists(id));

        try (InputStream stream = backend.read(id)) {
            assertArrayEquals(content, stream.readAllBytes());
        }

        DataRecord record = backend.getRecord(id);
        assertEquals(content.length, record.getLength());
        assertEquals(content.length, record.getLength());
        long lastModified = record.getLastModified();
        assertTrue(lastModified > 0);
        assertEquals(lastModified, record.getLastModified());
        try (InputStream stream = record.getStream()) {
            assertArrayEquals(content, stream.readAllBytes());
        }

        Iterator<DataIdentifier> identifiers = backend.getAllIdentifiers();
        assertTrue(identifiers.hasNext());
        assertEquals(id, identifiers.next());
        assertFalse(identifiers.hasNext());

        Iterator<DataRecord> records = backend.getAllRecords();
        assertTrue(records.hasNext());
        assertEquals(id, records.next().getIdentifier());
        assertFalse(records.hasNext());

        backend.deleteRecord(id);

        assertEquals(List.of(CID_ONE), client.pinRemoveCalls);
        assertFalse(backend.exists(id));
        assertNull(backend.getCID(id));
        tempFile.delete();
        backend.close();
    }

    @Test
    public void testExistsReturnsFalseWhenBlockStatIsIncompleteOrThrows() throws Exception {
        RecordingIpfsClient client = new RecordingIpfsClient();
        client.addResults.add(new MerkleNode(CID_ONE));
        client.blockStats.put(CID_ONE, Map.of("Links", 1));
        IPFSBackend backend = new IPFSBackend(endpoint -> client);
        backend.init();

        File tempFile = File.createTempFile("oak-ipfs-stat", ".bin");
        Files.writeString(tempFile.toPath(), "stat");
        DataIdentifier id = new DataIdentifier("blob-stat");

        backend.write(id, tempFile);

        assertFalse(backend.exists(id));

        client.blockStatFailure = new RuntimeException("stat failed");
        assertFalse(backend.exists(id));

        tempFile.delete();
        backend.close();
    }

    @Test
    public void testMetadataLifecycleAndPrefixDeletion() throws Exception {
        RecordingIpfsClient client = new RecordingIpfsClient();
        client.addResults.add(new MerkleNode(CID_ONE));
        client.addResults.add(new MerkleNode(CID_TWO));
        client.blockStats.put(CID_ONE, Map.of("Size", 4));
        client.blockStats.put(CID_TWO, Map.of("Size", 6));
        IPFSBackend backend = new IPFSBackend(endpoint -> client);
        backend.init();

        backend.addMetadataRecord(new ByteArrayInputStream("meta".getBytes(StandardCharsets.UTF_8)), "pref-one");
        File tempFile = File.createTempFile("oak-ipfs-meta", ".txt");
        Files.writeString(tempFile.toPath(), "second");
        backend.addMetadataRecord(tempFile, "pref-two");

        assertTrue(backend.metadataRecordExists("pref-one"));
        assertTrue(backend.metadataRecordExists("pref-two"));
        assertNotNull(backend.getMetadataRecord("pref-one"));
        assertEquals(2, backend.getAllMetadataRecords("pref").size());
        assertEquals(List.of(CID_ONE, CID_TWO), client.pinAddCalls);

        assertTrue(backend.deleteMetadataRecord("pref-one"));
        assertFalse(backend.metadataRecordExists("pref-one"));

        backend.deleteAllMetadataRecords("pref");

        assertFalse(backend.metadataRecordExists("pref-two"));
        assertEquals(List.of(CID_ONE, CID_TWO), client.pinRemoveCalls);
        tempFile.delete();
        backend.close();
    }

    @Test
    public void testMetadataAddWithoutReturnedCidAndMissingFilePath() throws Exception {
        RecordingIpfsClient client = new RecordingIpfsClient();
        IPFSBackend backend = new IPFSBackend(endpoint -> client);
        backend.init();

        backend.addMetadataRecord(new ByteArrayInputStream("meta".getBytes(StandardCharsets.UTF_8)), "empty-result");
        assertFalse(backend.metadataRecordExists("empty-result"));

        try {
            backend.addMetadataRecord(new File("does-not-exist-" + System.nanoTime()), "missing-file");
            fail("Expected missing file to fail");
        } catch (DataStoreException e) {
            assertTrue(e.getMessage().contains("Failed to add metadata from file: missing-file"));
        }

        backend.close();
    }

    @Test
    public void testDeleteMetadataRecordReturnsFalseWhenUnpinFails() throws Exception {
        RecordingIpfsClient client = new RecordingIpfsClient();
        client.addResults.add(new MerkleNode(CID_ONE));
        IPFSBackend backend = new IPFSBackend(endpoint -> client);
        backend.init();

        backend.addMetadataRecord(new ByteArrayInputStream("meta".getBytes(StandardCharsets.UTF_8)), "failing-delete");
        client.pinRemoveFailure = new RuntimeException("rm failed");

        assertFalse(backend.deleteMetadataRecord("failing-delete"));
        assertTrue(backend.metadataRecordExists("failing-delete"));

        backend.close();
    }

    @Test
    public void testDeleteAllMetadataRecordsSwallowsDeleteFailures() throws Exception {
        RecordingIpfsClient client = new RecordingIpfsClient();
        client.addResults.add(new MerkleNode(CID_ONE));
        client.addResults.add(new MerkleNode(CID_TWO));
        IPFSBackend backend = new IPFSBackend(endpoint -> client);
        backend.init();

        backend.addMetadataRecord(new ByteArrayInputStream("one".getBytes(StandardCharsets.UTF_8)), "pref-one");
        backend.addMetadataRecord(new ByteArrayInputStream("two".getBytes(StandardCharsets.UTF_8)), "pref-two");
        client.pinRemoveFailure = new RuntimeException("rm failed");

        backend.deleteAllMetadataRecords("pref");

        assertTrue(backend.metadataRecordExists("pref-one"));
        assertTrue(backend.metadataRecordExists("pref-two"));
        backend.close();
    }

    @Test
    public void testWriteAndMetadataFailuresAreTranslated() throws Exception {
        RecordingIpfsClient client = new RecordingIpfsClient();
        IPFSBackend backend = new IPFSBackend(endpoint -> client);
        backend.init();

        File tempFile = File.createTempFile("oak-ipfs-empty", ".bin");
        Files.writeString(tempFile.toPath(), "empty");
        try {
            backend.write(new DataIdentifier("blob-empty"), tempFile);
            fail("Expected empty add result to fail");
        } catch (DataStoreException e) {
            assertTrue(e.getMessage().contains("IPFS add returned empty result"));
        }

        client.addResults.add(new MerkleNode(CID_ONE));
        client.pinAddFailure = new RuntimeException("pin-failed");
        try {
            backend.addMetadataRecord(new ByteArrayInputStream("meta".getBytes(StandardCharsets.UTF_8)), "failing-meta");
            fail("Expected metadata add to fail");
        } catch (DataStoreException e) {
            assertTrue(e.getMessage().contains("Failed to add metadata: failing-meta"));
        }

        tempFile.delete();
        backend.close();
    }

    @Test
    public void testRecordLengthFailureIsTranslated() throws Exception {
        RecordingIpfsClient client = new RecordingIpfsClient();
        client.addResults.add(new MerkleNode(CID_ONE));
        client.blockStats.put(CID_ONE, Map.of("Size", 5));
        IPFSBackend backend = new IPFSBackend(endpoint -> client);
        backend.init();

        File tempFile = File.createTempFile("oak-ipfs-length", ".bin");
        Files.writeString(tempFile.toPath(), "hello");
        DataIdentifier id = new DataIdentifier("blob-length");
        backend.write(id, tempFile);

        DataRecord record = backend.getRecord(id);
        client.blockStatFailure = new RuntimeException("stat failed");

        try {
            record.getLength();
            fail("Expected record length lookup to fail");
        } catch (DataStoreException e) {
            assertTrue(e.getMessage().contains("Failed to get length for"));
        }

        tempFile.delete();
        backend.close();
    }

    @Test
    public void testInitFactoryFailureAndReadDeleteFailuresAreTranslated() throws Exception {
        try {
            new IPFSBackend(endpoint -> {
                throw new RuntimeException("connect failed");
            }).init();
            fail("Expected init failure");
        } catch (DataStoreException e) {
            assertTrue(e.getMessage().contains("Failed to initialize IPFS backend"));
        }

        RecordingIpfsClient client = new RecordingIpfsClient();
        client.addResults.add(new MerkleNode(CID_ONE));
        client.catFailure = new RuntimeException("cat failed");
        client.pinRemoveFailure = new RuntimeException("rm failed");
        client.blockStats.put(CID_ONE, Map.of("Size", 5));
        IPFSBackend backend = new IPFSBackend(endpoint -> client);
        backend.init();

        File tempFile = File.createTempFile("oak-ipfs-read", ".bin");
        Files.writeString(tempFile.toPath(), "hello");
        DataIdentifier id = new DataIdentifier("blob-read");
        backend.write(id, tempFile);

        try {
            backend.read(id);
            fail("Expected read failure");
        } catch (DataStoreException e) {
            assertTrue(e.getMessage().contains("IPFS read failed"));
        }

        try {
            backend.deleteRecord(id);
            fail("Expected delete failure");
        } catch (DataStoreException e) {
            assertTrue(e.getMessage().contains("IPFS delete failed"));
        }

        tempFile.delete();
        backend.close();
    }

    private static final class RecordingIpfsClient implements IpfsClient {

        private final Queue<MerkleNode> addResults = new ArrayDeque<>();
        private final Map<String, byte[]> catResults = new HashMap<>();
        private final Map<String, Map<String, Object>> blockStats = new HashMap<>();
        private final List<String> pinAddCalls = new ArrayList<>();
        private final List<String> pinRemoveCalls = new ArrayList<>();

        private RuntimeException versionFailure;
        private RuntimeException pinAddFailure;
        private RuntimeException pinRemoveFailure;
        private RuntimeException catFailure;
        private RuntimeException blockStatFailure;

        @Override
        public Object version() {
            if (versionFailure != null) {
                throw versionFailure;
            }
            return Map.of("Version", "test");
        }

        @Override
        public List<MerkleNode> add(NamedStreamable upload) {
            if (addResults.isEmpty()) {
                return List.of();
            }
            return List.of(addResults.remove());
        }

        @Override
        public void pinAdd(String cid) {
            if (pinAddFailure != null) {
                throw pinAddFailure;
            }
            pinAddCalls.add(cid);
        }

        @Override
        public void pinRemove(String cid) {
            if (pinRemoveFailure != null) {
                throw pinRemoveFailure;
            }
            pinRemoveCalls.add(cid);
        }

        @Override
        public byte[] cat(String cid) {
            if (catFailure != null) {
                throw catFailure;
            }
            return catResults.get(cid);
        }

        @Override
        public Map<String, Object> blockStat(String cid) {
            if (blockStatFailure != null) {
                throw blockStatFailure;
            }
            return blockStats.get(cid);
        }
    }
}
