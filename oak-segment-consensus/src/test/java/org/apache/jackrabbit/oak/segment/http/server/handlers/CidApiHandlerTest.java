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

import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.apache.jackrabbit.oak.segment.http.server.ServerContext;
import org.apache.jackrabbit.oak.segment.http.server.binary.CidMappingService;
import org.apache.jackrabbit.oak.spi.state.NodeStore;
import org.junit.Test;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;

import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class CidApiHandlerTest {

    private static final String OAK_BLOB_ID = "ed06f9cbf0fe878013ccb266170e6b3ba676933a6f065675cc0115c840bf1442#22216";
    private static final String NORMALIZED_OAK_BLOB_ID = "ed06f9cbf0fe878013ccb266170e6b3ba676933a6f065675cc0115c840bf1442";
    private static final String CID = "Qmf4F3CWU6Ly958TFiR8BRP18gwvW3Xsj2yXu5DqkonWc3";

    @Test
    public void testHandleGetCidRejectsMissingBlobId() throws Exception {
        StringWriter body = new StringWriter();
        HttpServletResponse response = responseWithBody(body);
        HttpServletRequest request = request("/api/cid/");

        CidApiHandler handler = new CidApiHandler(newContext(Paths.get("/tmp/store")));
        handler.handleGetCid(request, response);

        verify(response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
        assertTrue(body.toString().contains("Missing Oak blob ID in path"));
    }

    @Test
    public void testHandleGetCidReturnsMappedCidPayload() throws Exception {
        Path storageDir = Files.createTempDirectory("cid-get");
        try {
            ServerContext context = newContext(storageDir);
            context.cidMappingService = new CidMappingService(storageDir);
            context.cidMappingService.registerMapping(OAK_BLOB_ID, CID);

            StringWriter body = new StringWriter();
            HttpServletResponse response = responseWithBody(body);
            HttpServletRequest request = request("/api/cid/" + OAK_BLOB_ID);

            new CidApiHandler(context).handleGetCid(request, response);

            verify(response).setStatus(HttpServletResponse.SC_OK);
            String json = body.toString();
            assertTrue(json.contains("\"oakBlobId\":\"" + OAK_BLOB_ID + "\""));
            assertTrue(json.contains("\"ipfsCid\":\"" + CID + "\""));
            assertTrue(json.contains("\"gatewayUrl\":\"https://ipfs.io/ipfs/" + CID + "\""));
            assertTrue(json.contains("\"localUrl\":\"http://localhost:8099/ipfs/" + CID + "\""));
        } finally {
            deleteRecursively(storageDir);
        }
    }

    @Test
    public void testHandleReverseLookupReturnsOakBlobId() throws Exception {
        Path storageDir = Files.createTempDirectory("cid-reverse");
        try {
            ServerContext context = newContext(storageDir);
            context.cidMappingService = new CidMappingService(storageDir);
            context.cidMappingService.registerMapping(OAK_BLOB_ID, CID);

            StringWriter body = new StringWriter();
            HttpServletResponse response = responseWithBody(body);
            HttpServletRequest request = request("/api/cid/reverse/" + CID);

            new CidApiHandler(context).handleReverseLookup(request, response);

            verify(response).setStatus(HttpServletResponse.SC_OK);
            String json = body.toString();
            assertTrue(json.contains("\"ipfsCid\":\"" + CID + "\""));
            assertTrue(json.contains("\"oakBlobId\":\"" + NORMALIZED_OAK_BLOB_ID + "\""));
        } finally {
            deleteRecursively(storageDir);
        }
    }

    @Test
    public void testHandleGatewayRedirectRedirectsToGateway() throws Exception {
        Path storageDir = Files.createTempDirectory("cid-gateway");
        try {
            ServerContext context = newContext(storageDir);
            context.cidMappingService = new CidMappingService(storageDir);
            context.cidMappingService.registerMapping(OAK_BLOB_ID, CID);

            HttpServletResponse response = mock(HttpServletResponse.class);
            HttpServletRequest request = request("/api/cid/gateway/" + OAK_BLOB_ID);

            new CidApiHandler(context).handleGatewayRedirect(request, response);

            verify(response).sendRedirect("https://ipfs.io/ipfs/" + CID);
        } finally {
            deleteRecursively(storageDir);
        }
    }

    @Test
    public void testHandleStatsReturnsServiceStatistics() throws Exception {
        Path storageDir = Files.createTempDirectory("cid-stats");
        try {
            ServerContext context = newContext(storageDir);
            context.cidMappingService = new CidMappingService(storageDir);
            context.cidMappingService.registerMapping(OAK_BLOB_ID, CID);
            context.cidMappingService.getCid(OAK_BLOB_ID);
            context.cidMappingService.getCid("missing");

            StringWriter body = new StringWriter();
            HttpServletResponse response = responseWithBody(body);

            new CidApiHandler(context).handleStats(request("/api/cid/stats"), response);

            verify(response).setStatus(HttpServletResponse.SC_OK);
            String json = body.toString();
            assertTrue(json.contains("\"totalMappings\":1"));
            assertTrue(json.contains("\"cacheHits\":1"));
            assertTrue(json.contains("\"cacheMisses\":1"));
            assertTrue(json.contains("\"currentSize\":1"));
        } finally {
            deleteRecursively(storageDir);
        }
    }

    private static ServerContext newContext(Path storeDirectory) {
        return new ServerContext(
            mock(FileStore.class),
            mock(NodeStore.class),
            storeDirectory,
            "http://localhost:8090"
        );
    }

    private static HttpServletRequest request(String pathInfo) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getPathInfo()).thenReturn(pathInfo);
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
}
