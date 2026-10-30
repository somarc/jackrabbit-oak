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
import org.apache.jackrabbit.oak.spi.blob.data.DataRecord;
import org.apache.jackrabbit.oak.spi.blob.data.DataStoreException;
import org.apache.jackrabbit.oak.spi.blob.AbstractDataRecord;
import org.apache.jackrabbit.oak.spi.blob.AbstractSharedBackend;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
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
import java.util.Collections;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
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
 *
 * <p>When an identifier is not in the local index, an optional
 * {@link CidResolver} is asked; a blob it knows is adopted into the local
 * index (mapping and attributes, without a retention link) and read through
 * this node, which fetches the blocks from its peers.</p>
 */
public class IPFSBackend extends AbstractSharedBackend {

    private static final Logger LOG = LoggerFactory.getLogger(IPFSBackend.class);

    private static final String DEFAULT_IPFS_FILES_ROOT = "/oak/ipfs";
    private static final String REFERENCE_KEY = "reference.key";
    private static final String LENGTH = "length";
    private static final String LAST_MODIFIED = "lastModified";
    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

    /**
     * Identifier-to-CID mappings kept in memory; older ones are read from the
     * IPFS Files index again when needed.
     */
    static final int CID_CACHE_ENTRIES = 100_000;

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
     * Credentials, timeouts and retry policy for the Kubo RPC client.
     */
    private IpfsClientSettings clientSettings = IpfsClientSettings.of(null);

    /**
     * In-memory cache for blob identifier-to-CID mappings.
     */
    private final Map<DataIdentifier, String> cidCache = Collections.synchronizedMap(
        new LinkedHashMap<>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<DataIdentifier, String> eldest) {
                return size() > CID_CACHE_ENTRIES;
            }
        });

    private final IpfsStatistics statistics = new IpfsStatistics();

    /**
     * The result of the last network check, or {@code null} if none ran.
     */
    private volatile IpfsNetworkPosture.Report networkReport;

    /**
     * Root directory in the IPFS Files namespace used for Oak metadata.
     */
    private String ipfsFilesRoot = DEFAULT_IPFS_FILES_ROOT;

    private volatile byte[] secret;

    private volatile CidResolver cidResolver;

    private IpfsClusterSettings clusterSettings = IpfsClusterSettings.disabled();

    /**
     * Replicates writes through IPFS Cluster when configured, otherwise {@code null}.
     */
    private IpfsClusterClient cluster;

    /**
     * Encrypts new blobs and decrypts encrypted ones when set, otherwise
     * {@code null}.
     */
    private volatile BlobKeyProvider blobKeys;

    private IpfsNetworkPosture.Policy networkPolicy = IpfsNetworkPosture.Policy.WARN;

    /**
     * The node's swarm listener as reachable from Oak ({@code host:port}), used
     * to prove a private network key; {@code null} skips that check.
     */
    private String swarmProbeAddress;

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
            IpfsClientSettings settings = clientSettings.withEndpoint(ipfsApiEndpoint);
            ipfsApiEndpoint = settings.endpoint();
            ipfs = ipfsClientFactory.create(settings);
            if (clusterSettings.enabled()) {
                cluster = new IpfsClusterClient(clusterSettings, settings);
                cluster.ping();
                LOG.info("Writes are acknowledged once {} IPFS Cluster peers hold them (at most {}), via {}",
                    clusterSettings.replicationMin(), clusterSettings.replicationMax(), clusterSettings.endpoint());
            }

            if (networkPolicy != IpfsNetworkPosture.Policy.OFF) {
                IpfsNetworkPosture.Report report;
                try {
                    report = IpfsNetworkPosture.inspect(ipfs, swarmProbeAddress, settings.connectTimeout());
                } catch (Exception e) {
                    if (networkPolicy == IpfsNetworkPosture.Policy.PRIVATE) {
                        throw new IOException("ipfsNetworkPolicy=private, but the IPFS node's network settings"
                            + " could not be read: " + e.getMessage(), e);
                    }
                    LOG.warn("Could not check whether IPFS node {} is private: {}", ipfsApiEndpoint, e.toString());
                    report = null;
                }
                if (report != null) {
                    networkReport = report;
                    IpfsNetworkPosture.enforce(networkPolicy, report, ipfsApiEndpoint);
                }
            }

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
            store(identifier, file);
            statistics.written(file.length());
        } catch (DataStoreException e) {
            statistics.writeFailed();
            throw e;
        }
    }

    private void store(DataIdentifier identifier, File file) throws DataStoreException {
        try {
            if (touchIfStored(identifier, file.length())) {
                replicate(identifier, resolveCid(identifier));
                return;
            }

            String cid = blobKeys == null ? ipfs.add(file) : addEncrypted(identifier, file);

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
                if (cluster == null || blobKeys != null) {
                    // With IPFS Cluster the node's pin may be a cluster replica, since Kubo pins have no
                    // owner; a ciphertext CID is new to every write, so nothing else can hold it yet.
                    try {
                        ipfs.pinRemove(cid);
                    } catch (Exception cleanupFailure) {
                        LOG.warn("Failed to unpin CID {} after mapping persistence error", cid, cleanupFailure);
                    }
                }
                throw e;
            }
            LOG.debug("Stored {} ({} bytes) as CID {}", identifier, file.length(), cid);
            replicate(identifier, cid);
        } catch (DataStoreException e) {
            throw e;
        } catch (Exception e) {
            throw new DataStoreException("IPFS write failed for " + identifier + ": " + e.getMessage(), e);
        }
    }

    /**
     * Imports an encrypted copy of the file. The ciphertext is bound to the
     * identifier and written to an owner-only temporary file, outside the
     * DataStore's staging directory so that a crash cannot leave it there to be
     * uploaded as a blob, and deleted once imported.
     */
    private String addEncrypted(DataIdentifier identifier, File file) throws Exception {
        File ciphertext = Files.createTempFile("oak-ipfs-", ".enc").toFile();
        try {
            BlobEncryption.encrypt(file, ciphertext, identifier.toString(), blobKeys);
            return ipfs.add(ciphertext);
        } finally {
            Files.deleteIfExists(ciphertext.toPath());
        }
    }

    /**
     * Waits until IPFS Cluster holds the blob on enough peers, when configured.
     * A blob stored locally whose replication failed stays stored; writing it
     * again retries the replication.
     */
    private void replicate(DataIdentifier identifier, String cid) throws IOException {
        if (cluster != null) {
            long start = System.nanoTime();
            boolean replicated = false;
            try {
                cluster.pinReplicated(cid, identifier.toString());
                replicated = true;
            } finally {
                statistics.clusterPinned((System.nanoTime() - start) / 1_000_000, replicated);
            }
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
        try {
            InputStream content = open(identifier);
            statistics.read();
            return content;
        } catch (DataStoreException e) {
            statistics.readFailed();
            throw e;
        }
    }

    private InputStream open(DataIdentifier identifier) throws DataStoreException {
        String cid = resolveCid(identifier);
        if (cid == null) {
            throw new DataStoreException("CID not found for identifier: " + identifier);
        }
        InputStream content = null;
        try {
            content = ipfs.cat(cid);
            BlobKeyProvider keys = blobKeys;
            if (keys != null) {
                // Sniffing is safe: what comes out is checked against the identifier below.
                content = new BufferedInputStream(content);
                if (BlobEncryption.isEncrypted(content)) {
                    content = BlobEncryption.decrypt(content, identifier.toString(), keys);
                }
            }
            return SHA256_HEX.matcher(identifier.toString()).matches()
                ? new Sha256VerifyingInputStream(content, identifier.toString())
                : content;
        } catch (Exception e) {
            closeQuietly(content);
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
     * Removes the pin, then the retention link, and only then the mapping, so a
     * delete that fails part-way can be retried.
     *
     * <p>Kubo and IPFS Cluster keep one pin per CID without owners. Without a
     * cluster, only blobs stored through this node (those with a retention
     * link) are unpinned. With a cluster, its pin and the local one are removed
     * only when {@code ipfsClusterUnpinOnDelete} declares the cluster dedicated
     * to this repository; otherwise reclaiming the storage is an operator
     * action on the cluster pinset.</p>
     */
    @Override
    public void deleteRecord(DataIdentifier identifier) throws DataStoreException {
        try {
            delete(identifier);
            statistics.deleted();
        } catch (DataStoreException e) {
            statistics.deleteFailed();
            throw e;
        }
    }

    private void delete(DataIdentifier identifier) throws DataStoreException {
        try {
            String cid = resolveCid(identifier);
            if (cid == null) {
                LOG.warn("Cannot delete - CID not found for: {}", identifier);
                return;
            }

            if (cluster != null ? clusterSettings.unpinOnDelete() : ipfs.fileExists(contentPath(identifier))) {
                if (cluster != null) {
                    cluster.unpin(cid);
                }
                unpinLocally(cid);
            }
            ipfs.deleteFile(contentPath(identifier));
            ipfs.deleteFile(blobAttrsPath(identifier));
            ipfs.deleteFile(blobIndexPath(identifier));
            cidCache.remove(identifier);
            // Blocks are reclaimed by the node's own garbage collection.
            LOG.debug("Deleted {} (CID {})", identifier, cid);
        } catch (Exception e) {
            throw new DataStoreException("IPFS delete failed for " + identifier + ": " + e.getMessage(), e);
        }
    }

    private void unpinLocally(String cid) throws Exception {
        try {
            ipfs.pinRemove(cid);
        } catch (Exception e) {
            if (!isNotPinned(e)) {
                throw e;
            }
            LOG.debug("CID {} was not directly pinned at delete time", cid);
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
     * Sets the resolver consulted for identifiers missing from the local index.
     *
     * @param cidResolver the resolver, or {@code null} for none
     */
    public void setCidResolver(CidResolver cidResolver) {
        this.cidResolver = cidResolver;
    }

    /**
     * Reports whether the IPFS node and, when configured, the IPFS Cluster
     * answer now, with the encryption and network settings in effect and the
     * operation counts since startup. Never throws; failures are reported.
     */
    Map<String, Object> health() {
        Map<String, Object> health = new LinkedHashMap<>();
        Map<String, Object> kubo = new LinkedHashMap<>();
        kubo.put("endpoint", ipfsApiEndpoint);
        long start = System.nanoTime();
        try {
            kubo.put("version", String.valueOf(ipfs.version()));
            kubo.put("status", "UP");
        } catch (Exception e) {
            kubo.put("status", "DOWN");
            kubo.put("error", String.valueOf(e.getMessage()));
        }
        kubo.put("latencyMillis", (System.nanoTime() - start) / 1_000_000);
        health.put("kubo", kubo);

        Map<String, Object> clusterHealth = new LinkedHashMap<>();
        clusterHealth.put("configured", cluster != null);
        if (cluster != null) {
            clusterHealth.put("endpoint", clusterSettings.endpoint());
            clusterHealth.put("replicationMin", clusterSettings.replicationMin());
            clusterHealth.put("replicationMax", clusterSettings.replicationMax());
            try {
                cluster.ping();
                clusterHealth.put("status", "UP");
            } catch (Exception e) {
                clusterHealth.put("status", "DOWN");
                clusterHealth.put("error", String.valueOf(e.getMessage()));
            }
        }
        health.put("cluster", clusterHealth);

        BlobKeyProvider keys = blobKeys;
        Map<String, Object> encryption = new LinkedHashMap<>();
        encryption.put("enabled", keys != null);
        if (keys != null) {
            encryption.put("currentKeyId", keys.current().keyId());
        }
        health.put("encryption", encryption);

        Map<String, Object> network = new LinkedHashMap<>();
        network.put("policy", networkPolicy.name().toLowerCase(Locale.ROOT));
        IpfsNetworkPosture.Report report = networkReport;
        if (report != null) {
            network.put("privateSwarm", report.privateSwarm());
            network.put("exposures", report.exposures());
        }
        health.put("network", network);
        health.put("statistics", statistics.snapshot());
        health.put("status", "UP".equals(kubo.get("status"))
            && (cluster == null || "UP".equals(clusterHealth.get("status"))) ? "UP" : "DOWN");
        return health;
    }

    Map<String, Long> statistics() {
        return statistics.snapshot();
    }

    /**
     * Checks up to {@code limit} blobs of the local index for retention: with
     * IPFS Cluster, that at least the replication minimum of peers hold each
     * one pinned; without it, that blobs stored through this node are pinned
     * here. Blobs adopted from another node are counted, not checked, unless a
     * cluster is configured.
     *
     * @return counts and up to 100 identifiers per problem
     */
    Map<String, Object> verify(int limit) throws DataStoreException {
        List<DataIdentifier> identifiers = loadPersistedIdentifiers();
        List<String> underReplicated = new ArrayList<>();
        List<String> unpinned = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int checked = 0;
        int adopted = 0;
        int problems = 0;
        for (DataIdentifier identifier : identifiers) {
            if (checked >= limit) {
                break;
            }
            checked++;
            try {
                String cid = resolveCid(identifier);
                if (cid == null) {
                    continue;
                }
                if (cluster != null) {
                    long pinned = cluster.statuses(cid).stream().filter(peer -> "pinned".equals(peer.status())).count();
                    if (pinned < clusterSettings.replicationMin()) {
                        problems++;
                        addCapped(underReplicated, identifier + " (" + cid + ") pinned on " + pinned);
                    }
                } else if (!ipfs.fileExists(contentPath(identifier))) {
                    adopted++;
                } else if (!ipfs.isPinned(cid)) {
                    problems++;
                    addCapped(unpinned, identifier + " (" + cid + ")");
                }
            } catch (Exception e) {
                problems++;
                addCapped(errors, identifier + ": " + e.getMessage());
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", identifiers.size());
        result.put("checked", checked);
        result.put("complete", checked == identifiers.size());
        result.put("problems", problems);
        result.put("adoptedNotChecked", adopted);
        if (cluster != null) {
            result.put("replicationMin", clusterSettings.replicationMin());
            result.put("underReplicated", underReplicated);
        } else {
            result.put("unpinned", unpinned);
        }
        result.put("errors", errors);
        return result;
    }

    private static void addCapped(List<String> list, String entry) {
        if (list.size() < 100) {
            list.add(entry);
        }
    }

    /**
     * Sets the keys that encrypt new blobs and decrypt encrypted ones;
     * {@code null} stores and reads plaintext.
     */
    void setBlobKeyProvider(BlobKeyProvider blobKeys) {
        this.blobKeys = blobKeys;
    }

    /**
     * Sets how {@link #init()} treats a node that may expose content outside a
     * private network, and the swarm listener used to prove its network key.
     */
    void setNetworkPolicy(IpfsNetworkPosture.Policy networkPolicy, String swarmProbeAddress) {
        this.networkPolicy = networkPolicy;
        this.swarmProbeAddress = swarmProbeAddress == null || swarmProbeAddress.isBlank()
            ? null : swarmProbeAddress.trim();
    }

    /**
     * Sets the IPFS Cluster replication used on the next {@link #init()}.
     */
    void setClusterSettings(IpfsClusterSettings clusterSettings) {
        this.clusterSettings = clusterSettings;
    }

    /**
     * Sets the credentials, timeouts and retry policy used on the next
     * {@link #init()}; the endpoint is taken from
     * {@link #setIpfsApiEndpoint(String)}.
     */
    void setClientSettings(IpfsClientSettings clientSettings) {
        this.clientSettings = clientSettings;
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
        List<Map.Entry<DataIdentifier, String>> cached;
        synchronized (cidCache) {
            cached = new ArrayList<>(cidCache.entrySet());
        }
        for (Map.Entry<DataIdentifier, String> entry : cached) {
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
        if (persisted == null) {
            persisted = adopt(identifier);
        }
        if (persisted != null) {
            cidCache.put(identifier, persisted);
        }
        return persisted;
    }

    /**
     * Records a blob known only to the {@link CidResolver} in the local index,
     * attributes first, so GC on this node can see it. Nothing is pinned here:
     * retention belongs to the node that stored the blob or to its cluster.
     */
    private String adopt(DataIdentifier identifier) throws DataStoreException {
        CidResolver resolver = cidResolver;
        if (resolver == null) {
            return null;
        }
        CidResolver.Resolution resolution;
        try {
            resolution = resolver.resolve(identifier.toString());
        } catch (Exception e) {
            throw new DataStoreException("Failed to resolve CID for " + identifier, e);
        }
        if (resolution == null) {
            return null;
        }
        try {
            long length = resolution.length() >= 0 ? resolution.length() : storedLength(resolution.cid());
            writeBlobAttributes(identifier, length, System.currentTimeMillis());
            ipfs.writeFile(blobIndexPath(identifier), resolution.cid().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new DataStoreException("Failed to adopt " + identifier + " as " + resolution.cid(), e);
        }
        LOG.debug("Adopted {} as CID {} from the resolver", identifier, resolution.cid());
        return resolution.cid();
    }

    /**
     * Returns the length of the content at the CID: the plaintext length for an
     * encrypted blob when keys are configured, otherwise its UnixFS file size.
     */
    private long storedLength(String cid) throws Exception {
        long size = ipfs.fileSize("/ipfs/" + cid);
        if (blobKeys != null) {
            try (InputStream header = ipfs.cat(cid)) {
                long plaintext = BlobEncryption.plaintextLength(header, size);
                if (plaintext >= 0) {
                    return plaintext;
                }
            }
        }
        return size;
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

    private static void closeQuietly(InputStream stream) {
        if (stream != null) {
            try {
                stream.close();
            } catch (IOException e) {
                LOG.debug("Failed to close IPFS stream: {}", e.getMessage());
            }
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
