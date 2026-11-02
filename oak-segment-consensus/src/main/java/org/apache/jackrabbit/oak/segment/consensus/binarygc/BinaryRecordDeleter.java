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

import org.apache.jackrabbit.oak.blob.cloud.ipfs.IPFSDataStore;
import org.apache.jackrabbit.oak.plugins.blob.datastore.DataStoreBlobStore;
import org.apache.jackrabbit.oak.segment.consensus.service.BinaryReleaseApplicationService;
import org.apache.jackrabbit.oak.segment.consensus.service.ReplicatedBinaryIndex;
import org.apache.jackrabbit.oak.spi.blob.BlobStore;
import org.apache.jackrabbit.oak.spi.blob.data.DataIdentifier;
import org.apache.jackrabbit.oak.spi.blob.data.DataRecord;
import org.apache.jackrabbit.oak.spi.blob.data.DataStore;
import org.apache.jackrabbit.oak.spi.blob.data.MultiDataStoreAware;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Deletes this member's records of released binaries, on its own thread and after a delay, never on the Aeron
 * service thread. A record is kept when its index entry exists again (a write referenced the binary after the
 * release), when it was modified after the release's {@code maxLastModified} (an upload of the same bytes is
 * still on its way to the log), or when this member does not hold it. Failures are logged and counted; they
 * never affect the replicated state, and a release is not retried.
 */
public final class BinaryRecordDeleter implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(BinaryRecordDeleter.class);

    /** The member-local store of binary records. */
    public interface RecordStore {
        /** @return the record's last-modified time in ms, or -1 when this member does not hold it */
        long lastModified(String identifier) throws Exception;

        void delete(String identifier) throws Exception;
    }

    private final RecordStore store;
    private final Supplier<NodeState> rootSupplier;
    private final BinaryGcStatus status;
    private final long delayMs;
    private final Consumer<String> onDeleted;
    private final LongSupplier clock;
    private final ScheduledExecutorService executor;

    /**
     * @param store     the records, or null when this member's BlobStore cannot delete (deletes are then skipped)
     * @param onDeleted called with each deleted identifier, or null
     */
    public BinaryRecordDeleter(@Nullable RecordStore store, Supplier<NodeState> rootSupplier, BinaryGcStatus status,
                               long delayMs, @Nullable Consumer<String> onDeleted, LongSupplier clock) {
        this.store = store;
        this.rootSupplier = rootSupplier;
        this.status = status;
        this.delayMs = delayMs;
        this.onDeleted = onDeleted;
        this.clock = clock;
        this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "binary-gc-delete");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * The records behind {@code blobStore} when it is a DataStore that can delete, otherwise null.
     */
    @Nullable
    public static RecordStore recordsOf(@Nullable BlobStore blobStore) {
        if (!(blobStore instanceof DataStoreBlobStore)) {
            return null;
        }
        DataStore dataStore = ((DataStoreBlobStore) blobStore).getDataStore();
        if (!(dataStore instanceof MultiDataStoreAware)) {
            return null;
        }
        return new RecordStore() {
            @Override
            public long lastModified(String identifier) throws Exception {
                DataIdentifier id = new DataIdentifier(identifier);
                // Probe IPFS quietly: its caching DataStore logs an error for every absent record.
                if (dataStore instanceof IPFSDataStore && !((IPFSDataStore) dataStore).getBackend().exists(id)) {
                    return -1;
                }
                DataRecord record = dataStore.getRecordIfStored(id);
                return record == null ? -1 : record.getLastModified();
            }

            @Override
            public void delete(String identifier) throws Exception {
                ((MultiDataStoreAware) dataStore).deleteRecord(new DataIdentifier(identifier));
            }
        };
    }

    /** Called after a release is applied on this member, still on the apply thread: only schedules work. */
    public void onReleased(BinaryReleaseApplicationService.Result result) {
        status.releaseApplied(result, clock.getAsLong());
        if (store == null || result.released().isEmpty()) {
            return;
        }
        status.deletesQueued(result.released().size());
        try {
            executor.schedule(() -> deleteAll(result), delayMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            log.warn("Binary GC is stopping; {} released records are not deleted", result.released().size());
        }
    }

    void deleteAll(BinaryReleaseApplicationService.Result result) {
        NodeState root = rootSupplier.get();
        int deleted = 0;
        for (String identifier : result.released()) {
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            if (delete(root, identifier, result.maxLastModified())) {
                deleted++;
            }
        }
        log.info("Binary GC deleted {} of {} released records (marked at {})", deleted, result.released().size(),
            result.markedAtPosition());
    }

    private boolean delete(NodeState root, String identifier, long maxLastModified) {
        try {
            if (ReplicatedBinaryIndex.contains(root, identifier)) {
                status.skippedReferenced();
                return false;
            }
            long lastModified = store.lastModified(identifier);
            if (lastModified < 0) {
                status.absent();
                return false;
            }
            if (maxLastModified > 0 && lastModified > maxLastModified) {
                status.skippedRecent();
                return false;
            }
            store.delete(identifier);
            status.deleted();
            if (onDeleted != null) {
                onDeleted.accept(identifier);
            }
            return true;
        } catch (Exception e) {
            status.deleteFailed();
            log.warn("Binary GC could not delete {}: {}", identifier, e.getMessage());
            return false;
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
