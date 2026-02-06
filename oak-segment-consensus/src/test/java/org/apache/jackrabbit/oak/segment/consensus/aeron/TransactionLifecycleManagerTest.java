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

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
}
