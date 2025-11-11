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

import org.apache.http.HttpStatus;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.jackrabbit.oak.commons.Buffer;
import org.apache.jackrabbit.oak.segment.spi.monitor.IOMonitor;
import org.apache.jackrabbit.oak.segment.spi.persistence.SegmentArchiveManager;
import org.apache.jackrabbit.oak.segment.spi.persistence.SegmentArchiveReader;
import org.apache.jackrabbit.oak.segment.spi.persistence.SegmentArchiveWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * HTTP-based implementation of SegmentArchiveManager.
 * Provides read-only access to a remote segment store via HTTP.
 * 
 * This is designed for the Blockchain AEM POC to enable remote mounting
 * of the global segment store.
 */
public class HttpSegmentArchiveManager implements SegmentArchiveManager {

    private static final Logger log = LoggerFactory.getLogger(HttpSegmentArchiveManager.class);

    private final String baseUrl;
    private final IOMonitor ioMonitor;
    private final HttpClientPool httpClientPool;
    private final CloseableHttpClient httpClient;

    /**
     * Create a new HTTP-based archive manager.
     * 
     * @param baseUrl Base URL of the GlobalStoreServer (e.g., "http://oak-global-store:8090")
     * @param ioMonitor IO monitor for tracking read operations
     * @param httpClientPool Shared HTTP client pool for connection reuse
     */
    public HttpSegmentArchiveManager(String baseUrl, IOMonitor ioMonitor, HttpClientPool httpClientPool) {
        log.info("⭐ ENTERING HttpSegmentArchiveManager constructor");
        log.info("⭐ baseUrl param: {}", baseUrl);
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        log.info("⭐ Normalized baseUrl: {}", this.baseUrl);
        this.ioMonitor = ioMonitor;
        log.info("⭐ Set ioMonitor");
        this.httpClientPool = httpClientPool;
        this.httpClient = httpClientPool.getHttpClient();
        log.info("⭐ Using shared HTTP client pool ({})", httpClientPool.getPoolStats());
        log.info("⭐ HttpSegmentArchiveManager constructor COMPLETE");
    }

    @Override
    public List<String> listArchives() throws IOException {
        log.info("💫 ENTERING listArchives()");
        // POC SIMPLIFICATION: No archive listing endpoint yet
        // For now, assume a single archive named "data.tar" (standard Oak naming)
        log.info("💫 POC mode: Returning hardcoded archive list");
        List<String> archives = new ArrayList<>();
        archives.add("data00000a.tar"); // Common Oak segment archive name
        log.info("💫 Returning {} archives", archives.size());
        return archives;
    }

    @Override
    public SegmentArchiveReader open(String archiveName) throws IOException {
        log.info("🌟 ENTERING open() for archive: {}", archiveName);
        
        // Check if archive exists by querying the server
        log.info("🌟 Checking if archive exists...");
        if (!exists(archiveName)) {
            log.info("🌟 Archive does not exist: {}", archiveName);
            return null;
        }

        log.info("🌟 Creating HttpSegmentArchiveReader...");
        HttpSegmentArchiveReader reader = new HttpSegmentArchiveReader(baseUrl, archiveName, ioMonitor, httpClientPool);
        log.info("🌟 HttpSegmentArchiveReader created successfully");
        return reader;
    }

    @Override
    public SegmentArchiveReader forceOpen(String archiveName) throws IOException {
        log.debug("Force opening archive: {}", archiveName);
        // For HTTP-based store, forceOpen is the same as open
        return new HttpSegmentArchiveReader(baseUrl, archiveName, ioMonitor, httpClientPool);
    }

    @Override
    public boolean exists(String archiveName) {
        // POC SIMPLIFICATION: Assume archive exists if it matches our hardcoded name
        boolean exists = "data00000a.tar".equals(archiveName);
        log.debug("POC mode: Archive {} exists: {}", archiveName, exists);
        return exists;
    }

    @Override
    public SegmentArchiveWriter create(String archiveName) throws IOException {
        // Return a no-op writer for read-only HTTP mount
        // Oak initialization requires this even for read-only stores
        log.debug("Creating no-op writer for read-only HTTP mount: {}", archiveName);
        return new NoOpSegmentArchiveWriter(archiveName);
    }

    @Override
    public boolean delete(String archiveName) {
        log.warn("Delete operation not supported on read-only HTTP store: {}", archiveName);
        return false;
    }

    @Override
    public boolean renameTo(String from, String to) {
        log.warn("Rename operation not supported on read-only HTTP store: {} -> {}", from, to);
        return false;
    }

    @Override
    public void copyFile(String from, String to) throws IOException {
        throw new UnsupportedOperationException(
            "Copy operation not supported on read-only HTTP store"
        );
    }

    @Override
    public void recoverEntries(String archiveName, LinkedHashMap<UUID, byte[]> entries) throws IOException {
        throw new UnsupportedOperationException(
            "Recovery operation not supported on HTTP store. Archives should be recovered on the server side."
        );
    }

    @Override
    public void backup(String archiveName, String backupArchiveName, Set<UUID> recoveredEntries) throws IOException {
        throw new UnsupportedOperationException(
            "Backup operation not supported on read-only HTTP store"
        );
    }

    @Override
    public boolean isReadOnly(String archiveName) {
        // HTTP store is always read-only for POC
        return true;
    }
    
    /**
     * No-op segment archive writer for read-only HTTP mounts.
     * All write operations are silently ignored.
     */
    private static class NoOpSegmentArchiveWriter implements SegmentArchiveWriter {
        private final String name;
        
        NoOpSegmentArchiveWriter(String name) {
            this.name = name;
        }
        
        @Override
        public String getName() {
            return name;
        }
        
        @Override
        public void writeSegment(long msb, long lsb, byte[] data, int offset, int size, int generation, int fullGeneration, boolean isCompacted) throws IOException {
            // No-op - silently ignore writes for read-only mount
        }
        
        @Override
        public Buffer readSegment(long msb, long lsb) throws IOException {
            // No-op writer can't read - return null
            return null;
        }
        
        @Override
        public void writeGraph(byte[] data) throws IOException {
            // No-op - silently ignore writes for read-only mount
        }
        
        @Override
        public void writeBinaryReferences(byte[] data) throws IOException {
            // No-op - silently ignore writes for read-only mount
        }
        
        @Override
        public long getLength() {
            return 0;
        }
        
        @Override
        public int getEntryCount() {
            return 0;
        }
        
        @Override
        public int getMaxEntryCount() {
            return 0;
        }
        
        @Override
        public void close() throws IOException {
            // No-op
        }
        
        @Override
        public void flush() throws IOException {
            // No-op
        }
        
        @Override
        public boolean isCreated() {
            return false;
        }
        
        @Override
        public boolean isRemote() {
            return true; // HTTP mount is always remote
        }
        
        @Override
        public boolean containsSegment(long msb, long lsb) {
            return false; // No-op writer doesn't actually contain any segments
        }
    }
}

