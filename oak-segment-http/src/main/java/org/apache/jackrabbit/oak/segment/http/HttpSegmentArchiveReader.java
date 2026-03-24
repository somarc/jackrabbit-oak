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

import org.apache.jackrabbit.oak.commons.Buffer;
import org.apache.jackrabbit.oak.segment.remote.AbstractRemoteSegmentArchiveReader;
import org.apache.jackrabbit.oak.segment.spi.monitor.IOMonitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.UUID;

/**
 * Remote {@link org.apache.jackrabbit.oak.segment.spi.persistence.SegmentArchiveReader}
 * that resolves segment UUIDs through the HTTP persistence endpoints.
 *
 * <p>The reader fetches individual segments from {@code /segments/&lt;uuid&gt;}
 * and does not currently receive a remote archive index or sidecar files such
 * as graph or binary-reference data. Those gaps are surfaced to the base class
 * through empty metadata so the reader stays honest about the current endpoint
 * contract.</p>
 */
public class HttpSegmentArchiveReader extends AbstractRemoteSegmentArchiveReader {

    private static final Logger log = LoggerFactory.getLogger(HttpSegmentArchiveReader.class);

    private final Http2ClientPool http2ClientPool;
    private final String baseUrl;
    private final String archiveName;

    /**
     * Creates a reader for a single remote archive view.
     *
     * @param baseUrl base URL of the remote segment-store endpoint
     * @param archiveName logical archive name presented to Oak
     * @param ioMonitor I/O monitor used by the base reader
     * @param http2ClientPool shared client used for remote reads
     * @throws IOException if the reader cannot be initialized
     */
    public HttpSegmentArchiveReader(String baseUrl, String archiveName, IOMonitor ioMonitor, Http2ClientPool http2ClientPool) throws IOException {
        super(ioMonitor, archiveName, Collections.emptyList()); // No remote index is exposed yet.
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.archiveName = archiveName;
        this.http2ClientPool = http2ClientPool;
        log.debug("Initialized HttpSegmentArchiveReader (HTTP/2) for archive: {} at: {}", archiveName, this.baseUrl);
    }
    
    @Override
    public String getName() {
        return archiveName;
    }

    @Override
    public Buffer readSegment(long msb, long lsb) throws IOException {
        UUID uuid = new UUID(msb, lsb);
        String segmentUrl = baseUrl + "/segments/" + uuid.toString();
        log.debug("Fetching segment via HTTP/2: {}", segmentUrl);

        try {
            byte[] segmentData = http2ClientPool.get(segmentUrl);
            log.debug("Fetched segment {} ({} bytes) via HTTP/2", uuid, segmentData.length);
            return Buffer.wrap(segmentData);
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("404")) {
                log.debug("Segment {} not found", uuid);
                return null;
            }
            throw new IOException("Failed to fetch segment via HTTP/2: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean containsSegment(long msb, long lsb) {
        // Use HEAD so existence checks do not download the segment body.
        UUID uuid = new UUID(msb, lsb);
        String segmentUrl = baseUrl + "/segments/" + uuid.toString();
        return http2ClientPool.exists(segmentUrl);
    }

    @Override
    protected void doReadSegmentToBuffer(String segmentFileName, Buffer buffer) throws IOException {
        // AbstractRemoteSegmentArchiveReader passes names like
        // "position.uuid"; only the UUID portion is part of the HTTP contract.
        String uuidPart = segmentFileName;
        if (segmentFileName.contains(".")) {
            uuidPart = segmentFileName.substring(segmentFileName.indexOf('.') + 1);
        }
        
        String segmentUrl = baseUrl + "/segments/" + uuidPart;
        log.debug("Fetching segment via HTTP/2: {}", segmentUrl);

        try {
            byte[] segmentData = http2ClientPool.get(segmentUrl);
            buffer.put(segmentData, 0, segmentData.length);
            buffer.flip();
            log.debug("Fetched segment {} ({} bytes) via HTTP/2", uuidPart, segmentData.length);
        } catch (Exception e) {
            throw new IOException("Failed to fetch segment via HTTP/2: " + e.getMessage(), e);
        }
    }

    @Override
    protected Buffer doReadDataFile(String extension) throws IOException {
        // The remote endpoint does not currently expose archive sidecar files.
        // Returning null lets the base class fall back to its on-demand logic.
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
        // Log HTTP/2 stats on close
        log.debug("Closed HttpSegmentArchiveReader. HTTP/2 stats: {}", http2ClientPool.getPoolStats());
    }
}
