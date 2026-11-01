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
package org.apache.jackrabbit.oak.blob.cloud.ipfs;

import org.junit.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class IpfsClientSettingsTest {

    @Test
    public void testEndpointForms() {
        assertEquals("http://127.0.0.1:5001/api/v0/", IpfsClientSettings.of(null).apiBase().toString());
        assertEquals("http://10.0.0.5:5001/api/v0/", IpfsClientSettings.of("/ip4/10.0.0.5/tcp/5001").apiBase().toString());
        assertEquals("http://[::1]:5001/api/v0/", IpfsClientSettings.of("/ip6/::1/tcp/5001").apiBase().toString());
        assertEquals("http://kubo:5001/api/v0/", IpfsClientSettings.of("/dns4/kubo/tcp/5001").apiBase().toString());
        assertEquals("https://ipfs.internal:443/api/v0/",
            IpfsClientSettings.of("/dns/ipfs.internal/tcp/443/https").apiBase().toString());
        assertEquals("https://ipfs.internal:443/api/v0/",
            IpfsClientSettings.of("/dns6/ipfs.internal/tcp/443/tls/http").apiBase().toString());
        assertEquals("https://proxy.example/ipfs/api/v0/",
            IpfsClientSettings.of("https://proxy.example/ipfs").apiBase().toString());
        assertEquals("http://kubo:5001/api/v0/", IpfsClientSettings.of("http://kubo:5001/").apiBase().toString());
        assertEquals("http://kubo:5001/api/v0/", IpfsClientSettings.of("http://kubo:5001/api/v0/").apiBase().toString());
        assertEquals("http://kubo:5001/api/v0/", IpfsClientSettings.of("/dns4/kubo/tcp/5001/http").apiBase().toString());
        assertEquals("https://kubo:443/api/v0/",
            IpfsClientSettings.of("/dns4/kubo/tcp/443/https/p2p/12D3KooWExample").apiBase().toString());
        assertEquals("https://kubo:443/api/v0/",
            IpfsClientSettings.of("/dns/kubo/tcp/443/tls/sni/kubo/http").apiBase().toString());
    }

    @Test
    public void testUnsupportedEndpointsAreRejected() {
        for (String endpoint : new String[] {"/unix/tmp/ipfs.sock", "/ip4/1.2.3.4/udp/5001", "kubo:5001",
                "/ip4/1.2.3.4/tcp/443/foo", "/ip4/1.2.3.4/tcp/443/tls/sni//http", "/ip4/1.2.3.4/tcp/0",
                "/ip4//tcp/5001", "/ip6/fe80::1%en0/tcp/5001"}) {
            try {
                IpfsClientSettings.of(endpoint).apiBase();
                fail("Expected " + endpoint + " to be rejected");
            } catch (IllegalArgumentException e) {
                assertTrue(e.getMessage().contains(endpoint));
            }
        }
    }

    @Test
    public void testUrlsMayNotCarryCredentialsQueriesOrFragments() {
        for (String endpoint : new String[] {"http://user:pw@kubo:5001", "http://kubo:5001/?x=1", "http://kubo:5001/#f"}) {
            try {
                IpfsClientSettings.of(endpoint).apiBase();
                fail("Expected " + endpoint + " to be rejected");
            } catch (IllegalArgumentException e) {
                assertFalse(e.getMessage().contains("pw"));
            }
        }
    }

    @Test
    public void testControlCharactersInTheAuthorizationAreRejectedWithoutEchoingIt() {
        try {
            new IpfsClientSettings(null, "Bearer sec\r\nX-Evil: 1", Duration.ofSeconds(1), Duration.ofSeconds(1), 1);
            fail("Expected a header-injection attempt to be rejected");
        } catch (IllegalArgumentException e) {
            assertFalse(e.getMessage().contains("sec"));
        }
    }

    @Test
    public void testFromPropertiesAcceptsStringsAndNumbers() {
        IpfsClientSettings settings = IpfsClientSettings.fromProperties(Map.of(
            IpfsClientSettings.ENDPOINT, "/ip4/10.0.0.5/tcp/5001",
            IpfsClientSettings.AUTHORIZATION, "Bearer abc",
            IpfsClientSettings.CONNECT_TIMEOUT_MILLIS, 1500,
            IpfsClientSettings.REQUEST_TIMEOUT_MILLIS, "2500",
            IpfsClientSettings.MAX_ATTEMPTS, 5));

        assertEquals("/ip4/10.0.0.5/tcp/5001", settings.endpoint());
        assertEquals("Bearer abc", settings.authorization());
        assertEquals(Duration.ofMillis(1500), settings.connectTimeout());
        assertEquals(Duration.ofMillis(2500), settings.requestTimeout());
        assertEquals(5, settings.maxAttempts());
    }

    @Test
    public void testDefaultsAndBlankValues() {
        IpfsClientSettings settings = IpfsClientSettings.fromProperties(Map.of(
            IpfsClientSettings.AUTHORIZATION, "  ", IpfsClientSettings.MAX_ATTEMPTS, ""));

        assertEquals(IpfsClientSettings.DEFAULT_ENDPOINT, settings.endpoint());
        assertNull(settings.authorization());
        assertEquals(IpfsClientSettings.DEFAULT_CONNECT_TIMEOUT, settings.connectTimeout());
        assertEquals(IpfsClientSettings.DEFAULT_REQUEST_TIMEOUT, settings.requestTimeout());
        assertEquals(IpfsClientSettings.DEFAULT_MAX_ATTEMPTS, settings.maxAttempts());
        assertEquals("/ip4/1.2.3.4/tcp/1", settings.withEndpoint("/ip4/1.2.3.4/tcp/1").endpoint());
    }

    @Test
    public void testInvalidValuesAreRejected() {
        for (Map<String, Object> properties : java.util.List.<Map<String, Object>>of(
                Map.of(IpfsClientSettings.MAX_ATTEMPTS, 0),
                Map.of(IpfsClientSettings.MAX_ATTEMPTS, 11),
                Map.of(IpfsClientSettings.MAX_ATTEMPTS, 4_294_967_297L),
                Map.of(IpfsClientSettings.REQUEST_TIMEOUT_MILLIS, -1),
                Map.of(IpfsClientSettings.CONNECT_TIMEOUT_MILLIS, 0),
                Map.of(IpfsClientSettings.MAX_ATTEMPTS, "three"))) {
            try {
                IpfsClientSettings.fromProperties(properties);
                fail("Expected " + properties + " to be rejected");
            } catch (IllegalArgumentException e) {
                // expected
            }
        }
    }

    @Test
    public void testToStringRedactsTheAuthorization() {
        IpfsClientSettings settings = new IpfsClientSettings(null, "Bearer secret-token",
            Duration.ofSeconds(1), Duration.ofSeconds(1), 1);

        assertFalse(settings.toString().contains("secret-token"));
        assertTrue(settings.toString().contains("<redacted>"));
        assertTrue(IpfsClientSettings.of(null).toString().contains("authorization=none"));
    }
}
