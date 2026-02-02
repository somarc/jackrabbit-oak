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

import java.util.Collections;
import java.util.List;

public final class IdentityRegistryEntry {
    private final String agentId;
    private final String registrationUri;
    private final List<String> capabilities;

    public IdentityRegistryEntry(String agentId, String registrationUri, List<String> capabilities) {
        this.agentId = agentId;
        this.registrationUri = registrationUri;
        this.capabilities = capabilities == null ? Collections.emptyList() : Collections.unmodifiableList(capabilities);
    }

    public String getAgentId() {
        return agentId;
    }

    public String getRegistrationUri() {
        return registrationUri;
    }

    public List<String> getCapabilities() {
        return capabilities;
    }
}
