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

import org.apache.jackrabbit.oak.commons.json.JsonObject;
import org.apache.jackrabbit.oak.commons.json.JsopTokenizer;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Pins content through the IPFS Cluster REST API and waits until enough peers
 * hold it.
 *
 * <p>A pin's allocations are not replicas: a peer whose IPFS node has just
 * failed can still be allocated and then report {@code pin_error}. Progress is
 * therefore measured by peers whose status is {@code pinned}. Waiting ends
 * early when the peers that could still pin are too few, after one attempt to
 * recover pins in {@code pin_error}. The cluster keeps one pin per CID
 * without reference counts, so {@link #unpin} is only safe when the cluster
 * serves a single Oak repository.</p>
 */
final class IpfsClusterClient {

    private static final long POLL_MILLIS = 250;
    private static final Set<String> PENDING =
        Set.of("pinning", "pin_queued", "unpinned", "undefined", "unexpectedly_unpinned");
    private static final Pattern CID_FORMAT = Pattern.compile("Qm[1-9A-HJ-NP-Za-km-z]{44}|b[a-z2-7]{20,}");

    private final IpfsClusterSettings settings;
    private final HttpRpc rpc;

    IpfsClusterClient(IpfsClusterSettings settings, IpfsClientSettings connection) {
        this(settings, connection, null);
    }

    IpfsClusterClient(IpfsClusterSettings settings, IpfsClientSettings connection, SSLContext sslContext) {
        this.settings = settings;
        this.rpc = new HttpRpc("IPFS Cluster", settings.apiBase(), settings.authorization(),
            connection.connectTimeout(), connection.requestTimeout(), connection.maxAttempts(), sslContext);
    }

    /**
     * Checks that the cluster's REST API answers.
     */
    void ping() throws IOException {
        rpc.text("GET", "id", "");
    }

    /**
     * Pins the CID with the configured replication factors, unless enough peers
     * already hold it, and returns once at least {@code replicationMin} peers
     * have it pinned.
     *
     * @param name label shown in the cluster's pinset, e.g. the Oak identifier
     * @throws IOException if the minimum cannot be reached within the pin timeout
     */
    void pinReplicated(String cid, String name) throws IOException {
        if (!CID_FORMAT.matcher(cid).matches()) {
            throw new IOException("Not a CID: " + cid);
        }
        try {
            if (pinned(statuses(cid)) >= settings.replicationMin()) {
                return;
            }
        } catch (InterruptedIOException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            // Unknown state: pin it.
        }
        rpc.text("POST", "pins/" + cid, "replication-min=" + settings.replicationMin()
            + "&replication-max=" + settings.replicationMax()
            + "&name=" + URLEncoder.encode(name, StandardCharsets.UTF_8));
        long deadline = System.nanoTime() + settings.pinTimeout().toNanos();
        boolean recovered = false;
        List<PeerPin> peers = List.of();
        Exception lastError = null;
        while (true) {
            try {
                peers = statuses(cid);
                lastError = null;
            } catch (InterruptedIOException e) {
                throw e;
            } catch (IOException | RuntimeException e) {
                lastError = e;
            }
            if (lastError == null) {
                long pinned = pinned(peers);
                if (pinned >= settings.replicationMin()) {
                    return;
                }
                long possible = pinned + peers.stream().filter(peer -> PENDING.contains(peer.status())).count();
                if (possible < settings.replicationMin()) {
                    if (recovered || peers.stream().noneMatch(peer -> "pin_error".equals(peer.status()))) {
                        throw new IOException("IPFS Cluster cannot pin " + cid + " on " + settings.replicationMin()
                            + " peers: " + describe(peers));
                    }
                    // pin_error is sticky until recovered, e.g. after an IPFS node restarted.
                    recovered = true;
                    try {
                        rpc.text("POST", "pins/" + cid + "/recover", "");
                    } catch (InterruptedIOException e) {
                        throw e;
                    } catch (IOException e) {
                        lastError = e;
                    }
                }
            }
            if (System.nanoTime() > deadline) {
                throw new IOException("IPFS Cluster pinned " + cid + " on only " + pinned(peers) + " of the required "
                    + settings.replicationMin() + " peers within " + settings.pinTimeout() + ": " + describe(peers),
                    lastError);
            }
            try {
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("Interrupted waiting for IPFS Cluster to pin " + cid);
            }
        }
    }

    /**
     * Removes the CID from the cluster pinset; a CID that is not pinned is
     * ignored.
     */
    void unpin(String cid) throws IOException {
        try {
            rpc.text("DELETE", "pins/" + cid, "");
        } catch (HttpRpc.RpcException e) {
            if (e.status() != 404) {
                throw e;
            }
        }
    }

    /**
     * Returns each peer's status for the CID.
     */
    List<PeerPin> statuses(String cid) throws IOException {
        JsonObject peerMap = JsonObject.fromJson(rpc.text("GET", "pins/" + cid, ""), true)
            .getChildren().get("peer_map");
        List<PeerPin> peers = new ArrayList<>();
        if (peerMap == null) {
            return peers;
        }
        for (Map.Entry<String, JsonObject> peer : peerMap.getChildren().entrySet()) {
            Map<String, String> properties = peer.getValue().getProperties();
            peers.add(new PeerPin(peer.getKey(), text(properties.get("peername")),
                properties.containsKey("status") ? text(properties.get("status")) : "undefined",
                text(properties.get("error"))));
        }
        return peers;
    }

    private static long pinned(List<PeerPin> peers) {
        return peers.stream().filter(peer -> "pinned".equals(peer.status())).count();
    }

    private static String describe(List<PeerPin> peers) {
        return peers.stream().map(PeerPin::toString).collect(Collectors.joining(", ", "[", "]"));
    }

    private static String text(String json) {
        return json == null || "null".equals(json) ? "" : JsopTokenizer.decodeQuoted(json);
    }

    /**
     * One cluster peer's state for a pin, keyed by its peer ID.
     */
    record PeerPin(String peer, String name, String status, String error) {
        @Override
        public String toString() {
            return (name.isEmpty() ? peer : name) + "=" + status + (error.isEmpty() ? "" : "(" + error + ")");
        }
    }
}
