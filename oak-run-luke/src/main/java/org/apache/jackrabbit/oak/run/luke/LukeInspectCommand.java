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
package org.apache.jackrabbit.oak.run.luke;

import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import org.apache.jackrabbit.oak.run.commons.Command;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.*;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * CLI Command to inspect a Lucene index - LUKE style.
 * 
 * Usage:
 *   java -jar oak-run-luke.jar inspect /path/to/index [options]
 */
public class LukeInspectCommand implements Command {

    @Override
    public void execute(String... args) throws Exception {
        OptionParser parser = new OptionParser();
        
        OptionSpec<String> indexOpt = parser.accepts("index", "Path to Lucene index directory")
                .withRequiredArg().ofType(String.class);
        OptionSpec<String> fieldOpt = parser.accepts("field", "Analyze specific field")
                .withRequiredArg().ofType(String.class);
        OptionSpec<Integer> topTermsOpt = parser.accepts("top-terms", "Show top N terms by frequency")
                .withRequiredArg().ofType(Integer.class).defaultsTo(20);
        OptionSpec<Integer> docOpt = parser.accepts("doc", "Show specific document by ID")
                .withRequiredArg().ofType(Integer.class);
        OptionSpec<Integer> sampleOpt = parser.accepts("sample", "Sample N random documents")
                .withRequiredArg().ofType(Integer.class);
        OptionSpec<?> segmentsOpt = parser.accepts("segments", "Show segment information");
        OptionSpec<?> healthOpt = parser.accepts("health", "Show health report");
        OptionSpec<?> fieldsOpt = parser.accepts("fields", "List all fields");
        
        // NEW: Actionable insight options
        OptionSpec<?> insightsOpt = parser.accepts("insights", "⭐ Actionable optimization insights with suggestions");
        OptionSpec<Integer> contentDistOpt = parser.accepts("content-dist", "Content distribution by path (depth)")
                .withRequiredArg().ofType(Integer.class).defaultsTo(2);
        OptionSpec<?> cardinalityOpt = parser.accepts("cardinality", "Field cardinality analysis");
        OptionSpec<String> duplicatesOpt = parser.accepts("duplicates", "Detect duplicate values in field")
                .withRequiredArg().ofType(String.class);
        
        OptionSpec<?> helpOpt = parser.accepts("help", "Show help").forHelp();
        OptionSpec<File> nonOptions = parser.nonOptions("index-path").ofType(File.class);
        
        OptionSet options = parser.parse(args);
        
        if (options.has(helpOpt) || (options.valuesOf(nonOptions).isEmpty() && !options.has(indexOpt))) {
            printUsage();
            parser.printHelpOn(System.out);
            return;
        }
        
        // Determine index path
        File indexPath;
        if (options.has(indexOpt)) {
            indexPath = new File(options.valueOf(indexOpt));
        } else {
            indexPath = options.valuesOf(nonOptions).get(0);
        }
        
        // Find actual index directory
        File actualIndexDir = findActualIndexDirectory(indexPath);
        if (actualIndexDir == null) {
            System.err.println("ERROR: No valid Lucene index found at: " + indexPath);
            System.err.println("Tip: Look for directories containing 'segments_*' files");
            return;
        }
        
        System.out.println("Opening index: " + actualIndexDir.getAbsolutePath());
        System.out.println();
        
        Directory dir = FSDirectory.open(actualIndexDir);
        DirectoryReader reader = DirectoryReader.open(dir);
        
        try {
            if (options.has(insightsOpt)) {
                showInsights(reader, dir, actualIndexDir, 15);
            } else if (options.has(contentDistOpt) && !options.valuesOf(contentDistOpt).isEmpty()) {
                showContentDistribution(reader, options.valueOf(contentDistOpt));
            } else if (options.has(cardinalityOpt)) {
                showCardinality(reader, 30);
            } else if (options.has(duplicatesOpt)) {
                showDuplicates(reader, options.valueOf(duplicatesOpt), 20);
            } else if (options.has(healthOpt)) {
                showHealthReport(reader, dir, actualIndexDir);
            } else if (options.has(segmentsOpt)) {
                showSegmentInfo(dir);
            } else if (options.has(fieldsOpt)) {
                showFields(reader, options.valueOf(topTermsOpt));
            } else if (options.has(fieldOpt)) {
                showFieldAnalysis(reader, options.valueOf(fieldOpt), options.valueOf(topTermsOpt));
            } else if (options.has(docOpt)) {
                showDocument(reader, options.valueOf(docOpt));
            } else if (options.has(sampleOpt)) {
                showSampleDocuments(reader, options.valueOf(sampleOpt));
            } else {
                // Default: show overview
                showOverview(reader, dir, actualIndexDir);
            }
        } finally {
            reader.close();
            dir.close();
        }
    }
    
    private void printUsage() {
        System.out.println("LUKE Index Inspector - Deep Lucene index analysis");
        System.out.println("=================================================");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  java -jar oak-run-luke.jar inspect /path/to/index [options]");
        System.out.println();
        System.out.println("ACTIONABLE INSIGHTS (Recommended):");
        System.out.println("  --insights           ⭐ Actionable optimization suggestions with table output");
        System.out.println("  --content-dist N     Content distribution by path at depth N (default: 2)");
        System.out.println("  --cardinality        Field cardinality analysis (find memory hogs)");
        System.out.println("  --duplicates FIELD   Detect duplicate/variant values in a field");
        System.out.println();
        System.out.println("ANALYSIS OPTIONS:");
        System.out.println("  --health             Health report with score and recommendations");
        System.out.println("  --segments           Segment analysis with deletion ratios");
        System.out.println("  --fields             List all fields with term counts");
        System.out.println("  --field FIELD        Analyze specific field (use with --top-terms)");
        System.out.println("  --top-terms N        Number of top terms to show (default: 20)");
        System.out.println();
        System.out.println("DOCUMENT INSPECTION:");
        System.out.println("  --doc ID             Show specific document by ID");
        System.out.println("  --sample N           Sample N random documents");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  # ⭐ Get actionable insights (START HERE!)");
        System.out.println("  java -jar oak-run-luke.jar inspect /path/to/index --insights");
        System.out.println();
        System.out.println("  # See what content is indexed");
        System.out.println("  java -jar oak-run-luke.jar inspect /path/to/index --content-dist 3");
        System.out.println();
        System.out.println("  # Find fields with too many unique values");
        System.out.println("  java -jar oak-run-luke.jar inspect /path/to/index --cardinality");
        System.out.println();
        System.out.println("  # Find duplicate tag values (e.g., 'urgent', 'URGENT', 'Urgent')");
        System.out.println("  java -jar oak-run-luke.jar inspect /path/to/index --duplicates tags");
        System.out.println();
    }
    
    private File findActualIndexDirectory(File path) {
        if (!path.exists()) {
            return null;
        }
        
        // Check if path itself contains segments
        if (hasSegmentsFile(path)) {
            return path;
        }
        
        // Check data subdirectory (Oak IndexCopier structure)
        File dataDir = new File(path, "data");
        if (dataDir.exists() && hasSegmentsFile(dataDir)) {
            return dataDir;
        }
        
        return null;
    }
    
    private boolean hasSegmentsFile(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return false;
        for (File f : files) {
            if (f.getName().startsWith("segments") && !f.getName().equals("segments.gen")) {
                return true;
            }
        }
        return false;
    }
    
    private void showOverview(IndexReader reader, Directory dir, File indexDir) throws IOException {
        long size = getFolderSize(indexDir);
        
        int fieldCount = 0;
        long totalTerms = 0;
        Map<String, Long> fieldTerms = new TreeMap<>();
        
        Fields fields = MultiFields.getFields(reader);
        if (fields != null) {
            for (String fieldName : fields) {
                fieldCount++;
                Terms terms = fields.terms(fieldName);
                if (terms != null) {
                    long termCount = terms.size();
                    if (termCount == -1) {
                        termCount = countTermsManually(terms);
                    }
                    fieldTerms.put(fieldName, termCount);
                    totalTerms += termCount;
                }
            }
        }
        
        System.out.println("═══════════════════════════════════════════════════════════════════════════════════════");
        System.out.println("INDEX OVERVIEW");
        System.out.println("═══════════════════════════════════════════════════════════════════════════════════════");
        System.out.println("Path: " + indexDir.getAbsolutePath());
        System.out.printf("Size: %s%n", humanReadableSize(size));
        System.out.println();
        System.out.println("DOCUMENT STATISTICS:");
        System.out.printf("  Documents: %,d%n", reader.numDocs());
        System.out.printf("  Max Doc ID: %,d%n", reader.maxDoc());
        System.out.printf("  Deletions: %,d (%.1f%%)%n", reader.numDeletedDocs(),
                reader.maxDoc() > 0 ? (reader.numDeletedDocs() * 100.0 / reader.maxDoc()) : 0);
        System.out.println();
        System.out.println("FIELD STATISTICS:");
        System.out.printf("  Total Fields: %d%n", fieldCount);
        System.out.printf("  Total Terms: %,d%n", totalTerms);
        System.out.println();
        
        // Top 10 largest fields
        List<Map.Entry<String, Long>> sorted = new ArrayList<>(fieldTerms.entrySet());
        sorted.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        
        System.out.println("TOP 10 LARGEST FIELDS (by term count):");
        System.out.printf("  %-50s %15s %8s%n", "Field", "Terms", "%");
        System.out.println("  " + repeatChar('-', 75));
        
        int count = 0;
        for (Map.Entry<String, Long> entry : sorted) {
            if (count++ >= 10) break;
            String fieldName = entry.getKey();
            long terms = entry.getValue();
            double pct = totalTerms > 0 ? (terms * 100.0 / totalTerms) : 0;
            String display = fieldName.length() > 48 ? fieldName.substring(0, 45) + "..." : fieldName;
            System.out.printf("  %-50s %,15d %7.2f%%%n", display, terms, pct);
        }
        System.out.println("═══════════════════════════════════════════════════════════════════════════════════════");
    }
    
    private void showHealthReport(IndexReader reader, Directory dir, File indexDir) throws IOException {
        long size = getFolderSize(indexDir);
        int numDocs = reader.numDocs();
        int deletions = reader.numDeletedDocs();
        double deletionRatio = reader.maxDoc() > 0 ? (deletions * 100.0 / reader.maxDoc()) : 0;
        
        SegmentInfos segInfos = new SegmentInfos();
        segInfos.read(dir);
        int segmentCount = segInfos.size();
        
        int fieldCount = 0;
        Fields fields = MultiFields.getFields(reader);
        if (fields != null) {
            for (@SuppressWarnings("unused") String f : fields) {
                fieldCount++;
            }
        }
        
        // Calculate health score
        int score = 100;
        List<String> issues = new ArrayList<>();
        List<String> recommendations = new ArrayList<>();
        
        if (deletionRatio > 30) {
            score -= 30;
            issues.add("HIGH: Deletion ratio " + String.format("%.1f%%", deletionRatio));
            recommendations.add("-> Run index optimization to reclaim space");
        } else if (deletionRatio > 10) {
            score -= 10;
            issues.add("WARN: Moderate deletion ratio " + String.format("%.1f%%", deletionRatio));
        }
        
        if (segmentCount > 50) {
            score -= 25;
            issues.add("HIGH: Too many segments: " + segmentCount);
            recommendations.add("-> Index is fragmented - consider merge");
        } else if (segmentCount > 20) {
            score -= 10;
            issues.add("WARN: High segment count: " + segmentCount);
        }
        
        String status = score >= 90 ? "EXCELLENT" : score >= 70 ? "GOOD" : score >= 50 ? "NEEDS ATTENTION" : "POOR";
        
        System.out.println("═══════════════════════════════════════════════════════════════════════════════════════");
        System.out.println("INDEX HEALTH REPORT");
        System.out.println("═══════════════════════════════════════════════════════════════════════════════════════");
        System.out.printf("HEALTH SCORE: %d/100 (%s)%n", score, status);
        System.out.println();
        System.out.println("METRICS:");
        System.out.printf("  Documents: %,d%n", numDocs);
        System.out.printf("  Deletions: %,d (%.1f%%)%n", deletions, deletionRatio);
        System.out.printf("  Segments: %d%n", segmentCount);
        System.out.printf("  Fields: %d%n", fieldCount);
        System.out.printf("  Size: %s%n", humanReadableSize(size));
        System.out.println();
        
        if (!issues.isEmpty()) {
            System.out.println("ISSUES:");
            for (String issue : issues) {
                System.out.println("  " + issue);
            }
            System.out.println();
        }
        
        if (!recommendations.isEmpty()) {
            System.out.println("RECOMMENDATIONS:");
            for (String rec : recommendations) {
                System.out.println("  " + rec);
            }
            System.out.println();
        }
        
        if (issues.isEmpty()) {
            System.out.println("OK: No issues detected - index appears healthy");
        }
        System.out.println("═══════════════════════════════════════════════════════════════════════════════════════");
    }
    
    private void showSegmentInfo(Directory dir) throws IOException {
        SegmentInfos segInfos = new SegmentInfos();
        segInfos.read(dir);
        
        System.out.println("═══════════════════════════════════════════════════════════════════════════════════════");
        System.out.println("SEGMENT ANALYSIS");
        System.out.println("═══════════════════════════════════════════════════════════════════════════════════════");
        System.out.printf("Total Segments: %d%n", segInfos.size());
        System.out.printf("Lucene Version: %s%n", segInfos.getVersion());
        System.out.println();
        System.out.printf("%-30s | %12s | %12s | %8s%n", "Segment", "Docs", "Deletions", "Del %");
        System.out.println(repeatChar('-', 70));
        
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
            System.out.printf("%-30s | %,12d | %,12d | %7.1f%%%n", segName, docs, deletes, delPercent);
        }
        
        System.out.println(repeatChar('-', 70));
        double totalDelPercent = totalDocs > 0 ? (totalDeletes * 100.0 / totalDocs) : 0;
        System.out.printf("%-30s | %,12d | %,12d | %7.1f%%%n", "TOTAL", totalDocs, totalDeletes, totalDelPercent);
        System.out.println("═══════════════════════════════════════════════════════════════════════════════════════");
    }
    
    private void showFields(IndexReader reader, int topTerms) throws IOException {
        System.out.println("═══════════════════════════════════════════════════════════════════════════════════════");
        System.out.println("FIELD ANALYSIS");
        System.out.println("═══════════════════════════════════════════════════════════════════════════════════════");
        System.out.printf("%-50s | %15s%n", "Field Name", "Term Count");
        System.out.println(repeatChar('-', 70));
        
        Fields fields = MultiFields.getFields(reader);
        if (fields != null) {
            List<FieldTermCount> fieldList = new ArrayList<>();
            for (String fieldName : fields) {
                Terms terms = fields.terms(fieldName);
                if (terms != null) {
                    long termCount = terms.size();
                    if (termCount == -1) {
                        termCount = countTermsManually(terms);
                    }
                    fieldList.add(new FieldTermCount(fieldName, termCount));
                }
            }
            
            // Sort by term count descending
            fieldList.sort((a, b) -> Long.compare(b.termCount, a.termCount));
            
            for (FieldTermCount ftc : fieldList) {
                String display = ftc.fieldName.length() > 48 ? ftc.fieldName.substring(0, 45) + "..." : ftc.fieldName;
                System.out.printf("%-50s | %,15d%n", display, ftc.termCount);
            }
        }
        System.out.println("═══════════════════════════════════════════════════════════════════════════════════════");
    }
    
    private void showFieldAnalysis(IndexReader reader, String fieldName, int topTerms) throws IOException {
        Terms terms = MultiFields.getTerms(reader, fieldName);
        if (terms == null) {
            System.err.println("ERROR: Field not found: " + fieldName);
            return;
        }
        
        System.out.println("═══════════════════════════════════════════════════════════════════════════════════════");
        System.out.printf("FIELD ANALYSIS: %s%n", fieldName);
        System.out.println("═══════════════════════════════════════════════════════════════════════════════════════");
        
        // Count terms and collect top by doc frequency
        List<TermInfo> topList = new ArrayList<>();
        TermsEnum termsEnum = terms.iterator(null);
        BytesRef term;
        long totalTerms = 0;
        
        while ((term = termsEnum.next()) != null) {
            totalTerms++;
            int docFreq = termsEnum.docFreq();
            
            topList.add(new TermInfo(term.utf8ToString(), docFreq));
            
            // Keep only top N*2 to avoid memory issues
            if (topList.size() > topTerms * 2) {
                topList.sort((a, b) -> Integer.compare(b.docFreq, a.docFreq));
                topList = new ArrayList<>(topList.subList(0, topTerms));
            }
        }
        
        topList.sort((a, b) -> Integer.compare(b.docFreq, a.docFreq));
        if (topList.size() > topTerms) {
            topList = topList.subList(0, topTerms);
        }
        
        System.out.printf("Total Unique Terms: %,d%n", totalTerms);
        System.out.println();
        System.out.printf("TOP %d TERMS BY DOCUMENT FREQUENCY:%n", topTerms);
        System.out.printf("%-50s | %12s%n", "Term", "Doc Freq");
        System.out.println(repeatChar('-', 65));
        
        for (TermInfo ti : topList) {
            String display = ti.term;
            if (display.length() > 48) {
                display = display.substring(0, 45) + "...";
            }
            display = display.replaceAll("[\\p{Cntrl}]", "?");
            System.out.printf("%-50s | %,12d%n", display, ti.docFreq);
        }
        System.out.println("═══════════════════════════════════════════════════════════════════════════════════════");
    }
    
    private void showDocument(IndexReader reader, int docId) throws IOException {
        if (docId < 0 || docId >= reader.maxDoc()) {
            System.err.printf("ERROR: Invalid doc ID %d (valid range: 0-%d)%n", docId, reader.maxDoc() - 1);
            return;
        }
        
        Document doc = reader.document(docId);
        if (doc == null) {
            System.out.println("Document #" + docId + " is deleted");
            return;
        }
        
        System.out.println("═══════════════════════════════════════════════════════════════════════════════════════");
        System.out.printf("DOCUMENT #%d%n", docId);
        System.out.println("═══════════════════════════════════════════════════════════════════════════════════════");
        
        for (IndexableField field : doc.getFields()) {
            String value = field.stringValue();
            if (value != null) {
                if (value.length() > 200) {
                    value = value.substring(0, 197) + "...";
                }
                value = value.replaceAll("[\\p{Cntrl}]", " ");
                System.out.printf("%s: %s%n", field.name(), value);
            } else if (field.binaryValue() != null) {
                System.out.printf("%s: [binary, %d bytes]%n", field.name(), field.binaryValue().length);
            } else if (field.numericValue() != null) {
                System.out.printf("%s: %s%n", field.name(), field.numericValue());
            }
        }
        System.out.println("═══════════════════════════════════════════════════════════════════════════════════════");
    }
    
    private void showSampleDocuments(IndexReader reader, int sampleSize) throws IOException {
        int numDocs = reader.numDocs();
        if (numDocs == 0) {
            System.out.println("Index is empty");
            return;
        }
        
        Random rand = new Random();
        Set<Integer> sampledIds = new HashSet<>();
        int maxAttempts = sampleSize * 10;
        int attempts = 0;
        
        while (sampledIds.size() < sampleSize && sampledIds.size() < numDocs && attempts < maxAttempts) {
            int docId = rand.nextInt(reader.maxDoc());
            sampledIds.add(docId);
            attempts++;
        }
        
        System.out.println("═══════════════════════════════════════════════════════════════════════════════════════");
        System.out.printf("DOCUMENT SAMPLES (showing %d of %,d documents)%n", sampledIds.size(), numDocs);
        System.out.println("═══════════════════════════════════════════════════════════════════════════════════════");
        
        for (int docId : sampledIds) {
            System.out.println();
            System.out.printf("--- Document #%d ---%n", docId);
            
            Document doc = reader.document(docId);
            if (doc == null) {
                System.out.println("  (deleted)");
                continue;
            }
            
            for (IndexableField field : doc.getFields()) {
                String value = field.stringValue();
                if (value != null) {
                    if (value.length() > 100) {
                        value = value.substring(0, 97) + "...";
                    }
                    value = value.replaceAll("[\\p{Cntrl}]", " ");
                    System.out.printf("  %s: %s%n", field.name(), value);
                } else if (field.binaryValue() != null) {
                    System.out.printf("  %s: [binary, %d bytes]%n", field.name(), field.binaryValue().length);
                }
            }
        }
        System.out.println();
        System.out.println("═══════════════════════════════════════════════════════════════════════════════════════");
    }
    
    // ============================================================
    // ACTIONABLE INSIGHTS
    // ============================================================

    private void showInsights(IndexReader reader, Directory dir, File indexDir, int maxFields) throws IOException {
        long size = getFolderSize(indexDir);
        int numDocs = reader.numDocs();
        int maxDoc = reader.maxDoc();
        int deletions = maxDoc - numDocs;
        double deleteRatio = maxDoc > 0 ? (deletions * 100.0 / maxDoc) : 0;
        
        // Collect field stats
        List<FieldInsight> insights = new ArrayList<>();
        Fields fields = MultiFields.getFields(reader);
        long totalTerms = 0;
        
        if (fields != null) {
            for (String fieldName : fields) {
                Terms terms = fields.terms(fieldName);
                if (terms == null) continue;
                
                long termCount = terms.size();
                if (termCount < 0) termCount = countTermsManually(terms);
                
                int docCount = terms.getDocCount();
                if (docCount < 0) docCount = numDocs;
                
                double coverage = numDocs > 0 ? (docCount * 100.0 / numDocs) : 0;
                double cardinalityRatio = docCount > 0 ? (double) termCount / docCount : 0;
                
                // Get top 3 values
                List<String> topValues = new ArrayList<>();
                TermsEnum te = terms.iterator(null);
                List<TermInfo> topTerms = new ArrayList<>();
                
                while (te.next() != null) {
                    topTerms.add(new TermInfo(te.term().utf8ToString(), te.docFreq()));
                    if (topTerms.size() > 100) {
                        topTerms.sort((a, b) -> Integer.compare(b.docFreq, a.docFreq));
                        topTerms = new ArrayList<>(topTerms.subList(0, 10));
                    }
                }
                
                topTerms.sort((a, b) -> Integer.compare(b.docFreq, a.docFreq));
                for (int i = 0; i < Math.min(3, topTerms.size()); i++) {
                    TermInfo ti = topTerms.get(i);
                    String t = ti.term.length() > 12 ? ti.term.substring(0, 9) + "..." : ti.term;
                    topValues.add(t + "(" + ti.docFreq + ")");
                }
                
                totalTerms += termCount;
                insights.add(new FieldInsight(fieldName, termCount, docCount, coverage, cardinalityRatio, topValues));
            }
        }
        
        // Sort by term count
        insights.sort((a, b) -> Long.compare(b.termCount, a.termCount));
        
        // Calculate percentages and suggestions
        for (FieldInsight fi : insights) {
            fi.percentage = totalTerms > 0 ? (fi.termCount * 100.0 / totalTerms) : 0;
            fi.suggestion = generateSuggestion(fi);
        }
        
        // Output
        System.out.println("╔═══════════════════════════════════════════════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║                              INDEX INSIGHTS - ACTIONABLE OPTIMIZATION GUIDE                                            ║");
        System.out.println("╠═══════════════════════════════════════════════════════════════════════════════════════════════════════════════════════╣");
        System.out.printf("║ Index: %-108s ║%n", indexDir.getAbsolutePath().length() > 108 ? 
                "..." + indexDir.getAbsolutePath().substring(indexDir.getAbsolutePath().length() - 105) : indexDir.getAbsolutePath());
        System.out.printf("║ Documents: %,d active | %,d deleted (%.1f%% waste) | Size: %s %52s ║%n", 
                numDocs, deletions, deleteRatio, humanReadableSize(size), "");
        System.out.println("╠════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════╣");
        System.out.println("║                                              TOP FIELDS BY SIZE                                                        ║");
        System.out.println("╠══════════════════════════╤═══════════════╤══════════╤══════════╤══════════════════════════════╤═══════════════════════╣");
        System.out.println("║ Field                    │ Terms (Size)  │ Coverage │ Card.    │ Top Values                   │ Suggestion            ║");
        System.out.println("╠══════════════════════════╪═══════════════╪══════════╪══════════╪══════════════════════════════╪═══════════════════════╣");
        
        int displayed = 0;
        long displayedTerms = 0;
        for (FieldInsight fi : insights) {
            if (displayed >= maxFields) break;
            
            String truncField = fi.fieldName.length() > 24 ? fi.fieldName.substring(0, 21) + "..." : fi.fieldName;
            String topVals = fi.topValues.isEmpty() ? "-" : String.join(", ", fi.topValues);
            if (topVals.length() > 28) topVals = topVals.substring(0, 25) + "...";
            String truncSug = fi.suggestion.length() > 21 ? fi.suggestion.substring(0, 18) + "..." : fi.suggestion;
            String cardStr = fi.cardinalityRatio > 1000 ? ">1000" : String.format("%.1f", fi.cardinalityRatio);
            
            System.out.printf("║ %-24s │ %,13d │ %6.1f%% │ %8s │ %-28s │ %-21s ║%n",
                    truncField, fi.termCount, fi.coverage, cardStr, topVals, truncSug);
            
            displayed++;
            displayedTerms += fi.termCount;
        }
        
        System.out.println("╠══════════════════════════╧═══════════════╧══════════╧══════════╧══════════════════════════════╧═══════════════════════╣");
        double displayedPct = totalTerms > 0 ? (displayedTerms * 100.0 / totalTerms) : 0;
        System.out.printf("║ Shown: Top %d fields = %,d terms (%.1f%% of total %,d terms) %50s ║%n", 
                displayed, displayedTerms, displayedPct, totalTerms, "");
        
        // Recommendations
        System.out.println("╠════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════╣");
        System.out.println("║                                           KEY RECOMMENDATIONS                                                          ║");
        System.out.println("╠════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════╣");
        
        List<String> recs = generateOverallRecommendations(insights, deleteRatio, numDocs);
        for (String rec : recs) {
            while (rec.length() > 116) {
                System.out.printf("║ %-116s ║%n", rec.substring(0, 116));
                rec = "    " + rec.substring(116);
            }
            System.out.printf("║ %-116s ║%n", rec);
        }
        
        System.out.println("╚════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════╝");
    }
    
    private void showContentDistribution(IndexReader reader, int depth) throws IOException {
        Fields fields = MultiFields.getFields(reader);
        Terms pathTerms = fields != null ? fields.terms(":path") : null;
        
        if (pathTerms == null) {
            System.out.println("ERROR: No :path field found in index. Cannot analyze content distribution.");
            System.out.println("This index may not have path-based content mapping enabled.");
            return;
        }
        
        Map<String, Integer> pathCounts = new HashMap<>();
        TermsEnum te = pathTerms.iterator(null);
        
        while (te.next() != null) {
            String path = te.term().utf8ToString();
            String prefix = getPathPrefix(path, depth);
            int docFreq = te.docFreq();
            pathCounts.merge(prefix, docFreq, Integer::sum);
        }
        
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(pathCounts.entrySet());
        sorted.sort((a, b) -> b.getValue().compareTo(a.getValue()));
        
        int totalDocs = reader.numDocs();
        
        System.out.println("═══════════════════════════════════════════════════════════════════════════════════════");
        System.out.println("CONTENT DISTRIBUTION ANALYSIS");
        System.out.println("═══════════════════════════════════════════════════════════════════════════════════════");
        System.out.printf("Analysis Depth: %d path segments%n", depth);
        System.out.printf("Total Documents: %,d%n%n", totalDocs);
        
        System.out.printf("%-60s | %10s | %7s | %s%n", "Content Path", "Documents", "% Total", "Bar");
        System.out.println(repeatChar('-', 60) + "-+-" + repeatChar('-', 10) + "-+-" + repeatChar('-', 7) + "-+-" + repeatChar('-', 20));
        
        int shown = 0;
        for (Map.Entry<String, Integer> entry : sorted) {
            if (shown >= 25) break;
            
            String path = entry.getKey();
            int count = entry.getValue();
            double pct = totalDocs > 0 ? (count * 100.0 / totalDocs) : 0;
            int barLen = (int) (pct / 5);
            String bar = repeatChar('█', barLen);
            
            String truncPath = path.length() > 58 ? "..." + path.substring(path.length() - 55) : path;
            System.out.printf("%-60s | %,10d | %6.1f%% | %s%n", truncPath, count, pct, bar);
            shown++;
        }
        
        if (sorted.size() > 25) {
            System.out.printf("... and %d more paths%n", sorted.size() - 25);
        }
        
        // Insights
        System.out.println();
        System.out.println("INSIGHTS:");
        if (!sorted.isEmpty()) {
            Map.Entry<String, Integer> top = sorted.get(0);
            double topPct = totalDocs > 0 ? (top.getValue() * 100.0 / totalDocs) : 0;
            System.out.printf("• Top content area: %s (%.1f%% of index)%n", top.getKey(), topPct);
            
            if (topPct > 80) {
                System.out.println("  ⚠️ Index is heavily concentrated in one area. Consider splitting indexes.");
            }
            
            for (Map.Entry<String, Integer> entry : sorted) {
                if (entry.getKey().contains("/dam")) {
                    double damPct = totalDocs > 0 ? (entry.getValue() * 100.0 / totalDocs) : 0;
                    System.out.printf("• DAM content: %.1f%% - %s%n", damPct,
                            damPct > 30 ? "Heavy DAM usage. Ensure pre-extracted cache for re-indexing." : "Moderate DAM content.");
                    break;
                }
            }
        }
        System.out.println("═══════════════════════════════════════════════════════════════════════════════════════");
    }
    
    private void showCardinality(IndexReader reader, int maxFields) throws IOException {
        int numDocs = reader.numDocs();
        List<CardinalityInfo> results = new ArrayList<>();
        
        Fields fields = MultiFields.getFields(reader);
        if (fields != null) {
            for (String fieldName : fields) {
                Terms terms = fields.terms(fieldName);
                if (terms == null) continue;
                
                long uniqueTerms = terms.size();
                if (uniqueTerms < 0) uniqueTerms = countTermsManually(terms);
                
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
        
        results.sort((a, b) -> Double.compare(b.cardinality, a.cardinality));
        
        System.out.println("═══════════════════════════════════════════════════════════════════════════════════════");
        System.out.println("FIELD CARDINALITY ANALYSIS");
        System.out.println("═══════════════════════════════════════════════════════════════════════════════════════");
        System.out.printf("Total Docs: %,d%n%n", numDocs);
        System.out.println("Cardinality = Unique Values / Documents. High cardinality = memory intensive.");
        System.out.println();
        
        System.out.printf("%-40s | %12s | %10s | %8s | %10s | %s%n", 
                "Field", "Unique Terms", "Doc Count", "Coverage", "Cardinality", "Status");
        System.out.println(repeatChar('-', 40) + "-+-" + repeatChar('-', 12) + "-+-" + repeatChar('-', 10) + 
                   "-+-" + repeatChar('-', 8) + "-+-" + repeatChar('-', 10) + "-+-" + repeatChar('-', 18));
        
        int shown = 0;
        for (CardinalityInfo ci : results) {
            if (shown >= maxFields) break;
            
            String truncField = ci.fieldName.length() > 38 ? ci.fieldName.substring(0, 35) + "..." : ci.fieldName;
            String cardStr = ci.cardinality > 1000 ? String.format("%,.0f", ci.cardinality) : String.format("%.2f", ci.cardinality);
            
            System.out.printf("%-40s | %,12d | %,10d | %6.1f%% | %10s | %s%n",
                    truncField, ci.uniqueTerms, ci.docCount, ci.coverage, cardStr, ci.warning);
            shown++;
        }
        
        System.out.println();
        System.out.println("LEGEND: ⚠️ HIGH CARDINALITY (>100) = Consider faceted search or filtering");
        System.out.println("        ⚡ MODERATE (>10) = Monitor for growth");
        System.out.println("        📉 SPARSE (<5% coverage, <1k docs) = Consider if field is needed");
        System.out.println("═══════════════════════════════════════════════════════════════════════════════════════");
    }
    
    private void showDuplicates(IndexReader reader, String fieldName, int maxResults) throws IOException {
        Fields fields = MultiFields.getFields(reader);
        Terms terms = fields != null ? fields.terms(fieldName) : null;
        
        if (terms == null) {
            System.out.println("ERROR: Field '" + fieldName + "' not found in index");
            return;
        }
        
        Map<String, List<TermWithCount>> normalizedGroups = new HashMap<>();
        TermsEnum te = terms.iterator(null);
        
        while (te.next() != null) {
            String termText = te.term().utf8ToString();
            int docFreq = te.docFreq();
            String normalized = termText.toLowerCase().trim().replaceAll("\\s+", " ");
            normalizedGroups.computeIfAbsent(normalized, k -> new ArrayList<>()).add(new TermWithCount(termText, docFreq));
        }
        
        List<DuplicateGroup> duplicates = new ArrayList<>();
        for (Map.Entry<String, List<TermWithCount>> entry : normalizedGroups.entrySet()) {
            if (entry.getValue().size() > 1) {
                int totalDocs = 0;
                for (TermWithCount twc : entry.getValue()) {
                    totalDocs += twc.docFreq;
                }
                duplicates.add(new DuplicateGroup(entry.getKey(), entry.getValue(), totalDocs));
            }
        }
        
        duplicates.sort((a, b) -> Integer.compare(b.totalDocs, a.totalDocs));
        
        System.out.println("═══════════════════════════════════════════════════════════════════════════════════════");
        System.out.println("DUPLICATE VALUE DETECTION");
        System.out.println("═══════════════════════════════════════════════════════════════════════════════════════");
        System.out.printf("Field: %s%n%n", fieldName);
        
        if (duplicates.isEmpty()) {
            System.out.println("✅ No duplicate variations found in this field.");
            System.out.println("   All values appear to be unique (case-sensitive, whitespace-normalized).");
        } else {
            System.out.printf("⚠️ Found %d groups of potential duplicates:%n%n", duplicates.size());
            
            int shown = 0;
            for (DuplicateGroup dg : duplicates) {
                if (shown >= maxResults) break;
                
                String displayNorm = dg.normalized.length() > 40 ? dg.normalized.substring(0, 37) + "..." : dg.normalized;
                System.out.printf("Group '%s' (%,d total docs):%n", displayNorm, dg.totalDocs);
                
                for (TermWithCount twc : dg.variants) {
                    String display = twc.term.length() > 50 ? twc.term.substring(0, 47) + "..." : twc.term;
                    System.out.printf("    • \"%s\" (%,d docs)%n", display, twc.docFreq);
                }
                System.out.println();
                shown++;
            }
            
            if (duplicates.size() > maxResults) {
                System.out.printf("... and %d more duplicate groups%n%n", duplicates.size() - maxResults);
            }
            
            System.out.println("RECOMMENDATION: Consider normalizing these values during indexing");
            System.out.println("                or implementing a custom analyzer with case-folding.");
        }
        System.out.println("═══════════════════════════════════════════════════════════════════════════════════════");
    }
    
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
        if (fi.cardinalityRatio > 100) {
            if (fi.fieldName.contains("fulltext") || fi.fieldName.equals(":fulltext")) {
                return "Use pre-extracted cache";
            }
            return "Consider faceting";
        }
        if (fi.termCount > 10_000_000) return "Major bloat - review";
        if (fi.coverage < 5) return "Sparse - needed?";
        if (fi.fieldName.contains("fulltext")) {
            return fi.termCount > 1_000_000 ? "Add stemming/synonyms" : "OK - fulltext";
        }
        if (fi.percentage > 30) return "Dominates index";
        return "OK";
    }
    
    private List<String> generateOverallRecommendations(List<FieldInsight> insights, double deleteRatio, int numDocs) {
        List<String> recs = new ArrayList<>();
        
        if (deleteRatio > 20) {
            recs.add(String.format("⚠️ HIGH DELETION RATIO (%.1f%%): Consider running compaction/optimization to reclaim space.", deleteRatio));
        }
        
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
        
        int highCardCount = 0;
        for (FieldInsight fi : insights) {
            if (fi.cardinalityRatio > 100 && !fi.fieldName.contains("fulltext")) highCardCount++;
        }
        
        if (highCardCount > 0) {
            recs.add(String.format("🔢 %d HIGH-CARDINALITY FIELDS: These consume extra memory. Consider if all need to be indexed.", highCardCount));
        }
        
        if (!insights.isEmpty() && insights.get(0).percentage > 50) {
            recs.add(String.format("📊 INDEX DOMINATED BY '%s' (%.1f%%): This field drives most of your index size.", 
                    insights.get(0).fieldName, insights.get(0).percentage));
        }
        
        if (numDocs > 1_000_000) {
            recs.add("🏗️ LARGE INDEX (>1M docs): Consider async indexing, dedicated index lanes, or split indexes.");
        }
        
        if (recs.isEmpty()) {
            recs.add("✅ INDEX LOOKS HEALTHY: No major issues detected.");
        }
        
        return recs;
    }

    // ============================================================
    // HELPER METHODS
    // ============================================================
    
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
    
    private String humanReadableSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }
    
    private String repeatChar(char c, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, c);
        return new String(chars);
    }
    
    // Helper classes
    private static class FieldTermCount {
        final String fieldName;
        final long termCount;
        
        FieldTermCount(String fieldName, long termCount) {
            this.fieldName = fieldName;
            this.termCount = termCount;
        }
    }
    
    private static class TermInfo {
        final String term;
        final int docFreq;
        
        TermInfo(String term, int docFreq) {
            this.term = term;
            this.docFreq = docFreq;
        }
    }
    
    private static class FieldInsight {
        final String fieldName;
        final long termCount;
        @SuppressWarnings("unused")
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
}
