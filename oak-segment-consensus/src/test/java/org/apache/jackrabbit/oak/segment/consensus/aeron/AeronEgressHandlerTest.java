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

import java.util.concurrent.atomic.AtomicInteger;

import io.aeron.Publication;
import io.aeron.cluster.client.AeronCluster;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.IdleStrategy;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class AeronEgressHandlerTest {

    @Test
    public void offerWithRetryReturnsFalseWhenClientIsMissing() {
        AeronEgressHandler handler = new AeronEgressHandler();
        RecordingIdleStrategy idleStrategy = new RecordingIdleStrategy();

        boolean sent = handler.offerWithRetry(
            null,
            idleStrategy,
            mock(MutableDirectBuffer.class),
            16,
            "write",
            2,
            null,
            false
        );

        assertFalse(sent);
        assertEquals(0, idleStrategy.resetCalls);
        assertEquals(0, idleStrategy.idleCalls);
    }

    @Test
    public void offerWithRetryRetriesBackpressureAndRunsSuccessCallback() {
        AeronEgressHandler handler = new AeronEgressHandler();
        RecordingIdleStrategy idleStrategy = new RecordingIdleStrategy();
        AeronCluster client = mock(AeronCluster.class);
        AtomicInteger successCalls = new AtomicInteger();
        when(client.offer(any(MutableDirectBuffer.class), eq(0), eq(16)))
            .thenReturn(Publication.BACK_PRESSURED, 42L);

        boolean sent = handler.offerWithRetry(
            client,
            idleStrategy,
            mock(MutableDirectBuffer.class),
            16,
            "write",
            2,
            successCalls::incrementAndGet,
            true
        );

        assertTrue(sent);
        assertEquals(1, successCalls.get());
        assertEquals(1, idleStrategy.resetCalls);
        assertEquals(1, idleStrategy.idleCalls);
    }

    @Test
    public void offerWithRetryFailsAfterBackpressureRetriesExhausted() {
        AeronEgressHandler handler = new AeronEgressHandler();
        RecordingIdleStrategy idleStrategy = new RecordingIdleStrategy();
        AeronCluster client = mock(AeronCluster.class);
        when(client.offer(any(MutableDirectBuffer.class), eq(0), eq(16)))
            .thenReturn(Publication.BACK_PRESSURED, Publication.BACK_PRESSURED);

        boolean sent = handler.offerWithRetry(
            client,
            idleStrategy,
            mock(MutableDirectBuffer.class),
            16,
            "write",
            1,
            null,
            false
        );

        assertFalse(sent);
        assertEquals(1, idleStrategy.resetCalls);
        assertEquals(2, idleStrategy.idleCalls);
    }

    @Test
    public void offerWithRetryHandlesNotConnectedRetries() {
        AeronEgressHandler handler = new AeronEgressHandler();
        RecordingIdleStrategy idleStrategy = new RecordingIdleStrategy();
        AeronCluster client = mock(AeronCluster.class);
        when(client.offer(any(MutableDirectBuffer.class), eq(0), eq(16)))
            .thenReturn(Publication.NOT_CONNECTED, 11L);

        boolean sent = handler.offerWithRetry(
            client,
            idleStrategy,
            mock(MutableDirectBuffer.class),
            16,
            "write",
            2,
            null,
            false
        );

        assertTrue(sent);
        assertEquals(1, idleStrategy.resetCalls);
        assertEquals(1, idleStrategy.idleCalls);
    }

    @Test
    public void offerWithRetryFailsWhenNotConnectedRetriesAreExhausted() {
        AeronEgressHandler handler = new AeronEgressHandler();
        RecordingIdleStrategy idleStrategy = new RecordingIdleStrategy();
        AeronCluster client = mock(AeronCluster.class);
        when(client.offer(any(MutableDirectBuffer.class), eq(0), eq(16)))
            .thenReturn(Publication.NOT_CONNECTED, Publication.NOT_CONNECTED);

        boolean sent = handler.offerWithRetry(
            client,
            idleStrategy,
            mock(MutableDirectBuffer.class),
            16,
            "write",
            1,
            null,
            false
        );

        assertFalse(sent);
        assertEquals(1, idleStrategy.resetCalls);
        assertEquals(2, idleStrategy.idleCalls);
    }

    @Test
    public void offerWithRetryFailsOnUnexpectedNegativeResult() {
        AeronEgressHandler handler = new AeronEgressHandler();
        RecordingIdleStrategy idleStrategy = new RecordingIdleStrategy();
        AeronCluster client = mock(AeronCluster.class);
        when(client.offer(any(MutableDirectBuffer.class), eq(0), eq(16))).thenReturn(-99L);

        boolean sent = handler.offerWithRetry(
            client,
            idleStrategy,
            mock(MutableDirectBuffer.class),
            16,
            "write",
            2,
            null,
            false
        );

        assertFalse(sent);
        assertEquals(1, idleStrategy.resetCalls);
        assertEquals(0, idleStrategy.idleCalls);
    }

    private static final class RecordingIdleStrategy implements IdleStrategy {
        private int idleCalls;
        private int resetCalls;

        @Override
        public void idle(int workCount) {
            if (workCount <= 0) {
                idleCalls++;
            }
        }

        @Override
        public void idle() {
            idleCalls++;
        }

        @Override
        public void reset() {
            resetCalls++;
        }
    }
}
