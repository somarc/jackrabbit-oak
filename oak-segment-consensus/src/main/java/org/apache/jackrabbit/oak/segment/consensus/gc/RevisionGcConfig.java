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

import org.apache.jackrabbit.oak.segment.compaction.SegmentGCOptions;
import org.apache.jackrabbit.oak.segment.consensus.config.RuntimeConfigValueResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Settings for member-local Oak revision GC on the validator {@code FileStore}.
 *
 * <p>Config precedence: OSGi override, system property, environment variable, default.
 */
public final class RevisionGcConfig {

    private static final Logger log = LoggerFactory.getLogger(RevisionGcConfig.class);

    public static final String ENABLED_PROPERTY = "oak.gc.enabled";
    public static final String TAIL_INTERVAL_PROPERTY = "oak.gc.tail.interval.ms";
    public static final String FULL_INTERVAL_PROPERTY = "oak.gc.full.interval.ms";
    public static final String RETAINED_GENERATIONS_PROPERTY = "oak.gc.retained.generations";
    public static final String FORCE_TIMEOUT_PROPERTY = "oak.gc.force.timeout.seconds";
    public static final String SIZE_DELTA_PROPERTY = "oak.gc.estimation.size.delta.bytes";
    public static final String MEMORY_THRESHOLD_PROPERTY = "oak.gc.memory.threshold.percent";

    public static final long DEFAULT_TAIL_INTERVAL_MS = 24L * 60 * 60 * 1000;
    public static final long DEFAULT_FULL_INTERVAL_MS = 7 * DEFAULT_TAIL_INTERVAL_MS;
    public static final int MIN_RETAINED_GENERATIONS = 2;
    public static final long MIN_INTERVAL_MS = 60_000L;

    /**
     * Replicated apply runs on Aeron's clustered-service thread, and every merge there waits for the
     * segment store's head lock. Oak's force phase holds that lock for up to {@code forceTimeout}
     * seconds (60 by default), which would stall consensus apply on this member for that long.
     * Oak skips the force phase entirely when the timeout is 0, so the default is 0: a compaction
     * that cannot catch up with concurrent commits after its retry cycles gives up, cleanup still
     * runs, and the next slot tries again. A positive value bounds blocking to that many seconds.
     */
    public static final int DEFAULT_FORCE_TIMEOUT_SECONDS = 0;

    private final boolean enabled;
    private final long tailIntervalMs;
    private final long fullIntervalMs;
    private final int retainedGenerations;
    private final int forceTimeoutSeconds;
    private final long sizeDeltaEstimationBytes;
    private final int memoryThresholdPercent;

    public RevisionGcConfig(boolean enabled,
                            long tailIntervalMs,
                            long fullIntervalMs,
                            int retainedGenerations,
                            int forceTimeoutSeconds,
                            long sizeDeltaEstimationBytes,
                            int memoryThresholdPercent) {
        if (tailIntervalMs <= 0 || fullIntervalMs <= 0) {
            throw new IllegalArgumentException("GC intervals must be positive");
        }
        if (retainedGenerations < MIN_RETAINED_GENERATIONS) {
            throw new IllegalArgumentException("retainedGenerations must be at least " + MIN_RETAINED_GENERATIONS);
        }
        if (forceTimeoutSeconds < 0 || sizeDeltaEstimationBytes < 0) {
            throw new IllegalArgumentException("forceTimeoutSeconds and sizeDeltaEstimationBytes must not be negative");
        }
        if (memoryThresholdPercent < 0 || memoryThresholdPercent > 100) {
            throw new IllegalArgumentException("memoryThresholdPercent must be between 0 and 100");
        }
        this.enabled = enabled;
        this.tailIntervalMs = tailIntervalMs;
        this.fullIntervalMs = fullIntervalMs;
        this.retainedGenerations = retainedGenerations;
        this.forceTimeoutSeconds = forceTimeoutSeconds;
        this.sizeDeltaEstimationBytes = sizeDeltaEstimationBytes;
        this.memoryThresholdPercent = memoryThresholdPercent;
    }

    /** Reads the settings; out-of-range values are raised to their minimum or fall back to the default. */
    public static RevisionGcConfig load() {
        boolean enabled = RuntimeConfigValueResolver.readBoolean(ENABLED_PROPERTY, "OAK_GC_ENABLED", true);
        long tail = atLeast(TAIL_INTERVAL_PROPERTY, RuntimeConfigValueResolver.readLong(
            TAIL_INTERVAL_PROPERTY, "OAK_GC_TAIL_INTERVAL_MS", DEFAULT_TAIL_INTERVAL_MS), MIN_INTERVAL_MS);
        long full = atLeast(FULL_INTERVAL_PROPERTY, RuntimeConfigValueResolver.readLong(
            FULL_INTERVAL_PROPERTY, "OAK_GC_FULL_INTERVAL_MS", DEFAULT_FULL_INTERVAL_MS), MIN_INTERVAL_MS);
        int retained = (int) atLeast(RETAINED_GENERATIONS_PROPERTY, RuntimeConfigValueResolver.readInt(
            RETAINED_GENERATIONS_PROPERTY, "OAK_GC_RETAINED_GENERATIONS",
            SegmentGCOptions.RETAINED_GENERATIONS_DEFAULT), MIN_RETAINED_GENERATIONS);
        int force = RuntimeConfigValueResolver.readInt(
            FORCE_TIMEOUT_PROPERTY, "OAK_GC_FORCE_TIMEOUT_SECONDS", DEFAULT_FORCE_TIMEOUT_SECONDS);
        long sizeDelta = RuntimeConfigValueResolver.readLong(
            SIZE_DELTA_PROPERTY, "OAK_GC_ESTIMATION_SIZE_DELTA_BYTES", SegmentGCOptions.SIZE_DELTA_ESTIMATION_DEFAULT);
        int memory = RuntimeConfigValueResolver.readInt(
            MEMORY_THRESHOLD_PROPERTY, "OAK_GC_MEMORY_THRESHOLD_PERCENT", SegmentGCOptions.MEMORY_THRESHOLD_DEFAULT);
        return new RevisionGcConfig(enabled, tail, full, retained,
            force >= 0 ? force : DEFAULT_FORCE_TIMEOUT_SECONDS,
            sizeDelta >= 0 ? sizeDelta : SegmentGCOptions.SIZE_DELTA_ESTIMATION_DEFAULT,
            memory >= 0 && memory <= 100 ? memory : SegmentGCOptions.MEMORY_THRESHOLD_DEFAULT);
    }

    private static long atLeast(String property, long value, long minimum) {
        if (value < minimum) {
            log.warn("{}={} is below the minimum; using {}", property, value, minimum);
            return minimum;
        }
        return value;
    }

    /** Oak's defaults with this config applied; estimation and the memory guard stay on. */
    public SegmentGCOptions toGCOptions() {
        return SegmentGCOptions.defaultGCOptions()
            .setPaused(!enabled)
            .setRetainedGenerations(retainedGenerations)
            .setForceTimeout(forceTimeoutSeconds)
            .setGcSizeDeltaEstimation(sizeDeltaEstimationBytes)
            .setMemoryThreshold(memoryThresholdPercent);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public long getTailIntervalMs() {
        return tailIntervalMs;
    }

    public long getFullIntervalMs() {
        return fullIntervalMs;
    }

    public int getRetainedGenerations() {
        return retainedGenerations;
    }

    public int getForceTimeoutSeconds() {
        return forceTimeoutSeconds;
    }

    public long getSizeDeltaEstimationBytes() {
        return sizeDeltaEstimationBytes;
    }

    public int getMemoryThresholdPercent() {
        return memoryThresholdPercent;
    }

    /** The shortest time between two compactions: tail and full compactions both advance the generation. */
    public long getCompactionIntervalMs() {
        return Math.min(tailIntervalMs, fullIntervalMs);
    }

    /**
     * How long a head stays readable after it was current: cleanup removes segments
     * {@code retainedGenerations} generations old, and every compaction adds a generation.
     */
    public long getReaderWindowMs() {
        return getCompactionIntervalMs() * (retainedGenerations - 1);
    }
}
