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
    service = NodeRuntimeTuningService.class,
    configurationPolicy = ConfigurationPolicy.OPTIONAL,
    immediate = true
)
@Designate(ocd = NodeRuntimeTuningConfig.class)
public final class NodeRuntimeTuningService {

    private static final Logger log = LoggerFactory.getLogger(NodeRuntimeTuningService.class);

    private static final String COMPONENT = "nodeRuntimeTuning";
    private static final String FALLBACK_SOURCE = "system-properties-or-env";

    @Activate
    protected void activate(NodeRuntimeTuningConfig config) {
        apply(config);
    }

    @Modified
    protected void modified(NodeRuntimeTuningConfig config) {
        apply(config);
    }

    @Deactivate
    protected void deactivate() {
        RuntimePropertyOverrideRegistry.clear(COMPONENT);
        log.info("NodeRuntimeTuningService deactivated");
    }

    private void apply(NodeRuntimeTuningConfig config) {
        Map<String, String> overrides = new LinkedHashMap<>();

        putIfBooleanLiteral(overrides, "consensus.enabled", config.consensus_enabled());
        putIfText(overrides, "consensus.mode", config.consensus_mode());
        putIfText(overrides, "wallet.keystore.path", config.wallet_keystore_path());
        putIfBooleanLiteral(overrides, "consensus.aeron.standby.bootstrap.enabled", config.standby_bootstrap_enabled());
        putIfText(overrides, "bootstrap.primary.host", config.bootstrap_primary_host());
        putIfPositive(overrides, "bootstrap.primary.port", config.bootstrap_primary_port());
        putIfText(overrides, "blobstore.type", config.blobstore_type());
        putIfText(overrides, "ipfs.api.endpoint", config.ipfs_api_endpoint());
        putIfPositive(overrides, "http.port", config.http_port());
        putIfPositive(overrides, "sharding.numShards", config.sharding_num_shards());
        putIfPositive(overrides, "oak.mock.epoch.duration.seconds", config.mock_epoch_duration_seconds());
        putIfText(overrides, "aeron.dir.name", config.aeron_dir_name());
        putIfText(overrides, "aeron.cluster.hostnames", config.aeron_cluster_hostnames());
        putIfText(overrides, "oak.proposal.persistence.dir", config.proposal_persistence_dir());

        RuntimePropertyOverrideRegistry.setOverrides(COMPONENT, overrides);
        RuntimePropertySourceRegistry.markSource(COMPONENT, overrides.isEmpty() ? FALLBACK_SOURCE : "osgi-config-admin");

        if (overrides.isEmpty()) {
            log.info("NODE_RUNTIME_TUNING_SOURCE source={} overrides=0", FALLBACK_SOURCE);
        } else {
            log.info("NODE_RUNTIME_TUNING_SOURCE source=osgi-config-admin overrides={}", overrides.keySet());
        }
    }

    private static void putIfText(Map<String, String> overrides, String key, String value) {
        if (value != null && !value.trim().isEmpty()) {
            overrides.put(key, value.trim());
        }
    }

    private static void putIfPositive(Map<String, String> overrides, String key, int value) {
        if (value > 0) {
            overrides.put(key, String.valueOf(value));
        }
    }

    private static void putIfPositive(Map<String, String> overrides, String key, long value) {
        if (value > 0L) {
            overrides.put(key, String.valueOf(value));
        }
    }

    private static void putIfBooleanLiteral(Map<String, String> overrides, String key, String value) {
        if (value == null) {
            return;
        }
        String normalized = value.trim().toLowerCase();
        if ("true".equals(normalized) || "false".equals(normalized)) {
            overrides.put(key, normalized);
        }
    }
}
