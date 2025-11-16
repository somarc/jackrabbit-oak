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
import org.apache.jackrabbit.oak.segment.http.server.ServerContext;
import org.apache.jackrabbit.oak.segment.http.server.util.DashboardDataService;
import org.apache.jackrabbit.oak.segment.http.server.util.FormatUtils;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URL;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
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
     * Handle dashboard homepage with live statistics.
     */
    public void handleDashboard(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("text/html; charset=UTF-8");

        Map<String, Object> clusterState = dataService.getAeronClusterState();
        List<AeronConsensusEngine.LeadershipChange> leadershipHistory = dataService.getLeadershipHistory(10);
        DashboardDataService.FileStoreStats fileStoreStats = dataService.getFileStoreStats();
        int clientCount = context.registeredClients.size();

        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>\n");
        html.append("<html>\n<head>\n");
        html.append("<meta charset='UTF-8'>\n");
        html.append("<meta name='viewport' content='width=device-width, initial-scale=1.0'>\n");
        html.append("<title>🔗 Oak Segment Consensus - Global Store</title>\n");
        html.append("<style>\n");
        html.append("body { margin: 0; font-family: 'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; background: #020617; color: #e2e8f0; }\n");
        html.append(".container { max-width: 1200px; margin: 0 auto; padding: 32px 24px 64px; }\n");
        html.append("h1 { font-size: 2.6em; margin-bottom: 8px; }\n");
        html.append(".subtitle { color: #94a3b8; margin-bottom: 32px; }\n");
        html.append(".summary-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 16px; margin-bottom: 32px; }\n");
        html.append(".card { background: rgba(15,23,42,0.85); border: 1px solid rgba(148,163,184,0.15); border-radius: 12px; padding: 20px; }\n");
        html.append(".card h2 { margin-top: 0; margin-bottom: 12px; font-size: 1.3em; }\n");
        html.append(".card-label { font-size: 0.75em; letter-spacing: 0.08em; text-transform: uppercase; color: #94a3b8; margin-bottom: 8px; }\n");
        html.append(".card-value { font-size: 1.7em; font-weight: 600; color: #f8fafc; word-break: break-word; }\n");
        html.append(".card-caption { margin-top: 6px; font-size: 0.85em; color: #94a3b8; }\n");
        html.append(".table-card table { width: 100%; border-collapse: collapse; margin-top: 4px; }\n");
        html.append(".table-card th { text-align: left; padding: 12px; font-size: 0.75em; letter-spacing: 0.08em; text-transform: uppercase; color: #94a3b8; border-bottom: 1px solid rgba(148,163,184,0.2); }\n");
        html.append(".table-card td { padding: 12px; border-bottom: 1px solid rgba(148,163,184,0.08); }\n");
        html.append(".table-card tr:hover { background: rgba(148,163,184,0.08); }\n");
        html.append(".badge { display: inline-flex; align-items: center; gap: 6px; padding: 2px 8px; border-radius: 999px; font-size: 0.75em; font-weight: 600; text-transform: uppercase; letter-spacing: 0.05em; }\n");
        html.append(".badge-leader { background: rgba(250,204,21,0.15); color: #facc15; }\n");
        html.append(".badge-follower { background: rgba(59,130,246,0.15); color: #60a5fa; }\n");
        html.append(".badge-self { background: rgba(52,211,153,0.18); color: #34d399; }\n");
        html.append(".empty-state { padding: 32px; border-radius: 12px; border: 1px dashed rgba(148,163,184,0.25); background: rgba(15,23,42,0.6); color: #94a3b8; margin-top: 24px; }\n");
        html.append(".api-links { display: flex; flex-wrap: wrap; gap: 12px; margin-top: 28px; }\n");
        html.append(".api-links a { padding: 10px 16px; border-radius: 999px; border: 1px solid rgba(148,163,184,0.2); color: #38bdf8; text-decoration: none; font-size: 0.85em; transition: background 0.2s, color 0.2s; }\n");
        html.append(".api-links a:hover { background: rgba(56,189,248,0.15); color: #0ea5e9; }\n");
        html.append(".action-card { text-align: center; padding: 40px 20px; }\n");
        html.append(".action-card a { display: inline-block; padding: 15px 40px; background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); color: white; text-decoration: none; border-radius: 8px; font-weight: 600; font-size: 1.1em; box-shadow: 0 4px 12px rgba(102, 126, 234, 0.4); transition: transform 0.2s; }\n");
        html.append(".action-card a:hover { transform: scale(1.05); }\n");
        html.append(".client-list { margin-top: 12px; font-size: 0.85em; }\n");
        html.append(".client-item { padding: 6px 0; color: #cbd5e1; }\n");
        html.append("</style>\n");
        html.append("</head>\n<body>\n");
        html.append("<div class='container'>\n");
        html.append("<h1>🔗 Oak Segment Consensus</h1>\n");
        html.append("<div class='subtitle'>Global P2P Oak Repository</div>\n");

        if (clusterState == null) {
            html.append("<div class='empty-state'>");
            html.append("Aeron Cluster is not initialised yet. Check validator logs and ensure the consensus engine is running.");
            html.append("</div>\n");
            
            // Still show Oak stats and Connected Peers even if Aeron isn't initialized
            html.append("<div class='summary-grid'>");
            appendSummaryCard(html, "Store Size", FormatUtils.formatBytes(fileStoreStats.size), fileStoreStats.segmentCount + " segments");
            appendSummaryCard(html, "Connected Peers", String.valueOf(clientCount), "AEM/Sling author instances");
            html.append("</div>\n");
            
            // Connected Peers Card
            html.append("<div class='card'>\n");
            html.append("<h2>🌐 Connected Peers</h2>\n");
            html.append("<div class='card-value'>").append(clientCount).append("</div>\n");
            html.append("<div class='card-caption'>AEM/Sling author instances</div>\n");
            if (!context.registeredClients.isEmpty()) {
                html.append("<div class='client-list'>");
                int shown = 0;
                for (org.apache.jackrabbit.oak.segment.http.server.model.ClientRegistration reg : context.registeredClients.values()) {
                    if (shown >= 5) {
                        html.append("<div class='client-item'>... and ").append(context.registeredClients.size() - 5).append(" more</div>");
                        break;
                    }
                    // Display wallet address as primary identifier
                    if (reg.walletAddress != null && !reg.walletAddress.isEmpty()) {
                        String walletDisplay = reg.walletAddress;
                        if (walletDisplay.length() > 42) {
                            walletDisplay = walletDisplay.substring(0, 42);
                        }
                        html.append("<div class='client-item'>• ").append(FormatUtils.escapeHtml(walletDisplay));
                        if (reg.clientId != null && !reg.clientId.isEmpty()) {
                            html.append(" <span style='color: #64748b; font-size: 0.85em;'>(ID: ").append(FormatUtils.escapeHtml(reg.clientId.length() > 20 ? reg.clientId.substring(0, 17) + "..." : reg.clientId)).append(")</span>");
                        }
                        html.append("</div>");
                    } else {
                        // Fallback to client ID if no wallet
                        String displayName = reg.clientId;
                        if (displayName.length() > 30) {
                            displayName = displayName.substring(0, 27) + "...";
                        }
                        html.append("<div class='client-item'>• ").append(FormatUtils.escapeHtml(displayName)).append("</div>");
                    }
                    shown++;
                }
                html.append("</div>");
            }
            html.append("</div>\n");
            
            // Content Explorer, API Explorer, and Chat Cards
            html.append("<div class='summary-grid' style='grid-template-columns: repeat(auto-fit, minmax(300px, 1fr)); margin-top: 32px;'>");
            html.append("<div class='card action-card'>\n");
            html.append("<h2>🔍 Content Explorer</h2>\n");
            html.append("<p style='margin: 20px 0; opacity: 0.9;'>Browse the global repository content tree and inspect segments</p>\n");
            html.append("<a href='/explorer'>Launch Explorer →</a>\n");
            html.append("</div>\n");
            html.append("<div class='card action-card'>\n");
            html.append("<h2>🧪 API Browser</h2>\n");
            html.append("<p style='margin: 20px 0; opacity: 0.9;'>Interactive API explorer and testing interface</p>\n");
            html.append("<a href='/api-browser'>Launch API Browser →</a>\n");
            html.append("</div>\n");
            html.append("<div class='card action-card'>\n");
            html.append("<h2>💬 LLM Chat</h2>\n");
            html.append("<p style='margin: 20px 0; opacity: 0.9;'>Ask questions about Oak internals and query validator state</p>\n");
            html.append("<a href='/chat'>Launch Chat →</a>\n");
            html.append("</div>\n");
            html.append("</div>\n");
        } else {
            String role = safeString(clusterState.get("role"), "UNKNOWN");
            boolean isLeader = Boolean.TRUE.equals(clusterState.get("isLeader"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> members = (List<Map<String, Object>>) clusterState.get("members");
            if (members == null) {
                members = Collections.emptyList();
            }

            String leaderUrl = (String) clusterState.get("currentLeader");
            if (leaderUrl == null && isLeader) {
                leaderUrl = context.selfUrl;
            }

            int memberCount = asInt(clusterState.get("memberCount"), members.size());
            int memberId = asInt(clusterState.get("memberId"), -1);
            int term = asInt(clusterState.get("term"), -1);
            long clusterTime = asLong(clusterState.get("clusterTime"), -1L);
            long logPosition = asLong(clusterState.get("logPosition"), -1L);

            html.append("<div class='summary-grid'>");
            appendSummaryCard(html, "Role", role, isLeader ? "This validator currently owns leadership" : "Following elected leader");
            appendSummaryCard(html, "Leader", formatLeaderLabel(leaderUrl), leaderUrl == null ? "Leader discovery pending" : (leaderUrl.equals(context.selfUrl) ? "This node is the leader" : "Tracking elected leader"));
            appendSummaryCard(html, "Term", term >= 0 ? String.valueOf(term) : "Not available", "Leadership term reported by Aeron");
            appendSummaryCard(html, "Member ID", memberId >= 0 ? "#" + memberId : "Unknown", "Aeron-assigned member identifier");
            appendSummaryCard(html, "Cluster Time", clusterTime > 0 ? formatTimestamp(clusterTime) : "Not available", clusterTime > 0 ? formatRelativeTime(clusterTime) : "-");
            appendSummaryCard(html, "Log Position", logPosition >= 0 ? String.format("%,d", logPosition) : "Not available", "Current replicated log index");
            appendSummaryCard(html, "Members", String.valueOf(memberCount), "Validators participating in this cluster");
            appendSummaryCard(html, "Store Size", FormatUtils.formatBytes(fileStoreStats.size), fileStoreStats.segmentCount + " segments");
            appendSummaryCard(html, "Connected Peers", String.valueOf(clientCount), "AEM/Sling author instances");
            html.append("</div>\n");

            html.append("<div class='card table-card'>\n");
            html.append("<h2>Cluster Members</h2>\n");
            if (members.isEmpty()) {
                html.append("<div class='card-caption'>No members reported by Aeron yet.</div>\n");
            } else {
                html.append("<table>\n<thead><tr><th>Node</th><th>Role</th><th>Status</th><th>URL</th></tr></thead><tbody>\n");
                for (Map<String, Object> member : members) {
                    String memberUrl = (String) member.get("url");
                    String memberRole = safeString(member.get("role"), "UNKNOWN");
                    String status = safeString(member.get("status"), "ACTIVE");
                    int nodeId = asInt(member.get("memberId"), -1);
                    boolean isMemberLeader = "LEADER".equalsIgnoreCase(memberRole) || (memberUrl != null && memberUrl.equals(leaderUrl));
                    boolean isMemberSelf = memberUrl != null && memberUrl.equals(context.selfUrl);

                    html.append("<tr>");
                    html.append("<td>");
                    html.append(FormatUtils.escapeHtml(describeNode(memberUrl, nodeId)));
                    if (isMemberSelf) {
                        html.append(" <span class='badge badge-self'>SELF</span>");
                    }
                    html.append("</td>");

                    html.append("<td>");
                    String badgeClass = isMemberLeader ? "badge badge-leader" : "badge badge-follower";
                    html.append("<span class='").append(badgeClass).append("'>");
                    html.append(FormatUtils.escapeHtml(memberRole));
                    html.append("</span>");
                    html.append("</td>");

                    html.append("<td>").append(FormatUtils.escapeHtml(status)).append("</td>");
                    html.append("<td>").append(FormatUtils.escapeHtml(safeUrl(memberUrl))).append("</td>");
                    html.append("</tr>\n");
                }
                html.append("</tbody></table>\n");
            }
            html.append("</div>\n");

            html.append("<div class='card table-card'>\n");
            html.append("<h2>Leadership History</h2>\n");
            if (leadershipHistory.isEmpty()) {
                html.append("<div class='card-caption'>No leadership rotations have been recorded yet.</div>\n");
            } else {
                html.append("<table class='history-table'>\n<thead><tr><th>Time</th><th>Relative</th><th>Change</th><th>Term</th><th>Node</th></tr></thead><tbody>\n");
                for (AeronConsensusEngine.LeadershipChange change : leadershipHistory) {
                    String previousRole = change.previousRole != null ? change.previousRole.name() : "UNKNOWN";
                    String newRole = change.newRole != null ? change.newRole.name() : "UNKNOWN";
                    html.append("<tr>");
                    html.append("<td>").append(FormatUtils.escapeHtml(formatTimestamp(change.timestamp))).append("</td>");
                    html.append("<td>").append(FormatUtils.escapeHtml(formatRelativeTime(change.timestamp))).append("</td>");
                    html.append("<td>").append(FormatUtils.escapeHtml(previousRole + " → " + newRole)).append("</td>");
                    html.append("<td>").append(change.term >= 0 ? String.valueOf(change.term) : "-").append("</td>");
                    html.append("<td>").append(FormatUtils.escapeHtml(describeNode(change.memberUrl, change.memberId))).append("</td>");
                    html.append("</tr>\n");
                }
                html.append("</tbody></table>\n");
            }
            html.append("</div>\n");

            // Connected Peers Card
            html.append("<div class='card'>\n");
            html.append("<h2>🌐 Connected Peers</h2>\n");
            html.append("<div class='card-value'>").append(clientCount).append("</div>\n");
            html.append("<div class='card-caption'>AEM/Sling author instances</div>\n");
            if (!context.registeredClients.isEmpty()) {
                html.append("<div class='client-list'>");
                int shown = 0;
                for (org.apache.jackrabbit.oak.segment.http.server.model.ClientRegistration reg : context.registeredClients.values()) {
                    if (shown >= 5) {
                        html.append("<div class='client-item'>... and ").append(context.registeredClients.size() - 5).append(" more</div>");
                        break;
                    }
                    // Display wallet address as primary identifier
                    if (reg.walletAddress != null && !reg.walletAddress.isEmpty()) {
                        String walletDisplay = reg.walletAddress;
                        if (walletDisplay.length() > 42) {
                            walletDisplay = walletDisplay.substring(0, 42);
                        }
                        html.append("<div class='client-item'>• ").append(FormatUtils.escapeHtml(walletDisplay));
                        if (reg.clientId != null && !reg.clientId.isEmpty()) {
                            html.append(" <span style='color: #64748b; font-size: 0.85em;'>(ID: ").append(FormatUtils.escapeHtml(reg.clientId.length() > 20 ? reg.clientId.substring(0, 17) + "..." : reg.clientId)).append(")</span>");
                        }
                        html.append("</div>");
                    } else {
                        // Fallback to client ID if no wallet
                        String displayName = reg.clientId;
                        if (displayName.length() > 30) {
                            displayName = displayName.substring(0, 27) + "...";
                        }
                        html.append("<div class='client-item'>• ").append(FormatUtils.escapeHtml(displayName)).append("</div>");
                    }
                    shown++;
                }
                html.append("</div>");
            }
            html.append("</div>\n");

            // Content Explorer, API Explorer, and Chat Cards
            html.append("<div class='summary-grid' style='grid-template-columns: repeat(auto-fit, minmax(300px, 1fr)); margin-top: 32px;'>");
            html.append("<div class='card action-card'>\n");
            html.append("<h2>🔍 Content Explorer</h2>\n");
            html.append("<p style='margin: 20px 0; opacity: 0.9;'>Browse the global repository content tree and inspect segments</p>\n");
            html.append("<a href='/explorer'>Launch Explorer →</a>\n");
            html.append("</div>\n");
            html.append("<div class='card action-card'>\n");
            html.append("<h2>🧪 API Browser</h2>\n");
            html.append("<p style='margin: 20px 0; opacity: 0.9;'>Interactive API explorer and testing interface</p>\n");
            html.append("<a href='/api-browser'>Launch API Browser →</a>\n");
            html.append("</div>\n");
            html.append("<div class='card action-card'>\n");
            html.append("<h2>💬 LLM Chat</h2>\n");
            html.append("<p style='margin: 20px 0; opacity: 0.9;'>Ask questions about Oak internals and query validator state</p>\n");
            html.append("<a href='/chat'>Launch Chat →</a>\n");
            html.append("</div>\n");
            html.append("</div>\n");

            html.append("<div class='api-links'>\n");
            html.append("<a href='/v1/aeron/cluster-state' target='_blank'>View cluster-state JSON</a>\n");
            html.append("<a href='/v1/aeron/leadership-history' target='_blank'>View leadership history JSON</a>\n");
            html.append("<a href='/v1/consensus/status' target='_blank'>View consensus status JSON</a>\n");
            html.append("</div>\n");
        }

        html.append("</div>\n</body>\n</html>");

        response.getWriter().write(html.toString());
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
     * Handle blockchain explorer UI - Etherscan-like interface.
     */
    public void handleExplorerUI(HttpServletResponse response) throws IOException {
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
        html.append(".container { max-width: 1400px; margin: 0 auto; padding: 20px; }\n");
        html.append(".panel { background: #1e293b; border-radius: 10px; padding: 20px; margin: 20px 0; ");
        html.append("border: 1px solid #334155; }\n");
        html.append(".panel h2 { color: #a78bfa; margin-bottom: 15px; font-size: 1.3em; }\n");
        html.append(".tree-node { padding: 8px; margin: 4px 0; background: #0f172a; border-radius: 5px; ");
        html.append("cursor: pointer; transition: all 0.2s; }\n");
        html.append(".tree-node:hover { background: #1e293b; transform: translateX(5px); }\n");
        html.append(".node-name { color: #60a5fa; font-weight: 500; }\n");
        html.append(".node-type { color: #94a3b8; font-size: 0.9em; margin-left: 10px; }\n");
        html.append(".property { padding: 5px; margin: 3px 0; font-family: monospace; font-size: 0.9em; }\n");
        html.append(".prop-name { color: #fbbf24; }\n");
        html.append(".prop-value { color: #34d399; }\n");
        html.append(".breadcrumb { padding: 10px; background: #0f172a; border-radius: 5px; margin-bottom: 15px; }\n");
        html.append(".breadcrumb a { color: #60a5fa; text-decoration: none; margin: 0 5px; }\n");
        html.append(".breadcrumb a:hover { text-decoration: underline; }\n");
        html.append(".segment-entry { background: #0f172a; padding: 12px; margin: 8px 0; border-radius: 5px; ");
        html.append("border-left: 3px solid #8b5cf6; }\n");
        html.append(".segment-id { font-family: monospace; color: #60a5fa; }\n");
        html.append(".timestamp { color: #94a3b8; font-size: 0.9em; }\n");
        html.append(".tabs { display: flex; gap: 10px; margin-bottom: 20px; }\n");
        html.append(".tab { padding: 10px 20px; background: #1e293b; border-radius: 5px; cursor: pointer; ");
        html.append("border: 2px solid transparent; }\n");
        html.append(".tab.active { border-color: #8b5cf6; background: #2d3748; }\n");
        html.append(".loading { text-align: center; padding: 40px; color: #94a3b8; }\n");
        html.append("</style>\n");
        html.append("<script>\n");
        html.append("let currentPath = '/';\n\n");
        html.append("async function loadNode(path) {\n");
        html.append("  currentPath = path;\n");
        html.append("  document.getElementById('loading').style.display = 'block';\n");
        html.append("  document.getElementById('node-content').style.display = 'none';\n");
        html.append("  const response = await fetch('/api/explore?path=' + encodeURIComponent(path));\n");
        html.append("  const data = await response.json();\n");
        html.append("  displayNode(data);\n");
        html.append("  document.getElementById('loading').style.display = 'none';\n");
        html.append("  document.getElementById('node-content').style.display = 'block';\n");
        html.append("}\n\n");
        html.append("function displayNode(node) {\n");
        html.append("  const breadcrumb = document.getElementById('breadcrumb');\n");
        html.append("  const parts = currentPath.split('/').filter(p => p);\n");
        html.append("  let path = '';\n");
        html.append("  breadcrumb.innerHTML = '<a href=\"#\" onclick=\"loadNode(\\'/\\'); return false;\">root</a>';\n");
        html.append("  parts.forEach(part => {\n");
        html.append("    path += '/' + part;\n");
        html.append("    breadcrumb.innerHTML += ' / <a href=\"#\" onclick=\"loadNode(\\'' + path + '\\'); return false;\">' + part + '</a>';\n");
        html.append("  });\n\n");
        html.append("  const children = document.getElementById('children');\n");
        html.append("  children.innerHTML = '';\n");
        html.append("  node.children.forEach(child => {\n");
        html.append("    const div = document.createElement('div');\n");
        html.append("    div.className = 'tree-node';\n");
        html.append("    div.innerHTML = '<span class=\"node-name\">📁 ' + child + '</span>';\n");
        html.append("    div.onclick = () => loadNode(currentPath === '/' ? '/' + child : currentPath + '/' + child);\n");
        html.append("    children.appendChild(div);\n");
        html.append("  });\n\n");
        html.append("  const props = document.getElementById('properties');\n");
        html.append("  props.innerHTML = '';\n");
        html.append("  Object.entries(node.properties).forEach(([key, value]) => {\n");
        html.append("    const div = document.createElement('div');\n");
        html.append("    div.className = 'property';\n");
        html.append("    div.innerHTML = '<span class=\"prop-name\">' + key + ':</span> <span class=\"prop-value\">' + JSON.stringify(value) + '</span>';\n");
        html.append("    props.appendChild(div);\n");
        html.append("  });\n");
        html.append("}\n\n");
        html.append("async function loadTarFiles() {\n");
        html.append("  const response = await fetch('/api/segments/tars');\n");
        html.append("  const tars = await response.json();\n");
        html.append("  const container = document.getElementById('tar-files');\n");
        html.append("  container.innerHTML = '';\n");
        html.append("  if (tars.length === 0) {\n");
        html.append("    container.innerHTML = '<div style=\"color: #94a3b8; padding: 10px;\">No TAR files found</div>';\n");
        html.append("    return;\n");
        html.append("  }\n");
        html.append("  tars.forEach(tar => {\n");
        html.append("    const div = document.createElement('div');\n");
        html.append("    div.className = 'segment-entry';\n");
        html.append("    div.style.borderLeft = '3px solid #06b6d4';\n");
        html.append("    const segmentLabel = tar.estimatedCount ? tar.segmentCount + ' (est.)' : tar.segmentCount;\n");
        html.append("    div.innerHTML = '<div style=\"display: flex; justify-content: space-between; align-items: center;\">' +\n");
        html.append("      '<div>' +\n");
        html.append("        '<div class=\"segment-id\" style=\"margin-bottom: 5px;\">💾 ' + tar.name + '</div>' +\n");
        html.append("        '<div class=\"timestamp\">Size: ' + tar.sizeFormatted + ' • Segments: ' + segmentLabel + '</div>' +\n");
        html.append("      '</div>' +\n");
        html.append("      '<div style=\"text-align: right; font-size: 0.85em; color: #94a3b8;\">' +\n");
        html.append("        '<div>Created: ' + new Date(tar.created).toLocaleString() + '</div>' +\n");
        html.append("        '<div>Modified: ' + new Date(tar.modified).toLocaleString() + '</div>' +\n");
        html.append("      '</div>' +\n");
        html.append("    '</div>';\n");
        html.append("    container.appendChild(div);\n");
        html.append("  });\n");
        html.append("}\n\n");
        html.append("async function loadRecentSegments() {\n");
        html.append("  const response = await fetch('/api/segments/recent');\n");
        html.append("  const segments = await response.json();\n");
        html.append("  const container = document.getElementById('recent-segments');\n");
        html.append("  container.innerHTML = '';\n");
        html.append("  if (segments.length === 0) {\n");
        html.append("    container.innerHTML = '<div style=\"color: #94a3b8; padding: 10px;\">No recent segments</div>';\n");
        html.append("    return;\n");
        html.append("  }\n");
        html.append("  segments.forEach(seg => {\n");
        html.append("    const div = document.createElement('div');\n");
        html.append("    div.className = 'segment-entry';\n");
        html.append("    div.innerHTML = '<div class=\"segment-id\">Segment: ' + seg.id + '</div>' +\n");
        html.append("                    '<div class=\"timestamp\">' + seg.timestamp + '</div>';\n");
        html.append("    container.appendChild(div);\n");
        html.append("  });\n");
        html.append("}\n\n");
        html.append("window.onload = () => { loadNode('/'); loadTarFiles(); loadRecentSegments(); setInterval(loadRecentSegments, 5000); };\n");
        html.append("</script>\n");
        html.append("</head>\n<body>\n");
        html.append("<div class='header'>\n");
        html.append("<div class='container'><h1>🔗 Oak Segment Consensus Explorer</h1>\n");
        html.append("<div>Content Browser & Segment Inspector</div></div>\n");
        html.append("</div>\n");
        html.append("<div class='container'>\n");
        html.append("<div class='panel'>\n");
        html.append("<h2>🌳 Content Tree</h2>\n");
        html.append("<div class='breadcrumb' id='breadcrumb'>/</div>\n");
        html.append("<div id='loading' class='loading' style='display:none'>Loading...</div>\n");
        html.append("<div id='node-content'>\n");
        html.append("<div id='children'></div>\n");
        html.append("<h3 style='margin-top: 20px; color: #a78bfa;'>Properties</h3>\n");
        html.append("<div id='properties'></div>\n");
        html.append("</div></div>\n");
        html.append("<div class='panel'>\n");
        html.append("<h2>💾 TAR Files (Segment Storage Blocks)</h2>\n");
        html.append("<div id='tar-files'></div>\n");
        html.append("</div>\n");
        html.append("<div class='panel'>\n");
        html.append("<h2>📦 Recent Segments (Journal)</h2>\n");
        html.append("<div id='recent-segments'></div>\n");
        html.append("</div>\n");
        html.append("</div>\n</body>\n</html>");
        
        response.getWriter().write(html.toString());
    }
    
    /**
     * Handle interactive API Browser UI (HAL-style explorer).
     */
    public void handleApiBrowserUI(HttpServletResponse response) throws IOException {
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
        html.append("<h2>🔄 Consensus APIs</h2>\n");
        addApiEndpoint(html, "GET", "/v1/consensus/status", "Get consensus state (Aeron-aware)", "consensus_status");
        addApiEndpoint(html, "POST", "/v1/propose-write", "Propose signed write transaction", "propose_write");
        addApiEndpoint(html, "GET", "/v1/head", "Get current HEAD record ID (text)", "head");
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
        html.append("<h2>📄 Oak Files</h2>\n");
        addApiEndpoint(html, "GET", "/journal.log", "Journal file (text)", "journal");
        addApiEndpoint(html, "GET", "/manifest", "Manifest file (text)", "manifest");
        addApiEndpoint(html, "GET", "/gc.log", "Garbage collection log (text)", "gc");
        addApiEndpoint(html, "GET", "/segments/{id}", "Fetch segment by ID (binary)", "segment_get");
        addApiEndpoint(html, "HEAD", "/segments/{id}", "Check segment existence", "segment_head");
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
        html.append("    formHtml += '<textarea id=\"request-body\">' + getExampleBody(id) + '</textarea>';\n");
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
        html.append("    'test_write': JSON.stringify({wallet: '0x742d35Cc6634C0532925a3b844Bc9e7595f0bEb', message: 'Hello Blockchain!', contentType: 'page', signature: '0x...', clientId: 'test-client'}, null, 2),\n");
        html.append("    'register_client': JSON.stringify({clientId: 'sling-author-1', clientUrl: 'http://localhost:8080', walletAddress: '0xabc...def'}, null, 2)\n");
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
     */
    public void handleChatUI(HttpServletResponse response) throws IOException {
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
    
}

