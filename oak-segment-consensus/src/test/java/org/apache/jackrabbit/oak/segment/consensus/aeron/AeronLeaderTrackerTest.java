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

import io.aeron.cluster.service.Cluster;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AeronLeaderTrackerTest {

    @Test
    public void leadershipHistoryReturnsNewestFirstAndRespectsLimit() {
        AeronLeaderTracker tracker = new AeronLeaderTracker(null);

        tracker.recordChange(Cluster.Role.FOLLOWER, Cluster.Role.LEADER, 7, 1, "node-1", 1000L);
        tracker.recordChange(Cluster.Role.LEADER, Cluster.Role.FOLLOWER, 8, 2, "node-2", 2000L);

        List<LeadershipChange> all = tracker.getLeadershipHistory(0);
        assertEquals(2, all.size());
        assertEquals(2000L, all.get(0).timestamp);
        assertEquals(1000L, all.get(1).timestamp);

        List<LeadershipChange> limited = tracker.getLeadershipHistory(1);
        assertEquals(1, limited.size());
        assertEquals(2000L, limited.get(0).timestamp);
        assertEquals(Cluster.Role.LEADER, limited.get(0).newRole);
    }

    @Test
    public void historyIsCappedAt100Entries() {
        AeronLeaderTracker tracker = new AeronLeaderTracker(null);
        for (int i = 0; i < 120; i++) {
            tracker.recordChange(Cluster.Role.FOLLOWER, Cluster.Role.LEADER, i, i, "node-" + i, i);
        }
        List<LeadershipChange> all = tracker.getLeadershipHistory(0);
        assertEquals(100, all.size());
        assertEquals(119L, all.get(0).timestamp);
        assertEquals(20L, all.get(all.size() - 1).timestamp);
        assertTrue(all.get(0).term > all.get(all.size() - 1).term);
    }
}
