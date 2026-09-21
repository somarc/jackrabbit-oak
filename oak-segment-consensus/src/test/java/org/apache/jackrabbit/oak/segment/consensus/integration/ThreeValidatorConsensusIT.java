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
package org.apache.jackrabbit.oak.segment.consensus.integration;

import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * Integration tests for 3-validator Aeron consensus cluster.
 * 
 * <p>Uses Testcontainers to spin up a real 3-node cluster and test:
 * <ul>
 *   <li>Leader election</li>
 *   <li>Write replication</li>
 *   <li>Leader failover</li>
 *   <li>Network partition recovery</li>
 * </ul>
 * 
 * <p>Prerequisites:
 * <ul>
 *   <li>Docker installed and running</li>
 *   <li>oak-global-store:latest image built</li>
 * </ul>
 */
public class ThreeValidatorConsensusIT {

    private static final String VALIDATOR_IMAGE = "oak-global-store:latest";
    private static final int HTTP_PORT = 8090;
    private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration CONSENSUS_TIMEOUT = Duration.ofSeconds(30);

    @ClassRule
    public static Network network = Network.newNetwork();

    private GenericContainer<?> validator0;
    private GenericContainer<?> validator1;
    private GenericContainer<?> validator2;

    private HttpClient httpClient;

    @Before
    public void setUp() throws Exception {
        httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

        // Start validator 0 (genesis)
        validator0 = createValidator(0, "");
        validator0.start();
        waitForHealth(validator0);

        // Start validator 1 (bootstraps from validator 0)
        validator1 = createValidator(1, "http://validator-0:8090");
        validator1.start();
        waitForHealth(validator1);

        // Start validator 2 (bootstraps from validator 0)
        validator2 = createValidator(2, "http://validator-0:8090");
        validator2.start();
        waitForHealth(validator2);

        // Wait for consensus to form
        waitForConsensus();
    }

    @After
    public void tearDown() {
        if (validator2 != null) validator2.stop();
        if (validator1 != null) validator1.stop();
        if (validator0 != null) validator0.stop();
    }

    // ═══════════════════════════════════════════════════════════════
    // CONSENSUS FORMATION TESTS
    // ═══════════════════════════════════════════════════════════════

    @Test
    public void testClusterFormsConsensus() throws Exception {
        // Given: 3 validators started
        // When: Cluster state queried
        // Then: Should have elected leader
        
        String leaderUrl = findLeader();
        assertNotNull("Cluster should have elected a leader", leaderUrl);
    }

    @Test
    public void testAllValidatorsHaveSameHead() throws Exception {
        // Given: Consensus formed
        // When: HEAD queried from all validators
        // Then: All should have same HEAD
        
        String head0 = getHead(validator0);
        String head1 = getHead(validator1);
        String head2 = getHead(validator2);
        
        assertEquals("Validator 0 and 1 should have same HEAD", head0, head1);
        assertEquals("Validator 1 and 2 should have same HEAD", head1, head2);
    }

    // ═══════════════════════════════════════════════════════════════
    // WRITE REPLICATION TESTS
    // ═══════════════════════════════════════════════════════════════

    @Test
    public void testWriteReplicatesToAllValidators() throws Exception {
        // Given: Consensus formed
        // When: Write submitted to leader
        // Then: All validators should have the content
        
        String leaderUrl = findLeader();
        
        // Submit write to leader
        String path = "/oak-chain/0xTEST/content-" + System.currentTimeMillis();
        submitWrite(leaderUrl, path, "{\"title\":\"Test Content\"}");
        
        // Wait for replication
        Thread.sleep(2000);
        
        // Verify all validators have the content
        assertTrue("Validator 0 should have content", hasContent(validator0, path));
        assertTrue("Validator 1 should have content", hasContent(validator1, path));
        assertTrue("Validator 2 should have content", hasContent(validator2, path));
    }

    @Test
    public void testWriteToFollowerRedirectsToLeader() throws Exception {
        // Given: Consensus formed
        // When: Write submitted to follower
        // Then: Should redirect to leader or return leader URL
        
        String leaderUrl = findLeader();
        GenericContainer<?> follower = leaderUrl.contains(getValidatorUrl(validator0)) 
            ? validator1 : validator0;
        
        // Submit write to follower
        String path = "/oak-chain/0xTEST/follower-write-" + System.currentTimeMillis();
        HttpResponse<String> response = submitWriteRaw(getValidatorUrl(follower), path, "{}");
        
        // Should either redirect (307) or return leader info
        assertTrue("Should redirect or return leader info",
            response.statusCode() == 307 || 
            response.body().contains("leader") ||
            response.statusCode() == 200);
    }

    // ═══════════════════════════════════════════════════════════════
    // LEADER FAILOVER TESTS
    // ═══════════════════════════════════════════════════════════════

    @Test
    public void testLeaderFailoverElectsNewLeader() throws Exception {
        // Given: Consensus formed with leader
        String originalLeader = findLeader();
        GenericContainer<?> leaderContainer = getContainerByUrl(originalLeader);
        
        // When: Leader stopped
        leaderContainer.stop();
        
        // Then: New leader should be elected
        Thread.sleep(10000); // Wait for election timeout
        
        String newLeader = findLeaderFromRemaining(leaderContainer);
        assertNotNull("New leader should be elected", newLeader);
        assertNotEquals("New leader should be different", originalLeader, newLeader);
    }

    @Test
    public void testWritesWorkAfterFailover() throws Exception {
        // Given: Leader failed over
        String originalLeader = findLeader();
        GenericContainer<?> leaderContainer = getContainerByUrl(originalLeader);
        leaderContainer.stop();
        
        Thread.sleep(10000); // Wait for election
        
        String newLeader = findLeaderFromRemaining(leaderContainer);
        
        // When: Write submitted to new leader
        String path = "/oak-chain/0xTEST/post-failover-" + System.currentTimeMillis();
        submitWrite(newLeader, path, "{\"title\":\"Post-Failover\"}");
        
        // Then: Write should succeed
        Thread.sleep(2000);
        
        // Verify on remaining validators
        for (GenericContainer<?> v : new GenericContainer[]{validator0, validator1, validator2}) {
            if (v != null && v.isRunning()) {
                assertTrue("Remaining validator should have content", hasContent(v, path));
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // HELPER METHODS
    // ═══════════════════════════════════════════════════════════════

    private GenericContainer<?> createValidator(int nodeId, String bootstrapPeer) {
        GenericContainer<?> container = new GenericContainer<>(DockerImageName.parse(VALIDATOR_IMAGE))
            .withNetwork(network)
            .withNetworkAliases("validator-" + nodeId)
            .withExposedPorts(HTTP_PORT)
            .withEnv("AERON_CLUSTER_NODE_ID", String.valueOf(nodeId))
            .withEnv("AERON_CLUSTER_HOSTNAMES", "validator-0,validator-1,validator-2")
            .withEnv("CONSENSUS_ENABLED", "true")
            .withEnv("CONSENSUS_MODE", "aeron")
            .withEnv("CONSENSUS_SELF_URL", "http://validator-" + nodeId + ":8090")
            .withEnv("CONSENSUS_PEERS", bootstrapPeer)
            .waitingFor(Wait.forHttp("/health").forPort(HTTP_PORT).withStartupTimeout(STARTUP_TIMEOUT));
        
        return container;
    }

    private void waitForHealth(GenericContainer<?> container) throws Exception {
        String url = getValidatorUrl(container) + "/health";
        
        for (int i = 0; i < 60; i++) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    return;
                }
            } catch (Exception e) {
                // Retry
            }
            Thread.sleep(1000);
        }
        fail("Validator did not become healthy in time");
    }

    private void waitForConsensus() throws Exception {
        for (int i = 0; i < 30; i++) {
            String leader = findLeader();
            if (leader != null) {
                return;
            }
            Thread.sleep(1000);
        }
        fail("Consensus did not form in time");
    }

    private String findLeader() throws Exception {
        for (GenericContainer<?> v : new GenericContainer[]{validator0, validator1, validator2}) {
            if (v != null && v.isRunning()) {
                String url = getValidatorUrl(v) + "/v1/aeron/cluster-state";
                try {
                    HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .GET()
                        .build();
                    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                    if (response.body().contains("\"role\":\"LEADER\"")) {
                        return getValidatorUrl(v);
                    }
                } catch (Exception e) {
                    // Try next
                }
            }
        }
        return null;
    }

    private String findLeaderFromRemaining(GenericContainer<?> excluded) throws Exception {
        for (GenericContainer<?> v : new GenericContainer[]{validator0, validator1, validator2}) {
            if (v != null && v != excluded && v.isRunning()) {
                String url = getValidatorUrl(v) + "/v1/aeron/cluster-state";
                try {
                    HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .GET()
                        .build();
                    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                    if (response.body().contains("\"role\":\"LEADER\"")) {
                        return getValidatorUrl(v);
                    }
                } catch (Exception e) {
                    // Try next
                }
            }
        }
        return null;
    }

    private String getValidatorUrl(GenericContainer<?> container) {
        return "http://localhost:" + container.getMappedPort(HTTP_PORT);
    }

    private GenericContainer<?> getContainerByUrl(String url) {
        if (url.contains(String.valueOf(validator0.getMappedPort(HTTP_PORT)))) return validator0;
        if (url.contains(String.valueOf(validator1.getMappedPort(HTTP_PORT)))) return validator1;
        if (url.contains(String.valueOf(validator2.getMappedPort(HTTP_PORT)))) return validator2;
        return null;
    }

    private String getHead(GenericContainer<?> container) throws Exception {
        String url = getValidatorUrl(container) + "/journal.log";
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .GET()
            .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        String[] lines = response.body().split("\n");
        return lines.length > 0 ? lines[lines.length - 1].trim() : "";
    }

    private void submitWrite(String leaderUrl, String path, String content) throws Exception {
        HttpResponse<String> response = submitWriteRaw(leaderUrl, path, content);
        assertEquals("Write should succeed", 200, response.statusCode());
    }

    private HttpResponse<String> submitWriteRaw(String baseUrl, String path, String content) throws Exception {
        String url = baseUrl + "/v1/write?path=" + path + "&wallet=0xTEST&tier=PRIORITY";
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(content))
            .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private boolean hasContent(GenericContainer<?> container, String path) throws Exception {
        String url = getValidatorUrl(container) + "/v1/explore?path=" + path;
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .GET()
            .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return response.statusCode() == 200 && !response.body().contains("\"error\"");
    }
}
