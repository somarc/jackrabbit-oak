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
 *   ollama pull phi3
 *   # Or: ollama pull llama3.1:8b
 * </pre>
 */
public class OllamaLLMService implements LLMService {
    private static final Logger log = LoggerFactory.getLogger(OllamaLLMService.class);
    
    private final String ollamaUrl;
    private final String modelName;
    private final Gson gson = new Gson();
    private final CloseableHttpClient httpClient;
    private volatile boolean available = false;
    
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
        
        return "phi3"; // Default model
    }
    
    public OllamaLLMService(String ollamaUrl, String modelName) {
        this.ollamaUrl = ollamaUrl;
        this.modelName = modelName;
        
        // Create HTTP client with longer timeouts for LLM inference
        RequestConfig requestConfig = RequestConfig.custom()
            .setConnectTimeout(10000)
            .setSocketTimeout(120000)  // 2 minutes for LLM inference
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
                    available = true;
                    log.info("✅ Ollama LLM service available at {} with model {}", ollamaUrl, modelName);
                } else {
                    log.warn("⚠️  Ollama service not available: HTTP {}", statusCode);
                    available = false;
                }
            }
        } catch (Exception e) {
            log.warn("⚠️  Ollama LLM service not available: {}", e.getMessage());
            log.debug("To enable LLM chat, install Ollama: https://ollama.ai and run: ollama pull {}", modelName);
            available = false;
        }
    }
    
    @Override
    public boolean isAvailable() {
        return available;
    }
    
    @Override
    public String generate(String query, String context) {
        if (!available) {
            return "LLM service is not available. Please ensure Ollama is running and model '" + modelName + "' is installed.\n" +
                   "Install: https://ollama.ai\n" +
                   "Pull model: ollama pull " + modelName;
        }
        
        try {
            // Build prompt with context
            StringBuilder prompt = new StringBuilder();
            prompt.append("You are an AI assistant for Apache Jackrabbit Oak validators.\n");
            prompt.append("Answer questions concisely using the provided context.\n\n");
            
            if (context != null && !context.isEmpty()) {
                prompt.append("Context:\n");
                prompt.append(context);
                prompt.append("\n");
            }
            
            prompt.append("Question: ").append(query).append("\n\n");
            prompt.append("Answer:");
            
            // Build JSON request
            JsonObject requestJson = new JsonObject();
            requestJson.addProperty("model", modelName);
            requestJson.addProperty("prompt", prompt.toString());
            requestJson.addProperty("stream", false);
            // Options can be added here if needed (e.g., temperature, top_p)
            
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

