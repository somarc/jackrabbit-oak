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

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.jackrabbit.oak.segment.consensus.aeron.AeronClusterConfig;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class StartupPreflightCoordinatorTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @After
    public void tearDown() {
        System.clearProperty("consensus.mode");
        System.clearProperty("consensus.aeron.standby.bootstrap.enabled");
        System.clearProperty("bootstrap.primary.host");
        System.clearProperty("bootstrap.primary.port");
    }

    @Test
    public void testPrepareCreatesMissingDirectoryAndMarksItEmpty() throws Exception {
        Path storeDir = tempFolder.getRoot().toPath().resolve("missing-store");

        StartupPreflightCoordinator.PreflightResult result =
            new StartupPreflightCoordinator(new BootstrapPreflightPlanner(url -> false))
                .prepare(storeDir.toString(), 8090, aeronConfig(new String[0]));

        assertTrue(Files.isDirectory(storeDir));
        assertTrue(result.isAeronMode());
        assertTrue(result.isDirectoryEmpty());
        assertFalse(result.needsBootstrapBeforeBuild());
        assertEquals("", result.getVerifiedBootstrapPrimaryHost());
        assertEquals(0, result.getVerifiedBootstrapPrimaryPort());
    }

    @Test
    public void testPrepareRejectsNonAeronConsensusMode() throws Exception {
        System.setProperty("consensus.mode", "dag");
        Path storeDir = tempFolder.getRoot().toPath().resolve("segmentstore");

        try {
            new StartupPreflightCoordinator().prepare(storeDir.toString(), 8090, aeronConfig(new String[0]));
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("only supports Aeron Cluster consensus"));
        }
    }

    @Test
    public void testPrepareDetectsExistingStoreFromJournalFile() throws Exception {
        Path storeDir = tempFolder.newFolder("existing-store").toPath();
        Files.writeString(storeDir.resolve("journal.log"), "record-1");

        StartupPreflightCoordinator.PreflightResult result =
            new StartupPreflightCoordinator(new BootstrapPreflightPlanner(url -> {
                fail("Preflight probe should not be consulted for non-empty stores");
                return false;
            })).prepare(storeDir.toString(), 8090, aeronConfig(new String[] {"http://validator-1:8090"}));

        assertFalse(result.isDirectoryEmpty());
        assertFalse(result.needsBootstrapBeforeBuild());
        assertEquals("", result.getVerifiedBootstrapPrimaryHost());
        assertEquals(0, result.getVerifiedBootstrapPrimaryPort());
    }

    @Test
    public void testPrepareUsesReachablePeerWhenStandbyBootstrapEnabled() throws Exception {
        System.setProperty("consensus.aeron.standby.bootstrap.enabled", "true");
        Path storeDir = tempFolder.newFolder("empty-store-peer").toPath();

        StartupPreflightCoordinator.PreflightResult result =
            new StartupPreflightCoordinator(new BootstrapPreflightPlanner(
                url -> "http://validator-1:8090/health".equals(url)
            )).prepare(storeDir.toString(), 8090, aeronConfig(new String[] {"http://validator-1:8090"}));

        assertTrue(result.isDirectoryEmpty());
        assertTrue(result.needsBootstrapBeforeBuild());
        assertEquals("validator-1", result.getVerifiedBootstrapPrimaryHost());
        assertEquals(8091, result.getVerifiedBootstrapPrimaryPort());
    }

    @Test
    public void testPrepareUsesConfiguredBootstrapPrimaryFallback() throws Exception {
        System.setProperty("consensus.aeron.standby.bootstrap.enabled", "true");
        System.setProperty("bootstrap.primary.host", "bootstrap-node");
        System.setProperty("bootstrap.primary.port", "9001");
        Path storeDir = tempFolder.newFolder("empty-store-config").toPath();

        StartupPreflightCoordinator.PreflightResult result =
            new StartupPreflightCoordinator(new BootstrapPreflightPlanner(
                url -> "http://bootstrap-node:9000/health".equals(url)
            )).prepare(storeDir.toString(), 8090, aeronConfig(new String[0]));

        assertTrue(result.isDirectoryEmpty());
        assertTrue(result.needsBootstrapBeforeBuild());
        assertEquals("bootstrap-node", result.getVerifiedBootstrapPrimaryHost());
        assertEquals(9001, result.getVerifiedBootstrapPrimaryPort());
    }

    private static AeronClusterConfig aeronConfig(String[] peerUrls) {
        return (AeronClusterConfig) Proxy.newProxyInstance(
            AeronClusterConfig.class.getClassLoader(),
            new Class<?>[] {AeronClusterConfig.class},
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "peerUrls":
                        return peerUrls != null ? peerUrls : method.getDefaultValue();
                    case "annotationType":
                        return AeronClusterConfig.class;
                    default:
                        return method.getDefaultValue();
                }
            }
        );
    }
}
