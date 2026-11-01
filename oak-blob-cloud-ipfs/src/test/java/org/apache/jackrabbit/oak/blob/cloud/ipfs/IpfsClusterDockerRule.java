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

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Ports;
import org.apache.jackrabbit.oak.commons.json.JsonObject;
import org.apache.jackrabbit.oak.commons.json.JsopReader;
import org.apache.jackrabbit.oak.commons.json.JsopTokenizer;
import org.junit.Assume;
import org.junit.rules.ExternalResource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

/**
 * Starts N private-swarm Kubo nodes, each paired with an IPFS Cluster peer (CRDT consensus),
 * on one Docker network. {@link #before()} returns once every cluster peer has received the
 * allocation metrics of all N peers, i.e. once a pin with replication-min=N can be allocated.
 * Skips the test when Docker is not available.
 */
public class IpfsClusterDockerRule extends ExternalResource {

    public static final DockerImageName KUBO_IMAGE = DockerImageName.parse("ipfs/kubo:v0.43.1");
    public static final DockerImageName CLUSTER_IMAGE = DockerImageName.parse("ipfs/ipfs-cluster:v1.1.6");

    private static final int KUBO_RPC_PORT = 5001;
    private static final int CLUSTER_API_PORT = 9094;
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    // runs once, after `ipfs init` and before the daemon starts (kubo image /container-init.d hook)
    private static final String PRIVATE_SWARM_INIT = String.join("\n",
            "#!/bin/sh",
            "set -e",
            "ipfs bootstrap rm --all",
            "ipfs config --json AutoConf.Enabled false",
            "ipfs config --json AutoTLS.Enabled false",
            "ipfs config Routing.Type dht",
            "ipfs config --json Routing.DelegatedRouters '[]'",
            "ipfs config --json Ipns.DelegatedPublishers '[]'",
            "ipfs config --json DNS.Resolvers '{}'",
            "ipfs config --json Discovery.MDNS.Enabled false",
            "ipfs config --json Swarm.Transports.Network.Websocket false",
            "");

    private final int size;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final List<GenericContainer<?>> kubos = new ArrayList<>();
    private final List<GenericContainer<?>> clusters = new ArrayList<>();
    private final List<String> kuboPeerIds = new ArrayList<>();
    private Network network;

    public IpfsClusterDockerRule() {
        this(3);
    }

    public IpfsClusterDockerRule(int size) {
        this.size = size;
    }

    @Override
    protected void before() throws Throwable {
        Assume.assumeTrue("Docker is not available", DockerClientFactory.instance().isDockerAvailable());
        try {
            start();
        } catch (Throwable t) {
            after();
            throw t;
        }
    }

    @Override
    protected void after() {
        clusters.forEach(GenericContainer::stop);
        kubos.forEach(GenericContainer::stop);
        if (network != null) {
            network.close();
        }
    }

    public int size() {
        return size;
    }

    /** Kubo RPC API of node {@code i} as a host multiaddr, e.g. {@code /ip4/127.0.0.1/tcp/32768}. */
    public String kuboRpcMultiaddr(int i) {
        return "/ip4/127.0.0.1/tcp/" + hostPort(kubos.get(i), KUBO_RPC_PORT);
    }

    /** Kubo RPC API of node {@code i} as an HTTP base URL. */
    public URI kuboRpcUrl(int i) {
        return URI.create("http://127.0.0.1:" + hostPort(kubos.get(i), KUBO_RPC_PORT));
    }

    /** IPFS Cluster REST API (v1) of node {@code i}. */
    public URI clusterApiUrl(int i) {
        return URI.create("http://127.0.0.1:" + hostPort(clusters.get(i), CLUSTER_API_PORT));
    }

    /** Stops node {@code i}'s Kubo container, keeping its repo and identity for {@link #startKubo}. */
    public void stopKubo(int i) {
        GenericContainer<?> kubo = kubos.get(i);
        kubo.getDockerClient().stopContainerCmd(kubo.getContainerId()).exec();
    }

    /** Restarts a stopped Kubo container and reconnects it to the other Kubo nodes. */
    public void startKubo(int i) throws IOException, InterruptedException {
        GenericContainer<?> kubo = kubos.get(i);
        kubo.getDockerClient().startContainerCmd(kubo.getContainerId()).exec();
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (true) {
            try {
                kuboPeerId(i);
                break;
            } catch (IOException e) {
                if (System.nanoTime() > deadline) {
                    throw e;
                }
                Thread.sleep(250);
            }
        }
        connectKubo(i);
    }

    private void start() throws IOException, InterruptedException {
        network = Network.newNetwork();
        String swarmKey = "/key/swarm/psk/1.0.0/\n/base16/\n" + randomHex();
        String clusterSecret = randomHex();

        for (int i = 0; i < size; i++) {
            kubos.add(new GenericContainer<>(KUBO_IMAGE)
                    .withNetwork(network)
                    .withNetworkAliases("kubo" + i)
                    .withEnv("IPFS_SWARM_KEY", swarmKey)
                    .withEnv("LIBP2P_FORCE_PNET", "1")
                    .withCopyToContainer(Transferable.of(PRIVATE_SWARM_INIT, 0755),
                            "/container-init.d/001-private-swarm.sh")
                    .withExposedPorts(KUBO_RPC_PORT)
                    .waitingFor(Wait.forLogMessage(".*Daemon is ready.*\\n", 1).withStartupTimeout(TIMEOUT)));
        }
        Startables.deepStart(kubos).join();
        for (int i = 0; i < size; i++) {
            kuboPeerIds.add(kuboPeerId(i));
        }
        for (int i = 0; i < size; i++) {
            connectKubo(i);
        }

        for (int i = 0; i < size; i++) {
            clusters.add(new GenericContainer<>(CLUSTER_IMAGE)
                    .withNetwork(network)
                    .withNetworkAliases("cluster" + i)
                    .withEnv("CLUSTER_SECRET", clusterSecret)
                    .withEnv("CLUSTER_PEERNAME", "cluster" + i)
                    .withEnv("CLUSTER_IPFSHTTP_NODEMULTIADDRESS", "/dns4/kubo" + i + "/tcp/" + KUBO_RPC_PORT)
                    .withEnv("CLUSTER_CRDT_TRUSTEDPEERS", "*")
                    .withEnv("CLUSTER_RESTAPI_HTTPLISTENMULTIADDRESS", "/ip4/0.0.0.0/tcp/" + CLUSTER_API_PORT)
                    .withEnv("CLUSTER_MONITORPINGINTERVAL", "2s")
                    // informers re-publish every metric_ttl/2; the default 30s makes joining take ~15s
                    .withEnv("CLUSTER_DISK_METRICTTL", "4s")
                    .withEnv("CLUSTER_TAGS_METRICTTL", "4s")
                    .withEnv("CLUSTER_PINQUEUE_METRICTTL", "4s")
                    .withEnv("CLUSTER_MDNSINTERVAL", "0")
                    .withExposedPorts(CLUSTER_API_PORT)
                    .waitingFor(Wait.forLogMessage(".*IPFS Cluster is READY.*\\n", 1).withStartupTimeout(TIMEOUT)));
        }
        clusters.get(0).start();
        String bootstrap = "/dns4/cluster0/tcp/9096/p2p/" + string(getJson(clusterApiUrl(0).resolve("/id")), "id");
        List<GenericContainer<?>> joiners = clusters.subList(1, size);
        joiners.forEach(c -> c.withEnv("CLUSTER_PEERADDRESSES", bootstrap));
        Startables.deepStart(joiners).join();
        awaitAllocatable();
    }

    // full mesh; the cluster's ipfshttp connector would also do this, but only after connect_swarms_delay (30s).
    // A restarted node can race its peers' own redials (libp2p simultaneous open), so a failed dial is retried
    // and an existing connection counts as success.
    private void connectKubo(int i) throws IOException, InterruptedException {
        for (int j = 0; j < size; j++) {
            if (j == i) {
                continue;
            }
            for (int attempt = 1; ; attempt++) {
                try {
                    post(kuboRpcUrl(i).resolve("/api/v0/swarm/connect?arg=/dns4/kubo" + j + "/tcp/4001/p2p/"
                            + kuboPeerIds.get(j)));
                    break;
                } catch (IOException e) {
                    if (post(kuboRpcUrl(i).resolve("/api/v0/swarm/peers")).contains(kuboPeerIds.get(j))) {
                        break;
                    }
                    if (attempt == 10) {
                        throw e;
                    }
                    Thread.sleep(300);
                }
            }
        }
    }

    // the allocator only considers peers whose informer metrics (freespace, tag:group, ...) it has
    // received; being listed in GET /peers is not enough (POST /pins then fails with "not enough peers")
    private void awaitAllocatable() throws IOException, InterruptedException {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        for (int i = 0; i < size; i++) {
            while (!allocatable(clusterApiUrl(i))) {
                if (System.nanoTime() > deadline) {
                    throw new IOException("cluster peer " + i + " has not received the metrics of all " + size + " peers");
                }
                Thread.sleep(250);
            }
        }
    }

    private boolean allocatable(URI api) throws IOException, InterruptedException {
        JsopTokenizer names = new JsopTokenizer(get(api.resolve("/monitor/metrics")));
        names.read('[');
        if (names.matches(']')) {
            return false;
        }
        do {
            if (validPeers(api, names.read(JsopReader.STRING)) < size) {
                return false;
            }
        } while (names.matches(','));
        return true;
    }

    private int validPeers(URI api, String metric) throws IOException, InterruptedException {
        JsopTokenizer t = new JsopTokenizer(get(api.resolve("/monitor/metrics/" + metric)));
        Set<String> peers = new HashSet<>();
        t.read('[');
        if (!t.matches(']')) {
            do {
                t.read('{');
                JsonObject m = JsonObject.create(t, true);
                if ("true".equals(m.getProperties().get("valid"))) {
                    peers.add(string(m, "peer"));
                }
            } while (t.matches(','));
        }
        return peers.size();
    }

    private String kuboPeerId(int i) throws IOException, InterruptedException {
        return string(JsonObject.fromJson(post(kuboRpcUrl(i).resolve("/api/v0/id")), true), "ID");
    }

    private JsonObject getJson(URI uri) throws IOException, InterruptedException {
        return JsonObject.fromJson(get(uri), true);
    }

    private String get(URI uri) throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(uri).GET());
    }

    private String post(URI uri) throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(uri).POST(HttpRequest.BodyPublishers.noBody()));
    }

    private String send(HttpRequest.Builder request) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(request.timeout(Duration.ofSeconds(10)).build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException(response.request().uri() + " -> " + response.statusCode() + " " + response.body());
        }
        return response.body();
    }

    // the mapped port can change across stop/start, so ask Docker instead of the cached container info
    private static int hostPort(GenericContainer<?> container, int port) {
        Ports.Binding[] bindings = container.getDockerClient().inspectContainerCmd(container.getContainerId()).exec()
                .getNetworkSettings().getPorts().getBindings().get(ExposedPort.tcp(port));
        if (bindings == null || bindings.length == 0) {
            throw new IllegalStateException("port " + port + " of " + container.getContainerName() + " is not published");
        }
        return Integer.parseInt(bindings[0].getHostPortSpec());
    }

    private static String string(JsonObject json, String key) {
        return JsopTokenizer.decodeQuoted(json.getProperties().get(key));
    }

    private static String randomHex() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
