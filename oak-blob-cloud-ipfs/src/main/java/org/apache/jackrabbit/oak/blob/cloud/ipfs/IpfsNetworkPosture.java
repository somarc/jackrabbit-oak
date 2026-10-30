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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Checks whether the Kubo node behind the RPC API keeps content off the public
 * IPFS network.
 *
 * <p>A default Kubo node joins the public network: it bootstraps to public
 * peers, announces every block it stores to the public DHT, asks public HTTP
 * routers, and is reachable through public relays. The node's configuration
 * shows those settings but not whether it uses a private network key
 * ({@code swarm.key}). That is proven on the wire instead: a node with a key
 * answers a connection with a random nonce, a public node with the plaintext
 * multistream header. The wire probe needs the swarm listener's address as
 * Oak reaches it, so it runs only when one is configured.</p>
 */
final class IpfsNetworkPosture {

    private static final Logger LOG = LoggerFactory.getLogger(IpfsNetworkPosture.class);

    /**
     * What to do about a node that may expose content.
     */
    enum Policy {
        /** Do not inspect the node. */
        OFF,
        /** Log each exposure as a warning. */
        WARN,
        /** Refuse to start unless the node is proven private and exposes nothing. */
        PRIVATE;

        static Policy parse(String value) {
            if (value == null || value.isBlank()) {
                return WARN;
            }
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("ipfsNetworkPolicy must be off, warn or private, not " + value, e);
            }
        }
    }

    /**
     * @param privateSwarm {@code true} if the wire probe saw a private network,
     *                     {@code false} if it saw a public one, {@code null} if
     *                     it did not run or was inconclusive
     * @param exposures    settings that send content or metadata outside the
     *                     node's private network
     */
    record Report(Boolean privateSwarm, List<String> exposures) {
        boolean provenPrivate() {
            return Boolean.TRUE.equals(privateSwarm) && exposures.isEmpty();
        }
    }

    static final byte[] MULTISTREAM = "\u0013/multistream/1.0.0\n".getBytes(StandardCharsets.US_ASCII);

    private IpfsNetworkPosture() {
    }

    /**
     * Inspects the node's configuration and listeners and, when
     * {@code probeAddress} ({@code host:port}) is given, its swarm listener.
     */
    static Report inspect(IpfsClient ipfs, String probeAddress, Duration probeTimeout) throws Exception {
        Boolean privateSwarm = probeAddress == null ? null : probe(probeAddress, probeTimeout);
        JsonObject config = JsonObject.fromJson(ipfs.config(), true);
        List<String> exposures = new ArrayList<>();
        if (Boolean.FALSE.equals(privateSwarm)) {
            exposures.add("the swarm at " + probeAddress + " has no private network key (swarm.key)");
        }
        // HTTP routers and AutoConf are plain HTTPS calls that a swarm key does not cover.
        List<String> routers = strings(property(config, "Routing", "DelegatedRouters"));
        String routing = text(property(config, "Routing", "Type"));
        JsonObject customRouters = child(config, "Routing", "Routers");
        boolean httpRouter = customRouters != null && customRouters.getChildren().values().stream()
            .anyMatch(r -> "\"http\"".equals(r.getProperties().get("Type")));
        if (routing == null || List.of("auto", "autoclient", "delegated").contains(routing) || httpRouter
                || routers.stream().anyMatch(r -> "auto".equals(r) || r.startsWith("http"))) {
            exposures.add("routing asks public HTTP routers (Routing.Type=" + routing
                + ", Routing.DelegatedRouters=" + routers + ")");
        }
        if (!"false".equals(property(config, "AutoConf", "Enabled"))) {
            exposures.add("AutoConf fetches network settings from a public server (AutoConf.Enabled is not false)");
        }
        if (!Boolean.TRUE.equals(privateSwarm)) {
            // Without a key these reach the public network; with one they stay inside it.
            List<String> bootstrap = strings(property(config, "Bootstrap"));
            if (bootstrap.stream().anyMatch(b -> "auto".equals(b) || b.contains("bootstrap.libp2p.io"))) {
                exposures.add("Bootstrap connects to public peers " + bootstrap);
            }
            if (!"false".equals(property(config, "Provide", "Enabled"))) {
                exposures.add("stored blocks are announced to the network (Provide.Enabled is not false)");
            }
            if (!"false".equals(property(config, "Swarm", "RelayClient", "Enabled"))) {
                exposures.add("the node is reachable through relays (Swarm.RelayClient.Enabled is not false)");
            }
            List<String> nonTcp = ipfs.swarmListenAddresses().stream()
                .filter(a -> a.contains("/quic") || a.contains("/webtransport") || a.contains("/webrtc"))
                .toList();
            if (!nonTcp.isEmpty()) {
                // A private network supports TCP only, so these listeners mean the node has no key.
                exposures.add("the swarm listens on transports a private network cannot use " + nonTcp);
            }
        }
        return new Report(privateSwarm, exposures);
    }

    /**
     * Opens a connection to a libp2p swarm listener and reports whether it
     * speaks the private network protocol.
     *
     * @return {@code true} for a private network, {@code false} for the public
     *         one, {@code null} if the listener could not be reached or answered
     *         neither way in time
     */
    static Boolean probe(String hostAndPort, Duration timeout) {
        int colon = hostAndPort.lastIndexOf(':');
        if (colon <= 0) {
            throw new IllegalArgumentException("ipfsSwarmProbeAddress must be host:port, not " + hostAndPort);
        }
        String host = hostAndPort.substring(0, colon).replace("[", "").replace("]", "");
        int port = Integer.parseInt(hostAndPort.substring(colon + 1));
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), (int) timeout.toMillis());
            socket.setSoTimeout((int) timeout.toMillis());
            OutputStream out = socket.getOutputStream();
            out.write(MULTISTREAM);
            out.flush();
            InputStream in = socket.getInputStream();
            byte[] head = in.readNBytes(MULTISTREAM.length);
            if (head.length < MULTISTREAM.length) {
                return null;
            }
            return !Arrays.equals(head, MULTISTREAM);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Returns the raw JSON value at the path, or {@code null} when it is
     * missing.
     */
    private static String property(JsonObject config, String... path) {
        JsonObject node = child(config, Arrays.copyOf(path, path.length - 1));
        return node == null ? null : node.getProperties().get(path[path.length - 1]);
    }

    private static JsonObject child(JsonObject config, String... path) {
        JsonObject node = config;
        for (int i = 0; i < path.length && node != null; i++) {
            node = node.getChildren().get(path[i]);
        }
        return node;
    }

    private static String text(String json) {
        return json == null || "null".equals(json) ? null : JsopTokenizer.decodeQuoted(json);
    }

    /**
     * Returns the strings in a raw JSON array; {@code null} gives an empty list.
     */
    static List<String> strings(String json) {
        List<String> values = new ArrayList<>();
        if (json == null || "null".equals(json)) {
            return values;
        }
        JsopTokenizer tokenizer = new JsopTokenizer(json);
        tokenizer.read('[');
        if (!tokenizer.matches(']')) {
            do {
                values.add(tokenizer.readString());
            } while (tokenizer.matches(','));
            tokenizer.read(']');
        }
        return values;
    }

    /**
     * Logs each exposure and, under {@link Policy#PRIVATE}, fails unless the
     * node is proven private and exposes nothing.
     */
    static void enforce(Policy policy, Report report, String endpoint) throws IOException {
        for (String exposure : report.exposures()) {
            LOG.warn("IPFS node {} may expose content outside its private network: {}", endpoint, exposure);
        }
        if (report.privateSwarm() == null) {
            LOG.info("IPFS node {} was not checked for a private network key; set ipfsSwarmProbeAddress to its"
                + " swarm listener (host:port) to check it", endpoint);
        } else if (report.provenPrivate()) {
            LOG.info("IPFS node {} uses a private network and exposes nothing outside it", endpoint);
        }
        if (policy == Policy.PRIVATE && !report.provenPrivate()) {
            List<String> reasons = new ArrayList<>(report.exposures());
            if (report.privateSwarm() == null) {
                reasons.add("no private network key was proven (set ipfsSwarmProbeAddress)");
            }
            throw new IOException("ipfsNetworkPolicy=private, but IPFS node " + endpoint + " is not private: "
                + String.join("; ", reasons));
        }
    }
}
