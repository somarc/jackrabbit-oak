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

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.time.Duration;

/**
 * Shared transport wrapper for the HTTP segment-store client.
 *
 * <p>The pool owns a single JDK {@link HttpClient} configured to prefer
 * HTTP/2 while remaining compatible with servers that only negotiate
 * HTTP/1.1. The same client is reused for segment, journal, GC journal, and
 * manifest reads so connection setup and protocol negotiation are amortized
 * across the mount. The client's default executor lets idle threads expire, so
 * a retired mount leaves no threads behind.</p>
 */
public class Http2ClientPool {
    
    private static final Logger log = LoggerFactory.getLogger(Http2ClientPool.class);
    
    // Timeouts
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    
    private final HttpClient httpClient;
    
    /**
     * Creates a pool with the default timeouts.
     */
    public Http2ClientPool() {
        this(HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)  // Prefer HTTP/2, fallback to HTTP/1.1
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build());
        log.info("HTTP/2 Client Pool initialized (connectTimeout={}s, requestTimeout={}s, HTTP/2 with HTTP/1.1 fallback)",
            CONNECT_TIMEOUT.getSeconds(), REQUEST_TIMEOUT.getSeconds());
    }

    /**
     * Testing seam that injects a prebuilt client.
     */
    Http2ClientPool(HttpClient httpClient) {
        this.httpClient = httpClient;
    }
    
    /**
     * Fetches a binary resource.
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
        byte[] body = response.body();
        if (response.statusCode() != 200) {
            throw new RuntimeException("HTTP " + response.statusCode() + " for " + url);
        }
        
        return body;
    }
    
    /**
     * Fetches a text resource.
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

    /**
     * Status of a {@code HEAD} request, so callers can tell "absent" (404) from a failing endpoint.
     *
     * @param url the resource URL
     * @return the HTTP status code
     * @throws IOException when the request itself fails
     * @throws InterruptedException when interrupted while waiting for the response
     */
    public int headStatus(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(REQUEST_TIMEOUT)
            .method("HEAD", HttpRequest.BodyPublishers.noBody())
            .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private boolean exists(String url, boolean warnOnFailure) {
        try {
            return headStatus(url) == 200;
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
}
