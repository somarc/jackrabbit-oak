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
package org.apache.jackrabbit.oak.segment.http.server.binary;

import org.apache.jackrabbit.oak.segment.consensus.config.IpfsGatewayUrls;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CID Mapping Service - Coordinates Oak blob IDs with IPFS CIDs at scale.
 * 
 * <h2>Problem</h2>
 * Oak uses SHA-256 hex-encoded blob IDs (e.g., ed06f9cb...#22216)
 * IPFS uses multihash CIDs (e.g., Qmf4F3CWU6Ly958TFiR8BRP18gwvW3Xsj2yXu5DqkonWc3)
 * 
 * Both are content-addressed (deterministic hashes), but different encoding.
 * We need to map between them for:
 * - UI display (show IPFS gateway links)
 * - Cross-validator consistency (same binary = same CID everywhere)
 * - Direct binary access (ADR 019)
 * 
 * <h2>Architecture</h2>
 * <pre>
 * Binary Upload Flow:
 * 
 *   Client ─── binaryData ───► Validator
 *                                  │
 *                                  ▼
 *                          ┌──────────────┐
 *                          │  BlobStore   │
 *                          │ (writeBlob)  │
 *                          └──────┬───────┘
 *                                 │
 *               ┌─────────────────┴─────────────────┐
 *               ▼                                   ▼
 *     ┌─────────────────┐                 ┌─────────────────┐
 *     │   Oak Blob ID   │                 │    IPFS CID     │
 *     │ ed06f9cb...#sz  │                 │   Qmf4F3CW...   │
 *     └────────┬────────┘                 └────────┬────────┘
 *              │                                   │
 *              └─────────────┬─────────────────────┘
 *                            ▼
 *                   ┌────────────────┐
 *                   │ CidMappingService│
 *                   │ (bidirectional) │
 *                   └────────────────┘
 *                            │
 *                            ▼
 *                   ┌────────────────┐
 *                   │ Persistent     │
 *                   │ Storage (.props)│
 *                   └────────────────┘
 * </pre>
 * 
 * <h2>Scaling Strategy</h2>
 * - Each validator maintains its own local mapping
 * - Mappings are deterministic (same binary = same IDs on all validators)
 * - IPFS P2P replication ensures binary availability across network
 * - No need for cross-validator sync of mappings (they compute identically)
 * 
 * @see org.apache.jackrabbit.oak.blob.cloud.ipfs.IPFSBackend
 */
public class CidMappingService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(CidMappingService.class);

    /** File name for persisted mappings */
    private static final String MAPPING_FILE = "cid-mappings.properties";

    /** Oak blob ID → IPFS CID mapping */
    private final Map<String, String> oakToCid = new ConcurrentHashMap<>();

    /** IPFS CID → Oak blob ID mapping (reverse lookup) */
    private final Map<String, String> cidToOak = new ConcurrentHashMap<>();

    /** Directory for persistent storage */
    private final Path storageDir;
    private final ExecutorService persistExecutor;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /** Statistics */
    private long totalMappings = 0;
    private long cacheHits = 0;
    private long cacheMisses = 0;

    /**
     * Create a new CID Mapping Service.
     * 
     * @param storageDir Directory for persistent storage (validator's store dir)
     */
    public CidMappingService(Path storageDir) {
        this.storageDir = storageDir;
        this.persistExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "cid-mapping-persist");
            thread.setDaemon(true);
            return thread;
        });
        loadMappings();
        log.info("✅ CidMappingService initialized with {} mappings from {}", 
            totalMappings, storageDir);
    }

    /**
     * Register a new Oak blob ID ↔ IPFS CID mapping.
     * 
     * @param oakBlobId Oak blob ID (e.g., "ed06f9cb...#22216")
     * @param ipfsCid IPFS CID (e.g., "Qmf4F3CWU6Ly958TFiR8BRP18gwvW3Xsj2yXu5DqkonWc3")
     */
    public void registerMapping(String oakBlobId, String ipfsCid) {
        if (oakBlobId == null || ipfsCid == null) {
            log.warn("Ignoring null mapping: oakBlobId={}, ipfsCid={}", oakBlobId, ipfsCid);
            return;
        }

        // Normalize Oak blob ID (remove size suffix for consistent lookups)
        String normalizedOakId = normalizeOakBlobId(oakBlobId);

        oakToCid.put(normalizedOakId, ipfsCid);
        cidToOak.put(ipfsCid, normalizedOakId);
        totalMappings++;

        log.debug("📎 Registered CID mapping: {} ↔ {}", normalizedOakId, ipfsCid);

        // Persist asynchronously (don't block the caller)
        persistMappingsAsync();
    }

    /**
     * Get IPFS CID for an Oak blob ID.
     * 
     * @param oakBlobId Oak blob ID (with or without size suffix)
     * @return Optional containing the IPFS CID, or empty if not found
     */
    public Optional<String> getCid(String oakBlobId) {
        if (oakBlobId == null) {
            return Optional.empty();
        }

        String normalizedId = normalizeOakBlobId(oakBlobId);
        String cid = oakToCid.get(normalizedId);

        if (cid != null) {
            cacheHits++;
            return Optional.of(cid);
        }

        cacheMisses++;
        return Optional.empty();
    }

    /**
     * Get Oak blob ID for an IPFS CID.
     * 
     * @param ipfsCid IPFS CID
     * @return Optional containing the Oak blob ID, or empty if not found
     */
    public Optional<String> getOakBlobId(String ipfsCid) {
        if (ipfsCid == null) {
            return Optional.empty();
        }

        String oakId = cidToOak.get(ipfsCid);
        return Optional.ofNullable(oakId);
    }

    /**
     * Get IPFS gateway URL for an Oak blob ID.
     * 
     * @param oakBlobId Oak blob ID
     * @return Optional containing gateway URL using the configured gateway base
     */
    public Optional<String> getGatewayUrl(String oakBlobId) {
        return getCid(oakBlobId)
            .map(IpfsGatewayUrls::gatewayUrl);
    }

    /**
     * Get local IPFS URL for an Oak blob ID.
     * 
     * @param oakBlobId Oak blob ID
     * @return Optional containing local URL using the configured local gateway base
     */
    public Optional<String> getLocalUrl(String oakBlobId) {
        return getCid(oakBlobId)
            .map(IpfsGatewayUrls::localGatewayUrl);
    }

    /**
     * Get statistics about the mapping service.
     */
    public CidMappingStats getStats() {
        return new CidMappingStats(totalMappings, cacheHits, cacheMisses, oakToCid.size());
    }

    /**
     * Normalize Oak blob ID by removing size suffix.
     * "ed06f9cb...#22216" → "ed06f9cb..."
     */
    private String normalizeOakBlobId(String oakBlobId) {
        int hashIndex = oakBlobId.indexOf('#');
        if (hashIndex > 0) {
            return oakBlobId.substring(0, hashIndex);
        }
        return oakBlobId;
    }

    /**
     * Load mappings from persistent storage.
     */
    private void loadMappings() {
        Path mappingFile = storageDir.resolve(MAPPING_FILE);
        
        if (!Files.exists(mappingFile)) {
            log.debug("No existing CID mappings file at {}", mappingFile);
            return;
        }

        try (InputStream in = Files.newInputStream(mappingFile)) {
            Properties props = new Properties();
            props.load(in);

            for (String oakId : props.stringPropertyNames()) {
                String cid = props.getProperty(oakId);
                oakToCid.put(oakId, cid);
                cidToOak.put(cid, oakId);
                totalMappings++;
            }

            log.info("📂 Loaded {} CID mappings from {}", totalMappings, mappingFile);

        } catch (IOException e) {
            log.error("Failed to load CID mappings from {}: {}", mappingFile, e.getMessage());
        }
    }

    /**
     * Persist mappings to storage asynchronously.
     */
    private void persistMappingsAsync() {
        if (closed.get()) {
            log.debug("Skipping CID mapping persistence because service is closed");
            return;
        }
        try {
            persistExecutor.execute(() -> {
                try {
                    persistMappings();
                } catch (RuntimeException e) {
                    log.error("Failed to persist CID mappings", e);
                }
            });
        } catch (RejectedExecutionException e) {
            log.debug("Skipping CID mapping persistence during shutdown");
        }
    }

    /**
     * Persist mappings to storage.
     */
    private synchronized void persistMappings() {
        Path mappingFile = storageDir.resolve(MAPPING_FILE);

        try {
            Properties props = new Properties();
            oakToCid.forEach(props::setProperty);

            try (OutputStream out = Files.newOutputStream(mappingFile)) {
                props.store(out, "Oak Blob ID → IPFS CID Mappings");
            }

            log.debug("💾 Persisted {} CID mappings to {}", oakToCid.size(), mappingFile);

        } catch (IOException e) {
            log.error("Failed to persist CID mappings to {}: {}", mappingFile, e.getMessage());
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        persistExecutor.shutdown();
        try {
            if (!persistExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                persistExecutor.shutdownNow();
                if (!persistExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    log.warn("Timed out waiting for CID mapping persistence to stop");
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            persistExecutor.shutdownNow();
        }
    }

    /**
     * Statistics for the CID mapping service.
     */
    public static class CidMappingStats {
        public final long totalMappings;
        public final long cacheHits;
        public final long cacheMisses;
        public final int currentSize;
        public final double hitRate;

        public CidMappingStats(long totalMappings, long cacheHits, long cacheMisses, int currentSize) {
            this.totalMappings = totalMappings;
            this.cacheHits = cacheHits;
            this.cacheMisses = cacheMisses;
            this.currentSize = currentSize;
            this.hitRate = (cacheHits + cacheMisses) > 0 
                ? (double) cacheHits / (cacheHits + cacheMisses) 
                : 0.0;
        }

        public String toJson() {
            return String.format(
                "{\"totalMappings\":%d,\"cacheHits\":%d,\"cacheMisses\":%d,\"currentSize\":%d,\"hitRate\":%.2f}",
                totalMappings, cacheHits, cacheMisses, currentSize, hitRate
            );
        }
    }
}
