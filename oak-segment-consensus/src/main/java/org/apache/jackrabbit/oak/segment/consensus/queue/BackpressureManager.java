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
    private final long maxPendingMessages;
    private final long backpressureTimeoutMs;
    private final long parkNanos;
    
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
    private final AtomicLong lastPendingChangeMs = new AtomicLong(0);
    private final AtomicLong pendingSinceMs = new AtomicLong(0);
    private final AtomicLong lastObservedPending = new AtomicLong(0);
    private final AtomicLong backpressureTimeoutCount = new AtomicLong(0);
    private final AtomicLong stalePendingReconciliationCount = new AtomicLong(0);
    private final AtomicLong lastReconciledMs = new AtomicLong(0);
    
    /**
     * Volatile flag to track if backpressure is currently active.
     * Used for logging state transitions.
     */
    private volatile boolean backpressureActive = false;
    
    /**
     * Create a new backpressure manager.
     */
    public BackpressureManager() {
        this(ProposalQueueTuningRegistry.get().getMaxPendingMessages(),
            ProposalQueueTuningRegistry.get().getBackpressureTimeoutMs(),
            ProposalQueueTuningRegistry.get().getBackpressureParkNanos());
    }

    /**
     * Create a new backpressure manager with explicit tuning.
     */
    public BackpressureManager(long maxPendingMessages, long backpressureTimeoutMs, long parkNanos) {
        this.maxPendingMessages = maxPendingMessages;
        this.backpressureTimeoutMs = backpressureTimeoutMs;
        this.parkNanos = parkNanos;
        log.info("BackpressureManager initialized:");
        log.info("  Max pending messages: {}", this.maxPendingMessages);
        log.info("  Backpressure timeout: {}ms", this.backpressureTimeoutMs);
        log.info("  Backpressure park nanos: {}", this.parkNanos);
    }
    
    /**
     * Increment sent message counter.
     * Called by ProposalQueueManager after offering message to Aeron.
     */
    public void incrementSent() {
        incrementSent(1);
    }

    /**
     * Increment sent message counter by N.
     * Non-positive deltas are ignored.
     */
    public void incrementSent(long delta) {
        if (delta <= 0) {
            return;
        }
        sentCount.addAndGet(delta);
        observePendingState(System.currentTimeMillis());
    }
    
    /**
     * Increment acknowledged message counter.
     * Called by AeronConsensusEngine after write applied via snapshot.
     */
    public void incrementAcknowledged() {
        incrementAcknowledged(1);
    }

    /**
     * Increment acknowledged message counter by N.
     * Clamps to sentCount so pending never goes negative.
     */
    public void incrementAcknowledged(long delta) {
        if (delta <= 0) {
            return;
        }
        acknowledgedCount.updateAndGet(current -> {
            long target = current + delta;
            long sent = sentCount.get();
            return Math.min(target, sent);
        });
        observePendingState(System.currentTimeMillis());
    }
    
    /**
     * Get current number of pending (unacknowledged) messages.
     * 
     * @return Sent messages minus acknowledged messages
     */
    public long getPendingCount() {
        return Math.max(0L, sentCount.get() - acknowledgedCount.get());
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

    public long getBackpressureTimeoutCount() {
        return backpressureTimeoutCount.get();
    }

    public long getStalePendingReconciliationCount() {
        return stalePendingReconciliationCount.get();
    }

    public long getPendingOldestMs(long nowMs) {
        long since = pendingSinceMs.get();
        if (since <= 0) {
            return 0;
        }
        return Math.max(0, nowMs - since);
    }

    public long getPendingStalledMs(long nowMs) {
        long pending = getPendingCount();
        if (pending <= 0) {
            return 0;
        }
        long lastChange = lastPendingChangeMs.get();
        if (lastChange <= 0) {
            return 0;
        }
        return Math.max(0, nowMs - lastChange);
    }

    public long getMaxPendingMessages() {
        return maxPendingMessages;
    }

    public long getBackpressureTimeoutMs() {
        return backpressureTimeoutMs;
    }

    public long getBackpressureParkNanos() {
        return parkNanos;
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
        long now = System.currentTimeMillis();
        observePendingState(now);
        
        // Fast path: no backpressure needed
        if (pending < maxPendingMessages) {
            if (backpressureActive) {
                backpressureActive = false;
                log.info("✅ Releasing backpressure: pending={}, max={}", 
                    pending, maxPendingMessages);
            }
            return;
        }
        
        // Slow path: apply backpressure
        if (!backpressureActive) {
            backpressureActive = true;
            log.warn("⚠️  Applying backpressure: pending={}, max={}", 
                pending, maxPendingMessages);
            log.warn("   Aeron cluster is slower than write rate - blocking new writes");
        }
        
        long startTime = System.currentTimeMillis();
        long deadline = startTime + backpressureTimeoutMs;
        long pendingAtStart = pending;
        boolean pendingChanged = false;
        
        // Wait for pending count to drop below max
        while (getPendingCount() >= maxPendingMessages) {
            long currentPending = getPendingCount();
            long currentNow = System.currentTimeMillis();
            observePendingState(currentNow);
            if (currentPending != pendingAtStart) {
                pendingChanged = true;
            }
            // Check timeout
            if (currentNow > deadline) {
                backpressureTimeoutCount.incrementAndGet();
                if (!pendingChanged && reconcilePendingAccounting(
                        "backpressure-timeout",
                        "pending did not move while sender blocked",
                        currentPending,
                        currentNow)) {
                    return;
                }
                log.error("❌ Backpressure timeout: pending={}, max={}, waited={}ms",
                    currentPending, maxPendingMessages, backpressureTimeoutMs);
                throw new BackpressureTimeoutException(
                    currentPending, 
                    maxPendingMessages, 
                    backpressureTimeoutMs
                );
            }
            
            // Park thread for 1ms (efficient waiting)
            // LockSupport.parkNanos() yields CPU instead of busy-waiting
            LockSupport.parkNanos(parkNanos);
        }
        
        // Backpressure released
        long waitTime = System.currentTimeMillis() - startTime;
        backpressureActive = false;
        observePendingState(System.currentTimeMillis());
        log.info("✅ Backpressure released after {}ms: pending={}, max={}",
            waitTime, getPendingCount(), maxPendingMessages);
    }

    /**
     * Attempt to reconcile stale pending accounting.
     *
     * <p>Used as a safety valve when pending acknowledgements are stuck and
     * no longer reflect the actual queue/cluster state.
     */
    public boolean reconcileIfStalled(long minStallMs, String reason) {
        long now = System.currentTimeMillis();
        long pending = getPendingCount();
        observePendingState(now);
        if (pending < maxPendingMessages) {
            return false;
        }
        long stalledMs = getPendingStalledMs(now);
        if (stalledMs < Math.max(0L, minStallMs)) {
            return false;
        }
        return reconcilePendingAccounting(
            reason,
            "pending exceeded max and remained unchanged",
            pending,
            now
        );
    }
    
    /**
     * Reset counters (for testing).
     */
    public void reset() {
        sentCount.set(0);
        acknowledgedCount.set(0);
        lastPendingChangeMs.set(0);
        pendingSinceMs.set(0);
        lastObservedPending.set(0);
        backpressureTimeoutCount.set(0);
        stalePendingReconciliationCount.set(0);
        lastReconciledMs.set(0);
        backpressureActive = false;
    }
    
    /**
     * Get statistics summary for logging/metrics.
     * 
     * @return Human-readable statistics
     */
    public String getStats() {
        return String.format(
            "BackpressureManager[sent=%d, acked=%d, pending=%d, max=%d, active=%s, timeouts=%d, reconciliations=%d]",
            getSentCount(),
            getAcknowledgedCount(),
            getPendingCount(),
            maxPendingMessages,
            backpressureActive,
            getBackpressureTimeoutCount(),
            getStalePendingReconciliationCount()
        );
    }

    private void observePendingState(long nowMs) {
        long pending = getPendingCount();
        long previous = lastObservedPending.getAndSet(pending);
        if (pending != previous) {
            lastPendingChangeMs.set(nowMs);
        }
        if (pending > 0) {
            pendingSinceMs.compareAndSet(0, nowMs);
        } else {
            pendingSinceMs.set(0);
        }
    }

    private boolean reconcilePendingAccounting(String reason, String detail, long pending, long nowMs) {
        long sent = sentCount.get();
        long ackedBefore = acknowledgedCount.get();
        if (ackedBefore >= sent) {
            return false;
        }
        acknowledgedCount.set(sent);
        stalePendingReconciliationCount.incrementAndGet();
        lastReconciledMs.set(nowMs);
        backpressureActive = false;
        observePendingState(nowMs);
        log.warn("⚠️  Reconciled stale backpressure accounting: reason={}, detail={}, sent={}, ackedBefore={}, pendingBefore={}, pendingAfter={}",
            reason, detail, sent, ackedBefore, pending, getPendingCount());
        return true;
    }
}
