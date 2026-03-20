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

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component(
    service = ProposalQueueTuningService.class,
    configurationPolicy = ConfigurationPolicy.REQUIRE,
    immediate = true
)
@Designate(ocd = ProposalQueueTuningConfig.class)
public final class ProposalQueueTuningService {

    private static final Logger log = LoggerFactory.getLogger(ProposalQueueTuningService.class);

    @Activate
    protected void activate(ProposalQueueTuningConfig config) {
        ProposalQueueTuning tuning = ProposalQueueTuning.fromConfig(config);
        ProposalQueueTuningRegistry.set(tuning);
        log.info("ProposalQueueTuningService activated");
        log.info("QUEUE_TUNING_SOURCE source=osgi-config-admin persistence_enabled={} max_message_batch={} finalization_chunk_size={} finalization_chunk_delay_ms={} max_pending_messages={} backpressure_timeout_ms={} counter_rotation_interval_ms={} release_mode={}",
            tuning.isPersistenceEnabled(),
            tuning.getMaxMessageBatch(),
            tuning.getFinalizationChunkSize(),
            tuning.getFinalizationChunkDelayMs(),
            tuning.getMaxPendingMessages(),
            tuning.getBackpressureTimeoutMs(),
            tuning.getCounterRotationIntervalMs(),
            tuning.getReleaseMode().configValue());
        log.info("  maxMessageBatch: {}", tuning.getMaxMessageBatch());
        log.info("  finalizationChunkSize: {}", tuning.getFinalizationChunkSize());
        log.info("  finalizationChunkDelayMs: {}", tuning.getFinalizationChunkDelayMs());
        log.info("  maxRetryCount: {}", tuning.getMaxRetryCount());
        log.info("  verifierThreads: {}", tuning.getVerifierThreads());
        log.info("  confirmationTimeoutMs: {}", tuning.getConfirmationTimeoutMs());
        log.info("  restoreTimeoutMs: {}", tuning.getRestoreTimeoutMs());
        log.info("  processedRetentionMs: {}", tuning.getProcessedRetentionMs());
        log.info("  persistenceEnabled: {}", tuning.isPersistenceEnabled());
        log.info("  persistenceFlushIntervalMs: {}", tuning.getPersistenceFlushIntervalMs());
        log.info("  persistenceFlushBatch: {}", tuning.getPersistenceFlushBatch());
        log.info("  maxPendingMessages: {}", tuning.getMaxPendingMessages());
        log.info("  backpressureTimeoutMs: {}", tuning.getBackpressureTimeoutMs());
        log.info("  backpressureParkNanos: {}", tuning.getBackpressureParkNanos());
        log.info("  counterRotationIntervalMs: {}", tuning.getCounterRotationIntervalMs());
        log.info("  releaseMode: {}", tuning.getReleaseMode().configValue());
    }

    @Modified
    protected void modified(ProposalQueueTuningConfig config) {
        activate(config);
        log.info("ProposalQueueTuningService modified");
    }
}
