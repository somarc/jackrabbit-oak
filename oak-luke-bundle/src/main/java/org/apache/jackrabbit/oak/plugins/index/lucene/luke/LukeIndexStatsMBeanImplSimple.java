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

import static org.apache.jackrabbit.oak.commons.IOUtils.humanReadableByteCount;

/**
 * Simplified implementation for Oak 1.22.x that works WITHOUT IndexTracker.
 * Uses IndexCopier and direct filesystem access only.
 */
public class LukeIndexStatsMBeanImplSimple extends AnnotatedStandardMBean implements LukeIndexStatsMBean {

    private static final Logger log = LoggerFactory.getLogger(LukeIndexStatsMBeanImplSimple.class);

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

        List<String> results = new ArrayList<>();
        Directory dir = null;
        IndexReader reader = null;
        
        try {
            dir = FSDirectory.open(indexDir);
            reader = DirectoryReader.open(dir);
            
            Fields fields = MultiFields.getFields(reader);
            if (fields != null) {
                int count = 0;
                for (String fieldName : fields) {
                    if (count++ >= maxFields) break;
                    Terms terms = fields.terms(fieldName);
                    results.add(String.format("Field: %s, Terms: %d",
                            fieldName,
                            terms != null ? terms.size() : 0));
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

        List<String> results = new ArrayList<>();
        Directory dir = null;
        IndexReader reader = null;
        
        try {
            dir = FSDirectory.open(indexDir);
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

        Directory dir = null;
        IndexReader reader = null;
        
        try {
            dir = FSDirectory.open(indexDir);
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

        Directory dir = null;
        try {
            dir = FSDirectory.open(indexDir);
            DirectoryReader reader = DirectoryReader.open(dir);
            int numDocs = reader.numDocs();
            reader.close();
            return String.format("OK: Valid Lucene index with %,d documents at %s", numDocs, indexDir.getAbsolutePath());
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

        List<String> results = new ArrayList<>();
        Directory dir = null;
        IndexReader reader = null;
        
        try {
            dir = FSDirectory.open(indexDir);
            reader = DirectoryReader.open(dir);
            
            // Count terms per field (adapted from LUKE's IndexInfo)
            Map<String, FieldTermCount> fieldCounts = new HashMap<>();
            long totalTerms = 0;
            
            Fields fields = MultiFields.getFields(reader);
            if (fields != null) {
                for (String fieldName : fields) {
                    Terms terms = fields.terms(fieldName);
                    if (terms != null) {
                        long termCount = terms.size();
                        fieldCounts.put(fieldName, new FieldTermCount(fieldName, termCount));
                        totalTerms += termCount;
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

        List<String> results = new ArrayList<>();
        Directory dir = null;
        IndexReader reader = null;
        
        try {
            dir = FSDirectory.open(indexDir);
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

        Directory dir = null;
        IndexReader reader = null;
        
        try {
            dir = FSDirectory.open(indexDir);
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
     * Checks if a directory contains a valid Lucene index.
     * A valid index must have a segments file (segments_N).
     */
    private boolean isValidLuceneIndex(File dir) {
        if (!dir.isDirectory()) {
            return false;
        }
        
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) {
            return false;
        }
        
        // Look for segments file (segments_N or segments.gen)
        for (File file : files) {
            String name = file.getName();
            if (name.startsWith("segments") && !name.equals("segments.gen")) {
                return true;
            }
        }
        
        // No segments file found - this is likely just metadata
        log.debug("Directory {} does not contain a valid Lucene index (no segments file)", dir);
        return false;
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
}

