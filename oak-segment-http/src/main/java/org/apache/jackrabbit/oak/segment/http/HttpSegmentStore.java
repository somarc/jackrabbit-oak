/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jackrabbit.oak.segment.http;

import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.apache.jackrabbit.oak.commons.Buffer;
import org.apache.jackrabbit.oak.segment.Segment;
import org.apache.jackrabbit.oak.segment.SegmentId;
import org.apache.jackrabbit.oak.segment.SegmentIdProvider;
import org.apache.jackrabbit.oak.segment.SegmentNotFoundException;
import org.apache.jackrabbit.oak.segment.SegmentStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * HTTP-based remote segment store for Blockchain AEM.
 * 
 * <p>This implementation uses Oak's PUBLIC SegmentStore interface to fetch
 * segments over HTTP from a remote GlobalStoreServer. This is the proven
 * pattern used by oak-segment-azure and oak-segment-aws.</p>
 * 
 * <p><strong>Key Design Principles:</strong></p>
 * <ul>
 *   <li>Uses ONLY public Oak APIs (no internal access)</li>
 *   <li>Regular OSGi bundle (not a fragment)</li>
 *   <li>Read-only access to global store</li>
 *   <li>Local caching for performance</li>
 * </ul>
 * 
 * <p><strong>Protocol:</strong></p>
 * <pre>
 * GET /segments/{segmentId} → segment bytes
 * HEAD /segments/{segmentId} → 200 if exists, 404 if not
 * </pre>
 */
public class HttpSegmentStore implements SegmentStore {
    
    private static final Logger LOG = LoggerFactory.getLogger(HttpSegmentStore.class);
    
    private static final int MAX_CACHE_SIZE = 256;
    
    private final String baseUrl;
    private final CloseableHttpClient httpClient;
    private final SegmentIdProvider segmentIdProvider;
    
    /**
     * Local LRU cache of segments fetched from remote store.
     * Key: SegmentId (as string), Value: Segment bytes
     */
    private final Map<String, byte[]> segmentCache;
    private final ReadWriteLock cacheLock;
    
    /**
     * Creates a new HTTP segment store.
     * 
     * @param baseUrl Base URL of the GlobalStoreServer (e.g., "http://global-store:8090")
     * @param segmentIdProvider Provider for creating SegmentId instances (needed for parsing)
     */
    public HttpSegmentStore(String baseUrl, SegmentIdProvider segmentIdProvider) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.httpClient = HttpClients.createDefault();
        this.segmentIdProvider = segmentIdProvider;
        this.cacheLock = new ReentrantReadWriteLock();
        
        // Simple LRU cache using LinkedHashMap (no Guava - security)
        this.segmentCache = new LinkedHashMap<String, byte[]>(MAX_CACHE_SIZE + 1, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
                return size() > MAX_CACHE_SIZE;
            }
        };
        
        LOG.info("===========================================");
        LOG.info("  HttpSegmentStore Initialized");
        LOG.info("  Remote URL: {}", this.baseUrl);
        LOG.info("  Cache Size: {} segments (LRU)", MAX_CACHE_SIZE);
        LOG.info("===========================================");
    }
    
    @Override
    public boolean containsSegment(SegmentId id) {
        String segmentIdStr = id.toString();
        
        // Check cache first (thread-safe read)
        cacheLock.readLock().lock();
        try {
            if (segmentCache.containsKey(segmentIdStr)) {
                LOG.debug("Segment {} found in cache", segmentIdStr);
                return true;
            }
        } finally {
            cacheLock.readLock().unlock();
        }
        
        // Check remote via HEAD request
        String url = baseUrl + "/segments/" + segmentIdStr;
        HttpHead request = new HttpHead(url);
        
        try (CloseableHttpResponse response = httpClient.execute(request)) {
            int statusCode = response.getStatusLine().getStatusCode();
            boolean exists = (statusCode == 200);
            
            LOG.debug("Segment {} {} on remote store (HTTP {})", 
                segmentIdStr, exists ? "exists" : "not found", statusCode);
            
            return exists;
            
        } catch (IOException e) {
            LOG.error("Failed to check segment existence: " + segmentIdStr, e);
            return false;
        }
    }
    @Override
    public Segment readSegment(SegmentId segmentId) {
        String segmentIdStr = segmentId.toString();
        
        // Check cache first (thread-safe read)
        cacheLock.readLock().lock();
        byte[] cachedBytes;
        try {
            cachedBytes = segmentCache.get(segmentIdStr);
        } finally {
            cacheLock.readLock().unlock();
        }
        
        if (cachedBytes != null) {
            LOG.debug("Segment {} retrieved from cache ({} bytes)", segmentIdStr, cachedBytes.length);
            return parseSegment(segmentId, cachedBytes);
        }
        
        // Fetch from remote
        String url = baseUrl + "/segments/" + segmentIdStr;
        HttpGet request = new HttpGet(url);
        
        try (CloseableHttpResponse response = httpClient.execute(request)) {
            int statusCode = response.getStatusLine().getStatusCode();
            
            if (statusCode == 404) {
                LOG.warn("Segment {} not found on remote store", segmentIdStr);
                throw new SegmentNotFoundException(segmentId);
            }
            
            if (statusCode != 200) {
                throw new IOException("Unexpected HTTP status: " + statusCode);
            }
            
            HttpEntity entity = response.getEntity();
            if (entity == null) {
                throw new IOException("No content in HTTP response");
            }
            
            byte[] segmentBytes = EntityUtils.toByteArray(entity);
            
            LOG.info("✅ Fetched segment {} from remote ({} bytes)", segmentIdStr, segmentBytes.length);
            
            // Cache for future reads (thread-safe write)
            cacheLock.writeLock().lock();
            try {
                segmentCache.put(segmentIdStr, segmentBytes);
            } finally {
                cacheLock.writeLock().unlock();
            }
            
            return parseSegment(segmentId, segmentBytes);
            
        } catch (SegmentNotFoundException e) {
            throw e;
        } catch (IOException e) {
            LOG.error("Failed to read segment from remote: " + segmentIdStr, e);
            throw new SegmentNotFoundException(segmentId);
        }
    }
    
    @Override
    public void writeSegment(SegmentId id, byte[] bytes, int offset, int length) throws IOException {
        // Read-only store for POC
        // Future: Could POST to /segments/{id} for consensus writes
        throw new IOException("HttpSegmentStore is read-only (write via consensus not yet implemented)");
    }
    
    /**
     * Parses segment bytes into a Segment object.
     * 
     * <p>Uses the Segment's public constructor that accepts a SegmentIdProvider
     * and Buffer. This is the standard way to reconstruct segments from bytes,
     * used throughout Oak including Cold Standby client.</p>
     * 
     * @param segmentId The ID of the segment being parsed
     * @param bytes The raw segment bytes fetched from remote
     * @return Reconstructed Segment object
     */
    private Segment parseSegment(SegmentId segmentId, byte[] bytes) {
        // Wrap bytes in Oak's Buffer
        Buffer buffer = Buffer.wrap(bytes);
        
        // Create Segment using public constructor
        // This reconstructs the segment with all its metadata
        return new Segment(segmentIdProvider, segmentId, buffer);
    }
    
    /**
     * Closes the HTTP client and cleans up resources.
     */
    public void close() throws IOException {
        LOG.info("Closing HttpSegmentStore...");
        httpClient.close();
        
        // Clear cache (thread-safe)
        cacheLock.writeLock().lock();
        try {
            segmentCache.clear();
        } finally {
            cacheLock.writeLock().unlock();
        }
        
        LOG.info("HttpSegmentStore closed");
    }
}

