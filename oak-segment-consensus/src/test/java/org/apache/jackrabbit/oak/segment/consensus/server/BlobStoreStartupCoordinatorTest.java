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

    private static final String PROP_BLOBSTORE_TYPE = "blobstore.type";
    private static final String PROP_IPFS_API_ENDPOINT = "ipfs.api.endpoint";

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private final BlobStoreStartupCoordinator coordinator = new BlobStoreStartupCoordinator();

    @After
    public void tearDown() {
        System.clearProperty(PROP_BLOBSTORE_TYPE);
        System.clearProperty(PROP_IPFS_API_ENDPOINT);
    }

    @Test
    public void testInitializeRejectsMissingBlobStoreType() throws Exception {
        try {
            coordinator.initialize(tempFolder.getRoot(), mock(GlobalStoreServerComponentFactory.class));
            fail("Expected IOException");
        } catch (IOException e) {
            assertEquals("blobstore.type is not configured", e.getMessage());
        }
    }

    @Test
    public void testInitializeRejectsUnsupportedBlobStoreType() throws Exception {
        System.setProperty(PROP_BLOBSTORE_TYPE, "file");

        try {
            coordinator.initialize(tempFolder.getRoot(), mock(GlobalStoreServerComponentFactory.class));
            fail("Expected IOException");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("Invalid blobstore.type"));
        }
    }

    @Test
    public void testInitializeBuildsIpfsBlobStore() throws Exception {
        System.setProperty(PROP_BLOBSTORE_TYPE, "ipfs");
        System.setProperty(PROP_IPFS_API_ENDPOINT, "/ip4/10.0.0.7/tcp/5001");

        GlobalStoreServerComponentFactory componentFactory = mock(GlobalStoreServerComponentFactory.class);
        BlobStore blobStore = mock(BlobStore.class);
        File storeDir = tempFolder.newFolder("segmentstore");
        when(componentFactory.createIpfsBlobStore("/ip4/10.0.0.7/tcp/5001", storeDir)).thenReturn(blobStore);

        BlobStoreStartupCoordinator.StartupResult result = coordinator.initialize(storeDir, componentFactory);

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

        try {
            coordinator.initialize(tempFolder.newFolder("segmentstore-failing"), componentFactory);
            fail("Expected IOException");
        } catch (IOException e) {
            assertEquals("IPFS BlobStore initialization failed", e.getMessage());
            assertSame(cause, e.getCause());
        }
    }
}
