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
package org.apache.jackrabbit.oak.segment.consensus.server;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.apache.jackrabbit.oak.segment.consensus.config.StorageBackendConfig;
import org.apache.jackrabbit.oak.segment.consensus.gc.RevisionGcConfig;
import org.apache.jackrabbit.oak.segment.consensus.gc.RevisionGcMonitor;
import org.apache.jackrabbit.oak.segment.consensus.gc.RevisionGcScheduler;
import org.apache.jackrabbit.oak.segment.consensus.gc.RevisionGcStatus;
import org.apache.jackrabbit.oak.segment.consensus.gc.RevisionGcStatus.Outcome;
import org.apache.jackrabbit.oak.segment.consensus.gc.RevisionGcStatus.Run;
import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.apache.jackrabbit.oak.spi.blob.MemoryBlobStore;
import org.apache.jackrabbit.oak.spi.commit.CommitInfo;
import org.apache.jackrabbit.oak.spi.commit.EmptyHook;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;
import org.apache.jackrabbit.oak.spi.state.NodeStore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The validator store as {@link StorageBackendFactory} builds it, collected by the scheduler:
 * content is written and deleted, two full GCs run in their slots, and the deleted content's
 * segments are reclaimed. The surefire configuration sets {@code oak.gc.backoff=0} so Oak does
 * not skip the second run for following the first within ten hours.
 */
public class StorageBackendFactoryGcTest {

    private static final long FULL_INTERVAL_MS = 300;

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void scheduledFullGcReclaimsDeletedContent() throws Exception {
        RevisionGcConfig config = new RevisionGcConfig(true, 3_600_000, FULL_INTERVAL_MS, 2, 0, 0, 15);
        RevisionGcMonitor monitor = new RevisionGcMonitor();
        ServerStorageRuntime runtime = StorageBackendFactory.createStorageRuntime(folder.getRoot(),
            new MemoryBlobStore(), StorageBackendConfig.load(), config, monitor);
        FileStore fileStore = runtime.fileStore();
        RevisionGcScheduler scheduler = new RevisionGcScheduler(runtime.gcConfig(), fileStore,
            runtime.gcMonitor(), () -> true);
        try {
            NodeStore store = runtime.authoritativeNodeStore();
            writeContent(store, 400);
            deleteContent(store);
            fileStore.flush();
            long sizeWithGarbage = fileStore.size();

            scheduler.start(0, 1);
            List<Run> fullRuns = awaitCompletedFullRuns(scheduler, 2);

            Run first = fullRuns.get(0);
            Run second = fullRuns.get(1);
            assertEquals(Outcome.COMPLETED, first.outcome);
            assertEquals(Outcome.COMPLETED, second.outcome);
            assertTrue("reclaimed " + second.reclaimedBytes, second.reclaimedBytes > 0);
            assertTrue(second.sizeAfter + " < " + sizeWithGarbage, second.sizeAfter < sizeWithGarbage);
            assertTrue(fileStore.size() < sizeWithGarbage);

            RevisionGcStatus status = scheduler.status();
            assertTrue(status.fullGeneration >= 2);
            assertTrue(status.nextFullAt > second.startedAt);
            assertFalse(store.getRoot().hasChildNode("content"));
            assertTrue(store.getRoot().hasChildNode("kept"));
        } finally {
            scheduler.close();
            runtime.readViewResources().close();
            fileStore.close();
        }
    }

    private static List<Run> awaitCompletedFullRuns(RevisionGcScheduler scheduler, int count) throws Exception {
        List<Run> runs = new ArrayList<>();
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            Run last = scheduler.status().lastFull;
            if (last != null && (runs.isEmpty() || runs.get(runs.size() - 1) != last)) {
                runs.add(last);
                if (last.outcome == Outcome.COMPLETED && completed(runs) == count) {
                    break;
                }
            }
            Thread.sleep(20);
        }
        List<Run> completed = new ArrayList<>();
        for (Run run : runs) {
            if (run.outcome == Outcome.COMPLETED) {
                completed.add(run);
            }
        }
        assertEquals("full runs: " + describe(runs), count, completed.size());
        return completed;
    }

    private static long completed(List<Run> runs) {
        return runs.stream().filter(run -> run.outcome == Outcome.COMPLETED).count();
    }

    private static String describe(List<Run> runs) {
        StringBuilder text = new StringBuilder();
        for (Run run : runs) {
            text.append(run.toMap()).append(' ');
        }
        return text.toString();
    }

    private static void writeContent(NodeStore store, int nodes) throws Exception {
        Random random = new Random(42);
        NodeBuilder root = store.getRoot().builder();
        root.child("kept").setProperty("value", "stays");
        NodeBuilder content = root.child("content");
        for (int i = 0; i < nodes; i++) {
            char[] text = new char[10_000];
            for (int c = 0; c < text.length; c++) {
                text[c] = (char) ('a' + random.nextInt(26));
            }
            content.child("n" + i).setProperty("value", new String(text));
        }
        store.merge(root, EmptyHook.INSTANCE, CommitInfo.EMPTY);
    }

    private static void deleteContent(NodeStore store) throws Exception {
        NodeBuilder root = store.getRoot().builder();
        root.getChildNode("content").remove();
        store.merge(root, EmptyHook.INSTANCE, CommitInfo.EMPTY);
    }
}
