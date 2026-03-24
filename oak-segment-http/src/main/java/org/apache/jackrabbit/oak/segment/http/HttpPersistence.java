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
 * {@link SegmentNodeStorePersistence} that exposes a remote segment store over
 * HTTP.
 *
 * <p>This persistence is read-only: it wires HTTP implementations for archive,
 * journal, GC journal, and manifest access, and returns no-op locks or writers
 * where the Oak SPI still expects mutating entry points. Resources are resolved
 * relative to a single base URL shared by the helpers in this package.</p>
 *
 * <p>The transport prefers HTTP/2 through {@link Http2ClientPool}, but remains
 * compatible with HTTP/1.1 endpoints through the JDK client's normal fallback
 * behaviour.</p>
 */
public class HttpPersistence implements SegmentNodeStorePersistence {
    
    private static final Logger log = LoggerFactory.getLogger(HttpPersistence.class);
    
    private final String baseUrl;
    private final WriteAccessController writeAccessController;
    private final Http2ClientPool http2ClientPool;
    
    /**
     * Creates a read-only HTTP persistence layer rooted at the given base URL.
     *
     * @param baseUrl base URL of the remote segment-store endpoint
     */
    public HttpPersistence(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.writeAccessController = new WriteAccessController();
        this.http2ClientPool = new Http2ClientPool();
        // WriteAccessController is read-only by default for remote stores
        log.info("Initialized HttpPersistence (HTTP/2) for: {}", this.baseUrl);
        log.info("HTTP/2 Client Pool: {}", http2ClientPool.getPoolStats());
    }
    
    @Override
    public SegmentArchiveManager createArchiveManager(boolean mmap, boolean offHeapAccess, 
                                                     IOMonitor ioMonitor, 
                                                     FileStoreMonitor fileStoreMonitor,
                                                     RemoteStoreMonitor remoteStoreMonitor) {
        log.debug("Creating HttpSegmentArchiveManager (HTTP/2, mmap={}, offHeapAccess={})", mmap, offHeapAccess);
        HttpSegmentArchiveManager manager = new HttpSegmentArchiveManager(baseUrl, ioMonitor, http2ClientPool);
        log.debug("HttpSegmentArchiveManager (HTTP/2) created successfully");
        return manager;
    }
    
    @Override
    public boolean segmentFilesExist() {
        log.debug("Checking if segment files exist at: {}", baseUrl);
        // Archive discovery is currently deferred to the archive manager because
        // the remote endpoint does not expose a dedicated listing endpoint yet.
        return true;
    }
    
    @Override
    public JournalFile getJournalFile() {
        log.debug("Creating HttpJournalFile (HTTP/2) for: {}", baseUrl);
        return new HttpJournalFile(baseUrl, writeAccessController, http2ClientPool);
    }
    
    @Override
    public GCJournalFile getGCJournalFile() throws IOException {
        log.debug("Creating HttpGCJournalFile (HTTP/2) for: {}", baseUrl);
        return new HttpGCJournalFile(baseUrl, http2ClientPool);
    }
    
    @Override
    public ManifestFile getManifestFile() throws IOException {
        log.debug("Creating HttpManifestFile (HTTP/2) for: {}", baseUrl);
        return new HttpManifestFile(baseUrl, http2ClientPool);
    }
    
    @Override
    public RepositoryLock lockRepository() throws IOException {
        log.debug("Creating no-op repository lock for read-only HTTP store");
        // Read-only mount doesn't need locking
        return new NoOpRepositoryLock();
    }
    
    /**
     * Repository lock placeholder returned for a read-only remote mount.
     */
    private static class NoOpRepositoryLock implements RepositoryLock {
        @Override
        public void unlock() throws IOException {
            // Nothing to release because no lock is actually acquired.
        }
    }
}
