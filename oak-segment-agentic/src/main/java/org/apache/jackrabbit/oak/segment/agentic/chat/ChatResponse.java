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
import java.util.List;

/**
 * Response model for chat endpoint.
 */
public class ChatResponse {
    public String answer;
    public List<Source> sources = new ArrayList<>();
    public double confidence = 1.0;
    
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

