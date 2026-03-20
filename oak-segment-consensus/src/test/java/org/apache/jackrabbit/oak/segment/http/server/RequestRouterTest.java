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
package org.apache.jackrabbit.oak.segment.http.server;

import org.apache.jackrabbit.oak.segment.consensus.aeron.AeronConsensusEngine;
import org.apache.jackrabbit.oak.segment.consensus.fragmentation.FragmentationTracker;
import org.apache.jackrabbit.oak.segment.consensus.gc.GCAccountManager;
import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.apache.jackrabbit.oak.spi.state.NodeStore;
import org.eclipse.jetty.server.Request;
import org.junit.Test;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.nio.file.Paths;

import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class RequestRouterTest {

    @Test
    public void testApiBrowserDisabledWhenHeadlessToggleIsFalse() throws Exception {
        withRoutingProperties(false, () -> {
            ServerContext context = newContext();
            RequestRouter router = new RequestRouter(context);
            Request baseRequest = mock(Request.class);
            HttpServletRequest request = request("GET", "/api-browser");
            HttpServletResponse response = responseWithBody();

            router.route(baseRequest, request, response);

            verify(baseRequest).setHandled(true);
            verify(response).setStatus(HttpServletResponse.SC_GONE);
            assertTrue(body.toString().contains("Browser UI routes are disabled"));
        });
    }

    @Test
    public void testOsgiConfigRouteReturnsEffectiveConfig() throws Exception {
        withRoutingProperties(true, () -> {
            RequestRouter router = new RequestRouter(newContext());
            Request baseRequest = mock(Request.class);
            HttpServletRequest request = request("GET", "/v1/config/osgi");
            HttpServletResponse response = responseWithBody();

            router.route(baseRequest, request, response);

            verify(baseRequest).setHandled(true);
            verify(response).setStatus(HttpServletResponse.SC_OK);
            assertTrue(body.toString().contains("\"contractVersion\":\"config.osgi.v1\""));
        });
    }

    @Test
    public void testOsgiConfigDeltaRouteReturnsDeltaPayload() throws Exception {
        withRoutingProperties(true, () -> {
            RequestRouter router = new RequestRouter(newContext());
            Request baseRequest = mock(Request.class);
            HttpServletRequest request = request("GET", "/v1/config/osgi/delta");
            HttpServletResponse response = responseWithBody();

            router.route(baseRequest, request, response);

            verify(baseRequest).setHandled(true);
            verify(response).setStatus(HttpServletResponse.SC_OK);
            assertTrue(body.toString().contains("\"contractVersion\":\"config.osgi.delta.v1\""));
        });
    }

    @Test
    public void testOsgiConfigSchemaRouteReturnsSchemaPayload() throws Exception {
        withRoutingProperties(true, () -> {
            RequestRouter router = new RequestRouter(newContext());
            Request baseRequest = mock(Request.class);
            HttpServletRequest request = request("GET", "/v1/config/osgi/schema");
            HttpServletResponse response = responseWithBody();

            router.route(baseRequest, request, response);

            verify(baseRequest).setHandled(true);
            verify(response).setStatus(HttpServletResponse.SC_OK);
            assertTrue(body.toString().contains("\"contractVersion\":\"config.osgi.schema.v1\""));
        });
    }

    @Test
    public void testOsgiConfigSourcesRouteReturnsSourcesPayload() throws Exception {
        withRoutingProperties(true, () -> {
            RequestRouter router = new RequestRouter(newContext());
            Request baseRequest = mock(Request.class);
            HttpServletRequest request = request("GET", "/v1/config/osgi/sources");
            HttpServletResponse response = responseWithBody();

            router.route(baseRequest, request, response);

            verify(baseRequest).setHandled(true);
            verify(response).setStatus(HttpServletResponse.SC_OK);
            assertTrue(body.toString().contains("\"contractVersion\":\"config.osgi.sources.v1\""));
        });
    }

    @Test
    public void testDashboardRouteRendersLandingPage() throws Exception {
        withRoutingProperties(true, () -> {
            RequestRouter router = new RequestRouter(newContext());
            Request baseRequest = mock(Request.class);
            HttpServletRequest request = request("GET", "/dashboard");
            HttpServletResponse response = responseWithBody();

            router.route(baseRequest, request, response);

            verify(baseRequest).setHandled(true);
            verify(response).setStatus(HttpServletResponse.SC_OK);
            assertTrue(body.toString().contains("Oak Control Plane Home"));
        });
    }

    @Test
    public void testExplorerRouteRendersExplorerUiWhenBrowserEnabled() throws Exception {
        withRoutingProperties(true, () -> {
            RequestRouter router = new RequestRouter(newContext());
            Request baseRequest = mock(Request.class);
            HttpServletRequest request = request("GET", "/explorer");
            HttpServletResponse response = responseWithBody();

            router.route(baseRequest, request, response);

            verify(baseRequest).setHandled(true);
            verify(response).setStatus(HttpServletResponse.SC_OK);
            assertTrue(body.toString().contains("Explorer | Blockchain AEM Validator"));
        });
    }

    @Test
    public void testApiBrowserRouteRendersBrowserUiWhenEnabled() throws Exception {
        withRoutingProperties(true, () -> {
            RequestRouter router = new RequestRouter(newContext());
            Request baseRequest = mock(Request.class);
            HttpServletRequest request = request("GET", "/api-browser");
            HttpServletResponse response = responseWithBody();

            router.route(baseRequest, request, response);

            verify(baseRequest).setHandled(true);
            verify(response).setStatus(HttpServletResponse.SC_OK);
            assertTrue(body.toString().contains("API Browser | Blockchain AEM Validator"));
        });
    }

    @Test
    public void testChatRouteRendersChatUiWhenBrowserEnabled() throws Exception {
        withRoutingProperties(true, () -> {
            RequestRouter router = new RequestRouter(newContext());
            Request baseRequest = mock(Request.class);
            HttpServletRequest request = request("GET", "/chat");
            HttpServletResponse response = responseWithBody();

            router.route(baseRequest, request, response);

            verify(baseRequest).setHandled(true);
            verify(response).setStatus(HttpServletResponse.SC_OK);
            assertTrue(body.toString().contains("LLM Chat | Blockchain AEM Validator"));
        });
    }

    @Test
    public void testFragmentationMetricsRouteReturnsTrackedEntityMetrics() throws Exception {
        withRoutingProperties(true, () -> {
            ServerContext context = newContext();
            FragmentationTracker tracker = new FragmentationTracker();
            tracker.recordWrite("0xabc", "data00001a.tar", 2048L);
            context.fragmentationTracker = tracker;
            RequestRouter router = new RequestRouter(context);
            Request baseRequest = mock(Request.class);
            HttpServletRequest request = request("GET", "/v1/fragmentation/metrics");
            HttpServletResponse response = responseWithBody();

            router.route(baseRequest, request, response);

            verify(baseRequest).setHandled(true);
            verify(response).setStatus(HttpServletResponse.SC_OK);
            assertTrue(body.toString().contains("\"walletAddress\":\"0xabc\""));
            assertTrue(body.toString().contains("\"totalEntities\":1"));
        });
    }

    @Test
    public void testGcAccountRouteHandlesNestedWalletPath() throws Exception {
        withRoutingProperties(true, () -> {
            ServerContext context = newContext();
            GCAccountManager accountManager = new GCAccountManager();
            accountManager.addDebt("0xwallet", "/oak-chain/demo", 5L);
            accountManager.convertAllPendingToExecuted();
            context.gcAccountManager = accountManager;
            RequestRouter router = new RequestRouter(context);
            Request baseRequest = mock(Request.class);
            HttpServletRequest request = request("GET", "/v1/gc/account/0xwallet");
            HttpServletResponse response = responseWithBody();

            router.route(baseRequest, request, response);

            verify(baseRequest).setHandled(true);
            verify(response).setStatus(HttpServletResponse.SC_OK);
            assertTrue(body.toString().contains("\"walletAddress\":\"0xwallet\""));
            assertTrue(body.toString().contains("\"executedDebt\":\"0.50\""));
        });
    }

    @Test
    public void testGcAccountPayRouteUsesNestedWalletPath() throws Exception {
        withRoutingProperties(true, () -> {
            ServerContext context = newContext();
            GCAccountManager accountManager = new GCAccountManager();
            accountManager.addDebt("0xwallet", "/oak-chain/demo", 5L);
            accountManager.convertAllPendingToExecuted();
            context.gcAccountManager = accountManager;
            RequestRouter router = new RequestRouter(context);
            Request baseRequest = mock(Request.class);
            HttpServletRequest request = request("POST", "/v1/gc/account/0xwallet/pay");
            when(request.getParameter("amount")).thenReturn("0.25");
            when(request.getParameter("txHash")).thenReturn("0xtx");
            HttpServletResponse response = responseWithBody();

            router.route(baseRequest, request, response);

            verify(baseRequest).setHandled(true);
            verify(response).setStatus(HttpServletResponse.SC_OK);
            assertTrue(body.toString().contains("\"amountPaid\":\"0.25\""));
            assertTrue(body.toString().contains("\"remainingDebt\":\"0.25\""));
        });
    }

    @Test
    public void testGcTriggerRouteExecutesCycle() throws Exception {
        withRoutingProperties(true, () -> {
            ServerContext context = newContext();
            GCAccountManager accountManager = new GCAccountManager();
            accountManager.getAccount("0xblocked").totalDebt = new BigDecimal("120.00");
            context.gcAccountManager = accountManager;
            RequestRouter router = new RequestRouter(context);
            Request baseRequest = mock(Request.class);
            HttpServletRequest request = request("POST", "/v1/gc/trigger");
            HttpServletResponse response = responseWithBody();

            router.route(baseRequest, request, response);

            verify(baseRequest).setHandled(true);
            verify(response).setStatus(HttpServletResponse.SC_OK);
            assertTrue(body.toString().contains("\"entitiesBlocked\":1"));
            assertTrue(body.toString().contains("\"blockedWallets\":[\"0xblocked\"]"));
        });
    }

    @Test
    public void testClusterHealthRouteReturnsClusterMetrics() throws Exception {
        withRoutingProperties(true, () -> {
            ServerContext context = newContext();
            AeronConsensusEngine engine = baseEngine();
            when(engine.isClusterHealthy()).thenReturn(true);
            when(engine.getReachableValidatorCount()).thenReturn(2);
            when(engine.getTotalMemberCount()).thenReturn(3);
            when(engine.getQuorumSize()).thenReturn(2);
            when(engine.hasQuorum()).thenReturn(true);
            when(engine.getLastHeartbeatTime()).thenReturn(1234L);
            when(engine.getHeartbeatAgeMs()).thenReturn(56L);
            when(engine.getCurrentLeader()).thenReturn("http://validator-2:8090");
            context.aeronConsensusEngine = engine;
            RequestRouter router = new RequestRouter(context);
            Request baseRequest = mock(Request.class);
            HttpServletRequest request = request("GET", "/health/cluster");
            HttpServletResponse response = responseWithBody();

            router.route(baseRequest, request, response);

            verify(baseRequest).setHandled(true);
            verify(response).setStatus(HttpServletResponse.SC_OK);
            assertTrue(body.toString().contains("\"status\":\"UP\""));
            assertTrue(body.toString().contains("\"reachableCount\":2"));
            assertTrue(body.toString().contains("\"hasQuorum\":true"));
        });
    }

    @Test
    public void testOpsHealthSnapshotRouteReturnsOpsEnvelope() throws Exception {
        withRoutingProperties(true, () -> {
            ServerContext context = newContext();
            AeronConsensusEngine engine = baseEngine();
            when(engine.isClusterHealthy()).thenReturn(true);
            when(engine.getReachableValidatorCount()).thenReturn(2);
            when(engine.getTotalMemberCount()).thenReturn(3);
            when(engine.getQuorumSize()).thenReturn(2);
            when(engine.getCurrentRole()).thenReturn(org.apache.jackrabbit.oak.segment.consensus.leader.ValidatorRole.FOLLOWER);
            when(engine.getCurrentLeader()).thenReturn("http://validator-2:8090");
            context.aeronConsensusEngine = engine;
            RequestRouter router = new RequestRouter(context);
            Request baseRequest = mock(Request.class);
            HttpServletRequest request = request("GET", "/v1/ops/snapshots/health");
            HttpServletResponse response = responseWithBody();

            router.route(baseRequest, request, response);

            verify(baseRequest).setHandled(true);
            verify(response).setStatus(HttpServletResponse.SC_OK);
            assertTrue(body.toString().contains("\"contractVersion\":\"ops.v1\""));
            assertTrue(body.toString().contains("\"hit\":false"));
        });
    }

    @Test
    public void testAeronClusterStateRouteReturnsEnrichedState() throws Exception {
        withRoutingProperties(true, () -> {
            ServerContext context = newContext();
            context.selfUrl = "http://validator-2:8090";
            context.aeronConsensusEngine = baseEngine();
            RequestRouter router = new RequestRouter(context);
            Request baseRequest = mock(Request.class);
            HttpServletRequest request = request("GET", "/v1/aeron/cluster-state");
            HttpServletResponse response = responseWithBody();

            router.route(baseRequest, request, response);

            verify(baseRequest).setHandled(true);
            verify(response).setStatus(HttpServletResponse.SC_OK);
            assertTrue(body.toString().contains("\"clusterId\":\"oak-consensus-cluster\""));
            assertTrue(body.toString().contains("\"walletAddress\":\"0x2222222222222222222222222222222222222222\""));
        });
    }

    @Test
    public void testAeronValidatorIdentitiesRouteReturnsIdentityPayload() throws Exception {
        withRoutingProperties(true, () -> {
            ServerContext context = newContext();
            context.selfUrl = "http://validator-2:8090";
            context.aeronConsensusEngine = baseEngine();
            RequestRouter router = new RequestRouter(context);
            Request baseRequest = mock(Request.class);
            HttpServletRequest request = request("GET", "/v1/aeron/validator-identities");
            HttpServletResponse response = responseWithBody();

            router.route(baseRequest, request, response);

            verify(baseRequest).setHandled(true);
            verify(response).setStatus(HttpServletResponse.SC_OK);
            assertTrue(body.toString().contains("\"totalValidators\":1"));
            assertTrue(body.toString().contains("\"knownWallets\":1"));
        });
    }

    @Test
    public void testAeronReplicationLagRouteReturnsLagPayload() throws Exception {
        withRoutingProperties(true, () -> {
            ServerContext context = newContext();
            AeronConsensusEngine engine = baseEngine();
            Map<String, Object> lagStatus = new HashMap<>();
            lagStatus.put("healthy", true);
            lagStatus.put("replicationLag", 4);
            when(engine.getReplicationLagStatus()).thenReturn(lagStatus);
            context.aeronConsensusEngine = engine;
            RequestRouter router = new RequestRouter(context);
            Request baseRequest = mock(Request.class);
            HttpServletRequest request = request("GET", "/v1/aeron/replication-lag");
            HttpServletResponse response = responseWithBody();

            router.route(baseRequest, request, response);

            verify(baseRequest).setHandled(true);
            verify(response).setStatus(HttpServletResponse.SC_OK);
            assertTrue(body.toString().contains("\"replicationLag\":4"));
            assertTrue(body.toString().contains("\"healthy\":true"));
        });
    }

    @Test
    public void testOpsClusterSnapshotRouteReturnsOpsEnvelope() throws Exception {
        withRoutingProperties(true, () -> {
            ServerContext context = newContext();
            context.selfUrl = "http://validator-2:8090";
            context.aeronConsensusEngine = baseEngine();
            RequestRouter router = new RequestRouter(context);
            Request baseRequest = mock(Request.class);
            HttpServletRequest request = request("GET", "/v1/ops/snapshots/cluster");
            HttpServletResponse response = responseWithBody();

            router.route(baseRequest, request, response);

            verify(baseRequest).setHandled(true);
            verify(response).setStatus(HttpServletResponse.SC_OK);
            assertTrue(body.toString().contains("\"contractVersion\":\"ops.v1\""));
            assertTrue(body.toString().contains("\"clusterId\":\"oak-consensus-cluster\""));
        });
    }

    @Test
    public void testHeadRouteUsesAeronHeadValuesWhenAvailable() throws Exception {
        withRoutingProperties(true, () -> {
            ServerContext context = newContextWithHead("file-head");
            AeronConsensusEngine engine = mock(AeronConsensusEngine.class);
            when(engine.getLatestHead()).thenReturn("latest-aeron-head");
            when(engine.getCommittedHead()).thenReturn("committed-aeron-head");
            when(engine.getLatestEpochSeen()).thenReturn(12);
            when(engine.getLastCommittedEpoch()).thenReturn(11);
            context.aeronConsensusEngine = engine;
            RequestRouter router = new RequestRouter(context);
            Request baseRequest = mock(Request.class);
            HttpServletRequest request = request("GET", "/v1/head");
            HttpServletResponse response = responseWithBody();

            router.route(baseRequest, request, response);

            verify(baseRequest).setHandled(true);
            verify(response).setStatus(HttpServletResponse.SC_OK);
            assertTrue(body.toString().contains("\"latestHead\": \"latest-aeron-head\""));
            assertTrue(body.toString().contains("\"committedHead\": \"committed-aeron-head\""));
            assertTrue(body.toString().contains("\"latestEpochSeen\": 12"));
            assertTrue(body.toString().contains("\"committedEpoch\": 11"));
        });
    }

    @Test
    public void testHeadRouteFallsBackToFileStoreHeadWhenAeronStateMissing() throws Exception {
        withRoutingProperties(true, () -> {
            ServerContext context = newContextWithHead("fallback-file-head");
            AeronConsensusEngine engine = mock(AeronConsensusEngine.class);
            when(engine.getLatestHead()).thenReturn(null);
            when(engine.getCommittedHead()).thenReturn("");
            when(engine.getLatestEpochSeen()).thenReturn(-1);
            when(engine.getLastCommittedEpoch()).thenReturn(-1);
            context.aeronConsensusEngine = engine;
            RequestRouter router = new RequestRouter(context);
            Request baseRequest = mock(Request.class);
            HttpServletRequest request = request("GET", "/v1/head");
            HttpServletResponse response = responseWithBody();

            router.route(baseRequest, request, response);

            verify(baseRequest).setHandled(true);
            verify(response).setStatus(HttpServletResponse.SC_OK);
            assertTrue(body.toString().contains("\"latestHead\": \"fallback-file-head\""));
            assertTrue(body.toString().contains("\"committedHead\": \"fallback-file-head\""));
        });
    }

    @Test
    public void testRecentEventsRouteReturnsEmptyPayloadWhenNoEventsBroadcast() throws Exception {
        withRoutingProperties(true, () -> {
            RequestRouter router = new RequestRouter(newContext());
            Request baseRequest = mock(Request.class);
            HttpServletRequest request = request("GET", "/v1/events/recent");
            HttpServletResponse response = responseWithBody();

            router.route(baseRequest, request, response);

            verify(baseRequest).setHandled(true);
            assertTrue(body.toString().contains("\"events\":[]"));
            assertTrue(body.toString().contains("\"count\":0"));
        });
    }

    @Test
    public void testEventStatsRouteReturnsEmptyStatsPayload() throws Exception {
        withRoutingProperties(true, () -> {
            RequestRouter router = new RequestRouter(newContext());
            Request baseRequest = mock(Request.class);
            HttpServletRequest request = request("GET", "/v1/events/stats");
            HttpServletResponse response = responseWithBody();

            router.route(baseRequest, request, response);

            verify(baseRequest).setHandled(true);
            assertTrue(body.toString().contains("\"connectedClients\":0"));
            assertTrue(body.toString().contains("\"eventBufferSize\":0"));
            assertTrue(body.toString().contains("\"totalEventsBroadcast\":0"));
        });
    }

    private StringWriter body;

    private ServerContext newContext() {
        return new ServerContext(
            mock(FileStore.class),
            mock(NodeStore.class),
            Paths.get("/tmp/store"),
            "http://localhost:8090"
        );
    }

    private ServerContext newContextWithHead(String head) {
        FileStore fileStore = mock(FileStore.class, RETURNS_DEEP_STUBS);
        when(fileStore.getHead().getRecordId().toString10()).thenReturn(head);
        return new ServerContext(
            fileStore,
            mock(NodeStore.class),
            Paths.get("/tmp/store"),
            "http://localhost:8090"
        );
    }

    private HttpServletRequest request(String method, String uri) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn(uri);
        when(request.getMethod()).thenReturn(method);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        return request;
    }

    private HttpServletResponse responseWithBody() throws Exception {
        HttpServletResponse response = mock(HttpServletResponse.class);
        body = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(body));
        return response;
    }

    private AeronConsensusEngine baseEngine() {
        AeronConsensusEngine engine = mock(AeronConsensusEngine.class);
        when(engine.getNativeClusterState()).thenReturn(nativeClusterState());
        when(engine.getWalletAddress()).thenReturn("0x2222222222222222222222222222222222222222");
        when(engine.getPublicKeyHex()).thenReturn("0xabc123");
        when(engine.getReachableValidatorCount()).thenReturn(3);
        when(engine.getLastHeartbeatTime()).thenReturn(1234L);
        when(engine.getCurrentLeader()).thenReturn("http://validator-2:8090");
        when(engine.getAllFollowers()).thenReturn(new ArrayList<>());
        when(engine.isLeader()).thenReturn(false);
        return engine;
    }

    private Map<String, Object> nativeClusterState() {
        Map<String, Object> state = new HashMap<>();
        state.put("role", "FOLLOWER");
        state.put("memberId", 2);
        state.put("clusterMemberCount", 3);
        java.util.List<Map<String, Object>> members = new ArrayList<>();
        Map<String, Object> self = new HashMap<>();
        self.put("url", "http://validator-2:8090");
        self.put("memberId", 2);
        members.add(self);
        state.put("members", members);
        return state;
    }

    private void withRoutingProperties(boolean browserUiEnabled, ThrowingRunnable runnable) throws Exception {
        String previousBrowser = System.getProperty("oak.http.browser.ui.enabled");
        String previousRateLimit = System.getProperty("rate.limit.enabled");
        try {
            System.setProperty("oak.http.browser.ui.enabled", Boolean.toString(browserUiEnabled));
            System.setProperty("rate.limit.enabled", "false");
            runnable.run();
        } finally {
            if (previousBrowser == null) {
                System.clearProperty("oak.http.browser.ui.enabled");
            } else {
                System.setProperty("oak.http.browser.ui.enabled", previousBrowser);
            }
            if (previousRateLimit == null) {
                System.clearProperty("rate.limit.enabled");
            } else {
                System.setProperty("rate.limit.enabled", previousRateLimit);
            }
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
