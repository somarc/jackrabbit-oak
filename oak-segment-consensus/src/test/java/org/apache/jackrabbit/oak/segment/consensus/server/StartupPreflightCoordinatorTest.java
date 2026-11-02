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
package org.apache.jackrabbit.oak.segment.consensus.server;

import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.jackrabbit.oak.segment.consensus.config.BlockchainConfig;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class StartupPreflightCoordinatorTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @After
    public void tearDown() {
        System.clearProperty("consensus.mode");
    }

    @Test
    public void testPrepareCreatesMissingDirectory() throws Exception {
        Path storeDir = tempFolder.getRoot().toPath().resolve("missing-store");

        assertEquals(storeDir.toFile(), new StartupPreflightCoordinator().prepare(storeDir.toString()));

        assertTrue(Files.isDirectory(storeDir));
    }

    @Test
    public void testPrepareValidatesBlockchainRuntimeBeforeCreatingStore() throws Exception {
        Path storeDir = tempFolder.getRoot().toPath().resolve("never-created");
        BlockchainConfig config = mock(BlockchainConfig.class);
        when(config.getMode()).thenReturn(BlockchainConfig.Mode.SEPOLIA);
        when(config.getRpcUrl()).thenReturn("https://rpc.example.invalid");
        when(config.getContractAddress()).thenReturn("0x0000000000000000000000000000000000000000");

        try {
            new StartupPreflightCoordinator(() -> config).prepare(storeDir.toString());
            fail("Expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("example and zero-address defaults are not valid"));
        }
        assertFalse(Files.exists(storeDir));
    }

    @Test
    public void testPrepareRejectsNonAeronConsensusMode() throws Exception {
        System.setProperty("consensus.mode", "dag");
        Path storeDir = tempFolder.getRoot().toPath().resolve("segmentstore");

        try {
            new StartupPreflightCoordinator().prepare(storeDir.toString());
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("only supports Aeron Cluster consensus"));
        }
    }
}
