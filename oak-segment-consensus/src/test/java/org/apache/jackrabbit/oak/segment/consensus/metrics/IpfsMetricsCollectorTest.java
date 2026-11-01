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
package org.apache.jackrabbit.oak.segment.consensus.metrics;

import io.prometheus.client.CollectorRegistry;
import org.apache.jackrabbit.oak.blob.cloud.ipfs.IPFSDataStore;
import org.junit.After;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class IpfsMetricsCollectorTest {

    @After
    public void unbind() {
        IpfsMetricsCollector.INSTANCE.source(() -> null);
    }

    @Test
    public void testCountsAreExportedFromTheDataStore() {
        IPFSDataStore ipfs = mock(IPFSDataStore.class);
        when(ipfs.getStatistics()).thenReturn(Map.of("writes", 3L, "writtenBytes", 4096L, "clusterPins", 3L,
            "clusterPinMillis", 1500L));
        IpfsMetricsCollector.INSTANCE.source(() -> ipfs);

        CollectorRegistry registry = CollectorRegistry.defaultRegistry;
        assertEquals(3.0, registry.getSampleValue("oak_ipfs_writes_total"), 0);
        assertEquals(4096.0, registry.getSampleValue("oak_ipfs_written_bytes_total"), 0);
        assertEquals(3.0, registry.getSampleValue("oak_ipfs_cluster_pins_total"), 0);
        assertEquals(1.5, registry.getSampleValue("oak_ipfs_cluster_pin_seconds_total"), 0);
        assertNull(registry.getSampleValue("oak_ipfs_reads_total"));
    }

    @Test
    public void testNothingIsExportedWithoutAnIpfsDataStore() {
        assertNull(CollectorRegistry.defaultRegistry.getSampleValue("oak_ipfs_writes_total"));
    }
}
