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
package org.apache.jackrabbit.oak.segment.consensus.gc;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

import org.apache.jackrabbit.oak.segment.consensus.gc.RevisionGcStatus.GcType;
import org.apache.jackrabbit.oak.segment.consensus.gc.RevisionGcStatus.Outcome;
import org.apache.jackrabbit.oak.segment.consensus.gc.RevisionGcStatus.Run;
import org.apache.jackrabbit.oak.segment.consensus.genesis.CanonicalGenesisContent;
import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.apache.jackrabbit.oak.segment.spi.persistence.GCGeneration;
import org.apache.jackrabbit.oak.spi.state.NodeStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs Oak online revision GC ({@link FileStore#tailGC()} and {@link FileStore#fullGC()}: estimation,
 * compaction, cleanup) on this member's store, on its own daemon thread, one run at a time, in the
 * member's wall-clock slots (see {@link RevisionGcSchedule}). GC is member-local: there is no
 * replicated decision, vote or HTTP trigger.
 */
public final class RevisionGcScheduler implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RevisionGcScheduler.class);
    static final String NOT_READY = "store not initialized or genesis not committed";
    private static final long STOP_WAIT_SECONDS = 30;

    private final RevisionGcConfig config;
    private final FileStore fileStore;
    private final RevisionGcMonitor monitor;
    private final BooleanSupplier ready;
    private final LongSupplier clock;
    private final ScheduledExecutorService executor;

    private volatile RevisionGcSchedule schedule;
    private volatile int memberIndex;
    private volatile int memberCount = 1;
    private volatile long nextSlot = -1;
    private volatile GcType running;
    private volatile Run lastTail;
    private volatile Run lastFull;
    private volatile boolean closed;
    private ScheduledFuture<?> pending;

    public RevisionGcScheduler(RevisionGcConfig config, FileStore fileStore, RevisionGcMonitor monitor,
                               BooleanSupplier ready) {
        this(config, fileStore, monitor, ready, System::currentTimeMillis,
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "oak-revision-gc");
                thread.setDaemon(true);
                return thread;
            }));
    }

    RevisionGcScheduler(RevisionGcConfig config, FileStore fileStore, RevisionGcMonitor monitor,
                        BooleanSupplier ready, LongSupplier clock, ScheduledExecutorService executor) {
        this.config = config;
        this.fileStore = fileStore;
        this.monitor = monitor;
        this.ready = ready;
        this.clock = clock;
        this.executor = executor;
        this.schedule = new RevisionGcSchedule(config.getTailIntervalMs(), config.getFullIntervalMs(), 0, 1);
    }

    /** Ready once the store holds the committed genesis; GC before that would compact a placeholder head. */
    public static BooleanSupplier genesisCommitted(NodeStore nodeStore) {
        return () -> nodeStore != null && CanonicalGenesisContent.getGenesisNode(nodeStore.getRoot()).exists();
    }

    /** Schedules the first run in this member's first slot after start-up. Does nothing when GC is disabled. */
    public synchronized void start(int memberIndex, int memberCount) {
        if (closed || nextSlot >= 0) {
            return;
        }
        this.memberCount = Math.max(1, memberCount);
        this.memberIndex = Math.floorMod(memberIndex, this.memberCount);
        this.schedule = new RevisionGcSchedule(config.getTailIntervalMs(), config.getFullIntervalMs(),
            this.memberIndex, this.memberCount);
        if (!config.isEnabled()) {
            log.info("Segment GC disabled ({}=false)", RevisionGcConfig.ENABLED_PROPERTY);
            return;
        }
        nextSlot = schedule.firstSlot(clock.getAsLong());
        log.info("Segment GC scheduled: member {} of {}, period {} ms, offset {} ms, full every {} slots, "
                + "first run at {} ({}), retainedGenerations {}, forceTimeout {} s",
            this.memberIndex, this.memberCount, schedule.periodMs, schedule.offsetMs, schedule.fullEvery,
            schedule.slotTime(nextSlot), schedule.typeOf(nextSlot), config.getRetainedGenerations(),
            config.getForceTimeoutSeconds());
        scheduleNext();
    }

    private synchronized void scheduleNext() {
        if (closed) {
            return;
        }
        long delay = Math.max(0, schedule.slotTime(nextSlot) - clock.getAsLong());
        pending = executor.schedule(this::runDueSlot, delay, TimeUnit.MILLISECONDS);
    }

    private void runDueSlot() {
        try {
            run(schedule.typeOf(nextSlot));
        } catch (RuntimeException e) {
            log.error("Segment GC slot failed", e);
        }
        // Slots that passed while this run took its time are skipped, never run back to back.
        nextSlot = schedule.slotAfter(clock.getAsLong());
        scheduleNext();
    }

    /** Runs one GC of {@code type} on the calling thread and records the outcome. */
    Run run(GcType type) {
        long startedAt = clock.getAsLong();
        if (closed) {
            return record(new Run(type, startedAt, startedAt, Outcome.CANCELLED, null, null, null, "shutting down"));
        }
        if (!isReady()) {
            return record(new Run(type, startedAt, clock.getAsLong(), Outcome.SKIPPED, null, null, null, NOT_READY));
        }
        RevisionGcMonitor.Capture capture = monitor.begin();
        running = type;
        Long sizeBefore = null;
        Outcome outcome;
        String detail;
        try {
            sizeBefore = fileStore.size();
            if (type == GcType.FULL) {
                fileStore.fullGC();
            } else {
                fileStore.tailGC();
            }
            if (capture.compacted) {
                outcome = Outcome.COMPLETED;
                detail = null;
            } else if (closed) {
                outcome = Outcome.CANCELLED;
                detail = capture.lastMessage;
            } else if (capture.skipReason != null) {
                outcome = Outcome.SKIPPED;
                detail = capture.skipReason;
            } else {
                outcome = Outcome.FAILED;
                detail = capture.error != null ? capture.error : capture.lastMessage;
            }
        } catch (Exception e) {
            outcome = closed ? Outcome.CANCELLED : Outcome.FAILED;
            detail = e.toString();
        } finally {
            monitor.end();
            running = null;
        }
        Long sizeAfter = capture.sizeAfter != null ? capture.sizeAfter : currentSize();
        Long reclaimed = capture.sizeAfter != null ? capture.reclaimedBytes : null;
        Run run = new Run(type, startedAt, clock.getAsLong(), outcome, reclaimed, sizeBefore, sizeAfter, detail);
        log.info("Segment GC {} {}: reclaimed {} bytes, size {} -> {}{}", type.key(), outcome.key(),
            reclaimed, sizeBefore, sizeAfter, detail != null ? " (" + detail + ")" : "");
        return record(run);
    }

    private boolean isReady() {
        try {
            return ready.getAsBoolean();
        } catch (RuntimeException e) {
            log.debug("Segment GC readiness check failed: {}", e.toString());
            return false;
        }
    }

    private Run record(Run run) {
        if (run.type == GcType.FULL) {
            lastFull = run;
        } else {
            lastTail = run;
        }
        return run;
    }

    private Long currentSize() {
        try {
            return fileStore.size();
        } catch (RuntimeException e) {
            return null;
        }
    }

    public RevisionGcStatus status() {
        RevisionGcSchedule current = schedule;
        long slot = nextSlot;
        Integer generation = null;
        Integer fullGeneration = null;
        Boolean compacted = null;
        try {
            GCGeneration head = fileStore.getRevisions().getHead().getSegmentId().getGcGeneration();
            generation = head.getGeneration();
            fullGeneration = head.getFullGeneration();
            compacted = head.isCompacted();
        } catch (RuntimeException e) {
            log.debug("Cannot read the head's GC generation: {}", e.toString());
        }
        boolean scheduled = slot >= 0 && !closed;
        return new RevisionGcStatus(config, memberIndex, memberCount, current.offsetMs,
            generation, fullGeneration, compacted, running, monitor.oakStatus(), lastTail, lastFull,
            scheduled ? current.nextTime(GcType.TAIL, slot) : null,
            scheduled ? current.nextTime(GcType.FULL, slot) : null);
    }

    /**
     * Stops scheduling and cancels a run in progress through Oak's own cancellation. The GC thread
     * is not interrupted: an interrupt during tar file I/O closes the store's file channels.
     */
    @Override
    public void close() {
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            if (pending != null) {
                pending.cancel(false);
            }
        }
        fileStore.cancelGC();
        executor.shutdown();
        try {
            if (!executor.awaitTermination(STOP_WAIT_SECONDS, TimeUnit.SECONDS)) {
                log.warn("Segment GC did not stop within {} s", STOP_WAIT_SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
