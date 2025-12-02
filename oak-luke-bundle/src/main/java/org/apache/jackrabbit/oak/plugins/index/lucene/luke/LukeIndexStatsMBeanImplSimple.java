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
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;

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
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.FieldInfos;
import org.apache.lucene.index.Fields;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.MultiFields;
import org.apache.lucene.index.SegmentInfos;
import org.apache.lucene.index.SegmentCommitInfo;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.jackrabbit.oak.commons.IOUtils.humanReadableByteCount;

/**
 * Best-in-class LUKE-style Lucene index inspection for Oak/AEM.
 * 
 * <p>Designed to answer: "What the heck is in my index? What's taking up space?"</p>
 * 
 * <p>Works with Oak 1.22.x (AEM 6.5.x) using direct Lucene API access.</p>
 * 
 * <p><b>Note:</b> For fulltext backup, use oak-run tika commands which can run while AEM is online.</p>
 */
public class LukeIndexStatsMBeanImplSimple extends AnnotatedStandardMBean implements LukeIndexStatsMBean {

    private static final Logger log = LoggerFactory.getLogger(LukeIndexStatsMBeanImplSimple.class);

    @SuppressWarnings("unused")  // May be used in future enhancements
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

    // ============================================================
    // JAVA 8 COMPATIBILITY HELPERS
    // ============================================================
    
    /** Java 8 compatible String.repeat() alternative */
    private static String repeatStr(String s, int count) {
        if (count <= 0) return "";
        StringBuilder sb = new StringBuilder(s.length() * count);
        for (int i = 0; i < count; i++) {
            sb.append(s);
        }
        return sb.toString();
    }
    
    /** Java 8 compatible char repeat */
    private static String repeatChar(char c, int count) {
        if (count <= 0) return "";
        char[] chars = new char[count];
        Arrays.fill(chars, c);
        return new String(chars);
    }

    // ============================================================
    // INDEX DISCOVERY & NAVIGATION
    // ============================================================

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

            File indexBaseDir = new File(repositoryHome, "repository/index");
            if (indexBaseDir.exists() && indexBaseDir.isDirectory()) {
                File[] indexDirs = indexBaseDir.listFiles();
                if (indexDirs != null) {
                    for (File indexDir : indexDirs) {
                        if (indexDir.isDirectory()) {
                            try {
                                boolean isValid = isValidLuceneIndex(indexDir);
                                long size = getFolderSize(indexDir);
                                
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
    public String getLocalIndexPath(String indexPath) {
        File indexDir = findIndexDirectory(indexPath);
        return indexDir != null ? indexDir.getAbsolutePath() : "Not found";
    }

    @Override
    public String validateLocalIndex(String indexPath) throws IOException {
        File indexDir = findIndexDirectory(indexPath);
        if (indexDir == null) {
            return "ERROR: Index not found: " + indexPath + 
                   "\nTip: Use getLocalIndexDirectories() to see available indexes";
        }

        if (!isValidLuceneIndex(indexDir)) {
            return "ERROR: Directory exists but does not contain valid Lucene index (no segments file): " + 
                   indexDir.getAbsolutePath();
        }

        File actualIndexDir = getActualIndexDirectory(indexDir);
        Directory dir = null;
        try {
            dir = FSDirectory.open(actualIndexDir);
            DirectoryReader reader = DirectoryReader.open(dir);
            int numDocs = reader.numDocs();
            reader.close();
            return String.format("OK: Valid Lucene index with %,d documents\nPath: %s", 
                    numDocs, actualIndexDir.getAbsolutePath());
        } catch (Exception e) {
            return String.format("ERROR: Failed to open index\nPath: %s\nReason: %s", 
                    indexDir.getAbsolutePath(), e.getMessage());
        } finally {
            if (dir != null) dir.close();
        }
    }

    // ============================================================
    // BASIC INDEX STATISTICS
    // ============================================================

    @Override
    public String getLukeBasicStats(String indexPath) throws IOException {
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
            
            Fields fields = MultiFields.getFields(reader);
            int fieldCount = 0;
            if (fields != null) {
                for (@SuppressWarnings("unused") String f : fields) {
                    fieldCount++;
                }
            }
            
            long indexSize = getFolderSize(indexDir);
            
            StringBuilder sb = new StringBuilder();
            sb.append("===============================================================\n");
            sb.append("LUCENE INDEX OVERVIEW\n");
            sb.append("===============================================================\n");
            sb.append(String.format("Index: %s\n", indexPath));
            sb.append(String.format("Path: %s\n", actualIndexDir.getAbsolutePath()));
            sb.append(String.format("Size: %s\n", humanReadableByteCount(indexSize)));
            sb.append("\n");
            sb.append("DOCUMENT STATISTICS:\n");
            sb.append(String.format("  Total Documents: %,d\n", reader.numDocs()));
            sb.append(String.format("  Max Doc ID: %,d\n", reader.maxDoc()));
            sb.append(String.format("  Deleted Docs: %,d\n", reader.numDeletedDocs()));
            if (reader.maxDoc() > 0) {
                double deletionRatio = (reader.numDeletedDocs() * 100.0) / reader.maxDoc();
                sb.append(String.format("  Deletion Ratio: %.1f%%\n", deletionRatio));
            }
            sb.append("\n");
            sb.append(String.format("Field Count: %,d\n", fieldCount));
            sb.append("===============================================================\n");
            
            return sb.toString();
        } finally {
            if (reader != null) reader.close();
            if (dir != null) dir.close();
        }
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
                        if (termCount == -1) {
                            log.debug("Counting terms manually for field: {}", fieldName);
                            termCount = countTermsManually(terms);
                        }
                        
                        fieldCounts.put(fieldName, new FieldTermCount(fieldName, termCount));
                        totalTerms += termCount;
                    }
                }
            }
            
            List<FieldTermCount> sorted = new ArrayList<>(fieldCounts.values());
            Collections.sort(sorted);
            List<FieldTermCount> top10 = sorted.subList(0, Math.min(10, sorted.size()));
            
            for (FieldTermCount ftc : top10) {
                ftc.percentage = totalTerms > 0 ? (ftc.termCount * 100.0 / totalTerms) : 0.0;
            }
            
            long indexSize = getFolderSize(indexDir);
            
            StringBuilder sb = new StringBuilder();
            sb.append("===============================================================\n");
            sb.append("INDEX COMPOSITION ANALYSIS\n");
            sb.append("===============================================================\n");
            sb.append(String.format("Index: %s\n", indexPath));
            sb.append(String.format("Size: %s\n", humanReadableByteCount(indexSize)));
            sb.append("\n");
            sb.append("DOCUMENT STATISTICS:\n");
            sb.append(String.format("  Documents: %,d\n", reader.numDocs()));
            sb.append(String.format("  Deletions: %,d (%.1f%%)\n", 
                    reader.numDeletedDocs(),
                    reader.maxDoc() > 0 ? (reader.numDeletedDocs() * 100.0 / reader.maxDoc()) : 0));
            sb.append("\n");
            sb.append("FIELD STATISTICS:\n");
            sb.append(String.format("  Total Fields: %d\n", fieldCount));
            sb.append(String.format("  Total Terms: %,d\n", totalTerms));
            sb.append(String.format("  Avg Terms/Field: %,d\n", fieldCount > 0 ? totalTerms / fieldCount : 0));
            sb.append("\n");
            sb.append("TOP 10 LARGEST FIELDS (by term count):\n");
            sb.append(String.format("  %-50s %15s %8s\n", "Field", "Terms", "%"));
            sb.append("  " + repeatChar('-', 75) + "\n");
            for (int i = 0; i < top10.size(); i++) {
                FieldTermCount ftc = top10.get(i);
                String truncated = ftc.fieldName.length() > 48 ? 
                        ftc.fieldName.substring(0, 45) + "..." : ftc.fieldName;
                sb.append(String.format("  %-50s %,15d %7.2f%%\n",
                        truncated, ftc.termCount, ftc.percentage));
            }
            sb.append("===============================================================\n");
            
            return sb.toString();
            
        } finally {
            if (reader != null) reader.close();
            if (dir != null) dir.close();
        }
    }

    // ============================================================
    // FIELD ANALYSIS
    // ============================================================

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
                        if (termCount == -1) {
                            termCount = countTermsManually(terms);
                        }
                        results.add(String.format("Field: %-50s Terms: %,15d", 
                                fieldName.length() > 50 ? fieldName.substring(0, 47) + "..." : fieldName, 
                                termCount));
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
            
            Map<String, FieldTermCount> fieldCounts = new HashMap<>();
            long totalTerms = 0;
            
            Fields fields = MultiFields.getFields(reader);
            if (fields != null) {
                for (String fieldName : fields) {
                    Terms terms = fields.terms(fieldName);
                    if (terms != null) {
                        long termCount = terms.size();
                        if (termCount == -1) {
                            log.debug("Counting terms manually for field: {}", fieldName);
                            termCount = countTermsManually(terms);
                        }
                        fieldCounts.put(fieldName, new FieldTermCount(fieldName, termCount));
                        totalTerms += termCount;
                    }
                }
            }
            
            for (FieldTermCount ftc : fieldCounts.values()) {
                ftc.percentage = totalTerms > 0 ? (ftc.termCount * 100.0 / totalTerms) : 0.0;
            }
            
            List<FieldTermCount> sorted = new ArrayList<>(fieldCounts.values());
            Collections.sort(sorted);
            
            results.add("=========================================================================================");
            results.add(String.format("FIELD SIZE ANALYSIS: %s", indexPath));
            results.add(String.format("Total Terms: %,d across %d fields", totalTerms, fieldCounts.size()));
            results.add("=========================================================================================");
            results.add(String.format("%-55s | %15s | %8s", "Field Name", "Term Count", "% Total"));
            results.add(repeatChar('-', 87));
            
            int count = 0;
            for (FieldTermCount ftc : sorted) {
                if (count++ >= maxFields) break;
                results.add(ftc.toString());
            }
            
            results.add("=========================================================================================");
            
        } finally {
            if (reader != null) reader.close();
            if (dir != null) dir.close();
        }
        
        return results.toArray(new String[0]);
    }

    @Override
    public String[] getFieldFlags(String indexPath) throws IOException {
        File indexDir = findIndexDirectory(indexPath);
        if (indexDir == null) {
            return new String[]{"ERROR: Index not found: " + indexPath};
        }

        File actualIndexDir = getActualIndexDirectory(indexDir);
        List<String> results = new ArrayList<>();
        Directory dir = null;
        DirectoryReader reader = null;
        
        try {
            dir = FSDirectory.open(actualIndexDir);
            reader = DirectoryReader.open(dir);
            
            results.add("=========================================================================================");
            results.add("FIELD FLAGS ANALYSIS");
            results.add("=========================================================================================");
            results.add("Legend: I=Indexed, T=Tokenized, D=DocValues, V=Vectors, N=Norms, P=Payloads");
            results.add("");
            results.add(String.format("%-50s | %s", "Field Name", "Flags"));
            results.add(repeatChar('-', 70));
            
            Set<String> seenFields = new HashSet<>();
            for (int i = 0; i < reader.leaves().size(); i++) {
                FieldInfos fieldInfos = reader.leaves().get(i).reader().getFieldInfos();
                for (FieldInfo info : fieldInfos) {
                    if (seenFields.contains(info.name)) continue;
                    seenFields.add(info.name);
                    
                    StringBuilder flags = new StringBuilder();
                    flags.append(info.isIndexed() ? "I" : "-");
                    flags.append(info.isIndexed() && info.getIndexOptions() != null ? "T" : "-");
                    flags.append(info.hasDocValues() ? "D" : "-");
                    flags.append(info.hasVectors() ? "V" : "-");
                    flags.append(info.hasNorms() ? "N" : "-");
                    flags.append(info.hasPayloads() ? "P" : "-");
                    
                    String fieldName = info.name.length() > 48 ? 
                            info.name.substring(0, 45) + "..." : info.name;
                    results.add(String.format("%-50s | %s", fieldName, flags.toString()));
                }
            }
            
            results.add("=========================================================================================");
            
        } finally {
            if (reader != null) reader.close();
            if (dir != null) dir.close();
        }
        
        return results.toArray(new String[0]);
    }

    // ============================================================
    // TERM ANALYSIS
    // ============================================================

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
            if (terms == null) {
                return new String[]{"ERROR: Field not found: " + fieldName};
            }
            
            results.add("=========================================================================================");
            results.add(String.format("TERM STATISTICS: %s / %s", indexPath, fieldName));
            results.add("=========================================================================================");
            results.add(String.format("%-50s | %12s | %12s", "Term", "Doc Freq", "Total Freq"));
            results.add(repeatChar('-', 80));
            
            TermsEnum termsEnum = terms.iterator(null);
            BytesRef term;
            int count = 0;
            while ((term = termsEnum.next()) != null && count++ < maxTerms) {
                String termText = term.utf8ToString();
                if (termText.length() > 48) {
                    termText = termText.substring(0, 45) + "...";
                }
                termText = termText.replaceAll("[\\p{Cntrl}]", "?");
                
                results.add(String.format("%-50s | %,12d | %,12d",
                        termText,
                        termsEnum.docFreq(),
                        termsEnum.totalTermFreq()));
            }
            
            results.add("=========================================================================================");
            
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
            
            List<TermInfo> topTerms = new ArrayList<>();
            
            Fields fields = MultiFields.getFields(reader);
            if (fields != null) {
                for (String field : fields) {
                    if (fieldName != null && !fieldName.isEmpty() && !field.equals(fieldName)) {
                        continue;
                    }
                    
                    Terms terms = fields.terms(field);
                    if (terms != null) {
                        TermsEnum termsEnum = terms.iterator(null);
                        BytesRef term;
                        while ((term = termsEnum.next()) != null) {
                            int docFreq = termsEnum.docFreq();
                            topTerms.add(new TermInfo(field, term.utf8ToString(), docFreq, termsEnum.totalTermFreq()));
                            
                            if (topTerms.size() > maxTerms * 2) {
                                Collections.sort(topTerms);
                                topTerms = new ArrayList<>(topTerms.subList(0, maxTerms));
                            }
                        }
                    }
                }
            }
            
            Collections.sort(topTerms);
            if (topTerms.size() > maxTerms) {
                topTerms = topTerms.subList(0, maxTerms);
            }
            
            String scope = (fieldName != null && !fieldName.isEmpty()) ? fieldName : "All Fields";
            results.add("=========================================================================================");
            results.add(String.format("TOP TERMS BY DOC FREQUENCY: %s", indexPath));
            results.add(String.format("Scope: %s | Showing top %d", scope, Math.min(maxTerms, topTerms.size())));
            results.add("=========================================================================================");
            results.add(String.format("%-35s | %-35s | %12s", "Field", "Term", "Doc Freq"));
            results.add(repeatChar('-', 90));
            
            for (TermInfo ti : topTerms) {
                String truncatedField = ti.field.length() > 33 ? ti.field.substring(0, 30) + "..." : ti.field;
                String truncatedTerm = ti.term.length() > 33 ? ti.term.substring(0, 30) + "..." : ti.term;
                truncatedTerm = truncatedTerm.replaceAll("[\\p{Cntrl}]", "?");
                results.add(String.format("%-35s | %-35s | %,12d", truncatedField, truncatedTerm, ti.docFreq));
            }
            
            results.add("=========================================================================================");
            
        } finally {
            if (reader != null) reader.close();
            if (dir != null) dir.close();
        }
        
        return results.toArray(new String[0]);
    }

    @Override
    public String[] getTermDistribution(String indexPath, String fieldName, int buckets) throws IOException {
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
            if (terms == null) {
                return new String[]{"ERROR: Field not found: " + fieldName};
            }
            
            List<Integer> docFreqs = new ArrayList<>();
            TermsEnum termsEnum = terms.iterator(null);
            int totalTerms = 0;
            int maxDocFreq = 0;
            long totalDocFreq = 0;
            
            while (termsEnum.next() != null) {
                int df = termsEnum.docFreq();
                docFreqs.add(df);
                totalTerms++;
                maxDocFreq = Math.max(maxDocFreq, df);
                totalDocFreq += df;
            }
            
            if (totalTerms == 0) {
                return new String[]{"No terms found in field: " + fieldName};
            }
            
            int[] bucketCounts = new int[buckets];
            double logMax = Math.log10(maxDocFreq + 1);
            
            for (int df : docFreqs) {
                double logDf = Math.log10(df + 1);
                int bucket = (int) ((logDf / logMax) * (buckets - 1));
                bucket = Math.min(bucket, buckets - 1);
                bucketCounts[bucket]++;
            }
            
            int maxBucket = 0;
            for (int count : bucketCounts) {
                maxBucket = Math.max(maxBucket, count);
            }
            
            results.add("=========================================================================================");
            results.add(String.format("TERM FREQUENCY DISTRIBUTION (ZIPF ANALYSIS): %s / %s", indexPath, fieldName));
            results.add("=========================================================================================");
            results.add(String.format("Total Unique Terms: %,d", totalTerms));
            results.add(String.format("Max Doc Frequency: %,d", maxDocFreq));
            results.add(String.format("Avg Doc Frequency: %.1f", (double) totalDocFreq / totalTerms));
            results.add("");
            results.add("Distribution (log scale, left = rare terms, right = common terms):");
            results.add("");
            
            int barWidth = 40;
            for (int i = 0; i < buckets; i++) {
                int barLen = maxBucket > 0 ? (bucketCounts[i] * barWidth / maxBucket) : 0;
                double dfRangeStart = Math.pow(10, (double) i / buckets * logMax) - 1;
                double dfRangeEnd = Math.pow(10, (double) (i + 1) / buckets * logMax) - 1;
                
                String bar = repeatStr("#", barLen) + repeatStr(".", barWidth - barLen);
                results.add(String.format("df %5.0f-%5.0f | %s | %,d terms", 
                        dfRangeStart, dfRangeEnd, bar, bucketCounts[i]));
            }
            
            results.add("");
            
            int rareTerms = 0;
            int commonTerms = 0;
            for (int df : docFreqs) {
                if (df <= 10) rareTerms++;
                else if (df > 100) commonTerms++;
            }
            
            double rarePercent = (rareTerms * 100.0) / totalTerms;
            double commonPercent = (commonTerms * 100.0) / totalTerms;
            
            results.add("INTERPRETATION:");
            results.add(String.format("  Rare terms (df <= 10): %,d (%.1f%%) - typical 'long tail'", rareTerms, rarePercent));
            results.add(String.format("  Common terms (df > 100): %,d (%.1f%%) - high-frequency vocabulary", commonTerms, commonPercent));
            
            if (rarePercent > 80) {
                results.add("  -> High long-tail distribution (typical for fulltext indexes)");
            } else if (commonPercent > 30) {
                results.add("  -> Many common terms (may indicate low-cardinality field)");
            }
            
            results.add("=========================================================================================");
            
        } finally {
            if (reader != null) reader.close();
            if (dir != null) dir.close();
        }
        
        return results.toArray(new String[0]);
    }

    // ============================================================
    // SEGMENT ANALYSIS
    // ============================================================

    @Override
    public String[] getSegmentInfo(String indexPath) throws IOException {
        File indexDir = findIndexDirectory(indexPath);
        if (indexDir == null) {
            return new String[]{"ERROR: Index not found: " + indexPath};
        }

        File actualIndexDir = getActualIndexDirectory(indexDir);
        List<String> results = new ArrayList<>();
        Directory dir = null;
        
        try {
            dir = FSDirectory.open(actualIndexDir);
            SegmentInfos segInfos = new SegmentInfos();
            segInfos.read(dir);
            
            results.add("=========================================================================================");
            results.add(String.format("SEGMENT ANALYSIS: %s", indexPath));
            results.add("=========================================================================================");
            results.add(String.format("Total Segments: %d", segInfos.size()));
            results.add(String.format("Lucene Version: %s", segInfos.getVersion()));
            results.add(String.format("Generation: %d", segInfos.getGeneration()));
            results.add("");
            results.add(String.format("%-30s | %12s | %12s | %8s", "Segment", "Docs", "Deletions", "Del %"));
            results.add(repeatChar('-', 70));
            
            long totalDocs = 0;
            long totalDeletes = 0;
            
            for (SegmentCommitInfo info : segInfos) {
                int docs = info.info.getDocCount();
                int deletes = info.getDelCount();
                totalDocs += docs;
                totalDeletes += deletes;
                
                double delPercent = docs > 0 ? (deletes * 100.0 / docs) : 0;
                String segName = info.info.name;
                if (segName.length() > 28) {
                    segName = segName.substring(0, 25) + "...";
                }
                
                results.add(String.format("%-30s | %,12d | %,12d | %7.1f%%", 
                        segName, docs, deletes, delPercent));
            }
            
            results.add(repeatChar('-', 70));
            double totalDelPercent = totalDocs > 0 ? (totalDeletes * 100.0 / totalDocs) : 0;
            results.add(String.format("%-30s | %,12d | %,12d | %7.1f%%", 
                    "TOTAL", totalDocs, totalDeletes, totalDelPercent));
            
            results.add("");
            
            int segmentCount = segInfos.size();
            if (segmentCount > 50) {
                results.add("WARNING: HIGH SEGMENT COUNT - Consider segment merge/optimization");
            } else if (segmentCount > 20) {
                results.add("INFO: Moderate segment count - May benefit from optimization");
            } else {
                results.add("OK: Healthy segment count");
            }
            
            if (totalDelPercent > 30) {
                results.add("WARNING: HIGH DELETION RATIO - Consider index optimization");
            } else if (totalDelPercent > 10) {
                results.add("INFO: Moderate deletions - Monitor over time");
            } else {
                results.add("OK: Low deletion ratio");
            }
            
            results.add("=========================================================================================");
            
        } finally {
            if (dir != null) dir.close();
        }
        
        return results.toArray(new String[0]);
    }

    // ============================================================
    // INDEX HEALTH & DIAGNOSTICS
    // ============================================================

    @Override
    public String getIndexHealthReport(String indexPath) throws IOException {
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
            
            SegmentInfos segInfos = new SegmentInfos();
            segInfos.read(dir);
            
            long indexSize = getFolderSize(indexDir);
            int numDocs = reader.numDocs();
            int maxDoc = reader.maxDoc();
            int deletions = reader.numDeletedDocs();
            double deletionRatio = maxDoc > 0 ? (deletions * 100.0 / maxDoc) : 0;
            int segmentCount = segInfos.size();
            
            int fieldCount = 0;
            Fields fields = MultiFields.getFields(reader);
            if (fields != null) {
                for (@SuppressWarnings("unused") String f : fields) {
                    fieldCount++;
                }
            }
            
            double avgDocsPerSegment = segmentCount > 0 ? (double) numDocs / segmentCount : 0;
            
            StringBuilder sb = new StringBuilder();
            sb.append("===============================================================\n");
            sb.append("INDEX HEALTH REPORT\n");
            sb.append("===============================================================\n");
            sb.append(String.format("Index: %s\n", indexPath));
            sb.append(String.format("Size: %s\n", humanReadableByteCount(indexSize)));
            sb.append("\n");
            
            int score = 100;
            List<String> issues = new ArrayList<>();
            List<String> recommendations = new ArrayList<>();
            
            if (deletionRatio > 30) {
                score -= 30;
                issues.add("HIGH: Deletion ratio: " + String.format("%.1f%%", deletionRatio));
                recommendations.add("-> Run index optimization to reclaim space");
            } else if (deletionRatio > 10) {
                score -= 10;
                issues.add("WARN: Moderate deletion ratio: " + String.format("%.1f%%", deletionRatio));
            }
            
            if (segmentCount > 50) {
                score -= 25;
                issues.add("HIGH: Too many segments: " + segmentCount);
                recommendations.add("-> Index is fragmented - consider segment merge");
            } else if (segmentCount > 20) {
                score -= 10;
                issues.add("WARN: High segment count: " + segmentCount);
            }
            
            if (fieldCount > 500) {
                score -= 15;
                issues.add("WARN: Many fields indexed: " + fieldCount);
                recommendations.add("-> Review if all fields are necessary");
            }
            
            double bytesPerDoc = numDocs > 0 ? (double) indexSize / numDocs : 0;
            if (bytesPerDoc > 1024 * 1024) {
                score -= 10;
                issues.add("WARN: Large average document size: " + humanReadableByteCount((long) bytesPerDoc) + "/doc");
                recommendations.add("-> Check for oversized stored fields or excessive fulltext");
            }
            
            String healthStatus;
            if (score >= 90) {
                healthStatus = "EXCELLENT";
            } else if (score >= 70) {
                healthStatus = "GOOD";
            } else if (score >= 50) {
                healthStatus = "NEEDS ATTENTION";
            } else {
                healthStatus = "POOR";
            }
            
            sb.append(String.format("HEALTH SCORE: %d/100 (%s)\n", score, healthStatus));
            sb.append("\n");
            
            sb.append("METRICS:\n");
            sb.append(String.format("  Documents: %,d\n", numDocs));
            sb.append(String.format("  Deletions: %,d (%.1f%%)\n", deletions, deletionRatio));
            sb.append(String.format("  Segments: %d\n", segmentCount));
            sb.append(String.format("  Fields: %d\n", fieldCount));
            sb.append(String.format("  Avg Docs/Segment: %,.0f\n", avgDocsPerSegment));
            sb.append(String.format("  Avg Size/Doc: %s\n", humanReadableByteCount((long) bytesPerDoc)));
            sb.append("\n");
            
            if (!issues.isEmpty()) {
                sb.append("ISSUES DETECTED:\n");
                for (String issue : issues) {
                    sb.append("  " + issue + "\n");
                }
                sb.append("\n");
            }
            
            if (!recommendations.isEmpty()) {
                sb.append("RECOMMENDATIONS:\n");
                for (String rec : recommendations) {
                    sb.append("  " + rec + "\n");
                }
                sb.append("\n");
            }
            
            if (issues.isEmpty()) {
                sb.append("OK: No issues detected - index appears healthy\n");
            }
            
            sb.append("===============================================================\n");
            
            return sb.toString();
            
        } finally {
            if (reader != null) reader.close();
            if (dir != null) dir.close();
        }
    }

    // ============================================================
    // DOCUMENT SAMPLING
    // ============================================================

    @Override
    public String[] sampleDocuments(String indexPath, int sampleSize, String showFields) throws IOException {
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
            
            int numDocs = reader.numDocs();
            if (numDocs == 0) {
                return new String[]{"Index is empty"};
            }
            
            Set<String> fieldsToShow = null;
            if (showFields != null && !showFields.isEmpty()) {
                fieldsToShow = new HashSet<>(Arrays.asList(showFields.split(",")));
            }
            
            Random rand = new Random();
            Set<Integer> sampledIds = new HashSet<>();
            int maxAttempts = sampleSize * 10;
            int attempts = 0;
            
            while (sampledIds.size() < sampleSize && sampledIds.size() < numDocs && attempts < maxAttempts) {
                int docId = rand.nextInt(reader.maxDoc());
                if (!reader.hasDeletions() || reader.document(docId) != null) {
                    sampledIds.add(docId);
                }
                attempts++;
            }
            
            results.add("=========================================================================================");
            results.add(String.format("DOCUMENT SAMPLES: %s", indexPath));
            results.add(String.format("Sampling %d of %,d documents", sampledIds.size(), numDocs));
            results.add("=========================================================================================");
            
            for (int docId : sampledIds) {
                results.add("");
                results.add(String.format("--- Document #%d ---", docId));
                
                Document doc = reader.document(docId);
                for (IndexableField field : doc.getFields()) {
                    if (fieldsToShow != null && !fieldsToShow.contains(field.name())) {
                        continue;
                    }
                    
                    String value = field.stringValue();
                    if (value != null) {
                        if (value.length() > 200) {
                            value = value.substring(0, 197) + "...";
                        }
                        value = value.replaceAll("[\\p{Cntrl}]", " ");
                        results.add(String.format("  %s: %s", field.name(), value));
                    } else if (field.binaryValue() != null) {
                        results.add(String.format("  %s: [binary, %d bytes]", field.name(), field.binaryValue().length));
                    } else if (field.numericValue() != null) {
                        results.add(String.format("  %s: %s", field.name(), field.numericValue()));
                    }
                }
            }
            
            results.add("");
            results.add("=========================================================================================");
            
        } finally {
            if (reader != null) reader.close();
            if (dir != null) dir.close();
        }
        
        return results.toArray(new String[0]);
    }

    @Override
    public String getDocument(String indexPath, int docId) throws IOException {
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
            
            if (docId < 0 || docId >= reader.maxDoc()) {
                return String.format("ERROR: Invalid doc ID %d (valid range: 0-%d)", docId, reader.maxDoc() - 1);
            }
            
            Document doc = reader.document(docId);
            if (doc == null) {
                return String.format("Document #%d is deleted", docId);
            }
            
            StringBuilder sb = new StringBuilder();
            sb.append("===============================================================\n");
            sb.append(String.format("DOCUMENT #%d\n", docId));
            sb.append("===============================================================\n");
            
            for (IndexableField field : doc.getFields()) {
                String value = field.stringValue();
                if (value != null) {
                    if (value.length() > 500) {
                        value = value.substring(0, 497) + "...";
                    }
                    value = value.replaceAll("[\\p{Cntrl}]", " ");
                    sb.append(String.format("%s: %s\n", field.name(), value));
                } else if (field.binaryValue() != null) {
                    sb.append(String.format("%s: [binary, %d bytes]\n", field.name(), field.binaryValue().length));
                } else if (field.numericValue() != null) {
                    sb.append(String.format("%s: %s\n", field.name(), field.numericValue()));
                }
            }
            
            sb.append("===============================================================\n");
            
            return sb.toString();
            
        } finally {
            if (reader != null) reader.close();
            if (dir != null) dir.close();
        }
    }

    // ============================================================
    // FULLTEXT ANALYSIS
    // ============================================================

    @Override
    public String getFulltextStats(String indexPath) throws IOException {
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
            
            Fields fields = MultiFields.getFields(reader);
            if (fields == null) {
                return "ERROR: No fields found in index";
            }
            
            Terms fulltextTerms = fields.terms(":fulltext");
            if (fulltextTerms == null) {
                return formatNoFulltextReport(indexPath, indexDir, reader);
            }
            
            long termCount = fulltextTerms.size();
            if (termCount == -1) {
                log.debug("Counting :fulltext terms manually");
                termCount = countTermsManually(fulltextTerms);
            }
            
            long indexSizeBytes = getFolderSize(indexDir);
            
            StringBuilder sb = new StringBuilder();
            sb.append("===============================================================\n");
            sb.append("FULLTEXT ANALYSIS\n");
            sb.append("===============================================================\n");
            sb.append(String.format("Index: %s\n", indexPath));
            sb.append(String.format("Total Index Size: %s\n", humanReadableByteCount(indexSizeBytes)));
            sb.append("\n");
            
            sb.append(":FULLTEXT FIELD:\n");
            sb.append(String.format("  Unique Terms: %,d\n", termCount));
            sb.append(String.format("  Documents: %,d\n", reader.numDocs()));
            sb.append("\n");
            
            if (termCount > 10_000_000) {
                sb.append("RECOMMENDATIONS:\n");
                sb.append("  WARNING: LARGE fulltext index detected\n");
                sb.append("  \n");
                sb.append("  For re-indexing, use pre-extracted cache:\n");
                sb.append("  1. oak-run tika --generate (creates binary stats CSV)\n");
                sb.append("  2. oak-run tika --populate (creates pre-extracted cache)\n");
                sb.append("  3. Configure DataStoreTextProviderService OSGi\n");
                sb.append("  4. Set alwaysUsePreExtractedCache=true\n");
                sb.append("  \n");
                sb.append("  This can reduce re-indexing time by 90%+\n");
            } else if (termCount > 1_000_000) {
                sb.append("RECOMMENDATIONS:\n");
                sb.append("  INFO: Moderate fulltext index size\n");
                sb.append("  Consider pre-extracted cache for frequent re-indexing\n");
            } else {
                sb.append("RECOMMENDATIONS:\n");
                sb.append("  OK: Fulltext index is reasonably sized\n");
                sb.append("  Pre-extracted cache optional but can still help\n");
            }
            
            sb.append("===============================================================\n");
            
            return sb.toString();
            
        } finally {
            if (reader != null) reader.close();
            if (dir != null) dir.close();
        }
    }

    @Override
    public String launchLukeGUI(String indexPath) {
        return "GUI not supported in JMX mode.\n\n" +
               "For interactive GUI analysis, use:\n" +
               "  java -jar luke-4.0.0-ALPHA.jar\n\n" +
               "Or use oak-luke-standalone JAR for CLI access.";
    }

    // ============================================================
    // ACTIONABLE INSIGHTS (The "Aha!" Moments)
    // ============================================================

    @Override
    public String getIndexInsights(String indexPath, int maxFields) throws IOException {
        log.info("Generating actionable insights for: {} (max {} fields)", indexPath, maxFields);
        
        File indexDir = findIndexDirectory(indexPath);
        if (indexDir == null) {
            return "ERROR: Could not find local index for: " + indexPath;
        }
        
        File actualDir = getActualIndexDirectory(indexDir);
        Directory dir = null;
        IndexReader reader = null;
        
        try {
            dir = FSDirectory.open(actualDir);
            reader = DirectoryReader.open(dir);
            
            int numDocs = reader.numDocs();
            int maxDoc = reader.maxDoc();
            int deletions = maxDoc - numDocs;
            double deleteRatio = maxDoc > 0 ? (deletions * 100.0 / maxDoc) : 0;
            long indexSize = getFolderSize(indexDir);
            
            // Collect field stats with cardinality
            // MEMORY/CPU SAFETY: Limit iterations for huge fields
            final int MAX_TERMS_FOR_TOP_VALUES = 500_000; // Skip top-values for fields >500k terms
            final int MAX_TERMS_FOR_COUNTING = 10_000_000; // Estimate for fields >10M terms
            
            List<FieldInsight> insights = new ArrayList<FieldInsight>();
            Fields fields = MultiFields.getFields(reader);
            
            if (fields != null) {
                for (String fieldName : fields) {
                    Terms terms = fields.terms(fieldName);
                    if (terms == null) continue;
                    
                    long termCount = terms.size();
                    boolean estimatedCount = false;
                    
                    if (termCount < 0) {
                        // Count manually but with a limit
                        termCount = countTermsWithLimit(terms, MAX_TERMS_FOR_COUNTING);
                        if (termCount >= MAX_TERMS_FOR_COUNTING) {
                            estimatedCount = true; // Mark as estimate
                        }
                    }
                    
                    int docCount = terms.getDocCount();
                    if (docCount < 0) docCount = numDocs;
                    
                    double coverage = numDocs > 0 ? (docCount * 100.0 / numDocs) : 0;
                    
                    // Get top 3 values for this field - BUT skip for very large fields
                    List<String> topValues = new ArrayList<String>();
                    
                    if (termCount <= MAX_TERMS_FOR_TOP_VALUES) {
                        // Safe to iterate for top values
                        TermsEnum te = terms.iterator(null);
                        PriorityQueue<TermInfo> topTerms = new PriorityQueue<TermInfo>(4, new Comparator<TermInfo>() {
                            @Override
                            public int compare(TermInfo a, TermInfo b) {
                                return Integer.compare(a.docFreq, b.docFreq); // Min heap
                            }
                        });
                        
                        while (te.next() != null) {
                            String termText = te.term().utf8ToString();
                            int docFreq = te.docFreq();
                            topTerms.offer(new TermInfo(fieldName, termText, docFreq, 0));
                            if (topTerms.size() > 3) topTerms.poll();
                        }
                        
                        while (!topTerms.isEmpty()) {
                            TermInfo ti = topTerms.poll();
                            String truncated = ti.term.length() > 15 ? ti.term.substring(0, 12) + "..." : ti.term;
                            topValues.add(0, String.format("%s(%,d)", truncated, ti.docFreq));
                        }
                    } else {
                        // Field too large - skip top values to save CPU
                        topValues.add(String.format("[%s terms - skipped]", estimatedCount ? ">" + MAX_TERMS_FOR_COUNTING : termCount));
                    }
                    
                    // Calculate cardinality ratio (unique values per doc)
                    double cardinalityRatio = docCount > 0 ? (double) termCount / docCount : 0;
                    
                    insights.add(new FieldInsight(fieldName, termCount, docCount, coverage, cardinalityRatio, topValues));
                }
            }
            
            // Sort by term count (size proxy)
            Collections.sort(insights);
            
            // Calculate totals
            long totalTerms = 0;
            for (FieldInsight fi : insights) {
                totalTerms += fi.termCount;
            }
            
            // Generate suggestions for each field
            for (FieldInsight fi : insights) {
                fi.percentage = totalTerms > 0 ? (fi.termCount * 100.0 / totalTerms) : 0;
                fi.suggestion = generateSuggestion(fi);
            }
            
            // Build output table
            StringBuilder sb = new StringBuilder();
            sb.append("╔═══════════════════════════════════════════════════════════════════════════════════════════════════════════════════════╗\n");
            sb.append("║                              INDEX INSIGHTS - ACTIONABLE OPTIMIZATION GUIDE                                            ║\n");
            sb.append("╠═══════════════════════════════════════════════════════════════════════════════════════════════════════════════════════╣\n");
            sb.append(String.format("║ Index: %-108s ║\n", indexPath));
            sb.append(String.format("║ Documents: %,d active | %,d deleted (%.1f%% waste) | Size: %s %52s ║\n", 
                    numDocs, deletions, deleteRatio, humanReadableByteCount(indexSize), ""));
            sb.append("╠════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════╣\n");
            sb.append("║                                              TOP FIELDS BY SIZE                                                        ║\n");
            sb.append("╠══════════════════════════╤═══════════════╤══════════╤══════════╤══════════════════════════════╤═══════════════════════╣\n");
            sb.append("║ Field                    │ Terms (Size)  │ Coverage │ Card.    │ Top Values                   │ Suggestion            ║\n");
            sb.append("╠══════════════════════════╪═══════════════╪══════════╪══════════╪══════════════════════════════╪═══════════════════════╣\n");
            
            int displayed = 0;
            long displayedTerms = 0;
            for (FieldInsight fi : insights) {
                if (displayed >= maxFields) break;
                
                String truncField = fi.fieldName.length() > 24 ? fi.fieldName.substring(0, 21) + "..." : fi.fieldName;
                String topVals = fi.topValues.isEmpty() ? "-" : String.join(", ", fi.topValues);
                if (topVals.length() > 28) topVals = topVals.substring(0, 25) + "...";
                String truncSug = fi.suggestion.length() > 21 ? fi.suggestion.substring(0, 18) + "..." : fi.suggestion;
                String cardStr = fi.cardinalityRatio > 1000 ? ">1000" : String.format("%.1f", fi.cardinalityRatio);
                
                sb.append(String.format("║ %-24s │ %,13d │ %6.1f%% │ %8s │ %-28s │ %-21s ║\n",
                        truncField, fi.termCount, fi.coverage, cardStr, topVals, truncSug));
                
                displayed++;
                displayedTerms += fi.termCount;
            }
            
            sb.append("╠══════════════════════════╧═══════════════╧══════════╧══════════╧══════════════════════════════╧═══════════════════════╣\n");
            
            // Summary and recommendations
            double displayedPct = totalTerms > 0 ? (displayedTerms * 100.0 / totalTerms) : 0;
            sb.append(String.format("║ Shown: Top %d fields = %,d terms (%.1f%% of total %,d terms) %50s ║\n", 
                    displayed, displayedTerms, displayedPct, totalTerms, ""));
            sb.append("╠════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════╣\n");
            sb.append("║                                           KEY RECOMMENDATIONS                                                          ║\n");
            sb.append("╠════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════╣\n");
            
            // Generate overall recommendations
            List<String> recommendations = generateOverallRecommendations(insights, deleteRatio, numDocs);
            for (String rec : recommendations) {
                // Word wrap at 116 chars
                while (rec.length() > 116) {
                    sb.append(String.format("║ %-116s ║\n", rec.substring(0, 116)));
                    rec = "    " + rec.substring(116);
                }
                sb.append(String.format("║ %-116s ║\n", rec));
            }
            
            sb.append("╚════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════╝\n");
            
            return sb.toString();
            
        } finally {
            if (reader != null) reader.close();
            if (dir != null) dir.close();
        }
    }

    @Override
    public String getContentDistribution(String indexPath, int depth) throws IOException {
        log.info("Analyzing content distribution for: {} at depth {}", indexPath, depth);
        
        File indexDir = findIndexDirectory(indexPath);
        if (indexDir == null) {
            return "ERROR: Could not find local index for: " + indexPath;
        }
        
        File actualDir = getActualIndexDirectory(indexDir);
        Directory dir = null;
        IndexReader reader = null;
        
        try {
            dir = FSDirectory.open(actualDir);
            reader = DirectoryReader.open(dir);
            
            // Look for :path field
            Fields fields = MultiFields.getFields(reader);
            Terms pathTerms = fields != null ? fields.terms(":path") : null;
            
            if (pathTerms == null) {
                return "ERROR: No :path field found in index. Cannot analyze content distribution.\n" +
                       "This index may not have path-based content mapping enabled.";
            }
            
            // Count documents per path prefix (with memory limit)
            final int MAX_PATH_PREFIXES = 50_000;
            Map<String, Integer> pathCounts = new HashMap<String, Integer>();
            TermsEnum te = pathTerms.iterator(null);
            boolean truncated = false;
            
            while (te.next() != null) {
                String path = te.term().utf8ToString();
                String prefix = getPathPrefix(path, depth);
                int docFreq = te.docFreq();
                
                // Memory guard: limit unique prefixes
                if (!pathCounts.containsKey(prefix) && pathCounts.size() >= MAX_PATH_PREFIXES) {
                    truncated = true;
                    continue; // Skip new prefixes, still count existing ones
                }
                
                Integer current = pathCounts.get(prefix);
                pathCounts.put(prefix, (current != null ? current : 0) + docFreq);
            }
            
            // Sort by count
            List<Map.Entry<String, Integer>> sorted = new ArrayList<Map.Entry<String, Integer>>(pathCounts.entrySet());
            Collections.sort(sorted, new Comparator<Map.Entry<String, Integer>>() {
                @Override
                public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) {
                    return b.getValue().compareTo(a.getValue());
                }
            });
            
            int totalDocs = reader.numDocs();
            
            StringBuilder sb = new StringBuilder();
            sb.append("═══════════════════════════════════════════════════════════════════════════════════════\n");
            sb.append("CONTENT DISTRIBUTION ANALYSIS\n");
            sb.append("═══════════════════════════════════════════════════════════════════════════════════════\n");
            sb.append(String.format("Index: %s\n", indexPath));
            sb.append(String.format("Analysis Depth: %d path segments\n", depth));
            sb.append(String.format("Total Documents: %,d\n", totalDocs));
            sb.append(String.format("Unique Path Prefixes: %,d\n", pathCounts.size()));
            if (truncated) {
                sb.append("⚠️ Results truncated to limit memory usage (50,000 prefix limit)\n");
            }
            sb.append("\n");
            
            sb.append(String.format("%-60s | %10s | %7s | %s\n", "Content Path", "Documents", "% Total", "Bar"));
            sb.append(repeatStr("-", 60) + "-+-" + repeatStr("-", 10) + "-+-" + repeatStr("-", 7) + "-+-" + repeatStr("-", 20) + "\n");
            
            int shown = 0;
            for (Map.Entry<String, Integer> entry : sorted) {
                if (shown >= 25) break; // Limit output
                
                String path = entry.getKey();
                int count = entry.getValue();
                double pct = totalDocs > 0 ? (count * 100.0 / totalDocs) : 0;
                
                // Simple ASCII bar
                int barLen = (int) (pct / 5); // Max 20 chars for 100%
                String bar = repeatStr("█", barLen);
                
                String truncPath = path.length() > 58 ? "..." + path.substring(path.length() - 55) : path;
                sb.append(String.format("%-60s | %,10d | %6.1f%% | %s\n", truncPath, count, pct, bar));
                shown++;
            }
            
            if (sorted.size() > 25) {
                sb.append(String.format("... and %d more paths\n", sorted.size() - 25));
            }
            
            sb.append("\n");
            sb.append("INSIGHTS:\n");
            
            // Generate content insights
            if (!sorted.isEmpty()) {
                Map.Entry<String, Integer> top = sorted.get(0);
                double topPct = totalDocs > 0 ? (top.getValue() * 100.0 / totalDocs) : 0;
                sb.append(String.format("• Top content area: %s (%.1f%% of index)\n", top.getKey(), topPct));
                
                if (topPct > 80) {
                    sb.append("  ⚠️ Index is heavily concentrated in one area. Consider splitting indexes.\n");
                }
                
                // Check for DAM content
                for (Map.Entry<String, Integer> entry : sorted) {
                    if (entry.getKey().contains("/dam")) {
                        double damPct = totalDocs > 0 ? (entry.getValue() * 100.0 / totalDocs) : 0;
                        sb.append(String.format("• DAM content: %.1f%% - ", damPct));
                        if (damPct > 30) {
                            sb.append("Heavy DAM usage. Ensure pre-extracted cache is configured for re-indexing.\n");
                        } else {
                            sb.append("Moderate DAM content.\n");
                        }
                        break;
                    }
                }
            }
            
            sb.append("═══════════════════════════════════════════════════════════════════════════════════════\n");
            
            return sb.toString();
            
        } finally {
            if (reader != null) reader.close();
            if (dir != null) dir.close();
        }
    }

    @Override
    public String[] getFieldCardinality(String indexPath, int maxFields) throws IOException {
        log.info("Analyzing field cardinality for: {}", indexPath);
        
        File indexDir = findIndexDirectory(indexPath);
        if (indexDir == null) {
            return new String[]{"ERROR: Could not find local index for: " + indexPath};
        }
        
        File actualDir = getActualIndexDirectory(indexDir);
        Directory dir = null;
        IndexReader reader = null;
        
        try {
            dir = FSDirectory.open(actualDir);
            reader = DirectoryReader.open(dir);
            
            int numDocs = reader.numDocs();
            List<CardinalityInfo> results = new ArrayList<CardinalityInfo>();
            
            Fields fields = MultiFields.getFields(reader);
            if (fields != null) {
                for (String fieldName : fields) {
                    Terms terms = fields.terms(fieldName);
                    if (terms == null) continue;
                    
                    long uniqueTerms = terms.size();
                    if (uniqueTerms < 0) {
                        uniqueTerms = countTermsManually(terms);
                    }
                    
                    int docCount = terms.getDocCount();
                    if (docCount < 0) docCount = numDocs;
                    
                    double coverage = numDocs > 0 ? (docCount * 100.0 / numDocs) : 0;
                    double cardinality = docCount > 0 ? (double) uniqueTerms / docCount : 0;
                    
                    String warning = "";
                    if (cardinality > 100) {
                        warning = "⚠️ HIGH CARDINALITY";
                    } else if (cardinality > 10) {
                        warning = "⚡ MODERATE";
                    } else if (coverage < 5 && docCount < 1000) {
                        warning = "📉 SPARSE";
                    }
                    
                    results.add(new CardinalityInfo(fieldName, uniqueTerms, docCount, coverage, cardinality, warning));
                }
            }
            
            // Sort by cardinality (high cardinality = potential problem)
            Collections.sort(results, new Comparator<CardinalityInfo>() {
                @Override
                public int compare(CardinalityInfo a, CardinalityInfo b) {
                    return Double.compare(b.cardinality, a.cardinality);
                }
            });
            
            List<String> output = new ArrayList<String>();
            output.add("═══════════════════════════════════════════════════════════════════════════════════════");
            output.add("FIELD CARDINALITY ANALYSIS");
            output.add("═══════════════════════════════════════════════════════════════════════════════════════");
            output.add(String.format("Index: %s | Total Docs: %,d", indexPath, numDocs));
            output.add("");
            output.add("Cardinality = Unique Values / Documents. High cardinality = memory intensive.");
            output.add("");
            output.add(String.format("%-40s | %12s | %10s | %8s | %10s | %s", 
                    "Field", "Unique Terms", "Doc Count", "Coverage", "Cardinality", "Status"));
            output.add(repeatStr("-", 40) + "-+-" + repeatStr("-", 12) + "-+-" + repeatStr("-", 10) + 
                       "-+-" + repeatStr("-", 8) + "-+-" + repeatStr("-", 10) + "-+-" + repeatStr("-", 18));
            
            int shown = 0;
            for (CardinalityInfo ci : results) {
                if (shown >= maxFields) break;
                
                String truncField = ci.fieldName.length() > 38 ? ci.fieldName.substring(0, 35) + "..." : ci.fieldName;
                String cardStr = ci.cardinality > 1000 ? String.format("%,.0f", ci.cardinality) : String.format("%.2f", ci.cardinality);
                
                output.add(String.format("%-40s | %,12d | %,10d | %6.1f%% | %10s | %s",
                        truncField, ci.uniqueTerms, ci.docCount, ci.coverage, cardStr, ci.warning));
                shown++;
            }
            
            output.add("");
            output.add("LEGEND: ⚠️ HIGH CARDINALITY (>100) = Consider faceted search or filtering");
            output.add("        ⚡ MODERATE (>10) = Monitor for growth");
            output.add("        📉 SPARSE (<5% coverage, <1k docs) = Consider if field is needed");
            output.add("═══════════════════════════════════════════════════════════════════════════════════════");
            
            return output.toArray(new String[0]);
            
        } finally {
            if (reader != null) reader.close();
            if (dir != null) dir.close();
        }
    }

    // Memory safety limit for duplicate detection
    private static final int MAX_TERMS_FOR_DUPLICATE_DETECTION = 100_000;
    
    @Override
    public String[] detectDuplicateValues(String indexPath, String fieldName, int maxResults) throws IOException {
        log.info("Detecting duplicate values in field {} for: {}", fieldName, indexPath);
        
        File indexDir = findIndexDirectory(indexPath);
        if (indexDir == null) {
            return new String[]{"ERROR: Could not find local index for: " + indexPath};
        }
        
        File actualDir = getActualIndexDirectory(indexDir);
        Directory dir = null;
        IndexReader reader = null;
        
        try {
            dir = FSDirectory.open(actualDir);
            reader = DirectoryReader.open(dir);
            
            Fields fields = MultiFields.getFields(reader);
            Terms terms = fields != null ? fields.terms(fieldName) : null;
            
            if (terms == null) {
                return new String[]{"ERROR: Field '" + fieldName + "' not found in index"};
            }
            
            // MEMORY SAFETY: Check term count before loading
            long termCount = terms.size();
            if (termCount < 0) {
                // Unknown size - sample first 1000 to estimate
                TermsEnum sampleEnum = terms.iterator(null);
                int sampleCount = 0;
                while (sampleEnum.next() != null && sampleCount < 1000) {
                    sampleCount++;
                }
                if (sampleCount >= 1000) {
                    // Field is large, estimate conservatively
                    termCount = MAX_TERMS_FOR_DUPLICATE_DETECTION + 1;
                }
            }
            
            List<String> output = new ArrayList<String>();
            output.add("═══════════════════════════════════════════════════════════════════════════════════════");
            output.add("DUPLICATE VALUE DETECTION");
            output.add("═══════════════════════════════════════════════════════════════════════════════════════");
            output.add(String.format("Index: %s", indexPath));
            output.add(String.format("Field: %s", fieldName));
            output.add("");
            
            // MEMORY GUARD: Refuse to process high-cardinality fields
            if (termCount > MAX_TERMS_FOR_DUPLICATE_DETECTION) {
                output.add(String.format("⛔ ABORTED: Field has too many unique terms (%,d+)", 
                        termCount > 0 ? termCount : MAX_TERMS_FOR_DUPLICATE_DETECTION));
                output.add(String.format("   Memory limit: %,d terms max", MAX_TERMS_FOR_DUPLICATE_DETECTION));
                output.add("");
                output.add("This field is HIGH-CARDINALITY and not suitable for duplicate detection.");
                output.add("Duplicate detection works best on low-cardinality fields like:");
                output.add("  • cq:tags (hundreds to thousands of values)");
                output.add("  • jcr:primaryType (tens of values)");
                output.add("  • author/creator fields (hundreds of values)");
                output.add("");
                output.add("For high-cardinality fields, use getFieldCardinality() instead.");
                output.add("═══════════════════════════════════════════════════════════════════════════════════════");
                return output.toArray(new String[0]);
            }
            
            // Safe to proceed - collect terms with memory limit
            Map<String, List<TermWithCount>> normalizedGroups = new HashMap<String, List<TermWithCount>>();
            TermsEnum te = terms.iterator(null);
            int processedTerms = 0;
            
            while (te.next() != null) {
                // Double-check memory safety during iteration
                if (processedTerms >= MAX_TERMS_FOR_DUPLICATE_DETECTION) {
                    log.warn("Duplicate detection hit memory limit at {} terms for field {}", 
                            processedTerms, fieldName);
                    break;
                }
                
                String termText = te.term().utf8ToString();
                int docFreq = te.docFreq();
                
                // Normalize: lowercase, trim, collapse whitespace
                String normalized = termText.toLowerCase().trim().replaceAll("\\s+", " ");
                
                List<TermWithCount> group = normalizedGroups.get(normalized);
                if (group == null) {
                    group = new ArrayList<TermWithCount>();
                    normalizedGroups.put(normalized, group);
                }
                group.add(new TermWithCount(termText, docFreq));
                processedTerms++;
            }
            
            // Find groups with multiple variants
            List<DuplicateGroup> duplicates = new ArrayList<DuplicateGroup>();
            for (Map.Entry<String, List<TermWithCount>> entry : normalizedGroups.entrySet()) {
                if (entry.getValue().size() > 1) {
                    int totalDocs = 0;
                    for (TermWithCount twc : entry.getValue()) {
                        totalDocs += twc.docFreq;
                    }
                    duplicates.add(new DuplicateGroup(entry.getKey(), entry.getValue(), totalDocs));
                }
            }
            
            // Clear the large map immediately after extracting duplicates
            normalizedGroups.clear();
            normalizedGroups = null;
            
            // Sort by total document impact
            Collections.sort(duplicates, new Comparator<DuplicateGroup>() {
                @Override
                public int compare(DuplicateGroup a, DuplicateGroup b) {
                    return Integer.compare(b.totalDocs, a.totalDocs);
                }
            });
            
            output.add(String.format("Analyzed: %,d terms", processedTerms));
            output.add("");
            
            if (duplicates.isEmpty()) {
                output.add("✅ No duplicate variations found in this field.");
                output.add("   All values appear to be unique (case-sensitive, whitespace-normalized).");
            } else {
                output.add(String.format("⚠️ Found %d groups of potential duplicates:", duplicates.size()));
                output.add("");
                
                int shown = 0;
                for (DuplicateGroup dg : duplicates) {
                    if (shown >= maxResults) break;
                    
                    output.add(String.format("Group '%s' (%,d total docs):", 
                            dg.normalized.length() > 40 ? dg.normalized.substring(0, 37) + "..." : dg.normalized,
                            dg.totalDocs));
                    
                    for (TermWithCount twc : dg.variants) {
                        String display = twc.term.length() > 50 ? twc.term.substring(0, 47) + "..." : twc.term;
                        output.add(String.format("    • \"%s\" (%,d docs)", display, twc.docFreq));
                    }
                    output.add("");
                    shown++;
                }
                
                if (duplicates.size() > maxResults) {
                    output.add(String.format("... and %d more duplicate groups", duplicates.size() - maxResults));
                }
                
                output.add("");
                output.add("RECOMMENDATION: Consider normalizing these values during indexing");
                output.add("                or implementing a custom analyzer with case-folding.");
            }
            
            output.add("═══════════════════════════════════════════════════════════════════════════════════════");
            
            return output.toArray(new String[0]);
            
        } finally {
            if (reader != null) reader.close();
            if (dir != null) dir.close();
        }
    }

    // ============================================================
    // INSIGHT HELPER METHODS
    // ============================================================

    private String getPathPrefix(String path, int depth) {
        if (path == null || path.isEmpty()) return "/";
        
        String[] parts = path.split("/");
        StringBuilder prefix = new StringBuilder();
        
        for (int i = 0; i < parts.length && i <= depth; i++) {
            if (!parts[i].isEmpty()) {
                prefix.append("/").append(parts[i]);
            }
        }
        
        return prefix.length() > 0 ? prefix.toString() : "/";
    }

    private String generateSuggestion(FieldInsight fi) {
        // High cardinality fields
        if (fi.cardinalityRatio > 100) {
            if (fi.fieldName.contains("fulltext") || fi.fieldName.equals(":fulltext")) {
                return "Use pre-extracted cache";
            }
            return "Consider faceting";
        }
        
        // Very high term count
        if (fi.termCount > 10_000_000) {
            return "Major bloat - review";
        }
        
        // Low coverage
        if (fi.coverage < 5) {
            return "Sparse - needed?";
        }
        
        // Fulltext specific
        if (fi.fieldName.contains("fulltext")) {
            if (fi.termCount > 1_000_000) {
                return "Add stemming/synonyms";
            }
            return "OK - fulltext";
        }
        
        // High percentage of index
        if (fi.percentage > 30) {
            return "Dominates index";
        }
        
        return "OK";
    }

    private List<String> generateOverallRecommendations(List<FieldInsight> insights, double deleteRatio, int numDocs) {
        List<String> recs = new ArrayList<String>();
        
        // Check deletion ratio
        if (deleteRatio > 20) {
            recs.add("⚠️ HIGH DELETION RATIO (%.1f%%): Consider running compaction/optimization to reclaim space." + deleteRatio);
        }
        
        // Check for fulltext field
        boolean hasFulltext = false;
        long fulltextTerms = 0;
        for (FieldInsight fi : insights) {
            if (fi.fieldName.contains("fulltext") || fi.fieldName.equals(":fulltext")) {
                hasFulltext = true;
                fulltextTerms = fi.termCount;
                break;
            }
        }
        
        if (hasFulltext && fulltextTerms > 10_000_000) {
            recs.add(String.format("📚 LARGE FULLTEXT (%,d terms): Use oak-run tika --generate/--populate for pre-extracted cache before re-indexing.", fulltextTerms));
        }
        
        // Check for high-cardinality fields
        int highCardCount = 0;
        for (FieldInsight fi : insights) {
            if (fi.cardinalityRatio > 100 && !fi.fieldName.contains("fulltext")) {
                highCardCount++;
            }
        }
        
        if (highCardCount > 0) {
            recs.add(String.format("🔢 %d HIGH-CARDINALITY FIELDS: These consume extra memory. Consider if all need to be indexed.", highCardCount));
        }
        
        // Check concentration
        if (!insights.isEmpty() && insights.get(0).percentage > 50) {
            recs.add(String.format("📊 INDEX DOMINATED BY '%s' (%.1f%%): This field drives most of your index size.", 
                    insights.get(0).fieldName, insights.get(0).percentage));
        }
        
        // General recommendation
        if (numDocs > 1_000_000) {
            recs.add("🏗️ LARGE INDEX (>1M docs): Consider async indexing, dedicated index lanes, or split indexes.");
        }
        
        if (recs.isEmpty()) {
            recs.add("✅ INDEX LOOKS HEALTHY: No major issues detected.");
        }
        
        return recs;
    }

    // ============================================================
    // INSIGHT INNER CLASSES
    // ============================================================

    private static class FieldInsight implements Comparable<FieldInsight> {
        final String fieldName;
        final long termCount;
        final int docCount;
        final double coverage;
        final double cardinalityRatio;
        final List<String> topValues;
        double percentage;
        String suggestion;

        FieldInsight(String fieldName, long termCount, int docCount, double coverage, 
                     double cardinalityRatio, List<String> topValues) {
            this.fieldName = fieldName;
            this.termCount = termCount;
            this.docCount = docCount;
            this.coverage = coverage;
            this.cardinalityRatio = cardinalityRatio;
            this.topValues = topValues;
        }

        @Override
        public int compareTo(FieldInsight other) {
            return Long.compare(other.termCount, this.termCount);
        }
    }

    private static class CardinalityInfo {
        final String fieldName;
        final long uniqueTerms;
        final int docCount;
        final double coverage;
        final double cardinality;
        final String warning;

        CardinalityInfo(String fieldName, long uniqueTerms, int docCount, 
                        double coverage, double cardinality, String warning) {
            this.fieldName = fieldName;
            this.uniqueTerms = uniqueTerms;
            this.docCount = docCount;
            this.coverage = coverage;
            this.cardinality = cardinality;
            this.warning = warning;
        }
    }

    private static class TermWithCount {
        final String term;
        final int docFreq;

        TermWithCount(String term, int docFreq) {
            this.term = term;
            this.docFreq = docFreq;
        }
    }

    private static class DuplicateGroup {
        final String normalized;
        final List<TermWithCount> variants;
        final int totalDocs;

        DuplicateGroup(String normalized, List<TermWithCount> variants, int totalDocs) {
            this.normalized = normalized;
            this.variants = variants;
            this.totalDocs = totalDocs;
        }
    }

    // ============================================================
    // HELPER METHODS
    // ============================================================

    private File findIndexDirectory(String indexPath) {
        File indexBaseDir = new File(repositoryHome, "repository/index");
        if (!indexBaseDir.exists()) {
            return null;
        }

        File[] dirs = indexBaseDir.listFiles();
        if (dirs != null) {
            String searchName = indexPath.replace("/oak:index/", "").replace("/", "_");
            for (File dir : dirs) {
                if (dir.getName().contains(searchName)) {
                    if (isValidLuceneIndex(dir)) {
                        return dir;
                    }
                }
            }
        }
        return null;
    }
    
    private File getActualIndexDirectory(File indexDir) {
        File dataDir = new File(indexDir, "data");
        if (dataDir.exists() && dataDir.isDirectory() && hasSegmentsFile(dataDir)) {
            return dataDir;
        }
        return indexDir;
    }

    private boolean isValidLuceneIndex(File dir) {
        if (!dir.isDirectory()) {
            return false;
        }
        
        if (hasSegmentsFile(dir)) {
            return true;
        }
        
        File dataDir = new File(dir, "data");
        if (dataDir.exists() && dataDir.isDirectory()) {
            if (hasSegmentsFile(dataDir)) {
                return true;
            }
        }
        
        return false;
    }
    
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

    private long countTermsManually(Terms terms) throws IOException {
        long count = 0;
        TermsEnum te = terms.iterator(null);
        while (te.next() != null) {
            count++;
        }
        return count;
    }
    
    /**
     * Count terms with a limit to prevent excessive CPU usage on huge fields.
     * Returns the limit value if limit is reached, indicating "at least this many".
     */
    private long countTermsWithLimit(Terms terms, int limit) throws IOException {
        long count = 0;
        TermsEnum te = terms.iterator(null);
        while (te.next() != null) {
            count++;
            if (count >= limit) {
                return count; // Hit limit, return early
            }
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
    
    private String formatNoFulltextReport(String indexPath, File indexDir, IndexReader reader) throws IOException {
        int fieldCount = 0;
        Fields fields = MultiFields.getFields(reader);
        if (fields != null) {
            for (@SuppressWarnings("unused") String f : fields) {
                fieldCount++;
            }
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("===============================================================\n");
        sb.append("FULLTEXT ANALYSIS\n");
        sb.append("===============================================================\n");
        sb.append(String.format("Index: %s\n", indexPath));
        sb.append("\n");
        sb.append("WARNING: No :fulltext field found in this index\n");
        sb.append("\n");
        sb.append(String.format("Documents: %,d\n", reader.numDocs()));
        sb.append(String.format("Fields: %,d\n", fieldCount));
        sb.append("\n");
        sb.append("This index does not contain binary fulltext extractions.\n");
        sb.append("Pre-extracted cache not applicable.\n");
        sb.append("===============================================================\n");
        return sb.toString();
    }

    // ============================================================
    // INNER CLASSES
    // ============================================================

    /** Holds term count per field for sorting */
    private static class FieldTermCount implements Comparable<FieldTermCount> {
        final String fieldName;
        final long termCount;
        double percentage;

        FieldTermCount(String fieldName, long termCount) {
            this.fieldName = fieldName;
            this.termCount = termCount;
        }

        @Override
        public int compareTo(FieldTermCount other) {
            return Long.compare(other.termCount, this.termCount); // Descending
        }

        @Override
        public String toString() {
            String truncated = fieldName.length() > 53 ? 
                    fieldName.substring(0, 50) + "..." : fieldName;
            return String.format("%-55s | %,15d | %7.2f%%", truncated, termCount, percentage);
        }
    }

    /** Holds term info for top terms ranking */
    private static class TermInfo implements Comparable<TermInfo> {
        final String field;
        final String term;
        final int docFreq;
        @SuppressWarnings("unused")  // Kept for future totalTermFreq display
        final long totalFreq;

        TermInfo(String field, String term, int docFreq, long totalFreq) {
            this.field = field;
            this.term = term;
            this.docFreq = docFreq;
            this.totalFreq = totalFreq;
        }

        @Override
        public int compareTo(TermInfo other) {
            return Integer.compare(other.docFreq, this.docFreq); // Descending
        }
    }
}
