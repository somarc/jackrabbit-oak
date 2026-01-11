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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Hybrid RAG service combining vector and keyword retrieval.
 * 
 * <p>Uses Reciprocal Rank Fusion (RRF) to combine results from:
 * <ul>
 *   <li>Vector retrieval (semantic similarity via embeddings)</li>
 *   <li>Keyword retrieval (TF-IDF based)</li>
 * </ul>
 * 
 * <p>This hybrid approach provides better results than either method alone:
 * <ul>
 *   <li>Vector: Good at semantic similarity ("how does X work?")</li>
 *   <li>Keyword: Good at exact matches ("AeronConsensusEngine")</li>
 * </ul>
 * 
 * @since 1.89
 */
public class HybridRAGService {
    private static final Logger log = LoggerFactory.getLogger(HybridRAGService.class);
    
    /** RRF constant - higher values give more weight to top results */
    private static final double RRF_K = 60.0;
    
    /** Weight for vector retrieval (0.0 to 1.0) */
    private static final double VECTOR_WEIGHT = 0.55;
    
    /** Weight for keyword retrieval (0.0 to 1.0) */
    private static final double KEYWORD_WEIGHT = 0.45;
    
    private final RAGService keywordService;
    private final VectorRAGService vectorService;
    private volatile boolean initialized = false;
    
    public HybridRAGService(RAGService keywordService, VectorRAGService vectorService) {
        this.keywordService = keywordService;
        this.vectorService = vectorService;
    }
    
    /**
     * Initialize hybrid RAG by indexing embeddings.
     * Should be called after keyword service has indexed chunks.
     */
    public void initialize() {
        if (initialized) {
            return;
        }
        
        if (vectorService.isAvailable()) {
            // Get chunks from keyword service and index them for vector search
            List<RAGService.CodeChunk> allChunks = keywordService.getAllChunks();
            if (!allChunks.isEmpty()) {
                log.info("🔄 Initializing hybrid RAG with {} chunks...", allChunks.size());
                vectorService.indexChunks(allChunks);
                initialized = true;
                log.info("✅ Hybrid RAG initialized: vector={}, keyword={}", 
                    vectorService.isIndexed(), true);
            }
        } else {
            log.info("📝 Vector RAG not available, using keyword-only mode");
            initialized = true;
        }
    }
    
    /**
     * Retrieve relevant code chunks using hybrid search.
     * 
     * @param query User's question
     * @param topK Number of results to return
     * @return List of relevant code chunks, ranked by combined score
     */
    public List<RAGService.CodeChunk> retrieve(String query, int topK) {
        if (!initialized) {
            initialize();
        }
        
        // Get results from both retrievers
        List<RAGService.CodeChunk> keywordResults = keywordService.retrieve(query);
        List<RAGService.CodeChunk> vectorResults = vectorService.isAvailable() && vectorService.isIndexed()
            ? vectorService.retrieveBySimilarity(query, topK * 2)
            : new ArrayList<>();
        
        // If vector not available, fall back to keyword only
        if (vectorResults.isEmpty()) {
            log.debug("Using keyword-only retrieval (vector unavailable)");
            return keywordResults.stream().limit(topK).collect(Collectors.toList());
        }
        
        // Reciprocal Rank Fusion
        Map<String, Double> scores = new HashMap<>();
        Map<String, RAGService.CodeChunk> chunkMap = new HashMap<>();
        
        // Add vector results with RRF scores
        addRRFScores(scores, chunkMap, vectorResults, VECTOR_WEIGHT);
        
        // Add keyword results with RRF scores
        addRRFScores(scores, chunkMap, keywordResults, KEYWORD_WEIGHT);
        
        // Sort by combined score and return top-K
        List<RAGService.CodeChunk> results = scores.entrySet().stream()
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
            .limit(topK)
            .map(e -> chunkMap.get(e.getKey()))
            .collect(Collectors.toList());
        
        log.debug("Hybrid retrieval: {} vector + {} keyword → {} combined results",
            vectorResults.size(), keywordResults.size(), results.size());
        
        return results;
    }
    
    /**
     * Retrieve with detailed scores for debugging.
     */
    public List<ScoredResult> retrieveWithScores(String query, int topK) {
        if (!initialized) {
            initialize();
        }
        
        List<RAGService.CodeChunk> keywordResults = keywordService.retrieve(query);
        List<VectorRAGService.ScoredChunk> vectorResults = vectorService.isAvailable() && vectorService.isIndexed()
            ? vectorService.retrieveWithScores(query, topK * 2)
            : new ArrayList<>();
        
        Map<String, ScoredResult> results = new HashMap<>();
        
        // Process vector results
        int rank = 1;
        for (VectorRAGService.ScoredChunk sc : vectorResults) {
            String key = getChunkKey(sc.chunk);
            ScoredResult sr = results.computeIfAbsent(key, k -> new ScoredResult(sc.chunk));
            sr.vectorRank = rank;
            sr.vectorScore = sc.score;
            sr.rrfScore += VECTOR_WEIGHT * (1.0 / (RRF_K + rank));
            rank++;
        }
        
        // Process keyword results
        rank = 1;
        for (RAGService.CodeChunk chunk : keywordResults) {
            String key = getChunkKey(chunk);
            ScoredResult sr = results.computeIfAbsent(key, k -> new ScoredResult(chunk));
            sr.keywordRank = rank;
            sr.rrfScore += KEYWORD_WEIGHT * (1.0 / (RRF_K + rank));
            rank++;
        }
        
        return results.values().stream()
            .sorted(Comparator.comparingDouble((ScoredResult sr) -> sr.rrfScore).reversed())
            .limit(topK)
            .collect(Collectors.toList());
    }
    
    /**
     * Add RRF scores for a list of results.
     */
    private void addRRFScores(Map<String, Double> scores, 
                              Map<String, RAGService.CodeChunk> chunkMap,
                              List<RAGService.CodeChunk> results, 
                              double weight) {
        int rank = 1;
        for (RAGService.CodeChunk chunk : results) {
            String key = getChunkKey(chunk);
            chunkMap.putIfAbsent(key, chunk);
            
            // RRF formula: score = weight * 1/(k + rank)
            double rrfScore = weight * (1.0 / (RRF_K + rank));
            scores.merge(key, rrfScore, Double::sum);
            rank++;
        }
    }
    
    /**
     * Generate unique key for a chunk.
     */
    private String getChunkKey(RAGService.CodeChunk chunk) {
        return chunk.filePath + ":" + chunk.name;
    }
    
    /**
     * Check if hybrid RAG is fully available (both vector and keyword).
     */
    public boolean isFullyAvailable() {
        return vectorService.isAvailable() && vectorService.isIndexed();
    }
    
    /**
     * Get retrieval mode description.
     */
    public String getMode() {
        if (vectorService.isAvailable() && vectorService.isIndexed()) {
            return "hybrid (vector + keyword)";
        } else {
            return "keyword-only";
        }
    }
    
    /**
     * Get statistics about the hybrid service.
     */
    public String getStats() {
        return String.format("HybridRAG: mode=%s, vectorWeight=%.0f%%, keywordWeight=%.0f%%",
            getMode(), VECTOR_WEIGHT * 100, KEYWORD_WEIGHT * 100);
    }
    
    /**
     * Detailed scored result for debugging.
     */
    public static class ScoredResult {
        public final RAGService.CodeChunk chunk;
        public int vectorRank = -1;
        public int keywordRank = -1;
        public double vectorScore = 0.0;
        public double rrfScore = 0.0;
        
        public ScoredResult(RAGService.CodeChunk chunk) {
            this.chunk = chunk;
        }
        
        @Override
        public String toString() {
            return String.format("%s: rrf=%.4f, vecRank=%d(%.3f), kwRank=%d",
                chunk.name, rrfScore, vectorRank, vectorScore, keywordRank);
        }
    }
}
