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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class AdaptiveReleaseGovernor {

    enum GovernorState {
        HEALTHY,
        PRESSURED,
        OVERLOADED
    }

    enum ReleaseAction {
        DIRECT,
        BUFFERED,
        THROTTLED
    }

    static final class SignalSnapshot {
        private final long verifiedFinalizedGap;
        private final long backpressurePendingCount;
        private final long backpressureMaxPending;
        private final boolean backpressureActive;
        private final long backpressurePendingOldestMs;
        private final long backpressurePendingStalledMs;
        private final long verifiedPackingBufferCount;
        private final long releaseReadyBatchCount;
        private final long releaseReadyProposalCount;

        SignalSnapshot(long verifiedFinalizedGap,
                       long backpressurePendingCount,
                       long backpressureMaxPending,
                       boolean backpressureActive,
                       long backpressurePendingOldestMs,
                       long backpressurePendingStalledMs,
                       long verifiedPackingBufferCount,
                       long releaseReadyBatchCount,
                       long releaseReadyProposalCount) {
            this.verifiedFinalizedGap = Math.max(0L, verifiedFinalizedGap);
            this.backpressurePendingCount = Math.max(0L, backpressurePendingCount);
            this.backpressureMaxPending = Math.max(1L, backpressureMaxPending);
            this.backpressureActive = backpressureActive;
            this.backpressurePendingOldestMs = Math.max(0L, backpressurePendingOldestMs);
            this.backpressurePendingStalledMs = Math.max(0L, backpressurePendingStalledMs);
            this.verifiedPackingBufferCount = Math.max(0L, verifiedPackingBufferCount);
            this.releaseReadyBatchCount = Math.max(0L, releaseReadyBatchCount);
            this.releaseReadyProposalCount = Math.max(0L, releaseReadyProposalCount);
        }

        long getVerifiedFinalizedGap() {
            return verifiedFinalizedGap;
        }

        long getBackpressurePendingCount() {
            return backpressurePendingCount;
        }

        long getBackpressureMaxPending() {
            return backpressureMaxPending;
        }

        boolean isBackpressureActive() {
            return backpressureActive;
        }

        long getBackpressurePendingOldestMs() {
            return backpressurePendingOldestMs;
        }

        long getBackpressurePendingStalledMs() {
            return backpressurePendingStalledMs;
        }

        long getVerifiedPackingBufferCount() {
            return verifiedPackingBufferCount;
        }

        long getReleaseReadyBatchCount() {
            return releaseReadyBatchCount;
        }

        long getReleaseReadyProposalCount() {
            return releaseReadyProposalCount;
        }
    }

    static final class Decision {
        private static final Decision HEALTHY_DIRECT =
            new Decision(GovernorState.HEALTHY, ReleaseAction.DIRECT, Collections.<String>emptyList());

        private final GovernorState state;
        private final ReleaseAction action;
        private final List<String> reasonCodes;

        Decision(GovernorState state, ReleaseAction action, List<String> reasonCodes) {
            this.state = state;
            this.action = action;
            this.reasonCodes = Collections.unmodifiableList(new ArrayList<String>(reasonCodes));
        }

        static Decision healthyDirect() {
            return HEALTHY_DIRECT;
        }

        GovernorState getState() {
            return state;
        }

        ReleaseAction getAction() {
            return action;
        }

        List<String> getReasonCodes() {
            return reasonCodes;
        }

        String signature() {
            return state.name() + "|" + action.name() + "|" + String.join(",", reasonCodes);
        }
    }

    private final long pressuredVerifiedFinalizedGap;
    private final long overloadedVerifiedFinalizedGap;
    private final long pressuredPendingThreshold;
    private final long overloadedPendingThreshold;
    private final long pressuredPendingOldestMs;
    private final long overloadedPendingOldestMs;
    private final long pressuredPendingStalledMs;
    private final long overloadedPendingStalledMs;
    private final long pressuredVerifiedPackingBufferCount;
    private final long overloadedVerifiedPackingBufferCount;
    private final long pressuredReleaseReadyProposalCount;
    private final long overloadedReleaseReadyProposalCount;

    AdaptiveReleaseGovernor(long pressuredVerifiedFinalizedGap,
                            long overloadedVerifiedFinalizedGap,
                            long pressuredPendingThreshold,
                            long overloadedPendingThreshold,
                            long pressuredPendingOldestMs,
                            long overloadedPendingOldestMs,
                            long pressuredPendingStalledMs,
                            long overloadedPendingStalledMs,
                            long pressuredVerifiedPackingBufferCount,
                            long overloadedVerifiedPackingBufferCount,
                            long pressuredReleaseReadyProposalCount,
                            long overloadedReleaseReadyProposalCount) {
        this.pressuredVerifiedFinalizedGap = Math.max(1L, pressuredVerifiedFinalizedGap);
        this.overloadedVerifiedFinalizedGap = Math.max(this.pressuredVerifiedFinalizedGap, overloadedVerifiedFinalizedGap);
        this.pressuredPendingThreshold = Math.max(1L, pressuredPendingThreshold);
        this.overloadedPendingThreshold = Math.max(this.pressuredPendingThreshold, overloadedPendingThreshold);
        this.pressuredPendingOldestMs = Math.max(1L, pressuredPendingOldestMs);
        this.overloadedPendingOldestMs = Math.max(this.pressuredPendingOldestMs, overloadedPendingOldestMs);
        this.pressuredPendingStalledMs = Math.max(1L, pressuredPendingStalledMs);
        this.overloadedPendingStalledMs = Math.max(this.pressuredPendingStalledMs, overloadedPendingStalledMs);
        this.pressuredVerifiedPackingBufferCount = Math.max(1L, pressuredVerifiedPackingBufferCount);
        this.overloadedVerifiedPackingBufferCount = Math.max(this.pressuredVerifiedPackingBufferCount, overloadedVerifiedPackingBufferCount);
        this.pressuredReleaseReadyProposalCount = Math.max(1L, pressuredReleaseReadyProposalCount);
        this.overloadedReleaseReadyProposalCount = Math.max(this.pressuredReleaseReadyProposalCount, overloadedReleaseReadyProposalCount);
    }

    static AdaptiveReleaseGovernor fromTuning(ProposalQueueTuning tuning) {
        long baseReleaseWindow = Math.max(1L, (long) tuning.getFinalizationChunkSize() * tuning.getMaxMessageBatch());
        long pressuredGap = Math.max(64L, baseReleaseWindow * 4L);
        long overloadedGap = Math.max(256L, pressuredGap * 4L);
        long pressuredPending = Math.max(1L, tuning.getMaxPendingMessages() / 2L);
        long overloadedPending = Math.max(1L, Math.max(pressuredPending + 1L, (tuning.getMaxPendingMessages() * 9L) / 10L));
        long pressuredPacking = Math.max(64L, baseReleaseWindow * 4L);
        long overloadedPacking = Math.max(256L, pressuredPacking * 4L);
        long pressuredReleaseReady = Math.max(32L, baseReleaseWindow * 2L);
        long overloadedReleaseReady = Math.max(128L, pressuredReleaseReady * 4L);
        return new AdaptiveReleaseGovernor(
            pressuredGap,
            overloadedGap,
            pressuredPending,
            overloadedPending,
            5_000L,
            30_000L,
            2_000L,
            10_000L,
            pressuredPacking,
            overloadedPacking,
            pressuredReleaseReady,
            overloadedReleaseReady
        );
    }

    Decision evaluate(SignalSnapshot signals) {
        List<String> overloadedReasons = collectReasons(signals, true);
        if (!overloadedReasons.isEmpty()) {
            return new Decision(GovernorState.OVERLOADED, ReleaseAction.THROTTLED, overloadedReasons);
        }

        List<String> pressuredReasons = collectReasons(signals, false);
        if (!pressuredReasons.isEmpty()) {
            return new Decision(GovernorState.PRESSURED, ReleaseAction.BUFFERED, pressuredReasons);
        }

        return Decision.healthyDirect();
    }

    private List<String> collectReasons(SignalSnapshot signals, boolean overloaded) {
        List<String> reasons = new ArrayList<String>();
        long gapThreshold = overloaded ? overloadedVerifiedFinalizedGap : pressuredVerifiedFinalizedGap;
        long pendingThreshold = overloaded ? overloadedPendingThreshold : pressuredPendingThreshold;
        long oldestThreshold = overloaded ? overloadedPendingOldestMs : pressuredPendingOldestMs;
        long stalledThreshold = overloaded ? overloadedPendingStalledMs : pressuredPendingStalledMs;
        long packingThreshold = overloaded ? overloadedVerifiedPackingBufferCount : pressuredVerifiedPackingBufferCount;
        long releaseReadyThreshold = overloaded ? overloadedReleaseReadyProposalCount : pressuredReleaseReadyProposalCount;

        if (signals.isBackpressureActive()) {
            reasons.add("backpressure_active");
        }
        if (signals.getBackpressurePendingCount() >= pendingThreshold) {
            reasons.add("backpressure_pending_high");
        }
        if (signals.getBackpressurePendingOldestMs() >= oldestThreshold) {
            reasons.add("backpressure_pending_oldest_high");
        }
        if (signals.getBackpressurePendingStalledMs() >= stalledThreshold) {
            reasons.add("backpressure_pending_stalled");
        }
        if (signals.getVerifiedFinalizedGap() >= gapThreshold) {
            reasons.add("verified_finalized_gap_high");
        }
        if (signals.getVerifiedPackingBufferCount() >= packingThreshold) {
            reasons.add("verified_packing_buffer_high");
        }
        if (signals.getReleaseReadyProposalCount() >= releaseReadyThreshold) {
            reasons.add("release_ready_backlog_high");
        }
        if (signals.getReleaseReadyBatchCount() > 0 && signals.getBackpressurePendingCount() >= pendingThreshold) {
            reasons.add("release_ready_batches_waiting");
        }
        return reasons;
    }
}
