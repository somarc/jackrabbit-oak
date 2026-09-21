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
package org.apache.jackrabbit.oak.segment.http.server;

import org.apache.jackrabbit.oak.segment.consensus.security.JoinProof;
import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

final class JoinProofFactory {

    private static final Logger log = LoggerFactory.getLogger(JoinProofFactory.class);

    private final FileStore fileStore;
    private final ServerContext context;
    private final LongSupplier clock;

    JoinProofFactory(FileStore fileStore, ServerContext context, LongSupplier clock) {
        this.fileStore = fileStore;
        this.context = context;
        this.clock = clock;
    }

    JoinProof create(String validatorId, String validatorUrl) {
        JoinProof proof = new JoinProof();

        try {
            String currentHead = fileStore.getHead().getRecordId().toString();
            long now = clock.getAsLong();
            proof.setProofGeneratedAt(now);
            proof.setHeadSegmentId(currentHead);
            proof.setHeadCapturedAt(now);

            if (context.aeronConsensusEngine != null) {
                proof.setCurrentEpoch(context.aeronConsensusEngine.getCurrentEpoch());
                proof.setEpochCalculatedAt(now);
            }

            String genesisSegmentId = getOrInitializeGenesis(currentHead);
            String genesisHash = computeGenesisHash(genesisSegmentId);
            proof.setGenesisSegmentId(genesisSegmentId);
            proof.setGenesisHash(genesisHash);

            proof.setValidatorId(validatorId);
            proof.setValidatorUrl(validatorUrl);
            String nonce = generateSecureNonce();
            proof.setChallengeNonce(nonce);
            proof.setNonceSignature(signNonce(nonce, validatorId));

            List<String> sampleIds = new ArrayList<>();
            List<String> sampleHashes = new ArrayList<>();
            sampleIds.add(currentHead);
            sampleHashes.add(computeSegmentHash(currentHead));
            if (!currentHead.equals(genesisSegmentId)) {
                sampleIds.add(genesisSegmentId);
                sampleHashes.add(genesisHash);
            }
            proof.setSampleSegmentIds(sampleIds);
            proof.setSampleSegmentHashes(sampleHashes);

            log.info("✅ Generated Join Proof:");
            log.info("   HEAD: {}", currentHead.substring(0, Math.min(24, currentHead.length())));
            log.info("   Genesis: {}", genesisSegmentId.substring(0, Math.min(24, genesisSegmentId.length())));
            log.info("   Genesis Hash: {}", genesisHash.substring(0, Math.min(16, genesisHash.length())) + "...");
            log.info("   Epoch: {}", proof.getCurrentEpoch());
            log.info("   Samples: {}", sampleIds.size());
        } catch (Exception e) {
            log.error("❌ Failed to generate join proof", e);
        }

        return proof;
    }

    String getOrInitializeGenesis(String currentHead) {
        if (context.genesisSegmentId != null) {
            return context.genesisSegmentId;
        }

        synchronized (context) {
            if (context.genesisSegmentId == null) {
                context.genesisSegmentId = currentHead;
                context.genesisTimestamp = clock.getAsLong();
                context.genesisHash = computeGenesisHash(currentHead);
                log.info("🌱 Initialized genesis: {}", currentHead.substring(0, Math.min(24, currentHead.length())));
            }
        }

        return context.genesisSegmentId;
    }

    String computeGenesisHash(String segmentId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(segmentId.getBytes(StandardCharsets.UTF_8));
            long timestamp = context.genesisTimestamp > 0 ? context.genesisTimestamp : clock.getAsLong();
            digest.update(Long.toString(timestamp).getBytes(StandardCharsets.UTF_8));
            if (context.selfUrl != null) {
                digest.update(context.selfUrl.getBytes(StandardCharsets.UTF_8));
            }
            return bytesToHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            log.error("SHA-256 not available", e);
            return "sha256-unavailable";
        }
    }

    String computeSegmentHash(String segmentId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(segmentId.getBytes(StandardCharsets.UTF_8));
            digest.update(Long.toString(clock.getAsLong()).getBytes(StandardCharsets.UTF_8));
            return bytesToHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            log.error("SHA-256 not available", e);
            return "sha256-unavailable";
        }
    }

    String generateSecureNonce() {
        try {
            SecureRandom random = new SecureRandom();
            byte[] nonceBytes = new byte[32];
            random.nextBytes(nonceBytes);
            return bytesToHex(nonceBytes);
        } catch (Exception e) {
            log.warn("Failed to generate secure nonce, using fallback", e);
            return "nonce-" + clock.getAsLong() + "-" + Math.random();
        }
    }

    String signNonce(String nonce, String validatorId) {
        try {
            javax.crypto.Mac hmac = javax.crypto.Mac.getInstance("HmacSHA256");
            javax.crypto.spec.SecretKeySpec keySpec = new javax.crypto.spec.SecretKeySpec(
                validatorId.getBytes(StandardCharsets.UTF_8),
                "HmacSHA256"
            );
            hmac.init(keySpec);
            return bytesToHex(hmac.doFinal(nonce.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            log.warn("Failed to sign nonce, using fallback", e);
            return "sig-" + nonce.hashCode();
        }
    }

    static String bytesToHex(byte[] bytes) {
        StringBuilder hex = new StringBuilder();
        for (byte b : bytes) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
}
