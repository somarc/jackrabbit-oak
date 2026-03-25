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
package org.apache.jackrabbit.oak.segment.http.server.handlers;

import org.apache.jackrabbit.oak.segment.consensus.aeron.AeronClusterLauncher;
import org.apache.jackrabbit.oak.segment.consensus.aeron.AeronConsensusEngine;
import org.apache.jackrabbit.oak.segment.consensus.aeron.CrashHandler;
import org.apache.jackrabbit.oak.segment.consensus.aeron.MediaDriverHealthMonitor;
import org.apache.jackrabbit.oak.segment.consensus.leader.ValidatorRole;
import org.apache.jackrabbit.oak.segment.consensus.sharding.ShardingRuntimeConfig;
import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.apache.jackrabbit.oak.segment.http.server.ServerContext;
import org.apache.jackrabbit.oak.segment.http.server.model.ClientRegistration;
import org.apache.jackrabbit.oak.segment.http.server.model.ValidatorRegistration;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.apache.jackrabbit.oak.spi.state.NodeStore;
import org.junit.Test;

import javax.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;

import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.*;

public class HealthHandlerTest {

    @Test
    public void testHandleHealthHealthyWithoutEngine() throws Exception {
        StringWriter body = new StringWriter();
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getWriter()).thenReturn(new PrintWriter(body));

        ServerContext context = new ServerContext(mock(FileStore.class), mock(NodeStore.class),
            Paths.get("/tmp/store"), "http://localhost:8090");

        HealthHandler handler = new HealthHandler(
            context.fileStore,
            context.nodeStore,
            context.storeDirectory,
            null,
            Collections.emptyMap(),
            Collections.emptyMap(),
            context
        );

        handler.handleHealth(response);

        verify(response).setStatus(HttpServletResponse.SC_OK);
        String json = body.toString();
        assertTrue(json.contains("\"status\":\"UP\""));
        assertTrue(json.contains("\"store\":"));
    }

    @Test
    public void testHandleHealthUnhealthyWithReason() throws Exception {
        StringWriter body = new StringWriter();
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getWriter()).thenReturn(new PrintWriter(body));

        ServerContext context = new ServerContext(mock(FileStore.class), mock(NodeStore.class),
            Paths.get("/tmp/store"), "http://localhost:8090");
        AeronConsensusEngine engine = mock(AeronConsensusEngine.class);
        when(engine.isClusterHealthy()).thenReturn(false);
        when(engine.getUnhealthyReason()).thenReturn("no_leader");
        when(engine.getReachableValidatorCount()).thenReturn(0);
        when(engine.getTotalMemberCount()).thenReturn(3);
        when(engine.getQuorumSize()).thenReturn(2);
        when(engine.getCurrentRole()).thenReturn(org.apache.jackrabbit.oak.segment.consensus.leader.ValidatorRole.FOLLOWER);
        when(engine.getCommittedHead()).thenReturn("");
        when(engine.getLatestHead()).thenReturn("");
        when(engine.getLatestEpochSeen()).thenReturn(-1);
        when(engine.getLastCommittedEpoch()).thenReturn(-1);
        context.aeronConsensusEngine = engine;

        HealthHandler handler = new HealthHandler(
            context.fileStore,
            context.nodeStore,
            context.storeDirectory,
            engine,
            Collections.emptyMap(),
            Collections.emptyMap(),
            context
        );

        handler.handleHealth(response);

        verify(response).setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        String json = body.toString();
        assertTrue(json.contains("\"unhealthyReason\":\"no_leader\""));
        assertTrue(json.contains("\"clusterHealthy\":false"));
    }

    @Test
    public void testHandleLocalHealthIgnoresClusterQuorum() throws Exception {
        StringWriter body = new StringWriter();
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getWriter()).thenReturn(new PrintWriter(body));

        ServerContext context = new ServerContext(mock(FileStore.class), mock(NodeStore.class),
            Paths.get("/tmp/store"), "http://localhost:8090");
        AeronConsensusEngine engine = mock(AeronConsensusEngine.class);
        when(engine.getCurrentRole()).thenReturn(ValidatorRole.LEADER);
        when(engine.isClusterHealthy()).thenReturn(false);
        context.aeronConsensusEngine = engine;

        HealthHandler handler = new HealthHandler(
            context.fileStore,
            context.nodeStore,
            context.storeDirectory,
            engine,
            Collections.emptyMap(),
            Collections.emptyMap(),
            context
        );

        handler.handleLocalHealth(response);

        verify(response).setStatus(HttpServletResponse.SC_OK);
        String json = body.toString();
        assertTrue(json.contains("\"scope\":\"local\""));
        assertTrue(json.contains("\"status\":\"UP\""));
        assertTrue(json.contains("\"currentRole\":\"LEADER\""));
    }

    @Test
    public void testHandleHealthIncludesBlobStoreAndCommitProgress() throws Exception {
        StringWriter body = new StringWriter();
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getWriter()).thenReturn(new PrintWriter(body));

        ServerContext context = newContext(Files.createTempDirectory("health-progress"));
        context.blobStoreType = "ipfs";
        context.blobStore = mock(org.apache.jackrabbit.oak.spi.blob.BlobStore.class);

        AeronConsensusEngine engine = mock(AeronConsensusEngine.class);
        when(engine.isClusterHealthy()).thenReturn(true);
        when(engine.getReachableValidatorCount()).thenReturn(3);
        when(engine.getTotalMemberCount()).thenReturn(3);
        when(engine.getQuorumSize()).thenReturn(2);
        when(engine.getCurrentRole()).thenReturn(ValidatorRole.LEADER);
        when(engine.getCommittedHead()).thenReturn("committed-head");
        when(engine.getLatestHead()).thenReturn("latest-head");
        when(engine.getLatestEpochSeen()).thenReturn(12);
        when(engine.getLastCommittedEpoch()).thenReturn(11);
        context.aeronConsensusEngine = engine;

        HealthHandler handler = newHandler(context);
        handler.handleHealth(response);

        verify(response).setStatus(HttpServletResponse.SC_OK);
        String json = body.toString();
        assertTrue(json.contains("\"blobStoreType\":\"ipfs\""));
        assertTrue(json.contains("\"blobStoreActive\":true"));
        assertTrue(json.contains("\"committedHead\":\"committed-head\""));
        assertTrue(json.contains("\"latestHead\":\"latest-head\""));
        assertTrue(json.contains("\"latestEpochSeen\":12"));
        assertTrue(json.contains("\"committedEpoch\":11"));
    }

    @Test
    public void testHandleClusterHealthUnavailableWithoutEngine() throws Exception {
        StringWriter body = new StringWriter();
        HttpServletResponse response = responseWithBody(body);
        ServerContext context = newContext(Files.createTempDirectory("health-cluster-unavailable"));

        HealthHandler handler = newHandler(context);
        handler.handleClusterHealth(response);

        verify(response).setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        String json = body.toString();
        assertTrue(json.contains("\"status\":\"UNAVAILABLE\""));
        assertTrue(json.contains("\"reason\":\"cluster_not_initialized\""));
        assertTrue(json.contains("\"success\":false"));
    }

    @Test
    public void testHandleClusterHealthUnhealthyIncludesQuorumMetrics() throws Exception {
        StringWriter body = new StringWriter();
        HttpServletResponse response = responseWithBody(body);
        ServerContext context = newContext(Files.createTempDirectory("health-cluster-unhealthy"));
        AeronConsensusEngine engine = mock(AeronConsensusEngine.class);
        when(engine.isClusterHealthy()).thenReturn(false);
        when(engine.getUnhealthyReason()).thenReturn("session_timeout");
        when(engine.getReachableValidatorCount()).thenReturn(1);
        when(engine.getTotalMemberCount()).thenReturn(3);
        when(engine.getQuorumSize()).thenReturn(2);
        when(engine.hasQuorum()).thenReturn(false);
        when(engine.getLastHeartbeatTime()).thenReturn(1234L);
        when(engine.getHeartbeatAgeMs()).thenReturn(5678L);
        when(engine.getCurrentLeaderHint()).thenReturn("http://leader:8090");
        context.aeronConsensusEngine = engine;

        HealthHandler handler = newHandler(context);
        handler.handleClusterHealth(response);

        verify(response).setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        String json = body.toString();
        assertTrue(json.contains("\"status\":\"UNHEALTHY\""));
        assertTrue(json.contains("\"unhealthyReason\":\"session_timeout\""));
        assertTrue(json.contains("\"reachableCount\":1"));
        assertTrue(json.contains("\"hasQuorum\":false"));
        assertTrue(json.contains("\"leaderUrl\":\"http://leader:8090\""));
    }

    @Test
    public void testHandleClusterHealthHealthy() throws Exception {
        StringWriter body = new StringWriter();
        HttpServletResponse response = responseWithBody(body);
        ServerContext context = newContext(Files.createTempDirectory("health-cluster-healthy"));
        AeronConsensusEngine engine = mock(AeronConsensusEngine.class);
        when(engine.isClusterHealthy()).thenReturn(true);
        when(engine.getReachableValidatorCount()).thenReturn(3);
        when(engine.getTotalMemberCount()).thenReturn(3);
        when(engine.getQuorumSize()).thenReturn(2);
        when(engine.hasQuorum()).thenReturn(true);
        when(engine.getLastHeartbeatTime()).thenReturn(4321L);
        when(engine.getHeartbeatAgeMs()).thenReturn(55L);
        when(engine.getCurrentLeaderHint()).thenReturn("http://leader:8090");
        context.aeronConsensusEngine = engine;

        HealthHandler handler = newHandler(context);
        handler.handleClusterHealth(response);

        verify(response).setStatus(HttpServletResponse.SC_OK);
        String json = body.toString();
        assertTrue(json.contains("\"status\":\"UP\""));
        assertTrue(json.contains("\"success\":true"));
        assertTrue(json.contains("\"hasQuorum\":true"));
        assertTrue(json.contains("\"leaderUrl\":\"http://leader:8090\""));
    }

    @Test
    public void testHandleDeepHealthHealthySubsystems() throws Exception {
        StringWriter body = new StringWriter();
        HttpServletResponse response = responseWithBody(body);
        Path storeDirectory = Files.createTempDirectory("health-deep-healthy");
        FileStore fileStore = mock(FileStore.class, RETURNS_DEEP_STUBS);
        when(fileStore.getHead().getRecordId().toString10()).thenReturn("0123456789abcdef0123456789");
        NodeStore nodeStore = mock(NodeStore.class);
        when(nodeStore.getRoot()).thenReturn(mock(NodeState.class));

        ServerContext context = new ServerContext(fileStore, nodeStore, storeDirectory, "http://localhost:8090");
        context.registeredClients.put("client-1",
            new ClientRegistration("client-1", "http://author-1:4502", "0x1111111111111111111111111111111111111111"));
        context.registeredValidators.put("validator-1",
            new ValidatorRegistration("validator-1", "http://validator-1:8090"));
        context.blobStoreType = "ipfs";
        context.blobStore = mock(org.apache.jackrabbit.oak.spi.blob.BlobStore.class);
        context.cidMappingService = mock(org.apache.jackrabbit.oak.segment.http.server.binary.CidMappingService.class);
        context.setAuthoritativeNodeStore(mock(NodeStore.class));
        context.setShardingRuntimeConfig(ShardingRuntimeConfig.fromSpecs(
            true,
            "80-ff",
            "10-11=http://cluster-a:8090"
        ));

        AeronConsensusEngine engine = mock(AeronConsensusEngine.class);
        when(engine.isClusterHealthy()).thenReturn(true);
        when(engine.getReachableValidatorCount()).thenReturn(3);
        when(engine.getTotalMemberCount()).thenReturn(3);
        when(engine.getQuorumSize()).thenReturn(2);
        when(engine.getCurrentRole()).thenReturn(ValidatorRole.LEADER);
        when(engine.getHeartbeatAgeMs()).thenReturn(11L);
        when(engine.getCurrentLeaderHint()).thenReturn("http://leader:8090");
        when(engine.isLeader()).thenReturn(true);
        when(engine.getCurrentEpoch()).thenReturn(21);
        when(engine.getCurrentTerm()).thenReturn(8);
        when(engine.getCommittedHead()).thenReturn("committed-head");
        when(engine.getLatestHead()).thenReturn("latest-head");
        when(engine.getLatestEpochSeen()).thenReturn(21);
        when(engine.getLastCommittedEpoch()).thenReturn(20);
        context.aeronConsensusEngine = engine;

        HealthHandler handler = newHandler(context);
        handler.handleDeepHealth(response);

        verify(response).setStatus(HttpServletResponse.SC_OK);
        String json = body.toString();
        assertTrue(json.contains("\"success\":true"));
        assertTrue(json.contains("\"mode\":\"aeron-cluster\""));
        assertTrue(json.contains("\"registeredClients\":1"));
        assertTrue(json.contains("\"registeredValidators\":1"));
        assertTrue(json.contains("\"type\":\"ipfs\""));
        assertTrue(json.contains("\"cidMappingAvailable\":true"));
        assertTrue(json.contains("\"sharding\":"));
        assertTrue(json.contains("\"enabled\":true"));
        assertTrue(json.contains("\"localPrefixes\":\"80-ff\""));
        assertTrue(json.contains("\"remoteMountCount\":2"));
        assertTrue(json.contains("\"authoritativeStoreSeparated\":true"));
        assertTrue(json.matches("(?s).*\"overall\":\\{[^}]*\"status\":\"UP\"[^}]*}.*"));
    }

    @Test
    public void testHandleDeepHealthDegradedSubsystemsAndMediaDriver() throws Exception {
        StringWriter body = new StringWriter();
        HttpServletResponse response = responseWithBody(body);
        Path storeDirectory = Files.createTempDirectory("health-deep-degraded");
        ServerContext context = new ServerContext(null, null, storeDirectory, "http://localhost:8090");
        context.blobStoreType = "s3";

        AeronConsensusEngine engine = mock(AeronConsensusEngine.class);
        when(engine.isClusterHealthy()).thenReturn(false);
        when(engine.getUnhealthyReason()).thenReturn("session_timeout");
        when(engine.getReachableValidatorCount()).thenReturn(1);
        when(engine.getTotalMemberCount()).thenReturn(3);
        when(engine.getQuorumSize()).thenReturn(2);
        when(engine.getCurrentRole()).thenReturn(ValidatorRole.FOLLOWER);
        when(engine.getHeartbeatAgeMs()).thenReturn(444L);
        when(engine.getCurrentLeaderHint()).thenReturn(null);
        when(engine.isLeader()).thenReturn(false);
        when(engine.getCurrentEpoch()).thenReturn(9);
        when(engine.getCurrentTerm()).thenReturn(4);
        context.aeronConsensusEngine = engine;

        CrashHandler crashHandler = mock(CrashHandler.class);
        when(crashHandler.getState()).thenReturn("CRASHED");
        when(crashHandler.hasCrashed()).thenReturn(true);
        when(crashHandler.shouldForceBootstrap()).thenReturn(true);

        MediaDriverHealthMonitor healthMonitor = mock(MediaDriverHealthMonitor.class);
        when(healthMonitor.isHealthy()).thenReturn(false);
        when(healthMonitor.getHealthStatus()).thenReturn("DEGRADED");
        when(healthMonitor.getErrorCount()).thenReturn(2L);
        when(healthMonitor.getTimeoutCount()).thenReturn(3L);
        when(healthMonitor.getBackpressureCount()).thenReturn(4L);
        when(healthMonitor.getFreeSpaceMB()).thenReturn(512L);

        AeronClusterLauncher launcher = mock(AeronClusterLauncher.class);
        when(launcher.getCrashHandler()).thenReturn(crashHandler);
        when(launcher.getHealthMonitor()).thenReturn(healthMonitor);
        context.aeronClusterLauncher = launcher;

        HealthHandler handler = new HealthHandler(
            null,
            null,
            storeDirectory,
            engine,
            context.registeredClients,
            context.registeredValidators,
            context
        );
        handler.handleDeepHealth(response);

        verify(response).setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        String json = body.toString();
        assertTrue(json.contains("FileStore not initialized"));
        assertTrue(json.contains("NodeStore not initialized"));
        assertTrue(json.contains("\"status\":\"UNHEALTHY\""));
        assertTrue(json.contains("\"unhealthyReason\":\"session_timeout\""));
        assertTrue(json.contains("\"status\":\"DEGRADED\""));
        assertTrue(json.contains("\"hasCrashed\":true"));
        assertTrue(json.contains("\"type\":\"s3\""));
        assertTrue(json.contains("\"error\":\"BlobStore not initialized\""));
        assertTrue(json.matches("(?s).*\"overall\":\\{[^}]*\"status\":\"DEGRADED\"[^}]*}.*"));
    }

    @Test
    public void testHandleGetOpsHealthSnapshotUsesCacheOnSecondCall() throws Exception {
        ServerContext context = newContext(Files.createTempDirectory("health-ops-cache"));
        AeronConsensusEngine engine = mock(AeronConsensusEngine.class);
        when(engine.isClusterHealthy()).thenReturn(true);
        when(engine.getReachableValidatorCount()).thenReturn(2);
        when(engine.getTotalMemberCount()).thenReturn(3);
        when(engine.getQuorumSize()).thenReturn(2);
        when(engine.getCurrentRole()).thenReturn(ValidatorRole.FOLLOWER);
        when(engine.getCurrentLeaderHint()).thenReturn("http://leader:8090");
        context.aeronConsensusEngine = engine;

        HealthHandler handler = newHandler(context);

        StringWriter firstBody = new StringWriter();
        HttpServletResponse firstResponse = responseWithBody(firstBody);
        handler.handleGetOpsHealthSnapshot(firstResponse);
        verify(firstResponse).setStatus(HttpServletResponse.SC_OK);
        assertTrue(firstBody.toString().contains("\"hit\":false"));

        StringWriter secondBody = new StringWriter();
        HttpServletResponse secondResponse = responseWithBody(secondBody);
        handler.handleGetOpsHealthSnapshot(secondResponse);
        verify(secondResponse).setStatus(HttpServletResponse.SC_OK);
        assertTrue(secondBody.toString().contains("\"hit\":true"));
        assertTrue(secondBody.toString().contains("\"contractVersion\":\"ops.v1\""));
    }

    @Test
    public void testHandleGetOpsHealthSnapshotFallsBackToStaleCacheWhenRefreshFails() throws Exception {
        ServerContext context = newContext(Files.createTempDirectory("health-ops-stale"));
        AeronConsensusEngine healthyEngine = mock(AeronConsensusEngine.class);
        when(healthyEngine.isClusterHealthy()).thenReturn(true);
        when(healthyEngine.getReachableValidatorCount()).thenReturn(2);
        when(healthyEngine.getTotalMemberCount()).thenReturn(3);
        when(healthyEngine.getQuorumSize()).thenReturn(2);
        when(healthyEngine.getCurrentRole()).thenReturn(ValidatorRole.FOLLOWER);
        when(healthyEngine.getCurrentLeaderHint()).thenReturn("http://leader:8090");
        context.aeronConsensusEngine = healthyEngine;

        HealthHandler handler = newHandler(context);

        StringWriter initialBody = new StringWriter();
        HttpServletResponse initialResponse = responseWithBody(initialBody);
        handler.handleGetOpsHealthSnapshot(initialResponse);

        setLongField(handler, "cachedOpsHealthSnapshotSourceTimestampMs", System.currentTimeMillis() - 5_000L);

        AeronConsensusEngine brokenEngine = mock(AeronConsensusEngine.class);
        when(brokenEngine.isClusterHealthy()).thenThrow(new RuntimeException("boom"));
        context.aeronConsensusEngine = brokenEngine;

        StringWriter staleBody = new StringWriter();
        HttpServletResponse staleResponse = responseWithBody(staleBody);
        handler.handleGetOpsHealthSnapshot(staleResponse);

        verify(staleResponse).setStatus(HttpServletResponse.SC_OK);
        String json = staleBody.toString();
        assertTrue(json.contains("\"degraded\":true"));
        assertTrue(json.contains("\"degradedReason\":\"STALE_CACHE_FALLBACK\""));
        assertTrue(json.contains("\"hit\":true"));
    }

    @Test
    public void testHandleGetOpsHealthSnapshotReturnsServiceUnavailableWithoutCache() throws Exception {
        ServerContext context = newContext(Files.createTempDirectory("health-ops-upstream"));
        AeronConsensusEngine brokenEngine = mock(AeronConsensusEngine.class);
        when(brokenEngine.isClusterHealthy()).thenThrow(new RuntimeException("boom"));
        context.aeronConsensusEngine = brokenEngine;

        HealthHandler handler = newHandler(context);
        StringWriter body = new StringWriter();
        HttpServletResponse response = responseWithBody(body);
        handler.handleGetOpsHealthSnapshot(response);

        verify(response).setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        String json = body.toString();
        assertTrue(json.contains("\"contractVersion\":\"ops.v1\""));
        assertTrue(json.contains("\"degraded\":true"));
        assertTrue(json.contains("\"degradedReason\":\"UPSTREAM_UNAVAILABLE\""));
    }

    private static ServerContext newContext(Path storeDirectory) {
        return new ServerContext(mock(FileStore.class), mock(NodeStore.class), storeDirectory, "http://localhost:8090");
    }

    private static HealthHandler newHandler(ServerContext context) {
        return new HealthHandler(
            context.fileStore,
            context.nodeStore,
            context.storeDirectory,
            context.aeronConsensusEngine,
            context.registeredClients,
            context.registeredValidators,
            context
        );
    }

    private static HttpServletResponse responseWithBody(StringWriter body) throws Exception {
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getWriter()).thenReturn(new PrintWriter(body));
        return response;
    }

    private static void setLongField(Object target, String fieldName, long value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setLong(target, value);
    }
}
