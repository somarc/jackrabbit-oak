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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.jackrabbit.oak.segment.consensus.config.BlockchainConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Caches and polls Ethereum Beacon Chain epoch data.
 *
 * <p>Mock and local modes derive epochs from the local clock; Sepolia and mainnet poll beaconcha.in
 * every minute. Reads use the cached values, so a failing poll only keeps the last (or calculated) epoch.
 */
public class BeaconChainClient {

    private static final Logger log = LoggerFactory.getLogger(BeaconChainClient.class);

    private static final long EPOCH_DURATION_MS = 384_000L; // 32 slots × 12 s
    private static final long POLL_INTERVAL_MS  = 60_000L;  // 1 minute

    private final BlockchainConfig.Mode networkMode;
    private final BeaconChainProvider provider;

    // Cached epoch state
    private volatile long cachedFinalizedEpoch = -1;
    private volatile long cachedCurrentEpoch   = -1;
    private volatile long lastUpdateTime       = 0L;
    private volatile String lastError          = null;

    private volatile ScheduledExecutorService pollingExecutor;

    // ── constructors ─────────────────────────────────────────────────────────

    /** Production constructor. Mock and local modes never perform Beacon HTTP. */
    public BeaconChainClient() {
        this(BlockchainConfig.getInstance().getMode(), HttpFetcher::httpGet);
    }

    /** Package-private: used by tests to inject a mock {@link HttpFetcher}. */
    BeaconChainClient(BlockchainConfig.Mode mode, HttpFetcher fetcher) {
        this.networkMode = mode;
        this.provider = mode.usesLocalClockEpochs()
            ? new ClockBeaconChainProvider()
            : new BeaconchainDotInProvider(modeToApiUrl(mode), fetcher);
        log.info("Beacon Chain Client: mode={}, provider={}", mode, provider.name());
        updateCachedEpochs();
    }

    // ── polling lifecycle ─────────────────────────────────────────────────────

    public void startBackgroundPolling() {
        if (pollingExecutor != null) {
            log.warn("Background polling already started");
            return;
        }
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
            }
        }, POLL_INTERVAL_MS, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);

        log.info("Epoch refresh scheduled (interval={}s, provider={}, externalNetworkPolling={})",
            POLL_INTERVAL_MS / 1000, provider.name(), !networkMode.usesLocalClockEpochs());
    }

    public void stopBackgroundPolling() {
        if (pollingExecutor != null) {
            pollingExecutor.shutdown();
            pollingExecutor = null;
            log.info("Beacon Chain polling stopped");
        }
    }

    // ── epoch refresh ─────────────────────────────────────────────────────────

    private void updateCachedEpochs() {
        try {
            long finalizedEpoch = provider.fetchFinalizedEpoch();
            if (finalizedEpoch < 0) {
                throw new RuntimeException("Provider returned -1");
            }

            if (cachedFinalizedEpoch > 0 && finalizedEpoch < cachedFinalizedEpoch) {
                log.warn("Epoch went backwards: {} -> {} (API glitch?)",
                    cachedFinalizedEpoch, finalizedEpoch);
            }
            if (finalizedEpoch != cachedFinalizedEpoch) {
                log.info("Epoch advanced: {} -> {} (current: {})",
                    cachedFinalizedEpoch, finalizedEpoch, finalizedEpoch + 2);
            }

            cachedFinalizedEpoch = finalizedEpoch;
            cachedCurrentEpoch   = finalizedEpoch + 2;
            lastUpdateTime       = System.currentTimeMillis();
            lastError            = null;
        } catch (Exception e) {
            String msg = e.getMessage();
            if (!String.valueOf(msg).equals(String.valueOf(lastError))) {
                log.error("Epoch fetch failed: {}", msg);
            }
            lastError = msg;
            if (cachedFinalizedEpoch < 0) {
                fallbackCalculateEpochs();
            }
        }
    }

    private void fallbackCalculateEpochs() {
        long genesisMs = (networkMode == BlockchainConfig.Mode.MAINNET)
            ? 1_606_824_023_000L   // mainnet genesis: Dec 1 2020
            : 1_655_733_600_000L;  // sepolia genesis: Jun 20 2022
        cachedFinalizedEpoch = new ClockBeaconChainProvider(genesisMs, EPOCH_DURATION_MS, System::currentTimeMillis)
            .fetchFinalizedEpoch();
        cachedCurrentEpoch   = cachedFinalizedEpoch + 2;
        lastUpdateTime       = System.currentTimeMillis();
        log.warn("Using calculated epoch fallback: finalized={}, current={}",
            cachedFinalizedEpoch, cachedCurrentEpoch);
    }

    // ── public accessors ──────────────────────────────────────────────────────

    public long getCachedFinalizedEpoch() { return cachedFinalizedEpoch; }
    public long getCachedCurrentEpoch()   { return cachedCurrentEpoch;   }
    public BlockchainConfig.Mode getNetworkMode() { return networkMode;  }
    public boolean isEpochDataFresh() {
        return (System.currentTimeMillis() - lastUpdateTime) < 300_000L;
    }

    public Map<String, Object> getHealthStatus() {
        Map<String, Object> h = new HashMap<>();
        h.put("mode",              networkMode.toString());
        h.put("providers",         List.of(provider.name()));
        h.put("fresh",             isEpochDataFresh());
        h.put("currentEpoch",      cachedCurrentEpoch);
        h.put("finalizedEpoch",    cachedFinalizedEpoch);
        h.put("lastUpdateTime",    lastUpdateTime);
        h.put("timeSinceUpdateMs", System.currentTimeMillis() - lastUpdateTime);
        h.put("lastError",         lastError);
        h.put("chainContext",      networkMode == BlockchainConfig.Mode.MAINNET ? "mainnet" : "sepolia");
        h.put("epochSource",       networkMode.usesLocalClockEpochs() ? "local-clock" : "beacon-provider");
        h.put("externalNetworkPolling", !networkMode.usesLocalClockEpochs());
        return h;
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static String modeToApiUrl(BlockchainConfig.Mode mode) {
        return mode == BlockchainConfig.Mode.MAINNET
            ? BeaconchainDotInProvider.MAINNET_BASE
            : BeaconchainDotInProvider.SEPOLIA_BASE;
    }
}
