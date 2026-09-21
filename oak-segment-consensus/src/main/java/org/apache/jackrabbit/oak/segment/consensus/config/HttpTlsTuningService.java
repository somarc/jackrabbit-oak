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

import org.apache.jackrabbit.oak.segment.http.server.TlsConfiguration;
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
    service = HttpTlsTuningService.class,
    configurationPolicy = ConfigurationPolicy.OPTIONAL,
    immediate = true
)
@Designate(ocd = HttpTlsTuningConfig.class)
public final class HttpTlsTuningService {

    private static final Logger log = LoggerFactory.getLogger(HttpTlsTuningService.class);
    private static final String COMPONENT = "tlsTuning";
    private static final String NAMESPACE = HttpTlsTuningService.class.getName();
    private static final String FALLBACK_SOURCE = "system-properties";

    @Activate
    protected void activate(HttpTlsTuningConfig config) {
        apply(config);
    }

    @Modified
    protected void modified(HttpTlsTuningConfig config) {
        apply(config);
    }

    @Deactivate
    protected void deactivate() {
        RuntimePropertyOverrideRegistry.clear(NAMESPACE);
        RuntimePropertySourceRegistry.markSource(COMPONENT, FALLBACK_SOURCE);
        log.info("TLS_TUNING_SOURCE source={} action=deactivate", FALLBACK_SOURCE);
    }

    private void apply(HttpTlsTuningConfig config) {
        Map<String, String> overrides = new LinkedHashMap<>();
        putIfBoolean(overrides, TlsConfiguration.PROP_TLS_ENABLED, config.enabled());
        putIfText(overrides, TlsConfiguration.PROP_KEYSTORE_PATH, config.keystore_path());
        putIfText(overrides, TlsConfiguration.PROP_KEYSTORE_PASSWORD, config.keystore_password());
        putIfText(overrides, TlsConfiguration.PROP_KEYSTORE_TYPE, config.keystore_type());
        putIfText(overrides, TlsConfiguration.PROP_CERT_PATH, config.cert_path());
        putIfText(overrides, TlsConfiguration.PROP_KEY_PATH, config.key_path());
        putIfText(overrides, TlsConfiguration.PROP_TRUSTSTORE_PATH, config.truststore_path());
        putIfText(overrides, TlsConfiguration.PROP_TRUSTSTORE_PASSWORD, config.truststore_password());
        putIfText(overrides, TlsConfiguration.PROP_CLIENT_AUTH, config.client_auth());
        putIfText(overrides, TlsConfiguration.PROP_PROTOCOLS, config.protocols());
        putIfText(overrides, TlsConfiguration.PROP_CIPHERS, config.ciphers());

        RuntimePropertyOverrideRegistry.setOverrides(NAMESPACE, overrides);
        RuntimePropertySourceRegistry.markSource(COMPONENT, overrides.isEmpty() ? FALLBACK_SOURCE : "osgi-config-admin");
        log.info("TLS_TUNING_SOURCE source={} overrides={}",
            RuntimePropertySourceRegistry.getSource(COMPONENT, FALLBACK_SOURCE), overrides.keySet());
    }

    private void putIfText(Map<String, String> overrides, String key, String value) {
        if (RuntimeConfigValueResolver.hasText(value)) {
            overrides.put(key, value.trim());
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
