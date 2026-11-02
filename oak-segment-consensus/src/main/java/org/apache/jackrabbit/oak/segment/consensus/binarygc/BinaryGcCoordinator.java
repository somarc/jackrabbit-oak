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
package org.apache.jackrabbit.oak.segment.consensus.binarygc;

import org.apache.jackrabbit.oak.segment.consensus.service.AppliedLogPosition;
import org.apache.jackrabbit.oak.segment.consensus.service.BinaryReleaseApplicationService;
import org.apache.jackrabbit.oak.segment.consensus.service.ReplicatedBinaryIndex;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Binary GC mark on the leader. When a mark is due and this member may propose cluster commands, it reads the
 * applied watermark and the binary index from one root, collects the blob references in its segment store, and
 * proposes BINARY_RELEASE batches for index entries that nothing references, last referenced before the
 * watermark entry and longer ago than the grace period. Proposals are offers: replicated apply skips any entry
 * referenced since the mark. No payment, vote or HTTP trigger is involved.
 */
public final class BinaryGcCoordinator implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(BinaryGcCoordinator.class);
    static final long MAX_POLL_MS = 60_000L;
    static final long MAX_FIRST_MARK_DELAY_MS = 600_000L;

    public interface Proposer {
        boolean canPropose();

        boolean propose(long markedAtPosition, long maxLastModified, List<String> blobIds);
    }

    /** The blob ids referenced by the segment store, including every generation it still retains. */
    public interface ReferenceSource {
        void collect(Consumer<String> collector) throws IOException;
    }

    private final BinaryGcConfig config;
    private final Supplier<NodeState> rootSupplier;
    private final ReferenceSource references;
    private final Proposer proposer;
    private final LongSupplier clock;
    private final BinaryGcStatus status;
    private ScheduledExecutorService scheduler;
    private long nextMarkAtMs;

    public BinaryGcCoordinator(BinaryGcConfig config, Supplier<NodeState> rootSupplier, ReferenceSource references,
                               Proposer proposer, LongSupplier clock, BinaryGcStatus status) {
        this.config = config;
        this.rootSupplier = rootSupplier;
        this.references = references;
        this.proposer = proposer;
        this.clock = clock;
        this.status = status;
        scheduleNext(clock.getAsLong(), Math.min(config.intervalMs(), MAX_FIRST_MARK_DELAY_MS));
    }

    public synchronized void start() {
        if (scheduler != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "binary-gc-mark");
            thread.setDaemon(true);
            return thread;
        });
        long pollMs = Math.min(config.intervalMs(), MAX_POLL_MS);
        scheduler.scheduleWithFixedDelay(this::tickQuietly, pollMs, pollMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void close() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    private void tickQuietly() {
        try {
            tick();
        } catch (RuntimeException e) {
            log.warn("Binary GC tick failed: {}", e.getMessage(), e);
        }
    }

    /** Marks when a mark is due and this member may propose; a follower waits until it leads. */
    synchronized void tick() {
        long now = clock.getAsLong();
        if (now < nextMarkAtMs || !proposer.canPropose()) {
            return;
        }
        mark();
        scheduleNext(clock.getAsLong(), config.intervalMs());
    }

    /** Runs one mark and proposes its releases. */
    synchronized void mark() {
        long now = clock.getAsLong();
        NodeState root = rootSupplier.get();
        AppliedLogPosition watermark = AppliedLogPosition.read(root);
        if (watermark.isNone()) {
            status.markCompleted(now, 0, 0, 0, 0, "nothing applied yet");
            return;
        }
        long markedAtPosition = watermark.position();
        Set<String> referenced = new HashSet<>();
        long[] referenceCount = new long[1];
        try {
            references.collect(blobId -> {
                referenceCount[0]++;
                String identifier = ReplicatedBinaryIndex.identifier(blobId);
                if (identifier != null) {
                    referenced.add(identifier);
                }
            });
        } catch (IOException | RuntimeException e) {
            log.warn("Binary GC mark could not collect blob references: {}", e.getMessage(), e);
            status.markCompleted(now, markedAtPosition, referenceCount[0], 0, 0, "reference collection failed: "
                + e.getMessage());
            return;
        }
        long cutoff = now - config.graceMs();
        List<String> candidates = new ArrayList<>();
        ReplicatedBinaryIndex.forEach(root, entry -> {
            if (entry.refPosition() < markedAtPosition && entry.refTime() <= cutoff
                && !referenced.contains(entry.identifier())) {
                candidates.add(entry.identifier());
            }
        });
        Collections.sort(candidates);
        long proposed = 0;
        String error = null;
        for (int from = 0; from < candidates.size(); from += BinaryReleaseApplicationService.MAX_BLOB_IDS) {
            List<String> batch = candidates.subList(from,
                Math.min(candidates.size(), from + BinaryReleaseApplicationService.MAX_BLOB_IDS));
            if (!proposer.canPropose() || !proposer.propose(markedAtPosition, cutoff, new ArrayList<>(batch))) {
                error = "proposal not sent; the next mark retries";
                break;
            }
            proposed += batch.size();
        }
        status.markCompleted(now, markedAtPosition, referenceCount[0], candidates.size(), proposed, error);
        log.info("Binary GC mark at position {}: {} references, {} candidates, {} proposed{}", markedAtPosition,
            referenceCount[0], candidates.size(), proposed, error != null ? " (" + error + ")" : "");
    }

    private void scheduleNext(long now, long delayMs) {
        nextMarkAtMs = now + delayMs;
        status.nextMarkAt(nextMarkAtMs);
    }
}
