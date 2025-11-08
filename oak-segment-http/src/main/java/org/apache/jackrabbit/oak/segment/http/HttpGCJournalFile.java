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
import org.apache.jackrabbit.oak.segment.spi.persistence.GCJournalFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * HTTP-based GC journal file for read-only access.
 */
public class HttpGCJournalFile implements GCJournalFile {
    
    private final String baseUrl;
    private final CloseableHttpClient httpClient;
    
    public HttpGCJournalFile(String baseUrl) {
        this.baseUrl = baseUrl;
        this.httpClient = HttpClients.createDefault();
    }
    
    @Override
    public void writeLine(String line) throws IOException {
        // No-op for read-only HTTP mount
        // Silently ignore GC journal writes - this is expected for read-only stores
    }
    
    @Override
    public List<String> readLines() throws IOException {
        HttpGet request = new HttpGet(baseUrl + "/gc.log");
        try (CloseableHttpResponse response = httpClient.execute(request)) {
            if (response.getStatusLine().getStatusCode() == HttpStatus.SC_OK) {
                List<String> lines = new ArrayList<>();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(response.getEntity().getContent()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        lines.add(line);
                    }
                }
                return lines;
            } else if (response.getStatusLine().getStatusCode() == HttpStatus.SC_NOT_FOUND) {
                // GC journal doesn't exist yet - return empty
                return new ArrayList<>();
            } else {
                throw new IOException("Failed to fetch gc.log: HTTP " + 
                    response.getStatusLine().getStatusCode());
            }
        }
    }
    
    @Override
    public void truncate() throws IOException {
        // No-op for read-only HTTP mount
        // Silently ignore truncation - this is expected for read-only stores
    }
}

