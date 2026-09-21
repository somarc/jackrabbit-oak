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

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

import org.apache.jackrabbit.oak.segment.consensus.aeron.AeronConsensusEngine;

final class AeronClusterElectionObserver {

    interface TimeSource {
        long currentTimeMillis();
    }

    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    static final class ObservationSummary {
        final int changeCount;
        final int uniqueLeaders;
        final int finalLeaderId;
        final int clusterSize;

        ObservationSummary(int changeCount, int uniqueLeaders, int finalLeaderId, int clusterSize) {
            this.changeCount = changeCount;
            this.uniqueLeaders = uniqueLeaders;
            this.finalLeaderId = finalLeaderId;
            this.clusterSize = clusterSize;
        }
    }

    private final TimeSource timeSource;
    private final Sleeper sleeper;
    private final Consumer<String> lineSink;

    AeronClusterElectionObserver() {
        this(System::currentTimeMillis, Thread::sleep, System.out::println);
    }

    AeronClusterElectionObserver(TimeSource timeSource, Sleeper sleeper, Consumer<String> lineSink) {
        this.timeSource = timeSource;
        this.sleeper = sleeper;
        this.lineSink = lineSink;
    }

    ObservationSummary observe(AeronConsensusEngine engine, long observationMs) throws Exception {
        Set<Integer> observedLeaders = new HashSet<>();
        long startTime = timeSource.currentTimeMillis();
        int lastLeaderId = -1;
        int changeCount = 0;

        lineSink.accept("   Observing elections for " + (observationMs / 1000) + " seconds...");
        lineSink.accept("");

        while (timeSource.currentTimeMillis() - startTime < observationMs) {
            int currentLeaderId = engine.getLeaderMemberId();

            if (currentLeaderId >= 0) {
                observedLeaders.add(currentLeaderId);

                if (currentLeaderId != lastLeaderId) {
                    if (lastLeaderId != -1) {
                        changeCount++;
                    }
                    String role = engine.isLeader() ? "LEADER (this node)" : "FOLLOWER";
                    lineSink.accept(
                        "   " + new SimpleDateFormat("HH:mm:ss").format(new Date(timeSource.currentTimeMillis())) +
                            " - Leader is node " + currentLeaderId +
                            " (role: " + role + ", changes: " + changeCount + ")"
                    );
                    lastLeaderId = currentLeaderId;
                }
            }

            sleeper.sleep(1000);
        }

        ObservationSummary summary = new ObservationSummary(
            changeCount,
            observedLeaders.size(),
            lastLeaderId,
            engine.getClusterSize()
        );

        lineSink.accept("");
        lineSink.accept("   Observation Results:");
        lineSink.accept("   - Duration: " + (observationMs / 1000) + " seconds");
        lineSink.accept("   - Leadership changes: " + summary.changeCount);
        lineSink.accept("   - Unique leaders observed: " + summary.uniqueLeaders + " of " + summary.clusterSize + " nodes");
        lineSink.accept("   - Final leader: node " + summary.finalLeaderId);
        lineSink.accept("");

        if (observedLeaders.isEmpty()) {
            throw new Exception("No leader elected during observation period");
        }

        if (summary.uniqueLeaders == 1 && summary.changeCount == 0) {
            lineSink.accept("   ℹ️  Single stable leader throughout observation (healthy)");
        } else if (summary.changeCount > 3) {
            lineSink.accept("   ⚠️  WARNING: " + summary.changeCount + " leadership changes detected");
            lineSink.accept("              This may indicate network instability");
        }

        return summary;
    }
}
