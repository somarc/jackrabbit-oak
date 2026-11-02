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
package org.apache.jackrabbit.oak.segment.consensus.mount;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.jackrabbit.oak.api.CommitFailedException;
import org.apache.jackrabbit.oak.segment.RecordId;
import org.apache.jackrabbit.oak.segment.SegmentId;
import org.apache.jackrabbit.oak.segment.SegmentNodeStore;
import org.apache.jackrabbit.oak.segment.SegmentNodeStoreBuilders;
import org.apache.jackrabbit.oak.segment.SegmentNotFoundException;
import org.apache.jackrabbit.oak.segment.compaction.SegmentGCOptions;
import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.apache.jackrabbit.oak.segment.file.FileStoreBuilder;
import org.apache.jackrabbit.oak.spi.commit.CommitInfo;
import org.apache.jackrabbit.oak.spi.commit.EmptyHook;
import org.apache.jackrabbit.oak.spi.mount.Mounts;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * A mount reading a real owner store over HTTP while the owner compacts. Two full compactions reclaim the
 * segments of the head the mount holds; the read fails with a missing segment, and reporting that failure
 * refreshes the mount to the owner's current head. A stopped owner is reported as a transport failure.
 */
public class MountReaderAcrossRemoteCompactionTest {

    private static final String PAGE = "/oak-chain/aa/bb/cc/0xaabbcc0000000000000000000000000000000001/page";

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder(new File("target"));

    private File ownerDirectory;
    private FileStore owner;
    private SegmentNodeStore ownerStore;
    private HttpServer server;
    private boolean serverStopped;
    private String url;
    private LazyHttpNodeStore mount;

    @Before
    public void startOwner() throws Exception {
        ownerDirectory = folder.newFolder("owner");
        owner = FileStoreBuilder.fileStoreBuilder(ownerDirectory)
            .withMaxFileSize(1)
            .withMemoryMapping(false)
            .withGCOptions(SegmentGCOptions.defaultGCOptions()
                .setEstimationDisabled(true)
                .setRetainedGenerations(2))
            .build();
        ownerStore = SegmentNodeStoreBuilders.builder(owner).build();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::serve);
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @After
    public void stopOwner() throws IOException {
        if (mount != null) {
            mount.close();
        }
        if (!serverStopped) {
            server.stop(0);
        }
        owner.close();
    }

    @Test
    public void testAMissingSegmentAfterTwoRemoteCompactionsRefreshesTheMount() throws Exception {
        writePage("1", 300);
        mount = mount();
        NodeState held = mount.getRoot();
        RecordId heldHead = RecordId.fromString(owner.getSegmentIdProvider(), mount.status("aa").revision());

        writePage("2", 0);
        assertTrue(owner.compactFull());
        owner.cleanup();
        writePage("3", 0);
        assertTrue(owner.compactFull());
        owner.cleanup();
        owner.flush();
        assertFalse("two full compactions reclaim the mounted head's segment",
            owner.containsSegment(heldHead.getSegmentId()));

        MountReadFailures.begin(mounts());
        try {
            try {
                read(held);
                fail("Expected the reclaimed segment to be missing");
            } catch (SegmentNotFoundException expected) {
                // the owner no longer holds the head this view was reading
            }
            List<MountReadFailures.Failure> failures = MountReadFailures.current();
            assertFalse(failures.isEmpty());
            assertEquals(MountReadFailures.Kind.MISSING, failures.get(0).kind());
            assertEquals(url, failures.get(0).endpoint());

            assertEquals("mount_stale", MountReadFailures.failureAnswer().code());
            assertEquals("the failure refreshed the mount to the owner's head", "3", read(mount.getRoot()));
        } finally {
            MountReadFailures.end();
        }
    }

    @Test
    public void testAnUnreachableOwnerIsReportedAsATransportFailure() throws Exception {
        writePage("1", 300);
        mount = mount();
        NodeState root = mount.getRoot();
        assertEquals("1", read(root));
        server.stop(0);
        serverStopped = true;

        MountReadFailures.begin(mounts());
        try {
            try {
                for (int i = 299; i >= 0; i--) {
                    root.getChildNode("filler").getChildNode("n" + i).getString("data");
                }
                fail("Expected an uncached filler segment to be unreadable");
            } catch (SegmentNotFoundException expected) {
                // Oak reports any unreadable segment as missing
            }
            List<MountReadFailures.Failure> failures = MountReadFailures.current();
            assertFalse(failures.isEmpty());
            assertTrue(failures.stream().allMatch(f -> f.kind() == MountReadFailures.Kind.TRANSPORT));

            assertEquals("mount_unavailable", MountReadFailures.failureAnswer().code());
            assertFalse(mount.status("aa").available());
        } finally {
            MountReadFailures.end();
        }
    }

    private LazyHttpNodeStore mount() {
        return new LazyHttpNodeStore(url, "owner", 5_000L, 30_000L, new CircuitBreaker("owner"),
            LazyHttpNodeStore::createRemoteNodeStore, 1_000L, 3_600_000L);
    }

    private RemoteMounts mounts() {
        RemoteMounts.Endpoint endpoint = new RemoteMounts.Endpoint(url, "aa", mount);
        return new RemoteMounts(Mounts.defaultMountInfoProvider(), Map.of("owner", endpoint), List.of(endpoint));
    }

    /** Sets the page value and, when {@code fillerNodes > 0}, adds about 10 KB per filler node; otherwise drops it. */
    private void writePage(String value, int fillerNodes) throws CommitFailedException, IOException {
        NodeBuilder root = ownerStore.getRoot().builder();
        NodeBuilder page = root;
        for (String name : PAGE.substring(1).split("/")) {
            page = page.child(name);
        }
        page.setProperty("v", value);
        if (fillerNodes > 0) {
            NodeBuilder filler = root.child("filler");
            String data = "x".repeat(10_000);
            for (int i = 0; i < fillerNodes; i++) {
                filler.child("n" + i).setProperty("data", data + i);
            }
        } else {
            root.getChildNode("filler").remove();
        }
        ownerStore.merge(root, EmptyHook.INSTANCE, CommitInfo.EMPTY);
        owner.flush();
    }

    private static String read(NodeState root) {
        NodeState node = root;
        for (String name : PAGE.substring(1).split("/")) {
            node = node.getChildNode(name);
        }
        return node.getString("v");
    }

    /** The owner side of the mount contract, as {@code FileHandler} serves it. */
    private void serve(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        byte[] body = null;
        if (path.startsWith("/segments/")) {
            UUID uuid = UUID.fromString(path.substring("/segments/".length()));
            SegmentId id = owner.getSegmentIdProvider()
                .newSegmentId(uuid.getMostSignificantBits(), uuid.getLeastSignificantBits());
            if (owner.containsSegment(id)) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                owner.readSegment(id).writeTo(bytes);
                body = bytes.toByteArray();
            }
        } else if ("/journal.log".equals(path) || "/manifest".equals(path) || "/gc.log".equals(path)) {
            File file = new File(ownerDirectory, path.substring(1));
            if (file.exists()) {
                body = Files.readAllBytes(file.toPath());
            }
        }
        if (body == null) {
            exchange.sendResponseHeaders(404, -1);
        } else if ("HEAD".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(200, -1);
        } else {
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
        exchange.close();
    }
}
