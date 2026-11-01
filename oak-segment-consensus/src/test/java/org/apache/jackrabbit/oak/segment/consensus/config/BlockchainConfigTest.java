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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class BlockchainConfigTest {

    private static final String PROP_MODE = "oak.blockchain.mode";
    private static final String PROP_CONTRACT = "oak.blockchain.contractAddress";
    private static final String PROP_RPC_URL = "oak.blockchain.rpcUrl";
    private static final String PROP_GAS_PRICE_GWEI = "oak.blockchain.gasPriceGwei";
    private static final String PROP_GAS_WRITE_STANDARD = "oak.blockchain.gas.write.standard";
    private static final String PROP_CODEHASH = "oak.blockchain.contract.codehash";

    @Before
    public void setUp() {
        clearProps();
        RuntimePropertyOverrideRegistry.clear(BlockchainConfigTuningService.NAMESPACE);
        RuntimePropertySourceRegistry.markSource(
            BlockchainConfigTuningService.COMPONENT, BlockchainConfigTuningService.FALLBACK_SOURCE);
        BlockchainConfig.reset();
    }

    @After
    public void tearDown() {
        clearProps();
        RuntimePropertyOverrideRegistry.clear(BlockchainConfigTuningService.NAMESPACE);
        RuntimePropertySourceRegistry.markSource(
            BlockchainConfigTuningService.COMPONENT, BlockchainConfigTuningService.FALLBACK_SOURCE);
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
    public void testUnknownModeIsRejectedWithAllowedValues() {
        System.setProperty(PROP_MODE, "not-a-real-mode");

        try {
            BlockchainConfig.getInstance();
            fail("An unknown mode must not fall back to mock");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("not-a-real-mode"));
            assertTrue(e.getMessage(), e.getMessage().contains("mock, local, sepolia, mainnet"));
        }
    }

    @Test
    public void testUnknownModeFromOsgiOverrideIsRejected() {
        RuntimePropertyOverrideRegistry.setOverrides(BlockchainConfigTuningService.NAMESPACE,
            Collections.singletonMap(PROP_MODE, "sepolia-typo"));

        try {
            BlockchainConfig.getInstance();
            fail("An unknown mode must not fall back to mock");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("sepolia-typo"));
        }
    }

    @Test
    public void testBlankModeMeansUnsetAndDefaultsToMock() {
        assertEquals(BlockchainConfig.Mode.MOCK, BlockchainConfig.Mode.fromString(null));
        assertEquals(BlockchainConfig.Mode.MOCK, BlockchainConfig.Mode.fromString("  "));
        assertEquals(BlockchainConfig.Mode.LOCAL, BlockchainConfig.Mode.fromString(" LOCAL "));
    }

    @Test
    public void testLocalModeIsChainBackedWithLocalChainIdAndClockEpochs() {
        System.setProperty(PROP_MODE, "local");

        BlockchainConfig config = BlockchainConfig.getInstance();

        assertEquals(BlockchainConfig.Mode.LOCAL, config.getMode());
        assertFalse(config.isMockMode());
        assertTrue(config.getMode().isChainBacked());
        assertTrue(config.getMode().usesLocalClockEpochs());
        assertEquals(31337L, config.getMode().getExpectedChainId());
    }

    @Test
    public void testExpectedChainIdsAndConfirmationDefaultsPerMode() {
        assertEquals(0L, BlockchainConfig.Mode.MOCK.getExpectedChainId());
        assertEquals(31337L, BlockchainConfig.Mode.LOCAL.getExpectedChainId());
        assertEquals(11155111L, BlockchainConfig.Mode.SEPOLIA.getExpectedChainId());
        assertEquals(1L, BlockchainConfig.Mode.MAINNET.getExpectedChainId());

        assertEquals(1, BlockchainConfig.Mode.LOCAL.getDefaultRequiredConfirmations());
        assertEquals(3, BlockchainConfig.Mode.SEPOLIA.getDefaultRequiredConfirmations());
        assertTrue(BlockchainConfig.Mode.MAINNET.usesFinalizedTag());
        assertFalse(BlockchainConfig.Mode.SEPOLIA.usesFinalizedTag());
        assertTrue(BlockchainConfig.Mode.MAINNET.requiresContractCodeHash());
        assertFalse(BlockchainConfig.Mode.LOCAL.requiresContractCodeHash());
        assertFalse(BlockchainConfig.Mode.SEPOLIA.usesLocalClockEpochs());
    }

    @Test
    public void testSystemPropertySetsContractCodeHash() {
        String hash = "0x" + "ab".repeat(32);
        System.setProperty(PROP_CODEHASH, hash);

        assertEquals(hash, BlockchainConfig.getInstance().getContractCodeHash());
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
        RuntimePropertyOverrideRegistry.setOverrides(BlockchainConfigTuningService.NAMESPACE,
            Collections.singletonMap(PROP_GAS_PRICE_GWEI, "5")
        );
        RuntimePropertySourceRegistry.markSource(BlockchainConfigTuningService.COMPONENT, "osgi-config-admin");

        BlockchainConfig config = BlockchainConfig.getInstance();

        assertEquals(5L, config.getGasPriceGwei());
        assertEquals("osgi-config-admin", BlockchainConfigIntrospection.source());
    }

    @Test
    public void testTuningServicePublishesOverridesThroughTheSharedRegistry() {
        BlockchainConfigTuningConfig osgiConfig = mock(BlockchainConfigTuningConfig.class);
        when(osgiConfig.gas_price_gwei()).thenReturn(7L);
        BlockchainConfigTuningService service = new BlockchainConfigTuningService();

        service.activate(osgiConfig);
        assertEquals("7", RuntimePropertyOverrideRegistry.get(PROP_GAS_PRICE_GWEI));
        assertEquals(7L, BlockchainConfig.getInstance().getGasPriceGwei());
        assertEquals("osgi-config-admin", BlockchainConfigIntrospection.source());

        service.deactivate();
        assertNull(RuntimePropertyOverrideRegistry.get(PROP_GAS_PRICE_GWEI));
        assertEquals("env-or-system-properties", BlockchainConfigIntrospection.source());
    }

    private void clearProps() {
        System.clearProperty(PROP_MODE);
        System.clearProperty(PROP_CONTRACT);
        System.clearProperty(PROP_RPC_URL);
        System.clearProperty(PROP_GAS_PRICE_GWEI);
        System.clearProperty(PROP_GAS_WRITE_STANDARD);
        System.clearProperty(PROP_CODEHASH);
    }
}
