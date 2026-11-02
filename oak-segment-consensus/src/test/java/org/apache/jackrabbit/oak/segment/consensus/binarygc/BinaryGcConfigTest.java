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
package org.apache.jackrabbit.oak.segment.consensus.binarygc;

import org.apache.jackrabbit.oak.segment.consensus.config.StorageBackendConfig;
import org.junit.After;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class BinaryGcConfigTest {

    private static final List<String> PROPERTIES = List.of(BinaryGcConfig.ENABLED, BinaryGcConfig.INTERVAL_MS,
        BinaryGcConfig.GRACE_MS, BinaryGcConfig.DELETE_DELAY_MS, "ipfs.cluster.endpoint",
        "ipfs.cluster.unpin-on-delete");

    @After
    public void clear() {
        PROPERTIES.forEach(System::clearProperty);
    }

    @Test
    public void binaryGcIsOnByDefaultWithDailyMarksAndADayOfGrace() {
        BinaryGcConfig config = BinaryGcConfig.fromRuntime();
        assertTrue(config.enabled());
        assertEquals(86_400_000L, config.intervalMs());
        assertEquals(86_400_000L, config.graceMs());
        assertEquals(600_000L, config.deleteDelayMs());
    }

    @Test
    public void settingsAreReadAndMalformedValuesFailStartup() {
        System.setProperty(BinaryGcConfig.ENABLED, " FALSE ");
        System.setProperty(BinaryGcConfig.INTERVAL_MS, "1000");
        System.setProperty(BinaryGcConfig.GRACE_MS, "0");
        System.setProperty(BinaryGcConfig.DELETE_DELAY_MS, "5");
        BinaryGcConfig config = BinaryGcConfig.fromRuntime();
        assertFalse(config.enabled());
        assertEquals(1000L, config.intervalMs());
        assertEquals(0L, config.graceMs());
        assertEquals(5L, config.deleteDelayMs());

        System.setProperty(BinaryGcConfig.ENABLED, "yes");
        assertThrows(IllegalArgumentException.class, BinaryGcConfig::fromRuntime);
        System.setProperty(BinaryGcConfig.ENABLED, "true");
        System.setProperty(BinaryGcConfig.INTERVAL_MS, "daily");
        assertThrows(IllegalArgumentException.class, BinaryGcConfig::fromRuntime);
        System.setProperty(BinaryGcConfig.INTERVAL_MS, "0");
        assertThrows(IllegalArgumentException.class, BinaryGcConfig::fromRuntime);
        System.setProperty(BinaryGcConfig.INTERVAL_MS, "10");
        System.setProperty(BinaryGcConfig.GRACE_MS, "-1");
        assertThrows(IllegalArgumentException.class, BinaryGcConfig::fromRuntime);
    }

    @Test
    public void clusterUnpinIsOffByDefaultAndNeedsAnIpfsCluster() {
        assertFalse(StorageBackendConfig.ipfsClusterUnpinOnDeleteFromRuntime());
        System.setProperty("ipfs.cluster.unpin-on-delete", "true");
        assertFalse("no IPFS Cluster, nothing to unpin", StorageBackendConfig.ipfsClusterUnpinOnDeleteFromRuntime());
        System.setProperty("ipfs.cluster.endpoint", "http://cluster:9094");
        assertTrue(StorageBackendConfig.ipfsClusterUnpinOnDeleteFromRuntime());
        System.setProperty("ipfs.cluster.unpin-on-delete", "maybe");
        assertThrows(IllegalStateException.class, StorageBackendConfig::ipfsClusterUnpinOnDeleteFromRuntime);
    }
}
