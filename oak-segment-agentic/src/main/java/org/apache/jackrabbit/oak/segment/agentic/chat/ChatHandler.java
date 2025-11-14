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
package org.apache.jackrabbit.oak.segment.agentic.chat;

import com.google.gson.Gson;
import org.apache.jackrabbit.oak.segment.agentic.llm.LLMService;
import org.apache.jackrabbit.oak.segment.agentic.rag.RAGService;
import org.apache.jackrabbit.oak.segment.agentic.tools.AgenticTool;
import org.apache.jackrabbit.oak.segment.agentic.tools.LogAccessTool;
import org.apache.jackrabbit.oak.segment.agentic.tools.OSGiBundleTool;
import org.apache.jackrabbit.oak.segment.agentic.tools.OSGiComponentTool;
import org.apache.jackrabbit.oak.segment.agentic.tools.OSGiServiceTool;
import org.apache.jackrabbit.oak.segment.agentic.tools.ToolResult;
import org.apache.jackrabbit.oak.segment.agentic.tools.ValidatorApiTool;
import org.apache.jackrabbit.oak.segment.agentic.tools.ValidatorLLMChatTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP handler for chat endpoint.
 */
public class ChatHandler {
    private static final Logger log = LoggerFactory.getLogger(ChatHandler.class);
    
    private final LLMService llmService;
    private final RAGService ragService;
    private final List<AgenticTool> tools;
    private final Gson gson = new Gson();
    private final String baseUrl;
    private final boolean isSlingContext;
    
    public ChatHandler(LLMService llmService, RAGService ragService, String baseUrl) {
        this.llmService = llmService;
        this.ragService = ragService;
        this.baseUrl = baseUrl;
        this.isSlingContext = detectSlingContext();
        this.tools = new ArrayList<>();
        
        // Initialize tools based on context
        initializeTools();
    }
    
    /**
     * Detect if running in Sling context vs Validator context.
     */
    private boolean detectSlingContext() {
        // Check for Sling-specific system properties
        if (System.getProperty("sling.instance.id") != null ||
            System.getProperty("sling.server.url") != null) {
            return true;
        }
        
        // Check for Sling environment variable
        if (System.getenv("SLING_HOME") != null) {
            return true;
        }
        
        // Check if OSGi framework is available (Sling runs in OSGi)
        try {
            Class<?> frameworkUtilClass = Class.forName("org.osgi.framework.FrameworkUtil");
            Object bundle = frameworkUtilClass.getMethod("getBundle", Class.class)
                .invoke(null, getClass());
            if (bundle != null) {
                // Check if Sling bundles are present
                Object bundleContext = bundle.getClass()
                    .getMethod("getBundleContext").invoke(bundle);
                if (bundleContext != null) {
                    Object[] bundles = (Object[]) bundleContext.getClass()
                        .getMethod("getBundles").invoke(bundleContext);
                    if (bundles != null) {
                        for (Object b : bundles) {
                            String symbolicName = (String) b.getClass()
                                .getMethod("getSymbolicName").invoke(b);
                            if (symbolicName != null && symbolicName.contains("sling")) {
                                return true;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Not in OSGi context, likely validator
        }
        
        return false;
    }
    
    /**
     * Initialize tools based on context (Sling vs Validator).
     */
    private void initializeTools() {
        // Common tools
        this.tools.add(new LogAccessTool());
        
        if (isSlingContext) {
            // Sling-author context: Focus on Sling/Oak state, OSGi introspection
            log.info("🤖 Agentic Chat initialized in SLING-AUTHOR context");
            this.tools.add(new OSGiBundleTool());
            this.tools.add(new OSGiServiceTool());
            this.tools.add(new OSGiComponentTool());
            
            // Validator tools for querying validator state (read-only)
            String validatorUrl = getValidatorUrl();
            if (validatorUrl != null && !validatorUrl.isEmpty()) {
                this.tools.add(new ValidatorApiTool(validatorUrl));
                this.tools.add(new ValidatorLLMChatTool(validatorUrl));
            }
        } else {
            // Validator context: Focus on validator internals, Aeron, consensus
            log.info("🤖 Agentic Chat initialized in VALIDATOR context");
            this.tools.add(new ValidatorApiTool(baseUrl));
            // Validator doesn't need OSGi introspection (not running in OSGi)
        }
    }
    
    /**
     * Get validator URL from configuration (for Sling context).
     */
    private String getValidatorUrl() {
        // Check environment variable
        String url = System.getenv("OAK_GLOBAL_STORE_URL");
        if (url != null && !url.isEmpty()) {
            return url;
        }
        
        // Check system property
        url = System.getProperty("oak.globalStore.url");
        if (url != null && !url.isEmpty()) {
            return url;
        }
        
        // Try OSGi Configuration Admin
        try {
            Class<?> frameworkUtilClass = Class.forName("org.osgi.framework.FrameworkUtil");
            Object bundle = frameworkUtilClass.getMethod("getBundle", Class.class)
                .invoke(null, getClass());
            if (bundle != null) {
                Object bundleContext = bundle.getClass()
                    .getMethod("getBundleContext").invoke(bundle);
                if (bundleContext != null) {
                    // Try to get Configuration Admin
                    Object serviceRef = bundleContext.getClass()
                        .getMethod("getServiceReference", String.class)
                        .invoke(bundleContext, "org.osgi.service.cm.ConfigurationAdmin");
                    if (serviceRef != null) {
                        Object configAdmin = bundleContext.getClass()
                            .getMethod("getService", Class.forName("org.osgi.framework.ServiceReference"))
                            .invoke(bundleContext, serviceRef);
                        if (configAdmin != null) {
                            Object config = configAdmin.getClass()
                                .getMethod("getConfiguration", String.class)
                                .invoke(configAdmin, "org.apache.jackrabbit.oak.segment.http.HttpPersistenceService");
                            if (config != null) {
                                Object properties = config.getClass()
                                    .getMethod("getProperties").invoke(config);
                                if (properties != null) {
                                    Object value = properties.getClass()
                                        .getMethod("get", Object.class)
                                        .invoke(properties, "globalStoreUrl");
                                    if (value != null) {
                                        return value.toString();
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Could not read validator URL from OSGi config", e);
        }
        
        return "http://localhost:8091"; // Default
    }
    
    /**
     * Handle POST /v1/chat request.
     */
    public void handleChat(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        
        try {
            // Parse request body
            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = request.getReader()) {
                String line;
                while ((line = reader.readLine()) != null) {
                    body.append(line);
                }
            }
            
            ChatRequest chatRequest = gson.fromJson(body.toString(), ChatRequest.class);
            
            if (chatRequest.query == null || chatRequest.query.trim().isEmpty()) {
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                response.getWriter().write(gson.toJson(Map.of("error", "Query is required")));
                return;
            }
            
            log.info("💬 Chat query: {}", chatRequest.query);
            
            // Process chat request
            ChatResponse chatResponse = processChat(chatRequest);
            
            // Write response
            response.setStatus(HttpServletResponse.SC_OK);
            response.getWriter().write(gson.toJson(chatResponse));
            
        } catch (Exception e) {
            log.error("Error handling chat request", e);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.getWriter().write(gson.toJson(Map.of("error", e.getMessage())));
        }
    }
    
    private ChatResponse processChat(ChatRequest request) {
        ChatResponse response = new ChatResponse();
        
        // 1. RAG: Retrieve relevant code/docs
        List<RAGService.CodeChunk> relevantChunks = ragService.retrieve(request.query);
        
        // 2. Determine which tools to use
        List<AgenticTool> activeTools = selectTools(request.query);
        
        // 3. Execute tools
        Map<String, ToolResult> toolResults = new HashMap<>();
        for (AgenticTool tool : activeTools) {
            try {
                ToolResult result = tool.execute(request.query, request.context);
                toolResults.put(tool.getName(), result);
                
                // Add to sources
                if (result.success) {
                    ChatResponse.Source source = new ChatResponse.Source(
                        tool.getName(),
                        tool.getName(),
                        result.data
                    );
                    response.sources.add(source);
                }
            } catch (Exception e) {
                log.warn("Tool {} failed", tool.getName(), e);
            }
        }
        
        // 4. Build LLM context
        StringBuilder context = new StringBuilder();
        
        // Add context-specific information
        if (isSlingContext) {
            context.append("You are an AI assistant for Apache Sling authors.\n");
            context.append("Your focus is on Sling state, Oak internals, OSGi bundles/components/services, ");
            context.append("and troubleshooting Sling author issues.\n");
            context.append("You can query validator APIs and LLM chat for validator-specific questions.\n\n");
        } else {
            context.append("You are an AI assistant for Oak Segment Consensus validators.\n");
            context.append("Your focus is on validator internals, Aeron, Raft consensus, ");
            context.append("network state, and validator APIs.\n\n");
            
            // Add validator APIs info (all endpoints from API browser)
            context.append("Available Validator APIs:\n\n");
            
            context.append("📊 Explorer APIs:\n");
            context.append("- GET /api/explore?path=/ - Browse node tree structure (JSON)\n");
            context.append("- GET /api/segments/tars - List all TAR files and storage blocks (JSON)\n");
            context.append("- GET /api/segments/recent - Recent segment writes from journal (JSON)\n\n");
            
            context.append("💚 Health & Monitoring:\n");
            context.append("- GET /health - Basic health check (JSON)\n");
            context.append("- GET /health/deep - Comprehensive health validation (JSON)\n");
            context.append("- GET /api/metrics - Consensus & replication metrics (JSON)\n");
            context.append("- GET /metrics - Prometheus metrics (text)\n\n");
            
            context.append("🔄 Consensus APIs:\n");
            context.append("- GET /v1/consensus/status - Get consensus state (Aeron-aware)\n");
            context.append("- POST /v1/propose-write - Propose signed write transaction\n");
            context.append("- GET /v1/head - Get current HEAD record ID (text)\n\n");
            
            context.append("✈️ Aeron Cluster APIs:\n");
            context.append("- GET /v1/aeron/cluster-state - Complete Aeron Cluster state (JSON)\n");
            context.append("- GET /v1/aeron/raft-metrics - Raft-specific metrics (JSON)\n");
            context.append("- GET /v1/aeron/node-status?nodeId=0 - Status of specific cluster node (JSON)\n");
            context.append("- GET /v1/aeron/leadership-history?limit=10 - Recent leadership changes (JSON)\n\n");
            
            context.append("🌐 Registration & Discovery:\n");
            context.append("- POST /v1/register-client - Register a Sling author client\n");
            context.append("- GET /v1/peers - List all known validators (JSON)\n");
            context.append("- GET /v1/ngrok-url - Get public ngrok URL (text)\n\n");
            
            context.append("📄 Oak Files:\n");
            context.append("- GET /journal.log - Journal file (text)\n");
            context.append("- GET /manifest - Manifest file (text)\n");
            context.append("- GET /gc.log - Garbage collection log (text)\n");
            context.append("- GET /segments/{id} - Fetch segment by ID (binary)\n");
            context.append("- HEAD /segments/{id} - Check segment existence\n\n");
        }
        
        // Add available tools info
        context.append("Available Tools:\n");
        for (AgenticTool tool : tools) {
            context.append("- ").append(tool.getName()).append(": ").append(tool.getDescription()).append("\n");
        }
        context.append("\n");
        
        // Add tool results first (most important)
        if (!toolResults.isEmpty()) {
            context.append("Current System State:\n");
            for (Map.Entry<String, ToolResult> entry : toolResults.entrySet()) {
                if (entry.getValue().success) {
                    context.append(entry.getKey()).append(": ").append(entry.getValue().data).append("\n");
                }
            }
            context.append("\n");
        }
        
        // Add RAG chunks (code documentation)
        if (!relevantChunks.isEmpty()) {
            context.append("Relevant Code:\n");
            for (RAGService.CodeChunk chunk : relevantChunks) {
                context.append(chunk.name).append(": ").append(chunk.content).append("\n");
            }
            context.append("\n");
        }
        
        // 5. Call LLM
        String answer = llmService.generate(request.query, context.toString());
        response.answer = answer;
        
        // 6. Add RAG sources
        for (RAGService.CodeChunk chunk : relevantChunks) {
            ChatResponse.Source source = new ChatResponse.Source(
                "rag",
                chunk.filePath,
                chunk.content
            );
            response.sources.add(source);
        }
        
        return response;
    }
    
    private List<AgenticTool> selectTools(String query) {
        List<AgenticTool> selected = new ArrayList<>();
        
        for (AgenticTool tool : tools) {
            if (tool.shouldUse(query)) {
                selected.add(tool);
            }
        }
        
        return selected;
    }
}

