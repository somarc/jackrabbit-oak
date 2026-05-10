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
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

public class EventBroadcasterTest {

    @Test
    public void testBroadcastSendsToMatchingClientsAndBuffersRecentEvents() {
        EventBroadcaster broadcaster = new EventBroadcaster();
        try {
            StringWriter matchingBody = new StringWriter();
            StringWriter nonMatchingBody = new StringWriter();
            SSEClient matching = new SSEClient(
                mock(AsyncContext.class),
                new PrintWriter(matchingBody),
                new HashSet<>(Collections.singletonList("content")),
                new HashSet<>(),
                new HashSet<>(),
                null,
                null
            );
            SSEClient nonMatching = new SSEClient(
                mock(AsyncContext.class),
                new PrintWriter(nonMatchingBody),
                new HashSet<>(Collections.singletonList("binary")),
                new HashSet<>(),
                new HashSet<>(),
                null,
                null
            );

            assertTrue(broadcaster.addClient(matching));
            assertTrue(broadcaster.addClient(nonMatching));

            ContentEvent event = ContentEvent.builder().id("evt-1").timestamp(100L).build();
            broadcaster.broadcast(event);

            assertTrue(matchingBody.toString().contains("id: evt-1"));
            assertEquals("", nonMatchingBody.toString());
            List<ContentEvent> recent = broadcaster.getRecentEvents(10);
            assertEquals(1, recent.size());
            assertEquals("evt-1", recent.get(0).getId());
            assertEquals(1L, broadcaster.getTotalEventsBroadcast());
        } finally {
            broadcaster.shutdown();
        }
    }

    @Test
    public void testEmitHelpersPopulateExpectedEventTypes() {
        EventBroadcaster broadcaster = new EventBroadcaster();
        try {
            broadcaster.emitContentWrite("/content/doc-1", "0xwallet", "acme", "hello", "0xsig", "text/plain");
            broadcaster.emitBinaryUpload("/content/doc-2", "0xwallet", "acme", "img", "QmCid", 42L, "image/png");
            broadcaster.emitContentDelete("/content/doc-3", "0xwallet", "acme", "0xdead");
            broadcaster.emitWalletRegistration("0xwallet", "owner");
            broadcaster.emitConsensusEvent(ContentEvent.Action.COMMIT, "commit");

            List<ContentEvent> recent = broadcaster.getRecentEvents(10);
            assertEquals(5, recent.size());
            assertEquals("content", recent.get(0).getType());
            assertEquals("binary", recent.get(1).getType());
            assertEquals("delete", recent.get(2).getType());
            assertEquals("wallet", recent.get(3).getType());
            assertEquals("consensus", recent.get(4).getType());
            assertEquals(5L, broadcaster.getTotalEventsBroadcast());
        } finally {
            broadcaster.shutdown();
        }
    }
}
