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
    name = "Oak Blockchain Runtime Tuning",
    description = "OSGi-managed blockchain mode, endpoint, and write gas pricing assumptions."
)
public @interface BlockchainConfigTuningConfig {

    @AttributeDefinition(
        name = "Mode",
        description = "Blockchain mode override (mock, sepolia, mainnet). Empty keeps env/system default."
    )
    String mode() default "";

    @AttributeDefinition(
        name = "Contract Address",
        description = "Smart contract address override. Empty keeps env/system default."
    )
    String contract_address() default "";

    @AttributeDefinition(
        name = "RPC URL",
        description = "Ethereum RPC URL override. Empty keeps env/system default."
    )
    String rpc_url() default "";

    @AttributeDefinition(
        name = "Gas Price (gwei)",
        description = "Gas price assumption for write cost estimates. <=0 keeps env/system/default."
    )
    long gas_price_gwei() default -1;

    @AttributeDefinition(
        name = "Write Gas Units - STANDARD",
        description = "Measured gas units for standard write path. <=0 keeps env/system/default."
    )
    long gas_write_standard() default -1;

    @AttributeDefinition(
        name = "Write Gas Units - EXPRESS",
        description = "Measured gas units for express write path. <=0 keeps env/system/default."
    )
    long gas_write_express() default -1;

    @AttributeDefinition(
        name = "Write Gas Units - PRIORITY",
        description = "Measured gas units for priority write path. <=0 keeps env/system/default."
    )
    long gas_write_priority() default -1;
}
