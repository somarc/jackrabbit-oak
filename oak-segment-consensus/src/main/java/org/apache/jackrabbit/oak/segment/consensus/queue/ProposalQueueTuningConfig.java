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

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(
    name = "Oak Proposal Queue Tuning",
    description = "Performance and backpressure tuning for the proposal queue and Aeron write flow."
)
public @interface ProposalQueueTuningConfig {

    @AttributeDefinition(
        name = "Confirmation Timeout (ms)",
        description = "Max time to wait for proposal confirmation before rejecting."
    )
    long confirmation_timeout_ms() default ProposalQueueTuning.DEFAULT_CONFIRMATION_TIMEOUT_MS;

    @AttributeDefinition(
        name = "Required Confirmations",
        description = "Minimum payment confirmations required before a verified proposal can leave the verifier stage."
    )
    int required_confirmations() default ProposalQueueTuning.DEFAULT_REQUIRED_CONFIRMATIONS;

    @AttributeDefinition(
        name = "Restore Timeout (ms)",
        description = "Timeout applied to proposals restored from persistence. Uses confirmation timeout if <= 0."
    )
    long restore_timeout_ms() default ProposalQueueTuning.DEFAULT_CONFIRMATION_TIMEOUT_MS;

    @AttributeDefinition(
        name = "Max Aeron Batches Per Cycle",
        description = "Process up to N proposal batches per Aeron sender cycle."
    )
    int max_message_batch() default ProposalQueueTuning.DEFAULT_MAX_MESSAGE_BATCH;

    @AttributeDefinition(
        name = "Max Retry Count",
        description = "Maximum retries before rejecting a proposal."
    )
    int max_retry_count() default ProposalQueueTuning.DEFAULT_MAX_RETRY_COUNT;

    @AttributeDefinition(
        name = "Finalization Chunk Size",
        description = "Max proposals per release chunk when draining verified work to Aeron (WAN-safe batching)."
    )
    int finalization_chunk_size() default ProposalQueueTuning.DEFAULT_FINALIZATION_CHUNK_SIZE;

    @AttributeDefinition(
        name = "Finalization Chunk Delay (ms)",
        description = "Optional delay between verified release chunks. Set 0 to disable artificial pacing."
    )
    long finalization_chunk_delay_ms() default ProposalQueueTuning.DEFAULT_FINALIZATION_CHUNK_DELAY_MS;

    @AttributeDefinition(
        name = "Verifier Threads",
        description = "Number of EVM verifier agent threads."
    )
    int verifier_threads() default ProposalQueueTuning.DEFAULT_VERIFIER_THREADS;

    @AttributeDefinition(
        name = "Processed Retention (ms)",
        description = "How long to retain processed proposals for metrics/visibility."
    )
    long processed_retention_ms() default ProposalQueueTuning.DEFAULT_PROCESSED_RETENTION_MS;

    @AttributeDefinition(
        name = "Persistence Enabled",
        description = "Enable proposal queue durability file persistence."
    )
    boolean persistence_enabled() default true;

    @AttributeDefinition(
        name = "Persistence Flush Interval (ms)",
        description = "Async persistence flush interval. 0 disables interval-based flush."
    )
    long persistence_flush_interval_ms() default ProposalQueueTuning.DEFAULT_PERSISTENCE_FLUSH_INTERVAL_MS;

    @AttributeDefinition(
        name = "Persistence Flush Batch",
        description = "Flush after this many pending changes. 0 disables batch-based flush."
    )
    int persistence_flush_batch() default ProposalQueueTuning.DEFAULT_PERSISTENCE_FLUSH_BATCH;

    @AttributeDefinition(
        name = "Max Pending Messages",
        description = "Maximum pending (unacknowledged) messages before backpressure applies."
    )
    long max_pending_messages() default ProposalQueueTuning.DEFAULT_MAX_PENDING_MESSAGES;

    @AttributeDefinition(
        name = "Backpressure Timeout (ms)",
        description = "Timeout while waiting for pending messages to drain."
    )
    long backpressure_timeout_ms() default ProposalQueueTuning.DEFAULT_BACKPRESSURE_TIMEOUT_MS;

    @AttributeDefinition(
        name = "Backpressure Park Nanos",
        description = "Park duration (ns) while waiting under backpressure."
    )
    long backpressure_park_nanos() default ProposalQueueTuning.DEFAULT_BACKPRESSURE_PARK_NANOS;

    @AttributeDefinition(
        name = "Counter Rotation Interval (ms)",
        description = "Rotate high-volume API counters into persisted lifetime buckets at this interval. Set 0 to disable rotation."
    )
    long counter_rotation_interval_ms() default ProposalQueueTuning.DEFAULT_COUNTER_ROTATION_INTERVAL_MS;

    @AttributeDefinition(
        name = "Release Mode",
        description = "Verified release pipeline mode. Supported values: adaptive-shadow, adaptive-active. The legacy value epoch is accepted as a deprecated alias for adaptive-active."
    )
    String release_mode() default ProposalQueueTuning.DEFAULT_RELEASE_MODE;

    @AttributeDefinition(
        name = "Priority Direct Release Enabled",
        description = "When enabled, PRIORITY proposals bypass the scheduler and release directly to Aeron after verification as a compatibility entitlement."
    )
    boolean priority_direct_release_enabled() default ProposalQueueTuning.DEFAULT_PRIORITY_DIRECT_RELEASE_ENABLED;

    @AttributeDefinition(
        name = "Validator-Hosted Binary Upload Enabled",
        description = "Enable validator-hosted binary upload handling on write proposals."
    )
    boolean validator_hosted_binary_upload_enabled() default ProposalQueueTuning.DEFAULT_VALIDATOR_HOSTED_BINARY_UPLOAD_ENABLED;

    @AttributeDefinition(
        name = "Validator Binary Requires Priority Tier",
        description = "Legacy entitlement policy. When enabled, validator-hosted binary upload requires paymentTier=priority."
    )
    boolean validator_hosted_binary_requires_priority_tier() default ProposalQueueTuning.DEFAULT_VALIDATOR_HOSTED_BINARY_REQUIRES_PRIORITY_TIER;

    @AttributeDefinition(
        name = "Payload Inline Max Bytes",
        description = "Maximum UTF-8 payload size to retain inline in memory when queue depth is healthy."
    )
    long payload_inline_max_bytes() default ProposalQueueTuning.DEFAULT_PAYLOAD_INLINE_MAX_BYTES;

    @AttributeDefinition(
        name = "Payload Spill Soft Pending Threshold",
        description = "Once pending proposal count reaches this threshold, new write payloads spill to disk-only mode."
    )
    long payload_spill_soft_pending() default ProposalQueueTuning.DEFAULT_PAYLOAD_SPILL_SOFT_PENDING;

    @AttributeDefinition(
        name = "Payload Spill Max Bytes",
        description = "Maximum total bytes allowed in the payload spill store before new writes are rejected."
    )
    long payload_spill_max_bytes() default ProposalQueueTuning.DEFAULT_PAYLOAD_SPILL_MAX_BYTES;

    @AttributeDefinition(
        name = "Hard Max Pending Proposals",
        description = "Hard limit for pending proposals across queue stages before new writes are rejected."
    )
    long hard_max_pending_proposals() default ProposalQueueTuning.DEFAULT_HARD_MAX_PENDING_PROPOSALS;

    @AttributeDefinition(
        name = "Payload Spill Directory",
        description = "Optional directory override for spilled proposal payloads. Empty uses the proposal persistence directory or an ephemeral temp directory."
    )
    String payload_spill_dir() default ProposalQueueTuning.DEFAULT_PAYLOAD_SPILL_DIR;
}
