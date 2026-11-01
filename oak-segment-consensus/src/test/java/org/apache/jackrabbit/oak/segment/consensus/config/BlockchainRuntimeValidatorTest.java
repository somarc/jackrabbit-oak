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

import org.junit.Test;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class BlockchainRuntimeValidatorTest {

    private static final String RPC = "http://127.0.0.1:8545";
    private static final String CONTRACT = "0x5FbDB2315678afecb367f032d93F642f64180aa3";

    @Test
    public void mockModeNeedsNoChainSettings() {
        BlockchainRuntimeValidator.validate(BlockchainConfig.Mode.MOCK, null, null, null);
    }

    @Test
    public void localAndSepoliaAcceptAWellFormedRuntime() {
        BlockchainRuntimeValidator.validate(BlockchainConfig.Mode.LOCAL, RPC, CONTRACT, null);
        BlockchainRuntimeValidator.validate(BlockchainConfig.Mode.SEPOLIA, "wss://rpc.example.invalid/v3/key",
            CONTRACT, "0x" + "0f".repeat(32));
    }

    @Test
    public void mainnetIsRejected() {
        assertRejected(BlockchainConfig.Mode.MAINNET, RPC, CONTRACT, "0x" + "0f".repeat(32), "MAINNET mode is disabled");
    }

    @Test
    public void localModeRequiresRpcUrlAndContract() {
        assertRejected(BlockchainConfig.Mode.LOCAL, null, CONTRACT, null, "OAK_BLOCKCHAIN_RPC_URL");
        assertRejected(BlockchainConfig.Mode.LOCAL, RPC, " ", null, "explicit Oak payment contract address");
    }

    @Test
    public void rpcUrlMustBeAnAbsoluteHttpOrWsUrl() {
        assertRejected(BlockchainConfig.Mode.SEPOLIA, "localhost:8545", CONTRACT, null, "absolute http(s) or ws(s)");
        assertRejected(BlockchainConfig.Mode.SEPOLIA, "ftp://rpc.example.invalid", CONTRACT, null, "absolute http(s)");
        assertRejected(BlockchainConfig.Mode.SEPOLIA, "http://bad host", CONTRACT, null, "not a valid URI");
    }

    @Test
    public void contractMustBeADeployedWellFormedAddress() {
        assertRejected(BlockchainConfig.Mode.SEPOLIA, RPC, BlockchainRuntimeValidator.ZERO_ADDRESS, null,
            "example and zero-address defaults are not valid");
        assertRejected(BlockchainConfig.Mode.SEPOLIA, RPC,
            BlockchainRuntimeValidator.EXAMPLE_SEPOLIA_CONTRACT.toLowerCase(), null, "example and zero-address");
        assertRejected(BlockchainConfig.Mode.SEPOLIA, RPC, "0xdef", null, "20-byte hex address");
    }

    @Test
    public void configuredCodeHashMustBeAKeccakHash() {
        assertRejected(BlockchainConfig.Mode.LOCAL, RPC, CONTRACT, "0x1234", "32-byte keccak256");
    }

    private static void assertRejected(BlockchainConfig.Mode mode, String rpcUrl, String contract, String codeHash,
                                       String expectedMessage) {
        try {
            BlockchainRuntimeValidator.validate(mode, rpcUrl, contract, codeHash);
            fail("Expected rejection: " + expectedMessage);
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(expectedMessage));
        }
    }
}
