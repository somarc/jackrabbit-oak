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

import org.apache.jackrabbit.oak.segment.Segment;
import org.apache.jackrabbit.oak.segment.SegmentId;
import org.apache.jackrabbit.oak.segment.SegmentIdProvider;
import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.junit.Test;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class FileHandlerTest {

    @Test
    public void testHandleFileHeadReturnsNotFoundForMissingFile() throws Exception {
        Path storeDirectory = Files.createTempDirectory("file-head-missing");
        try {
            StringWriter body = new StringWriter();
            HttpServletResponse response = responseWithBody(body);

            new FileHandler(mock(FileStore.class), storeDirectory, new HashSet<>())
                .handleFileHead(response, "manifest", "text/plain");

            verify(response).setStatus(HttpServletResponse.SC_NOT_FOUND);
            assertTrue(body.toString().contains("File not found: manifest"));
        } finally {
            deleteRecursively(storeDirectory);
        }
    }

    @Test
    public void testHandleFileHeadSetsHeadersForExistingFile() throws Exception {
        Path storeDirectory = Files.createTempDirectory("file-head");
        try {
            byte[] payload = "manifest-data".getBytes(StandardCharsets.UTF_8);
            Files.write(storeDirectory.resolve("manifest"), payload);
            HttpServletResponse response = mock(HttpServletResponse.class);

            new FileHandler(mock(FileStore.class), storeDirectory, new HashSet<>())
                .handleFileHead(response, "manifest", "text/plain");

            verify(response).setStatus(HttpServletResponse.SC_OK);
            verify(response).setContentType("text/plain");
            verify(response).setContentLengthLong(payload.length);
        } finally {
            deleteRecursively(storeDirectory);
        }
    }

    @Test
    public void testHandleFileStreamsExistingFile() throws Exception {
        Path storeDirectory = Files.createTempDirectory("file-get");
        try {
            byte[] payload = "journal-entry".getBytes(StandardCharsets.UTF_8);
            Files.write(storeDirectory.resolve("journal.log"), payload);
            RecordingServletOutputStream output = new RecordingServletOutputStream();
            HttpServletResponse response = mock(HttpServletResponse.class);
            when(response.getOutputStream()).thenReturn(output);
            HttpServletRequest request = request("10.0.0.5", 4502, "OakClient/1.0");

            new FileHandler(mock(FileStore.class), storeDirectory, new HashSet<>())
                .handleFile(request, response, "journal.log", "text/plain");

            verify(response).setStatus(HttpServletResponse.SC_OK);
            verify(response).setContentType("text/plain");
            verify(response).setContentLengthLong(payload.length);
            assertArrayEquals(payload, output.toByteArray());
        } finally {
            deleteRecursively(storeDirectory);
        }
    }

    @Test
    public void testHandleSegmentHeadReturnsNotFoundWhenNoTarFilesExist() throws Exception {
        Path storeDirectory = Files.createTempDirectory("segment-head-missing");
        try {
            StringWriter body = new StringWriter();
            HttpServletResponse response = responseWithBody(body);

            new FileHandler(mock(FileStore.class), storeDirectory, new HashSet<>())
                .handleSegmentHead(response, UUID.randomUUID().toString());

            verify(response).setStatus(HttpServletResponse.SC_NOT_FOUND);
            assertTrue(body.toString().contains("Segment not found"));
        } finally {
            deleteRecursively(storeDirectory);
        }
    }

    @Test
    public void testHandleSegmentHeadReturnsOkWhenTarFileExists() throws Exception {
        Path storeDirectory = Files.createTempDirectory("segment-head");
        try {
            Files.write(storeDirectory.resolve("data00000a.tar"), new byte[] {1});
            HttpServletResponse response = mock(HttpServletResponse.class);

            new FileHandler(mock(FileStore.class), storeDirectory, new HashSet<>())
                .handleSegmentHead(response, UUID.randomUUID().toString());

            verify(response).setStatus(HttpServletResponse.SC_OK);
            verify(response).setHeader("X-Segment-Found", "true");
        } finally {
            deleteRecursively(storeDirectory);
        }
    }

    @Test
    public void testHandleSegmentGetRejectsInvalidUuid() throws Exception {
        Path storeDirectory = Files.createTempDirectory("segment-invalid");
        try {
            StringWriter body = new StringWriter();
            HttpServletResponse response = responseWithBody(body);

            new FileHandler(mock(FileStore.class), storeDirectory, new HashSet<>())
                .handleSegmentGet(request("127.0.0.1", 8090, "OakClient/1.0"), response, "not-a-uuid");

            verify(response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
            assertTrue(body.toString().contains("Invalid segment UUID"));
        } finally {
            deleteRecursively(storeDirectory);
        }
    }

    @Test
    public void testHandleSegmentGetReturnsNotFoundWhenStoreDoesNotContainSegment() throws Exception {
        Path storeDirectory = Files.createTempDirectory("segment-missing");
        try {
            Files.write(storeDirectory.resolve("data00000a.tar"), new byte[] {1});
            FileStore fileStore = mock(FileStore.class);
            SegmentIdProvider provider = mock(SegmentIdProvider.class);
            SegmentId segmentId = mock(SegmentId.class);
            UUID uuid = UUID.randomUUID();
            when(fileStore.getSegmentIdProvider()).thenReturn(provider);
            when(provider.newSegmentId(uuid.getMostSignificantBits(), uuid.getLeastSignificantBits())).thenReturn(segmentId);
            when(fileStore.containsSegment(segmentId)).thenReturn(false);

            StringWriter body = new StringWriter();
            HttpServletResponse response = responseWithBody(body);

            new FileHandler(fileStore, storeDirectory, new HashSet<>())
                .handleSegmentGet(request("127.0.0.1", 8090, "OakClient/1.0"), response, uuid.toString());

            verify(response).setStatus(HttpServletResponse.SC_NOT_FOUND);
            assertTrue(body.toString().contains("Segment not found"));
        } finally {
            deleteRecursively(storeDirectory);
        }
    }

    @Test
    public void testHandleSegmentGetWritesBinaryAndTracksRemotePeer() throws Exception {
        Path storeDirectory = Files.createTempDirectory("segment-get");
        try {
            Files.write(storeDirectory.resolve("data00000a.tar"), new byte[] {1});
            FileStore fileStore = mock(FileStore.class);
            SegmentIdProvider provider = mock(SegmentIdProvider.class);
            SegmentId segmentId = mock(SegmentId.class);
            Segment segment = mock(Segment.class);
            UUID uuid = UUID.randomUUID();
            when(fileStore.getSegmentIdProvider()).thenReturn(provider);
            when(provider.newSegmentId(uuid.getMostSignificantBits(), uuid.getLeastSignificantBits())).thenReturn(segmentId);
            when(fileStore.containsSegment(segmentId)).thenReturn(true);
            when(fileStore.readSegment(segmentId)).thenReturn(segment);
            doAnswer(invocation -> {
                ByteArrayOutputStream stream = invocation.getArgument(0);
                stream.write(new byte[] {1, 2, 3, 4});
                return null;
            }).when(segment).writeTo(any(ByteArrayOutputStream.class));

            Set<String> connectedPeers = new HashSet<>();
            RecordingServletOutputStream output = new RecordingServletOutputStream();
            HttpServletResponse response = mock(HttpServletResponse.class);
            when(response.getOutputStream()).thenReturn(output);

            new FileHandler(fileStore, storeDirectory, connectedPeers)
                .handleSegmentGet(request("10.0.0.8", 8090, "OakClient/1.0"), response, uuid.toString());

            verify(response).setStatus(HttpServletResponse.SC_OK);
            verify(response).setContentType("application/octet-stream");
            verify(response).setContentLength(4);
            verify(response).setHeader("X-Segment-Length", "4");
            assertArrayEquals(new byte[] {1, 2, 3, 4}, output.toByteArray());
            assertTrue(connectedPeers.contains("10.0.0.8:8090"));
        } finally {
            deleteRecursively(storeDirectory);
        }
    }

    private static HttpServletRequest request(String remoteAddr, int remotePort, String userAgent) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn(remoteAddr);
        when(request.getRemotePort()).thenReturn(remotePort);
        when(request.getHeader("User-Agent")).thenReturn(userAgent);
        return request;
    }

    private static HttpServletResponse responseWithBody(StringWriter body) throws Exception {
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getWriter()).thenReturn(new PrintWriter(body));
        return response;
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (java.util.stream.Stream<Path> stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
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
