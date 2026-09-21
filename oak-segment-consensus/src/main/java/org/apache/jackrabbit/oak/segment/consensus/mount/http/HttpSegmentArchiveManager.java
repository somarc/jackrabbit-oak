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
package org.apache.jackrabbit.oak.segment.consensus.mount.http;

import org.apache.jackrabbit.oak.commons.Buffer;
import org.apache.jackrabbit.oak.segment.spi.monitor.IOMonitor;
import org.apache.jackrabbit.oak.segment.spi.persistence.SegmentArchiveManager;
import org.apache.jackrabbit.oak.segment.spi.persistence.SegmentArchiveReader;
import org.apache.jackrabbit.oak.segment.spi.persistence.SegmentArchiveWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Read-only {@link SegmentArchiveManager} for the HTTP persistence mount.
 *
 * <p>The current remote contract does not expose archive discovery or mutation
 * endpoints. As a result this implementation advertises a single conventional
 * archive name, opens readers against the remote segment endpoints, and
 * supplies no-op writers where Oak still requires a writer instance during
 * startup.</p>
 */
public class HttpSegmentArchiveManager implements SegmentArchiveManager {

    private static final Logger log = LoggerFactory.getLogger(HttpSegmentArchiveManager.class);

    private final String baseUrl;
    private final IOMonitor ioMonitor;
    private final Http2ClientPool http2ClientPool;

    /**
     * Creates a read-only archive manager rooted at the given base URL.
     *
     * @param baseUrl base URL of the remote segment-store endpoint
     * @param ioMonitor I/O monitor used by archive readers
     * @param http2ClientPool shared client used for remote reads
     */
    public HttpSegmentArchiveManager(String baseUrl, IOMonitor ioMonitor, Http2ClientPool http2ClientPool) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.ioMonitor = ioMonitor;
        this.http2ClientPool = http2ClientPool;
        log.debug("Initialized HttpSegmentArchiveManager (HTTP/2) for: {} (stats: {})", this.baseUrl, http2ClientPool.getPoolStats());
    }
    
    @Override
    public List<String> listArchives() throws IOException {
        // The current HTTP endpoint exposes segments directly, not an archive index.
        List<String> archives = new ArrayList<>();
        archives.add("data00000a.tar");
        log.debug("Listing archives: {} (hardcoded for HTTP store)", archives);
        return archives;
    }

    @Override
    public SegmentArchiveReader open(String archiveName) throws IOException {
        log.debug("Opening archive via HTTP/2: {}", archiveName);
        
        if (!exists(archiveName)) {
            log.debug("Archive does not exist: {}", archiveName);
            return null;
        }

        HttpSegmentArchiveReader reader = new HttpSegmentArchiveReader(baseUrl, archiveName, ioMonitor, http2ClientPool);
        log.debug("Opened archive via HTTP/2: {}", archiveName);
        return reader;
    }

    @Override
    public SegmentArchiveReader forceOpen(String archiveName) throws IOException {
        log.debug("Force opening archive via HTTP/2: {}", archiveName);
        // There is no additional recovery path for the HTTP mount, so forceOpen
        // simply skips the existence probe and constructs a reader directly.
        return new HttpSegmentArchiveReader(baseUrl, archiveName, ioMonitor, http2ClientPool);
    }

    @Override
    public boolean exists(String archiveName) {
        // Until the server exposes archive discovery, only the conventional
        // single-archive name is treated as present.
        boolean exists = "data00000a.tar".equals(archiveName);
        log.debug("POC mode: Archive {} exists: {}", archiveName, exists);
        return exists;
    }

    @Override
    public SegmentArchiveWriter create(String archiveName) throws IOException {
        // Oak startup still asks for a writer even when the persistence is
        // read-only, so return a sink implementation.
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
     * Writer placeholder used to satisfy Oak's SPI for a read-only mount.
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
            // Intentionally ignored because the mount is read-only.
        }
        
        @Override
        public Buffer readSegment(long msb, long lsb) throws IOException {
            // This writer never persists anything, so there is nothing to read.
            return null;
        }
        
        @Override
        public void writeGraph(byte[] data) throws IOException {
            // Intentionally ignored because the mount is read-only.
        }
        
        @Override
        public void writeBinaryReferences(byte[] data) throws IOException {
            // Intentionally ignored because the mount is read-only.
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
            return true; // The writer is only used by the remote HTTP mount.
        }
        
        @Override
        public boolean containsSegment(long msb, long lsb) {
            return false; // The writer never stores any local segment content.
        }
    }
}
