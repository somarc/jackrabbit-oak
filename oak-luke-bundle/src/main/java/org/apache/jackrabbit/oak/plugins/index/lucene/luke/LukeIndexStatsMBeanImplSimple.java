/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.jackrabbit.oak.plugins.index.lucene.luke;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.management.openmbean.CompositeDataSupport;
import javax.management.openmbean.CompositeType;
import javax.management.openmbean.OpenDataException;
import javax.management.openmbean.OpenType;
import javax.management.openmbean.SimpleType;
import javax.management.openmbean.TabularData;
import javax.management.openmbean.TabularDataSupport;
import javax.management.openmbean.TabularType;

import org.apache.jackrabbit.oak.commons.jmx.AnnotatedStandardMBean;
import org.apache.jackrabbit.oak.plugins.index.lucene.IndexCopier;
import org.apache.jackrabbit.oak.plugins.index.lucene.IndexTracker;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.Fields;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.MultiFields;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.apache.jackrabbit.oak.commons.IOUtils.humanReadableByteCount;

/**
 * Simplified implementation for Oak 1.22.x that works WITHOUT IndexTracker.
 * Uses IndexCopier and direct filesystem access only.
 */
public class LukeIndexStatsMBeanImplSimple extends AnnotatedStandardMBean implements LukeIndexStatsMBean {

    private static final Logger log = LoggerFactory.getLogger(LukeIndexStatsMBeanImplSimple.class);

    // Job tracking for fulltext backup
    private static final Map<String, FulltextBackupJob> backupJobs = new ConcurrentHashMap<>();
    
    private final IndexCopier indexCopier;
    private final File repositoryHome;

    public LukeIndexStatsMBeanImplSimple(@Nullable IndexTracker indexTracker,
                                         @Nullable IndexCopier indexCopier,
                                         File repositoryHome) {
        super(LukeIndexStatsMBean.class);
        this.indexCopier = indexCopier;
        this.repositoryHome = repositoryHome;
        
        if (indexTracker != null) {
            log.info("IndexTracker available but not used in this simplified implementation");
        }
        if (indexCopier == null) {
            log.warn("IndexCopier not available - will use repository home: {}", repositoryHome);
        }
    }

    @Override
    public TabularData getLocalIndexDirectories() {
        TabularDataSupport tds;
        try {
            String[] headers = new String[]{"indexPath", "localPath", "size", "exists"};
            CompositeType ct = new CompositeType(
                    "LocalIndexDirectory",
                    "Local Index Directory Information",
                    headers,
                    headers,
                    new OpenType[]{SimpleType.STRING, SimpleType.STRING, SimpleType.STRING, SimpleType.BOOLEAN}
            );
            TabularType tt = new TabularType(
                    "LocalIndexDirectories",
                    "Local Index Directories",
                    ct,
                    new String[]{"indexPath"}
            );
            tds = new TabularDataSupport(tt);

            // Find index directories in repository/index folder
            File indexBaseDir = new File(repositoryHome, "repository/index");
            if (indexBaseDir.exists() && indexBaseDir.isDirectory()) {
                File[] indexDirs = indexBaseDir.listFiles();
                if (indexDirs != null) {
                    for (File indexDir : indexDirs) {
                        if (indexDir.isDirectory()) {
                            try {
                                // Only include valid Lucene indexes
                                boolean isValid = isValidLuceneIndex(indexDir);
                                long size = getFolderSize(indexDir);
                                
                                // Add status indicator for metadata-only directories
                                String displayName = indexDir.getName();
                                if (!isValid) {
                                    displayName += " (metadata only)";
                                }
                                
                                tds.put(new CompositeDataSupport(ct, headers, new Object[]{
                                        displayName,
                                        indexDir.getAbsolutePath(),
                                        humanReadableByteCount(size),
                                        isValid
                                }));
                            } catch (Exception e) {
                                log.warn("Error processing directory: {}", indexDir, e);
                            }
                        }
                    }
                }
            } else {
                log.warn("Index base directory not found: {}", indexBaseDir);
            }

        } catch (OpenDataException e) {
            throw new IllegalStateException("Failed to create tabular data", e);
        }
        return tds;
    }

    @Override
    public TabularData getLukeIndexStats(String indexPath) throws IOException {
        try {
            return createDummyTabularData("Not Implemented", "Use getLukeBasicStats instead");
        } catch (OpenDataException e) {
            throw new IOException("Failed to create tabular data", e);
        }
    }

    @Override
    public String[] getLukeFieldInfo(String indexPath, int maxFields) throws IOException {
        File indexDir = findIndexDirectory(indexPath);
        if (indexDir == null) {
            return new String[]{"ERROR: Index not found: " + indexPath};
        }

        File actualIndexDir = getActualIndexDirectory(indexDir);
        List<String> results = new ArrayList<>();
        Directory dir = null;
        IndexReader reader = null;
        
        try {
            dir = FSDirectory.open(actualIndexDir);
            reader = DirectoryReader.open(dir);
            
            Fields fields = MultiFields.getFields(reader);
            if (fields != null) {
                int count = 0;
                for (String fieldName : fields) {
                    if (count++ >= maxFields) break;
                    Terms terms = fields.terms(fieldName);
                    if (terms != null) {
                        long termCount = terms.size();
                        
                        // If size() returns -1, count manually (expensive)
                        if (termCount == -1) {
                            termCount = countTermsManually(terms);
                        }
                        
                        results.add(String.format("Field: %s, Terms: %,d", fieldName, termCount));
                    }
                }
            }
        } finally {
            if (reader != null) reader.close();
            if (dir != null) dir.close();
        }

        return results.toArray(new String[0]);
    }

    @Override
    public String[] getLukeTermStats(String indexPath, String fieldName, int maxTerms) throws IOException {
        File indexDir = findIndexDirectory(indexPath);
        if (indexDir == null) {
            return new String[]{"ERROR: Index not found: " + indexPath};
        }

        File actualIndexDir = getActualIndexDirectory(indexDir);
        List<String> results = new ArrayList<>();
        Directory dir = null;
        IndexReader reader = null;
        
        try {
            dir = FSDirectory.open(actualIndexDir);
            reader = DirectoryReader.open(dir);
            
            Terms terms = MultiFields.getTerms(reader, fieldName);
            if (terms != null) {
                TermsEnum termsEnum = terms.iterator(null);  // Lucene 4.7.2 API
                BytesRef term;
                int count = 0;
                while ((term = termsEnum.next()) != null && count++ < maxTerms) {
                    results.add(String.format("Term: %s, DocFreq: %d",
                            term.utf8ToString(),
                            termsEnum.docFreq()));
                }
            }
        } finally {
            if (reader != null) reader.close();
            if (dir != null) dir.close();
        }

        return results.toArray(new String[0]);
    }

    @Override
    public String getLukeBasicStats(String indexPath) throws IOException {
        File indexDir = findIndexDirectory(indexPath);
        if (indexDir == null) {
            return "ERROR: Index not found: " + indexPath;
        }

        // Oak IndexCopier often stores index in "data" subdirectory
        File actualIndexDir = getActualIndexDirectory(indexDir);
        
        Directory dir = null;
        IndexReader reader = null;
        
        try {
            dir = FSDirectory.open(actualIndexDir);
            reader = DirectoryReader.open(dir);
            
            StringBuilder sb = new StringBuilder();
            sb.append("Index: ").append(indexPath).append("\n");
            sb.append("Local Path: ").append(indexDir.getAbsolutePath()).append("\n");
            sb.append("Documents: ").append(reader.numDocs()).append("\n");
            sb.append("Max Doc: ").append(reader.maxDoc()).append("\n");
            sb.append("Deletions: ").append(reader.numDeletedDocs()).append("\n");
            
            Fields fields = MultiFields.getFields(reader);
            if (fields != null) {
                int fieldCount = 0;
                for (String ignored : fields) {
                    fieldCount++;
                }
                sb.append("Fields: ").append(fieldCount).append("\n");
            }
            
            sb.append("Size: ").append(humanReadableByteCount(getFolderSize(indexDir)));
            
            return sb.toString();
        } finally {
            if (reader != null) reader.close();
            if (dir != null) dir.close();
        }
    }

    @Override
    public String validateLocalIndex(String indexPath) throws IOException {
        File indexDir = findIndexDirectory(indexPath);
        if (indexDir == null) {
            return "ERROR: Index not found or not a valid Lucene index: " + indexPath + 
                   "\nTip: Use getLocalIndexDirectories() to see which indexes are valid";
        }

        // Double-check it's valid (findIndexDirectory already checks, but be explicit)
        if (!isValidLuceneIndex(indexDir)) {
            return "ERROR: Directory exists but does not contain a valid Lucene index (no segments file): " + 
                   indexDir.getAbsolutePath() + 
                   "\nThis may be a metadata-only directory or an index being rebuilt.";
        }

        File actualIndexDir = getActualIndexDirectory(indexDir);
        Directory dir = null;
        try {
            dir = FSDirectory.open(actualIndexDir);
            DirectoryReader reader = DirectoryReader.open(dir);
            int numDocs = reader.numDocs();
            reader.close();
            return String.format("OK: Valid Lucene index with %,d documents at %s", numDocs, actualIndexDir.getAbsolutePath());
        } catch (Exception e) {
            return String.format("ERROR: Failed to open index at %s\nReason: %s", 
                    indexDir.getAbsolutePath(), e.getMessage());
        } finally {
            if (dir != null) dir.close();
        }
    }

    @Override
    public String getLocalIndexPath(String indexPath) {
        File indexDir = findIndexDirectory(indexPath);
        return indexDir != null ? indexDir.getAbsolutePath() : "Not found";
    }

    @Override
    public String launchLukeGUI(String indexPath) {
        return "GUI not supported in this version - use JMX operations instead";
    }


    @Override
    public String[] getFieldSizeAnalysis(String indexPath, int maxFields) throws IOException {
        File indexDir = findIndexDirectory(indexPath);
        if (indexDir == null) {
            return new String[]{"ERROR: Index not found: " + indexPath};
        }

        File actualIndexDir = getActualIndexDirectory(indexDir);
        List<String> results = new ArrayList<>();
        Directory dir = null;
        IndexReader reader = null;
        
        try {
            dir = FSDirectory.open(actualIndexDir);
            reader = DirectoryReader.open(dir);
            
            // Count terms per field (adapted from LUKE's IndexInfo)
            Map<String, FieldTermCount> fieldCounts = new HashMap<>();
            long totalTerms = 0;
            
            Fields fields = MultiFields.getFields(reader);
            if (fields != null) {
                int analyzed = 0;
                
                for (String fieldName : fields) {
                    Terms terms = fields.terms(fieldName);
                    if (terms != null) {
                        long termCount = terms.size();
                        
                        // Lucene 4.7.2: size() returns -1 if unknown
                        // Must iterate to count (expensive but accurate)
                        if (termCount == -1) {
                            log.debug("Counting terms manually for field: {}", fieldName);
                            termCount = countTermsManually(terms);
                        }
                        
                        fieldCounts.put(fieldName, new FieldTermCount(fieldName, termCount));
                        totalTerms += termCount;
                        
                        if (++analyzed % 10 == 0) {
                            log.info("Analyzed {} fields so far...", analyzed);
                        }
                    }
                }
            }
            
            // Calculate percentages
            for (FieldTermCount ftc : fieldCounts.values()) {
                ftc.percentage = totalTerms > 0 ? (ftc.termCount * 100.0 / totalTerms) : 0.0;
            }
            
            // Sort by term count descending
            List<FieldTermCount> sorted = new ArrayList<>(fieldCounts.values());
            Collections.sort(sorted);
            
            // Format results
            results.add(String.format("═══════════════════════════════════════════════════════════════════════════════════════"));
            results.add(String.format("INDEX SIZE ANALYSIS: %s", indexPath));
            results.add(String.format("Total Terms: %,d across %d fields", totalTerms, fieldCounts.size()));
            results.add(String.format("═══════════════════════════════════════════════════════════════════════════════════════"));
            results.add(String.format("%-60s | %-15s | %s", "Field Name", "Term Count", "% of Total"));
            results.add(String.format("%-60s-+-%-15s-+-%s", 
                    "------------------------------------------------------------",
                    "---------------",
                    "----------"));
            
            int count = 0;
            for (FieldTermCount ftc : sorted) {
                if (count++ >= maxFields) break;
                results.add(ftc.toString());
            }
            
            results.add(String.format("═══════════════════════════════════════════════════════════════════════════════════════"));
            
        } finally {
            if (reader != null) reader.close();
            if (dir != null) dir.close();
        }
        
        return results.toArray(new String[0]);
    }

    @Override
    public String[] getTopTermsByDocFreq(String indexPath, String fieldName, int maxTerms) throws IOException {
        File indexDir = findIndexDirectory(indexPath);
        if (indexDir == null) {
            return new String[]{"ERROR: Index not found: " + indexPath};
        }

        File actualIndexDir = getActualIndexDirectory(indexDir);
        List<String> results = new ArrayList<>();
        Directory dir = null;
        IndexReader reader = null;
        
        try {
            dir = FSDirectory.open(actualIndexDir);
            reader = DirectoryReader.open(dir);
            
            // Collect top terms (adapted from LUKE's HighFreqTerms)
            List<TermInfo> topTerms = new ArrayList<>();
            
            Fields fields = MultiFields.getFields(reader);
            if (fields != null) {
                for (String field : fields) {
                    // Skip if specific field requested and this isn't it
                    if (fieldName != null && !fieldName.isEmpty() && !field.equals(fieldName)) {
                        continue;
                    }
                    
                    Terms terms = fields.terms(field);
                    if (terms != null) {
                        TermsEnum termsEnum = terms.iterator(null);
                        BytesRef term;
                        while ((term = termsEnum.next()) != null) {
                            int docFreq = termsEnum.docFreq();
                            topTerms.add(new TermInfo(field, term.utf8ToString(), docFreq, 0));
                            
                            // Keep only top N to avoid memory issues
                            if (topTerms.size() > maxTerms * 2) {
                                Collections.sort(topTerms);
                                topTerms = new ArrayList<>(topTerms.subList(0, maxTerms));
                            }
                        }
                    }
                }
            }
            
            // Final sort
            Collections.sort(topTerms);
            if (topTerms.size() > maxTerms) {
                topTerms = topTerms.subList(0, maxTerms);
            }
            
            // Format results
            String scope = (fieldName != null && !fieldName.isEmpty()) ? fieldName : "All Fields";
            results.add(String.format("═══════════════════════════════════════════════════════════════════════════════════════"));
            results.add(String.format("TOP TERMS BY DOCUMENT FREQUENCY: %s", indexPath));
            results.add(String.format("Scope: %s | Showing top %d terms", scope, maxTerms));
            results.add(String.format("═══════════════════════════════════════════════════════════════════════════════════════"));
            results.add(String.format("%-40s | %-40s | %s", "Field", "Term", "Doc Frequency"));
            results.add(String.format("%-40s-+-%-40s-+-%s",
                    "----------------------------------------",
                    "----------------------------------------",
                    "---------------"));
            
            for (TermInfo ti : topTerms) {
                String truncatedTerm = ti.term.length() > 40 ? ti.term.substring(0, 37) + "..." : ti.term;
                results.add(String.format("%-40s | %-40s | %,10d",
                        ti.field, truncatedTerm, ti.docFreq));
            }
            
            results.add(String.format("═══════════════════════════════════════════════════════════════════════════════════════"));
            
        } finally {
            if (reader != null) reader.close();
            if (dir != null) dir.close();
        }
        
        return results.toArray(new String[0]);
    }

    @Override
    public String getIndexCompositionStats(String indexPath) throws IOException {
        File indexDir = findIndexDirectory(indexPath);
        if (indexDir == null) {
            return "ERROR: Index not found: " + indexPath;
        }

        File actualIndexDir = getActualIndexDirectory(indexDir);
        Directory dir = null;
        IndexReader reader = null;
        
        try {
            dir = FSDirectory.open(actualIndexDir);
            reader = DirectoryReader.open(dir);
            
            // Gather comprehensive statistics
            long totalTerms = 0;
            int fieldCount = 0;
            Map<String, FieldTermCount> fieldCounts = new HashMap<>();
            
            Fields fields = MultiFields.getFields(reader);
            if (fields != null) {
                for (String fieldName : fields) {
                    fieldCount++;
                    Terms terms = fields.terms(fieldName);
                    if (terms != null) {
                        long termCount = terms.size();
                        
                        // Lucene 4.7.2: size() returns -1 if unknown
                        // Must iterate to count (expensive but accurate)
                        if (termCount == -1) {
                            log.debug("Counting terms manually for field: {}", fieldName);
                            termCount = countTermsManually(terms);
                        }
                        
                        fieldCounts.put(fieldName, new FieldTermCount(fieldName, termCount));
                        totalTerms += termCount;
                    }
                }
            }
            
            // Sort and find top 10
            List<FieldTermCount> sorted = new ArrayList<>(fieldCounts.values());
            Collections.sort(sorted);
            List<FieldTermCount> top10 = sorted.subList(0, Math.min(10, sorted.size()));
            
            // Calculate percentages
            for (FieldTermCount ftc : top10) {
                ftc.percentage = totalTerms > 0 ? (ftc.termCount * 100.0 / totalTerms) : 0.0;
            }
            
            long indexSize = getFolderSize(indexDir);
            
            StringBuilder sb = new StringBuilder();
            sb.append("═══════════════════════════════════════════════════════════════\n");
            sb.append("INDEX COMPOSITION ANALYSIS\n");
            sb.append("═══════════════════════════════════════════════════════════════\n");
            sb.append(String.format("Index: %s\n", indexPath));
            sb.append(String.format("Location: %s\n", indexDir.getAbsolutePath()));
            sb.append(String.format("Size: %s\n", humanReadableByteCount(indexSize)));
            sb.append("\n");
            sb.append("DOCUMENT STATISTICS:\n");
            sb.append(String.format("  Total Documents: %,d\n", reader.numDocs()));
            sb.append(String.format("  Max Doc ID: %,d\n", reader.maxDoc()));
            sb.append(String.format("  Deleted Documents: %,d\n", reader.numDeletedDocs()));
            sb.append("\n");
            sb.append("FIELD STATISTICS:\n");
            sb.append(String.format("  Total Fields: %d\n", fieldCount));
            sb.append(String.format("  Total Terms: %,d\n", totalTerms));
            sb.append(String.format("  Avg Terms/Field: %,d\n", fieldCount > 0 ? totalTerms / fieldCount : 0));
            sb.append("\n");
            sb.append("TOP 10 LARGEST FIELDS (by term count):\n");
            for (int i = 0; i < top10.size(); i++) {
                FieldTermCount ftc = top10.get(i);
                String truncated = ftc.fieldName.length() > 50 ? 
                        ftc.fieldName.substring(0, 47) + "..." : ftc.fieldName;
                sb.append(String.format("  %2d. %-50s %,12d terms (%5.2f%%)\n",
                        i + 1, truncated, ftc.termCount, ftc.percentage));
            }
            sb.append("═══════════════════════════════════════════════════════════════\n");
            
            return sb.toString();
            
        } finally {
            if (reader != null) reader.close();
            if (dir != null) dir.close();
        }
    }

    private File findIndexDirectory(String indexPath) {
        // Try to find index directory by name pattern
        File indexBaseDir = new File(repositoryHome, "repository/index");
        if (!indexBaseDir.exists()) {
            return null;
        }

        File[] dirs = indexBaseDir.listFiles();
        if (dirs != null) {
            String searchName = indexPath.replace("/oak:index/", "").replace("/", "_");
            for (File dir : dirs) {
                if (dir.getName().contains(searchName)) {
                    // Verify it's actually a Lucene index, not just metadata
                    if (isValidLuceneIndex(dir)) {
                        return dir;
                    }
                }
            }
        }
        return null;
    }
    
    /**
     * Gets the actual directory containing the Lucene index files.
     * Oak's IndexCopier stores the index in a "data" subdirectory.
     */
    private File getActualIndexDirectory(File indexDir) {
        // Check if there's a "data" subdirectory (Oak IndexCopier structure)
        File dataDir = new File(indexDir, "data");
        if (dataDir.exists() && dataDir.isDirectory() && hasSegmentsFile(dataDir)) {
            return dataDir;
        }
        
        // Otherwise use the directory as-is
        return indexDir;
    }

    /**
     * Checks if a directory contains a valid Lucene index.
     * A valid index must have a segments file (segments_N).
     * Oak's IndexCopier often stores the actual index in a "data" subdirectory.
     */
    private boolean isValidLuceneIndex(File dir) {
        if (!dir.isDirectory()) {
            return false;
        }
        
        // Check root directory first
        if (hasSegmentsFile(dir)) {
            return true;
        }
        
        // Check "data" subdirectory (common Oak IndexCopier structure)
        File dataDir = new File(dir, "data");
        if (dataDir.exists() && dataDir.isDirectory()) {
            if (hasSegmentsFile(dataDir)) {
                return true;
            }
        }
        
        // No segments file found - this is likely just metadata
        log.debug("Directory {} does not contain a valid Lucene index (no segments file)", dir);
        return false;
    }
    
    /**
     * Checks if a directory contains a segments file.
     */
    private boolean hasSegmentsFile(File dir) {
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) {
            return false;
        }
        
        for (File file : files) {
            String name = file.getName();
            if (name.startsWith("segments") && !name.equals("segments.gen")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Manually counts terms in a Terms object.
     * Required when Terms.size() returns -1 (unknown).
     * This is EXPENSIVE - O(t) where t = number of terms.
     */
    private long countTermsManually(Terms terms) throws IOException {
        long count = 0;
        TermsEnum te = terms.iterator(null);
        while (te.next() != null) {
            count++;
        }
        return count;
    }
    
    private long getFolderSize(File folder) {
        long size = 0;
        File[] files = folder.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile()) {
                    size += file.length();
                } else if (file.isDirectory()) {
                    size += getFolderSize(file);
                }
            }
        }
        return size;
    }
    
    @Override
    public String getFulltextStats(String indexPath) throws IOException {
        File indexDir = findIndexDirectory(indexPath);
        if (indexDir == null) {
            return "ERROR: Index not found: " + indexPath;
        }

        File actualIndexDir = getActualIndexDirectory(indexDir);
        if (!isValidLuceneIndex(actualIndexDir)) {
            return "ERROR: Not a valid Lucene index: " + actualIndexDir.getAbsolutePath();
        }

        Directory dir = null;
        IndexReader reader = null;
        
        try {
            dir = FSDirectory.open(actualIndexDir);
            reader = DirectoryReader.open(dir);
            
            // Analyze :fulltext field
            Fields fields = MultiFields.getFields(reader);
            if (fields == null) {
                return "ERROR: No fields found in index";
            }
            
            Terms fulltextTerms = fields.terms(":fulltext");
            if (fulltextTerms == null) {
                return formatNoFulltextReport(indexPath, indexDir, reader);
            }
            
            // Get term count for :fulltext field
            long termCount = fulltextTerms.size();
            if (termCount == -1) {
                log.debug("Counting :fulltext terms manually (size() returned -1)");
                termCount = countTermsManually(fulltextTerms);
            }
            
            // Estimate stored text size (rough approximation)
            // Average term length ~20 bytes, stored fulltext has overhead
            long estimatedStoredSize = termCount * 100; // Conservative estimate
            
            // Get index size on disk
            long indexSizeBytes = getFolderSize(indexDir);
            
            // Estimate backup time (reading from index)
            // Rough estimate: 1GB takes ~30 seconds to read and write
            long backupTimeSeconds = estimatedStoredSize / (1024 * 1024 * 1024 / 30);
            if (backupTimeSeconds < 60) backupTimeSeconds = 60; // Minimum 1 minute
            
            // Format report
            StringBuilder sb = new StringBuilder();
            sb.append("═══════════════════════════════════════════════════════════════\n");
            sb.append("FULLTEXT INDEX STATISTICS\n");
            sb.append("═══════════════════════════════════════════════════════════════\n");
            sb.append(String.format("Index: %s\n", indexPath));
            sb.append(String.format("Location: %s\n", indexDir.getAbsolutePath()));
            sb.append("\n");
            
            sb.append("FULLTEXT FIELD ANALYSIS:\n");
            sb.append(String.format("  Field: :fulltext\n"));
            sb.append(String.format("  Total Terms: %,d\n", termCount));
            sb.append(String.format("  Estimated Stored Text Size: %.1f MB\n", estimatedStoredSize / (1024.0 * 1024.0)));
            sb.append("\n");
            
            sb.append("INDEX SIZE:\n");
            sb.append(String.format("  Total Index Size on Disk: %.1f MB\n", indexSizeBytes / (1024.0 * 1024.0)));
            sb.append("\n");
            
            sb.append("BACKUP ESTIMATE:\n");
            sb.append(String.format("  Time to backup: ~%d minutes (read from Lucene index)\n", backupTimeSeconds / 60));
            sb.append(String.format("  Disk space needed: ~%.1f MB (stored text + overhead)\n", estimatedStoredSize / (1024.0 * 1024.0) * 1.2));
            sb.append("\n");
            
            // Recommendation
            if (termCount > 100000) {
                sb.append("RECOMMENDATION:\n");
                sb.append("  ✅ Backup RECOMMENDED - will save 100x time during re-indexing\n");
                sb.append("  ⚠️  Large fulltext index detected. Pre-extracted cache will significantly\n");
                sb.append("     accelerate re-indexing by eliminating binary extraction time.\n");
            } else {
                sb.append("RECOMMENDATION:\n");
                sb.append("  ℹ️  Fulltext index is relatively small. Backup may not be necessary\n");
                sb.append("     unless re-indexing frequently.\n");
            }
            
            sb.append("═══════════════════════════════════════════════════════════════\n");
            
            return sb.toString();
            
        } finally {
            if (reader != null) reader.close();
            if (dir != null) dir.close();
        }
    }
    
    private String formatNoFulltextReport(String indexPath, File indexDir, IndexReader reader) throws IOException {
        // Count fields manually for Lucene 4.7.2
        int fieldCount = 0;
        Fields fields = MultiFields.getFields(reader);
        if (fields != null) {
            for (String fieldName : fields) {
                fieldCount++;
            }
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("═══════════════════════════════════════════════════════════════\n");
        sb.append("FULLTEXT INDEX STATISTICS\n");
        sb.append("═══════════════════════════════════════════════════════════════\n");
        sb.append(String.format("Index: %s\n", indexPath));
        sb.append(String.format("Location: %s\n", indexDir.getAbsolutePath()));
        sb.append("\n");
        sb.append("FULLTEXT FIELD ANALYSIS:\n");
        sb.append("  ⚠️  No :fulltext field found in this index\n");
        sb.append("\n");
        sb.append(String.format("  Total Documents: %,d\n", reader.numDocs()));
        sb.append(String.format("  Total Fields: %,d\n", fieldCount));
        sb.append("\n");
        sb.append("RECOMMENDATION:\n");
        sb.append("  ℹ️  This index does not contain fulltext data.\n");
        sb.append("     Fulltext backup not applicable.\n");
        sb.append("═══════════════════════════════════════════════════════════════\n");
        return sb.toString();
    }

    private TabularData createDummyTabularData(String key, String value) throws OpenDataException {
        CompositeType rowType = new CompositeType(
                "Row", "Row",
                new String[]{"Key", "Value"},
                new String[]{"Key", "Value"},
                new OpenType[]{SimpleType.STRING, SimpleType.STRING}
        );
        TabularType tableType = new TabularType("Data", "Data", rowType, new String[]{"Key"});
        TabularDataSupport tds = new TabularDataSupport(tableType);
        tds.put(new CompositeDataSupport(rowType, new String[]{"Key", "Value"}, new Object[]{key, value}));
        return tds;
    }

    private TabularData stringsToTabularData(List<String> strings) throws OpenDataException {
        CompositeType rowType = new CompositeType(
                "Row", "Row",
                new String[]{"Value"},
                new String[]{"Value"},
                new OpenType[]{SimpleType.STRING}
        );
        TabularType tableType = new TabularType("Results", "Results", rowType, new String[]{"Value"});
        TabularDataSupport tds = new TabularDataSupport(tableType);
        for (String s : strings) {
            tds.put(new CompositeDataSupport(rowType, new String[]{"Value"}, new Object[]{s}));
        }
        return tds;
    }
    
    // ============================================================
    // FULLTEXT BACKUP OPERATIONS (Phase 2)
    // ============================================================
    
    @Override
    public String startFulltextBackup(String storePath, String indexPath) throws IOException {
        // Validate store path
        if (storePath == null || storePath.trim().isEmpty()) {
            return "ERROR: storePath is required (e.g., /opt/aem/fulltext-store)";
        }
        
        // Check if store path is writable
        File storeDir = new File(storePath);
        if (storeDir.exists()) {
            if (!storeDir.isDirectory()) {
                return "ERROR: storePath exists but is not a directory: " + storePath;
            }
            if (!storeDir.canWrite()) {
                return "ERROR: storePath is not writable: " + storePath;
            }
        } else {
            // Try to create
            File parentDir = storeDir.getParentFile();
            if (parentDir != null && !parentDir.canWrite()) {
                return "ERROR: Cannot create storePath (parent not writable): " + storePath;
            }
        }
        
        // Find index
        File indexDir = findIndexDirectory(indexPath);
        if (indexDir == null) {
            return "ERROR: Index not found: " + indexPath;
        }
        
        File actualIndexDir = getActualIndexDirectory(indexDir);
        if (!isValidLuceneIndex(actualIndexDir)) {
            return "ERROR: Not a valid Lucene index: " + actualIndexDir.getAbsolutePath();
        }
        
        // Generate job ID
        String jobId = "backup-" + System.currentTimeMillis();
        
        // Check for existing running job for same index
        for (FulltextBackupJob existingJob : backupJobs.values()) {
            if (existingJob.getIndexPath().equals(indexPath) && 
                existingJob.getStatus() == FulltextBackupJob.Status.RUNNING) {
                return "ERROR: A backup job is already running for this index. Job ID: " + existingJob.getJobId() + 
                       "\nUse cancelFulltextBackup(\"" + existingJob.getJobId() + "\") to cancel it first.";
            }
        }
        
        // Create and start job
        FulltextBackupJob job = new FulltextBackupJob(jobId, storePath, indexPath, actualIndexDir);
        backupJobs.put(jobId, job);
        job.start();
        
        log.info("Started fulltext backup job: {} for index: {} to store: {}", jobId, indexPath, storePath);
        
        StringBuilder sb = new StringBuilder();
        sb.append("═══════════════════════════════════════════════════════════════\n");
        sb.append("FULLTEXT BACKUP STARTED\n");
        sb.append("═══════════════════════════════════════════════════════════════\n");
        sb.append(String.format("Job ID: %s\n", jobId));
        sb.append(String.format("Index: %s\n", indexPath));
        sb.append(String.format("Store Path: %s\n", storePath));
        sb.append("\n");
        sb.append("Monitor progress with:\n");
        sb.append(String.format("  getFulltextBackupProgress(\"%s\")\n", jobId));
        sb.append("\n");
        sb.append("Cancel with:\n");
        sb.append(String.format("  cancelFulltextBackup(\"%s\")\n", jobId));
        sb.append("═══════════════════════════════════════════════════════════════\n");
        
        return sb.toString();
    }
    
    @Override
    public String getFulltextBackupProgress(String jobId) throws IOException {
        if (jobId == null || jobId.trim().isEmpty()) {
            return "ERROR: jobId is required. Use listFulltextBackupJobs() to see available jobs.";
        }
        
        FulltextBackupJob job = backupJobs.get(jobId);
        if (job == null) {
            StringBuilder sb = new StringBuilder();
            sb.append("ERROR: Job not found: " + jobId + "\n\n");
            sb.append("Available jobs:\n");
            if (backupJobs.isEmpty()) {
                sb.append("  (none)\n");
            } else {
                for (FulltextBackupJob j : backupJobs.values()) {
                    sb.append(String.format("  %s (%s) - %s\n", j.getJobId(), j.getStatus(), j.getIndexPath()));
                }
            }
            return sb.toString();
        }
        
        return job.formatProgress();
    }
    
    @Override
    public String cancelFulltextBackup(String jobId) throws IOException {
        if (jobId == null || jobId.trim().isEmpty()) {
            return "ERROR: jobId is required.";
        }
        
        FulltextBackupJob job = backupJobs.get(jobId);
        if (job == null) {
            return "ERROR: Job not found: " + jobId;
        }
        
        if (job.getStatus() != FulltextBackupJob.Status.RUNNING && 
            job.getStatus() != FulltextBackupJob.Status.PENDING) {
            return "Cannot cancel job - current status: " + job.getStatus();
        }
        
        job.cancel();
        
        StringBuilder sb = new StringBuilder();
        sb.append("═══════════════════════════════════════════════════════════════\n");
        sb.append("CANCELLATION REQUESTED\n");
        sb.append("═══════════════════════════════════════════════════════════════\n");
        sb.append(String.format("Job ID: %s\n", jobId));
        sb.append("Status: Cancellation requested\n");
        sb.append("\n");
        sb.append("The job will stop at the next safe point.\n");
        sb.append("Check progress for final status:\n");
        sb.append(String.format("  getFulltextBackupProgress(\"%s\")\n", jobId));
        sb.append("═══════════════════════════════════════════════════════════════\n");
        
        return sb.toString();
    }
    
    @Override
    public String[] listFulltextBackupJobs() throws IOException {
        List<String> result = new ArrayList<>();
        
        if (backupJobs.isEmpty()) {
            result.add("No fulltext backup jobs found.");
            result.add("");
            result.add("To start a backup:");
            result.add("  startFulltextBackup(\"/opt/aem/fulltext-store\", \"/oak:index/damAssetLucene\")");
            return result.toArray(new String[0]);
        }
        
        result.add("═══════════════════════════════════════════════════════════════");
        result.add("FULLTEXT BACKUP JOBS");
        result.add("═══════════════════════════════════════════════════════════════");
        result.add("");
        
        for (FulltextBackupJob job : backupJobs.values()) {
            String statusEmoji = "";
            switch (job.getStatus()) {
                case RUNNING: statusEmoji = "🔄"; break;
                case COMPLETED: statusEmoji = "✅"; break;
                case CANCELLED: statusEmoji = "⏹️"; break;
                case FAILED: statusEmoji = "❌"; break;
                case PENDING: statusEmoji = "⏳"; break;
            }
            
            result.add(String.format("%s Job: %s", statusEmoji, job.getJobId()));
            result.add(String.format("   Status: %s", job.getStatus()));
            result.add(String.format("   Index: %s", job.getIndexPath()));
            result.add(String.format("   Store: %s", job.getStorePath()));
            result.add(String.format("   Progress: %.1f%% (%,d/%,d docs)", 
                    job.getProgressPercent(), job.getDocumentsProcessed(), job.getDocumentsTotal()));
            result.add(String.format("   Files Written: %,d", job.getFilesWritten()));
            result.add(String.format("   Size: %.1f MB", job.getBytesWritten() / (1024.0 * 1024.0)));
            result.add("");
        }
        
        result.add("═══════════════════════════════════════════════════════════════");
        
        return result.toArray(new String[0]);
    }
}

