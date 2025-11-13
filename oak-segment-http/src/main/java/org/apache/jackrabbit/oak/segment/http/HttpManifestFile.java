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
import org.apache.jackrabbit.oak.segment.spi.persistence.ManifestFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Properties;

/**
 * HTTP-based manifest file for read-only access.
 */
public class HttpManifestFile implements ManifestFile {
    
    private static final Logger log = LoggerFactory.getLogger(HttpManifestFile.class);
    
    private final String baseUrl;
    private final HttpClientPool httpClientPool;
    private final CloseableHttpClient httpClient;
    
    public HttpManifestFile(String baseUrl, HttpClientPool httpClientPool) {
        this.baseUrl = baseUrl;
        this.httpClientPool = httpClientPool;
        this.httpClient = httpClientPool.getHttpClient();
    }
    
    @Override
    public boolean exists() {
        String manifestUrl = baseUrl + "/manifest";
        log.debug("🔍 Checking if manifest exists at: {}", manifestUrl);
        try {
            org.apache.http.client.methods.HttpHead request = 
                new org.apache.http.client.methods.HttpHead(manifestUrl);
            try (CloseableHttpResponse response = httpClient.execute(request)) {
                int statusCode = response.getStatusLine().getStatusCode();
                boolean exists = (statusCode == HttpStatus.SC_OK);
                log.debug("🔍 Manifest HEAD request: {} -> {} (exists: {})", manifestUrl, statusCode, exists);
                return exists;
            }
        } catch (IOException e) {
            log.warn("⚠️ Failed to check manifest existence at {}: {}", manifestUrl, e.getMessage());
            return false;
        }
    }
    
    @Override
    public Properties load() throws IOException {
        HttpGet request = new HttpGet(baseUrl + "/manifest");
        try (CloseableHttpResponse response = httpClient.execute(request)) {
            if (response.getStatusLine().getStatusCode() == HttpStatus.SC_OK) {
                Properties props = new Properties();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(response.getEntity().getContent()))) {
                    props.load(reader);
                }
                return props;
            } else if (response.getStatusLine().getStatusCode() == HttpStatus.SC_NOT_FOUND) {
                // Manifest doesn't exist - return empty properties
                return new Properties();
            } else {
                throw new IOException("Failed to fetch manifest: HTTP " + 
                    response.getStatusLine().getStatusCode());
            }
        }
    }
    
    @Override
    public void save(Properties properties) throws IOException {
        // No-op for read-only HTTP mount
        // Silently ignore manifest writes - this is expected for read-only stores
        // DO NOT throw exception - Oak initialization requires this to succeed
    }
}

