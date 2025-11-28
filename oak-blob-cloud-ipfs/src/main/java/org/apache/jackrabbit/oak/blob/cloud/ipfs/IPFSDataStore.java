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

import org.apache.jackrabbit.oak.plugins.blob.AbstractSharedCachingDataStore;
import org.apache.jackrabbit.oak.spi.blob.AbstractSharedBackend;
import org.apache.jackrabbit.oak.spi.blob.SharedBackend;

import java.util.Properties;

/**
 * IPFS DataStore for Oak - stores large binaries in IPFS.
 * 
 * This provides:
 * - Content-addressed storage (CID = cryptographic hash, immutable)
 * - Decentralized replication (P2P between validators)
 * - Built-in deduplication (same binary uploaded multiple times = stored once)
 * - Blockchain-native storage layer (complements Oak segments)
 * 
 * Strategic positioning (ADR 015):
 * - Oak segments → AEM compatibility (moat)
 * - IPFS binaries → Blockchain-native (differentiation)
 * 
 * Usage:
 * <pre>
 * IPFSDataStore ds = new IPFSDataStore();
 * ds.setIpfsApiEndpoint("/ip4/127.0.0.1/tcp/5001");
 * ds.init();
 * 
 * // Upload binary
 * DataRecord record = ds.addRecord(inputStream);
 * 
 * // Retrieve binary
 * InputStream data = record.getStream();
 * </pre>
 * 
 * Configuration properties:
 * - ipfsApiEndpoint: IPFS HTTP API endpoint (default: /ip4/127.0.0.1/tcp/5001)
 * - minRecordLength: Minimum size for external storage (default: 16KB)
 * - cache*: Local cache settings (inherited from AbstractSharedCachingDataStore)
 * 
 * @see IPFSBackend
 * @see org.apache.jackrabbit.oak.plugins.blob.AbstractSharedCachingDataStore
 */
public class IPFSDataStore extends AbstractSharedCachingDataStore {

    protected Properties properties;

    private IPFSBackend ipfsBackend;

    /**
     * The minimum size of an object that should be stored in IPFS.
     * Smaller objects are stored inline in Oak segments.
     * Default: 16KB (matches S3DataStore default)
     */
    private int minRecordLength = 16 * 1024;

    @Override
    protected AbstractSharedBackend createBackend() {
        ipfsBackend = new IPFSBackend();
        if (properties != null) {
            // Apply properties to backend
            String endpoint = properties.getProperty("ipfsApiEndpoint");
            if (endpoint != null) {
                ipfsBackend.setIpfsApiEndpoint(endpoint);
            }
        }
        return ipfsBackend;
    }

    /**
     * Properties required to configure the IPFS Backend.
     * 
     * Supported properties:
     * - ipfsApiEndpoint: IPFS HTTP API multiaddr (e.g., /ip4/127.0.0.1/tcp/5001)
     */
    public void setProperties(Properties properties) {
        this.properties = properties;
    }

    public SharedBackend getBackend() {
        return backend;
    }

    @Override
    public int getMinRecordLength() {
        return minRecordLength;
    }

    public void setMinRecordLength(int minRecordLength) {
        this.minRecordLength = minRecordLength;
    }
    
    /**
     * Set IPFS API endpoint directly (convenience method).
     * 
     * @param endpoint IPFS HTTP API multiaddr (e.g., /ip4/127.0.0.1/tcp/5001)
     */
    public void setIpfsApiEndpoint(String endpoint) {
        if (properties == null) {
            properties = new Properties();
        }
        properties.setProperty("ipfsApiEndpoint", endpoint);
        
        if (ipfsBackend != null) {
            ipfsBackend.setIpfsApiEndpoint(endpoint);
        }
    }
    
    /**
     * Get IPFS API endpoint.
     */
    public String getIpfsApiEndpoint() {
        if (ipfsBackend != null) {
            return ipfsBackend.getIpfsApiEndpoint();
        }
        return properties != null ? properties.getProperty("ipfsApiEndpoint") : null;
    }
    
    /**
     * Get IPFS CID for an Oak blob ID.
     * 
     * This allows coordination between Oak blob IDs (SHA-256 hex) and IPFS CIDs (multihash).
     * 
     * @param oakBlobId Oak blob ID (e.g., "ed06f9cb...#22216")
     * @return IPFS CID (e.g., "Qmf4F3...") or null if not found
     */
    public String getCID(String oakBlobId) {
        if (ipfsBackend == null) {
            return null;
        }
        // Remove size suffix if present
        String blobIdWithoutSize = oakBlobId.contains("#") 
            ? oakBlobId.substring(0, oakBlobId.indexOf('#')) 
            : oakBlobId;
        
        org.apache.jackrabbit.core.data.DataIdentifier identifier = 
            new org.apache.jackrabbit.core.data.DataIdentifier(blobIdWithoutSize);
        return ipfsBackend.getCID(identifier);
    }
    
    /**
     * Get all CID mappings.
     * 
     * @return Map of Oak blob IDs to IPFS CIDs
     */
    public java.util.Map<String, String> getAllCIDMappings() {
        if (ipfsBackend == null) {
            return java.util.Collections.emptyMap();
        }
        return ipfsBackend.getAllCIDMappings();
    }
}

