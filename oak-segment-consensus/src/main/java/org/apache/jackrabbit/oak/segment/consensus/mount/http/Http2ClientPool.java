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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Shared transport wrapper for the HTTP segment-store client.
 *
 * <p>The pool owns a single JDK {@link HttpClient} configured to prefer
 * HTTP/2 while remaining compatible with servers that only negotiate
 * HTTP/1.1. The same client is reused for segment, journal, GC journal, and
 * manifest reads so connection setup and protocol negotiation are amortized
 * across the mount.</p>
 *
 * <p>The class also keeps lightweight counters that are useful when debugging
 * which protocol version was negotiated and how much payload has been
 * transferred.</p>
 */
public class Http2ClientPool {
    
    private static final Logger log = LoggerFactory.getLogger(Http2ClientPool.class);
    
    // Timeouts
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    
    // Executor shared with the JDK client implementation.
    private static final int THREAD_POOL_SIZE = 10;
    
    private final HttpClient httpClient;
    private final Executor executor;
    
    // Metrics
    private final AtomicLong requestCount = new AtomicLong(0);
    private final AtomicLong http2RequestCount = new AtomicLong(0);
    private final AtomicLong http1RequestCount = new AtomicLong(0);
    private final AtomicLong totalBytesReceived = new AtomicLong(0);
    
    /**
     * Creates a pool with the default timeouts and a daemon-thread executor.
     */
    public Http2ClientPool() {
        log.info("Initializing HTTP/2 Client Pool (connectTimeout={}s, requestTimeout={}s)", 
                 CONNECT_TIMEOUT.getSeconds(), REQUEST_TIMEOUT.getSeconds());
        
        this.executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE, r -> {
            Thread t = new Thread(r, "http2-client-pool");
            t.setDaemon(true);
            return t;
        });
        
        this.httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)  // Prefer HTTP/2, fallback to HTTP/1.1
            .connectTimeout(CONNECT_TIMEOUT)
            .executor(executor)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
        
        log.info("HTTP/2 Client Pool initialized (version preference: HTTP/2 with HTTP/1.1 fallback)");
    }

    /**
     * Testing seam that injects a prebuilt client and skips executor creation.
     */
    Http2ClientPool(HttpClient httpClient) {
        this.httpClient = httpClient;
        this.executor = null;
    }
    
    /**
     * Returns the shared client instance used by this pool.
     *
     * @return the configured {@link HttpClient}
     */
    public HttpClient getHttpClient() {
        return httpClient;
    }
    
    /**
     * Fetches a binary resource and records protocol and byte counters.
     *
     * @param url the resource URL
     * @return the response body
     * @throws Exception if the request fails or returns a non-200 status
     */
    public byte[] get(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(REQUEST_TIMEOUT)
            .GET()
            .build();
        
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        
        // Record which protocol version the server negotiated for this request.
        requestCount.incrementAndGet();
        if (response.version() == HttpClient.Version.HTTP_2) {
            http2RequestCount.incrementAndGet();
        } else {
            http1RequestCount.incrementAndGet();
        }
        
        byte[] body = response.body();
        totalBytesReceived.addAndGet(body.length);
        
        if (response.statusCode() != 200) {
            throw new RuntimeException("HTTP " + response.statusCode() + " for " + url);
        }
        
        return body;
    }
    
    /**
     * Fetches a text resource and records which protocol version was used.
     *
     * @param url the resource URL
     * @return the response body as a string
     * @throws Exception if the request fails or returns a non-200 status
     */
    public String getString(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(REQUEST_TIMEOUT)
            .GET()
            .build();
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        // Record which protocol version the server negotiated for this request.
        requestCount.incrementAndGet();
        if (response.version() == HttpClient.Version.HTTP_2) {
            http2RequestCount.incrementAndGet();
        } else {
            http1RequestCount.incrementAndGet();
        }
        
        if (response.statusCode() != 200) {
            throw new RuntimeException("HTTP " + response.statusCode() + " for " + url);
        }
        
        return response.body();
    }
    
    /**
     * Uses {@code HEAD} to test whether a resource is present without fetching
     * its body.
     *
     * @param url the resource URL
     * @return {@code true} when the endpoint returns HTTP 200, otherwise
     *         {@code false}
     */
    public boolean exists(String url) {
        return exists(url, true);
    }

    /**
     * Variant of {@link #exists(String)} that suppresses warning-level logs
     * when the probe is expected to race startup.
     *
     * @param url the resource URL
     * @return {@code true} when the endpoint returns HTTP 200, otherwise
     *         {@code false}
     */
    public boolean existsQuietly(String url) {
        return exists(url, false);
    }

    private boolean exists(String url, boolean warnOnFailure) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build();
            
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            
            requestCount.incrementAndGet();
            if (response.version() == HttpClient.Version.HTTP_2) {
                http2RequestCount.incrementAndGet();
            } else {
                http1RequestCount.incrementAndGet();
            }
            
            return response.statusCode() == 200;
        } catch (Exception e) {
            String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            if (warnOnFailure) {
                log.warn("HEAD request failed for {}: {}", url, message);
            } else {
                log.debug("HEAD request failed during quiet probe for {}: {}", url, message);
            }
            return false;
        }
    }
    
    /**
     * Returns the current counters in a log-friendly format.
     *
     * @return a formatted snapshot of protocol and transfer statistics
     */
    public String getPoolStats() {
        long total = requestCount.get();
        long h2 = http2RequestCount.get();
        long h1 = http1RequestCount.get();
        long bytes = totalBytesReceived.get();
        double h2Percent = total > 0 ? (h2 * 100.0 / total) : 0;
        
        return String.format("Requests=%d, HTTP/2=%d (%.1f%%), HTTP/1.1=%d, BytesReceived=%d", 
                             total, h2, h2Percent, h1, bytes);
    }
    
    /**
     * Log current statistics.
     */
    public void logStats() {
        log.info("HTTP/2 Pool Stats: {}", getPoolStats());
    }
    
    /**
     * Logs the final counters for the pool.
     *
     * <p>The JDK {@link HttpClient} API does not expose an explicit close
     * operation, so this method is intentionally observational.</p>
     */
    public void shutdown() {
        log.info("Shutting down HTTP/2 Client Pool. Final stats: {}", getPoolStats());
        // Java HttpClient doesn't need explicit shutdown, but log final stats
    }
}
