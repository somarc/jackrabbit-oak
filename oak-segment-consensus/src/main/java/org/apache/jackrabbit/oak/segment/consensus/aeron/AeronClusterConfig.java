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
package org.apache.jackrabbit.oak.segment.consensus.aeron;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

/**
 * OSGi configuration for Aeron cluster service.
 */
@ObjectClassDefinition(
    name = "Blockchain AEM - Aeron Cluster",
    description = "Configuration for Aeron cluster consensus service"
)
public @interface AeronClusterConfig {

    @AttributeDefinition(
        name = "Enabled",
        description = "Enable Aeron cluster service"
    )
    boolean enabled() default true;

    @AttributeDefinition(
        name = "Node ID",
        description = "Aeron cluster node ID"
    )
    int nodeId() default 0;

    @AttributeDefinition(
        name = "Self URL",
        description = "Validator self URL (e.g., http://localhost:8090)"
    )
    String selfUrl() default "http://localhost:8090";

    @AttributeDefinition(
        name = "Peer URLs",
        description = "Peer validator URLs"
    )
    String[] peerUrls() default {};

    @AttributeDefinition(
        name = "Observe Elections",
        description = "Observe elections on startup before genesis writes"
    )
    boolean observeElections() default true;

    @AttributeDefinition(
        name = "Log Cluster State Details",
        description = "Enable verbose cluster state logging on startup"
    )
    boolean logClusterStateDetails() default false;

    @AttributeDefinition(
        name = "Beacon API URL",
        description = "Ethereum beacon API URL"
    )
    String beaconApiUrl() default "https://beaconcha.in/api";
}
