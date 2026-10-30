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
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class DefaultIpfsClientTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private static final String CID = "QmYwAPJzv5CZsnAzt8auVZRnGi2C4gYQqbiZ9erjRzCQXD";
    private static final byte[] CONTENT = "payload-data".getBytes(StandardCharsets.UTF_8);
    private static final String VERSION = "0.12.0";

    @Test
    public void testDelegatesToIpfsHttpApi() throws Exception {
        try (FakeIpfsNode node = new FakeIpfsNode()) {
            DefaultIpfsClient client = new DefaultIpfsClient(node.endpoint());

            assertEquals(VERSION, client.version());

            File payload = folder.newFile("payload.bin");
            Files.write(payload.toPath(), CONTENT);
            assertEquals(CID, client.add(payload));

            try (InputStream content = client.cat(CID)) {
                assertArrayEquals(CONTENT, content.readAllBytes());
            }
            client.pinRemove(CID);
            client.ensureDirectory("/oak/ipfs/metadata");
            client.writeFile("/oak/ipfs/metadata/payload.bin", CONTENT);
            client.linkCid(CID, "/oak/ipfs/content/payload.bin");
            assertTrue(client.fileExists("/oak/ipfs/metadata"));
            assertTrue(client.fileExists("/oak/ipfs/metadata/payload.bin"));
            assertTrue(client.fileExists("/oak/ipfs/content/payload.bin"));
            assertEquals(CONTENT.length, client.fileSize("/oak/ipfs/metadata/payload.bin"));
            assertEquals(List.of("payload.bin"), client.listFiles("/oak/ipfs/metadata"));
            assertArrayEquals(CONTENT, client.readFile("/oak/ipfs/metadata/payload.bin"));
            client.deleteFile("/oak/ipfs/metadata/payload.bin");
            assertTrue(client.listFiles("/oak/ipfs/metadata").isEmpty());

            RequestSnapshot versionRequest = node.firstRequest("/api/v0/version");
            assertEquals("POST", versionRequest.method);

            RequestSnapshot addRequest = node.firstRequest("/api/v0/add");
            assertEquals("POST", addRequest.method);
            assertTrue(addRequest.query.contains("cid-version=0"));
            assertTrue(addRequest.query.contains("hash=sha2-256"));
            assertTrue(addRequest.query.contains("chunker=size-262144"));
            assertTrue(addRequest.query.contains("pin=true"));
            assertTrue(addRequest.query.contains("raw-leaves=false"));
            assertTrue(addRequest.query.contains("max-file-links=174"));
            assertTrue(addRequest.query.contains("trickle=false"));
            assertTrue(node.firstRequest("/api/v0/pin/ls").query.contains("type=recursive"));
            assertTrue(addRequest.contentType.startsWith("multipart/form-data; boundary="));
            String multipartBody = new String(addRequest.body, StandardCharsets.UTF_8);
            assertTrue(multipartBody.contains("filename=\"blob\""));
            assertTrue(multipartBody.contains("payload-data"));

            RequestSnapshot catRequest = node.firstRequest("/api/v0/cat");
            assertEquals("POST", catRequest.method);
            assertTrue(catRequest.query.contains("arg=" + CID));

            RequestSnapshot pinRemoveRequest = node.firstRequest("/api/v0/pin/rm");
            assertEquals("POST", pinRemoveRequest.method);
            assertTrue(pinRemoveRequest.query.contains("arg=" + CID));

            RequestSnapshot mkdirRequest = node.firstRequest("/api/v0/files/mkdir");
            assertEquals("POST", mkdirRequest.method);
            assertTrue(mkdirRequest.query.contains("arg=%2Foak%2Fipfs%2Fmetadata"));

            RequestSnapshot writeRequest = node.firstRequest("/api/v0/files/write");
            assertEquals("POST", writeRequest.method);
            assertTrue(writeRequest.query.contains("arg=%2Foak%2Fipfs%2Fmetadata%2Fpayload.bin"));
            assertTrue(writeRequest.query.contains("create=true"));
            assertTrue(writeRequest.query.contains("parents=true"));
            assertTrue(writeRequest.query.contains("truncate=true"));

            RequestSnapshot copyRequest = node.firstRequest("/api/v0/files/cp");
            assertEquals("POST", copyRequest.method);
            assertTrue(copyRequest.query.contains("arg=%2Fipfs%2F" + CID));
            assertTrue(copyRequest.query.contains("arg=%2Foak%2Fipfs%2Fcontent%2Fpayload.bin"));

            RequestSnapshot lsRequest = node.firstRequest("/api/v0/files/ls");
            assertEquals("POST", lsRequest.method);
            assertTrue(lsRequest.query.contains("arg=%2Foak%2Fipfs%2Fmetadata"));

            RequestSnapshot readRequest = node.firstRequest("/api/v0/files/read");
            assertEquals("POST", readRequest.method);
            assertTrue(readRequest.query.contains("arg=%2Foak%2Fipfs%2Fmetadata%2Fpayload.bin"));

            RequestSnapshot rmRequest = node.lastRequest("/api/v0/files/rm");
            assertEquals("POST", rmRequest.method);
            assertTrue(rmRequest.query.contains("arg=%2Foak%2Fipfs%2Fmetadata%2Fpayload.bin"));
        }
    }

    @Test
    public void testMissingPathsAreReportedAndOtherFailuresAreThrown() throws Exception {
        try (FakeIpfsNode node = new FakeIpfsNode()) {
            DefaultIpfsClient client = new DefaultIpfsClient(node.endpoint());

            assertFalse(client.fileExists("/oak/ipfs/blob-index/missing"));
            assertNull(client.readFile("/oak/ipfs/blob-index/missing"));
            client.deleteFile("/oak/ipfs/blob-index/missing");

            node.failAll = true;
            assertThrowsNonNotFound(() -> client.fileExists("/oak/ipfs/blob-index/missing"));
            assertThrowsNonNotFound(() -> client.readFile("/oak/ipfs/blob-index/missing"));
            assertThrowsNonNotFound(() -> {
                client.deleteFile("/oak/ipfs/blob-index/missing");
                return null;
            });
        }
    }

    @Test
    public void testCidV1IsAccepted() throws Exception {
        try (FakeIpfsNode node = new FakeIpfsNode()) {
            DefaultIpfsClient client = new DefaultIpfsClient(node.endpoint());
            String cidV1 = "bafkreidgvpkjawlxz6sffxzwgooowe5yt7i6wsyg236mfoks77nywkptdq";

            try (InputStream content = client.cat(cidV1)) {
                assertArrayEquals(CONTENT, content.readAllBytes());
            }
            client.pinRemove(cidV1);

            assertTrue(node.firstRequest("/api/v0/cat").query.contains("arg=" + cidV1));
            assertTrue(node.firstRequest("/api/v0/pin/rm").query.contains("arg=" + cidV1));
        }
    }

    @Test
    public void testSendsTheConfiguredAuthorizationOnlyWhenSet() throws Exception {
        try (FakeIpfsNode node = new FakeIpfsNode()) {
            new DefaultIpfsClient(settings(node, "Bearer t0k3n", Duration.ofSeconds(5), 1)).version();
            assertEquals("Bearer t0k3n", node.lastRequest("/api/v0/version").authorization);

            new DefaultIpfsClient(node.endpoint()).version();
            assertNull(node.lastRequest("/api/v0/version").authorization);
        }
    }

    @Test
    public void testRetriesProxyFailuresUntilTheyClear() throws Exception {
        try (FakeIpfsNode node = new FakeIpfsNode()) {
            node.failNext("/api/v0/version", 503, 2);

            assertEquals(VERSION, new DefaultIpfsClient(settings(node, null, Duration.ofSeconds(5), 3)).version());
            assertEquals(3, node.count("/api/v0/version"));
        }
    }

    @Test
    public void testGivesUpAfterMaxAttempts() throws Exception {
        try (FakeIpfsNode node = new FakeIpfsNode()) {
            node.failNext("/api/v0/version", 504, 5);
            try {
                new DefaultIpfsClient(settings(node, null, Duration.ofSeconds(5), 2)).version();
                throw new AssertionError("Expected the call to give up");
            } catch (HttpRpc.RpcException e) {
                assertEquals(504, e.status());
            }
            assertEquals(2, node.count("/api/v0/version"));
        }
    }

    @Test
    public void testDoesNotRetryKuboCommandErrors() throws Exception {
        try (FakeIpfsNode node = new FakeIpfsNode()) {
            node.failNext("/api/v0/files/stat", 500, 1);
            try {
                new DefaultIpfsClient(settings(node, null, Duration.ofSeconds(5), 3)).fileExists("/oak/x");
                throw new AssertionError("Expected the Kubo error to be thrown");
            } catch (HttpRpc.RpcException e) {
                assertEquals(500, e.status());
                assertTrue(e.getMessage().contains("injected failure"));
            }
            assertEquals(1, node.count("/api/v0/files/stat"));
        }
    }

    @Test
    public void testRequestTimeoutBoundsASlowNode() throws Exception {
        try (FakeIpfsNode node = new FakeIpfsNode()) {
            node.delayMillis = 2_000;
            try {
                new DefaultIpfsClient(settings(node, null, Duration.ofMillis(200), 1)).version();
                throw new AssertionError("Expected a timeout");
            } catch (HttpTimeoutException e) {
                // expected
            }
        }
    }

    @Test
    public void testUnreachableNodeFailsAfterRetrying() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        IpfsClientSettings settings = new IpfsClientSettings("/ip4/127.0.0.1/tcp/" + port, null,
            Duration.ofSeconds(2), Duration.ofSeconds(2), 2);
        try {
            new DefaultIpfsClient(settings).version();
            throw new AssertionError("Expected a connection failure");
        } catch (IOException e) {
            assertFalse(e instanceof HttpRpc.RpcException);
        }
    }

    @Test
    public void testUploadsStreamTheWholeFile() throws Exception {
        try (FakeIpfsNode node = new FakeIpfsNode()) {
            byte[] large = new byte[5 * 1024 * 1024 + 7];
            new java.util.Random(1).nextBytes(large);
            File file = folder.newFile("large.bin");
            Files.write(file.toPath(), large);

            new DefaultIpfsClient(node.endpoint()).add(file);

            RequestSnapshot add = node.lastRequest("/api/v0/add");
            assertArrayEquals(large, FakeIpfsNode.extractMultipartPayload(add.contentType, add.body));
        }
    }

    @Test
    public void testABodyThatStallsFailsWithinTheIdleTimeout() throws Exception {
        try (FakeIpfsNode node = new FakeIpfsNode()) {
            node.stallBodyMillis = 3_000;
            DefaultIpfsClient client = new DefaultIpfsClient(settings(node, null, Duration.ofMillis(300), 1));
            long start = System.nanoTime();
            try (InputStream content = client.cat(CID)) {
                content.readAllBytes();
                throw new AssertionError("Expected the stalled body to time out");
            } catch (HttpTimeoutException e) {
                assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 2_500);
            }
        }
    }

    @Test
    public void testAddIsRetriedWithTheWholeFile() throws Exception {
        try (FakeIpfsNode node = new FakeIpfsNode()) {
            node.failNext("/api/v0/add", 503, 1);
            File file = folder.newFile("retried.bin");
            Files.write(file.toPath(), CONTENT);

            assertEquals(CID, new DefaultIpfsClient(settings(node, null, Duration.ofSeconds(5), 2)).add(file));
            assertEquals(2, node.count("/api/v0/add"));
            RequestSnapshot retried = node.lastRequest("/api/v0/add");
            assertArrayEquals(CONTENT, FakeIpfsNode.extractMultipartPayload(retried.contentType, retried.body));
        }
    }

    @Test
    public void testAddFailsWhenKuboDidNotPinTheFile() throws Exception {
        try (FakeIpfsNode node = new FakeIpfsNode()) {
            node.pinMissing = true;
            File file = folder.newFile("unpinned.bin");
            Files.write(file.toPath(), CONTENT);
            try {
                new DefaultIpfsClient(node.endpoint()).add(file);
                throw new AssertionError("Expected an unpinned add to fail");
            } catch (IOException e) {
                assertTrue(e.getMessage().contains("did not pin"));
            }
        }
    }

    @Test
    public void testLinkSurvivesALostCopyResponse() throws Exception {
        try (FakeIpfsNode node = new FakeIpfsNode()) {
            node.lostCopies = 1;
            DefaultIpfsClient client = new DefaultIpfsClient(settings(node, null, Duration.ofSeconds(5), 3));

            client.linkCid(CID, "/oak/ipfs/content/lost");

            assertEquals(2, node.count("/api/v0/files/cp"));
            assertTrue(client.fileExists("/oak/ipfs/content/lost"));
        }
    }

    @Test
    public void testListingHandlesNullEntriesAndEscapedNames() throws Exception {
        try (FakeIpfsNode node = new FakeIpfsNode()) {
            DefaultIpfsClient client = new DefaultIpfsClient(node.endpoint());
            String name = "a\"b<c>&\u00e9";
            client.writeFile("/oak/ipfs/odd/" + name, CONTENT);
            node.nullEntriesPath = "/oak/ipfs/empty";

            assertEquals(List.of(name), client.listFiles("/oak/ipfs/odd"));
            assertTrue(client.listFiles("/oak/ipfs/empty").isEmpty());
        }
    }

    private static IpfsClientSettings settings(FakeIpfsNode node, String authorization, Duration requestTimeout,
                                               int maxAttempts) {
        return new IpfsClientSettings(node.endpoint(), authorization, Duration.ofSeconds(5), requestTimeout,
            maxAttempts);
    }

    private static void assertThrowsNonNotFound(java.util.concurrent.Callable<?> call) {
        try {
            call.call();
        } catch (Exception e) {
            assertFalse(DefaultIpfsClient.isNotFound(e));
            return;
        }
        throw new AssertionError("Expected a failure that is not reported as a missing path");
    }

    private static final class FakeIpfsNode implements AutoCloseable {

        private final HttpServer server;
        private final List<RequestSnapshot> requests = java.util.Collections.synchronizedList(new ArrayList<>());
        private final Map<String, byte[]> files = new LinkedHashMap<>();
        private final Set<String> directories = new HashSet<>();
        private final Map<String, Deque<Integer>> failures = new ConcurrentHashMap<>();
        private final Map<String, String> hashes = new ConcurrentHashMap<>();
        private volatile boolean failAll;
        private volatile long delayMillis;
        private volatile long stallBodyMillis;
        private volatile boolean pinMissing;
        private volatile int lostCopies;
        private volatile String nullEntriesPath;

        private FakeIpfsNode() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/v0/", this::handle);
            server.start();
            directories.add("/");
        }

        private String endpoint() {
            return "/ip4/127.0.0.1/tcp/" + server.getAddress().getPort();
        }

        private RequestSnapshot lastRequest(String path) {
            for (int i = requests.size() - 1; i >= 0; i--) {
                if (requests.get(i).path.equals(path)) {
                    return requests.get(i);
                }
            }
            throw new AssertionError("No request recorded for path " + path);
        }

        private void failNext(String path, int status, int times) {
            Deque<Integer> queue = failures.computeIfAbsent(path, key -> new ArrayDeque<>());
            for (int i = 0; i < times; i++) {
                queue.add(status);
            }
        }

        private long count(String path) {
            return requests.stream().filter(request -> request.path.equals(path)).count();
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
                headers.getFirst("Authorization"),
                requestBody
            ));

            String path = exchange.getRequestURI().getPath();
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            Deque<Integer> pending = failures.get(path);
            Integer failure = pending == null ? null : pending.poll();
            if (failure != null) {
                byte[] bytes = "{\"Message\":\"injected failure\",\"Code\":0,\"Type\":\"error\"}"
                    .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(failure, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
                return;
            }
            if (failAll) {
                respondError(exchange, "{\"Message\":\"context deadline exceeded\",\"Code\":0,\"Type\":\"error\"}");
                return;
            }
            if ("/api/v0/version".equals(path)) {
                respondJson(exchange, "{\"Version\":\"" + VERSION + "\"}");
                return;
            }
            if ("/api/v0/add".equals(path)) {
                respondJson(exchange, "{\"Name\":\"payload.bin\",\"Hash\":\"" + CID + "\",\"Size\":\"" + CONTENT.length + "\"}");
                return;
            }
            if ("/api/v0/pin/rm".equals(path)) {
                respondJson(exchange, "{\"Pins\":[\"" + CID + "\"]}");
                return;
            }
            if ("/api/v0/cat".equals(path)) {
                if (stallBodyMillis > 0) {
                    exchange.sendResponseHeaders(200, 0);
                    exchange.getResponseBody().write(CONTENT, 0, 3);
                    exchange.getResponseBody().flush();
                    try {
                        Thread.sleep(stallBodyMillis);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    try {
                        exchange.getResponseBody().write(CONTENT, 3, CONTENT.length - 3);
                    } catch (IOException e) {
                        // the client gave up
                    }
                    exchange.close();
                    return;
                }
                respondBytes(exchange, CONTENT, "application/octet-stream");
                return;
            }
            if ("/api/v0/pin/ls".equals(path)) {
                if (pinMissing) {
                    respondError(exchange, "{\"Message\":\"path '" + queryArg(exchange) + "' is not pinned\",\"Code\":0,\"Type\":\"error\"}");
                    return;
                }
                respondJson(exchange, "{\"Keys\":{\"" + queryArg(exchange) + "\":{\"Type\":\"recursive\"}}}");
                return;
            }
            if ("/api/v0/files/mkdir".equals(path)) {
                createDirectory(queryArg(exchange));
                respondJson(exchange, "{\"Message\":\"ok\"}");
                return;
            }
            if ("/api/v0/files/write".equals(path)) {
                String filePath = queryArg(exchange);
                createDirectory(parent(filePath));
                files.put(filePath, extractMultipartPayload(headers.getFirst("Content-Type"), requestBody));
                respondJson(exchange, "{\"Message\":\"ok\"}");
                return;
            }
            if ("/api/v0/files/cp".equals(path)) {
                String[] args = queryArgs(exchange);
                if (args.length >= 2) {
                    if (files.containsKey(args[1])) {
                        respondError(exchange, "{\"Message\":\"directory already has entry by that name\",\"Code\":0,\"Type\":\"error\"}");
                        return;
                    }
                    createDirectory(parent(args[1]));
                    files.put(args[1], ("copied:" + args[0]).getBytes(StandardCharsets.UTF_8));
                    hashes.put(args[1], args[0].substring("/ipfs/".length()));
                }
                if (lostCopies > 0) {
                    lostCopies--;
                    exchange.sendResponseHeaders(504, -1);
                    exchange.close();
                    return;
                }
                respondJson(exchange, "{\"Message\":\"ok\"}");
                return;
            }
            if ("/api/v0/files/stat".equals(path)) {
                String target = queryArg(exchange);
                if (files.containsKey(target)) {
                    String hash = hashes.get(target);
                    respondJson(exchange, "{\"Size\":" + files.get(target).length
                        + (hash == null ? "" : ",\"Hash\":\"" + hash + "\"") + "}");
                    return;
                }
                if (directories.contains(target)) {
                    respondJson(exchange, "{\"Size\":0}");
                    return;
                }
                respondNotFound(exchange);
                return;
            }
            if ("/api/v0/files/ls".equals(path)) {
                String target = queryArg(exchange);
                if (target.equals(nullEntriesPath)) {
                    respondJson(exchange, "{\"Entries\":null}");
                    return;
                }
                List<String> children = directChildren(target);
                StringBuilder body = new StringBuilder("{\"Entries\":[");
                for (int i = 0; i < children.size(); i++) {
                    if (i > 0) {
                        body.append(',');
                    }
                    body.append("{\"Name\":\"").append(goJsonEscape(children.get(i)))
                        .append("\",\"Type\":0,\"Size\":0,\"Hash\":\"\"}");
                }
                body.append("]}");
                respondJson(exchange, body.toString());
                return;
            }
            if ("/api/v0/files/read".equals(path)) {
                String target = queryArg(exchange);
                byte[] data = files.get(target);
                if (data == null) {
                    respondNotFound(exchange);
                    return;
                }
                respondBytes(exchange, data, "application/octet-stream");
                return;
            }
            if ("/api/v0/files/rm".equals(path)) {
                if (files.remove(queryArg(exchange)) == null) {
                    respondNotFound(exchange);
                    return;
                }
                respondJson(exchange, "{\"Message\":\"ok\"}");
                return;
            }


            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        }

        private String queryArg(HttpExchange exchange) throws IOException {
            String[] args = queryArgs(exchange);
            return args.length > 0 ? args[0] : "";
        }

        private String[] queryArgs(HttpExchange exchange) throws IOException {
            String rawQuery = exchange.getRequestURI().getRawQuery();
            if (rawQuery == null || rawQuery.isEmpty()) {
                return new String[0];
            }
            List<String> args = new ArrayList<>();
            for (String param : rawQuery.split("&")) {
                if (param.startsWith("arg=")) {
                    args.add(URLDecoder.decode(param.substring(4), StandardCharsets.UTF_8.name()));
                }
            }
            return args.toArray(new String[0]);
        }

        private void createDirectory(String path) {
            if (path == null || path.isEmpty() || "/".equals(path)) {
                directories.add("/");
                return;
            }
            String current = "";
            for (String part : path.substring(1).split("/")) {
                current = current + "/" + part;
                directories.add(current);
            }
        }

        private static String parent(String path) {
            int separator = path.lastIndexOf('/');
            if (separator <= 0) {
                return "/";
            }
            return path.substring(0, separator);
        }

        private List<String> directChildren(String path) {
            String prefix = path.endsWith("/") ? path : path + "/";
            List<String> children = new ArrayList<>();
            for (String filePath : files.keySet()) {
                if (!filePath.startsWith(prefix)) {
                    continue;
                }
                String remainder = filePath.substring(prefix.length());
                if (!remainder.isEmpty() && !remainder.contains("/")) {
                    children.add(remainder);
                }
            }
            Collections.sort(children);
            return children;
        }

        private static byte[] extractMultipartPayload(String contentType, byte[] body) {
            String boundary = contentType.substring(contentType.indexOf("boundary=") + "boundary=".length());
            String payload = new String(body, StandardCharsets.ISO_8859_1);
            String delimiter = "--" + boundary;
            int dataStart = payload.indexOf("\r\n\r\n");
            if (dataStart < 0) {
                return new byte[0];
            }
            dataStart += 4;
            int dataEnd = payload.indexOf("\r\n" + delimiter, dataStart);
            if (dataEnd < 0) {
                dataEnd = payload.length();
            }
            return payload.substring(dataStart, dataEnd).getBytes(StandardCharsets.ISO_8859_1);
        }

        private static String goJsonEscape(String value) {
            StringBuilder escaped = new StringBuilder();
            for (char c : value.toCharArray()) {
                if (c == '"' || c == '\\') {
                    escaped.append('\\').append(c);
                } else if (c == '<' || c == '>' || c == '&' || c > 0x7e) {
                    escaped.append(String.format("\\u%04x", (int) c));
                } else {
                    escaped.append(c);
                }
            }
            return escaped.toString();
        }

        private static void respondNotFound(HttpExchange exchange) throws IOException {
            respondError(exchange, "{\"Message\":\"file does not exist\",\"Code\":0,\"Type\":\"error\"}");
        }

        private static void respondError(HttpExchange exchange, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(500, bytes.length);
            exchange.getResponseBody().write(bytes);
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
        private final String authorization;
        private final byte[] body;

        private RequestSnapshot(String method, String path, String query, String contentType,
                                String authorization, byte[] body) {
            this.method = method;
            this.path = path;
            this.query = query;
            this.contentType = contentType;
            this.authorization = authorization;
            this.body = body;
        }
    }
}
