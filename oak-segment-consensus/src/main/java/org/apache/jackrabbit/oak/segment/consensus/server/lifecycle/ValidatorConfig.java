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
package org.apache.jackrabbit.oak.segment.consensus.server.lifecycle;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(
    name = "Blockchain AEM Validator Configuration",
    description = "Core configuration for the blockchain validator node"
)
public @interface ValidatorConfig {

    @AttributeDefinition(name = "HTTP Port", description = "Port for the validator HTTP API")
    int http_port() default 8090;

    @AttributeDefinition(name = "Store Directory", description = "Segment store directory")
    String store_directory() default "/var/oak-chain/segmentstore-composite-mount-oak-chain";

    @AttributeDefinition(name = "Consensus Enabled", description = "Enable Aeron consensus")
    boolean consensus_enabled() default true;

    @AttributeDefinition(name = "Consensus Mode", description = "Consensus mode (aeron)")
    String consensus_mode() default "aeron";

    @AttributeDefinition(name = "Self URL", description = "Validator URL (e.g., http://validator-0:8090)")
    String consensus_self_url() default "";

    @AttributeDefinition(name = "Peer URLs", description = "Comma-separated peer URLs")
    String consensus_peers() default "";
}
