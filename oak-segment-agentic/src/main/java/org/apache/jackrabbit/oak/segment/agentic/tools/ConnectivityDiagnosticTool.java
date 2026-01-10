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

import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Tool for diagnosing connectivity issues between agents.
 * 
 * <p>Self-troubleshooting capabilities:
 * - Test connectivity to validators
 * - Diagnose network issues
 * - Check endpoint availability
 * - Suggest fixes for common problems
 */
public class ConnectivityDiagnosticTool implements AgenticTool {
    private static final Logger log = LoggerFactory.getLogger(ConnectivityDiagnosticTool.class);
    
    private final CloseableHttpClient httpClient;
    
    public ConnectivityDiagnosticTool() {
        RequestConfig requestConfig = RequestConfig.custom()
            .setConnectTimeout(5000)
            .setSocketTimeout(10000)
            .build();
        this.httpClient = HttpClients.custom()
            .setDefaultRequestConfig(requestConfig)
            .build();
    }
    
    @Override
    public String getName() {
        return "connectivity-diagnostic";
    }
    
    @Override
    public String getDescription() {
        return "Diagnose connectivity issues between agents. Tests validator endpoints, checks network connectivity, " +
               "and suggests fixes for common problems.";
    }
    
    @Override
    public boolean shouldUse(String query) {
        String lower = query.toLowerCase();
        return lower.contains("connect") || lower.contains("diagnose") ||
               lower.contains("troubleshoot") || lower.contains("test connection") ||
               lower.contains("network") && lower.contains("issue") ||
               lower.contains("can't reach") || lower.contains("unreachable");
    }
    
    @Override
    public ToolResult execute(String query, Map<String, Object> context) {
        try {
            StringBuilder result = new StringBuilder();
            result.append("🔍 Connectivity Diagnostic Report:\n\n");
            
            List<String> validatorUrls = getValidatorUrls();
            if (validatorUrls.isEmpty()) {
                result.append("⚠️  No validators configured.\n");
                result.append("   Check OAK_GLOBAL_STORE_URL environment variable.\n");
                return ToolResult.success(result.toString(), "connectivity-diagnostic");
            }
            
            List<DiagnosticResult> diagnostics = new ArrayList<>();
            
            for (String validatorUrl : validatorUrls) {
                DiagnosticResult diagnostic = diagnoseValidator(validatorUrl);
                diagnostics.add(diagnostic);
            }
            
            // Build report
            for (DiagnosticResult diagnostic : diagnostics) {
                result.append("─".repeat(60)).append("\n");
                result.append("Validator: ").append(diagnostic.url).append("\n");
                result.append("Status: ").append(diagnostic.status).append("\n");
                
                if (diagnostic.healthCheck) {
                    result.append("✅ Health endpoint: OK\n");
                } else {
                    result.append("❌ Health endpoint: FAILED\n");
                }
                
                if (diagnostic.chatEndpoint) {
                    result.append("✅ Chat endpoint: OK\n");
                } else {
                    result.append("❌ Chat endpoint: FAILED (agent chat not available)\n");
                }
                
                if (diagnostic.apiEndpoint) {
                    result.append("✅ API endpoint: OK\n");
                } else {
                    result.append("❌ API endpoint: FAILED\n");
                }
                
                if (diagnostic.latency > 0) {
                    result.append("⏱️  Latency: ").append(diagnostic.latency).append("ms\n");
                }
                
                if (!diagnostic.issues.isEmpty()) {
                    result.append("\n⚠️  Issues detected:\n");
                    for (String issue : diagnostic.issues) {
                        result.append("   - ").append(issue).append("\n");
                    }
                }
                
                if (!diagnostic.suggestions.isEmpty()) {
                    result.append("\n💡 Suggestions:\n");
                    for (String suggestion : diagnostic.suggestions) {
                        result.append("   - ").append(suggestion).append("\n");
                    }
                }
                
                result.append("\n");
            }
            
            // Summary
            long healthyCount = diagnostics.stream().filter(d -> d.healthCheck && d.apiEndpoint).count();
            result.append("Summary: ").append(healthyCount).append("/").append(diagnostics.size())
                  .append(" validators healthy\n");
            
            if (healthyCount == 0) {
                result.append("\n🚨 All validators unreachable! Check:\n");
                result.append("   1. Validator containers are running\n");
                result.append("   2. Network connectivity\n");
                result.append("   3. Firewall rules\n");
                result.append("   4. OAK_GLOBAL_STORE_URL configuration\n");
            }
            
            return ToolResult.success(result.toString(), "connectivity-diagnostic");
        } catch (Exception e) {
            log.error("Error in connectivity diagnostic", e);
            return ToolResult.failure("Error diagnosing connectivity: " + e.getMessage());
        }
    }
    
    /**
     * Diagnose a single validator.
     */
    private DiagnosticResult diagnoseValidator(String url) {
        DiagnosticResult result = new DiagnosticResult();
        result.url = url;
        
        // Test health endpoint
        try {
            long startTime = System.currentTimeMillis();
            HttpGet request = new HttpGet(url + "/health");
            try (CloseableHttpResponse response = httpClient.execute(request)) {
                long latency = System.currentTimeMillis() - startTime;
                result.latency = latency;
                
                if (response.getStatusLine().getStatusCode() == 200) {
                    result.healthCheck = true;
                    result.status = "✅ REACHABLE";
                } else {
                    result.healthCheck = false;
                    result.status = "⚠️  RESPONDING (non-200)";
                    result.issues.add("Health endpoint returned non-200 status");
                }
            }
        } catch (java.net.ConnectException e) {
            result.healthCheck = false;
            result.status = "❌ UNREACHABLE";
            result.issues.add("Connection refused - validator may not be running");
            result.suggestions.add("Check if validator container is running: docker ps");
            result.suggestions.add("Check validator logs: docker logs <validator-container>");
        } catch (java.net.SocketTimeoutException e) {
            result.healthCheck = false;
            result.status = "⏱️  TIMEOUT";
            result.issues.add("Connection timeout - validator may be slow or unreachable");
            result.suggestions.add("Check network connectivity");
            result.suggestions.add("Check if validator is under heavy load");
        } catch (IOException e) {
            result.healthCheck = false;
            result.status = "❌ ERROR";
            result.issues.add("Network error: " + e.getMessage());
            result.suggestions.add("Check network configuration");
            result.suggestions.add("Verify URL is correct: " + url);
        }
        
        // Test API endpoint
        if (result.healthCheck) {
            try {
                HttpGet request = new HttpGet(url + "/v1/consensus/status");
                try (CloseableHttpResponse response = httpClient.execute(request)) {
                    if (response.getStatusLine().getStatusCode() == 200) {
                        result.apiEndpoint = true;
                    } else {
                        result.apiEndpoint = false;
                        result.issues.add("API endpoint returned non-200 status");
                    }
                }
            } catch (Exception e) {
                result.apiEndpoint = false;
                result.issues.add("API endpoint error: " + e.getMessage());
            }
        }
        
        // Test chat endpoint (optional)
        if (result.healthCheck) {
            try {
                HttpGet request = new HttpGet(url + "/v1/chat");
                // Chat endpoint requires POST, but we can check if it exists
                request.setHeader("Content-Type", "application/json");
                try (CloseableHttpResponse response = httpClient.execute(request)) {
                    // 405 Method Not Allowed means endpoint exists but needs POST
                    int statusCode = response.getStatusLine().getStatusCode();
                    if (statusCode == 405 || statusCode == 200) {
                        result.chatEndpoint = true;
                    } else if (statusCode == 404) {
                        result.chatEndpoint = false;
                        result.issues.add("Chat endpoint not available (oak-segment-agentic may not be installed)");
                    }
                }
            } catch (Exception e) {
                // Ignore chat endpoint errors
                log.debug("Chat endpoint check failed", e);
            }
        }
        
        return result;
    }
    
    /**
     * Get validator URLs from configuration.
     */
    private List<String> getValidatorUrls() {
        List<String> urls = new ArrayList<>();
        
        String url = System.getenv("OAK_GLOBAL_STORE_URL");
        if (url != null && !url.isEmpty()) {
            urls.add(url);
        }
        
        url = System.getProperty("oak.globalStore.url");
        if (url != null && !url.isEmpty() && !urls.contains(url)) {
            urls.add(url);
        }
        
        if (urls.isEmpty()) {
            urls.add("http://localhost:8090");
            urls.add("http://localhost:8091");
            urls.add("http://localhost:8092");
        }
        
        return urls;
    }
    
    /**
     * Diagnostic result for a validator.
     */
    private static class DiagnosticResult {
        String url;
        String status = "UNKNOWN";
        boolean healthCheck = false;
        boolean apiEndpoint = false;
        boolean chatEndpoint = false;
        long latency = 0;
        List<String> issues = new ArrayList<>();
        List<String> suggestions = new ArrayList<>();
    }
}

