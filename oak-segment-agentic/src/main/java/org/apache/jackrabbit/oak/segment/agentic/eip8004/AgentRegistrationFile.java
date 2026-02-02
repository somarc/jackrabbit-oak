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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class AgentRegistrationFile {
    private static final String DEFAULT_TYPE = "https://eips.ethereum.org/EIPS/eip-8004#registration-v1";

    private final String type;
    private final String name;
    private final String description;
    private final List<Service> services;
    private final List<String> supportedTrust;
    private final Map<String, Object> metadata;

    private AgentRegistrationFile(String type,
                                  String name,
                                  String description,
                                  List<Service> services,
                                  List<String> supportedTrust,
                                  Map<String, Object> metadata) {
        this.type = type;
        this.name = name;
        this.description = description;
        this.services = services == null ? Collections.emptyList() : services;
        this.supportedTrust = supportedTrust == null ? Collections.emptyList() : supportedTrust;
        this.metadata = metadata == null ? Collections.emptyMap() : metadata;
    }

    public static AgentRegistrationFile forAgent(String agentId,
                                                 String agentType,
                                                 Eip8004Config config,
                                                 List<String> capabilities) {
        String name = config.getServiceName() + "-" + agentId;
        String description = "Oak-Chain agentic service (" + agentType + ")";
        Service service = new Service(
                config.getServiceName(),
                config.getServiceEndpoint(),
                config.getServiceVersion(),
                capabilities
        );

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("agentId", agentId);
        metadata.put("agentType", agentType);
        if (!config.getChainId().isEmpty()) {
            metadata.put("chainId", config.getChainId());
        }

        return new AgentRegistrationFile(
                DEFAULT_TYPE,
                name,
                description,
                List.of(service),
                config.getSupportedTrust(),
                metadata
        );
    }

    public String toJson() {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        return gson.toJson(this);
    }

    public static final class Service {
        private final String name;
        private final String endpoint;
        private final String version;
        private final List<String> capabilities;

        public Service(String name, String endpoint, String version, List<String> capabilities) {
            this.name = name;
            this.endpoint = endpoint;
            this.version = version;
            this.capabilities = capabilities == null ? Collections.emptyList() : capabilities;
        }

        public String getName() {
            return name;
        }

        public String getEndpoint() {
            return endpoint;
        }

        public String getVersion() {
            return version;
        }

        public List<String> getCapabilities() {
            return capabilities;
        }
    }
}
