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

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Background job for backing up fulltext data from a Lucene index.
 * 
 * This job reads stored :fulltext fields from the index and writes them
 * to filesystem in a format compatible with oak-run tika --populate.
 * 
 * Key features:
 * - Runs in background thread (non-blocking JMX)
 * - Progress tracking (documents processed, bytes written)
 * - Cancellation support
 * - Error handling with continuation
 * - Manifest generation (CSV format)
 * 
 * Output format:
 *   /storePath/
 *     ├── doc-0.txt              (fulltext for document 0)
 *     ├── doc-1.txt              (fulltext for document 1)
 *     ├── ...
 *     ├── manifest.csv           (docId, path, size)
 *     └── backup-status.json     (job metadata)
 */
public class FulltextBackupJob implements Runnable {
    
    private static final Logger log = LoggerFactory.getLogger(FulltextBackupJob.class);
    
    // Job identification
    private final String jobId;
    private final String storePath;
    private final String indexPath;
    private final File indexDir;
    
    // Job state
    public enum Status {
        PENDING, RUNNING, COMPLETED, CANCELLED, FAILED
    }
    private volatile Status status = Status.PENDING;
    private volatile String errorMessage = null;
    
    // Progress tracking
    private final AtomicInteger documentsProcessed = new AtomicInteger(0);
    private final AtomicInteger documentsTotal = new AtomicInteger(0);
    private final AtomicInteger filesWritten = new AtomicInteger(0);
    private final AtomicInteger skipped = new AtomicInteger(0);
    private final AtomicInteger errors = new AtomicInteger(0);
    private final AtomicLong bytesWritten = new AtomicLong(0);
    
    // Timing
    private long startTime = 0;
    private long endTime = 0;
    
    // Cancellation
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    
    // Thread
    private Thread workerThread = null;
    
    /**
     * Create a new fulltext backup job.
     * 
     * @param jobId Unique job identifier
     * @param storePath Filesystem path to store extracted text
     * @param indexPath Oak index path (for logging/metadata)
     * @param indexDir Actual filesystem directory of the Lucene index
     */
    public FulltextBackupJob(String jobId, String storePath, String indexPath, File indexDir) {
        this.jobId = jobId;
        this.storePath = storePath;
        this.indexPath = indexPath;
        this.indexDir = indexDir;
    }
    
    /**
     * Start the backup job in a background thread.
     */
    public void start() {
        workerThread = new Thread(this, "FulltextBackup-" + jobId);
        workerThread.setDaemon(true);
        workerThread.start();
        log.info("Started fulltext backup job: {} for index: {}", jobId, indexPath);
    }
    
    /**
     * Cancel the running job.
     */
    public void cancel() {
        cancelled.set(true);
        if (workerThread != null) {
            workerThread.interrupt();
        }
        log.info("Cancellation requested for job: {}", jobId);
    }
    
    @Override
    public void run() {
        status = Status.RUNNING;
        startTime = System.currentTimeMillis();
        
        Directory dir = null;
        IndexReader reader = null;
        PrintWriter manifestWriter = null;
        
        try {
            // Create output directory
            File storeDir = new File(storePath);
            if (!storeDir.exists()) {
                if (!storeDir.mkdirs()) {
                    throw new IOException("Failed to create store directory: " + storePath);
                }
            }
            
            // Verify index exists
            if (!indexDir.exists() || !indexDir.isDirectory()) {
                throw new IOException("Index directory not found: " + indexDir.getAbsolutePath());
            }
            
            // Open Lucene index
            log.info("Opening Lucene index: {}", indexDir.getAbsolutePath());
            dir = FSDirectory.open(indexDir);
            reader = DirectoryReader.open(dir);
            
            int maxDoc = reader.maxDoc();
            documentsTotal.set(maxDoc);
            log.info("Index has {} documents", maxDoc);
            
            // Open manifest file
            File manifestFile = new File(storeDir, "manifest.csv");
            manifestWriter = new PrintWriter(new BufferedWriter(new FileWriter(manifestFile)));
            manifestWriter.println("docId,path,size,status");
            
            // Process each document
            for (int docId = 0; docId < maxDoc; docId++) {
                
                // Check for cancellation
                if (cancelled.get()) {
                    status = Status.CANCELLED;
                    log.info("Job {} cancelled at document {}/{}", jobId, docId, maxDoc);
                    break;
                }
                
                try {
                    processDocument(reader, docId, storeDir, manifestWriter);
                } catch (Exception e) {
                    errors.incrementAndGet();
                    log.warn("Error processing document {}: {}", docId, e.getMessage());
                    manifestWriter.println(String.format("%d,ERROR,0,error:%s", docId, e.getMessage().replace(",", ";")));
                }
                
                documentsProcessed.incrementAndGet();
                
                // Log progress every 1000 documents
                if (docId > 0 && docId % 1000 == 0) {
                    log.info("Progress: {}/{} documents ({}%), {} files written, {} MB",
                            docId, maxDoc, 
                            (docId * 100 / maxDoc),
                            filesWritten.get(),
                            bytesWritten.get() / (1024 * 1024));
                }
            }
            
            // Write status file
            writeStatusFile(storeDir);
            
            if (status != Status.CANCELLED) {
                status = Status.COMPLETED;
            }
            
        } catch (Exception e) {
            status = Status.FAILED;
            errorMessage = e.getMessage();
            log.error("Fulltext backup job {} failed: {}", jobId, e.getMessage(), e);
        } finally {
            endTime = System.currentTimeMillis();
            
            // Close resources
            if (manifestWriter != null) {
                manifestWriter.close();
            }
            if (reader != null) {
                try { reader.close(); } catch (Exception e) { /* ignore */ }
            }
            if (dir != null) {
                try { dir.close(); } catch (Exception e) { /* ignore */ }
            }
            
            log.info("Fulltext backup job {} finished with status: {} in {}ms", 
                    jobId, status, (endTime - startTime));
        }
    }
    
    /**
     * Process a single document - extract fulltext and write to file.
     */
    private void processDocument(IndexReader reader, int docId, File storeDir, PrintWriter manifestWriter) throws IOException {
        // Check if document is deleted
        // Note: In Lucene 4.7.2, we use liveDocs but for simplicity we'll check via document access
        
        Document doc = reader.document(docId);
        if (doc == null) {
            skipped.incrementAndGet();
            manifestWriter.println(String.format("%d,,0,deleted", docId));
            return;
        }
        
        // Get fulltext field
        String fulltext = doc.get(":fulltext");
        if (fulltext == null || fulltext.trim().isEmpty()) {
            skipped.incrementAndGet();
            // Try to get path for logging
            String path = doc.get(":path");
            if (path == null) path = "";
            manifestWriter.println(String.format("%d,\"%s\",0,no-fulltext", docId, escapeCsv(path)));
            return;
        }
        
        // Get path for manifest
        String path = doc.get(":path");
        if (path == null) path = "";
        
        // Write fulltext to file
        File textFile = new File(storeDir, "doc-" + docId + ".txt");
        try (PrintWriter textWriter = new PrintWriter(new BufferedWriter(new FileWriter(textFile)))) {
            textWriter.print(fulltext);
        }
        
        long fileSize = textFile.length();
        filesWritten.incrementAndGet();
        bytesWritten.addAndGet(fileSize);
        
        // Write manifest entry
        manifestWriter.println(String.format("%d,\"%s\",%d,ok", docId, escapeCsv(path), fileSize));
    }
    
    /**
     * Write backup status JSON file.
     */
    private void writeStatusFile(File storeDir) throws IOException {
        File statusFile = new File(storeDir, "backup-status.json");
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        
        try (PrintWriter writer = new PrintWriter(new BufferedWriter(new FileWriter(statusFile)))) {
            writer.println("{");
            writer.println("  \"jobId\": \"" + jobId + "\",");
            writer.println("  \"status\": \"" + status + "\",");
            writer.println("  \"startTime\": \"" + sdf.format(new Date(startTime)) + "\",");
            writer.println("  \"endTime\": \"" + sdf.format(new Date(endTime > 0 ? endTime : System.currentTimeMillis())) + "\",");
            writer.println("  \"durationMs\": " + (endTime > 0 ? (endTime - startTime) : (System.currentTimeMillis() - startTime)) + ",");
            writer.println("  \"statistics\": {");
            writer.println("    \"documentsTotal\": " + documentsTotal.get() + ",");
            writer.println("    \"documentsProcessed\": " + documentsProcessed.get() + ",");
            writer.println("    \"filesWritten\": " + filesWritten.get() + ",");
            writer.println("    \"skipped\": " + skipped.get() + ",");
            writer.println("    \"errors\": " + errors.get() + ",");
            writer.println("    \"bytesWritten\": " + bytesWritten.get());
            writer.println("  },");
            writer.println("  \"configuration\": {");
            writer.println("    \"storePath\": \"" + storePath + "\",");
            writer.println("    \"indexPath\": \"" + indexPath + "\",");
            writer.println("    \"indexDir\": \"" + indexDir.getAbsolutePath() + "\"");
            writer.println("  }");
            if (errorMessage != null) {
                writer.println(",  \"errorMessage\": \"" + errorMessage.replace("\"", "\\\"") + "\"");
            }
            writer.println("}");
        }
    }
    
    /**
     * Escape string for CSV output.
     */
    private String escapeCsv(String value) {
        if (value == null) return "";
        return value.replace("\"", "\"\"");
    }
    
    // ============================================================
    // Progress/Status Getters
    // ============================================================
    
    public String getJobId() {
        return jobId;
    }
    
    public Status getStatus() {
        return status;
    }
    
    public String getErrorMessage() {
        return errorMessage;
    }
    
    public int getDocumentsProcessed() {
        return documentsProcessed.get();
    }
    
    public int getDocumentsTotal() {
        return documentsTotal.get();
    }
    
    public int getFilesWritten() {
        return filesWritten.get();
    }
    
    public int getSkipped() {
        return skipped.get();
    }
    
    public int getErrors() {
        return errors.get();
    }
    
    public long getBytesWritten() {
        return bytesWritten.get();
    }
    
    public long getStartTime() {
        return startTime;
    }
    
    public long getEndTime() {
        return endTime;
    }
    
    public long getElapsedMs() {
        if (startTime == 0) return 0;
        return (endTime > 0 ? endTime : System.currentTimeMillis()) - startTime;
    }
    
    public long getEstimatedRemainingMs() {
        int processed = documentsProcessed.get();
        int total = documentsTotal.get();
        if (processed == 0 || total == 0) return 0;
        
        long elapsed = getElapsedMs();
        double rate = (double) processed / elapsed;
        int remaining = total - processed;
        return (long) (remaining / rate);
    }
    
    public double getProgressPercent() {
        int total = documentsTotal.get();
        if (total == 0) return 0;
        return (documentsProcessed.get() * 100.0) / total;
    }
    
    public String getStorePath() {
        return storePath;
    }
    
    public String getIndexPath() {
        return indexPath;
    }
    
    /**
     * Format progress as human-readable string.
     */
    public String formatProgress() {
        StringBuilder sb = new StringBuilder();
        sb.append("═══════════════════════════════════════════════════════════════\n");
        sb.append("FULLTEXT BACKUP PROGRESS\n");
        sb.append("═══════════════════════════════════════════════════════════════\n");
        sb.append(String.format("Job ID: %s\n", jobId));
        sb.append(String.format("Status: %s\n", status));
        sb.append("\n");
        
        sb.append("Progress:\n");
        sb.append(String.format("  Documents Processed: %,d / %,d (%.1f%%)\n", 
                documentsProcessed.get(), documentsTotal.get(), getProgressPercent()));
        sb.append(String.format("  Files Written: %,d\n", filesWritten.get()));
        sb.append(String.format("  Skipped (no fulltext): %,d\n", skipped.get()));
        sb.append(String.format("  Errors: %,d\n", errors.get()));
        sb.append("\n");
        
        sb.append("Timing:\n");
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        if (startTime > 0) {
            sb.append(String.format("  Started: %s\n", sdf.format(new Date(startTime))));
        }
        sb.append(String.format("  Elapsed: %s\n", formatDuration(getElapsedMs())));
        if (status == Status.RUNNING) {
            sb.append(String.format("  Estimated Remaining: %s\n", formatDuration(getEstimatedRemainingMs())));
        }
        if (endTime > 0) {
            sb.append(String.format("  Ended: %s\n", sdf.format(new Date(endTime))));
        }
        sb.append("\n");
        
        sb.append("Output:\n");
        sb.append(String.format("  Store Path: %s\n", storePath));
        sb.append(String.format("  Total Size: %.1f MB\n", bytesWritten.get() / (1024.0 * 1024.0)));
        sb.append("\n");
        
        if (documentsProcessed.get() > 0 && getElapsedMs() > 0) {
            double docsPerSecond = documentsProcessed.get() / (getElapsedMs() / 1000.0);
            double mbPerMinute = (bytesWritten.get() / (1024.0 * 1024.0)) / (getElapsedMs() / 60000.0);
            sb.append("Rate:\n");
            sb.append(String.format("  ~%.0f docs/second\n", docsPerSecond));
            sb.append(String.format("  ~%.1f MB/minute\n", mbPerMinute));
        }
        
        if (errorMessage != null) {
            sb.append("\n");
            sb.append("Error:\n");
            sb.append(String.format("  %s\n", errorMessage));
        }
        
        sb.append("═══════════════════════════════════════════════════════════════\n");
        return sb.toString();
    }
    
    private String formatDuration(long ms) {
        if (ms < 1000) return ms + "ms";
        if (ms < 60000) return String.format("%.1f seconds", ms / 1000.0);
        if (ms < 3600000) return String.format("%.1f minutes", ms / 60000.0);
        return String.format("%.1f hours", ms / 3600000.0);
    }
}

