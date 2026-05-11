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

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tries a prioritized list of {@link BeaconChainProvider}s in order, returning
 * the first successful result. Open circuits (via {@link CircuitBreakingBeaconProvider})
 * are skipped without waiting.
 *
 * <p>Throws an exception only when every provider has failed or is open.
 */
public class FallbackBeaconChainProvider implements BeaconChainProvider {

    private static final Logger log = LoggerFactory.getLogger(FallbackBeaconChainProvider.class);

    private final List<BeaconChainProvider> providers;

    public FallbackBeaconChainProvider(List<BeaconChainProvider> providers) {
        if (providers == null || providers.isEmpty()) {
            throw new IllegalArgumentException("At least one provider is required");
        }
        this.providers = providers;
    }

    public FallbackBeaconChainProvider(BeaconChainProvider... providers) {
        this(Arrays.asList(providers));
    }

    @Override
    public long fetchFinalizedEpoch() throws Exception {
        StringBuilder errors = new StringBuilder();
        for (BeaconChainProvider provider : providers) {
            try {
                long epoch = provider.fetchFinalizedEpoch();
                if (epoch >= 0) {
                    log.debug("Epoch {} fetched from {}", epoch, provider.name());
                    return epoch;
                }
                errors.append(provider.name()).append(":returned -1; ");
            } catch (CircuitBreakingBeaconProvider.CircuitOpenException e) {
                log.debug("Skipping {} (circuit open)", provider.name());
                errors.append(provider.name()).append(":circuit-open; ");
            } catch (Exception e) {
                log.warn("Provider {} failed: {}", provider.name(), e.getMessage());
                errors.append(provider.name()).append(":").append(e.getMessage()).append("; ");
            }
        }
        throw new Exception("All beacon providers failed: " + errors);
    }

    @Override
    public String name() {
        return "fallback[" + providers.stream().map(BeaconChainProvider::name)
            .collect(Collectors.joining(", ")) + "]";
    }

    /** Returns provider names in priority order, for health reporting. */
    public List<String> getProviderNames() {
        return providers.stream().map(BeaconChainProvider::name).collect(Collectors.toList());
    }

    /** Returns the underlying provider list (package-private for tests). */
    List<BeaconChainProvider> getProviders() {
        return providers;
    }

    /**
     * Builds a fallback provider from config, wrapping each with a circuit breaker.
     *
     * <p>Provider priority:
     * <ol>
     *   <li>Local beacon node ({@code BEACON_LOCAL_NODE_URL} / {@code beacon.local.node.url}),
     *       if configured</li>
     *   <li>beaconcha.in ({@code primaryApiUrl})</li>
     * </ol>
     *
     * @param primaryApiUrl  beaconcha.in base URL (mainnet or sepolia)
     * @param localNodeUrl   optional local node URL (empty string = not configured)
     */
    static FallbackBeaconChainProvider buildDefault(String primaryApiUrl, String localNodeUrl) {
        java.util.List<BeaconChainProvider> list = new java.util.ArrayList<>();

        if (localNodeUrl != null && !localNodeUrl.isEmpty()) {
            list.add(new CircuitBreakingBeaconProvider(
                new LocalBeaconNodeProvider(localNodeUrl, LocalBeaconNodeProvider.defaultFetcher()),
                5, 60_000L));
            log.info("Beacon provider #1: local-beacon-node ({})", localNodeUrl);
        }

        list.add(new CircuitBreakingBeaconProvider(
            new BeaconchainDotInProvider(primaryApiUrl, BeaconchainDotInProvider.defaultFetcher()),
            5, 60_000L));
        log.info("Beacon provider #{}: beaconcha.in ({})", list.size(), primaryApiUrl);

        return new FallbackBeaconChainProvider(list);
    }
}
