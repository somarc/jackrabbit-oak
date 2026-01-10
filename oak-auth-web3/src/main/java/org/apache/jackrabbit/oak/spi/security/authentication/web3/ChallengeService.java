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
package org.apache.jackrabbit.oak.spi.security.authentication.web3;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Server-side challenge generation and validation service for WebAuthn/biometric authentication.
 * 
 * <p>This service provides cryptographically secure challenge generation and one-time-use
 * validation to prevent replay attacks in the Web3 biometric authentication flow.</p>
 * 
 * <h2>Challenge Lifecycle</h2>
 * <pre>
 * 1. Client requests challenge → {@link #generateChallenge(String)}
 * 2. Server stores challenge with TTL
 * 3. Client signs challenge with biometric
 * 4. Client submits signed challenge
 * 5. Server validates challenge → {@link #validateAndConsumeChallenge(String, byte[])}
 * 6. Challenge is consumed (one-time use)
 * </pre>
 * 
 * <h2>Security Properties</h2>
 * <ul>
 *   <li><strong>Cryptographic Randomness</strong>: Uses {@link SecureRandom} for challenge generation</li>
 *   <li><strong>One-Time Use</strong>: Challenges are consumed on validation (prevents replay)</li>
 *   <li><strong>Time-Limited</strong>: Challenges expire after configurable TTL (default 5 minutes)</li>
 *   <li><strong>Client Binding</strong>: Challenges are bound to client identifier (IP, session, etc.)</li>
 * </ul>
 * 
 * <h2>Usage Example</h2>
 * <pre>
 * ChallengeService challengeService = ChallengeService.getInstance();
 * 
 * // 1. Generate challenge for client
 * Challenge challenge = challengeService.generateChallenge(clientId);
 * // Send challenge.getBytes() to client
 * 
 * // 2. Later, validate signed challenge
 * boolean valid = challengeService.validateAndConsumeChallenge(clientId, challengeBytes);
 * if (valid) {
 *     // Proceed with signature verification
 * }
 * </pre>
 * 
 * <h2>Thread Safety</h2>
 * <p>This service is thread-safe and can be used from multiple threads concurrently.</p>
 * 
 * @see Web3BiometricLoginModule
 * @see Web3BiometricCredentials
 */
public class ChallengeService {
    
    private static final Logger log = LoggerFactory.getLogger(ChallengeService.class);
    
    /** Default challenge size in bytes (32 bytes = 256 bits) */
    private static final int DEFAULT_CHALLENGE_SIZE = 32;
    
    /** Default challenge TTL in milliseconds (5 minutes) */
    private static final long DEFAULT_CHALLENGE_TTL_MS = 5 * 60 * 1000;
    
    /** Cleanup interval for expired challenges (1 minute) */
    private static final long CLEANUP_INTERVAL_MS = 60 * 1000;
    
    /** Singleton instance */
    private static volatile ChallengeService instance;
    
    /** Secure random generator */
    private final SecureRandom secureRandom;
    
    /** Challenge storage: clientId -> Challenge */
    private final Map<String, Challenge> challenges;
    
    /** Challenge TTL in milliseconds */
    private final long challengeTtlMs;
    
    /** Challenge size in bytes */
    private final int challengeSize;
    
    /** Cleanup executor */
    private final ScheduledExecutorService cleanupExecutor;
    
    /**
     * Represents a stored challenge with metadata.
     */
    public static class Challenge {
        private final byte[] bytes;
        private final String clientId;
        private final long createdAt;
        private final long expiresAt;
        private volatile boolean consumed;
        
        Challenge(byte[] bytes, String clientId, long ttlMs) {
            this.bytes = bytes.clone();
            this.clientId = clientId;
            this.createdAt = System.currentTimeMillis();
            this.expiresAt = this.createdAt + ttlMs;
            this.consumed = false;
        }
        
        /**
         * Returns a copy of the challenge bytes.
         */
        @NotNull
        public byte[] getBytes() {
            return bytes.clone();
        }
        
        /**
         * Returns the challenge as a Base64-encoded string.
         */
        @NotNull
        public String getBase64() {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        }
        
        /**
         * Returns the client identifier this challenge is bound to.
         */
        @NotNull
        public String getClientId() {
            return clientId;
        }
        
        /**
         * Returns the creation timestamp.
         */
        public long getCreatedAt() {
            return createdAt;
        }
        
        /**
         * Returns the expiration timestamp.
         */
        public long getExpiresAt() {
            return expiresAt;
        }
        
        /**
         * Checks if the challenge has expired.
         */
        public boolean isExpired() {
            return System.currentTimeMillis() > expiresAt;
        }
        
        /**
         * Checks if the challenge has been consumed.
         */
        public boolean isConsumed() {
            return consumed;
        }
        
        /**
         * Marks the challenge as consumed (one-time use).
         */
        void consume() {
            this.consumed = true;
        }
    }
    
    /**
     * Creates a new ChallengeService with default settings.
     */
    public ChallengeService() {
        this(DEFAULT_CHALLENGE_SIZE, DEFAULT_CHALLENGE_TTL_MS);
    }
    
    /**
     * Creates a new ChallengeService with custom settings.
     * 
     * @param challengeSize challenge size in bytes
     * @param challengeTtlMs challenge time-to-live in milliseconds
     */
    public ChallengeService(int challengeSize, long challengeTtlMs) {
        this.secureRandom = new SecureRandom();
        this.challenges = new ConcurrentHashMap<>();
        this.challengeSize = challengeSize;
        this.challengeTtlMs = challengeTtlMs;
        
        // Start cleanup task
        this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ChallengeService-Cleanup");
            t.setDaemon(true);
            return t;
        });
        this.cleanupExecutor.scheduleAtFixedRate(
            this::cleanupExpiredChallenges,
            CLEANUP_INTERVAL_MS,
            CLEANUP_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        );
        
        log.info("✅ ChallengeService initialized: size={}bytes, ttl={}ms", challengeSize, challengeTtlMs);
    }
    
    /**
     * Returns the singleton instance of ChallengeService.
     * 
     * @return the singleton instance
     */
    @NotNull
    public static synchronized ChallengeService getInstance() {
        if (instance == null) {
            instance = new ChallengeService();
        }
        return instance;
    }
    
    /**
     * Generates a new cryptographically secure challenge for the given client.
     * 
     * <p>If a previous challenge exists for this client, it is replaced.</p>
     * 
     * @param clientId client identifier (IP address, session ID, etc.)
     * @return the generated challenge
     */
    @NotNull
    public Challenge generateChallenge(@NotNull String clientId) {
        // Generate random bytes
        byte[] bytes = new byte[challengeSize];
        secureRandom.nextBytes(bytes);
        
        // Create challenge
        Challenge challenge = new Challenge(bytes, clientId, challengeTtlMs);
        
        // Store (replaces any existing challenge for this client)
        challenges.put(clientId, challenge);
        
        log.debug("🎲 Generated challenge for client {}: {} (expires in {}ms)", 
            clientId, challenge.getBase64().substring(0, 8) + "...", challengeTtlMs);
        
        return challenge;
    }
    
    /**
     * Validates and consumes a challenge.
     * 
     * <p>This method performs the following checks:</p>
     * <ol>
     *   <li>Challenge exists for the client</li>
     *   <li>Challenge has not expired</li>
     *   <li>Challenge has not been consumed</li>
     *   <li>Challenge bytes match</li>
     * </ol>
     * 
     * <p>If validation succeeds, the challenge is consumed (one-time use).</p>
     * 
     * @param clientId client identifier
     * @param challengeBytes the challenge bytes to validate
     * @return true if the challenge is valid and was consumed, false otherwise
     */
    public boolean validateAndConsumeChallenge(@NotNull String clientId, @NotNull byte[] challengeBytes) {
        Challenge stored = challenges.get(clientId);
        
        if (stored == null) {
            log.warn("⚠️  No challenge found for client: {}", clientId);
            return false;
        }
        
        if (stored.isExpired()) {
            log.warn("⚠️  Challenge expired for client: {} (expired {}ms ago)", 
                clientId, System.currentTimeMillis() - stored.getExpiresAt());
            challenges.remove(clientId);
            return false;
        }
        
        if (stored.isConsumed()) {
            log.warn("⚠️  Challenge already consumed for client: {} (replay attempt?)", clientId);
            return false;
        }
        
        // Compare challenge bytes (constant-time comparison to prevent timing attacks)
        if (!constantTimeEquals(stored.bytes, challengeBytes)) {
            log.warn("⚠️  Challenge mismatch for client: {}", clientId);
            return false;
        }
        
        // Consume the challenge (one-time use)
        stored.consume();
        challenges.remove(clientId);
        
        log.debug("✅ Challenge validated and consumed for client: {}", clientId);
        return true;
    }
    
    /**
     * Gets the current challenge for a client without consuming it.
     * 
     * @param clientId client identifier
     * @return the challenge, or null if none exists or it's expired
     */
    @Nullable
    public Challenge getChallenge(@NotNull String clientId) {
        Challenge challenge = challenges.get(clientId);
        
        if (challenge == null) {
            return null;
        }
        
        if (challenge.isExpired() || challenge.isConsumed()) {
            challenges.remove(clientId);
            return null;
        }
        
        return challenge;
    }
    
    /**
     * Revokes a challenge for a client.
     * 
     * @param clientId client identifier
     * @return true if a challenge was revoked, false if none existed
     */
    public boolean revokeChallenge(@NotNull String clientId) {
        Challenge removed = challenges.remove(clientId);
        if (removed != null) {
            log.debug("🗑️  Revoked challenge for client: {}", clientId);
            return true;
        }
        return false;
    }
    
    /**
     * Returns the number of active (non-expired, non-consumed) challenges.
     */
    public int getActiveChallengeCount() {
        return (int) challenges.values().stream()
            .filter(c -> !c.isExpired() && !c.isConsumed())
            .count();
    }
    
    /**
     * Shuts down the challenge service and releases resources.
     */
    public void shutdown() {
        cleanupExecutor.shutdown();
        try {
            if (!cleanupExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                cleanupExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            cleanupExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        challenges.clear();
        log.info("✅ ChallengeService shutdown complete");
    }
    
    /**
     * Cleans up expired and consumed challenges.
     */
    private void cleanupExpiredChallenges() {
        int removed = 0;
        for (Map.Entry<String, Challenge> entry : challenges.entrySet()) {
            Challenge challenge = entry.getValue();
            if (challenge.isExpired() || challenge.isConsumed()) {
                challenges.remove(entry.getKey());
                removed++;
            }
        }
        if (removed > 0) {
            log.debug("🧹 Cleaned up {} expired/consumed challenges", removed);
        }
    }
    
    /**
     * Constant-time byte array comparison to prevent timing attacks.
     */
    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) {
            return false;
        }
        
        int result = 0;
        for (int i = 0; i < a.length; i++) {
            result |= a[i] ^ b[i];
        }
        return result == 0;
    }
}
