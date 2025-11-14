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
import java.util.Map;

/**
 * Tool for LLM-to-LLM chat with validator's agentic chat interface.
 * Allows Sling authors to query validators using their LLM capabilities.
 */
public class ValidatorLLMChatTool implements AgenticTool {
    private static final Logger log = LoggerFactory.getLogger(ValidatorLLMChatTool.class);
    
    private final String validatorBaseUrl;
    private final Gson gson = new Gson();
    private final CloseableHttpClient httpClient;
    
    public ValidatorLLMChatTool(String validatorBaseUrl) {
        this.validatorBaseUrl = validatorBaseUrl;
        RequestConfig requestConfig = RequestConfig.custom()
            .setConnectTimeout(10000)
            .setSocketTimeout(120000)  // 2 minutes for LLM inference
            .build();
        this.httpClient = HttpClients.custom()
            .setDefaultRequestConfig(requestConfig)
            .build();
    }
    
    @Override
    public String getName() {
        return "validator-llm-chat";
    }
    
    @Override
    public String getDescription() {
        return "Query validator's LLM chat interface for validator-specific questions (Aeron, consensus, network state, validator internals)";
    }
    
    @Override
    public boolean shouldUse(String query) {
        String lower = query.toLowerCase();
        // Only use for validator-specific questions when running in Sling context
        return (lower.contains("validator") || lower.contains("aeron") || 
                lower.contains("consensus") || lower.contains("raft") ||
                lower.contains("cluster") || lower.contains("network")) &&
               !lower.contains("sling") && !lower.contains("osgi");
    }
    
    @Override
    public ToolResult execute(String query, Map<String, Object> context) {
        try {
            // Build chat request
            String chatUrl = validatorBaseUrl + "/v1/chat";
            
            Map<String, String> requestBody = Map.of("query", query);
            String jsonPayload = gson.toJson(requestBody);
            
            HttpPost request = new HttpPost(chatUrl);
            request.setEntity(new StringEntity(jsonPayload, "UTF-8"));
            request.setHeader("Content-Type", "application/json");
            
            // TODO: Add 0x address authentication header
            // request.setHeader("X-Wallet-Address", walletAddress);
            // request.setHeader("X-Signature", signature);
            
            try (CloseableHttpResponse response = httpClient.execute(request)) {
                int statusCode = response.getStatusLine().getStatusCode();
                String responseBody = EntityUtils.toString(response.getEntity());
                
                if (statusCode >= 200 && statusCode < 300) {
                    // Parse response to extract answer
                    try {
                        Map<String, Object> chatResponse = gson.fromJson(responseBody, Map.class);
                        String answer = (String) chatResponse.get("answer");
                        if (answer != null) {
                            return ToolResult.success(
                                "Validator LLM Response:\n" + answer,
                                "validator-llm-chat");
                        }
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
}

