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

import org.apache.jackrabbit.oak.segment.consensus.aeron.AeronClusterConfig;
import org.junit.After;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GlobalStoreRuntimeConfigUtilTest {

    @After
    public void tearDown() {
        System.clearProperty("wallet.keystore.path");
        System.clearProperty("consensus.self.url");
        System.clearProperty("consensus.peers");
        System.clearProperty("ethereum.beacon.api.url");
    }

    @Test
    public void testResolveNodeKeystorePathUsesStoreDefault() {
        String path = GlobalStoreRuntimeConfigUtil.resolveNodeKeystorePath("/tmp/oak");

        assertEquals("/tmp/oak/validator-keystore.properties", path);
    }

    @Test
    public void testResolveNodeKeystorePathUsesConfiguredOverride() {
        System.setProperty("wallet.keystore.path", "/secure/validator.properties");

        String path = GlobalStoreRuntimeConfigUtil.resolveNodeKeystorePath("/tmp/oak");

        assertEquals("/secure/validator.properties", path);
    }

    @Test
    public void testIsConfiguredSelfUrlUsesAeronConfig() {
        assertTrue(GlobalStoreRuntimeConfigUtil.isConfiguredSelfUrl(aeronConfig("http://validator-7:8090", null, null)));
    }

    @Test
    public void testIsConfiguredSelfUrlUsesRuntimeProperty() {
        System.setProperty("consensus.self.url", "https://validator.example.com");

        assertTrue(GlobalStoreRuntimeConfigUtil.isConfiguredSelfUrl(null));
    }

    @Test
    public void testIsConfiguredSelfUrlReturnsFalseWhenUnset() {
        assertFalse(GlobalStoreRuntimeConfigUtil.isConfiguredSelfUrl(null));
    }

    @Test
    public void testResolveSelfUrlPrefersAeronConfig() {
        System.setProperty("consensus.self.url", "http://property.example.com:8090");

        String url = GlobalStoreRuntimeConfigUtil.resolveSelfUrl(
            8090,
            aeronConfig("http://validator-3:8090", null, null)
        );

        assertEquals("http://validator-3:8090", url);
    }

    @Test
    public void testResolveSelfUrlFallsBackToConfiguredProperty() {
        System.setProperty("consensus.self.url", "https://public.example.com:443");

        String url = GlobalStoreRuntimeConfigUtil.resolveSelfUrl(8090, null);

        assertEquals("https://public.example.com:443", url);
    }

    @Test
    public void testResolveSelfUrlFallsBackToLocalResolvedUrl() {
        String url = GlobalStoreRuntimeConfigUtil.resolveSelfUrl(8090, null);

        assertTrue(url.startsWith("http://"));
        assertTrue(url.endsWith(":8090"));
    }

    @Test
    public void testResolvePeerUrlsUsesTrimmedAeronConfigPeers() {
        List<String> peerUrls = GlobalStoreRuntimeConfigUtil.resolvePeerUrls(
            aeronConfig(null, new String[] {" http://validator-1:8090 ", "", "http://validator-2:8090"}, null)
        );

        assertEquals(2, peerUrls.size());
        assertEquals("http://validator-1:8090", peerUrls.get(0));
        assertEquals("http://validator-2:8090", peerUrls.get(1));
    }

    @Test
    public void testResolvePeerUrlsFallsBackToConfiguredProperty() {
        System.setProperty("consensus.peers", "http://127.0.0.1:8091, http://127.0.0.1:8092");

        List<String> peerUrls = GlobalStoreRuntimeConfigUtil.resolvePeerUrls(null);

        assertEquals(2, peerUrls.size());
        assertEquals("http://127.0.0.1:8091", peerUrls.get(0));
        assertEquals("http://127.0.0.1:8092", peerUrls.get(1));
    }

    @Test
    public void testResolveBeaconApiUrlPrefersAeronConfig() {
        String url = GlobalStoreRuntimeConfigUtil.resolveBeaconApiUrl(
            aeronConfig(null, null, "https://beacon.example.com/api")
        );

        assertEquals("https://beacon.example.com/api", url);
    }

    @Test
    public void testResolveBeaconApiUrlFallsBackToConfiguredPropertyThenDefault() {
        System.setProperty("ethereum.beacon.api.url", "https://property.example.com/api");
        assertEquals("https://property.example.com/api", GlobalStoreRuntimeConfigUtil.resolveBeaconApiUrl(null));

        System.clearProperty("ethereum.beacon.api.url");
        assertEquals("https://beaconcha.in/api", GlobalStoreRuntimeConfigUtil.resolveBeaconApiUrl(null));
    }

    private static AeronClusterConfig aeronConfig(String selfUrl, String[] peerUrls, String beaconApiUrl) {
        return (AeronClusterConfig) Proxy.newProxyInstance(
            AeronClusterConfig.class.getClassLoader(),
            new Class<?>[]{AeronClusterConfig.class},
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "selfUrl":
                        return selfUrl != null ? selfUrl : method.getDefaultValue();
                    case "peerUrls":
                        return peerUrls != null ? peerUrls : method.getDefaultValue();
                    case "beaconApiUrl":
                        return beaconApiUrl != null ? beaconApiUrl : method.getDefaultValue();
                    case "annotationType":
                        return AeronClusterConfig.class;
                    default:
                        return method.getDefaultValue();
                }
            }
        );
    }
}
