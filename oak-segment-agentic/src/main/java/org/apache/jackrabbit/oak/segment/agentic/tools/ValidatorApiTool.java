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
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Map;

/**
 * Tool for querying validator APIs.
 */
public class ValidatorApiTool implements AgenticTool {
    private static final Logger log = LoggerFactory.getLogger(ValidatorApiTool.class);
    
    private final String baseUrl;
    private final Gson gson = new Gson();
    private final CloseableHttpClient httpClient;
    
    public ValidatorApiTool(String baseUrl) {
        // If baseUrl is null or empty, try to get from OSGi config or environment
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
     * Get validator URL from OSGi configuration or environment variables.
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
        
        // Try to read from OSGi Configuration Admin
        try {
            Object bundleContext = getBundleContext();
            if (bundleContext != null) {
                // Try to get Configuration Admin service
                Object configAdmin = getConfigurationAdmin(bundleContext);
                if (configAdmin != null) {
                    // Look for HttpPersistenceService configuration
                    String configUrl = getConfigFromAdmin(configAdmin, 
                        "org.apache.jackrabbit.oak.segment.http.HttpPersistenceService", 
                        "globalStoreUrl");
                    if (configUrl != null && !configUrl.isEmpty()) {
                        return configUrl;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Could not read validator URL from OSGi config", e);
        }
        
        // Default fallback
        return "http://localhost:8091";
    }
    
    private Object getBundleContext() {
        try {
            Class<?> frameworkUtilClass = Class.forName("org.osgi.framework.FrameworkUtil");
            Method getBundleMethod = frameworkUtilClass.getMethod("getBundle", Class.class);
            Object bundle = getBundleMethod.invoke(null, getClass());
            
            if (bundle != null) {
                Method getBundleContextMethod = bundle.getClass().getMethod("getBundleContext");
                return getBundleContextMethod.invoke(bundle);
            }
        } catch (Exception e) {
            // Ignore
        }
        return null;
    }
    
    private Object getConfigurationAdmin(Object bundleContext) {
        try {
            Method getServiceReferenceMethod = bundleContext.getClass()
                .getMethod("getServiceReference", String.class);
            Object serviceRef = getServiceReferenceMethod.invoke(
                bundleContext, "org.osgi.service.cm.ConfigurationAdmin");
            
            if (serviceRef != null) {
                Method getServiceMethod = bundleContext.getClass()
                    .getMethod("getService", java.lang.reflect.ParameterizedType.class);
                // Try with ServiceReference parameter
                try {
                    return getServiceMethod.invoke(bundleContext, serviceRef);
                } catch (Exception e) {
                    // Try alternative signature
                    Method getServiceAltMethod = bundleContext.getClass()
                        .getMethod("getService", Class.forName("org.osgi.framework.ServiceReference"));
                    return getServiceAltMethod.invoke(bundleContext, serviceRef);
                }
            }
        } catch (Exception e) {
            log.debug("Could not get ConfigurationAdmin service", e);
        }
        return null;
    }
    
    private String getConfigFromAdmin(Object configAdmin, String pid, String propertyName) {
        try {
            Method getConfigurationMethod = configAdmin.getClass()
                .getMethod("getConfiguration", String.class);
            Object config = getConfigurationMethod.invoke(configAdmin, pid);
            
            if (config != null) {
                Method getPropertiesMethod = config.getClass().getMethod("getProperties");
                Object properties = getPropertiesMethod.invoke(config);
                
                if (properties != null) {
                    Method getMethod = properties.getClass().getMethod("get", Object.class);
                    Object value = getMethod.invoke(properties, propertyName);
                    if (value != null) {
                        return value.toString();
                    }
                }
            }
        } catch (Exception e) {
            // Ignore
        }
        return null;
    }
    
    @Override
    public String getName() {
        return "validator-api";
    }
    
    @Override
    public String getDescription() {
        return "Query validator APIs: cluster state, consensus status, peers, health, metrics, explorer, segments, journal, manifest, aeron, raft, leadership history, node status";
    }
    
    @Override
    public boolean shouldUse(String query) {
        String lower = query.toLowerCase();
        return lower.contains("leader") || lower.contains("cluster") || 
               lower.contains("consensus") || lower.contains("peer") ||
               lower.contains("validator") || lower.contains("status") ||
               lower.contains("health") || lower.contains("metric") ||
               lower.contains("aeron") || lower.contains("raft") ||
               lower.contains("explore") || lower.contains("segment") ||
               lower.contains("journal") || lower.contains("manifest") ||
               lower.contains("tar") || lower.contains("head") ||
               lower.contains("node") || lower.contains("recent");
    }
    
    @Override
    public ToolResult execute(String query, Map<String, Object> context) {
        try {
            String lower = query.toLowerCase();
            
            // Explorer APIs
            if (lower.contains("explore") || lower.contains("browse") || lower.contains("tree")) {
                String path = extractPath(query);
                return queryEndpoint("/api/explore?path=" + path, "explore");
            }
            
            if (lower.contains("tar") && (lower.contains("list") || lower.contains("all"))) {
                return queryEndpoint("/api/segments/tars", "tars");
            }
            
            if (lower.contains("recent") || (lower.contains("segment") && lower.contains("recent"))) {
                return queryEndpoint("/api/segments/recent", "recent-segments");
            }
            
            // Health & Monitoring
            if (lower.contains("health")) {
                if (lower.contains("deep") || lower.contains("comprehensive")) {
                    return queryEndpoint("/health/deep", "health-deep");
                }
                return queryEndpoint("/health", "health");
            }
            
            if (lower.contains("metric")) {
                if (lower.contains("prometheus")) {
                    return queryEndpoint("/metrics", "prometheus-metrics");
                }
                return queryEndpoint("/api/metrics", "metrics");
            }
            
            // Consensus APIs
            if (lower.contains("head") && !lower.contains("leadership")) {
                return queryEndpoint("/v1/head", "head");
            }
            
            if (lower.contains("consensus")) {
                return queryEndpoint("/v1/consensus/status", "consensus-status");
            }
            
            // Aeron Cluster APIs
            if (lower.contains("leadership") && lower.contains("history")) {
                String limit = extractLimit(query);
                return queryEndpoint("/v1/aeron/leadership-history?limit=" + limit, "leadership-history");
            }
            
            if (lower.contains("node") && lower.contains("status")) {
                String nodeId = extractNodeId(query);
                return queryEndpoint("/v1/aeron/node-status?nodeId=" + nodeId, "node-status");
            }
            
            if (lower.contains("raft") && lower.contains("metric")) {
                return queryEndpoint("/v1/aeron/raft-metrics", "raft-metrics");
            }
            
            if (lower.contains("leader") || lower.contains("cluster") || lower.contains("aeron")) {
                return queryEndpoint("/v1/aeron/cluster-state", "cluster-state");
            }
            
            // Registration & Discovery
            if (lower.contains("peer")) {
                return queryEndpoint("/v1/peers", "peers");
            }
            
            if (lower.contains("ngrok")) {
                return queryEndpoint("/v1/ngrok-url", "ngrok-url");
            }
            
            // Oak Files
            if (lower.contains("journal")) {
                return queryEndpoint("/journal.log", "journal");
            }
            
            if (lower.contains("manifest")) {
                return queryEndpoint("/manifest", "manifest");
            }
            
            if (lower.contains("gc") && lower.contains("log")) {
                return queryEndpoint("/gc.log", "gc-log");
            }
            
            if (lower.contains("segment") && !lower.contains("recent") && !lower.contains("tar")) {
                // Try to extract segment ID
                String segmentId = extractSegmentId(query);
                if (segmentId != null && !segmentId.isEmpty()) {
                    return queryEndpoint("/segments/" + segmentId, "segment");
                }
            }
            
            // Default: try consensus status (most common query)
            return queryEndpoint("/v1/consensus/status", "consensus-status");
        } catch (Exception e) {
            log.error("Error querying validator API", e);
            return ToolResult.failure("Error querying validator API: " + e.getMessage());
        }
    }
    
    /**
     * Extract path from query (for /api/explore endpoint).
     */
    private String extractPath(String query) {
        // Look for path patterns like "path /oak-chain" or "path=/oak-chain"
        String[] words = query.split("\\s+");
        for (int i = 0; i < words.length - 1; i++) {
            if (words[i].equalsIgnoreCase("path") || words[i].equalsIgnoreCase("at")) {
                String path = words[i + 1].replaceAll("[^a-zA-Z0-9/\\-_]", "");
                if (path.startsWith("/")) {
                    return path;
                } else {
                    return "/" + path;
                }
            }
        }
        // Check for path= pattern
        int idx = query.toLowerCase().indexOf("path=");
        if (idx >= 0) {
            String after = query.substring(idx + 5).trim();
            String path = after.split("\\s+")[0].replaceAll("[^a-zA-Z0-9/\\-_]", "");
            if (path.startsWith("/")) {
                return path;
            } else {
                return "/" + path;
            }
        }
        return "/"; // Default to root
    }
    
    /**
     * Extract limit from query (for leadership-history).
     */
    private String extractLimit(String query) {
        // Look for "limit 10" or "limit=10"
        String[] words = query.split("\\s+");
        for (int i = 0; i < words.length - 1; i++) {
            if (words[i].equalsIgnoreCase("limit")) {
                try {
                    int limit = Integer.parseInt(words[i + 1].replaceAll("[^0-9]", ""));
                    return String.valueOf(Math.min(limit, 100)); // Cap at 100
                } catch (NumberFormatException e) {
                    // Ignore
                }
            }
        }
        return "10"; // Default
    }
    
    /**
     * Extract node ID from query (for node-status).
     */
    private String extractNodeId(String query) {
        // Look for "node 0" or "nodeId=0" or "nodeId 0"
        String[] words = query.split("\\s+");
        for (int i = 0; i < words.length - 1; i++) {
            if (words[i].equalsIgnoreCase("node") || words[i].equalsIgnoreCase("nodeid")) {
                try {
                    int nodeId = Integer.parseInt(words[i + 1].replaceAll("[^0-9]", ""));
                    return String.valueOf(nodeId);
                } catch (NumberFormatException e) {
                    // Ignore
                }
            }
        }
        // Check for nodeId= pattern
        int idx = query.toLowerCase().indexOf("nodeid=");
        if (idx >= 0) {
            String after = query.substring(idx + 7).trim();
            try {
                int nodeId = Integer.parseInt(after.split("\\s+")[0].replaceAll("[^0-9]", ""));
                return String.valueOf(nodeId);
            } catch (NumberFormatException e) {
                // Ignore
            }
        }
        return "0"; // Default to node 0
    }
    
    /**
     * Extract segment ID from query.
     */
    private String extractSegmentId(String query) {
        // Look for segment ID pattern (hexadecimal, typically long)
        // Segment IDs are usually in format like "abc123..." or "segment abc123"
        String[] words = query.split("\\s+");
        for (int i = 0; i < words.length; i++) {
            String word = words[i].replaceAll("[^a-fA-F0-9]", "");
            if (word.length() >= 8 && word.matches("[a-fA-F0-9]+")) {
                return word;
            }
        }
        return null;
    }
    
    private ToolResult queryEndpoint(String endpoint, String sourceName) throws IOException {
        String url = baseUrl + endpoint;
        HttpGet request = new HttpGet(url);
        
        try (CloseableHttpResponse response = httpClient.execute(request)) {
            int statusCode = response.getStatusLine().getStatusCode();
            String responseBody = EntityUtils.toString(response.getEntity());
            
            if (statusCode >= 200 && statusCode < 300) {
                return ToolResult.success(responseBody, sourceName);
            } else {
                return ToolResult.failure("API returned HTTP " + statusCode + ": " + responseBody);
            }
        }
    }
}

