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

import org.apache.jackrabbit.oak.segment.consensus.config.StorageBackendConfig;
import org.junit.After;
import org.junit.Test;

import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class DefaultGlobalStoreServerComponentFactoryTest {

    @After
    public void clearProps() {
        for (String prop : new String[] {"ipfs.api.endpoint", "ipfs.api.authorization", "ipfs.api.max-attempts",
                "ipfs.cluster.endpoint", "ipfs.cluster.authorization", "ipfs.encryption.keys",
                "ipfs.network.policy", "ipfs.swarm.probe-address"}) {
            System.clearProperty(prop);
        }
    }

    @Test
    public void testIpfsDataStorePropertiesCarryTheClientSettings() {
        System.setProperty("ipfs.api.endpoint", "/dns4/kubo/tcp/5001");
        System.setProperty("ipfs.api.authorization", "Bearer abc");
        System.setProperty("ipfs.api.max-attempts", "4");

        Properties properties = DefaultGlobalStoreServerComponentFactory.ipfsDataStoreProperties(
            StorageBackendConfig.load());

        assertEquals("/dns4/kubo/tcp/5001", properties.getProperty("ipfsApiEndpoint"));
        assertEquals("Bearer abc", properties.getProperty("ipfsApiAuthorization"));
        assertEquals("10000", properties.getProperty("ipfsConnectTimeoutMillis"));
        assertEquals("60000", properties.getProperty("ipfsRequestTimeoutMillis"));
        assertEquals("4", properties.getProperty("ipfsMaxAttempts"));
    }

    @Test
    public void testNoAuthorizationOrClusterIsSentWhenNoneIsConfigured() {
        Properties properties = DefaultGlobalStoreServerComponentFactory.ipfsDataStoreProperties(
            StorageBackendConfig.load());

        assertFalse(properties.containsKey("ipfsApiAuthorization"));
        assertFalse(properties.containsKey("ipfsClusterEndpoint"));
        assertFalse(properties.containsKey("ipfsEncryptionKeys"));
    }

    @Test
    public void testNetworkPolicyReachesTheDataStore() {
        assertEquals("warn", DefaultGlobalStoreServerComponentFactory.ipfsDataStoreProperties(
            StorageBackendConfig.load()).getProperty("ipfsNetworkPolicy"));

        System.setProperty("ipfs.network.policy", "private");
        System.setProperty("ipfs.swarm.probe-address", "kubo:4001");
        Properties properties = DefaultGlobalStoreServerComponentFactory.ipfsDataStoreProperties(
            StorageBackendConfig.load());

        assertEquals("private", properties.getProperty("ipfsNetworkPolicy"));
        assertEquals("kubo:4001", properties.getProperty("ipfsSwarmProbeAddress"));
    }

    @Test
    public void testEncryptionKeysReachTheDataStore() {
        System.setProperty("ipfs.encryption.keys", "k1;k1:AAAA");

        assertEquals("k1;k1:AAAA", DefaultGlobalStoreServerComponentFactory.ipfsDataStoreProperties(
            StorageBackendConfig.load()).getProperty("ipfsEncryptionKeys"));
    }

    @Test
    public void testClusterSettingsReachTheDataStore() {
        System.setProperty("ipfs.cluster.endpoint", "/dns4/cluster0/tcp/9094");
        System.setProperty("ipfs.cluster.authorization", "Basic abc");

        Properties properties = DefaultGlobalStoreServerComponentFactory.ipfsDataStoreProperties(
            StorageBackendConfig.load());

        assertEquals("/dns4/cluster0/tcp/9094", properties.getProperty("ipfsClusterEndpoint"));
        assertEquals("Basic abc", properties.getProperty("ipfsClusterAuthorization"));
        assertEquals("2", properties.getProperty("ipfsClusterReplicationMin"));
        assertEquals("3", properties.getProperty("ipfsClusterReplicationMax"));
        assertEquals("120000", properties.getProperty("ipfsClusterPinTimeoutMillis"));
    }
}
