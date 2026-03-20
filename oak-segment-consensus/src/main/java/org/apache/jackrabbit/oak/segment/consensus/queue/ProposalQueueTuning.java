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
    static final int DEFAULT_REQUIRED_CONFIRMATIONS = 1;
    static final int DEFAULT_MAX_MESSAGE_BATCH = 10;
    static final int DEFAULT_MAX_RETRY_COUNT = 5;
    static final int DEFAULT_FINALIZATION_CHUNK_SIZE = 3;
    static final long DEFAULT_FINALIZATION_CHUNK_DELAY_MS = 0L;
    static final int DEFAULT_VERIFIER_THREADS = 1;
    static final long DEFAULT_PROCESSED_RETENTION_MS = 10 * 60 * 1000L;
    static final boolean DEFAULT_PERSISTENCE_ENABLED = true;
    static final long DEFAULT_PERSISTENCE_FLUSH_INTERVAL_MS = 250L;
    static final int DEFAULT_PERSISTENCE_FLUSH_BATCH = 100;
    static final long DEFAULT_MAX_PENDING_MESSAGES = 10_000L;
    static final long DEFAULT_BACKPRESSURE_TIMEOUT_MS = 30_000L;
    static final long DEFAULT_BACKPRESSURE_PARK_NANOS = 1_000_000L;
    static final long DEFAULT_COUNTER_ROTATION_INTERVAL_MS = 24L * 60L * 60L * 1000L;
    static final String DEFAULT_RELEASE_MODE = "epoch";
    static final boolean DEFAULT_PRIORITY_DIRECT_RELEASE_ENABLED = true;
    static final boolean DEFAULT_VALIDATOR_HOSTED_BINARY_UPLOAD_ENABLED = true;
    static final boolean DEFAULT_VALIDATOR_HOSTED_BINARY_REQUIRES_PRIORITY_TIER = true;

    private final long confirmationTimeoutMs;
    private final int requiredConfirmations;
    private final long restoreTimeoutMs;
    private final int maxMessageBatch;
    private final int maxRetryCount;
    private final int finalizationChunkSize;
    private final long finalizationChunkDelayMs;
    private final int verifierThreads;
    private final long processedRetentionMs;
    private final boolean persistenceEnabled;
    private final long persistenceFlushIntervalMs;
    private final int persistenceFlushBatch;
    private final long maxPendingMessages;
    private final long backpressureTimeoutMs;
    private final long backpressureParkNanos;
    private final long counterRotationIntervalMs;
    private final AdaptiveReleaseMode releaseMode;
    private final boolean priorityDirectReleaseEnabled;
    private final boolean validatorHostedBinaryUploadEnabled;
    private final boolean validatorHostedBinaryRequiresPriorityTier;

    private ProposalQueueTuning(long confirmationTimeoutMs,
                                int requiredConfirmations,
                                long restoreTimeoutMs,
                                int maxMessageBatch,
                                int maxRetryCount,
                                int finalizationChunkSize,
                                long finalizationChunkDelayMs,
                                int verifierThreads,
                                long processedRetentionMs,
                                boolean persistenceEnabled,
                                long persistenceFlushIntervalMs,
                                int persistenceFlushBatch,
                                long maxPendingMessages,
                                long backpressureTimeoutMs,
                                long backpressureParkNanos,
                                long counterRotationIntervalMs,
                                AdaptiveReleaseMode releaseMode,
                                boolean priorityDirectReleaseEnabled,
                                boolean validatorHostedBinaryUploadEnabled,
                                boolean validatorHostedBinaryRequiresPriorityTier) {
        this.confirmationTimeoutMs = confirmationTimeoutMs;
        this.requiredConfirmations = requiredConfirmations;
        this.restoreTimeoutMs = restoreTimeoutMs;
        this.maxMessageBatch = maxMessageBatch;
        this.maxRetryCount = maxRetryCount;
        this.finalizationChunkSize = finalizationChunkSize;
        this.finalizationChunkDelayMs = finalizationChunkDelayMs;
        this.verifierThreads = verifierThreads;
        this.processedRetentionMs = processedRetentionMs;
        this.persistenceEnabled = persistenceEnabled;
        this.persistenceFlushIntervalMs = persistenceFlushIntervalMs;
        this.persistenceFlushBatch = persistenceFlushBatch;
        this.maxPendingMessages = maxPendingMessages;
        this.backpressureTimeoutMs = backpressureTimeoutMs;
        this.backpressureParkNanos = backpressureParkNanos;
        this.counterRotationIntervalMs = counterRotationIntervalMs;
        this.releaseMode = releaseMode != null ? releaseMode : AdaptiveReleaseMode.EPOCH;
        this.priorityDirectReleaseEnabled = priorityDirectReleaseEnabled;
        this.validatorHostedBinaryUploadEnabled = validatorHostedBinaryUploadEnabled;
        this.validatorHostedBinaryRequiresPriorityTier = validatorHostedBinaryRequiresPriorityTier;
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
        int requiredConfirmations = readIntProp(
            "oak.proposal.confirmation.required",
            DEFAULT_REQUIRED_CONFIRMATIONS,
            1
        );
        int maxMessageBatch = readIntProp("oak.proposal.batch.max", DEFAULT_MAX_MESSAGE_BATCH, 1);
        int maxRetryCount = readIntProp("oak.proposal.max.retry.count", DEFAULT_MAX_RETRY_COUNT, 1);
        int finalizationChunkSize = readIntProp("oak.proposal.finalization.chunk.size", DEFAULT_FINALIZATION_CHUNK_SIZE, 1);
        long finalizationChunkDelayMs = Long.getLong(
            "oak.proposal.finalization.chunk.delay.ms",
            DEFAULT_FINALIZATION_CHUNK_DELAY_MS
        );
        int verifierThreads = readIntProp(
            "oak.proposal.verifier.threads",
            defaultVerifierThreads(),
            1
        );
        long processedRetentionMs = Long.getLong(
            "oak.proposal.processed.retention.ms",
            DEFAULT_PROCESSED_RETENTION_MS
        );
        boolean persistenceEnabled = Boolean.parseBoolean(System.getProperty(
            "oak.proposal.persistence.enabled",
            String.valueOf(DEFAULT_PERSISTENCE_ENABLED)
        ));
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
        long counterRotationIntervalMs = Long.getLong(
            "oak.proposal.counter.rotation.ms",
            DEFAULT_COUNTER_ROTATION_INTERVAL_MS
        );
        AdaptiveReleaseMode releaseMode = AdaptiveReleaseMode.fromValue(
            System.getProperty("oak.proposal.release.mode", DEFAULT_RELEASE_MODE)
        );
        boolean priorityDirectReleaseEnabled = Boolean.parseBoolean(System.getProperty(
            "oak.proposal.priority.direct.release.enabled",
            String.valueOf(DEFAULT_PRIORITY_DIRECT_RELEASE_ENABLED)
        ));
        boolean validatorHostedBinaryUploadEnabled = Boolean.parseBoolean(System.getProperty(
            "oak.proposal.validator.binary.upload.enabled",
            String.valueOf(DEFAULT_VALIDATOR_HOSTED_BINARY_UPLOAD_ENABLED)
        ));
        boolean validatorHostedBinaryRequiresPriorityTier = Boolean.parseBoolean(System.getProperty(
            "oak.proposal.validator.binary.requires.priority",
            String.valueOf(DEFAULT_VALIDATOR_HOSTED_BINARY_REQUIRES_PRIORITY_TIER)
        ));
        return new ProposalQueueTuning(
            confirmationTimeoutMs,
            requiredConfirmations,
            restoreTimeoutMs,
            maxMessageBatch,
            maxRetryCount,
            finalizationChunkSize,
            clampLong(finalizationChunkDelayMs, 0L),
            verifierThreads,
            processedRetentionMs,
            persistenceEnabled,
            persistenceFlushIntervalMs,
            persistenceFlushBatch,
            maxPendingMessages,
            backpressureTimeoutMs,
            backpressureParkNanos,
            counterRotationIntervalMs,
            releaseMode,
            priorityDirectReleaseEnabled,
            validatorHostedBinaryUploadEnabled,
            validatorHostedBinaryRequiresPriorityTier
        );
    }

    static ProposalQueueTuning fromConfig(ProposalQueueTuningConfig config) {
        long confirmationTimeoutMs = Math.max(1L, config.confirmation_timeout_ms());
        int requiredConfirmations = clampInt(config.required_confirmations(), 1);
        long restoreTimeoutMs = config.restore_timeout_ms() > 0
            ? config.restore_timeout_ms()
            : confirmationTimeoutMs;
        restoreTimeoutMs = Math.max(1L, restoreTimeoutMs);
        int configuredVerifierThreads = config.verifier_threads();
        int effectiveVerifierThreads = configuredVerifierThreads == DEFAULT_VERIFIER_THREADS
            ? defaultVerifierThreads()
            : configuredVerifierThreads;
        return new ProposalQueueTuning(
            confirmationTimeoutMs,
            requiredConfirmations,
            restoreTimeoutMs,
            clampInt(config.max_message_batch(), 1),
            config.max_retry_count(),
            clampInt(config.finalization_chunk_size(), 1),
            clampLong(config.finalization_chunk_delay_ms(), 0L),
            effectiveVerifierThreads,
            config.processed_retention_ms(),
            config.persistence_enabled(),
            config.persistence_flush_interval_ms(),
            config.persistence_flush_batch(),
            clampLong(config.max_pending_messages(), 1L),
            clampLong(config.backpressure_timeout_ms(), 1L),
            config.backpressure_park_nanos(),
            clampLong(config.counter_rotation_interval_ms(), 0L),
            AdaptiveReleaseMode.fromValue(config.release_mode()),
            config.priority_direct_release_enabled(),
            config.validator_hosted_binary_upload_enabled(),
            config.validator_hosted_binary_requires_priority_tier()
        );
    }

    long getConfirmationTimeoutMs() {
        return confirmationTimeoutMs;
    }

    int getRequiredConfirmations() {
        return requiredConfirmations;
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

    long getFinalizationChunkDelayMs() {
        return finalizationChunkDelayMs;
    }

    int getVerifierThreads() {
        return verifierThreads;
    }

    long getProcessedRetentionMs() {
        return processedRetentionMs;
    }

    boolean isPersistenceEnabled() {
        return persistenceEnabled;
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

    long getCounterRotationIntervalMs() {
        return counterRotationIntervalMs;
    }

    AdaptiveReleaseMode getReleaseMode() {
        return releaseMode;
    }

    boolean isPriorityDirectReleaseEnabled() {
        return priorityDirectReleaseEnabled;
    }

    boolean isValidatorHostedBinaryUploadEnabled() {
        return validatorHostedBinaryUploadEnabled;
    }

    boolean isValidatorHostedBinaryRequiresPriorityTier() {
        return validatorHostedBinaryRequiresPriorityTier;
    }

    private static int readIntProp(String key, int defaultValue, int minValue) {
        int value = Integer.getInteger(key, defaultValue);
        return clampInt(value, minValue);
    }

    private static int clampInt(int value, int minValue) {
        if (value < minValue) {
            return minValue;
        }
        return value;
    }

    private static long clampLong(long value, long minValue) {
        if (value < minValue) {
            return minValue;
        }
        return value;
    }

    private static int defaultVerifierThreads() {
        int cores = Runtime.getRuntime().availableProcessors();
        int recommended = Math.max(DEFAULT_VERIFIER_THREADS, Math.min(4, Math.max(1, cores / 2)));
        return recommended;
    }
}
