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
        final String externalDashboardUrl = System.getProperty("oak.dashboard.external.url", "");
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
        html.append("</style></head><body><div class='wrap'>");
        html.append("<h1>Oak Control Plane Home</h1>");
        html.append("<p>API-first runtime. The legacy in-process dashboard is retired from this entry point.</p>");
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
        html.append("<h2>Surfaces</h2>");
        html.append("<div class='links'>");
        html.append("<a class='link' href='/api-browser'><strong>API Browser</strong><div class='muted'>Interactive endpoint catalog and tester.</div></a>");
        html.append("<a class='link' href='/v1/consensus/status'><strong>/v1/consensus/status</strong><div class='muted'>Consensus status and leader context.</div></a>");
        html.append("<a class='link' href='/v1/proposals/queue/stats'><strong>/v1/proposals/queue/stats</strong><div class='muted'>Queue/finality/backpressure counters.</div></a>");
        html.append("<a class='link' href='/v1/config/osgi'><strong>/v1/config/osgi</strong><div class='muted'>Effective OSGi tuning values.</div></a>");
        html.append("<a class='link' href='/v1/explorer/summary'><strong>/v1/explorer/summary</strong><div class='muted'>Explorer contract for external blockscan UI.</div></a>");
        html.append("<a class='link' href='/health'><strong>/health</strong><div class='muted'>Shallow health.</div></a>");
        html.append("<a class='link' href='/health/deep'><strong>/health/deep</strong><div class='muted'>Deep dependency health.</div></a>");
        if (externalDashboardUrl != null && !externalDashboardUrl.trim().isEmpty()) {
            html.append("<a class='link' href='").append(FormatUtils.escapeHtml(externalDashboardUrl)).append("'><strong>External Ops Dashboard</strong><div class='muted'>Configured via -Doak.dashboard.external.url.</div></a>");
        }
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
        addIndexEntry(endpoints, "GET", "/health", "Shallow health", "Health");
        addIndexEntry(endpoints, "GET", "/health/deep", "Deep health", "Health");
        addIndexEntry(endpoints, "GET", "/api/metrics", "Consensus and replication metrics", "Health");
        addIndexEntry(endpoints, "GET", "/metrics", "Prometheus metrics", "Health");

        addIndexEntry(endpoints, "GET", "/v1/consensus/status", "Consensus status", "Consensus");
        addIndexEntry(endpoints, "POST", "/v1/propose-write", "Propose signed write", "Consensus");
        addIndexEntry(endpoints, "POST", "/v1/propose-delete", "Propose signed delete", "Consensus");
        addIndexEntry(endpoints, "GET", "/v1/proposals/pending/count", "Pending proposal count", "Consensus");
        addIndexEntry(endpoints, "GET", "/v1/proposals/queue/stats", "Queue and finality counters", "Consensus");
        addIndexEntry(endpoints, "GET", "/v1/proposals/epochs", "Proposal epoch flow", "Consensus");
        addIndexEntry(endpoints, "GET", "/v1/proposals/{id}/status", "Proposal status by id", "Consensus");
        addIndexEntry(endpoints, "GET", "/v1/head", "Head status", "Consensus");

        addIndexEntry(endpoints, "GET", "/v1/explorer/summary", "Explorer summary contract", "Explorer");
        addIndexEntry(endpoints, "GET", "/v1/explorer/epochs", "Explorer epoch flow", "Explorer");
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
     * DEPRECATED: Old inline explorer UI (kept for reference).
     */
    @SuppressWarnings("unused")
    private void handleExplorerUI_OLD(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("text/html; charset=UTF-8");
        
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>\n<html>\n<head>\n");
        html.append("<meta charset='UTF-8'>\n");
        html.append("<title>🔗 Oak Segment Consensus Explorer</title>\n");
        html.append("<style>\n");
        html.append("* { margin: 0; padding: 0; box-sizing: border-box; }\n");
        html.append("body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; ");
        html.append("background: #0f172a; color: #e2e8f0; min-height: 100vh; }\n");
        html.append(".header { background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); padding: 20px; ");
        html.append("box-shadow: 0 4px 6px rgba(0,0,0,0.3); }\n");
        html.append(".header h1 { font-size: 2em; margin-bottom: 5px; }\n");
        html.append(".header-subtitle { opacity: 0.9; }\n");
        html.append(".header-nav { margin-top: 10px; }\n");
        html.append(".header-nav a { color: white; text-decoration: none; margin-right: 20px; opacity: 0.8; }\n");
        html.append(".header-nav a:hover { opacity: 1; text-decoration: underline; }\n");
        html.append(".container { max-width: 1600px; margin: 0 auto; padding: 20px; }\n");
        html.append(".main-grid { display: grid; grid-template-columns: 1fr 400px; gap: 20px; }\n");
        html.append("@media (max-width: 1200px) { .main-grid { grid-template-columns: 1fr; } }\n");
        html.append(".panel { background: #1e293b; border-radius: 10px; padding: 20px; margin-bottom: 20px; ");
        html.append("border: 1px solid #334155; }\n");
        html.append(".panel h2 { color: #a78bfa; margin-bottom: 15px; font-size: 1.2em; display: flex; align-items: center; gap: 8px; }\n");
        html.append(".panel h3 { color: #94a3b8; margin: 20px 0 10px 0; font-size: 1em; }\n");
        
        // Breadcrumb styling
        html.append(".breadcrumb { padding: 12px 15px; background: #0f172a; border-radius: 8px; margin-bottom: 15px; ");
        html.append("font-family: monospace; font-size: 0.95em; display: flex; align-items: center; flex-wrap: wrap; gap: 5px; }\n");
        html.append(".breadcrumb a { color: #60a5fa; text-decoration: none; padding: 2px 6px; border-radius: 4px; }\n");
        html.append(".breadcrumb a:hover { background: #334155; text-decoration: underline; }\n");
        html.append(".breadcrumb .sep { color: #475569; }\n");
        
        // Node stats bar
        html.append(".node-stats { display: flex; gap: 15px; margin-bottom: 15px; flex-wrap: wrap; }\n");
        html.append(".stat-badge { background: #0f172a; padding: 8px 12px; border-radius: 6px; font-size: 0.85em; }\n");
        html.append(".stat-badge .label { color: #94a3b8; }\n");
        html.append(".stat-badge .value { color: #60a5fa; font-weight: 600; margin-left: 5px; }\n");
        
        // Tree nodes with better icons
        html.append(".tree-node { padding: 10px 12px; margin: 4px 0; background: #0f172a; border-radius: 6px; ");
        html.append("cursor: pointer; transition: all 0.2s; display: flex; align-items: center; gap: 10px; border-left: 3px solid transparent; }\n");
        html.append(".tree-node:hover { background: #1e293b; border-left-color: #8b5cf6; transform: translateX(3px); }\n");
        html.append(".tree-node .icon { font-size: 1.1em; }\n");
        html.append(".tree-node .name { color: #60a5fa; font-weight: 500; flex: 1; }\n");
        html.append(".tree-node .meta { color: #64748b; font-size: 0.8em; }\n");
        html.append(".tree-node.wallet { border-left-color: #10b981; }\n");
        html.append(".tree-node.content { border-left-color: #f59e0b; }\n");
        html.append(".tree-node.file { border-left-color: #ec4899; }\n");
        
        // Properties with type indicators
        html.append(".property { padding: 10px 12px; margin: 6px 0; background: #0f172a; border-radius: 6px; ");
        html.append("font-family: monospace; font-size: 0.9em; display: flex; align-items: flex-start; gap: 10px; }\n");
        html.append(".prop-icon { font-size: 1em; min-width: 20px; text-align: center; }\n");
        html.append(".prop-content { flex: 1; min-width: 0; }\n");
        html.append(".prop-name { color: #fbbf24; font-weight: 500; }\n");
        html.append(".prop-value { color: #34d399; word-break: break-all; }\n");
        html.append(".prop-type { color: #64748b; font-size: 0.75em; margin-left: 8px; }\n");
        
        // Special property types
        html.append(".prop-ipfs { background: linear-gradient(135deg, #0f172a 0%, #1a1a2e 100%); border-left: 3px solid #06b6d4; }\n");
        html.append(".prop-ipfs .prop-value { color: #06b6d4; }\n");
        html.append(".prop-ipfs a { color: #06b6d4; text-decoration: none; }\n");
        html.append(".prop-ipfs a:hover { text-decoration: underline; }\n");
        html.append(".prop-wallet { border-left: 3px solid #10b981; }\n");
        html.append(".prop-wallet .prop-value { color: #10b981; }\n");
        html.append(".prop-binary { border-left: 3px solid #ec4899; }\n");
        html.append(".prop-binary .prop-value { color: #ec4899; }\n");
        html.append(".prop-timestamp { border-left: 3px solid #f59e0b; }\n");
        
        // Segment entries
        html.append(".segment-entry { background: #0f172a; padding: 12px; margin: 8px 0; border-radius: 6px; ");
        html.append("border-left: 3px solid #8b5cf6; }\n");
        html.append(".segment-id { font-family: monospace; color: #60a5fa; font-size: 0.9em; }\n");
        html.append(".timestamp { color: #94a3b8; font-size: 0.85em; }\n");
        
        // Empty state
        html.append(".empty-state { text-align: center; padding: 30px; color: #64748b; }\n");
        html.append(".empty-state .icon { font-size: 2em; margin-bottom: 10px; }\n");
        
        // Loading spinner
        html.append(".loading { text-align: center; padding: 40px; color: #94a3b8; }\n");
        html.append(".loading::after { content: ''; display: inline-block; width: 20px; height: 20px; ");
        html.append("border: 2px solid #8b5cf6; border-top-color: transparent; border-radius: 50%; ");
        html.append("animation: spin 1s linear infinite; margin-left: 10px; vertical-align: middle; }\n");
        html.append("@keyframes spin { to { transform: rotate(360deg); } }\n");
        
        // Copy button
        html.append(".copy-btn { background: #334155; border: none; color: #94a3b8; padding: 4px 8px; ");
        html.append("border-radius: 4px; cursor: pointer; font-size: 0.75em; margin-left: 8px; }\n");
        html.append(".copy-btn:hover { background: #475569; color: #e2e8f0; }\n");
        
        html.append("</style>\n");
        
        // JavaScript
        html.append("<script>\n");
        html.append("let currentPath = '/';\n");
        html.append("let nodeData = null;\n\n");
        
        html.append("async function loadNode(path) {\n");
        html.append("  currentPath = path;\n");
        html.append("  document.getElementById('loading').style.display = 'block';\n");
        html.append("  document.getElementById('node-content').style.display = 'none';\n");
        html.append("  try {\n");
        html.append("    const response = await fetch('/api/explore?path=' + encodeURIComponent(path));\n");
        html.append("    nodeData = await response.json();\n");
        html.append("    if (nodeData.error) {\n");
        html.append("      displayError(nodeData.error);\n");
        html.append("    } else {\n");
        html.append("      displayNode(nodeData);\n");
        html.append("    }\n");
        html.append("  } catch (e) {\n");
        html.append("    displayError(e.message);\n");
        html.append("  }\n");
        html.append("  document.getElementById('loading').style.display = 'none';\n");
        html.append("  document.getElementById('node-content').style.display = 'block';\n");
        html.append("}\n\n");
        
        html.append("function displayError(msg) {\n");
        html.append("  document.getElementById('children').innerHTML = '<div class=\"empty-state\"><div class=\"icon\">⚠️</div><div>' + msg + '</div></div>';\n");
        html.append("  document.getElementById('properties').innerHTML = '';\n");
        html.append("  document.getElementById('node-stats').innerHTML = '';\n");
        html.append("}\n\n");
        
        html.append("function displayNode(node) {\n");
        // Breadcrumb
        html.append("  const breadcrumb = document.getElementById('breadcrumb');\n");
        html.append("  const parts = currentPath.split('/').filter(p => p);\n");
        html.append("  let path = '';\n");
        html.append("  breadcrumb.innerHTML = '<a href=\"#\" onclick=\"loadNode(\\'/\\'); return false;\">🏠 root</a>';\n");
        html.append("  parts.forEach((part, idx) => {\n");
        html.append("    path += '/' + part;\n");
        html.append("    const isLast = idx === parts.length - 1;\n");
        html.append("    const icon = getNodeIcon(part);\n");
        html.append("    breadcrumb.innerHTML += '<span class=\"sep\">/</span><a href=\"#\" onclick=\"loadNode(\\'' + path + '\\'); return false;\">' + icon + ' ' + part + '</a>';\n");
        html.append("  });\n\n");
        
        // Node stats
        html.append("  const stats = document.getElementById('node-stats');\n");
        html.append("  const childCount = node.children ? node.children.length : 0;\n");
        html.append("  const propCount = node.properties ? Object.keys(node.properties).length : 0;\n");
        html.append("  stats.innerHTML = '<div class=\"stat-badge\"><span class=\"label\">Children:</span><span class=\"value\">' + childCount + '</span></div>';\n");
        html.append("  stats.innerHTML += '<div class=\"stat-badge\"><span class=\"label\">Properties:</span><span class=\"value\">' + propCount + '</span></div>';\n");
        html.append("  if (node.properties && node.properties['jcr:primaryType']) {\n");
        html.append("    stats.innerHTML += '<div class=\"stat-badge\"><span class=\"label\">Type:</span><span class=\"value\">' + node.properties['jcr:primaryType'] + '</span></div>';\n");
        html.append("  }\n\n");
        
        // Children
        html.append("  const children = document.getElementById('children');\n");
        html.append("  children.innerHTML = '';\n");
        html.append("  if (node.children && node.children.length > 0) {\n");
        html.append("    node.children.sort().forEach(child => {\n");
        html.append("      const div = document.createElement('div');\n");
        html.append("      const nodeClass = getNodeClass(child);\n");
        html.append("      div.className = 'tree-node ' + nodeClass;\n");
        html.append("      const icon = getNodeIcon(child);\n");
        html.append("      div.innerHTML = '<span class=\"icon\">' + icon + '</span><span class=\"name\">' + child + '</span>';\n");
        html.append("      div.onclick = () => loadNode(currentPath === '/' ? '/' + child : currentPath + '/' + child);\n");
        html.append("      children.appendChild(div);\n");
        html.append("    });\n");
        html.append("  } else {\n");
        html.append("    children.innerHTML = '<div class=\"empty-state\"><div class=\"icon\">📭</div><div>No child nodes</div></div>';\n");
        html.append("  }\n\n");
        
        // Properties
        html.append("  const props = document.getElementById('properties');\n");
        html.append("  props.innerHTML = '';\n");
        html.append("  if (node.properties && Object.keys(node.properties).length > 0) {\n");
        html.append("    Object.entries(node.properties).sort((a,b) => a[0].localeCompare(b[0])).forEach(([key, value]) => {\n");
        html.append("      const div = document.createElement('div');\n");
        html.append("      const propClass = getPropClass(key, value);\n");
        html.append("      const propIcon = getPropIcon(key, value);\n");
        html.append("      div.className = 'property ' + propClass;\n");
        html.append("      const valueHtml = formatPropValue(key, value);\n");
        html.append("      div.innerHTML = '<span class=\"prop-icon\">' + propIcon + '</span>' +\n");
        html.append("        '<div class=\"prop-content\"><span class=\"prop-name\">' + key + '</span>' +\n");
        html.append("        '<span class=\"prop-type\">' + getPropType(value) + '</span><br>' +\n");
        html.append("        '<span class=\"prop-value\">' + valueHtml + '</span></div>';\n");
        html.append("      props.appendChild(div);\n");
        html.append("    });\n");
        html.append("  } else {\n");
        html.append("    props.innerHTML = '<div class=\"empty-state\"><div class=\"icon\">📋</div><div>No properties</div></div>';\n");
        html.append("  }\n");
        html.append("}\n\n");
        
        // Helper functions
        html.append("function getNodeIcon(name) {\n");
        html.append("  if (name.startsWith('0x')) return '👛';\n");
        html.append("  if (name === 'oak-chain') return '⛓️';\n");
        html.append("  if (name === 'content') return '📄';\n");
        html.append("  if (name.startsWith('file-')) return '🖼️';\n");
        html.append("  if (name.startsWith('page-')) return '📝';\n");
        html.append("  if (name === 'ethereum' || name === 'epoch') return '💎';\n");
        html.append("  if (name.match(/^[0-9a-f]{2}$/)) return '📂';\n");
        html.append("  return '📁';\n");
        html.append("}\n\n");
        
        html.append("function getNodeClass(name) {\n");
        html.append("  if (name.startsWith('0x')) return 'wallet';\n");
        html.append("  if (name === 'content') return 'content';\n");
        html.append("  if (name.startsWith('file-')) return 'file';\n");
        html.append("  return '';\n");
        html.append("}\n\n");
        
        html.append("function getPropClass(key, value) {\n");
        html.append("  if (key === 'wallet' || (typeof value === 'string' && value.startsWith('0x') && value.length === 42)) return 'prop-wallet';\n");
        html.append("  if (key === 'jcr:data' || (typeof value === 'string' && (value.startsWith('ipfs://') || value.startsWith('Qm')))) return 'prop-ipfs';\n");
        html.append("  if (typeof value === 'string' && value.includes('[Binary:')) return 'prop-binary';\n");
        html.append("  if (key === 'timestamp' || key.includes('Time') || key.includes('Date')) return 'prop-timestamp';\n");
        html.append("  return '';\n");
        html.append("}\n\n");
        
        html.append("function getPropIcon(key, value) {\n");
        html.append("  if (key === 'wallet') return '👛';\n");
        html.append("  if (key === 'jcr:data') return '📦';\n");
        html.append("  if (key === 'jcr:primaryType') return '🏷️';\n");
        html.append("  if (key === 'jcr:mimeType' || key === 'mimeType') return '📎';\n");
        html.append("  if (key === 'contentType') return '📋';\n");
        html.append("  if (key === 'message') return '💬';\n");
        html.append("  if (key === 'signature') return '✍️';\n");
        html.append("  if (key === 'source') return '🔗';\n");
        html.append("  if (key === 'timestamp') return '🕐';\n");
        html.append("  if (key === 'jcr:intentToken' || key === 'intentToken') return '🎫';\n");
        html.append("  if (key === 'jcr:pendingBinary') return '⏳';\n");
        html.append("  if (typeof value === 'string' && value.includes('[Binary:')) return '🖼️';\n");
        html.append("  return '📝';\n");
        html.append("}\n\n");
        
        html.append("function getPropType(value) {\n");
        html.append("  if (typeof value === 'string' && value.includes('[Binary:')) return 'BINARY';\n");
        html.append("  if (typeof value === 'boolean') return 'BOOLEAN';\n");
        html.append("  if (typeof value === 'number') return 'NUMBER';\n");
        html.append("  if (Array.isArray(value)) return 'ARRAY[' + value.length + ']';\n");
        html.append("  return 'STRING';\n");
        html.append("}\n\n");
        
        html.append("function formatPropValue(key, value) {\n");
        html.append("  const strValue = typeof value === 'string' ? value : JSON.stringify(value);\n");
        // IPFS CID detection
        html.append("  if (strValue.startsWith('ipfs://')) {\n");
        html.append("    const cid = strValue.replace('ipfs://', '');\n");
        html.append("    return '<a href=\"http://127.0.0.1:8080/ipfs/' + cid + '\" target=\"_blank\">🔗 ' + strValue + '</a> <button class=\"copy-btn\" onclick=\"copyText(\\'' + cid + '\\'); event.stopPropagation();\">Copy CID</button>';\n");
        html.append("  }\n");
        html.append("  if (strValue.startsWith('Qm') && strValue.length > 40) {\n");
        html.append("    return '<a href=\"http://127.0.0.1:8080/ipfs/' + strValue + '\" target=\"_blank\">🔗 ipfs://' + strValue + '</a> <button class=\"copy-btn\" onclick=\"copyText(\\'' + strValue + '\\'); event.stopPropagation();\">Copy CID</button>';\n");
        html.append("  }\n");
        // Binary data
        html.append("  if (strValue.includes('[Binary:')) {\n");
        html.append("    const match = strValue.match(/\\[Binary: (\\d+) bytes\\]/);\n");
        html.append("    if (match) {\n");
        html.append("      const bytes = parseInt(match[1]);\n");
        html.append("      const kb = (bytes / 1024).toFixed(1);\n");
        html.append("      return '📦 Binary Data (' + kb + ' KB) <span style=\"color:#64748b;font-size:0.85em;\">- stored in BlobStore</span>';\n");
        html.append("    }\n");
        html.append("  }\n");
        // Wallet address
        html.append("  if (strValue.startsWith('0x') && strValue.length === 42) {\n");
        html.append("    return strValue + ' <button class=\"copy-btn\" onclick=\"copyText(\\'' + strValue + '\\'); event.stopPropagation();\">Copy</button>';\n");
        html.append("  }\n");
        // Timestamp
        html.append("  if (key === 'timestamp' && /^\\d{13}$/.test(strValue)) {\n");
        html.append("    const date = new Date(parseInt(strValue));\n");
        html.append("    return strValue + ' <span style=\"color:#64748b;font-size:0.85em;\">(' + date.toLocaleString() + ')</span>';\n");
        html.append("  }\n");
        html.append("  return strValue;\n");
        html.append("}\n\n");
        
        html.append("function copyText(text) {\n");
        html.append("  navigator.clipboard.writeText(text);\n");
        html.append("}\n\n");
        
        // TAR files and recent segments
        html.append("async function loadTarFiles() {\n");
        html.append("  const response = await fetch('/api/segments/tars');\n");
        html.append("  const tars = await response.json();\n");
        html.append("  const container = document.getElementById('tar-files');\n");
        html.append("  container.innerHTML = '';\n");
        html.append("  if (tars.length === 0) {\n");
        html.append("    container.innerHTML = '<div class=\"empty-state\"><div class=\"icon\">💾</div><div>No TAR files</div></div>';\n");
        html.append("    return;\n");
        html.append("  }\n");
        html.append("  tars.forEach(tar => {\n");
        html.append("    const div = document.createElement('div');\n");
        html.append("    div.className = 'segment-entry';\n");
        html.append("    div.style.borderLeftColor = '#06b6d4';\n");
        html.append("    div.innerHTML = '<div class=\"segment-id\">💾 ' + tar.name + '</div>' +\n");
        html.append("      '<div class=\"timestamp\">' + tar.sizeFormatted + ' • ~' + tar.segmentCount + ' segments</div>';\n");
        html.append("    container.appendChild(div);\n");
        html.append("  });\n");
        html.append("}\n\n");
        
        html.append("async function loadRecentSegments() {\n");
        html.append("  const response = await fetch('/api/segments/recent');\n");
        html.append("  const segments = await response.json();\n");
        html.append("  const container = document.getElementById('recent-segments');\n");
        html.append("  container.innerHTML = '';\n");
        html.append("  if (segments.length === 0) {\n");
        html.append("    container.innerHTML = '<div class=\"empty-state\"><div class=\"icon\">📦</div><div>No recent segments</div></div>';\n");
        html.append("    return;\n");
        html.append("  }\n");
        html.append("  segments.slice(0, 10).forEach(seg => {\n");
        html.append("    const div = document.createElement('div');\n");
        html.append("    div.className = 'segment-entry';\n");
        html.append("    div.innerHTML = '<div class=\"segment-id\">' + seg.id.substring(0, 20) + '...</div>' +\n");
        html.append("      '<div class=\"timestamp\">' + seg.timestamp + '</div>';\n");
        html.append("    container.appendChild(div);\n");
        html.append("  });\n");
        html.append("}\n\n");
        
        html.append("window.onload = () => {\n");
        html.append("  const urlParams = new URLSearchParams(window.location.search);\n");
        html.append("  const path = urlParams.get('path') || '/';\n");
        html.append("  loadNode(path);\n");
        html.append("  loadTarFiles();\n");
        html.append("  loadRecentSegments();\n");
        html.append("  setInterval(loadRecentSegments, 5000);\n");
        html.append("};\n");
        html.append("</script>\n");
        html.append("</head>\n<body>\n");
        
        // Header
        html.append("<div class='header'>\n");
        html.append("<div class='container'>\n");
        html.append("<h1>🔗 Oak Segment Consensus Explorer</h1>\n");
        html.append("<div class='header-subtitle'>Content Browser & Segment Inspector</div>\n");
        html.append("<div class='header-nav'>\n");
        html.append("<a href='/'>← Dashboard</a>\n");
        html.append("<a href='/api-browser'>API Browser</a>\n");
        html.append("<a href='/api/explore?path=/'>JSON API</a>\n");
        html.append("</div>\n");
        html.append("</div></div>\n");
        
        // Main content
        html.append("<div class='container'>\n");
        html.append("<div class='main-grid'>\n");
        
        // Left column - Content tree
        html.append("<div>\n");
        html.append("<div class='panel'>\n");
        html.append("<h2>🌳 Content Tree</h2>\n");
        html.append("<div class='breadcrumb' id='breadcrumb'>/</div>\n");
        html.append("<div class='node-stats' id='node-stats'></div>\n");
        html.append("<div id='loading' class='loading' style='display:none'>Loading</div>\n");
        html.append("<div id='node-content'>\n");
        html.append("<h3>📂 Children</h3>\n");
        html.append("<div id='children'></div>\n");
        html.append("<h3>📋 Properties</h3>\n");
        html.append("<div id='properties'></div>\n");
        html.append("</div></div>\n");
        html.append("</div>\n");
        
        // Right column - Segments
        html.append("<div>\n");
        html.append("<div class='panel'>\n");
        html.append("<h2>💾 TAR Files</h2>\n");
        html.append("<div id='tar-files'></div>\n");
        html.append("</div>\n");
        html.append("<div class='panel'>\n");
        html.append("<h2>📦 Recent Segments</h2>\n");
        html.append("<div id='recent-segments'></div>\n");
        html.append("</div>\n");
        html.append("</div>\n");
        
        html.append("</div>\n"); // main-grid
        html.append("</div>\n"); // container
        html.append("</body>\n</html>");
        
        response.getWriter().write(html.toString());
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
     * DEPRECATED: Old inline API browser UI (kept for reference).
     */
    @SuppressWarnings("unused")
    private void handleApiBrowserUI_OLD(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("text/html; charset=UTF-8");
        
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>\n<html>\n<head>\n");
        html.append("<meta charset='UTF-8'>\n");
        html.append("<title>🧪 Oak Consensus API Browser</title>\n");
        html.append("<style>\n");
        html.append("* { margin: 0; padding: 0; box-sizing: border-box; }\n");
        html.append("body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; ");
        html.append("background: #0f172a; color: #e2e8f0; min-height: 100vh; }\n");
        html.append(".header { background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); padding: 20px; ");
        html.append("box-shadow: 0 4px 6px rgba(0,0,0,0.3); }\n");
        html.append(".header h1 { font-size: 2em; margin-bottom: 5px; }\n");
        html.append(".header p { opacity: 0.9; }\n");
        html.append(".container { max-width: 1600px; margin: 0 auto; padding: 20px; }\n");
        html.append(".api-categories { display: grid; grid-template-columns: repeat(auto-fit, minmax(300px, 1fr)); gap: 20px; margin: 20px 0; }\n");
        html.append(".category { background: #1e293b; border-radius: 10px; padding: 20px; border: 1px solid #334155; }\n");
        html.append(".category h2 { color: #a78bfa; margin-bottom: 15px; font-size: 1.2em; }\n");
        html.append(".endpoint { background: #0f172a; padding: 12px; margin: 8px 0; border-radius: 6px; ");
        html.append("cursor: pointer; border-left: 3px solid #8b5cf6; transition: all 0.2s; }\n");
        html.append(".endpoint:hover { background: #1e293b; transform: translateX(5px); }\n");
        html.append(".method { display: inline-block; padding: 3px 8px; border-radius: 4px; font-weight: 600; ");
        html.append("font-size: 0.75em; margin-right: 8px; }\n");
        html.append(".method-GET { background: #10b981; color: white; }\n");
        html.append(".method-POST { background: #3b82f6; color: white; }\n");
        html.append(".method-PUT { background: #f59e0b; color: white; }\n");
        html.append(".method-DELETE { background: #ef4444; color: white; }\n");
        html.append(".method-HEAD { background: #6b7280; color: white; }\n");
        html.append(".endpoint-path { font-family: monospace; color: #60a5fa; font-size: 0.9em; }\n");
        html.append(".endpoint-desc { color: #94a3b8; font-size: 0.85em; margin-top: 5px; }\n");
        html.append(".test-panel { background: #1e293b; border-radius: 10px; padding: 20px; margin: 20px 0; ");
        html.append("border: 1px solid #334155; display: none; }\n");
        html.append(".test-panel h3 { color: #a78bfa; margin-bottom: 15px; }\n");
        html.append(".form-group { margin: 15px 0; }\n");
        html.append(".form-group label { display: block; margin-bottom: 5px; color: #94a3b8; font-size: 0.9em; }\n");
        html.append("input, textarea { width: 100%; padding: 10px; background: #0f172a; border: 1px solid #334155; ");
        html.append("border-radius: 5px; color: #e2e8f0; font-family: monospace; }\n");
        html.append("textarea { min-height: 100px; font-size: 0.9em; }\n");
        html.append("button { padding: 10px 20px; background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); ");
        html.append("color: white; border: none; border-radius: 5px; cursor: pointer; font-weight: 600; }\n");
        html.append("button:hover { transform: scale(1.05); }\n");
        html.append(".response { background: #0f172a; border-radius: 5px; padding: 15px; margin: 15px 0; ");
        html.append("border-left: 3px solid #10b981; }\n");
        html.append(".response-header { color: #94a3b8; font-size: 0.85em; margin-bottom: 10px; }\n");
        html.append(".response-body { font-family: monospace; font-size: 0.85em; white-space: pre-wrap; ");
        html.append("word-wrap: break-word; color: #34d399; }\n");
        html.append(".back-link { display: inline-block; margin-bottom: 20px; color: #60a5fa; text-decoration: none; }\n");
        html.append(".back-link:hover { text-decoration: underline; }\n");
        html.append("</style>\n");
        html.append("</head>\n");
        html.append("<body>\n");
        
        html.append("<div class='header'>\n");
        html.append("<h1>🧪 Interactive API Browser</h1>\n");
        html.append("<p>Explore and test all Oak Segment Consensus APIs</p>\n");
        html.append("</div>\n");
        
        html.append("<div class='container'>\n");
        html.append("<a href='/' class='back-link'>← Back to Dashboard</a>\n");
        
        html.append("<div id='test-panel' class='test-panel'>\n");
        html.append("<h3 id='test-title'></h3>\n");
        html.append("<div id='test-form'></div>\n");
        html.append("<div id='response-container'></div>\n");
        html.append("</div>\n");
        
        html.append("<div class='api-categories'>\n");
        
        html.append("<div class='category'>\n");
        html.append("<h2>📊 Explorer APIs</h2>\n");
        addApiEndpoint(html, "GET", "/explorer", "Blockchain content explorer UI", "explorer");
        addApiEndpoint(html, "GET", "/api/explore?path=/", "Browse node tree structure (JSON)", "explore");
        addApiEndpoint(html, "GET", "/api/segments/tars", "List all TAR files and storage blocks (JSON)", "tars");
        addApiEndpoint(html, "GET", "/api/segments/recent", "Recent segment writes from journal (JSON)", "recent");
        html.append("</div>\n");
        
        html.append("<div class='category'>\n");
        html.append("<h2>💚 Health & Monitoring</h2>\n");
        addApiEndpoint(html, "GET", "/health", "Basic health check (JSON)", "health");
        addApiEndpoint(html, "GET", "/health/deep", "Comprehensive health validation (JSON)", "health_deep");
        addApiEndpoint(html, "GET", "/api/metrics", "Consensus & replication metrics (JSON)", "metrics");
        addApiEndpoint(html, "GET", "/metrics", "Prometheus metrics (text)", "prometheus");
        html.append("</div>\n");
        
        html.append("<div class='category'>\n");
        html.append("<h2>⚙️ Configuration</h2>\n");
        addApiEndpoint(html, "GET", "/v1/blockchain/config", "Get blockchain mode and network config (MOCK/SEPOLIA/MAINNET)", "blockchain_config");
        html.append("</div>\n");

        html.append("<div class='category'>\n");
        html.append("<h2>🧭 Explorer APIs (Phase 1)</h2>\n");
        addApiEndpoint(html, "GET", "/v1/explorer/summary", "Explorer summary for external blockscan UI", "explorer_summary");
        addApiEndpoint(html, "GET", "/v1/explorer/proposals/{proposalId}", "Explorer proposal detail by proposal ID", "explorer_proposal");
        addApiEndpoint(html, "GET", "/v1/explorer/wallets/{walletAddress}", "Explorer wallet detail + recent content", "explorer_wallet");
        addApiEndpoint(html, "GET", "/v1/explorer/epochs", "Explorer epoch flow snapshot", "explorer_epochs");
        html.append("</div>\n");
        
        html.append("<div class='category'>\n");
        html.append("<h2>🔄 Consensus APIs</h2>\n");
        addApiEndpoint(html, "GET", "/v1/consensus/status", "Get consensus state (Aeron-aware)", "consensus_status");
        addApiEndpoint(html, "POST", "/v1/propose-write", "Propose signed write transaction (signature verified via EthereumSignatureVerifier)", "propose_write");
        addApiEndpoint(html, "POST", "/v1/propose-delete", "Propose signed delete transaction", "propose_delete");
        addApiEndpoint(html, "POST", "/v1/binary/declare-intent", "Declare binary upload intent (returns intentToken)", "binary_declare_intent");
        addApiEndpoint(html, "GET", "/v1/binary/check-intent/{token}", "Check binary upload intent status (JSON)", "binary_check_intent");
        addApiEndpoint(html, "POST", "/v1/binary/complete-upload", "Complete binary upload with IPFS CID (JSON)", "binary_complete_upload");
        addApiEndpoint(html, "GET", "/v1/head", "Get latest HEAD ⚠️ DEPRECATED - use /v1/consensus/status instead (ADR-012)", "head");
        html.append("</div>\n");
        
        html.append("<div class='category'>\n");
        html.append("<h2>💰 Wallet Analytics</h2>\n");
        addApiEndpoint(html, "GET", "/v1/wallets/stats", "Get wallet statistics (write counts, sizes, costs)", "wallet_stats");
        addApiEndpoint(html, "GET", "/v1/wallets/content?wallet=0x...", "Get content by wallet address", "wallet_content");
        html.append("</div>\n");
        
        html.append("<div class='category'>\n");
        html.append("<h2>✈️ Aeron Cluster APIs</h2>\n");
        addApiEndpoint(html, "GET", "/v1/aeron/cluster-state", "Complete Aeron Cluster state (JSON)", "aeron_cluster_state");
        addApiEndpoint(html, "GET", "/v1/aeron/raft-metrics", "Raft-specific metrics (JSON)", "aeron_raft_metrics");
        addApiEndpoint(html, "GET", "/v1/aeron/node-status?nodeId=0", "Status of specific cluster node (JSON)", "aeron_node_status");
        addApiEndpoint(html, "GET", "/v1/aeron/leadership-history?limit=10", "Recent leadership changes (JSON)", "aeron_leadership_history");
        html.append("</div>\n");
        
        html.append("<div class='category'>\n");
        html.append("<h2>🌐 Registration & Discovery</h2>\n");
        addApiEndpoint(html, "POST", "/v1/register-client", "Register a Sling author client", "register_client");
        addApiEndpoint(html, "GET", "/v1/peers", "List all known validators (JSON)", "peers");
        addApiEndpoint(html, "GET", "/v1/ngrok-url", "Get public ngrok URL (text)", "ngrok");
        html.append("</div>\n");
        
        html.append("<div class='category'>\n");
        html.append("<h2>🗑️ Garbage Collection & Compaction</h2>\n");
        addApiEndpoint(html, "GET", "/v1/gc/estimate", "Estimate GC cost and reclaimable space (JSON)", "gc_estimate");
        addApiEndpoint(html, "GET", "/v1/gc/status", "Get GC proposal status and history (JSON)", "gc_status");
        addApiEndpoint(html, "POST", "/v1/propose-gc", "Propose a GC operation (replicated via Aeron Raft)", "propose_gc");
        addApiEndpoint(html, "POST", "/v1/gc/trigger", "Trigger automated GC check (testing/manual override)", "gc_trigger");
        addApiEndpoint(html, "POST", "/v1/gc/execute", "Manually execute an approved GC proposal (auto-executes on approval)", "gc_execute");
        addApiEndpoint(html, "GET", "/v1/compaction/proposals", "Get pending compaction proposals (JSON)", "compaction_proposals");
        html.append("</div>\n");
        
        html.append("<div class='category'>\n");
        html.append("<h2>💰 GC Account Management</h2>\n");
        addApiEndpoint(html, "GET", "/v1/gc/account/{walletAddress}", "Get GC account status for entity (JSON)", "gc_account_status");
        addApiEndpoint(html, "POST", "/v1/gc/account/{walletAddress}/pay?amount=X", "Record payment towards GC debt (JSON)", "gc_account_pay");
        addApiEndpoint(html, "POST", "/v1/gc/account/{walletAddress}/set-limit?limit=X", "Set GC debt limit for testing (JSON)", "gc_account_limit");
        addApiEndpoint(html, "POST", "/v1/gc/account/{walletAddress}/execute-pending", "Convert pending debt to executed debt (JSON)", "gc_account_execute");
        html.append("</div>\n");
        
        html.append("<div class='category'>\n");
        html.append("<h2>📊 Fragmentation Metrics</h2>\n");
        addApiEndpoint(html, "GET", "/v1/fragmentation/metrics", "Get fragmentation metrics for all entities (JSON)", "fragmentation_metrics");
        addApiEndpoint(html, "GET", "/v1/fragmentation/metrics/{walletAddress}", "Get fragmentation metrics for specific entity (JSON)", "fragmentation_entity");
        addApiEndpoint(html, "GET", "/v1/fragmentation/top?limit=20", "Get top N most fragmented entities (JSON)", "fragmentation_top");
        html.append("</div>\n");
        
        html.append("<div class='category'>\n");
        html.append("<h2>📋 Proposal Management</h2>\n");
        addApiEndpoint(html, "GET", "/v1/proposals/pending/count", "Get count of pending proposals (JSON)", "proposals_count");
        addApiEndpoint(html, "GET", "/v1/proposals/queue/stats", "Get proposal queue stats (JSON)", "proposals_queue_stats");
        addApiEndpoint(html, "GET", "/v1/proposals/epochs", "Get proposal epoch distribution and flow (JSON)", "proposals_epochs");
        addApiEndpoint(html, "GET", "/v1/proposals/{id}/status", "Get status of specific proposal (JSON)", "proposal_status");
        html.append("</div>\n");
        
        html.append("<div class='category'>\n");
        html.append("<h2>🤖 AI Chat (Optional)</h2>\n");
        addApiEndpoint(html, "POST", "/v1/chat", "LLM-powered chat interface (requires oak-segment-agentic)", "chat");
        html.append("</div>\n");
        
        html.append("<div class='category'>\n");
        html.append("<h2>🔧 Internal / Advanced APIs</h2>\n");
        addApiEndpoint(html, "POST", "/v1/follower/head-update", "Receive HEAD update from leader (internal cluster sync)", "follower_head_update");
        html.append("<div style='margin-top: 8px; padding: 8px; background: rgba(251, 191, 36, 0.1); border-left: 2px solid #fbbf24; border-radius: 4px; font-size: 0.85em; color: #fbbf24;'>⚠️ These endpoints are for internal cluster operations. Use with caution.</div>\n");
        html.append("</div>\n");
        
        html.append("<div class='category'>\n");
        html.append("<h2>📄 Oak Files</h2>\n");
        addApiEndpoint(html, "GET", "/journal.log", "Journal file (text)", "journal");
        addApiEndpoint(html, "GET", "/manifest", "Manifest file (text)", "manifest");
        addApiEndpoint(html, "HEAD", "/manifest", "Check manifest existence (HEAD)", "manifest_head");
        addApiEndpoint(html, "GET", "/gc.log", "Garbage collection log (text)", "gc");
        addApiEndpoint(html, "GET", "/segments/{id}", "Fetch segment by ID (binary)", "segment_get");
        addApiEndpoint(html, "HEAD", "/segments/{id}", "Check segment existence (HEAD)", "segment_head");
        html.append("</div>\n");
        
        html.append("</div>\n");
        
        html.append("<script>\n");
        html.append("function testEndpoint(method, path, id) {\n");
        html.append("  const panel = document.getElementById('test-panel');\n");
        html.append("  const title = document.getElementById('test-title');\n");
        html.append("  const form = document.getElementById('test-form');\n");
        html.append("  const responseContainer = document.getElementById('response-container');\n");
        html.append("  \n");
        html.append("  panel.style.display = 'block';\n");
        html.append("  title.textContent = method + ' ' + path;\n");
        html.append("  responseContainer.innerHTML = '';\n");
        html.append("  \n");
        html.append("  let formHtml = '';\n");
        html.append("  \n");
        html.append("  if (path.includes('{')) {\n");
        html.append("    formHtml += '<div class=\"form-group\">';\n");
        html.append("    formHtml += '<label>Path Parameters:</label>';\n");
        html.append("    formHtml += '<input type=\"text\" id=\"path-params\" placeholder=\"e.g., segment ID\" />';\n");
        html.append("    formHtml += '</div>';\n");
        html.append("  }\n");
        html.append("  \n");
        html.append("  if (path.includes('?')) {\n");
        html.append("    formHtml += '<div class=\"form-group\">';\n");
        html.append("    formHtml += '<label>Query Parameters:</label>';\n");
        html.append("    formHtml += '<input type=\"text\" id=\"query-params\" placeholder=\"e.g., path=/oak-chain\" value=\"' + (path.split('?')[1] || '') + '\" />';\n");
        html.append("    formHtml += '</div>';\n");
        html.append("  }\n");
        html.append("  \n");
        html.append("  if (method === 'POST' || method === 'PUT') {\n");
        html.append("    formHtml += '<div class=\"form-group\">';\n");
        html.append("    formHtml += '<label>Request Body (JSON):</label>';\n");
        html.append("    // Get example body and ensure it's valid JSON\n");
        html.append("    const exampleBody = getExampleBody(id);\n");
        html.append("    // Validate the example body is valid JSON\n");
        html.append("    try {\n");
        html.append("      JSON.parse(exampleBody);\n");
        html.append("    } catch (e) {\n");
        html.append("      console.error('Invalid example JSON for', id, ':', e);\n");
        html.append("    }\n");
        html.append("    formHtml += '<textarea id=\"request-body\" style=\"font-family: monospace; white-space: pre;\">' + exampleBody + '</textarea>';\n");
        html.append("    formHtml += '</div>';\n");
        html.append("  }\n");
        html.append("  \n");
        html.append("  formHtml += '<button onclick=\"sendRequest(\\'' + method + '\\', \\'' + path + '\\')\">Send Request</button>';\n");
        html.append("  form.innerHTML = formHtml;\n");
        html.append("  \n");
        html.append("  panel.scrollIntoView({ behavior: 'smooth' });\n");
        html.append("}\n");
        html.append("\n");
        html.append("function getExampleBody(id) {\n");
        html.append("  const examples = {\n");
        html.append("    'propose_write': JSON.stringify({wallet: '0x742d35Cc6634C0532925a3b844Bc9e7595f0bEb', message: 'Hello Blockchain!', contentType: 'page', signature: '0x...', clientId: 'test-client', paymentTier: 'STANDARD'}, null, 2),\n");
        html.append("    'register_client': JSON.stringify({clientId: 'sling-author-1', clientUrl: 'http://localhost:8080', walletAddress: '0xabc...def'}, null, 2),\n");
        html.append("    'propose_gc': JSON.stringify({walletAddress: '0x742d35Cc6634C0532925a3b844Bc9e7595f0bEb', targetRevision: null}, null, 2),\n");
        html.append("    'gc_execute': JSON.stringify({proposalId: 'uuid-here'}, null, 2),\n");
        html.append("    'follower_head_update': JSON.stringify({head: 'abc123...', epoch: 408488, leaderUrl: 'http://validator-1:8090'}, null, 2),\n");
        html.append("    'chat': JSON.stringify({query: 'What is the current cluster status?'}, null, 2)\n");
        html.append("  };\n");
        html.append("  return examples[id] || '{}';\n");
        html.append("}\n");
        html.append("\n");
        html.append("async function sendRequest(method, path) {\n");
        html.append("  const responseContainer = document.getElementById('response-container');\n");
        html.append("  responseContainer.innerHTML = '<div class=\"response\"><div class=\"response-header\">Sending request...</div></div>';\n");
        html.append("  \n");
        html.append("  try {\n");
        html.append("    let finalPath = path.split('?')[0];\n");
        html.append("    \n");
        html.append("    const pathParams = document.getElementById('path-params');\n");
        html.append("    if (pathParams && pathParams.value) {\n");
        html.append("      finalPath = finalPath.replace('{id}', pathParams.value);\n");
        html.append("    }\n");
        html.append("    \n");
        html.append("    const queryParams = document.getElementById('query-params');\n");
        html.append("    if (queryParams && queryParams.value) {\n");
        html.append("      finalPath += '?' + queryParams.value;\n");
        html.append("    }\n");
        html.append("    \n");
        html.append("    const options = { method };\n");
        html.append("    \n");
        html.append("    const bodyField = document.getElementById('request-body');\n");
        html.append("    if (bodyField && bodyField.value) {\n");
        html.append("      // Validate JSON before sending\n");
        html.append("      try {\n");
        html.append("        JSON.parse(bodyField.value);\n");
        html.append("      } catch (e) {\n");
        html.append("        throw new Error('Invalid JSON: ' + e.message);\n");
        html.append("      }\n");
        html.append("      options.headers = { 'Content-Type': 'application/json' };\n");
        html.append("      options.body = bodyField.value;\n");
        html.append("    }\n");
        html.append("    \n");
        html.append("    const startTime = Date.now();\n");
        html.append("    const response = await fetch(finalPath, options);\n");
        html.append("    const duration = Date.now() - startTime;\n");
        html.append("    \n");
        html.append("    const contentType = response.headers.get('content-type');\n");
        html.append("    let body;\n");
        html.append("    \n");
        html.append("    if (contentType && contentType.includes('application/json')) {\n");
        html.append("      body = JSON.stringify(await response.json(), null, 2);\n");
        html.append("    } else {\n");
        html.append("      body = await response.text();\n");
        html.append("    }\n");
        html.append("    \n");
        html.append("    responseContainer.innerHTML = \n");
        html.append("      '<div class=\"response\">' +\n");
        html.append("      '<div class=\"response-header\">Status: ' + response.status + ' ' + response.statusText + ' (' + duration + 'ms)</div>' +\n");
        html.append("      '<div class=\"response-body\">' + body + '</div>' +\n");
        html.append("      '</div>';\n");
        html.append("    \n");
        html.append("  } catch (error) {\n");
        html.append("    responseContainer.innerHTML = \n");
        html.append("      '<div class=\"response\" style=\"border-left-color: #ef4444;\">' +\n");
        html.append("      '<div class=\"response-header\">Error</div>' +\n");
        html.append("      '<div class=\"response-body\" style=\"color: #f87171;\">' + error.message + '</div>' +\n");
        html.append("      '</div>';\n");
        html.append("  }\n");
        html.append("}\n");
        html.append("</script>\n");
        
        html.append("</div>\n");
        html.append("</body>\n</html>\n");
        
        response.getWriter().write(html.toString());
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
     * DEPRECATED: Old inline chat UI (kept for reference).
     */
    @SuppressWarnings("unused")
    private void handleChatUI_OLD(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("text/html; charset=UTF-8");
        
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>\n<html>\n<head>\n");
        html.append("<meta charset='UTF-8'>\n");
        html.append("<title>💬 Oak LLM Chat</title>\n");
        html.append("<style>\n");
        html.append("* { margin: 0; padding: 0; box-sizing: border-box; }\n");
        html.append("body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; ");
        html.append("background: #0f172a; color: #e2e8f0; min-height: 100vh; display: flex; flex-direction: column; }\n");
        html.append(".header { background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); padding: 20px; ");
        html.append("box-shadow: 0 4px 6px rgba(0,0,0,0.3); }\n");
        html.append(".header h1 { font-size: 2em; margin-bottom: 5px; }\n");
        html.append(".header p { opacity: 0.9; }\n");
        html.append(".container { max-width: 1200px; margin: 0 auto; padding: 20px; flex: 1; display: flex; flex-direction: column; }\n");
        html.append(".back-link { display: inline-block; margin-bottom: 20px; color: #60a5fa; text-decoration: none; }\n");
        html.append(".back-link:hover { text-decoration: underline; }\n");
        html.append(".chat-container { flex: 1; display: flex; flex-direction: column; background: #1e293b; ");
        html.append("border-radius: 10px; border: 1px solid #334155; overflow: hidden; }\n");
        html.append(".chat-messages { flex: 1; overflow-y: auto; padding: 20px; display: flex; flex-direction: column; gap: 16px; }\n");
        html.append(".message { padding: 12px 16px; border-radius: 8px; max-width: 85%; word-wrap: break-word; }\n");
        html.append(".message.user { background: #334155; align-self: flex-end; border-left: 3px solid #60a5fa; }\n");
        html.append(".message.assistant { background: #0f172a; align-self: flex-start; border-left: 3px solid #a78bfa; }\n");
        html.append(".message-header { font-size: 0.85em; color: #94a3b8; margin-bottom: 8px; font-weight: 600; }\n");
        html.append(".message-content { line-height: 1.6; color: #e2e8f0; }\n");
        html.append(".message-sources { margin-top: 12px; padding-top: 12px; border-top: 1px solid #334155; }\n");
        html.append(".source { background: #0f172a; padding: 8px 12px; margin-top: 8px; border-radius: 6px; ");
        html.append("border-left: 2px solid #8b5cf6; font-size: 0.85em; }\n");
        html.append(".source-type { color: #a78bfa; font-weight: 600; margin-right: 8px; }\n");
        html.append(".source-data { color: #94a3b8; font-family: monospace; font-size: 0.9em; margin-top: 4px; ");
        html.append("white-space: pre-wrap; word-wrap: break-word; max-height: 200px; overflow-y: auto; }\n");
        html.append(".chat-input-container { padding: 20px; background: #0f172a; border-top: 1px solid #334155; }\n");
        html.append(".input-group { display: flex; gap: 12px; }\n");
        html.append("textarea { flex: 1; padding: 12px; background: #1e293b; border: 1px solid #334155; ");
        html.append("border-radius: 8px; color: #e2e8f0; font-family: inherit; font-size: 0.95em; ");
        html.append("resize: vertical; min-height: 60px; max-height: 200px; }\n");
        html.append("textarea:focus { outline: none; border-color: #667eea; }\n");
        html.append("button { padding: 12px 24px; background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); ");
        html.append("color: white; border: none; border-radius: 8px; cursor: pointer; font-weight: 600; ");
        html.append("font-size: 0.95em; transition: transform 0.2s; }\n");
        html.append("button:hover:not(:disabled) { transform: scale(1.05); }\n");
        html.append("button:disabled { opacity: 0.5; cursor: not-allowed; }\n");
        html.append(".loading { display: inline-block; width: 16px; height: 16px; border: 2px solid #667eea; ");
        html.append("border-top-color: transparent; border-radius: 50%; animation: spin 0.8s linear infinite; }\n");
        html.append("@keyframes spin { to { transform: rotate(360deg); } }\n");
        html.append(".examples { margin-top: 20px; padding: 16px; background: #1e293b; border-radius: 8px; ");
        html.append("border: 1px solid #334155; }\n");
        html.append(".examples h3 { color: #a78bfa; margin-bottom: 12px; font-size: 0.95em; }\n");
        html.append(".example-chip { display: inline-block; padding: 6px 12px; margin: 4px; background: #0f172a; ");
        html.append("border: 1px solid #334155; border-radius: 6px; color: #94a3b8; font-size: 0.85em; ");
        html.append("cursor: pointer; transition: all 0.2s; }\n");
        html.append(".example-chip:hover { background: #334155; color: #e2e8f0; border-color: #667eea; }\n");
        html.append(".empty-state { text-align: center; padding: 60px 20px; color: #94a3b8; }\n");
        html.append(".empty-state h2 { color: #a78bfa; margin-bottom: 12px; }\n");
        html.append(".mode-toggle-container { display: flex; align-items: center; gap: 12px; margin-top: 16px; padding-top: 16px; border-top: 1px solid #334155; }\n");
        html.append(".mode-toggle-label { font-size: 0.9rem; color: #94a3b8; font-weight: 500; }\n");
        html.append(".mode-toggle { position: relative; display: inline-block; width: 60px; height: 30px; }\n");
        html.append(".mode-toggle input { opacity: 0; width: 0; height: 0; }\n");
        html.append(".mode-toggle-slider { position: absolute; cursor: pointer; top: 0; left: 0; right: 0; bottom: 0; background-color: #334155; transition: 0.3s; border-radius: 30px; }\n");
        html.append(".mode-toggle-slider:before { position: absolute; content: \"\"; height: 22px; width: 22px; left: 4px; bottom: 4px; background-color: white; transition: 0.3s; border-radius: 50%; }\n");
        html.append(".mode-toggle input:checked + .mode-toggle-slider { background-color: #667eea; }\n");
        html.append(".mode-toggle input:checked + .mode-toggle-slider:before { transform: translateX(30px); }\n");
        html.append(".mode-indicator { font-size: 0.85rem; color: #667eea; font-weight: 600; }\n");
        html.append(".mode-indicator.agent-mode { color: #a78bfa; }\n");
        html.append("</style>\n");
        html.append("</head>\n");
        html.append("<body>\n");
        
        html.append("<div class='header'>\n");
        html.append("<h1>💬 LLM Chat Assistant</h1>\n");
        html.append("<p>Ask questions about Oak internals, query validator state, and get AI-powered answers</p>\n");
        html.append("<div class='mode-toggle-container'>\n");
        html.append("<span class='mode-toggle-label'>Chat Mode:</span>\n");
        html.append("<label class='mode-toggle'>\n");
        html.append("<input type='checkbox' id='agentModeToggle' onchange='toggleAgentMode()'>\n");
        html.append("<span class='mode-toggle-slider'></span>\n");
        html.append("</label>\n");
        html.append("<span class='mode-indicator' id='modeIndicator'>User ↔ LLM</span>\n");
        html.append("</div>\n");
        html.append("</div>\n");
        
        html.append("<div class='container'>\n");
        html.append("<a href='/' class='back-link'>← Back to Dashboard</a>\n");
        
        html.append("<div class='chat-container'>\n");
        html.append("<div class='chat-messages' id='chat-messages'>\n");
        html.append("<div class='empty-state'>\n");
        html.append("<h2>💬 Start a conversation</h2>\n");
        html.append("<p>Ask me anything about Oak validators, cluster state, or consensus mechanisms</p>\n");
        html.append("</div>\n");
        html.append("</div>\n");
        
        html.append("<div class='chat-input-container'>\n");
        html.append("<div class='examples'>\n");
        html.append("<h3>💡 Example questions:</h3>\n");
        html.append("<span class='example-chip' onclick=\"setQuery('What is the current cluster status?')\">What is the current cluster status?</span>\n");
        html.append("<span class='example-chip' onclick=\"setQuery('How many validators are active?')\">How many validators are active?</span>\n");
        html.append("<span class='example-chip' onclick=\"setQuery('What is the current leader?')\">What is the current leader?</span>\n");
        html.append("<span class='example-chip' onclick=\"setQuery('Explain how FileStore works in Oak')\">Explain how FileStore works in Oak</span>\n");
        html.append("<span class='example-chip' onclick=\"setQuery('What is the consensus status?')\">What is the consensus status?</span>\n");
        html.append("</div>\n");
        html.append("<div class='input-group'>\n");
        html.append("<textarea id='query-input' placeholder='Ask a question about Oak validators, cluster state, or consensus...' ");
        html.append("rows='2'></textarea>\n");
        html.append("<button id='send-button' onclick='sendMessage()'>Send</button>\n");
        html.append("</div>\n");
        html.append("</div>\n");
        
        html.append("</div>\n");
        html.append("</div>\n");
        
        html.append("<script>\n");
        html.append("const chatMessages = document.getElementById('chat-messages');\n");
        html.append("const queryInput = document.getElementById('query-input');\n");
        html.append("const sendButton = document.getElementById('send-button');\n");
        html.append("const agentModeToggle = document.getElementById('agentModeToggle');\n");
        html.append("const modeIndicator = document.getElementById('modeIndicator');\n");
        html.append("let conversationHistory = [];\n");
        html.append("let isAgentMode = false;\n");
        html.append("\n");
        html.append("function toggleAgentMode() {\n");
        html.append("  isAgentMode = agentModeToggle.checked;\n");
        html.append("  if (isAgentMode) {\n");
        html.append("    modeIndicator.textContent = 'Agent ↔ Agent';\n");
        html.append("    modeIndicator.classList.add('agent-mode');\n");
        html.append("    conversationHistory = [];\n");
        html.append("  } else {\n");
        html.append("    modeIndicator.textContent = 'User ↔ LLM';\n");
        html.append("    modeIndicator.classList.remove('agent-mode');\n");
        html.append("    conversationHistory = [];\n");
        html.append("  }\n");
        html.append("}\n");
        html.append("\n");
        html.append("function setQuery(text) {\n");
        html.append("  queryInput.value = text;\n");
        html.append("  queryInput.focus();\n");
        html.append("}\n");
        html.append("\n");
        html.append("function addMessage(role, content, sources) {\n");
        html.append("  const emptyState = chatMessages.querySelector('.empty-state');\n");
        html.append("  if (emptyState) emptyState.remove();\n");
        html.append("  \n");
        html.append("  const messageDiv = document.createElement('div');\n");
        html.append("  messageDiv.className = 'message ' + role;\n");
        html.append("  \n");
        html.append("  let html = '<div class=\"message-header\">' + (role === 'user' ? '👤 You' : '🤖 Assistant') + '</div>';\n");
        html.append("  html += '<div class=\"message-content\">' + escapeHtml(content) + '</div>';\n");
        html.append("  \n");
        html.append("  if (sources && sources.length > 0) {\n");
        html.append("    html += '<div class=\"message-sources\">';\n");
        html.append("    html += '<div style=\"color: #94a3b8; font-size: 0.85em; margin-bottom: 8px;\">📚 Sources:</div>';\n");
        html.append("    sources.forEach(source => {\n");
        html.append("      html += '<div class=\"source\">';\n");
        html.append("      html += '<span class=\"source-type\">' + escapeHtml(source.type || 'unknown') + '</span>';\n");
        html.append("      if (source.endpoint) {\n");
        html.append("        html += '<span style=\"color: #60a5fa;\">' + escapeHtml(source.endpoint) + '</span>';\n");
        html.append("      }\n");
        html.append("      if (source.data) {\n");
        html.append("        const dataStr = typeof source.data === 'string' ? source.data : JSON.stringify(source.data, null, 2);\n");
        html.append("        html += '<div class=\"source-data\">' + escapeHtml(dataStr.substring(0, 500)) + (dataStr.length > 500 ? '...' : '') + '</div>';\n");
        html.append("      }\n");
        html.append("      html += '</div>';\n");
        html.append("    });\n");
        html.append("    html += '</div>';\n");
        html.append("  }\n");
        html.append("  \n");
        html.append("  messageDiv.innerHTML = html;\n");
        html.append("  chatMessages.appendChild(messageDiv);\n");
        html.append("  chatMessages.scrollTop = chatMessages.scrollHeight;\n");
        html.append("}\n");
        html.append("\n");
        html.append("function escapeHtml(text) {\n");
        html.append("  const div = document.createElement('div');\n");
        html.append("  div.textContent = text;\n");
        html.append("  return div.innerHTML;\n");
        html.append("}\n");
        html.append("\n");
        html.append("async function sendMessage() {\n");
        html.append("  const query = queryInput.value.trim();\n");
        html.append("  if (!query) return;\n");
        html.append("  \n");
        html.append("  // Add user message\n");
        html.append("  addMessage('user', query);\n");
        html.append("  \n");
        html.append("  // Clear input and disable button\n");
        html.append("  queryInput.value = '';\n");
        html.append("  sendButton.disabled = true;\n");
        html.append("  sendButton.innerHTML = '<span class=\"loading\"></span> Thinking...';\n");
        html.append("  \n");
        html.append("  // Add loading message\n");
        html.append("  const loadingDiv = document.createElement('div');\n");
        html.append("  loadingDiv.className = 'message assistant';\n");
        html.append("  loadingDiv.id = 'loading-message';\n");
        html.append("  loadingDiv.innerHTML = '<div class=\"message-header\">🤖 Assistant</div><div class=\"message-content\"><span class=\"loading\"></span> Processing your question...</div>';\n");
        html.append("  chatMessages.appendChild(loadingDiv);\n");
        html.append("  chatMessages.scrollTop = chatMessages.scrollHeight;\n");
        html.append("  \n");
        html.append("  try {\n");
        html.append("    const requestBody = { query: query };\n");
        html.append("    if (isAgentMode) {\n");
        html.append("      requestBody.context = {\n");
        html.append("        agentToAgent: true,\n");
        html.append("        conversationHistory: conversationHistory\n");
        html.append("      };\n");
        html.append("    }\n");
        html.append("    \n");
        html.append("    const response = await fetch('/v1/chat', {\n");
        html.append("      method: 'POST',\n");
        html.append("      headers: { 'Content-Type': 'application/json' },\n");
        html.append("      body: JSON.stringify(requestBody)\n");
        html.append("    });\n");
        html.append("    \n");
        html.append("    if (!response.ok) {\n");
        html.append("      const errorText = await response.text();\n");
        html.append("      throw new Error('HTTP ' + response.status + ': ' + errorText);\n");
        html.append("    }\n");
        html.append("    \n");
        html.append("    const data = await response.json();\n");
        html.append("    \n");
        html.append("    // Remove loading message\n");
        html.append("    const loadingMsg = document.getElementById('loading-message');\n");
        html.append("    if (loadingMsg) loadingMsg.remove();\n");
        html.append("    \n");
        html.append("    // Update conversation history for agent-to-agent mode\n");
        html.append("    if (isAgentMode) {\n");
        html.append("      conversationHistory.push({ role: 'user', content: query });\n");
        html.append("      conversationHistory.push({ role: 'assistant', content: data.answer || 'No answer provided' });\n");
        html.append("    }\n");
        html.append("    \n");
        html.append("    // Add assistant response\n");
        html.append("    let answerText = data.answer || 'No answer provided';\n");
        html.append("    if (isAgentMode && data.agentMetadata) {\n");
        html.append("      answerText += '\\n\\n--- Agent Info ---\\n';\n");
        html.append("      if (data.agentMetadata.respondingAgentId) {\n");
        html.append("        answerText += 'Responding Agent: ' + data.agentMetadata.respondingAgentId + '\\n';\n");
        html.append("      }\n");
        html.append("      if (data.agentMetadata.respondingAgentType) {\n");
        html.append("        answerText += 'Agent Type: ' + data.agentMetadata.respondingAgentType + '\\n';\n");
        html.append("      }\n");
        html.append("    }\n");
        html.append("    addMessage('assistant', answerText, data.sources || []);\n");
        html.append("    \n");
        html.append("  } catch (error) {\n");
        html.append("    // Remove loading message\n");
        html.append("    const loadingMsg = document.getElementById('loading-message');\n");
        html.append("    if (loadingMsg) loadingMsg.remove();\n");
        html.append("    \n");
        html.append("    // Add error message\n");
        html.append("    addMessage('assistant', '❌ Error: ' + error.message + '\\n\\nMake sure Ollama is running and the phi3 model is installed:\\n\\n' + \n");
        html.append("      '```\\n' +\n");
        html.append("      'ollama pull phi3\\n' +\n");
        html.append("      '```', []);\n");
        html.append("  } finally {\n");
        html.append("    sendButton.disabled = false;\n");
        html.append("    sendButton.innerHTML = 'Send';\n");
        html.append("    queryInput.focus();\n");
        html.append("  }\n");
        html.append("}\n");
        html.append("\n");
        html.append("// Allow Enter to send (Shift+Enter for new line)\n");
        html.append("queryInput.addEventListener('keydown', (e) => {\n");
        html.append("  if (e.key === 'Enter' && !e.shiftKey) {\n");
        html.append("    e.preventDefault();\n");
        html.append("    sendMessage();\n");
        html.append("  }\n");
        html.append("});\n");
        html.append("</script>\n");
        
        html.append("</body>\n</html>\n");
        
        response.getWriter().write(html.toString());
    }
    
    /**
     * Helper method to add an API endpoint to the browser UI.
     */
    private void addApiEndpoint(StringBuilder html, String method, String path, String description, String id) {
        html.append("<div class='endpoint' onclick='testEndpoint(\"").append(method).append("\", \"").append(path).append("\", \"").append(id).append("\")'>\n");
        html.append("<span class='method method-").append(method).append("'>").append(method).append("</span>\n");
        html.append("<span class='endpoint-path'>").append(FormatUtils.escapeHtml(path)).append("</span>\n");
        html.append("<div class='endpoint-desc'>").append(FormatUtils.escapeHtml(description)).append("</div>\n");
        html.append("</div>\n");
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
    
    /**
     * Get validator wallet address from ServerContext.
     */
    private String getValidatorWalletAddress() {
        // ADR 046: Show cluster wallet (payment destination), not node wallet
        String address = context.clusterWalletAddress;
        if (address == null || address.equals("0x0000000000000000000000000000000000000000")) {
            // Fallback to node wallet if no cluster wallet configured
            address = context.validatorWalletAddress;
        }
        return address != null ? address : "0x0000000000000000000000000000000000000000";
    }
    
}
