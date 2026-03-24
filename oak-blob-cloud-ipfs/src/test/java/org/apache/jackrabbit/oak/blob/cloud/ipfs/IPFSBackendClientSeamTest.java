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
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
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
    public void testInitTreatsEmptyEndpointAsUnset() throws Exception {
        RecordingIpfsClient client = new RecordingIpfsClient();
        AtomicReference<String> endpointRef = new AtomicReference<>();
        IPFSBackend backend = new IPFSBackend(endpoint -> {
            endpointRef.set(endpoint);
            return client;
        });
        backend.setIpfsApiEndpoint("");

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
    public void testGetRecordRejectsCachedCidWhenBlockStatReturnsNull() throws Exception {
        RecordingIpfsClient client = new RecordingIpfsClient();
        client.addResults.add(new MerkleNode(CID_ONE));
        IPFSBackend backend = new IPFSBackend(endpoint -> client);
        backend.init();

        File tempFile = File.createTempFile("oak-ipfs-null-stat", ".bin");
        Files.writeString(tempFile.toPath(), "missing");
        DataIdentifier id = new DataIdentifier("blob-null-stat");

        backend.write(id, tempFile);

        assertFalse(backend.exists(id));
        try {
            backend.getRecord(id);
            fail("Expected missing block stat to reject cached record");
        } catch (DataStoreException e) {
            assertTrue(e.getMessage().contains("Record not found"));
        }

        tempFile.delete();
        backend.close();
    }

    @Test
    public void testMetadataLifecycleAndPrefixDeletion() throws Exception {
        RecordingIpfsClient client = new RecordingIpfsClient();
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
        assertTrue(client.pinAddCalls.isEmpty());

        assertTrue(backend.deleteMetadataRecord("pref-one"));
        assertFalse(backend.metadataRecordExists("pref-one"));

        backend.deleteAllMetadataRecords("pref");

        assertFalse(backend.metadataRecordExists("pref-two"));
        assertTrue(client.pinRemoveCalls.isEmpty());
        tempFile.delete();
        backend.close();
    }

    @Test
    public void testMetadataPrefixOperationsIgnoreNonMatchingEntries() throws Exception {
        RecordingIpfsClient client = new RecordingIpfsClient();
        client.addResults.add(new MerkleNode(CID_ONE));
        client.addResults.add(new MerkleNode(CID_TWO));
        IPFSBackend backend = new IPFSBackend(endpoint -> client);
        backend.init();

        backend.addMetadataRecord(new ByteArrayInputStream("one".getBytes(StandardCharsets.UTF_8)), "pref-one");
        backend.addMetadataRecord(new ByteArrayInputStream("two".getBytes(StandardCharsets.UTF_8)), "other-one");

        List<DataRecord> prefRecords = backend.getAllMetadataRecords("pref");
        assertEquals(1, prefRecords.size());
        assertEquals(new DataIdentifier("pref-one"), prefRecords.get(0).getIdentifier());

        backend.deleteAllMetadataRecords("pref");

        assertFalse(backend.metadataRecordExists("pref-one"));
        assertTrue(backend.metadataRecordExists("other-one"));
        assertTrue(client.pinRemoveCalls.isEmpty());
        backend.close();
    }

    @Test
    public void testMetadataAddAndMissingFilePath() throws Exception {
        RecordingIpfsClient client = new RecordingIpfsClient();
        IPFSBackend backend = new IPFSBackend(endpoint -> client);
        backend.init();

        backend.addMetadataRecord(new ByteArrayInputStream("meta".getBytes(StandardCharsets.UTF_8)), "empty-result");
        assertTrue(backend.metadataRecordExists("empty-result"));
        assertEquals(new DataIdentifier("empty-result"), backend.getMetadataRecord("empty-result").getIdentifier());

        try {
            backend.addMetadataRecord(new File("does-not-exist-" + System.nanoTime()), "missing-file");
            fail("Expected missing file to fail");
        } catch (DataStoreException e) {
            assertTrue(e.getMessage().contains("Failed to add metadata from file: missing-file"));
        }

        backend.close();
    }

    @Test
    public void testDeleteMetadataRecordReturnsFalseWhenFileDeleteFails() throws Exception {
        RecordingIpfsClient client = new RecordingIpfsClient();
        IPFSBackend backend = new IPFSBackend(endpoint -> client);
        backend.init();

        backend.addMetadataRecord(new ByteArrayInputStream("meta".getBytes(StandardCharsets.UTF_8)), "failing-delete");
        client.fileDeleteFailure = new RuntimeException("rm failed");

        assertFalse(backend.deleteMetadataRecord("failing-delete"));
        assertTrue(backend.metadataRecordExists("failing-delete"));

        backend.close();
    }

    @Test
    public void testDeleteAllMetadataRecordsSwallowsDeleteFailures() throws Exception {
        RecordingIpfsClient client = new RecordingIpfsClient();
        IPFSBackend backend = new IPFSBackend(endpoint -> client);
        backend.init();

        backend.addMetadataRecord(new ByteArrayInputStream("one".getBytes(StandardCharsets.UTF_8)), "pref-one");
        backend.addMetadataRecord(new ByteArrayInputStream("two".getBytes(StandardCharsets.UTF_8)), "pref-two");
        client.fileDeleteFailure = new RuntimeException("rm failed");

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

        client.fileWriteFailure = new RuntimeException("write-failed");
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
    public void testCidMappingsMetadataAndReferenceKeySurviveRestart() throws Exception {
        RecordingIpfsClient client = new RecordingIpfsClient();
        client.addResults.add(new MerkleNode(CID_ONE));
        client.blockStats.put(CID_ONE, Map.of("Size", 5));
        client.catResults.put(CID_ONE, "hello".getBytes(StandardCharsets.UTF_8));

        IPFSBackend firstBackend = new IPFSBackend(endpoint -> client);
        firstBackend.init();

        File tempFile = File.createTempFile("oak-ipfs-persist", ".bin");
        Files.writeString(tempFile.toPath(), "hello");
        DataIdentifier identifier = new DataIdentifier("blob-persist");

        firstBackend.write(identifier, tempFile);
        firstBackend.addMetadataRecord(new ByteArrayInputStream("meta".getBytes(StandardCharsets.UTF_8)), "meta-persist");
        byte[] firstReferenceKey = firstBackend.getOrCreateReferenceKey();
        firstBackend.close();

        IPFSBackend secondBackend = new IPFSBackend(endpoint -> client);
        secondBackend.init();

        assertEquals(CID_ONE, secondBackend.getCID(identifier));
        assertTrue(secondBackend.exists(identifier));
        assertEquals(Map.of("blob-persist", CID_ONE), secondBackend.getAllCIDMappings());

        Iterator<DataIdentifier> identifiers = secondBackend.getAllIdentifiers();
        assertTrue(identifiers.hasNext());
        assertEquals(identifier, identifiers.next());
        assertFalse(identifiers.hasNext());

        DataRecord metadataRecord = secondBackend.getMetadataRecord("meta-persist");
        assertNotNull(metadataRecord);
        assertEquals(new DataIdentifier("meta-persist"), metadataRecord.getIdentifier());
        try (InputStream metadataStream = metadataRecord.getStream()) {
            assertArrayEquals("meta".getBytes(StandardCharsets.UTF_8), metadataStream.readAllBytes());
        }

        assertArrayEquals(firstReferenceKey, secondBackend.getOrCreateReferenceKey());
        tempFile.delete();
        secondBackend.close();
    }

    @Test
    public void testRecordLengthReturnsNegativeOneWhenCidWasEvicted() throws Exception {
        RecordingIpfsClient client = new RecordingIpfsClient();
        client.addResults.add(new MerkleNode(CID_ONE));
        client.blockStats.put(CID_ONE, Map.of("Size", 5));
        IPFSBackend backend = new IPFSBackend(endpoint -> client);
        backend.init();

        File tempFile = File.createTempFile("oak-ipfs-evicted", ".bin");
        Files.writeString(tempFile.toPath(), "hello");
        DataIdentifier id = new DataIdentifier("blob-evicted");
        backend.write(id, tempFile);

        DataRecord record = backend.getRecord(id);
        backend.deleteRecord(id);

        assertEquals(-1L, record.getLength());

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
        private final Map<String, byte[]> files = new LinkedHashMap<>();
        private final Set<String> directories = new HashSet<>();

        private RuntimeException versionFailure;
        private RuntimeException pinAddFailure;
        private RuntimeException pinRemoveFailure;
        private RuntimeException catFailure;
        private RuntimeException blockStatFailure;
        private RuntimeException fileWriteFailure;
        private RuntimeException fileDeleteFailure;
        private RuntimeException fileReadFailure;
        private RuntimeException fileSizeFailure;
        private RuntimeException fileListFailure;
        private RuntimeException directoryFailure;

        private RecordingIpfsClient() {
            directories.add("/");
        }

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

        @Override
        public void ensureDirectory(String path) {
            if (directoryFailure != null) {
                throw directoryFailure;
            }
            String normalized = normalize(path);
            createDirectory(normalized);
        }

        @Override
        public void writeFile(String path, byte[] data) throws Exception {
            if (fileWriteFailure != null) {
                throw fileWriteFailure;
            }
            String normalized = normalize(path);
            createDirectory(parent(normalized));
            files.put(normalized, data.clone());
        }

        @Override
        public byte[] readFile(String path) throws Exception {
            if (fileReadFailure != null) {
                throw fileReadFailure;
            }
            byte[] data = files.get(normalize(path));
            if (data == null) {
                throw new IOException("missing file");
            }
            return data.clone();
        }

        @Override
        public boolean fileExists(String path) {
            String normalized = normalize(path);
            return files.containsKey(normalized) || directories.contains(normalized);
        }

        @Override
        public List<String> listFiles(String path) throws Exception {
            if (fileListFailure != null) {
                throw fileListFailure;
            }
            String normalized = normalize(path);
            String prefix = normalized.endsWith("/") ? normalized : normalized + "/";
            List<String> names = new ArrayList<>();
            for (String filePath : files.keySet()) {
                if (!filePath.startsWith(prefix)) {
                    continue;
                }
                String remainder = filePath.substring(prefix.length());
                if (!remainder.isEmpty() && !remainder.contains("/")) {
                    names.add(remainder);
                }
            }
            Collections.sort(names);
            return names;
        }

        @Override
        public void deleteFile(String path) throws Exception {
            if (fileDeleteFailure != null) {
                throw fileDeleteFailure;
            }
            files.remove(normalize(path));
        }

        @Override
        public long fileSize(String path) throws Exception {
            if (fileSizeFailure != null) {
                throw fileSizeFailure;
            }
            byte[] data = files.get(normalize(path));
            if (data == null) {
                throw new IOException("missing file");
            }
            return data.length;
        }

        private void createDirectory(String path) {
            String normalized = normalize(path);
            if ("/".equals(normalized)) {
                directories.add(normalized);
                return;
            }
            String current = "";
            for (String part : normalized.substring(1).split("/")) {
                current = current + "/" + part;
                directories.add(current);
            }
        }

        private static String parent(String path) {
            int separator = path.lastIndexOf('/');
            if (separator <= 0) {
                return "/";
            }
            return path.substring(0, separator);
        }

        private static String normalize(String path) {
            if (path == null || path.isEmpty()) {
                return "/";
            }
            if ("/".equals(path)) {
                return path;
            }
            String normalized = path.startsWith("/") ? path : "/" + path;
            while (normalized.endsWith("/") && normalized.length() > 1) {
                normalized = normalized.substring(0, normalized.length() - 1);
            }
            return normalized;
        }
    }
}
