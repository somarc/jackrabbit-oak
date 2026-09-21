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

import org.agrona.concurrent.Agent;
import org.agrona.concurrent.AgentRunner;
import org.agrona.concurrent.BusySpinIdleStrategy;
import org.junit.Test;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

public class ProposalQueueAgentRuntimeTest {

    @Test
    public void startRegistersNamedThreadsAndStopClosesAllRunners() {
        RecordingRunnerFactory runnerFactory = new RecordingRunnerFactory(4);
        ProposalQueueAgentRuntime runtime = new ProposalQueueAgentRuntime(runnerFactory);

        runtime.start(
            BusySpinIdleStrategy::new,
            NoopAgent::new,
            2,
            BusySpinIdleStrategy::new,
            ignored -> new NoopAgent(),
            BusySpinIdleStrategy::new,
            NoopAgent::new,
            LoggerFactory.getLogger(ProposalQueueAgentRuntimeTest.class)
        );

        assertEquals(2, runtime.getVerifierThreadCount());
        assertEquals(
            Arrays.asList("aeron-sender", "evm-verifier-0", "evm-verifier-1", "release-finalizer"),
            runnerFactory.threadNames
        );

        runtime.stop(LoggerFactory.getLogger(ProposalQueueAgentRuntimeTest.class));

        for (AgentRunner runner : runnerFactory.runners) {
            verify(runner, times(1)).close();
        }
        assertEquals(0, runtime.getVerifierThreadCount());
    }

    @Test
    public void stopContinuesClosingWhenOneRunnerThrows() {
        RecordingRunnerFactory runnerFactory = new RecordingRunnerFactory(3);
        doThrow(new IllegalStateException("boom")).when(runnerFactory.runners.get(0)).close();
        ProposalQueueAgentRuntime runtime = new ProposalQueueAgentRuntime(runnerFactory);

        runtime.start(
            BusySpinIdleStrategy::new,
            NoopAgent::new,
            1,
            BusySpinIdleStrategy::new,
            ignored -> new NoopAgent(),
            BusySpinIdleStrategy::new,
            NoopAgent::new,
            LoggerFactory.getLogger(ProposalQueueAgentRuntimeTest.class)
        );

        runtime.stop(LoggerFactory.getLogger(ProposalQueueAgentRuntimeTest.class));

        verify(runnerFactory.runners.get(0), times(1)).close();
        verify(runnerFactory.runners.get(1), times(1)).close();
        verify(runnerFactory.runners.get(2), times(1)).close();
    }

    private static final class RecordingRunnerFactory implements ProposalQueueAgentRuntime.RunnerFactory {
        private final List<AgentRunner> runners = new ArrayList<>();
        private final List<String> threadNames = new ArrayList<>();
        private int nextIndex = 0;

        private RecordingRunnerFactory(int count) {
            for (int i = 0; i < count; i++) {
                runners.add(mock(AgentRunner.class));
            }
        }

        @Override
        public AgentRunner create(org.agrona.concurrent.IdleStrategy idleStrategy,
                                  org.agrona.ErrorHandler errorHandler,
                                  Agent agent) {
            return runners.get(nextIndex++);
        }

        @Override
        public void startOnThread(AgentRunner runner, String threadName) {
            threadNames.add(threadName);
        }
    }

    private static final class NoopAgent implements Agent {
        @Override
        public int doWork() {
            return 0;
        }

        @Override
        public String roleName() {
            return "noop";
        }
    }
}
