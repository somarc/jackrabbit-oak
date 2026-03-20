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
import java.nio.file.Paths;

import static org.junit.Assert.assertTrue;
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

    private StringWriter body;

    private ServerContext newContext() {
        return new ServerContext(
            mock(FileStore.class),
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
