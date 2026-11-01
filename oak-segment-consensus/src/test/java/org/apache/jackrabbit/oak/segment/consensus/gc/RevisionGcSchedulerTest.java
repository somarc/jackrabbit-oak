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

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.jackrabbit.oak.plugins.memory.EmptyNodeState;
import org.apache.jackrabbit.oak.segment.consensus.gc.RevisionGcStatus.GcType;
import org.apache.jackrabbit.oak.segment.consensus.gc.RevisionGcStatus.Outcome;
import org.apache.jackrabbit.oak.segment.consensus.gc.RevisionGcStatus.Run;
import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.apache.jackrabbit.oak.segment.spi.persistence.GCGeneration;
import org.apache.jackrabbit.oak.spi.state.NodeStore;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class RevisionGcSchedulerTest {

    private static final long DAY = 86_400_000L;
    private static final long WEEK = 7 * DAY;
    private static final RevisionGcConfig CONFIG = new RevisionGcConfig(true, DAY, WEEK, 2, 0, 0, 15);

    private final AtomicLong clock = new AtomicLong(100 * DAY + 1);
    private final AtomicBoolean ready = new AtomicBoolean(true);
    private final RevisionGcMonitor monitor = new RevisionGcMonitor();
    private FileStore fileStore;
    private ScheduledExecutorService executor;

    @Before
    public void setUp() {
        fileStore = mock(FileStore.class, RETURNS_DEEP_STUBS);
        when(fileStore.size()).thenReturn(1000L);
        executor = mock(ScheduledExecutorService.class);
        doReturn(mock(ScheduledFuture.class)).when(executor).schedule(any(Runnable.class), anyLong(), any());
    }

    private RevisionGcScheduler scheduler(RevisionGcConfig config) {
        return new RevisionGcScheduler(config, fileStore, monitor, ready::get, clock::get, executor);
    }

    @Test
    public void startSchedulesTheMembersFirstSlotAfterTheGrace() {
        RevisionGcScheduler scheduler = scheduler(CONFIG);

        scheduler.start(1, 3);

        // member 1 of 3: slots at day + 8 h; 100 days + 1 ms is past slot 99 (99 d 8 h), so slot 100.
        verify(executor).schedule(any(Runnable.class), eq(8 * 3_600_000L - 1), eq(TimeUnit.MILLISECONDS));
        RevisionGcStatus status = scheduler.status();
        assertEquals(1, status.memberIndex);
        assertEquals(3, status.memberCount);
        assertEquals(8 * 3_600_000L, status.memberOffsetMs);
        assertEquals(Long.valueOf(100 * DAY + 8 * 3_600_000L), status.nextTailAt);
        assertEquals(Long.valueOf(105 * DAY + 8 * 3_600_000L), status.nextFullAt);
    }

    @Test
    public void startIsIdempotentAndDisabledGcSchedulesNothing() {
        RevisionGcScheduler scheduler = scheduler(CONFIG);
        scheduler.start(0, 1);
        scheduler.start(0, 1);
        verify(executor, times(1)).schedule(any(Runnable.class), anyLong(), any());

        ScheduledExecutorService idle = mock(ScheduledExecutorService.class);
        RevisionGcScheduler disabled = new RevisionGcScheduler(
            new RevisionGcConfig(false, DAY, WEEK, 2, 0, 0, 15), fileStore, monitor, ready::get, clock::get, idle);
        disabled.start(0, 1);
        verify(idle, never()).schedule(any(Runnable.class), anyLong(), any());
        RevisionGcStatus status = disabled.status();
        assertFalse(status.config.isEnabled());
        assertNull(status.nextTailAt);
        assertNull(status.nextFullAt);
    }

    @Test
    public void aDueSlotRunsItsTypeAndSchedulesTheNextSlot() throws Exception {
        RevisionGcScheduler scheduler = scheduler(CONFIG);
        scheduler.start(0, 1);
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).schedule(task.capture(), anyLong(), any());

        clock.set(105 * DAY);
        task.getValue().run();

        // slot 101 (day 101) is a tail slot; slots that passed are skipped, so the next is day 106.
        verify(fileStore).tailGC();
        verify(fileStore, never()).fullGC();
        verify(executor).schedule(any(Runnable.class), eq(DAY), eq(TimeUnit.MILLISECONDS));
        assertEquals(Long.valueOf(105 * DAY), scheduler.status().lastTail != null
            ? scheduler.status().lastTail.startedAt : null);
    }

    @Test
    public void aCompactionThatOakReportsIsCompleted() throws Exception {
        doAnswer(invocation -> {
            monitor.compacted();
            monitor.cleaned(400, 600);
            return null;
        }).when(fileStore).fullGC();

        Run run = scheduler(CONFIG).run(GcType.FULL);

        assertEquals(Outcome.COMPLETED, run.outcome);
        assertEquals(Long.valueOf(400), run.reclaimedBytes);
        assertEquals(Long.valueOf(1000), run.sizeBefore);
        assertEquals(Long.valueOf(600), run.sizeAfter);
        assertNull(run.detail);
    }

    @Test
    public void anOakSkipIsRecordedWithItsReason() throws Exception {
        doAnswer(invocation -> {
            monitor.skipped("estimation completed in {}. {}", "1 s", "no gain");
            return null;
        }).when(fileStore).tailGC();

        Run run = scheduler(CONFIG).run(GcType.TAIL);

        assertEquals(Outcome.SKIPPED, run.outcome);
        assertEquals("estimation completed in 1 s. no gain", run.detail);
        assertNull(run.reclaimedBytes);
        assertEquals(Long.valueOf(1000), run.sizeAfter);
    }

    @Test
    public void aCompactionThatGaveUpIsFailedWithOaksLastMessage() throws Exception {
        doAnswer(invocation -> {
            monitor.info("compaction gave up compacting concurrent commits after {} cycles.", 5);
            monitor.cleaned(0, 1000);
            return null;
        }).when(fileStore).fullGC();

        Run run = scheduler(CONFIG).run(GcType.FULL);

        assertEquals(Outcome.FAILED, run.outcome);
        assertEquals("compaction gave up compacting concurrent commits after 5 cycles.", run.detail);
        assertEquals(Long.valueOf(0), run.reclaimedBytes);
        assertEquals("failed", run.toMap().get("outcome"));
        assertTrue(run.toMap().containsKey("error"));
    }

    @Test
    public void anOakErrorOrExceptionIsFailed() throws Exception {
        doAnswer(invocation -> {
            monitor.error("compaction encountered an error", new IOException("disk"));
            return null;
        }).when(fileStore).fullGC();
        Run reported = scheduler(CONFIG).run(GcType.FULL);
        assertEquals(Outcome.FAILED, reported.outcome);
        assertEquals("compaction encountered an error: java.io.IOException: disk", reported.detail);

        doThrow(new IOException("broken")).when(fileStore).tailGC();
        Run thrown = scheduler(CONFIG).run(GcType.TAIL);
        assertEquals(Outcome.FAILED, thrown.outcome);
        assertEquals("java.io.IOException: broken", thrown.detail);
    }

    @Test
    public void runsAreSkippedUntilTheStoreIsReady() throws Exception {
        ready.set(false);
        RevisionGcScheduler scheduler = scheduler(CONFIG);

        Run run = scheduler.run(GcType.FULL);

        assertEquals(Outcome.SKIPPED, run.outcome);
        assertEquals(RevisionGcScheduler.NOT_READY, run.detail);
        verify(fileStore, never()).fullGC();
        assertEquals(run, scheduler.status().lastFull);

        RevisionGcScheduler failing = new RevisionGcScheduler(CONFIG, fileStore, monitor,
            () -> {
                throw new IllegalStateException("no root");
            }, clock::get, executor);
        assertEquals(Outcome.SKIPPED, failing.run(GcType.TAIL).outcome);
    }

    @Test
    public void closeCancelsOaksGcAndARunDuringShutdownIsCancelled() throws Exception {
        RevisionGcScheduler scheduler = scheduler(CONFIG);
        doAnswer(invocation -> {
            scheduler.close();
            return null;
        }).when(fileStore).fullGC();

        Run run = scheduler.run(GcType.FULL);

        assertEquals(Outcome.CANCELLED, run.outcome);
        verify(fileStore).cancelGC();
        verify(executor).shutdown();
        verify(executor, never()).shutdownNow();

        Run after = scheduler.run(GcType.TAIL);
        assertEquals(Outcome.CANCELLED, after.outcome);
        verify(fileStore, never()).tailGC();
        scheduler.start(0, 1);
        verify(executor, never()).schedule(any(Runnable.class), anyLong(), any());
        assertNull(scheduler.status().nextFullAt);
    }

    @Test
    public void statusReportsTheHeadGenerationAndRunningPhase() {
        GCGeneration generation = GCGeneration.newGCGeneration(5, 2, true);
        when(fileStore.getRevisions().getHead().getSegmentId().getGcGeneration()).thenReturn(generation);
        monitor.updateStatus("compaction");

        Map<String, Object> map = scheduler(CONFIG).status().toMap();

        @SuppressWarnings("unchecked")
        Map<String, Object> gen = (Map<String, Object>) map.get("generation");
        assertEquals(2, gen.get("full"));
        assertEquals(5, gen.get("generation"));
        assertEquals(true, gen.get("compacted"));
        assertEquals("compaction", map.get("oakStatus"));
        assertEquals(DAY, map.get("readerWindowMs"));
        assertEquals(true, map.get("enabled"));
        assertNull(map.get("running"));

        Map<String, Object> federation = scheduler(CONFIG).status().toFederationMap();
        assertEquals(2, federation.get("retainedGenerations"));
        assertEquals(WEEK, federation.get("fullIntervalMs"));
        assertEquals(DAY, federation.get("readerWindowMs"));
    }

    @Test
    public void statusToleratesAnUnreadableStore() {
        when(fileStore.getRevisions()).thenThrow(new IllegalStateException("closed"));
        RevisionGcStatus status = scheduler(CONFIG).status();
        assertNull(status.generation);
        assertNull(status.fullGeneration);
    }

    @Test
    public void genesisCommittedNeedsAStoreWithGenesis() {
        NodeStore empty = mock(NodeStore.class);
        when(empty.getRoot()).thenReturn(EmptyNodeState.EMPTY_NODE);
        assertFalse(RevisionGcScheduler.genesisCommitted(null).getAsBoolean());
        assertFalse(RevisionGcScheduler.genesisCommitted(empty).getAsBoolean());
    }
}
