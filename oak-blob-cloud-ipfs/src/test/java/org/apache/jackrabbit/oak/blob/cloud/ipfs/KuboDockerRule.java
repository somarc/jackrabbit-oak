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

import org.junit.Assume;
import org.junit.rules.ExternalResource;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Starts a Kubo daemon in Docker for tests that need real IPFS semantics:
 * offline by default, or {@link #online} with or without a private network
 * key, optionally requiring a bearer token on its RPC API
 * ({@code API.Authorizations}). Tests are skipped when Docker is unavailable;
 * any other startup failure fails them.
 */
public class KuboDockerRule extends ExternalResource {

    private static final DockerImageName IMAGE = DockerImageName.parse("ipfs/kubo:v0.43.1");
    private static final int API_PORT = 5001;
    private static final int SWARM_PORT = 4001;

    private final String bearerToken;
    private final StringBuilder configCommands = new StringBuilder();
    private GenericContainer<?> container;
    private boolean online;
    private String swarmKey;

    public KuboDockerRule() {
        this(null);
    }

    /**
     * @param bearerToken token the RPC API requires, or {@code null} for none
     */
    public KuboDockerRule(String bearerToken) {
        this.bearerToken = bearerToken;
    }

    /**
     * Applies a Kubo config value (as JSON) before the daemon starts.
     */
    public KuboDockerRule configured(String key, String json) {
        configCommands.append("ipfs config --json ").append(key).append(" '").append(json).append("'\n");
        return this;
    }

    /**
     * Runs the daemon with its swarm listening (port 4001 exposed) but without
     * public bootstrap peers, routing or AutoConf, so it stays off the public
     * network.
     *
     * @param swarmKey private network key file content, or {@code null} for a
     *                 node without one
     */
    public KuboDockerRule online(String swarmKey) {
        this.online = true;
        this.swarmKey = swarmKey;
        configured("Bootstrap", "[]");
        configured("AutoConf.Enabled", "false");
        configured("Routing.Type", "\"none\"");
        configured("Routing.DelegatedRouters", "[]");
        configured("Ipns.DelegatedPublishers", "[]");
        configured("DNS.Resolvers", "{}");
        return this;
    }

    @Override
    protected void before() {
        container = new GenericContainer<>(IMAGE)
            .withCommand(online ? new String[] {"daemon"} : new String[] {"daemon", "--offline"})
            .withExposedPorts(online ? new Integer[] {API_PORT, SWARM_PORT} : new Integer[] {API_PORT})
            .waitingFor(Wait.forLogMessage(".*Daemon is ready.*", 1))
            .withStartupTimeout(Duration.ofSeconds(90));
        if (bearerToken != null) {
            configured("API.Authorizations",
                "{\"oak\":{\"AuthSecret\":\"bearer:" + bearerToken + "\",\"AllowedPaths\":[\"/api/v0\"]}}");
        }
        if (swarmKey != null) {
            container.withEnv("IPFS_SWARM_KEY", swarmKey).withEnv("LIBP2P_FORCE_PNET", "1");
        }
        if (configCommands.length() > 0) {
            container.withCopyToContainer(Transferable.of("#!/bin/sh\nset -e\n" + configCommands, 0755),
                "/container-init.d/010-oak-config.sh");
        }
        container.start();
    }

    @Override
    protected void after() {
        if (container != null) {
            container.stop();
        }
    }

    @Override
    public Statement apply(Statement base, Description description) {
        return new Statement() {
            @Override
            public void evaluate() throws Throwable {
                Assume.assumeTrue("Docker is not available", DockerClientFactory.instance().isDockerAvailable());
                before();
                try {
                    base.evaluate();
                } finally {
                    after();
                }
            }
        };
    }

    /**
     * Returns the Kubo RPC API multiaddr on the host.
     */
    public String apiEndpoint() {
        return "/ip4/127.0.0.1/tcp/" + container.getMappedPort(API_PORT);
    }

    /**
     * Returns the swarm listener on the host as {@code host:port}; requires
     * {@link #online}.
     */
    public String swarmAddress() {
        return "127.0.0.1:" + container.getMappedPort(SWARM_PORT);
    }

    /**
     * Calls a Kubo RPC command directly, for test assertions, and returns the
     * response body.
     *
     * @param commandAndQuery for example {@code cid/format?arg=Qm...&v=1}
     */
    public String rpc(String commandAndQuery) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(
                "http://127.0.0.1:" + container.getMappedPort(API_PORT) + "/api/v0/" + commandAndQuery))
            .POST(HttpRequest.BodyPublishers.noBody());
        if (bearerToken != null) {
            request.header("Authorization", "Bearer " + bearerToken);
        }
        HttpResponse<String> response = HttpClient.newHttpClient()
            .send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Kubo " + commandAndQuery + " failed: " + response.body());
        }
        return response.body();
    }
}
