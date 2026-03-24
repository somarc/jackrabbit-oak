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
import org.apache.jackrabbit.oak.segment.agentic.rag.HybridRAGService;
import org.apache.jackrabbit.oak.segment.agentic.rag.RAGService;
import org.apache.jackrabbit.oak.segment.agentic.rag.VectorRAGService;
import org.apache.jackrabbit.oak.segment.agentic.tools.AgenticTool;
import org.apache.jackrabbit.oak.segment.agentic.tools.AgentDiscoveryTool;
import org.apache.jackrabbit.oak.segment.agentic.tools.ApiDocumentationTool;
import org.apache.jackrabbit.oak.segment.agentic.tools.ConnectivityDiagnosticTool;
import org.apache.jackrabbit.oak.segment.agentic.tools.LogAccessTool;
import org.apache.jackrabbit.oak.segment.agentic.tools.OSGiBundleTool;
import org.apache.jackrabbit.oak.segment.agentic.tools.OSGiComponentTool;
import org.apache.jackrabbit.oak.segment.agentic.tools.OSGiServiceTool;
import org.apache.jackrabbit.oak.segment.agentic.tools.TarMkAnalysisTool;
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
 * 
 * <p>Features:
 * <ul>
 *   <li>Hybrid RAG (vector + keyword) for better code retrieval</li>
 *   <li>Conversation memory for multi-turn context</li>
 *   <li>Dynamic model selection based on query complexity</li>
 *   <li>Agent-to-agent communication support</li>
 * </ul>
 * 
 * @since 1.89
 */
public class ChatHandler {
    private static final Logger log = LoggerFactory.getLogger(ChatHandler.class);
    
    private final LLMService llmService;
    /** Keyword-based RAG service (used by hybridRAGService) */
    @SuppressWarnings("unused") // Used internally by hybridRAGService
    private final RAGService ragService;
    private final VectorRAGService vectorRAGService;
    private final HybridRAGService hybridRAGService;
    private final ConversationMemory conversationMemory;
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
        this.conversationMemory = new ConversationMemory();
        
        // Initialize vector RAG service
        this.vectorRAGService = new VectorRAGService();
        
        // Initialize hybrid RAG service
        this.hybridRAGService = new HybridRAGService(ragService, vectorRAGService);
        
        // Initialize tools based on context
        initializeTools();
        
        // Initialize hybrid RAG in background
        initializeHybridRAG();
    }
    
    /**
     * Initialize hybrid RAG service in background thread.
     */
    private void initializeHybridRAG() {
        Thread initThread = new Thread(() -> {
            try {
                log.info("🔄 Initializing hybrid RAG service...");
                hybridRAGService.initialize();
                log.info("✅ Hybrid RAG ready: {}", hybridRAGService.getMode());
            } catch (Exception e) {
                log.warn("⚠️  Hybrid RAG initialization failed, using keyword-only: {}", e.getMessage());
            }
        }, "hybrid-rag-init");
        initThread.setDaemon(true);
        initThread.start();
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
        this.tools.add(new AgentDiscoveryTool());
        this.tools.add(new ConnectivityDiagnosticTool());
        
        // API documentation tool (always available, especially important for agent-to-agent)
        String validatorUrl = isSlingContext ? getValidatorUrl() : baseUrl;
        if (validatorUrl == null || validatorUrl.isEmpty()) {
            validatorUrl = "http://localhost:8091"; // Default fallback
        }
        this.tools.add(new ApiDocumentationTool(isSlingContext, validatorUrl));
        
        if (isSlingContext) {
            // Sling-author context: Focus on Sling/Oak state, OSGi introspection
            log.info("🤖 Agentic Chat initialized in SLING-AUTHOR context");
            this.tools.add(new OSGiBundleTool());
            this.tools.add(new OSGiServiceTool());
            this.tools.add(new OSGiComponentTool());
            
            // Validator tools for querying validator state (read-only)
            if (validatorUrl != null && !validatorUrl.isEmpty()) {
                this.tools.add(new ValidatorApiTool(validatorUrl));
                this.tools.add(new ValidatorLLMChatTool(validatorUrl));
            }
        } else {
            // Validator context: Focus on validator internals, Aeron, consensus
            log.info("🤖 Agentic Chat initialized in VALIDATOR context");
            this.tools.add(new ValidatorApiTool(baseUrl));
            this.tools.add(new TarMkAnalysisTool(baseUrl));
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
                                .invoke(configAdmin, "org.apache.jackrabbit.oak.segment.consensus.aeron.AeronClusterService");
                            if (config != null) {
                                Object properties = config.getClass()
                                    .getMethod("getProperties").invoke(config);
                                if (properties != null) {
                                    Object value = properties.getClass()
                                        .getMethod("get", Object.class)
                                        .invoke(properties, "selfUrl");
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
            
            // Support both 'query' and 'message' field names for backward compatibility
            String effectiveQuery = chatRequest.getEffectiveQuery();
            if (effectiveQuery == null || effectiveQuery.trim().isEmpty()) {
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                response.getWriter().write(gson.toJson(Map.of("error", "Query is required (use 'query' or 'message' field)")));
                return;
            }
            
            // Normalize to query field for downstream processing
            chatRequest.query = effectiveQuery;
            
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
        
        // Check for agent-to-agent communication metadata
        boolean isAgentToAgent = false;
        Map<String, Object> agentMetadata = null;
        
        // Check for top-level agentMode flag (backward compatibility)
        if (request.agentMode != null && request.agentMode) {
            isAgentToAgent = true;
            agentMetadata = new HashMap<>();
            agentMetadata.put("requestingAgentId", getWalletAddress());
            agentMetadata.put("requestingAgentType", isSlingContext ? "sling-author" : "validator");
            agentMetadata.put("requestingCapabilities", getRespondingCapabilities());
            log.info("🤖 Agent-to-Agent mode enabled (agentMode flag) - Agent: {} ({})", 
                agentMetadata.get("requestingAgentId"), agentMetadata.get("requestingAgentType"));
        }
        
        if (request.context != null && !isAgentToAgent) {
            // Check for explicit agentToAgent flag (from UI toggle)
            Object agentToAgentFlag = request.context.get("agentToAgent");
            if (agentToAgentFlag != null && Boolean.TRUE.equals(agentToAgentFlag)) {
                isAgentToAgent = true;
                agentMetadata = new HashMap<>();
                // Use wallet address as agent ID
                agentMetadata.put("requestingAgentId", getWalletAddress());
                agentMetadata.put("requestingAgentType", isSlingContext ? "sling-author" : "validator");
                agentMetadata.put("requestingCapabilities", getRespondingCapabilities());
                log.info("🤖 Agent-to-Agent mode enabled (UI toggle) - Agent: {} ({})", 
                    agentMetadata.get("requestingAgentId"), agentMetadata.get("requestingAgentType"));
            }
            // Also check for explicit agent context (from another agent)
            Object agentContext = request.context.get("agentId");
            if (agentContext != null && !isAgentToAgent) {
                isAgentToAgent = true;
                agentMetadata = new HashMap<>();
                agentMetadata.put("requestingAgentId", request.context.get("agentId"));
                agentMetadata.put("requestingAgentType", request.context.get("agentType"));
                agentMetadata.put("requestingCapabilities", request.context.get("capabilities"));
                log.info("🤖 Agent-to-Agent request from {} ({})", 
                    request.context.get("agentId"), request.context.get("agentType"));
            }
        }
        
        // 1. RAG: Retrieve relevant code/docs using hybrid search (vector + keyword)
        List<RAGService.CodeChunk> relevantChunks = hybridRAGService.retrieve(request.query, 10);
        log.debug("RAG mode: {}, retrieved {} chunks", hybridRAGService.getMode(), relevantChunks.size());
        
        // 2. Determine which tools to use
        List<AgenticTool> activeTools = selectTools(request.query);
        
        // In agent-to-agent mode, be more proactive about executing tools
        if (isAgentToAgent) {
            // Always include API documentation tool
            ApiDocumentationTool apiDocTool = null;
            for (AgenticTool tool : tools) {
                if (tool instanceof ApiDocumentationTool) {
                    apiDocTool = (ApiDocumentationTool) tool;
                    break;
                }
            }
            if (apiDocTool != null && !activeTools.contains(apiDocTool)) {
                activeTools.add(apiDocTool);
            }
            
            // In agent-to-agent mode, be more aggressive about executing API tools
            // If the query seems to ask for data, try to get it proactively
            String lowerQuery = request.query.toLowerCase();
            boolean needsData = lowerQuery.contains("what") || lowerQuery.contains("show") || 
                               lowerQuery.contains("get") || lowerQuery.contains("current") ||
                               lowerQuery.contains("status") || lowerQuery.contains("state") ||
                               lowerQuery.contains("leader") || lowerQuery.contains("cluster");
            
            if (needsData) {
                // Add ValidatorApiTool if not already selected and we're in validator context
                if (!isSlingContext) {
                    ValidatorApiTool validatorApiTool = null;
                    for (AgenticTool tool : tools) {
                        if (tool instanceof ValidatorApiTool) {
                            validatorApiTool = (ValidatorApiTool) tool;
                            break;
                        }
                    }
                    if (validatorApiTool != null && !activeTools.contains(validatorApiTool)) {
                        activeTools.add(validatorApiTool);
                        log.debug("🤖 Agent-to-Agent: Proactively adding ValidatorApiTool to fetch data");
                    }
                }
            }
        }
        
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
        
        // Add conversation history if session ID provided
        if (request.sessionId != null && !request.sessionId.isEmpty()) {
            String conversationContext = conversationMemory.getContextForLLM(request.sessionId);
            if (!conversationContext.isEmpty()) {
                context.append(conversationContext).append("\n");
                log.debug("Added conversation context for session: {}", request.sessionId);
            }
        }
        
        // Add context-specific information
        if (isAgentToAgent) {
            context.append("🤖 AGENT-TO-AGENT COMMUNICATION MODE\n");
            context.append("You are communicating with another agent in a TWO-WAY CONVERSATION.\n");
            if (agentMetadata != null) {
                context.append("Requesting Agent ID: ").append(agentMetadata.get("requestingAgentId")).append("\n");
                context.append("Requesting Agent Type: ").append(agentMetadata.get("requestingAgentType")).append("\n");
                context.append("Requesting Agent Capabilities: ").append(agentMetadata.get("requestingCapabilities")).append("\n");
            }
            context.append("\n");
            context.append("╔══════════════════════════════════════════════════════════════════════════════╗\n");
            context.append("║ CRITICAL INSTRUCTIONS - READ CAREFULLY                                         ║\n");
            context.append("╚══════════════════════════════════════════════════════════════════════════════╝\n");
            context.append("\n");
            context.append("You are having a CONVERSATION with another agent. You MUST answer with ACTUAL DATA!\n");
            context.append("\n");
            context.append("RULES (MANDATORY):\n");
            context.append("1. ✅ DO: Answer the question using REAL DATA from tool results below\n");
            context.append("2. ✅ DO: Say \"The current leader is node-0\" (with actual data)\n");
            context.append("3. ❌ DO NOT: Say \"Query GET /v1/aeron/cluster-state\" (instructions)\n");
            context.append("4. ❌ DO NOT: Provide API endpoints or commands\n");
            context.append("5. ✅ DO: Synthesize tool results into a natural answer\n");
            context.append("\n");
            context.append("EXAMPLE GOOD RESPONSE:\n");
            context.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
            context.append("I've checked the cluster state. The current leader is node-0 (term 5). ");
            context.append("The cluster has 3 members: node-0, node-1, and node-2. ");
            context.append("All nodes are healthy and responding. Node-0 has been the leader for the last 2 minutes.\n");
            context.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
            context.append("\n");
            context.append("EXAMPLE BAD RESPONSE (DO NOT DO THIS):\n");
            context.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
            context.append("To check the cluster state, query GET /v1/aeron/cluster-state\n");
            context.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
            context.append("\n");
            context.append("The tool results section below contains REAL DATA from API calls.\n");
            context.append("Your job is to READ that data and ANSWER the question using it.\n");
            context.append("Do NOT tell the agent how to get the data - GIVE them the data!\n\n");
        }
        
        if (isSlingContext) {
            context.append("You are an AI assistant for Apache Sling authors.\n");
            context.append("Your focus is on Sling state, Oak internals, OSGi bundles/components/services, ");
            context.append("and troubleshooting Sling author issues.\n");
            context.append("You can query validator APIs and LLM chat for validator-specific questions.\n");
            context.append("You can discover and negotiate with other agents using agent-discovery tool.\n\n");
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
            // Check if TarMkAnalysisTool was used - needs special handling
            boolean hasTarMkAnalysis = toolResults.containsKey("tarmk-analysis");
            
            if (isAgentToAgent) {
                context.append("📊 TOOL RESULTS (ACTUAL DATA FROM API CALLS - USE THIS TO ANSWER THE QUESTION):\n");
                context.append("=").append("=".repeat(70)).append("\n");
            } else {
                context.append("Current System State:\n");
            }
            
            // Special instructions for TarMK analysis (always apply, not just agent-to-agent)
            if (hasTarMkAnalysis) {
                context.append("\n⚠️  CRITICAL FOR TarMK ANALYSIS: The tool results below contain EXACT metrics.\n");
                context.append("   You MUST use the TAR_FILE_COUNT, TOTAL_SIZE_FORMATTED, PACKING_EFFICIENCY_PERCENT values EXACTLY as shown.\n");
                context.append("   Do NOT invent different numbers like '100 files' or '50 GB' - use the ACTUAL values provided.\n");
                context.append("   If it says 40 files and 10.0 MB, report exactly that - not estimates or examples.\n");
                context.append("   Look for lines like 'TAR_FILE_COUNT=40' and 'TOTAL_SIZE_FORMATTED=10.0 MB' - use those EXACT values.\n\n");
            }
            
            for (Map.Entry<String, ToolResult> entry : toolResults.entrySet()) {
                if (entry.getValue().success) {
                    if (isAgentToAgent) {
                        context.append("\n[").append(entry.getKey()).append("]\n");
                        context.append(entry.getValue().data);
                        context.append("\n");
                    } else {
                        context.append(entry.getKey()).append(": ").append(entry.getValue().data).append("\n");
                    }
                } else {
                    if (isAgentToAgent) {
                        context.append("\n[").append(entry.getKey()).append("] FAILED: ").append(entry.getValue().data).append("\n");
                    }
                }
            }
            if (isAgentToAgent) {
                context.append("=").append("=".repeat(70)).append("\n");
                context.append("\n");
                context.append("IMPORTANT: The data above is REAL, ACTUAL data from API calls. ");
                context.append("Use this data to answer the requesting agent's question in a natural, conversational way.\n");
                if (hasTarMkAnalysis) {
                    context.append("For TarMK metrics, use the EXACT numbers from TAR_FILE_COUNT, TOTAL_SIZE_FORMATTED, etc. ");
                    context.append("Do NOT make up different numbers.\n");
                }
                context.append("Do NOT just repeat the instructions - synthesize the data into a helpful response.\n\n");
            } else {
                // Even in non-agent-to-agent mode, add special instructions for TarMK
                if (hasTarMkAnalysis) {
                    context.append("\n⚠️  CRITICAL: For TarMK analysis, use the EXACT metric values shown above.\n");
                    context.append("   Do NOT invent or estimate different numbers. Use TAR_FILE_COUNT, TOTAL_SIZE_FORMATTED, etc. exactly as provided.\n\n");
                } else {
                    context.append("\n");
                }
            }
        } else if (isAgentToAgent) {
            context.append("⚠️  No tool results available. You may need to suggest which API endpoint to call.\n\n");
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
        
        // 6. Add agent metadata if this is agent-to-agent communication
        if (isAgentToAgent && agentMetadata != null) {
            // Add response agent metadata (using Ethereum wallet address)
            agentMetadata.put("respondingAgentId", getWalletAddress());
            agentMetadata.put("respondingAgentType", isSlingContext ? "sling-author" : "validator");
            agentMetadata.put("respondingCapabilities", getRespondingCapabilities());
            // Store in a way that can be accessed (we'll add this to response later)
            // For now, append to answer
            answer += "\n\n--- Agent Metadata ---\n";
            answer += "Responding Agent: " + agentMetadata.get("respondingAgentId") + " (" + 
                     agentMetadata.get("respondingAgentType") + ")\n";
            answer += "Capabilities: " + agentMetadata.get("respondingCapabilities") + "\n";
            response.answer = answer;
        }
        
        // 7. Add RAG sources
        for (RAGService.CodeChunk chunk : relevantChunks) {
            ChatResponse.Source source = new ChatResponse.Source(
                "rag",
                chunk.filePath,
                chunk.content
            );
            response.sources.add(source);
        }
        
        // 8. Save to conversation memory if session ID provided
        if (request.sessionId != null && !request.sessionId.isEmpty()) {
            conversationMemory.addTurn(request.sessionId, request.query, response.answer);
            log.debug("Saved turn to conversation memory for session: {}", request.sessionId);
        }
        
        // 9. Add metadata about RAG mode to response
        response.metadata = new HashMap<>();
        response.metadata.put("ragMode", hybridRAGService.getMode());
        response.metadata.put("chunksRetrieved", relevantChunks.size());
        if (request.sessionId != null) {
            response.metadata.put("sessionId", request.sessionId);
            response.metadata.put("conversationTurns", conversationMemory.getTurnCount(request.sessionId));
        }
        
        return response;
    }
    
    /**
     * Get Ethereum wallet address for agent identification.
     * Uses 0x wallet address for provable identity and signing.
     */
    private String getWalletAddress() {
        // Try to get wallet address from OSGi service (Sling context)
        if (isSlingContext) {
            try {
                Object bundleContext = getBundleContext();
                String address = getWalletAddressFromOsgi(bundleContext);
                if (address != null && !address.isEmpty()) {
                    return address;
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

    private String getWalletAddressFromOsgi(Object bundleContext) throws Exception {
        if (bundleContext == null) {
            return null;
        }
        String[] serviceNames = {
            "com.oakchain.connector.wallet.SlingAuthorWalletService",
            "org.apache.jackrabbit.oak.segment.http.wallet.SlingAuthorWalletService"
        };
        for (String serviceName : serviceNames) {
            Object serviceRef = bundleContext.getClass()
                .getMethod("getServiceReference", String.class)
                .invoke(bundleContext, serviceName);
            if (serviceRef == null) {
                continue;
            }
            Object walletService = bundleContext.getClass()
                .getMethod("getService", Class.forName("org.osgi.framework.ServiceReference"))
                .invoke(bundleContext, serviceRef);
            if (walletService == null) {
                continue;
            }
            String address = (String) walletService.getClass()
                .getMethod("getWalletAddress").invoke(walletService);
            if (address != null && !address.isEmpty()) {
                return address;
            }
        }
        return null;
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
     * Get capabilities of this responding agent.
     */
    private List<String> getRespondingCapabilities() {
        List<String> capabilities = new ArrayList<>();
        
        if (isSlingContext) {
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
