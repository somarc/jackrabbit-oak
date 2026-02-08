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
package org.apache.jackrabbit.oak.segment.consensus.eth;

import org.apache.jackrabbit.oak.segment.consensus.config.BlockchainConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Client for fetching Ethereum Beacon Chain epoch data.
 * 
 * <p><strong>MODE-AWARE</strong>: Behavior depends on blockchain mode:
 * <ul>
 *   <li><strong>MAINNET</strong>: Fetches real epochs from beaconcha.in mainnet API</li>
 *   <li><strong>SEPOLIA</strong>: Fetches real epochs from beaconcha.in Sepolia API</li>
 *   <li><strong>MOCK</strong>: Uses synthetic epochs for fast iteration without Ethereum</li>
 * </ul>
 * 
 * <p>SINGLE SOURCE OF TRUTH for Ethereum epoch data across oak-segment-consensus.
 * 
 * @see <a href="https://beaconcha.in/api/v1/docs">Beaconcha.in API Docs</a>
 */
public class BeaconChainClient {
    private static final Logger log = LoggerFactory.getLogger(BeaconChainClient.class);
    
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // API ENDPOINTS (beaconcha.in provides both mainnet and Sepolia)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    private static final String MAINNET_API = "https://beaconcha.in/api/v1";
    private static final String SEPOLIA_API = "https://sepolia.beaconcha.in/api/v1";
    
    // Epoch duration is 6.4 minutes on all networks
    private static final long EPOCH_DURATION_MS = 384000L; // 32 slots × 12 seconds
    
    // Mock mode: configurable epoch duration (default 300 seconds)
    private static final String ENV_MOCK_EPOCH_DURATION_SECONDS = "OAK_MOCK_EPOCH_DURATION_SECONDS";
    private static final String PROP_MOCK_EPOCH_DURATION_SECONDS = "oak.mock.epoch.duration.seconds";
    private static final long DEFAULT_MOCK_EPOCH_DURATION_MS = 300_000L;
    
    // Poll intervals
    private static final long REAL_POLL_INTERVAL_MS = 60_000L;  // 1 minute for real chains
    private static final long MOCK_POLL_INTERVAL_MS = 5_000L;   // 5 seconds for mock
    
    // Network mode
    private final BlockchainConfig.Mode networkMode;
    private final String apiBaseUrl;
    
    // Cached epoch state (single source of truth)
    private volatile long cachedFinalizedEpoch = -1;
    private volatile long cachedCurrentEpoch = -1;
    private volatile long lastUpdateTime = 0;
    private volatile String lastError = null;
    
    // Mock mode state
    private final long mockStartTime;
    private final long mockEpochDurationMs;
    private volatile long mockEpochOffset = 0; // Can be set via API for testing
    
    // Background polling thread
    private java.util.concurrent.ScheduledExecutorService pollingExecutor;
    
    // Track last logged epoch to avoid spamming logs
    private long lastLoggedEpoch = -1;
    
    // API call statistics
    private volatile long apiCallCount = 0;
    private volatile long apiErrorCount = 0;
    
    /**
     * Create a new Beacon Chain client (mode-aware).
     * 
     * @param beaconApiUrl Ignored - we use mode-specific URLs now
     */
    public BeaconChainClient(String beaconApiUrl) {
        BlockchainConfig config = BlockchainConfig.getInstance();
        this.networkMode = config.getMode();
        this.mockStartTime = System.currentTimeMillis();
        this.mockEpochDurationMs = resolveMockEpochDurationMs();
        
        // Select API URL based on mode
        switch (networkMode) {
            case MAINNET:
                this.apiBaseUrl = MAINNET_API;
                break;
            case SEPOLIA:
                this.apiBaseUrl = SEPOLIA_API;
                break;
            case MOCK:
            default:
                this.apiBaseUrl = null; // Mock mode doesn't use API
                break;
        }
        
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        log.info("🔗 Beacon Chain Client Initialized");
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        log.info("   Mode: {}", networkMode);
        
        if (networkMode == BlockchainConfig.Mode.MOCK) {
            log.info("   📝 MOCK MODE - Synthetic epochs ({}s/epoch)", mockEpochDurationMs / 1000);
            log.info("   📝 Use /api/mock/set-epoch to control epoch");
        } else {
            log.info("   API: {}", apiBaseUrl);
            log.info("   📡 REAL API - Fetching from beaconcha.in");
        }
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        
        // Initialize immediately
        updateCachedEpochs();
    }
    
    /**
     * Start background polling thread to keep epoch data fresh.
     */
    public void startBackgroundPolling() {
        if (pollingExecutor != null) {
            log.warn("Background polling already started - skipping duplicate initialization");
            return;
        }
        
        long pollInterval = (networkMode == BlockchainConfig.Mode.MOCK) 
            ? MOCK_POLL_INTERVAL_MS 
            : REAL_POLL_INTERVAL_MS;
        
        pollingExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "beacon-chain-epoch-poller");
            t.setDaemon(true);
            return t;
        });
        
        pollingExecutor.scheduleAtFixedRate(() -> {
            try {
                updateCachedEpochs();
            } catch (Exception e) {
                log.error("Epoch update failed: {}", e.getMessage());
                lastError = e.getMessage();
                apiErrorCount++;
                // Don't crash in mock mode - just log and continue
                if (networkMode != BlockchainConfig.Mode.MOCK) {
                    log.error("Real mode epoch failure - this may impact finality tracking!");
                }
            }
        }, pollInterval, pollInterval, java.util.concurrent.TimeUnit.MILLISECONDS);
        
        log.info("✅ Beacon Chain epoch polling started");
        log.info("   - Mode: {}", networkMode);
        log.info("   - Poll interval: {}s", pollInterval / 1000);
        log.info("   - Initial finalized epoch: {}", cachedFinalizedEpoch);
        log.info("   - Initial current epoch: {}", cachedCurrentEpoch);
    }
    
    /**
     * Stop background polling thread (cleanup).
     */
    public void stopBackgroundPolling() {
        if (pollingExecutor != null) {
            pollingExecutor.shutdown();
            pollingExecutor = null;
            log.info("Beacon Chain epoch polling stopped");
        }
    }
    
    /**
     * Update cached epoch values based on mode.
     */
    private void updateCachedEpochs() {
        long startTime = System.nanoTime();
        
        if (networkMode == BlockchainConfig.Mode.MOCK) {
            updateMockEpochs();
        } else {
            updateRealEpochs();
        }
        
        long durationMs = (System.nanoTime() - startTime) / 1_000_000;
        log.debug("Epoch update completed in {}ms: finalized={}, current={}", 
            durationMs, cachedFinalizedEpoch, cachedCurrentEpoch);
    }
    
    /**
     * Mock mode: Calculate synthetic epochs for testing.
     * 
     * <p>Epochs increment every 30 seconds, starting from a reasonable base.
     * Use setMockEpoch() to control the epoch for specific test scenarios.
     */
    private void updateMockEpochs() {
        long elapsed = System.currentTimeMillis() - mockStartTime;
        long baseEpoch = elapsed / mockEpochDurationMs;
        
        // Start at epoch 1000 so it looks realistic, plus any manual offset
        long currentEpoch = 1000 + baseEpoch + mockEpochOffset;
        long finalizedEpoch = currentEpoch - 2;
        
        boolean epochAdvanced = (finalizedEpoch != cachedFinalizedEpoch);
        
        if (epochAdvanced) {
            log.info("🧪 MOCK epoch advanced: {} -> {} (current: {})", 
                cachedFinalizedEpoch, finalizedEpoch, currentEpoch);
        }
        
        cachedFinalizedEpoch = finalizedEpoch;
        cachedCurrentEpoch = currentEpoch;
        lastUpdateTime = System.currentTimeMillis();
        lastError = null;
    }
    
    /**
     * Real mode: Fetch epochs from beaconcha.in API.
     */
    private void updateRealEpochs() {
        try {
            // Fetch latest finalized epoch from API
            String endpoint = "/epoch/finalized";
            String response = httpGet(apiBaseUrl + endpoint);
            apiCallCount++;
            
            // Parse response: {"status":"OK","data":{"epoch":12345,...}}
            long finalizedEpoch = parseEpochFromResponse(response);
            
            if (finalizedEpoch < 0) {
                throw new RuntimeException("Failed to parse epoch from API response");
            }
            
            // Current epoch is typically finalized + 2 (can verify with /epoch/latest)
            long currentEpoch = finalizedEpoch + 2;
            
            // Validate epochs
            if (cachedFinalizedEpoch > 0 && finalizedEpoch < cachedFinalizedEpoch) {
                log.warn("⚠️  Epoch went backwards? {} -> {} (API glitch?)", 
                    cachedFinalizedEpoch, finalizedEpoch);
                // Don't crash - could be API issue, just log and continue
            }
            
            boolean epochAdvanced = (finalizedEpoch != cachedFinalizedEpoch);
            
            if (epochAdvanced) {
                log.info("🔗 {} epoch advanced: {} -> {} (current: {})", 
                    networkMode, cachedFinalizedEpoch, finalizedEpoch, currentEpoch);
            }
            
            cachedFinalizedEpoch = finalizedEpoch;
            cachedCurrentEpoch = currentEpoch;
            lastUpdateTime = System.currentTimeMillis();
            lastError = null;
            
        } catch (Exception e) {
            apiErrorCount++;
            lastError = e.getMessage();
            log.error("Failed to fetch epoch from {}: {}", apiBaseUrl, e.getMessage());
            
            // If we have no cached data, try fallback calculation
            if (cachedFinalizedEpoch < 0) {
                log.warn("Using calculated epoch as fallback (no cached data)");
                fallbackCalculateEpochs();
            }
        }
    }
    
    /**
     * Fallback: Calculate epochs from genesis time (if API fails).
     */
    private void fallbackCalculateEpochs() {
        long genesisTime = (networkMode == BlockchainConfig.Mode.MAINNET) 
            ? 1606824023000L  // Mainnet: Dec 1, 2020
            : 1655733600000L; // Sepolia: June 20, 2022
        
        long msSinceGenesis = System.currentTimeMillis() - genesisTime;
        long currentEpoch = msSinceGenesis / EPOCH_DURATION_MS;
        long finalizedEpoch = currentEpoch - 2;
        
        cachedFinalizedEpoch = finalizedEpoch;
        cachedCurrentEpoch = currentEpoch;
        lastUpdateTime = System.currentTimeMillis();
        
        log.warn("⚠️  Using CALCULATED epoch (API failed): finalized={}, current={}", 
            finalizedEpoch, currentEpoch);
    }
    
    /**
     * Parse epoch number from beaconcha.in API response.
     * 
     * Expected format: {"status":"OK","data":{"epoch":12345,...}}
     */
    private long parseEpochFromResponse(String json) {
        try {
            // Simple parsing - find "epoch": followed by number
            int epochIdx = json.indexOf("\"epoch\"");
            if (epochIdx < 0) {
                // Try alternate format: {"status":"OK","data":12345}
                int dataIdx = json.indexOf("\"data\"");
                if (dataIdx > 0) {
                    int colonIdx = json.indexOf(":", dataIdx);
                    int endIdx = json.indexOf(",", colonIdx);
                    if (endIdx < 0) endIdx = json.indexOf("}", colonIdx);
                    if (colonIdx > 0 && endIdx > colonIdx) {
                        String numStr = json.substring(colonIdx + 1, endIdx).trim();
                        return Long.parseLong(numStr);
                    }
                }
                return -1;
            }
            
            int colonIdx = json.indexOf(":", epochIdx);
            int endIdx = json.indexOf(",", colonIdx);
            if (endIdx < 0) endIdx = json.indexOf("}", colonIdx);
            
            if (colonIdx > 0 && endIdx > colonIdx) {
                String numStr = json.substring(colonIdx + 1, endIdx).trim();
                return Long.parseLong(numStr);
            }
        } catch (Exception e) {
            log.error("Failed to parse epoch from JSON: {}", e.getMessage());
        }
        return -1;
    }
    
    /**
     * Make HTTP GET request to Beacon Chain API.
     */
    private String httpGet(String endpoint) throws Exception {
        URL url = new URL(endpoint);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("User-Agent", "OakSegmentConsensus/1.0");
        
        int responseCode = conn.getResponseCode();
        if (responseCode != 200) {
            throw new Exception("HTTP error: " + responseCode + " from " + endpoint);
        }
        
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            return response.toString();
        }
    }
    
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // PUBLIC GETTERS (SINGLE SOURCE OF TRUTH)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    
    /**
     * Get cached finalized epoch (fast - no API call).
     */
    public long getCachedFinalizedEpoch() {
        return cachedFinalizedEpoch;
    }
    
    /**
     * Get cached current epoch (fast - no API call).
     */
    public long getCachedCurrentEpoch() {
        return cachedCurrentEpoch;
    }
    
    /**
     * Get network mode.
     */
    public BlockchainConfig.Mode getNetworkMode() {
        return networkMode;
    }
    
    /**
     * Get time since last epoch update.
     */
    public long getMillisSinceLastUpdate() {
        return System.currentTimeMillis() - lastUpdateTime;
    }
    
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // MOCK MODE CONTROL (for testing)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    
    /**
     * Set mock epoch offset (only works in MOCK mode).
     * 
     * <p>Use this to simulate specific epoch scenarios for testing.
     * 
     * @param offset Number of epochs to add to the base mock epoch
     * @return true if set successfully (mock mode), false otherwise
     */
    public boolean setMockEpochOffset(long offset) {
        if (networkMode != BlockchainConfig.Mode.MOCK) {
            log.warn("Cannot set mock epoch in {} mode", networkMode);
            return false;
        }
        
        log.info("🧪 Setting mock epoch offset: {} (was: {})", offset, mockEpochOffset);
        mockEpochOffset = offset;
        updateMockEpochs(); // Update immediately
        return true;
    }
    
    /**
     * Advance mock epoch by N epochs (only works in MOCK mode).
     * 
     * @param epochs Number of epochs to advance
     * @return true if advanced successfully
     */
    public boolean advanceMockEpoch(int epochs) {
        if (networkMode != BlockchainConfig.Mode.MOCK) {
            log.warn("Cannot advance mock epoch in {} mode", networkMode);
            return false;
        }
        
        log.info("🧪 Advancing mock epoch by {}", epochs);
        mockEpochOffset += epochs;
        updateMockEpochs();
        return true;
    }
    
    /**
     * Get current mock epoch offset.
     */
    public long getMockEpochOffset() {
        return mockEpochOffset;
    }
    
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // HEALTH & MONITORING
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    
    /**
     * Check if epoch data is fresh.
     */
    public boolean isEpochDataFresh() {
        long maxStale = (networkMode == BlockchainConfig.Mode.MOCK) 
            ? 60_000L   // 1 minute for mock
            : 300_000L; // 5 minutes for real
        return (System.currentTimeMillis() - lastUpdateTime) < maxStale;
    }
    
    /**
     * Get health status for monitoring.
     */
    public java.util.Map<String, Object> getHealthStatus() {
        java.util.Map<String, Object> health = new java.util.HashMap<>();
        
        health.put("mode", networkMode.toString());
        health.put("apiUrl", apiBaseUrl);
        health.put("fresh", isEpochDataFresh());
        health.put("currentEpoch", cachedCurrentEpoch);
        health.put("finalizedEpoch", cachedFinalizedEpoch);
        health.put("lastUpdateTime", lastUpdateTime);
        health.put("timeSinceUpdateMs", System.currentTimeMillis() - lastUpdateTime);
        health.put("apiCallCount", apiCallCount);
        health.put("apiErrorCount", apiErrorCount);
        health.put("lastError", lastError);
        
        if (networkMode == BlockchainConfig.Mode.MOCK) {
            health.put("mockEpochOffset", mockEpochOffset);
            health.put("mockEpochDurationMs", mockEpochDurationMs);
        }
        
        return health;
    }

    private long resolveMockEpochDurationMs() {
        long defaultSeconds = DEFAULT_MOCK_EPOCH_DURATION_MS / 1000L;
        String envValue = System.getenv(ENV_MOCK_EPOCH_DURATION_SECONDS);
        String propValue = System.getProperty(PROP_MOCK_EPOCH_DURATION_SECONDS);
        String raw = (envValue != null && !envValue.trim().isEmpty()) ? envValue : propValue;
        if (raw == null || raw.trim().isEmpty()) {
            return DEFAULT_MOCK_EPOCH_DURATION_MS;
        }
        try {
            long seconds = Long.parseLong(raw.trim());
            if (seconds <= 0) {
                log.warn("Invalid mock epoch duration seconds ({}={}) - using default {}s",
                    ENV_MOCK_EPOCH_DURATION_SECONDS, raw, defaultSeconds);
                return DEFAULT_MOCK_EPOCH_DURATION_MS;
            }
            return seconds * 1000L;
        } catch (NumberFormatException e) {
            log.warn("Failed to parse mock epoch duration seconds (raw='{}') - using default {}s",
                raw, defaultSeconds);
            return DEFAULT_MOCK_EPOCH_DURATION_MS;
        }
    }
    
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // LEGACY COMPATIBILITY
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    
    /**
     * Get latest finalized epoch (legacy compatibility).
     * 
     * @deprecated Use getCachedFinalizedEpoch() instead
     */
    @Deprecated
    public EpochData getLatestFinalizedEpoch() throws Exception {
        return getEpochDetails(cachedFinalizedEpoch);
    }
    
    /**
     * Get epoch details (for compatibility).
     */
    public EpochData getEpochDetails(long epochNumber) {
        EpochData data = new EpochData();
        data.epochNumber = epochNumber;
        data.timestamp = System.currentTimeMillis();
        data.finalized = (epochNumber <= cachedFinalizedEpoch);
        data.epochsBehindCurrent = (int)(cachedCurrentEpoch - epochNumber);
        
        // Mock data for other fields
        data.blocksProposed = 32;
        data.blocksSkipped = 0;
        data.attestations = 150;
        data.totalValidators = 2127176L;
        data.activeValidators = 2126153L;
        data.slashings = 0;
        data.deposits = 0;
        data.voluntaryExits = 0;
        
        return data;
    }
    
    public long getLastUpdateTime() {
        return lastUpdateTime;
    }
    
    public void checkEpochFreshness() {
        if (!isEpochDataFresh()) {
            throw new IllegalStateException("Epoch data is stale!");
        }
    }
}
