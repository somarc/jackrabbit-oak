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

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;

import io.aeron.Aeron;
import io.prometheus.client.CollectorRegistry;
import org.agrona.collections.IntObjConsumer;
import org.agrona.concurrent.AtomicBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.status.CountersReader;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static io.aeron.driver.status.PublisherLimit.PUBLISHER_LIMIT_TYPE_ID;
import static org.agrona.concurrent.status.CountersReader.RECORD_ALLOCATED;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class AeronPrometheusMetricsTest {

    private static final int STREAM_COUNTER_ID = 40;

    @Before
    @After
    public void clearRegistry() {
        CollectorRegistry.defaultRegistry.clear();
    }

    @Test
    public void constructorRejectsNullAeron() {
        try {
            new AeronPrometheusMetrics(null);
            fail("expected constructor to reject null aeron");
        } catch (IllegalArgumentException e) {
            assertEquals("Aeron instance cannot be null", e.getMessage());
        }
    }

    @Test
    public void updateMetricsRegistersAndUpdatesSystemAndStreamCounters() throws Exception {
        StubCountersReader reader = new StubCountersReader(64);
        AeronPrometheusMetrics metrics = metrics(reader);
        try {
            shutdownBackgroundUpdates(metrics);
            reader.setCounterValue(io.aeron.driver.status.SystemCounterDescriptor.ERRORS.id(), 11L);
            reader.setStreamCounter(
                STREAM_COUNTER_ID,
                PUBLISHER_LIMIT_TYPE_ID,
                123L,
                "publisher limit",
                42,
                7,
                "aeron:udp?endpoint=127.0.0.1:9000");
            metrics.updateMetrics();

            Double streamValue = CollectorRegistry.defaultRegistry.getSampleValue(
                "aeron_pub_limit", new String[] {"session_id", "stream_id", "channel"},
                new String[] {"42", "7", "aeron:udp?endpoint=127.0.0.1:9000"});
            assertTrue(streamValue != null && streamValue == 123.0);
            assertEquals(11.0, CollectorRegistry.defaultRegistry.getSampleValue("aeron_errors"), 0.0);

            metrics.updateGaugeValues();
            assertEquals(123.0, CollectorRegistry.defaultRegistry.getSampleValue(
                "aeron_pub_limit", new String[] {"session_id", "stream_id", "channel"},
                new String[] {"42", "7", "aeron:udp?endpoint=127.0.0.1:9000"}), 0.0);
        } finally {
            metrics.close();
        }
    }

    @Test
    public void updateMetricsCanRemoveInactiveStreamGaugesAndIgnoreClosedState() throws Exception {
        StubCountersReader reader = new StubCountersReader(64);
        AeronPrometheusMetrics metrics = metrics(reader);
        try {
            shutdownBackgroundUpdates(metrics);
            reader.setStreamCounter(
                STREAM_COUNTER_ID,
                PUBLISHER_LIMIT_TYPE_ID,
                5L,
                "publisher limit",
                42,
                7,
                "aeron:udp?endpoint=127.0.0.1:9000");
            metrics.updateMetrics();
            assertEquals(1, streamGaugeCount(metrics));

            reader.clearCounter(STREAM_COUNTER_ID);
            metrics.updateMetrics();
            assertEquals(0, streamGaugeCount(metrics));
            assertEquals(5.0, CollectorRegistry.defaultRegistry.getSampleValue(
                "aeron_pub_limit", new String[] {"session_id", "stream_id", "channel"},
                new String[] {"42", "7", "aeron:udp?endpoint=127.0.0.1:9000"}), 0.0);

            metrics.close();
            metrics.updateMetrics();
            assertEquals(0, streamGaugeCount(metrics));
        } finally {
            metrics.close();
        }
    }

    @Test
    public void registerIfStreamCounterHandlesUnknownAndExceptionalCounters() throws Exception {
        StubCountersReader reader = new StubCountersReader(64);
        AeronPrometheusMetrics metrics = metrics(reader);
        try {
            shutdownBackgroundUpdates(metrics);
            reader.setCounterType(1, -1);
            assertFalse(registerIfStreamCounter(metrics, 1));

            reader.setStreamCounter(2, PUBLISHER_LIMIT_TYPE_ID, 0L, "publisher limit", 11, 12, "aeron:ipc");
            reader.setThrowMetaDataBuffer(true);
            assertFalse(registerIfStreamCounter(metrics, 2));
            reader.setThrowMetaDataBuffer(false);

            assertEquals("pub_limit", invokeStreamMetricName(PUBLISHER_LIMIT_TYPE_ID));
            assertEquals("snd_limit", invokeStreamMetricName(io.aeron.driver.status.SenderLimit.SENDER_LIMIT_TYPE_ID));
            assertEquals("rcv_pos", invokeStreamMetricName(io.aeron.driver.status.ReceiverPos.RECEIVER_POS_TYPE_ID));
            assertEquals("sub_pos", invokeStreamMetricName(io.aeron.driver.status.PerImageIndicator.PER_IMAGE_TYPE_ID));
            assertEquals("pub_pos", invokeStreamMetricName(io.aeron.driver.status.PublisherPos.PUBLISHER_POS_TYPE_ID));
            assertEquals("stream_counter_999", invokeStreamMetricName(999));
            assertEquals("hello_world", sanitize("HELLO_WORLD"));
            assertEquals("hello_world_42", sanitize("HELLO-WORLD 42"));
        } finally {
            metrics.close();
        }
    }

    @Test
    public void closeIsIdempotentAndPreservesInterruptStatus() {
        CountersReader reader = mock(CountersReader.class);
        AeronPrometheusMetrics metrics = metrics(reader);

        Thread.currentThread().interrupt();
        metrics.close();
        assertTrue(Thread.interrupted());

        metrics.close();
    }

    private static AeronPrometheusMetrics metrics(CountersReader reader) {
        Aeron aeron = mock(Aeron.class);
        when(aeron.countersReader()).thenReturn(reader);
        return new AeronPrometheusMetrics(aeron);
    }

    private static boolean registerIfStreamCounter(AeronPrometheusMetrics metrics, int counterId) throws Exception {
        Method method = AeronPrometheusMetrics.class.getDeclaredMethod("registerIfStreamCounter", int.class);
        method.setAccessible(true);
        return (Boolean) method.invoke(metrics, counterId);
    }

    private static String invokeStreamMetricName(int typeId) throws Exception {
        Method method = AeronPrometheusMetrics.class.getDeclaredMethod("streamMetricName", int.class);
        method.setAccessible(true);
        return (String) method.invoke(null, typeId);
    }

    private static String sanitize(String input) throws Exception {
        Method method = AeronPrometheusMetrics.class.getDeclaredMethod("sanitize", String.class);
        method.setAccessible(true);
        return (String) method.invoke(null, input);
    }

    @SuppressWarnings("unchecked")
    private static int streamGaugeCount(AeronPrometheusMetrics metrics) throws Exception {
        Field field = AeronPrometheusMetrics.class.getDeclaredField("streamGauges");
        field.setAccessible(true);
        return ((Map<Integer, ?>) field.get(metrics)).size();
    }

    private static void shutdownBackgroundUpdates(AeronPrometheusMetrics metrics) throws Exception {
        Field field = AeronPrometheusMetrics.class.getDeclaredField("executor");
        field.setAccessible(true);
        ((ScheduledExecutorService) field.get(metrics)).shutdownNow();
    }

    private static final class StubCountersReader extends CountersReader {

        private final UnsafeBuffer metaData;
        private final UnsafeBuffer values;
        private final Set<Integer> activeCounterIds = new LinkedHashSet<>();
        private boolean throwMetaDataBuffer;

        private StubCountersReader(int maxCounterCount) {
            this(new UnsafeBuffer(new byte[maxCounterCount * METADATA_LENGTH]),
                new UnsafeBuffer(new byte[maxCounterCount * COUNTER_LENGTH]));
        }

        private StubCountersReader(UnsafeBuffer metaData, UnsafeBuffer values) {
            super(metaData, values);
            this.metaData = metaData;
            this.values = values;
        }

        @Override
        public AtomicBuffer metaDataBuffer() {
            if (throwMetaDataBuffer) {
                throw new RuntimeException("boom");
            }
            return metaData;
        }

        @Override
        public void forEach(IntObjConsumer<String> consumer) {
            for (int counterId : activeCounterIds) {
                consumer.accept(counterId, getCounterLabel(counterId));
            }
        }

        private void setCounterValue(int counterId, long value) {
            values.putLong(counterOffset(counterId), value);
        }

        private void setCounterType(int counterId, int typeId) {
            metaData.putInt(metaDataOffset(counterId), RECORD_ALLOCATED);
            metaData.putInt(metaDataOffset(counterId) + TYPE_ID_OFFSET, typeId);
        }

        private void setStreamCounter(
            int counterId, int typeId, long value, String label, int sessionId, int streamId, String channel) {
            setCounterType(counterId, typeId);
            setCounterValue(counterId, value);

            int keyOffset = metaDataOffset(counterId) + KEY_OFFSET;
            metaData.putInt(keyOffset + io.aeron.driver.status.StreamCounter.SESSION_ID_OFFSET, sessionId);
            metaData.putInt(keyOffset + io.aeron.driver.status.StreamCounter.STREAM_ID_OFFSET, streamId);
            byte[] channelBytes = channel.getBytes(StandardCharsets.US_ASCII);
            metaData.putInt(keyOffset + io.aeron.driver.status.StreamCounter.CHANNEL_OFFSET, channelBytes.length);
            metaData.putBytes(
                keyOffset + io.aeron.driver.status.StreamCounter.CHANNEL_OFFSET + Integer.BYTES,
                channelBytes);

            byte[] labelBytes = label.getBytes(StandardCharsets.UTF_8);
            metaData.putInt(metaDataOffset(counterId) + LABEL_OFFSET, labelBytes.length);
            metaData.putBytes(metaDataOffset(counterId) + LABEL_OFFSET + Integer.BYTES, labelBytes);

            activeCounterIds.add(counterId);
        }

        private void clearCounter(int counterId) {
            activeCounterIds.remove(counterId);
            metaData.putInt(metaDataOffset(counterId), 0);
            values.putLong(counterOffset(counterId), 0L);
        }

        private void setThrowMetaDataBuffer(boolean throwMetaDataBuffer) {
            this.throwMetaDataBuffer = throwMetaDataBuffer;
        }
    }
}
