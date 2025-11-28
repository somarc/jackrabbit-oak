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

import io.ipfs.api.IPFS;
import io.ipfs.api.MerkleNode;
import io.ipfs.api.NamedStreamable;
import io.ipfs.cid.Cid;
import io.ipfs.multihash.Multihash;
import org.apache.jackrabbit.core.data.DataIdentifier;
import org.apache.jackrabbit.core.data.DataRecord;
import org.apache.jackrabbit.core.data.DataStoreException;
import org.apache.jackrabbit.oak.spi.blob.AbstractDataRecord;
import org.apache.jackrabbit.oak.spi.blob.AbstractSharedBackend;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.*;

/**
 * IPFS backend for Oak BlobStore.
 * 
 * This backend stores large binaries (jcr:data) in IPFS, providing:
 * - Content-addressed storage (CID = cryptographic hash)
 * - Decentralized replication (P2P between validators)
 * - Built-in deduplication (same binary = same CID)
 * - Blockchain-native storage layer
 * 
 * See ADR 015 for strategic rationale.
 */
public class IPFSBackend extends AbstractSharedBackend {

    private static final Logger LOG = LoggerFactory.getLogger(IPFSBackend.class);
    
    private static final String KEY_PREFIX = "ipfs_";
    
    /**
     * IPFS HTTP API client
     */
    private IPFS ipfs;
    
    /**
     * IPFS API endpoint (e.g., "/ip4/127.0.0.1/tcp/5001")
     */
    private String ipfsApiEndpoint;
    
    /**
     * Local cache of pinned CIDs (DataIdentifier → CID mapping)
     */
    private final Map<DataIdentifier, String> cidCache = new HashMap<>();
    
    /**
     * Timestamp when backend was initialized
     */
    private Date startTime;

    @Override
    public void init() throws DataStoreException {
        try {
            LOG.info("🚀 Initializing IPFS Backend...");
            
            // Default to localhost IPFS node if not configured
            if (ipfsApiEndpoint == null || ipfsApiEndpoint.isEmpty()) {
                ipfsApiEndpoint = "/ip4/127.0.0.1/tcp/5001";
            }
            
            // Connect to IPFS node via HTTP API
            ipfs = new IPFS(ipfsApiEndpoint);
            
            // Test connection (try a simple operation)
            try {
                Object versionInfo = ipfs.version();
                LOG.info("✅ Connected to IPFS node: {} (version info: {})", ipfsApiEndpoint, versionInfo);
            } catch (Exception e) {
                LOG.warn("Connected to IPFS node: {} (could not get version: {})", ipfsApiEndpoint, e.getMessage());
            }
            
            startTime = new Date();
            
        } catch (Exception e) {
            throw new DataStoreException("Failed to initialize IPFS backend: " + e.getMessage(), e);
        }
    }

    @Override
    public void write(DataIdentifier identifier, File file) throws DataStoreException {
        try {
            LOG.debug("📤 Uploading file to IPFS: {} ({} bytes)", identifier, file.length());
            
            // Add file to IPFS
            NamedStreamable.FileWrapper fileWrapper = new NamedStreamable.FileWrapper(file);
            List<MerkleNode> nodes = ipfs.add(fileWrapper);
            
            if (nodes.isEmpty()) {
                throw new DataStoreException("IPFS add returned empty result for " + identifier);
            }
            
            MerkleNode result = nodes.get(0);
            String cid = result.hash.toString();
            
            LOG.info("📦 Uploaded binary to IPFS: {} → CID: {}", identifier, cid);
            
            // Pin to ensure persistence (prevents garbage collection)
            ipfs.pin.add(Multihash.fromBase58(cid));
            LOG.debug("📌 Pinned CID: {}", cid);
            
            // Cache the mapping
            cidCache.put(identifier, cid);
            
            // Verify CID matches Oak identifier (both are content hashes)
            // Note: Oak uses hex-encoded SHA-256, IPFS uses base58-encoded multihash
            // For POC, we trust IPFS's content addressing
            
        } catch (Exception e) {
            throw new DataStoreException("IPFS write failed for " + identifier + ": " + e.getMessage(), e);
        }
    }

    @Override
    public InputStream read(DataIdentifier identifier) throws DataStoreException {
        try {
            // Try to get CID from cache first
            String cid = cidCache.get(identifier);
            
            if (cid == null) {
                // If not cached, try to construct CID from identifier
                // For POC, we'll need to track this mapping
                throw new DataStoreException("CID not found for identifier: " + identifier);
            }
            
            LOG.debug("📥 Fetching binary from IPFS: CID: {}", cid);
            
            // Fetch from IPFS (local cache or network)
            byte[] content = ipfs.cat(Multihash.fromBase58(cid));
            
            LOG.info("✅ Retrieved binary from IPFS: {} ({} bytes)", identifier, content.length);
            
            return new ByteArrayInputStream(content);
            
        } catch (Exception e) {
            throw new DataStoreException("IPFS read failed for " + identifier + ": " + e.getMessage(), e);
        }
    }

    @Override
    public DataRecord getRecord(DataIdentifier identifier) throws DataStoreException {
        // Check if CID exists in our cache
        String cid = cidCache.get(identifier);
        
        if (cid == null || !exists(identifier)) {
            throw new DataStoreException("Record not found: " + identifier);
        }
        
        return new IPFSDataRecord(this, identifier);
    }

    @Override
    public Iterator<DataIdentifier> getAllIdentifiers() throws DataStoreException {
        // Return all cached identifiers
        // In production, this would query IPFS for all pinned CIDs
        return new ArrayList<>(cidCache.keySet()).iterator();
    }

    @Override
    public Iterator<DataRecord> getAllRecords() throws DataStoreException {
        List<DataRecord> records = new ArrayList<>();
        Iterator<DataIdentifier> identifiers = getAllIdentifiers();
        
        while (identifiers.hasNext()) {
            DataIdentifier id = identifiers.next();
            records.add(new IPFSDataRecord(this, id));
        }
        
        return records.iterator();
    }

    @Override
    public boolean exists(DataIdentifier identifier) throws DataStoreException {
        try {
            String cid = cidCache.get(identifier);
            
            if (cid == null) {
                return false;
            }
            
            // Check if block exists in IPFS
            Map<String, Object> stat = ipfs.block.stat(Multihash.fromBase58(cid));
            return stat != null && stat.containsKey("Size");
            
        } catch (Exception e) {
            LOG.debug("IPFS exists check failed for {}: {}", identifier, e.getMessage());
            return false;
        }
    }

    @Override
    public void close() throws DataStoreException {
        LOG.info("🛑 Closing IPFS Backend");
        // IPFS HTTP client doesn't need explicit close
        cidCache.clear();
    }

    @Override
    public void deleteRecord(DataIdentifier identifier) throws DataStoreException {
        try {
            String cid = cidCache.get(identifier);
            
            if (cid == null) {
                LOG.warn("Cannot delete - CID not found for: {}", identifier);
                return;
            }
            
            LOG.debug("🗑️  Unpinning CID from IPFS: {}", cid);
            
            // Unpin from IPFS (allows garbage collection)
            ipfs.pin.rm(Multihash.fromBase58(cid));
            
            // Remove from cache
            cidCache.remove(identifier);
            
            LOG.info("✅ Unpinned binary from IPFS: {} (CID: {})", identifier, cid);
            
            // Note: Actual deletion happens during IPFS garbage collection (ipfs repo gc)
            
        } catch (Exception e) {
            throw new DataStoreException("IPFS delete failed for " + identifier + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void addMetadataRecord(InputStream input, String name) throws DataStoreException {
        try {
            LOG.debug("Adding metadata record: {}", name);
            
            // Store metadata as IPFS file
            byte[] data = input.readAllBytes();
            NamedStreamable.ByteArrayWrapper wrapper = new NamedStreamable.ByteArrayWrapper(name, data);
            List<MerkleNode> nodes = ipfs.add(wrapper);
            
            if (!nodes.isEmpty()) {
                String cid = nodes.get(0).hash.toString();
                ipfs.pin.add(Multihash.fromBase58(cid));
                LOG.info("📝 Added metadata: {} → CID: {}", name, cid);
                
                // Store metadata CID with special prefix
                cidCache.put(new DataIdentifier("META_" + name), cid);
            }
            
        } catch (Exception e) {
            throw new DataStoreException("Failed to add metadata: " + name, e);
        }
    }

    @Override
    public void addMetadataRecord(File input, String name) throws DataStoreException {
        try (InputStream is = Files.newInputStream(input.toPath())) {
            addMetadataRecord(is, name);
        } catch (Exception e) {
            throw new DataStoreException("Failed to add metadata from file: " + name, e);
        }
    }

    @Override
    public DataRecord getMetadataRecord(String name) {
        DataIdentifier metaId = new DataIdentifier("META_" + name);
        String cid = cidCache.get(metaId);
        
        if (cid != null) {
            return new IPFSDataRecord(this, metaId);
        }
        
        return null;
    }

    @Override
    public List<DataRecord> getAllMetadataRecords(String prefix) {
        List<DataRecord> records = new ArrayList<>();
        String metaPrefix = "META_" + prefix;
        
        for (Map.Entry<DataIdentifier, String> entry : cidCache.entrySet()) {
            if (entry.getKey().toString().startsWith(metaPrefix)) {
                records.add(new IPFSDataRecord(this, entry.getKey()));
            }
        }
        
        return records;
    }

    @Override
    public boolean deleteMetadataRecord(String name) {
        try {
            DataIdentifier metaId = new DataIdentifier("META_" + name);
            deleteRecord(metaId);
            return true;
        } catch (DataStoreException e) {
            LOG.error("Failed to delete metadata: {}", name, e);
            return false;
        }
    }

    @Override
    public void deleteAllMetadataRecords(String prefix) {
        String metaPrefix = "META_" + prefix;
        List<DataIdentifier> toDelete = new ArrayList<>();
        
        for (DataIdentifier id : cidCache.keySet()) {
            if (id.toString().startsWith(metaPrefix)) {
                toDelete.add(id);
            }
        }
        
        for (DataIdentifier id : toDelete) {
            try {
                deleteRecord(id);
            } catch (DataStoreException e) {
                LOG.error("Failed to delete metadata: {}", id, e);
            }
        }
    }

    @Override
    public boolean metadataRecordExists(String name) {
        DataIdentifier metaId = new DataIdentifier("META_" + name);
        return cidCache.containsKey(metaId);
    }

    // --- Getters/Setters ---
    
    public void setIpfsApiEndpoint(String endpoint) {
        this.ipfsApiEndpoint = endpoint;
    }
    
    public String getIpfsApiEndpoint() {
        return ipfsApiEndpoint;
    }
    
    /**
     * Get CID for a given DataIdentifier (for debugging/monitoring)
     */
    public String getCID(DataIdentifier identifier) {
        return cidCache.get(identifier);
    }
    
    /**
     * Get all CID mappings (Oak blob ID → IPFS CID).
     * 
     * @return Map of Oak blob IDs (as hex strings) to IPFS CIDs
     */
    public Map<String, String> getAllCIDMappings() {
        Map<String, String> mappings = new HashMap<>();
        for (Map.Entry<DataIdentifier, String> entry : cidCache.entrySet()) {
            mappings.put(entry.getKey().toString(), entry.getValue());
        }
        return mappings;
    }
    
    /**
     * Inner class for DataRecord implementation
     */
    private static class IPFSDataRecord extends AbstractDataRecord {
        
        private final IPFSBackend backend;
        private final DataIdentifier identifier;
        private long length = -1;
        private long lastModified = -1;
        
        public IPFSDataRecord(IPFSBackend backend, DataIdentifier identifier) {
            super(backend, identifier);
            this.backend = backend;
            this.identifier = identifier;
        }
        
        @Override
        public InputStream getStream() throws DataStoreException {
            return backend.read(identifier);
        }
        
        @Override
        public long getLength() throws DataStoreException {
            if (length == -1) {
                try {
                    String cid = backend.getCID(identifier);
                    if (cid != null) {
                        Map<String, Object> stat = backend.ipfs.block.stat(Multihash.fromBase58(cid));
                        length = ((Number) stat.get("Size")).longValue();
                    }
                } catch (Exception e) {
                    throw new DataStoreException("Failed to get length for " + identifier, e);
                }
            }
            return length;
        }
        
        @Override
        public long getLastModified() {
            if (lastModified == -1) {
                // IPFS doesn't track modification time, use backend start time
                lastModified = backend.startTime.getTime();
            }
            return lastModified;
        }
    }
}

