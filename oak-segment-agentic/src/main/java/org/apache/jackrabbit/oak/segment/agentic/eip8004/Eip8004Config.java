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
package org.apache.jackrabbit.oak.segment.agentic.eip8004;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class Eip8004Config {
    public static final String PROP_ENABLED = "oak.eip8004.enabled";
    public static final String PROP_CHAIN_ID = "oak.eip8004.chainId";
    public static final String PROP_IDENTITY_REGISTRY = "oak.eip8004.identityRegistry";
    public static final String PROP_REGISTRATION_URI = "oak.eip8004.registration.uri";
    public static final String PROP_SERVICE_ENDPOINT = "oak.eip8004.service.endpoint";
    public static final String PROP_SERVICE_NAME = "oak.eip8004.service.name";
    public static final String PROP_SERVICE_VERSION = "oak.eip8004.service.version";
    public static final String PROP_CAPABILITIES = "oak.eip8004.capabilities";
    public static final String PROP_SUPPORTED_TRUST = "oak.eip8004.supportedTrust";

    private final boolean enabled;
    private final String chainId;
    private final String identityRegistryAddress;
    private final String registrationUri;
    private final String serviceEndpoint;
    private final String serviceName;
    private final String serviceVersion;
    private final List<String> capabilities;
    private final List<String> supportedTrust;

    private Eip8004Config(
            boolean enabled,
            String chainId,
            String identityRegistryAddress,
            String registrationUri,
            String serviceEndpoint,
            String serviceName,
            String serviceVersion,
            List<String> capabilities,
            List<String> supportedTrust
    ) {
        this.enabled = enabled;
        this.chainId = chainId;
        this.identityRegistryAddress = identityRegistryAddress;
        this.registrationUri = registrationUri;
        this.serviceEndpoint = serviceEndpoint;
        this.serviceName = serviceName;
        this.serviceVersion = serviceVersion;
        this.capabilities = capabilities;
        this.supportedTrust = supportedTrust;
    }

    public static Eip8004Config load() {
        boolean enabled = parseBoolean(getEnvOrProperty("OAK_EIP8004_ENABLED", PROP_ENABLED).orElse("false"));
        String chainId = getEnvOrProperty("OAK_EIP8004_CHAIN_ID", PROP_CHAIN_ID).orElse("");
        String identityRegistry = getEnvOrProperty("OAK_EIP8004_IDENTITY_REGISTRY", PROP_IDENTITY_REGISTRY).orElse("");
        String registrationUri = getEnvOrProperty("OAK_EIP8004_REGISTRATION_URI", PROP_REGISTRATION_URI).orElse("");
        String serviceEndpoint = getEnvOrProperty("OAK_EIP8004_SERVICE_ENDPOINT", PROP_SERVICE_ENDPOINT).orElse("");
        String serviceName = getEnvOrProperty("OAK_EIP8004_SERVICE_NAME", PROP_SERVICE_NAME).orElse("Oak-Chain-Agentic");
        String serviceVersion = getEnvOrProperty("OAK_EIP8004_SERVICE_VERSION", PROP_SERVICE_VERSION).orElse("0.1.0");
        List<String> capabilities = splitList(getEnvOrProperty("OAK_EIP8004_CAPABILITIES", PROP_CAPABILITIES).orElse(""));
        List<String> supportedTrust = splitList(getEnvOrProperty("OAK_EIP8004_SUPPORTED_TRUST", PROP_SUPPORTED_TRUST)
                .orElse("reputation,crypto-economic"));

        return new Eip8004Config(
                enabled,
                chainId,
                identityRegistry,
                registrationUri,
                serviceEndpoint,
                serviceName,
                serviceVersion,
                capabilities,
                supportedTrust
        );
    }

    private static Optional<String> getEnvOrProperty(String envKey, String propKey) {
        String envValue = System.getenv(envKey);
        if (envValue != null && !envValue.isEmpty()) {
            return Optional.of(envValue);
        }
        String propValue = System.getProperty(propKey);
        if (propValue != null && !propValue.isEmpty()) {
            return Optional.of(propValue);
        }
        return Optional.empty();
    }

    private static boolean parseBoolean(String raw) {
        return "true".equalsIgnoreCase(raw) || "1".equals(raw) || "yes".equalsIgnoreCase(raw);
    }

    private static List<String> splitList(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return Collections.emptyList();
        }
        String[] parts = raw.split(",");
        List<String> values = new ArrayList<>();
        for (String part : parts) {
            String value = part.trim();
            if (!value.isEmpty()) {
                values.add(value.toLowerCase(Locale.ROOT));
            }
        }
        return values;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getChainId() {
        return chainId;
    }

    public String getIdentityRegistryAddress() {
        return identityRegistryAddress;
    }

    public String getRegistrationUri() {
        return registrationUri;
    }

    public String getServiceEndpoint() {
        return serviceEndpoint;
    }

    public String getServiceName() {
        return serviceName;
    }

    public String getServiceVersion() {
        return serviceVersion;
    }

    public List<String> getCapabilities() {
        return capabilities;
    }

    public List<String> getSupportedTrust() {
        return supportedTrust;
    }
}
