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
import org.apache.jackrabbit.oak.segment.agentic.eip8004.Eip8004AgentQuery;
import org.apache.jackrabbit.oak.segment.agentic.eip8004.Eip8004Config;
import org.apache.jackrabbit.oak.segment.agentic.eip8004.Eip8004RegistrationService;
import org.apache.jackrabbit.oak.segment.agentic.eip8004.IdentityRegistryClient;
import org.apache.jackrabbit.oak.segment.agentic.eip8004.IdentityRegistryClientFactory;
import org.apache.jackrabbit.oak.segment.agentic.eip8004.IdentityRegistryEntry;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
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
 * Tool for discovering and negotiating with other agents.
 * 
 * <p>Enables agents to:
 * - Discover available validators/Sling authors
 * - Negotiate optimal communication protocols
 * - Exchange capability information
 * - Establish agent-to-agent communication channels
 */
public class AgentDiscoveryTool implements AgenticTool {
    private static final Logger log = LoggerFactory.getLogger(AgentDiscoveryTool.class);
    
    private final Gson gson = new Gson();
    private final CloseableHttpClient httpClient;
    private final String agentId;
    private final String agentType;
    private final Eip8004Config eip8004Config;
    private final IdentityRegistryClient identityRegistryClient;
    private final Eip8004RegistrationService registrationService;
    
    public AgentDiscoveryTool() {
        this.agentType = detectAgentType();
        this.agentId = getWalletAddress(); // Use Ethereum wallet address (0x...) for provable identity
        RequestConfig requestConfig = RequestConfig.custom()
            .setConnectTimeout(5000)
            .setSocketTimeout(10000)
            .build();
        this.httpClient = HttpClients.custom()
            .setDefaultRequestConfig(requestConfig)
            .build();
        this.eip8004Config = Eip8004Config.load();
        this.identityRegistryClient = IdentityRegistryClientFactory.create(eip8004Config);
        this.registrationService = new Eip8004RegistrationService(eip8004Config, identityRegistryClient);
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
    
    private String detectAgentType() {
        if (System.getProperty("sling.instance.id") != null ||
            System.getProperty("sling.server.url") != null ||
            System.getenv("SLING_HOME") != null) {
            return "sling-author";
        }
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
        return "agent-discovery";
    }
    
    @Override
    public String getDescription() {
        return "Discover and negotiate with other agents (validators/Sling authors). " +
               "Can discover available agents, exchange capabilities, and establish optimal communication protocols.";
    }
    
    @Override
    public boolean shouldUse(String query) {
        String lower = query.toLowerCase();
        return lower.contains("discover") || lower.contains("find agents") ||
               lower.contains("agent") && (lower.contains("negotiate") || lower.contains("capabilities") ||
               lower.contains("available") || lower.contains("peer"));
    }
    
    @Override
    public ToolResult execute(String query, Map<String, Object> context) {
        try {
            String lower = query.toLowerCase();
            
            // Discover validators
            if (lower.contains("validator") || lower.contains("peer") || lower.contains("discover")) {
                return discoverValidators();
            }
            
            // Negotiate with specific agent
            if (lower.contains("negotiate") || lower.contains("capabilities")) {
                String targetUrl = extractUrl(query);
                if (targetUrl != null) {
                    return negotiateWithAgent(targetUrl);
                }
            }
            
            // Default: discover validators
            return discoverValidators();
        } catch (Exception e) {
            log.error("Error in agent discovery", e);
            return ToolResult.failure("Error discovering agents: " + e.getMessage());
        }
    }
    
    /**
     * Discover available validators.
     */
    private ToolResult discoverValidators() {
        List<String> discoveredAgents = new ArrayList<>();
        List<String> validatorUrls = getValidatorUrls();
        Eip8004RegistrationService.RegistrationResult registrationResult =
            registrationService.ensureRegistered(agentId, agentType, getCapabilities());
        
        StringBuilder result = new StringBuilder();
        result.append("🔍 Agent Discovery Results:\n\n");
        result.append("Agent ID: ").append(agentId).append("\n");
        result.append("Agent Type: ").append(agentType).append("\n\n");
        appendEip8004Status(result, registrationResult);
        
        if (validatorUrls.isEmpty()) {
            result.append("⚠️  No validators configured. Check OAK_GLOBAL_STORE_URL environment variable.\n");
            return ToolResult.success(result.toString(), "agent-discovery");
        }
        
        result.append("Discovered Validators:\n");
        result.append("─".repeat(50)).append("\n");
        
        for (String validatorUrl : validatorUrls) {
            try {
                Map<String, Object> agentInfo = probeAgent(validatorUrl);
                if (agentInfo != null) {
                    discoveredAgents.add(validatorUrl);
                    result.append("✅ ").append(validatorUrl).append("\n");
                    result.append("   Type: ").append(agentInfo.get("agentType")).append("\n");
                    result.append("   Capabilities: ").append(agentInfo.get("capabilities")).append("\n");
                    result.append("   Status: ").append(agentInfo.get("status")).append("\n");
                    result.append("\n");
                } else {
                    result.append("❌ ").append(validatorUrl).append(" (unreachable or not an agent)\n");
                }
            } catch (Exception e) {
                result.append("❌ ").append(validatorUrl).append(" (error: ").append(e.getMessage()).append(")\n");
            }
        }
        
        result.append("\nTotal discovered: ").append(discoveredAgents.size()).append(" agent(s)\n");
        
        if (!discoveredAgents.isEmpty()) {
            result.append("\n💡 Tip: Use 'negotiate with agent at ").append(discoveredAgents.get(0))
                  .append("' to establish communication protocol.\n");
        }

        appendEip8004Discovery(result);
        
        return ToolResult.success(result.toString(), "agent-discovery");
    }
    
    /**
     * Probe an agent to get its information.
     */
    private Map<String, Object> probeAgent(String url) throws IOException {
        // Try to get agent info via health endpoint or chat endpoint
        String healthUrl = url + "/health";
        HttpGet request = new HttpGet(healthUrl);
        
        try (CloseableHttpResponse response = httpClient.execute(request)) {
            int statusCode = response.getStatusLine().getStatusCode();
            if (statusCode >= 200 && statusCode < 300) {
                // Check if it supports agent chat
                String chatUrl = url + "/v1/chat";
                HttpPost chatRequest = new HttpPost(chatUrl);
                Map<String, Object> probeBody = new HashMap<>();
                probeBody.put("query", "AGENT_PROBE: What are your capabilities?");
                chatRequest.setEntity(new StringEntity(gson.toJson(probeBody), "UTF-8"));
                chatRequest.setHeader("Content-Type", "application/json");
                chatRequest.setHeader("X-Agent-ID", agentId);
                chatRequest.setHeader("X-Agent-Type", agentType);
                
                try (CloseableHttpResponse chatResponse = httpClient.execute(chatRequest)) {
                    if (chatResponse.getStatusLine().getStatusCode() == 200) {
                        Map<String, Object> info = new HashMap<>();
                        info.put("agentType", "validator");
                        info.put("capabilities", List.of("llm-chat", "validator-apis", "aeron-consensus"));
                        info.put("status", "active");
                        return info;
                    }
                } catch (Exception e) {
                    log.debug("Chat endpoint not available", e);
                }
                
                // Fallback: basic validator info
                Map<String, Object> info = new HashMap<>();
                info.put("agentType", "validator");
                info.put("capabilities", List.of("validator-apis"));
                info.put("status", "active (no agent chat)");
                return info;
            }
        }
        
        return null;
    }
    
    /**
     * Negotiate communication protocol with another agent.
     */
    private ToolResult negotiateWithAgent(String targetUrl) {
        try {
            StringBuilder result = new StringBuilder();
            result.append("🤝 Agent Negotiation:\n\n");
            result.append("From: ").append(agentId).append(" (").append(agentType).append(")\n");
            result.append("To: ").append(targetUrl).append("\n\n");
            
            // Send negotiation request
            String chatUrl = targetUrl + "/v1/chat";
            Map<String, Object> negotiationBody = new HashMap<>();
            negotiationBody.put("query", "AGENT_NEGOTIATION: What are your capabilities and preferred communication protocol?");
            
            Map<String, Object> agentContext = new HashMap<>();
            agentContext.put("agentId", agentId);
            agentContext.put("agentType", agentType);
            agentContext.put("capabilities", getCapabilities());
            agentContext.put("negotiation", true);
            negotiationBody.put("context", agentContext);
            
            HttpPost request = new HttpPost(chatUrl);
            request.setEntity(new StringEntity(gson.toJson(negotiationBody), "UTF-8"));
            request.setHeader("Content-Type", "application/json");
            request.setHeader("X-Agent-ID", agentId);
            request.setHeader("X-Agent-Type", agentType);
            
            try (CloseableHttpResponse response = httpClient.execute(request)) {
                int statusCode = response.getStatusLine().getStatusCode();
                String responseBody = EntityUtils.toString(response.getEntity());
                
                if (statusCode >= 200 && statusCode < 300) {
                    result.append("✅ Negotiation successful!\n\n");
                    result.append("Response:\n");
                    result.append(responseBody);
                    result.append("\n\n💡 Agents can now communicate using LLM chat protocol.\n");
                    return ToolResult.success(result.toString(), "agent-discovery");
                } else {
                    return ToolResult.failure("Negotiation failed: HTTP " + statusCode + ": " + responseBody);
                }
            }
        } catch (Exception e) {
            log.error("Error negotiating with agent", e);
            return ToolResult.failure("Error negotiating with agent: " + e.getMessage());
        }
    }
    
    /**
     * Get validator URLs from configuration.
     */
    private List<String> getValidatorUrls() {
        List<String> urls = new ArrayList<>();
        
        // Check environment variable
        String url = System.getenv("OAK_GLOBAL_STORE_URL");
        if (url != null && !url.isEmpty()) {
            urls.add(url);
        }
        
        // Check system property
        url = System.getProperty("oak.globalStore.url");
        if (url != null && !url.isEmpty() && !urls.contains(url)) {
            urls.add(url);
        }
        
        // Default fallback
        if (urls.isEmpty()) {
            urls.add("http://localhost:8090");
            urls.add("http://localhost:8091");
            urls.add("http://localhost:8092");
        }
        
        return urls;
    }
    
    /**
     * Extract URL from query string.
     */
    private String extractUrl(String query) {
        // Look for URL patterns
        String[] words = query.split("\\s+");
        for (String word : words) {
            if (word.startsWith("http://") || word.startsWith("https://")) {
                return word;
            }
        }
        return null;
    }
    
    /**
     * Get capabilities of this agent.
     */
    private List<String> getCapabilities() {
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

    private void appendEip8004Status(StringBuilder result, Eip8004RegistrationService.RegistrationResult registrationResult) {
        if (!eip8004Config.isEnabled()) {
            return;
        }
        result.append("EIP-8004 Registration:\n");
        result.append("─".repeat(50)).append("\n");
        switch (registrationResult.getStatus()) {
            case DISABLED:
                result.append("ℹ️  EIP-8004 disabled\n");
                break;
            case PENDING:
                result.append("⚠️  Registration URI not configured; generated registration JSON (")
                      .append(registrationResult.getRegistrationJson().length())
                      .append(" chars).\n");
                result.append("   Set ").append(Eip8004Config.PROP_REGISTRATION_URI)
                      .append(" to enable on-chain registration.\n");
                break;
            case REGISTERED:
                result.append("✅ Registered with agent id ").append(registrationResult.getMessage()).append("\n");
                break;
            case ALREADY_REGISTERED:
                result.append("✅ Already registered\n");
                break;
            case FAILED:
                result.append("❌ Registration failed: ").append(registrationResult.getMessage()).append("\n");
                break;
            default:
                result.append("ℹ️  Registration status unknown\n");
        }
        result.append("\n");
    }

    private void appendEip8004Discovery(StringBuilder result) {
        if (!eip8004Config.isEnabled()) {
            return;
        }
        List<IdentityRegistryEntry> entries = identityRegistryClient.discoverAgents(
            new Eip8004AgentQuery(eip8004Config.getChainId(), getCapabilities()));
        result.append("\nEIP-8004 Discovery:\n");
        result.append("─".repeat(50)).append("\n");
        if (entries.isEmpty()) {
            result.append("No agents discovered via Identity Registry (stub client).\n");
            return;
        }
        for (IdentityRegistryEntry entry : entries) {
            result.append("✅ ").append(entry.getAgentId()).append("\n");
            result.append("   Registration: ").append(entry.getRegistrationUri()).append("\n");
            result.append("   Capabilities: ").append(entry.getCapabilities()).append("\n");
        }
    }
}
