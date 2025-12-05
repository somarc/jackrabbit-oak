# Segment Persistence Acknowledgment Flow - Implementation Guide

**Feature**: QueueSegment/SegmentPersisted message flow with retry logic  
**Priority**: 🔴 Critical (Production Blocker)  
**Effort**: 2 weeks  
**Status**: Implementation Ready

---

## Overview

This feature adds **durability guarantees** to oak-segment-consensus by implementing an acknowledgment flow for segment persistence, similar to Adobe's oak-repository-service.

**What it solves**:
- ✅ Guarantees segments are persisted to storage before considering write committed
- ✅ Enables detection/recovery from storage failures
- ✅ Provides visibility into storage lag (monitoring)
- ✅ Implements retry logic with exponential backoff

---

## Architecture

### Current Flow (No Acknowledgments)

```
Validator                 Aeron Cluster         Other Validators
  │                            │                        │
  │  Write Proposal            │                        │
  ├──────────────────────────►│                        │
  │                            │  Replicate            │
  │                            ├───────────────────────►│
  │                            │                        │
  │  ✅ All nodes commit locally (deterministic)       │
  │  ❌ No confirmation segments are on disk           │
```

**Problem**: Segments might not be flushed to disk yet, or disk write could fail silently.

---

### New Flow (With Acknowledgments)

```
Validator (Leader)       Aeron Cluster         Validator (Followers)        Storage
  │                            │                        │                      │
  │  Write Proposal            │                        │                      │
  ├──────────────────────────►│                        │                      │
  │                            │  Replicate            │                      │
  │                            ├───────────────────────►│                      │
  │                            │                        │                      │
  │  ⓵ Apply to Oak            │         ⓵ Apply to Oak                      │
  │  NodeStore.merge()         │         NodeStore.merge()                    │
  │  FileStore.flush()         │         FileStore.flush()                    │
  │                            │                        │                      │
  │  ⓶ Queue segments          │                        │                      │
  │  QueueSegment msg          │                        │                      │
  ├──────────────────────────►│                        │                      │
  │  (segmentId, size)         │  QueueSegment         │                      │
  │                            ├───────────────────────►│                      │
  │                            │                        │                      │
  │                            │                        │  ⓷ Persist segments │
  │                            │                        │  to storage         │
  │                            │                        ├─────────────────────►│
  │                            │                        │                      │
  │                            │  SegmentPersisted     │                      │
  │  SegmentPersisted          │◄───────────────────────┤                      │
  │◄───────────────────────────┤  (segmentId, success) │                      │
  │                            │                        │                      │
  │  ⓸ Mark as confirmed       │                        │                      │
  │  pendingTracker.confirm()  │                        │                      │
  │                            │                        │                      │
  │  ⓹ If timeout:             │                        │                      │
  │  Retry QueueSegment        │                        │                      │
  │  (exponential backoff)     │                        │                      │
```

**Benefits**:
- Durability guarantee (segment confirmed on disk)
- Failure detection (timeout → retry or alert)
- Monitoring (track pending segments, lag time)

---

## Implementation Plan

### Phase 1: Message Definitions (Day 1)

1. **Add Aeron Message Templates**
   - `QueueSegment` (templateId 103)
   - `SegmentPersisted` (templateId 104)
   - `AckSegmentPersisted` (templateId 105)

2. **Update SBE Schema** (if using Simple Binary Encoding)

### Phase 2: PendingSegmentTracker (Day 2-3)

1. **Core Tracker Class**
   - Track pending segments (segmentId → metadata)
   - Timeout detection (configurable, default 30s)
   - Retry logic with exponential backoff
   - Thread-safe (ConcurrentHashMap)

2. **Metrics Integration**
   - Pending segment count
   - Persistence latency histogram
   - Timeout/retry counters

### Phase 3: Integration (Day 4-6)

1. **Producer Side** (after Oak commit)
   - Detect new segments created
   - Send QueueSegment to Aeron
   - Track in PendingSegmentTracker

2. **Consumer Side** (on followers + leader)
   - Receive QueueSegment
   - Persist segment to storage
   - Send SegmentPersisted acknowledgment

3. **Acknowledgment Handling**
   - Receive SegmentPersisted
   - Mark segment as confirmed in tracker
   - Remove from pending queue

### Phase 4: Retry Logic (Day 7-8)

1. **Timeout Detection**
   - Background thread checks pending segments
   - Identifies timeouts (> 30s without ack)

2. **Retry Strategy**
   - Exponential backoff (1s, 2s, 4s, 8s, 16s)
   - Max attempts: 5
   - After max attempts: log error, alert monitoring

### Phase 5: Testing & Validation (Day 9-10)

1. **Unit Tests**
   - PendingSegmentTracker behavior
   - Timeout detection
   - Retry logic

2. **Integration Tests**
   - Happy path (immediate ack)
   - Slow storage (delayed ack)
   - Failed storage (no ack, retry)
   - Network partition (timeout, recover)

---

## Code Structure

```
oak-segment-consensus/src/main/java/
└── org/apache/jackrabbit/oak/segment/consensus/
    ├── messages/
    │   ├── QueueSegmentMessage.java           # NEW
    │   ├── SegmentPersistedMessage.java       # NEW
    │   └── AckSegmentPersistedMessage.java    # NEW
    │
    ├── persistence/
    │   ├── PendingSegmentTracker.java         # NEW (core tracker)
    │   ├── PendingSegment.java                # NEW (metadata holder)
    │   ├── SegmentPersistenceListener.java    # NEW (callback interface)
    │   └── SegmentPersistenceMetrics.java     # NEW (Prometheus metrics)
    │
    ├── aeron/
    │   ├── AeronConsensusEngine.java          # MODIFIED (add message handlers)
    │   ├── MessageDispatcher.java             # MODIFIED (dispatch new messages)
    │   └── RaftAppendCallback.java            # MODIFIED (send QueueSegment)
    │
    └── server/
        └── GlobalStoreServer.java             # MODIFIED (wire up tracker)
```

---

## Implementation: PendingSegmentTracker

```java
package org.apache.jackrabbit.oak.segment.consensus.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks pending segments awaiting persistence acknowledgment.
 * 
 * <p>Similar to oak-repository-service's PendingMessageTracker, this class:
 * <ul>
 *   <li>Tracks segments sent via QueueSegment messages</li>
 *   <li>Detects timeouts (no SegmentPersisted ack received)</li>
 *   <li>Implements retry logic with exponential backoff</li>
 *   <li>Provides metrics for monitoring</li>
 * </ul>
 * 
 * <p><strong>Thread Safety:</strong> All methods are thread-safe using ConcurrentHashMap.
 * 
 * <p><strong>Configuration:</strong>
 * <pre>
 * segment.persistence.timeoutMs=30000       # 30 seconds
 * segment.persistence.maxRetries=5          # 5 attempts
 * segment.persistence.initialBackoffMs=1000 # 1 second
 * segment.persistence.backoffMultiplier=2.0 # Double each retry
 * </pre>
 * 
 * @see org.apache.jackrabbit.oak.segment.consensus.messages.QueueSegmentMessage
 * @see org.apache.jackrabbit.oak.segment.consensus.messages.SegmentPersistedMessage
 */
public class PendingSegmentTracker {
    
    private static final Logger LOG = LoggerFactory.getLogger(PendingSegmentTracker.class);
    
    // Configuration (can be externalized to properties)
    private final long timeoutMs;
    private final int maxRetries;
    private final long initialBackoffMs;
    private final double backoffMultiplier;
    
    // Pending segments (segmentId → metadata)
    private final Map<UUID, PendingSegment> pending = new ConcurrentHashMap<>();
    
    // Callback for retrying
    private final SegmentRetryCallback retryCallback;
    
    // Metrics
    private final SegmentPersistenceMetrics metrics;
    
    // Background timeout checker
    private final ScheduledExecutorService timeoutChecker;
    private volatile boolean running = false;
    
    // Counters for monitoring
    private final AtomicLong totalQueued = new AtomicLong(0);
    private final AtomicLong totalConfirmed = new AtomicLong(0);
    private final AtomicLong totalTimedOut = new AtomicLong(0);
    private final AtomicLong totalRetried = new AtomicLong(0);
    
    /**
     * Create tracker with default configuration.
     * 
     * @param retryCallback callback to invoke when retry is needed
     * @param metrics Prometheus metrics
     */
    public PendingSegmentTracker(SegmentRetryCallback retryCallback, SegmentPersistenceMetrics metrics) {
        this(30_000L, 5, 1000L, 2.0, retryCallback, metrics);
    }
    
    /**
     * Create tracker with custom configuration.
     * 
     * @param timeoutMs timeout before retry (default: 30s)
     * @param maxRetries max retry attempts (default: 5)
     * @param initialBackoffMs initial backoff delay (default: 1s)
     * @param backoffMultiplier backoff multiplier (default: 2.0)
     * @param retryCallback callback to invoke when retry is needed
     * @param metrics Prometheus metrics
     */
    public PendingSegmentTracker(
            long timeoutMs,
            int maxRetries,
            long initialBackoffMs,
            double backoffMultiplier,
            SegmentRetryCallback retryCallback,
            SegmentPersistenceMetrics metrics) {
        
        this.timeoutMs = timeoutMs;
        this.maxRetries = maxRetries;
        this.initialBackoffMs = initialBackoffMs;
        this.backoffMultiplier = backoffMultiplier;
        this.retryCallback = retryCallback;
        this.metrics = metrics;
        
        // Timeout checker runs every 5 seconds
        this.timeoutChecker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "segment-persistence-timeout-checker");
            t.setDaemon(true);
            return t;
        });
        
        LOG.info("✅ PendingSegmentTracker initialized: timeout={}ms, maxRetries={}, initialBackoff={}ms",
            timeoutMs, maxRetries, initialBackoffMs);
    }
    
    /**
     * Start the timeout checker thread.
     */
    public void start() {
        if (running) {
            LOG.warn("PendingSegmentTracker already running");
            return;
        }
        
        running = true;
        
        timeoutChecker.scheduleAtFixedRate(
            this::checkTimeouts,
            5, // initial delay
            5, // period
            TimeUnit.SECONDS
        );
        
        LOG.info("🚀 PendingSegmentTracker started (checking timeouts every 5s)");
    }
    
    /**
     * Stop the timeout checker thread.
     */
    public void stop() {
        if (!running) {
            return;
        }
        
        running = false;
        
        try {
            timeoutChecker.shutdown();
            if (!timeoutChecker.awaitTermination(10, TimeUnit.SECONDS)) {
                timeoutChecker.shutdownNow();
            }
        } catch (InterruptedException e) {
            timeoutChecker.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        LOG.info("🛑 PendingSegmentTracker stopped");
    }
    
    /**
     * Track a new segment awaiting persistence.
     * 
     * @param segmentId segment UUID
     * @param sizeBytes segment size in bytes
     */
    public void trackSegment(UUID segmentId, long sizeBytes) {
        PendingSegment segment = new PendingSegment(
            segmentId,
            sizeBytes,
            System.currentTimeMillis(),
            0 // attempt count
        );
        
        pending.put(segmentId, segment);
        totalQueued.incrementAndGet();
        
        metrics.pendingSegmentsGauge.set(pending.size());
        metrics.segmentsQueuedCounter.inc();
        
        LOG.debug("📦 Tracking segment: {} ({} bytes, pending: {})", 
            segmentId, sizeBytes, pending.size());
    }
    
    /**
     * Mark segment as confirmed (SegmentPersisted received).
     * 
     * @param segmentId segment UUID
     * @return true if segment was pending, false if not found
     */
    public boolean confirmSegment(UUID segmentId) {
        PendingSegment segment = pending.remove(segmentId);
        
        if (segment == null) {
            LOG.warn("⚠️  Received SegmentPersisted for unknown segment: {}", segmentId);
            return false;
        }
        
        long latencyMs = System.currentTimeMillis() - segment.queuedAt;
        
        totalConfirmed.incrementAndGet();
        metrics.pendingSegmentsGauge.set(pending.size());
        metrics.segmentsConfirmedCounter.inc();
        metrics.persistenceLatencyHistogram.observe(latencyMs / 1000.0); // seconds
        
        LOG.info("✅ Segment confirmed: {} (latency: {}ms, pending: {})", 
            segmentId, latencyMs, pending.size());
        
        return true;
    }
    
    /**
     * Check for timed-out segments and trigger retries.
     * 
     * Called periodically by background thread.
     */
    private void checkTimeouts() {
        if (!running) {
            return;
        }
        
        long now = System.currentTimeMillis();
        int checked = 0;
        int timedOut = 0;
        int retried = 0;
        int failed = 0;
        
        for (Map.Entry<UUID, PendingSegment> entry : pending.entrySet()) {
            UUID segmentId = entry.getKey();
            PendingSegment segment = entry.getValue();
            
            checked++;
            
            long elapsed = now - segment.queuedAt;
            
            if (elapsed > timeoutMs) {
                timedOut++;
                totalTimedOut.incrementAndGet();
                metrics.segmentsTimedOutCounter.inc();
                
                if (segment.attemptCount < maxRetries) {
                    // Retry with exponential backoff
                    long backoffMs = (long) (initialBackoffMs * Math.pow(backoffMultiplier, segment.attemptCount));
                    
                    LOG.warn("⏱️  Segment timeout: {} (elapsed: {}ms, attempt: {}/{}, backoff: {}ms)",
                        segmentId, elapsed, segment.attemptCount + 1, maxRetries, backoffMs);
                    
                    // Update attempt count
                    segment.attemptCount++;
                    segment.queuedAt = now; // Reset timer for next attempt
                    
                    // Schedule retry after backoff
                    timeoutChecker.schedule(() -> {
                        if (pending.containsKey(segmentId)) {
                            LOG.info("🔄 Retrying segment: {} (attempt: {}/{})",
                                segmentId, segment.attemptCount, maxRetries);
                            
                            retryCallback.retrySegment(segmentId, segment.sizeBytes);
                            totalRetried.incrementAndGet();
                            metrics.segmentsRetriedCounter.inc();
                        }
                    }, backoffMs, TimeUnit.MILLISECONDS);
                    
                    retried++;
                    
                } else {
                    // Max retries exceeded - give up
                    LOG.error("❌ Segment persistence FAILED after {} attempts: {} (elapsed: {}ms)",
                        maxRetries, segmentId, elapsed);
                    
                    pending.remove(segmentId);
                    metrics.pendingSegmentsGauge.set(pending.size());
                    metrics.segmentsFailedCounter.inc();
                    
                    failed++;
                }
            }
        }
        
        if (timedOut > 0) {
            LOG.info("🔍 Timeout check complete: checked={}, timedOut={}, retried={}, failed={}, pending={}",
                checked, timedOut, retried, failed, pending.size());
        }
    }
    
    /**
     * Get current pending segment count.
     */
    public int getPendingCount() {
        return pending.size();
    }
    
    /**
     * Get statistics for monitoring.
     */
    public Map<String, Long> getStats() {
        return Map.of(
            "pending", (long) pending.size(),
            "totalQueued", totalQueued.get(),
            "totalConfirmed", totalConfirmed.get(),
            "totalTimedOut", totalTimedOut.get(),
            "totalRetried", totalRetried.get()
        );
    }
    
    /**
     * Callback interface for retrying segment persistence.
     */
    @FunctionalInterface
    public interface SegmentRetryCallback {
        /**
         * Retry sending QueueSegment message for this segment.
         * 
         * @param segmentId segment UUID
         * @param sizeBytes segment size
         */
        void retrySegment(UUID segmentId, long sizeBytes);
    }
}
```

---

## Implementation: PendingSegment (Metadata)

```java
package org.apache.jackrabbit.oak.segment.consensus.persistence;

import java.util.UUID;

/**
 * Metadata for a segment awaiting persistence acknowledgment.
 */
public class PendingSegment {
    
    /** Segment UUID */
    public final UUID segmentId;
    
    /** Segment size in bytes */
    public final long sizeBytes;
    
    /** Timestamp when segment was queued (for timeout detection) */
    public long queuedAt;
    
    /** Number of retry attempts so far */
    public int attemptCount;
    
    public PendingSegment(UUID segmentId, long sizeBytes, long queuedAt, int attemptCount) {
        this.segmentId = segmentId;
        this.sizeBytes = sizeBytes;
        this.queuedAt = queuedAt;
        this.attemptCount = attemptCount;
    }
    
    @Override
    public String toString() {
        return String.format("PendingSegment{id=%s, size=%d, queued=%d, attempts=%d}",
            segmentId, sizeBytes, queuedAt, attemptCount);
    }
}
```

---

## Implementation: Prometheus Metrics

```java
package org.apache.jackrabbit.oak.segment.consensus.persistence;

import io.prometheus.client.Counter;
import io.prometheus.client.Gauge;
import io.prometheus.client.Histogram;

/**
 * Prometheus metrics for segment persistence tracking.
 */
public class SegmentPersistenceMetrics {
    
    /** Current number of pending segments awaiting acknowledgment */
    public final Gauge pendingSegmentsGauge = Gauge.build()
        .name("oak_segment_persistence_pending")
        .help("Number of segments awaiting persistence acknowledgment")
        .register();
    
    /** Total segments queued for persistence */
    public final Counter segmentsQueuedCounter = Counter.build()
        .name("oak_segment_persistence_queued_total")
        .help("Total segments queued for persistence")
        .register();
    
    /** Total segments confirmed (SegmentPersisted received) */
    public final Counter segmentsConfirmedCounter = Counter.build()
        .name("oak_segment_persistence_confirmed_total")
        .help("Total segments confirmed as persisted")
        .register();
    
    /** Total segments that timed out */
    public final Counter segmentsTimedOutCounter = Counter.build()
        .name("oak_segment_persistence_timeouts_total")
        .help("Total segments that timed out waiting for acknowledgment")
        .register();
    
    /** Total retry attempts */
    public final Counter segmentsRetriedCounter = Counter.build()
        .name("oak_segment_persistence_retries_total")
        .help("Total segment persistence retry attempts")
        .register();
    
    /** Total segments that failed after max retries */
    public final Counter segmentsFailedCounter = Counter.build()
        .name("oak_segment_persistence_failed_total")
        .help("Total segments that failed persistence after max retries")
        .register();
    
    /** Histogram of persistence latency (time from queue to confirmation) */
    public final Histogram persistenceLatencyHistogram = Histogram.build()
        .name("oak_segment_persistence_latency_seconds")
        .help("Segment persistence latency (queue to confirmation)")
        .buckets(0.01, 0.05, 0.1, 0.5, 1.0, 2.0, 5.0, 10.0, 30.0, 60.0)
        .register();
}
```

---

## Integration: Sending QueueSegment

```java
// In ConsensusApiHandler.java or AeronConsensusEngine.java

/**
 * After Oak commit completes, detect new segments and queue them.
 */
private void onSegmentsCreated(List<UUID> newSegments, long totalSize) {
    LOG.info("📦 Detected {} new segments (total: {} bytes)", newSegments.size(), totalSize);
    
    for (UUID segmentId : newSegments) {
        long segmentSize = calculateSegmentSize(segmentId); // Get actual size
        
        // Track in pending tracker
        pendingSegmentTracker.trackSegment(segmentId, segmentSize);
        
        // Send QueueSegment message to Aeron
        sendQueueSegmentMessage(segmentId, segmentSize);
    }
}

/**
 * Send QueueSegment message via Aeron.
 */
private void sendQueueSegmentMessage(UUID segmentId, long sizeBytes) {
    String json = String.format(
        "{\"segmentId\":\"%s\",\"sizeBytes\":%d,\"timestamp\":%d}",
        segmentId.toString(),
        sizeBytes,
        System.currentTimeMillis()
    );
    
    // Send via Aeron (templateId 103)
    raftAppendCallback.appendSegmentQueue(json);
    
    LOG.debug("📤 Sent QueueSegment: {} ({} bytes)", segmentId, sizeBytes);
}
```

---

## Integration: Receiving SegmentPersisted

```java
// In MessageDispatcher.java

/**
 * Handle SegmentPersisted message (templateId 104).
 */
private boolean handleSegmentPersisted(DirectBuffer buffer, int index, int length) {
    try {
        // Extract JSON payload
        int jsonStartIndex = index + 10;
        int jsonLength = length - 10;
        byte[] jsonBytes = new byte[jsonLength];
        buffer.getBytes(jsonStartIndex, jsonBytes);
        String json = new String(jsonBytes, StandardCharsets.UTF_8).trim();
        
        // Parse fields
        String segmentIdStr = extractJsonField(json, "segmentId");
        String successStr = extractJsonField(json, "success");
        
        UUID segmentId = UUID.fromString(segmentIdStr);
        boolean success = Boolean.parseBoolean(successStr);
        
        if (success) {
            LOG.debug("📥 Received SegmentPersisted: {}", segmentId);
            
            // Mark as confirmed in tracker
            pendingSegmentTracker.confirmSegment(segmentId);
            
            return true;
        } else {
            LOG.error("❌ Segment persistence failed: {}", segmentId);
            return false;
        }
        
    } catch (Exception e) {
        LOG.error("Failed to handle SegmentPersisted message", e);
        return false;
    }
}
```

---

## Integration: Persistence Callback

```java
// In StorageLayer or FileStoreWrapper

/**
 * After segment is flushed to disk, send SegmentPersisted acknowledgment.
 */
private void onSegmentFlushedToDisk(UUID segmentId) {
    LOG.debug("💾 Segment flushed to disk: {}", segmentId);
    
    // Send SegmentPersisted message back to leader
    sendSegmentPersistedMessage(segmentId, true);
}

/**
 * Send SegmentPersisted message via Aeron.
 */
private void sendSegmentPersistedMessage(UUID segmentId, boolean success) {
    String json = String.format(
        "{\"segmentId\":\"%s\",\"success\":%b,\"timestamp\":%d}",
        segmentId.toString(),
        success,
        System.currentTimeMillis()
    );
    
    // Send via Aeron (templateId 104)
    aeronClient.sendSegmentPersisted(json);
    
    LOG.debug("📤 Sent SegmentPersisted: {} (success: {})", segmentId, success);
}
```

---

## Configuration

Add to `CONFIGURATION.md`:

```properties
# Segment Persistence Tracking
segment.persistence.enabled=true
segment.persistence.timeoutMs=30000
segment.persistence.maxRetries=5
segment.persistence.initialBackoffMs=1000
segment.persistence.backoffMultiplier=2.0

# Enable metrics
segment.persistence.metrics.enabled=true
```

---

## Testing Strategy

### Unit Tests

```java
@Test
public void testSegmentTracking() {
    PendingSegmentTracker tracker = new PendingSegmentTracker(
        (segmentId, size) -> { /* retry callback */ },
        new SegmentPersistenceMetrics()
    );
    
    UUID segmentId = UUID.randomUUID();
    
    // Track segment
    tracker.trackSegment(segmentId, 1024);
    assertEquals(1, tracker.getPendingCount());
    
    // Confirm segment
    assertTrue(tracker.confirmSegment(segmentId));
    assertEquals(0, tracker.getPendingCount());
}

@Test
public void testTimeout() throws InterruptedException {
    AtomicInteger retryCount = new AtomicInteger(0);
    
    PendingSegmentTracker tracker = new PendingSegmentTracker(
        1000, // 1 second timeout
        3,    // 3 retries
        100,  // 100ms backoff
        2.0,  // 2x multiplier
        (segmentId, size) -> retryCount.incrementAndGet(),
        new SegmentPersistenceMetrics()
    );
    
    tracker.start();
    
    UUID segmentId = UUID.randomUUID();
    tracker.trackSegment(segmentId, 1024);
    
    // Wait for timeout + retries
    Thread.sleep(5000);
    
    // Should have retried 3 times
    assertEquals(3, retryCount.get());
    
    tracker.stop();
}
```

### Integration Tests

```java
@Test
public void testEndToEndFlow() {
    // 1. Start cluster
    // 2. Submit write proposal
    // 3. Verify QueueSegment sent
    // 4. Verify SegmentPersisted received
    // 5. Check tracker shows 0 pending
}
```

---

## Monitoring Dashboard

### Grafana Queries

```promql
# Pending segments over time
oak_segment_persistence_pending

# Persistence latency (p95)
histogram_quantile(0.95, 
  rate(oak_segment_persistence_latency_seconds_bucket[5m])
)

# Timeout rate
rate(oak_segment_persistence_timeouts_total[5m])

# Retry rate
rate(oak_segment_persistence_retries_total[5m])

# Success rate
rate(oak_segment_persistence_confirmed_total[5m]) / 
rate(oak_segment_persistence_queued_total[5m])
```

---

## Migration Path

### Phase 1: Add Without Breaking (Week 1)

1. Implement `PendingSegmentTracker` (disabled by default)
2. Add message handlers (no-op if disabled)
3. Deploy with feature flag OFF

### Phase 2: Enable & Monitor (Week 2)

1. Enable feature flag (`segment.persistence.enabled=true`)
2. Monitor metrics (pending, latency, timeouts)
3. Tune timeouts/retries based on production data

### Phase 3: Enforce (Week 3)

1. Make acknowledgment mandatory (reject writes if tracker full)
2. Add alerting for high timeout rates
3. Remove feature flag (always enabled)

---

## Success Criteria

✅ **Functional**:
- Segments tracked from creation to confirmation
- Timeouts detected within configured threshold
- Retries execute with exponential backoff
- Max retries honored (failure after exhaustion)

✅ **Performance**:
- < 5ms overhead per segment tracking
- < 100ms p99 acknowledgment latency (local storage)
- < 1% timeout rate in steady state

✅ **Operational**:
- Prometheus metrics exposed
- Grafana dashboard shows real-time state
- Alerts fire for > 5% timeout rate

---

## Next Steps

1. **Review this implementation guide**
2. **Create feature branch**: `feature/segment-persistence-ack-flow`
3. **Implement Phase 1** (PendingSegmentTracker + messages)
4. **Add unit tests**
5. **Integrate with AeronConsensusEngine**
6. **Add integration tests**
7. **Document in README**
8. **Create PR for review**

**Estimated Timeline**: 10 days (2 weeks with testing/review)

---

**Questions?** See:
- GAP-ANALYSIS-VS-OAK-REPOSITORY-SERVICE.md (lines 228-281)
- oak-repository-service Messages.md (QueueSegment/SegmentPersisted flow)
- oak-repository-service transactions.md (PendingMessageTracker pattern)

