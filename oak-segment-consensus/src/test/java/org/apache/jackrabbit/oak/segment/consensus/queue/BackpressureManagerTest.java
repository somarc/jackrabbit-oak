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
package org.apache.jackrabbit.oak.segment.consensus.queue;

import org.junit.Test;

import static org.junit.Assert.*;

public class BackpressureManagerTest {

    @Test
    public void testCountersAndPending() {
        BackpressureManager manager = new BackpressureManager();

        manager.incrementSent();
        manager.incrementSent();
        manager.incrementAcknowledged();

        assertEquals(2, manager.getSentCount());
        assertEquals(1, manager.getAcknowledgedCount());
        assertEquals(1, manager.getPendingCount());
    }

    @Test
    public void testResetClearsCounts() {
        BackpressureManager manager = new BackpressureManager();

        manager.incrementSent();
        manager.incrementAcknowledged();
        manager.reset();

        assertEquals(0, manager.getSentCount());
        assertEquals(0, manager.getAcknowledgedCount());
        assertEquals(0, manager.getPendingCount());
        assertFalse(manager.isBackpressureActive());
    }

    @Test
    public void testApplyBackpressureFastPath() throws Exception {
        BackpressureManager manager = new BackpressureManager();

        manager.incrementSent();
        manager.incrementAcknowledged();

        manager.applyBackpressureIfNeeded();

        assertFalse(manager.isBackpressureActive());
    }

    @Test
    public void testGetStatsIncludesCounts() {
        BackpressureManager manager = new BackpressureManager();

        manager.incrementSent();
        String stats = manager.getStats();

        assertTrue(stats.contains("sent=1"));
        assertTrue(stats.contains("acked=0"));
        assertTrue(stats.contains("pending=1"));
    }

    @Test
    public void testBulkIncrementSent() {
        BackpressureManager manager = new BackpressureManager();

        manager.incrementSent(5);
        manager.incrementAcknowledged(2);

        assertEquals(5, manager.getSentCount());
        assertEquals(2, manager.getAcknowledgedCount());
        assertEquals(3, manager.getPendingCount());
    }

    @Test
    public void testAcknowledgedIsClampedToSent() {
        BackpressureManager manager = new BackpressureManager();

        manager.incrementSent(3);
        manager.incrementAcknowledged(10);

        assertEquals(3, manager.getSentCount());
        assertEquals(3, manager.getAcknowledgedCount());
        assertEquals(0, manager.getPendingCount());
    }

    @Test
    public void testReconcileIfStalledClearsPending() {
        BackpressureManager manager = new BackpressureManager(5, 100, 1_000);

        manager.incrementSent(8);

        assertTrue(manager.reconcileIfStalled(0, "test"));
        assertEquals(8, manager.getSentCount());
        assertEquals(8, manager.getAcknowledgedCount());
        assertEquals(0, manager.getPendingCount());
        assertEquals(1, manager.getStalePendingReconciliationCount());
    }

    @Test
    public void testReconcileIfStalledHonorsThreshold() {
        BackpressureManager manager = new BackpressureManager(5, 100, 1_000);

        manager.incrementSent(6);

        assertFalse(manager.reconcileIfStalled(60_000, "test"));
        assertEquals(6, manager.getPendingCount());
        assertEquals(0, manager.getStalePendingReconciliationCount());
    }
}
