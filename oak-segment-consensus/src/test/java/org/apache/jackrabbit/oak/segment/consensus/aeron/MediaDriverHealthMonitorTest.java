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
package org.apache.jackrabbit.oak.segment.consensus.aeron;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicLong;

import io.aeron.Aeron;
import org.agrona.concurrent.status.CountersReader;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class MediaDriverHealthMonitorTest {

    @Test
    public void constructorRejectsNullAeron() {
        try {
            new MediaDriverHealthMonitor(null);
            fail("expected constructor to reject null aeron");
        } catch (IllegalArgumentException e) {
            assertEquals("Aeron instance cannot be null", e.getMessage());
        }
    }

    @Test
    public void checkHealthTracksFailuresAndCanRecover() throws Exception {
        CountersReader countersReader = mock(CountersReader.class);
        AtomicLong errors = new AtomicLong(0);
        AtomicLong timeouts = new AtomicLong(0);
        when(countersReader.getCounterValue(org.mockito.ArgumentMatchers.anyInt())).thenAnswer(invocation -> {
            int id = invocation.getArgument(0);
            if (id == io.aeron.driver.status.SystemCounterDescriptor.ERRORS.id()) {
                return errors.get();
            }
            if (id == io.aeron.driver.status.SystemCounterDescriptor.CLIENT_TIMEOUTS.id()) {
                return timeouts.get();
            }
            return 0L;
        });
        MediaDriverHealthMonitor monitor = monitor(countersReader);
        try {
            errors.set(3);
            timeouts.set(1);
            invokeCheckHealth(monitor);

            assertFalse(monitor.isHealthy());
            assertTrue(monitor.getHealthStatus().contains("HIGH_ERROR_RATE"));
            assertTrue(monitor.getHealthStatus().contains("TIMEOUTS"));
            assertEquals(3L, monitor.getErrorCount());
            assertEquals(0L, monitor.getBackpressureCount());
            assertEquals(1L, monitor.getTimeoutCount());
            assertTrue(monitor.getFreeSpace() > 0);

            errors.set(0);
            timeouts.set(0);
            invokeCheckHealth(monitor);

            assertTrue(monitor.isHealthy());
            assertEquals("OK", monitor.getHealthStatus());
        } finally {
            monitor.close();
        }
    }

    @Test
    public void getSystemCounterReturnsZeroWhenCounterReadFails() throws Exception {
        CountersReader countersReader = mock(CountersReader.class);
        when(countersReader.getCounterValue(org.mockito.ArgumentMatchers.anyInt())).thenThrow(new RuntimeException("boom"));
        MediaDriverHealthMonitor monitor = monitor(countersReader);
        try {
            assertEquals(0L, invokeGetSystemCounter(monitor, 7));
        } finally {
            monitor.close();
        }
    }

    @Test
    public void closeIsIdempotentAndPreservesInterruptStatus() throws Exception {
        CountersReader countersReader = mock(CountersReader.class);
        when(countersReader.getCounterValue(org.mockito.ArgumentMatchers.anyInt())).thenReturn(0L);
        MediaDriverHealthMonitor monitor = monitor(countersReader);

        Thread.currentThread().interrupt();
        monitor.close();
        assertTrue(Thread.interrupted());

        monitor.close();
    }

    @Test
    public void closedMonitorSkipsFurtherHealthChecks() throws Exception {
        CountersReader countersReader = mock(CountersReader.class);
        AtomicLong errors = new AtomicLong(0);
        AtomicLong timeouts = new AtomicLong(0);
        when(countersReader.getCounterValue(org.mockito.ArgumentMatchers.anyInt())).thenAnswer(invocation -> {
            int id = invocation.getArgument(0);
            if (id == io.aeron.driver.status.SystemCounterDescriptor.ERRORS.id()) {
                return errors.get();
            }
            if (id == io.aeron.driver.status.SystemCounterDescriptor.CLIENT_TIMEOUTS.id()) {
                return timeouts.get();
            }
            return 0L;
        });
        MediaDriverHealthMonitor monitor = monitor(countersReader);
        try {
            monitor.close();
            errors.set(3);
            timeouts.set(1);
            invokeCheckHealth(monitor);

            assertTrue(monitor.isHealthy());
            assertEquals("OK", monitor.getHealthStatus());
        } finally {
            monitor.close();
        }
    }

    private static MediaDriverHealthMonitor monitor(CountersReader countersReader) {
        Aeron aeron = mock(Aeron.class);
        when(aeron.countersReader()).thenReturn(countersReader);
        MediaDriverHealthMonitor monitor = new MediaDriverHealthMonitor(aeron);
        shutdownBackgroundChecks(monitor);
        return monitor;
    }

    private static void invokeCheckHealth(MediaDriverHealthMonitor monitor) throws Exception {
        Method method = MediaDriverHealthMonitor.class.getDeclaredMethod("checkHealth");
        method.setAccessible(true);
        method.invoke(monitor);
    }

    private static long invokeGetSystemCounter(MediaDriverHealthMonitor monitor, int counterId) throws Exception {
        Method method = MediaDriverHealthMonitor.class.getDeclaredMethod("getSystemCounter", int.class);
        method.setAccessible(true);
        return (Long) method.invoke(monitor, counterId);
    }

    private static void shutdownBackgroundChecks(MediaDriverHealthMonitor monitor) {
        try {
            Field field = MediaDriverHealthMonitor.class.getDeclaredField("executor");
            field.setAccessible(true);
            ((ScheduledExecutorService) field.get(monitor)).shutdownNow();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to disable MediaDriverHealthMonitor scheduler", e);
        }
    }
}
