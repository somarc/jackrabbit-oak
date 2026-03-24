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

import org.apache.jackrabbit.oak.segment.spi.persistence.GCJournalFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Read-only {@link GCJournalFile} backed by the remote {@code /gc.log}
 * endpoint.
 *
 * <p>Writes and truncation are intentionally ignored because the HTTP
 * persistence layer exposes a read-only mount. A missing remote GC journal is
 * treated as an empty history.</p>
 */
public class HttpGCJournalFile implements GCJournalFile {
    
    private static final Logger log = LoggerFactory.getLogger(HttpGCJournalFile.class);
    
    private final String baseUrl;
    private final Http2ClientPool http2ClientPool;
    
    /**
     * Creates a remote GC journal adapter rooted at the given persistence URL.
     *
     * @param baseUrl the base URL of the remote HTTP persistence endpoint
     * @param http2ClientPool shared client used for remote reads
     */
    public HttpGCJournalFile(String baseUrl, Http2ClientPool http2ClientPool) {
        this.baseUrl = baseUrl;
        this.http2ClientPool = http2ClientPool;
    }
    
    @Override
    public void writeLine(String line) throws IOException {
        // Intentionally ignored because the remote HTTP store is read-only.
    }
    
    @Override
    public List<String> readLines() throws IOException {
        String url = baseUrl + "/gc.log";
        try {
            String content = http2ClientPool.getString(url);
            return new ArrayList<>(Arrays.asList(content.split("\n")));
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("404")) {
                // A missing GC journal means the remote store has no GC history yet.
                return new ArrayList<>();
            }
            throw new IOException("Failed to fetch gc.log via HTTP/2: " + e.getMessage(), e);
        }
    }
    
    @Override
    public void truncate() throws IOException {
        // Intentionally ignored because the remote HTTP store is read-only.
    }
}
