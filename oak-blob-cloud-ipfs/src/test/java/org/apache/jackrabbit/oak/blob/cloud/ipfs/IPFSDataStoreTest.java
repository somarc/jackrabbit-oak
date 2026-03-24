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

import org.apache.jackrabbit.core.data.DataIdentifier;
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
     * Test setProperties with endpoint.
     */
    @Test
    public void testSetPropertiesWithEndpoint() {
        Properties props = new Properties();
        props.setProperty("ipfsApiEndpoint", "/ip4/192.168.1.100/tcp/5001");
        
        dataStore.setProperties(props);
        
        // Endpoint should be retrievable
        assertEquals("/ip4/192.168.1.100/tcp/5001", dataStore.getIpfsApiEndpoint());
    }
    
    /**
     * Test setIpfsApiEndpoint directly.
     */
    @Test
    public void testSetIpfsApiEndpointDirectly() {
        dataStore.setIpfsApiEndpoint("/ip4/127.0.0.1/tcp/5001");
        assertEquals("/ip4/127.0.0.1/tcp/5001", dataStore.getIpfsApiEndpoint());
    }
    
    /**
     * Test setIpfsApiEndpoint creates properties if null.
     */
    @Test
    public void testSetIpfsApiEndpointCreatesProperties() {
        // Properties should be null initially
        assertNull(dataStore.getIpfsApiEndpoint());
        
        // Setting endpoint should create properties
        dataStore.setIpfsApiEndpoint("/ip4/127.0.0.1/tcp/5001");
        
        assertEquals("/ip4/127.0.0.1/tcp/5001", dataStore.getIpfsApiEndpoint());
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
     * Test getAllCIDMappings returns empty before init.
     */
    @Test
    public void testGetAllCIDMappingsBeforeInit() {
        Map<String, String> mappings = dataStore.getAllCIDMappings();
        assertNotNull(mappings);
        assertTrue(mappings.isEmpty());
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
     * Test properties are passed to backend.
     */
    @Test
    public void testPropertiesPassedToBackend() {
        Properties props = new Properties();
        props.setProperty("ipfsApiEndpoint", "/ip4/10.0.0.1/tcp/5001");
        props.setProperty("someOtherProperty", "value");
        
        dataStore.setProperties(props);
        
        // Verify endpoint is stored
        assertEquals("/ip4/10.0.0.1/tcp/5001", dataStore.getIpfsApiEndpoint());
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
    
    /**
     * Test endpoint with various formats.
     */
    @Test
    public void testEndpointFormats() {
        // IPv4
        dataStore.setIpfsApiEndpoint("/ip4/127.0.0.1/tcp/5001");
        assertEquals("/ip4/127.0.0.1/tcp/5001", dataStore.getIpfsApiEndpoint());
        
        // IPv6
        dataStore.setIpfsApiEndpoint("/ip6/::1/tcp/5001");
        assertEquals("/ip6/::1/tcp/5001", dataStore.getIpfsApiEndpoint());
        
        // DNS
        dataStore.setIpfsApiEndpoint("/dns4/ipfs.example.com/tcp/5001");
        assertEquals("/dns4/ipfs.example.com/tcp/5001", dataStore.getIpfsApiEndpoint());
    }
    
    /**
     * Test multiple endpoint changes.
     */
    @Test
    public void testMultipleEndpointChanges() {
        dataStore.setIpfsApiEndpoint("/ip4/127.0.0.1/tcp/5001");
        assertEquals("/ip4/127.0.0.1/tcp/5001", dataStore.getIpfsApiEndpoint());
        
        dataStore.setIpfsApiEndpoint("/ip4/192.168.1.1/tcp/5001");
        assertEquals("/ip4/192.168.1.1/tcp/5001", dataStore.getIpfsApiEndpoint());
        
        dataStore.setIpfsApiEndpoint("/ip4/10.0.0.1/tcp/5001");
        assertEquals("/ip4/10.0.0.1/tcp/5001", dataStore.getIpfsApiEndpoint());
    }

    @Test
    public void testCreateBackendWithoutPropertiesLeavesEndpointUnset() {
        IPFSBackend backend = (IPFSBackend) dataStore.createBackend();

        assertNull(backend.getIpfsApiEndpoint());
        assertNull(dataStore.getIpfsApiEndpoint());
    }

    @Test
    public void testCreateBackendWithPropertiesWithoutEndpointLeavesEndpointUnset() {
        dataStore.setProperties(new Properties());

        IPFSBackend backend = (IPFSBackend) dataStore.createBackend();

        assertNull(backend.getIpfsApiEndpoint());
        assertNull(dataStore.getIpfsApiEndpoint());
    }

    @Test
    public void testCreateBackendAppliesConfiguredEndpoint() {
        Properties props = new Properties();
        props.setProperty("ipfsApiEndpoint", "/dns4/ipfs.example.com/tcp/5001");
        dataStore.setProperties(props);

        IPFSBackend backend = (IPFSBackend) dataStore.createBackend();

        assertEquals("/dns4/ipfs.example.com/tcp/5001", backend.getIpfsApiEndpoint());
        assertEquals("/dns4/ipfs.example.com/tcp/5001", dataStore.getIpfsApiEndpoint());
    }

    @Test
    public void testSetIpfsApiEndpointUpdatesCreatedBackend() {
        IPFSBackend backend = (IPFSBackend) dataStore.createBackend();

        dataStore.setIpfsApiEndpoint("/ip4/10.0.0.8/tcp/5001");

        assertEquals("/ip4/10.0.0.8/tcp/5001", backend.getIpfsApiEndpoint());
        assertEquals("/ip4/10.0.0.8/tcp/5001", dataStore.getIpfsApiEndpoint());
    }

    @Test
    public void testGetCIDAndMappingsAfterBackendCreation() throws Exception {
        IPFSBackend backend = (IPFSBackend) dataStore.createBackend();
        addCidMapping(backend, "blob-id", "QmTestCid");

        assertEquals("QmTestCid", dataStore.getCID("blob-id"));
        assertEquals("QmTestCid", dataStore.getCID("blob-id#128"));
        assertEquals(Map.of("blob-id", "QmTestCid"), dataStore.getAllCIDMappings());
    }

    @SuppressWarnings("unchecked")
    private static void addCidMapping(IPFSBackend backend, String blobId, String cid) throws Exception {
        Field cidCacheField = IPFSBackend.class.getDeclaredField("cidCache");
        cidCacheField.setAccessible(true);
        Map<DataIdentifier, String> cidCache = (Map<DataIdentifier, String>) cidCacheField.get(backend);
        cidCache.put(new DataIdentifier(blobId), cid);
    }
}
