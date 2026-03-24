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
package org.apache.jackrabbit.oak.segment.consensus.mount.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class Http2ClientPoolTest {

    private HttpServer server;
    private String baseUrl;

    @Before
    public void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/bytes", exchange -> respond(exchange, 200, new byte[] {1, 2, 3, 4}));
        server.createContext("/text", exchange -> respond(exchange, 200, "consensus-mount-http".getBytes(StandardCharsets.UTF_8)));
        server.createContext("/exists", exchange -> respond(exchange, 200, new byte[0]));
        server.createContext("/missing", exchange -> respond(exchange, 404, "missing".getBytes(StandardCharsets.UTF_8)));
        server.createContext("/error", exchange -> respond(exchange, 500, "boom".getBytes(StandardCharsets.UTF_8)));
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @After
    public void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    public void testGetReturnsBytesAndTracksStats() throws Exception {
        Http2ClientPool pool = new Http2ClientPool();

        byte[] body = pool.get(baseUrl + "/bytes");

        assertArrayEquals(new byte[] {1, 2, 3, 4}, body);
        assertNotNull(pool.getHttpClient());
        assertTrue(pool.getPoolStats().contains("Requests=1"));
        assertTrue(pool.getPoolStats().contains("HTTP/1.1=1"));
        assertTrue(pool.getPoolStats().contains("BytesReceived=4"));
    }

    @Test
    public void testGetStringReturnsBody() throws Exception {
        Http2ClientPool pool = new Http2ClientPool();

        String body = pool.getString(baseUrl + "/text");

        assertEquals("consensus-mount-http", body);
        assertTrue(pool.getPoolStats().contains("Requests=1"));
    }

    @Test
    public void testExistsUsesHeadAndReflectsStatusCode() {
        Http2ClientPool pool = new Http2ClientPool();

        assertTrue(pool.exists(baseUrl + "/exists"));
        assertFalse(pool.exists(baseUrl + "/missing"));
        assertTrue(pool.getPoolStats().contains("Requests=2"));
    }

    @Test
    public void testGetThrowsForNon200Response() throws Exception {
        Http2ClientPool pool = new Http2ClientPool();

        try {
            pool.get(baseUrl + "/error");
            fail("Expected non-200 response to throw");
        } catch (RuntimeException e) {
            assertTrue(e.getMessage().contains("HTTP 500"));
        }
    }

    @Test
    public void testExistsReturnsFalseWhenRequestFails() {
        Http2ClientPool pool = new Http2ClientPool();

        assertFalse(pool.exists("http://127.0.0.1:1/unreachable"));
    }

    @Test
    public void testInjectedHttp2ResponsesTrackHttp2StatsAndLifecycle() throws Exception {
        StubHttpClient httpClient = new StubHttpClient();
        httpClient.enqueue(new StubResponse<>(HttpClient.Version.HTTP_2, 200, new byte[] {9, 8}));
        httpClient.enqueue(new StubResponse<>(HttpClient.Version.HTTP_2, 200, "hello-h2"));
        httpClient.enqueue(new StubResponse<>(HttpClient.Version.HTTP_2, 200, null));
        Http2ClientPool pool = new Http2ClientPool(httpClient);

        assertArrayEquals(new byte[] {9, 8}, pool.get("http://validator.example/bytes"));
        assertEquals("hello-h2", pool.getString("http://validator.example/text"));
        assertTrue(pool.exists("http://validator.example/exists"));
        assertSame(HttpClient.Version.HTTP_2, pool.getHttpClient().version());

        String stats = pool.getPoolStats();
        assertTrue(stats.contains("Requests=3"));
        assertTrue(stats.contains("HTTP/2=3 (100.0%)"));
        assertTrue(stats.contains("HTTP/1.1=0"));
        assertTrue(stats.contains("BytesReceived=2"));

        pool.logStats();
        pool.shutdown();
    }

    @Test
    public void testInjectedGetStringThrowsForNon200Response() throws Exception {
        StubHttpClient httpClient = new StubHttpClient();
        httpClient.enqueue(new StubResponse<>(HttpClient.Version.HTTP_2, 500, "boom"));
        Http2ClientPool pool = new Http2ClientPool(httpClient);

        try {
            pool.getString("http://validator.example/error");
            fail("Expected non-200 response to throw");
        } catch (RuntimeException e) {
            assertTrue(e.getMessage().contains("HTTP 500"));
        }
    }

    private static void respond(HttpExchange exchange, int statusCode, byte[] body) throws IOException {
        if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(statusCode, -1);
            exchange.close();
            return;
        }

        exchange.sendResponseHeaders(statusCode, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private static final class StubHttpClient extends HttpClient {

        private final Queue<Object> responses = new ArrayDeque<>();

        void enqueue(Object responseOrThrowable) {
            responses.add(responseOrThrowable);
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.empty();
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NORMAL;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            return null;
        }

        @Override
        public SSLParameters sslParameters() {
            return new SSLParameters();
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_2;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler)
                throws IOException, InterruptedException {
            Object next = responses.remove();
            if (next instanceof IOException) {
                throw (IOException) next;
            }
            if (next instanceof InterruptedException) {
                throw (InterruptedException) next;
            }
            if (next instanceof RuntimeException) {
                throw (RuntimeException) next;
            }
            return (HttpResponse<T>) next;
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                                                                HttpResponse.BodyHandler<T> responseBodyHandler) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                                                                HttpResponse.BodyHandler<T> responseBodyHandler,
                                                                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class StubResponse<T> implements HttpResponse<T> {

        private final HttpClient.Version version;
        private final int statusCode;
        private final T body;

        private StubResponse(HttpClient.Version version, int statusCode, T body) {
            this.version = version;
            this.statusCode = statusCode;
            this.body = body;
        }

        @Override
        public int statusCode() {
            return statusCode;
        }

        @Override
        public HttpRequest request() {
            return HttpRequest.newBuilder(URI.create("http://validator.example")).build();
        }

        @Override
        public Optional<HttpResponse<T>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(Map.of(), (name, value) -> true);
        }

        @Override
        public T body() {
            return body;
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return URI.create("http://validator.example");
        }

        @Override
        public HttpClient.Version version() {
            return version;
        }
    }
}
