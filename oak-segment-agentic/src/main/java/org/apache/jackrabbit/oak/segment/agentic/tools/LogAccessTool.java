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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Tool for accessing and analyzing log files.
 * 
 * <p>Can read log files from:
 * - Logback file appenders (if available)
 * - Common log locations
 * - System property configured paths
 */
public class LogAccessTool implements AgenticTool {
    private static final Logger log = LoggerFactory.getLogger(LogAccessTool.class);
    
    private final List<String> logFilePaths = new ArrayList<>();
    
    public LogAccessTool() {
        discoverLogFiles();
    }
    
    /**
     * Discover available log files.
     */
    private void discoverLogFiles() {
        // Try to find log files from Logback appenders (if available)
        try {
            // Use reflection to avoid hard dependency on logback
            Object loggerFactory = LoggerFactory.getILoggerFactory();
            if (loggerFactory != null && loggerFactory.getClass().getName().contains("logback")) {
                java.lang.reflect.Method getLoggerListMethod = loggerFactory.getClass().getMethod("getLoggerList");
                @SuppressWarnings("unchecked")
                List<?> loggers = (List<?>) getLoggerListMethod.invoke(loggerFactory);
                
                for (Object logger : loggers) {
                    java.lang.reflect.Method iteratorForAppendersMethod = logger.getClass().getMethod("iteratorForAppenders");
                    @SuppressWarnings("unchecked")
                    Enumeration<?> appenders = (Enumeration<?>) iteratorForAppendersMethod.invoke(logger);
                    
                    while (appenders.hasMoreElements()) {
                        Object appender = appenders.nextElement();
                        if (appender.getClass().getName().contains("FileAppender")) {
                            java.lang.reflect.Method getFileMethod = appender.getClass().getMethod("getFile");
                            String filePath = (String) getFileMethod.invoke(appender);
                            if (filePath != null && !logFilePaths.contains(filePath)) {
                                logFilePaths.add(filePath);
                                log.debug("Found log file from appender: {}", filePath);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Could not access Logback appenders: {}", e.getMessage());
        }
        
        // Add common log locations
        String[] commonPaths = {
            System.getProperty("log.file.path"),
            System.getProperty("user.dir") + "/logs/validator.log",
            System.getProperty("user.dir") + "/logs/application.log",
            System.getProperty("user.dir") + "/validator.log",
            System.getProperty("user.dir") + "/application.log",
            "/var/log/oak-validator.log",
            "/var/log/validator.log"
        };
        
        for (String path : commonPaths) {
            if (path != null) {
                File file = new File(path);
                if (file.exists() && file.isFile() && file.canRead()) {
                    if (!logFilePaths.contains(path)) {
                        logFilePaths.add(path);
                        log.debug("Found log file: {}", path);
                    }
                }
            }
        }
        
        if (logFilePaths.isEmpty()) {
            log.warn("⚠️  No log files discovered. Log access may be limited.");
        } else {
            log.info("📋 Discovered {} log file(s)", logFilePaths.size());
        }
    }
    
    @Override
    public String getName() {
        return "log-access";
    }
    
    @Override
    public String getDescription() {
        return "Read and analyze log files. Can filter by log level, search terms, time range, and show recent entries.";
    }
    
    @Override
    public boolean shouldUse(String query) {
        String lower = query.toLowerCase();
        return lower.contains("log") || lower.contains("error") || 
               lower.contains("warn") || lower.contains("exception") ||
               lower.contains("trace") || lower.contains("debug") ||
               lower.contains("recent") && (lower.contains("log") || lower.contains("entry"));
    }
    
    @Override
    public ToolResult execute(String query, Map<String, Object> context) {
        try {
            if (logFilePaths.isEmpty()) {
                return ToolResult.failure("No log files found. Configure log file path with -Dlog.file.path=<path>");
            }
            
            // Parse query for filters
            String lower = query.toLowerCase();
            String logLevel = extractLogLevel(lower);
            String searchTerm = extractSearchTerm(query, lower);
            int lineLimit = extractLineLimit(lower);
            boolean tailOnly = lower.contains("tail") || lower.contains("recent") || lower.contains("last");
            
            StringBuilder result = new StringBuilder();
            result.append("Log Analysis Results:\n");
            result.append("=".repeat(50)).append("\n\n");
            
            int totalLines = 0;
            for (String logPath : logFilePaths) {
                try {
                    List<String> lines = readLogFile(logPath, logLevel, searchTerm, lineLimit, tailOnly);
                    if (!lines.isEmpty()) {
                        result.append("File: ").append(logPath).append("\n");
                        result.append("-".repeat(50)).append("\n");
                        for (String line : lines) {
                            result.append(line).append("\n");
                        }
                        result.append("\n");
                        totalLines += lines.size();
                    }
                } catch (Exception e) {
                    log.warn("Error reading log file {}: {}", logPath, e.getMessage());
                    result.append("Error reading ").append(logPath).append(": ").append(e.getMessage()).append("\n\n");
                }
            }
            
            if (totalLines == 0) {
                return ToolResult.success("No matching log entries found.", "log-access");
            }
            
            result.append("Total matching entries: ").append(totalLines).append("\n");
            
            return ToolResult.success(result.toString(), "log-access");
            
        } catch (Exception e) {
            log.error("Error accessing logs", e);
            return ToolResult.failure("Error accessing logs: " + e.getMessage());
        }
    }
    
    /**
     * Extract log level from query (ERROR, WARN, INFO, DEBUG, TRACE).
     */
    private String extractLogLevel(String lowerQuery) {
        if (lowerQuery.contains("error") || lowerQuery.contains("exception")) {
            return "ERROR";
        }
        if (lowerQuery.contains("warn")) {
            return "WARN";
        }
        if (lowerQuery.contains("debug")) {
            return "DEBUG";
        }
        if (lowerQuery.contains("trace")) {
            return "TRACE";
        }
        if (lowerQuery.contains("info")) {
            return "INFO";
        }
        return null; // All levels
    }
    
    /**
     * Extract search term from query.
     */
    private String extractSearchTerm(String originalQuery, String lowerQuery) {
        // Look for patterns like "containing X", "with X", "about X"
        String[] patterns = {
            "containing (.+)",
            "with (.+)",
            "about (.+)",
            "for (.+)",
            "showing (.+)",
            "matching (.+)"
        };
        
        for (String pattern : patterns) {
            Pattern p = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
            java.util.regex.Matcher m = p.matcher(originalQuery);
            if (m.find() && m.groupCount() > 0) {
                String term = m.group(1).trim();
                // Remove trailing punctuation
                term = term.replaceAll("[.,;:!?]+$", "");
                if (term.length() > 2) {
                    return term;
                }
            }
        }
        
        return null;
    }
    
    /**
     * Extract line limit from query (e.g., "last 50 lines").
     */
    private int extractLineLimit(String lowerQuery) {
        Pattern p = Pattern.compile("(last|recent|tail|show)\\s+(\\d+)\\s*(lines|entries)?", Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher m = p.matcher(lowerQuery);
        if (m.find() && m.groupCount() >= 2) {
            try {
                return Integer.parseInt(m.group(2));
            } catch (NumberFormatException e) {
                // Ignore
            }
        }
        
        // Default limits
        if (lowerQuery.contains("tail") || lowerQuery.contains("recent") || lowerQuery.contains("last")) {
            return 50; // Default tail size
        }
        
        return 200; // Default limit for full search
    }
    
    /**
     * Read log file with filters.
     */
    private List<String> readLogFile(String filePath, String logLevel, String searchTerm, 
                                     int lineLimit, boolean tailOnly) throws IOException {
        File file = new File(filePath);
        if (!file.exists() || !file.canRead()) {
            return Collections.emptyList();
        }
        
        List<String> allLines = new ArrayList<>();
        List<String> matchingLines = new ArrayList<>();
        
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                allLines.add(line);
                
                // Apply filters
                boolean matches = true;
                
                if (logLevel != null) {
                    if (!line.contains(logLevel)) {
                        matches = false;
                    }
                }
                
                if (searchTerm != null && matches) {
                    if (!line.toLowerCase().contains(searchTerm.toLowerCase())) {
                        matches = false;
                    }
                }
                
                if (matches) {
                    matchingLines.add(line);
                }
            }
        }
        
        // Apply tail if requested
        if (tailOnly && !matchingLines.isEmpty()) {
            int start = Math.max(0, matchingLines.size() - lineLimit);
            matchingLines = matchingLines.subList(start, matchingLines.size());
        } else if (matchingLines.size() > lineLimit) {
            // Limit results
            matchingLines = matchingLines.subList(0, lineLimit);
        }
        
        return matchingLines;
    }
}

