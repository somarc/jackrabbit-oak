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
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class RevisionGcConfigTest {

    private static final String[] PROPERTIES = {
        RevisionGcConfig.ENABLED_PROPERTY,
        RevisionGcConfig.TAIL_INTERVAL_PROPERTY,
        RevisionGcConfig.FULL_INTERVAL_PROPERTY,
        RevisionGcConfig.RETAINED_GENERATIONS_PROPERTY,
        RevisionGcConfig.FORCE_TIMEOUT_PROPERTY,
        RevisionGcConfig.SIZE_DELTA_PROPERTY,
        RevisionGcConfig.MEMORY_THRESHOLD_PROPERTY,
    };

    @After
    public void clearProperties() {
        for (String property : PROPERTIES) {
            System.clearProperty(property);
        }
    }

    @Test
    public void defaultsAreOnDailyWeeklyTwoGenerationsAndNoForcedCompaction() {
        RevisionGcConfig config = RevisionGcConfig.load();

        assertTrue(config.isEnabled());
        assertEquals(86_400_000L, config.getTailIntervalMs());
        assertEquals(604_800_000L, config.getFullIntervalMs());
        assertEquals(2, config.getRetainedGenerations());
        assertEquals(0, config.getForceTimeoutSeconds());
        assertEquals(SegmentGCOptions.SIZE_DELTA_ESTIMATION_DEFAULT, config.getSizeDeltaEstimationBytes());
        assertEquals(SegmentGCOptions.MEMORY_THRESHOLD_DEFAULT, config.getMemoryThresholdPercent());
        assertEquals(86_400_000L, config.getCompactionIntervalMs());
        assertEquals(86_400_000L, config.getReaderWindowMs());
    }

    @Test
    public void readsSystemProperties() {
        System.setProperty(RevisionGcConfig.ENABLED_PROPERTY, "false");
        System.setProperty(RevisionGcConfig.TAIL_INTERVAL_PROPERTY, "3600000");
        System.setProperty(RevisionGcConfig.FULL_INTERVAL_PROPERTY, "7200000");
        System.setProperty(RevisionGcConfig.RETAINED_GENERATIONS_PROPERTY, "3");
        System.setProperty(RevisionGcConfig.FORCE_TIMEOUT_PROPERTY, "5");
        System.setProperty(RevisionGcConfig.SIZE_DELTA_PROPERTY, "1024");
        System.setProperty(RevisionGcConfig.MEMORY_THRESHOLD_PROPERTY, "25");

        RevisionGcConfig config = RevisionGcConfig.load();

        assertFalse(config.isEnabled());
        assertEquals(3_600_000L, config.getTailIntervalMs());
        assertEquals(7_200_000L, config.getFullIntervalMs());
        assertEquals(3, config.getRetainedGenerations());
        assertEquals(5, config.getForceTimeoutSeconds());
        assertEquals(1024L, config.getSizeDeltaEstimationBytes());
        assertEquals(25, config.getMemoryThresholdPercent());
        assertEquals(2 * 3_600_000L, config.getReaderWindowMs());
    }

    @Test
    public void raisesValuesBelowTheirMinimum() {
        System.setProperty(RevisionGcConfig.TAIL_INTERVAL_PROPERTY, "10");
        System.setProperty(RevisionGcConfig.FULL_INTERVAL_PROPERTY, "-1");
        System.setProperty(RevisionGcConfig.RETAINED_GENERATIONS_PROPERTY, "1");

        RevisionGcConfig config = RevisionGcConfig.load();

        assertEquals(RevisionGcConfig.MIN_INTERVAL_MS, config.getTailIntervalMs());
        assertEquals(RevisionGcConfig.MIN_INTERVAL_MS, config.getFullIntervalMs());
        assertEquals(2, config.getRetainedGenerations());
    }

    @Test
    public void fallsBackToDefaultsForInvalidValues() {
        System.setProperty(RevisionGcConfig.TAIL_INTERVAL_PROPERTY, "daily");
        System.setProperty(RevisionGcConfig.FORCE_TIMEOUT_PROPERTY, "-3");
        System.setProperty(RevisionGcConfig.SIZE_DELTA_PROPERTY, "-1");
        System.setProperty(RevisionGcConfig.MEMORY_THRESHOLD_PROPERTY, "101");

        RevisionGcConfig config = RevisionGcConfig.load();

        assertEquals(RevisionGcConfig.DEFAULT_TAIL_INTERVAL_MS, config.getTailIntervalMs());
        assertEquals(RevisionGcConfig.DEFAULT_FORCE_TIMEOUT_SECONDS, config.getForceTimeoutSeconds());
        assertEquals(SegmentGCOptions.SIZE_DELTA_ESTIMATION_DEFAULT, config.getSizeDeltaEstimationBytes());
        assertEquals(SegmentGCOptions.MEMORY_THRESHOLD_DEFAULT, config.getMemoryThresholdPercent());
    }

    @Test
    public void appliesToOakGcOptionsWithEstimationOn() {
        SegmentGCOptions options = new RevisionGcConfig(true, 60_000, 120_000, 3, 4, 2048, 20).toGCOptions();

        assertFalse(options.isPaused());
        assertFalse(options.isEstimationDisabled());
        assertEquals(3, options.getRetainedGenerations());
        assertEquals(4, options.getForceTimeout());
        assertEquals(2048L, options.getGcSizeDeltaEstimation());
        assertEquals(20, options.getMemoryThreshold());
        assertEquals(SegmentGCOptions.RETRY_COUNT_DEFAULT, options.getRetryCount());
    }

    @Test
    public void disabledGcPausesOakCompaction() {
        assertTrue(new RevisionGcConfig(false, 60_000, 120_000, 2, 0, 0, 15).toGCOptions().isPaused());
    }

    @Test
    public void readerWindowFollowsTheShorterInterval() {
        assertEquals(3 * 50_000L, new RevisionGcConfig(true, 100_000, 50_000, 4, 0, 0, 15).getReaderWindowMs());
    }

    @Test
    public void rejectsInvalidValues() {
        assertThrows(IllegalArgumentException.class, () -> new RevisionGcConfig(true, 0, 1, 2, 0, 0, 15));
        assertThrows(IllegalArgumentException.class, () -> new RevisionGcConfig(true, 1, 0, 2, 0, 0, 15));
        assertThrows(IllegalArgumentException.class, () -> new RevisionGcConfig(true, 1, 1, 1, 0, 0, 15));
        assertThrows(IllegalArgumentException.class, () -> new RevisionGcConfig(true, 1, 1, 2, -1, 0, 15));
        assertThrows(IllegalArgumentException.class, () -> new RevisionGcConfig(true, 1, 1, 2, 0, -1, 15));
        assertThrows(IllegalArgumentException.class, () -> new RevisionGcConfig(true, 1, 1, 2, 0, 0, -1));
        assertThrows(IllegalArgumentException.class, () -> new RevisionGcConfig(true, 1, 1, 2, 0, 0, 101));
    }
}
