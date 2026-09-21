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
package org.apache.jackrabbit.oak.segment.http.server.util;

/**
 * Simple JSON parsing utilities.
 * 
 * <p>Extracted from SegmentHttpServer for better organization.
 * Note: This is a simple parser for Phase 1. Consider using Jackson or Gson in the future.</p>
 */
public class JsonParser {
    
    /**
     * Extract a JSON field value from a JSON string.
     * 
     * @param json The JSON string
     * @param field The field name to extract
     * @return The field value, or null if not found
     */
    public static String extractField(String json, String field) {
        int start = json.indexOf("\"" + field + "\"");
        if (start == -1) return null;
        
        start = json.indexOf(":", start) + 1;
        
        // Skip whitespace
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) {
            start++;
        }
        
        // Check if value is quoted (string) or unquoted (number/boolean/null)
        if (start >= json.length()) return null;
        
        if (json.charAt(start) == '"') {
            // Quoted string
            start++; // Skip opening quote
            int end = json.indexOf("\"", start);
            if (end == -1) return null;
            return json.substring(start, end);
        } else {
            // Unquoted value (number, boolean, or null) - read until comma, }, or ]
            int end = start;
            while (end < json.length()) {
                char c = json.charAt(end);
                if (c == ',' || c == '}' || c == ']' || Character.isWhitespace(c)) {
                    break;
                }
                end++;
            }
            return json.substring(start, end).trim();
        }
    }
    
    /**
     * Extract a JSON object (not a primitive) from a JSON string.
     * Used to extract nested objects like the proof and handover.
     * 
     * @param json The JSON string
     * @param field The field name containing the object
     * @return The JSON object as a string, or null if not found
     */
    public static String extractObject(String json, String field) {
        String pattern = "\"" + field + "\":";
        int start = json.indexOf(pattern);
        if (start == -1) return null;
        
        start = json.indexOf("{", start);
        if (start == -1) return null;
        
        // Find matching closing brace
        int depth = 0;
        int end = start;
        while (end < json.length()) {
            char c = json.charAt(end);
            if (c == '{') depth++;
            if (c == '}') {
                depth--;
                if (depth == 0) {
                    return json.substring(start, end + 1);
                }
            }
            end++;
        }
        
        return null;
    }
}

