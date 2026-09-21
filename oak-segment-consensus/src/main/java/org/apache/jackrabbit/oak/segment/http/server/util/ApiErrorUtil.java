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

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * Standard JSON error response utility for API endpoints.
 */
public final class ApiErrorUtil {

    private ApiErrorUtil() {
    }

    public static void sendJsonError(HttpServletResponse response, int statusCode, String message) throws IOException {
        sendJsonError(response, statusCode, defaultCode(statusCode), message);
    }

    public static void sendJsonError(HttpServletResponse response, int statusCode, String code, String message) throws IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setStatus(statusCode);

        String safeMessage = escapeJson(message);
        String safeCode = escapeJson(code != null ? code : defaultCode(statusCode));

        StringBuilder json = new StringBuilder();
        json.append("{");
        json.append("\"success\":false,");
        json.append("\"error\":\"").append(safeMessage).append("\",");
        json.append("\"code\":\"").append(safeCode).append("\",");
        json.append("\"status\":").append(statusCode).append(",");
        json.append("\"timestamp\":").append(System.currentTimeMillis());
        json.append("}");

        try (PrintWriter writer = response.getWriter()) {
            writer.write(json.toString());
        }
    }

    private static String defaultCode(int statusCode) {
        switch (statusCode) {
            case HttpServletResponse.SC_BAD_REQUEST:
                return "bad_request";
            case HttpServletResponse.SC_UNAUTHORIZED:
                return "unauthorized";
            case HttpServletResponse.SC_FORBIDDEN:
                return "forbidden";
            case HttpServletResponse.SC_NOT_FOUND:
                return "not_found";
            case HttpServletResponse.SC_CONFLICT:
                return "conflict";
            case HttpServletResponse.SC_GONE:
                return "gone";
            case HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE:
                return "unsupported_media_type";
            case HttpServletResponse.SC_SERVICE_UNAVAILABLE:
                return "service_unavailable";
            case HttpServletResponse.SC_INTERNAL_SERVER_ERROR:
                return "internal_error";
            default:
                return "error";
        }
    }

    private static String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }
}
