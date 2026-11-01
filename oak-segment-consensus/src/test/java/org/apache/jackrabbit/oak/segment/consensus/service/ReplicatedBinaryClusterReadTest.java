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
package org.apache.jackrabbit.oak.segment.consensus.service;

import org.apache.jackrabbit.oak.api.Blob;
import org.apache.jackrabbit.oak.api.Type;
import org.apache.jackrabbit.oak.blob.cloud.ipfs.IPFSDataStore;
import org.apache.jackrabbit.oak.blob.cloud.ipfs.IpfsClusterDockerRule;
import org.apache.jackrabbit.oak.plugins.blob.datastore.DataStoreBlobStore;
import org.apache.jackrabbit.oak.plugins.memory.MemoryNodeStore;
import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.apache.jackrabbit.oak.spi.blob.BlobOptions;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Properties;
import java.util.Random;
import java.util.UUID;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * End to end across two validators that each run their own Kubo node and IPFS
 * Cluster peer: the leader stores a binary (acknowledged once two peers hold
 * it), both members apply the same replicated write, the leader's IPFS node is
 * lost, the surviving IPFS nodes collect garbage so that only pinned replicas
 * remain, and the other member still reads the binary through the replicated
 * binary index.
 */
public class ReplicatedBinaryClusterReadTest {

    private static final String WALLET = "0x1234567890abcdef1234567890abcdef12345678";
    private static final String PATH = "/oak-chain/aa/bb/cc/" + WALLET + "/Acme/content/doc-1";

    @ClassRule
    public static final IpfsClusterDockerRule NODES = new IpfsClusterDockerRule(3);

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void testAMemberReadsABinaryStoredThroughAnotherAfterThatNodeIsLost() throws Exception {
        Member leader = member(0);
        Member follower = member(1);
        byte[] content = new byte[1536 * 1024 + 5];
        new Random(42).nextBytes(content);

        String blobId = leader.blobStore.writeBlob(new ByteArrayInputStream(content),
            new BlobOptions().setUpload(BlobOptions.UploadType.SYNCHRONOUS));
        String cid = ((IPFSDataStore) leader.blobStore.getDataStore()).getCID(blobId);
        for (Member member : List.of(leader, follower)) {
            member.service.applyWriteWithAuditMetadata(WALLET, PATH, "file", "m", "0xsig", null, blobId,
                "application/octet-stream", cid,
                MutationAuditMetadata.write(null, null, "p-1", null, null, null, null).withAppliedAt(1_000L));
        }

        NODES.stopKubo(0);
        try {
            for (int i = 1; i < NODES.size(); i++) {
                HttpResponse<String> gc = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(NODES.kuboRpcUrl(i).resolve("/api/v0/repo/gc"))
                        .POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
                assertEquals(gc.body(), 200, gc.statusCode());
            }
            NodeState node = follower.nodeStore.getRoot();
            for (String part : PATH.substring(1).split("/")) {
                node = node.getChildNode(part);
            }
            Blob blob = node.getProperty("jcr:data").getValue(Type.BINARY);
            try (InputStream stream = blob.getNewStream()) {
                assertArrayEquals(content, stream.readAllBytes());
            }
        } finally {
            NODES.startKubo(0);
        }
        leader.blobStore.close();
        follower.blobStore.close();
    }

    private Member member(int node) throws Exception {
        Properties properties = new Properties();
        properties.setProperty("ipfsApiEndpoint", NODES.kuboRpcMultiaddr(node));
        properties.setProperty("ipfsFilesRoot", "/oak/validator-" + node + "-" + UUID.randomUUID());
        properties.setProperty("ipfsClusterEndpoint", NODES.clusterApiUrl(node).toString());
        properties.setProperty("ipfsClusterReplicationMin", "2");
        properties.setProperty("ipfsClusterReplicationMax", "3");
        IPFSDataStore dataStore = new IPFSDataStore();
        dataStore.setProperties(properties);
        dataStore.setCacheSize(0);
        dataStore.init(folder.newFolder().getAbsolutePath());
        MemoryNodeStore nodeStore = new MemoryNodeStore();
        dataStore.setCidResolver(new ReplicatedBinaryIndex(nodeStore));
        DataStoreBlobStore blobStore = new DataStoreBlobStore(dataStore);
        FileStore fileStore = mock(FileStore.class, RETURNS_DEEP_STUBS);
        when(fileStore.getHead().getRecordId().toString()).thenReturn("prev-head");
        when(fileStore.getHead().getRecordId().toString10()).thenReturn("new-head");
        return new Member(nodeStore, blobStore,
            new WriteApplicationService(fileStore, nodeStore, blobStore, mock(FileStoreFlushService.class)));
    }

    private static final class Member {
        private final MemoryNodeStore nodeStore;
        private final DataStoreBlobStore blobStore;
        private final WriteApplicationService service;

        private Member(MemoryNodeStore nodeStore, DataStoreBlobStore blobStore, WriteApplicationService service) {
            this.nodeStore = nodeStore;
            this.blobStore = blobStore;
            this.service = service;
        }
    }
}
