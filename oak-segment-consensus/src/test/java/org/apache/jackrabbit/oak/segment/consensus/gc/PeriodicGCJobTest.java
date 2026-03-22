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

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class PeriodicGCJobTest {

    @Test
    public void testStopWhenNotRunningIsNoOp() {
        PeriodicGCJob job = new PeriodicGCJob(new GCAccountManager());

        assertFalse(job.isRunning());

        job.stop();

        assertFalse(job.isRunning());
        assertEquals(0L, job.getTotalExecutions());
        assertEquals(0L, job.getLastExecutionTime());
    }

    @Test
    public void testDuplicateStartDoesNotReplaceExecutor() throws Exception {
        PeriodicGCJob job = new PeriodicGCJob(new GCAccountManager());

        try {
            job.start();

            ScheduledExecutorService firstExecutor = readExecutor(job);
            assertNotNull(firstExecutor);
            assertTrue(job.isRunning());

            job.start();

            ScheduledExecutorService secondExecutor = readExecutor(job);
            assertSame(firstExecutor, secondExecutor);
            assertTrue(job.isRunning());
            assertEquals(0L, job.getTotalExecutions());
        } finally {
            job.stop();
        }

        assertFalse(job.isRunning());
    }

    @Test
    public void testExecuteCycleWithNoPendingDebtUpdatesCountersAndTime() throws Exception {
        GCAccountManager accountManager = new GCAccountManager();
        PeriodicGCJob job = new PeriodicGCJob(accountManager);

        assertEquals(0L, job.getTotalExecutions());
        assertEquals(0L, job.getLastExecutionTime());

        invokeExecuteCycle(job);

        assertEquals(1L, job.getTotalExecutions());
        assertTrue(job.getLastExecutionTime() > 0L);
        assertTrue(job.getStats().contains("executions=1"));
    }

    @Test
    public void testExecuteCycleConvertsPendingDebtAndBlocksWhenOverLimit() throws Exception {
        GCAccountManager accountManager = new GCAccountManager();
        String walletAddress = "0xfeedface";
        accountManager.setDebtLimit(walletAddress, new BigDecimal("0.05"));
        accountManager.addDebt(walletAddress, "/content/test", 1L);

        EntityGCAccount accountBefore = accountManager.getAccount(walletAddress);
        assertEquals(new BigDecimal("0.10"), accountBefore.getPendingDebt());
        assertFalse(accountBefore.writesBlocked);

        PeriodicGCJob job = new PeriodicGCJob(accountManager);

        invokeExecuteCycle(job);

        EntityGCAccount accountAfter = accountManager.getAccount(walletAddress);
        assertEquals(1L, job.getTotalExecutions());
        assertTrue(job.getLastExecutionTime() > 0L);
        assertTrue(accountAfter.getPendingDebt().compareTo(BigDecimal.ZERO) == 0);
        assertEquals(new BigDecimal("0.10"), accountAfter.executedDebt);
        assertTrue(accountAfter.writesBlocked);
        assertEquals(1, accountManager.getBlockedAccounts().size());
    }

    private static void invokeExecuteCycle(PeriodicGCJob job) throws Exception {
        Method method = PeriodicGCJob.class.getDeclaredMethod("executeGCCycle");
        method.setAccessible(true);
        method.invoke(job);
    }

    private static ScheduledExecutorService readExecutor(PeriodicGCJob job) throws Exception {
        Field field = PeriodicGCJob.class.getDeclaredField("executor");
        field.setAccessible(true);
        return (ScheduledExecutorService) field.get(job);
    }
}
