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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class IpfsClusterClientTest {

    private static final String CID = "QmYwAPJzv5CZsnAzt8auVZRnGi2C4gYQqbiZ9erjRzCQXD";

    private HttpServer server;
    private final List<String> requests = Collections.synchronizedList(new ArrayList<>());
    private final List<String> authorizations = Collections.synchronizedList(new ArrayList<>());
    private final Deque<String[]> statusRounds = new LinkedList<>();
    private volatile int postStatus = 200;
    private volatile String postBody = "{}";
    private volatile int deleteStatus = 200;

    @Before
    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/pins/", this::handle);
        server.start();
    }

    @After
    public void stop() {
        server.stop(0);
    }

    @Test
    public void testWaitsUntilTheMinimumOfPeersHasPinned() throws Exception {
        statusRounds.add(new String[] {"remote", "remote", "remote"});
        statusRounds.add(new String[] {"pinning", "pin_queued", "pinning"});
        statusRounds.add(new String[] {"pinned", "pinning", "remote"});
        statusRounds.add(new String[] {"pinned", "pinned", "remote"});

        client(2, 3, Duration.ofSeconds(10), "Basic b2FrOnBhc3M=").pinReplicated(CID, "oak-id");

        assertEquals("GET /pins/" + CID, requests.get(0));
        String post = requests.get(1);
        assertTrue(post, post.startsWith("POST /pins/" + CID + "?"));
        assertTrue(post.contains("replication-min=2"));
        assertTrue(post.contains("replication-max=3"));
        assertTrue(post.contains("name=oak-id"));
        assertEquals(5, requests.size());
        assertTrue(authorizations.stream().allMatch("Basic b2FrOnBhc3M="::equals));
    }

    @Test
    public void testFailsEarlyWhenTooFewPeersCanStillPin() throws Exception {
        statusRounds.add(new String[] {"pinned", "pin_error", "cluster_error"});
        long start = System.nanoTime();
        try {
            client(2, 3, Duration.ofSeconds(30), null).pinReplicated(CID, "oak-id");
            fail("Expected the replication minimum to be unreachable");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("cannot pin"));
            assertTrue(e.getMessage().contains("pin_error"));
        }
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toSeconds() < 5);
    }

    @Test
    public void testGivesUpAfterThePinTimeout() throws Exception {
        for (int i = 0; i < 100; i++) {
            statusRounds.add(new String[] {"pinned", "pinning", "pinning"});
        }
        try {
            client(2, 3, Duration.ofMillis(600), null).pinReplicated(CID, "oak-id");
            fail("Expected a timeout");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("on only 1 of the required 2 peers"));
        }
    }

    @Test
    public void testClusterErrorsAreReported() throws Exception {
        postStatus = 500;
        postBody = "{\"code\":500,\"message\":\"not enough peers to allocate CID\"}";
        try {
            client(2, 3, Duration.ofSeconds(5), null).pinReplicated(CID, "oak-id");
            fail("Expected the cluster error");
        } catch (HttpRpc.RpcException e) {
            assertEquals(500, e.status());
            assertTrue(e.getMessage().contains("not enough peers to allocate CID"));
        }
    }

    @Test
    public void testUnpinIgnoresAMissingPinButNotOtherErrors() throws Exception {
        IpfsClusterClient client = client(2, 3, Duration.ofSeconds(5), null);
        deleteStatus = 404;
        client.unpin(CID);
        assertEquals("DELETE /pins/" + CID, requests.get(0));

        deleteStatus = 500;
        try {
            client.unpin(CID);
            fail("Expected the cluster error");
        } catch (HttpRpc.RpcException e) {
            assertEquals(500, e.status());
        }
    }

    @Test
    public void testStatusesAreKeyedByPeerId() throws Exception {
        statusRounds.add(new String[] {"pinned", "remote"});

        assertEquals(List.of(
                new IpfsClusterClient.PeerPin("12D3KooPeer0", "cluster0", "pinned", ""),
                new IpfsClusterClient.PeerPin("12D3KooPeer1", "cluster1", "remote", "")),
            client(1, 2, Duration.ofSeconds(5), null).statuses(CID));
    }

    @Test
    public void testAlreadyReplicatedContentIsNotPinnedAgain() throws Exception {
        statusRounds.add(new String[] {"pinned", "pinned", "remote"});

        client(2, 3, Duration.ofSeconds(5), null).pinReplicated(CID, "oak-id");

        assertEquals(List.of("GET /pins/" + CID), requests);
    }

    @Test
    public void testPinErrorsAreRecoveredOnce() throws Exception {
        statusRounds.add(new String[] {"remote", "remote", "remote"});
        statusRounds.add(new String[] {"pinned", "pin_error", "remote"});
        statusRounds.add(new String[] {"pinned", "pinning", "remote"});
        statusRounds.add(new String[] {"pinned", "pinned", "remote"});

        client(2, 3, Duration.ofSeconds(10), null).pinReplicated(CID, "oak-id");

        assertEquals(1, requests.stream().filter(r -> r.equals("POST /pins/" + CID + "/recover")).count());
    }

    @Test
    public void testAPinErrorThatPersistsAfterRecoveryFails() throws Exception {
        statusRounds.add(new String[] {"remote", "remote"});
        statusRounds.add(new String[] {"pinned", "pin_error"});
        try {
            client(2, 2, Duration.ofSeconds(10), null).pinReplicated(CID, "oak-id");
            fail("Expected the pin error to be reported");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("cluster1=pin_error(boom)"));
        }
        assertEquals(1, requests.stream().filter(r -> r.endsWith("/recover")).count());
    }

    @Test
    public void testTransientStatusFailuresAreRetriedUntilTheTimeout() throws Exception {
        statusRounds.add(new String[] {"remote", "remote"});
        statusRounds.add(null);
        statusRounds.add(new String[] {"unexpectedly_unpinned", "pinned"});
        statusRounds.add(new String[] {"pinned", "pinned"});

        client(2, 2, Duration.ofSeconds(10), null).pinReplicated(CID, "oak-id");
    }

    @Test
    public void testNonCidsAreRejectedBeforeAnyRequest() {
        try {
            client(1, 1, Duration.ofSeconds(5), null).pinReplicated("../peers", "oak-id");
            fail("Expected the CID to be rejected");
        } catch (IOException e) {
            assertTrue(requests.isEmpty());
        }
    }

    @Test
    public void testPingCallsTheIdEndpoint() throws Exception {
        server.createContext("/id", exchange -> respond(exchange, 200, "{\"id\":\"12D3KooPeer0\"}"));
        client(1, 1, Duration.ofSeconds(5), "Bearer t").ping();
    }

    private IpfsClusterClient client(int min, int max, Duration timeout, String authorization) {
        IpfsClusterSettings settings = new IpfsClusterSettings(
            "http://127.0.0.1:" + server.getAddress().getPort(), authorization, min, max, timeout, false);
        return new IpfsClusterClient(settings, IpfsClientSettings.of(null));
    }

    private void handle(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getRawQuery();
        requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath()
            + (query == null ? "" : "?" + query));
        authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
        switch (exchange.getRequestMethod()) {
            case "POST":
                respond(exchange, postStatus, postBody);
                return;
            case "DELETE":
                respond(exchange, deleteStatus, deleteStatus == 404
                    ? "{\"code\":404,\"message\":\"pin is not part of the pinset\"}" : "{}");
                return;
            default:
                if (statusRounds.size() > 1 && statusRounds.peek() == null) {
                    statusRounds.poll();
                    respond(exchange, 500, "{\"code\":500,\"message\":\"peer monitor restarting\"}");
                    return;
                }
                String[] round = statusRounds.size() > 1 ? statusRounds.poll() : statusRounds.peek();
                StringBuilder peers = new StringBuilder();
                for (int i = 0; round != null && i < round.length; i++) {
                    if (i > 0) {
                        peers.append(',');
                    }
                    peers.append("\"12D3KooPeer").append(i).append("\":{\"peername\":\"cluster").append(i)
                        .append("\",\"status\":\"").append(round[i]).append("\",\"error\":\"")
                        .append("pin_error".equals(round[i]) ? "boom" : "").append("\"}");
                }
                respond(exchange, 200, "{\"cid\":\"" + CID + "\",\"peer_map\":{" + peers + "}}");
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
