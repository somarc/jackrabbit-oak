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

import io.ipfs.api.MerkleNode;
import io.ipfs.api.NamedStreamable;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link AbstractSharedBackend} implementation that stores Oak binaries in
 * IPFS.
 *
 * <p>The backend uploads content through the IPFS HTTP API, pins the resulting
 * CID, and persists the {@code DataIdentifier -> CID} mapping in the IPFS Files
 * namespace so restarts can still resolve Oak blob identifiers back to IPFS
 * objects.</p>
 *
 * <p>Backend metadata, including {@code reference.key}, is also stored in the
 * IPFS Files namespace under stable paths. The in-memory cache now acts only as
 * an acceleration layer over that durable state.</p>
 */
public class IPFSBackend extends AbstractSharedBackend {

    private static final Logger LOG = LoggerFactory.getLogger(IPFSBackend.class);

    private static final String DEFAULT_IPFS_API_ENDPOINT = "/ip4/127.0.0.1/tcp/5001";
    private static final String DEFAULT_IPFS_FILES_ROOT = "/oak/ipfs";
    private static final String REFERENCE_KEY = "reference.key";

    private final IpfsClientFactory ipfsClientFactory;

    /**
     * Client created during {@link #init()} and reused for all IPFS API calls.
     */
    private IpfsClient ipfs;
    
    /**
     * IPFS API endpoint (e.g., "/ip4/127.0.0.1/tcp/5001")
     */
    private String ipfsApiEndpoint;
    
    /**
     * In-memory cache for blob identifier-to-CID mappings.
     */
    private final Map<DataIdentifier, String> cidCache = new ConcurrentHashMap<>();

    /**
     * Root directory in the IPFS Files namespace used for Oak metadata.
     */
    private String ipfsFilesRoot = DEFAULT_IPFS_FILES_ROOT;

    /**
     * Initialization timestamp used as a synthetic last-modified value.
     */
    private Date startTime;

    private volatile byte[] secret;

    /**
     * Creates a backend using the default HTTP IPFS client implementation.
     */
    public IPFSBackend() {
        this(DefaultIpfsClient::new);
    }

    /**
     * Testing seam that injects a custom IPFS client factory.
     */
    IPFSBackend(IpfsClientFactory ipfsClientFactory) {
        this.ipfsClientFactory = ipfsClientFactory;
    }

    @Override
    public void init() throws DataStoreException {
        try {
            LOG.info("🚀 Initializing IPFS Backend...");
            
            // Default to a local daemon when the caller did not configure one.
            if (ipfsApiEndpoint == null || ipfsApiEndpoint.isEmpty()) {
                ipfsApiEndpoint = DEFAULT_IPFS_API_ENDPOINT;
            }
            
            // Connect once and reuse the same client for all later calls.
            ipfs = ipfsClientFactory.create(ipfsApiEndpoint);
            
            // Version lookup is a lightweight connectivity check; failure is
            // logged but does not block startup if the client was created.
            try {
                Object versionInfo = ipfs.version();
                LOG.info("✅ Connected to IPFS node: {} (version info: {})", ipfsApiEndpoint, versionInfo);
            } catch (Exception e) {
                LOG.warn("Connected to IPFS node: {} (could not get version: {})", ipfsApiEndpoint, e.getMessage());
            }

            ensureNamespace();
            
            startTime = new Date();
            
        } catch (Exception e) {
            throw new DataStoreException("Failed to initialize IPFS backend: " + e.getMessage(), e);
        }
    }

    @Override
    public void write(DataIdentifier identifier, File file) throws DataStoreException {
        try {
            LOG.debug("📤 Uploading file to IPFS: {} ({} bytes)", identifier, file.length());
            
            // Upload the file and use the first returned node as the content CID.
            NamedStreamable.FileWrapper fileWrapper = new NamedStreamable.FileWrapper(file);
            List<MerkleNode> nodes = ipfs.add(fileWrapper);
            
            if (nodes.isEmpty()) {
                throw new DataStoreException("IPFS add returned empty result for " + identifier);
            }
            
            MerkleNode result = nodes.get(0);
            String cid = result.hash.toString();
            
            LOG.info("📦 Uploaded binary to IPFS: {} → CID: {}", identifier, cid);
            
            // Pin the CID so the node keeps the content available locally.
            ipfs.pinAdd(cid);
            LOG.debug("📌 Pinned CID: {}", cid);
            
            try {
                persistCidMapping(identifier, cid);
                persistContentReference(identifier, cid);
                cidCache.put(identifier, cid);
            } catch (Exception e) {
                deleteContentReferenceQuietly(identifier);
                deleteCidMappingQuietly(identifier);
                try {
                    ipfs.pinRemove(cid);
                } catch (Exception cleanupFailure) {
                    LOG.warn("Failed to unpin CID {} after mapping persistence error", cid, cleanupFailure);
                }
                throw e;
            }
            
        } catch (Exception e) {
            throw new DataStoreException("IPFS write failed for " + identifier + ": " + e.getMessage(), e);
        }
    }

    @Override
    public InputStream read(DataIdentifier identifier) throws DataStoreException {
        try {
            // Reads are resolved through the durable mapping file and cached in memory.
            String cid = resolveCid(identifier);
            
            if (cid == null) {
                throw new DataStoreException("CID not found for identifier: " + identifier);
            }
            
            LOG.debug("📥 Fetching binary from IPFS: CID: {}", cid);
            
            // The IPFS node decides whether this is served from local storage or
            // the wider network.
            byte[] content = ipfs.cat(cid);
            
            LOG.info("✅ Retrieved binary from IPFS: {} ({} bytes)", identifier, content.length);
            
            return new ByteArrayInputStream(content);
            
        } catch (Exception e) {
            throw new DataStoreException("IPFS read failed for " + identifier + ": " + e.getMessage(), e);
        }
    }

    @Override
    public DataRecord getRecord(DataIdentifier identifier) throws DataStoreException {
        if (!exists(identifier)) {
            throw new DataStoreException("Record not found: " + identifier);
        }
        
        return new IPFSDataRecord(this, identifier);
    }

    @Override
    public Iterator<DataIdentifier> getAllIdentifiers() throws DataStoreException {
        Set<DataIdentifier> identifiers = new LinkedHashSet<>(cidCache.keySet());
        identifiers.addAll(loadPersistedIdentifiers());
        return identifiers.iterator();
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
            String cid = resolveCid(identifier);
            
            if (cid == null) {
                return false;
            }
            
            // IPFS block metadata doubles as our existence probe.
            Map<String, Object> stat = ipfs.blockStat(cid);
            return stat != null && stat.containsKey("Size");
            
        } catch (Exception e) {
            LOG.debug("IPFS exists check failed for {}: {}", identifier, e.getMessage());
            return false;
        }
    }

    @Override
    public void close() throws DataStoreException {
        LOG.info("🛑 Closing IPFS Backend");
        // The HTTP client has no explicit close hook; clear local state instead.
        cidCache.clear();
        secret = null;
    }

    @Override
    public void deleteRecord(DataIdentifier identifier) throws DataStoreException {
        try {
            String cid = resolveCid(identifier);
            
            if (cid == null) {
                LOG.warn("Cannot delete - CID not found for: {}", identifier);
                return;
            }
            
            LOG.debug("🗑️  Unpinning CID from IPFS: {}", cid);

            // The MFS content link is the durable retention root for this blob.
            deleteContentReference(identifier);
            deleteCidMapping(identifier);

            // Unpinning makes the content eligible for later IPFS garbage collection.
            try {
                ipfs.pinRemove(cid);
            } catch (Exception e) {
                if (isNotPinned(e)) {
                    LOG.debug("CID {} was not directly pinned at delete time; relying on removed MFS link", cid);
                } else {
                    throw e;
                }
            }
            
            LOG.info("✅ Unpinned binary from IPFS: {} (CID: {})", identifier, cid);
            
            // Actual block removal is deferred to the node's garbage-collection cycle.
            
        } catch (Exception e) {
            throw new DataStoreException("IPFS delete failed for " + identifier + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void addMetadataRecord(InputStream input, String name) throws DataStoreException {
        requireInput(input);
        requireName(name);
        try {
            LOG.debug("Adding metadata record: {}", name);
            
            // Metadata is stored in the durable IPFS Files namespace so it can
            // be addressed by name after restart.
            byte[] data = input.readAllBytes();
            ensureNamespace();
            ipfs.writeFile(metadataPath(name), data);
            LOG.info("📝 Added metadata: {}", name);
            
        } catch (Exception e) {
            throw new DataStoreException("Failed to add metadata: " + name, e);
        }
    }

    @Override
    public void addMetadataRecord(File input, String name) throws DataStoreException {
        requireFile(input);
        requireName(name);
        try (InputStream is = Files.newInputStream(input.toPath())) {
            addMetadataRecord(is, name);
        } catch (Exception e) {
            throw new DataStoreException("Failed to add metadata from file: " + name, e);
        }
    }

    @Override
    public DataRecord getMetadataRecord(String name) {
        requireName(name);
        if (metadataRecordExists(name)) {
            return new IPFSMetadataRecord(this, name);
        }
        return null;
    }

    @Override
    public List<DataRecord> getAllMetadataRecords(String prefix) {
        requirePrefix(prefix);
        List<DataRecord> records = new ArrayList<>();
        for (String name : listMetadataNames(prefix)) {
            if (metadataRecordExists(name)) {
                records.add(new IPFSMetadataRecord(this, name));
            }
        }
        return records;
    }

    @Override
    public boolean deleteMetadataRecord(String name) {
        requireName(name);
        try {
            if (!metadataRecordExists(name)) {
                return false;
            }
            ipfs.deleteFile(metadataPath(name));
            return true;
        } catch (Exception e) {
            LOG.error("Failed to delete metadata: {}", name, e);
            return false;
        }
    }

    @Override
    public void deleteAllMetadataRecords(String prefix) {
        requirePrefix(prefix);
        for (String name : listMetadataNames(prefix)) {
            try {
                ipfs.deleteFile(metadataPath(name));
            } catch (Exception e) {
                LOG.error("Failed to delete metadata: {}", name, e);
            }
        }
    }

    @Override
    public boolean metadataRecordExists(String name) {
        requireName(name);
        if (ipfs == null) {
            return false;
        }
        try {
            return ipfs.fileExists(metadataPath(name));
        } catch (Exception e) {
            LOG.debug("Failed to check metadata record {}: {}", name, e.getMessage());
            return false;
        }
    }

    // Accessors used by tests and higher-level integrations.
    
    /**
     * Sets the IPFS API endpoint that will be used on the next {@link #init()}.
     *
     * @param endpoint IPFS API multiaddr or endpoint string
     */
    public void setIpfsApiEndpoint(String endpoint) {
        this.ipfsApiEndpoint = endpoint;
    }
    
    /**
     * Returns the configured IPFS API endpoint.
     *
     * @return the configured endpoint, or {@code null} when none has been set
     */
    public String getIpfsApiEndpoint() {
        return ipfsApiEndpoint;
    }

    /**
     * Sets the IPFS Files namespace root used for Oak-managed metadata.
     *
     * @param ipfsFilesRoot MFS root path where Oak should persist mappings
     */
    public void setIpfsFilesRoot(String ipfsFilesRoot) {
        this.ipfsFilesRoot = normalizeRoot(ipfsFilesRoot);
    }

    /**
     * Returns the configured IPFS Files namespace root.
     *
     * @return MFS root path used for Oak metadata
     */
    public String getIpfsFilesRoot() {
        return ipfsFilesRoot;
    }
    
    /**
     * Returns the cached CID for a given identifier.
     *
     * @param identifier Oak data identifier to resolve
     * @return the cached CID, or {@code null} when no mapping is known
     */
    public String getCID(DataIdentifier identifier) {
        try {
            return resolveCid(identifier);
        } catch (DataStoreException e) {
            LOG.debug("Failed to resolve CID for {}: {}", identifier, e.getMessage());
            return null;
        }
    }
    
    /**
     * Get all CID mappings (Oak blob ID → IPFS CID).
     * 
     * @return Map of Oak blob IDs (as hex strings) to IPFS CIDs
     */
    public Map<String, String> getAllCIDMappings() {
        Map<String, String> mappings = new LinkedHashMap<>();
        try {
            for (DataIdentifier identifier : loadPersistedIdentifiers()) {
                String cid = resolveCid(identifier);
                if (cid != null) {
                    mappings.put(identifier.toString(), cid);
                }
            }
        } catch (DataStoreException e) {
            LOG.debug("Failed to load persisted CID mappings: {}", e.getMessage());
        }
        for (Map.Entry<DataIdentifier, String> entry : cidCache.entrySet()) {
            mappings.putIfAbsent(entry.getKey().toString(), entry.getValue());
        }
        return mappings;
    }

    @Override
    public byte[] getOrCreateReferenceKey() throws DataStoreException {
        if (secret != null && secret.length > 0) {
            return secret;
        }

        synchronized (this) {
            if (secret != null && secret.length > 0) {
                return secret;
            }

            if (metadataRecordExists(REFERENCE_KEY)) {
                secret = readMetadataBytes(REFERENCE_KEY);
            } else {
                byte[] key = super.getOrCreateReferenceKey();
                addMetadataRecord(new ByteArrayInputStream(key), REFERENCE_KEY);
                secret = readMetadataBytes(REFERENCE_KEY);
            }
            return secret;
        }
    }

    private void ensureNamespace() throws Exception {
        ipfs.ensureDirectory(ipfsFilesRoot);
        ipfs.ensureDirectory(blobIndexDirectory());
        ipfs.ensureDirectory(contentDirectory());
        ipfs.ensureDirectory(metadataDirectory());
    }

    private String resolveCid(DataIdentifier identifier) throws DataStoreException {
        String cached = cidCache.get(identifier);
        if (cached != null) {
            return cached;
        }

        String persisted = readPersistedCid(identifier);
        if (persisted != null) {
            cidCache.put(identifier, persisted);
        }
        return persisted;
    }

    private String readPersistedCid(DataIdentifier identifier) throws DataStoreException {
        if (ipfs == null) {
            return null;
        }
        try {
            String path = blobIndexPath(identifier);
            if (!ipfs.fileExists(path)) {
                return null;
            }
            return trimToNull(new String(ipfs.readFile(path), StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new DataStoreException("Failed to read CID mapping for " + identifier, e);
        }
    }

    private void persistCidMapping(DataIdentifier identifier, String cid) throws Exception {
        ensureNamespace();
        ipfs.writeFile(blobIndexPath(identifier), cid.getBytes(StandardCharsets.UTF_8));
    }

    private void persistContentReference(DataIdentifier identifier, String cid) throws Exception {
        ensureNamespace();
        ipfs.linkCid(cid, contentPath(identifier));
    }

    private void deleteCidMapping(DataIdentifier identifier) throws Exception {
        String path = blobIndexPath(identifier);
        if (ipfs.fileExists(path)) {
            ipfs.deleteFile(path);
        }
        cidCache.remove(identifier);
    }

    private void deleteCidMappingQuietly(DataIdentifier identifier) {
        try {
            deleteCidMapping(identifier);
        } catch (Exception e) {
            LOG.debug("Failed to clean up CID mapping for {} after write error: {}", identifier, e.getMessage());
        }
    }

    private void deleteContentReference(DataIdentifier identifier) throws Exception {
        String path = contentPath(identifier);
        if (ipfs.fileExists(path)) {
            ipfs.deleteFile(path);
        }
    }

    private void deleteContentReferenceQuietly(DataIdentifier identifier) {
        try {
            deleteContentReference(identifier);
        } catch (Exception e) {
            LOG.debug("Failed to clean up content reference for {} after write error: {}", identifier, e.getMessage());
        }
    }

    private List<DataIdentifier> loadPersistedIdentifiers() throws DataStoreException {
        List<DataIdentifier> identifiers = new ArrayList<>();
        if (ipfs == null) {
            return identifiers;
        }
        try {
            for (String encodedName : ipfs.listFiles(blobIndexDirectory())) {
                identifiers.add(new DataIdentifier(decodeName(encodedName)));
            }
            return identifiers;
        } catch (Exception e) {
            throw new DataStoreException("Failed to list persisted blob identifiers", e);
        }
    }

    private List<String> listMetadataNames(String prefix) {
        List<String> names = new ArrayList<>();
        if (ipfs == null) {
            return names;
        }
        try {
            for (String encodedName : ipfs.listFiles(metadataDirectory())) {
                String name = decodeName(encodedName);
                if (name.startsWith(prefix)) {
                    names.add(name);
                }
            }
        } catch (Exception e) {
            LOG.error("Failed to list metadata records with prefix {}", prefix, e);
        }
        return names;
    }

    private byte[] readMetadataBytes(String name) throws DataStoreException {
        requireName(name);
        if (ipfs == null) {
            throw new DataStoreException("IPFS backend is not initialized");
        }
        try {
            return ipfs.readFile(metadataPath(name));
        } catch (Exception e) {
            throw new DataStoreException("Failed to read metadata: " + name, e);
        }
    }

    private long getMetadataLength(String name) throws DataStoreException {
        try {
            return ipfs.fileSize(metadataPath(name));
        } catch (Exception e) {
            throw new DataStoreException("Failed to get metadata length for " + name, e);
        }
    }

    private String blobIndexPath(DataIdentifier identifier) {
        return joinPath(blobIndexDirectory(), encodeName(identifier.toString()));
    }

    private String metadataPath(String name) {
        return joinPath(metadataDirectory(), encodeName(name));
    }

    private String blobIndexDirectory() {
        return joinPath(ipfsFilesRoot, "blob-index");
    }

    private String contentPath(DataIdentifier identifier) {
        return joinPath(contentDirectory(), encodeName(identifier.toString()));
    }

    private String contentDirectory() {
        return joinPath(ipfsFilesRoot, "content");
    }

    private String metadataDirectory() {
        return joinPath(ipfsFilesRoot, "metadata");
    }

    private static String encodeName(String value) {
        return Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeName(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static void requireInput(InputStream input) {
        Objects.requireNonNull(input, "input should not be null");
    }

    private static void requireFile(File input) {
        Objects.requireNonNull(input, "input should not be null");
    }

    private static void requireName(String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("name should not be empty");
        }
    }

    private static void requirePrefix(String prefix) {
        if (prefix == null) {
            throw new IllegalArgumentException("prefix should not be null");
        }
    }

    private static String normalizeRoot(String value) {
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

    private static String joinPath(String root, String child) {
        return "/".equals(root) ? "/" + child : root + "/" + child;
    }

    private static boolean isNotPinned(Exception e) {
        Throwable current = e;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String lower = message.toLowerCase(Locale.ROOT);
                if (lower.contains("not pinned")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }
    
    /**
     * {@link DataRecord} view backed by a cached IPFS CID.
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
                    // Cache the block size on first access to avoid repeated stat calls.
                    String cid = backend.resolveCid(identifier);
                    if (cid != null) {
                        Map<String, Object> stat = backend.ipfs.blockStat(cid);
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
                // IPFS content is immutable, so expose backend start time as a
                // stable synthetic modification timestamp.
                lastModified = backend.startTime.getTime();
            }
            return lastModified;
        }
    }

    private static class IPFSMetadataRecord extends AbstractDataRecord {

        private final IPFSBackend backend;
        private final String name;
        private long length = -1;
        private long lastModified = -1;

        private IPFSMetadataRecord(IPFSBackend backend, String name) {
            super(backend, new DataIdentifier(name));
            this.backend = backend;
            this.name = name;
        }

        @Override
        public InputStream getStream() throws DataStoreException {
            return new ByteArrayInputStream(backend.readMetadataBytes(name));
        }

        @Override
        public long getLength() throws DataStoreException {
            if (length == -1) {
                length = backend.getMetadataLength(name);
            }
            return length;
        }

        @Override
        public long getLastModified() {
            if (lastModified == -1) {
                lastModified = backend.startTime != null ? backend.startTime.getTime() : 0L;
            }
            return lastModified;
        }
    }
}
