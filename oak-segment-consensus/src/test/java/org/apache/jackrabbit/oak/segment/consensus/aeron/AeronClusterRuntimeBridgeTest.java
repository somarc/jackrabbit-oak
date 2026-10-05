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

import io.aeron.cluster.service.ClusteredService;
import io.aeron.cluster.service.ClusteredServiceContainer;
import org.junit.Test;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

public class AeronClusterRuntimeBridgeTest {

    @Test
    public void activateStartsHealthMonitorConfiguresIngressAndSchedulesReset() {
        io.aeron.Aeron aeron = mock(io.aeron.Aeron.class);
        ClusteredServiceContainer container = mock(ClusteredServiceContainer.class);
        ClusteredServiceContainer.Context context = mock(ClusteredServiceContainer.Context.class);
        when(container.context()).thenReturn(context);
        when(context.aeron()).thenReturn(aeron);

        AeronConsensusEngine engine = mock(AeronConsensusEngine.class);
        AeronClusterFailureCoordinator failureCoordinator = mock(AeronClusterFailureCoordinator.class);
        MediaDriverHealthMonitor healthMonitor = mock(MediaDriverHealthMonitor.class);

        MediaDriverHealthMonitor started =
            new AeronClusterRuntimeBridge(engine, ignored -> healthMonitor)
                .activate(container, "aeron-dir", failureCoordinator);

        assertSame(healthMonitor, started);
        verify(engine).setAeronDirectoryName("aeron-dir");
        verify(failureCoordinator).scheduleSuccessfulStartupReset();
    }

    @Test
    public void activateHandlesMissingAeronWithoutStartingHealthMonitor() {
        ClusteredServiceContainer container = mock(ClusteredServiceContainer.class);
        ClusteredServiceContainer.Context context = mock(ClusteredServiceContainer.Context.class);
        when(container.context()).thenReturn(context);
        when(context.aeron()).thenReturn(null);

        ClusteredService service = mock(ClusteredService.class);
        AeronClusterFailureCoordinator failureCoordinator = mock(AeronClusterFailureCoordinator.class);

        assertNull(new AeronClusterRuntimeBridge(service, ignored -> mock(MediaDriverHealthMonitor.class))
            .activate(container, "ignored", failureCoordinator));

        verifyNoInteractions(service);
        verify(failureCoordinator).scheduleSuccessfulStartupReset();
    }

    @Test
    public void activateSwallowsHealthMonitorFactoryFailure() {
        io.aeron.Aeron aeron = mock(io.aeron.Aeron.class);
        ClusteredServiceContainer container = mock(ClusteredServiceContainer.class);
        ClusteredServiceContainer.Context context = mock(ClusteredServiceContainer.Context.class);
        when(container.context()).thenReturn(context);
        when(context.aeron()).thenReturn(aeron);

        ClusteredService service = mock(ClusteredService.class);
        AeronClusterFailureCoordinator failureCoordinator = mock(AeronClusterFailureCoordinator.class);

        assertNull(new AeronClusterRuntimeBridge(service, ignored -> {
            throw new IllegalStateException("boom");
        }).activate(container, "ignored", failureCoordinator));

        verifyNoInteractions(service);
        verify(failureCoordinator).scheduleSuccessfulStartupReset();
    }

    @Test
    public void activateSupportsMissingFailureCoordinator() {
        ClusteredServiceContainer container = mock(ClusteredServiceContainer.class);
        ClusteredServiceContainer.Context context = mock(ClusteredServiceContainer.Context.class);
        when(container.context()).thenReturn(context);
        when(context.aeron()).thenReturn(null);

        assertNull(new AeronClusterRuntimeBridge(mock(ClusteredService.class), ignored -> mock(MediaDriverHealthMonitor.class))
            .activate(container, "ignored", null));
    }
}
