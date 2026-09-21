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

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AdaptiveReleaseGovernorTest {

    private final AdaptiveReleaseGovernor governor = new AdaptiveReleaseGovernor(
        100,
        400,
        50,
        90,
        5_000,
        30_000,
        2_000,
        10_000,
        80,
        300,
        40,
        150
    );

    @Test
    public void testHealthySignalsStayDirect() {
        AdaptiveReleaseGovernor.Decision decision = governor.evaluate(new AdaptiveReleaseGovernor.SignalSnapshot(
            10,
            5,
            100,
            false,
            0,
            0,
            12,
            1,
            8
        ));

        assertEquals(AdaptiveReleaseGovernor.GovernorState.HEALTHY, decision.getState());
        assertEquals(AdaptiveReleaseGovernor.ReleaseAction.DIRECT, decision.getAction());
        assertTrue(decision.getReasonCodes().isEmpty());
    }

    @Test
    public void testGapPressureMovesToBuffered() {
        AdaptiveReleaseGovernor.Decision decision = governor.evaluate(new AdaptiveReleaseGovernor.SignalSnapshot(
            120,
            10,
            100,
            false,
            0,
            0,
            20,
            2,
            10
        ));

        assertEquals(AdaptiveReleaseGovernor.GovernorState.PRESSURED, decision.getState());
        assertEquals(AdaptiveReleaseGovernor.ReleaseAction.BUFFERED, decision.getAction());
        assertTrue(decision.getReasonCodes().contains("verified_finalized_gap_high"));
    }

    @Test
    public void testBackpressureAndStallMoveToThrottled() {
        AdaptiveReleaseGovernor.Decision decision = governor.evaluate(new AdaptiveReleaseGovernor.SignalSnapshot(
            40,
            95,
            100,
            true,
            35_000,
            12_000,
            350,
            12,
            175
        ));

        assertEquals(AdaptiveReleaseGovernor.GovernorState.OVERLOADED, decision.getState());
        assertEquals(AdaptiveReleaseGovernor.ReleaseAction.THROTTLED, decision.getAction());
        assertTrue(decision.getReasonCodes().contains("backpressure_active"));
        assertTrue(decision.getReasonCodes().contains("backpressure_pending_stalled"));
        assertTrue(decision.getReasonCodes().contains("release_ready_backlog_high"));
    }
}
