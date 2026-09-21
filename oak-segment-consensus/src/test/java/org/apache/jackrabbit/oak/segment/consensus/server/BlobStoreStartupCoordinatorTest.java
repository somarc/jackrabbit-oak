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

import java.io.File;
import java.io.IOException;

import org.apache.jackrabbit.oak.segment.consensus.config.StorageBackendConfig;
import org.apache.jackrabbit.oak.spi.blob.BlobStore;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class BlobStoreStartupCoordinatorTest {

    // Property keys that StorageBackendConfig reads (via backward-compat alias)
    private static final String PROP_BLOBSTORE_TYPE = "blobstore.type";
    private static final String PROP_IPFS_API_ENDPOINT = "ipfs.api.endpoint";

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private final BlobStoreStartupCoordinator coordinator = new BlobStoreStartupCoordinator();

    @After
    public void tearDown() {
        System.clearProperty(PROP_BLOBSTORE_TYPE);
        System.clearProperty(PROP_IPFS_API_ENDPOINT);
        System.clearProperty("oak.blob.backend");
    }

    @Test
    public void testInvalidBlobBackendRejectedAtConfigLoad() {
        System.setProperty(PROP_BLOBSTORE_TYPE, "file");

        try {
            StorageBackendConfig.load();
            fail("Expected IllegalStateException for unknown blob backend");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("file"));
        }
    }

    @Test
    public void testInitializeDefaultsToIpfsWhenNoBackendConfigured() throws Exception {
        // No blobstore.type or oak.blob.backend set — default is ipfs
        GlobalStoreServerComponentFactory componentFactory = mock(GlobalStoreServerComponentFactory.class);
        BlobStore blobStore = mock(BlobStore.class);
        File storeDir = tempFolder.newFolder("segmentstore");
        when(componentFactory.createIpfsBlobStore(anyString(), any(File.class))).thenReturn(blobStore);

        StorageBackendConfig config = StorageBackendConfig.load();
        BlobStoreStartupCoordinator.StartupResult result =
            coordinator.initialize(storeDir, config, componentFactory);

        assertEquals("ipfs", result.getBlobStoreType());
        assertSame(blobStore, result.getBlobStore());
    }

    @Test
    public void testInitializeBuildsIpfsBlobStore() throws Exception {
        System.setProperty(PROP_BLOBSTORE_TYPE, "ipfs");
        System.setProperty(PROP_IPFS_API_ENDPOINT, "/ip4/10.0.0.7/tcp/5001");

        GlobalStoreServerComponentFactory componentFactory = mock(GlobalStoreServerComponentFactory.class);
        BlobStore blobStore = mock(BlobStore.class);
        File storeDir = tempFolder.newFolder("segmentstore");
        when(componentFactory.createIpfsBlobStore("/ip4/10.0.0.7/tcp/5001", storeDir)).thenReturn(blobStore);

        StorageBackendConfig config = StorageBackendConfig.load();
        BlobStoreStartupCoordinator.StartupResult result =
            coordinator.initialize(storeDir, config, componentFactory);

        assertEquals("ipfs", result.getBlobStoreType());
        assertSame(blobStore, result.getBlobStore());
        verify(componentFactory).createIpfsBlobStore("/ip4/10.0.0.7/tcp/5001", storeDir);
    }

    @Test
    public void testInitializeWrapsIpfsBlobStoreFailure() throws Exception {
        System.setProperty(PROP_BLOBSTORE_TYPE, "ipfs");

        RuntimeException cause = new RuntimeException("ipfs unavailable");
        GlobalStoreServerComponentFactory componentFactory = mock(GlobalStoreServerComponentFactory.class);
        when(componentFactory.createIpfsBlobStore(anyString(), any(File.class))).thenThrow(cause);

        StorageBackendConfig config = StorageBackendConfig.load();
        try {
            coordinator.initialize(tempFolder.newFolder("segmentstore-failing"), config, componentFactory);
            fail("Expected IOException");
        } catch (IOException e) {
            assertTrue(e.getMessage().startsWith("IPFS BlobStore initialization failed"));
            assertSame(cause, e.getCause());
        }
    }
}
