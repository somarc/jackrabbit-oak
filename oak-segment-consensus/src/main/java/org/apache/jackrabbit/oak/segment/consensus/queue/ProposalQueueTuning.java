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

final class ProposalQueueTuning {

    static final long DEFAULT_CONFIRMATION_TIMEOUT_MS = 300_000;
    static final int DEFAULT_MAX_MESSAGE_BATCH = 10;
    static final int DEFAULT_MAX_RETRY_COUNT = 5;
    static final int DEFAULT_FINALIZATION_CHUNK_SIZE = 3;
    static final int DEFAULT_VERIFIER_THREADS = 1;
    static final long DEFAULT_PROCESSED_RETENTION_MS = 10 * 60 * 1000L;
    static final long DEFAULT_PERSISTENCE_FLUSH_INTERVAL_MS = 250L;
    static final int DEFAULT_PERSISTENCE_FLUSH_BATCH = 100;
    static final long DEFAULT_MAX_PENDING_MESSAGES = 10_000L;
    static final long DEFAULT_BACKPRESSURE_TIMEOUT_MS = 30_000L;
    static final long DEFAULT_BACKPRESSURE_PARK_NANOS = 1_000_000L;

    private final long confirmationTimeoutMs;
    private final long restoreTimeoutMs;
    private final int maxMessageBatch;
    private final int maxRetryCount;
    private final int finalizationChunkSize;
    private final int verifierThreads;
    private final long processedRetentionMs;
    private final long persistenceFlushIntervalMs;
    private final int persistenceFlushBatch;
    private final long maxPendingMessages;
    private final long backpressureTimeoutMs;
    private final long backpressureParkNanos;

    private ProposalQueueTuning(long confirmationTimeoutMs,
                                long restoreTimeoutMs,
                                int maxMessageBatch,
                                int maxRetryCount,
                                int finalizationChunkSize,
                                int verifierThreads,
                                long processedRetentionMs,
                                long persistenceFlushIntervalMs,
                                int persistenceFlushBatch,
                                long maxPendingMessages,
                                long backpressureTimeoutMs,
                                long backpressureParkNanos) {
        this.confirmationTimeoutMs = confirmationTimeoutMs;
        this.restoreTimeoutMs = restoreTimeoutMs;
        this.maxMessageBatch = maxMessageBatch;
        this.maxRetryCount = maxRetryCount;
        this.finalizationChunkSize = finalizationChunkSize;
        this.verifierThreads = verifierThreads;
        this.processedRetentionMs = processedRetentionMs;
        this.persistenceFlushIntervalMs = persistenceFlushIntervalMs;
        this.persistenceFlushBatch = persistenceFlushBatch;
        this.maxPendingMessages = maxPendingMessages;
        this.backpressureTimeoutMs = backpressureTimeoutMs;
        this.backpressureParkNanos = backpressureParkNanos;
    }

    static ProposalQueueTuning fromSystemProperties() {
        long confirmationTimeoutMs = Long.getLong(
            "oak.proposal.confirmation.timeout.ms",
            DEFAULT_CONFIRMATION_TIMEOUT_MS
        );
        long restoreTimeoutMs = Long.getLong(
            "oak.proposal.restore.timeout.ms",
            confirmationTimeoutMs
        );
        int maxMessageBatch = readIntProp("oak.proposal.batch.max", DEFAULT_MAX_MESSAGE_BATCH, 1);
        int maxRetryCount = readIntProp("oak.proposal.max.retry.count", DEFAULT_MAX_RETRY_COUNT, 1);
        int finalizationChunkSize = readIntProp("oak.proposal.finalization.chunk.size", DEFAULT_FINALIZATION_CHUNK_SIZE, 1);
        int verifierThreads = readIntProp("oak.proposal.verifier.threads", DEFAULT_VERIFIER_THREADS, 1);
        long processedRetentionMs = Long.getLong(
            "oak.proposal.processed.retention.ms",
            DEFAULT_PROCESSED_RETENTION_MS
        );
        long persistenceFlushIntervalMs = Long.getLong(
            "oak.proposal.persistence.flush.ms",
            DEFAULT_PERSISTENCE_FLUSH_INTERVAL_MS
        );
        int persistenceFlushBatch = readIntProp(
            "oak.proposal.persistence.flush.batch",
            DEFAULT_PERSISTENCE_FLUSH_BATCH,
            0
        );
        long maxPendingMessages = Long.getLong(
            "oak.consensus.max.pending.messages",
            DEFAULT_MAX_PENDING_MESSAGES
        );
        long backpressureTimeoutMs = Long.getLong(
            "oak.consensus.backpressure.timeout.ms",
            DEFAULT_BACKPRESSURE_TIMEOUT_MS
        );
        long backpressureParkNanos = Long.getLong(
            "oak.consensus.backpressure.park.nanos",
            DEFAULT_BACKPRESSURE_PARK_NANOS
        );
        return new ProposalQueueTuning(
            confirmationTimeoutMs,
            restoreTimeoutMs,
            maxMessageBatch,
            maxRetryCount,
            finalizationChunkSize,
            verifierThreads,
            processedRetentionMs,
            persistenceFlushIntervalMs,
            persistenceFlushBatch,
            maxPendingMessages,
            backpressureTimeoutMs,
            backpressureParkNanos
        );
    }

    static ProposalQueueTuning fromConfig(ProposalQueueTuningConfig config) {
        long confirmationTimeoutMs = config.confirmation_timeout_ms();
        long restoreTimeoutMs = config.restore_timeout_ms() > 0
            ? config.restore_timeout_ms()
            : confirmationTimeoutMs;
        return new ProposalQueueTuning(
            confirmationTimeoutMs,
            restoreTimeoutMs,
            config.max_message_batch(),
            config.max_retry_count(),
            config.finalization_chunk_size(),
            config.verifier_threads(),
            config.processed_retention_ms(),
            config.persistence_flush_interval_ms(),
            config.persistence_flush_batch(),
            config.max_pending_messages(),
            config.backpressure_timeout_ms(),
            config.backpressure_park_nanos()
        );
    }

    long getConfirmationTimeoutMs() {
        return confirmationTimeoutMs;
    }

    long getRestoreTimeoutMs() {
        return restoreTimeoutMs;
    }

    int getMaxMessageBatch() {
        return maxMessageBatch;
    }

    int getMaxRetryCount() {
        return maxRetryCount;
    }

    int getFinalizationChunkSize() {
        return finalizationChunkSize;
    }

    int getVerifierThreads() {
        return verifierThreads;
    }

    long getProcessedRetentionMs() {
        return processedRetentionMs;
    }

    long getPersistenceFlushIntervalMs() {
        return persistenceFlushIntervalMs;
    }

    int getPersistenceFlushBatch() {
        return persistenceFlushBatch;
    }

    long getMaxPendingMessages() {
        return maxPendingMessages;
    }

    long getBackpressureTimeoutMs() {
        return backpressureTimeoutMs;
    }

    long getBackpressureParkNanos() {
        return backpressureParkNanos;
    }

    private static int readIntProp(String key, int defaultValue, int minValue) {
        int value = Integer.getInteger(key, defaultValue);
        if (value < minValue) {
            return minValue;
        }
        return value;
    }
}
