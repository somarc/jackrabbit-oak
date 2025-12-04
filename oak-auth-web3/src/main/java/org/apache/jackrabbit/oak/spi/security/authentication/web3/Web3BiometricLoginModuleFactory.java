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
package org.apache.jackrabbit.oak.spi.security.authentication.web3;

import org.apache.felix.jaas.LoginModuleFactory;
import org.jetbrains.annotations.NotNull;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.security.auth.spi.LoginModule;

/**
 * OSGi LoginModuleFactory for {@link Web3BiometricLoginModule}.
 * 
 * <p>This factory allows the Web3 biometric authentication to be configured
 * via OSGi Configuration Admin, rather than requiring manual JAAS configuration.</p>
 * 
 * <h2>Usage in OSGi Environment (Sling/AEM)</h2>
 * <p>Deploy this bundle and configure via OSGi Config Admin. Example configuration:</p>
 * <pre>
 * {
 *   "jaas.controlFlag": "SUFFICIENT",
 *   "jaas.ranking": 150
 * }
 * </pre>
 * 
 * <p>The factory will automatically register with the Felix JAAS service under the
 * "jackrabbit.oak" realm.</p>
 * 
 * <h2>Control Flags</h2>
 * <ul>
 *   <li><b>REQUIRED</b> - Must succeed; continue to other modules</li>
 *   <li><b>REQUISITE</b> - Must succeed or fail immediately</li>
 *   <li><b>SUFFICIENT</b> - If succeeds, skip remaining modules (recommended)</li>
 *   <li><b>OPTIONAL</b> - Continue regardless of outcome</li>
 * </ul>
 * 
 * @see Web3BiometricLoginModule
 * @see org.apache.felix.jaas.LoginModuleFactory
 */
@Component(
    service = LoginModuleFactory.class,
    property = {
        "jaas.controlFlag=SUFFICIENT",
        "jaas.realmName=jackrabbit.oak",
        "jaas.ranking:Integer=150"
    }
)
@Designate(ocd = Web3BiometricLoginModuleFactory.Config.class)
public class Web3BiometricLoginModuleFactory implements LoginModuleFactory {
    
    private static final Logger log = LoggerFactory.getLogger(Web3BiometricLoginModuleFactory.class);
    
    /**
     * OSGi Configuration for the LoginModule.
     */
    @ObjectClassDefinition(
        name = "Web3 Biometric Authentication LoginModule",
        description = "JAAS LoginModule for WebAuthn/FIDO2 biometric authentication with P-256 signatures"
    )
    @interface Config {
        // No configuration needed for basic setup
        // Future: Add ethereum.rpc.url, verifier.contract.address, etc.
    }
    
    /**
     * OSGi component activation.
     * 
     * @param config the OSGi configuration
     */
    @Activate
    protected void activate(Config config) {
        log.info("✅ Web3 Biometric LoginModule Factory activated");
        log.info("   - Control Flag: SUFFICIENT");
        log.info("   - JAAS Realm: jackrabbit.oak");
        log.info("   - Ranking: 150");
        log.info("   - Auto-creates users in /rep:security/rep:authorizables/rep:users/");
        log.info("   - Adds users to 'administrators' group");
    }
    
    /**
     * Creates a new {@link Web3BiometricLoginModule} instance.
     * 
     * <p>Called by Felix JAAS whenever a new login is initiated.</p>
     * 
     * @return a new LoginModule instance
     */
    @NotNull
    @Override
    public LoginModule createLoginModule() {
        log.debug("Creating new Web3BiometricLoginModule instance");
        return new Web3BiometricLoginModule();
    }
}

