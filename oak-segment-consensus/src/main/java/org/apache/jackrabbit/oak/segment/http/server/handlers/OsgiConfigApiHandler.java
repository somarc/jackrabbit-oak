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

import org.apache.jackrabbit.oak.segment.consensus.queue.ProposalQueueTuningIntrospection;
import org.apache.jackrabbit.oak.segment.http.server.RateLimiter;
import org.apache.jackrabbit.oak.segment.http.server.RateLimiterTuningIntrospection;
import org.apache.jackrabbit.oak.segment.http.server.util.JsonOutputUtil;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only config introspection endpoints for OSGi-governed runtime knobs.
 */
public class OsgiConfigApiHandler {

    public void handleEffectiveConfig(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json; charset=UTF-8");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contractVersion", "config.osgi.v1");
        payload.put("generatedAtMs", System.currentTimeMillis());
        payload.put("components", buildComponents());
        response.getWriter().write(JsonOutputUtil.toJson(payload));
    }

    public void handleConfigSources(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json; charset=UTF-8");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contractVersion", "config.osgi.sources.v1");
        payload.put("generatedAtMs", System.currentTimeMillis());
        payload.put("sources", buildSourcesMap());
        response.getWriter().write(JsonOutputUtil.toJson(payload));
    }

    public void handleConfigSchema(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json; charset=UTF-8");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contractVersion", "config.osgi.schema.v1");
        payload.put("generatedAtMs", System.currentTimeMillis());
        payload.put("schema", buildSchema());
        response.getWriter().write(JsonOutputUtil.toJson(payload));
    }

    private Map<String, Object> buildComponents() {
        Map<String, Object> components = new LinkedHashMap<>();
        components.put("proposalQueueTuning", ProposalQueueTuningIntrospection.effectiveValues());
        components.put("rateLimiterTuning", RateLimiterTuningIntrospection.effectiveValues());
        return components;
    }

    private Map<String, Object> buildSourcesMap() {
        Map<String, Object> sources = new LinkedHashMap<>();
        sources.put("proposalQueueTuning", ProposalQueueTuningIntrospection.source());
        sources.put("rateLimiterTuning", RateLimiterTuningIntrospection.source());
        return sources;
    }

    private List<Map<String, Object>> buildSchema() {
        List<Map<String, Object>> schema = new ArrayList<>();

        schema.add(schemaEntry(
            "proposalQueueTuning.max_message_batch",
            "int",
            10,
            "runtime-readable",
            "guarded",
            "Max Aeron batches per sender cycle",
            "oak.proposal.batch.max"));
        schema.add(schemaEntry(
            "proposalQueueTuning.finalization_chunk_size",
            "int",
            3,
            "runtime-readable",
            "guarded",
            "Max proposals finalized per chunk",
            "oak.proposal.finalization.chunk.size"));
        schema.add(schemaEntry(
            "proposalQueueTuning.max_pending_messages",
            "long",
            10000,
            "runtime-readable",
            "expert-only",
            "Backpressure pending-message ceiling",
            "oak.consensus.max.pending.messages"));
        schema.add(schemaEntry(
            "proposalQueueTuning.backpressure_timeout_ms",
            "long",
            30000,
            "runtime-readable",
            "expert-only",
            "Timeout while waiting under backpressure",
            "oak.consensus.backpressure.timeout.ms"));
        schema.add(schemaEntry(
            "proposalQueueTuning.persistence_flush_interval_ms",
            "long",
            250,
            "runtime-readable",
            "guarded",
            "Durability flush interval",
            "oak.proposal.persistence.flush.ms"));
        schema.add(schemaEntry(
            "proposalQueueTuning.persistence_flush_batch",
            "int",
            100,
            "runtime-readable",
            "guarded",
            "Durability flush batch threshold",
            "oak.proposal.persistence.flush.batch"));

        schema.add(schemaEntry(
            "rateLimiterTuning.enabled",
            "boolean",
            true,
            "runtime-readable",
            "safe",
            "Enable HTTP rate limiting",
            RateLimiter.PROP_ENABLED));
        schema.add(schemaEntry(
            "rateLimiterTuning.requests_per_second",
            "int",
            100,
            "runtime-readable",
            "guarded",
            "Per-client request budget",
            RateLimiter.PROP_REQUESTS_PER_SECOND));
        schema.add(schemaEntry(
            "rateLimiterTuning.write_rps",
            "int",
            10,
            "runtime-readable",
            "guarded",
            "Per-wallet write budget",
            RateLimiter.PROP_WRITE_RPS));
        return schema;
    }

    private Map<String, Object> schemaEntry(String key,
                                            String type,
                                            Object defaultValue,
                                            String reloadMode,
                                            String risk,
                                            String description,
                                            String systemPropertyAlias) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("key", key);
        entry.put("type", type);
        entry.put("default", defaultValue);
        entry.put("reloadMode", reloadMode);
        entry.put("risk", risk);
        entry.put("description", description);
        entry.put("systemPropertyAlias", systemPropertyAlias);
        return entry;
    }
}
