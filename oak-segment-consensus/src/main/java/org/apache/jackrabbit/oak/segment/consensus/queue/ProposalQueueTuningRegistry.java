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

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class ProposalQueueTuningRegistry {

    private static final Logger log = LoggerFactory.getLogger(ProposalQueueTuningRegistry.class);
    private static final AtomicReference<ProposalQueueTuning> OVERRIDE = new AtomicReference<>();
    private static final AtomicBoolean FALLBACK_WARNED = new AtomicBoolean(false);
    private static final AtomicReference<String> SOURCE = new AtomicReference<>("system-properties");

    private ProposalQueueTuningRegistry() {
        // utility
    }

    static ProposalQueueTuning get() {
        ProposalQueueTuning tuning = OVERRIDE.get();
        if (tuning != null) {
            return tuning;
        }
        ProposalQueueTuning fallback = ProposalQueueTuning.fromSystemProperties();
        if (FALLBACK_WARNED.compareAndSet(false, true)) {
            log.warn("QUEUE_TUNING_SOURCE source=system-properties persistence_enabled={} max_message_batch={} finalization_chunk_size={} finalization_chunk_delay_ms={} max_pending_messages={} backpressure_timeout_ms={} counter_rotation_interval_ms={} release_mode={}",
                fallback.isPersistenceEnabled(),
                fallback.getMaxMessageBatch(),
                fallback.getFinalizationChunkSize(),
                fallback.getFinalizationChunkDelayMs(),
                fallback.getMaxPendingMessages(),
                fallback.getBackpressureTimeoutMs(),
                fallback.getCounterRotationIntervalMs(),
                fallback.getReleaseMode().configValue());
        }
        return fallback;
    }

    static void set(ProposalQueueTuning tuning) {
        if (tuning != null) {
            OVERRIDE.set(tuning);
            SOURCE.set("osgi-config-admin");
        }
    }

    static String getSource() {
        return SOURCE.get();
    }
}
