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
package org.apache.jackrabbit.oak.segment.consensus.eth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Circuit-breaker decorator for a {@link BeaconChainProvider}.
 *
 * <p>State machine:
 * <ul>
 *   <li><b>CLOSED</b> (normal): passes every call through to the delegate.</li>
 *   <li><b>OPEN</b>: after {@code failureThreshold} consecutive failures, rejects
 *       calls immediately without hitting the delegate.</li>
 *   <li><b>HALF-OPEN</b>: after {@code resetAfterMs} in the open state, allows
 *       one probe call through. A successful probe → CLOSED; a failed probe →
 *       OPEN with a refreshed cooldown.</li>
 * </ul>
 */
class CircuitBreakingBeaconProvider implements BeaconChainProvider {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreakingBeaconProvider.class);

    private final BeaconChainProvider delegate;
    private final int failureThreshold;
    private final long resetAfterMs;

    private volatile int consecutiveFailures = 0;
    private volatile long openedAt = 0L;

    CircuitBreakingBeaconProvider(BeaconChainProvider delegate, int failureThreshold, long resetAfterMs) {
        this.delegate = delegate;
        this.failureThreshold = failureThreshold;
        this.resetAfterMs = resetAfterMs;
    }

    @Override
    public long fetchFinalizedEpoch() throws Exception {
        if (circuitIsOpen()) {
            throw new CircuitOpenException(delegate.name() + " circuit is open (" +
                consecutiveFailures + " consecutive failures)");
        }
        try {
            long epoch = delegate.fetchFinalizedEpoch();
            onSuccess();
            return epoch;
        } catch (Exception e) {
            onFailure();
            throw e;
        }
    }

    @Override
    public String name() {
        return delegate.name();
    }

    boolean isCircuitOpen() {
        return circuitIsOpen();
    }

    int getConsecutiveFailures() {
        return consecutiveFailures;
    }

    private boolean circuitIsOpen() {
        if (consecutiveFailures < failureThreshold) {
            return false;
        }
        if (System.currentTimeMillis() - openedAt >= resetAfterMs) {
            // Cooldown elapsed — transition to half-open: allow one probe through
            // without clearing consecutiveFailures (cleared only on success)
            return false;
        }
        return true;
    }

    private void onSuccess() {
        if (consecutiveFailures > 0) {
            log.info("Circuit closed for {} after successful probe", delegate.name());
            consecutiveFailures = 0;
        }
    }

    private void onFailure() {
        consecutiveFailures++;
        if (consecutiveFailures >= failureThreshold) {
            openedAt = System.currentTimeMillis(); // refresh cooldown on every failure past threshold
            log.warn("Circuit open for {} ({} consecutive failures, resets in {}s)",
                delegate.name(), consecutiveFailures, resetAfterMs / 1000);
        }
    }

    static final class CircuitOpenException extends RuntimeException {
        CircuitOpenException(String message) {
            super(message);
        }
    }
}
