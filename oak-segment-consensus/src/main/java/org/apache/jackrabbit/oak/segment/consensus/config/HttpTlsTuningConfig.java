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
    name = "Oak HTTP TLS Runtime Tuning",
    description = "Optional OSGi overrides for validator TLS listener settings. Empty values preserve system-property defaults."
)
public @interface HttpTlsTuningConfig {

    @AttributeDefinition(
        name = "Enabled Override",
        description = "Set true or false to override tls.enabled. Empty preserves system-property/default behavior."
    )
    String enabled() default "";

    @AttributeDefinition(
        name = "Keystore Path",
        description = "Override tls.keystore.path. Empty preserves existing behavior."
    )
    String keystore_path() default "";

    @AttributeDefinition(
        name = "Keystore Password",
        description = "Override tls.keystore.password. Empty preserves existing behavior."
    )
    String keystore_password() default "";

    @AttributeDefinition(
        name = "Keystore Type",
        description = "Override tls.keystore.type. Empty preserves existing behavior."
    )
    String keystore_type() default "";

    @AttributeDefinition(
        name = "Certificate Path",
        description = "Override tls.cert.path. Empty preserves existing behavior."
    )
    String cert_path() default "";

    @AttributeDefinition(
        name = "Private Key Path",
        description = "Override tls.key.path. Empty preserves existing behavior."
    )
    String key_path() default "";

    @AttributeDefinition(
        name = "Truststore Path",
        description = "Override tls.truststore.path. Empty preserves existing behavior."
    )
    String truststore_path() default "";

    @AttributeDefinition(
        name = "Truststore Password",
        description = "Override tls.truststore.password. Empty preserves existing behavior."
    )
    String truststore_password() default "";

    @AttributeDefinition(
        name = "Client Auth",
        description = "Override tls.client.auth. Empty preserves existing behavior."
    )
    String client_auth() default "";

    @AttributeDefinition(
        name = "Protocols",
        description = "Override tls.protocols. Empty preserves existing behavior."
    )
    String protocols() default "";

    @AttributeDefinition(
        name = "Ciphers",
        description = "Override tls.ciphers. Empty preserves existing behavior."
    )
    String ciphers() default "";
}
