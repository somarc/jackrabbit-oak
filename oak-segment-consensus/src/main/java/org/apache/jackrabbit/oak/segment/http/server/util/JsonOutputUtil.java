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

import java.lang.reflect.Array;
import java.util.Iterator;
import java.util.Map;

/**
 * Minimal JSON serializer for HTTP responses.
 *
 * <p>Supports Maps, Iterables, arrays, strings, numbers, booleans and nulls.
 */
public final class JsonOutputUtil {

    private JsonOutputUtil() {
        // Utility class
    }

    public static String toJson(Object value) {
        StringBuilder json = new StringBuilder();
        appendValue(json, value);
        return json.toString();
    }

    @SuppressWarnings("unchecked")
    private static void appendValue(StringBuilder json, Object value) {
        if (value == null) {
            json.append("null");
            return;
        }

        if (value instanceof String) {
            json.append("\"").append(FormatUtils.escapeJson((String) value)).append("\"");
            return;
        }

        if (value instanceof Number || value instanceof Boolean) {
            json.append(value);
            return;
        }

        if (value instanceof Map) {
            json.append("{");
            Iterator<Map.Entry<Object, Object>> it = ((Map<Object, Object>) value).entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Object, Object> entry = it.next();
                json.append("\"")
                    .append(FormatUtils.escapeJson(String.valueOf(entry.getKey())))
                    .append("\":");
                appendValue(json, entry.getValue());
                if (it.hasNext()) {
                    json.append(",");
                }
            }
            json.append("}");
            return;
        }

        if (value instanceof Iterable) {
            json.append("[");
            Iterator<?> it = ((Iterable<?>) value).iterator();
            while (it.hasNext()) {
                appendValue(json, it.next());
                if (it.hasNext()) {
                    json.append(",");
                }
            }
            json.append("]");
            return;
        }

        if (value.getClass().isArray()) {
            json.append("[");
            int length = Array.getLength(value);
            for (int i = 0; i < length; i++) {
                appendValue(json, Array.get(value, i));
                if (i < length - 1) {
                    json.append(",");
                }
            }
            json.append("]");
            return;
        }

        json.append("\"").append(FormatUtils.escapeJson(String.valueOf(value))).append("\"");
    }
}
