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
import org.apache.jackrabbit.oak.segment.http.server.ServerContext;
import org.apache.jackrabbit.oak.segment.http.server.sse.ContentEvent;
import org.apache.jackrabbit.oak.segment.http.server.sse.EventBroadcaster;
import org.apache.jackrabbit.oak.spi.state.NodeStore;
import org.junit.Test;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class EventStreamHandlerTest {

    @Test
    public void testHandleRecentEventsAppliesFiltersAndLimit() throws Exception {
        EventBroadcaster broadcaster = new EventBroadcaster();
        try {
            broadcaster.broadcast(ContentEvent.builder()
                .id("evt-1")
                .timestamp(100L)
                .path("/content/doc-1")
                .wallet("0xwallet")
                .organization("acme")
                .build());
            broadcaster.broadcast(ContentEvent.builder()
                .id("evt-2")
                .type(ContentEvent.EventType.BINARY)
                .timestamp(200L)
                .path("/content/doc-2")
                .wallet("0xwallet")
                .organization("acme")
                .ipfsCid("QmCid")
                .build());
            broadcaster.broadcast(ContentEvent.builder()
                .id("evt-3")
                .timestamp(300L)
                .path("/content/doc-3")
                .wallet("0xother")
                .organization("other")
                .build());

            HttpServletRequest request = mock(HttpServletRequest.class);
            when(request.getParameter("limit")).thenReturn("1");
            when(request.getParameter("since")).thenReturn("150");
            when(request.getParameter("types")).thenReturn("binary");
            when(request.getParameter("wallets")).thenReturn("0xwallet");
            when(request.getParameter("organizations")).thenReturn("acme");
            StringWriter body = new StringWriter();
            HttpServletResponse response = responseWithBody(body);

            new EventStreamHandler(newContext(), broadcaster).handleRecentEvents(request, response);

            assertTrue(body.toString().contains("\"contractVersion\":\"events.recent.v1\""));
            assertTrue(body.toString().contains("\"count\":1"));
            assertTrue(body.toString().contains("\"hasMore\":true"));
            assertTrue(body.toString().contains("\"lastId\":\"evt-2\""));
            assertTrue(body.toString().contains("\"ipfsCid\":\"QmCid\""));
        } finally {
            broadcaster.shutdown();
        }
    }

    @Test
    public void testHandleRecentEventsAppliesPathPrefixFilter() throws Exception {
        EventBroadcaster broadcaster = new EventBroadcaster();
        try {
            broadcaster.broadcast(ContentEvent.builder()
                .id("evt-local")
                .timestamp(100L)
                .path("/oak-chain/00/aa")
                .wallet("0xwallet")
                .build());
            broadcaster.broadcast(ContentEvent.builder()
                .id("evt-other")
                .timestamp(200L)
                .path("/content/doc-2")
                .wallet("0xwallet")
                .build());

            HttpServletRequest request = mock(HttpServletRequest.class);
            when(request.getParameter("path")).thenReturn("/oak-chain");
            StringWriter body = new StringWriter();
            HttpServletResponse response = responseWithBody(body);

            new EventStreamHandler(newContext(), broadcaster).handleRecentEvents(request, response);

            assertTrue(body.toString().contains("\"id\":\"evt-local\""));
            assertFalse(body.toString().contains("\"id\":\"evt-other\""));
        } finally {
            broadcaster.shutdown();
        }
    }

    @Test
    public void testHandleStatsReturnsBroadcasterMetrics() throws Exception {
        EventBroadcaster broadcaster = new EventBroadcaster();
        try {
            broadcaster.broadcast(ContentEvent.builder().id("evt-1").timestamp(100L).build());
            StringWriter body = new StringWriter();
            HttpServletResponse response = responseWithBody(body);

            new EventStreamHandler(newContext(), broadcaster).handleStats(mock(HttpServletRequest.class), response);

            verify(response).setContentType("application/json");
            assertTrue(body.toString().contains("\"contractVersion\":\"events.stats.v1\""));
            assertTrue(body.toString().contains("\"connectedClients\":0"));
            assertTrue(body.toString().contains("\"eventBufferSize\":1"));
            assertTrue(body.toString().contains("\"totalEventsBroadcast\":1"));
        } finally {
            broadcaster.shutdown();
        }
    }

    @Test
    public void testHandleEventStreamWritesInitialCommentsAndReplaysMatchingEvents() throws Exception {
        EventBroadcaster broadcaster = new EventBroadcaster();
        try {
            broadcaster.broadcast(ContentEvent.builder()
                .id("evt-1")
                .timestamp(100L)
                .path("/content/doc-1")
                .wallet("0xwallet")
                .organization("acme")
                .build());
            HttpServletRequest request = mock(HttpServletRequest.class);
            AsyncContext asyncContext = mock(AsyncContext.class);
            when(request.getParameter("types")).thenReturn("content");
            when(request.getParameter("wallets")).thenReturn("0xwallet");
            when(request.getParameter("organizations")).thenReturn("acme");
            when(request.getParameter("path")).thenReturn("/content");
            when(request.getHeader("Last-Event-ID")).thenReturn(null);
            when(request.getRemoteAddr()).thenReturn("127.0.0.1");
            when(request.startAsync()).thenReturn(asyncContext);
            StringWriter body = new StringWriter();
            HttpServletResponse response = responseWithBody(body);

            new EventStreamHandler(newContext(), broadcaster).handleEventStream(request, response);

            verify(asyncContext).setTimeout(0);
            verify(response).setContentType("text/event-stream");
            assertTrue(body.toString().contains(": connected to oak-chain event stream"));
            assertTrue(body.toString().contains("event: content"));
            assertTrue(body.toString().contains("id: evt-1"));
        } finally {
            broadcaster.shutdown();
        }
    }

    @Test
    public void testHandleOpsEventStreamUsesOpsEnvelopeAndNumericReplayFallback() throws Exception {
        EventBroadcaster broadcaster = new EventBroadcaster();
        try {
            broadcaster.broadcast(ContentEvent.builder()
                .id("10")
                .type(ContentEvent.EventType.CONSENSUS)
                .action(ContentEvent.Action.LEADER_CHANGE)
                .timestamp(100L)
                .message("leader switched")
                .build());
            HttpServletRequest request = mock(HttpServletRequest.class);
            AsyncContext asyncContext = mock(AsyncContext.class);
            when(request.getHeader("Last-Event-ID")).thenReturn("9");
            when(request.getRemoteAddr()).thenReturn("127.0.0.1");
            when(request.startAsync()).thenReturn(asyncContext);
            StringWriter body = new StringWriter();
            HttpServletResponse response = responseWithBody(body);

            new EventStreamHandler(newContext(), broadcaster).handleOpsEventStream(request, response);

            assertTrue(body.toString().contains("event: cluster.leader.changed"));
            assertTrue(body.toString().contains("\"contractVersion\":\"ops.v1\""));
            assertTrue(body.toString().contains("\"eventId\":\"10\""));
        } finally {
            broadcaster.shutdown();
        }
    }

    private static ServerContext newContext() {
        ServerContext context = new ServerContext(
            mock(FileStore.class),
            mock(NodeStore.class),
            Paths.get("/tmp/store"),
            "http://validator-1:8090"
        );
        context.selfUrl = "http://validator-1:8090";
        return context;
    }

    private static HttpServletResponse responseWithBody(StringWriter body) throws Exception {
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getWriter()).thenReturn(new PrintWriter(body));
        return response;
    }
}
