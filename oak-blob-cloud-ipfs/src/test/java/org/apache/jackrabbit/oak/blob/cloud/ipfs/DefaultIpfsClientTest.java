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

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.ipfs.api.MerkleNode;
import io.ipfs.api.NamedStreamable;
import org.junit.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class DefaultIpfsClientTest {

    private static final String CID = "QmYwAPJzv5CZsnAzt8auVZRnGi2C4gYQqbiZ9erjRzCQXD";
    private static final byte[] CONTENT = "payload-data".getBytes(StandardCharsets.UTF_8);
    private static final String VERSION = "0.12.0";

    @Test
    public void testDelegatesToIpfsHttpApi() throws Exception {
        try (FakeIpfsNode node = new FakeIpfsNode()) {
            DefaultIpfsClient client = new DefaultIpfsClient(node.endpoint());

            assertEquals(VERSION, client.version());

            List<MerkleNode> nodes = client.add(new NamedStreamable.ByteArrayWrapper("payload.bin", CONTENT));
            assertEquals(1, nodes.size());
            assertEquals(CID, nodes.get(0).hash.toString());

            client.pinAdd(CID);
            assertArrayEquals(CONTENT, client.cat(CID));
            assertEquals(Map.of("Size", CONTENT.length), client.blockStat(CID));
            client.pinRemove(CID);

            RequestSnapshot versionRequest = node.firstRequest("/api/v0/version");
            assertEquals("POST", versionRequest.method);
            assertEquals("application/json", versionRequest.contentType);

            RequestSnapshot addRequest = node.firstRequest("/api/v0/add");
            assertEquals("POST", addRequest.method);
            assertTrue(addRequest.query.contains("stream-channels=true"));
            assertTrue(addRequest.contentType.startsWith("multipart/form-data; boundary="));
            String multipartBody = new String(addRequest.body, StandardCharsets.UTF_8);
            assertTrue(multipartBody.contains("payload.bin"));
            assertTrue(multipartBody.contains("payload-data"));

            RequestSnapshot pinAddRequest = node.firstRequest("/api/v0/pin/add");
            assertEquals("POST", pinAddRequest.method);
            assertEquals("application/json", pinAddRequest.contentType);
            assertTrue(pinAddRequest.query.contains("arg=" + CID));

            RequestSnapshot catRequest = node.firstRequest("/api/v0/cat");
            assertEquals("POST", catRequest.method);
            assertEquals("application/json", catRequest.contentType);
            assertTrue(catRequest.query.contains("arg=" + CID));

            RequestSnapshot blockStatRequest = node.firstRequest("/api/v0/block/stat");
            assertEquals("POST", blockStatRequest.method);
            assertEquals("application/json", blockStatRequest.contentType);
            assertTrue(blockStatRequest.query.contains("arg=" + CID));

            RequestSnapshot pinRemoveRequest = node.firstRequest("/api/v0/pin/rm");
            assertEquals("POST", pinRemoveRequest.method);
            assertEquals("application/json", pinRemoveRequest.contentType);
            assertTrue(pinRemoveRequest.query.contains("arg=" + CID));
            assertTrue(pinRemoveRequest.query.contains("r=true"));
        }
    }

    private static final class FakeIpfsNode implements AutoCloseable {

        private final HttpServer server;
        private final List<RequestSnapshot> requests = new ArrayList<>();

        private FakeIpfsNode() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/v0/", this::handle);
            server.start();
        }

        private String endpoint() {
            return "/ip4/127.0.0.1/tcp/" + server.getAddress().getPort();
        }

        private RequestSnapshot firstRequest(String path) {
            for (RequestSnapshot request : requests) {
                if (request.path.equals(path)) {
                    return request;
                }
            }
            throw new AssertionError("No request recorded for path " + path);
        }

        private void handle(HttpExchange exchange) throws IOException {
            byte[] requestBody = exchange.getRequestBody().readAllBytes();
            Headers headers = exchange.getRequestHeaders();
            requests.add(new RequestSnapshot(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(),
                exchange.getRequestURI().getRawQuery(),
                headers.getFirst("Content-Type"),
                requestBody
            ));

            String path = exchange.getRequestURI().getPath();
            if ("/api/v0/version".equals(path)) {
                respondJson(exchange, "{\"Version\":\"" + VERSION + "\"}");
                return;
            }
            if ("/api/v0/add".equals(path)) {
                respondJson(exchange, "{\"Name\":\"payload.bin\",\"Hash\":\"" + CID + "\",\"Size\":\"" + CONTENT.length + "\"}");
                return;
            }
            if ("/api/v0/pin/add".equals(path) || "/api/v0/pin/rm".equals(path)) {
                respondJson(exchange, "{\"Pins\":[\"" + CID + "\"]}");
                return;
            }
            if ("/api/v0/cat".equals(path)) {
                respondBytes(exchange, CONTENT, "application/octet-stream");
                return;
            }
            if ("/api/v0/block/stat".equals(path)) {
                respondJson(exchange, "{\"Size\":" + CONTENT.length + "}");
                return;
            }

            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        }

        private static void respondJson(HttpExchange exchange, String body) throws IOException {
            respondBytes(exchange, body.getBytes(StandardCharsets.UTF_8), "application/json");
        }

        private static void respondBytes(HttpExchange exchange, byte[] body, String contentType) throws IOException {
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private static final class RequestSnapshot {
        private final String method;
        private final String path;
        private final String query;
        private final String contentType;
        private final byte[] body;

        private RequestSnapshot(String method, String path, String query, String contentType, byte[] body) {
            this.method = method;
            this.path = path;
            this.query = query;
            this.contentType = contentType;
            this.body = body;
        }
    }
}
