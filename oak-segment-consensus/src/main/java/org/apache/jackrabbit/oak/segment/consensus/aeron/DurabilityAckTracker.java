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

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks durability acknowledgments until an outcome is determined.
 */
final class DurabilityAckTracker {
    private final ConcurrentHashMap<String, PendingDurability> pending = new ConcurrentHashMap<>();

    void track(String proposalId, int totalMembers, int requiredAcks) {
        pending.compute(proposalId, (id, existing) -> {
            if (existing == null) {
                return new PendingDurability(totalMembers, requiredAcks);
            }
            existing.totalMembers = totalMembers;
            existing.requiredAcks = requiredAcks;
            return existing;
        });
    }

    Outcome record(String proposalId, int memberId, String durableHead, boolean success, String error,
                   int defaultTotalMembers, int defaultRequiredAcks) {
        if (memberId < 0) {
            return null;
        }
        PendingDurability current = pending.compute(proposalId, (id, existing) -> {
            PendingDurability state = existing != null ? existing : new PendingDurability(defaultTotalMembers, defaultRequiredAcks);
            if (state.completed) {
                return state;
            }
            if (success) {
                state.ackedMembers.add(memberId);
                if (durableHead != null && !durableHead.isEmpty() && state.durableHead == null) {
                    state.durableHead = durableHead;
                }
            } else {
                state.failedMembers.add(memberId);
                if (error != null && !error.isEmpty() && state.lastError == null) {
                    state.lastError = error;
                }
            }
            return state;
        });

        if (current == null || current.completed) {
            return null;
        }

        if (current.ackedMembers.size() >= current.requiredAcks) {
            current.completed = true;
            return new Outcome(true, true, current.durableHead, null, current.totalMembers, current.requiredAcks);
        }

        int maxPossibleSuccess = current.totalMembers - current.failedMembers.size();
        if (maxPossibleSuccess < current.requiredAcks) {
            current.completed = true;
            return new Outcome(true, false, current.durableHead, current.lastError, current.totalMembers, current.requiredAcks);
        }

        return new Outcome(false, false, current.durableHead, current.lastError, current.totalMembers, current.requiredAcks);
    }

    void complete(String proposalId) {
        pending.remove(proposalId);
    }

    private static final class PendingDurability {
        private volatile int totalMembers;
        private volatile int requiredAcks;
        private final Set<Integer> ackedMembers = ConcurrentHashMap.newKeySet();
        private final Set<Integer> failedMembers = ConcurrentHashMap.newKeySet();
        private volatile String durableHead;
        private volatile String lastError;
        private volatile boolean completed;

        private PendingDurability(int totalMembers, int requiredAcks) {
            this.totalMembers = totalMembers;
            this.requiredAcks = requiredAcks;
        }
    }

    static final class Outcome {
        final boolean shouldAck;
        final boolean success;
        final String durableHead;
        final String error;
        final int totalMembers;
        final int requiredAcks;

        private Outcome(boolean shouldAck, boolean success, String durableHead, String error,
                        int totalMembers, int requiredAcks) {
            this.shouldAck = shouldAck;
            this.success = success;
            this.durableHead = durableHead;
            this.error = error;
            this.totalMembers = totalMembers;
            this.requiredAcks = requiredAcks;
        }
    }
}
