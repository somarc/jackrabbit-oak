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
package org.apache.jackrabbit.oak.segment.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class Http2ClientPoolTest {

    private HttpServer server;
    private String baseUrl;

    @Before
    public void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/bytes", exchange -> respond(exchange, 200, new byte[] {1, 2, 3, 4}));
        server.createContext("/text", exchange -> respond(exchange, 200, "oak-segment-http".getBytes(StandardCharsets.UTF_8)));
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

        assertEquals("oak-segment-http", body);
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
}
