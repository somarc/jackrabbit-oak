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
package org.apache.jackrabbit.oak.segment.agentic.tools;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;

/**
 * Tool for analyzing TarMK growth state and providing AI-powered insights.
 * 
 * <p>This tool queries TarMK statistics and fragmentation metrics to evaluate
 * storage efficiency, packing efficiency, and provide recommendations for
 * garbage collection and compaction decisions.</p>
 */
public class TarMkAnalysisTool implements AgenticTool {
    private static final Logger log = LoggerFactory.getLogger(TarMkAnalysisTool.class);
    private static final Gson gson = new Gson();
    
    private final String baseUrl;
    private final CloseableHttpClient httpClient;
    
    public TarMkAnalysisTool(String baseUrl) {
        // If baseUrl is null or empty, try to get from config
        if (baseUrl == null || baseUrl.isEmpty()) {
            baseUrl = getValidatorUrlFromConfig();
        }
        this.baseUrl = baseUrl;
        // Create HTTP client with reasonable timeouts
        RequestConfig requestConfig = RequestConfig.custom()
            .setConnectTimeout(5000)
            .setSocketTimeout(10000)
            .build();
        this.httpClient = HttpClients.custom()
            .setDefaultRequestConfig(requestConfig)
            .build();
    }
    
    /**
     * Get validator URL from configuration or environment variables.
     */
    private String getValidatorUrlFromConfig() {
        // Check environment variable first
        String url = System.getenv("OAK_GLOBAL_STORE_URL");
        if (url != null && !url.isEmpty()) {
            return url;
        }
        
        // Check system property
        url = System.getProperty("oak.globalStore.url");
        if (url != null && !url.isEmpty()) {
            return url;
        }
        
        // Default fallback
        return "http://localhost:8091";
    }
    
    @Override
    public String getName() {
        return "tarmk-analysis";
    }
    
    @Override
    public String getDescription() {
        return "Analyze TarMK growth state: TAR file statistics, packing efficiency, fragmentation metrics, GC recommendations, compaction economics";
    }
    
    @Override
    public boolean shouldUse(String query) {
        String lower = query.toLowerCase();
        return lower.contains("tar") || lower.contains("tarmk") ||
               lower.contains("growth") || lower.contains("fragmentation") ||
               lower.contains("packing") || lower.contains("efficiency") ||
               lower.contains("compaction") || lower.contains("gc") ||
               lower.contains("revision cleanup") || lower.contains("storage") ||
               lower.contains("segment") && (lower.contains("file") || lower.contains("count"));
    }
    
    @Override
    public ToolResult execute(String query, Map<String, Object> context) {
        try {
            // Collect all relevant TarMK data
            StringBuilder analysis = new StringBuilder();
            analysis.append("📊 TarMK Growth State Analysis\n");
            analysis.append("=").append("=".repeat(60)).append("\n\n");
            
            // 1. Get TAR file list
            String tarFilesData = queryEndpoint("/api/segments/tars");
            if (tarFilesData != null && !tarFilesData.isEmpty()) {
                analysis.append("📁 TAR Files:\n");
                analysis.append(tarFilesData);
                analysis.append("\n\n");
            }
            
            // 2. Get health/deep stats (includes FileStore stats)
            String healthData = queryEndpoint("/health/deep");
            if (healthData != null && !healthData.isEmpty()) {
                analysis.append("💚 FileStore Health:\n");
                analysis.append(healthData);
                analysis.append("\n\n");
            }
            
            // 3. Get fragmentation metrics (if available)
            String fragmentationData = queryEndpoint("/v1/fragmentation/metrics");
            if (fragmentationData != null && !fragmentationData.isEmpty() && 
                !fragmentationData.contains("404") && !fragmentationData.contains("Not Found")) {
                analysis.append("🔍 Fragmentation Metrics:\n");
                analysis.append(fragmentationData);
                analysis.append("\n\n");
            }
            
            // 4. Get metrics (includes segment counts)
            String metricsData = queryEndpoint("/api/metrics");
            if (metricsData != null && !metricsData.isEmpty()) {
                analysis.append("📈 Consensus & Replication Metrics:\n");
                analysis.append(metricsData);
                analysis.append("\n\n");
            }
            
            // 5. Parse and calculate key metrics
            TarMkMetrics metrics = parseMetrics(tarFilesData, healthData);
            if (metrics != null) {
                analysis.append("╔══════════════════════════════════════════════════════════════════════════════╗\n");
                analysis.append("║ CRITICAL: USE THESE EXACT METRICS - DO NOT MAKE UP NUMBERS                    ║\n");
                analysis.append("╚══════════════════════════════════════════════════════════════════════════════╝\n");
                analysis.append("\n📊 ACTUAL TarMK Metrics (USE THESE EXACT VALUES):\n");
                analysis.append(String.format("TAR_FILE_COUNT=%d\n", metrics.tarFileCount));
                analysis.append(String.format("TOTAL_SIZE_BYTES=%d\n", metrics.totalSize));
                analysis.append(String.format("TOTAL_SIZE_FORMATTED=%s\n", formatBytes(metrics.totalSize)));
                analysis.append(String.format("AVERAGE_TAR_SIZE_BYTES=%d\n", metrics.averageTarSize));
                analysis.append(String.format("AVERAGE_TAR_SIZE_FORMATTED=%s\n", formatBytes(metrics.averageTarSize)));
                analysis.append(String.format("LARGEST_TAR_SIZE_BYTES=%d\n", metrics.largestTarSize));
                analysis.append(String.format("LARGEST_TAR_SIZE_FORMATTED=%s\n", formatBytes(metrics.largestTarSize)));
                analysis.append(String.format("SMALLEST_TAR_SIZE_BYTES=%d\n", metrics.smallestTarSize));
                analysis.append(String.format("SMALLEST_TAR_SIZE_FORMATTED=%s\n", formatBytes(metrics.smallestTarSize)));
                analysis.append(String.format("PACKING_EFFICIENCY_PERCENT=%.1f\n", metrics.packingEfficiency));
                analysis.append(String.format("SEGMENT_COUNT=%d\n", metrics.segmentCount));
                analysis.append("\n⚠️  IMPORTANT: When reporting these metrics, use the EXACT values above.\n");
                analysis.append("   Do NOT invent or estimate different numbers. These are the actual current values.\n\n");
                
                // Add analysis insights
                analysis.append("\n💡 Analysis Insights:\n");
                if (metrics.packingEfficiency < 10.0) {
                    analysis.append("⚠️  HIGH FRAGMENTATION: Many small TAR files detected (packing efficiency < 10%)\n");
                    analysis.append("   - Consider fragmentation-based compaction if operational costs exceed compaction costs\n");
                    analysis.append("   - Monitor file handle count (ulimit constraint)\n");
                } else if (metrics.packingEfficiency < 50.0) {
                    analysis.append("⚡ MODERATE FRAGMENTATION: Acceptable packing efficiency\n");
                    analysis.append("   - Evaluate cost/benefit for fragmentation-based compaction\n");
                } else {
                    analysis.append("✅ GOOD PACKING: Efficient TAR file utilization\n");
                }
                
                analysis.append("\n📋 Revision Cleanup Considerations:\n");
                analysis.append("- Growth Behavior: Pure adds always grow; deletes mark segments dead but don't reclaim space\n");
                analysis.append("- GC Process: Marks reachable segments from HEAD, rewrites TAR files if ≥25% space savings\n");
                analysis.append("- Economic Model: GC tied to blockchain incentives (gas costs, validator consensus, stake)\n");
                analysis.append("- Consensus Required: All validators must agree which revisions are safe to remove\n");
                
                analysis.append("\n💭 Economic Trade-offs:\n");
                analysis.append("- Compaction Costs: Gas fees, validator time, storage I/O, consensus overhead\n");
                analysis.append("- Fragmentation Costs: More file handles, slower operations, increased overhead\n");
                if (metrics.packingEfficiency < 10.0) {
                    analysis.append("- Current State: High fragmentation - fragmentation costs likely exceed compaction costs\n");
                } else {
                    analysis.append("- Current State: Evaluate cost/benefit for fragmentation-based compaction\n");
                }
            }
            
            analysis.append("\n").append("=").append("=".repeat(60)).append("\n");
            analysis.append("CRITICAL INSTRUCTIONS FOR LLM:\n");
            analysis.append("1. Report ONLY the metrics shown above (TAR_FILE_COUNT, TOTAL_SIZE_FORMATTED, etc.)\n");
            analysis.append("2. Do NOT make up or estimate different numbers\n");
            analysis.append("3. If the metrics show 40 TAR files and 10.0 MB total size, report exactly that\n");
            analysis.append("4. Use the PACKING_EFFICIENCY_PERCENT value exactly as shown\n");
            analysis.append("5. Base all recommendations on these ACTUAL metrics, not hypothetical scenarios\n");
            analysis.append("\nUse this ACTUAL data to provide recommendations for GC, compaction, and storage optimization.\n");
            
            return ToolResult.success(analysis.toString(), "tarmk-analysis");
            
        } catch (Exception e) {
            log.error("Error analyzing TarMK state", e);
            return ToolResult.failure("Error analyzing TarMK state: " + e.getMessage());
        }
    }
    
    /**
     * Query an API endpoint and return the response body.
     */
    private String queryEndpoint(String endpoint) {
        try {
            String url = baseUrl + endpoint;
            HttpGet request = new HttpGet(url);
            
            try (CloseableHttpResponse response = httpClient.execute(request)) {
                int statusCode = response.getStatusLine().getStatusCode();
                String responseBody = EntityUtils.toString(response.getEntity());
                
                if (statusCode >= 200 && statusCode < 300) {
                    return responseBody;
                } else {
                    log.debug("API endpoint {} returned HTTP {}", endpoint, statusCode);
                    return null;
                }
            }
        } catch (IOException e) {
            log.debug("Error querying endpoint {}: {}", endpoint, e.getMessage());
            return null;
        }
    }
    
    /**
     * Parse TarMK metrics from API responses.
     */
    private TarMkMetrics parseMetrics(String tarFilesData, String healthData) {
        TarMkMetrics metrics = new TarMkMetrics();
        
        try {
            // Parse TAR files JSON array
            if (tarFilesData != null && tarFilesData.startsWith("[")) {
                com.google.gson.JsonArray tarArray = gson.fromJson(tarFilesData, com.google.gson.JsonArray.class);
                metrics.tarFileCount = tarArray.size();
                
                long totalSize = 0;
                long largestSize = 0;
                long smallestSize = Long.MAX_VALUE;
                
                for (int i = 0; i < tarArray.size(); i++) {
                    JsonObject tar = tarArray.get(i).getAsJsonObject();
                    long size = tar.has("size") ? tar.get("size").getAsLong() : 0;
                    totalSize += size;
                    if (size > largestSize) {
                        largestSize = size;
                    }
                    if (size < smallestSize && size > 0) {
                        smallestSize = size;
                    }
                }
                
                metrics.totalSize = totalSize;
                metrics.largestTarSize = largestSize;
                metrics.smallestTarSize = smallestSize == Long.MAX_VALUE ? 0 : smallestSize;
                metrics.averageTarSize = metrics.tarFileCount > 0 ? totalSize / metrics.tarFileCount : 0;
                
                // Calculate packing efficiency (percentage of max file size, typically 256 MB)
                long maxFileSize = 256L * 1024 * 1024; // 256 MB default
                if (metrics.averageTarSize > 0) {
                    metrics.packingEfficiency = (double) metrics.averageTarSize / maxFileSize * 100.0;
                }
            }
            
            // Parse health data for segment count
            if (healthData != null) {
                JsonObject health = gson.fromJson(healthData, JsonObject.class);
                if (health.has("fileStore") && health.get("fileStore").isJsonObject()) {
                    JsonObject fileStore = health.get("fileStore").getAsJsonObject();
                    if (fileStore.has("segmentCount")) {
                        metrics.segmentCount = fileStore.get("segmentCount").getAsInt();
                    }
                }
            }
            
            return metrics;
        } catch (Exception e) {
            log.debug("Error parsing TarMK metrics", e);
            return metrics; // Return partial metrics
        }
    }
    
    /**
     * Format bytes to human-readable string.
     */
    private String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        } else if (bytes < 1024 * 1024 * 1024) {
            return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        } else {
            return String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
        }
    }
    
    /**
     * TarMK metrics data class.
     */
    private static class TarMkMetrics {
        int tarFileCount = 0;
        long totalSize = 0;
        long averageTarSize = 0;
        long largestTarSize = 0;
        long smallestTarSize = 0;
        int segmentCount = 0;
        double packingEfficiency = 0.0;
    }
}

