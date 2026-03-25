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

import org.apache.jackrabbit.oak.segment.http.server.ValidatorAuthHandler;
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
    service = ValidatorAuthTuningService.class,
    configurationPolicy = ConfigurationPolicy.OPTIONAL,
    immediate = true
)
@Designate(ocd = ValidatorAuthTuningConfig.class)
public final class ValidatorAuthTuningService {

    private static final Logger log = LoggerFactory.getLogger(ValidatorAuthTuningService.class);
    private static final String COMPONENT = "validatorAuthTuning";
    private static final String NAMESPACE = ValidatorAuthTuningService.class.getName();
    private static final String FALLBACK_SOURCE = "system-properties";

    @Activate
    protected void activate(ValidatorAuthTuningConfig config) {
        apply(config);
    }

    @Modified
    protected void modified(ValidatorAuthTuningConfig config) {
        apply(config);
    }

    @Deactivate
    protected void deactivate() {
        RuntimePropertyOverrideRegistry.clear(NAMESPACE);
        RuntimePropertySourceRegistry.markSource(COMPONENT, FALLBACK_SOURCE);
        log.info("VALIDATOR_AUTH_TUNING_SOURCE source={} action=deactivate", FALLBACK_SOURCE);
    }

    private void apply(ValidatorAuthTuningConfig config) {
        Map<String, String> overrides = new LinkedHashMap<>();
        putIfBoolean(overrides, ValidatorAuthHandler.PROP_AUTH_ENABLED, config.enabled());
        putIfNonNegative(overrides, ValidatorAuthHandler.PROP_SESSION_TTL, config.session_ttl_hours());
        putIfText(overrides, ValidatorAuthHandler.PROP_ALLOWED_OPERATOR_IDS, config.allowed_operator_ids());
        putIfText(overrides, ValidatorAuthHandler.PROP_ALLOWED_WALLETS, config.allowed_wallets());

        RuntimePropertyOverrideRegistry.setOverrides(NAMESPACE, overrides);
        RuntimePropertySourceRegistry.markSource(COMPONENT, overrides.isEmpty() ? FALLBACK_SOURCE : "osgi-config-admin");
        log.info("VALIDATOR_AUTH_TUNING_SOURCE source={} overrides={}",
            RuntimePropertySourceRegistry.getSource(COMPONENT, FALLBACK_SOURCE), overrides.keySet());
    }

    private void putIfText(Map<String, String> overrides, String key, String value) {
        if (RuntimeConfigValueResolver.hasText(value)) {
            overrides.put(key, value.trim());
        }
    }

    private void putIfNonNegative(Map<String, String> overrides, String key, int value) {
        if (value >= 0) {
            overrides.put(key, String.valueOf(value));
        }
    }

    private void putIfBoolean(Map<String, String> overrides, String key, String value) {
        if (!RuntimeConfigValueResolver.hasText(value)) {
            return;
        }
        String normalized = value.trim().toLowerCase();
        if ("true".equals(normalized) || "false".equals(normalized)) {
            overrides.put(key, normalized);
        }
    }
}
