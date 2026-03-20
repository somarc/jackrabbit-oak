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
package org.apache.jackrabbit.oak.segment.consensus.service;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.jackrabbit.oak.segment.consensus.config.RuntimeConfigValueResolver;
import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Asynchronous FileStore flush coordinator.
 *
 * <p>Determinism is provided by Aeron commit ordering. This service batches
 * {@link FileStore#flush()} calls to avoid per-write fsync contention.</p>
 */
public final class FileStoreFlushService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(FileStoreFlushService.class);

    private static final long DEFAULT_FLUSH_INTERVAL_MS = 250L;
    private static final int DEFAULT_FLUSH_BATCH = 100;

    private final FileStore fileStore;
    private final long flushIntervalMs;
    private final int flushBatch;
    private final AtomicLong pendingChanges = new AtomicLong(0);
    private final AtomicBoolean flushInProgress = new AtomicBoolean(false);
    private final Object flushLock = new Object();
    private volatile boolean dirty = false;
    private final ScheduledExecutorService scheduler;

    public FileStoreFlushService(FileStore fileStore) {
        this.fileStore = fileStore;
        this.flushIntervalMs = RuntimeConfigValueResolver.readLong("oak.filestore.flush.ms", DEFAULT_FLUSH_INTERVAL_MS);
        this.flushBatch = RuntimeConfigValueResolver.readInt("oak.filestore.flush.batch", DEFAULT_FLUSH_BATCH);

        if (isAsyncEnabled()) {
            this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "oak-filestore-flush");
                t.setDaemon(true);
                return t;
            });
            this.scheduler.scheduleAtFixedRate(
                this::flushIfDirty,
                flushIntervalMs,
                flushIntervalMs,
                TimeUnit.MILLISECONDS
            );
            log.info("✅ FileStore flush: async (every {}ms or {} changes)",
                flushIntervalMs, flushBatch);
        } else {
            this.scheduler = null;
            log.info("✅ FileStore flush: synchronous (per-change)");
        }
    }

    public void onChangeApplied() {
        if (!isAsyncEnabled()) {
            flushNow();
            return;
        }
        pendingChanges.incrementAndGet();
        dirty = true;
        if (flushBatch > 0 && pendingChanges.get() >= flushBatch) {
            flushIfDirty();
        }
    }

    private boolean isAsyncEnabled() {
        return flushIntervalMs > 0 || flushBatch > 1;
    }

    private void flushIfDirty() {
        if (!dirty) {
            return;
        }
        if (!flushInProgress.compareAndSet(false, true)) {
            return;
        }
        try {
            if (!dirty) {
                return;
            }
            long pendingBefore = pendingChanges.get();
            flushNow();
            long remaining = pendingChanges.addAndGet(-pendingBefore);
            if (remaining <= 0) {
                pendingChanges.set(0);
                dirty = false;
            } else {
                dirty = true;
            }
        } finally {
            flushInProgress.set(false);
        }
    }

    private void flushNow() {
        synchronized (flushLock) {
            try {
                fileStore.flush();
            } catch (java.io.IOException e) {
                log.warn("FileStore flush failed: {}", e.getMessage());
            }
        }
    }

    @Override
    public void close() {
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                scheduler.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        flushIfDirty();
    }
}
