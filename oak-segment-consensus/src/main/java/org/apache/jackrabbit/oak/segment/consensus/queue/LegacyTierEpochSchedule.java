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

/**
 * Compatibility policy for the current tier-driven epoch scheduler.
 *
 * <p>This keeps the existing epoch contract in one place so write and delete
 * paths cannot drift independently while adaptive release work is staged in.
 */
final class LegacyTierEpochSchedule {

    static final int STANDARD_DELAY_EPOCHS = 2;
    static final int EXPRESS_DELAY_EPOCHS = 1;
    static final int PRIORITY_DELAY_EPOCHS = 0;

    private LegacyTierEpochSchedule() {
    }

    static long resolveQueueEpoch(ValidatorEarningsTracker.PaymentTier tier, long currentEpoch) {
        if (tier == ValidatorEarningsTracker.PaymentTier.PRIORITY) {
            return currentEpoch - STANDARD_DELAY_EPOCHS;
        }
        return currentEpoch;
    }

    static int resolveFinalityDelay(ValidatorEarningsTracker.PaymentTier tier) {
        if (tier == null) {
            return STANDARD_DELAY_EPOCHS;
        }
        if (tier == ValidatorEarningsTracker.PaymentTier.PRIORITY) {
            return PRIORITY_DELAY_EPOCHS;
        }
        if (tier == ValidatorEarningsTracker.PaymentTier.EXPRESS) {
            return EXPRESS_DELAY_EPOCHS;
        }
        return STANDARD_DELAY_EPOCHS;
    }
}
