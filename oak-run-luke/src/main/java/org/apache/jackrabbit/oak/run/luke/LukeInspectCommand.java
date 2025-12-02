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
            if (options.has(healthOpt)) {
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
        System.out.println("Examples:");
        System.out.println("  # Basic overview");
        System.out.println("  java -jar oak-run-luke.jar inspect crx-quickstart/repository/index/damAssetLucene-*/data");
        System.out.println();
        System.out.println("  # Health check");
        System.out.println("  java -jar oak-run-luke.jar inspect /path/to/index --health");
        System.out.println();
        System.out.println("  # Show all fields");
        System.out.println("  java -jar oak-run-luke.jar inspect /path/to/index --fields");
        System.out.println();
        System.out.println("  # Analyze specific field");
        System.out.println("  java -jar oak-run-luke.jar inspect /path/to/index --field :fulltext --top-terms 50");
        System.out.println();
        System.out.println("  # Sample documents");
        System.out.println("  java -jar oak-run-luke.jar inspect /path/to/index --sample 5");
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
}
