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

import org.apache.jackrabbit.oak.segment.http.server.ServerContext;
import org.apache.jackrabbit.oak.segment.http.server.model.ClientRegistration;
import org.apache.jackrabbit.oak.segment.http.server.model.ValidatorRegistration;
import org.apache.jackrabbit.oak.segment.http.server.model.WriteMetadata;
import org.apache.jackrabbit.oak.segment.http.server.util.FormatUtils;
import org.apache.jackrabbit.oak.segment.http.server.util.DashboardDataService;
import org.apache.jackrabbit.oak.segment.consensus.state.ConsensusState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Handler for UI rendering endpoints (dashboard, explorer, API browser).
 * 
 * <p>Extracted from SegmentHttpServer for better separation of concerns.</p>
 */
public class DashboardHandler {
    
    private static final Logger log = LoggerFactory.getLogger(DashboardHandler.class);
    
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
        
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // BLOCKCHAIN CONSENSUS: API-Driven Dashboard Data Fetching
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // All dashboard data is fetched from the same sources as API endpoints:
        // - FileStore stats: DashboardDataService.getFileStoreStats() (same as /health/deep)
        // - Consensus state: DashboardDataService.getConsensusState() (same as /v1/consensus/status)
        // - Recent segments: DashboardDataService.getRecentSegments() (same as /api/segments/recent)
        // This ensures single source of truth and consistent data across dashboard and APIs.
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        
        // Get FileStore statistics (same as /health/deep API)
        DashboardDataService.FileStoreStats fileStoreStats = dataService.getFileStoreStats();
        long storeSize = fileStoreStats.size;
        int segmentCount = fileStoreStats.segmentCount;
        
        // Get recent segment writes (same as /api/segments/recent API)
        List<String> recentWrites = dataService.getRecentSegments();
        
        // Get consensus state (same as /v1/consensus/status API)
        ConsensusState consensusState = dataService.getConsensusState();
        
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>\n");
        html.append("<html>\n<head>\n");
        html.append("<meta charset='UTF-8'>\n");
        html.append("<meta name='viewport' content='width=device-width, initial-scale=1.0'>\n");
        html.append("<title>🔗 Oak Segment Consensus - Global Store</title>\n");
        html.append("<script src='https://cdn.jsdelivr.net/npm/mermaid@10/dist/mermaid.min.js'></script>\n");
        html.append("<script>mermaid.initialize({ startOnLoad: true, theme: 'dark' });</script>\n");
        html.append("<style>\n");
        html.append("* { margin: 0; padding: 0; box-sizing: border-box; }\n");
        html.append("body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Oxygen, Ubuntu, sans-serif; ");
        html.append("background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); color: #fff; min-height: 100vh; padding: 20px; }\n");
        html.append(".container { max-width: 1200px; margin: 0 auto; }\n");
        html.append("header { text-align: center; padding: 40px 0; }\n");
        html.append("h1 { font-size: 3em; margin-bottom: 10px; text-shadow: 2px 2px 4px rgba(0,0,0,0.3); }\n");
        html.append(".subtitle { font-size: 1.2em; opacity: 0.9; }\n");
        html.append(".grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 20px; margin: 30px 0; max-width: 1400px; }\n");
        html.append(".card { background: rgba(255,255,255,0.1); backdrop-filter: blur(10px); border-radius: 15px; ");
        html.append("padding: 25px; box-shadow: 0 8px 32px rgba(0,0,0,0.1); border: 1px solid rgba(255,255,255,0.2); }\n");
        html.append(".card h2 { font-size: 1.5em; margin-bottom: 15px; display: flex; align-items: center; gap: 10px; }\n");
        html.append(".stat { font-size: 2.5em; font-weight: bold; margin: 10px 0; }\n");
        html.append(".label { font-size: 0.9em; opacity: 0.8; text-transform: uppercase; letter-spacing: 1px; }\n");
        html.append(".journal-entry { background: rgba(0,0,0,0.2); padding: 10px; margin: 8px 0; border-radius: 5px; ");
        html.append("font-family: 'Courier New', monospace; font-size: 0.85em; word-break: break-all; }\n");
        html.append(".pulse { animation: pulse 2s ease-in-out infinite; }\n");
        html.append("@keyframes pulse { 0%, 100% { opacity: 1; } 50% { opacity: 0.6; } }\n");
        html.append(".status { display: inline-block; width: 12px; height: 12px; background: #4ade80; border-radius: 50%; ");
        html.append("animation: pulse 2s ease-in-out infinite; margin-right: 8px; }\n");
        html.append(".endpoints { display: grid; gap: 10px; margin-top: 15px; }\n");
        html.append(".endpoint { background: rgba(0,0,0,0.2); padding: 10px; border-radius: 5px; font-size: 0.9em; }\n");
        html.append(".endpoint code { background: rgba(255,255,255,0.1); padding: 2px 6px; border-radius: 3px; }\n");
        html.append("</style>\n");
        html.append("<script>\n");
        html.append("// Auto-refresh every 10 seconds\n");
        html.append("setTimeout(() => window.location.reload(), 10000);\n");
        html.append("</script>\n");
        html.append("</head>\n<body>\n");
        html.append("<div class='container'>\n");
        
        // Header
        html.append("<header>\n");
        html.append("<h1>🔗 Oak Segment Consensus</h1>\n");
        html.append("<div class='subtitle'>Global P2P Oak Repository</div>\n");
        html.append("</header>\n");
        
        // Stats Grid
        html.append("<div class='grid'>\n");
        
        // Status Card
        html.append("<div class='card'>\n");
        html.append("<h2><span class='status'></span>Server Status</h2>\n");
        html.append("<div class='stat pulse'>LIVE</div>\n");
        html.append("<div class='label'>HTTP Segment Transfer Active</div>\n");
        html.append("</div>\n");
        
        // Store Size Card
        html.append("<div class='card'>\n");
        html.append("<h2>📦 Store Size</h2>\n");
        html.append("<div class='stat'>").append(FormatUtils.formatBytes(storeSize)).append("</div>\n");
        html.append("<div class='label'>").append(segmentCount).append(" Segments</div>\n");
        html.append("</div>\n");
        
        // Validator Network Card
        // BLOCKCHAIN CONSENSUS: Use consensus state from API (same as /v1/consensus/status)
        int validatorCount = 1; // Self
        String consensusType = "Single";
        String myRole = "STANDALONE";
        String roleColor = "#94a3b8"; // slate
        String currentLeader = null;
        
        if (consensusState != null) {
            // Use consensus state from API (single source of truth)
            validatorCount = consensusState.totalValidators;
            consensusType = consensusState.consensusType.equals("leader-based") ? "Leader-Based" : consensusState.consensusType;
            myRole = consensusState.currentRole.toString();
            currentLeader = consensusState.currentLeader;
            
            // Set role color based on role
            if ("LEADER".equals(myRole)) {
                roleColor = "#fbbf24"; // gold
            } else if (myRole.contains("PROBATION")) {
                roleColor = "#eab308"; // yellow (probation)
            } else {
                roleColor = "#3b82f6"; // blue
            }
        }
        
        html.append("<div class='card'>\n");
        html.append("<h2>🗳️  Validator Network</h2>\n");
        html.append("<div class='stat'>").append(validatorCount).append("</div>\n");
        html.append("<div class='label'>").append(consensusType).append("</div>\n");
        
        // Leader mode: Show role prominently
        // BLOCKCHAIN CONSENSUS: Use consensus state from API
        // Show role for both leader-based and aeron-cluster consensus
        if (consensusState != null && 
            ("leader-based".equals(consensusState.consensusType) || "aeron-cluster".equals(consensusState.consensusType))) {
            html.append("<div style='margin-top: 12px; padding: 10px; background: ").append(roleColor).append("; border-radius: 8px; text-align: center;'>\n");
            html.append("<div style='font-size: 1.2em; font-weight: 700; color: #fff;'>");
            if ("LEADER".equals(myRole)) {
                html.append("👑 ").append(myRole).append(" 👑");
            } else {
                html.append("📡 ").append(myRole);
            }
            html.append("</div>\n");
            html.append("</div>\n");
            
            // Show current leader if we're a follower
            if (!"LEADER".equals(myRole) && currentLeader != null) {
                String leaderName = currentLeader.contains("validator-") 
                    ? currentLeader.substring(currentLeader.indexOf("validator-")).split(":")[0]
                    : "unknown";
                html.append("<div style='margin-top: 8px; padding: 8px; background: rgba(0,0,0,0.2); border-radius: 6px; font-size: 0.8em; text-align: center;'>\n");
                html.append("<div style='opacity: 0.8;'>Current Leader:</div>\n");
                html.append("<div style='font-weight: 600; color: #fbbf24; margin-top: 4px;'>👑 ").append(leaderName).append("</div>\n");
                html.append("</div>\n");
            }
            
            // Leader rewards note
            if ("LEADER".equals(myRole)) {
                html.append("<div style='margin-top: 8px; padding: 8px; background: rgba(251,191,36,0.15); border-radius: 6px; font-size: 0.75em; border-left: 3px solid #fbbf24;'>\n");
                html.append("<div style='font-weight: 600; margin-bottom: 4px;'>💰 Leader Rewards:</div>\n");
                html.append("<div style='opacity: 0.9; line-height: 1.4;'>Earning all transaction fees during leadership term</div>\n");
                html.append("</div>\n");
            }
        } else if (context.epochLeaderEngine != null) {
            // Fallback to direct engine access (shouldn't happen if ConsensusStateService is set)
            html.append("<div style='margin-top: 12px; padding: 10px; background: ").append(roleColor).append("; border-radius: 8px; text-align: center;'>\n");
            html.append("<div style='font-size: 1.2em; font-weight: 700; color: #fff;'>");
            if (context.epochLeaderEngine.isLeader()) {
                html.append("👑 ").append(myRole).append(" 👑");
            } else {
                html.append("📡 ").append(myRole);
            }
            html.append("</div>\n");
            html.append("</div>\n");
        }
        
        // Show all validators in network (voting + non-voting)
        // BLOCKCHAIN CONSENSUS: Use consensus state from API (same as /v1/peers API)
        List<String> allValidators = new ArrayList<>();
        List<String> nonVotingFollowers = new ArrayList<>();
        
        if (consensusState != null) {
            // Use consensus state from API (single source of truth)
            allValidators = consensusState.allValidators;
            nonVotingFollowers = consensusState.nonVotingFollowers;
        } else if (context.epochLeaderEngine != null) {
            // Fallback to direct engine access (shouldn't happen if ConsensusStateService is set)
            allValidators.add(context.selfUrl);
            allValidators.addAll(context.epochLeaderEngine.getAllFollowers());
            nonVotingFollowers = context.epochLeaderEngine.getNonVotingFollowers();
        }
        
        if (!allValidators.isEmpty()) {
            
            html.append("<div style='margin-top: 12px; font-size: 0.75em; opacity: 0.8;'>");
            html.append("<div style='margin-bottom: 6px; font-weight: 600;'>Validator Network:</div>");
            
            for (String validatorUrl : allValidators) {
                boolean isSelf = validatorUrl.equals(context.selfUrl);
                String validatorName = validatorUrl.contains("validator-") 
                    ? validatorUrl.substring(validatorUrl.indexOf("validator-")).split(":")[0]
                    : validatorUrl;
                
                // Use same status logic as /v1/peers API
                String statusEmoji = "🟢";
                String statusLabel = "";
                
                if (!isSelf) {
                    boolean isOnProbation = nonVotingFollowers.contains(validatorUrl);
                    
                    if (isOnProbation) {
                        statusEmoji = "🟡";
                        statusLabel = " <span style='font-size: 10px; background: rgba(234,179,8,0.2); color: #fbbf24; padding: 2px 6px; border-radius: 3px;'>PROBATION</span>";
                    } else {
                        // Check lastSeen to determine if OFFLINE
                        // Use same logic as PeerDiscoveryHandler
                        long now = System.currentTimeMillis();
                        int leaderTermSeconds = consensusState != null ? consensusState.leaderTermSeconds : 300;
                        long offlineThresholdMs = leaderTermSeconds * 2 * 1000L; // 2 epochs
                        
                        long lastSeen = now; // Default to now
                        for (ValidatorRegistration reg : context.registeredValidators.values()) {
                            if (reg.validatorUrl.equals(validatorUrl)) {
                                lastSeen = reg.lastSeen;
                                break;
                            }
                        }
                        
                        long timeSinceLastSeen = now - lastSeen;
                        if (timeSinceLastSeen > offlineThresholdMs) {
                            statusEmoji = "🔴";
                            statusLabel = " <span style='font-size: 10px; background: rgba(239,68,68,0.2); color: #ef4444; padding: 2px 6px; border-radius: 3px;'>OFFLINE</span>";
                        } else {
                            statusEmoji = "🟢";
                        }
                    }
                }
                
                html.append("<div style='margin-top: 4px; padding: 4px 8px; background: rgba(255,255,255,0.05); border-radius: 4px; display: flex; justify-content: space-between; align-items: center;'>");
                html.append("<span>");
                if (isSelf) {
                    html.append("🟢 <strong>").append(FormatUtils.escapeHtml(validatorName)).append("</strong> (YOU)");
                } else {
                    html.append(statusEmoji).append(" ").append(FormatUtils.escapeHtml(validatorName)).append(statusLabel);
                }
                html.append("</span>");
                html.append("</div>");
            }
            html.append("</div>");
        }
        html.append("</div>\n");
        
        // Connected Peers Card
        // BLOCKCHAIN CONSENSUS: Show validator peers (not just Sling clients)
        int peerCount = context.registeredClients.size();
        int validatorPeerCount = 0;
        if (consensusState != null && consensusState.allValidators != null) {
            // Count validators excluding self
            validatorPeerCount = consensusState.allValidators.size() - 1;
        }
        
        html.append("<div class='card'>\n");
        html.append("<h2>🌐 Connected Peers</h2>\n");
        if (validatorPeerCount > 0) {
            html.append("<div class='stat'>").append(validatorPeerCount).append("</div>\n");
            html.append("<div class='label'>Validator Peers</div>\n");
        } else {
            html.append("<div class='stat'>").append(peerCount).append("</div>\n");
            html.append("<div class='label'>Registered Sling Authors</div>\n");
        }
        
        if (!context.registeredClients.isEmpty()) {
            html.append("<div style='margin-top: 12px; font-size: 0.75em; opacity: 0.8;'>");
            html.append("<div style='margin-bottom: 4px;'>Registered Clients:</div>");
            int shown = 0;
            for (ClientRegistration reg : context.registeredClients.values()) {
                if (shown >= 5) {
                    html.append("<div style='margin-top: 4px;'>... and ").append(context.registeredClients.size() - 5).append(" more</div>");
                    break;
                }
                String displayName = reg.clientId;
                if (displayName.length() > 20) {
                    displayName = displayName.substring(0, 17) + "...";
                }
                html.append("<div style='margin-top: 2px;'>• ").append(FormatUtils.escapeHtml(displayName));
                if (reg.walletAddress != null && !reg.walletAddress.isEmpty()) {
                    html.append(" (").append(FormatUtils.escapeHtml(reg.walletAddress.substring(0, Math.min(10, reg.walletAddress.length())))).append("...)");
                }
                html.append("</div>");
                shown++;
            }
            html.append("</div>");
        }
        html.append("</div>\n");
        
        // Next Leader Election Card (only in Leader mode)
        // BLOCKCHAIN CONSENSUS: Use consensus state from API
        if (consensusState != null && "leader-based".equals(consensusState.consensusType)) {
            int currentEpoch = consensusState.currentEpoch;
            int leaderTermSeconds = consensusState.leaderTermSeconds;
            int secondsUntilRotation = consensusState.secondsUntilRotation;
            
            String timeRemaining = "0s";
            if (secondsUntilRotation > 0) {
                long minutes = secondsUntilRotation / 60;
                long seconds = secondsUntilRotation % 60;
                if (minutes > 0) {
                    timeRemaining = minutes + "m " + seconds + "s";
                } else {
                    timeRemaining = seconds + "s";
                }
            }
            
            html.append("<div class='card'>\n");
            html.append("<h2>⏱️  Next Leader Election</h2>\n");
            html.append("<div class='stat' style='font-size: 2em;'>").append(timeRemaining).append("</div>\n");
            html.append("<div class='label'>").append(leaderTermSeconds).append("s Term Duration</div>\n");
            
            html.append("<div style='margin-top: 12px; padding: 8px; background: rgba(0,0,0,0.2); border-radius: 6px; font-size: 0.8em;'>\n");
            html.append("<div style='opacity: 0.7; margin-bottom: 4px;'>Current Epoch:</div>\n");
            html.append("<div style='font-weight: 600; color: #3b82f6;'>").append(currentEpoch).append("</div>\n");
            html.append("</div>\n");
            
            // Use nextLeader from consensus state (from API)
            if (consensusState.nextLeader != null && !consensusState.nextLeader.isEmpty()) {
                String nextLeaderUrl = consensusState.nextLeader;
                String nextLeaderName = nextLeaderUrl.contains("validator-") 
                    ? nextLeaderUrl.substring(nextLeaderUrl.indexOf("validator-")).split(":")[0]
                    : "unknown";
                boolean willBeMe = nextLeaderUrl.equals(context.selfUrl);
                
                html.append("<div style='margin-top: 8px; padding: 8px; background: rgba(59,130,246,0.15); border-radius: 6px; font-size: 0.75em; border-left: 3px solid #3b82f6;'>\n");
                html.append("<div style='font-weight: 600; margin-bottom: 4px;'>Next Leader:</div>\n");
                html.append("<div style='opacity: 0.9; line-height: 1.4;'>");
                if (willBeMe) {
                    html.append("👑 <strong style='color: #fbbf24;'>YOU</strong> will be the next leader!");
                } else {
                    html.append("👑 ").append(FormatUtils.escapeHtml(nextLeaderName));
                }
                html.append("</div>\n");
                html.append("</div>\n");
            }
            
            html.append("</div>\n");
        } else if (context.epochLeaderEngine != null) {
            // Fallback to direct engine access (shouldn't happen if ConsensusStateService is set)
            int currentEpoch = context.epochLeaderEngine.getCurrentEpoch();
            int leaderTermSeconds = context.epochLeaderEngine.getElection().getLeaderTermSeconds();
            
            long epochStartTime = (long) currentEpoch * leaderTermSeconds * 1000L;
            long currentTime = System.currentTimeMillis();
            long nextElectionTime = epochStartTime + (leaderTermSeconds * 1000L);
            long secondsUntilElection = (nextElectionTime - currentTime) / 1000;
            
            String timeRemaining = "0s";
            if (secondsUntilElection > 0) {
                long minutes = secondsUntilElection / 60;
                long seconds = secondsUntilElection % 60;
                if (minutes > 0) {
                    timeRemaining = minutes + "m " + seconds + "s";
                } else {
                    timeRemaining = seconds + "s";
                }
            }
            
            html.append("<div class='card'>\n");
            html.append("<h2>⏱️  Next Leader Election</h2>\n");
            html.append("<div class='stat' style='font-size: 2em;'>").append(timeRemaining).append("</div>\n");
            html.append("<div class='label'>").append(leaderTermSeconds).append("s Term Duration</div>\n");
            html.append("</div>\n");
        }
        
        // Add dynamic metrics cards via JavaScript
        html.append("<div id='dynamic-metrics'></div>\n");
        
        html.append("</div>\n"); // End grid
        
        // Recent Writes
        html.append("<div class='card'>\n");
        html.append("<h2>📝 Recent Segment Writes</h2>\n");
        if (recentWrites.isEmpty()) {
            html.append("<div class='journal-entry'>No recent writes</div>\n");
        } else {
            for (String entry : recentWrites) {
                String recordIdShort = entry.split(":")[0];
                if (recordIdShort.length() > 20) {
                    recordIdShort = recordIdShort.substring(0, 20);
                }
                
                WriteMetadata meta = context.recentWriteMetadata.get(recordIdShort);
                
                if (meta != null) {
                    String badge = "";
                    String badgeColor = "";
                    if ("consensus".equals(meta.source)) {
                        badge = "CONSENSUS";
                        badgeColor = "#10b981";
                    } else if ("dag-local".equals(meta.source)) {
                        badge = "DAG";
                        badgeColor = "#fbbf24";
                    } else if ("leader-accepted".equals(meta.source)) {
                        badge = "CONSENSUS";
                        badgeColor = "#fbbf24";
                    } else if ("epoch-sync".equals(meta.source)) {
                        badge = "EPOCH";
                        badgeColor = "#3b82f6";
                    }
                    
                    String validatorName = meta.validator;
                    if (validatorName.contains("validator-")) {
                        validatorName = validatorName.substring(validatorName.indexOf("validator-"));
                        validatorName = validatorName.split(":")[0];
                    }
                    
                    html.append("<div class='journal-entry' style='border-left: 4px solid " + badgeColor + ";'>");
                    html.append("<div style='display: flex; justify-content: space-between; align-items: center;'>");
                    html.append("<code style='flex: 1;'>").append(FormatUtils.escapeHtml(entry)).append("</code>");
                    html.append("<div style='display: flex; gap: 8px; margin-left: 12px;'>");
                    html.append("<span style='background: " + badgeColor + "; padding: 2px 8px; border-radius: 4px; font-size: 0.75em; font-weight: 600;'>");
                    html.append(badge).append("</span>");
                    html.append("<span style='background: rgba(255,255,255,0.1); padding: 2px 8px; border-radius: 4px; font-size: 0.75em;'>");
                    html.append("🗳️ ").append(FormatUtils.escapeHtml(validatorName)).append("</span>");
                    html.append("</div></div>");
                    if (meta.message != null && !meta.message.isEmpty()) {
                        html.append("<div style='margin-top: 4px; font-size: 0.85em; opacity: 0.8;'>💬 ").append(FormatUtils.escapeHtml(meta.message)).append("</div>");
                    }
                    html.append("</div>\n");
                } else {
                    html.append("<div class='journal-entry'>").append(FormatUtils.escapeHtml(entry)).append("</div>\n");
                }
            }
        }
        html.append("</div>\n");
        
        // Explorer Link
        html.append("<div class='card' style='text-align: center; padding: 40px;'>\n");
        html.append("<h2>🔍 Content Explorer</h2>\n");
        html.append("<p style='margin: 20px 0; opacity: 0.9;'>Browse the global repository content tree and inspect segments</p>\n");
        html.append("<a href='/explorer' style='display: inline-block; padding: 15px 40px; background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); ");
        html.append("color: white; text-decoration: none; border-radius: 8px; font-weight: 600; font-size: 1.1em; ");
        html.append("box-shadow: 0 4px 12px rgba(102, 126, 234, 0.4); transition: transform 0.2s;' ");
        html.append("onmouseover='this.style.transform=\"scale(1.05)\"' onmouseout='this.style.transform=\"scale(1)\"'>");
        html.append("Launch Explorer →</a>\n");
        html.append("</div>\n");
        
        // API Endpoints - Comprehensive List
        html.append("<div class='card'>\n");
        html.append("<h2>🔌 API Endpoints</h2>\n");
        
        html.append("<div style='margin-bottom: 16px; padding: 12px; background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); border-radius: 8px;'>\n");
        html.append("<a href='/api-browser' style='color: white; text-decoration: none; font-weight: 600; display: flex; align-items: center; gap: 8px;'>\n");
        html.append("🧪 Interactive API Browser →</a>\n");
        html.append("</div>\n");
        
        html.append("<div class='endpoints'>\n");
        
        html.append("<div style='margin-top: 12px; font-weight: 600; color: #3b82f6;'>📊 Explorer APIs</div>\n");
        html.append("<div class='endpoint'><code>GET /explorer</code> - Blockchain content explorer UI</div>\n");
        html.append("<div class='endpoint'><code>GET /api/explore?path={path}</code> - Browse node tree (JSON)</div>\n");
        html.append("<div class='endpoint'><code>GET /api/segments/tars</code> - TAR files and storage (JSON)</div>\n");
        html.append("<div class='endpoint'><code>GET /api/segments/recent</code> - Recent segment writes (JSON)</div>\n");
        
        html.append("<div style='margin-top: 12px; font-weight: 600; color: #10b981;'>💚 Health & Monitoring</div>\n");
        html.append("<div class='endpoint'><code>GET /health</code> - Basic health check (JSON)</div>\n");
        html.append("<div class='endpoint'><code>GET /health/deep</code> - Comprehensive health validation (JSON)</div>\n");
        html.append("<div class='endpoint'><code>GET /api/metrics</code> - Consensus & replication metrics (JSON)</div>\n");
        html.append("<div class='endpoint'><code>GET /metrics</code> - Prometheus metrics (text)</div>\n");
        
        html.append("<div style='margin-top: 12px; font-weight: 600; color: #f59e0b;'>🔄 Consensus APIs</div>\n");
        html.append("<div class='endpoint'><code>GET /v1/consensus/status</code> - Get consensus state (Aeron-aware)</div>\n");
        html.append("<div class='endpoint'><code>POST /v1/test-write</code> - Test write with wallet signature</div>\n");
        html.append("<div class='endpoint'><code>GET /v1/head</code> - Current HEAD record ID (text)</div>\n");
        
        html.append("<div style='margin-top: 12px; font-weight: 600; color: #06b6d4;'>✈️ Aeron Cluster APIs</div>\n");
        html.append("<div class='endpoint'><code>GET /v1/aeron/cluster-state</code> - Complete Aeron Cluster state (JSON)</div>\n");
        html.append("<div class='endpoint'><code>GET /v1/aeron/raft-metrics</code> - Raft-specific metrics (JSON)</div>\n");
        html.append("<div class='endpoint'><code>GET /v1/aeron/node-status</code> - Status of specific cluster node (JSON)</div>\n");
        html.append("<div class='endpoint'><code>GET /v1/aeron/leadership-history</code> - Recent leadership changes (JSON)</div>\n");
        
        html.append("<div style='margin-top: 12px; font-weight: 600; color: #8b5cf6;'>🌐 Registration & Discovery</div>\n");
        html.append("<div class='endpoint'><code>POST /v1/register-client</code> - Register Sling author</div>\n");
        html.append("<div class='endpoint'><code>GET /v1/peers</code> - List all known validators (JSON)</div>\n");
        html.append("<div class='endpoint'><code>GET /v1/ngrok-url</code> - Get public ngrok URL (text)</div>\n");
        
        html.append("<div style='margin-top: 12px; font-weight: 600; color: #6b7280;'>📄 Oak Files</div>\n");
        html.append("<div class='endpoint'><code>GET /journal.log</code> - Journal file (text)</div>\n");
        html.append("<div class='endpoint'><code>GET /manifest</code> - Manifest file (text)</div>\n");
        html.append("<div class='endpoint'><code>GET /gc.log</code> - Garbage collection log (text)</div>\n");
        html.append("<div class='endpoint'><code>GET /segments/{id}</code> - Fetch segment by ID (binary)</div>\n");
        html.append("<div class='endpoint'><code>HEAD /segments/{id}</code> - Check segment existence</div>\n");
        
        html.append("</div>\n");
        html.append("</div>\n");
        
        html.append("</div>\n"); // End container
        
        // Add JavaScript for dynamic metrics
        html.append("<script>\n");
        html.append("async function loadMetrics() {\n");
        html.append("  try {\n");
        html.append("    const response = await fetch('/api/metrics');\n");
        html.append("    const data = await response.json();\n");
        html.append("    \n");
        html.append("    let html = '';\n");
        html.append("    \n");
        html.append("    if (data.consensus) {\n");
        html.append("      html += '<div class=\"card\">';\n");
        html.append("      html += '<h2>🎯 Consensus Performance</h2>';\n");
        html.append("      html += '<div class=\"stat\">' + data.consensus.successRate.toFixed(1) + '%</div>';\n");
        html.append("      html += '<div class=\"label\">Success Rate (' + data.consensus.successfulProposals + '/' + data.consensus.totalProposals + ')</div>';\n");
        html.append("      html += '<div style=\"margin-top: 10px; font-size: 0.9em; opacity: 0.8;\">⏱️  Avg Consensus: ' + data.consensus.averageConsensusTimeMs + 'ms</div>';\n");
        html.append("      html += '</div>';\n");
        html.append("    }\n");
        html.append("    \n");
        html.append("    if (data.replication) {\n");
        html.append("      html += '<div class=\"card\">';\n");
        html.append("      html += '<h2>🔄 Replication</h2>';\n");
        html.append("      html += '<div class=\"stat\">' + data.replication.totalSegments + '</div>';\n");
        html.append("      html += '<div class=\"label\">Segments Replicated (' + data.replication.totalMb + ' MB)</div>';\n");
        html.append("      html += '</div>';\n");
        html.append("    }\n");
        html.append("    \n");
        html.append("    if (data.validator) {\n");
        html.append("      html += '<div class=\"card\">';\n");
        html.append("      html += '<h2>🪪 Validator Identity</h2>';\n");
        html.append("      html += '<div style=\"font-family: monospace; font-size: 0.85em; word-break: break-all; margin: 10px 0;\">' + data.validator.url + '</div>';\n");
        html.append("      html += '<div class=\"label\">My Address</div>';\n");
        html.append("      html += '</div>';\n");
        html.append("    }\n");
        html.append("    \n");
        html.append("    document.getElementById('dynamic-metrics').innerHTML = html;\n");
        html.append("  } catch (e) {\n");
        html.append("    console.error('Failed to load metrics:', e);\n");
        html.append("  }\n");
        html.append("}\n");
        html.append("\n");
        html.append("loadMetrics();\n");
        html.append("setInterval(loadMetrics, 5000);\n");
        html.append("</script>\n");
        
        html.append("</body>\n</html>");
        
        response.getWriter().write(html.toString());
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
        addApiEndpoint(html, "POST", "/v1/test-write", "Test write with wallet signature (demo)", "test_write");
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

