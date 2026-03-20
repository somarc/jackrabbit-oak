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
    name = "Oak Validator Auth Runtime Tuning",
    description = "Optional OSGi overrides for validator dashboard authentication."
)
public @interface ValidatorAuthTuningConfig {

    @AttributeDefinition(
        name = "Enabled Override",
        description = "Set true or false to override dashboard.auth.enabled. Empty preserves existing behavior."
    )
    String enabled() default "";

    @AttributeDefinition(
        name = "Session TTL Hours",
        description = "Override dashboard.auth.session.ttl. Negative keeps existing behavior."
    )
    int session_ttl_hours() default -1;

    @AttributeDefinition(
        name = "Allowed Wallets",
        description = "Comma-separated wallet allow-list override. Empty preserves existing behavior."
    )
    String allowed_wallets() default "";
}
