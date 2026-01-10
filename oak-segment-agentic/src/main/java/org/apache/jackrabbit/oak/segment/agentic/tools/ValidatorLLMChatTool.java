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
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tool for LLM-to-LLM chat with validator's agentic chat interface.
 * Allows Sling authors to query validators using their LLM capabilities.
 * 
 * <p>Enhanced with structured agent-to-agent communication protocol:
 * - Agent identification and capability negotiation
 * - Context-aware query routing
 * - Conversation history tracking
 * - Self-troubleshooting support
 */
public class ValidatorLLMChatTool implements AgenticTool {
    private static final Logger log = LoggerFactory.getLogger(ValidatorLLMChatTool.class);
    
    private final String validatorBaseUrl;
    private final Gson gson = new Gson();
    private final CloseableHttpClient httpClient;
    private final String agentId; // Unique identifier for this agent instance
    private final String agentType; // "sling-author" or "validator"
    
    public ValidatorLLMChatTool(String validatorBaseUrl) {
        this.validatorBaseUrl = validatorBaseUrl;
        this.agentType = detectAgentType();
        this.agentId = getWalletAddress(); // Use Ethereum wallet address (0x...) for provable identity
        RequestConfig requestConfig = RequestConfig.custom()
            .setConnectTimeout(10000)
            .setSocketTimeout(120000)  // 2 minutes for LLM inference
            .build();
        this.httpClient = HttpClients.custom()
            .setDefaultRequestConfig(requestConfig)
            .build();
    }
    
    /**
     * Get Ethereum wallet address for agent identification.
     * Uses 0x wallet address for provable identity and signing.
     */
    private String getWalletAddress() {
        // Try to get wallet address from OSGi service (Sling context)
        if ("sling-author".equals(agentType)) {
            try {
                Object bundleContext = getBundleContext();
                if (bundleContext != null) {
                    Object serviceRef = bundleContext.getClass()
                        .getMethod("getServiceReference", String.class)
                        .invoke(bundleContext, "org.apache.jackrabbit.oak.segment.http.wallet.SlingAuthorWalletService");
                    if (serviceRef != null) {
                        Object walletService = bundleContext.getClass()
                            .getMethod("getService", Class.forName("org.osgi.framework.ServiceReference"))
                            .invoke(bundleContext, serviceRef);
                        if (walletService != null) {
                            String address = (String) walletService.getClass()
                                .getMethod("getWalletAddress").invoke(walletService);
                            if (address != null && !address.isEmpty()) {
                                return address;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("Could not get wallet address from OSGi service", e);
            }
        }
        
        // Fallback: Try system property (for validators or if OSGi lookup fails)
        String walletAddress = System.getProperty("wallet.address");
        if (walletAddress != null && !walletAddress.isEmpty()) {
            return walletAddress;
        }
        
        // Last resort: Generate temporary ID (should not happen in production)
        log.warn("⚠️  No wallet address found - using temporary ID. Wallet should be configured.");
        String hostname = System.getProperty("user.name", "unknown");
        long pid = ProcessHandle.current().pid();
        long timestamp = System.currentTimeMillis();
        return String.format("temp-%s-%d-%d", hostname, pid, timestamp);
    }
    
    /**
     * Get BundleContext using reflection (for Sling context).
     */
    private Object getBundleContext() {
        try {
            Class<?> frameworkUtilClass = Class.forName("org.osgi.framework.FrameworkUtil");
            Object bundle = frameworkUtilClass.getMethod("getBundle", Class.class)
                .invoke(null, getClass());
            if (bundle != null) {
                return bundle.getClass().getMethod("getBundleContext").invoke(bundle);
            }
        } catch (Exception e) {
            // Not in OSGi context
        }
        return null;
    }
    
    /**
     * Detect agent type (sling-author vs validator).
     */
    private String detectAgentType() {
        // Check for Sling-specific indicators
        if (System.getProperty("sling.instance.id") != null ||
            System.getProperty("sling.server.url") != null ||
            System.getenv("SLING_HOME") != null) {
            return "sling-author";
        }
        // Check for OSGi framework (Sling runs in OSGi)
        try {
            Class<?> frameworkUtilClass = Class.forName("org.osgi.framework.FrameworkUtil");
            Object bundle = frameworkUtilClass.getMethod("getBundle", Class.class)
                .invoke(null, getClass());
            if (bundle != null) {
                return "sling-author";
            }
        } catch (Exception e) {
            // Not in OSGi context
        }
        return "validator";
    }
    
    @Override
    public String getName() {
        return "validator-llm-chat";
    }
    
    @Override
    public String getDescription() {
        return "Query validator's LLM chat interface for validator-specific questions (Aeron, consensus, network state, validator internals). " +
               "Supports structured agent-to-agent communication with capability negotiation and self-troubleshooting.";
    }
    
    @Override
    public boolean shouldUse(String query) {
        String lower = query.toLowerCase();
        // Enhanced detection: agent-to-agent communication patterns
        boolean isAgentQuery = lower.contains("agent") && (lower.contains("talk") || lower.contains("communicate") || 
                                                           lower.contains("negotiate") || lower.contains("discover"));
        // Only use for validator-specific questions when running in Sling context
        return (isAgentQuery || lower.contains("validator") || lower.contains("aeron") || 
                lower.contains("consensus") || lower.contains("raft") ||
                lower.contains("cluster") || lower.contains("network")) &&
               !lower.contains("sling") && !lower.contains("osgi");
    }
    
    @Override
    public ToolResult execute(String query, Map<String, Object> context) {
        try {
            // Build enhanced chat request with agent metadata
            String chatUrl = validatorBaseUrl + "/v1/chat";
            
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("query", query);
            
            // Add agent-to-agent communication metadata
            Map<String, Object> agentContext = new HashMap<>();
            agentContext.put("agentId", agentId);
            agentContext.put("agentType", agentType);
            agentContext.put("capabilities", getAgentCapabilities());
            agentContext.put("timestamp", System.currentTimeMillis());
            
            // Add conversation context if available
            if (context != null && context.containsKey("conversationHistory")) {
                agentContext.put("conversationHistory", context.get("conversationHistory"));
            }
            
            requestBody.put("context", agentContext);
            
            String jsonPayload = gson.toJson(requestBody);
            
            HttpPost request = new HttpPost(chatUrl);
            request.setEntity(new StringEntity(jsonPayload, "UTF-8"));
            request.setHeader("Content-Type", "application/json");
            request.setHeader("X-Agent-ID", agentId);
            request.setHeader("X-Agent-Type", agentType);
            
            // TODO: Add 0x address authentication header
            // request.setHeader("X-Wallet-Address", walletAddress);
            // request.setHeader("X-Signature", signature);
            
            log.debug("🤖 Agent-to-Agent: {} ({}) querying validator at {}", agentId, agentType, validatorBaseUrl);
            
            try (CloseableHttpResponse response = httpClient.execute(request)) {
                int statusCode = response.getStatusLine().getStatusCode();
                String responseBody = EntityUtils.toString(response.getEntity());
                
                if (statusCode >= 200 && statusCode < 300) {
                    // Parse response to extract answer and agent metadata
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> chatResponse = (Map<String, Object>) gson.fromJson(responseBody, Map.class);
                        String answer = (String) chatResponse.get("answer");
                        
                        // Extract agent response metadata if present
                        StringBuilder result = new StringBuilder();
                        result.append("🤖 Validator Agent Response:\n\n");
                        
                        if (answer != null) {
                            result.append(answer);
                        } else {
                            result.append(responseBody);
                        }
                        
                        // Add agent metadata if available
                        if (chatResponse.containsKey("agentMetadata")) {
                            result.append("\n\n--- Agent Metadata ---\n");
                            result.append(gson.toJson(chatResponse.get("agentMetadata")));
                        }
                        
                        return ToolResult.success(result.toString(), "validator-llm-chat");
                    } catch (Exception e) {
                        log.debug("Could not parse LLM response", e);
                    }
                    
                    return ToolResult.success(responseBody, "validator-llm-chat");
                } else {
                    return ToolResult.failure("Validator LLM API returned HTTP " + statusCode + ": " + responseBody);
                }
            }
        } catch (IOException e) {
            log.error("Error querying validator LLM chat", e);
            return ToolResult.failure("Error querying validator LLM: " + e.getMessage());
        }
    }
    
    /**
     * Get agent capabilities for negotiation.
     */
    private List<String> getAgentCapabilities() {
        List<String> capabilities = new ArrayList<>();
        
        if ("sling-author".equals(agentType)) {
            capabilities.add("osgi-introspection");
            capabilities.add("sling-state-query");
            capabilities.add("oak-composite-mount");
            capabilities.add("validator-api-access");
            capabilities.add("rag-sling-codebase");
        } else {
            capabilities.add("aeron-consensus");
            capabilities.add("validator-apis");
            capabilities.add("segment-serving");
            capabilities.add("raft-state");
            capabilities.add("rag-validator-codebase");
        }
        
        capabilities.add("llm-chat");
        capabilities.add("log-access");
        capabilities.add("agent-to-agent-communication");
        
        return capabilities;
    }
}

