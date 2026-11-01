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

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** An immutable snapshot of this member's segment GC: settings, generation, last and next runs. */
public final class RevisionGcStatus {

    public enum GcType {
        TAIL, FULL;

        String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public enum Outcome {
        COMPLETED, SKIPPED, FAILED, CANCELLED;

        String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** One finished run. Sizes are null when the run did not reach the store. */
    public static final class Run {
        public final GcType type;
        public final long startedAt;
        public final long finishedAt;
        public final Outcome outcome;
        public final Long reclaimedBytes;
        public final Long sizeBefore;
        public final Long sizeAfter;
        public final String detail;

        public Run(GcType type, long startedAt, long finishedAt, Outcome outcome,
                   Long reclaimedBytes, Long sizeBefore, Long sizeAfter, String detail) {
            this.type = type;
            this.startedAt = startedAt;
            this.finishedAt = finishedAt;
            this.outcome = outcome;
            this.reclaimedBytes = reclaimedBytes;
            this.sizeBefore = sizeBefore;
            this.sizeAfter = sizeAfter;
            this.detail = detail;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("type", type.key());
            map.put("startedAt", startedAt);
            map.put("finishedAt", finishedAt);
            map.put("outcome", outcome.key());
            map.put("reclaimedBytes", reclaimedBytes);
            map.put("sizeBefore", sizeBefore);
            map.put("sizeAfter", sizeAfter);
            map.put(outcome == Outcome.FAILED ? "error" : "reason", detail);
            return map;
        }
    }

    public final RevisionGcConfig config;
    public final int memberIndex;
    public final int memberCount;
    public final long memberOffsetMs;
    /** The head's GC generation, or null when the store cannot be read. */
    public final Integer generation;
    public final Integer fullGeneration;
    public final Boolean compacted;
    /** The type of the run in progress, or null. */
    public final GcType running;
    /** Oak's own GC phase, as reported through the GC monitor. */
    public final String oakStatus;
    public final Run lastTail;
    public final Run lastFull;
    public final Long nextTailAt;
    public final Long nextFullAt;

    RevisionGcStatus(RevisionGcConfig config, int memberIndex, int memberCount, long memberOffsetMs,
                     Integer generation, Integer fullGeneration, Boolean compacted,
                     GcType running, String oakStatus, Run lastTail, Run lastFull,
                     Long nextTailAt, Long nextFullAt) {
        this.config = config;
        this.memberIndex = memberIndex;
        this.memberCount = memberCount;
        this.memberOffsetMs = memberOffsetMs;
        this.generation = generation;
        this.fullGeneration = fullGeneration;
        this.compacted = compacted;
        this.running = running;
        this.oakStatus = oakStatus;
        this.lastTail = lastTail;
        this.lastFull = lastFull;
        this.nextTailAt = nextTailAt;
        this.nextFullAt = nextFullAt;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("enabled", config.isEnabled());
        map.put("retainedGenerations", config.getRetainedGenerations());
        map.put("tailIntervalMs", config.getTailIntervalMs());
        map.put("fullIntervalMs", config.getFullIntervalMs());
        map.put("forceTimeoutSeconds", config.getForceTimeoutSeconds());
        map.put("estimationSizeDeltaBytes", config.getSizeDeltaEstimationBytes());
        map.put("memoryThresholdPercent", config.getMemoryThresholdPercent());
        map.put("readerWindowMs", config.getReaderWindowMs());
        map.put("memberIndex", memberIndex);
        map.put("memberCount", memberCount);
        map.put("memberOffsetMs", memberOffsetMs);

        Map<String, Object> gen = new LinkedHashMap<>();
        gen.put("full", fullGeneration);
        gen.put("generation", generation);
        gen.put("compacted", compacted);
        map.put("generation", gen);

        map.put("running", running != null ? running.key() : null);
        map.put("oakStatus", oakStatus);

        Map<String, Object> last = new LinkedHashMap<>();
        last.put("tail", lastTail != null ? lastTail.toMap() : null);
        last.put("full", lastFull != null ? lastFull.toMap() : null);
        map.put("lastRun", last);

        Map<String, Object> next = new LinkedHashMap<>();
        next.put("tail", nextTailAt);
        next.put("full", nextFullAt);
        map.put("nextRun", next);
        return map;
    }

    /** What remote readers need: how long a mounted head of this member stays readable. */
    public Map<String, Object> toFederationMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("enabled", config.isEnabled());
        map.put("retainedGenerations", config.getRetainedGenerations());
        map.put("tailIntervalMs", config.getTailIntervalMs());
        map.put("fullIntervalMs", config.getFullIntervalMs());
        map.put("readerWindowMs", config.getReaderWindowMs());
        return map;
    }
}
