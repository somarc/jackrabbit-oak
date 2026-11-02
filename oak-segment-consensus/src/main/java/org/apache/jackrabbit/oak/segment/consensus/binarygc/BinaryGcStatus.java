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

import org.apache.jackrabbit.oak.segment.consensus.service.BinaryReleaseApplicationService;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** What this member knows about binary GC; node-local, never replicated. */
public final class BinaryGcStatus {

    private final BinaryGcConfig config;
    private final boolean unpinOnDelete;
    private volatile Map<String, Object> lastMark;
    private volatile Map<String, Object> lastRelease;
    private volatile long nextMarkAtMs;
    private final AtomicLong deletesOk = new AtomicLong();
    private final AtomicLong deletesFailed = new AtomicLong();
    private final AtomicLong deletesPending = new AtomicLong();
    private final AtomicLong skippedRecent = new AtomicLong();
    private final AtomicLong skippedReferenced = new AtomicLong();
    private final AtomicLong absent = new AtomicLong();

    public BinaryGcStatus(BinaryGcConfig config, boolean unpinOnDelete) {
        this.config = config;
        this.unpinOnDelete = unpinOnDelete;
    }

    void markCompleted(long atMs, long markedAtPosition, long references, long candidates, long proposed,
                       @Nullable String error) {
        Map<String, Object> mark = new LinkedHashMap<>();
        mark.put("at", atMs);
        mark.put("markedAtPosition", markedAtPosition);
        mark.put("references", references);
        mark.put("candidates", candidates);
        mark.put("proposed", proposed);
        mark.put("error", error);
        lastMark = mark;
    }

    void nextMarkAt(long atMs) {
        nextMarkAtMs = atMs;
    }

    void releaseApplied(BinaryReleaseApplicationService.Result result, long atMs) {
        Map<String, Object> release = new LinkedHashMap<>();
        release.put("at", atMs);
        release.put("position", result.position() != null ? result.position().position() : null);
        release.put("markedAtPosition", result.markedAtPosition());
        release.put("released", result.released().size());
        release.put("skipped", result.skipped());
        lastRelease = release;
    }

    void deletesQueued(int count) {
        deletesPending.addAndGet(count);
    }

    void deleted() {
        deletesOk.incrementAndGet();
        deletesPending.decrementAndGet();
    }

    void deleteFailed() {
        deletesFailed.incrementAndGet();
        deletesPending.decrementAndGet();
    }

    void skippedRecent() {
        skippedRecent.incrementAndGet();
        deletesPending.decrementAndGet();
    }

    void skippedReferenced() {
        skippedReferenced.incrementAndGet();
        deletesPending.decrementAndGet();
    }

    void absent() {
        absent.incrementAndGet();
        deletesPending.decrementAndGet();
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("enabled", config.enabled());
        map.put("intervalMs", config.intervalMs());
        map.put("graceMs", config.graceMs());
        map.put("deleteDelayMs", config.deleteDelayMs());
        map.put("unpinOnDelete", unpinOnDelete);
        map.put("nextMarkAt", nextMarkAtMs > 0 ? nextMarkAtMs : null);
        map.put("lastMark", lastMark);
        map.put("lastRelease", lastRelease);
        Map<String, Object> deletes = new LinkedHashMap<>();
        deletes.put("ok", deletesOk.get());
        deletes.put("failed", deletesFailed.get());
        deletes.put("pending", deletesPending.get());
        deletes.put("skippedRecent", skippedRecent.get());
        deletes.put("skippedReferenced", skippedReferenced.get());
        deletes.put("absent", absent.get());
        map.put("deletes", deletes);
        return map;
    }
}
