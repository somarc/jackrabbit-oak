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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Tracks Aeron Cluster and Raft consensus performance metrics.
 * 
 * <p>Key metrics tracked:
 * <ul>
 *   <li>Raft consensus latency (time from ingress to replication acknowledgment)</li>
 *   <li>Throughput (writes/sec, batch sizes)</li>
 *   <li>Queue depths (pending ingress, pending acknowledgments)</li>
 *   <li>Utilization (actual throughput vs theoretical max)</li>
 * </ul>
 * 
 * <p>This helps identify bottlenecks:
 * <ul>
 *   <li>High Raft latency → Network or disk bottleneck</li>
 *   <li>Low batch sizes → Insufficient load or batching disabled</li>
 *   <li>High pending ACKs → Backpressure kicking in</li>
 *   <li>Low utilization → Headroom available</li>
 * </ul>
 */
public class AeronPerformanceMetrics {
    private static final Logger log = LoggerFactory.getLogger(AeronPerformanceMetrics.class);
    
    // Throughput metrics
    private final LongAdder totalMessagesIngressed = new LongAdder();
    private final LongAdder totalMessagesReplicated = new LongAdder();
    private final LongAdder totalMessagesFailed = new LongAdder();
    
    // Latency tracking
    private final AtomicLong totalRaftLatencyNanos = new AtomicLong(0);
    private final LongAdder raftLatencySamples = new LongAdder();
    private final AtomicLong minRaftLatencyNanos = new AtomicLong(Long.MAX_VALUE);
    private final AtomicLong maxRaftLatencyNanos = new AtomicLong(0);
    
    // Batch tracking
    private final LongAdder totalBatches = new LongAdder();
    private final LongAdder totalMessagesInBatches = new LongAdder();
    
    // Queue depths (snapshots)
    private volatile long currentPendingIngress = 0;
    private volatile long currentPendingAcknowledgments = 0;
    
    // Timing
    private volatile long metricsStartTime = System.currentTimeMillis();
    
    public AeronPerformanceMetrics() {
        log.info("✅ AeronPerformanceMetrics initialized");
    }
    
    /**
     * Record that a message was ingressed to Raft (sent to leader).
     */
    public void recordMessageIngressed() {
        totalMessagesIngressed.increment();
    }
    
    /**
     * Record that a message was successfully replicated via Raft.
     * 
     * @param ingressTimestampNanos When the message was first ingressed (from System.nanoTime())
     */
    public void recordMessageReplicated(long ingressTimestampNanos) {
        totalMessagesReplicated.increment();
        
        // Calculate Raft consensus latency
        long replicationTimestampNanos = System.nanoTime();
        long latencyNanos = replicationTimestampNanos - ingressTimestampNanos;
        
        if (latencyNanos > 0) { // Guard against clock skew
            totalRaftLatencyNanos.addAndGet(latencyNanos);
            raftLatencySamples.increment();
            
            // Update min/max
            updateMin(minRaftLatencyNanos, latencyNanos);
            updateMax(maxRaftLatencyNanos, latencyNanos);
        }
    }
    
    /**
     * Record that a message failed to be replicated.
     */
    public void recordMessageFailed() {
        totalMessagesFailed.increment();
    }
    
    /**
     * Record that a batch of messages was processed.
     * 
     * @param batchSize Number of messages in the batch
     */
    public void recordBatch(int batchSize) {
        if (batchSize > 0) {
            totalBatches.increment();
            totalMessagesInBatches.add(batchSize);
        }
    }
    
    /**
     * Update current queue depths (called periodically).
     * 
     * @param pendingIngress Messages waiting to be sent to Raft
     * @param pendingAcknowledgments Messages sent to Raft but not yet acknowledged
     */
    public void updateQueueDepths(long pendingIngress, long pendingAcknowledgments) {
        this.currentPendingIngress = pendingIngress;
        this.currentPendingAcknowledgments = pendingAcknowledgments;
    }
    
    /**
     * Get a snapshot of current metrics.
     */
    public Snapshot getSnapshot() {
        long now = System.currentTimeMillis();
        long totalIngressed = totalMessagesIngressed.sum();
        long totalReplicated = totalMessagesReplicated.sum();
        long totalFailed = totalMessagesFailed.sum();
        long samples = raftLatencySamples.sum();
        long totalLatency = totalRaftLatencyNanos.get();
        long batches = totalBatches.sum();
        long messagesInBatches = totalMessagesInBatches.sum();
        
        return new Snapshot(
            now,
            metricsStartTime,
            totalIngressed,
            totalReplicated,
            totalFailed,
            samples > 0 ? totalLatency / samples : 0,
            minRaftLatencyNanos.get() == Long.MAX_VALUE ? 0 : minRaftLatencyNanos.get(),
            maxRaftLatencyNanos.get(),
            batches,
            messagesInBatches,
            currentPendingIngress,
            currentPendingAcknowledgments
        );
    }
    
    /**
     * Reset all counters (useful for testing or periodic reporting).
     */
    public void reset() {
        totalMessagesIngressed.reset();
        totalMessagesReplicated.reset();
        totalMessagesFailed.reset();
        totalRaftLatencyNanos.set(0);
        raftLatencySamples.reset();
        minRaftLatencyNanos.set(Long.MAX_VALUE);
        maxRaftLatencyNanos.set(0);
        totalBatches.reset();
        totalMessagesInBatches.reset();
        metricsStartTime = System.currentTimeMillis();
        
        log.info("📊 AeronPerformanceMetrics reset");
    }
    
    private void updateMin(AtomicLong current, long value) {
        long currentMin = current.get();
        while (value < currentMin) {
            if (current.compareAndSet(currentMin, value)) {
                break;
            }
            currentMin = current.get();
        }
    }
    
    private void updateMax(AtomicLong current, long value) {
        long currentMax = current.get();
        while (value > currentMax) {
            if (current.compareAndSet(currentMax, value)) {
                break;
            }
            currentMax = current.get();
        }
    }
    
    /**
     * Immutable snapshot of metrics at a point in time.
     */
    public static class Snapshot {
        public final long snapshotTime;
        public final long metricsStartTime;
        public final long totalIngressed;
        public final long totalReplicated;
        public final long totalFailed;
        public final long avgRaftLatencyNanos;
        public final long minRaftLatencyNanos;
        public final long maxRaftLatencyNanos;
        public final long totalBatches;
        public final long totalMessagesInBatches;
        public final long currentPendingIngress;
        public final long currentPendingAcknowledgments;
        
        public Snapshot(
                long snapshotTime,
                long metricsStartTime,
                long totalIngressed,
                long totalReplicated,
                long totalFailed,
                long avgRaftLatencyNanos,
                long minRaftLatencyNanos,
                long maxRaftLatencyNanos,
                long totalBatches,
                long totalMessagesInBatches,
                long currentPendingIngress,
                long currentPendingAcknowledgments) {
            this.snapshotTime = snapshotTime;
            this.metricsStartTime = metricsStartTime;
            this.totalIngressed = totalIngressed;
            this.totalReplicated = totalReplicated;
            this.totalFailed = totalFailed;
            this.avgRaftLatencyNanos = avgRaftLatencyNanos;
            this.minRaftLatencyNanos = minRaftLatencyNanos;
            this.maxRaftLatencyNanos = maxRaftLatencyNanos;
            this.totalBatches = totalBatches;
            this.totalMessagesInBatches = totalMessagesInBatches;
            this.currentPendingIngress = currentPendingIngress;
            this.currentPendingAcknowledgments = currentPendingAcknowledgments;
        }
        
        /**
         * Calculate throughput (messages/sec) since metrics start.
         */
        public double getThroughput() {
            long durationMs = snapshotTime - metricsStartTime;
            if (durationMs == 0) return 0;
            return (totalReplicated * 1000.0) / durationMs;
        }
        
        /**
         * Calculate average batch size.
         */
        public double getAvgBatchSize() {
            if (totalBatches == 0) return 0;
            return (double) totalMessagesInBatches / totalBatches;
        }
        
        /**
         * Calculate success rate (0-1).
         */
        public double getSuccessRate() {
            long totalAttempted = totalIngressed;
            if (totalAttempted == 0) return 0;
            return (double) totalReplicated / totalAttempted;
        }
        
        /**
         * Calculate theoretical max throughput based on Raft latency.
         */
        public double getTheoreticalMaxThroughput() {
            if (avgRaftLatencyNanos == 0) return 0;
            // Max throughput = 1 / latency (in messages/sec)
            return 1_000_000_000.0 / avgRaftLatencyNanos;
        }
        
        /**
         * Calculate utilization percentage (0-100).
         */
        public double getUtilization() {
            double theoreticalMax = getTheoreticalMaxThroughput();
            if (theoreticalMax == 0) return 0;
            return (getThroughput() / theoreticalMax) * 100;
        }
        
        /**
         * Format Raft latency for human readability.
         */
        public String getAvgRaftLatencyFormatted() {
            if (avgRaftLatencyNanos < 1_000) {
                return String.format("%dns", avgRaftLatencyNanos);
            } else if (avgRaftLatencyNanos < 1_000_000) {
                return String.format("%.2fμs", avgRaftLatencyNanos / 1_000.0);
            } else {
                return String.format("%.2fms", avgRaftLatencyNanos / 1_000_000.0);
            }
        }
        
        /**
         * Format summary for logging.
         */
        public String toSummaryString() {
            return String.format(
                "📊 Raft Performance: %.0f w/s (%.1f%% utilization) | " +
                "Latency: %s (min: %s, max: %s) | " +
                "Batch: %.1f msgs/batch | " +
                "Success: %.1f%% | " +
                "Queue: %d ingress, %d pending ACKs | " +
                "Theoretical max: %.0f w/s",
                getThroughput(),
                getUtilization(),
                getAvgRaftLatencyFormatted(),
                formatLatency(minRaftLatencyNanos),
                formatLatency(maxRaftLatencyNanos),
                getAvgBatchSize(),
                getSuccessRate() * 100,
                currentPendingIngress,
                currentPendingAcknowledgments,
                getTheoreticalMaxThroughput()
            );
        }
        
        private String formatLatency(long nanos) {
            if (nanos < 1_000) {
                return String.format("%dns", nanos);
            } else if (nanos < 1_000_000) {
                return String.format("%.1fμs", nanos / 1_000.0);
            } else {
                return String.format("%.1fms", nanos / 1_000_000.0);
            }
        }
    }
}

