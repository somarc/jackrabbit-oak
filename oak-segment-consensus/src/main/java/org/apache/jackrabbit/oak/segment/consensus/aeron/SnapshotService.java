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
package org.apache.jackrabbit.oak.segment.consensus.aeron;

import io.aeron.ExclusivePublication;
import io.aeron.Image;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.UnsafeBuffer;
import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;

/**
 * Service responsible for creating and restoring Aeron Cluster snapshots.
 * 
 * <p>Extracted from AeronConsensusEngine to isolate snapshot logic and improve testability.
 * Handles streaming TAR files and journal.log to snapshot publication, and restoring
 * state from snapshot images.
 * 
 * <p><strong>OSGi Component:</strong> Lifecycle managed, requires FileStore injection.
 * 
 * <p><strong>Responsibilities:</strong>
 * <ul>
 *   <li>Stream FileStore TAR files to Aeron snapshot publication</li>
 *   <li>Stream journal.log to snapshot publication</li>
 *   <li>Restore FileStore state from snapshot image</li>
 *   <li>Handle snapshot metadata (HEAD, epoch, timestamp)</li>
 * </ul>
 */
@Component(
    service = SnapshotService.class,
    immediate = true,
    configurationPolicy = ConfigurationPolicy.OPTIONAL,
    property = {
        "service.description=Aeron Snapshot Service",
        "service.vendor=Apache Software Foundation"
    }
)
public class SnapshotService {
    
    private static final Logger log = LoggerFactory.getLogger(SnapshotService.class);
    
    private FileStore fileStore;
    private String storeDirectory;
    
    /**
     * Snapshot state metadata.
     */
    public static class SnapshotState {
        public final String head;
        public final int epoch;
        public final long timestamp;
        public final int fileCount;
        
        public SnapshotState(String head, int epoch, long timestamp, int fileCount) {
            this.head = head;
            this.epoch = epoch;
            this.timestamp = timestamp;
            this.fileCount = fileCount;
        }
    }
    
    /**
     * Create a new snapshot service (default constructor for OSGi).
     */
    public SnapshotService() {
        this.fileStore = null;
        this.storeDirectory = null;
    }
    
    /**
     * Create a new snapshot service with dependencies (for programmatic use).
     * 
     * @param fileStore Oak FileStore instance
     * @param storeDirectory path to segment store directory
     */
    public SnapshotService(FileStore fileStore, String storeDirectory) {
        this.fileStore = fileStore;
        this.storeDirectory = storeDirectory;
    }
    
    /**
     * OSGi lifecycle: Activate component.
     */
    @Activate
    protected void activate() {
        log.info("✅ SnapshotService activated: directory={}", storeDirectory);
    }
    
    /**
     * OSGi lifecycle: Deactivate component.
     */
    @Deactivate
    protected void deactivate() {
        log.info("✅ SnapshotService deactivated");
    }
    
    /**
     * Set FileStore (for OSGi injection).
     */
    public void setFileStore(FileStore fileStore) {
        this.fileStore = fileStore;
        log.debug("FileStore injected");
    }
    
    /**
     * Set store directory (for OSGi injection).
     */
    public void setStoreDirectory(String storeDirectory) {
        this.storeDirectory = storeDirectory;
        log.debug("Store directory set: {}", storeDirectory);
    }
    
    /**
     * Create a snapshot and stream to Aeron publication.
     * 
     * @param pub snapshot publication
     * @param idleStrategy idle strategy for publication
     * @throws Exception if snapshot creation fails
     */
    public void createSnapshot(ExclusivePublication pub, IdleStrategy idleStrategy) throws Exception {
        log.info("📸 Creating Aeron snapshot...");
        
        // Get current HEAD
        String currentHead = fileStore.getHead().getRecordId().toString();
        int currentEpoch = 0; // TODO: get from epoch tracker
        long currentTimestamp = System.currentTimeMillis();
        
        // Send metadata first
        sendSnapshotMetadata(pub, idleStrategy, currentHead, currentEpoch, currentTimestamp);
        
        // Stream TAR files
        streamTarFiles(pub, idleStrategy);
        
        // Stream journal.log
        streamJournal(pub, idleStrategy);
        
        log.info("✅ Snapshot complete: head={}, epoch={}", currentHead, currentEpoch);
    }
    
    /**
     * Restore state from snapshot image.
     * 
     * @param snapshotImage Aeron snapshot image
     * @return snapshot state metadata
     */
    public SnapshotState restoreSnapshot(Image snapshotImage) {
        log.info("📦 Restoring from Aeron snapshot...");
        
        // TODO: Implement snapshot restoration
        // This would:
        // 1. Read metadata frame
        // 2. Read TAR file frames and write to storeDirectory
        // 3. Read journal.log frame and write to storeDirectory
        // 4. Return SnapshotState
        
        log.warn("Snapshot restoration not yet implemented");
        return new SnapshotState(null, 0, System.currentTimeMillis(), 0);
    }
    
    /**
     * Send snapshot metadata frame.
     */
    private void sendSnapshotMetadata(ExclusivePublication pub, IdleStrategy idleStrategy,
                                     String head, int epoch, long timestamp) throws Exception {
        
        String json = String.format("{\"type\":\"metadata\",\"head\":\"%s\",\"epoch\":%d,\"timestamp\":%d}",
                                   head, epoch, timestamp);
        
        byte[] jsonBytes = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        UnsafeBuffer buffer = new UnsafeBuffer(new byte[jsonBytes.length + 4]);
        buffer.putInt(0, jsonBytes.length);
        buffer.putBytes(4, jsonBytes);
        
        offerWithRetry(pub, idleStrategy, buffer, 0, buffer.capacity());
        
        log.debug("📤 Sent snapshot metadata: head={}", head);
    }
    
    /**
     * Stream all TAR files to snapshot publication.
     */
    private void streamTarFiles(ExclusivePublication pub, IdleStrategy idleStrategy) throws Exception {
        File storeDir = new File(storeDirectory);
        File[] tarFiles = storeDir.listFiles((dir, name) -> name.endsWith(".tar"));
        
        if (tarFiles == null || tarFiles.length == 0) {
            log.debug("No TAR files to stream");
            return;
        }
        
        log.info("📤 Streaming {} TAR files...", tarFiles.length);
        
        for (File tarFile : tarFiles) {
            streamFile(pub, idleStrategy, tarFile, "tar");
        }
    }
    
    /**
     * Stream journal.log to snapshot publication.
     */
    private void streamJournal(ExclusivePublication pub, IdleStrategy idleStrategy) throws Exception {
        File journalFile = new File(storeDirectory, "journal.log");
        
        if (!journalFile.exists()) {
            log.debug("No journal.log to stream");
            return;
        }
        
        log.info("📤 Streaming journal.log...");
        streamFile(pub, idleStrategy, journalFile, "journal");
    }
    
    /**
     * Stream a single file to snapshot publication.
     */
    private void streamFile(ExclusivePublication pub, IdleStrategy idleStrategy, 
                           File file, String fileType) throws Exception {
        
        final int CHUNK_SIZE = 1024 * 1024; // 1 MB chunks
        byte[] chunkBuffer = new byte[CHUNK_SIZE];
        
        try (FileInputStream fis = new FileInputStream(file)) {
            long totalBytes = 0;
            int bytesRead;
            
            while ((bytesRead = fis.read(chunkBuffer)) != -1) {
                // Frame format: [type:4][filename_len:4][filename:N][data_len:4][data:N]
                byte[] filenameBytes = file.getName().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                int frameSize = 4 + 4 + filenameBytes.length + 4 + bytesRead;
                
                UnsafeBuffer buffer = new UnsafeBuffer(new byte[frameSize]);
                int offset = 0;
                
                // Type
                buffer.putInt(offset, fileType.hashCode());
                offset += 4;
                
                // Filename length + filename
                buffer.putInt(offset, filenameBytes.length);
                offset += 4;
                buffer.putBytes(offset, filenameBytes);
                offset += filenameBytes.length;
                
                // Data length + data
                buffer.putInt(offset, bytesRead);
                offset += 4;
                buffer.putBytes(offset, chunkBuffer, 0, bytesRead);
                
                offerWithRetry(pub, idleStrategy, buffer, 0, frameSize);
                
                totalBytes += bytesRead;
            }
            
            log.debug("✅ Streamed {}: {} bytes", file.getName(), totalBytes);
        }
    }
    
    /**
     * Offer buffer to publication with retry.
     */
    private void offerWithRetry(ExclusivePublication pub, IdleStrategy idleStrategy,
                               UnsafeBuffer buffer, int offset, int length) {
        
        idleStrategy.reset();
        while (pub.offer(buffer, offset, length) < 0) {
            idleStrategy.idle();
        }
    }
}

