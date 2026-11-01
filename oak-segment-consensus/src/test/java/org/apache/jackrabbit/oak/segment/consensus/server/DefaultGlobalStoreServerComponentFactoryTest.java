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
        for (String prop : new String[] {"ipfs.api.endpoint", "ipfs.api.authorization", "ipfs.api.max-attempts"}) {
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
    public void testNoAuthorizationIsSentWhenNoneIsConfigured() {
        Properties properties = DefaultGlobalStoreServerComponentFactory.ipfsDataStoreProperties(
            StorageBackendConfig.load());

        assertFalse(properties.containsKey("ipfsApiAuthorization"));
    }
}
