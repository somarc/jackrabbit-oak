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

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Vector-based RAG service using Ollama embeddings.
 * 
 * <p>Uses nomic-embed-text model (Apache 2.0 licensed) for generating
 * semantic embeddings of code chunks. Provides cosine similarity search
 * for finding semantically relevant code.
 * 
 * <p>Embeddings are cached to disk for fast startup on subsequent runs.
 * 
 * @since 1.89
 */
public class VectorRAGService {
    private static final Logger log = LoggerFactory.getLogger(VectorRAGService.class);
    
    /** Default embedding model - Apache 2.0 licensed */
    private static final String DEFAULT_EMBEDDING_MODEL = "nomic-embed-text";
    
    /** Embedding dimension for nomic-embed-text (768 for reference) */
    // Note: Actual dimension is determined at runtime from model response
    
    private final String ollamaUrl;
    private final String embeddingModel;
    private final CloseableHttpClient httpClient;
    private final Gson gson = new Gson();
    
    /** Map from chunk ID to embedding vector */
    private final Map<String, float[]> embeddings = new ConcurrentHashMap<>();
    
    /** Map from chunk ID to CodeChunk */
    private final Map<String, RAGService.CodeChunk> chunkById = new ConcurrentHashMap<>();
    
    private Path embeddingCacheFile;
    private volatile boolean available = false;
    private volatile boolean indexed = false;
    
    public VectorRAGService() {
        this(getOllamaUrlFromConfig(), getEmbeddingModelFromConfig());
    }
    
    public VectorRAGService(String ollamaUrl, String embeddingModel) {
        this.ollamaUrl = ollamaUrl;
        this.embeddingModel = embeddingModel;
        
        // Create HTTP client with reasonable timeouts
        RequestConfig requestConfig = RequestConfig.custom()
            .setConnectTimeout(10000)
            .setSocketTimeout(30000)  // Embeddings are faster than generation
            .build();
        this.httpClient = HttpClients.custom()
            .setDefaultRequestConfig(requestConfig)
            .build();
        
        initializeCache();
        checkAvailability();
    }
    
    /**
     * Get Ollama URL from environment or system property.
     */
    private static String getOllamaUrlFromConfig() {
        String url = System.getenv("OLLAMA_URL");
        if (url != null && !url.isEmpty()) {
            return url;
        }
        url = System.getProperty("ollama.url");
        if (url != null && !url.isEmpty()) {
            return url;
        }
        // Docker detection
        boolean isDocker = System.getenv("container") != null;
        return isDocker ? "http://host.docker.internal:11434" : "http://localhost:11434";
    }
    
    /**
     * Get embedding model from environment or system property.
     */
    private static String getEmbeddingModelFromConfig() {
        String model = System.getenv("OLLAMA_EMBEDDING_MODEL");
        if (model != null && !model.isEmpty()) {
            return model;
        }
        model = System.getProperty("ollama.embedding.model");
        if (model != null && !model.isEmpty()) {
            return model;
        }
        return DEFAULT_EMBEDDING_MODEL;
    }
    
    /**
     * Initialize embedding cache directory.
     */
    private void initializeCache() {
        String cacheBase = System.getProperty("oak.rag.cache.dir",
            System.getProperty("java.io.tmpdir") + "/.oak-rag-cache");
        Path cacheDir = Paths.get(cacheBase);
        embeddingCacheFile = cacheDir.resolve("embeddings-" + embeddingModel.replace(":", "-") + ".json");
        
        try {
            Files.createDirectories(cacheDir);
            log.debug("Vector RAG cache directory: {}", cacheDir);
        } catch (IOException e) {
            log.warn("Could not create embedding cache directory: {}", e.getMessage());
        }
    }
    
    /**
     * Check if Ollama embedding service is available.
     */
    private void checkAvailability() {
        try {
            // Try a simple embedding to verify the model is available
            float[] test = embed("test");
            if (test != null && test.length > 0) {
                available = true;
                log.info("✅ Vector RAG service available with model {} (dim={})", 
                    embeddingModel, test.length);
            }
        } catch (Exception e) {
            log.warn("⚠️  Vector RAG service not available: {}", e.getMessage());
            log.debug("To enable vector RAG, run: ollama pull {}", embeddingModel);
            available = false;
        }
    }
    
    /**
     * Check if the vector service is available.
     */
    public boolean isAvailable() {
        return available;
    }
    
    /**
     * Check if embeddings have been indexed.
     */
    public boolean isIndexed() {
        return indexed;
    }
    
    /**
     * Index code chunks by generating embeddings.
     * 
     * @param chunks List of code chunks to index
     */
    public void indexChunks(List<RAGService.CodeChunk> chunks) {
        if (!available) {
            log.warn("Vector RAG not available, skipping embedding generation");
            return;
        }
        
        // Try to load from cache first
        if (loadEmbeddingsFromCache(chunks)) {
            log.info("✅ Loaded {} embeddings from cache", embeddings.size());
            indexed = true;
            return;
        }
        
        log.info("🔄 Generating embeddings for {} chunks...", chunks.size());
        long startTime = System.currentTimeMillis();
        int successCount = 0;
        int failCount = 0;
        
        for (RAGService.CodeChunk chunk : chunks) {
            String chunkId = generateChunkId(chunk);
            chunkById.put(chunkId, chunk);
            
            // Skip if already embedded
            if (embeddings.containsKey(chunkId)) {
                successCount++;
                continue;
            }
            
            try {
                // Create embedding text: name + summary + truncated content
                String embeddingText = buildEmbeddingText(chunk);
                float[] embedding = embed(embeddingText);
                
                if (embedding != null && embedding.length > 0) {
                    embeddings.put(chunkId, embedding);
                    successCount++;
                    
                    // Progress logging every 50 chunks
                    if (successCount % 50 == 0) {
                        log.info("  Embedded {}/{} chunks...", successCount, chunks.size());
                    }
                } else {
                    failCount++;
                }
            } catch (Exception e) {
                log.debug("Failed to embed chunk {}: {}", chunk.name, e.getMessage());
                failCount++;
            }
        }
        
        long elapsed = System.currentTimeMillis() - startTime;
        log.info("✅ Generated {} embeddings in {}ms ({} failed)", 
            successCount, elapsed, failCount);
        
        // Save to cache
        saveEmbeddingsToCache();
        indexed = true;
    }
    
    /**
     * Build text for embedding from a code chunk.
     */
    private String buildEmbeddingText(RAGService.CodeChunk chunk) {
        StringBuilder text = new StringBuilder();
        text.append(chunk.name).append("\n");
        
        if (chunk.summary != null && !chunk.summary.isEmpty()) {
            text.append(chunk.summary).append("\n");
        }
        
        // Truncate content to avoid token limits (nomic-embed-text has 8192 token limit)
        String content = chunk.content;
        if (content.length() > 4000) {
            content = content.substring(0, 4000) + "...";
        }
        text.append(content);
        
        return text.toString();
    }
    
    /**
     * Generate a unique ID for a code chunk.
     */
    private String generateChunkId(RAGService.CodeChunk chunk) {
        return chunk.filePath + ":" + chunk.name;
    }
    
    /**
     * Generate embedding for text using Ollama.
     * 
     * @param text Text to embed
     * @return Embedding vector, or null if failed
     */
    public float[] embed(String text) {
        if (!available && embeddings.isEmpty()) {
            return null;
        }
        
        try {
            JsonObject requestJson = new JsonObject();
            requestJson.addProperty("model", embeddingModel);
            requestJson.addProperty("prompt", text);
            
            HttpPost request = new HttpPost(ollamaUrl + "/api/embeddings");
            request.setEntity(new StringEntity(gson.toJson(requestJson), "UTF-8"));
            request.setHeader("Content-Type", "application/json");
            
            try (CloseableHttpResponse response = httpClient.execute(request)) {
                int statusCode = response.getStatusLine().getStatusCode();
                String responseBody = EntityUtils.toString(response.getEntity());
                
                if (statusCode >= 200 && statusCode < 300) {
                    JsonObject jsonResponse = gson.fromJson(responseBody, JsonObject.class);
                    
                    if (jsonResponse.has("embedding")) {
                        JsonArray embeddingArray = jsonResponse.getAsJsonArray("embedding");
                        float[] embedding = new float[embeddingArray.size()];
                        for (int i = 0; i < embeddingArray.size(); i++) {
                            embedding[i] = embeddingArray.get(i).getAsFloat();
                        }
                        return embedding;
                    }
                } else {
                    log.debug("Embedding API error: HTTP {} - {}", statusCode, responseBody);
                }
            }
        } catch (IOException e) {
            log.debug("Error calling embedding API: {}", e.getMessage());
        }
        
        return null;
    }
    
    /**
     * Retrieve code chunks by semantic similarity.
     * 
     * @param query Query text
     * @param topK Number of results to return
     * @return List of most similar code chunks
     */
    public List<RAGService.CodeChunk> retrieveBySimilarity(String query, int topK) {
        if (!available || embeddings.isEmpty()) {
            return new ArrayList<>();
        }
        
        // Generate query embedding
        float[] queryEmbedding = embed(query);
        if (queryEmbedding == null) {
            return new ArrayList<>();
        }
        
        // Calculate similarity scores
        List<ScoredChunk> scored = new ArrayList<>();
        for (Map.Entry<String, float[]> entry : embeddings.entrySet()) {
            RAGService.CodeChunk chunk = chunkById.get(entry.getKey());
            if (chunk != null) {
                double similarity = cosineSimilarity(queryEmbedding, entry.getValue());
                scored.add(new ScoredChunk(chunk, similarity));
            }
        }
        
        // Sort by similarity (descending) and return top-K
        return scored.stream()
            .sorted(Comparator.comparingDouble((ScoredChunk sc) -> sc.score).reversed())
            .limit(topK)
            .map(sc -> sc.chunk)
            .collect(Collectors.toList());
    }
    
    /**
     * Retrieve with scores for debugging/analysis.
     */
    public List<ScoredChunk> retrieveWithScores(String query, int topK) {
        if (!available || embeddings.isEmpty()) {
            return new ArrayList<>();
        }
        
        float[] queryEmbedding = embed(query);
        if (queryEmbedding == null) {
            return new ArrayList<>();
        }
        
        List<ScoredChunk> scored = new ArrayList<>();
        for (Map.Entry<String, float[]> entry : embeddings.entrySet()) {
            RAGService.CodeChunk chunk = chunkById.get(entry.getKey());
            if (chunk != null) {
                double similarity = cosineSimilarity(queryEmbedding, entry.getValue());
                scored.add(new ScoredChunk(chunk, similarity));
            }
        }
        
        return scored.stream()
            .sorted(Comparator.comparingDouble((ScoredChunk sc) -> sc.score).reversed())
            .limit(topK)
            .collect(Collectors.toList());
    }
    
    /**
     * Calculate cosine similarity between two vectors.
     */
    private double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) {
            return 0.0;
        }
        
        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        
        for (int i = 0; i < a.length; i++) {
            dotProduct += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        
        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }
        
        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }
    
    /**
     * Load embeddings from cache.
     */
    private boolean loadEmbeddingsFromCache(List<RAGService.CodeChunk> chunks) {
        if (embeddingCacheFile == null || !Files.exists(embeddingCacheFile)) {
            return false;
        }
        
        try (FileReader reader = new FileReader(embeddingCacheFile.toFile())) {
            Type type = new TypeToken<Map<String, float[]>>(){}.getType();
            Map<String, float[]> cached = gson.fromJson(reader, type);
            
            if (cached == null || cached.isEmpty()) {
                return false;
            }
            
            // Build chunk ID map
            for (RAGService.CodeChunk chunk : chunks) {
                String chunkId = generateChunkId(chunk);
                chunkById.put(chunkId, chunk);
            }
            
            // Check if cache covers most chunks
            int matchCount = 0;
            for (String chunkId : chunkById.keySet()) {
                if (cached.containsKey(chunkId)) {
                    matchCount++;
                }
            }
            
            // If cache covers >80% of chunks, use it
            if (matchCount > chunks.size() * 0.8) {
                embeddings.putAll(cached);
                log.debug("Cache hit rate: {}/{} chunks", matchCount, chunks.size());
                return true;
            } else {
                log.debug("Cache stale: only {}/{} chunks match", matchCount, chunks.size());
                return false;
            }
        } catch (Exception e) {
            log.debug("Could not load embedding cache: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * Save embeddings to cache.
     */
    private void saveEmbeddingsToCache() {
        if (embeddingCacheFile == null || embeddings.isEmpty()) {
            return;
        }
        
        try (FileWriter writer = new FileWriter(embeddingCacheFile.toFile())) {
            gson.toJson(embeddings, writer);
            log.info("💾 Saved {} embeddings to cache", embeddings.size());
        } catch (Exception e) {
            log.warn("Could not save embedding cache: {}", e.getMessage());
        }
    }
    
    /**
     * Get embedding statistics.
     */
    public String getStats() {
        return String.format("VectorRAG: model=%s, chunks=%d, available=%s", 
            embeddingModel, embeddings.size(), available);
    }
    
    /**
     * Scored chunk for ranking.
     */
    public static class ScoredChunk {
        public final RAGService.CodeChunk chunk;
        public final double score;
        
        public ScoredChunk(RAGService.CodeChunk chunk, double score) {
            this.chunk = chunk;
            this.score = score;
        }
    }
}
