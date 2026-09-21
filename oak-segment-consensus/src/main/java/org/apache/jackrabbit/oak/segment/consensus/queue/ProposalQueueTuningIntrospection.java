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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Read-only introspection surface for effective queue tuning values.
 */
public final class ProposalQueueTuningIntrospection {

    private ProposalQueueTuningIntrospection() {
    }

    public static Map<String, Object> effectiveValues() {
        ProposalQueueTuning tuning = ProposalQueueTuningRegistry.get();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("confirmation_timeout_ms", tuning.getConfirmationTimeoutMs());
        values.put("required_confirmations", tuning.getRequiredConfirmations());
        values.put("restore_timeout_ms", tuning.getRestoreTimeoutMs());
        values.put("max_message_batch", tuning.getMaxMessageBatch());
        values.put("max_retry_count", tuning.getMaxRetryCount());
        values.put("finalization_chunk_size", tuning.getFinalizationChunkSize());
        values.put("finalization_chunk_delay_ms", tuning.getFinalizationChunkDelayMs());
        values.put("verifier_threads", tuning.getVerifierThreads());
        values.put("processed_retention_ms", tuning.getProcessedRetentionMs());
        values.put("persistence_enabled", tuning.isPersistenceEnabled());
        values.put("persistence_flush_interval_ms", tuning.getPersistenceFlushIntervalMs());
        values.put("persistence_flush_batch", tuning.getPersistenceFlushBatch());
        values.put("max_pending_messages", tuning.getMaxPendingMessages());
        values.put("backpressure_timeout_ms", tuning.getBackpressureTimeoutMs());
        values.put("backpressure_park_nanos", tuning.getBackpressureParkNanos());
        values.put("counter_rotation_interval_ms", tuning.getCounterRotationIntervalMs());
        values.put("release_mode", tuning.getReleaseMode().configValue());
        values.put("validator_hosted_binary_upload_enabled", tuning.isValidatorHostedBinaryUploadEnabled());
        values.put("payload_inline_max_bytes", tuning.getPayloadInlineMaxBytes());
        values.put("payload_spill_soft_pending", tuning.getPayloadSpillSoftPending());
        values.put("payload_spill_max_bytes", tuning.getPayloadSpillMaxBytes());
        values.put("hard_max_pending_proposals", tuning.getHardMaxPendingProposals());
        values.put("payload_spill_dir", tuning.getPayloadSpillDir());
        return values;
    }

    public static String source() {
        return ProposalQueueTuningRegistry.getSource();
    }
}
