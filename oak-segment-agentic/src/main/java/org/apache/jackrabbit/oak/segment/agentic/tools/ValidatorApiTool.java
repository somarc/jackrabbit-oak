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
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Tool for querying validator APIs.
 */
public class ValidatorApiTool implements AgenticTool {
    private static final Logger log = LoggerFactory.getLogger(ValidatorApiTool.class);
    
    private final OkHttpClient httpClient;
    private final String baseUrl;
    private final Gson gson = new Gson();
    
    public ValidatorApiTool(String baseUrl) {
        this.baseUrl = baseUrl;
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();
    }
    
    @Override
    public String getName() {
        return "validator-api";
    }
    
    @Override
    public String getDescription() {
        return "Query validator APIs for cluster state, consensus status, and peer information";
    }
    
    @Override
    public boolean shouldUse(String query) {
        String lower = query.toLowerCase();
        return lower.contains("leader") || lower.contains("cluster") || 
               lower.contains("consensus") || lower.contains("peer") ||
               lower.contains("validator") || lower.contains("status");
    }
    
    @Override
    public ToolResult execute(String query, Map<String, Object> context) {
        try {
            // Try multiple endpoints based on query
            String lower = query.toLowerCase();
            
            if (lower.contains("leader") || lower.contains("cluster")) {
                return queryEndpoint("/v1/aeron/cluster-state", "cluster-state");
            }
            
            if (lower.contains("consensus") || lower.contains("status")) {
                return queryEndpoint("/v1/consensus/status", "consensus-status");
            }
            
            if (lower.contains("peer")) {
                return queryEndpoint("/v1/peers", "peers");
            }
            
            // Default: try cluster state
            return queryEndpoint("/v1/aeron/cluster-state", "cluster-state");
        } catch (Exception e) {
            log.error("Error querying validator API", e);
            return ToolResult.failure("Error querying validator API: " + e.getMessage());
        }
    }
    
    private ToolResult queryEndpoint(String endpoint, String sourceName) throws IOException {
        String url = baseUrl + endpoint;
        Request request = new Request.Builder()
            .url(url)
            .get()
            .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                return ToolResult.failure("API returned HTTP " + response.code());
            }
            
            String body = response.body().string();
            return ToolResult.success(body, sourceName);
        }
    }
}

