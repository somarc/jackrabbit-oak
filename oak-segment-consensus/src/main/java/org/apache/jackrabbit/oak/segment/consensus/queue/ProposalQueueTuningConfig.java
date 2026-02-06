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
        description = "Max proposals per chunk when finalizing an epoch (WAN-safe batching)."
    )
    int finalization_chunk_size() default ProposalQueueTuning.DEFAULT_FINALIZATION_CHUNK_SIZE;

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
}
