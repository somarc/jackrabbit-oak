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

import org.apache.jackrabbit.oak.segment.consensus.aeron.AeronConsensusEngine;
import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.apache.jackrabbit.oak.segment.http.server.ServerContext;
import org.apache.jackrabbit.oak.spi.state.NodeStore;
import org.junit.Test;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Paths;

import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class LeaderConsensusHandlerTest {

    @Test
    public void testHandleFollowerHeadUpdateRequiresAeronEngine() throws Exception {
        StringWriter body = new StringWriter();
        HttpServletResponse response = responseWithBody(body);

        new LeaderConsensusHandler(newContext(null)).handleFollowerHeadUpdate(
            request("{\"head\":\"abc:r1\",\"leaderUrl\":\"http://validator-1:8090\"}"),
            response
        );

        verify(response).setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        assertTrue(body.toString().contains("Aeron consensus engine not configured"));
    }

    @Test
    public void testHandleFollowerHeadUpdateRejectsLeaderNode() throws Exception {
        StringWriter body = new StringWriter();
        HttpServletResponse response = responseWithBody(body);
        AeronConsensusEngine engine = mock(AeronConsensusEngine.class);
        when(engine.isLeader()).thenReturn(true);

        new LeaderConsensusHandler(newContext(engine)).handleFollowerHeadUpdate(
            request("{\"head\":\"abc:r1\",\"leaderUrl\":\"http://validator-1:8090\"}"),
            response
        );

        verify(response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
        assertTrue(body.toString().contains("I am the leader, not a follower"));
    }

    @Test
    public void testHandleFollowerHeadUpdateRejectsMissingHead() throws Exception {
        StringWriter body = new StringWriter();
        HttpServletResponse response = responseWithBody(body);
        AeronConsensusEngine engine = baseFollowerEngine();

        new LeaderConsensusHandler(newContext(engine)).handleFollowerHeadUpdate(
            request("{\"leaderUrl\":\"http://validator-1:8090\"}"),
            response
        );

        verify(response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
        assertTrue(body.toString().contains("Missing required field: head"));
    }

    @Test
    public void testHandleFollowerHeadUpdateRejectsMissingLeaderUrl() throws Exception {
        StringWriter body = new StringWriter();
        HttpServletResponse response = responseWithBody(body);
        AeronConsensusEngine engine = baseFollowerEngine();

        new LeaderConsensusHandler(newContext(engine)).handleFollowerHeadUpdate(
            request("{\"head\":\"abc:r1\"}"),
            response
        );

        verify(response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
        assertTrue(body.toString().contains("Missing required field: leaderUrl"));
    }

    @Test
    public void testHandleFollowerHeadUpdateRejectsNonLeaderOrigin() throws Exception {
        StringWriter body = new StringWriter();
        HttpServletResponse response = responseWithBody(body);
        AeronConsensusEngine engine = baseFollowerEngine();
        when(engine.getCurrentLeader()).thenReturn("http://validator-2:8091");

        new LeaderConsensusHandler(newContext(engine)).handleFollowerHeadUpdate(
            request("{\"head\":\"abc:r1\",\"epoch\":\"9\",\"leaderUrl\":\"http://validator-1:8090\"}"),
            response
        );

        verify(response).setStatus(HttpServletResponse.SC_FORBIDDEN);
        assertTrue(body.toString().contains("Not current leader"));
    }

    @Test
    public void testHandleFollowerHeadUpdateAcceptsLeaderWithEquivalentPort() throws Exception {
        StringWriter body = new StringWriter();
        HttpServletResponse response = responseWithBody(body);
        AeronConsensusEngine engine = baseFollowerEngine();
        when(engine.getCurrentLeader()).thenReturn("http://127.0.0.1:8090");
        when(engine.pullSegmentsForHead("abc:r1", "http://localhost:8090")).thenReturn(7);

        new LeaderConsensusHandler(newContext(engine)).handleFollowerHeadUpdate(
            request("{\"head\":\"abc:r1\",\"epoch\":\"9\",\"leaderUrl\":\"http://localhost:8090\"}"),
            response
        );

        verify(response).setStatus(HttpServletResponse.SC_OK);
        String json = body.toString();
        assertTrue(json.contains("\"success\":true"));
        assertTrue(json.contains("\"message\":\"HEAD replicated\""));
        assertTrue(json.contains("\"segmentCount\":7"));
    }

    @Test
    public void testHandleFollowerHeadUpdateReturnsServerErrorOnPullFailure() throws Exception {
        StringWriter body = new StringWriter();
        HttpServletResponse response = responseWithBody(body);
        AeronConsensusEngine engine = baseFollowerEngine();
        when(engine.getCurrentLeader()).thenReturn("http://validator-1:8090");
        when(engine.pullSegmentsForHead("abc:r1", "http://validator-1:8090"))
            .thenThrow(new IllegalStateException("pull failed"));

        new LeaderConsensusHandler(newContext(engine)).handleFollowerHeadUpdate(
            request("{\"head\":\"abc:r1\",\"leaderUrl\":\"http://validator-1:8090\"}"),
            response
        );

        verify(response).setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        assertTrue(body.toString().contains("Failed to process HEAD update: pull failed"));
    }

    private static ServerContext newContext(AeronConsensusEngine engine) {
        ServerContext context = new ServerContext(
            mock(FileStore.class),
            mock(NodeStore.class),
            Paths.get("/tmp/store"),
            "http://validator-2:8090"
        );
        context.aeronConsensusEngine = engine;
        return context;
    }

    private static AeronConsensusEngine baseFollowerEngine() {
        AeronConsensusEngine engine = mock(AeronConsensusEngine.class);
        when(engine.isLeader()).thenReturn(false);
        return engine;
    }

    private static HttpServletRequest request(String body) throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getReader()).thenReturn(new BufferedReader(new StringReader(body)));
        return request;
    }

    private static HttpServletResponse responseWithBody(StringWriter body) throws Exception {
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getWriter()).thenReturn(new PrintWriter(body));
        return response;
    }
}
