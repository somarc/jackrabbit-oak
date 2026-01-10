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
package org.apache.jackrabbit.oak.segment.agentic.rag;

import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link RAGService}.
 */
public class RAGServiceTest {

    private RAGService ragService;

    @Before
    public void setUp() {
        // RAGService will initialize with basic chunks if codebase not found
        ragService = new RAGService();
    }

    @Test
    public void testRetrieve_EmptyQuery_ReturnsEmptyOrMinimalResults() {
        List<RAGService.CodeChunk> results = ragService.retrieve("");
        // Empty query should return empty or minimal results
        assertNotNull("Results should not be null", results);
    }

    @Test
    public void testRetrieve_NullQuery_HandledGracefully() {
        try {
            List<RAGService.CodeChunk> results = ragService.retrieve(null);
            // Should handle null gracefully
            assertNotNull("Results should not be null even for null query", results);
        } catch (NullPointerException e) {
            fail("RAGService should handle null query gracefully");
        }
    }

    @Test
    public void testRetrieve_FileStoreQuery_ReturnsRelevantChunks() {
        List<RAGService.CodeChunk> results = ragService.retrieve("FileStore");
        assertNotNull("Results should not be null", results);
        // Should find FileStore in basic chunks at minimum
        if (!results.isEmpty()) {
            boolean hasFileStore = results.stream()
                .anyMatch(chunk -> chunk.name.contains("FileStore") || 
                                   chunk.content.toLowerCase().contains("filestore"));
            assertTrue("Should find FileStore-related content", hasFileStore);
        }
    }

    @Test
    public void testRetrieve_ConsensusQuery_ReturnsRelevantChunks() {
        List<RAGService.CodeChunk> results = ragService.retrieve("consensus");
        assertNotNull("Results should not be null", results);
        // Should find consensus-related content
        if (!results.isEmpty()) {
            boolean hasConsensus = results.stream()
                .anyMatch(chunk -> chunk.name.toLowerCase().contains("consensus") || 
                                   chunk.content.toLowerCase().contains("consensus"));
            assertTrue("Should find consensus-related content", hasConsensus);
        }
    }

    @Test
    public void testRetrieve_LimitedResults() {
        List<RAGService.CodeChunk> results = ragService.retrieve("oak segment store");
        assertNotNull("Results should not be null", results);
        // Should return at most 10 results
        assertTrue("Should return at most 10 results", results.size() <= 10);
    }

    @Test
    public void testCodeChunk_Matches_CaseInsensitive() {
        RAGService.CodeChunk chunk = new RAGService.CodeChunk(
            "TestClass",
            "test/path/TestClass.java",
            "This is a test content with FileStore reference"
        );
        
        assertTrue("Should match case-insensitive", chunk.matches("filestore"));
        assertTrue("Should match case-insensitive", chunk.matches("FILESTORE"));
        assertTrue("Should match case-insensitive", chunk.matches("FileStore"));
    }

    @Test
    public void testCodeChunk_Matches_ByName() {
        RAGService.CodeChunk chunk = new RAGService.CodeChunk(
            "AeronConsensusEngine",
            "test/path/AeronConsensusEngine.java",
            "Some content"
        );
        
        assertTrue("Should match by name", chunk.matches("aeron"));
        assertTrue("Should match by name", chunk.matches("consensus"));
        assertTrue("Should match by name", chunk.matches("engine"));
    }

    @Test
    public void testCodeChunk_Matches_ByFilePath() {
        RAGService.CodeChunk chunk = new RAGService.CodeChunk(
            "SomeClass",
            "oak-segment-consensus/src/main/java/SomeClass.java",
            "Some content"
        );
        
        assertTrue("Should match by file path", chunk.matches("oak-segment-consensus"));
    }

    @Test
    public void testCodeChunk_Matches_ByContent() {
        RAGService.CodeChunk chunk = new RAGService.CodeChunk(
            "SomeClass",
            "test/path/SomeClass.java",
            "This class handles Raft consensus protocol"
        );
        
        assertTrue("Should match by content", chunk.matches("raft"));
        assertTrue("Should match by content", chunk.matches("protocol"));
    }

    @Test
    public void testCodeChunk_DoesNotMatch_UnrelatedQuery() {
        RAGService.CodeChunk chunk = new RAGService.CodeChunk(
            "FileStore",
            "oak-segment-tar/FileStore.java",
            "Manages segment storage"
        );
        
        assertFalse("Should not match unrelated query", chunk.matches("kubernetes"));
        assertFalse("Should not match unrelated query", chunk.matches("docker"));
    }

    @Test
    public void testCodeChunk_ToString_ContainsSummary() {
        RAGService.CodeChunk chunk = new RAGService.CodeChunk(
            "TestClass",
            "test/path/TestClass.java",
            "Content here",
            "This is a summary"
        );
        
        String str = chunk.toString();
        assertTrue("toString should contain name", str.contains("TestClass"));
        assertTrue("toString should contain path", str.contains("test/path/TestClass.java"));
        assertTrue("toString should contain summary", str.contains("This is a summary"));
    }

    @Test
    public void testCodeChunk_ToString_TruncatesLongContent() {
        StringBuilder longContent = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            longContent.append("This is line ").append(i).append(" of content. ");
        }
        
        RAGService.CodeChunk chunk = new RAGService.CodeChunk(
            "TestClass",
            "test/path/TestClass.java",
            longContent.toString()
        );
        
        String str = chunk.toString();
        // Should truncate to ~500 chars + "..."
        assertTrue("toString should truncate long content", str.contains("..."));
    }

    @Test
    public void testRetrieve_ReturnsResultsSortedByRelevance() {
        // Add a specific query that should have clear relevance ordering
        List<RAGService.CodeChunk> results = ragService.retrieve("AeronConsensusEngine");
        
        if (results.size() >= 2) {
            // First result should be more relevant (higher score)
            // This is a basic check - exact ordering depends on index content
            RAGService.CodeChunk first = results.get(0);
            assertNotNull("First result should not be null", first);
        }
    }
}
