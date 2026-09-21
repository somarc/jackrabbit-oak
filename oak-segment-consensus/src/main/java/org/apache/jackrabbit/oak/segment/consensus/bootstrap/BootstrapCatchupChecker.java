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
package org.apache.jackrabbit.oak.segment.consensus.bootstrap;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class BootstrapCatchupChecker {

    private static final Logger log = LoggerFactory.getLogger(BootstrapCatchupChecker.class);

    boolean isCaughtUp(FileStore fileStore, String primaryUrl) {
        if (primaryUrl == null) {
            return true;
        }

        try {
            long localSize = fileStore.size();
            boolean localIsEmpty = localSize == 0;
            String localHead = resolveLocalHead(fileStore);
            String primaryHead = fetchPrimaryHead(primaryUrl);

            if (localIsEmpty && (primaryHead == null || primaryHead.isEmpty() || "null".equals(primaryHead))) {
                log.info("✅ EMPTY-TO-EMPTY BOOTSTRAP: Both stores empty (ready for genesis)");
                log.info("   Local: empty store ({} bytes)", localSize);
                log.info("   Primary: empty store (HEAD: {})", primaryHead);
                log.info("   Genesis will be created as first consensus write after cluster forms");
                return true;
            }

            if (localIsEmpty && primaryHead != null && !primaryHead.isEmpty() && !"null".equals(primaryHead)) {
                log.debug("   Local empty, primary has data: {} - syncing...",
                    primaryHead.substring(0, Math.min(20, primaryHead.length())));
                return false;
            }

            if (localHead != null && (primaryHead == null || primaryHead.isEmpty() || "null".equals(primaryHead))) {
                log.warn("Local has HEAD but primary doesn't - unusual state");
                return false;
            }

            if (localHead == null) {
                log.warn("Local HEAD is null but primary has: {}", primaryHead);
                return false;
            }

            String localUuid = localHead.split("[:.]")[0];
            String primaryUuid = primaryHead.split("[:.]")[0];
            boolean caughtUp = localUuid.equals(primaryUuid);

            if (caughtUp) {
                log.info("✅ CAUGHT UP! Local HEAD matches primary");
                log.info("   Local HEAD: {}", localHead);
                log.info("   Primary HEAD: {}", primaryHead);
            } else {
                log.debug("   Still syncing... Local: {} vs Primary: {}",
                    localHead.substring(0, Math.min(20, localHead.length())),
                    primaryHead.substring(0, Math.min(20, primaryHead.length())));
            }

            return caughtUp;
        } catch (Exception e) {
            log.debug("Failed to check sync status: {}", e.getMessage());
            return false;
        }
    }

    private static String resolveLocalHead(FileStore fileStore) {
        try {
            return fileStore.getHead().getRecordId().toString10();
        } catch (Exception e) {
            log.debug("Local store has no valid HEAD (likely empty): {}", e.getMessage());
            return null;
        }
    }

    private static String fetchPrimaryHead(String primaryUrl) throws Exception {
        URL url = new URL(primaryUrl + "/v1/head");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(3000);
        conn.setReadTimeout(3000);

        int responseCode = conn.getResponseCode();
        if (responseCode != 200) {
            log.warn("Failed to get primary HEAD: HTTP {}", responseCode);
            return null;
        }

        StringBuilder jsonResponse = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                jsonResponse.append(line);
            }
        }

        String primaryHead = extractJsonField(jsonResponse.toString(), "\"latestHead\"", 12);
        if (primaryHead == null) {
            primaryHead = extractJsonField(jsonResponse.toString(), "\"committedHead\"", 16);
        }
        return primaryHead;
    }

    private static String extractJsonField(String json, String fieldName, int offset) {
        int fieldIndex = json.indexOf(fieldName);
        if (fieldIndex < 0) {
            return null;
        }

        int startIndex = json.indexOf("\"", fieldIndex + offset) + 1;
        int endIndex = json.indexOf("\"", startIndex);
        if (startIndex <= 0 || endIndex <= startIndex) {
            return null;
        }
        return json.substring(startIndex, endIndex);
    }
}
