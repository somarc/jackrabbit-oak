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
package org.apache.jackrabbit.oak.segment.agentic.llm;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
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

/**
 * LLM service implementation using Ollama (local LLM server).
 * 
 * <p>Requires Ollama to be running locally with a model installed.
 * Example setup:
 * <pre>
 *   # Install Ollama: https://ollama.ai
 *   ollama pull qwen3:8b           # Fast/default model
 *   ollama pull devstral:24b       # Balanced model for complex reasoning
 *   ollama pull nomic-embed-text   # Embeddings for RAG
 * </pre>
 * 
 * <p>Supports dynamic model selection based on query complexity:
 * <ul>
 *   <li>Fast model (qwen3:8b): Quick responses for simple queries</li>
 *   <li>Balanced model (devstral:24b): Complex reasoning and explanations</li>
 * </ul>
 * 
 * @since 1.89
 */
public class OllamaLLMService implements LLMService {
    private static final Logger log = LoggerFactory.getLogger(OllamaLLMService.class);
    
    /** Fast model for quick responses (used as default) */
    public static final String MODEL_FAST = "qwen3:8b";
    
    /** Balanced model for complex reasoning */
    private static final String MODEL_BALANCED = "devstral:24b";
    
    private final String ollamaUrl;
    private final String defaultModelName;
    private final Gson gson = new Gson();
    private final CloseableHttpClient httpClient;
    private volatile boolean available = false;
    private volatile boolean balancedModelAvailable = false;
    
    public OllamaLLMService() {
        this(getOllamaUrlFromConfig(), getModelNameFromConfig());
    }
    
    /**
     * Get Ollama URL from environment variable or system property, with fallback.
     * Supports Docker environments by checking for host.docker.internal.
     */
    private static String getOllamaUrlFromConfig() {
        // Check environment variable first
        String url = System.getenv("OLLAMA_URL");
        if (url != null && !url.isEmpty()) {
            return url;
        }
        
        // Check system property
        url = System.getProperty("ollama.url");
        if (url != null && !url.isEmpty()) {
            return url;
        }
        
        // Default: detect if running in Docker (check for common Docker indicators)
        // If in Docker, use host.docker.internal to reach Ollama on host machine
        // Otherwise, use localhost for direct host execution
        boolean isDocker = System.getenv("container") != null || 
                          System.getProperty("java.class.path", "").contains("/opt/sling") ||
                          System.getProperty("user.name", "").equals("sling");
        
        if (isDocker) {
            return "http://host.docker.internal:11434";
        } else {
            return "http://localhost:11434";
        }
    }
    
    /**
     * Get model name from environment variable or system property, with fallback.
     */
    private static String getModelNameFromConfig() {
        String model = System.getenv("OLLAMA_MODEL");
        if (model != null && !model.isEmpty()) {
            return model;
        }
        
        model = System.getProperty("ollama.model");
        if (model != null && !model.isEmpty()) {
            return model;
        }
        
        return MODEL_FAST; // Default model
    }
    
    public OllamaLLMService(String ollamaUrl, String modelName) {
        this.ollamaUrl = ollamaUrl;
        this.defaultModelName = modelName;
        
        // Create HTTP client with longer timeouts for LLM inference
        RequestConfig requestConfig = RequestConfig.custom()
            .setConnectTimeout(10000)
            .setSocketTimeout(180000)  // 3 minutes for LLM inference (qwen3 can be slower)
            .build();
        this.httpClient = HttpClients.custom()
            .setDefaultRequestConfig(requestConfig)
            .build();
        
        // Check availability on construction
        checkAvailability();
    }
    
    private void checkAvailability() {
        try {
            // Try to list models to verify Ollama is running
            HttpGet request = new HttpGet(ollamaUrl + "/api/tags");
            try (CloseableHttpResponse response = httpClient.execute(request)) {
                int statusCode = response.getStatusLine().getStatusCode();
                if (statusCode >= 200 && statusCode < 300) {
                    String responseBody = EntityUtils.toString(response.getEntity());
                    available = true;
                    
                    // Check if balanced model is available
                    if (responseBody.contains(MODEL_BALANCED)) {
                        balancedModelAvailable = true;
                        log.info("✅ Ollama LLM service available at {} with models: {} (fast), {} (balanced)", 
                            ollamaUrl, defaultModelName, MODEL_BALANCED);
                    } else {
                        log.info("✅ Ollama LLM service available at {} with model {}", ollamaUrl, defaultModelName);
                        log.info("💡 For better reasoning, also pull: ollama pull {}", MODEL_BALANCED);
                    }
                } else {
                    log.warn("⚠️  Ollama service not available: HTTP {}", statusCode);
                    available = false;
                }
            }
        } catch (Exception e) {
            log.warn("⚠️  Ollama LLM service not available: {}", e.getMessage());
            log.debug("To enable LLM chat, install Ollama: https://ollama.ai and run: ollama pull {}", defaultModelName);
            available = false;
        }
    }
    
    /**
     * Select the best model for a query based on complexity.
     * 
     * @param query User's query
     * @param isAgentToAgent Whether this is agent-to-agent communication
     * @return Model name to use
     */
    private String selectModel(String query, boolean isAgentToAgent) {
        // Agent-to-agent always uses balanced model for better reasoning
        if (isAgentToAgent && balancedModelAvailable) {
            return MODEL_BALANCED;
        }
        
        String lowerQuery = query.toLowerCase();
        
        // Complex reasoning queries benefit from balanced model
        boolean needsReasoning = 
            lowerQuery.contains("explain") ||
            lowerQuery.contains("how does") ||
            lowerQuery.contains("why") ||
            lowerQuery.contains("architecture") ||
            lowerQuery.contains("design") ||
            lowerQuery.contains("compare") ||
            lowerQuery.contains("difference") ||
            lowerQuery.contains("analyze") ||
            lowerQuery.contains("debug") ||
            lowerQuery.contains("troubleshoot") ||
            lowerQuery.length() > 200;  // Long queries often need more reasoning
        
        if (needsReasoning && balancedModelAvailable) {
            log.debug("Using balanced model ({}) for complex query", MODEL_BALANCED);
            return MODEL_BALANCED;
        }
        
        return defaultModelName;
    }
    
    /**
     * Get the current model being used.
     * 
     * @return Default model name
     */
    public String getModelName() {
        return defaultModelName;
    }
    
    /**
     * Check if balanced model is available.
     * 
     * @return true if balanced model is available
     */
    public boolean isBalancedModelAvailable() {
        return balancedModelAvailable;
    }
    
    @Override
    public boolean isAvailable() {
        return available;
    }
    
    @Override
    public String generate(String query, String context) {
        if (!available) {
            return "LLM service is not available. Please ensure Ollama is running and model '" + defaultModelName + "' is installed.\n" +
                   "Install: https://ollama.ai\n" +
                   "Pull model: ollama pull " + defaultModelName;
        }
        
        try {
            // Detect if this is agent-to-agent mode from context
            boolean isAgentToAgent = context != null && (
                context.contains("AGENT-TO-AGENT") || 
                context.contains("TWO-WAY CONVERSATION") ||
                context.contains("TOOL RESULTS")
            );
            
            // Select best model for this query
            String selectedModel = selectModel(query, isAgentToAgent);
            
            // Build prompt with context
            StringBuilder prompt = new StringBuilder();
            
            if (isAgentToAgent) {
                // Agent-to-agent mode: Very explicit instructions
                prompt.append("You are an AI agent communicating with another AI agent.\n");
                prompt.append("CRITICAL: You MUST answer with ACTUAL DATA, not instructions!\n");
                prompt.append("The tool results below contain REAL data from API calls - USE IT!\n\n");
            } else {
                prompt.append("You are an AI assistant for Apache Jackrabbit Oak validators.\n");
                prompt.append("Answer questions concisely using the provided context.\n\n");
            }
            
            if (context != null && !context.isEmpty()) {
                // Put tool results FIRST if in agent-to-agent mode (LLMs pay more attention to what comes first)
                if (isAgentToAgent && context.contains("TOOL RESULTS")) {
                    // Extract tool results section and put it first
                    int toolStart = context.indexOf("TOOL RESULTS");
                    String separator = "=".repeat(70);
                    int toolEnd = context.indexOf(separator, toolStart + 12);
                    if (toolEnd > toolStart) {
                        String toolResults = context.substring(toolStart, toolEnd + separator.length());
                        String restOfContext = context.substring(0, toolStart) + context.substring(toolEnd + separator.length());
                        prompt.append(toolResults).append("\n\n");
                        prompt.append(restOfContext);
                    } else {
                        prompt.append(context);
                    }
                } else {
                    prompt.append(context);
                }
                prompt.append("\n");
            }
            
            prompt.append("Question: ").append(query).append("\n\n");
            
            if (isAgentToAgent) {
                prompt.append("REMEMBER: Answer with ACTUAL DATA from the tool results above.\n");
                prompt.append("Do NOT provide instructions - provide the ANSWER with real data!\n");
                prompt.append("Example: \"The current leader is node-0 (term 5)\" NOT \"Query GET /v1/aeron/cluster-state\"\n\n");
            }
            
            prompt.append("Answer:");
            
            // Build JSON request
            JsonObject requestJson = new JsonObject();
            requestJson.addProperty("model", selectedModel);
            requestJson.addProperty("prompt", prompt.toString());
            requestJson.addProperty("stream", false);
            
            log.debug("Using model: {} for query: {}...", selectedModel, 
                query.length() > 50 ? query.substring(0, 50) : query);
            
            // For agent-to-agent mode, use lower temperature for more deterministic responses
            // and higher top_p to focus on the most likely tokens
            if (isAgentToAgent) {
                JsonObject options = new JsonObject();
                options.addProperty("temperature", 0.3);  // Lower = more deterministic
                options.addProperty("top_p", 0.9);        // Focus on high-probability tokens
                options.addProperty("num_predict", 500);  // Limit response length
                requestJson.add("options", options);
            }
            
            String jsonPayload = gson.toJson(requestJson);
            
            // Execute request
            HttpPost request = new HttpPost(ollamaUrl + "/api/generate");
            request.setEntity(new StringEntity(jsonPayload, "UTF-8"));
            request.setHeader("Content-Type", "application/json");
            
            try (CloseableHttpResponse response = httpClient.execute(request)) {
                int statusCode = response.getStatusLine().getStatusCode();
                String responseBody = EntityUtils.toString(response.getEntity());
                
                if (statusCode >= 200 && statusCode < 300) {
                    JsonObject jsonResponse = gson.fromJson(responseBody, JsonObject.class);
                    
                    if (jsonResponse.has("response")) {
                        return jsonResponse.get("response").getAsString();
                    } else {
                        log.error("Unexpected Ollama response format: {}", responseBody);
                        return "Error: Unexpected response format from LLM service";
                    }
                } else {
                    log.error("Ollama API error: HTTP {} - {}", statusCode, responseBody);
                    return "Error calling LLM service: HTTP " + statusCode;
                }
            }
        } catch (IOException e) {
            log.error("Error calling Ollama LLM service", e);
            return "Error calling LLM service: " + e.getMessage();
        }
    }
}
