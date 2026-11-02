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

import org.apache.jackrabbit.oak.segment.consensus.config.RuntimeConfigValueResolver;

/**
 * Member-local binary GC settings. They decide whether this member proposes releases (as leader) and deletes
 * released records; applying a replicated release does not depend on them.
 */
public final class BinaryGcConfig {

    static final String ENABLED = "oak.gc.binary.enabled";
    static final String INTERVAL_MS = "oak.gc.binary.interval.ms";
    static final String GRACE_MS = "oak.gc.binary.grace.ms";
    static final String DELETE_DELAY_MS = "oak.gc.binary.delete.delay.ms";
    static final long DAY_MS = 86_400_000L;
    static final long DEFAULT_DELETE_DELAY_MS = 600_000L;

    private final boolean enabled;
    private final long intervalMs;
    private final long graceMs;
    private final long deleteDelayMs;

    public BinaryGcConfig(boolean enabled, long intervalMs, long graceMs, long deleteDelayMs) {
        if (intervalMs <= 0 || graceMs < 0 || deleteDelayMs < 0) {
            throw new IllegalArgumentException("Binary GC needs a positive interval and non-negative grace and delay");
        }
        this.enabled = enabled;
        this.intervalMs = intervalMs;
        this.graceMs = graceMs;
        this.deleteDelayMs = deleteDelayMs;
    }

    /** Reads the settings; a malformed value throws {@link IllegalArgumentException}. */
    public static BinaryGcConfig fromRuntime() {
        return new BinaryGcConfig(
            readBoolean(ENABLED, "OAK_GC_BINARY_ENABLED", true),
            readLong(INTERVAL_MS, "OAK_GC_BINARY_INTERVAL_MS", DAY_MS),
            readLong(GRACE_MS, "OAK_GC_BINARY_GRACE_MS", DAY_MS),
            readLong(DELETE_DELAY_MS, "OAK_GC_BINARY_DELETE_DELAY_MS", DEFAULT_DELETE_DELAY_MS));
    }

    public boolean enabled() {
        return enabled;
    }

    public long intervalMs() {
        return intervalMs;
    }

    /** A binary is released only when its last reference is older than this. */
    public long graceMs() {
        return graceMs;
    }

    /** How long a member waits after a release before deleting the records. */
    public long deleteDelayMs() {
        return deleteDelayMs;
    }

    private static boolean readBoolean(String property, String env, boolean defaultValue) {
        String raw = RuntimeConfigValueResolver.readString(property, env, null);
        if (!RuntimeConfigValueResolver.hasText(raw)) {
            return defaultValue;
        }
        String value = raw.trim();
        if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
            throw new IllegalArgumentException(property + " must be true or false, not '" + raw + "'");
        }
        return Boolean.parseBoolean(value);
    }

    private static long readLong(String property, String env, long defaultValue) {
        String raw = RuntimeConfigValueResolver.readString(property, env, null);
        if (!RuntimeConfigValueResolver.hasText(raw)) {
            return defaultValue;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(property + " must be a number of milliseconds, not '" + raw + "'", e);
        }
    }
}
