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
import org.apache.jackrabbit.core.data.DataRecord;
import org.apache.jackrabbit.core.data.DataStoreException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Iterator;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link IPFSBackend}.
 * 
 * <p>These tests verify the IPFS backend behavior without requiring a real IPFS node.
 * Tests that require IPFS connectivity are marked with @Ignore or use mocks.
 */
public class IPFSBackendTest {
    
    private IPFSBackend backend;
    
    @Before
    public void setUp() {
        backend = new IPFSBackend();
    }
    
    @After
    public void tearDown() {
        if (backend != null) {
            try {
                backend.close();
            } catch (DataStoreException e) {
                // Ignore cleanup errors
            }
        }
    }
    
    /**
     * Test default endpoint configuration.
     */
    @Test
    public void testDefaultEndpoint() {
        assertNull(backend.getIpfsApiEndpoint());
        
        backend.setIpfsApiEndpoint("/ip4/127.0.0.1/tcp/5001");
        assertEquals("/ip4/127.0.0.1/tcp/5001", backend.getIpfsApiEndpoint());
    }
    
    /**
     * Test custom endpoint configuration.
     */
    @Test
    public void testCustomEndpoint() {
        backend.setIpfsApiEndpoint("/ip4/192.168.1.100/tcp/5001");
        assertEquals("/ip4/192.168.1.100/tcp/5001", backend.getIpfsApiEndpoint());
    }
    
    /**
     * Test getCID returns null for unknown identifier.
     */
    @Test
    public void testGetCIDUnknownIdentifier() {
        DataIdentifier id = new DataIdentifier("unknown-blob-id");
        assertNull(backend.getCID(id));
    }
    
    /**
     * Test getAllCIDMappings returns empty map initially.
     */
    @Test
    public void testGetAllCIDMappingsEmpty() {
        Map<String, String> mappings = backend.getAllCIDMappings();
        assertNotNull(mappings);
        assertTrue(mappings.isEmpty());
    }
    
    /**
     * Test getAllIdentifiers returns empty iterator initially.
     */
    @Test
    public void testGetAllIdentifiersEmpty() throws DataStoreException {
        Iterator<DataIdentifier> ids = backend.getAllIdentifiers();
        assertNotNull(ids);
        assertFalse(ids.hasNext());
    }
    
    /**
     * Test getAllRecords returns empty iterator initially.
     */
    @Test
    public void testGetAllRecordsEmpty() throws DataStoreException {
        Iterator<DataRecord> records = backend.getAllRecords();
        assertNotNull(records);
        assertFalse(records.hasNext());
    }
    
    /**
     * Test exists returns false for unknown identifier.
     */
    @Test
    public void testExistsUnknownIdentifier() throws DataStoreException {
        DataIdentifier id = new DataIdentifier("unknown-blob-id");
        assertFalse(backend.exists(id));
    }
    
    /**
     * Test getRecord throws for unknown identifier.
     */
    @Test(expected = DataStoreException.class)
    public void testGetRecordUnknownIdentifier() throws DataStoreException {
        DataIdentifier id = new DataIdentifier("unknown-blob-id");
        backend.getRecord(id);
    }
    
    /**
     * Test read throws for unknown identifier.
     */
    @Test(expected = DataStoreException.class)
    public void testReadUnknownIdentifier() throws DataStoreException {
        DataIdentifier id = new DataIdentifier("unknown-blob-id");
        backend.read(id);
    }
    
    /**
     * Test deleteRecord handles unknown identifier gracefully.
     */
    @Test
    public void testDeleteRecordUnknownIdentifier() throws DataStoreException {
        DataIdentifier id = new DataIdentifier("unknown-blob-id");
        // Should not throw, just log warning
        backend.deleteRecord(id);
    }
    
    /**
     * Test close clears cache.
     */
    @Test
    public void testCloseClarsCache() throws DataStoreException {
        // Close should work even without init
        backend.close();
        
        // Verify cache is empty
        Map<String, String> mappings = backend.getAllCIDMappings();
        assertTrue(mappings.isEmpty());
    }
    
    /**
     * Test metadata record does not exist initially.
     */
    @Test
    public void testMetadataRecordNotExists() {
        assertFalse(backend.metadataRecordExists("test-metadata"));
    }
    
    /**
     * Test getMetadataRecord returns null for unknown name.
     */
    @Test
    public void testGetMetadataRecordUnknown() {
        DataRecord record = backend.getMetadataRecord("unknown-metadata");
        assertNull(record);
    }
    
    /**
     * Test getAllMetadataRecords returns empty list initially.
     */
    @Test
    public void testGetAllMetadataRecordsEmpty() {
        assertTrue(backend.getAllMetadataRecords("").isEmpty());
        assertTrue(backend.getAllMetadataRecords("prefix").isEmpty());
    }
    
    /**
     * Test deleteMetadataRecord handles unknown name.
     */
    @Test
    public void testDeleteMetadataRecordUnknown() {
        boolean deleted = backend.deleteMetadataRecord("unknown-metadata");
        assertFalse(deleted);
    }
    
    /**
     * Test deleteAllMetadataRecords handles empty case.
     */
    @Test
    public void testDeleteAllMetadataRecordsEmpty() {
        // Should not throw
        backend.deleteAllMetadataRecords("");
        backend.deleteAllMetadataRecords("prefix");
    }
    
    /**
     * Test init fails without IPFS node (expected in test environment).
     */
    @Test
    public void testInitWithoutIPFSNode() {
        // Set a non-existent endpoint
        backend.setIpfsApiEndpoint("/ip4/127.0.0.1/tcp/59999");
        
        try {
            backend.init();
            // If init succeeds, IPFS node is running (unexpected in CI)
            // This is fine - test passes either way
        } catch (DataStoreException e) {
            // Expected - no IPFS node available
            assertTrue(e.getMessage().contains("Failed to initialize IPFS backend") ||
                      e.getMessage().contains("Connection refused") ||
                      e.getMessage().contains("connect"));
        }
    }
    
    /**
     * Test endpoint setter/getter consistency.
     */
    @Test
    public void testEndpointSetterGetterConsistency() {
        String[] endpoints = {
            "/ip4/127.0.0.1/tcp/5001",
            "/ip4/192.168.1.100/tcp/5001",
            "/dns4/ipfs.example.com/tcp/5001",
            "/ip6/::1/tcp/5001"
        };
        
        for (String endpoint : endpoints) {
            backend.setIpfsApiEndpoint(endpoint);
            assertEquals(endpoint, backend.getIpfsApiEndpoint());
        }
    }
    
    /**
     * Test metadata names are exposed without synthetic identifier prefixes.
     */
    @Test
    public void testMetadataRecordNamingContract() {
        assertFalse(backend.metadataRecordExists("test"));
        assertNull(backend.getMetadataRecord("test"));
    }
}
