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
package org.apache.jackrabbit.oak.segment.http.server.handlers;

import org.apache.jackrabbit.oak.api.Type;
import org.apache.jackrabbit.oak.plugins.memory.MemoryNodeStore;
import org.apache.jackrabbit.oak.segment.consensus.service.AppliedLogPosition;
import org.apache.jackrabbit.oak.spi.blob.BlobStore;
import org.apache.jackrabbit.oak.spi.blob.data.DataStoreException;
import org.apache.jackrabbit.oak.spi.commit.CommitInfo;
import org.apache.jackrabbit.oak.spi.commit.EmptyHook;
import org.apache.jackrabbit.oak.composite.CompositeNodeStore;
import org.apache.jackrabbit.oak.spi.mount.MountInfoProvider;
import org.apache.jackrabbit.oak.spi.mount.Mounts;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;
import org.apache.jackrabbit.oak.spi.state.NodeStore;
import org.junit.Test;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ExplorerApiHandlerTest {

    @Test
    public void testHandleExploreNodeReturnsChildrenAndProperties() throws Exception {
        MemoryNodeStore nodeStore = new MemoryNodeStore();
        NodeBuilder root = nodeStore.getRoot().builder();
        NodeBuilder doc = root.child("content").child("doc");
        doc.child("child-a");
        doc.setProperty("title", "Hello");
        doc.setProperty("enabled", true);
        doc.setProperty("version", 7L);
        doc.setProperty("tags", Arrays.asList("one", "two"), Type.STRINGS);
        nodeStore.merge(root, EmptyHook.INSTANCE, CommitInfo.EMPTY);

        StringWriter body = new StringWriter();
        HttpServletResponse response = responseWithBody(body);

        ExplorerApiHandler handler = new ExplorerApiHandler(nodeStore, null);
        handler.handleExploreNode(response, "/content/doc");

        verify(response).setStatus(HttpServletResponse.SC_OK);
        String json = body.toString();
        assertTrue(json.contains("\"path\":\"/content/doc\""));
        assertTrue(json.contains("\"children\":[\"child-a\"]"));
        assertTrue(json.contains("\"title\":\"Hello\""));
        assertTrue(json.contains("\"enabled\":\"true\""));
        assertTrue(json.contains("\"version\":\"7\""));
        assertTrue(json.contains("\"tags\":[\"one\",\"two\"]"));
    }

    @Test
    public void exploreReadsTheCurrentReadViewAfterItIsSwapped() throws Exception {
        MemoryNodeStore before = new MemoryNodeStore();
        MemoryNodeStore after = new MemoryNodeStore();
        NodeBuilder root = after.getRoot().builder();
        root.child("oak-chain").child("87");
        after.merge(root, EmptyHook.INSTANCE, CommitInfo.EMPTY);
        AtomicReference<NodeStore> readView = new AtomicReference<>(before);
        ExplorerApiHandler handler = ExplorerApiHandler.withSuppliers(readView::get, () -> null);

        HttpServletResponse missing = responseWithBody(new StringWriter());
        handler.handleExploreNode(missing, "/oak-chain/87");
        verify(missing).setStatus(HttpServletResponse.SC_NOT_FOUND);

        readView.set(after);
        HttpServletResponse found = responseWithBody(new StringWriter());
        handler.handleExploreNode(found, "/oak-chain/87");
        verify(found).setStatus(HttpServletResponse.SC_OK);
    }

    @Test
    public void exploreReachesContentUnderARemoteMountWhoseAncestorsAreNotLocal() throws Exception {
        String mountRoot = "/oak-chain/87/e0/d8/0x87e0d8cae7590570963f22b1fc75df597d77feba";
        MemoryNodeStore local = new MemoryNodeStore();
        NodeBuilder localRoot = local.getRoot().builder();
        localRoot.child("oak-chain").child("00");
        local.merge(localRoot, EmptyHook.INSTANCE, CommitInfo.EMPTY);
        MemoryNodeStore remote = new MemoryNodeStore();
        NodeBuilder remoteRoot = remote.getRoot().builder();
        NodeBuilder page = remoteRoot;
        for (String name : (mountRoot + "/content/page").substring(1).split("/")) {
            page = page.child(name);
        }
        page.setProperty("message", "from cluster B");
        remote.merge(remoteRoot, EmptyHook.INSTANCE, CommitInfo.EMPTY);
        MountInfoProvider mounts = Mounts.newBuilder().mount("remote-b", true, List.of(), List.of(mountRoot)).build();
        NodeStore readView = new CompositeNodeStore.Builder(mounts, local).addMount("remote-b", remote).build();
        ExplorerApiHandler handler = new ExplorerApiHandler(readView, null);

        StringWriter body = new StringWriter();
        HttpServletResponse found = responseWithBody(body);
        handler.handleExploreNode(found, mountRoot + "/content/page");
        verify(found).setStatus(HttpServletResponse.SC_OK);
        assertTrue(body.toString(), body.toString().contains("from cluster B"));

        HttpServletResponse missing = responseWithBody(new StringWriter());
        handler.handleExploreNode(missing, mountRoot + "/content/absent");
        verify(missing).setStatus(HttpServletResponse.SC_NOT_FOUND);
    }

    @Test
    public void rootListingHidesTheConsensusWatermarkNode() throws Exception {
        MemoryNodeStore nodeStore = new MemoryNodeStore();
        NodeBuilder root = nodeStore.getRoot().builder();
        root.child("oak-chain");
        new AppliedLogPosition(64L, 0, 0L).writeTo(root);
        nodeStore.merge(root, EmptyHook.INSTANCE, CommitInfo.EMPTY);
        StringWriter body = new StringWriter();

        new ExplorerApiHandler(nodeStore, null).handleExploreNode(responseWithBody(body), "/");

        assertTrue(body.toString(), body.toString().contains("\"children\":[\"oak-chain\"]"));
    }

    @Test
    public void testHandleExploreNodeReturnsNotFoundForMissingPath() throws Exception {
        StringWriter body = new StringWriter();
        HttpServletResponse response = responseWithBody(body);

        ExplorerApiHandler handler = new ExplorerApiHandler(new MemoryNodeStore(), null);
        handler.handleExploreNode(response, "/missing");

        verify(response).setStatus(HttpServletResponse.SC_NOT_FOUND);
        assertTrue(body.toString().contains("Node not found"));
    }

    @Test
    public void testHandleBlobStreamRejectsMissingBlobStore() throws Exception {
        StringWriter body = new StringWriter();
        HttpServletResponse response = responseWithBody(body);

        ExplorerApiHandler handler = new ExplorerApiHandler(new MemoryNodeStore(), null);
        handler.handleBlobStream(mock(HttpServletRequest.class), response, "blob-1");

        verify(response).setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        assertTrue(body.toString().contains("BlobStore not configured"));
    }

    @Test
    public void testHandleBlobStreamReturnsNotFoundWhenBlobMissing() throws Exception {
        StringWriter body = new StringWriter();
        HttpServletResponse response = responseWithBody(body);
        BlobStore blobStore = mock(BlobStore.class);
        when(blobStore.getInputStream("blob-404")).thenReturn(null);

        ExplorerApiHandler handler = new ExplorerApiHandler(new MemoryNodeStore(), blobStore);
        handler.handleBlobStream(mock(HttpServletRequest.class), response, "blob-404");

        verify(response).setStatus(HttpServletResponse.SC_NOT_FOUND);
        assertTrue(body.toString().contains("Blob not found: blob-404"));
    }

    @Test
    public void testHandleBlobStreamHidesBackendFailureDetails() throws Exception {
        BlobStore blobStore = mock(BlobStore.class);
        when(blobStore.getInputStream("blob-missing"))
            .thenThrow(new NullPointerException("No DataRecord found for blobId [blob-missing]"));
        when(blobStore.getInputStream("blob-released"))
            .thenThrow(new IOException(new DataStoreException("Record blob-released does not exist")));
        when(blobStore.getInputStream("blob-broken"))
            .thenThrow(new IOException("IPFS read failed: Kubo /oak/ipfs/blob-index/YWJj at 10.0.0.5 refused"));
        ExplorerApiHandler handler = new ExplorerApiHandler(new MemoryNodeStore(), blobStore);

        StringWriter missingBody = new StringWriter();
        HttpServletResponse missing = responseWithBody(missingBody);
        handler.handleBlobStream(mock(HttpServletRequest.class), missing, "blob-missing");
        verify(missing).setStatus(HttpServletResponse.SC_NOT_FOUND);
        assertTrue(missingBody.toString().contains("blob_not_found"));

        HttpServletResponse released = responseWithBody(new StringWriter());
        handler.handleBlobStream(mock(HttpServletRequest.class), released, "blob-released");
        verify(released).setStatus(HttpServletResponse.SC_NOT_FOUND);

        StringWriter brokenBody = new StringWriter();
        HttpServletResponse broken = responseWithBody(brokenBody);
        handler.handleBlobStream(mock(HttpServletRequest.class), broken, "blob-broken");
        verify(broken).setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        assertTrue(brokenBody.toString().contains("blob_stream_failed"));
        assertFalse(brokenBody.toString().contains("10.0.0.5"));
        assertFalse(brokenBody.toString().contains("/oak/ipfs"));
    }

    @Test
    public void testHandleBlobStreamWritesResponseBodyAndHeaders() throws Exception {
        BlobStore blobStore = mock(BlobStore.class);
        byte[] payload = "hello-image".getBytes(StandardCharsets.UTF_8);
        when(blobStore.getInputStream("ed06f9cb-demo")).thenReturn(new ByteArrayInputStream(payload));

        RecordingServletOutputStream output = new RecordingServletOutputStream();
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getOutputStream()).thenReturn(output);

        ExplorerApiHandler handler = new ExplorerApiHandler(new MemoryNodeStore(), blobStore);
        handler.handleBlobStream(mock(HttpServletRequest.class), response, "ed06f9cb-demo");

        verify(response).setStatus(HttpServletResponse.SC_OK);
        verify(response).setContentType("image/jpeg");
        verify(response).setHeader("X-Blob-Id", "ed06f9cb-demo");
        assertArrayEquals(payload, output.toByteArray());
    }

    @Test
    public void testHandleBlobStreamUsesLateBoundBlobStoreSupplier() throws Exception {
        final BlobStore[] blobStoreRef = new BlobStore[1];
        BlobStore blobStore = mock(BlobStore.class);
        byte[] payload = "late-bound".getBytes(StandardCharsets.UTF_8);
        when(blobStore.getInputStream("blob-late")).thenReturn(new ByteArrayInputStream(payload));

        RecordingServletOutputStream output = new RecordingServletOutputStream();
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getOutputStream()).thenReturn(output);

        ExplorerApiHandler handler = ExplorerApiHandler.withSuppliers(
            MemoryNodeStore::new,
            () -> blobStoreRef[0]
        );
        blobStoreRef[0] = blobStore;

        handler.handleBlobStream(mock(HttpServletRequest.class), response, "blob-late");

        verify(response).setStatus(HttpServletResponse.SC_OK);
        verify(response).setHeader("X-Blob-Id", "blob-late");
        assertArrayEquals(payload, output.toByteArray());
    }

    private static HttpServletResponse responseWithBody(StringWriter body) throws Exception {
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getWriter()).thenReturn(new PrintWriter(body));
        return response;
    }

    private static final class RecordingServletOutputStream extends ServletOutputStream {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();

        @Override
        public void write(int b) {
            output.write(b);
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setWriteListener(WriteListener writeListener) {
        }

        private byte[] toByteArray() {
            return output.toByteArray();
        }
    }
}
