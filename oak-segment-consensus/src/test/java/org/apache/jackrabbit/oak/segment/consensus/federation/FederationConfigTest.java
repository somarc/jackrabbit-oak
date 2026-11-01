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
package org.apache.jackrabbit.oak.segment.consensus.federation;

import org.junit.Test;

import java.nio.file.Paths;

import static org.apache.jackrabbit.oak.segment.consensus.federation.FederationFixtures.ENTERPRISE_A;
import static org.apache.jackrabbit.oak.segment.consensus.federation.FederationFixtures.charterA;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class FederationConfigTest {

    private static final String REGISTRY = "0x" + "e1".repeat(20);

    @Test
    public void choosesTheDirectorySourceFromTheCharter() {
        assertNull(new FederationConfig(null, null, 1000L).directorySource());
        assertNull(new FederationConfig(charterA(), null, 1000L).directorySource());
        assertTrue(new FederationConfig(charterA(), Paths.get("/tmp/directory.json"), 1000L).directorySource()
            instanceof StaticFileDirectorySource);
        FederationConfig registry = new FederationConfig(Charter.of(ENTERPRISE_A, "local", 31337L, REGISTRY), null,
            1000L, "http://127.0.0.1:8545", "");
        registry.validate();
        assertTrue(registry.directorySource() instanceof RegistryDirectorySource);
    }

    @Test
    public void aRegistryCharterNeedsAnRpcEndpointAndOnMainnetAPinnedCodeHash() {
        Charter local = Charter.of(ENTERPRISE_A, "local", 31337L, REGISTRY);
        assertThrows(IllegalArgumentException.class, () -> new FederationConfig(local, null, 1000L).validate());
        assertThrows(IllegalArgumentException.class, () -> new FederationConfig(local,
            Paths.get("/tmp/directory.json"), 1000L, "http://127.0.0.1:8545", "").validate());
        Charter mainnet = Charter.of(ENTERPRISE_A, "mainnet", 1L, REGISTRY);
        assertThrows(IllegalArgumentException.class,
            () -> new FederationConfig(mainnet, null, 1000L, "https://rpc.example", "").validate());
        new FederationConfig(mainnet, null, 1000L, "https://rpc.example", "0x" + "ab".repeat(32)).validate();
        new FederationConfig(charterA(), Paths.get("/tmp/directory.json"), 1000L).validate();
    }
}
