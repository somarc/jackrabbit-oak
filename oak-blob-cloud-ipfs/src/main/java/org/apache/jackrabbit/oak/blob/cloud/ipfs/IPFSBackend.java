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
import org.apache.jackrabbit.oak.spi.blob.data.DataIdentifier;
import org.apache.jackrabbit.oak.spi.blob.data.DataRecord;
import org.apache.jackrabbit.oak.spi.blob.data.DataStoreException;
import org.apache.jackrabbit.oak.spi.blob.AbstractDataRecord;
import org.apache.jackrabbit.oak.spi.blob.AbstractSharedBackend;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * {@link AbstractSharedBackend} implementation that stores Oak binaries in
 * IPFS.
 *
 * <p>The backend imports and pins content through the IPFS HTTP API and keeps
 * its durable state in the IPFS Files namespace (MFS) under
 * {@code ipfsFilesRoot}:</p>
 * <ul>
 *     <li>{@code blob-index/<id>}: the CID of the blob (unchanged since the
 *     first release, so older builds can still read it);</li>
 *     <li>{@code blob-attrs/<id>}: the blob length and last-modified time,
 *     which Oak's DataStore garbage collector compares against;</li>
 *     <li>{@code content/<id>}: a link to the CID that keeps the blocks
 *     retained independently of pins;</li>
 *     <li>{@code metadata/<name>} and {@code metadata-attrs/<name>}: shared
 *     DataStore metadata records and their last-modified time.</li>
 * </ul>
 *
 * <p>Entries written before {@code blob-attrs} existed report their UnixFS
 * file size and the current time as last-modified, which keeps them out of
 * garbage collection until they are written again.</p>
 */
public class IPFSBackend extends AbstractSharedBackend {

    private static final Logger LOG = LoggerFactory.getLogger(IPFSBackend.class);

    private static final String DEFAULT_IPFS_API_ENDPOINT = "/ip4/127.0.0.1/tcp/5001";
    private static final String DEFAULT_IPFS_FILES_ROOT = "/oak/ipfs";
    private static final String REFERENCE_KEY = "reference.key";
    private static final String LENGTH = "length";
    private static final String LAST_MODIFIED = "lastModified";
    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

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
            if (ipfsApiEndpoint == null || ipfsApiEndpoint.isEmpty()) {
                ipfsApiEndpoint = DEFAULT_IPFS_API_ENDPOINT;
            }
            ipfs = ipfsClientFactory.create(ipfsApiEndpoint);

            // Version lookup is informational; the namespace setup below is
            // the real connectivity check.
            try {
                LOG.info("Connected to IPFS node {} (version info: {})", ipfsApiEndpoint, ipfs.version());
            } catch (Exception e) {
                LOG.warn("Connected to IPFS node {} (could not get version: {})", ipfsApiEndpoint, e.getMessage());
            }

            for (String directory : List.of(blobIndexDirectory(), blobAttrsDirectory(), contentDirectory(),
                    metadataDirectory(), metadataAttrsDirectory())) {
                ipfs.ensureDirectory(directory);
            }
        } catch (Exception e) {
            throw new DataStoreException("Failed to initialize IPFS backend: " + e.getMessage(), e);
        }
    }

    @Override
    public void write(DataIdentifier identifier, File file) throws DataStoreException {
        try {
            if (touchIfStored(identifier, file.length())) {
                return;
            }

            List<MerkleNode> nodes = ipfs.add(new NamedStreamable.FileWrapper(file));
            if (nodes.isEmpty()) {
                throw new DataStoreException("IPFS add returned empty result for " + identifier);
            }
            String cid = nodes.get(0).hash.toString();

            try {
                writeBlobAttributes(identifier, file.length(), System.currentTimeMillis());
                ipfs.writeFile(blobIndexPath(identifier), cid.getBytes(StandardCharsets.UTF_8));
                ipfs.linkCid(cid, contentPath(identifier));
                cidCache.put(identifier, cid);
            } catch (Exception e) {
                deleteQuietly(contentPath(identifier));
                deleteQuietly(blobAttrsPath(identifier));
                deleteQuietly(blobIndexPath(identifier));
                cidCache.remove(identifier);
                try {
                    ipfs.pinRemove(cid);
                } catch (Exception cleanupFailure) {
                    LOG.warn("Failed to unpin CID {} after mapping persistence error", cid, cleanupFailure);
                }
                throw e;
            }
            LOG.debug("Stored {} ({} bytes) as CID {}", identifier, file.length(), cid);
        } catch (DataStoreException e) {
            throw e;
        } catch (Exception e) {
            throw new DataStoreException("IPFS write failed for " + identifier + ": " + e.getMessage(), e);
        }
    }

    /**
     * Mirrors the S3 and Azure backends: rewriting a stored blob only refreshes
     * its last-modified time, and a different length for the same identifier is
     * a collision.
     */
    private boolean touchIfStored(DataIdentifier identifier, long length) throws Exception {
        if (resolveCid(identifier) == null || !ipfs.fileExists(contentPath(identifier))) {
            return false;
        }
        Long storedLength = longAttribute(readAttributes(blobAttrsPath(identifier)), LENGTH);
        if (storedLength != null && storedLength != length) {
            throw new DataStoreException("Collision: " + identifier + " is stored with length "
                + storedLength + ", not " + length);
        }
        writeBlobAttributes(identifier, length, System.currentTimeMillis());
        return true;
    }

    @Override
    public InputStream read(DataIdentifier identifier) throws DataStoreException {
        String cid = resolveCid(identifier);
        if (cid == null) {
            throw new DataStoreException("CID not found for identifier: " + identifier);
        }
        try {
            InputStream content = ipfs.cat(cid);
            return SHA256_HEX.matcher(identifier.toString()).matches()
                ? new Sha256VerifyingInputStream(content, identifier.toString())
                : content;
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
        return loadPersistedIdentifiers().iterator();
    }

    /**
     * Records are returned with their attributes loaded. If they cannot be read
     * the enumeration fails, because Oak would otherwise encode an unknown
     * length into the blob id and treat a referenced blob as garbage.
     */
    @Override
    public Iterator<DataRecord> getAllRecords() throws DataStoreException {
        Iterator<DataIdentifier> identifiers = getAllIdentifiers();
        return new Iterator<>() {
            @Override
            public boolean hasNext() {
                return identifiers.hasNext();
            }

            @Override
            public DataRecord next() {
                IPFSDataRecord record = new IPFSDataRecord(IPFSBackend.this, identifiers.next());
                try {
                    record.loadAttributes();
                } catch (DataStoreException e) {
                    throw new IllegalStateException("Cannot enumerate " + record.getIdentifier(), e);
                }
                return record;
            }
        };
    }

    /**
     * A blob exists when its CID mapping is stored. Failures to reach IPFS are
     * thrown rather than reported as absence.
     */
    @Override
    public boolean exists(DataIdentifier identifier) throws DataStoreException {
        return resolveCid(identifier) != null;
    }

    @Override
    public void close() throws DataStoreException {
        // The HTTP client has no explicit close hook; clear local state instead.
        cidCache.clear();
        secret = null;
    }

    /**
     * Removes the retention link, then the pin, and only then the mapping, so a
     * delete that fails part-way can be retried.
     */
    @Override
    public void deleteRecord(DataIdentifier identifier) throws DataStoreException {
        try {
            String cid = resolveCid(identifier);
            if (cid == null) {
                LOG.warn("Cannot delete - CID not found for: {}", identifier);
                return;
            }

            ipfs.deleteFile(contentPath(identifier));
            try {
                ipfs.pinRemove(cid);
            } catch (Exception e) {
                if (!isNotPinned(e)) {
                    throw e;
                }
                LOG.debug("CID {} was not directly pinned at delete time", cid);
            }
            ipfs.deleteFile(blobAttrsPath(identifier));
            ipfs.deleteFile(blobIndexPath(identifier));
            cidCache.remove(identifier);
            // Blocks are reclaimed by the node's own garbage collection.
            LOG.debug("Unpinned {} (CID {})", identifier, cid);
        } catch (Exception e) {
            throw new DataStoreException("IPFS delete failed for " + identifier + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void addMetadataRecord(InputStream input, String name) throws DataStoreException {
        requireInput(input);
        requireName(name);
        try {
            ipfs.writeFile(metadataPath(name), input.readAllBytes());
            ipfs.writeFile(metadataAttrsPath(name), attributes(Map.of(LAST_MODIFIED, System.currentTimeMillis())));
            LOG.debug("Added metadata record {}", name);
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
            records.add(new IPFSMetadataRecord(this, name));
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
            ipfs.deleteFile(metadataAttrsPath(name));
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
                ipfs.deleteFile(metadataAttrsPath(name));
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
            throw new IllegalStateException("Failed to check metadata record " + name, e);
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

            byte[] stored = readMetadataIfPresent(REFERENCE_KEY);
            if (stored == null) {
                addMetadataRecord(new ByteArrayInputStream(super.getOrCreateReferenceKey()), REFERENCE_KEY);
                stored = readMetadataBytes(REFERENCE_KEY);
            }
            secret = stored;
            return secret;
        }
    }

    private String resolveCid(DataIdentifier identifier) throws DataStoreException {
        String cached = cidCache.get(identifier);
        if (cached != null) {
            return cached;
        }
        if (ipfs == null) {
            return null;
        }
        String persisted;
        try {
            byte[] mapping = ipfs.readFile(blobIndexPath(identifier));
            persisted = mapping == null ? null : trimToNull(new String(mapping, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new DataStoreException("Failed to read CID mapping for " + identifier, e);
        }
        if (persisted != null) {
            cidCache.put(identifier, persisted);
        }
        return persisted;
    }

    private void writeBlobAttributes(DataIdentifier identifier, long length, long lastModified) throws Exception {
        Map<String, Long> values = new LinkedHashMap<>();
        values.put(LENGTH, length);
        values.put(LAST_MODIFIED, lastModified);
        ipfs.writeFile(blobAttrsPath(identifier), attributes(values));
    }

    /**
     * Returns a numeric attribute, or {@code null} when the attributes or the
     * value are missing or unreadable, which callers treat as a legacy entry.
     */
    private static Long longAttribute(Properties attributes, String key) {
        if (attributes == null) {
            return null;
        }
        try {
            return Long.valueOf(attributes.getProperty(key));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Properties readAttributes(String path) throws Exception {
        byte[] data = ipfs.readFile(path);
        if (data == null) {
            return null;
        }
        Properties properties = new Properties();
        properties.load(new StringReader(new String(data, StandardCharsets.UTF_8)));
        return properties;
    }

    private static byte[] attributes(Map<String, Long> values) {
        StringBuilder text = new StringBuilder();
        values.forEach((key, value) -> text.append(key).append('=').append(value).append('\n'));
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    private void deleteQuietly(String path) {
        try {
            ipfs.deleteFile(path);
        } catch (Exception e) {
            LOG.debug("Failed to clean up {} after write error: {}", path, e.getMessage());
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
            // An empty list would let shared GC sweep without every repository's references.
            throw new IllegalStateException("Failed to list metadata records with prefix " + prefix, e);
        }
        return names;
    }

    private byte[] readMetadataBytes(String name) throws DataStoreException {
        byte[] data = readMetadataIfPresent(name);
        if (data == null) {
            throw new DataStoreException("Metadata record not found: " + name);
        }
        return data;
    }

    private byte[] readMetadataIfPresent(String name) throws DataStoreException {
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

    private String blobIndexPath(DataIdentifier identifier) {
        return joinPath(blobIndexDirectory(), encodeName(identifier.toString()));
    }

    private String blobAttrsPath(DataIdentifier identifier) {
        return joinPath(blobAttrsDirectory(), encodeName(identifier.toString()));
    }

    private String contentPath(DataIdentifier identifier) {
        return joinPath(contentDirectory(), encodeName(identifier.toString()));
    }

    private String metadataPath(String name) {
        return joinPath(metadataDirectory(), encodeName(name));
    }

    private String metadataAttrsPath(String name) {
        return joinPath(metadataAttrsDirectory(), encodeName(name));
    }

    private String blobIndexDirectory() {
        return joinPath(ipfsFilesRoot, "blob-index");
    }

    private String blobAttrsDirectory() {
        return joinPath(ipfsFilesRoot, "blob-attrs");
    }

    private String contentDirectory() {
        return joinPath(ipfsFilesRoot, "content");
    }

    private String metadataDirectory() {
        return joinPath(ipfsFilesRoot, "metadata");
    }

    private String metadataAttrsDirectory() {
        return joinPath(ipfsFilesRoot, "metadata-attrs");
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
            if (message != null && message.toLowerCase(Locale.ROOT).contains("not pinned")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * Fails the read at end of stream when the content does not hash to the
     * SHA-256 identifier, so a truncated or wrong payload is never cached as
     * the blob.
     */
    private static final class Sha256VerifyingInputStream extends FilterInputStream {

        private final String expectedHex;
        private final MessageDigest digest;
        private boolean verified;
        private IOException failure;

        private Sha256VerifyingInputStream(InputStream in, String expectedHex) {
            super(in);
            this.expectedHex = expectedHex;
            try {
                this.digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public int read() throws IOException {
            if (failure != null) {
                throw failure;
            }
            int b = in.read();
            if (b == -1) {
                verify();
            } else {
                digest.update((byte) b);
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (failure != null) {
                throw failure;
            }
            int count = in.read(buffer, offset, length);
            if (count == -1) {
                verify();
            } else {
                digest.update(buffer, offset, count);
            }
            return count;
        }

        @Override
        public long skip(long n) throws IOException {
            byte[] buffer = new byte[8192];
            long skipped = 0;
            while (skipped < n) {
                int count = read(buffer, 0, (int) Math.min(buffer.length, n - skipped));
                if (count == -1) {
                    break;
                }
                skipped += count;
            }
            return skipped;
        }

        @Override
        public boolean markSupported() {
            return false;
        }

        @Override
        public void mark(int readLimit) {
        }

        @Override
        public void reset() throws IOException {
            throw new IOException("mark/reset not supported");
        }

        private void verify() throws IOException {
            if (verified) {
                return;
            }
            verified = true;
            String actual = HexFormat.of().formatHex(digest.digest());
            if (!actual.equals(expectedHex)) {
                failure = new IOException("IPFS content for " + expectedHex + " hashes to " + actual
                    + "; the stream was truncated or the CID mapping is wrong");
                throw failure;
            }
        }
    }

    /**
     * {@link DataRecord} view whose length and last-modified time come from the
     * blob's stored attributes.
     */
    private static class IPFSDataRecord extends AbstractDataRecord {

        private final IPFSBackend backend;
        private final DataIdentifier identifier;
        private long length = -1;
        private long lastModified = -1;

        IPFSDataRecord(IPFSBackend backend, DataIdentifier identifier) {
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
            loadAttributes();
            return length;
        }

        @Override
        public long getLastModified() {
            try {
                loadAttributes();
            } catch (DataStoreException e) {
                // Unknown age must never make a blob look old enough to collect.
                LOG.warn("Failed to read attributes of {}; reporting it as just modified", identifier, e);
                return System.currentTimeMillis();
            }
            return lastModified;
        }

        synchronized void loadAttributes() throws DataStoreException {
            if (lastModified != -1) {
                return;
            }
            try {
                Properties attributes = backend.readAttributes(backend.blobAttrsPath(identifier));
                Long storedLength = longAttribute(attributes, LENGTH);
                Long storedLastModified = longAttribute(attributes, LAST_MODIFIED);
                if (storedLength != null && storedLastModified != null) {
                    length = storedLength;
                    lastModified = storedLastModified;
                    return;
                }
                String cid = backend.resolveCid(identifier);
                long now = System.currentTimeMillis();
                if (cid == null) {
                    length = -1;
                    lastModified = now;
                    return;
                }
                length = backend.ipfs.fileSize("/ipfs/" + cid);
                lastModified = now;
                // Recording "now" once lets a legacy blob age out of the GC window
                // instead of looking new forever; it is never earlier than the
                // real write time.
                try {
                    backend.writeBlobAttributes(identifier, length, now);
                } catch (Exception e) {
                    LOG.debug("Could not record attributes of legacy blob {}: {}", identifier, e.getMessage());
                }
            } catch (Exception e) {
                throw new DataStoreException("Failed to get length for " + identifier, e);
            }
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
                try {
                    length = backend.ipfs.fileSize(backend.metadataPath(name));
                } catch (Exception e) {
                    throw new DataStoreException("Failed to get metadata length for " + name, e);
                }
            }
            return length;
        }

        @Override
        public long getLastModified() {
            if (lastModified == -1) {
                try {
                    Long stored = longAttribute(backend.readAttributes(backend.metadataAttrsPath(name)), LAST_MODIFIED);
                    lastModified = stored == null ? System.currentTimeMillis() : stored;
                } catch (Exception e) {
                    LOG.warn("Failed to read attributes of metadata record {}; reporting it as just modified", name, e);
                    return System.currentTimeMillis();
                }
            }
            return lastModified;
        }
    }
}
