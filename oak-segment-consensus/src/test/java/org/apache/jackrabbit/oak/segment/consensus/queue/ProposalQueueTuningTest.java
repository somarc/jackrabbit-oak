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

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ProposalQueueTuningTest {

    @After
    public void clearProps() {
        System.clearProperty("oak.proposal.persistence.enabled");
        System.clearProperty("oak.proposal.persistence.flush.ms");
        System.clearProperty("oak.proposal.persistence.flush.batch");
        System.clearProperty("oak.proposal.release.mode");
        System.clearProperty("oak.proposal.confirmation.required");
        System.clearProperty("oak.proposal.validator.binary.upload.enabled");
        System.clearProperty("oak.proposal.payload.inline.max.bytes");
        System.clearProperty("oak.proposal.payload.spill.soft.pending");
        System.clearProperty("oak.proposal.payload.spill.max.bytes");
        System.clearProperty("oak.proposal.hard.max.pending");
        System.clearProperty("oak.proposal.payload.spill.dir");
    }

    @Test
    public void testPersistenceDefaultsFromSystemProperties() {
        ProposalQueueTuning tuning = ProposalQueueTuning.fromSystemProperties();

        assertTrue("Persistence should default to enabled", tuning.persistenceEnabled());
        assertEquals("Default flush interval should match constant",
            ProposalQueueTuning.DEFAULT_PERSISTENCE_FLUSH_INTERVAL_MS,
            tuning.persistenceFlushIntervalMs());
        assertEquals("Default flush batch should match constant",
            ProposalQueueTuning.DEFAULT_PERSISTENCE_FLUSH_BATCH,
            tuning.persistenceFlushBatch());
    }

    @Test
    public void testPersistenceOverridesFromSystemProperties() {
        System.setProperty("oak.proposal.persistence.enabled", "false");
        System.setProperty("oak.proposal.persistence.flush.ms", "750");
        System.setProperty("oak.proposal.persistence.flush.batch", "400");

        ProposalQueueTuning tuning = ProposalQueueTuning.fromSystemProperties();

        assertFalse("Persistence flag should be configurable", tuning.persistenceEnabled());
        assertEquals("Flush interval override should apply", 750L, tuning.persistenceFlushIntervalMs());
        assertEquals("Flush batch override should apply", 400, tuning.persistenceFlushBatch());
    }

    @Test
    public void testReleaseModeDefaultsToAdaptiveActive() {
        ProposalQueueTuning tuning = ProposalQueueTuning.fromSystemProperties();

        assertEquals(AdaptiveReleaseMode.ADAPTIVE_ACTIVE, tuning.releaseMode());
    }

    @Test
    public void testRequiredConfirmationsDefaultsToOne() {
        ProposalQueueTuning tuning = ProposalQueueTuning.fromSystemProperties();

        assertEquals(1, tuning.requiredConfirmations());
    }

    @Test
    public void testRequiredConfirmationsOverrideApplies() {
        System.setProperty("oak.proposal.confirmation.required", "3");

        ProposalQueueTuning tuning = ProposalQueueTuning.fromSystemProperties();

        assertEquals(3, tuning.requiredConfirmations());
    }

    @Test
    public void testReleaseModeOverrideParsesShadowMode() {
        System.setProperty("oak.proposal.release.mode", "adaptive-shadow");

        ProposalQueueTuning tuning = ProposalQueueTuning.fromSystemProperties();

        assertEquals(AdaptiveReleaseMode.ADAPTIVE_SHADOW, tuning.releaseMode());
    }

    @Test
    public void testReleaseModeOverrideParsesActiveModeAlias() {
        System.setProperty("oak.proposal.release.mode", "active");

        ProposalQueueTuning tuning = ProposalQueueTuning.fromSystemProperties();

        assertEquals(AdaptiveReleaseMode.ADAPTIVE_ACTIVE, tuning.releaseMode());
    }

    @Test
    public void testReleaseModeEpochAliasFallsBackToAdaptiveActive() {
        System.setProperty("oak.proposal.release.mode", "epoch");

        ProposalQueueTuning tuning = ProposalQueueTuning.fromSystemProperties();

        assertEquals(AdaptiveReleaseMode.ADAPTIVE_ACTIVE, tuning.releaseMode());
    }

    @Test
    public void testInvalidReleaseModeFallsBackToAdaptiveActive() {
        System.setProperty("oak.proposal.release.mode", "unknown-mode");

        ProposalQueueTuning tuning = ProposalQueueTuning.fromSystemProperties();

        assertEquals(AdaptiveReleaseMode.ADAPTIVE_ACTIVE, tuning.releaseMode());
    }

    @Test
    public void testValidatorHostedBinaryUploadDefaultsEnabled() {
        ProposalQueueTuning tuning = ProposalQueueTuning.fromSystemProperties();

        assertTrue(tuning.validatorHostedBinaryUploadEnabled());
    }

    @Test
    public void testValidatorHostedBinaryUploadOverrideApplies() {
        System.setProperty("oak.proposal.validator.binary.upload.enabled", "false");

        ProposalQueueTuning tuning = ProposalQueueTuning.fromSystemProperties();

        assertFalse(tuning.validatorHostedBinaryUploadEnabled());
    }

    @Test
    public void testPayloadSpillDefaultsApply() {
        ProposalQueueTuning tuning = ProposalQueueTuning.fromSystemProperties();

        assertEquals(ProposalQueueTuning.DEFAULT_PAYLOAD_INLINE_MAX_BYTES, tuning.payloadInlineMaxBytes());
        assertEquals(ProposalQueueTuning.DEFAULT_PAYLOAD_SPILL_SOFT_PENDING, tuning.payloadSpillSoftPending());
        assertEquals(ProposalQueueTuning.DEFAULT_PAYLOAD_SPILL_MAX_BYTES, tuning.payloadSpillMaxBytes());
        assertEquals(ProposalQueueTuning.DEFAULT_HARD_MAX_PENDING_PROPOSALS, tuning.hardMaxPendingProposals());
        assertEquals(ProposalQueueTuning.DEFAULT_PAYLOAD_SPILL_DIR, tuning.payloadSpillDir());
    }

    @Test
    public void testPayloadSpillOverridesApply() {
        System.setProperty("oak.proposal.payload.inline.max.bytes", "64");
        System.setProperty("oak.proposal.payload.spill.soft.pending", "32");
        System.setProperty("oak.proposal.payload.spill.max.bytes", "2048");
        System.setProperty("oak.proposal.hard.max.pending", "48");
        System.setProperty("oak.proposal.payload.spill.dir", "/tmp/oak-spill");

        ProposalQueueTuning tuning = ProposalQueueTuning.fromSystemProperties();

        assertEquals(64L, tuning.payloadInlineMaxBytes());
        assertEquals(32L, tuning.payloadSpillSoftPending());
        assertEquals(2048L, tuning.payloadSpillMaxBytes());
        assertEquals(48L, tuning.hardMaxPendingProposals());
        assertEquals("/tmp/oak-spill", tuning.payloadSpillDir());
    }
}
