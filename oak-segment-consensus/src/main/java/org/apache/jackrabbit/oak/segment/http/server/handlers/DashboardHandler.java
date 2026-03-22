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
import java.math.BigInteger;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
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
        addIndexEntry(endpoints, "GET", "/v1/index", "Live API discovery index", "Discovery");
        addIndexEntry(endpoints, "GET", "/v1/config/osgi", "Effective OSGi config values", "Configuration");
        addIndexEntry(endpoints, "GET", "/v1/config/osgi/schema", "OSGi config metadata schema", "Configuration");
        addIndexEntry(endpoints, "GET", "/v1/config/osgi/sources", "OSGi config source map", "Configuration");
        addIndexEntry(endpoints, "GET", "/v1/config/osgi/coverage", "OSGi config coverage and missing keys", "Configuration");
        addIndexEntry(endpoints, "GET", "/v1/config/osgi/delta", "OSGi config values drift from defaults", "Configuration");
        addIndexEntry(endpoints, "GET", "/health", "Shallow health", "Health");
        addIndexEntry(endpoints, "GET", "/health/deep", "Deep health", "Health");
        addIndexEntry(endpoints, "GET", "/api/metrics", "Consensus and replication metrics", "Health");
        addIndexEntry(endpoints, "GET", "/metrics", "Prometheus metrics", "Health");

        addIndexEntry(endpoints, "GET", "/v1/consensus/status", "Consensus status", "Consensus");
        addIndexEntry(endpoints, "POST", "/v1/propose-write", "Propose signed write", "Consensus");
        addIndexEntry(endpoints, "POST", "/v1/propose-delete", "Propose signed delete", "Consensus");
        addIndexEntry(endpoints, "GET", "/v1/proposals/pending/count", "Pending proposal count", "Consensus");
        addIndexEntry(endpoints, "GET", "/v1/proposals/queue/stats", "Queue and finality counters", "Consensus");
        addIndexEntry(endpoints, "GET", "/v1/proposals/release-flow", "Adaptive proposal release flow", "Consensus");
        addIndexEntry(endpoints, "GET", "/v1/proposals/epochs", "Proposal epoch flow compatibility overlay", "Consensus");
        addIndexEntry(endpoints, "GET", "/v1/proposals/{id}/status", "Proposal status by id", "Consensus");
        addIndexEntry(endpoints, "GET", "/v1/head", "Head status", "Consensus");

        addIndexEntry(endpoints, "GET", "/v1/explorer/summary", "Explorer summary contract", "Explorer");
        addIndexEntry(endpoints, "GET", "/v1/explorer/release-flow", "Explorer adaptive release flow", "Explorer");
        addIndexEntry(endpoints, "GET", "/v1/explorer/epochs", "Explorer epoch flow compatibility overlay", "Explorer");
        addIndexEntry(endpoints, "GET", "/v1/explorer/proposals/{proposalId}", "Explorer proposal detail", "Explorer");
        addIndexEntry(endpoints, "GET", "/v1/explorer/wallets/{walletAddress}", "Explorer wallet detail", "Explorer");
        addIndexEntry(endpoints, "GET", "/explorer", "Explorer UI", "Explorer");
        addIndexEntry(endpoints, "GET", "/api/explore?path=/", "Node tree browse API", "Explorer");
        addIndexEntry(endpoints, "GET", "/api/segments/recent", "Recent segments", "Explorer");
        addIndexEntry(endpoints, "GET", "/api/segments/tars", "TAR file listing", "Explorer");
        addIndexEntry(endpoints, "GET", "/api/blob/{blobId}", "Blob stream by blob id", "Explorer");
        addIndexEntry(endpoints, "GET", "/api/cid/{oakBlobId}", "CID mapping by Oak blob id", "Explorer");
        addIndexEntry(endpoints, "GET", "/api/cid/stats", "CID mapping stats", "Explorer");
        addIndexEntry(endpoints, "GET", "/api/cid/reverse/{cid}", "Reverse CID lookup", "Explorer");

        addIndexEntry(endpoints, "GET", "/v1/wallets/stats", "Wallet usage and counts", "Wallets");
        addIndexEntry(endpoints, "GET", "/v1/wallets/content?wallet=0x...", "Wallet content query", "Wallets");
        addIndexEntry(endpoints, "POST|PUT", "/v1/register-client", "Register client", "Registration");
        addIndexEntry(endpoints, "GET", "/v1/peers", "Peer list", "Registration");
        addIndexEntry(endpoints, "GET", "/v1/ngrok-url", "Current ngrok URL", "Registration");
        addIndexEntry(endpoints, "GET", "/v1/blockchain/config", "Blockchain mode config", "Configuration");

        addIndexEntry(endpoints, "GET", "/v1/aeron/cluster-state", "Aeron cluster state", "Aeron");
        addIndexEntry(endpoints, "GET", "/v1/aeron/validator-identities", "Validator identity map", "Aeron");
        addIndexEntry(endpoints, "GET", "/v1/aeron/raft-metrics", "Raft metrics", "Aeron");
        addIndexEntry(endpoints, "GET", "/v1/aeron/node-status?nodeId=0", "Per-node status", "Aeron");
        addIndexEntry(endpoints, "GET", "/v1/aeron/leadership-history?limit=10", "Leadership history", "Aeron");
        addIndexEntry(endpoints, "GET", "/v1/aeron/replication-lag", "Replication lag", "Aeron");
        addIndexEntry(endpoints, "POST", "/v1/follower/head-update", "Follower head update (internal)", "Aeron");

        addIndexEntry(endpoints, "GET", "/v1/ops/snapshots/health", "Ops health snapshot", "Ops Snapshots");
        addIndexEntry(endpoints, "GET", "/v1/ops/snapshots/cluster", "Ops cluster snapshot", "Ops Snapshots");
        addIndexEntry(endpoints, "GET", "/v1/ops/snapshots/replication", "Ops replication snapshot", "Ops Snapshots");
        addIndexEntry(endpoints, "GET", "/v1/ops/snapshots/queue", "Ops queue snapshot", "Ops Snapshots");
        addIndexEntry(endpoints, "GET", "/v1/ops/operations/{operationId}", "Ops operation status", "Ops Snapshots");

        addIndexEntry(endpoints, "GET", "/v1/events/recent?limit=50", "Recent events", "Events");
        addIndexEntry(endpoints, "GET", "/v1/events/stats", "Event stats", "Events");
        addIndexEntry(endpoints, "GET", "/v1/events/stream", "Event stream (SSE)", "Events");
        addIndexEntry(endpoints, "GET", "/v1/ops/events/stream", "Ops event stream (SSE)", "Events");

        addIndexEntry(endpoints, "GET", "/v1/gc/estimate", "GC estimate", "GC");
        addIndexEntry(endpoints, "GET", "/v1/gc/status", "GC status", "GC");
        addIndexEntry(endpoints, "POST", "/v1/propose-gc", "Propose GC operation", "GC");
        addIndexEntry(endpoints, "POST", "/v1/gc/trigger", "Trigger GC check", "GC");
        addIndexEntry(endpoints, "POST", "/v1/gc/execute", "Execute approved GC", "GC");
        addIndexEntry(endpoints, "GET", "/v1/compaction/proposals", "Compaction proposals", "GC");
        addIndexEntry(endpoints, "GET", "/v1/gc/account/{walletAddress}", "GC account status", "GC Accounts");
        addIndexEntry(endpoints, "POST", "/v1/gc/account/{walletAddress}/pay?amount=X", "GC debt payment", "GC Accounts");
        addIndexEntry(endpoints, "POST", "/v1/gc/account/{walletAddress}/set-limit?limit=X", "Set debt limit", "GC Accounts");
        addIndexEntry(endpoints, "POST", "/v1/gc/account/{walletAddress}/execute-pending", "Execute pending debt", "GC Accounts");

        addIndexEntry(endpoints, "GET", "/v1/fragmentation/metrics", "All fragmentation metrics", "Fragmentation");
        addIndexEntry(endpoints, "GET", "/v1/fragmentation/metrics/{walletAddress}", "Fragmentation by wallet", "Fragmentation");
        addIndexEntry(endpoints, "GET", "/v1/fragmentation/top?limit=20", "Top fragmented wallets", "Fragmentation");

        addIndexEntry(endpoints, "POST", "/v1/binary/declare-intent", "Declare binary upload intent", "Binary");
        addIndexEntry(endpoints, "GET", "/v1/binary/check-intent/{token}", "Check binary intent", "Binary");
        addIndexEntry(endpoints, "POST", "/v1/binary/complete-upload", "Complete binary upload", "Binary");

        addIndexEntry(endpoints, "POST", "/api/mock/advance-epoch?epochs=1", "Advance mock epoch", "Mock");
        addIndexEntry(endpoints, "POST", "/api/mock/set-epoch-offset?offset=0", "Set mock epoch offset", "Mock");
        addIndexEntry(endpoints, "GET", "/api/mock/epoch-status", "Mock epoch status", "Mock");

        addIndexEntry(endpoints, "POST", "/v1/chat", "Agentic chat endpoint", "LLM");

        addIndexEntry(endpoints, "GET", "/api-browser", "Interactive API browser", "UI");
        addIndexEntry(endpoints, "GET", "/chat", "Chat UI", "UI");
        addIndexEntry(endpoints, "GET", "/dashboard", "Control-plane landing page", "UI");
        addIndexEntry(endpoints, "GET", "/", "Control-plane landing page", "UI");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contractVersion", "index.v1");
        payload.put("generatedAtMs", System.currentTimeMillis());
        payload.put("count", endpoints.size());
        payload.put("endpoints", endpoints);

        response.getWriter().write(JsonOutputUtil.toJson(payload));
    }

    // ========== Helper methods shared by dashboard handlers (explorer, api-browser, etc.) ==========

    private void addIndexEntry(List<Map<String, Object>> endpoints,
                               String method,
                               String path,
                               String description,
                               String category) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("method", method);
        item.put("path", path);
        item.put("description", description);
        item.put("category", category);
        endpoints.add(item);
    }
    
    private void appendSummaryCard(StringBuilder html, String label, String value, String caption) {
        html.append("<div class='card'>");
        html.append("<div class='card-label'>").append(FormatUtils.escapeHtml(label)).append("</div>");
        html.append("<div class='card-value'>").append(FormatUtils.escapeHtml(value)).append("</div>");
        if (caption != null && !caption.isEmpty()) {
            html.append("<div class='card-caption'>").append(FormatUtils.escapeHtml(caption)).append("</div>");
        }
        html.append("</div>");
    }
    
    private void appendMiniCard(StringBuilder html, String label, String value, String color, String caption) {
        html.append("<div style='background: rgba(15,23,42,0.6); padding: 12px; border-radius: 6px; border-left: 3px solid ").append(color).append(";'>\n");
        html.append("<div style='color: #94a3b8; font-size: 0.75em; margin-bottom: 6px;'>").append(FormatUtils.escapeHtml(label)).append("</div>\n");
        html.append("<div style='font-size: 1.5em; font-weight: 600; color: ").append(color).append(";'>").append(FormatUtils.escapeHtml(value)).append("</div>\n");
        html.append("<div style='color: #94a3b8; font-size: 0.75em; margin-top: 6px;'>").append(FormatUtils.escapeHtml(caption)).append("</div>\n");
        html.append("</div>\n");
    }

    private String formatTimestamp(long epochMillis) {
        if (epochMillis <= 0) {
            return "-";
        }
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(epochMillis));
    }

    private String formatRelativeTime(long epochMillis) {
        if (epochMillis <= 0) {
            return "-";
        }
        long diffMillis = System.currentTimeMillis() - epochMillis;
        if (diffMillis < 1000) {
            return "just now";
        }
        long seconds = diffMillis / 1000;
        if (seconds < 60) {
            return seconds + "s ago";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes + "m ago";
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return hours + "h ago";
        }
        long days = hours / 24;
        return days + "d ago";
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

    private String safeUrl(String url) {
        return url != null ? url : "-";
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

    private String describeNode(String url, int memberId) {
        String name = shortName(url);
        if (memberId >= 0) {
            return name + " (#" + memberId + ")";
        }
        return name;
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
     * Format Wei to ETH (simplified - assumes 18 decimals).
     */
    private String formatWeiToEth(BigInteger wei) {
        if (wei == null || wei.equals(BigInteger.ZERO)) {
            return "0";
        }
        // Simple formatting: divide by 10^18
        BigInteger eth = wei.divide(BigInteger.valueOf(10).pow(18));
        BigInteger remainder = wei.remainder(BigInteger.valueOf(10).pow(18));
        if (remainder.equals(BigInteger.ZERO)) {
            return eth.toString();
        }
        String remainderStr = remainder.toString();
        // Pad with zeros if needed
        while (remainderStr.length() < 18) {
            remainderStr = "0" + remainderStr;
        }
        // Take first 6 decimal places
        return eth.toString() + "." + remainderStr.substring(0, Math.min(6, remainderStr.length()));
    }
    
    private String formatLeaderLabel(String leaderUrl) {
        if (leaderUrl == null || leaderUrl.isEmpty()) {
            return "Unknown";
        }
        String name = shortName(leaderUrl);
        if (leaderUrl.equals(context.selfUrl)) {
            return name + " (self)";
        }
        return name;
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
     * Handle LLM Chat UI interface.
     * Now uses external template for consistent Blockchain AEM styling.
     */
    public void handleChatUI(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("text/html; charset=UTF-8");
        
        // Load template
        String template = loadTemplate("/chat-template.html");
        
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
