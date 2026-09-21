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

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.*;

public class BlockchainConfigTest {

    private static final String PROP_MODE = "oak.blockchain.mode";
    private static final String PROP_CONTRACT = "oak.blockchain.contractAddress";
    private static final String PROP_RPC_URL = "oak.blockchain.rpcUrl";
    private static final String PROP_GAS_PRICE_GWEI = "oak.blockchain.gasPriceGwei";
    private static final String PROP_GAS_WRITE_STANDARD = "oak.blockchain.gas.write.standard";

    @Before
    public void setUp() {
        clearProps();
        BlockchainConfigOverrideRegistry.clear();
        BlockchainConfigSourceRegistry.markFallbackSource();
        BlockchainConfig.reset();
    }

    @After
    public void tearDown() {
        clearProps();
        BlockchainConfigOverrideRegistry.clear();
        BlockchainConfigSourceRegistry.markFallbackSource();
        BlockchainConfig.reset();
    }

    @Test
    public void testDefaultsToMockMode() {
        BlockchainConfig config = BlockchainConfig.getInstance();

        assertEquals(BlockchainConfig.Mode.MOCK, config.getMode());
        assertEquals("mock", config.getNetwork());
        assertNotNull("Default contract should be set", config.getContractAddress());
        assertNull("RPC URL should default to null", config.getRpcUrl());
        assertEquals(3L, config.getGasPriceGwei());
        assertEquals(74_534L, config.getWriteGasUnitsStandard());
    }

    @Test
    public void testSystemPropertyOverridesMode() {
        System.setProperty(PROP_MODE, "sepolia");

        BlockchainConfig config = BlockchainConfig.getInstance();

        assertEquals(BlockchainConfig.Mode.SEPOLIA, config.getMode());
        assertEquals("sepolia", config.getNetwork());
    }

    @Test
    public void testSystemPropertyOverridesContractAddress() {
        System.setProperty(PROP_CONTRACT, "0xdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef");

        BlockchainConfig config = BlockchainConfig.getInstance();

        assertEquals("0xdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef", config.getContractAddress());
    }

    @Test
    public void testSystemPropertyOverridesRpcUrl() {
        System.setProperty(PROP_RPC_URL, "https://example.invalid/rpc");

        BlockchainConfig config = BlockchainConfig.getInstance();

        assertEquals("https://example.invalid/rpc", config.getRpcUrl());
    }

    @Test
    public void testInvalidModeFallsBackToMock() {
        System.setProperty(PROP_MODE, "not-a-real-mode");

        BlockchainConfig config = BlockchainConfig.getInstance();

        assertEquals(BlockchainConfig.Mode.MOCK, config.getMode());
    }

    @Test
    public void testSystemPropertyOverridesGasModel() {
        System.setProperty(PROP_GAS_PRICE_GWEI, "9");
        System.setProperty(PROP_GAS_WRITE_STANDARD, "80000");

        BlockchainConfig config = BlockchainConfig.getInstance();

        assertEquals(9L, config.getGasPriceGwei());
        assertEquals(80_000L, config.getWriteGasUnitsStandard());
    }

    @Test
    public void testOsgiOverrideTakesPrecedenceOverSystemProperty() {
        System.setProperty(PROP_GAS_PRICE_GWEI, "11");
        BlockchainConfigOverrideRegistry.setOverrides(
            Collections.singletonMap(PROP_GAS_PRICE_GWEI, "5")
        );
        BlockchainConfigSourceRegistry.markOsgiSource();

        BlockchainConfig config = BlockchainConfig.getInstance();

        assertEquals(5L, config.getGasPriceGwei());
        assertEquals("osgi-config-admin", BlockchainConfigSourceRegistry.getSource());
    }

    private void clearProps() {
        System.clearProperty(PROP_MODE);
        System.clearProperty(PROP_CONTRACT);
        System.clearProperty(PROP_RPC_URL);
        System.clearProperty(PROP_GAS_PRICE_GWEI);
        System.clearProperty(PROP_GAS_WRITE_STANDARD);
    }
}
