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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.jackrabbit.oak.api.Type;
import org.apache.jackrabbit.oak.segment.SegmentNodeStore;
import org.apache.jackrabbit.oak.segment.SegmentNodeStoreBuilders;
import org.apache.jackrabbit.oak.segment.consensus.gc.RevisionGcStatus.GcType;
import org.apache.jackrabbit.oak.segment.consensus.gc.RevisionGcStatus.Outcome;
import org.apache.jackrabbit.oak.segment.consensus.gc.RevisionGcStatus.Run;
import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.apache.jackrabbit.oak.segment.file.FileStoreBuilder;
import org.apache.jackrabbit.oak.spi.commit.CommitInfo;
import org.apache.jackrabbit.oak.spi.commit.EmptyHook;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Replicated apply merges on the Aeron service thread while this member compacts. With the
 * default force timeout of 0, compaction never takes the store's exclusive lock for a forced
 * catch-up, so merges keep landing during a full GC.
 */
public class RevisionGcWriterTest {

    /** Far below Oak's 60 s default force timeout; generous for a loaded build machine. */
    private static final long MAX_MERGE_MS = 5_000;

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void mergesContinueWhileFullGcCompacts() throws Exception {
        RevisionGcConfig config = new RevisionGcConfig(true, 3_600_000, 3_600_000, 2, 0, 0, 15);
        RevisionGcMonitor monitor = new RevisionGcMonitor();
        try (FileStore fileStore = FileStoreBuilder.fileStoreBuilder(folder.getRoot())
                .withMaxFileSize(256)
                .withMemoryMapping(false)
                .withGCOptions(config.toGCOptions())
                .withGCMonitor(monitor)
                .build()) {
            SegmentNodeStore store = SegmentNodeStoreBuilders.builder(fileStore).build();
            writeTree(store, 20_000);

            AtomicBoolean stop = new AtomicBoolean();
            AtomicLong merges = new AtomicLong();
            AtomicLong slowest = new AtomicLong();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            CountDownLatch started = new CountDownLatch(1);
            Thread writer = new Thread(() -> {
                try {
                    while (!stop.get()) {
                        long begin = System.nanoTime();
                        NodeBuilder root = store.getRoot().builder();
                        root.child("apply").setProperty("seq", merges.get());
                        store.merge(root, EmptyHook.INSTANCE, CommitInfo.EMPTY);
                        slowest.accumulateAndGet(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin), Math::max);
                        merges.incrementAndGet();
                        started.countDown();
                        Thread.sleep(2);
                    }
                } catch (Throwable t) {
                    failure.set(t);
                    started.countDown();
                }
            }, "apply-writer");
            writer.start();
            assertTrue(started.await(10, TimeUnit.SECONDS));

            RevisionGcScheduler scheduler = new RevisionGcScheduler(config, fileStore, monitor, () -> true);
            long before = merges.get();
            Run run = scheduler.run(GcType.FULL);
            long during = merges.get() - before;

            stop.set(true);
            writer.join(10_000);

            assertNull(failure.get());
            assertNotEquals(Outcome.SKIPPED, run.outcome);
            assertNotEquals(Outcome.CANCELLED, run.outcome);
            assertTrue("merges during GC: " + during, during > 0);
            assertTrue("slowest merge took " + slowest.get() + " ms", slowest.get() < MAX_MERGE_MS);
            assertEquals(merges.get() - 1,
                (long) store.getRoot().getChildNode("apply").getProperty("seq").getValue(Type.LONG));
        }
    }

    private static void writeTree(SegmentNodeStore store, int nodes) throws Exception {
        NodeBuilder root = store.getRoot().builder();
        NodeBuilder content = root.child("content");
        for (int i = 0; i < nodes; i++) {
            content.child("n" + i).setProperty("value", "value-" + i + "-" + "x".repeat(200));
        }
        store.merge(root, EmptyHook.INSTANCE, CommitInfo.EMPTY);
    }
}
