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
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

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
    
    private final OkHttpClient httpClient;
    private final String ollamaUrl;
    private final String modelName;
    private final Gson gson = new Gson();
    private volatile boolean available = false;
    
    public OllamaLLMService() {
        this("http://localhost:11434", "phi3");
    }
    
    public OllamaLLMService(String ollamaUrl, String modelName) {
        this.ollamaUrl = ollamaUrl;
        this.modelName = modelName;
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)  // Increased for LLM inference
            .writeTimeout(60, TimeUnit.SECONDS)
            .build();
        
        // Check availability on construction
        checkAvailability();
    }
    
    private void checkAvailability() {
        try {
            // Try to list models to verify Ollama is running
            Request request = new Request.Builder()
                .url(ollamaUrl + "/api/tags")
                .get()
                .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    available = true;
                    log.info("✅ Ollama LLM service available at {} with model {}", ollamaUrl, modelName);
                } else {
                    log.warn("⚠️  Ollama service not available: HTTP {}", response.code());
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
            
            RequestBody body = RequestBody.create(
                gson.toJson(requestJson),
                MediaType.get("application/json")
            );
            
            Request request = new Request.Builder()
                .url(ollamaUrl + "/api/generate")
                .post(body)
                .build();
            
            // Execute request
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errorBody = response.body() != null ? response.body().string() : "Unknown error";
                    log.error("Ollama API error: HTTP {} - {}", response.code(), errorBody);
                    return "Error calling LLM service: HTTP " + response.code();
                }
                
                String responseBody = response.body().string();
                JsonObject jsonResponse = gson.fromJson(responseBody, JsonObject.class);
                
                if (jsonResponse.has("response")) {
                    return jsonResponse.get("response").getAsString();
                } else {
                    log.error("Unexpected Ollama response format: {}", responseBody);
                    return "Error: Unexpected response format from LLM service";
                }
            }
        } catch (IOException e) {
            log.error("Error calling Ollama LLM service", e);
            return "Error calling LLM service: " + e.getMessage();
        }
    }
}

