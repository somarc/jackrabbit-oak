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

import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.apache.jackrabbit.oak.segment.consensus.aeron.AeronConsensusEngine;
import org.apache.jackrabbit.oak.segment.http.server.ServerContext;
import org.apache.jackrabbit.oak.spi.state.NodeStore;
import org.junit.Test;

import javax.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Paths;

import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.util.HashMap;
import java.util.Map;

public class DashboardHandlerTest {

    @Test
    public void testHandleApiIndexIncludesCoreEndpoints() throws Exception {
        StringWriter body = new StringWriter();
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getWriter()).thenReturn(new PrintWriter(body));

        ServerContext context = new ServerContext(
            mock(FileStore.class),
            mock(NodeStore.class),
            Paths.get("/tmp/store"),
            "http://localhost:8090"
        );
        DashboardHandler handler = new DashboardHandler(context);

        handler.handleApiIndex(response);

        verify(response).setStatus(HttpServletResponse.SC_OK);
        verify(response).setContentType("application/json; charset=UTF-8");
        String json = body.toString();
        assertTrue(json.contains("\"contractVersion\":\"index.v1\""));
        assertTrue(json.contains("\"count\":"));
        assertTrue(json.contains("\"path\":\"/v1/index\""));
        assertTrue(json.contains("\"path\":\"/v1/config/osgi/coverage\""));
        assertTrue(json.contains("\"path\":\"/v1/config/osgi/delta\""));
        assertTrue(json.contains("\"path\":\"/v1/proposals/queue/stats\""));
        assertTrue(json.contains("\"path\":\"/v1/explorer/summary\""));
        assertTrue(json.contains("\"path\":\"/v1/consensus/status\""));
        assertTrue(json.contains("\"path\":\"/v1/aeron/cluster-state\""));
        assertTrue(json.contains("\"path\":\"/v1/events/stats\""));
        assertTrue(json.contains("\"path\":\"/v1/gc/status\""));
        assertTrue(json.contains("\"path\":\"/metrics\""));
    }

    @Test
    public void testHandleDashboardRendersApiFirstLanding() throws Exception {
        StringWriter body = new StringWriter();
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getWriter()).thenReturn(new PrintWriter(body));

        ServerContext context = new ServerContext(
            mock(FileStore.class),
            mock(NodeStore.class),
            Paths.get("/tmp/store"),
            "http://localhost:8090"
        );
        DashboardHandler handler = new DashboardHandler(context);

        handler.handleDashboard(response);

        verify(response).setStatus(HttpServletResponse.SC_OK);
        verify(response).setContentType("text/html; charset=UTF-8");
        String html = body.toString();
        assertTrue(html.contains("Oak Control Plane Home"));
        assertTrue(html.contains("/api-browser"));
        assertTrue(html.contains("/v1/proposals/queue/stats"));
        assertTrue(html.contains("legacy in-process dashboard is retired"));
    }

    @Test
    public void testHandleDashboardUsesMemberIdWhenLeaderNodeIdMissing() throws Exception {
        StringWriter body = new StringWriter();
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getWriter()).thenReturn(new PrintWriter(body));

        ServerContext context = new ServerContext(
            mock(FileStore.class),
            mock(NodeStore.class),
            Paths.get("/tmp/store"),
            "http://localhost:8090"
        );

        AeronConsensusEngine engine = mock(AeronConsensusEngine.class);
        Map<String, Object> nativeState = new HashMap<>();
        nativeState.put("role", "LEADER");
        nativeState.put("memberId", 0);
        nativeState.put("clusterMemberCount", 3);
        nativeState.put("leadershipTermId", 1L);
        when(engine.getNativeClusterState()).thenReturn(nativeState);
        when(engine.getReachableValidatorCount()).thenReturn(3);
        when(engine.getLastHeartbeatTime()).thenReturn(System.currentTimeMillis());
        context.aeronConsensusEngine = engine;

        DashboardHandler handler = new DashboardHandler(context);
        handler.handleDashboard(response);

        String html = body.toString();
        assertTrue(html.contains("<div class='k'>Leader</div><div class='v'>0</div>"));
    }
}
