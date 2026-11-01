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
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Validators that each run their own Kubo node in a private swarm: a blob
 * stored through one node is readable through another once the other knows
 * its CID from replicated state (here a shared map standing in for the
 * consensus-replicated index).
 */
public class IPFSReplicatedReadTest {

    @ClassRule
    public static final IpfsClusterDockerRule NODES = new IpfsClusterDockerRule(3);

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private final Map<String, CidResolver.Resolution> replicatedIndex = new ConcurrentHashMap<>();

    @Test
    public void testAnyNodeReadsABlobStoredThroughAnother() throws Exception {
        DataStoreBlobStore writer = blobStore(0, true);
        byte[] content = new byte[3 * 1024 * 1024 + 11];
        new Random(7).nextBytes(content);
        String blobId = writer.writeBlob(new ByteArrayInputStream(content),
            new BlobOptions().setUpload(BlobOptions.UploadType.SYNCHRONOUS));
        String identifier = blobId.substring(0, blobId.indexOf('#'));
        IPFSDataStore writerStore = (IPFSDataStore) writer.getDataStore();
        replicatedIndex.put(identifier, new CidResolver.Resolution(writerStore.getCID(blobId), content.length));

        DataStoreBlobStore isolated = blobStore(1, false);
        assertFalse(((IPFSDataStore) isolated.getDataStore()).exists(new DataIdentifier(identifier)));

        for (int node = 1; node < NODES.size(); node++) {
            DataStoreBlobStore reader = blobStore(node, true);
            assertEquals(content.length, reader.getBlobLength(blobId));
            try (InputStream stream = reader.getInputStream(blobId)) {
                assertArrayEquals(content, stream.readAllBytes());
            }
            IPFSDataStore readerStore = (IPFSDataStore) reader.getDataStore();
            assertEquals(writerStore.getCID(blobId), readerStore.getCID(blobId));
            assertTrue(readerStore.getAllCIDMappings().containsKey(identifier));
            reader.close();
        }
        isolated.close();
        writer.close();
    }

    private DataStoreBlobStore blobStore(int node, boolean withResolver) throws Exception {
        IPFSDataStore dataStore = new IPFSDataStore();
        dataStore.setIpfsApiEndpoint(NODES.kuboRpcMultiaddr(node));
        dataStore.setIpfsFilesRoot("/oak/validator-" + node + "-" + UUID.randomUUID());
        dataStore.setCacheSize(0);
        if (withResolver) {
            dataStore.setCidResolver(replicatedIndex::get);
        }
        dataStore.init(folder.newFolder().getAbsolutePath());
        return new DataStoreBlobStore(dataStore);
    }
}
