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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ConnectException;
import java.util.List;

final class PeerAnnouncementClient {

    interface UrlResolver {
        String resolve(String url);
    }

    interface Poster {
        PeerJsonHttpClient.PostResult postJson(String targetUrl, String jsonPayload) throws IOException;
    }

    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    static final class BroadcastSummary {
        private final int successCount;
        private final int failureCount;

        BroadcastSummary(int successCount, int failureCount) {
            this.successCount = successCount;
            this.failureCount = failureCount;
        }

        int getSuccessCount() {
            return successCount;
        }

        int getFailureCount() {
            return failureCount;
        }
    }

    private static final Logger log = LoggerFactory.getLogger(PeerAnnouncementClient.class);
    private static final int MAX_REGISTRATION_RETRIES = 5;
    private static final int INITIAL_RETRY_DELAY_MS = 2000;
    private static final int MAX_RETRY_DELAY_MS = 16000;

    private final UrlResolver urlResolver;
    private final Poster poster;
    private final Sleeper sleeper;

    PeerAnnouncementClient(UrlResolver urlResolver, Poster poster, Sleeper sleeper) {
        this.urlResolver = urlResolver;
        this.poster = poster;
        this.sleeper = sleeper;
    }

    void registerWithPeers(String validatorId, String selfUrl, List<String> peerUrls) {
        log.info("📡 Registering with {} peer validators (with retry logic)...", peerUrls.size());

        for (String peerUrl : peerUrls) {
            if (peerUrl.equals(selfUrl)) {
                continue;
            }

            String peerUrlIP = urlResolver.resolve(peerUrl);
            boolean registered = false;
            for (int attempt = 1; attempt <= MAX_REGISTRATION_RETRIES; attempt++) {
                try {
                    String registrationUrl = peerUrlIP + "/v1/register-validator";
                    String jsonPayload = buildRegistrationPayload(validatorId, selfUrl);
                    int responseCode = poster.postJson(registrationUrl, jsonPayload).getResponseCode();
                    if (responseCode == 200) {
                        log.info("✅ Registered with peer validator: {} → {} (attempt {}/{})",
                            peerUrl, peerUrlIP, attempt, MAX_REGISTRATION_RETRIES);
                        registered = true;
                        break;
                    }
                    log.debug("⚠️  Registration attempt {}/{} failed for {}: HTTP {}",
                        attempt, MAX_REGISTRATION_RETRIES, peerUrlIP, responseCode);
                } catch (Exception e) {
                    log.debug("⚠️  Registration attempt {}/{} failed for {}: {}",
                        attempt, MAX_REGISTRATION_RETRIES, peerUrlIP, e.getMessage());
                }

                if (attempt < MAX_REGISTRATION_RETRIES) {
                    int delayMs = Math.min(INITIAL_RETRY_DELAY_MS * (1 << (attempt - 1)), MAX_RETRY_DELAY_MS);
                    try {
                        log.debug("⏳ Retrying registration with {} in {}ms...", peerUrlIP, delayMs);
                        sleeper.sleep(delayMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        log.warn("⚠️  Registration retry interrupted");
                        break;
                    }
                }
            }

            if (!registered) {
                log.debug("⚠️  Failed to register with peer validator {} ({} → {}) after {} attempts (non-critical - Aeron Cluster handles consensus)",
                    peerUrl, peerUrlIP, peerUrlIP, MAX_REGISTRATION_RETRIES);
            }
        }

        log.info("📡 Validator peer registration complete");
    }

    BroadcastSummary broadcastPresence(String validatorId, String validatorUrl, List<String> peerUrls, JoinProof proof) {
        int successCount = 0;
        int failureCount = 0;

        for (String peerUrl : peerUrls) {
            try {
                if (peerUrl.equals(validatorUrl)) {
                    log.debug("   Skipping self: {}", peerUrl);
                    continue;
                }

                String peerUrlIP = urlResolver.resolve(peerUrl);
                String peerJoinedUrl = peerUrlIP + "/v1/consensus/peer-joined";
                String jsonPayload = buildPeerJoinedPayload(validatorId, validatorUrl, proof);

                log.info("   → Broadcasting to {} → {} (with public key)", peerUrl, peerUrlIP);

                PeerJsonHttpClient.PostResult result = poster.postJson(peerJoinedUrl, jsonPayload);
                if (result.getResponseCode() == 200) {
                    log.info("   ✅ Accepted by peer: {} → {}", peerUrl, peerUrlIP);
                    log.debug("      Response: {}", result.getResponseBody());
                    successCount++;
                } else {
                    log.warn("   ❌ Rejected by peer {}: HTTP {}", peerUrl, result.getResponseCode());
                    failureCount++;
                }
            } catch (ConnectException e) {
                log.warn("   ⚠️  Cannot reach peer {}: {}", peerUrl, e.getMessage());
                failureCount++;
            } catch (Exception e) {
                log.error("   ❌ Failed to broadcast to peer {}: {}", peerUrl, e.getMessage());
                failureCount++;
            }
        }

        return new BroadcastSummary(successCount, failureCount);
    }

    static String buildRegistrationPayload(String validatorId, String validatorUrl) {
        return String.format(
            "{\"validatorId\":\"%s\",\"validatorUrl\":\"%s\"}",
            escapeJson(validatorId),
            escapeJson(validatorUrl)
        );
    }

    static String buildPeerJoinedPayload(String validatorId, String validatorUrl, JoinProof proof) {
        return String.format(
            "{\"validatorId\":\"%s\",\"validatorUrl\":\"%s\",\"proof\":%s,\"publicKey\":\"\"}",
            escapeJson(validatorId),
            escapeJson(validatorUrl),
            proof.toJson()
        );
    }

    private static String escapeJson(String value) {
        return value.replace("\"", "\\\"");
    }
}
