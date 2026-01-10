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
 * Unit tests for {@link ValidatorApiTool}.
 */
public class ValidatorApiToolTest {

    private ValidatorApiTool tool;

    @Before
    public void setUp() {
        // Use a non-existent URL to avoid actual network calls in tests
        tool = new ValidatorApiTool("http://localhost:99999");
    }

    @Test
    public void testGetName() {
        assertEquals("validator-api", tool.getName());
    }

    @Test
    public void testGetDescription() {
        String description = tool.getDescription();
        assertNotNull("Description should not be null", description);
        assertTrue("Description should mention cluster", description.contains("cluster"));
        assertTrue("Description should mention consensus", description.contains("consensus"));
    }

    // ========== shouldUse() tests ==========

    @Test
    public void testShouldUse_LeaderQuery_ReturnsTrue() {
        assertTrue("Should use for leader query", tool.shouldUse("What is the current leader?"));
        assertTrue("Should use for leader query", tool.shouldUse("who is the leader"));
        assertTrue("Should use for leader query", tool.shouldUse("show me the leader"));
    }

    @Test
    public void testShouldUse_ClusterQuery_ReturnsTrue() {
        assertTrue("Should use for cluster query", tool.shouldUse("What is the cluster state?"));
        assertTrue("Should use for cluster query", tool.shouldUse("show cluster status"));
    }

    @Test
    public void testShouldUse_ConsensusQuery_ReturnsTrue() {
        assertTrue("Should use for consensus query", tool.shouldUse("What is the consensus status?"));
        assertTrue("Should use for consensus query", tool.shouldUse("show consensus state"));
    }

    @Test
    public void testShouldUse_PeerQuery_ReturnsTrue() {
        assertTrue("Should use for peer query", tool.shouldUse("List all peers"));
        assertTrue("Should use for peer query", tool.shouldUse("show me the peers"));
    }

    @Test
    public void testShouldUse_HealthQuery_ReturnsTrue() {
        assertTrue("Should use for health query", tool.shouldUse("Check health status"));
        assertTrue("Should use for health query", tool.shouldUse("is the validator healthy?"));
    }

    @Test
    public void testShouldUse_MetricsQuery_ReturnsTrue() {
        assertTrue("Should use for metrics query", tool.shouldUse("Show me the metrics"));
        assertTrue("Should use for metrics query", tool.shouldUse("what are the current metrics?"));
    }

    @Test
    public void testShouldUse_AeronQuery_ReturnsTrue() {
        assertTrue("Should use for aeron query", tool.shouldUse("What is the aeron state?"));
        assertTrue("Should use for aeron query", tool.shouldUse("show aeron cluster info"));
    }

    @Test
    public void testShouldUse_RaftQuery_ReturnsTrue() {
        assertTrue("Should use for raft query", tool.shouldUse("Show raft metrics"));
        assertTrue("Should use for raft query", tool.shouldUse("what is the raft state?"));
    }

    @Test
    public void testShouldUse_ExploreQuery_ReturnsTrue() {
        assertTrue("Should use for explore query", tool.shouldUse("Explore the node tree"));
        assertTrue("Should use for explore query", tool.shouldUse("browse path /oak-chain"));
    }

    @Test
    public void testShouldUse_SegmentQuery_ReturnsTrue() {
        assertTrue("Should use for segment query", tool.shouldUse("Show recent segments"));
        assertTrue("Should use for segment query", tool.shouldUse("list segment files"));
    }

    @Test
    public void testShouldUse_JournalQuery_ReturnsTrue() {
        assertTrue("Should use for journal query", tool.shouldUse("Show the journal"));
        assertTrue("Should use for journal query", tool.shouldUse("read journal log"));
    }

    @Test
    public void testShouldUse_ManifestQuery_ReturnsTrue() {
        assertTrue("Should use for manifest query", tool.shouldUse("Show the manifest"));
        assertTrue("Should use for manifest query", tool.shouldUse("read manifest file"));
    }

    @Test
    public void testShouldUse_TarQuery_ReturnsTrue() {
        assertTrue("Should use for tar query", tool.shouldUse("List all tar files"));
        assertTrue("Should use for tar query", tool.shouldUse("show tar segments"));
    }

    @Test
    public void testShouldUse_HeadQuery_ReturnsTrue() {
        assertTrue("Should use for head query", tool.shouldUse("What is the current head?"));
        assertTrue("Should use for head query", tool.shouldUse("show head record"));
    }

    @Test
    public void testShouldUse_NodeStatusQuery_ReturnsTrue() {
        assertTrue("Should use for node status query", tool.shouldUse("What is the status of node 0?"));
        assertTrue("Should use for node status query", tool.shouldUse("show node status"));
    }

    @Test
    public void testShouldUse_RecentQuery_ReturnsTrue() {
        assertTrue("Should use for recent query", tool.shouldUse("Show recent writes"));
        assertTrue("Should use for recent query", tool.shouldUse("what are the recent segments?"));
    }

    @Test
    public void testShouldUse_DataQuery_ReturnsTrue() {
        // Queries that ask for data should trigger the tool
        assertTrue("Should use for 'what' query", tool.shouldUse("What is happening?"));
        assertTrue("Should use for 'show' query", tool.shouldUse("Show me something"));
        assertTrue("Should use for 'get' query", tool.shouldUse("Get the current state"));
        assertTrue("Should use for 'current' query", tool.shouldUse("Current status please"));
    }

    @Test
    public void testShouldUse_UnrelatedQuery_ReturnsFalse() {
        assertFalse("Should not use for unrelated query", tool.shouldUse("How do I write Java code?"));
        assertFalse("Should not use for unrelated query", tool.shouldUse("Explain object-oriented programming"));
    }

    // ========== execute() tests ==========

    @Test
    public void testExecute_ReturnsFailureWhenValidatorUnreachable() {
        // With an invalid URL, execute should return a failure
        Map<String, Object> context = new HashMap<>();
        ToolResult result = tool.execute("What is the leader?", context);
        
        assertNotNull("Result should not be null", result);
        assertFalse("Result should indicate failure", result.success);
        assertTrue("Result should contain error message", result.data.contains("Error"));
    }

    @Test
    public void testExecute_WithNullContext_HandlesGracefully() {
        ToolResult result = tool.execute("What is the leader?", null);
        
        assertNotNull("Result should not be null", result);
        // Should handle null context gracefully
    }

    @Test
    public void testExecute_WithEmptyQuery_StillAttempts() {
        Map<String, Object> context = new HashMap<>();
        ToolResult result = tool.execute("", context);
        
        assertNotNull("Result should not be null", result);
        // Empty query should still attempt to call default endpoint
    }

    // ========== ToolResult tests ==========

    @Test
    public void testToolResult_Success() {
        ToolResult result = ToolResult.success("test data", "test-source");
        
        assertTrue("Success result should have success=true", result.success);
        assertEquals("Data should match", "test data", result.data);
        assertEquals("Source should match", "test-source", result.source);
    }

    @Test
    public void testToolResult_Failure() {
        ToolResult result = ToolResult.failure("error message");
        
        assertFalse("Failure result should have success=false", result.success);
        assertEquals("Data should contain error", "error message", result.data);
    }
}
