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
import org.junit.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PeerAnnouncementClientTest {

    @Test
    public void testRegisterWithPeersRetriesThenSucceeds() {
        List<String> postedUrls = new ArrayList<>();
        List<String> payloads = new ArrayList<>();
        List<Long> sleeps = new ArrayList<>();
        Map<String, Integer> attempts = new HashMap<>();
        PeerAnnouncementClient client = new PeerAnnouncementClient(
            url -> url.replace("validator-2", "172.18.0.2"),
            (targetUrl, payload) -> {
                postedUrls.add(targetUrl);
                payloads.add(payload);
                int count = attempts.merge(targetUrl, 1, Integer::sum);
                if (count < 3) {
                    throw new IOException("not ready");
                }
                return new PeerJsonHttpClient.PostResult(200, "");
            },
            sleeps::add
        );

        client.registerWithPeers("validator-1", "http://validator-1:8090",
            Arrays.asList("http://validator-1:8090", "http://validator-2:8090"));

        assertEquals(Arrays.asList(2000L, 4000L), sleeps);
        assertEquals(3, postedUrls.size());
        assertEquals("http://172.18.0.2:8090/v1/register-validator", postedUrls.get(0));
        assertEquals("{\"validatorId\":\"validator-1\",\"validatorUrl\":\"http://validator-1:8090\"}", payloads.get(0));
    }

    @Test
    public void testRegisterWithPeersStopsWhenRetrySleepInterrupted() {
        List<Long> sleeps = new ArrayList<>();
        PeerAnnouncementClient client = new PeerAnnouncementClient(
            url -> url,
            (targetUrl, payload) -> {
                throw new IOException("still down");
            },
            millis -> {
                sleeps.add(millis);
                throw new InterruptedException("stop");
            }
        );

        try {
            client.registerWithPeers("validator-1", "http://validator-1:8090",
                Arrays.asList("http://validator-2:8090"));

            assertEquals(Arrays.asList(2000L), sleeps);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    public void testBroadcastPresenceCountsSuccessesAndFailures() {
        List<String> postedUrls = new ArrayList<>();
        List<String> payloads = new ArrayList<>();
        PeerAnnouncementClient client = new PeerAnnouncementClient(
            url -> url.replace("validator-2", "172.18.0.2").replace("validator-3", "172.18.0.3"),
            (targetUrl, payload) -> {
                postedUrls.add(targetUrl);
                payloads.add(payload);
                if (targetUrl.contains("172.18.0.2")) {
                    return new PeerJsonHttpClient.PostResult(200, "{\"accepted\":true}");
                }
                throw new ConnectException("refused");
            },
            millis -> {
            }
        );

        JoinProof proof = new JoinProof();
        proof.setValidatorId("validator-1");
        proof.setValidatorUrl("http://validator-1:8090");
        proof.setHeadSegmentId("head-1");
        proof.setGenesisSegmentId("head-1");
        proof.setGenesisHash("hash-1");
        proof.setSampleSegmentIds(Arrays.asList("head-1"));
        proof.setSampleSegmentHashes(Arrays.asList("hash-1"));
        proof.setChallengeNonce("nonce");
        proof.setNonceSignature("sig");

        PeerAnnouncementClient.BroadcastSummary summary = client.broadcastPresence(
            "validator-1",
            "http://validator-1:8090",
            Arrays.asList("http://validator-1:8090", "http://validator-2:8090", "http://validator-3:8090"),
            proof
        );

        assertEquals(1, summary.getSuccessCount());
        assertEquals(1, summary.getFailureCount());
        assertEquals(Arrays.asList(
            "http://172.18.0.2:8090/v1/consensus/peer-joined",
            "http://172.18.0.3:8090/v1/consensus/peer-joined"
        ), postedUrls);
        assertTrue(payloads.get(0).contains("\"validatorId\":\"validator-1\""));
        assertTrue(payloads.get(0).contains("\"proof\":"));
        assertTrue(payloads.get(0).contains("\"publicKey\":\"\""));
    }

    @Test
    public void testPayloadBuildersEscapeQuotes() {
        JoinProof proof = new JoinProof();
        proof.setValidatorId("validator-1");
        proof.setValidatorUrl("http://validator-1:8090");
        proof.setHeadSegmentId("head-1");
        proof.setGenesisSegmentId("head-1");
        proof.setGenesisHash("hash-1");
        proof.setSampleSegmentIds(Arrays.asList("head-1"));
        proof.setSampleSegmentHashes(Arrays.asList("hash-1"));
        proof.setChallengeNonce("nonce");
        proof.setNonceSignature("sig");

        String registrationPayload = PeerAnnouncementClient.buildRegistrationPayload("validator\"1", "http://validator\"1:8090");
        String joinedPayload = PeerAnnouncementClient.buildPeerJoinedPayload("validator\"1", "http://validator\"1:8090", proof);

        assertEquals("{\"validatorId\":\"validator\\\"1\",\"validatorUrl\":\"http://validator\\\"1:8090\"}", registrationPayload);
        assertTrue(joinedPayload.contains("\"validatorId\":\"validator\\\"1\""));
        assertTrue(joinedPayload.contains("\"validatorUrl\":\"http://validator\\\"1:8090\""));
    }
}
