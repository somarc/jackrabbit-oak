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
package org.apache.jackrabbit.oak.segment.consensus.queue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * Manages backpressure for writes to the Aeron cluster.
 * 
 * <p>Tracks pending (unacknowledged) messages and applies backpressure when
 * the pending count exceeds the configured maximum. This prevents overwhelming
 * the Aeron cluster and avoids session timeouts.
 * 
 * <p><b>Key Concepts:</b>
 * <ul>
 *   <li><b>Sent Messages:</b> Messages offered to Aeron cluster</li>
 *   <li><b>Acknowledged Messages:</b> Messages confirmed by Aeron (replicated)</li>
 *   <li><b>Pending Messages:</b> Sent but not yet acknowledged (sent - acked)</li>
 *   <li><b>Backpressure:</b> Block new writes when pending >= max</li>
 * </ul>
 * 
 * <p><b>Benefits over Fixed Rate Limiting:</b>
 * <ul>
 *   <li>Dynamic - only blocks when actually needed</li>
 *   <li>Fast path - no delay when cluster healthy</li>
 *   <li>Self-regulating - adjusts to cluster capacity</li>
 *   <li>Higher throughput - not artificially limited</li>
 * </ul>
 * 
 * <p>Uses Aeron Cluster flow control pattern with configurable thresholds.
 */
public class BackpressureManager {
    
    private static final Logger log = LoggerFactory.getLogger(BackpressureManager.class);
    
    /**
     * Maximum pending (unacknowledged) messages before applying backpressure.
     * Configurable via system property.
     * 
     * Default: 2000 messages
     */
    private static final long MAX_PENDING_MESSAGES = 
        Long.getLong("oak.consensus.max.pending.messages", 2000);
    
    /**
     * Timeout in milliseconds for backpressure wait.
     * If pending messages remain above max for this long, throw exception.
     * 
     * Default: 10000ms (10 seconds)
     */
    private static final long BACKPRESSURE_TIMEOUT_MS =
        Long.getLong("oak.consensus.backpressure.timeout.ms", 10000);
    
    /**
     * Park duration in nanoseconds when waiting for acknowledgments.
     * 1ms = 1,000,000 nanoseconds
     */
    private static final long PARK_NANOS = 1_000_000; // 1ms
    
    /**
     * Counter for messages sent to Aeron cluster.
     * Incremented by ProposalQueueManager when offering to Aeron.
     */
    private final AtomicLong sentCount = new AtomicLong(0);
    
    /**
     * Counter for messages acknowledged by Aeron cluster.
     * Incremented by AeronConsensusEngine when snapshots applied.
     */
    private final AtomicLong acknowledgedCount = new AtomicLong(0);
    
    /**
     * Volatile flag to track if backpressure is currently active.
     * Used for logging state transitions.
     */
    private volatile boolean backpressureActive = false;
    
    /**
     * Create a new backpressure manager.
     */
    public BackpressureManager() {
        log.info("BackpressureManager initialized:");
        log.info("  Max pending messages: {}", MAX_PENDING_MESSAGES);
        log.info("  Backpressure timeout: {}ms", BACKPRESSURE_TIMEOUT_MS);
    }
    
    /**
     * Increment sent message counter.
     * Called by ProposalQueueManager after offering message to Aeron.
     */
    public void incrementSent() {
        sentCount.incrementAndGet();
    }
    
    /**
     * Increment acknowledged message counter.
     * Called by AeronConsensusEngine after write applied via snapshot.
     */
    public void incrementAcknowledged() {
        acknowledgedCount.incrementAndGet();
    }
    
    /**
     * Get current number of pending (unacknowledged) messages.
     * 
     * @return Sent messages minus acknowledged messages
     */
    public long getPendingCount() {
        return sentCount.get() - acknowledgedCount.get();
    }
    
    /**
     * Get total messages sent to Aeron.
     * 
     * @return Total sent count
     */
    public long getSentCount() {
        return sentCount.get();
    }
    
    /**
     * Get total messages acknowledged by Aeron.
     * 
     * @return Total acknowledged count
     */
    public long getAcknowledgedCount() {
        return acknowledgedCount.get();
    }
    
    /**
     * Check if backpressure is currently active.
     * 
     * @return true if pending messages >= max
     */
    public boolean isBackpressureActive() {
        return backpressureActive;
    }
    
    /**
     * Apply backpressure if pending messages exceed maximum.
     * 
     * <p>Blocks the calling thread until pending messages drop below max
     * or timeout is reached. Uses LockSupport.parkNanos() for efficient
     * waiting (yields CPU instead of busy-waiting).
     * 
     * <p><b>Flow:</b>
     * <ol>
     *   <li>Check if pending >= max</li>
     *   <li>If yes, enter backpressure loop</li>
     *   <li>Park thread for 1ms (yield CPU)</li>
     *   <li>Check again until pending < max</li>
     *   <li>If timeout exceeded, throw exception</li>
     *   <li>If pending drops, exit and allow send</li>
     * </ol>
     * 
     * @throws BackpressureTimeoutException if timeout exceeded
     */
    public void applyBackpressureIfNeeded() throws BackpressureTimeoutException {
        long pending = getPendingCount();
        
        // Fast path: no backpressure needed
        if (pending < MAX_PENDING_MESSAGES) {
            if (backpressureActive) {
                backpressureActive = false;
                log.info("✅ Releasing backpressure: pending={}, max={}", 
                    pending, MAX_PENDING_MESSAGES);
            }
            return;
        }
        
        // Slow path: apply backpressure
        if (!backpressureActive) {
            backpressureActive = true;
            log.warn("⚠️  Applying backpressure: pending={}, max={}", 
                pending, MAX_PENDING_MESSAGES);
            log.warn("   Aeron cluster is slower than write rate - blocking new writes");
        }
        
        long startTime = System.currentTimeMillis();
        long deadline = startTime + BACKPRESSURE_TIMEOUT_MS;
        
        // Wait for pending count to drop below max
        while (getPendingCount() >= MAX_PENDING_MESSAGES) {
            // Check timeout
            if (System.currentTimeMillis() > deadline) {
                long currentPending = getPendingCount();
                log.error("❌ Backpressure timeout: pending={}, max={}, waited={}ms",
                    currentPending, MAX_PENDING_MESSAGES, BACKPRESSURE_TIMEOUT_MS);
                throw new BackpressureTimeoutException(
                    currentPending, 
                    MAX_PENDING_MESSAGES, 
                    BACKPRESSURE_TIMEOUT_MS
                );
            }
            
            // Park thread for 1ms (efficient waiting)
            // LockSupport.parkNanos() yields CPU instead of busy-waiting
            LockSupport.parkNanos(PARK_NANOS);
        }
        
        // Backpressure released
        long waitTime = System.currentTimeMillis() - startTime;
        backpressureActive = false;
        log.info("✅ Backpressure released after {}ms: pending={}, max={}",
            waitTime, getPendingCount(), MAX_PENDING_MESSAGES);
    }
    
    /**
     * Reset counters (for testing).
     */
    public void reset() {
        sentCount.set(0);
        acknowledgedCount.set(0);
        backpressureActive = false;
    }
    
    /**
     * Get statistics summary for logging/metrics.
     * 
     * @return Human-readable statistics
     */
    public String getStats() {
        return String.format(
            "BackpressureManager[sent=%d, acked=%d, pending=%d, max=%d, active=%s]",
            getSentCount(),
            getAcknowledgedCount(),
            getPendingCount(),
            MAX_PENDING_MESSAGES,
            backpressureActive
        );
    }
}

