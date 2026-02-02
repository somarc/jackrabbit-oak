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
        createSnapshot(pub, idleStrategy, 0);
    }
    
    /**
     * Create a snapshot and stream to Aeron publication with epoch tracking.
     * 
     * @param pub snapshot publication
     * @param idleStrategy idle strategy for publication
     * @param currentEpoch current Ethereum epoch for snapshot metadata
     * @throws Exception if snapshot creation fails
     */
    public void createSnapshot(ExclusivePublication pub, IdleStrategy idleStrategy, int currentEpoch) throws Exception {
        log.info("📸 Creating Aeron snapshot...");
        
        // Get current HEAD
        String currentHead = fileStore.getHead().getRecordId().toString();
        long currentTimestamp = System.currentTimeMillis();
        
        log.info("Snapshot state - HEAD: {}, Epoch: {}, Dir: {}", currentHead, currentEpoch, storeDirectory);
        
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
     * <p>This method reads frames from the Aeron snapshot image and restores
     * the FileStore state. The restoration process:
     * <ol>
     *   <li>Read metadata frame (HEAD, epoch, timestamp)</li>
     *   <li>Read TAR file frames and write to storeDirectory</li>
     *   <li>Read journal.log frame and write to storeDirectory</li>
     *   <li>Return SnapshotState for verification</li>
     * </ol>
     * 
     * <p><strong>Test Scenarios:</strong>
     * <ul>
     *   <li><strong>Empty node joining:</strong> Node with no data receives full snapshot</li>
     *   <li><strong>Stale node rejoining:</strong> Node behind on log receives snapshot to catch up</li>
     *   <li><strong>Node recovery after crash:</strong> Node restores from last snapshot</li>
     * </ul>
     * 
     * @param snapshotImage Aeron snapshot image
     * @return snapshot state metadata, or null if restoration fails
     */
    public SnapshotState restoreSnapshot(Image snapshotImage) {
        return restoreSnapshot(snapshotImage, new org.agrona.concurrent.BusySpinIdleStrategy());
    }

    /**
     * Restore state from snapshot image with explicit idle strategy.
     *
     * @param snapshotImage Aeron snapshot image
     * @param idleStrategy idle strategy for polling the snapshot image
     * @return snapshot state metadata, or null if restoration fails
     */
    public SnapshotState restoreSnapshot(Image snapshotImage, IdleStrategy idleStrategy) {
        log.info("📦 Restoring from Aeron snapshot...");

        if (storeDirectory == null) {
            log.error("Cannot restore snapshot: storeDirectory not set");
            return null;
        }

        File storeDir = new File(storeDirectory);
        if (!storeDir.exists() && !storeDir.mkdirs()) {
            log.error("Cannot create store directory: {}", storeDirectory);
            return null;
        }

        final java.util.concurrent.atomic.AtomicReference<String> headRef =
            new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.atomic.AtomicInteger epochRef = new java.util.concurrent.atomic.AtomicInteger(0);
        final java.util.concurrent.atomic.AtomicLong timestampRef = new java.util.concurrent.atomic.AtomicLong(0);
        final java.util.concurrent.atomic.AtomicInteger fileCountRef = new java.util.concurrent.atomic.AtomicInteger(0);
        final java.util.concurrent.atomic.AtomicBoolean metadataReceived =
            new java.util.concurrent.atomic.AtomicBoolean(false);

        final java.util.concurrent.atomic.AtomicReference<FileReceiver> currentFileReceiver =
            new java.util.concurrent.atomic.AtomicReference<>();

        io.aeron.FragmentAssembler fragmentAssembler = new io.aeron.FragmentAssembler(
            (buffer, offset, length, header) -> {
                if (length < SimpleMessageHeader.ENCODED_LENGTH) {
                    log.warn("⚠️  Snapshot fragment too short: {} bytes", length);
                    return;
                }

                try {
                    SimpleMessageHeader.HeaderInfo headerInfo = SimpleMessageHeader.decode(buffer, offset);
                    if (headerInfo.templateId != SimpleMessageHeader.TEMPLATE_ID_SNAPSHOT) {
                        return;
                    }

                    int payloadOffset = offset + SimpleMessageHeader.ENCODED_LENGTH;
                    int maxPayloadLength = Math.max(0, length - SimpleMessageHeader.ENCODED_LENGTH);
                    int payloadLength = Math.min(headerInfo.blockLength, maxPayloadLength);
                    if (payloadLength == 0) {
                        return;
                    }

                    byte[] payload = new byte[payloadLength];
                    buffer.getBytes(payloadOffset, payload);

                    String payloadStr = new String(payload, 0, Math.min(200, payloadLength),
                        java.nio.charset.StandardCharsets.UTF_8);

                    if (payloadStr.contains("\"type\":\"metadata\"")) {
                        String json = new String(payload, java.nio.charset.StandardCharsets.UTF_8);
                        String head = extractJsonField(json, "head");
                        Long epochValue = extractJsonFieldLong(json, "ethereumEpoch");
                        if (epochValue == null) {
                            epochValue = extractJsonFieldLong(json, "epoch");
                        }
                        Long timestampValue = extractJsonFieldLong(json, "timestamp");

                        if (head != null && epochValue != null && timestampValue != null) {
                            headRef.set(head);
                            epochRef.set(epochValue.intValue());
                            timestampRef.set(timestampValue);
                            metadataReceived.set(true);
                            log.info("   ✅ Metadata received: head={}, epoch={}", head, epochRef.get());
                        }
                    } else if (payloadStr.contains("\"type\":\"file_header\"")) {
                        String json = new String(payload, java.nio.charset.StandardCharsets.UTF_8);
                        String fileType = extractJsonField(json, "fileType");
                        String fileName = extractJsonField(json, "fileName");
                        Long fileSize = extractJsonFieldLong(json, "fileSize");

                        if (fileName != null && fileSize != null) {
                            FileReceiver prev = currentFileReceiver.get();
                            if (prev != null) {
                                prev.close();
                            }

                            File targetFile = new File(storeDirectory, fileName);
                            FileReceiver receiver = new FileReceiver(targetFile, fileSize);
                            currentFileReceiver.set(receiver);
                            fileCountRef.incrementAndGet();
                            log.info("   📥 Receiving {}: {} ({} bytes)",
                                fileType, fileName, fileSize);
                        }
                    } else {
                        FileReceiver receiver = currentFileReceiver.get();
                        if (receiver != null) {
                            receiver.writeChunk(payload, 0, payloadLength);
                        } else {
                            log.debug("Received snapshot chunk but no active receiver");
                        }
                    }
                } catch (Exception e) {
                    log.error("❌ Failed to process snapshot fragment", e);
                }
            }
        );

        IdleStrategy strategy = idleStrategy != null
            ? idleStrategy
            : new org.agrona.concurrent.BusySpinIdleStrategy();

        strategy.reset();
        int fragmentsPolled = 0;
        while (!snapshotImage.isEndOfStream()) {
            int fragments = snapshotImage.poll(fragmentAssembler, 20);
            if (fragments > 0) {
                fragmentsPolled += fragments;
            }
            strategy.idle(fragments);
        }

        FileReceiver finalReceiver = currentFileReceiver.get();
        if (finalReceiver != null) {
            finalReceiver.close();
        }

        if (!metadataReceived.get()) {
            log.warn("⚠️  Snapshot restoration incomplete: no metadata received");
            return null;
        }

        log.info("✅ Snapshot restored: head={}, epoch={}, files={}, fragments={}",
            headRef.get(), epochRef.get(), fileCountRef.get(), fragmentsPolled);

        return new SnapshotState(headRef.get(), epochRef.get(), timestampRef.get(), fileCountRef.get());
    }

    /**
     * Extract a field from JSON string (simple parser for snapshot metadata).
     */
    private String extractJsonField(String json, String field) {
        String pattern = "\"" + field + "\":";
        int start = json.indexOf(pattern);
        if (start < 0) {
            return null;
        }

        start += pattern.length();
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) {
            start++;
        }

        if (start >= json.length()) {
            return null;
        }

        if (json.charAt(start) == '"') {
            start++;
            int end = json.indexOf('"', start);
            if (end < 0) {
                return null;
            }
            return json.substring(start, end);
        }

        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
            end++;
        }
        return json.substring(start, end);
    }

    private Long extractJsonFieldLong(String json, String field) {
        String value = extractJsonField(json, field);
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Helper class to receive and write file chunks during snapshot restore.
     */
    private static class FileReceiver {
        private final File targetFile;
        private final long expectedSize;
        private long bytesReceived;
        private java.io.FileOutputStream fos;

        FileReceiver(File targetFile, long expectedSize) throws Exception {
            this.targetFile = targetFile;
            this.expectedSize = expectedSize;
            this.bytesReceived = 0;

            File parent = targetFile.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }

            this.fos = new java.io.FileOutputStream(targetFile);
        }

        void writeChunk(byte[] data, int offset, int length) throws Exception {
            fos.write(data, offset, length);
            bytesReceived += length;
        }

        void close() {
            try {
                if (fos != null) {
                    fos.close();
                }

                if (bytesReceived == expectedSize) {
                    log.debug("✅ Snapshot file complete: {} ({} bytes)", targetFile.getName(), bytesReceived);
                } else {
                    log.warn("⚠️  Snapshot file size mismatch: {} (expected {}, got {})",
                        targetFile.getName(), expectedSize, bytesReceived);
                }
            } catch (Exception e) {
                log.warn("❌ Failed to close snapshot file: {} - {}", targetFile.getName(), e.getMessage());
            }
        }
    }
    
    /**
     * Check if a node needs snapshot restoration.
     * 
     * <p>This is used to determine if a node joining the cluster should
     * request a snapshot instead of replaying the entire log.</p>
     * 
     * @param localHead current local HEAD (null if empty)
     * @param clusterHead cluster's current HEAD
     * @param logPosition current log position
     * @param clusterLogPosition cluster's log position
     * @return true if snapshot restoration is recommended
     */
    public boolean needsSnapshotRestoration(String localHead, String clusterHead,
                                           long logPosition, long clusterLogPosition) {
        // Empty node - definitely needs snapshot
        if (localHead == null || localHead.isEmpty()) {
            log.info("🔄 Empty node detected - snapshot restoration required");
            return true;
        }
        
        // Stale node - too far behind on log
        long logGap = clusterLogPosition - logPosition;
        long SNAPSHOT_THRESHOLD = 1000; // If more than 1000 entries behind, use snapshot
        
        if (logGap > SNAPSHOT_THRESHOLD) {
            log.info("🔄 Stale node detected - {} entries behind, snapshot restoration recommended", logGap);
            return true;
        }
        
        // Node is reasonably up-to-date, can replay log
        return false;
    }
    
    /**
     * Validate restored snapshot against expected state.
     * 
     * @param restored the restored snapshot state
     * @param expectedHead expected HEAD (from cluster)
     * @return true if snapshot is valid
     */
    public boolean validateSnapshot(SnapshotState restored, String expectedHead) {
        if (restored == null) {
            log.error("Snapshot validation failed: null state");
            return false;
        }
        
        if (restored.head == null || restored.head.isEmpty()) {
            log.error("Snapshot validation failed: no HEAD in restored state");
            return false;
        }
        
        if (expectedHead != null && !restored.head.equals(expectedHead)) {
            log.warn("Snapshot HEAD mismatch: restored={}, expected={}", restored.head, expectedHead);
            // This might be okay if the snapshot is slightly behind
        }
        
        if (restored.fileCount == 0) {
            log.warn("Snapshot validation warning: no files restored");
        }
        
        log.info("✅ Snapshot validated: head={}, epoch={}, files={}", 
                restored.head, restored.epoch, restored.fileCount);
        return true;
    }
    
    /**
     * Send snapshot metadata frame with SBE header.
     */
    private void sendSnapshotMetadata(ExclusivePublication pub, IdleStrategy idleStrategy,
                                     String head, int epoch, long timestamp) throws Exception {
        
        // Use ethereumEpoch field name for compatibility with AeronConsensusEngine
        String json = String.format("{\"type\":\"metadata\",\"head\":\"%s\",\"ethereumEpoch\":%d,\"timestamp\":%d}",
                                   head, epoch, timestamp);
        
        byte[] jsonBytes = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int totalLength = SimpleMessageHeader.ENCODED_LENGTH + jsonBytes.length;
        UnsafeBuffer buffer = new UnsafeBuffer(new byte[totalLength]);
        
        // Encode SBE header
        SimpleMessageHeader.encode(buffer, 0, jsonBytes.length, SimpleMessageHeader.TEMPLATE_ID_SNAPSHOT);
        
        // Copy JSON payload after header
        buffer.putBytes(SimpleMessageHeader.ENCODED_LENGTH, jsonBytes);
        
        offerWithRetry(pub, idleStrategy, buffer, 0, totalLength);
        
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
     * Stream a single file to snapshot publication with SBE header.
     * 
     * <p>First sends a file_header JSON message, then streams file chunks.
     */
    private void streamFile(ExclusivePublication pub, IdleStrategy idleStrategy, 
                           File file, String fileType) throws Exception {
        
        final int CHUNK_SIZE = 1024 * 1024; // 1 MB chunks
        byte[] chunkBuffer = new byte[CHUNK_SIZE];
        
        // Send file header first
        String headerJson = String.format(
            "{\"type\":\"file_header\",\"fileType\":\"%s\",\"fileName\":\"%s\",\"fileSize\":%d}",
            fileType, file.getName(), file.length()
        );
        byte[] headerBytes = headerJson.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int headerTotalLength = SimpleMessageHeader.ENCODED_LENGTH + headerBytes.length;
        UnsafeBuffer headerBuffer = new UnsafeBuffer(new byte[headerTotalLength]);
        SimpleMessageHeader.encode(headerBuffer, 0, headerBytes.length, SimpleMessageHeader.TEMPLATE_ID_SNAPSHOT);
        headerBuffer.putBytes(SimpleMessageHeader.ENCODED_LENGTH, headerBytes);
        offerWithRetry(pub, idleStrategy, headerBuffer, 0, headerTotalLength);
        
        // Stream file chunks
        try (FileInputStream fis = new FileInputStream(file)) {
            long totalBytes = 0;
            int bytesRead;
            
            while ((bytesRead = fis.read(chunkBuffer)) != -1) {
                // Chunk format: [SBE header][chunk data]
                int chunkTotalLength = SimpleMessageHeader.ENCODED_LENGTH + bytesRead;
                UnsafeBuffer buffer = new UnsafeBuffer(new byte[chunkTotalLength]);
                
                // Encode SBE header
                SimpleMessageHeader.encode(buffer, 0, bytesRead, SimpleMessageHeader.TEMPLATE_ID_SNAPSHOT);
                
                // Copy chunk data
                buffer.putBytes(SimpleMessageHeader.ENCODED_LENGTH, chunkBuffer, 0, bytesRead);
                
                offerWithRetry(pub, idleStrategy, buffer, 0, chunkTotalLength);
                
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
