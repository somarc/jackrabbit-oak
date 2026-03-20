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

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(
    name = "Oak Node Runtime Tuning",
    description = "Optional OSGi overrides for node bootstrap, storage, and server runtime knobs."
)
public @interface NodeRuntimeTuningConfig {

    @AttributeDefinition(
        name = "Consensus Enabled",
        description = "Enable consensus startup. Empty preserves system-property behavior."
    )
    String consensus_enabled() default "";

    @AttributeDefinition(
        name = "Consensus Mode",
        description = "Consensus mode override. Empty preserves system-property behavior."
    )
    String consensus_mode() default "";

    @AttributeDefinition(
        name = "Wallet Keystore Path",
        description = "Explicit node wallet keystore path. Empty preserves system-property behavior."
    )
    String wallet_keystore_path() default "";

    @AttributeDefinition(
        name = "Standby Bootstrap Enabled",
        description = "Enable pre-cluster standby bootstrap for empty stores. Empty preserves system-property behavior."
    )
    String standby_bootstrap_enabled() default "";

    @AttributeDefinition(
        name = "Bootstrap Primary Host",
        description = "Explicit bootstrap primary host. Empty preserves system-property behavior."
    )
    String bootstrap_primary_host() default "";

    @AttributeDefinition(
        name = "Bootstrap Primary Port",
        description = "Explicit bootstrap primary standby port. <= 0 preserves system-property behavior."
    )
    int bootstrap_primary_port() default -1;

    @AttributeDefinition(
        name = "BlobStore Type",
        description = "BlobStore type override. Empty preserves system-property or environment behavior."
    )
    String blobstore_type() default "";

    @AttributeDefinition(
        name = "IPFS API Endpoint",
        description = "IPFS API endpoint override. Empty preserves system-property or environment behavior."
    )
    String ipfs_api_endpoint() default "";

    @AttributeDefinition(
        name = "Secondary HTTP Port",
        description = "Optional plain HTTP port when TLS is enabled. <= 0 preserves system-property behavior."
    )
    int http_port() default -1;

    @AttributeDefinition(
        name = "Sharding Num Shards",
        description = "Shard count override. <= 0 preserves system-property or environment behavior."
    )
    int sharding_num_shards() default -1;

    @AttributeDefinition(
        name = "Mock Epoch Duration Seconds",
        description = "Mock-mode epoch duration override. <= 0 preserves system-property or environment behavior."
    )
    long mock_epoch_duration_seconds() default -1L;

    @AttributeDefinition(
        name = "Aeron Directory Name",
        description = "Explicit Aeron directory path/name. Empty preserves system-property behavior."
    )
    String aeron_dir_name() default "";

    @AttributeDefinition(
        name = "Aeron Cluster Hostnames",
        description = "Explicit Aeron cluster hostname list. Empty preserves system-property behavior."
    )
    String aeron_cluster_hostnames() default "";

    @AttributeDefinition(
        name = "Proposal Persistence Directory",
        description = "Explicit proposal persistence directory. Empty preserves system-property or environment behavior."
    )
    String proposal_persistence_dir() default "";
}
