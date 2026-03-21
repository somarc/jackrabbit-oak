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

import io.aeron.cluster.ClusteredMediaDriver;
import io.aeron.cluster.service.ClusteredServiceContainer;
import org.agrona.concurrent.ShutdownSignalBarrier;
import org.junit.Test;

import java.util.concurrent.ExecutorService;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

public class AeronClusterShutdownCoordinatorTest {

    @Test
    public void shutdownClosesResourcesSignalsBarrierAndStopsExecutor() {
        MediaDriverHealthMonitor healthMonitor = mock(MediaDriverHealthMonitor.class);
        ClusteredServiceContainer container = mock(ClusteredServiceContainer.class);
        ClusteredMediaDriver clusteredMediaDriver = mock(ClusteredMediaDriver.class);
        ShutdownSignalBarrier barrier = mock(ShutdownSignalBarrier.class);
        ExecutorService shutdownExecutor = mock(ExecutorService.class);
        AeronClusterShutdownCoordinator.ResourceCloser resourceCloser =
            mock(AeronClusterShutdownCoordinator.ResourceCloser.class);

        AeronClusterShutdownCoordinator.ShutdownResult result =
            new AeronClusterShutdownCoordinator(2, resourceCloser)
                .shutdown(healthMonitor, container, clusteredMediaDriver, barrier, shutdownExecutor);

        assertTrue(result.healthMonitorClosed);
        assertTrue(result.resourcesClosed);
        assertTrue(result.barrierSignaled);
        assertTrue(result.executorShutdown);
        verify(healthMonitor).close();
        verify(resourceCloser).close(container, clusteredMediaDriver);
        verify(barrier).signal();
        verify(shutdownExecutor).shutdown();
    }

    @Test
    public void shutdownContinuesWhenHealthMonitorCloseFails() {
        MediaDriverHealthMonitor healthMonitor = mock(MediaDriverHealthMonitor.class);
        doThrow(new IllegalStateException("boom")).when(healthMonitor).close();

        ShutdownSignalBarrier barrier = mock(ShutdownSignalBarrier.class);
        ExecutorService shutdownExecutor = mock(ExecutorService.class);
        AeronClusterShutdownCoordinator.ResourceCloser resourceCloser =
            mock(AeronClusterShutdownCoordinator.ResourceCloser.class);

        AeronClusterShutdownCoordinator.ShutdownResult result =
            new AeronClusterShutdownCoordinator(3, resourceCloser)
                .shutdown(healthMonitor, null, null, barrier, shutdownExecutor);

        assertFalse(result.healthMonitorClosed);
        assertTrue(result.resourcesClosed);
        assertTrue(result.barrierSignaled);
        assertTrue(result.executorShutdown);
        verify(resourceCloser).close(null, null);
        verify(barrier).signal();
        verify(shutdownExecutor).shutdown();
    }

    @Test
    public void shutdownContinuesWhenResourceCloseFails() {
        ShutdownSignalBarrier barrier = mock(ShutdownSignalBarrier.class);
        ExecutorService shutdownExecutor = mock(ExecutorService.class);
        AeronClusterShutdownCoordinator.ResourceCloser resourceCloser =
            mock(AeronClusterShutdownCoordinator.ResourceCloser.class);
        doThrow(new IllegalStateException("boom")).when(resourceCloser).close(null, null);

        AeronClusterShutdownCoordinator.ShutdownResult result =
            new AeronClusterShutdownCoordinator(4, resourceCloser)
                .shutdown(null, null, null, barrier, shutdownExecutor);

        assertFalse(result.healthMonitorClosed);
        assertFalse(result.resourcesClosed);
        assertTrue(result.barrierSignaled);
        assertTrue(result.executorShutdown);
        verify(resourceCloser).close(null, null);
        verify(barrier).signal();
        verify(shutdownExecutor).shutdown();
    }
}
