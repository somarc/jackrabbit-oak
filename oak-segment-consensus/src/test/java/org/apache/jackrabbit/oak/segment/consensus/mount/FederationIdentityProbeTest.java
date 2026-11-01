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

import com.sun.net.httpserver.HttpServer;
import org.apache.jackrabbit.oak.segment.http.server.util.JsonParser;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.apache.jackrabbit.oak.segment.consensus.federation.FederationFixtures.ENTERPRISE_A;
import static org.apache.jackrabbit.oak.segment.consensus.federation.FederationFixtures.ENTERPRISE_B;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class FederationIdentityProbeTest {

    private static final String CLUSTER_B = "0x" + "0b".repeat(32);
    private static final String OTHER_CLUSTER = "0x" + "0c".repeat(32);

    private HttpServer server;
    private volatile int status = 200;
    private volatile String body;
    private volatile long delayMs;
    private final FederationIdentityProbe probe =
        new FederationIdentityProbe(Duration.ofMillis(500), Duration.ofMillis(500));

    @Before
    public void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/federation", exchange -> {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        serving(ENTERPRISE_B, CLUSTER_B);
    }

    @After
    public void stopServer() {
        server.stop(0);
    }

    @Test
    public void testPassesWhenEnterpriseAndPinnedClusterMatch() throws Exception {
        probe.verify(endpoint(), ENTERPRISE_B, CLUSTER_B);
        probe.verify(endpoint(), ENTERPRISE_B, "");
    }

    @Test
    public void testRejectsAnotherEnterprise() {
        serving(ENTERPRISE_A, CLUSTER_B);
        assertRejected(ENTERPRISE_B, CLUSTER_B, "serves enterprise " + ENTERPRISE_A);
    }

    @Test
    public void testRejectsAnotherClusterOnlyWhenTheEntryPinsOne() throws Exception {
        serving(ENTERPRISE_B, OTHER_CLUSTER);
        assertRejected(ENTERPRISE_B, CLUSTER_B, "reports cluster " + OTHER_CLUSTER);
        probe.verify(endpoint(), ENTERPRISE_B, "");
    }

    @Test
    public void testRejectsAnEndpointWithoutCharterOrWithAnError() {
        body = JsonParser.toJson(Map.of("directory", Map.of()));
        assertRejected(ENTERPRISE_B, "", "no committed charter");
        status = 503;
        assertRejected(ENTERPRISE_B, "", "HTTP 503");
    }

    @Test
    public void testUnreachableAndSlowEndpointsFailWithinTheTimeouts() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        try {
            probe.verify("http://127.0.0.1:" + closedPort, ENTERPRISE_B, "");
            fail("unreachable endpoint must fail the identity check");
        } catch (IOException expected) {
            // connection refused
        }

        delayMs = 3_000L;
        long start = System.nanoTime();
        assertRejected(ENTERPRISE_B, "", "");
        assertTrue("bounded by the request timeout", System.nanoTime() - start < 2_000_000_000L);
    }

    private void serving(String enterpriseId, String clusterId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("charter", Map.of("enterpriseId", enterpriseId));
        payload.put("clusterId", clusterId);
        body = JsonParser.toJson(payload);
    }

    private String endpoint() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void assertRejected(String enterpriseId, String clusterId, String reason) {
        try {
            probe.verify(endpoint(), enterpriseId, clusterId);
            fail("expected the identity check to fail");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(reason));
        }
    }
}
