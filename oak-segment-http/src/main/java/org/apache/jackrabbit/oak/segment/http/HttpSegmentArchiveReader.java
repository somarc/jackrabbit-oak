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
import org.apache.jackrabbit.oak.segment.remote.AbstractRemoteSegmentArchiveReader;
import org.apache.jackrabbit.oak.segment.remote.RemoteSegmentArchiveEntry;
import org.apache.jackrabbit.oak.segment.spi.monitor.IOMonitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

/**
 * HTTP-based implementation of SegmentArchiveReader.
 * Fetches segments over HTTP from a GlobalStoreServer.
 */
public class HttpSegmentArchiveReader extends AbstractRemoteSegmentArchiveReader {

    private static final Logger log = LoggerFactory.getLogger(HttpSegmentArchiveReader.class);

    private final CloseableHttpClient httpClient;
    private final HttpClientPool httpClientPool;
    private final String baseUrl;
    private final String archiveName;
    private final long length;

    public HttpSegmentArchiveReader(String baseUrl, String archiveName, IOMonitor ioMonitor, HttpClientPool httpClientPool) throws IOException {
        super(ioMonitor); // MUST be first in Java
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.archiveName = archiveName;
        this.httpClientPool = httpClientPool;
        this.httpClient = httpClientPool.getHttpClient();
        this.length = computeArchiveIndexAndLength();
        log.debug("Initialized HttpSegmentArchiveReader for archive: {} at: {}", archiveName, this.baseUrl);
    }

    @Override
    public long length() {
        return length;
    }

    @Override
    public String getName() {
        return archiveName;
    }

    @Override
    protected long computeArchiveIndexAndLength() throws IOException {
        // Server uses simple /segments/{uuid} API
        // Segments are discovered on-demand, so archive length is unknown initially
        log.debug("Archive index not available - segments will be fetched on-demand from: {}/segments/{{uuid}}", baseUrl);
        return 0; // Unknown length until segments are read
    }

    @Override
    public Buffer readSegment(long msb, long lsb) throws IOException {
        // POC OVERRIDE: Bypass index lookup, fetch directly via HTTP
        // This allows on-demand fetching without pre-populating the archive index
        
        UUID uuid = new UUID(msb, lsb);
        String segmentUrl = baseUrl + "/segments/" + uuid.toString();
        log.debug("Fetching segment directly: {}", segmentUrl);

        HttpGet request = new HttpGet(segmentUrl);
        try (CloseableHttpResponse response = httpClient.execute(request)) {
            int statusCode = response.getStatusLine().getStatusCode();
            
            if (statusCode == HttpStatus.SC_OK) {
                byte[] segmentData = response.getEntity().getContent().readAllBytes();
                log.debug("Fetched segment {} ({} bytes)", uuid, segmentData.length);
                return Buffer.wrap(segmentData);
            } else if (statusCode == HttpStatus.SC_NOT_FOUND) {
                log.debug("Segment {} not found", uuid);
                return null;
            } else {
                throw new IOException("Failed to fetch segment: HTTP " + statusCode);
            }
        }
    }

    @Override
    public boolean containsSegment(long msb, long lsb) {
        // POC OVERRIDE: Check via HTTP HEAD instead of index lookup
        UUID uuid = new UUID(msb, lsb);
        String segmentUrl = baseUrl + "/segments/" + uuid.toString();

        org.apache.http.client.methods.HttpHead request = new org.apache.http.client.methods.HttpHead(segmentUrl);
        try (CloseableHttpResponse response = httpClient.execute(request)) {
            return response.getStatusLine().getStatusCode() == HttpStatus.SC_OK;
        } catch (IOException e) {
            log.warn("Error checking segment existence: {}", e.getMessage());
            return false;
        }
    }

    @Override
    protected void doReadSegmentToBuffer(String segmentFileName, Buffer buffer) throws IOException {
        // POC: segmentFileName format from RemoteSegmentArchiveEntry: "position.msb-lsb"
        // Example: "00000.{msb-as-hex}-{lsb-as-hex}"
        // But we're called with msb/lsb from the base class, so we need to extract UUID
        
        // Extract UUID from segment entry in index (already called by base class)
        // The base class passes the segment filename, which contains the UUID
        
        // For POC: Parse UUID from filename pattern
        String uuidPart = segmentFileName;
        if (segmentFileName.contains(".")) {
            // Format: "position.msb-lsb" -> extract "msb-lsb"
            uuidPart = segmentFileName.substring(segmentFileName.indexOf('.') + 1);
        }
        
        // Convert to UUID
        String segmentUrl = baseUrl + "/segments/" + uuidPart;
        log.debug("Fetching segment from: {}", segmentUrl);

        HttpGet request = new HttpGet(segmentUrl);
        try (CloseableHttpResponse response = httpClient.execute(request)) {
            int statusCode = response.getStatusLine().getStatusCode();
            
            if (statusCode == HttpStatus.SC_OK) {
                byte[] segmentData = response.getEntity().getContent().readAllBytes();
                buffer.put(segmentData, 0, segmentData.length);
                buffer.flip();
                log.debug("Fetched segment {} ({} bytes)", uuidPart, segmentData.length);
            } else {
                throw new IOException("Failed to fetch segment: HTTP " + statusCode);
            }
        }
    }

    @Override
    protected Buffer doReadDataFile(String extension) throws IOException {
        // POC SIMPLIFICATION: No archive metadata endpoints yet
        // Graph (.gph) and binary references (.brf) will be computed on-demand
        // by the base class if not available
        log.debug("POC mode: No archive metadata file for extension {}", extension);
        return null;
    }

    @Override
    protected File archivePathAsFile() {
        return new File(baseUrl + "/" + archiveName);
    }

    @Override
    public void close() {
        super.close();
        // Don't close httpClient - it's a shared pool managed by HttpClientPool
        // The pool will be closed when HttpPersistence is shut down
        log.debug("Closed HttpSegmentArchiveReader (pool remains active)");
    }
}

