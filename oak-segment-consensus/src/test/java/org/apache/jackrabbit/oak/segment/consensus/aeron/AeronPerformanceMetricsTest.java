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

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AeronPerformanceMetricsTest {

    @Test
    public void recordedMetricsAppearInSnapshots() {
        AeronPerformanceMetrics metrics = new AeronPerformanceMetrics();

        metrics.recordMessageIngressed();
        metrics.recordMessageIngressed();
        metrics.recordMessageReplicated(System.nanoTime() - 1_000_000L);
        metrics.recordMessageFailed();
        metrics.recordBatch(3);
        metrics.recordBatch(0);
        metrics.updateQueueDepths(4, 5);

        AeronPerformanceMetrics.Snapshot snapshot = metrics.getSnapshot();

        assertEquals(2L, snapshot.totalIngressed);
        assertEquals(1L, snapshot.totalReplicated);
        assertEquals(1L, snapshot.totalFailed);
        assertEquals(1L, snapshot.totalBatches);
        assertEquals(3L, snapshot.totalMessagesInBatches);
        assertEquals(4L, snapshot.currentPendingIngress);
        assertEquals(5L, snapshot.currentPendingAcknowledgments);
        assertTrue(snapshot.avgRaftLatencyNanos > 0);
        assertTrue(snapshot.minRaftLatencyNanos > 0);
        assertTrue(snapshot.maxRaftLatencyNanos > 0);
    }

    @Test
    public void futureIngressTimestampIsIgnoredAndResetClearsCounters() {
        AeronPerformanceMetrics metrics = new AeronPerformanceMetrics();

        metrics.recordMessageIngressed();
        metrics.recordMessageReplicated(System.nanoTime() + 1_000_000_000L);

        AeronPerformanceMetrics.Snapshot ignored = metrics.getSnapshot();
        assertEquals(0L, ignored.avgRaftLatencyNanos);
        assertEquals(0L, ignored.minRaftLatencyNanos);
        assertEquals(0L, ignored.maxRaftLatencyNanos);

        metrics.reset();

        AeronPerformanceMetrics.Snapshot reset = metrics.getSnapshot();
        assertEquals(0L, reset.totalIngressed);
        assertEquals(0L, reset.totalReplicated);
        assertEquals(0L, reset.totalFailed);
        assertEquals(0L, reset.totalBatches);
        assertEquals(0L, reset.totalMessagesInBatches);
        assertEquals(0L, reset.avgRaftLatencyNanos);
        assertEquals(0L, reset.minRaftLatencyNanos);
        assertEquals(0L, reset.maxRaftLatencyNanos);
    }

    @Test
    public void snapshotDerivedMetricsHandleZeroValuesAndFormulas() {
        AeronPerformanceMetrics.Snapshot zero = new AeronPerformanceMetrics.Snapshot(
            1000L, 1000L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L
        );
        AeronPerformanceMetrics.Snapshot loaded = new AeronPerformanceMetrics.Snapshot(
            2000L, 1000L, 10L, 5L, 5L, 20_000_000L, 900L, 2_000_000L, 2L, 8L, 3L, 4L
        );

        assertEquals(0.0, zero.getThroughput(), 0.0);
        assertEquals(0.0, zero.getAvgBatchSize(), 0.0);
        assertEquals(0.0, zero.getSuccessRate(), 0.0);
        assertEquals(0.0, zero.getTheoreticalMaxThroughput(), 0.0);
        assertEquals(0.0, zero.getUtilization(), 0.0);

        assertEquals(5.0, loaded.getThroughput(), 0.0001);
        assertEquals(4.0, loaded.getAvgBatchSize(), 0.0001);
        assertEquals(0.5, loaded.getSuccessRate(), 0.0001);
        assertEquals(50.0, loaded.getTheoreticalMaxThroughput(), 0.0001);
        assertEquals(10.0, loaded.getUtilization(), 0.0001);
    }

    @Test
    public void snapshotFormatsLatenciesAcrossUnitsAndSummary() {
        AeronPerformanceMetrics.Snapshot nanos = new AeronPerformanceMetrics.Snapshot(
            2000L, 1000L, 1L, 1L, 0L, 999L, 900L, 999L, 1L, 1L, 0L, 0L
        );
        AeronPerformanceMetrics.Snapshot micros = new AeronPerformanceMetrics.Snapshot(
            2000L, 1000L, 1L, 1L, 0L, 1_500L, 1_500L, 2_000L, 1L, 1L, 0L, 0L
        );
        AeronPerformanceMetrics.Snapshot millis = new AeronPerformanceMetrics.Snapshot(
            2000L, 1000L, 10L, 5L, 0L, 2_000_000L, 900L, 2_000_000L, 2L, 8L, 3L, 4L
        );

        assertEquals("999ns", nanos.getAvgRaftLatencyFormatted());
        assertEquals("1.50\u03bcs", micros.getAvgRaftLatencyFormatted());
        assertEquals("2.00ms", millis.getAvgRaftLatencyFormatted());

        String summary = millis.toSummaryString();
        String microSummary = micros.toSummaryString();
        assertTrue(summary.contains("Latency: 2.00ms"));
        assertTrue(summary.contains("min: 900ns"));
        assertTrue(summary.contains("max: 2.0ms"));
        assertTrue(summary.contains("Batch: 4.0 msgs/batch"));
        assertTrue(summary.contains("Success: 50.0%"));
        assertTrue(summary.contains("Queue: 3 ingress, 4 pending ACKs"));
        assertTrue(microSummary.contains("min: 1.5\u03bcs"));
    }
}
