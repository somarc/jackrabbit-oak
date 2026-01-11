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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Response model for chat endpoint.
 * 
 * <p>Includes:
 * <ul>
 *   <li>answer: The LLM-generated response</li>
 *   <li>sources: List of sources used (RAG chunks, API results, etc.)</li>
 *   <li>metadata: Additional info (RAG mode, session info, etc.)</li>
 * </ul>
 */
public class ChatResponse {
    /** LLM-generated answer */
    public String answer;
    
    /** Sources used to generate the answer */
    public List<Source> sources = new ArrayList<>();
    
    /** Confidence score (0.0 to 1.0) */
    public double confidence = 1.0;
    
    /** Additional metadata (ragMode, sessionId, conversationTurns, etc.) */
    public Map<String, Object> metadata = new HashMap<>();
    
    public static class Source {
        public String type;  // "api", "metrics", "log", "rag"
        public String endpoint;
        public String query;
        public Object data;
        
        public Source(String type, String endpoint, Object data) {
            this.type = type;
            this.endpoint = endpoint;
            this.data = data;
        }
        
        public Source(String type, String endpoint, String query, Object data) {
            this.type = type;
            this.endpoint = endpoint;
            this.query = query;
            this.data = data;
        }
    }
}

