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
import org.apache.http.StatusLine;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class HttpValidatorHealthProbeTest {

    private HttpServer server;
    private String baseUrl;

    @Before
    public void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/journal.log", exchange -> respond(exchange, 200, "head".getBytes(StandardCharsets.UTF_8)));
        server.createContext("/broken/journal.log", exchange -> respond(exchange, 503, "down".getBytes(StandardCharsets.UTF_8)));
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
    public void testReportsAvailableWhenJournalReturns200() {
        HttpValidatorHealthProbe probe = new HttpValidatorHealthProbe();

        assertTrue(probe.isAvailable(baseUrl, 1000));
    }

    @Test
    public void testReportsUnavailableForNon200Response() {
        HttpValidatorHealthProbe probe = new HttpValidatorHealthProbe();

        assertFalse(probe.isAvailable(baseUrl + "/broken", 1000));
    }

    @Test
    public void testReportsUnavailableWhenConnectionFails() {
        HttpValidatorHealthProbe probe = new HttpValidatorHealthProbe();

        assertFalse(probe.isAvailable("http://127.0.0.1:1", 100));
    }

    @Test
    public void testReportsUnavailableWhenResponseCloseFails() throws Exception {
        CloseableHttpResponse response = mock(CloseableHttpResponse.class);
        org.apache.http.impl.client.CloseableHttpClient client = mock(org.apache.http.impl.client.CloseableHttpClient.class);
        StatusLine statusLine = mock(StatusLine.class);
        when(statusLine.getStatusCode()).thenReturn(200);
        when(response.getStatusLine()).thenReturn(statusLine);
        when(client.execute(any())).thenReturn(response);
        doThrow(new IOException("close failed")).when(response).close();

        HttpValidatorHealthProbe probe = new HttpValidatorHealthProbe(requestConfig -> client);

        assertFalse(probe.isAvailable(baseUrl, 1000));
    }

    @Test
    public void testRuntimeFailureDuringStatusReadIsPropagated() throws Exception {
        CloseableHttpResponse response = mock(CloseableHttpResponse.class);
        org.apache.http.impl.client.CloseableHttpClient client = mock(org.apache.http.impl.client.CloseableHttpClient.class);
        when(client.execute(any())).thenReturn(response);
        when(response.getStatusLine()).thenThrow(new RuntimeException("status failed"));

        HttpValidatorHealthProbe probe = new HttpValidatorHealthProbe(requestConfig -> client);

        try {
            probe.isAvailable(baseUrl, 1000);
            fail("Expected runtime status failure to propagate");
        } catch (RuntimeException e) {
            assertTrue(e.getMessage().contains("status failed"));
        }
    }

    private static void respond(HttpExchange exchange, int statusCode, byte[] body) throws IOException {
        exchange.sendResponseHeaders(statusCode, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }
}
