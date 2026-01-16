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
package org.apache.jackrabbit.oak.segment.http.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Rate limiter for API endpoints using token bucket algorithm.
 * 
 * <p>Provides protection against:
 * <ul>
 *   <li>DDoS attacks</li>
 *   <li>Abusive clients</li>
 *   <li>Resource exhaustion</li>
 * </ul>
 * 
 * <p>Rate limiting strategies:
 * <ul>
 *   <li>Per-IP: Limits requests from a single IP address</li>
 *   <li>Per-Wallet: Limits requests from a single wallet address</li>
 *   <li>Global: Limits total requests to the server</li>
 * </ul>
 * 
 * <p>System properties:
 * <ul>
 *   <li>{@code rate.limit.enabled} - Enable rate limiting (default: true)</li>
 *   <li>{@code rate.limit.requests.per.second} - Requests per second per client (default: 100)</li>
 *   <li>{@code rate.limit.burst.size} - Maximum burst size (default: 200)</li>
 *   <li>{@code rate.limit.global.rps} - Global requests per second (default: 1000)</li>
 *   <li>{@code rate.limit.write.rps} - Write requests per second per wallet (default: 10)</li>
 * </ul>
 * 
 * @since 1.89
 */
public class RateLimiter {
    
    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);
    
    // System property keys
    public static final String PROP_ENABLED = "rate.limit.enabled";
    public static final String PROP_REQUESTS_PER_SECOND = "rate.limit.requests.per.second";
    public static final String PROP_BURST_SIZE = "rate.limit.burst.size";
    public static final String PROP_GLOBAL_RPS = "rate.limit.global.rps";
    public static final String PROP_WRITE_RPS = "rate.limit.write.rps";
    
    // Default values
    private static final int DEFAULT_REQUESTS_PER_SECOND = 100;
    private static final int DEFAULT_BURST_SIZE = 200;
    private static final int DEFAULT_GLOBAL_RPS = 1000;
    private static final int DEFAULT_WRITE_RPS = 10;
    
    // HTTP headers
    private static final String HEADER_RATE_LIMIT = "X-RateLimit-Limit";
    private static final String HEADER_RATE_REMAINING = "X-RateLimit-Remaining";
    private static final String HEADER_RATE_RESET = "X-RateLimit-Reset";
    private static final String HEADER_RETRY_AFTER = "Retry-After";
    private static final String HEADER_WALLET_ADDRESS = "X-Wallet-Address";
    
    private final boolean enabled;
    private final int requestsPerSecond;
    private final int burstSize;
    private final int globalRps;
    private final int writeRps;
    
    // Token buckets per client (IP or wallet)
    private final Map<String, TokenBucket> clientBuckets = new ConcurrentHashMap<>();
    private final Map<String, TokenBucket> walletWriteBuckets = new ConcurrentHashMap<>();
    private final TokenBucket globalBucket;
    
    // Metrics
    private final AtomicLong totalRequests = new AtomicLong(0);
    private final AtomicLong throttledRequests = new AtomicLong(0);
    
    // Cleanup scheduler
    private final ScheduledExecutorService cleanupScheduler;
    
    /**
     * Create rate limiter with default configuration from system properties.
     */
    public RateLimiter() {
        this.enabled = Boolean.parseBoolean(System.getProperty(PROP_ENABLED, "true"));
        this.requestsPerSecond = Integer.getInteger(PROP_REQUESTS_PER_SECOND, DEFAULT_REQUESTS_PER_SECOND);
        this.burstSize = Integer.getInteger(PROP_BURST_SIZE, DEFAULT_BURST_SIZE);
        this.globalRps = Integer.getInteger(PROP_GLOBAL_RPS, DEFAULT_GLOBAL_RPS);
        this.writeRps = Integer.getInteger(PROP_WRITE_RPS, DEFAULT_WRITE_RPS);
        
        this.globalBucket = new TokenBucket(globalRps, globalRps * 2);
        
        // Schedule cleanup of stale buckets every minute
        this.cleanupScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "rate-limiter-cleanup");
            t.setDaemon(true);
            return t;
        });
        this.cleanupScheduler.scheduleAtFixedRate(this::cleanupStaleBuckets, 1, 1, TimeUnit.MINUTES);
        
        log.info("Rate limiter initialized: enabled={}, rps={}, burst={}, globalRps={}, writeRps={}",
            enabled, requestsPerSecond, burstSize, globalRps, writeRps);
    }
    
    /**
     * Create rate limiter with explicit configuration.
     */
    public RateLimiter(boolean enabled, int requestsPerSecond, int burstSize, int globalRps, int writeRps) {
        this.enabled = enabled;
        this.requestsPerSecond = requestsPerSecond;
        this.burstSize = burstSize;
        this.globalRps = globalRps;
        this.writeRps = writeRps;
        
        this.globalBucket = new TokenBucket(globalRps, globalRps * 2);
        
        this.cleanupScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "rate-limiter-cleanup");
            t.setDaemon(true);
            return t;
        });
        this.cleanupScheduler.scheduleAtFixedRate(this::cleanupStaleBuckets, 1, 1, TimeUnit.MINUTES);
    }
    
    /**
     * Check if a request should be allowed.
     * 
     * @param request The HTTP request
     * @param response The HTTP response (for setting headers)
     * @return true if request is allowed, false if rate limited
     */
    public boolean allowRequest(HttpServletRequest request, HttpServletResponse response) {
        if (!enabled) {
            return true;
        }
        
        totalRequests.incrementAndGet();
        
        String clientId = getClientId(request);
        String path = request.getRequestURI();
        boolean isWriteRequest = isWriteRequest(request);
        
        // Check global rate limit
        if (!globalBucket.tryConsume()) {
            throttledRequests.incrementAndGet();
            setRateLimitHeaders(response, globalBucket, "global");
            log.warn("Global rate limit exceeded");
            return false;
        }
        
        // Check per-client rate limit
        TokenBucket clientBucket = clientBuckets.computeIfAbsent(clientId, 
            k -> new TokenBucket(requestsPerSecond, burstSize));
        
        if (!clientBucket.tryConsume()) {
            throttledRequests.incrementAndGet();
            setRateLimitHeaders(response, clientBucket, clientId);
            log.warn("Rate limit exceeded for client: {}", clientId);
            return false;
        }
        
        // Check per-wallet write rate limit
        if (isWriteRequest) {
            String walletAddress = getWalletAddress(request);
            if (walletAddress != null) {
                TokenBucket walletBucket = walletWriteBuckets.computeIfAbsent(walletAddress,
                    k -> new TokenBucket(writeRps, writeRps * 2));
                
                if (!walletBucket.tryConsume()) {
                    throttledRequests.incrementAndGet();
                    setRateLimitHeaders(response, walletBucket, walletAddress);
                    log.warn("Write rate limit exceeded for wallet: {}", walletAddress);
                    return false;
                }
            }
        }
        
        // Set rate limit headers for successful requests
        setRateLimitHeaders(response, clientBucket, clientId);
        return true;
    }
    
    /**
     * Send rate limit exceeded response.
     */
    public void sendRateLimitResponse(HttpServletResponse response) throws IOException {
        response.setStatus(429); // Too Many Requests
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"rate_limit_exceeded\",\"message\":\"Too many requests. Please retry later.\"}");
    }
    
    /**
     * Get client identifier (IP address or forwarded IP).
     */
    private String getClientId(HttpServletRequest request) {
        // Check for forwarded IP (behind load balancer/proxy)
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isEmpty()) {
            // Take the first IP in the chain (original client)
            return forwardedFor.split(",")[0].trim();
        }
        
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isEmpty()) {
            return realIp;
        }
        
        return request.getRemoteAddr();
    }
    
    /**
     * Get wallet address from request.
     */
    private String getWalletAddress(HttpServletRequest request) {
        // Check header first
        String walletHeader = request.getHeader(HEADER_WALLET_ADDRESS);
        if (walletHeader != null && !walletHeader.isEmpty()) {
            return walletHeader.toLowerCase();
        }
        
        // Check query parameter
        String walletParam = request.getParameter("wallet");
        if (walletParam != null && !walletParam.isEmpty()) {
            return walletParam.toLowerCase();
        }
        
        return null;
    }
    
    /**
     * Check if request is a write operation.
     */
    private boolean isWriteRequest(HttpServletRequest request) {
        String method = request.getMethod();
        String path = request.getRequestURI();
        
        // POST/PUT/DELETE are write operations
        if ("POST".equals(method) || "PUT".equals(method) || "DELETE".equals(method)) {
            return true;
        }
        
        // Specific write endpoints
        return path.contains("/v1/write") || 
               path.contains("/v1/delete") || 
               path.contains("/v1/gc/propose");
    }
    
    /**
     * Set rate limit headers on response.
     */
    private void setRateLimitHeaders(HttpServletResponse response, TokenBucket bucket, String clientId) {
        response.setHeader(HEADER_RATE_LIMIT, String.valueOf(bucket.getCapacity()));
        response.setHeader(HEADER_RATE_REMAINING, String.valueOf(bucket.getAvailableTokens()));
        response.setHeader(HEADER_RATE_RESET, String.valueOf(System.currentTimeMillis() / 1000 + 1));
        
        if (bucket.getAvailableTokens() <= 0) {
            response.setHeader(HEADER_RETRY_AFTER, "1");
        }
    }
    
    /**
     * Clean up stale token buckets (not used in last 5 minutes).
     */
    private void cleanupStaleBuckets() {
        long staleThreshold = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(5);
        
        clientBuckets.entrySet().removeIf(entry -> entry.getValue().getLastAccess() < staleThreshold);
        walletWriteBuckets.entrySet().removeIf(entry -> entry.getValue().getLastAccess() < staleThreshold);
        
        log.debug("Cleaned up stale rate limit buckets. Active: {} clients, {} wallets",
            clientBuckets.size(), walletWriteBuckets.size());
    }
    
    /**
     * Get metrics for monitoring.
     */
    public RateLimitMetrics getMetrics() {
        return new RateLimitMetrics(
            totalRequests.get(),
            throttledRequests.get(),
            clientBuckets.size(),
            walletWriteBuckets.size()
        );
    }
    
    /**
     * Shutdown the rate limiter.
     */
    public void shutdown() {
        cleanupScheduler.shutdown();
        try {
            cleanupScheduler.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * Token bucket implementation for rate limiting.
     */
    private static class TokenBucket {
        private final int capacity;
        private final int refillRate; // tokens per second
        private final AtomicInteger tokens;
        private final AtomicLong lastRefill;
        private final AtomicLong lastAccess;
        
        TokenBucket(int refillRate, int capacity) {
            this.capacity = capacity;
            this.refillRate = refillRate;
            this.tokens = new AtomicInteger(capacity);
            this.lastRefill = new AtomicLong(System.currentTimeMillis());
            this.lastAccess = new AtomicLong(System.currentTimeMillis());
        }
        
        boolean tryConsume() {
            refill();
            lastAccess.set(System.currentTimeMillis());
            
            int current;
            do {
                current = tokens.get();
                if (current <= 0) {
                    return false;
                }
            } while (!tokens.compareAndSet(current, current - 1));
            
            return true;
        }
        
        private void refill() {
            long now = System.currentTimeMillis();
            long last = lastRefill.get();
            long elapsed = now - last;
            
            if (elapsed >= 1000) { // Refill every second
                int tokensToAdd = (int) (elapsed / 1000) * refillRate;
                if (tokensToAdd > 0 && lastRefill.compareAndSet(last, now)) {
                    int current = tokens.get();
                    int newTokens = Math.min(capacity, current + tokensToAdd);
                    tokens.set(newTokens);
                }
            }
        }
        
        int getAvailableTokens() {
            refill();
            return tokens.get();
        }
        
        int getCapacity() {
            return capacity;
        }
        
        long getLastAccess() {
            return lastAccess.get();
        }
    }
    
    /**
     * Rate limit metrics for monitoring.
     */
    public static class RateLimitMetrics {
        public final long totalRequests;
        public final long throttledRequests;
        public final int activeClients;
        public final int activeWallets;
        
        RateLimitMetrics(long totalRequests, long throttledRequests, int activeClients, int activeWallets) {
            this.totalRequests = totalRequests;
            this.throttledRequests = throttledRequests;
            this.activeClients = activeClients;
            this.activeWallets = activeWallets;
        }
        
        public double getThrottleRate() {
            return totalRequests > 0 ? (double) throttledRequests / totalRequests : 0;
        }
    }
}
