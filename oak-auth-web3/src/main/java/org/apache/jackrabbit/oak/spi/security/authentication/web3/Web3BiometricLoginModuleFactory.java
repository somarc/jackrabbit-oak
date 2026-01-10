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
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.AttributeType;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.osgi.service.metatype.annotations.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.security.auth.spi.LoginModule;
import java.util.HashMap;
import java.util.Map;

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
 *   "jaas.ranking": 150,
 *   "challenge.expiration.seconds": 300,
 *   "auto.create.users": true,
 *   "default.group": "administrators",
 *   "allowed.wallet.patterns": ["0x.*"]
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
 * <h2>Configuration Properties</h2>
 * <table>
 *   <tr><th>Property</th><th>Default</th><th>Description</th></tr>
 *   <tr><td>challenge.expiration.seconds</td><td>300</td><td>Challenge validity period</td></tr>
 *   <tr><td>auto.create.users</td><td>true</td><td>Auto-create users on first login</td></tr>
 *   <tr><td>default.group</td><td>administrators</td><td>Group to add new users to</td></tr>
 *   <tr><td>require.ethereum.verification</td><td>false</td><td>Require on-chain verification</td></tr>
 *   <tr><td>ethereum.rpc.url</td><td></td><td>Ethereum RPC endpoint</td></tr>
 *   <tr><td>allowed.wallet.patterns</td><td>0x.*</td><td>Regex patterns for allowed wallets</td></tr>
 * </table>
 * 
 * @see Web3BiometricLoginModule
 * @see org.apache.felix.jaas.LoginModuleFactory
 */
@Component(
    service = LoginModuleFactory.class,
    configurationPolicy = ConfigurationPolicy.OPTIONAL,
    property = {
        "jaas.controlFlag=SUFFICIENT",
        "jaas.realmName=jackrabbit.oak",
        "jaas.ranking:Integer=150"
    }
)
@Designate(ocd = Web3BiometricLoginModuleFactory.Config.class)
public class Web3BiometricLoginModuleFactory implements LoginModuleFactory {
    
    private static final Logger log = LoggerFactory.getLogger(Web3BiometricLoginModuleFactory.class);
    
    // Configuration keys passed to LoginModule
    public static final String CONFIG_CHALLENGE_EXPIRATION = "challenge.expiration.seconds";
    public static final String CONFIG_AUTO_CREATE_USERS = "auto.create.users";
    public static final String CONFIG_DEFAULT_GROUP = "default.group";
    public static final String CONFIG_REQUIRE_ETHEREUM_VERIFICATION = "require.ethereum.verification";
    public static final String CONFIG_ETHEREUM_RPC_URL = "ethereum.rpc.url";
    public static final String CONFIG_ALLOWED_WALLET_PATTERNS = "allowed.wallet.patterns";
    public static final String CONFIG_ENABLE_METAMASK = "enable.metamask";
    public static final String CONFIG_ENABLE_BIOMETRIC = "enable.biometric";
    
    // Defaults
    private static final int DEFAULT_CHALLENGE_EXPIRATION = 300; // 5 minutes
    private static final boolean DEFAULT_AUTO_CREATE_USERS = true;
    private static final String DEFAULT_GROUP = "administrators";
    private static final boolean DEFAULT_REQUIRE_ETHEREUM = false;
    private static final String[] DEFAULT_WALLET_PATTERNS = {"0x[0-9a-fA-F]{40}"};
    
    // Current configuration
    private volatile Map<String, Object> moduleOptions = new HashMap<>();
    
    /**
     * OSGi Configuration for the LoginModule.
     */
    @ObjectClassDefinition(
        name = "Web3 Biometric Authentication LoginModule",
        description = "JAAS LoginModule for WebAuthn/FIDO2 biometric authentication with P-256 signatures and MetaMask wallet authentication"
    )
    @interface Config {
        
        @AttributeDefinition(
            name = "Challenge Expiration (seconds)",
            description = "How long a challenge remains valid for authentication",
            type = AttributeType.INTEGER,
            min = "30",
            max = "3600"
        )
        int challenge_expiration_seconds() default DEFAULT_CHALLENGE_EXPIRATION;
        
        @AttributeDefinition(
            name = "Auto-Create Users",
            description = "Automatically create Oak users on first successful authentication",
            type = AttributeType.BOOLEAN
        )
        boolean auto_create_users() default DEFAULT_AUTO_CREATE_USERS;
        
        @AttributeDefinition(
            name = "Default Group",
            description = "Group to add newly created users to (e.g., 'administrators', 'content-authors')",
            type = AttributeType.STRING
        )
        String default_group() default DEFAULT_GROUP;
        
        @AttributeDefinition(
            name = "Require Ethereum Verification",
            description = "Require on-chain verification of wallet ownership (requires RPC URL)",
            type = AttributeType.BOOLEAN
        )
        boolean require_ethereum_verification() default DEFAULT_REQUIRE_ETHEREUM;
        
        @AttributeDefinition(
            name = "Ethereum RPC URL",
            description = "Ethereum JSON-RPC endpoint for on-chain verification (e.g., https://mainnet.infura.io/v3/YOUR_KEY)",
            type = AttributeType.STRING
        )
        String ethereum_rpc_url() default "";
        
        @AttributeDefinition(
            name = "Allowed Wallet Patterns",
            description = "Regex patterns for allowed wallet addresses (one per line). Default allows all Ethereum addresses.",
            type = AttributeType.STRING,
            cardinality = Integer.MAX_VALUE
        )
        String[] allowed_wallet_patterns() default {"0x[0-9a-fA-F]{40}"};
        
        @AttributeDefinition(
            name = "Enable MetaMask Authentication",
            description = "Allow authentication via MetaMask wallet signatures (secp256k1)",
            type = AttributeType.BOOLEAN
        )
        boolean enable_metamask() default true;
        
        @AttributeDefinition(
            name = "Enable Biometric Authentication",
            description = "Allow authentication via WebAuthn/FIDO2 biometrics (P-256)",
            type = AttributeType.BOOLEAN
        )
        boolean enable_biometric() default true;
        
        @AttributeDefinition(
            name = "JAAS Control Flag",
            description = "JAAS control flag for this LoginModule",
            options = {
                @Option(label = "SUFFICIENT", value = "SUFFICIENT"),
                @Option(label = "REQUIRED", value = "REQUIRED"),
                @Option(label = "REQUISITE", value = "REQUISITE"),
                @Option(label = "OPTIONAL", value = "OPTIONAL")
            }
        )
        String jaas_controlFlag() default "SUFFICIENT";
        
        @AttributeDefinition(
            name = "JAAS Ranking",
            description = "Priority ranking for this LoginModule (higher = earlier in chain)",
            type = AttributeType.INTEGER
        )
        int jaas_ranking() default 150;
        
        @AttributeDefinition(
            name = "JAAS Realm Name",
            description = "JAAS realm this LoginModule belongs to",
            type = AttributeType.STRING
        )
        String jaas_realmName() default "jackrabbit.oak";
    }
    
    /**
     * OSGi component activation.
     * 
     * @param config the OSGi configuration
     */
    @Activate
    protected void activate(Config config) {
        updateConfiguration(config);
        logConfiguration("activated");
    }
    
    /**
     * OSGi component modification (configuration update).
     * 
     * @param config the updated OSGi configuration
     */
    @Modified
    protected void modified(Config config) {
        updateConfiguration(config);
        logConfiguration("modified");
    }
    
    /**
     * OSGi component deactivation.
     */
    @Deactivate
    protected void deactivate() {
        log.info("🔴 Web3 Biometric LoginModule Factory deactivated");
        moduleOptions.clear();
    }
    
    /**
     * Updates internal configuration from OSGi config.
     */
    private void updateConfiguration(Config config) {
        Map<String, Object> options = new HashMap<>();
        
        options.put(CONFIG_CHALLENGE_EXPIRATION, config.challenge_expiration_seconds());
        options.put(CONFIG_AUTO_CREATE_USERS, config.auto_create_users());
        options.put(CONFIG_DEFAULT_GROUP, config.default_group());
        options.put(CONFIG_REQUIRE_ETHEREUM_VERIFICATION, config.require_ethereum_verification());
        options.put(CONFIG_ETHEREUM_RPC_URL, config.ethereum_rpc_url());
        options.put(CONFIG_ALLOWED_WALLET_PATTERNS, config.allowed_wallet_patterns());
        options.put(CONFIG_ENABLE_METAMASK, config.enable_metamask());
        options.put(CONFIG_ENABLE_BIOMETRIC, config.enable_biometric());
        
        this.moduleOptions = options;
    }
    
    /**
     * Logs current configuration.
     */
    private void logConfiguration(String action) {
        log.info("✅ Web3 Biometric LoginModule Factory {}", action);
        log.info("   Configuration:");
        log.info("   - Challenge Expiration: {} seconds", moduleOptions.get(CONFIG_CHALLENGE_EXPIRATION));
        log.info("   - Auto-Create Users: {}", moduleOptions.get(CONFIG_AUTO_CREATE_USERS));
        log.info("   - Default Group: {}", moduleOptions.get(CONFIG_DEFAULT_GROUP));
        log.info("   - Require Ethereum Verification: {}", moduleOptions.get(CONFIG_REQUIRE_ETHEREUM_VERIFICATION));
        log.info("   - MetaMask Enabled: {}", moduleOptions.get(CONFIG_ENABLE_METAMASK));
        log.info("   - Biometric Enabled: {}", moduleOptions.get(CONFIG_ENABLE_BIOMETRIC));
        
        String rpcUrl = (String) moduleOptions.get(CONFIG_ETHEREUM_RPC_URL);
        if (rpcUrl != null && !rpcUrl.isEmpty()) {
            // Mask API key in logs
            String maskedUrl = rpcUrl.replaceAll("([a-zA-Z0-9]{8})[a-zA-Z0-9]+", "$1***");
            log.info("   - Ethereum RPC: {}", maskedUrl);
        }
        
        String[] patterns = (String[]) moduleOptions.get(CONFIG_ALLOWED_WALLET_PATTERNS);
        if (patterns != null && patterns.length > 0) {
            log.info("   - Allowed Wallet Patterns: {}", String.join(", ", patterns));
        }
    }
    
    /**
     * Creates a new {@link Web3BiometricLoginModule} instance.
     * 
     * <p>Called by Felix JAAS whenever a new login is initiated.
     * The module options are passed via the LoginModule's initialize() method.</p>
     * 
     * @return a new LoginModule instance
     */
    @NotNull
    @Override
    public LoginModule createLoginModule() {
        log.debug("Creating new Web3BiometricLoginModule instance with options: {}", moduleOptions);
        Web3BiometricLoginModule module = new Web3BiometricLoginModule();
        // Note: Options are passed via initialize() by the JAAS framework
        // We store them here for potential direct access
        module.setFactoryOptions(moduleOptions);
        return module;
    }
    
    /**
     * Gets the current module options.
     * 
     * @return unmodifiable map of current options
     */
    @NotNull
    public Map<String, Object> getModuleOptions() {
        return Map.copyOf(moduleOptions);
    }
}
