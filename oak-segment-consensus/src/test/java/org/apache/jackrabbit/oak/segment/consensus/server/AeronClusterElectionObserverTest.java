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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.jackrabbit.oak.segment.consensus.aeron.AeronConsensusEngine;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class AeronClusterElectionObserverTest {

    @Test
    public void observeTreatsSingleLeaderAsStableWithoutCountingInitialElectionAsChange() throws Exception {
        AtomicLong now = new AtomicLong(0L);
        List<String> lines = new ArrayList<>();
        AeronClusterElectionObserver observer = new AeronClusterElectionObserver(
            now::get,
            millis -> now.addAndGet(millis),
            lines::add
        );

        AeronConsensusEngine engine = mock(AeronConsensusEngine.class);
        when(engine.getLeaderMemberId()).thenReturn(1);
        when(engine.isLeader()).thenReturn(false);
        when(engine.getClusterSize()).thenReturn(3);

        AeronClusterElectionObserver.ObservationSummary summary = observer.observe(engine, 2000);

        assertEquals(0, summary.changeCount);
        assertEquals(1, summary.uniqueLeaders);
        assertEquals(1, summary.finalLeaderId);
        assertTrue(lines.stream().anyMatch(line -> line.contains("Single stable leader")));
    }

    @Test
    public void observeCountsLeadershipChangesAfterInitialElection() throws Exception {
        AtomicLong now = new AtomicLong(0L);
        AeronClusterElectionObserver observer = new AeronClusterElectionObserver(
            now::get,
            millis -> now.addAndGet(millis),
            ignored -> { }
        );

        AeronConsensusEngine engine = mock(AeronConsensusEngine.class);
        when(engine.getLeaderMemberId()).thenReturn(1, 2, 2);
        when(engine.isLeader()).thenReturn(false);
        when(engine.getClusterSize()).thenReturn(3);

        AeronClusterElectionObserver.ObservationSummary summary = observer.observe(engine, 3000);

        assertEquals(1, summary.changeCount);
        assertEquals(2, summary.uniqueLeaders);
        assertEquals(2, summary.finalLeaderId);
    }

    @Test
    public void observeFailsWhenNoLeaderAppears() throws Exception {
        AtomicLong now = new AtomicLong(0L);
        AeronClusterElectionObserver observer = new AeronClusterElectionObserver(
            now::get,
            millis -> now.addAndGet(millis),
            ignored -> { }
        );

        AeronConsensusEngine engine = mock(AeronConsensusEngine.class);
        when(engine.getLeaderMemberId()).thenReturn(-1);
        when(engine.getClusterSize()).thenReturn(3);

        try {
            observer.observe(engine, 1000);
            fail("Expected failure when no leader is observed");
        } catch (Exception e) {
            assertEquals("No leader elected during observation period", e.getMessage());
        }
    }
}
