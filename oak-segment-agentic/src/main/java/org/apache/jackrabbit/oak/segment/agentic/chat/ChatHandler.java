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
import org.apache.jackrabbit.oak.segment.agentic.tools.ToolResult;
import org.apache.jackrabbit.oak.segment.agentic.tools.ValidatorApiTool;
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
    
    public ChatHandler(LLMService llmService, RAGService ragService, String baseUrl) {
        this.llmService = llmService;
        this.ragService = ragService;
        this.baseUrl = baseUrl;
        this.tools = new ArrayList<>();
        
        // Initialize tools
        this.tools.add(new ValidatorApiTool(baseUrl));
        this.tools.add(new LogAccessTool());
        // Future: Add MetricsTool
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
        
        // Add available APIs info
        context.append("Available Validator APIs:\n");
        context.append("- /v1/aeron/cluster-state - Get cluster state and leader info\n");
        context.append("- /v1/consensus/status - Get consensus status\n");
        context.append("- /v1/peers - List all peers\n");
        context.append("- /v1/aeron/raft-metrics - Get Raft metrics\n\n");
        
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

