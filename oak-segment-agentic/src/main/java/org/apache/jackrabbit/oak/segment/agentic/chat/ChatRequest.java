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

import java.util.HashMap;
import java.util.Map;

/**
 * Request model for chat endpoint.
 * 
 * <p>Supports:
 * <ul>
 *   <li>query: The user's question (preferred)</li>
 *   <li>message: Alias for query (backward compatibility)</li>
 *   <li>sessionId: Optional session ID for conversation memory</li>
 *   <li>context: Additional context (agentToAgent, includeMetrics, etc.)</li>
 * </ul>
 */
public class ChatRequest {
    /** User's question (preferred field name) */
    public String query;
    
    /** Alias for query (backward compatibility with older frontends) */
    public String message;
    
    /** Agent mode flag (backward compatibility - use context.agentToAgent instead) */
    public Boolean agentMode;
    
    /** Optional session ID for conversation memory */
    public String sessionId;
    
    /** Additional context flags and data */
    public Map<String, Object> context = new HashMap<>();
    
    public ChatRequest() {
        // Default constructor for JSON deserialization
    }
    
    public ChatRequest(String query) {
        this.query = query;
    }
    
    public ChatRequest(String query, String sessionId) {
        this.query = query;
        this.sessionId = sessionId;
    }
    
    /**
     * Get the effective query, checking both 'query' and 'message' fields.
     * @return the query string, or null if neither is set
     */
    public String getEffectiveQuery() {
        if (query != null && !query.trim().isEmpty()) {
            return query;
        }
        if (message != null && !message.trim().isEmpty()) {
            return message;
        }
        return null;
    }
}

