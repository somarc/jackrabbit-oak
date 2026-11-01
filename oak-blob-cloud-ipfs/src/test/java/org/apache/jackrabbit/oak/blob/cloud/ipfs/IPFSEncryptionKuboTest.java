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

import org.apache.jackrabbit.oak.plugins.blob.datastore.DataStoreBlobStore;
import org.apache.jackrabbit.oak.spi.blob.BlobOptions;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Against a real Kubo node: an encrypted binary is stored as ciphertext that
 * does not contain the plaintext, and only a repository with the same keys can
 * read it back, including one that only knows its CID from replicated state.
 */
public class IPFSEncryptionKuboTest {

    @ClassRule
    public static final KuboDockerRule KUBO = new KuboDockerRule();

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private final Map<String, CidResolver.Resolution> replicatedIndex = new ConcurrentHashMap<>();

    @Test
    public void testOnlyRepositoriesWithTheKeysReadEncryptedBinaries() throws Exception {
        String keys = "k1;k1:" + Base64.getEncoder().encodeToString(bytes(32, 1));
        byte[] content = bytes(1024 * 1024 + 17, 2);

        DataStoreBlobStore writer = blobStore(keys);
        String blobId = writer.writeBlob(new ByteArrayInputStream(content),
            new BlobOptions().setUpload(BlobOptions.UploadType.SYNCHRONOUS));
        String cid = ((IPFSDataStore) writer.getDataStore()).getCID(blobId);
        replicatedIndex.put(blobId.substring(0, blobId.indexOf('#')),
            new CidResolver.Resolution(cid, content.length));

        byte[] stored = cat(cid);
        assertArrayEquals(BlobEncryption.MAGIC, Arrays.copyOf(stored, 4));
        assertFalse("the plaintext must not reach IPFS",
            indexOf(stored, Arrays.copyOfRange(content, 4096, 4096 + 64)) >= 0);
        assertEquals(content.length, writer.getBlobLength(blobId));

        DataStoreBlobStore reader = blobStore(keys);
        try (InputStream stream = reader.getInputStream(blobId)) {
            assertArrayEquals(content, stream.readAllBytes());
        }

        for (String otherKeys : new String[] {"k1;k1:" + Base64.getEncoder().encodeToString(bytes(32, 9)), null}) {
            DataStoreBlobStore other = blobStore(otherKeys);
            try (InputStream stream = other.getInputStream(blobId)) {
                stream.readAllBytes();
                fail("Expected the read to fail without the right key");
            } catch (IOException expected) {
                assertTrue(String.valueOf(expected.getMessage()), expected.getMessage() != null);
            }
            other.close();
        }
        reader.close();
        writer.close();
    }

    private DataStoreBlobStore blobStore(String keys) throws Exception {
        Properties properties = new Properties();
        properties.setProperty(IpfsClientSettings.ENDPOINT, KUBO.apiEndpoint());
        if (keys != null) {
            properties.setProperty(IPFSDataStore.ENCRYPTION_KEYS, keys);
        }
        IPFSDataStore dataStore = new IPFSDataStore();
        dataStore.setProperties(properties);
        dataStore.setIpfsFilesRoot("/oak/encrypted-" + UUID.randomUUID());
        dataStore.setCacheSize(0);
        dataStore.setCidResolver(replicatedIndex::get);
        dataStore.init(folder.newFolder().getAbsolutePath());
        return new DataStoreBlobStore(dataStore);
    }

    private static byte[] cat(String cid) throws IOException, InterruptedException {
        URI api = IpfsClientSettings.of(KUBO.apiEndpoint()).apiBase();
        HttpResponse<byte[]> response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(api.resolve("cat?arg=" + cid)).POST(HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, response.statusCode());
        return response.body();
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static byte[] bytes(int length, int seed) {
        byte[] bytes = new byte[length];
        new Random(seed).nextBytes(bytes);
        return bytes;
    }
}
