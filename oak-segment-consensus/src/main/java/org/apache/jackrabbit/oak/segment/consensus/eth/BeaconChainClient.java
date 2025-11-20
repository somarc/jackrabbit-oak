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
 * <p>This client polls the Beacon Chain API to detect new finalized epochs
 * and fetch their metadata. For the POC, we use a simplified approach with
 * mock data for some fields, but the epoch numbers and timestamps are real.
 * 
 * <p>In production, this would integrate with a full Beacon Chain node or
 * use multiple API endpoints to fetch complete epoch details.
 * 
 * @see <a href="https://ethereum.github.io/beacon-APIs/">Beacon Chain API Spec</a>
 */
public class BeaconChainClient {
    private static final Logger log = LoggerFactory.getLogger(BeaconChainClient.class);
    
    private static final long BEACON_GENESIS_TIME = 1606824023000L; // Dec 1, 2020 12:00:23 PM UTC
    private static final long EPOCH_DURATION_MS = 384000L; // 6.4 minutes (32 slots × 12 seconds)
    
    private final String beaconApiUrl;
    
    // Track last logged epoch to avoid spamming logs
    private long lastLoggedEpoch = -1;
    
    /**
     * Create a new Beacon Chain client.
     * 
     * @param beaconApiUrl Base URL for Beacon Chain API (e.g., "https://beaconcha.in")
     */
    public BeaconChainClient(String beaconApiUrl) {
        this.beaconApiUrl = beaconApiUrl;
    }
    
    /**
     * Fetch the latest finalized epoch from Beacon Chain.
     * 
     * <p>For POC, we calculate the current epoch based on time elapsed since genesis.
     * In production, this would query the actual Beacon Chain API.
     * 
     * @return Latest finalized epoch data
     * @throws Exception if unable to fetch epoch data
     */
    public EpochData getLatestFinalizedEpoch() throws Exception {
        // Calculate current epoch based on time since genesis
        long currentTimeMs = System.currentTimeMillis();
        long msSinceGenesis = currentTimeMs - BEACON_GENESIS_TIME;
        long currentEpoch = msSinceGenesis / EPOCH_DURATION_MS;
        
        // Finalized epoch is typically 2-3 epochs behind current
        // (waiting for 2/3 validator attestations)
        long finalizedEpoch = currentEpoch - 2;
        
        // Only log at DEBUG level to avoid spam (gets called every few seconds)
        log.debug("⛓️  Calculating finalized epoch: {} (current: {})", 
                 finalizedEpoch, currentEpoch);
        
        return getEpochDetails(finalizedEpoch);
    }
    
    /**
     * Fetch detailed epoch data for a specific epoch number.
     * 
     * <p>For POC, we use calculated/mock data. In production, this would
     * query actual Beacon Chain endpoints like:
     * - /eth/v1/beacon/blocks/{slot} for block data
     * - /eth/v1/beacon/states/{state_id}/validators for validator data
     * - /eth/v1/beacon/states/{state_id}/finality_checkpoints for finality
     * 
     * @param epochNumber Epoch number to fetch
     * @return Epoch data with metadata
     */
    public EpochData getEpochDetails(long epochNumber) {
        // Only log at INFO level when we see a NEW epoch
        // This reduces log spam from ~3 lines every 1-5 seconds to once per epoch (~6.4 minutes)
        boolean isNewEpoch = (epochNumber != lastLoggedEpoch);
        
        if (isNewEpoch) {
            log.info("📊 Fetching NEW Ethereum epoch: {}", epochNumber);
        } else {
            log.debug("📊 Re-fetching Ethereum epoch: {} (no change)", epochNumber);
        }
        
        // Calculate epoch timestamp
        long timestamp = BEACON_GENESIS_TIME + (epochNumber * EPOCH_DURATION_MS);
        
        // Calculate current epoch to determine finality status
        long currentTimeMs = System.currentTimeMillis();
        long msSinceGenesis = currentTimeMs - BEACON_GENESIS_TIME;
        long currentEpoch = msSinceGenesis / EPOCH_DURATION_MS;
        
        EpochData data = new EpochData();
        data.epochNumber = epochNumber;
        data.timestamp = timestamp;
        
        // Finality determination: epochs are finalized after 2 epoch delay
        // This ensures 2/3 validator consensus has been achieved
        data.epochsBehindCurrent = (int)(currentEpoch - epochNumber);
        data.finalized = data.epochsBehindCurrent >= 2;
        data.finalizedAt = data.finalized ? timestamp + (2 * EPOCH_DURATION_MS) : 0;
        
        // Generate mock block root (in production: fetch from Beacon Chain API)
        data.blockRoot = String.format("0x%064x", epochNumber);
        
        // For POC: Use realistic mock data
        // In production: Fetch from actual Beacon Chain API
        data.blocksProposed = 32; // Always 32 slots per epoch
        data.blocksSkipped = (int) (Math.random() * 3); // 0-2 skipped blocks (realistic)
        data.attestations = 150 + (int) (Math.random() * 50); // ~150-200 attestations
        
        // Current Ethereum validator counts (as of Nov 2025)
        // Source: https://beaconscan.com
        data.totalValidators = 2127176L;
        data.activeValidators = 2126153L;
        
        // Slashings and exits are rare
        data.slashings = (Math.random() < 0.01) ? 1 : 0; // 1% chance
        data.deposits = (int) (Math.random() * 5); // 0-4 new deposits
        data.voluntaryExits = (Math.random() < 0.05) ? 1 : 0; // 5% chance
        
        // Only log at INFO level for NEW epochs
        if (isNewEpoch) {
            log.info("✅ Fetched epoch {} (finalized: {}, blocks: {}/{}, attestations: {})", 
                     epochNumber, 
                     data.finalized, 
                     data.blocksProposed - data.blocksSkipped, 
                     data.blocksProposed,
                     data.attestations);
            lastLoggedEpoch = epochNumber;
        } else {
            log.debug("✅ Re-fetched epoch {} (finalized: {}, blocks: {}/{}, attestations: {})", 
                     epochNumber, 
                     data.finalized, 
                     data.blocksProposed - data.blocksSkipped, 
                     data.blocksProposed,
                     data.attestations);
        }
        
        return data;
    }
    
    /**
     * Utility method to make HTTP GET request (for future API integration).
     * 
     * @param endpoint API endpoint URL
     * @return Response body as string
     * @throws Exception if request fails
     */
    @SuppressWarnings("unused")
    private String httpGet(String endpoint) throws Exception {
        URL url = new URL(beaconApiUrl + endpoint);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        conn.setRequestProperty("Accept", "application/json");
        
        int responseCode = conn.getResponseCode();
        if (responseCode != 200) {
            throw new Exception("HTTP error: " + responseCode);
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
}

