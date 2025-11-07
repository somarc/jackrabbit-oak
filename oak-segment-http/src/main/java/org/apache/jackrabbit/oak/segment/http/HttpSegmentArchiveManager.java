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
    private final CloseableHttpClient httpClient;

    /**
     * Create a new HTTP-based archive manager.
     * 
     * @param baseUrl Base URL of the GlobalStoreServer (e.g., "http://oak-global-store:8090")
     * @param ioMonitor IO monitor for tracking read operations
     */
    public HttpSegmentArchiveManager(String baseUrl, IOMonitor ioMonitor) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.ioMonitor = ioMonitor;
        this.httpClient = HttpClients.createDefault();
        log.info("Initialized HttpSegmentArchiveManager for: {}", this.baseUrl);
    }

    @Override
    public List<String> listArchives() throws IOException {
        // POC SIMPLIFICATION: No archive listing endpoint yet
        // For now, assume a single archive named "data.tar" (standard Oak naming)
        log.debug("POC mode: Returning hardcoded archive list");
        List<String> archives = new ArrayList<>();
        archives.add("data00000a.tar"); // Common Oak segment archive name
        return archives;
    }

    @Override
    public SegmentArchiveReader open(String archiveName) throws IOException {
        log.debug("Opening archive: {}", archiveName);
        
        // Check if archive exists by querying the server
        if (!exists(archiveName)) {
            log.debug("Archive does not exist: {}", archiveName);
            return null;
        }

        return new HttpSegmentArchiveReader(baseUrl, archiveName, ioMonitor);
    }

    @Override
    public SegmentArchiveReader forceOpen(String archiveName) throws IOException {
        log.debug("Force opening archive: {}", archiveName);
        // For HTTP-based store, forceOpen is the same as open
        return new HttpSegmentArchiveReader(baseUrl, archiveName, ioMonitor);
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
        throw new UnsupportedOperationException(
            "HttpSegmentArchiveManager is read-only for POC. Write operations not supported."
        );
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
}

