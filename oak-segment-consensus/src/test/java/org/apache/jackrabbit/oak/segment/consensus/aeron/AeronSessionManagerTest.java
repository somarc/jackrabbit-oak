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
package org.apache.jackrabbit.oak.segment.consensus.aeron;

import io.aeron.cluster.codecs.CloseReason;
import io.aeron.cluster.service.ClientSession;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class AeronSessionManagerTest {

    @Test
    public void timeoutTriggersReconnectCallback() {
        AtomicInteger heartbeatCount = new AtomicInteger(0);
        AtomicReference<String> reconnectReason = new AtomicReference<>(null);
        AeronSessionManager manager = new AeronSessionManager(
            heartbeatCount::incrementAndGet,
            reconnectReason::set
        );

        ClientSession session = mock(ClientSession.class);
        when(session.id()).thenReturn(42L);
        manager.onSessionClose(session, System.currentTimeMillis(), CloseReason.TIMEOUT);

        assertEquals(1, heartbeatCount.get());
        assertEquals("session_timeout", reconnectReason.get());
    }

    @Test
    public void nonTimeoutCloseDoesNotReconnect() {
        AtomicInteger heartbeatCount = new AtomicInteger(0);
        AtomicReference<String> reconnectReason = new AtomicReference<>(null);
        AeronSessionManager manager = new AeronSessionManager(
            heartbeatCount::incrementAndGet,
            reconnectReason::set
        );

        ClientSession session = mock(ClientSession.class);
        when(session.id()).thenReturn(99L);
        manager.onSessionClose(session, System.currentTimeMillis(), CloseReason.CLIENT_ACTION);

        assertEquals(1, heartbeatCount.get());
        assertNull(reconnectReason.get());
    }
}
