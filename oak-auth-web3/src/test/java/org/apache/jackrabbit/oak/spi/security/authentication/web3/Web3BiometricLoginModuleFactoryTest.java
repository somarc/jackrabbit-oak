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

import org.junit.Test;

import javax.security.auth.spi.LoginModule;
import java.util.Map;

import static org.junit.Assert.*;

public class Web3BiometricLoginModuleFactoryTest {

    @Test
    public void testActivateAndGetModuleOptions() {
        Web3BiometricLoginModuleFactory factory = new Web3BiometricLoginModuleFactory();
        ConfigStub config = new ConfigStub();
        factory.activate(config);

        Map<String, Object> options = factory.getModuleOptions();
        assertEquals(120, options.get(Web3BiometricLoginModuleFactory.CONFIG_CHALLENGE_EXPIRATION));
        assertEquals(false, options.get(Web3BiometricLoginModuleFactory.CONFIG_AUTO_CREATE_USERS));
        assertEquals("content-authors", options.get(Web3BiometricLoginModuleFactory.CONFIG_DEFAULT_GROUP));
        assertEquals(true, options.get(Web3BiometricLoginModuleFactory.CONFIG_REQUIRE_ETHEREUM_VERIFICATION));
        assertEquals("https://rpc.example", options.get(Web3BiometricLoginModuleFactory.CONFIG_ETHEREUM_RPC_URL));
        assertArrayEquals(new String[] {"0xabc.*"}, (String[]) options.get(Web3BiometricLoginModuleFactory.CONFIG_ALLOWED_WALLET_PATTERNS));
        assertEquals(false, options.get(Web3BiometricLoginModuleFactory.CONFIG_ENABLE_METAMASK));
        assertEquals(true, options.get(Web3BiometricLoginModuleFactory.CONFIG_ENABLE_BIOMETRIC));
    }

    @Test
    public void testModifiedUpdatesOptions() {
        Web3BiometricLoginModuleFactory factory = new Web3BiometricLoginModuleFactory();
        factory.activate(new ConfigStub());

        ConfigStub updated = new ConfigStub();
        updated.challengeExpirationSeconds = 42;
        updated.defaultGroup = "admins";
        factory.modified(updated);

        Map<String, Object> options = factory.getModuleOptions();
        assertEquals(42, options.get(Web3BiometricLoginModuleFactory.CONFIG_CHALLENGE_EXPIRATION));
        assertEquals("admins", options.get(Web3BiometricLoginModuleFactory.CONFIG_DEFAULT_GROUP));
    }

    @Test
    public void testCreateLoginModuleUsesOptions() {
        Web3BiometricLoginModuleFactory factory = new Web3BiometricLoginModuleFactory();
        factory.activate(new ConfigStub());

        LoginModule module = factory.createLoginModule();
        assertTrue(module instanceof Web3BiometricLoginModule);

        Web3BiometricLoginModule web3Module = (Web3BiometricLoginModule) module;
        String group = web3Module.getFactoryOption(
            Web3BiometricLoginModuleFactory.CONFIG_DEFAULT_GROUP,
            "default"
        );
        assertEquals("content-authors", group);
    }

    @Test
    public void testDeactivateClearsOptions() {
        Web3BiometricLoginModuleFactory factory = new Web3BiometricLoginModuleFactory();
        factory.activate(new ConfigStub());
        factory.deactivate();
        assertTrue(factory.getModuleOptions().isEmpty());
    }

    private static final class ConfigStub implements Web3BiometricLoginModuleFactory.Config {
        private int challengeExpirationSeconds = 120;
        private boolean autoCreateUsers = false;
        private String defaultGroup = "content-authors";
        private boolean requireEthereumVerification = true;
        private String ethereumRpcUrl = "https://rpc.example";
        private String[] allowedWalletPatterns = {"0xabc.*"};
        private boolean enableMetamask = false;
        private boolean enableBiometric = true;

        @Override
        public int challenge_expiration_seconds() {
            return challengeExpirationSeconds;
        }

        @Override
        public boolean auto_create_users() {
            return autoCreateUsers;
        }

        @Override
        public String default_group() {
            return defaultGroup;
        }

        @Override
        public boolean require_ethereum_verification() {
            return requireEthereumVerification;
        }

        @Override
        public String ethereum_rpc_url() {
            return ethereumRpcUrl;
        }

        @Override
        public String[] allowed_wallet_patterns() {
            return allowedWalletPatterns;
        }

        @Override
        public boolean enable_metamask() {
            return enableMetamask;
        }

        @Override
        public boolean enable_biometric() {
            return enableBiometric;
        }

        @Override
        public String jaas_controlFlag() {
            return "SUFFICIENT";
        }

        @Override
        public int jaas_ranking() {
            return 150;
        }

        @Override
        public String jaas_realmName() {
            return "jackrabbit.oak";
        }

        @Override
        public Class<? extends java.lang.annotation.Annotation> annotationType() {
            return Web3BiometricLoginModuleFactory.Config.class;
        }
    }
}
