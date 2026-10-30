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
package org.apache.jackrabbit.oak.segment.http.server.handlers;

import org.apache.jackrabbit.oak.segment.http.server.util.JsonOutputUtil;
import org.slf4j.Logger;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * TTL cache behind the {@code /v1/ops/snapshots/*} endpoints. Serves the ops envelope
 * (freshness and cache metadata around the data) and falls back to the last snapshot
 * when building a fresh one fails.
 */
final class OpsSnapshotCache {

    interface Fallback {
        void write(Exception e) throws IOException;
    }

    private final long ttlMs;
    private final Logger log;
    private final Object lock = new Object();
    private volatile Map<String, Object> data;
    private volatile long sourceTimestampMs;

    OpsSnapshotCache(long ttlMs, Logger log) {
        this.ttlMs = ttlMs;
        this.log = log;
    }

    /**
     * @param orderedKeys emit envelope keys in insertion order ({@link LinkedHashMap})
     *                    instead of {@link HashMap} order; callers keep their historic byte layout
     */
    void serve(HttpServletResponse response, String contractVersion, boolean orderedKeys, String label,
               Callable<Map<String, Object>> loader, Fallback onFailure) throws IOException {
        long servedAtMs = System.currentTimeMillis();
        try {
            Map<String, Object> snapshot;
            long snapshotTimestampMs;
            boolean hit = false;
            synchronized (lock) {
                long now = System.currentTimeMillis();
                if (data != null && sourceTimestampMs > 0 && (now - sourceTimestampMs) <= ttlMs) {
                    snapshot = data;
                    snapshotTimestampMs = sourceTimestampMs;
                    hit = true;
                } else {
                    snapshot = loader.call();
                    snapshotTimestampMs = now;
                    data = snapshot;
                    sourceTimestampMs = now;
                }
            }
            write(response, contractVersion, orderedKeys, snapshot, snapshotTimestampMs, servedAtMs, false, null, hit);
        } catch (Exception e) {
            log.warn("Error building ops {} snapshot, attempting stale fallback: {}", label, e.getMessage());
            Map<String, Object> stale = data;
            long staleTimestampMs = sourceTimestampMs;
            if (stale != null && staleTimestampMs > 0) {
                write(response, contractVersion, orderedKeys, stale, staleTimestampMs, servedAtMs,
                    true, "STALE_CACHE_FALLBACK", true);
                return;
            }
            onFailure.write(e);
        }
    }

    private void write(HttpServletResponse response, String contractVersion, boolean orderedKeys,
                       Map<String, Object> snapshot, long snapshotTimestampMs, long servedAtMs,
                       boolean degraded, String degradedReason, boolean hit) throws IOException {
        Map<String, Object> payload = orderedKeys ? new LinkedHashMap<>() : new HashMap<>();
        payload.put("contractVersion", contractVersion);
        payload.put("sourceTimestampMs", snapshotTimestampMs);
        payload.put("servedAtMs", servedAtMs);
        payload.put("stalenessMs", Math.max(0L, servedAtMs - snapshotTimestampMs));
        payload.put("degraded", degraded);
        payload.put("degradedReason", degradedReason);
        Map<String, Object> cache = orderedKeys ? new LinkedHashMap<>() : new HashMap<>();
        cache.put("hit", hit);
        cache.put("ttlMs", ttlMs);
        payload.put("cache", cache);
        payload.put("data", snapshot);
        JsonOutputUtil.write(response, HttpServletResponse.SC_OK, payload);
    }
}
