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

import org.apache.jackrabbit.oak.segment.consensus.economics.ValidatorEarningsTracker;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class LegacyTierEpochScheduleTest {

    @Test
    public void testQueueEpochParityAcrossWriteAndDeletePaths() {
        long currentEpoch = 500L;

        assertEquals(498L,
            LegacyTierEpochSchedule.resolveQueueEpoch(ValidatorEarningsTracker.PaymentTier.PRIORITY, currentEpoch));
        assertEquals(500L,
            LegacyTierEpochSchedule.resolveQueueEpoch(ValidatorEarningsTracker.PaymentTier.EXPRESS, currentEpoch));
        assertEquals(500L,
            LegacyTierEpochSchedule.resolveQueueEpoch(ValidatorEarningsTracker.PaymentTier.STANDARD, currentEpoch));
    }

    @Test
    public void testFinalityDelayRemainsTierSpecific() {
        assertEquals(0,
            LegacyTierEpochSchedule.resolveFinalityDelay(ValidatorEarningsTracker.PaymentTier.PRIORITY));
        assertEquals(1,
            LegacyTierEpochSchedule.resolveFinalityDelay(ValidatorEarningsTracker.PaymentTier.EXPRESS));
        assertEquals(2,
            LegacyTierEpochSchedule.resolveFinalityDelay(ValidatorEarningsTracker.PaymentTier.STANDARD));
        assertEquals(2, LegacyTierEpochSchedule.resolveFinalityDelay(null));
    }
}
