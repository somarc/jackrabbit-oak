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

import java.util.ArrayList;
import java.util.List;

import org.apache.jackrabbit.oak.segment.consensus.gc.RevisionGcStatus.GcType;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class RevisionGcScheduleTest {

    private static final long HOUR = 3_600_000L;
    private static final long DAY = 24 * HOUR;
    private static final long WEEK = 7 * DAY;

    @Test
    public void membersAreOffsetByAnEqualShareOfThePeriod() {
        assertEquals(0L, new RevisionGcSchedule(DAY, WEEK, 0, 3).offsetMs);
        assertEquals(8 * HOUR, new RevisionGcSchedule(DAY, WEEK, 1, 3).offsetMs);
        assertEquals(16 * HOUR, new RevisionGcSchedule(DAY, WEEK, 2, 3).offsetMs);
    }

    @Test
    public void indexOutsideTheMembershipWrapsAndAnEmptyMembershipIsOneMember() {
        assertEquals(8 * HOUR, new RevisionGcSchedule(DAY, WEEK, 4, 3).offsetMs);
        assertEquals(0L, new RevisionGcSchedule(DAY, WEEK, 2, 0).offsetMs);
    }

    @Test
    public void fullRunsTakeEveryRoundedFullIntervalOfTailSlots() {
        RevisionGcSchedule schedule = new RevisionGcSchedule(DAY, WEEK, 0, 1);
        assertEquals(DAY, schedule.periodMs);
        assertEquals(7, schedule.fullEvery);
        assertEquals(GcType.FULL, schedule.typeOf(14));
        assertEquals(GcType.TAIL, schedule.typeOf(15));
        assertEquals(GcType.FULL, schedule.typeOf(-7));

        assertEquals(8, new RevisionGcSchedule(DAY, 8 * DAY - HOUR, 0, 1).fullEvery);
    }

    @Test
    public void aFullIntervalNotLongerThanTheTailIntervalRunsOnlyFullGc() {
        RevisionGcSchedule schedule = new RevisionGcSchedule(WEEK, DAY, 1, 2);
        assertEquals(DAY, schedule.periodMs);
        assertEquals(1, schedule.fullEvery);
        assertEquals(12 * HOUR, schedule.offsetMs);
        assertEquals(GcType.FULL, schedule.typeOf(5));
        assertNull(schedule.nextTime(GcType.TAIL, 5));
        assertEquals(Long.valueOf(5 * DAY + 12 * HOUR), schedule.nextTime(GcType.FULL, 5));
    }

    @Test
    public void slotsAreAnchoredToTheWallClock() {
        RevisionGcSchedule schedule = new RevisionGcSchedule(DAY, WEEK, 1, 3);
        assertEquals(100 * DAY + 8 * HOUR, schedule.slotTime(100));
        assertEquals(100, schedule.slotAfter(100 * DAY));
        assertEquals(101, schedule.slotAfter(100 * DAY + 8 * HOUR));
        assertEquals(101, schedule.slotAfter(100 * DAY + 9 * HOUR));
    }

    @Test
    public void theFirstRunIsNeverInsideTheStartupGrace() {
        RevisionGcSchedule schedule = new RevisionGcSchedule(DAY, WEEK, 1, 3);
        long slotTime = 100 * DAY + 8 * HOUR;

        assertEquals(100, schedule.firstSlot(slotTime - RevisionGcSchedule.MAX_STARTUP_GRACE_MS));
        assertEquals(101, schedule.firstSlot(slotTime - RevisionGcSchedule.MAX_STARTUP_GRACE_MS + 1));
        assertEquals(101, schedule.firstSlot(slotTime));
    }

    @Test
    public void theStartupGraceIsCappedByThePeriod() {
        RevisionGcSchedule schedule = new RevisionGcSchedule(200, 400, 0, 1);
        assertEquals(200, schedule.startupGraceMs);
        assertEquals(6, schedule.firstSlot(1_000));
    }

    @Test
    public void nextTimeFindsTheNextSlotOfEachType() {
        RevisionGcSchedule schedule = new RevisionGcSchedule(DAY, WEEK, 2, 3);
        assertEquals(Long.valueOf(15 * DAY + 16 * HOUR), schedule.nextTime(GcType.TAIL, 14 + 1));
        assertEquals(Long.valueOf(21 * DAY + 16 * HOUR), schedule.nextTime(GcType.FULL, 14 + 1));
        assertEquals(Long.valueOf(15 * DAY + 16 * HOUR), schedule.nextTime(GcType.TAIL, 14));
        assertEquals(Long.valueOf(14 * DAY + 16 * HOUR), schedule.nextTime(GcType.FULL, 14));
    }

    /** With every member's slots laid out over two weeks, no two runs are closer than period / n. */
    @Test
    public void membersOfAClusterNeverShareASlot() {
        int members = 3;
        List<Long> times = new ArrayList<>();
        for (int member = 0; member < members; member++) {
            RevisionGcSchedule schedule = new RevisionGcSchedule(DAY, WEEK, member, members);
            for (long slot = 0; slot < 14; slot++) {
                times.add(schedule.slotTime(slot));
            }
        }
        times.sort(Long::compare);
        for (int i = 1; i < times.size(); i++) {
            assertTrue(times.get(i) - times.get(i - 1) >= DAY / members);
        }
    }
}
