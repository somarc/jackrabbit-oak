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

import java.util.Map;

/**
 * Interface for agentic tools that can be called by the LLM.
 */
public interface AgenticTool {
    /**
     * Get the name of this tool.
     */
    String getName();
    
    /**
     * Get a description of what this tool does.
     */
    String getDescription();
    
    /**
     * Execute the tool with the given query and context.
     * 
     * @param query User's query (may contain instructions for the tool)
     * @param context Additional context (e.g., request context)
     * @return Result of tool execution
     */
    ToolResult execute(String query, Map<String, Object> context);
    
    /**
     * Check if this tool should be used for the given query.
     * 
     * @param query User's query
     * @return true if this tool is relevant
     */
    boolean shouldUse(String query);
}

