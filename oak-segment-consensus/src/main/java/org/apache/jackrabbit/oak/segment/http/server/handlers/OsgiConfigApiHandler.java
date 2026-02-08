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

import org.apache.jackrabbit.oak.segment.consensus.aeron.AeronClusterTuningIntrospection;
import org.apache.jackrabbit.oak.segment.consensus.queue.ProposalQueueTuningIntrospection;
import org.apache.jackrabbit.oak.segment.http.server.AuthTokenValidator;
import org.apache.jackrabbit.oak.segment.http.server.RateLimiter;
import org.apache.jackrabbit.oak.segment.http.server.RateLimiterTuningIntrospection;
import org.apache.jackrabbit.oak.segment.http.server.TlsConfiguration;
import org.apache.jackrabbit.oak.segment.http.server.ValidatorAuthHandler;
import org.apache.jackrabbit.oak.segment.http.server.util.JsonOutputUtil;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    public void handleCoverage(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json; charset=UTF-8");

        List<Map<String, Object>> schema = buildSchema();
        Set<String> exposedKeys = new LinkedHashSet<>();
        for (Map<String, Object> entry : schema) {
            Object raw = entry.get("key");
            if (raw != null) {
                exposedKeys.add(String.valueOf(raw));
            }
        }

        Set<String> knownTunables = buildKnownTunables();
        List<String> missing = new ArrayList<>();
        for (String key : knownTunables) {
            if (!exposedKeys.contains(key)) {
                missing.add(key);
            }
        }

        List<String> extra = new ArrayList<>();
        for (String key : exposedKeys) {
            if (!knownTunables.contains(key)) {
                extra.add(key);
            }
        }

        int known = knownTunables.size();
        int exposed = exposedKeys.size();
        double coveragePercent = known == 0 ? 100.0 : (exposed * 100.0) / known;

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("knownTunables", known);
        summary.put("exposedTunables", exposed);
        summary.put("missingTunables", missing.size());
        summary.put("extraExposedTunables", extra.size());
        summary.put("coveragePercent", Math.round(coveragePercent * 10.0d) / 10.0d);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contractVersion", "config.osgi.coverage.v1");
        payload.put("generatedAtMs", System.currentTimeMillis());
        payload.put("summary", summary);
        payload.put("missing", missing);
        payload.put("extra", extra);
        response.getWriter().write(JsonOutputUtil.toJson(payload));
    }

    private Map<String, Object> buildComponents() {
        Map<String, Object> components = new LinkedHashMap<>();
        components.put("aeronClusterTuning", AeronClusterTuningIntrospection.effectiveValues());
        components.put("proposalQueueTuning", ProposalQueueTuningIntrospection.effectiveValues());
        components.put("rateLimiterTuning", RateLimiterTuningIntrospection.effectiveValues());
        components.put("tlsTuning", buildTlsTuning());
        components.put("validatorAuthTuning", buildValidatorAuthTuning());
        components.put("validatorRegistrationTuning", buildValidatorRegistrationTuning());
        components.put("tokenAuthTuning", buildTokenAuthTuning());
        components.put("fileStoreFlushTuning", buildFileStoreFlushTuning());
        components.put("gcEconomicsTuning", buildGcEconomicsTuning());
        components.put("runtimeUiTuning", buildRuntimeUiTuning());
        return components;
    }

    private Map<String, Object> buildSourcesMap() {
        Map<String, Object> sources = new LinkedHashMap<>();
        sources.put("aeronClusterTuning", AeronClusterTuningIntrospection.source());
        sources.put("proposalQueueTuning", ProposalQueueTuningIntrospection.source());
        sources.put("rateLimiterTuning", RateLimiterTuningIntrospection.source());
        sources.put("tlsTuning", "system-properties");
        sources.put("validatorAuthTuning", "system-properties");
        sources.put("validatorRegistrationTuning", "system-properties");
        sources.put("tokenAuthTuning", "system-properties-or-env");
        sources.put("fileStoreFlushTuning", "system-properties");
        sources.put("gcEconomicsTuning", "system-properties");
        sources.put("runtimeUiTuning", "system-properties");
        return sources;
    }

    private List<Map<String, Object>> buildSchema() {
        List<Map<String, Object>> schema = new ArrayList<>();

        schema.add(schemaEntry(
            "aeronClusterTuning.cluster_environment",
            "string",
            "",
            "startup-only",
            "guarded",
            "Environment profile for Aeron timeout defaults",
            "oak.cluster.environment"));
        schema.add(schemaEntry(
            "aeronClusterTuning.session_timeout_minutes",
            "int",
            0,
            "startup-only",
            "expert-only",
            "Aeron session timeout override",
            "oak.cluster.session.timeout.minutes"));
        schema.add(schemaEntry(
            "aeronClusterTuning.media_driver_timeout_ms",
            "int",
            0,
            "startup-only",
            "expert-only",
            "MediaDriver timeout override",
            "oak.cluster.media.driver.timeout.ms"));
        schema.add(schemaEntry(
            "aeronClusterTuning.cluster_term_length_bytes",
            "int",
            0,
            "startup-only",
            "expert-only",
            "Aeron cluster term length",
            "oak.cluster.term.length.bytes"));
        schema.add(schemaEntry(
            "aeronClusterTuning.peer_probe_mode",
            "string",
            "",
            "startup-only",
            "guarded",
            "Peer probe mode for health checks",
            "oak.health.peerProbeMode"));
        schema.add(schemaEntry(
            "aeronClusterTuning.socket_send_buffer_bytes",
            "int",
            0,
            "startup-only",
            "guarded",
            "Aeron socket send buffer size",
            "aeron.socket.so_sndbuf"));
        schema.add(schemaEntry(
            "aeronClusterTuning.socket_receive_buffer_bytes",
            "int",
            0,
            "startup-only",
            "guarded",
            "Aeron socket receive buffer size",
            "aeron.socket.so_rcvbuf"));
        schema.add(schemaEntry(
            "aeronClusterTuning.publication_term_buffer_length_bytes",
            "int",
            0,
            "startup-only",
            "expert-only",
            "Aeron publication term buffer length",
            "oak.cluster.publication.term.buffer.length.bytes"));
        schema.add(schemaEntry(
            "aeronClusterTuning.heartbeat_max_age_ms",
            "long",
            0L,
            "startup-only",
            "guarded",
            "Heartbeat age threshold for liveness",
            "oak.cluster.heartbeat.maxAgeMs"));
        schema.add(schemaEntry(
            "aeronClusterTuning.reachability_cache_ms",
            "long",
            0L,
            "startup-only",
            "guarded",
            "Peer reachability cache duration",
            "oak.cluster.reachability.cacheMs"));
        schema.add(schemaEntry(
            "aeronClusterTuning.reachability_connect_timeout_ms",
            "int",
            0,
            "startup-only",
            "expert-only",
            "Peer reachability connect timeout",
            "oak.cluster.reachability.connectTimeoutMs"));
        schema.add(schemaEntry(
            "aeronClusterTuning.reachability_read_timeout_ms",
            "int",
            0,
            "startup-only",
            "expert-only",
            "Peer reachability read timeout",
            "oak.cluster.reachability.readTimeoutMs"));
        schema.add(schemaEntry(
            "aeronClusterTuning.reconnect_max_attempts",
            "int",
            0,
            "startup-only",
            "guarded",
            "Peer reconnect attempt ceiling",
            "oak.cluster.reconnect.maxAttempts"));
        schema.add(schemaEntry(
            "aeronClusterTuning.delete_aeron_dirs_on_startup",
            "boolean",
            false,
            "startup-only",
            "expert-only",
            "Delete Aeron runtime dirs during startup",
            "aeron.delete.dirs.on.startup"));
        schema.add(schemaEntry(
            "aeronClusterTuning.beacon_api_url",
            "string",
            "https://beaconcha.in/api",
            "startup-only",
            "guarded",
            "Beacon API base URL",
            "ethereum.beacon.api.url"));

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
            "proposalQueueTuning.persistence_enabled",
            "boolean",
            true,
            "runtime-readable",
            "guarded",
            "Enable persistent queue snapshots",
            "oak.proposal.persistence.enabled"));
        schema.add(schemaEntry(
            "proposalQueueTuning.confirmation_timeout_ms",
            "long",
            300000L,
            "runtime-readable",
            "guarded",
            "Proposal confirmation timeout",
            "oak.proposal.confirmation.timeout.ms"));
        schema.add(schemaEntry(
            "proposalQueueTuning.restore_timeout_ms",
            "long",
            300000L,
            "runtime-readable",
            "guarded",
            "Proposal restore timeout",
            "oak.proposal.restore.timeout.ms"));
        schema.add(schemaEntry(
            "proposalQueueTuning.verifier_threads",
            "int",
            1,
            "runtime-readable",
            "expert-only",
            "Verifier worker thread count",
            "oak.proposal.verifier.threads"));
        schema.add(schemaEntry(
            "proposalQueueTuning.processed_retention_ms",
            "long",
            600000L,
            "runtime-readable",
            "guarded",
            "Retention of processed proposal ids",
            "oak.proposal.processed.retention.ms"));
        schema.add(schemaEntry(
            "proposalQueueTuning.backpressure_park_nanos",
            "long",
            1000000L,
            "runtime-readable",
            "expert-only",
            "Backpressure park interval",
            "oak.consensus.backpressure.park.nanos"));
        schema.add(schemaEntry(
            "proposalQueueTuning.counter_rotation_interval_ms",
            "long",
            86400000L,
            "runtime-readable",
            "guarded",
            "Counter rotation interval",
            "oak.proposal.counter.rotation.ms"));

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
        schema.add(schemaEntry(
            "rateLimiterTuning.burst_size",
            "int",
            200,
            "runtime-readable",
            "guarded",
            "Per-client burst size",
            RateLimiter.PROP_BURST_SIZE));
        schema.add(schemaEntry(
            "rateLimiterTuning.global_rps",
            "int",
            1000,
            "runtime-readable",
            "guarded",
            "Global request budget",
            RateLimiter.PROP_GLOBAL_RPS));
        schema.add(schemaEntry(
            "rateLimiterTuning.warn_logging_enabled",
            "boolean",
            true,
            "runtime-readable",
            "safe",
            "Enable throttling warning logs",
            RateLimiter.PROP_WARN_LOGGING_ENABLED));
        schema.add(schemaEntry(
            "rateLimiterTuning.warn_log_interval_ms",
            "long",
            30000L,
            "runtime-readable",
            "safe",
            "Warning log rate-limit interval",
            RateLimiter.PROP_WARN_LOG_INTERVAL_MS));
        schema.add(schemaEntry(
            "rateLimiterTuning.warn_log_sample_size",
            "int",
            250,
            "runtime-readable",
            "safe",
            "Minimum throttles before warning log",
            RateLimiter.PROP_WARN_LOG_SAMPLE_SIZE));

        schema.add(schemaEntry(
            "tlsTuning.enabled",
            "boolean",
            false,
            "startup-only",
            "expert-only",
            "Enable TLS listener",
            TlsConfiguration.PROP_TLS_ENABLED));
        schema.add(schemaEntry(
            "tlsTuning.keystore_type",
            "string",
            "PKCS12",
            "startup-only",
            "guarded",
            "TLS keystore format",
            TlsConfiguration.PROP_KEYSTORE_TYPE));
        schema.add(schemaEntry(
            "tlsTuning.keystore_path_configured",
            "boolean",
            false,
            "startup-only",
            "expert-only",
            "Whether TLS keystore path is configured",
            TlsConfiguration.PROP_KEYSTORE_PATH));
        schema.add(schemaEntry(
            "tlsTuning.truststore_path_configured",
            "boolean",
            false,
            "startup-only",
            "expert-only",
            "Whether TLS truststore path is configured",
            TlsConfiguration.PROP_TRUSTSTORE_PATH));
        schema.add(schemaEntry(
            "tlsTuning.client_auth",
            "string",
            "none",
            "startup-only",
            "expert-only",
            "TLS client auth mode",
            TlsConfiguration.PROP_CLIENT_AUTH));
        schema.add(schemaEntry(
            "tlsTuning.protocols",
            "string",
            "TLSv1.2,TLSv1.3",
            "startup-only",
            "guarded",
            "Allowed TLS protocol list",
            TlsConfiguration.PROP_PROTOCOLS));
        schema.add(schemaEntry(
            "tlsTuning.ciphers_configured",
            "boolean",
            false,
            "startup-only",
            "expert-only",
            "Whether explicit cipher suites are configured",
            TlsConfiguration.PROP_CIPHERS));

        schema.add(schemaEntry(
            "validatorAuthTuning.enabled",
            "boolean",
            true,
            "startup-only",
            "guarded",
            "Enable dashboard WebAuthn session auth",
            ValidatorAuthHandler.PROP_AUTH_ENABLED));
        schema.add(schemaEntry(
            "validatorAuthTuning.session_ttl_hours",
            "int",
            24,
            "startup-only",
            "guarded",
            "Dashboard auth session TTL (hours)",
            ValidatorAuthHandler.PROP_SESSION_TTL));
        schema.add(schemaEntry(
            "validatorAuthTuning.allowed_wallets_configured",
            "boolean",
            false,
            "startup-only",
            "expert-only",
            "Whether dashboard auth allow-list is configured",
            ValidatorAuthHandler.PROP_ALLOWED_WALLETS));

        schema.add(schemaEntry(
            "validatorRegistrationTuning.enabled",
            "boolean",
            true,
            "startup-only",
            "guarded",
            "Enable validator self-registration surface",
            ValidatorRegistrationHandler.PROP_REGISTRATION_ENABLED));
        schema.add(schemaEntry(
            "validatorRegistrationTuning.approval_required",
            "boolean",
            false,
            "startup-only",
            "guarded",
            "Require approval for new registrations",
            ValidatorRegistrationHandler.PROP_REGISTRATION_APPROVAL_REQUIRED));
        schema.add(schemaEntry(
            "validatorRegistrationTuning.rp_id",
            "string",
            "oak-chain.io",
            "startup-only",
            "guarded",
            "WebAuthn relying-party id",
            ValidatorRegistrationHandler.PROP_RP_ID));
        schema.add(schemaEntry(
            "validatorRegistrationTuning.rp_name",
            "string",
            "Oak Chain Validator",
            "startup-only",
            "safe",
            "WebAuthn relying-party display name",
            ValidatorRegistrationHandler.PROP_RP_NAME));

        schema.add(schemaEntry(
            "tokenAuthTuning.auth_token_configured",
            "boolean",
            false,
            "startup-only",
            "expert-only",
            "Whether API auth token is configured",
            AuthTokenValidator.TOKEN_PROPERTY_NAME + "|" + AuthTokenValidator.TOKEN_ENV_VAR_NAME));

        schema.add(schemaEntry(
            "fileStoreFlushTuning.flush_interval_ms",
            "long",
            250L,
            "startup-only",
            "guarded",
            "FileStore async flush interval",
            "oak.filestore.flush.ms"));
        schema.add(schemaEntry(
            "fileStoreFlushTuning.flush_batch",
            "int",
            100,
            "startup-only",
            "guarded",
            "FileStore async flush batch threshold",
            "oak.filestore.flush.batch"));

        schema.add(schemaEntry(
            "gcEconomicsTuning.usdc_per_mb",
            "string",
            "0.10",
            "runtime-readable",
            "guarded",
            "Mock GC cost per MB",
            "gc.usdc.per.mb"));

        schema.add(schemaEntry(
            "runtimeUiTuning.browser_ui_enabled",
            "boolean",
            true,
            "startup-only",
            "safe",
            "Enable in-process browser UI routes",
            "oak.http.browser.ui.enabled"));
        schema.add(schemaEntry(
            "runtimeUiTuning.external_dashboard_url_configured",
            "boolean",
            false,
            "startup-only",
            "safe",
            "Whether external dashboard URL is configured",
            "oak.dashboard.external.url"));
        return schema;
    }

    private Map<String, Object> buildTlsTuning() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("enabled", readBoolean(TlsConfiguration.PROP_TLS_ENABLED, false));
        values.put("keystore_type", readString(TlsConfiguration.PROP_KEYSTORE_TYPE, "PKCS12"));
        values.put("keystore_path_configured", hasText(System.getProperty(TlsConfiguration.PROP_KEYSTORE_PATH)));
        values.put("truststore_path_configured", hasText(System.getProperty(TlsConfiguration.PROP_TRUSTSTORE_PATH)));
        values.put("client_auth", readString(TlsConfiguration.PROP_CLIENT_AUTH, "none"));
        values.put("protocols", readString(TlsConfiguration.PROP_PROTOCOLS, "TLSv1.2,TLSv1.3"));
        values.put("ciphers_configured", hasText(System.getProperty(TlsConfiguration.PROP_CIPHERS)));
        return values;
    }

    private Map<String, Object> buildValidatorAuthTuning() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("enabled", readBoolean(ValidatorAuthHandler.PROP_AUTH_ENABLED, true));
        values.put("session_ttl_hours", readInt(ValidatorAuthHandler.PROP_SESSION_TTL, 24));
        String allowedWallets = System.getProperty(ValidatorAuthHandler.PROP_ALLOWED_WALLETS);
        values.put("allowed_wallets_configured", hasText(allowedWallets));
        values.put("allowed_wallets_count", countCsv(allowedWallets));
        return values;
    }

    private Map<String, Object> buildValidatorRegistrationTuning() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("enabled", readBoolean(ValidatorRegistrationHandler.PROP_REGISTRATION_ENABLED, true));
        values.put("approval_required", readBoolean(ValidatorRegistrationHandler.PROP_REGISTRATION_APPROVAL_REQUIRED, false));
        values.put("rp_id", readString(ValidatorRegistrationHandler.PROP_RP_ID, "oak-chain.io"));
        values.put("rp_name", readString(ValidatorRegistrationHandler.PROP_RP_NAME, "Oak Chain Validator"));
        return values;
    }

    private Map<String, Object> buildTokenAuthTuning() {
        Map<String, Object> values = new LinkedHashMap<>();
        boolean tokenConfigured = hasText(System.getProperty(AuthTokenValidator.TOKEN_PROPERTY_NAME))
            || hasText(System.getenv(AuthTokenValidator.TOKEN_ENV_VAR_NAME));
        values.put("auth_token_configured", tokenConfigured);
        return values;
    }

    private Map<String, Object> buildFileStoreFlushTuning() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("flush_interval_ms", readLong("oak.filestore.flush.ms", 250L));
        values.put("flush_batch", readInt("oak.filestore.flush.batch", 100));
        return values;
    }

    private Map<String, Object> buildGcEconomicsTuning() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("usdc_per_mb", readString("gc.usdc.per.mb", "0.10"));
        return values;
    }

    private Map<String, Object> buildRuntimeUiTuning() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("browser_ui_enabled", readBoolean("oak.http.browser.ui.enabled", true));
        values.put("external_dashboard_url_configured", hasText(System.getProperty("oak.dashboard.external.url")));
        return values;
    }

    private Set<String> buildKnownTunables() {
        Set<String> known = new LinkedHashSet<>();
        for (Map<String, Object> entry : buildSchema()) {
            Object raw = entry.get("key");
            if (raw != null) {
                known.add(String.valueOf(raw));
            }
        }
        return known;
    }

    private int countCsv(String raw) {
        if (!hasText(raw)) {
            return 0;
        }
        int count = 0;
        String[] split = raw.split(",");
        for (String token : split) {
            if (hasText(token)) {
                count++;
            }
        }
        return count;
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static int readInt(String key, int defaultValue) {
        String raw = System.getProperty(key);
        if (!hasText(raw)) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static long readLong(String key, long defaultValue) {
        String raw = System.getProperty(key);
        if (!hasText(raw)) {
            return defaultValue;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static boolean readBoolean(String key, boolean defaultValue) {
        String raw = System.getProperty(key);
        if (!hasText(raw)) {
            return defaultValue;
        }
        return Boolean.parseBoolean(raw.trim());
    }

    private static String readString(String key, String defaultValue) {
        String raw = System.getProperty(key);
        if (raw == null) {
            return defaultValue;
        }
        return raw;
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
