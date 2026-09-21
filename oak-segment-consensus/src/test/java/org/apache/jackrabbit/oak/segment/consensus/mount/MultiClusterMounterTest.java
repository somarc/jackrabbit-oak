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
package org.apache.jackrabbit.oak.segment.consensus.mount;

import java.io.Closeable;
import java.io.IOException;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;
import java.util.Dictionary;

import org.apache.jackrabbit.oak.plugins.memory.MemoryNodeStore;
import org.apache.jackrabbit.oak.segment.consensus.registry.ClusterRegistration;
import org.apache.jackrabbit.oak.segment.consensus.registry.ShardRegistry;
import org.apache.jackrabbit.oak.spi.state.NodeStore;
import org.junit.Test;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

public class MultiClusterMounterTest {

    @Test
    public void testMountDoesNothingWhenNoRemoteClustersExist() {
        BundleContext bundleContext = mock(BundleContext.class);
        ShardRegistry shardRegistry = mock(ShardRegistry.class);
        when(shardRegistry.getRemoteClusters("0xlocal")).thenReturn(Collections.<ClusterRegistration>emptyList());

        MultiClusterMounter mounter = new MultiClusterMounter(
            bundleContext,
            new MemoryNodeStore(),
            shardRegistry,
            "0xlocal",
            (endpoint, mountName) -> new MemoryNodeStore()
        );

        mounter.mount();

        assertFalse(mounter.isMounted());
        assertEquals(0, mounter.getRemoteMountCount());
        verifyNoInteractions(bundleContext);
    }

    @Test
    public void testMountRegistersCompositeAndClosesRemoteStoresOnShutdown() {
        BundleContext bundleContext = mock(BundleContext.class);
        @SuppressWarnings("unchecked")
        ServiceRegistration<NodeStore> registration = (ServiceRegistration<NodeStore>) mock(ServiceRegistration.class);
        doReturn(registration).when(bundleContext).registerService(
            eq(NodeStore.class),
            any(NodeStore.class),
            any(Dictionary.class)
        );

        ShardRegistry shardRegistry = mock(ShardRegistry.class);
        when(shardRegistry.getRemoteClusters("0xlocal")).thenReturn(Arrays.asList(
            registration("0x1111111111111111111111111111111111111111", 0x100, 0x1FF, "http://cluster-a:8090"),
            registration("0x2222222222222222222222222222222222222222", 0x200, 0x2FF, "http://cluster-b:8090")
        ));

        CloseableMemoryNodeStore clusterA = new CloseableMemoryNodeStore();
        CloseableMemoryNodeStore clusterB = new CloseableMemoryNodeStore();
        MultiClusterMounter mounter = new MultiClusterMounter(
            bundleContext,
            new MemoryNodeStore(),
            shardRegistry,
            "0xlocal",
            (endpoint, mountName) -> endpoint.contains("cluster-a") ? clusterA : clusterB
        );

        mounter.mount();

        assertTrue(mounter.isMounted());
        assertEquals(2, mounter.getRemoteMountCount());
        verify(bundleContext).registerService(eq(NodeStore.class), any(NodeStore.class), any(Dictionary.class));

        mounter.shutdown();

        verify(registration).unregister();
        assertTrue(clusterA.closed);
        assertTrue(clusterB.closed);
        assertFalse(mounter.isMounted());
        assertEquals(0, mounter.getRemoteMountCount());
    }

    @Test
    public void testMountGracefullySkipsClustersWithoutEndpointOrFailedFactory() {
        BundleContext bundleContext = mock(BundleContext.class);
        @SuppressWarnings("unchecked")
        ServiceRegistration<NodeStore> registration = (ServiceRegistration<NodeStore>) mock(ServiceRegistration.class);
        doReturn(registration).when(bundleContext).registerService(
            eq(NodeStore.class),
            any(NodeStore.class),
            any(Dictionary.class)
        );

        ShardRegistry shardRegistry = mock(ShardRegistry.class);
        when(shardRegistry.getRemoteClusters("0xlocal")).thenReturn(Arrays.asList(
            registration("0x3333333333333333333333333333333333333333", 0x300, 0x3FF, "http://cluster-good:8090"),
            registration("0x4444444444444444444444444444444444444444", 0x400, 0x4FF, null),
            registration("0x5555555555555555555555555555555555555555", 0x500, 0x5FF, "http://cluster-bad:8090")
        ));

        CloseableMemoryNodeStore goodCluster = new CloseableMemoryNodeStore();
        MultiClusterMounter mounter = new MultiClusterMounter(
            bundleContext,
            new MemoryNodeStore(),
            shardRegistry,
            "0xlocal",
            (endpoint, mountName) -> {
                if (endpoint.contains("cluster-bad")) {
                    throw new IllegalStateException("boom");
                }
                return goodCluster;
            }
        );

        mounter.mount();

        assertTrue(mounter.isMounted());
        assertEquals(1, mounter.getRemoteMountCount());
        verify(bundleContext).registerService(eq(NodeStore.class), any(NodeStore.class), any(Dictionary.class));

        mounter.shutdown();
        assertTrue(goodCluster.closed);
    }

    private static ClusterRegistration registration(String wallet, int start, int end, String endpoint) {
        return new ClusterRegistration(wallet, start, end, BigInteger.ONE, true, endpoint);
    }

    private static final class CloseableMemoryNodeStore extends MemoryNodeStore implements Closeable {
        private boolean closed;

        @Override
        public void close() throws IOException {
            closed = true;
        }
    }
}
