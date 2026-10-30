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

import org.apache.jackrabbit.oak.commons.json.JsonObject;
import org.apache.jackrabbit.oak.commons.json.JsopTokenizer;
import org.apache.jackrabbit.oak.plugins.blob.MarkSweepGarbageCollector;
import org.apache.jackrabbit.oak.plugins.blob.datastore.DataStoreBlobStore;
import org.apache.jackrabbit.oak.plugins.blob.datastore.SharedDataStoreUtils.SharedStoreRecordType;
import org.apache.jackrabbit.oak.spi.blob.data.DataIdentifier;
import org.apache.jackrabbit.oak.spi.blob.data.DataRecord;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Runs the IPFS backend and DataStore against a real Kubo daemon, so the
 * assertions cover Kubo's actual semantics instead of a fake's.
 */
public class IPFSKuboTest {

    @ClassRule
    public static final KuboDockerRule KUBO = new KuboDockerRule();

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private String filesRoot;

    @Before
    public void setUp() {
        filesRoot = "/oak/test-" + UUID.randomUUID();
    }

    @Test
    public void testMultiChunkBlobReportsItsTrueLengthAndSurvivesRestart() throws Exception {
        // Larger than one 256 KiB chunk: Kubo's block/stat of the root reports
        // only the root node here, which is what the length used to come from.
        byte[] content = randomBytes(762_228);
        DataIdentifier id = sha256(content);
        File file = write(content);

        IPFSBackend backend = backend();
        long before = System.currentTimeMillis();
        backend.write(id, file);
        long after = System.currentTimeMillis();
        backend.close();

        IPFSBackend restarted = backend();
        DataRecord record = restarted.getRecord(id);
        assertEquals(content.length, record.getLength());
        assertTrue(record.getLastModified() >= before && record.getLastModified() <= after);
        try (InputStream stream = record.getStream()) {
            assertArrayEquals(content, stream.readAllBytes());
        }

        restarted.deleteRecord(id);
        assertFalse(restarted.exists(id));
        restarted.close();
    }

    @Test
    public void testLegacyMappingFallsBackToTheUnixFsFileSize() throws Exception {
        byte[] content = randomBytes(300_000);
        DataIdentifier id = sha256(content);
        IPFSBackend backend = backend();
        backend.write(id, write(content));

        DefaultIpfsClient client = new DefaultIpfsClient(KUBO.apiEndpoint());
        client.deleteFile(filesRoot + "/blob-attrs/" + encode(id.toString()));

        IPFSBackend restarted = backend();
        assertEquals(content.length, restarted.getRecord(id).getLength());
        backend.close();
        restarted.close();
    }

    @Test
    public void testMissingPathsAreAbsentNotErrors() throws Exception {
        DefaultIpfsClient client = new DefaultIpfsClient(KUBO.apiEndpoint());
        String missing = filesRoot + "/blob-index/missing";

        assertFalse(client.fileExists(missing));
        assertNull(client.readFile(missing));
        client.deleteFile(missing);
        assertFalse(backend().exists(new DataIdentifier("missing")));
    }

    @Test
    public void testCidV1FormOfAStoredCidIsReadable() throws Exception {
        byte[] content = randomBytes(1024);
        IPFSBackend backend = backend();
        DataIdentifier id = sha256(content);
        backend.write(id, write(content));

        String v1 = JsopTokenizer.decodeQuoted(JsonObject.fromJson(
            KUBO.rpc("cid/format?arg=" + backend.getCID(id) + "&v=1&b=base32"), true).getProperties().get("Formatted"));
        assertTrue(v1.startsWith("bafy"));
        try (InputStream stream = new DefaultIpfsClient(KUBO.apiEndpoint()).cat(v1)) {
            assertArrayEquals(content, stream.readAllBytes());
        }
        backend.close();
    }

    @Test
    public void testBlobIdsCarryTheTrueLengthWithoutALocalCache() throws Exception {
        DataStoreBlobStore blobStore = blobStore(0);

        String blobId = blobStore.writeBlob(new ByteArrayInputStream(randomBytes(700_000)));

        assertTrue(blobId.endsWith("#700000"));
        blobStore.close();
    }

    @Test
    public void testGarbageCollectionDeletesOnlyUnreferencedBlobsOutsideTheWindow() throws Exception {
        // With a local cache, as in the validator runtime, blob ids take their
        // length from the cached file while GC enumerates the backend.
        DataStoreBlobStore blobStore = blobStore(64L * 1024 * 1024);
        IPFSDataStore dataStore = (IPFSDataStore) blobStore.getDataStore();
        DefaultIpfsClient client = new DefaultIpfsClient(KUBO.apiEndpoint());
        long twoHoursAgo = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(2);

        List<String> referenced = List.of(
            blobStore.writeBlob(new ByteArrayInputStream(randomBytes(700_000))),
            blobStore.writeBlob(new ByteArrayInputStream(randomBytes(300_000))));
        DataIdentifier oldGarbage = identifier(blobStore.writeBlob(new ByteArrayInputStream(randomBytes(500_000))));
        DataIdentifier freshGarbage = identifier(blobStore.writeBlob(new ByteArrayInputStream(randomBytes(400_000))));
        DataIdentifier legacyGarbage = identifier(blobStore.writeBlob(new ByteArrayInputStream(randomBytes(300_000))));
        backdate(client, identifier(referenced.get(0)), 700_000, twoHoursAgo);
        backdate(client, oldGarbage, 500_000, twoHoursAgo);
        client.deleteFile(filesRoot + "/blob-attrs/" + encode(legacyGarbage.toString()));

        String repositoryId = UUID.randomUUID().toString();
        blobStore.addMetadataRecord(new ByteArrayInputStream(new byte[0]),
            SharedStoreRecordType.REPOSITORY.getNameFromId(repositoryId));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            collector(blobStore, referenced, executor, TimeUnit.HOURS.toMillis(1), repositoryId)
                .collectGarbage(false, true);
        } finally {
            executor.shutdownNow();
        }

        assertFalse(dataStore.exists(oldGarbage));
        assertTrue(dataStore.exists(freshGarbage));
        assertTrue(dataStore.exists(legacyGarbage));
        for (String blobId : referenced) {
            try (InputStream stream = blobStore.getInputStream(blobId)) {
                assertEquals(Long.parseLong(blobId.substring(blobId.indexOf('#') + 1)), stream.readAllBytes().length);
            }
        }
        blobStore.close();
    }

    private void backdate(DefaultIpfsClient client, DataIdentifier id, long length, long lastModified) throws Exception {
        client.writeFile(filesRoot + "/blob-attrs/" + encode(id.toString()),
            ("length=" + length + "\nlastModified=" + lastModified + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private static DataIdentifier identifier(String blobId) {
        return new DataIdentifier(blobId.substring(0, blobId.indexOf('#')));
    }

    private DataStoreBlobStore blobStore(long cacheSize) throws Exception {
        IPFSDataStore dataStore = new IPFSDataStore();
        dataStore.setIpfsApiEndpoint(KUBO.apiEndpoint());
        dataStore.setIpfsFilesRoot(filesRoot);
        dataStore.setCacheSize(cacheSize);
        dataStore.setStagingSplitPercentage(0);
        dataStore.init(folder.newFolder().getAbsolutePath());
        return new DataStoreBlobStore(dataStore);
    }

    private MarkSweepGarbageCollector collector(DataStoreBlobStore blobStore, List<String> referenced,
            ExecutorService executor, long maxLastModifiedInterval, String repositoryId) throws Exception {
        return new MarkSweepGarbageCollector(
            collector -> referenced.forEach(blobId -> collector.addReference(blobId, null)),
            blobStore, executor, folder.newFolder().getAbsolutePath(), 2048,
            maxLastModifiedInterval, repositoryId);
    }

    private IPFSBackend backend() throws Exception {
        IPFSBackend backend = new IPFSBackend();
        backend.setIpfsApiEndpoint(KUBO.apiEndpoint());
        backend.setIpfsFilesRoot(filesRoot);
        backend.init();
        return backend;
    }

    private File write(byte[] content) throws Exception {
        File file = folder.newFile();
        Files.write(file.toPath(), content);
        return file;
    }

    private static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        new Random().nextBytes(bytes);
        return bytes;
    }

    private static DataIdentifier sha256(byte[] content) throws Exception {
        return new DataIdentifier(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)));
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
