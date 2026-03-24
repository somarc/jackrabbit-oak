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

import org.apache.jackrabbit.oak.segment.remote.WriteAccessController;
import org.apache.jackrabbit.oak.segment.spi.persistence.JournalFile;
import org.apache.jackrabbit.oak.segment.spi.persistence.JournalFileReader;
import org.apache.jackrabbit.oak.segment.spi.persistence.JournalFileWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Read-only {@link JournalFile} implementation that serves
 * {@code journal.log} from the remote HTTP persistence endpoint.
 *
 * <p>The journal is fetched eagerly into memory when a reader is opened. The
 * lines are then reversed so callers see the newest revision first, matching
 * Oak's normal journal-reader behaviour.</p>
 */
public class HttpJournalFile implements JournalFile {
    
    private static final Logger log = LoggerFactory.getLogger(HttpJournalFile.class);
    
    private final String baseUrl;
    private final WriteAccessController writeAccessController;
    private final Http2ClientPool http2ClientPool;
    
    /**
     * Creates a journal adapter rooted at the given persistence URL.
     *
     * @param baseUrl the base URL of the remote HTTP persistence endpoint
     * @param writeAccessController retained to match the local SPI contract
     * @param http2ClientPool shared client used for remote reads
     */
    public HttpJournalFile(String baseUrl, WriteAccessController writeAccessController, Http2ClientPool http2ClientPool) {
        this.baseUrl = baseUrl;
        this.writeAccessController = writeAccessController;
        this.http2ClientPool = http2ClientPool;
        log.debug("Initialized HttpJournalFile (HTTP/2) for: {} (stats: {})", baseUrl, http2ClientPool.getPoolStats());
    }
    
    @Override
    public JournalFileReader openJournalReader() throws IOException {
        String url = baseUrl + "/journal.log";
        log.debug("Fetching journal via HTTP/2 from: {}", url);
        
        try {
            String content = http2ClientPool.getString(url);
            List<String> lines = new ArrayList<>(Arrays.asList(content.split("\n")));
            // Oak consumers expect the latest journal revision to be read first.
            Collections.reverse(lines);
            log.debug("Loaded {} journal entries via HTTP/2", lines.size());
            return new HttpJournalFileReader(lines);
        } catch (Exception e) {
            throw new IOException("Failed to fetch journal.log via HTTP/2: " + e.getMessage(), e);
        }
    }
    
    @Override
    public JournalFileWriter openJournalWriter() throws IOException {
        // Oak still requests a writer during startup, so return a sink that
        // satisfies the SPI without trying to acquire write access.
        log.debug("Returning no-op journal writer for read-only HTTP mount");
        return new NoOpJournalFileWriter();
    }
    
    /**
     * Journal writer placeholder required by Oak startup for a read-only mount.
     */
    private static class NoOpJournalFileWriter implements JournalFileWriter {
        @Override
        public void truncate() throws IOException {
            // Intentionally ignored because the mount is read-only.
        }

        @Override
        public void writeLine(String line) throws IOException {
            // Intentionally ignored because the mount is read-only.
        }

        @Override
        public void batchWriteLines(java.util.List<String> lines) throws IOException {
            // Intentionally ignored because the mount is read-only.
        }

        @Override
        public void close() throws IOException {
            // No-op
        }
    }
    
    @Override
    public String getName() {
        return "journal.log";
    }
    
    @Override
    public boolean exists() {
        String url = baseUrl + "/journal.log";
        return http2ClientPool.exists(url);
    }
    
    /**
     * In-memory reader over the reversed journal contents fetched from HTTP.
     */
    private static class HttpJournalFileReader implements JournalFileReader {
        private final List<String> lines;
        private int currentIndex = -1;
        
        HttpJournalFileReader(List<String> lines) {
            this.lines = lines;
        }
        
        @Override
        public String readLine() throws IOException {
            currentIndex++;
            if (currentIndex < lines.size()) {
                return lines.get(currentIndex);
            }
            return null;
        }
        
        @Override
        public void close() throws IOException {
            // No-op for in-memory reader
        }
    }
}
