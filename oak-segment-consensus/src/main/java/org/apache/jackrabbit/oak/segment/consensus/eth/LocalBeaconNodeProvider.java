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
package org.apache.jackrabbit.oak.segment.consensus.eth;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fetches finalized epoch data from a self-hosted Ethereum consensus-layer (beacon)
 * node via the standard Beacon REST API (EIP-3030 / consensus-spec REST API).
 *
 * <p>Uses {@code GET /eth/v1/beacon/states/head/finality_checkpoints}, which is
 * implemented by all major CL clients: Lighthouse, Prysm, Teku, Nimbus.
 *
 * <p>Expected response shape:
 * <pre>
 * {
 *   "data": {
 *     "finalized": { "epoch": "NNN", "root": "0x..." },
 *     ...
 *   }
 * }
 * </pre>
 */
class LocalBeaconNodeProvider implements BeaconChainProvider {

    private static final Logger log = LoggerFactory.getLogger(LocalBeaconNodeProvider.class);

    private static final String FINALITY_PATH = "/eth/v1/beacon/states/head/finality_checkpoints";

    private final String nodeUrl;
    private final HttpFetcher fetcher;

    LocalBeaconNodeProvider(String nodeUrl, HttpFetcher fetcher) {
        this.nodeUrl = nodeUrl.endsWith("/") ? nodeUrl.substring(0, nodeUrl.length() - 1) : nodeUrl;
        this.fetcher = fetcher;
    }

    @Override
    public long fetchFinalizedEpoch() throws Exception {
        String response = fetcher.fetch(nodeUrl + FINALITY_PATH);
        long epoch = parseFinalizedEpoch(response);
        if (epoch < 0) {
            throw new Exception("Could not parse finalized epoch from local beacon node response");
        }
        return epoch;
    }

    @Override
    public String name() {
        return "local-beacon-node(" + nodeUrl + ")";
    }

    /**
     * Parses the {@code data.finalized.epoch} field from the finality_checkpoints response.
     * The standard API returns epoch as a quoted decimal string: {@code "epoch": "NNN"}.
     * Returns -1 if parsing fails.
     */
    long parseFinalizedEpoch(String json) {
        if (json == null || json.isEmpty()) {
            return -1;
        }
        try {
            // Find "finalized" block, then "epoch" within it
            int finalizedIdx = json.indexOf("\"finalized\"");
            if (finalizedIdx < 0) {
                return -1;
            }
            int epochIdx = json.indexOf("\"epoch\"", finalizedIdx);
            if (epochIdx < 0) {
                return -1;
            }
            int colonIdx = json.indexOf(":", epochIdx);
            if (colonIdx < 0) {
                return -1;
            }
            // epoch value may be quoted ("NNN") or unquoted (NNN)
            int valueStart = colonIdx + 1;
            while (valueStart < json.length() && (json.charAt(valueStart) == ' ' || json.charAt(valueStart) == '"')) {
                valueStart++;
            }
            int valueEnd = valueStart;
            while (valueEnd < json.length() && Character.isDigit(json.charAt(valueEnd))) {
                valueEnd++;
            }
            if (valueEnd > valueStart) {
                return Long.parseLong(json.substring(valueStart, valueEnd));
            }
        } catch (Exception e) {
            log.debug("Finalized epoch parse failed: {}", e.getMessage());
        }
        return -1;
    }

    static HttpFetcher defaultFetcher() {
        return LocalBeaconNodeProvider::httpGet;
    }

    private static String httpGet(String endpoint) throws Exception {
        URL url = new URL(endpoint);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(10_000);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("User-Agent", "OakSegmentConsensus/1.0");

        int rc = conn.getResponseCode();
        if (rc != 200) {
            throw new Exception("HTTP " + rc + " from " + endpoint);
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        }
    }
}
