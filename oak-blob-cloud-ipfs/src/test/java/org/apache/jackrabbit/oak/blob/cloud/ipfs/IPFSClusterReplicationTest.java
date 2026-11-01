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
import org.apache.jackrabbit.oak.spi.blob.data.DataIdentifier;
import org.junit.ClassRule;
import org.junit.FixMethodOrder;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runners.MethodSorters;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
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
 * Three validators, each with its own Kubo node and IPFS Cluster peer in a
 * private swarm. Writes are acknowledged only once the replication minimum of
 * peers holds them, so the node that stored a binary can be lost.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class IPFSClusterReplicationTest {

    @ClassRule
    public static final IpfsClusterDockerRule NODES = new IpfsClusterDockerRule(3);

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private final Map<String, CidResolver.Resolution> replicatedIndex = new ConcurrentHashMap<>();

    @Test
    public void testAReplicatedWriteSurvivesLosingTheNodeThatStoredIt() throws Exception {
        DataStoreBlobStore writer = blobStore(0, 2, 3);
        byte[] content = randomBytes(2 * 1024 * 1024 + 3);
        String blobId = write(writer, content);
        IpfsClusterClient cluster = cluster(0, 2, 3);
        String cid = ((IPFSDataStore) writer.getDataStore()).getCID(blobId);
        long pinned = pinnedPeers(cluster, cid);
        assertTrue("pinned on " + pinned, pinned >= 2);

        NODES.stopKubo(0);
        try {
            // Only pinned blocks survive a repository GC, so the read below is served by cluster replicas.
            for (int node = 1; node < NODES.size(); node++) {
                gc(node);
            }
            DataStoreBlobStore reader = blobStore(1, 2, 3);
            try (InputStream stream = reader.getInputStream(blobId)) {
                assertArrayEquals(content, stream.readAllBytes());
            }
            reader.close();
        } finally {
            NODES.startKubo(0);
        }
        writer.close();
    }

    @Test
    public void testAWriteIsRefusedWhenTooFewPeersCanHoldIt() throws Exception {
        NODES.stopKubo(2);
        try {
            DataStoreBlobStore writer = blobStore(0, 3, 3);
            try {
                write(writer, randomBytes(512 * 1024));
                fail("Expected the write to be refused without three replicas");
            } catch (IOException e) {
                assertTrue(String.valueOf(e.getCause()), causeChain(e).contains("IPFS Cluster"));
            }
            writer.close();
        } finally {
            NODES.startKubo(2);
        }
    }

    @Test
    public void testDeleteKeepsTheClusterPinByDefault() throws Exception {
        DataStoreBlobStore writer = blobStore(1, 2, 3, false);
        String blobId = write(writer, randomBytes(300 * 1024));
        IPFSDataStore dataStore = (IPFSDataStore) writer.getDataStore();
        String cid = dataStore.getCID(blobId);

        dataStore.deleteRecord(new DataIdentifier(blobId.substring(0, blobId.indexOf('#'))));

        assertTrue(pinnedPeers(cluster(1, 2, 3), cid) >= 2);
        writer.close();
    }

    @Test
    public void testDeleteUnpinsAcrossADedicatedCluster() throws Exception {
        DataStoreBlobStore writer = blobStore(1, 2, 3, true);
        String blobId = write(writer, randomBytes(300 * 1024));
        IPFSDataStore dataStore = (IPFSDataStore) writer.getDataStore();
        String cid = dataStore.getCID(blobId);

        dataStore.deleteRecord(new DataIdentifier(blobId.substring(0, blobId.indexOf('#'))));

        IpfsClusterClient cluster = cluster(1, 2, 3);
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (pinnedPeers(cluster, cid) > 0 && System.nanoTime() < deadline) {
            Thread.sleep(250);
        }
        assertEquals(0, pinnedPeers(cluster, cid));
        assertFalse(localPins(1).contains(cid));
        writer.close();
    }

    private String write(DataStoreBlobStore blobStore, byte[] content) throws IOException {
        String blobId = blobStore.writeBlob(new ByteArrayInputStream(content),
            new BlobOptions().setUpload(BlobOptions.UploadType.SYNCHRONOUS));
        String identifier = blobId.substring(0, blobId.indexOf('#'));
        replicatedIndex.put(identifier, new CidResolver.Resolution(
            ((IPFSDataStore) blobStore.getDataStore()).getCID(blobId), content.length));
        return blobId;
    }

    private DataStoreBlobStore blobStore(int node, int replicationMin, int replicationMax) throws Exception {
        return blobStore(node, replicationMin, replicationMax, false);
    }

    private DataStoreBlobStore blobStore(int node, int replicationMin, int replicationMax, boolean unpinOnDelete)
            throws Exception {
        Properties properties = new Properties();
        properties.setProperty(IpfsClusterSettings.UNPIN_ON_DELETE, String.valueOf(unpinOnDelete));
        properties.setProperty(IpfsClientSettings.ENDPOINT, NODES.kuboRpcMultiaddr(node));
        properties.setProperty(IpfsClusterSettings.ENDPOINT, NODES.clusterApiUrl(node).toString());
        properties.setProperty(IpfsClusterSettings.REPLICATION_MIN, String.valueOf(replicationMin));
        properties.setProperty(IpfsClusterSettings.REPLICATION_MAX, String.valueOf(replicationMax));
        properties.setProperty(IpfsClusterSettings.PIN_TIMEOUT_MILLIS, "30000");
        IPFSDataStore dataStore = new IPFSDataStore();
        dataStore.setProperties(properties);
        dataStore.setIpfsFilesRoot("/oak/validator-" + node + "-" + UUID.randomUUID());
        dataStore.setCacheSize(0);
        dataStore.setCidResolver(replicatedIndex::get);
        dataStore.init(folder.newFolder().getAbsolutePath());
        return new DataStoreBlobStore(dataStore);
    }

    private IpfsClusterClient cluster(int node, int replicationMin, int replicationMax) {
        return new IpfsClusterClient(new IpfsClusterSettings(NODES.clusterApiUrl(node).toString(), null,
            replicationMin, replicationMax, Duration.ofSeconds(30), false), IpfsClientSettings.of(null));
    }

    private static long pinnedPeers(IpfsClusterClient cluster, String cid) throws IOException {
        return cluster.statuses(cid).stream().filter(peer -> "pinned".equals(peer.status())).count();
    }

    private static void gc(int node) throws IOException, InterruptedException {
        kubo(node, "repo/gc", "");
    }

    private static String localPins(int node) throws IOException, InterruptedException {
        return kubo(node, "pin/ls", "type=recursive");
    }

    private static String kubo(int node, String command, String query) throws IOException, InterruptedException {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(NODES.kuboRpcUrl(node).resolve("/api/v0/" + command + (query.isEmpty() ? "" : "?" + query)))
                .POST(HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException(command + " failed: " + response.body());
        }
        return response.body();
    }

    private static String causeChain(Throwable e) {
        StringBuilder messages = new StringBuilder();
        for (Throwable t = e; t != null; t = t.getCause()) {
            messages.append(t.getMessage()).append(" <- ");
        }
        return messages.toString();
    }

    private static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        new Random().nextBytes(bytes);
        return bytes;
    }
}
