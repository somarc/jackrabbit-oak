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
package org.apache.jackrabbit.oak.segment.http.server.sse;

import org.junit.Test;

import jakarta.servlet.AsyncContext;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Collections;
import java.util.HashSet;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

public class SSEClientTest {

    @Test
    public void testMatchesAppliesAllFilters() {
        SSEClient client = new SSEClient(
            mock(AsyncContext.class),
            new PrintWriter(new StringWriter()),
            new HashSet<>(Collections.singletonList("content")),
            new HashSet<>(Collections.singletonList("0xwallet")),
            new HashSet<>(Collections.singletonList("acme")),
            "/content",
            100L
        );

        ContentEvent matching = ContentEvent.builder()
            .timestamp(101L)
            .path("/content/doc-1")
            .wallet("0xwallet")
            .organization("acme")
            .build();
        ContentEvent wrongWallet = ContentEvent.builder()
            .timestamp(101L)
            .path("/content/doc-1")
            .wallet("0xother")
            .organization("acme")
            .build();

        assertTrue(client.matches(matching));
        assertFalse(client.matches(wrongWallet));
    }

    @Test
    public void testSendWritesStandardSsePayload() {
        StringWriter body = new StringWriter();
        SSEClient client = new SSEClient(
            mock(AsyncContext.class),
            new PrintWriter(body),
            new HashSet<>(),
            new HashSet<>(),
            new HashSet<>(),
            null,
            null
        );

        boolean sent = client.send(ContentEvent.builder().id("evt-1").timestamp(200L).build());

        assertTrue(sent);
        assertTrue(body.toString().contains("event: content"));
        assertTrue(body.toString().contains("id: evt-1"));
    }

    @Test
    public void testSendWritesOpsV1Payload() {
        StringWriter body = new StringWriter();
        SSEClient client = new SSEClient(
            mock(AsyncContext.class),
            new PrintWriter(body),
            new HashSet<>(),
            new HashSet<>(),
            new HashSet<>(),
            null,
            null,
            true,
            "http://validator-1:8090"
        );

        boolean sent = client.send(ContentEvent.builder()
            .id("evt-2")
            .type(ContentEvent.EventType.CONSENSUS)
            .action(ContentEvent.Action.LEADER_CHANGE)
            .timestamp(300L)
            .message("leader switched")
            .build());

        assertTrue(sent);
        String sse = body.toString();
        assertTrue(sse.contains("event: cluster.leader.changed"));
        assertTrue(sse.contains("\"contractVersion\":\"ops.v1\""));
        assertTrue(sse.contains("\"sourceNode\":\"http://validator-1:8090\""));
        assertTrue(sse.contains("\"legacyType\":\"consensus\""));
    }

    @Test
    public void testSendKeepAliveWritesComment() {
        StringWriter body = new StringWriter();
        SSEClient client = new SSEClient(
            mock(AsyncContext.class),
            new PrintWriter(body),
            new HashSet<>(),
            new HashSet<>(),
            new HashSet<>(),
            null,
            null
        );

        assertTrue(client.sendKeepAlive());
        assertTrue(body.toString().contains(": keep-alive"));
    }

    @Test
    public void testCloseCompletesAsyncContextAndMarksClosed() {
        AsyncContext asyncContext = mock(AsyncContext.class);
        SSEClient client = new SSEClient(
            asyncContext,
            new PrintWriter(new StringWriter()),
            new HashSet<>(),
            new HashSet<>(),
            new HashSet<>(),
            null,
            null
        );

        client.close();

        assertTrue(client.isClosed());
        verify(asyncContext).complete();
    }
}
