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

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AeronClusterBootstrapPlanTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void createUsesSelfOnlyForFreshClusterWithoutConfiguredHostnames() throws Exception {
        File storeDir = tempFolder.newFolder("store");

        AeronClusterBootstrapPlan plan = AeronClusterBootstrapPlan.create(
            0,
            "http://node-a:8080",
            Arrays.asList("http://node-b:8081", "http://node-c:8082"),
            storeDir.getAbsolutePath(),
            ""
        );

        assertFalse(plan.hasExistingCluster);
        assertEquals(AeronClusterBootstrapPlan.StartupMode.FRESH_SELF_ONLY, plan.startupMode);
        assertEquals(Arrays.asList("node-a"), plan.hostnames);
        assertEquals("http://node-a:8080", plan.nodeIdToUrl.get(0));
        assertEquals("http://node-b:8081", plan.nodeIdToUrl.get(1));
        assertEquals("http://node-c:8082", plan.nodeIdToUrl.get(2));
        assertEquals("node-a", plan.clientHostname);
    }

    @Test
    public void createUsesConfiguredHostnamesForFreshCluster() throws Exception {
        File storeDir = tempFolder.newFolder("store");

        AeronClusterBootstrapPlan plan = AeronClusterBootstrapPlan.create(
            1,
            "http://node-a:8080",
            Arrays.asList("http://node-b:8081"),
            storeDir.getAbsolutePath(),
            "node-a,node-b,node-c"
        );

        assertFalse(plan.hasExistingCluster);
        assertEquals(AeronClusterBootstrapPlan.StartupMode.FRESH_CONFIGURED, plan.startupMode);
        assertEquals(Arrays.asList("node-a", "node-b", "node-c"), plan.hostnames);
    }

    @Test
    public void createDiscoversHostnamesForExistingCluster() throws Exception {
        File storeDir = tempFolder.newFolder("store");
        File clusterDir = new File(storeDir, "aeron-cluster-node-2/cluster");
        assertTrue(clusterDir.mkdirs());
        Files.write(new File(clusterDir, "snapshot").toPath(), "x".getBytes(StandardCharsets.UTF_8));

        AeronClusterBootstrapPlan plan = AeronClusterBootstrapPlan.create(
            2,
            "http://node-a:8080",
            Arrays.asList("http://node-b:8081", "http://node-b:8081", "http://node-c:8082"),
            storeDir.getAbsolutePath(),
            ""
        );

        assertTrue(plan.hasExistingCluster);
        assertEquals(AeronClusterBootstrapPlan.StartupMode.EXISTING_CLUSTER, plan.startupMode);
        assertEquals(Arrays.asList("node-a", "node-b", "node-c"), plan.hostnames);
        assertTrue(plan.clusterDirExists);
        assertEquals(1, plan.clusterDirFileCount);
    }

    @Test
    public void createLetsConfiguredHostnamesOverrideExistingClusterDiscovery() throws Exception {
        File storeDir = tempFolder.newFolder("store");
        File clusterDir = new File(storeDir, "aeron-cluster-node-3/cluster");
        assertTrue(clusterDir.mkdirs());
        Files.write(new File(clusterDir, "snapshot").toPath(), "x".getBytes(StandardCharsets.UTF_8));

        AeronClusterBootstrapPlan plan = AeronClusterBootstrapPlan.create(
            3,
            "http://node-a:8080",
            Arrays.asList("http://node-b:8081"),
            storeDir.getAbsolutePath(),
            "configured-a,configured-b"
        );

        assertTrue(plan.hasExistingCluster);
        assertEquals(AeronClusterBootstrapPlan.StartupMode.EXISTING_CLUSTER, plan.startupMode);
        assertEquals(Arrays.asList("configured-a", "configured-b"), plan.hostnames);
    }

    @Test
    public void createFallsBackToLocalhostForInvalidSelfUrl() throws Exception {
        File storeDir = tempFolder.newFolder("store");

        AeronClusterBootstrapPlan plan = AeronClusterBootstrapPlan.create(
            0,
            "not-a-url",
            Arrays.asList("http://node-b:8081"),
            storeDir.getAbsolutePath(),
            ""
        );

        assertEquals("localhost", plan.clientHostname);
        assertEquals("not-a-url", plan.nodeIdToUrl.get(0));
    }
}
