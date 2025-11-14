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

/**
 * Result from executing an agentic tool.
 */
public class ToolResult {
    public final String data;
    public final String source;
    public final boolean success;
    public final String error;
    
    public ToolResult(String data, String source, boolean success) {
        this.data = data;
        this.source = source;
        this.success = success;
        this.error = null;
    }
    
    public ToolResult(String error) {
        this.data = null;
        this.source = null;
        this.success = false;
        this.error = error;
    }
    
    public static ToolResult success(String data, String source) {
        return new ToolResult(data, source, true);
    }
    
    public static ToolResult failure(String error) {
        return new ToolResult(error);
    }
}

