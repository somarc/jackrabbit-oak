# IPFS DataStore Robustness Analysis & Azure Blob Storage Comparison

**Date**: December 4, 2025  
**Purpose**: Ensure `oak-blob-cloud-ipfs` matches Azure Blob Storage production features within IPFS capabilities

---

## Executive Summary

Our **IPFSDataStore** implementation follows Oak's standard `AbstractSharedCachingDataStore` pattern (same as Azure/S3), providing a solid foundation. However, several **production-critical features** present in `AzureBlobStoreBackend` are missing or incomplete in our IPFS implementation.

**Key Findings**:
- ✅ **Architecture**: Correct (extends `AbstractSharedCachingDataStore`, implements `SharedBackend`)
- ✅ **Basic Operations**: Upload, download, delete work
- ❌ **Persistence**: CID mapping is in-memory only (not durable)
- ❌ **Reliability**: No retry logic, no timeout handling
- ❌ **Operations**: Missing metadata persistence, batch operations, async features
- ❌ **Monitoring**: No metrics, no health checks, minimal logging

**Priority Gaps**: CID persistence, retry logic, error handling, async operations.

---

## Architecture Comparison

### Azure Blob Storage Backend (Production Reference)

```
AzureDataStore (AbstractSharedCachingDataStore)
  │
  ├─► Local Cache (up to 64GB)
  │    └─► Staging download directory
  │
  └─► AzureBlobStoreBackend (AbstractSharedBackend)
       │
       ├─► CloudBlobContainer (Azure SDK)
       │    ├─► Upload with retry (exponential backoff)
       │    ├─► Download with retry + timeout
       │    ├─► Metadata operations (exists, list, stats)
       │    └─► Batch operations (parallel upload/download)
       │
       ├─► HttpClientProperties (connection pooling)
       ├─► Retry Policy (3 attempts, exponential backoff)
       ├─► Request Options (timeout: 120s)
       └─► Metrics & Logging (detailed telemetry)
```

**Key Features**:
- Persistent blob references (immutable, cloud-durable)
- Retry logic with exponential backoff
- Connection pooling + HTTP/2
- Batch operations (parallel uploads)
- Async operations (CompletableFuture)
- Shared Access Signatures (SAS tokens)
- Metadata records (persistent)
- Comprehensive error handling

---

### IPFS Backend (Current POC Implementation)

```
IPFSDataStore (AbstractSharedCachingDataStore)
  │
  ├─► Local Cache (up to 64GB)
  │
  └─► IPFSBackend (AbstractSharedBackend)
       │
       ├─► IPFS HTTP Client (java-ipfs-http-client)
       │    ├─► Upload (ipfs.add())
       │    ├─► Download (ipfs.cat())
       │    ├─► Pin (ipfs.pin.add/rm)
       │    └─► Basic operations (exists, stat)
       │
       ├─► In-Memory CID Cache (Map<DataIdentifier, String>)
       │    └─► ❌ Lost on restart!
       │
       └─► ❌ No retry logic
           ❌ No timeout handling
           ❌ No connection pooling
           ❌ No async operations
           ❌ No batch operations
```

**Missing Features** (vs Azure):
- ❌ Persistent CID mapping
- ❌ Retry logic
- ❌ Timeout configuration
- ❌ Connection pooling
- ❌ Async operations
- ❌ Batch uploads
- ❌ Comprehensive error handling
- ❌ Metrics & health checks

---

## Feature Gap Analysis

### 🔴 Critical Gaps (Production Blockers)

#### 1. **Persistent CID Mapping**

**Azure**:
```java
// Blobs are inherently persistent (stored in cloud)
CloudBlockBlob blob = container.getBlockBlobReference(identifier.toString());
blob.upload(inputStream, length); // Durable, replicated (99.999999999%)
```

**IPFS (Current)**:
```java
// CID stored in-memory only
cidCache.put(identifier, cid); // ❌ Lost on restart!
```

**Gap**: CID mappings disappear on restart. Cannot retrieve binaries after validator restarts.

**Solution**:
```java
/**
 * Store CID mappings in Oak itself (metadata nodes).
 * 
 * Structure:
 * /oak:blob-ipfs-mappings/
 *   ├─ abc123def456... → { cid: "QmXyz...", size: 1048576, uploaded: 1733421234000 }
 *   ├─ def456ghi789... → { cid: "QmAbc...", size: 2097152, uploaded: 1733421235000 }
 *   └─ ...
 */
public class PersistentCIDMappingService {
    private final NodeStore nodeStore;
    private static final String MAPPING_PATH = "/oak:blob-ipfs-mappings";
    
    public void storeCIDMapping(DataIdentifier identifier, String cid, long size) {
        NodeBuilder root = nodeStore.getRoot().builder();
        NodeBuilder mappings = getOrCreateNode(root, MAPPING_PATH);
        
        NodeBuilder entry = mappings.child(identifier.toString());
        entry.setProperty("cid", cid, Type.STRING);
        entry.setProperty("size", size, Type.LONG);
        entry.setProperty("uploaded", System.currentTimeMillis(), Type.LONG);
        
        nodeStore.merge(root, EmptyHook.INSTANCE, CommitInfo.EMPTY);
    }
    
    public String getCID(DataIdentifier identifier) {
        NodeState mappings = nodeStore.getRoot().getChildNode(MAPPING_PATH);
        if (!mappings.exists()) {
            return null;
        }
        
        NodeState entry = mappings.getChildNode(identifier.toString());
        if (!entry.exists()) {
            return null;
        }
        
        PropertyState cidProp = entry.getProperty("cid");
        return cidProp != null ? cidProp.getValue(Type.STRING) : null;
    }
    
    public void removeCIDMapping(DataIdentifier identifier) {
        NodeBuilder root = nodeStore.getRoot().builder();
        NodeBuilder mappings = root.getChildNode(MAPPING_PATH);
        
        if (mappings.exists()) {
            mappings.getChildNode(identifier.toString()).remove();
            nodeStore.merge(root, EmptyHook.INSTANCE, CommitInfo.EMPTY);
        }
    }
    
    // Load all CID mappings into memory cache on startup
    public Map<DataIdentifier, String> loadAllMappings() {
        Map<DataIdentifier, String> cache = new HashMap<>();
        NodeState mappings = nodeStore.getRoot().getChildNode(MAPPING_PATH);
        
        if (mappings.exists()) {
            for (ChildNodeEntry entry : mappings.getChildNodeEntries()) {
                PropertyState cidProp = entry.getNodeState().getProperty("cid");
                if (cidProp != null) {
                    DataIdentifier id = new DataIdentifier(entry.getName());
                    String cid = cidProp.getValue(Type.STRING);
                    cache.put(id, cid);
                }
            }
        }
        
        return cache;
    }
}
```

**Implementation**:
1. Add `PersistentCIDMappingService` class
2. Inject `NodeStore` into `IPFSBackend`
3. Call `storeCIDMapping()` after successful upload
4. Call `loadAllMappings()` in `init()`
5. Update `read()` to check persistent mappings if cache miss

**Timeline**: 2-3 days

---

#### 2. **Retry Logic with Exponential Backoff**

**Azure**:
```java
// Automatic retry with exponential backoff (Azure SDK)
BlobRequestOptions options = new BlobRequestOptions();
options.setRetryPolicyFactory(new RetryExponentialRetry(
    3000,  // deltaBackoffMs (initial delay)
    3       // maxAttempts
));

blob.upload(inputStream, length, null, options, null);
```

**IPFS (Current)**:
```java
// No retry - fails immediately on network error
List<MerkleNode> nodes = ipfs.add(fileWrapper); // ❌ Single attempt
```

**Gap**: Network glitches cause permanent failures. IPFS node restart = upload failures.

**Solution**:
```java
/**
 * Retry wrapper for IPFS operations.
 */
public class IPFSRetryUtil {
    private static final Logger LOG = LoggerFactory.getLogger(IPFSRetryUtil.class);
    
    private static final int MAX_ATTEMPTS = 3;
    private static final long INITIAL_BACKOFF_MS = 1000;
    private static final double BACKOFF_MULTIPLIER = 2.0;
    
    public static <T> T executeWithRetry(
            String operation,
            IPFSOperation<T> op
    ) throws DataStoreException {
        long backoffMs = INITIAL_BACKOFF_MS;
        
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                T result = op.execute();
                
                if (attempt > 1) {
                    LOG.info("✅ {} succeeded on attempt {}/{}", 
                        operation, attempt, MAX_ATTEMPTS);
                }
                
                return result;
                
            } catch (Exception e) {
                if (attempt == MAX_ATTEMPTS) {
                    LOG.error("❌ {} failed after {} attempts", operation, MAX_ATTEMPTS, e);
                    throw new DataStoreException("IPFS " + operation + " failed", e);
                }
                
                LOG.warn("⚠️  {} failed (attempt {}/{}), retrying in {}ms: {}", 
                    operation, attempt, MAX_ATTEMPTS, backoffMs, e.getMessage());
                
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new DataStoreException("Interrupted during retry", ie);
                }
                
                backoffMs = (long) (backoffMs * BACKOFF_MULTIPLIER);
            }
        }
        
        throw new DataStoreException("Unreachable code");
    }
    
    @FunctionalInterface
    public interface IPFSOperation<T> {
        T execute() throws Exception;
    }
}
```

**Usage**:
```java
@Override
public void write(DataIdentifier identifier, File file) throws DataStoreException {
    LOG.debug("📤 Uploading file to IPFS: {} ({} bytes)", identifier, file.length());
    
    String cid = IPFSRetryUtil.executeWithRetry("upload", () -> {
        NamedStreamable.FileWrapper fileWrapper = new NamedStreamable.FileWrapper(file);
        List<MerkleNode> nodes = ipfs.add(fileWrapper);
        
        if (nodes.isEmpty()) {
            throw new IOException("IPFS add returned empty result");
        }
        
        String resultCid = nodes.get(0).hash.toString();
        
        // Pin to ensure persistence
        ipfs.pin.add(Multihash.fromBase58(resultCid));
        
        return resultCid;
    });
    
    LOG.info("📦 Uploaded binary to IPFS: {} → CID: {}", identifier, cid);
    
    // Store mapping (persistent)
    persistentMappingService.storeCIDMapping(identifier, cid, file.length());
    
    // Cache the mapping (in-memory)
    cidCache.put(identifier, cid);
}
```

**Timeline**: 1 day

---

#### 3. **Timeout Configuration**

**Azure**:
```java
BlobRequestOptions options = new BlobRequestOptions();
options.setTimeoutIntervalInMs(120 * 1000); // 120 seconds
options.setMaximumExecutionTimeInMs(300 * 1000); // 5 minutes max

blob.upload(inputStream, length, null, options, null);
```

**IPFS (Current)**:
```java
// No timeout - hangs indefinitely if IPFS node is slow/stuck
ipfs.cat(Multihash.fromBase58(cid)); // ❌ No timeout
```

**Gap**: Slow IPFS operations can hang indefinitely, blocking threads.

**Solution**:
```java
/**
 * Timeout wrapper for IPFS operations.
 */
public class IPFSTimeoutExecutor {
    private static final Logger LOG = LoggerFactory.getLogger(IPFSTimeoutExecutor.class);
    
    private final ExecutorService executor = Executors.newCachedThreadPool(
        new ThreadFactoryBuilder()
            .setNameFormat("ipfs-timeout-%d")
            .setDaemon(true)
            .build()
    );
    
    public <T> T executeWithTimeout(
            String operation,
            long timeoutMs,
            IPFSOperation<T> op
    ) throws DataStoreException, TimeoutException {
        Future<T> future = executor.submit(() -> op.execute());
        
        try {
            T result = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            return result;
            
        } catch (TimeoutException e) {
            future.cancel(true);
            LOG.error("⏱️  {} timed out after {}ms", operation, timeoutMs);
            throw e;
            
        } catch (Exception e) {
            throw new DataStoreException("IPFS " + operation + " failed", e);
        }
    }
}
```

**Usage**:
```java
private final IPFSTimeoutExecutor timeoutExecutor = new IPFSTimeoutExecutor();
private static final long UPLOAD_TIMEOUT_MS = 120 * 1000; // 2 minutes
private static final long DOWNLOAD_TIMEOUT_MS = 60 * 1000; // 1 minute

@Override
public InputStream read(DataIdentifier identifier) throws DataStoreException {
    String cid = getCIDFromCacheOrPersistent(identifier);
    
    LOG.debug("📥 Fetching binary from IPFS: CID: {}", cid);
    
    byte[] content = timeoutExecutor.executeWithTimeout(
        "download",
        DOWNLOAD_TIMEOUT_MS,
        () -> ipfs.cat(Multihash.fromBase58(cid))
    );
    
    LOG.info("✅ Retrieved binary from IPFS: {} ({} bytes)", identifier, content.length);
    
    return new ByteArrayInputStream(content);
}
```

**Timeline**: 1 day

---

### 🟡 Important Gaps (Production Features)

#### 4. **Async Operations**

**Azure**:
```java
// Async upload with CompletableFuture
public CompletableFuture<Void> writeAsync(DataIdentifier identifier, File file) {
    return CompletableFuture.runAsync(() -> {
        try {
            write(identifier, file);
        } catch (DataStoreException e) {
            throw new CompletionException(e);
        }
    }, executorService);
}
```

**IPFS (Current)**:
```java
// All operations synchronous
// ❌ Blocks calling thread
```

**Gap**: Cannot pipeline uploads/downloads, wastes threads.

**Solution**:
```java
/**
 * Add async variants of key operations.
 */
private final ExecutorService asyncExecutor = Executors.newFixedThreadPool(
    Runtime.getRuntime().availableProcessors() * 2,
    new ThreadFactoryBuilder()
        .setNameFormat("ipfs-async-%d")
        .setDaemon(true)
        .build()
);

public CompletableFuture<Void> writeAsync(DataIdentifier identifier, File file) {
    return CompletableFuture.runAsync(() -> {
        try {
            write(identifier, file);
        } catch (DataStoreException e) {
            throw new CompletionException(e);
        }
    }, asyncExecutor);
}

public CompletableFuture<InputStream> readAsync(DataIdentifier identifier) {
    return CompletableFuture.supplyAsync(() -> {
        try {
            return read(identifier);
        } catch (DataStoreException e) {
            throw new CompletionException(e);
        }
    }, asyncExecutor);
}

// Batch upload with parallelism
public CompletableFuture<Void> writeBatch(List<Pair<DataIdentifier, File>> files) {
    List<CompletableFuture<Void>> futures = files.stream()
        .map(pair -> writeAsync(pair.getKey(), pair.getValue()))
        .collect(Collectors.toList());
    
    return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
}
```

**Timeline**: 2 days

---

#### 5. **Connection Pooling & HTTP/2**

**Azure**:
```java
// Azure SDK automatically uses connection pooling
// HTTP/2 when available, connection reuse, keep-alive
```

**IPFS (Current)**:
```java
// java-ipfs-http-client uses OkHttp internally
// Connection pooling enabled by default
// ✅ Already good! (inherited from library)
```

**Gap**: **None** - java-ipfs-http-client handles this well.

---

#### 6. **Batch Operations**

**Azure**:
```java
// Batch delete (parallel execution)
public void deleteAll(List<DataIdentifier> identifiers) throws DataStoreException {
    identifiers.parallelStream().forEach(id -> {
        try {
            deleteRecord(id);
        } catch (DataStoreException e) {
            LOG.error("Failed to delete {}", id, e);
        }
    });
}
```

**IPFS (Current)**:
```java
// Only single-item operations
// No batch delete, batch pin, etc.
```

**Gap**: Inefficient for bulk operations (e.g., GC deleting 1000 binaries).

**Solution**:
```java
/**
 * Batch operations for IPFS.
 */
public void deleteRecords(List<DataIdentifier> identifiers) throws DataStoreException {
    LOG.info("🗑️  Batch deleting {} binaries from IPFS", identifiers.size());
    
    List<String> cidsToUnpin = new ArrayList<>();
    
    // Collect CIDs
    for (DataIdentifier id : identifiers) {
        String cid = cidCache.get(id);
        if (cid != null) {
            cidsToUnpin.add(cid);
        }
    }
    
    // Batch unpin (parallel)
    cidsToUnpin.parallelStream().forEach(cid -> {
        try {
            ipfs.pin.rm(Multihash.fromBase58(cid));
            LOG.debug("📌 Unpinned: {}", cid);
        } catch (Exception e) {
            LOG.error("Failed to unpin {}", cid, e);
        }
    });
    
    // Remove from cache
    for (DataIdentifier id : identifiers) {
        cidCache.remove(id);
        persistentMappingService.removeCIDMapping(id);
    }
    
    LOG.info("✅ Batch deleted {} binaries", identifiers.size());
}

public void pinBatch(List<String> cids) {
    LOG.info("📌 Batch pinning {} CIDs", cids.size());
    
    cids.parallelStream().forEach(cid -> {
        try {
            ipfs.pin.add(Multihash.fromBase58(cid));
        } catch (Exception e) {
            LOG.error("Failed to pin {}", cid, e);
        }
    });
}
```

**Timeline**: 1 day

---

#### 7. **Metadata Records (Persistent)**

**Azure**:
```java
// Metadata stored as blobs in Azure
public void addMetadataRecord(InputStream input, String name) throws DataStoreException {
    String blobPath = "metadata/" + name;
    CloudBlockBlob blob = container.getBlockBlobReference(blobPath);
    blob.upload(input, -1); // Durable, replicated
}
```

**IPFS (Current)**:
```java
// Metadata stored in in-memory cidCache
cidCache.put(new DataIdentifier("META_" + name), cid); // ❌ Lost on restart!
```

**Gap**: Metadata mappings lost on restart (same issue as binary CIDs).

**Solution**:
```java
/**
 * Store metadata mappings persistently (same as binary CIDs).
 */
public void addMetadataRecord(InputStream input, String name) throws DataStoreException {
    try {
        LOG.debug("Adding metadata record: {}", name);
        
        byte[] data = input.readAllBytes();
        
        String cid = IPFSRetryUtil.executeWithRetry("upload-metadata", () -> {
            NamedStreamable.ByteArrayWrapper wrapper = 
                new NamedStreamable.ByteArrayWrapper(name, data);
            List<MerkleNode> nodes = ipfs.add(wrapper);
            
            if (nodes.isEmpty()) {
                throw new IOException("IPFS add returned empty result");
            }
            
            String resultCid = nodes.get(0).hash.toString();
            ipfs.pin.add(Multihash.fromBase58(resultCid));
            
            return resultCid;
        });
        
        LOG.info("📝 Added metadata: {} → CID: {}", name, cid);
        
        // Store metadata CID persistently
        DataIdentifier metaId = new DataIdentifier("META_" + name);
        persistentMappingService.storeCIDMapping(metaId, cid, data.length);
        
        // Cache
        cidCache.put(metaId, cid);
        
    } catch (Exception e) {
        throw new DataStoreException("Failed to add metadata: " + name, e);
    }
}
```

**Timeline**: 1 day (same pattern as binary CID persistence)

---

### 🟢 Already Good (Matches or Exceeds Azure)

#### 8. **Content-Addressed Storage**

**Azure**: Blob names are arbitrary (not content-based)  
**IPFS**: ✅ CID = cryptographic hash (content-addressed, deduplication built-in)

**Advantage**: IPFS automatically deduplicates. Azure requires manual dedup logic.

---

#### 9. **Decentralized Replication**

**Azure**: Centralized (Azure regions)  
**IPFS**: ✅ P2P replication between validators (decentralized)

**Advantage**: IPFS replicates across validator IPFS nodes automatically.

---

#### 10. **Immutability**

**Azure**: Blobs can be overwritten (need versioning feature)  
**IPFS**: ✅ CID = immutable (changing content = different CID)

**Advantage**: IPFS guarantees immutability at protocol level.

---

## Production Hardening Checklist

### Phase 1: Critical Fixes (1-2 weeks)

- [ ] **Persistent CID Mapping** (2-3 days)
  - [ ] Implement `PersistentCIDMappingService`
  - [ ] Store mappings in Oak metadata nodes (`/oak:blob-ipfs-mappings`)
  - [ ] Load mappings on startup
  - [ ] Update `write()` to persist CIDs
  - [ ] Update `read()` to check persistent mappings
  - [ ] Add migration for existing in-memory mappings

- [ ] **Retry Logic** (1 day)
  - [ ] Implement `IPFSRetryUtil`
  - [ ] Wrap all IPFS operations (upload, download, pin, unpin)
  - [ ] Exponential backoff (1s, 2s, 4s)
  - [ ] Configurable max attempts (default: 3)

- [ ] **Timeout Handling** (1 day)
  - [ ] Implement `IPFSTimeoutExecutor`
  - [ ] Wrap all IPFS operations with timeouts
  - [ ] Upload timeout: 2 minutes
  - [ ] Download timeout: 1 minute
  - [ ] Configurable via properties

- [ ] **Error Handling** (1 day)
  - [ ] Catch specific IPFS exceptions (network, timeout, not found)
  - [ ] Log errors with context (CID, identifier, operation)
  - [ ] Return meaningful error messages
  - [ ] Handle partial failures gracefully

### Phase 2: Production Features (1-2 weeks)

- [ ] **Async Operations** (2 days)
  - [ ] `writeAsync()` method
  - [ ] `readAsync()` method
  - [ ] `writeBatch()` for parallel uploads
  - [ ] Configurable thread pool size

- [ ] **Batch Operations** (1 day)
  - [ ] `deleteRecords(List<DataIdentifier>)` - batch delete
  - [ ] `pinBatch(List<String>)` - batch pin
  - [ ] `unpinBatch(List<String>)` - batch unpin
  - [ ] Parallel execution

- [ ] **Metadata Persistence** (1 day)
  - [ ] Store metadata CIDs in Oak (same as binary CIDs)
  - [ ] Update `addMetadataRecord()` to persist
  - [ ] Update `getMetadataRecord()` to check persistent store
  - [ ] Add metadata listing API

- [ ] **Health Checks** (1 day)
  - [ ] IPFS node connectivity check
  - [ ] IPFS version check
  - [ ] Storage stats (repo size, pinned CIDs count)
  - [ ] Expose health endpoint (`/v1/ipfs/health`)

- [ ] **Metrics** (1 day)
  - [ ] Upload count, bytes, duration
  - [ ] Download count, bytes, duration
  - [ ] Cache hit/miss rate
  - [ ] Error count by type
  - [ ] Expose Prometheus metrics

### Phase 3: Advanced Features (2-3 weeks)

- [ ] **IPFS Cluster Integration** (1 week)
  - [ ] Pin to multiple IPFS nodes (coordinated replication)
  - [ ] Replication factor configuration (default: 3)
  - [ ] Cluster member discovery
  - [ ] Pin status tracking

- [ ] **Lazy Pinning** (3 days)
  - [ ] Upload to IPFS but defer pinning
  - [ ] Pin only after confirmation (Oak commit success)
  - [ ] Unpin on abort/rollback
  - [ ] GC integration (unpin unreferenced CIDs)

- [ ] **Compression** (2 days)
  - [ ] Compress binaries before upload (gzip, brotli)
  - [ ] Store compressed flag in metadata
  - [ ] Decompress on download
  - [ ] Configurable compression threshold

- [ ] **Hybrid Fallback** (1 week)
  - [ ] S3/Azure fallback for IPFS failures
  - [ ] Upload to both IPFS + S3 (redundancy)
  - [ ] Read from S3 if IPFS unavailable
  - [ ] Configurable fallback strategy

---

## Testing Strategy

### Unit Tests (Already Done)

✅ `IPFSBackendTest.java` - basic operations  
✅ Mock IPFS client for unit tests

### Integration Tests (Missing)

- [ ] **IPFS Node Integration**
  - [ ] Start local IPFS node (Testcontainers)
  - [ ] Upload/download binaries
  - [ ] Pin/unpin operations
  - [ ] Metadata operations
  - [ ] Error scenarios (node down, timeout)

- [ ] **Persistence Tests**
  - [ ] CID mapping survives restart
  - [ ] Metadata survives restart
  - [ ] Migration from in-memory to persistent

- [ ] **Performance Tests**
  - [ ] Upload 100 binaries (1MB each)
  - [ ] Download 100 binaries
  - [ ] Batch operations (10,000 deletes)
  - [ ] Concurrent uploads (10 threads)

### Chaos Tests (Phase 3)

- [ ] IPFS node crash during upload
- [ ] IPFS node slow response (timeout)
- [ ] Network partition (IPFS unreachable)
- [ ] Disk full (IPFS repo full)

---

## Configuration Enhancements

### Current Configuration

```properties
# IPFS API endpoint
ipfsApiEndpoint=/ip4/127.0.0.1/tcp/5001

# Minimum size for external storage
minRecordLength=16384
```

### Recommended Production Configuration

```properties
# IPFS API endpoint (supports comma-separated list for cluster)
ipfs.api.endpoints=/ip4/ipfs-1/tcp/5001,/ip4/ipfs-2/tcp/5001,/ip4/ipfs-3/tcp/5001

# Retry configuration
ipfs.retry.maxAttempts=3
ipfs.retry.initialBackoffMs=1000
ipfs.retry.backoffMultiplier=2.0

# Timeout configuration
ipfs.upload.timeoutMs=120000
ipfs.download.timeoutMs=60000
ipfs.pin.timeoutMs=30000

# Async executor configuration
ipfs.async.threadPoolSize=8
ipfs.async.queueSize=1000

# Batch operation configuration
ipfs.batch.maxParallelism=10
ipfs.batch.maxSize=100

# Persistence configuration
ipfs.mapping.persistenceEnabled=true
ipfs.mapping.flushIntervalMs=60000

# Health check configuration
ipfs.health.checkIntervalMs=30000
ipfs.health.timeoutMs=5000

# Compression configuration
ipfs.compression.enabled=true
ipfs.compression.algorithm=gzip
ipfs.compression.thresholdBytes=10240

# Fallback configuration (Phase 3)
ipfs.fallback.enabled=false
ipfs.fallback.s3.bucket=oak-binaries-fallback
ipfs.fallback.strategy=IPFS_FIRST_S3_FALLBACK
```

---

## Monitoring & Observability

### Metrics to Add

```java
/**
 * IPFS DataStore metrics (Prometheus format).
 */
public class IPFSMetrics {
    // Upload metrics
    Counter uploadsTotal = Counter.build()
        .name("ipfs_uploads_total")
        .help("Total IPFS uploads")
        .register();
    
    Counter uploadBytesTotal = Counter.build()
        .name("ipfs_upload_bytes_total")
        .help("Total IPFS upload bytes")
        .register();
    
    Histogram uploadDuration = Histogram.build()
        .name("ipfs_upload_duration_seconds")
        .help("IPFS upload duration")
        .buckets(0.1, 0.5, 1, 2, 5, 10, 30)
        .register();
    
    // Download metrics
    Counter downloadsTotal = Counter.build()
        .name("ipfs_downloads_total")
        .help("Total IPFS downloads")
        .register();
    
    Counter downloadBytesTotal = Counter.build()
        .name("ipfs_download_bytes_total")
        .help("Total IPFS download bytes")
        .register();
    
    Histogram downloadDuration = Histogram.build()
        .name("ipfs_download_duration_seconds")
        .help("IPFS download duration")
        .buckets(0.01, 0.05, 0.1, 0.5, 1, 2, 5)
        .register();
    
    // Cache metrics
    Counter cacheHits = Counter.build()
        .name("ipfs_cache_hits_total")
        .help("Total cache hits")
        .register();
    
    Counter cacheMisses = Counter.build()
        .name("ipfs_cache_misses_total")
        .help("Total cache misses")
        .register();
    
    // Error metrics
    Counter errorsTotal = Counter.build()
        .name("ipfs_errors_total")
        .labelNames("operation", "error_type")
        .help("Total IPFS errors")
        .register();
    
    // IPFS node health
    Gauge nodeConnected = Gauge.build()
        .name("ipfs_node_connected")
        .help("IPFS node connection status (1=connected, 0=disconnected)")
        .register();
    
    Gauge repoPinnedObjects = Gauge.build()
        .name("ipfs_repo_pinned_objects")
        .help("Number of pinned objects in IPFS repo")
        .register();
    
    Gauge repoSizeBytes = Gauge.build()
        .name("ipfs_repo_size_bytes")
        .help("IPFS repo size in bytes")
        .register();
}
```

### Health Check Endpoint

```java
/**
 * IPFS health check endpoint.
 * 
 * GET /v1/ipfs/health
 */
public void handleIPFSHealth(HttpServletRequest request, HttpServletResponse response) {
    response.setContentType("application/json");
    
    try {
        // Check IPFS node connectivity
        long startTime = System.currentTimeMillis();
        Map<String, Object> versionInfo = ipfs.version();
        long latency = System.currentTimeMillis() - startTime;
        
        // Get repo stats
        Map<String, Object> repoStat = ipfs.repo.stat();
        
        // Get peer count
        List<Object> peers = ipfs.swarm.peers();
        
        // Build response
        String json = String.format(
            "{" +
            "\"status\":\"healthy\"," +
            "\"ipfsVersion\":\"%s\"," +
            "\"latencyMs\":%d," +
            "\"repoSizeBytes\":%d," +
            "\"pinnedObjects\":%d," +
            "\"connectedPeers\":%d," +
            "\"cidMappingsCached\":%d," +
            "\"timestamp\":%d" +
            "}",
            versionInfo.get("Version"),
            latency,
            repoStat.get("RepoSize"),
            repoStat.get("NumObjects"),
            peers.size(),
            cidCache.size(),
            System.currentTimeMillis()
        );
        
        response.getWriter().write(json);
        
    } catch (Exception e) {
        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.getWriter().write(String.format(
            "{\"status\":\"unhealthy\",\"error\":\"%s\",\"timestamp\":%d}",
            e.getMessage(),
            System.currentTimeMillis()
        ));
    }
}
```

---

## Comparison Summary

| Feature | Azure Blob Storage | IPFS (Current) | IPFS (Hardened) | Priority |
|---------|-------------------|----------------|-----------------|----------|
| **Persistence** | ✅ Cloud-durable | ❌ In-memory CIDs | ✅ Oak-persisted CIDs | 🔴 Critical |
| **Retry Logic** | ✅ Exponential backoff | ❌ Single attempt | ✅ 3 attempts + backoff | 🔴 Critical |
| **Timeout** | ✅ Configurable | ❌ No timeout | ✅ 2min upload, 1min download | 🔴 Critical |
| **Async Operations** | ✅ CompletableFuture | ❌ Sync only | ✅ Async + batch | 🟡 Important |
| **Connection Pooling** | ✅ Automatic | ✅ OkHttp (built-in) | ✅ Already good | ✅ Done |
| **Batch Operations** | ✅ Parallel delete | ❌ Single-item only | ✅ Batch pin/unpin | 🟡 Important |
| **Metadata** | ✅ Persistent blobs | ❌ In-memory | ✅ Oak-persisted | 🟡 Important |
| **Health Checks** | ✅ Azure SDK metrics | ❌ None | ✅ `/v1/ipfs/health` | 🟡 Important |
| **Metrics** | ✅ Azure Monitor | ❌ None | ✅ Prometheus | 🟡 Important |
| **Error Handling** | ✅ Detailed exceptions | ⚠️ Basic | ✅ Comprehensive | 🟡 Important |
| **Content-Addressed** | ❌ Arbitrary names | ✅ CID = hash | ✅ Unique advantage | ✅ Done |
| **Decentralized** | ❌ Azure regions | ✅ P2P network | ✅ Unique advantage | ✅ Done |
| **Immutability** | ⚠️ Versioning feature | ✅ Protocol-level | ✅ Unique advantage | ✅ Done |

---

## Recommended Implementation Plan

### Week 1: Critical Fixes
- Day 1-3: Persistent CID mapping (store in Oak)
- Day 4: Retry logic with exponential backoff
- Day 5: Timeout handling

### Week 2: Production Features
- Day 1-2: Async operations (CompletableFuture)
- Day 3: Batch operations (parallel pin/unpin)
- Day 4: Metadata persistence
- Day 5: Health checks + basic metrics

### Week 3: Testing & Polish
- Day 1-2: Integration tests (Testcontainers)
- Day 3: Performance tests
- Day 4-5: Documentation, code review, refinement

---

## Conclusion

**Current State**: POC-quality IPFS implementation with correct architecture but missing production features.

**Priority Actions**:
1. ✅ **Persistent CID Mapping** (production blocker)
2. ✅ **Retry Logic** (reliability blocker)
3. ✅ **Timeout Handling** (reliability blocker)

**Timeline**: 3 weeks to reach Azure Blob Storage parity (within IPFS capabilities).

**Strategic Recommendation**: Prioritize **persistent CID mapping** this week (Dec 4-6) before Garage Week demo (Dec 15). This ensures binaries survive validator restarts, demonstrating production viability.

---

**Related Documentation**:
- [IPFS-DATASTORE.md](../IPFS-DATASTORE.md) - Current configuration guide
- [ADR 015 - IPFS DataStore](../../../Blockchain-AEM/adr/015-ipfs-datastore-binary-storage.md)
- [GAP-ANALYSIS-VS-OAK-REPOSITORY-SERVICE.md](GAP-ANALYSIS-VS-OAK-REPOSITORY-SERVICE.md)
- [Azure BlobStore Implementation](../../oak-blob-cloud-azure/src/main/java/org/apache/jackrabbit/oak/blob/cloud/azure/blobstorage/AzureBlobStoreBackend.java)

