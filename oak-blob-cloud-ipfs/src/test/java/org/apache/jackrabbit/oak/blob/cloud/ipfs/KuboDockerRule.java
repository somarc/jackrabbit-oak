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
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

/**
 * Starts an offline Kubo daemon in Docker for tests that need real IPFS
 * semantics. Tests are skipped when Docker is unavailable; any other startup
 * failure fails them.
 */
public class KuboDockerRule extends ExternalResource {

    private static final DockerImageName IMAGE = DockerImageName.parse("ipfs/kubo:v0.43.1");
    private static final int API_PORT = 5001;

    private GenericContainer<?> container;

    @Override
    protected void before() {
        container = new GenericContainer<>(IMAGE)
            .withCommand("daemon", "--offline")
            .withExposedPorts(API_PORT)
            .waitingFor(Wait.forLogMessage(".*Daemon is ready.*", 1))
            .withStartupTimeout(Duration.ofSeconds(90));
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
}
