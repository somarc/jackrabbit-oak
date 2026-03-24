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
 * Oak {@link AbstractSharedCachingDataStore} implementation backed by
 * {@link IPFSBackend}.
 *
 * <p>This class is responsible for wiring datastore configuration into the
 * backend and exposing IPFS-specific convenience accessors such as the
 * configured endpoint, IPFS Files root, and current CID mappings. Blobs smaller than
 * {@link #getMinRecordLength()} stay inline in Oak segments, while larger
 * binaries are delegated to IPFS.</p>
 *
 * <p>The backend persists CID mappings and metadata in the IPFS Files namespace
 * so records remain discoverable across backend restarts that reconnect to the
 * same IPFS repository.</p>
 *
 * @see IPFSBackend
 * @see org.apache.jackrabbit.oak.plugins.blob.AbstractSharedCachingDataStore
 */
public class IPFSDataStore extends AbstractSharedCachingDataStore {

    private static final String DEFAULT_IPFS_FILES_ROOT = "/oak/ipfs";

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
            String filesRoot = properties.getProperty("ipfsFilesRoot");
            if (filesRoot != null) {
                ipfsBackend.setIpfsFilesRoot(filesRoot);
            }
        }
        return ipfsBackend;
    }

    /**
     * Supplies backend properties that will be applied when the backend is
     * created.
     *
     * <p>The backend currently consumes {@code ipfsApiEndpoint} and
     * {@code ipfsFilesRoot} directly.</p>
     *
     * @param properties datastore and backend configuration properties
     */
    public void setProperties(Properties properties) {
        this.properties = properties;
    }

    /**
     * Returns the instantiated shared backend.
     *
     * @return the backend currently associated with this datastore
     */
    public SharedBackend getBackend() {
        return backend;
    }

    @Override
    public int getMinRecordLength() {
        return minRecordLength;
    }

    /**
     * Sets the minimum blob size that should be delegated to the shared backend.
     *
     * @param minRecordLength size threshold in bytes
     */
    public void setMinRecordLength(int minRecordLength) {
        this.minRecordLength = minRecordLength;
    }
    
    /**
     * Sets the IPFS API endpoint directly.
     *
     * <p>The property is retained for later backend creation and also pushed to
     * the live backend when one already exists.</p>
     *
     * @param endpoint IPFS HTTP API multiaddr (for example
     *                 {@code /ip4/127.0.0.1/tcp/5001})
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
     * Returns the configured IPFS API endpoint.
     *
     * @return the live backend endpoint when initialized, otherwise the stored
     *         configuration value
     */
    public String getIpfsApiEndpoint() {
        if (ipfsBackend != null) {
            return ipfsBackend.getIpfsApiEndpoint();
        }
        return properties != null ? properties.getProperty("ipfsApiEndpoint") : null;
    }

    /**
     * Sets the IPFS Files namespace root used for durable Oak metadata.
     *
     * <p>Use a distinct root when multiple Oak repositories share one IPFS
     * repository to avoid collisions between CID mappings and metadata.</p>
     *
     * @param root MFS root path, for example {@code /oak/ipfs}
     */
    public void setIpfsFilesRoot(String root) {
        if (properties == null) {
            properties = new Properties();
        }
        String normalizedRoot = normalizeIpfsFilesRoot(root);
        properties.setProperty("ipfsFilesRoot", normalizedRoot);

        if (ipfsBackend != null) {
            ipfsBackend.setIpfsFilesRoot(normalizedRoot);
        }
    }

    /**
     * Returns the configured IPFS Files namespace root.
     *
     * @return the live backend root when initialized, otherwise the stored
     *         configuration value
     */
    public String getIpfsFilesRoot() {
        if (ipfsBackend != null) {
            return ipfsBackend.getIpfsFilesRoot();
        }
        return properties != null ? normalizeIpfsFilesRoot(properties.getProperty("ipfsFilesRoot")) : null;
    }
    
    /**
     * Returns the IPFS CID for an Oak blob identifier.
     *
     * <p>If the blob identifier contains the Oak size suffix (for example
     * {@code hash#length}), the suffix is stripped before the lookup.</p>
     *
     * @param oakBlobId Oak blob identifier
     * @return the resolved IPFS CID, or {@code null} when no mapping is known
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
     * Returns a snapshot of the backend's current identifier-to-CID mappings.
     *
     * @return mapping of Oak blob IDs to IPFS CIDs
     */
    public java.util.Map<String, String> getAllCIDMappings() {
        if (ipfsBackend == null) {
            return java.util.Collections.emptyMap();
        }
        return ipfsBackend.getAllCIDMappings();
    }

    private static String normalizeIpfsFilesRoot(String value) {
        if (value == null || value.trim().isEmpty()) {
            return DEFAULT_IPFS_FILES_ROOT;
        }
        String normalized = value.trim();
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
