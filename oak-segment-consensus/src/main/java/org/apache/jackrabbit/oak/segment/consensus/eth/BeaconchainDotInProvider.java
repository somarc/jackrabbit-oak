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
 * Fetches finalized epoch data from the beaconcha.in REST API.
 *
 * <p>Tries {@code /epoch/finalized} first; falls back to {@code /epoch/latest}
 * if the preferred endpoint fails. Both mainnet and Sepolia base URLs are
 * supported — pass the appropriate URL at construction time.
 */
class BeaconchainDotInProvider implements BeaconChainProvider {

    private static final Logger log = LoggerFactory.getLogger(BeaconchainDotInProvider.class);

    static final String MAINNET_BASE = "https://beaconcha.in/api/v1";
    static final String SEPOLIA_BASE = "https://sepolia.beaconcha.in/api/v1";

    private final String apiBaseUrl;
    private final HttpFetcher fetcher;

    BeaconchainDotInProvider(String apiBaseUrl, HttpFetcher fetcher) {
        this.apiBaseUrl = apiBaseUrl;
        this.fetcher = fetcher;
    }

    @Override
    public long fetchFinalizedEpoch() throws Exception {
        StringBuilder attempts = new StringBuilder();

        // Preferred endpoint
        try {
            String response = fetcher.fetch(apiBaseUrl + "/epoch/finalized");
            long epoch = parseEpochFromResponse(response);
            if (epoch >= 0) {
                return epoch;
            }
            attempts.append("/epoch/finalized:parse-failed; ");
        } catch (Exception e) {
            attempts.append("/epoch/finalized:").append(e.getMessage()).append("; ");
        }

        // Fallback: derive finalized from latest (latest - 2)
        try {
            String response = fetcher.fetch(apiBaseUrl + "/epoch/latest");
            long latest = parseEpochFromResponse(response);
            if (latest >= 0) {
                return Math.max(0L, latest - 2);
            }
            attempts.append("/epoch/latest:parse-failed");
        } catch (Exception e) {
            attempts.append("/epoch/latest:").append(e.getMessage());
        }

        throw new Exception("beaconcha.in all endpoints failed: " + attempts);
    }

    @Override
    public String name() {
        return "beaconcha.in(" + apiBaseUrl + ")";
    }

    String getApiBaseUrl() {
        return apiBaseUrl;
    }

    /**
     * Parses an epoch number from a beaconcha.in JSON response.
     * Handles {@code {"data":{"epoch":NNN}}} and {@code {"data":NNN}} shapes.
     * Returns -1 if parsing fails.
     */
    long parseEpochFromResponse(String json) {
        if (json == null || json.isEmpty()) {
            return -1;
        }
        try {
            int epochIdx = json.indexOf("\"epoch\"");
            if (epochIdx >= 0) {
                int colonIdx = json.indexOf(":", epochIdx);
                int endIdx = json.indexOf(",", colonIdx);
                if (endIdx < 0) endIdx = json.indexOf("}", colonIdx);
                if (colonIdx > 0 && endIdx > colonIdx) {
                    return Long.parseLong(json.substring(colonIdx + 1, endIdx).trim());
                }
                return -1;
            }

            // Alternate shape: "data": NNN
            int dataIdx = json.indexOf("\"data\"");
            if (dataIdx >= 0) {
                int colonIdx = json.indexOf(":", dataIdx);
                int endIdx = json.indexOf(",", colonIdx);
                if (endIdx < 0) endIdx = json.indexOf("}", colonIdx);
                if (colonIdx > 0 && endIdx > colonIdx) {
                    return Long.parseLong(json.substring(colonIdx + 1, endIdx).trim());
                }
            }
        } catch (Exception e) {
            log.debug("Epoch parse failed: {}", e.getMessage());
        }
        return -1;
    }

    static HttpFetcher defaultFetcher() {
        return BeaconchainDotInProvider::httpGet;
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
