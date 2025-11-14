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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Basic RAG service for code retrieval.
 * 
 * <p>This is a simple implementation that uses keyword matching.
 * Future versions can use vector embeddings for semantic search.
 */
public class RAGService {
    private static final Logger log = LoggerFactory.getLogger(RAGService.class);
    
    private final List<CodeChunk> codeChunks = new ArrayList<>();
    
    public RAGService() {
        // For MVP, we'll use a simple keyword-based approach
        // Future: Load code chunks from indexed codebase
        initializeBasicChunks();
    }
    
    private void initializeBasicChunks() {
        // Add some basic Oak knowledge for MVP
        codeChunks.add(new CodeChunk(
            "FileStore",
            "oak-segment-tar/src/main/java/org/apache/jackrabbit/oak/segment/file/FileStore.java",
            "FileStore manages the segment store. It handles segment creation, cleanup, and GC."
        ));
        
        codeChunks.add(new CodeChunk(
            "SegmentNodeStore",
            "oak-segment-tar/src/main/java/org/apache/jackrabbit/oak/segment/SegmentNodeStore.java",
            "SegmentNodeStore is the main NodeStore implementation for Oak segment store."
        ));
        
        codeChunks.add(new CodeChunk(
            "AeronConsensusEngine",
            "oak-segment-consensus/src/main/java/org/apache/jackrabbit/oak/segment/consensus/aeron/AeronConsensusEngine.java",
            "AeronConsensusEngine implements Raft-based consensus using Aeron Cluster."
        ));
    }
    
    /**
     * Retrieve relevant code chunks for a query.
     * 
     * @param query User's question
     * @return List of relevant code chunks
     */
    public List<CodeChunk> retrieve(String query) {
        String lower = query.toLowerCase();
        List<CodeChunk> results = new ArrayList<>();
        
        for (CodeChunk chunk : codeChunks) {
            if (chunk.matches(lower)) {
                results.add(chunk);
            }
        }
        
        // Return top 5 matches
        return results.subList(0, Math.min(5, results.size()));
    }
    
    /**
     * Represents a chunk of code/documentation.
     */
    public static class CodeChunk {
        public final String name;
        public final String filePath;
        public final String content;
        
        public CodeChunk(String name, String filePath, String content) {
            this.name = name;
            this.filePath = filePath;
            this.content = content;
        }
        
        public boolean matches(String query) {
            String lower = query.toLowerCase();
            return name.toLowerCase().contains(lower) ||
                   filePath.toLowerCase().contains(lower) ||
                   content.toLowerCase().contains(lower);
        }
        
        @Override
        public String toString() {
            return String.format("%s (%s): %s", name, filePath, content);
        }
    }
}

