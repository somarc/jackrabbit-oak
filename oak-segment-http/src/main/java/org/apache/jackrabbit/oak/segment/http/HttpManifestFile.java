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

import org.apache.jackrabbit.oak.segment.spi.persistence.ManifestFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.StringReader;
import java.util.Properties;

/**
 * Read-only {@link ManifestFile} adapter over the remote {@code /manifest}
 * endpoint.
 *
 * <p>{@link #load()} returns empty properties when the remote manifest is
 * absent. {@link #save(Properties)} is a no-op because the HTTP persistence
 * layer never mutates the remote store, but Oak startup still expects the call
 * to succeed.</p>
 */
public class HttpManifestFile implements ManifestFile {
    
    private static final Logger log = LoggerFactory.getLogger(HttpManifestFile.class);
    
    private final String baseUrl;
    private final Http2ClientPool http2ClientPool;
    
    /**
     * Creates a manifest adapter rooted at the given persistence URL.
     *
     * @param baseUrl the base URL of the remote HTTP persistence endpoint
     * @param http2ClientPool shared client used for remote reads
     */
    public HttpManifestFile(String baseUrl, Http2ClientPool http2ClientPool) {
        this.baseUrl = baseUrl;
        this.http2ClientPool = http2ClientPool;
    }
    
    @Override
    public boolean exists() {
        String manifestUrl = baseUrl + "/manifest";
        log.debug("Checking if manifest exists via HTTP/2 at: {}", manifestUrl);
        return http2ClientPool.exists(manifestUrl);
    }
    
    @Override
    public Properties load() throws IOException {
        String url = baseUrl + "/manifest";
        try {
            String content = http2ClientPool.getString(url);
            Properties props = new Properties();
            props.load(new StringReader(content));
            return props;
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("404")) {
                // A missing manifest is treated the same as an empty one.
                return new Properties();
            }
            throw new IOException("Failed to fetch manifest via HTTP/2: " + e.getMessage(), e);
        }
    }
    
    @Override
    public void save(Properties properties) throws IOException {
        // Intentionally ignored because the mount is read-only and callers still
        // expect the save path to complete without failing startup.
    }
}
