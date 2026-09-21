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

import org.junit.Test;

import static org.junit.Assert.*;

public class DurabilityAckTrackerTest {

    @Test
    public void quorumSuccessProducesAckOnce() {
        DurabilityAckTracker tracker = new DurabilityAckTracker();
        tracker.track("p1", 3, 2);

        DurabilityAckTracker.Outcome first = tracker.record("p1", 0, "h1", true, null, 3, 2);
        assertNotNull(first);
        assertFalse(first.shouldAck);

        DurabilityAckTracker.Outcome second = tracker.record("p1", 1, "h1", true, null, 3, 2);
        assertNotNull(second);
        assertTrue(second.shouldAck);
        assertTrue(second.success);
        assertEquals("h1", second.durableHead);

        DurabilityAckTracker.Outcome duplicate = tracker.record("p1", 1, "h1", true, null, 3, 2);
        assertNull(duplicate);
    }

    @Test
    public void tooManyFailuresProduceNegativeAck() {
        DurabilityAckTracker tracker = new DurabilityAckTracker();
        tracker.track("p2", 3, 3);

        DurabilityAckTracker.Outcome first = tracker.record("p2", 0, null, false, "disk full", 3, 3);
        assertNotNull(first);
        assertTrue(first.shouldAck);
        assertFalse(first.success);
        assertEquals("disk full", first.error);

        DurabilityAckTracker.Outcome second = tracker.record("p2", 1, null, false, "io", 3, 3);
        assertNull(second);
    }

    @Test
    public void invalidMemberIsIgnored() {
        DurabilityAckTracker tracker = new DurabilityAckTracker();
        DurabilityAckTracker.Outcome outcome = tracker.record("p3", -1, null, true, null, 3, 2);
        assertNull(outcome);
    }
}
