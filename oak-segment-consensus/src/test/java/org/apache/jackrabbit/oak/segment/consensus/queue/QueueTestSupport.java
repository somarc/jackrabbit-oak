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

import org.apache.jackrabbit.oak.segment.consensus.service.MutationAuditMetadata;

import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Helpers shared by the proposal queue tests. */
final class QueueTestSupport {

    private QueueTestSupport() {
    }

    static boolean waitForCondition(BooleanSupplier condition, long timeoutMs, long sleepMs)
        throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(sleepMs);
        }
        return condition.getAsBoolean();
    }

    static long longStat(Map<String, Object> stats, String key) {
        Object value = stats.get(key);
        assertNotNull("Missing stat: " + key, value);
        assertTrue("Stat is not numeric: " + key, value instanceof Number);
        return ((Number) value).longValue();
    }

    /** Accepts every append. */
    static final class NoopRaftAppendCallback implements RaftAppendCallback {
        @Override
        public boolean tryAppendProposalWithId(String unusedProposalId, String walletAddress, String path,
                                               String contentType, String message, String signature,
                                               String unusedBlobId, String unusedMimeType, String unusedIpfsCid,
                                               MutationAuditMetadata unusedAudit) {
            return true;
        }

        @Override
        public boolean tryAppendDeleteProposalWithId(String unusedProposalId, String walletAddress, String path,
                                                     String signature, MutationAuditMetadata unusedAudit) {
            return true;
        }

        @Override
        public int appendProposalBatch(List<QueuedProposal> batch) {
            return batch.size();
        }
    }
}
