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

import org.apache.jackrabbit.oak.spi.blob.data.DataIdentifier;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Properties;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link IPFSDataStore}.
 * 
 * <p>Tests the DataStore wrapper around IPFSBackend.
 */
public class IPFSDataStoreTest {
    
    private IPFSDataStore dataStore;
    
    @Before
    public void setUp() {
        dataStore = new IPFSDataStore();
    }
    
    @After
    public void tearDown() throws Exception {
        if (dataStore != null) {
            try {
                dataStore.close();
            } catch (Exception e) {
                // Ignore close errors - backend may not be initialized
            }
        }
    }
    
    /**
     * Test default minRecordLength.
     */
    @Test
    public void testDefaultMinRecordLength() {
        // Default is 16KB (16 * 1024 = 16384)
        assertEquals(16 * 1024, dataStore.getMinRecordLength());
    }
    
    /**
     * Test custom minRecordLength.
     */
    @Test
    public void testCustomMinRecordLength() {
        dataStore.setMinRecordLength(32 * 1024);
        assertEquals(32 * 1024, dataStore.getMinRecordLength());
        
        dataStore.setMinRecordLength(4096);
        assertEquals(4096, dataStore.getMinRecordLength());
    }
    
    /**
     * Test getCID returns null before init.
     */
    @Test
    public void testGetCIDBeforeInit() {
        String cid = dataStore.getCID("some-blob-id");
        assertNull(cid);
    }
    
    /**
     * Test getCID with size suffix.
     */
    @Test
    public void testGetCIDWithSizeSuffix() {
        // Oak blob IDs often have size suffix like "ed06f9cb...#22216"
        String cid = dataStore.getCID("ed06f9cb1234567890abcdef#22216");
        assertNull(cid); // No backend initialized
    }
    
    /**
     * Test getCID without size suffix.
     */
    @Test
    public void testGetCIDWithoutSizeSuffix() {
        String cid = dataStore.getCID("ed06f9cb1234567890abcdef");
        assertNull(cid); // No backend initialized
    }
    
    /**
     * Test getBackend returns null before init.
     */
    @Test
    public void testGetBackendBeforeInit() {
        // Backend is created lazily during init
        assertNull(dataStore.getBackend());
    }
    
    /**
     * Test minRecordLength boundary values.
     */
    @Test
    public void testMinRecordLengthBoundaries() {
        // Zero
        dataStore.setMinRecordLength(0);
        assertEquals(0, dataStore.getMinRecordLength());
        
        // Small value
        dataStore.setMinRecordLength(1);
        assertEquals(1, dataStore.getMinRecordLength());
        
        // Large value
        dataStore.setMinRecordLength(Integer.MAX_VALUE);
        assertEquals(Integer.MAX_VALUE, dataStore.getMinRecordLength());
    }
    
    @Test
    public void testCreateBackendWithoutPropertiesLeavesEndpointUnset() {
        IPFSBackend backend = (IPFSBackend) dataStore.createBackend();

        assertNull(backend.getIpfsApiEndpoint());
        assertEquals("/oak/ipfs", backend.getIpfsFilesRoot());
    }

    @Test
    public void testCreateBackendWithPropertiesWithoutEndpointLeavesEndpointUnset() {
        dataStore.setProperties(new Properties());

        IPFSBackend backend = (IPFSBackend) dataStore.createBackend();

        assertNull(backend.getIpfsApiEndpoint());
        assertEquals("/oak/ipfs", backend.getIpfsFilesRoot());
    }

    @Test
    public void testCreateBackendAppliesConfiguredEndpoint() {
        Properties props = new Properties();
        props.setProperty("ipfsApiEndpoint", "/dns4/ipfs.example.com/tcp/5001");
        props.setProperty("ipfsFilesRoot", "/oak/repo-a");
        dataStore.setProperties(props);

        IPFSBackend backend = (IPFSBackend) dataStore.createBackend();

        assertEquals("/dns4/ipfs.example.com/tcp/5001", backend.getIpfsApiEndpoint());
        assertEquals("/oak/repo-a", backend.getIpfsFilesRoot());
    }

    @Test
    public void testEncryptionKeysAreParsedWhenTheBackendIsCreated() {
        String secret = java.util.Base64.getEncoder().encodeToString(new byte[31]);
        Properties props = new Properties();
        props.setProperty(IPFSDataStore.ENCRYPTION_KEYS, "k1;k1:" + secret);
        dataStore.setProperties(props);
        try {
            dataStore.createBackend();
            fail("Expected a 31-byte key to be rejected");
        } catch (IllegalArgumentException e) {
            assertFalse(e.getMessage(), e.getMessage().contains(secret));
        }

        dataStore.setBlobKeyProvider(StaticBlobKeyProvider.fromSpec(
            "k2;k2:" + java.util.Base64.getEncoder().encodeToString(new byte[32])));
        assertNotNull("a provider takes precedence over the spec", dataStore.createBackend());

        dataStore.setBlobKeyProvider(null);
        props.setProperty(IPFSDataStore.ENCRYPTION_KEYS, "k1;k1:" + java.util.Base64.getEncoder().encodeToString(new byte[32]));
        assertNotNull(dataStore.createBackend());
    }

    @Test
    public void testGetCIDAfterBackendCreation() throws Exception {
        IPFSBackend backend = (IPFSBackend) dataStore.createBackend();
        addCidMapping(backend, "blob-id", "QmTestCid");

        assertEquals("QmTestCid", dataStore.getCID("blob-id"));
        assertEquals("QmTestCid", dataStore.getCID("blob-id#128"));
    }

    @Test
    public void testCidResolverReachesTheBackendBeforeAndAfterCreation() throws Exception {
        CidResolver first = identifier -> null;
        dataStore.setCidResolver(first);
        IPFSBackend backend = (IPFSBackend) dataStore.createBackend();
        assertSame(first, resolverOf(backend));

        CidResolver second = identifier -> null;
        dataStore.setCidResolver(second);
        assertSame(second, resolverOf(backend));
    }

    private static Object resolverOf(IPFSBackend backend) throws Exception {
        Field field = IPFSBackend.class.getDeclaredField("cidResolver");
        field.setAccessible(true);
        return field.get(backend);
    }

    @SuppressWarnings("unchecked")
    private static void addCidMapping(IPFSBackend backend, String blobId, String cid) throws Exception {
        Field cidCacheField = IPFSBackend.class.getDeclaredField("cidCache");
        cidCacheField.setAccessible(true);
        Map<DataIdentifier, String> cidCache = (Map<DataIdentifier, String>) cidCacheField.get(backend);
        cidCache.put(new DataIdentifier(blobId), cid);
    }
}
