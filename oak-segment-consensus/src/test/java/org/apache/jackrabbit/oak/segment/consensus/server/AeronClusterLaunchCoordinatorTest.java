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
package org.apache.jackrabbit.oak.segment.consensus.server;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;

import org.apache.jackrabbit.oak.segment.consensus.aeron.AeronClusterLauncher;
import org.apache.jackrabbit.oak.segment.consensus.aeron.AeronConsensusEngine;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

public class AeronClusterLaunchCoordinatorTest {

    @Test
    public void launchConfiguresShutdownCallbackAndStartsLauncher() throws Exception {
        AeronClusterLauncher launcher = mock(AeronClusterLauncher.class);
        AeronConsensusEngine engine = mock(AeronConsensusEngine.class);
        FatalMediaDriverExitHandler exitHandler = mock(FatalMediaDriverExitHandler.class);
        File clusterBaseDir = new File("target");

        AeronClusterLaunchCoordinator coordinator = new AeronClusterLaunchCoordinator(
            (nodeId, hostnames, baseDir, clusteredService) -> {
                assertSame(engine, clusteredService);
                assertSame(clusterBaseDir, baseDir);
                return launcher;
            },
            exitHandler
        );

        AeronClusterLauncher launched = coordinator.launch(
            2,
            Arrays.asList("node-a", "node-b"),
            clusterBaseDir,
            engine
        );

        assertSame(launcher, launched);
        ArgumentCaptor<Runnable> callbackCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(launcher).setShutdownCallback(callbackCaptor.capture());
        verify(launcher).launch();

        callbackCaptor.getValue().run();
        verify(exitHandler).handleFatalDriverError();
    }

    @Test
    public void launchWrapsLauncherFailureInIOException() throws Exception {
        AeronClusterLauncher launcher = mock(AeronClusterLauncher.class);
        doThrow(new IllegalStateException("boom")).when(launcher).launch();

        AeronClusterLaunchCoordinator coordinator = new AeronClusterLaunchCoordinator(
            (nodeId, hostnames, baseDir, clusteredService) -> launcher,
            mock(FatalMediaDriverExitHandler.class)
        );

        try {
            coordinator.launch(0, Arrays.asList("node-a"), new File("target"), mock(AeronConsensusEngine.class));
            fail("Expected IOException");
        } catch (IOException e) {
            assertSame(IllegalStateException.class, e.getCause().getClass());
        }
    }
}
