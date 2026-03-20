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

import org.apache.jackrabbit.oak.segment.consensus.security.EthereumWallet;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class GlobalStoreServerStartupValidationTest {

    private static final String PROP_BLOBSTORE_TYPE = "blobstore.type";
    private static final String PROP_CONSENSUS_MODE = "consensus.mode";

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @After
    public void tearDown() {
        System.clearProperty(PROP_BLOBSTORE_TYPE);
        System.clearProperty(PROP_CONSENSUS_MODE);
    }

    @Test
    public void testStartRejectsUnsupportedBlobStoreTypeAfterCreatingStoreDirectory() throws Exception {
        System.setProperty(PROP_BLOBSTORE_TYPE, "file");
        Path storeDir = tempFolder.getRoot().toPath().resolve("segmentstore-missing");

        IOException error = expectIoFailure(newServer(storeDir));

        assertTrue(Files.isDirectory(storeDir));
        assertTrue(error.getMessage().contains("Invalid blobstore.type"));
    }

    @Test
    public void testStartWrapsIpfsBlobStoreInitializationFailure() throws Exception {
        System.setProperty(PROP_BLOBSTORE_TYPE, "ipfs");
        RuntimeException cause = new RuntimeException("ipfs unavailable");
        GlobalStoreServer server = newServer(tempFolder.getRoot().toPath().resolve("segmentstore-ipfs"));
        when(serverFactory(server).createIpfsBlobStore(anyString(), org.mockito.ArgumentMatchers.any()))
            .thenThrow(cause);

        IOException error = expectIoFailure(server);

        assertTrue(error.getMessage().contains("IPFS BlobStore initialization failed"));
        assertSame(cause, error.getCause());
    }

    @Test
    public void testStartRejectsNonAeronConsensusMode() throws Exception {
        System.setProperty(PROP_CONSENSUS_MODE, "dag");

        try {
            newServer(tempFolder.getRoot().toPath().resolve("segmentstore-dag")).start();
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("only supports Aeron Cluster consensus"));
        }
    }

    private static IOException expectIoFailure(GlobalStoreServer server) throws Exception {
        try {
            server.start();
            fail("Expected IOException");
            return null;
        } catch (IOException e) {
            return e;
        }
    }

    private static GlobalStoreServerComponentFactory serverFactory(GlobalStoreServer server) throws Exception {
        java.lang.reflect.Field field = GlobalStoreServer.class.getDeclaredField("componentFactory");
        field.setAccessible(true);
        return (GlobalStoreServerComponentFactory) field.get(server);
    }

    private static GlobalStoreServer newServer(Path storeDir) throws Exception {
        GlobalStoreServer server = new GlobalStoreServer(8090, storeDir.toString());
        GlobalStoreServerComponentFactory factory = mock(GlobalStoreServerComponentFactory.class);
        EthereumWallet wallet = mock(EthereumWallet.class);
        when(factory.createEthereumWallet(anyString())).thenReturn(wallet);
        when(wallet.getWalletAddress()).thenReturn("0x1234567890abcdef1234567890abcdef12345678");
        server.setComponentFactory(factory);
        return server;
    }
}
