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

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class TransactionLifecycleManagerTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void commitIsIdempotentAndReplaySafe() throws Exception {
        AtomicLong now = new AtomicLong(1_000L);
        Path dir = tempFolder.newFolder("tx-lifecycle").toPath();
        TransactionLifecycleManager manager = new TransactionLifecycleManager(dir, now::get, 1000);

        TransactionLifecycleManager.TransitionResult started =
            manager.onStart("tx-1", "corr-1", 5000L, "0xabc");
        assertTrue(started.isApplied());

        TransactionLifecycleManager.TransitionResult committed =
            manager.onCommit("tx-1", "corr-1");
        assertTrue(committed.isApplied());
        assertEquals(TransactionLifecycleManager.TxStatus.COMMITTED, committed.getRecord().status);

        TransactionLifecycleManager.TransitionResult duplicateCommit =
            manager.onCommit("tx-1", "corr-1");
        assertTrue(duplicateCommit.isIdempotent());

        TransactionLifecycleManager.TransitionResult replayStart =
            manager.onStart("tx-1", "corr-1", 5000L, "0xabc");
        assertFalse(replayStart.isApplied());
        assertFalse(replayStart.isIdempotent());
    }

    @Test
    public void abortReplayIsIdempotentAndPreventsCommit() throws Exception {
        AtomicLong now = new AtomicLong(5_000L);
        Path dir = tempFolder.newFolder("tx-lifecycle-abort").toPath();
        TransactionLifecycleManager manager = new TransactionLifecycleManager(dir, now::get, 1000);

        assertTrue(manager.onStart("tx-2", "corr-2", 5000L, "0xdef").isApplied());
        assertTrue(manager.onAbort("tx-2", "corr-2", "manual").isApplied());

        TransactionLifecycleManager.TransitionResult replayAbort =
            manager.onAbort("tx-2", "corr-2", "manual");
        assertTrue(replayAbort.isIdempotent());

        TransactionLifecycleManager.TransitionResult invalidCommit =
            manager.onCommit("tx-2", "corr-2");
        assertFalse(invalidCommit.isApplied());
        assertFalse(invalidCommit.isIdempotent());
    }

    @Test
    public void timeoutMovesTransactionToTimedOutAndRejectsCommit() throws Exception {
        AtomicLong now = new AtomicLong(10_000L);
        Path dir = tempFolder.newFolder("tx-lifecycle-timeout").toPath();
        TransactionLifecycleManager manager = new TransactionLifecycleManager(dir, now::get, 1000);

        assertTrue(manager.onStart("tx-3", "corr-3", 100L, "0xaaa").isApplied());

        now.set(10_250L);
        List<TransactionLifecycleManager.TxRecord> expired = manager.expireTimedOut();
        assertEquals(1, expired.size());
        assertEquals(TransactionLifecycleManager.TxStatus.TIMED_OUT, expired.get(0).status);

        TransactionLifecycleManager.TransitionResult invalidCommit =
            manager.onCommit("tx-3", "corr-3");
        assertFalse(invalidCommit.isApplied());
        assertFalse(invalidCommit.isIdempotent());
    }

    @Test
    public void persistsStateAcrossRestart() throws Exception {
        AtomicLong now = new AtomicLong(20_000L);
        Path dir = tempFolder.newFolder("tx-lifecycle-persist").toPath();
        TransactionLifecycleManager manager = new TransactionLifecycleManager(dir, now::get, 1000);

        assertTrue(manager.onStart("tx-4", "corr-4", 5000L, "0x123").isApplied());

        TransactionLifecycleManager reloaded = new TransactionLifecycleManager(dir, now::get, 1000);
        Optional<TransactionLifecycleManager.TxRecord> loaded = reloaded.get("tx-4");
        assertTrue(loaded.isPresent());
        assertEquals(TransactionLifecycleManager.TxStatus.STARTED, loaded.get().status);

        TransactionLifecycleManager.TransitionResult committed = reloaded.onCommit("tx-4", "corr-4");
        assertTrue(committed.isApplied());
    }

    @Test
    public void canTransitionsDefaultTimeoutAndSyntheticAbortPathsAreCovered() throws Exception {
        AtomicLong now = new AtomicLong(30_000L);
        Path dir = tempFolder.newFolder("tx-lifecycle-can").toPath();
        TransactionLifecycleManager manager = new TransactionLifecycleManager(dir, now::get, 1000);

        assertEquals("missing transactionId", manager.onStart(" ", "corr", 100L, "0xabc").getReason());
        assertEquals("missing transactionId", manager.canStart(null).getReason());
        assertTrue(manager.canStart("tx-open").isApplied());
        assertNull(manager.canStart("tx-open").getRecord());

        TransactionLifecycleManager.TransitionResult started =
            manager.onStart("tx-open", null, 0L, "0xabc");
        assertTrue(started.isApplied());
        assertEquals(30_000L, started.getRecord().timeoutMs);

        assertTrue(manager.canStart("tx-open").isIdempotent());
        assertTrue(manager.canCommit("tx-open").isApplied());
        assertEquals(TransactionLifecycleManager.TxStatus.STARTED, manager.canAbort("tx-open").getRecord().status);

        TransactionLifecycleManager.TransitionResult aborted =
            manager.onAbort("tx-open", "corr-open", "manual");
        assertTrue(aborted.isApplied());
        assertEquals(TransactionLifecycleManager.TxStatus.ABORTED, aborted.getRecord().status);
        assertEquals("corr-open", aborted.getRecord().correlationId);
        assertEquals("manual", aborted.getRecord().abortReason);

        assertTrue(manager.canAbort("tx-open").isIdempotent());
        assertEquals("cannot commit terminal transaction: ABORTED", manager.canCommit("tx-open").getReason());
        assertEquals("cannot commit terminal transaction: ABORTED", manager.onCommit("tx-open", "corr-open").getReason());

        TransactionLifecycleManager.TransitionResult syntheticAbort =
            manager.onAbort("tx-synthetic", "corr-synth", "missing");
        assertTrue(syntheticAbort.isApplied());
        assertEquals(TransactionLifecycleManager.TxStatus.ABORTED, syntheticAbort.getRecord().status);
        assertEquals("corr-synth", syntheticAbort.getRecord().correlationId);
        assertTrue(manager.canAbort("tx-synthetic").isIdempotent());

        Map<String, Object> stats = manager.stats();
        assertEquals(0, stats.get("active"));
        assertEquals(2, stats.get("terminal"));
        assertEquals(0L, stats.get("committed"));
        assertEquals(2L, stats.get("aborted"));
        assertEquals(0L, stats.get("timedOut"));
    }

    @Test
    public void timeoutStatsAndTerminalEvictionAreTracked() throws Exception {
        AtomicLong now = new AtomicLong(40_000L);
        Path dir = tempFolder.newFolder("tx-lifecycle-eviction").toPath();
        TransactionLifecycleManager manager = new TransactionLifecycleManager(dir, now::get, 1);

        assertTrue(manager.onStart("tx-timeout", "corr-timeout", 50L, "0xaaa").isApplied());
        now.set(40_100L);
        List<TransactionLifecycleManager.TxRecord> expired = manager.expireTimedOut();
        assertEquals(1, expired.size());
        assertEquals(TransactionLifecycleManager.TxStatus.TIMED_OUT, expired.get(0).status);
        assertTrue(manager.canAbort("tx-timeout").isIdempotent());
        assertTrue(manager.onAbort("tx-timeout", "corr-timeout", "ignored").isIdempotent());

        for (int i = 0; i <= 100; i++) {
            assertTrue(manager.onAbort("tx-" + i, "corr-" + i, "manual").isApplied());
        }

        Map<String, Object> stats = manager.stats();
        assertEquals(0, stats.get("active"));
        assertEquals(100, stats.get("terminal"));
        assertEquals(100L, stats.get("aborted"));
        assertEquals(0L, stats.get("timedOut"));
        assertFalse(manager.get("tx-timeout").isPresent());
        assertFalse(manager.get("tx-0").isPresent());
        assertTrue(manager.get("tx-100").isPresent());
    }

    @Test
    public void corruptStateAndPersistFailuresDoNotBreakTransitions() throws Exception {
        AtomicLong now = new AtomicLong(50_000L);
        Path corruptDir = tempFolder.newFolder("tx-lifecycle-corrupt").toPath();
        Files.write(corruptDir.resolve("transaction-lifecycle.bin"), new byte[] {1, 2, 3, 4});

        TransactionLifecycleManager reloadedFromCorrupt =
            new TransactionLifecycleManager(corruptDir, now::get, 1000);
        assertFalse(reloadedFromCorrupt.get("tx-missing").isPresent());
        assertTrue(reloadedFromCorrupt.canStart("tx-missing").isApplied());

        Path persistFailureDir = tempFolder.newFolder("tx-lifecycle-persist-failure").toPath();
        Files.createDirectory(persistFailureDir.resolve("transaction-lifecycle.bin.tmp"));
        TransactionLifecycleManager persistFailureManager =
            new TransactionLifecycleManager(persistFailureDir, now::get, 1000);

        TransactionLifecycleManager.TransitionResult started =
            persistFailureManager.onStart("tx-persist-failure", null, 5L, "0xdef");
        assertTrue(started.isApplied());
        assertTrue(persistFailureManager.get("tx-persist-failure").isPresent());

        TransactionLifecycleManager afterRestart =
            new TransactionLifecycleManager(persistFailureDir, now::get, 1000);
        assertFalse(afterRestart.get("tx-persist-failure").isPresent());
        assertEquals("unknown transaction", afterRestart.onCommit("tx-persist-failure", "corr").getReason());
    }
}
