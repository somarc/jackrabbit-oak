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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Enhanced RAG service for code retrieval.
 * 
 * <p>Scans the Oak codebase and indexes Java files for semantic search.
 * Uses keyword matching with relevance scoring.
 */
public class RAGService {
    private static final Logger log = LoggerFactory.getLogger(RAGService.class);
    
    private final List<CodeChunk> codeChunks = new ArrayList<>();
    private final Map<String, Integer> termFrequency = new ConcurrentHashMap<>();
    private volatile boolean indexed = false;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private Path cacheDir;
    private Path indexCacheFile;
    private Path metadataCacheFile;
    
    public RAGService() {
        initializeCache();
        // Try to load from cache, otherwise index codebase
        if (!loadFromCache()) {
            indexCodebase();
        }
    }
    
    /**
     * Initialize cache directory.
     */
    private void initializeCache() {
        String cacheBase = System.getProperty("oak.rag.cache.dir", 
            System.getProperty("java.io.tmpdir") + "/.oak-rag-cache");
        cacheDir = Paths.get(cacheBase);
        indexCacheFile = cacheDir.resolve("rag-index.json");
        metadataCacheFile = cacheDir.resolve("rag-metadata.json");
        
        try {
            Files.createDirectories(cacheDir);
            log.debug("RAG cache directory: {}", cacheDir);
        } catch (IOException e) {
            log.warn("Could not create RAG cache directory: {}", e.getMessage());
        }
    }
    
    /**
     * Index the Oak codebase by scanning Java files.
     */
    private void indexCodebase() {
        // Try multiple possible locations for Oak codebase
        String[] possiblePaths = {
            System.getProperty("oak.codebase.path"),
            System.getProperty("user.dir") + "/jackrabbit-oak",
            System.getProperty("user.dir") + "/../jackrabbit-oak",
            System.getProperty("user.dir"),
            "/Users/mhess/aem/AEM Code/OAK/jackrabbit-oak"  // Fallback for development
        };
        
        Path oakRoot = null;
        for (String path : possiblePaths) {
            if (path != null) {
                Path candidate = Paths.get(path);
                if (Files.exists(candidate) && Files.isDirectory(candidate)) {
                    // Check if it looks like Oak root (has oak-segment-consensus or oak-segment-tar)
                    File[] modules = candidate.toFile().listFiles(f -> 
                        f.isDirectory() && f.getName().startsWith("oak-"));
                    if (modules != null && modules.length > 0) {
                        oakRoot = candidate;
                        log.info("📚 Found Oak codebase at: {}", oakRoot);
                        break;
                    }
                }
            }
        }
        
        if (oakRoot == null) {
            log.warn("⚠️  Oak codebase not found, using basic chunks only");
            initializeBasicChunks();
            return;
        }
        
        try {
            log.info("🔍 Indexing Oak codebase...");
            int indexedCount = scanAndIndex(oakRoot);
            log.info("✅ Indexed {} Java files", indexedCount);
            
            if (indexedCount == 0) {
                log.warn("⚠️  No Java files found, falling back to basic chunks");
                initializeBasicChunks();
            } else {
                indexed = true;
                // Calculate term frequencies for better relevance scoring
                calculateTermFrequencies();
                // Save to cache for next time
                saveToCache(oakRoot);
            }
        } catch (Exception e) {
            log.error("Error indexing codebase", e);
            log.warn("⚠️  Falling back to basic chunks");
            initializeBasicChunks();
        }
    }
    
    /**
     * Load index from cache if available and still valid.
     */
    private boolean loadFromCache() {
        if (indexCacheFile == null || metadataCacheFile == null) {
            return false;
        }
        
        if (!Files.exists(indexCacheFile) || !Files.exists(metadataCacheFile)) {
            log.debug("RAG cache not found, will index from scratch");
            return false;
        }
        
        try {
            // Load metadata first
            Map<String, Object> metadata;
            try (FileReader reader = new FileReader(metadataCacheFile.toFile())) {
                Type type = new TypeToken<Map<String, Object>>(){}.getType();
                metadata = gson.fromJson(reader, type);
            }
            
            String cachedOakRoot = (String) metadata.get("oakRoot");
            if (cachedOakRoot == null) {
                log.debug("Cache metadata invalid, re-indexing");
                return false;
            }
            
            // Check if cached root still exists and files haven't changed
            Path oakRoot = Paths.get(cachedOakRoot);
            if (!Files.exists(oakRoot)) {
                log.debug("Cached Oak root no longer exists: {}, re-indexing", cachedOakRoot);
                return false;
            }
            
            // Quick check: verify a few key files haven't changed
            if (!isCacheValid(oakRoot, metadata)) {
                log.info("🔄 RAG cache invalid (files changed), re-indexing...");
                return false;
            }
            
            // Load index from cache
            try (FileReader reader = new FileReader(indexCacheFile.toFile())) {
                Type listType = new TypeToken<List<CodeChunk>>(){}.getType();
                List<CodeChunk> cached = gson.fromJson(reader, listType);
                if (cached != null && !cached.isEmpty()) {
                    codeChunks.addAll(cached);
                    indexed = true;
                    
                    // Rebuild term frequency map
                    calculateTermFrequencies();
                    
                    log.info("✅ Loaded RAG index from cache ({} chunks)", codeChunks.size());
                    return true;
                }
            }
        } catch (Exception e) {
            log.warn("Error loading RAG cache, will re-index: {}", e.getMessage());
            log.debug("Cache load error details", e);
        }
        
        return false;
    }
    
    /**
     * Check if cache is still valid by comparing file modification times.
     */
    private boolean isCacheValid(Path oakRoot, Map<String, Object> metadata) {
        @SuppressWarnings("unchecked")
        Map<String, Long> cachedModTimes = (Map<String, Long>) metadata.get("fileModTimes");
        if (cachedModTimes == null || cachedModTimes.isEmpty()) {
            return false;
        }
        
        // Sample check: verify first 10 files haven't changed
        int checked = 0;
        int maxChecks = Math.min(10, cachedModTimes.size());
        
        for (Map.Entry<String, Long> entry : cachedModTimes.entrySet()) {
            if (checked >= maxChecks) break;
            
            Path filePath = oakRoot.resolve(entry.getKey());
            if (!Files.exists(filePath)) {
                return false; // File was deleted
            }
            
            try {
                FileTime modTime = Files.getLastModifiedTime(filePath);
                if (modTime.toMillis() != entry.getValue()) {
                    return false; // File was modified
                }
            } catch (IOException e) {
                log.debug("Could not check modification time for {}: {}", filePath, e.getMessage());
                return false;
            }
            
            checked++;
        }
        
        return true;
    }
    
    /**
     * Save index to cache.
     */
    private void saveToCache(Path oakRoot) {
        if (indexCacheFile == null || metadataCacheFile == null || codeChunks.isEmpty()) {
            return;
        }
        
        try {
            // Save index
            try (FileWriter writer = new FileWriter(indexCacheFile.toFile())) {
                gson.toJson(codeChunks, writer);
            }
            
            // Save metadata (file modification times for validation)
            Map<String, Long> fileModTimes = new HashMap<>();
            int sampleSize = Math.min(50, codeChunks.size()); // Sample first 50 files
            
            for (int i = 0; i < sampleSize; i++) {
                CodeChunk chunk = codeChunks.get(i);
                Path filePath = oakRoot.resolve(chunk.filePath);
                if (Files.exists(filePath)) {
                    try {
                        FileTime modTime = Files.getLastModifiedTime(filePath);
                        fileModTimes.put(chunk.filePath, modTime.toMillis());
                    } catch (IOException e) {
                        // Skip this file
                    }
                }
            }
            
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("oakRoot", oakRoot.toString());
            metadata.put("fileModTimes", fileModTimes);
            metadata.put("chunkCount", codeChunks.size());
            metadata.put("indexedAt", System.currentTimeMillis());
            
            try (FileWriter writer = new FileWriter(metadataCacheFile.toFile())) {
                gson.toJson(metadata, writer);
            }
            
            log.info("💾 Saved RAG index to cache ({} chunks)", codeChunks.size());
        } catch (Exception e) {
            log.warn("Could not save RAG cache: {}", e.getMessage());
            log.debug("Cache save error details", e);
        }
    }
    
    /**
     * Recursively scan and index Java files.
     */
    private int scanAndIndex(Path root) throws IOException {
        AtomicInteger count = new AtomicInteger(0);
        
        // Focus on key modules: consensus, agentic, segment-tar, segment-http
        String[] targetModules = {
            "oak-segment-consensus",
            "oak-segment-agentic",
            "oak-segment-tar",
            "oak-segment-http",
            "oak-store-composite",
            "oak-core"
        };
        
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile)
                 .filter(p -> p.toString().endsWith(".java"))
                 .filter(p -> {
                     String pathStr = p.toString();
                     // Include target modules or all if none specified
                     for (String module : targetModules) {
                         if (pathStr.contains(module + "/src/main/java")) {
                             return true;
                         }
                     }
                     return false;
                 })
                 .forEach(p -> {
                     try {
                         indexFile(p, root);
                         count.incrementAndGet();
                     } catch (Exception e) {
                         log.debug("Error indexing file {}: {}", p, e.getMessage());
                     }
                 });
        }
        
        return count.get();
    }
    
    /**
     * Index a single Java file.
     */
    private void indexFile(Path filePath, Path oakRoot) throws IOException {
        String content = new String(Files.readAllBytes(filePath), StandardCharsets.UTF_8);
        
        // Extract class name from file
        String className = extractClassName(content, filePath);
        
        // Get relative path from Oak root
        String relativePath = oakRoot.relativize(filePath).toString().replace('\\', '/');
        
        // Extract key information: class docs, method signatures, key fields
        String summary = extractSummary(content);
        
        // Split large files into chunks (max 2000 chars per chunk)
        if (content.length() > 2000) {
            // Create multiple chunks for large files
            String[] chunks = splitIntoChunks(content, 2000);
            for (int i = 0; i < chunks.length; i++) {
                codeChunks.add(new CodeChunk(
                    className + (chunks.length > 1 ? " (part " + (i + 1) + ")" : ""),
                    relativePath,
                    chunks[i],
                    summary
                ));
            }
        } else {
            codeChunks.add(new CodeChunk(
                className,
                relativePath,
                content,
                summary
            ));
        }
    }
    
    /**
     * Extract class name from Java file.
     */
    private String extractClassName(String content, Path filePath) {
        // Try to find class declaration
        String[] lines = content.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.startsWith("public class ") || line.startsWith("class ") || 
                line.startsWith("public interface ") || line.startsWith("interface ")) {
                String[] parts = line.split("\\s+");
                for (int i = 0; i < parts.length; i++) {
                    if (parts[i].equals("class") || parts[i].equals("interface")) {
                        if (i + 1 < parts.length) {
                            String name = parts[i + 1];
                            // Remove generics and extends/implements
                            int genericStart = name.indexOf('<');
                            if (genericStart > 0) {
                                name = name.substring(0, genericStart);
                            }
                            return name;
                        }
                    }
                }
            }
        }
        // Fallback to filename
        String fileName = filePath.getFileName().toString();
        return fileName.substring(0, fileName.length() - 5); // Remove .java
    }
    
    /**
     * Extract summary from Java file (class-level JavaDoc).
     */
    private String extractSummary(String content) {
        // Look for class-level JavaDoc
        int classIndex = content.indexOf("public class");
        if (classIndex == -1) {
            classIndex = content.indexOf("class ");
        }
        if (classIndex == -1) {
            classIndex = content.indexOf("public interface");
        }
        if (classIndex == -1) {
            classIndex = content.indexOf("interface ");
        }
        
        if (classIndex > 0) {
            String beforeClass = content.substring(0, classIndex);
            int javadocStart = beforeClass.lastIndexOf("/**");
            if (javadocStart >= 0) {
                int javadocEnd = content.indexOf("*/", javadocStart);
                if (javadocEnd > javadocStart) {
                    String javadoc = content.substring(javadocStart + 3, javadocEnd);
                    // Extract first meaningful sentence
                    String[] lines = javadoc.split("\n");
                    StringBuilder summary = new StringBuilder();
                    for (String line : lines) {
                        line = line.trim();
                        if (line.startsWith("*")) {
                            line = line.substring(1).trim();
                        }
                        if (!line.isEmpty() && !line.startsWith("@")) {
                            summary.append(line).append(" ");
                            if (summary.length() > 200) {
                                break;
                            }
                        }
                    }
                    return summary.toString().trim();
                }
            }
        }
        
        return "";
    }
    
    /**
     * Split content into chunks.
     */
    private String[] splitIntoChunks(String content, int maxChunkSize) {
        List<String> chunks = new ArrayList<>();
        String[] lines = content.split("\n");
        StringBuilder currentChunk = new StringBuilder();
        
        for (String line : lines) {
            if (currentChunk.length() + line.length() + 1 > maxChunkSize && currentChunk.length() > 0) {
                chunks.add(currentChunk.toString());
                currentChunk = new StringBuilder();
            }
            currentChunk.append(line).append("\n");
        }
        
        if (currentChunk.length() > 0) {
            chunks.add(currentChunk.toString());
        }
        
        return chunks.toArray(new String[0]);
    }
    
    /**
     * Calculate term frequencies for relevance scoring.
     */
    private void calculateTermFrequencies() {
        termFrequency.clear();
        for (CodeChunk chunk : codeChunks) {
            String[] words = (chunk.name + " " + chunk.content).toLowerCase()
                .split("[\\s\\.\\(\\)\\[\\]\\{\\},;]+");
            for (String word : words) {
                if (word.length() > 2) { // Ignore very short words
                    termFrequency.put(word, termFrequency.getOrDefault(word, 0) + 1);
                }
            }
        }
    }
    
    /**
     * Retrieve relevant code chunks for a query.
     * 
     * @param query User's question
     * @return List of relevant code chunks, sorted by relevance
     */
    public List<CodeChunk> retrieve(String query) {
        String lower = query.toLowerCase();
        List<ScoredChunk> scored = new ArrayList<>();
        
        // Extract query terms
        String[] queryTerms = lower.split("[\\s\\.\\(\\)\\[\\]\\{\\},;]+");
        
        for (CodeChunk chunk : codeChunks) {
            double score = calculateRelevanceScore(chunk, queryTerms, lower);
            if (score > 0) {
                scored.add(new ScoredChunk(chunk, score));
            }
        }
        
        // Sort by relevance score (descending)
        scored.sort(Comparator.comparingDouble((ScoredChunk sc) -> sc.score).reversed());
        
        // Return top 10 matches
        List<CodeChunk> results = new ArrayList<>();
        for (int i = 0; i < Math.min(10, scored.size()); i++) {
            results.add(scored.get(i).chunk);
        }
        
        return results;
    }
    
    /**
     * Calculate relevance score for a chunk against query terms.
     */
    private double calculateRelevanceScore(CodeChunk chunk, String[] queryTerms, String fullQuery) {
        double score = 0.0;
        String chunkText = (chunk.name + " " + chunk.content + " " + chunk.summary).toLowerCase();
        
        // Exact phrase match (highest weight)
        if (chunkText.contains(fullQuery)) {
            score += 10.0;
        }
        
        // Class name match (high weight)
        if (chunk.name.toLowerCase().contains(fullQuery)) {
            score += 8.0;
        }
        
        // Term frequency scoring
        for (String term : queryTerms) {
            if (term.length() < 3) continue;
            
            int termCount = countOccurrences(chunkText, term);
            if (termCount > 0) {
                // TF-IDF-like scoring (simplified)
                double tf = Math.log(1 + termCount);
                double idf = indexed ? Math.log((double) codeChunks.size() / (termFrequency.getOrDefault(term, 1))) : 1.0;
                score += tf * idf;
                
                // Boost for name matches
                if (chunk.name.toLowerCase().contains(term)) {
                    score += 3.0;
                }
            }
        }
        
        return score;
    }
    
    /**
     * Count occurrences of substring in text.
     */
    private int countOccurrences(String text, String substring) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(substring, index)) != -1) {
            count++;
            index += substring.length();
        }
        return count;
    }
    
    /**
     * Helper class for scoring chunks.
     */
    private static class ScoredChunk {
        final CodeChunk chunk;
        final double score;
        
        ScoredChunk(CodeChunk chunk, double score) {
            this.chunk = chunk;
            this.score = score;
        }
    }
    
    /**
     * Represents a chunk of code/documentation.
     */
    public static class CodeChunk {
        public final String name;
        public final String filePath;
        public final String content;
        public final String summary;
        
        public CodeChunk(String name, String filePath, String content) {
            this(name, filePath, content, "");
        }
        
        public CodeChunk(String name, String filePath, String content, String summary) {
            this.name = name;
            this.filePath = filePath;
            this.content = content;
            this.summary = summary != null ? summary : "";
        }
        
        public boolean matches(String query) {
            String lower = query.toLowerCase();
            return name.toLowerCase().contains(lower) ||
                   filePath.toLowerCase().contains(lower) ||
                   content.toLowerCase().contains(lower) ||
                   summary.toLowerCase().contains(lower);
        }
        
        @Override
        public String toString() {
            if (!summary.isEmpty()) {
                return String.format("%s (%s): %s\n%s", name, filePath, summary, 
                    content.length() > 500 ? content.substring(0, 500) + "..." : content);
            }
            return String.format("%s (%s): %s", name, filePath, 
                content.length() > 500 ? content.substring(0, 500) + "..." : content);
        }
    }
    
    /**
     * Initialize basic chunks as fallback.
     */
    private void initializeBasicChunks() {
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
}

