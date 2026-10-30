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

import org.apache.jackrabbit.oak.spi.blob.data.DataIdentifier;
import org.apache.jackrabbit.oak.spi.blob.data.DataRecord;
import org.apache.jackrabbit.oak.spi.blob.data.DataStoreException;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HashMap;
import java.util.HexFormat;
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
        IPFSBackend backend = new IPFSBackend(settings -> {
            endpointRef.set(settings.endpoint());
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
        IPFSBackend backend = new IPFSBackend(settings -> {
            endpointRef.set(settings.endpoint());
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
        client.addResults.add(CID_ONE);
        byte[] content = "hello-ipfs".getBytes(StandardCharsets.UTF_8);
        client.catResults.put(CID_ONE, content);
        IPFSBackend backend = new IPFSBackend(endpoint -> client);
        backend.setIpfsApiEndpoint("/ip4/10.0.0.9/tcp/5001");
        backend.init();

        File tempFile = File.createTempFile("oak-ipfs", ".bin");
        Files.write(tempFile.toPath(), content);
        DataIdentifier id = new DataIdentifier("blob-1");

        long before = System.currentTimeMillis();
        backend.write(id, tempFile);
        long after = System.currentTimeMillis();

        assertEquals(CID_ONE, backend.getCID(id));
        assertEquals(Map.of("blob-1", CID_ONE), backend.getAllCIDMappings());
        assertEquals(1, client.addCalls);
        assertEquals(List.of(CID_ONE + "@/oak/ipfs/content/YmxvYi0x"), client.linkCidCalls);
        assertTrue(backend.exists(id));

        try (InputStream stream = backend.read(id)) {
            assertArrayEquals(content, stream.readAllBytes());
        }

        DataRecord record = backend.getRecord(id);
        assertEquals(content.length, record.getLength());
        long lastModified = record.getLastModified();
        assertTrue(lastModified >= before && lastModified <= after);
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
        assertTrue(client.listFiles("/oak/ipfs/blob-attrs").isEmpty());
        assertTrue(client.listFiles("/oak/ipfs/content").isEmpty());
        tempFile.delete();
        backend.close();
    }

    @Test
    public void testLengthIsTheFileLengthNotTheRootBlockSize() throws Exception {
        RecordingIpfsClient client = new RecordingIpfsClient();
        client.addResults.add(CID_ONE);
        IPFSBackend backend = new IPFSBackend(endpoint -> client);
        backend.init();

        byte[] content = new byte[762_228];
        File tempFile = File.createTempFile("oak-ipfs-chunked", ".bin");
        Files.write(tempFile.toPath(), content);
        DataIdentifier id = new DataIdentifier("blob-chunked");
        backend.write(id, tempFile);

        IPFSBackend restarted = new IPFSBackend(endpoint -> client);
        restarted.init();
        assertEquals(content.length, restarted.getRecord(id).getLength());

        tempFile.delete();
        backend.close();
        restarted.close();
    }

    @Test
    public void testRewriteOnlyTouchesLastModifiedAndRejectsLengthCollisions() throws Exception {
        RecordingIpfsClient client = new RecordingIpfsClient();
        client.addResults.add(CID_ONE);
        IPFSBackend backend = new IPFSBackend(endpoint -> client);
        backend.init();

        File tempFile = File.createTempFile("oak-ipfs-touch", ".bin");
        Files.writeString(tempFile.toPath(), "hello");
        DataIdentifier id = new DataIdentifier("blob-touch");
        backend.write(id, tempFile);
        long firstWrite = backend.getRecord(id).getLastModified();

        Thread.sleep(5);
        backend.write(id, tempFile);

        assertEquals(1, client.addCalls);
        assertTrue(backend.getRecord(id).getLastModified() > firstWrite);

        Files.writeString(tempFile.toPath(), "hello, longer");
        try {
            backend.write(id, tempFile);
            fail("Expected a length collision");
        } catch (DataStoreException e) {
            assertTrue(e.getMessage().contains("Collision"));
        }

        tempFile.delete();
        backend.close();
    }

    @Test
    public void testLegacyMappingWithoutAttributesUsesFileSizeAndLooksNew() throws Exception {
        RecordingIpfsClient client = new RecordingIpfsClient();
        byte[] content = "legacy-content".getBytes(StandardCharsets.UTF_8);
        client.catResults.put(CID_ONE, content);
        DataIdentifier id = new DataIdentifier("blob-legacy");
        client.writeFile("/oak/ipfs/blob-index/YmxvYi1sZWdhY3k", CID_ONE.getBytes(StandardCharsets.UTF_8));
        IPFSBackend backend = new IPFSBackend(endpoint -> client);
        backend.init();

        long before = System.currentTimeMillis();
        DataRecord record = backend.getRecord(id);

        assertEquals(content.length, record.getLength());
        long lastModified = record.getLastModified();
        assertTrue(lastModified >= before);

        Thread.sleep(5);
        assertEquals(lastModified, backend.getRecord(id).getLastModified());
        backend.close();
    }

    @Test
    public void testUnreadableAttributesAreTreatedAsLegacy() throws Exception {
        RecordingIpfsClient client = new RecordingIpfsClient();
        client.addResults.add(CID_ONE);
        byte[] content = "hello".getBytes(StandardCharsets.UTF_8);
        client.catResults.put(CID_ONE, content);
        IPFSBackend backend = new IPFSBackend(endpoint -> client);
        backend.init();
        File tempFile = File.createTempFile("oak-ipfs-bad-attrs", ".bin");
        Files.write(tempFile.toPath(), content);
        DataIdentifier id = new DataIdentifier("blob-bad-attrs");
        backend.write(id, tempFile);
        String attrsPath = "/oak/ipfs/blob-attrs/YmxvYi1iYWQtYXR0cnM";
        client.writeFile(attrsPath, "length=oops\n".getBytes(StandardCharsets.UTF_8));

        backend.write(id, tempFile);
        assertEquals(1, client.addCalls);

        client.writeFile(attrsPath, "length=oops\n".getBytes(StandardCharsets.UTF_8));
        assertEquals(content.length, backend.getRecord(id).getLength());

        tempFile.delete();
        backend.close();
    }

    @Test
    public void testEnumerationFailsInsteadOfReportingUnknownLengths() throws Exception {
        RecordingIpfsClient client = new RecordingIpfsClient();
        client.addResults.add(CID_ONE);
        IPFSBackend backend = new IPFSBackend(endpoint -> client);
        backend.init();
        File tempFile = File.createTempFile("oak-ipfs-enumerate", ".bin");
        Files.writeString(tempFile.toPath(), "hello");
        backend.write(new DataIdentifier("blob-enumerate"), tempFile);
        client.fileReadFailure = new RuntimeException("connection refused");

        Iterator<DataRecord> records = backend.getAllRecords();
        try {
            records.next();
            fail("Expected enumeration to fail when attributes cannot be read");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("Cannot enumerate"));
        }

        tempFile.delete();
        backend.close();
    }

    @Test
    public void testMetadataFailuresAreNotReportedAsAbsence() throws Exception {
        RecordingIpfsClient client = new RecordingIpfsClient();
        IPFSBackend backend = new IPFSBackend(endpoint -> client);
        backend.init();
        byte[] key = backend.getOrCreateReferenceKey();

        IPFSBackend restarted = new IPFSBackend(endpoint -> client);
        restarted.init();
        client.fileReadFailure = new RuntimeException("connection refused");
        try {
            restarted.getOrCreateReferenceKey();
            fail("Expected an unreachable node to fail rather than create a new reference key");
        } catch (DataStoreException e) {
            assertTrue(e.getMessage().contains("Failed to read metadata"));
        }
        client.fileReadFailure = null;
        assertArrayEquals(key, restarted.getOrCreateReferenceKey());

        client.fileListFailure = new RuntimeException("connection refused");
        try {
            restarted.getAllMetadataRecords("repository");
            fail("Expected a failed listing to fail rather than report no records");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("Failed to list metadata records"));
        }

        backend.close();
        restarted.close();
    }

    @Test
    public void testExistsThrowsWhenTheMappingCannotBeRead() throws Exception {
        RecordingIpfsClient client = new RecordingIpfsClient();
        IPFSBackend backend = new IPFSBackend(endpoint -> client);
        backend.init();
        client.fileReadFailure = new RuntimeException("connection refused");

        try {
            backend.exists(new DataIdentifier("blob-unreachable"));
            fail("Expected an unreachable node to fail rather than report absence");
        } catch (DataStoreException e) {
            assertTrue(e.getMessage().contains("Failed to read CID mapping"));
        }

        backend.close();
    }

    @Test
    public void testReadVerifiesContentAgainstSha256Identifier() throws Exception {
        RecordingIpfsClient client = new RecordingIpfsClient();
        client.addResults.add(CID_ONE);
        byte[] content = "verified".getBytes(StandardCharsets.UTF_8);
        DataIdentifier id = new DataIdentifier(
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)));
        IPFSBackend backend = new IPFSBackend(endpoint -> client);
        backend.init();

        File tempFile = File.createTempFile("oak-ipfs-verified", ".bin");
        Files.write(tempFile.toPath(), content);
        backend.write(id, tempFile);

        client.catResults.put(CID_ONE, content);
        try (InputStream stream = backend.read(id)) {
            assertArrayEquals(content, stream.readAllBytes());
        }

        client.catResults.put(CID_ONE, "verif".getBytes(StandardCharsets.UTF_8));
        try (InputStream stream = backend.read(id)) {
            try {
                stream.readAllBytes();
                fail("Expected truncated content to fail verification");
            } catch (IOException e) {
                assertTrue(e.getMessage().contains("hashes to"));
            }
            try {
                stream.read();
                fail("Expected the verification failure to repeat on later reads");
            } catch (IOException e) {
                assertTrue(e.getMessage().contains("hashes to"));
            }
        }

        tempFile.delete();
        backend.close();
    }

    @Test
    public void testFailedMappingWriteUnpinsAndLeavesNoIndex() throws Exception {
        RecordingIpfsClient client = new RecordingIpfsClient();
        client.addResults.add(CID_ONE);
        IPFSBackend backend = new IPFSBackend(endpoint -> client);
        backend.init();
        client.fileWriteFailure = new RuntimeException("write failed");

        File tempFile = File.createTempFile("oak-ipfs-cleanup", ".bin");
        Files.writeString(tempFile.toPath(), "hello");
        DataIdentifier id = new DataIdentifier("blob-cleanup");
        try {
            backend.write(id, tempFile);
            fail("Expected the mapping write to fail");
        } catch (DataStoreException e) {
            assertTrue(e.getMessage().contains("IPFS write failed"));
        }

        assertEquals(List.of(CID_ONE), client.pinRemoveCalls);
        client.fileWriteFailure = null;
        assertFalse(backend.exists(id));

        tempFile.delete();
        backend.close();
    }

    @Test
    public void testFailedUnpinKeepsTheMappingSoDeleteCanBeRetried() throws Exception {
        RecordingIpfsClient client = new RecordingIpfsClient();
        client.addResults.add(CID_ONE);
        IPFSBackend backend = new IPFSBackend(endpoint -> client);
        backend.init();

        File tempFile = File.createTempFile("oak-ipfs-retry", ".bin");
        Files.writeString(tempFile.toPath(), "hello");
        DataIdentifier id = new DataIdentifier("blob-retry");
        backend.write(id, tempFile);

        client.pinRemoveFailure = new RuntimeException("daemon busy");
        try {
            backend.deleteRecord(id);
            fail("Expected the unpin to fail");
        } catch (DataStoreException e) {
            assertTrue(e.getMessage().contains("IPFS delete failed"));
        }
        assertTrue(backend.exists(id));

        client.pinRemoveFailure = null;
        backend.deleteRecord(id);
        assertFalse(backend.exists(id));
        assertEquals(List.of(CID_ONE), client.pinRemoveCalls);

        tempFile.delete();
        backend.close();
    }

    @Test
    public void testMetadataRecordsKeepTheirWriteTimeAcrossRestart() throws Exception {
        RecordingIpfsClient client = new RecordingIpfsClient();
        IPFSBackend backend = new IPFSBackend(endpoint -> client);
        backend.init();

        long before = System.currentTimeMillis();
        backend.addMetadataRecord(new ByteArrayInputStream("refs".getBytes(StandardCharsets.UTF_8)), "references-1");
        long after = System.currentTimeMillis();
        long lastModified = backend.getMetadataRecord("references-1").getLastModified();
        assertTrue(lastModified >= before && lastModified <= after);
        assertEquals(4, backend.getMetadataRecord("references-1").getLength());

        Thread.sleep(5);
        IPFSBackend restarted = new IPFSBackend(endpoint -> client);
        restarted.init();
        assertEquals(lastModified, restarted.getAllMetadataRecords("references").get(0).getLastModified());

        assertTrue(restarted.deleteMetadataRecord("references-1"));
        assertTrue(client.listFiles("/oak/ipfs/metadata-attrs").isEmpty());

        backend.close();
        restarted.close();
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
        assertEquals(0, client.addCalls);

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
        client.addResults.add(CID_ONE);
        client.addResults.add(CID_TWO);
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
            fail("Expected an add without a CID to fail");
        } catch (DataStoreException e) {
            assertTrue(e.getMessage().contains("Kubo add returned no CID"));
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
        client.addResults.add(CID_ONE);
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
        client.addResults.add(CID_ONE);
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
        client.addResults.add(CID_ONE);
        IPFSBackend backend = new IPFSBackend(endpoint -> client);
        backend.init();

        File tempFile = File.createTempFile("oak-ipfs-length", ".bin");
        Files.writeString(tempFile.toPath(), "hello");
        DataIdentifier id = new DataIdentifier("blob-length");
        backend.write(id, tempFile);

        DataRecord record = backend.getRecord(id);
        client.fileReadFailure = new RuntimeException("read failed");

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
            new IPFSBackend(settings -> {
                throw new RuntimeException("connect failed");
            }).init();
            fail("Expected init failure");
        } catch (DataStoreException e) {
            assertTrue(e.getMessage().contains("Failed to initialize IPFS backend"));
        }

        RecordingIpfsClient client = new RecordingIpfsClient();
        client.addResults.add(CID_ONE);
        client.catFailure = new RuntimeException("cat failed");
        client.pinRemoveFailure = new RuntimeException("rm failed");
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

    @Test
    public void testDeleteRecordIgnoresMissingDirectPinWhenContentIsRetainedViaFilesLink() throws Exception {
        RecordingIpfsClient client = new RecordingIpfsClient();
        client.addResults.add(CID_ONE);
        client.pinRemoveFailure = new RuntimeException("path is not pinned");
        IPFSBackend backend = new IPFSBackend(endpoint -> client);
        backend.init();

        File tempFile = File.createTempFile("oak-ipfs-indirect", ".bin");
        Files.writeString(tempFile.toPath(), "hello");
        DataIdentifier id = new DataIdentifier("blob-indirect");

        backend.write(id, tempFile);
        backend.deleteRecord(id);

        assertFalse(backend.exists(id));
        assertNull(backend.getCID(id));
        tempFile.delete();
        backend.close();
    }

    private static final class RecordingIpfsClient implements IpfsClient {

        private final Queue<String> addResults = new ArrayDeque<>();
        private final Map<String, byte[]> catResults = new HashMap<>();
        private int addCalls;
        private final List<String> pinRemoveCalls = new ArrayList<>();
        private final List<String> linkCidCalls = new ArrayList<>();
        private final Map<String, byte[]> files = new LinkedHashMap<>();
        private final Set<String> directories = new HashSet<>();

        private RuntimeException versionFailure;
        private RuntimeException pinRemoveFailure;
        private RuntimeException catFailure;
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
        public String add(File file) throws IOException {
            addCalls++;
            if (addResults.isEmpty()) {
                throw new IOException("Kubo add returned no CID");
            }
            return addResults.remove();
        }

        @Override
        public void pinRemove(String cid) {
            if (pinRemoveFailure != null) {
                throw pinRemoveFailure;
            }
            pinRemoveCalls.add(cid);
        }

        @Override
        public InputStream cat(String cid) throws IOException {
            if (catFailure != null) {
                throw catFailure;
            }
            byte[] content = catResults.get(cid);
            if (content == null) {
                throw new IOException("block not found");
            }
            return new ByteArrayInputStream(content);
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
        public void linkCid(String cid, String path) throws Exception {
            if (fileWriteFailure != null) {
                throw fileWriteFailure;
            }
            String normalized = normalize(path);
            createDirectory(parent(normalized));
            files.put(normalized, cid.getBytes(StandardCharsets.UTF_8));
            linkCidCalls.add(cid + "@" + normalized);
        }

        @Override
        public byte[] readFile(String path) throws Exception {
            if (fileReadFailure != null) {
                throw fileReadFailure;
            }
            byte[] data = files.get(normalize(path));
            return data == null ? null : data.clone();
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
            byte[] data = path.startsWith("/ipfs/")
                ? catResults.get(path.substring("/ipfs/".length()))
                : files.get(normalize(path));
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
