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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;

public final class Eip8004RegistrationService {
    private static final Logger log = LoggerFactory.getLogger(Eip8004RegistrationService.class);

    private final Eip8004Config config;
    private final IdentityRegistryClient identityRegistryClient;

    public Eip8004RegistrationService(Eip8004Config config, IdentityRegistryClient identityRegistryClient) {
        this.config = config;
        this.identityRegistryClient = identityRegistryClient;
    }

    public RegistrationResult ensureRegistered(String walletAddress, String agentType, List<String> fallbackCapabilities) {
        if (config == null || !config.isEnabled()) {
            return RegistrationResult.disabled();
        }
        if (walletAddress == null || walletAddress.isEmpty()) {
            return RegistrationResult.failed("Missing wallet address");
        }
        String registrationUri = config.getRegistrationUri();
        if (registrationUri == null || registrationUri.isEmpty()) {
            String json = buildRegistrationJson(walletAddress, agentType, fallbackCapabilities);
            log.info("EIP-8004 enabled but registration URI is not configured; skipping on-chain registration.");
            return RegistrationResult.pending(json);
        }
        if (identityRegistryClient.isRegistered(walletAddress)) {
            return RegistrationResult.alreadyRegistered();
        }
        String agentId = identityRegistryClient.register(walletAddress, registrationUri);
        if (agentId == null || agentId.isEmpty()) {
            return RegistrationResult.failed("Identity registry did not return an agent id");
        }
        return RegistrationResult.registered(agentId);
    }

    public String buildRegistrationJson(String walletAddress, String agentType, List<String> fallbackCapabilities) {
        List<String> capabilities = config.getCapabilities();
        if (capabilities == null || capabilities.isEmpty()) {
            capabilities = fallbackCapabilities == null ? Collections.emptyList() : fallbackCapabilities;
        }
        AgentRegistrationFile registrationFile = AgentRegistrationFile.forAgent(walletAddress, agentType, config, capabilities);
        return registrationFile.toJson();
    }

    public static final class RegistrationResult {
        public enum Status {
            DISABLED,
            PENDING,
            REGISTERED,
            ALREADY_REGISTERED,
            FAILED
        }

        private final Status status;
        private final String message;
        private final String registrationJson;

        private RegistrationResult(Status status, String message, String registrationJson) {
            this.status = status;
            this.message = message;
            this.registrationJson = registrationJson;
        }

        public static RegistrationResult disabled() {
            return new RegistrationResult(Status.DISABLED, "EIP-8004 disabled", "");
        }

        public static RegistrationResult pending(String registrationJson) {
            return new RegistrationResult(Status.PENDING, "Registration URI not set", registrationJson);
        }

        public static RegistrationResult registered(String agentId) {
            return new RegistrationResult(Status.REGISTERED, agentId, "");
        }

        public static RegistrationResult alreadyRegistered() {
            return new RegistrationResult(Status.ALREADY_REGISTERED, "Already registered", "");
        }

        public static RegistrationResult failed(String message) {
            return new RegistrationResult(Status.FAILED, message, "");
        }

        public Status getStatus() {
            return status;
        }

        public String getMessage() {
            return message;
        }

        public String getRegistrationJson() {
            return registrationJson;
        }
    }
}
