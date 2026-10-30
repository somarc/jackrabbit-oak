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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class IpfsClusterSettingsTest {

    @Test
    public void testDisabledUnlessAnEndpointIsSet() {
        assertFalse(IpfsClusterSettings.fromProperties(Map.of()).enabled());
        assertFalse(IpfsClusterSettings.disabled().enabled());

        IpfsClusterSettings settings = IpfsClusterSettings.fromProperties(Map.of(
            IpfsClusterSettings.ENDPOINT, "/dns4/cluster0/tcp/9094",
            IpfsClusterSettings.AUTHORIZATION, "Bearer abc",
            IpfsClusterSettings.REPLICATION_MIN, "3",
            IpfsClusterSettings.REPLICATION_MAX, 5,
            IpfsClusterSettings.PIN_TIMEOUT_MILLIS, 1500,
            IpfsClusterSettings.UNPIN_ON_DELETE, "true"));
        assertTrue(settings.enabled());
        assertTrue(settings.unpinOnDelete());
        assertEquals("http://cluster0:9094/", settings.apiBase().toString());
        assertEquals(3, settings.replicationMin());
        assertEquals(5, settings.replicationMax());
        assertEquals(Duration.ofMillis(1500), settings.pinTimeout());
        assertFalse(settings.toString().contains("abc"));
    }

    @Test
    public void testDefaults() {
        IpfsClusterSettings settings = IpfsClusterSettings.fromProperties(Map.of(
            IpfsClusterSettings.ENDPOINT, "https://cluster.internal:9094"));

        assertEquals(IpfsClusterSettings.DEFAULT_REPLICATION_MIN, settings.replicationMin());
        assertEquals(IpfsClusterSettings.DEFAULT_REPLICATION_MAX, settings.replicationMax());
        assertEquals(IpfsClusterSettings.DEFAULT_PIN_TIMEOUT, settings.pinTimeout());
        assertFalse(settings.unpinOnDelete());
        assertEquals("https://cluster.internal:9094/", settings.apiBase().toString());
    }

    @Test
    public void testInvalidSettingsAreRejected() {
        for (Map<String, Object> properties : java.util.List.<Map<String, Object>>of(
                Map.of(IpfsClusterSettings.REPLICATION_MIN, 0),
                Map.of(IpfsClusterSettings.REPLICATION_MIN, 3, IpfsClusterSettings.REPLICATION_MAX, 2),
                Map.of(IpfsClusterSettings.REPLICATION_MIN, 4_294_967_297L),
                Map.of(IpfsClusterSettings.PIN_TIMEOUT_MILLIS, 0),
                Map.of(IpfsClusterSettings.ENDPOINT, "/ip4/1.2.3.4/tcp/9094/foo"),
                Map.of(IpfsClusterSettings.AUTHORIZATION, "Basic x\r\ny"))) {
            try {
                IpfsClusterSettings.fromProperties(properties);
                fail("Expected " + properties.keySet() + " to be rejected");
            } catch (IllegalArgumentException | ArithmeticException e) {
                assertFalse(String.valueOf(e.getMessage()).contains("Basic x"));
            }
        }
    }
}
