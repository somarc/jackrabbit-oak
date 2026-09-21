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
import org.agrona.concurrent.IdleStrategy;
import org.slf4j.Logger;

import java.util.function.IntFunction;
import java.util.function.Supplier;

final class ProposalQueueAgentRuntime {

    interface RunnerFactory {
        AgentRunner create(IdleStrategy idleStrategy,
                           org.agrona.ErrorHandler errorHandler,
                           Agent agent);

        void startOnThread(AgentRunner runner, String threadName);
    }

    private static final class DefaultRunnerFactory implements RunnerFactory {
        @Override
        public AgentRunner create(IdleStrategy idleStrategy,
                                  org.agrona.ErrorHandler errorHandler,
                                  Agent agent) {
            return new AgentRunner(idleStrategy, errorHandler, null, agent);
        }

        @Override
        public void startOnThread(AgentRunner runner, String threadName) {
            AgentRunner.startOnThread(runner, r -> {
                Thread t = new Thread(r, threadName);
                t.setDaemon(true);
                return t;
            });
        }
    }

    private final RunnerFactory runnerFactory;
    private AgentRunner aeronSenderAgent;
    private AgentRunner[] evmVerifierAgents;
    private AgentRunner releaseFinalizerAgent;

    ProposalQueueAgentRuntime() {
        this(new DefaultRunnerFactory());
    }

    ProposalQueueAgentRuntime(RunnerFactory runnerFactory) {
        this.runnerFactory = runnerFactory;
    }

    void start(Supplier<IdleStrategy> aeronIdleStrategySupplier,
               Supplier<Agent> aeronAgentSupplier,
               int verifierThreads,
               Supplier<IdleStrategy> verifierIdleStrategySupplier,
               IntFunction<Agent> verifierAgentFactory,
               Supplier<IdleStrategy> releaseIdleStrategySupplier,
               Supplier<Agent> releaseAgentSupplier,
               Logger log) {
        aeronSenderAgent = runnerFactory.create(
            aeronIdleStrategySupplier.get(),
            throwable -> log.error("Error in Aeron sender agent", throwable),
            aeronAgentSupplier.get()
        );

        evmVerifierAgents = new AgentRunner[verifierThreads];
        for (int i = 0; i < verifierThreads; i++) {
            evmVerifierAgents[i] = runnerFactory.create(
                verifierIdleStrategySupplier.get(),
                throwable -> log.error("Error in EVM verifier agent", throwable),
                verifierAgentFactory.apply(i)
            );
        }

        releaseFinalizerAgent = runnerFactory.create(
            releaseIdleStrategySupplier.get(),
            throwable -> log.error("Error in release finalizer agent", throwable),
            releaseAgentSupplier.get()
        );

        runnerFactory.startOnThread(aeronSenderAgent, "aeron-sender");
        for (int i = 0; i < evmVerifierAgents.length; i++) {
            runnerFactory.startOnThread(evmVerifierAgents[i], "evm-verifier-" + i);
        }
        runnerFactory.startOnThread(releaseFinalizerAgent, "release-finalizer");
    }

    void stop(Logger log) {
        closeRunner(aeronSenderAgent, "Aeron sender", log);
        aeronSenderAgent = null;

        if (evmVerifierAgents != null) {
            for (AgentRunner evmVerifierAgent : evmVerifierAgents) {
                closeRunner(evmVerifierAgent, "EVM verifier", log);
            }
            evmVerifierAgents = null;
        }

        closeRunner(releaseFinalizerAgent, "release finalizer", log);
        releaseFinalizerAgent = null;
    }

    int getVerifierThreadCount() {
        return evmVerifierAgents != null ? evmVerifierAgents.length : 0;
    }

    private void closeRunner(AgentRunner runner, String label, Logger log) {
        if (runner == null) {
            return;
        }
        try {
            runner.close();
        } catch (Exception e) {
            log.error("Error closing {} agent", label, e);
        }
    }
}
