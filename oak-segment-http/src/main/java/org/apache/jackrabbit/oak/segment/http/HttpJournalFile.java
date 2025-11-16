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
import org.apache.jackrabbit.oak.segment.remote.WriteAccessController;
import org.apache.jackrabbit.oak.segment.spi.persistence.JournalFile;
import org.apache.jackrabbit.oak.segment.spi.persistence.JournalFileReader;
import org.apache.jackrabbit.oak.segment.spi.persistence.JournalFileWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * HTTP-based journal file implementation for read-only segment store access.
 */
public class HttpJournalFile implements JournalFile {
    
    private static final Logger log = LoggerFactory.getLogger(HttpJournalFile.class);
    
    private final String baseUrl;
    private final WriteAccessController writeAccessController;
    private final HttpClientPool httpClientPool;
    private final CloseableHttpClient httpClient;
    
    public HttpJournalFile(String baseUrl, WriteAccessController writeAccessController, HttpClientPool httpClientPool) {
        this.baseUrl = baseUrl;
        this.writeAccessController = writeAccessController;
        this.httpClientPool = httpClientPool;
        this.httpClient = httpClientPool.getHttpClient();
        log.debug("Initialized HttpJournalFile for: {} (pool: {})", baseUrl, httpClientPool.getPoolStats());
    }
    
    @Override
    public JournalFileReader openJournalReader() throws IOException {
        String url = baseUrl + "/journal.log";
        log.debug("Fetching journal from: {}", url);
        
        HttpGet request = new HttpGet(url);
        try (CloseableHttpResponse response = httpClient.execute(request)) {
            if (response.getStatusLine().getStatusCode() == HttpStatus.SC_OK) {
                // Read all lines into memory
                List<String> lines = new ArrayList<>();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(response.getEntity().getContent()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        lines.add(line);
                    }
                }
                
                log.debug("Loaded {} journal entries from HTTP", lines.size());
                return new HttpJournalFileReader(lines);
            } else {
                throw new IOException("Failed to fetch journal.log: HTTP " + 
                    response.getStatusLine().getStatusCode());
            }
        }
    }
    
    @Override
    public JournalFileWriter openJournalWriter() throws IOException {
        // Read-only mount - return a no-op writer
        // DO NOT call checkWritingAllowed() - it blocks forever!
        // Oak's TarRevisions requires a writer even for read-only stores
        log.debug("Returning no-op journal writer for read-only HTTP mount");
        return new NoOpJournalFileWriter();
    }
    
    /**
     * No-op journal writer for read-only HTTP mounts.
     * All write operations are silently ignored since this is a read-only view.
     */
    private static class NoOpJournalFileWriter implements JournalFileWriter {
        @Override
        public void truncate() throws IOException {
            // No-op for read-only mount
        }

        @Override
        public void writeLine(String line) throws IOException {
            // No-op for read-only mount - writes are silently ignored
        }

        @Override
        public void batchWriteLines(java.util.List<String> lines) throws IOException {
            // No-op for read-only mount - writes are silently ignored
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
        try {
            String url = baseUrl + "/journal.log";
            org.apache.http.client.methods.HttpHead request = 
                new org.apache.http.client.methods.HttpHead(url);
            try (CloseableHttpResponse response = httpClient.execute(request)) {
                return response.getStatusLine().getStatusCode() == HttpStatus.SC_OK;
            }
        } catch (IOException e) {
            log.debug("Error checking journal existence: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * In-memory journal reader for HTTP-fetched content.
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

