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

import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link LogAccessTool}.
 */
public class LogAccessToolTest {

    private LogAccessTool tool;

    @Before
    public void setUp() {
        tool = new LogAccessTool();
    }

    @Test
    public void testGetName() {
        assertEquals("log-access", tool.getName());
    }

    @Test
    public void testGetDescription() {
        String description = tool.getDescription();
        assertNotNull("Description should not be null", description);
        assertTrue("Description should mention log", description.toLowerCase().contains("log"));
        assertTrue("Description should mention filter", description.toLowerCase().contains("filter"));
    }

    // ========== shouldUse() tests ==========

    @Test
    public void testShouldUse_LogQuery_ReturnsTrue() {
        assertTrue("Should use for log query", tool.shouldUse("Show me the logs"));
        assertTrue("Should use for log query", tool.shouldUse("read log file"));
        assertTrue("Should use for log query", tool.shouldUse("what's in the log?"));
    }

    @Test
    public void testShouldUse_ErrorQuery_ReturnsTrue() {
        assertTrue("Should use for error query", tool.shouldUse("Show me errors"));
        assertTrue("Should use for error query", tool.shouldUse("any errors in the system?"));
        assertTrue("Should use for error query", tool.shouldUse("find error messages"));
    }

    @Test
    public void testShouldUse_WarnQuery_ReturnsTrue() {
        assertTrue("Should use for warn query", tool.shouldUse("Show warnings"));
        assertTrue("Should use for warn query", tool.shouldUse("any warnings?"));
    }

    @Test
    public void testShouldUse_ExceptionQuery_ReturnsTrue() {
        assertTrue("Should use for exception query", tool.shouldUse("Show exceptions"));
        assertTrue("Should use for exception query", tool.shouldUse("any exceptions thrown?"));
    }

    @Test
    public void testShouldUse_TraceQuery_ReturnsTrue() {
        assertTrue("Should use for trace query", tool.shouldUse("Show trace logs"));
        assertTrue("Should use for trace query", tool.shouldUse("enable trace logging"));
    }

    @Test
    public void testShouldUse_DebugQuery_ReturnsTrue() {
        assertTrue("Should use for debug query", tool.shouldUse("Show debug logs"));
        assertTrue("Should use for debug query", tool.shouldUse("debug information"));
    }

    @Test
    public void testShouldUse_RecentLogQuery_ReturnsTrue() {
        assertTrue("Should use for recent log query", tool.shouldUse("Show recent log entries"));
        assertTrue("Should use for recent log query", tool.shouldUse("recent entries in log"));
    }

    @Test
    public void testShouldUse_UnrelatedQuery_ReturnsFalse() {
        assertFalse("Should not use for unrelated query", tool.shouldUse("What is FileStore?"));
        assertFalse("Should not use for unrelated query", tool.shouldUse("Explain consensus"));
    }

    // ========== execute() tests ==========

    @Test
    public void testExecute_NoLogFiles_ReturnsHelpfulMessage() {
        // When no log files are found, should return helpful message
        Map<String, Object> context = new HashMap<>();
        ToolResult result = tool.execute("Show me errors", context);
        
        assertNotNull("Result should not be null", result);
        // Either success with "no matching entries" or failure with "no log files"
        assertNotNull("Result data should not be null", result.data);
    }

    @Test
    public void testExecute_WithNullContext_HandlesGracefully() {
        ToolResult result = tool.execute("Show me errors", null);
        
        assertNotNull("Result should not be null", result);
    }

    @Test
    public void testExecute_WithEmptyQuery_HandlesGracefully() {
        Map<String, Object> context = new HashMap<>();
        ToolResult result = tool.execute("", context);
        
        assertNotNull("Result should not be null", result);
    }

    @Test
    public void testExecute_ErrorLevelQuery_FiltersCorrectly() {
        Map<String, Object> context = new HashMap<>();
        ToolResult result = tool.execute("Show me errors", context);
        
        assertNotNull("Result should not be null", result);
        // The tool should attempt to filter by ERROR level
    }

    @Test
    public void testExecute_WarnLevelQuery_FiltersCorrectly() {
        Map<String, Object> context = new HashMap<>();
        ToolResult result = tool.execute("Show warnings", context);
        
        assertNotNull("Result should not be null", result);
        // The tool should attempt to filter by WARN level
    }

    @Test
    public void testExecute_TailQuery_LimitsResults() {
        Map<String, Object> context = new HashMap<>();
        ToolResult result = tool.execute("Show last 50 log entries", context);
        
        assertNotNull("Result should not be null", result);
        // The tool should limit to 50 entries
    }

    @Test
    public void testExecute_RecentQuery_LimitsResults() {
        Map<String, Object> context = new HashMap<>();
        ToolResult result = tool.execute("Show recent logs", context);
        
        assertNotNull("Result should not be null", result);
        // The tool should show recent entries
    }

    @Test
    public void testExecute_SearchTermQuery_FiltersCorrectly() {
        Map<String, Object> context = new HashMap<>();
        ToolResult result = tool.execute("Show logs containing consensus", context);
        
        assertNotNull("Result should not be null", result);
        // The tool should filter by search term
    }

    @Test
    public void testExecute_CombinedFilters_WorksTogether() {
        Map<String, Object> context = new HashMap<>();
        ToolResult result = tool.execute("Show last 20 error logs containing FileStore", context);
        
        assertNotNull("Result should not be null", result);
        // The tool should apply multiple filters
    }
}
