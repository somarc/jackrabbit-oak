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

import org.apache.jackrabbit.oak.segment.consensus.gc.RevisionGcStatus.GcType;

/**
 * Wall-clock GC slots for one member. Slot {@code j} is at {@code j × period + offset} (epoch ms),
 * where the period is the shorter of the two intervals and member {@code i} of {@code n} is offset
 * by {@code i × period / n}. Every slot runs one compaction: full on every {@code fullEvery}-th
 * slot, tail otherwise. Anchoring to the wall clock keeps members staggered across restarts, and
 * putting both types on one slot grid keeps them from running together or closer than a period.
 */
final class RevisionGcSchedule {

    static final long MAX_STARTUP_GRACE_MS = 15 * 60 * 1000L;

    final long periodMs;
    final long fullEvery;
    final long offsetMs;
    final long startupGraceMs;

    RevisionGcSchedule(long tailIntervalMs, long fullIntervalMs, int memberIndex, int memberCount) {
        int count = Math.max(1, memberCount);
        int index = Math.floorMod(memberIndex, count);
        this.periodMs = Math.min(tailIntervalMs, fullIntervalMs);
        this.fullEvery = Math.max(1, Math.round((double) fullIntervalMs / periodMs));
        this.offsetMs = periodMs * index / count;
        this.startupGraceMs = Math.min(periodMs, MAX_STARTUP_GRACE_MS);
    }

    long slotTime(long slot) {
        return slot * periodMs + offsetMs;
    }

    /** The first slot strictly after {@code time}. */
    long slotAfter(long time) {
        return Math.floorDiv(time - offsetMs, periodMs) + 1;
    }

    /** The first slot after start-up: never during the start-up grace period. */
    long firstSlot(long startedAt) {
        return slotAfter(startedAt + startupGraceMs - 1);
    }

    GcType typeOf(long slot) {
        return Math.floorMod(slot, fullEvery) == 0 ? GcType.FULL : GcType.TAIL;
    }

    /** The time of the first slot at or after {@code slot} that runs {@code type}, or null if none does. */
    Long nextTime(GcType type, long slot) {
        if (type == GcType.TAIL && fullEvery == 1) {
            return null;
        }
        long candidate = slot;
        while (typeOf(candidate) != type) {
            candidate++;
        }
        return slotTime(candidate);
    }
}
