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
        values.put("confirmation_timeout_ms", tuning.confirmationTimeoutMs());
        values.put("required_confirmations", tuning.requiredConfirmations());
        values.put("restore_timeout_ms", tuning.restoreTimeoutMs());
        values.put("max_message_batch", tuning.maxMessageBatch());
        values.put("max_retry_count", tuning.maxRetryCount());
        values.put("finalization_chunk_size", tuning.finalizationChunkSize());
        values.put("finalization_chunk_delay_ms", tuning.finalizationChunkDelayMs());
        values.put("verifier_threads", tuning.verifierThreads());
        values.put("processed_retention_ms", tuning.processedRetentionMs());
        values.put("persistence_enabled", tuning.persistenceEnabled());
        values.put("persistence_flush_interval_ms", tuning.persistenceFlushIntervalMs());
        values.put("persistence_flush_batch", tuning.persistenceFlushBatch());
        values.put("max_pending_messages", tuning.maxPendingMessages());
        values.put("backpressure_timeout_ms", tuning.backpressureTimeoutMs());
        values.put("backpressure_park_nanos", tuning.backpressureParkNanos());
        values.put("counter_rotation_interval_ms", tuning.counterRotationIntervalMs());
        values.put("release_mode", tuning.releaseMode().configValue());
        values.put("validator_hosted_binary_upload_enabled", tuning.validatorHostedBinaryUploadEnabled());
        values.put("payload_inline_max_bytes", tuning.payloadInlineMaxBytes());
        values.put("payload_spill_soft_pending", tuning.payloadSpillSoftPending());
        values.put("payload_spill_max_bytes", tuning.payloadSpillMaxBytes());
        values.put("hard_max_pending_proposals", tuning.hardMaxPendingProposals());
        values.put("payload_spill_dir", tuning.payloadSpillDir());
        return values;
    }

    public static String source() {
        return ProposalQueueTuningRegistry.getSource();
    }
}
