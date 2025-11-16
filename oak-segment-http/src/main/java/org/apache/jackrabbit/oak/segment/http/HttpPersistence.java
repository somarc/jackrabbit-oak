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
package org.apache.jackrabbit.oak.segment.http;

import org.apache.jackrabbit.oak.segment.remote.WriteAccessController;
import org.apache.jackrabbit.oak.segment.spi.monitor.FileStoreMonitor;
import org.apache.jackrabbit.oak.segment.spi.monitor.IOMonitor;
import org.apache.jackrabbit.oak.segment.spi.monitor.RemoteStoreMonitor;
import org.apache.jackrabbit.oak.segment.spi.persistence.GCJournalFile;
import org.apache.jackrabbit.oak.segment.spi.persistence.JournalFile;
import org.apache.jackrabbit.oak.segment.spi.persistence.ManifestFile;
import org.apache.jackrabbit.oak.segment.spi.persistence.RepositoryLock;
import org.apache.jackrabbit.oak.segment.spi.persistence.SegmentArchiveManager;
import org.apache.jackrabbit.oak.segment.spi.persistence.SegmentNodeStorePersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * HTTP-based persistence for remote segment stores.
 * 
 * <p>This implementation enables read-only access to a remote segment store
 * via HTTP, suitable for mounting global blockchain stores in Blockchain AEM.</p>
 * 
 * <p><strong>Design:</strong></p>
 * <ul>
 *   <li>Segments fetched on-demand via HTTP</li>
 *   <li>Read-only access (no writes supported)</li>
 *   <li>Journal/manifest handled via HTTP endpoints</li>
 *   <li>No repository locking (read-only mount)</li>
 * </ul>
 */
public class HttpPersistence implements SegmentNodeStorePersistence {
    
    private static final Logger log = LoggerFactory.getLogger(HttpPersistence.class);
    
    private final String baseUrl;
    private final WriteAccessController writeAccessController;
    private final HttpClientPool httpClientPool;
    
    /**
     * Create a new HTTP persistence layer.
     * 
     * @param baseUrl Base URL of the GlobalStoreServer (e.g., "http://oak-global-store:8090")
     */
    public HttpPersistence(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.writeAccessController = new WriteAccessController();
        this.httpClientPool = new HttpClientPool();
        // WriteAccessController is read-only by default for remote stores
        log.info("Initialized HttpPersistence for: {}", this.baseUrl);
        log.info("HTTP Client Pool: {}", httpClientPool.getPoolStats());
    }
    
    @Override
    public SegmentArchiveManager createArchiveManager(boolean mmap, boolean offHeapAccess, 
                                                     IOMonitor ioMonitor, 
                                                     FileStoreMonitor fileStoreMonitor,
                                                     RemoteStoreMonitor remoteStoreMonitor) {
        log.debug("Creating HttpSegmentArchiveManager (mmap={}, offHeapAccess={})", mmap, offHeapAccess);
        try {
            HttpSegmentArchiveManager manager = new HttpSegmentArchiveManager(baseUrl, ioMonitor, httpClientPool);
            log.debug("HttpSegmentArchiveManager created successfully");
            return manager;
        } catch (Exception e) {
            log.error("Failed to create HttpSegmentArchiveManager", e);
            throw e;
        }
    }
    
    @Override
    public boolean segmentFilesExist() {
        log.debug("Checking if segment files exist at: {}", baseUrl);
        // Assume segments exist if server is reachable
        // In production, would check via HTTP HEAD to /archives endpoint
        return true;
    }
    
    @Override
    public JournalFile getJournalFile() {
        log.debug("Creating HttpJournalFile for: {}", baseUrl);
        try {
            HttpJournalFile journalFile = new HttpJournalFile(baseUrl, writeAccessController, httpClientPool);
            return journalFile;
        } catch (Exception e) {
            log.error("Failed to create HttpJournalFile", e);
            throw e;
        }
    }
    
    @Override
    public GCJournalFile getGCJournalFile() throws IOException {
        log.debug("Creating HttpGCJournalFile for: {}", baseUrl);
        try {
            HttpGCJournalFile gcFile = new HttpGCJournalFile(baseUrl, httpClientPool);
            return gcFile;
        } catch (Exception e) {
            log.error("Failed to create HttpGCJournalFile", e);
            throw e;
        }
    }
    
    @Override
    public ManifestFile getManifestFile() throws IOException {
        log.debug("Creating HttpManifestFile for: {}", baseUrl);
        try {
            HttpManifestFile manifestFile = new HttpManifestFile(baseUrl, httpClientPool);
            return manifestFile;
        } catch (Exception e) {
            log.error("Failed to create HttpManifestFile", e);
            throw e;
        }
    }
    
    @Override
    public RepositoryLock lockRepository() throws IOException {
        log.debug("Creating no-op repository lock for read-only HTTP store");
        // Read-only mount doesn't need locking
        return new NoOpRepositoryLock();
    }
    
    /**
     * No-op repository lock for read-only mounts.
     */
    private static class NoOpRepositoryLock implements RepositoryLock {
        @Override
        public void unlock() throws IOException {
            // No-op
        }
    }
}

