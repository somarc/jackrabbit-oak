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
package org.apache.jackrabbit.oak.segment.consensus.config;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

@Component(
    service = BlockchainConfigTuningService.class,
    configurationPolicy = ConfigurationPolicy.OPTIONAL,
    immediate = true
)
@Designate(ocd = BlockchainConfigTuningConfig.class)
public final class BlockchainConfigTuningService {

    private static final Logger log = LoggerFactory.getLogger(BlockchainConfigTuningService.class);

    private static final String PROP_MODE = "oak.blockchain.mode";
    private static final String PROP_CONTRACT_ADDRESS = "oak.blockchain.contractAddress";
    private static final String PROP_RPC_URL = "oak.blockchain.rpcUrl";
    private static final String PROP_GAS_PRICE_GWEI = "oak.blockchain.gasPriceGwei";
    private static final String PROP_GAS_WRITE_STANDARD = "oak.blockchain.gas.write.standard";
    private static final String PROP_GAS_WRITE_EXPRESS = "oak.blockchain.gas.write.express";
    private static final String PROP_GAS_WRITE_PRIORITY = "oak.blockchain.gas.write.priority";

    @Activate
    protected void activate(BlockchainConfigTuningConfig config) {
        apply(config);
    }

    @Modified
    protected void modified(BlockchainConfigTuningConfig config) {
        apply(config);
    }

    @Deactivate
    protected void deactivate() {
        BlockchainConfigOverrideRegistry.clear();
        BlockchainConfigSourceRegistry.markFallbackSource();
        BlockchainConfig.reset();
        log.info("BLOCKCHAIN_CONFIG_SOURCE source=env-or-system-properties action=deactivate");
    }

    private void apply(BlockchainConfigTuningConfig config) {
        Map<String, String> overrides = new LinkedHashMap<>();

        putIfText(overrides, PROP_MODE, config.mode());
        putIfText(overrides, PROP_CONTRACT_ADDRESS, config.contract_address());
        putIfText(overrides, PROP_RPC_URL, config.rpc_url());
        putIfPositive(overrides, PROP_GAS_PRICE_GWEI, config.gas_price_gwei());
        putIfPositive(overrides, PROP_GAS_WRITE_STANDARD, config.gas_write_standard());
        putIfPositive(overrides, PROP_GAS_WRITE_EXPRESS, config.gas_write_express());
        putIfPositive(overrides, PROP_GAS_WRITE_PRIORITY, config.gas_write_priority());

        BlockchainConfigOverrideRegistry.setOverrides(overrides);

        if (overrides.isEmpty()) {
            BlockchainConfigSourceRegistry.markFallbackSource();
            log.info("BLOCKCHAIN_CONFIG_SOURCE source=env-or-system-properties overrides=0");
        } else {
            BlockchainConfigSourceRegistry.markOsgiSource();
            log.info("BLOCKCHAIN_CONFIG_SOURCE source=osgi-config-admin overrides={}", overrides.keySet());
        }

        BlockchainConfig.reset();
    }

    private void putIfText(Map<String, String> overrides, String key, String value) {
        if (value != null && !value.trim().isEmpty()) {
            overrides.put(key, value.trim());
        }
    }

    private void putIfPositive(Map<String, String> overrides, String key, long value) {
        if (value > 0) {
            overrides.put(key, String.valueOf(value));
        }
    }
}
