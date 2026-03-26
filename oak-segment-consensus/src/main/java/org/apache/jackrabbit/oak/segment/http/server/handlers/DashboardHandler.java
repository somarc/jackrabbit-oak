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

import org.apache.jackrabbit.oak.segment.consensus.aeron.AeronConsensusEngine;
import org.apache.jackrabbit.oak.segment.consensus.config.BlockchainConfig;
import org.apache.jackrabbit.oak.segment.consensus.config.RuntimeConfigValueResolver;
import org.apache.jackrabbit.oak.segment.consensus.fragmentation.FragmentationTracker;
import org.apache.jackrabbit.oak.segment.http.server.ServerContext;
import org.apache.jackrabbit.oak.segment.http.server.util.JsonOutputUtil;
import org.apache.jackrabbit.oak.segment.http.server.util.DashboardDataService;
import org.apache.jackrabbit.oak.segment.http.server.util.FormatUtils;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Handler for UI rendering endpoints (dashboard, explorer, API browser).
 * 
 * <p>Extracted from SegmentHttpServer for better separation of concerns.</p>
 */
public class DashboardHandler {
    
    private final ServerContext context;
    private final DashboardDataService dataService;
    
    public DashboardHandler(ServerContext context) {
        this.context = context;
        this.dataService = new DashboardDataService(context);
    }
    
    /**
     * Handle root landing page for operators.
     *
     * <p>This endpoint intentionally avoids rendering the legacy dashboard UI.
     * It serves a compact API-first entry page that points operators to
     * the API browser, health endpoints, and external dashboard.</p>
     */
    public void handleDashboard(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("text/html; charset=UTF-8");
        final String appName = "Oak Segment Consensus";
        final String version = DashboardHandler.class.getPackage() != null
                && DashboardHandler.class.getPackage().getImplementationVersion() != null
                ? DashboardHandler.class.getPackage().getImplementationVersion()
                : "dev";
        final String externalDashboardUrl = RuntimeConfigValueResolver.readString("oak.dashboard.external.url", "");
        final String uptime = formatUptime(java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime());
        final String now = formatTimestamp(System.currentTimeMillis());

        Map<String, Object> clusterState = Collections.emptyMap();
        try {
            clusterState = dataService.getAeronClusterState();
        } catch (Exception ignored) {
            // Keep landing page available even if cluster probing fails.
        }
        if (clusterState == null) {
            clusterState = Collections.emptyMap();
        }

        final String role = safeString(clusterState.get("role"), "UNKNOWN").toUpperCase();
        final int nodeIdInt = asInt(clusterState.get("memberId"), -1);
        final String nodeId = nodeIdInt >= 0 ? String.valueOf(nodeIdInt) : "UNKNOWN";
        final int leaderNodeInt = resolveLeaderNodeId(clusterState, role, nodeIdInt);
        final String leaderNode = leaderNodeInt >= 0 ? String.valueOf(leaderNodeInt) : "UNKNOWN";
        final long termValue = asLong(clusterState.get("leadershipTerm"),
            asLong(clusterState.get("leadershipTermId"), 0L));
        final String term = String.valueOf(termValue);
        final int membersValue = asInt(clusterState.get("memberCount"),
            asInt(clusterState.get("clusterMemberCount"), 0));
        final String members = String.valueOf(membersValue);

        StringBuilder html = new StringBuilder();
        html.append("<!doctype html><html><head><meta charset='utf-8'>");
        html.append("<meta name='viewport' content='width=device-width, initial-scale=1'>");
        html.append("<title>").append(FormatUtils.escapeHtml(appName)).append(" Control Plane</title>");
        html.append("<style>");
        html.append("body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;background:#0b1020;color:#e5e7eb;margin:0;padding:24px;}");
        html.append(".wrap{max-width:960px;margin:0 auto;}h1{margin:0 0 8px 0;font-size:30px;}p{color:#9ca3af;}a{color:#93c5fd;text-decoration:none;}a:hover{text-decoration:underline;}");
        html.append(".grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(180px,1fr));gap:12px;margin:18px 0;}");
        html.append(".card{background:#111827;border:1px solid #1f2937;border-radius:10px;padding:12px;} .k{color:#9ca3af;font-size:12px;} .v{font-size:20px;font-weight:700;margin-top:4px;}");
        html.append(".links{display:grid;grid-template-columns:repeat(auto-fit,minmax(280px,1fr));gap:10px;margin:16px 0;}");
        html.append(".link{background:#111827;border:1px solid #1f2937;border-radius:10px;padding:10px 12px;display:block;}");
        html.append(".muted{font-size:12px;color:#94a3b8;} .warn{margin-top:16px;padding:10px 12px;border-left:3px solid #f59e0b;background:#111827;border-radius:8px;}");
        html.append(".tip{margin-top:16px;padding:12px 14px;border-left:3px solid #38bdf8;background:#111827;border-radius:8px;}");
        html.append(".section{margin-top:20px;}");
        html.append("</style></head><body><div class='wrap'>");
        html.append("<h1>Oak Control Plane Home</h1>");
        html.append("<p>API-first runtime. This page is the read-only entry point for health, consensus, and OSGi-governed runtime configuration.</p>");
        html.append("<div class='grid'>");
        html.append("<div class='card'><div class='k'>Build</div><div class='v'>").append(FormatUtils.escapeHtml(version)).append("</div></div>");
        html.append("<div class='card'><div class='k'>Role</div><div class='v'>").append(FormatUtils.escapeHtml(role)).append("</div></div>");
        html.append("<div class='card'><div class='k'>Node</div><div class='v'>").append(FormatUtils.escapeHtml(nodeId)).append("</div></div>");
        html.append("<div class='card'><div class='k'>Leader</div><div class='v'>").append(FormatUtils.escapeHtml(leaderNode)).append("</div></div>");
        html.append("<div class='card'><div class='k'>Term</div><div class='v'>").append(FormatUtils.escapeHtml(term)).append("</div></div>");
        html.append("<div class='card'><div class='k'>Members</div><div class='v'>").append(FormatUtils.escapeHtml(members)).append("</div></div>");
        html.append("<div class='card'><div class='k'>Uptime</div><div class='v'>").append(FormatUtils.escapeHtml(uptime)).append("</div></div>");
        html.append("<div class='card'><div class='k'>Updated</div><div class='v'>").append(FormatUtils.escapeHtml(now)).append("</div></div>");
        html.append("</div>");
        html.append("<div class='tip'><strong>OSGi workflow:</strong> start with <code>/v1/config/osgi</code> for effective values, then <code>/v1/config/osgi/sources</code> for provenance, <code>/v1/config/osgi/schema</code> for metadata, and <code>/v1/config/osgi/delta</code> or <code>/v1/config/osgi/coverage</code> for drift and gaps.</div>");
        html.append("<div class='section'>");
        html.append("<h2>Control Plane Surfaces</h2>");
        html.append("<div class='links'>");
        html.append("<a class='link' href='/api-browser'><strong>API Browser</strong><div class='muted'>Interactive endpoint catalog and tester.</div></a>");
        html.append("<a class='link' href='/v1/consensus/status'><strong>/v1/consensus/status</strong><div class='muted'>Consensus status and leader context.</div></a>");
        html.append("<a class='link' href='/v1/proposals/queue/stats'><strong>/v1/proposals/queue/stats</strong><div class='muted'>Queue/finality/backpressure counters.</div></a>");
        html.append("<a class='link' href='/v1/proposals/release-flow'><strong>/v1/proposals/release-flow</strong><div class='muted'>Adaptive verified-release stages and governor state.</div></a>");
        html.append("<a class='link' href='/v1/config/osgi'><strong>/v1/config/osgi</strong><div class='muted'>Effective OSGi tuning values.</div></a>");
        html.append("<a class='link' href='/v1/config/osgi/schema'><strong>/v1/config/osgi/schema</strong><div class='muted'>Knob metadata: types, defaults, reload mode, and risk.</div></a>");
        html.append("<a class='link' href='/v1/config/osgi/sources'><strong>/v1/config/osgi/sources</strong><div class='muted'>Where each effective config group came from.</div></a>");
        html.append("<a class='link' href='/v1/config/osgi/coverage'><strong>/v1/config/osgi/coverage</strong><div class='muted'>Read-only config coverage and gaps.</div></a>");
        html.append("<a class='link' href='/v1/config/osgi/delta'><strong>/v1/config/osgi/delta</strong><div class='muted'>Current values vs defaults.</div></a>");
        html.append("<a class='link' href='/v1/explorer/summary'><strong>/v1/explorer/summary</strong><div class='muted'>Explorer contract for external blockscan UI.</div></a>");
        html.append("<a class='link' href='/health'><strong>/health</strong><div class='muted'>Shallow health.</div></a>");
        html.append("<a class='link' href='/health/deep'><strong>/health/deep</strong><div class='muted'>Deep dependency health.</div></a>");
        if (externalDashboardUrl != null && !externalDashboardUrl.trim().isEmpty()) {
            html.append("<a class='link' href='").append(FormatUtils.escapeHtml(externalDashboardUrl)).append("'><strong>External Ops Dashboard</strong><div class='muted'>Configured via -Doak.dashboard.external.url.</div></a>");
        }
        html.append("</div>");
        html.append("</div>");
        html.append("<div class='warn'><strong>Safety:</strong> This page is read-only. Use signed API/CLI flows for mutating operations.</div>");
        html.append("</div></body></html>");
        response.getWriter().write(html.toString());
    }

    /**
     * API discovery index for tooling and API Browser dynamic catalog rendering.
     */
    public void handleApiIndex(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json; charset=UTF-8");

        List<Map<String, Object>> endpoints = new ArrayList<>();
        addLocalDiagnosticIndexEntry(endpoints, "GET", "/v1/index", "Live validator surface manifest", "Discovery", null);
        addSourceIndexEntry(endpoints, "GET", "/v1/config/osgi", "Effective OSGi config values", "Configuration", "config.osgi.v1", "/ops/v1/config/osgi");
        addSourceIndexEntry(endpoints, "GET", "/v1/config/osgi/schema", "OSGi config metadata schema", "Configuration", "config.osgi.schema.v1", "/ops/v1/config/osgi/schema");
        addSourceIndexEntry(endpoints, "GET", "/v1/config/osgi/sources", "OSGi config source map", "Configuration", "config.osgi.sources.v1", "/ops/v1/config/osgi/sources");
        addSourceIndexEntry(endpoints, "GET", "/v1/config/osgi/coverage", "OSGi config coverage and missing keys", "Configuration", "config.osgi.coverage.v1", "/ops/v1/config/osgi/coverage");
        addSourceIndexEntry(endpoints, "GET", "/v1/config/osgi/delta", "OSGi config values drift from defaults", "Configuration", "config.osgi.delta.v1", "/ops/v1/config/osgi/delta");
        addLocalDiagnosticIndexEntry(endpoints, "GET", "/health", "Shallow health", "Health", "/ops/v1/health");
        addLocalDiagnosticIndexEntry(endpoints, "GET", "/health/local", "Local-only liveness", "Health", null);
        addLocalDiagnosticIndexEntry(endpoints, "GET", "/health/deep", "Deep dependency health", "Health", "/v1/ops/snapshots/runtime");
        addLocalDiagnosticIndexEntry(endpoints, "GET", "/health/cluster", "Cluster-only health", "Health", "/v1/ops/snapshots/health");
        addLocalDiagnosticIndexEntry(endpoints, "GET", "/api/metrics", "Consensus and replication metrics", "Health", "/v1/ops/snapshots/runtime");
        addLocalDiagnosticIndexEntry(endpoints, "GET", "/metrics", "Prometheus metrics", "Health", null);

        addSourceIndexEntry(endpoints, "GET", "/v1/consensus/leader", "Canonical leader resolution", "Consensus", "consensus.leader.v1", "/ops/v1/cluster");
        addSourceIndexEntry(endpoints, "GET", "/v1/consensus/status", "Consensus status", "Consensus", "consensus.status.v1", "/ops/v1/cluster");
        addInternalIndexEntry(endpoints, "POST", "/v1/propose-write", "Propose signed write", "Consensus", null);
        addInternalIndexEntry(endpoints, "POST", "/v1/propose-delete", "Propose signed delete", "Consensus", null);
        addInternalIndexEntry(endpoints, "GET", "/v1/proposals/pending/count", "Pending proposal count", "Consensus", "/ops/v1/proposals");
        addSourceIndexEntry(endpoints, "GET", "/v1/proposals/queue/stats", "Queue and finality counters", "Consensus", "ops.v1", "/ops/v1/proposals/queue/stats");
        addSourceIndexEntry(endpoints, "GET", "/v1/proposals/release-flow", "Adaptive proposal release flow", "Consensus", "release-flow.v1", "/ops/v1/proposals/release-flow");
        addInternalIndexEntry(endpoints, "GET", "/v1/proposals/{id}/status", "Proposal status by id", "Consensus", null);
        addSourceIndexEntry(endpoints, "GET", "/v1/settlement/proposals/{proposalId}", "Basic settlement details by proposal id", "Settlement", "settlement.v1", "/ops/v1/settlement/proposals/{proposalId}");
        addSourceIndexEntry(endpoints, "GET", "/v1/settlement/transactions/{transactionHash}", "Basic settlement details by transaction hash", "Settlement", "settlement.v1", "/ops/v1/settlement/transactions/{transactionHash}");
        addInternalIndexEntry(endpoints, "GET", "/v1/head", "Head status", "Consensus", null);

        addSourceIndexEntry(endpoints, "GET", "/v1/explorer/summary", "Explorer summary contract", "Explorer", "explorer.v1", "/ops/v1/explorer/summary");
        addSourceIndexEntry(endpoints, "GET", "/v1/explorer/release-flow", "Explorer adaptive release flow", "Explorer", "explorer.v1", "/ops/v1/explorer/release-flow");
        addSourceIndexEntry(endpoints, "GET", "/v1/explorer/proposals/{proposalId}", "Explorer proposal detail", "Explorer", "explorer.v1", "/ops/v1/explorer/proposal/{proposalId}");
        addSourceIndexEntry(endpoints, "GET", "/v1/explorer/wallets/{walletAddress}", "Explorer wallet detail", "Explorer", "explorer.v1", "/ops/v1/explorer/wallets/{walletAddress}");
        addLocalUiIndexEntry(endpoints, "GET", "/explorer", "Explorer UI", "Explorer");
        addLocalDiagnosticIndexEntry(endpoints, "GET", "/api/explore?path=/", "Node tree browse API", "Explorer", "/ops/v1/explorer/*");
        addLocalDiagnosticIndexEntry(endpoints, "GET", "/api/segments/recent", "Recent segments", "Explorer", "/v1/ops/snapshots/storage");
        addLocalDiagnosticIndexEntry(endpoints, "GET", "/api/segments/tars", "TAR file listing", "Explorer", "/v1/ops/snapshots/storage");
        addLocalDiagnosticIndexEntry(endpoints, "GET", "/api/blob/{blobId}", "Blob stream by blob id", "Explorer", null);
        addLocalDiagnosticIndexEntry(endpoints, "GET", "/api/cid/{oakBlobId}", "CID mapping by Oak blob id", "Explorer", null);
        addLocalDiagnosticIndexEntry(endpoints, "GET", "/api/cid/stats", "CID mapping stats", "Explorer", null);
        addLocalDiagnosticIndexEntry(endpoints, "GET", "/api/cid/reverse/{cid}", "Reverse CID lookup", "Explorer", null);

        addInternalIndexEntry(endpoints, "GET", "/v1/wallets/stats", "Wallet usage and counts", "Wallets", null);
        addInternalIndexEntry(endpoints, "GET", "/v1/wallets/content?wallet=0x...", "Wallet content query", "Wallets", null);
        addInternalIndexEntry(endpoints, "POST|PUT", "/v1/register-client", "Register client", "Registration", null);
        addInternalIndexEntry(endpoints, "GET", "/v1/peers", "Peer list", "Registration", null);
        addInternalIndexEntry(endpoints, "GET", "/v1/ngrok-url", "Current ngrok URL", "Registration", null);
        addSourceIndexEntry(endpoints, "GET", "/v1/blockchain/config", "Blockchain mode config", "Configuration", "blockchain.config.v1", "/ops/v1/blockchain/config");

        addLocalDiagnosticIndexEntry(endpoints, "GET", "/v1/aeron/cluster-state", "Aeron cluster state", "Aeron", "/v1/ops/snapshots/cluster");
        addLocalDiagnosticIndexEntry(endpoints, "GET", "/v1/aeron/validator-identities", "Validator identity map", "Aeron", "/v1/ops/snapshots/runtime");
        addLocalDiagnosticIndexEntry(endpoints, "GET", "/v1/aeron/raft-metrics", "Raft metrics", "Aeron", "/v1/ops/snapshots/runtime");
        addLocalDiagnosticIndexEntry(endpoints, "GET", "/v1/aeron/node-status?nodeId=0", "Per-node status", "Aeron", "/v1/ops/snapshots/runtime");
        addLocalDiagnosticIndexEntry(endpoints, "GET", "/v1/aeron/leadership-history?limit=10", "Leadership history", "Aeron", "/ops/v1/events/recent");
        addLocalDiagnosticIndexEntry(endpoints, "GET", "/v1/aeron/replication-lag", "Replication lag", "Aeron", "/v1/ops/snapshots/replication");
        addInternalIndexEntry(endpoints, "POST", "/v1/follower/head-update", "Follower head update (internal)", "Aeron", null);

        addSourceIndexEntry(endpoints, "GET", "/v1/ops/snapshots/health", "Ops health snapshot", "Ops Snapshots", "ops.v1", "/ops/v1/health");
        addSourceIndexEntry(endpoints, "GET", "/v1/ops/snapshots/runtime", "Ops runtime snapshot", "Ops Snapshots", "ops.runtime.v1", "/ops/v1/runtime/*");
        addSourceIndexEntry(endpoints, "GET", "/v1/ops/snapshots/storage", "Ops storage snapshot", "Ops Snapshots", "ops.storage.v1", "/ops/v1/runtime/storage");
        addSourceIndexEntry(endpoints, "GET", "/v1/ops/snapshots/cluster", "Ops cluster snapshot", "Ops Snapshots", "ops.v1", "/ops/v1/cluster");
        addSourceIndexEntry(endpoints, "GET", "/v1/ops/snapshots/replication", "Ops replication snapshot", "Ops Snapshots", "ops.v1", "/ops/v1/replication");
        addSourceIndexEntry(endpoints, "GET", "/v1/ops/snapshots/queue", "Ops queue snapshot", "Ops Snapshots", "ops.v1", "/ops/v1/queue");
        addInternalIndexEntry(endpoints, "GET", "/v1/ops/operations/{operationId}", "Ops operation status", "Ops Snapshots", null);

        addSourceIndexEntry(endpoints, "GET", "/v1/events/recent?limit=50", "Recent events", "Events", "events.recent.v1", "/ops/v1/events/recent");
        addSourceIndexEntry(endpoints, "GET", "/v1/events/stats", "Event stats", "Events", "events.stats.v1", "/ops/v1/events/stats");
        addLocalDiagnosticIndexEntry(endpoints, "GET", "/v1/events/stream", "Event stream (SSE)", "Events", "/ops/v1/events/*");
        addLocalDiagnosticIndexEntry(endpoints, "GET", "/v1/ops/events/stream", "Ops event stream (SSE)", "Events", "/ops/v1/events/*");

        addSourceIndexEntry(endpoints, "GET", "/v1/gc/estimate", "GC estimate", "GC", "gc.estimate.v1", "/ops/v1/gc/estimate");
        addSourceIndexEntry(endpoints, "GET", "/v1/gc/status", "GC status", "GC", "gc.status.v1", "/ops/v1/gc/status");
        addInternalIndexEntry(endpoints, "POST", "/v1/propose-gc", "Propose GC operation", "GC", null);
        addInternalIndexEntry(endpoints, "POST", "/v1/gc/trigger", "Trigger GC check", "GC", null);
        addInternalIndexEntry(endpoints, "POST", "/v1/gc/execute", "Execute approved GC", "GC", null);
        addSourceIndexEntry(endpoints, "GET", "/v1/compaction/proposals", "Compaction proposals", "GC", "gc.compaction.proposals.v1", "/ops/v1/compaction/proposals");
        addInternalIndexEntry(endpoints, "GET", "/v1/gc/account/{walletAddress}", "GC account status", "GC Accounts", "/ops/v1/gc/account/{walletAddress}");
        addInternalIndexEntry(endpoints, "POST", "/v1/gc/account/{walletAddress}/pay?amount=X", "GC debt payment", "GC Accounts", null);
        addInternalIndexEntry(endpoints, "POST", "/v1/gc/account/{walletAddress}/set-limit?limit=X", "Set debt limit", "GC Accounts", null);
        addInternalIndexEntry(endpoints, "POST", "/v1/gc/account/{walletAddress}/execute-pending", "Execute pending debt", "GC Accounts", null);

        addSourceIndexEntry(endpoints, "GET", "/v1/fragmentation/metrics", "All fragmentation metrics", "Fragmentation", "fragmentation.metrics.v1", "/ops/v1/fragmentation/metrics");
        addSourceIndexEntry(endpoints, "GET", "/v1/fragmentation/metrics/{walletAddress}", "Fragmentation by wallet", "Fragmentation", "fragmentation.metrics.entity.v1", "/ops/v1/fragmentation/metrics/{walletAddress}");
        addSourceIndexEntry(endpoints, "GET", "/v1/fragmentation/top?limit=20", "Top fragmented wallets", "Fragmentation", "fragmentation.top.v1", "/ops/v1/fragmentation/top");

        addInternalIndexEntry(endpoints, "POST", "/v1/binary/declare-intent", "Declare binary upload intent", "Binary", null);
        addInternalIndexEntry(endpoints, "GET", "/v1/binary/check-intent/{token}", "Check binary intent", "Binary", null);
        addInternalIndexEntry(endpoints, "POST", "/v1/binary/complete-upload", "Complete binary upload", "Binary", null);

        addLocalUiIndexEntry(endpoints, "GET", "/api-browser", "Interactive API browser", "UI");
        addLocalUiIndexEntry(endpoints, "GET", "/dashboard", "Control-plane landing page", "UI");
        addLocalUiIndexEntry(endpoints, "GET", "/", "Control-plane landing page", "UI");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contractVersion", "index.v1");
        payload.put("surfaceRole", "validator-native");
        payload.put("surfaceAuthority", "runtime-and-source");
        payload.put("preferredBrowserContract", "/ops/v1/* via edge/gateway");
        payload.put("upstreamAuthority", "/ops/v1/*");
        payload.put("surfaceClasses", Arrays.asList("source", "local-ui", "local-diagnostic", "internal"));
        payload.put("intendedConsumers", Arrays.asList("operators", "edge-adapters", "automation", "cli"));
        payload.put("generatedAtMs", System.currentTimeMillis());
        payload.put("count", endpoints.size());
        payload.put("endpoints", endpoints);

        response.getWriter().write(JsonOutputUtil.toJson(payload));
    }

    // ========== Helper methods shared by dashboard handlers (explorer, api-browser, etc.) ==========

    private void addSourceIndexEntry(List<Map<String, Object>> endpoints,
                                     String method,
                                     String path,
                                     String description,
                                     String category,
                                     String contractVersion,
                                     String replacement) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("method", method);
        item.put("path", path);
        item.put("description", description);
        item.put("category", category);
        item.put("surfaceClass", "source");
        item.put("contractVersion", contractVersion);
        item.put("upstreamAllowed", true);
        item.put("replacement", replacement);
        endpoints.add(item);
    }

    private void addLocalUiIndexEntry(List<Map<String, Object>> endpoints,
                                      String method,
                                      String path,
                                      String description,
                                      String category) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("method", method);
        item.put("path", path);
        item.put("description", description);
        item.put("category", category);
        item.put("surfaceClass", "local-ui");
        item.put("upstreamAllowed", false);
        item.put("replacement", "/ops/v1/* via edge/gateway");
        endpoints.add(item);
    }

    private void addLocalDiagnosticIndexEntry(List<Map<String, Object>> endpoints,
                                              String method,
                                              String path,
                                              String description,
                                              String category,
                                              String replacement) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("method", method);
        item.put("path", path);
        item.put("description", description);
        item.put("category", category);
        item.put("surfaceClass", "local-diagnostic");
        item.put("upstreamAllowed", false);
        item.put("replacement", replacement);
        endpoints.add(item);
    }

    private void addInternalIndexEntry(List<Map<String, Object>> endpoints,
                                       String method,
                                       String path,
                                       String description,
                                       String category,
                                       String replacement) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("method", method);
        item.put("path", path);
        item.put("description", description);
        item.put("category", category);
        item.put("surfaceClass", "internal");
        item.put("upstreamAllowed", false);
        item.put("replacement", replacement);
        endpoints.add(item);
    }
    
    private String formatTimestamp(long epochMillis) {
        if (epochMillis <= 0) {
            return "-";
        }
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(epochMillis));
    }

    private String formatUptime(long uptimeMs) {
        if (uptimeMs <= 0) {
            return "0s";
        }
        long totalSeconds = uptimeMs / 1000;
        long days = totalSeconds / 86400;
        long hours = (totalSeconds % 86400) / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (days > 0) {
            return String.format("%dd %dh %dm", days, hours, minutes);
        }
        if (hours > 0) {
            return String.format("%dh %dm %ds", hours, minutes, seconds);
        }
        if (minutes > 0) {
            return String.format("%dm %ds", minutes, seconds);
        }
        return String.format("%ds", seconds);
    }

    private long asLong(Object value, long fallback) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return fallback;
    }

    private int asInt(Object value, int fallback) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return fallback;
    }

    private String safeString(Object value, String fallback) {
        return value != null ? value.toString() : fallback;
    }

    private int resolveLeaderNodeId(Map<String, Object> clusterState, String role, int nodeId) {
        int leaderNode = asInt(clusterState.get("leaderNodeId"),
            asInt(clusterState.get("leaderMemberId"), -1));
        if (leaderNode >= 0) {
            return leaderNode;
        }
        if ("LEADER".equalsIgnoreCase(role) && nodeId >= 0) {
            return nodeId;
        }
        String leaderUrl = safeString(clusterState.get("currentLeader"), null);
        if (leaderUrl != null) {
            int fromUrl = nodeIdFromValidatorUrl(leaderUrl);
            if (fromUrl >= 0) {
                return fromUrl;
            }
        }
        return -1;
    }

    private int nodeIdFromValidatorUrl(String url) {
        if (url == null || url.isEmpty()) {
            return -1;
        }
        try {
            URL parsed = new URL(url);
            int port = parsed.getPort();
            if (port >= 8090 && (port - 8090) % 2 == 0) {
                return (port - 8090) / 2;
            }
        } catch (Exception ignored) {
            // fall through
        }
        return -1;
    }

    private String shortName(String url) {
        if (url == null || url.isEmpty()) {
            return "Unknown";
        }
        try {
            URL parsed = new URL(url);
            return parsed.getHost();
        } catch (Exception e) {
            return url;
        }
    }
    
    /**
     * Handle blockchain explorer UI - Rich Etherscan-like interface.
     * Enhanced with IPFS links, property type indicators, and better visualization.
     * Now uses external template for consistent Blockchain AEM styling.
     */
    public void handleExplorerUI(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("text/html; charset=UTF-8");
        
        // Load template
        String template = loadTemplate("/explorer-template.html");
        
        // Get blockchain mode
        BlockchainConfig config = BlockchainConfig.getInstance();
        String modeClass, modeLabel;
        switch (config.getMode()) {
            case MOCK:
                modeClass = "mode-mock";
                modeLabel = "MOCK MODE";
                break;
            case SEPOLIA:
                modeClass = "mode-sepolia";
                modeLabel = "SEPOLIA";
                break;
            case MAINNET:
                modeClass = "mode-mainnet";
                modeLabel = "MAINNET";
                break;
            default:
                modeClass = "mode-mock";
                modeLabel = "UNKNOWN";
        }
        
        String html = template
            .replace("{{MODE_CLASS}}", modeClass)
            .replace("{{MODE_LABEL}}", modeLabel);
        
        response.getWriter().write(html);
    }
    
    /**
     * Handle interactive API Browser UI (HAL-style explorer).
     * Now uses external template for consistent Blockchain AEM styling.
     */
    public void handleApiBrowserUI(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("text/html; charset=UTF-8");
        
        // Load template
        String template = loadTemplate("/api-browser-template.html");
        
        // Get blockchain mode
        BlockchainConfig config = BlockchainConfig.getInstance();
        String modeClass, modeLabel;
        switch (config.getMode()) {
            case MOCK:
                modeClass = "mode-mock";
                modeLabel = "MOCK MODE";
                break;
            case SEPOLIA:
                modeClass = "mode-sepolia";
                modeLabel = "SEPOLIA";
                break;
            case MAINNET:
                modeClass = "mode-mainnet";
                modeLabel = "MAINNET";
                break;
            default:
                modeClass = "mode-mock";
                modeLabel = "UNKNOWN";
        }
        
        String html = template
            .replace("{{MODE_CLASS}}", modeClass)
            .replace("{{MODE_LABEL}}", modeLabel);
        
        response.getWriter().write(html);
    }
    
    /**
     * Load HTML template from resources.
     */
    private String loadTemplate(String resourcePath) throws IOException {
        try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IOException("Template not found: " + resourcePath);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    
}
