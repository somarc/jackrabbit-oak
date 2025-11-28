# Oak IPFS Cloud Blob Store

**Status**: POC / Garage Week Demo  
**ADR**: See [ADR 015 - IPFS DataStore for Binary Storage](../../Blockchain-AEM/adr/015-ipfs-datastore-binary-storage.md)

## Overview

This module provides IPFS (InterPlanetary File System) backend for Oak's BlobStore, enabling blockchain-native storage of large binaries (jcr:data properties like images, videos, PDFs).

### Strategic Context

**Ecosystem Expansion Strategy** (ADR 014 + ADR 015):
- **Oak Segments** → AEM compatibility (same format, tools, APIs = moat)
- **IPFS Binaries** → Blockchain-native (decentralized, content-addressed = differentiation)

Oak already separates structured content (segments) from large binaries (BlobStore). This module leverages that clean abstraction to add IPFS storage without affecting AEM compatibility.

## Features

✅ **Content-addressed storage** - CID (Content Identifier) = cryptographic hash  
✅ **Decentralized replication** - P2P transfer between validators  
✅ **Built-in deduplication** - Same binary uploaded multiple times = stored once  
✅ **Blockchain-native** - IPFS is the decentralized storage layer for Web3  
✅ **Zero impact on Oak segments** - Binary backend is pluggable, segments unchanged  

## Architecture

```
Oak SegmentNodeStore
 ├─ Segments (structure, properties, paths) → TAR files
 └─ Large binaries (jcr:data) → IPFS
     ├─ IPFSDataStore (Oak DataStore implementation)
     ├─ IPFSBackend (IPFS HTTP API client)
     └─ IPFS Node (local daemon, P2P network)
```

**Data flow:**
1. Sling uploads 10MB image via JCR API
2. Oak writes metadata to segment (path, properties, CID reference)
3. IPFSDataStore uploads binary to IPFS → gets CID
4. IPFS node pins CID (persistence) and replicates to network
5. Oak stores CID reference in jcr:data property
6. On retrieval: Oak fetches binary from IPFS by CID

## Requirements

### Runtime Requirements

- **IPFS node** (Kubo/go-ipfs) running locally or remotely
- Java 11+
- Oak 1.89+ (this codebase)

### Maven Dependency

```xml
<dependency>
    <groupId>org.apache.jackrabbit</groupId>
    <artifactId>oak-blob-cloud-ipfs</artifactId>
    <version>1.89-SNAPSHOT</version>
</dependency>
```

## Quick Start

### 1. Start IPFS Node

```bash
# Install IPFS (if not already installed)
# macOS:
brew install ipfs

# Linux:
wget https://dist.ipfs.tech/kubo/v0.24.0/kubo_v0.24.0_linux-amd64.tar.gz
tar -xvzf kubo_v0.24.0_linux-amd64.tar.gz
cd kubo && sudo bash install.sh

# Initialize IPFS repository
ipfs init --profile server

# Start IPFS daemon
ipfs daemon
# Listens on:
# - API: http://127.0.0.1:5001
# - Gateway: http://127.0.0.1:8080
# - P2P: /ip4/0.0.0.0/tcp/4001
```

### 2. Configure Oak to Use IPFS DataStore

**Programmatic configuration:**

```java
import org.apache.jackrabbit.oak.blob.cloud.ipfs.IPFSDataStore;
import org.apache.jackrabbit.oak.segment.SegmentNodeStoreBuilders;
import org.apache.jackrabbit.oak.segment.file.FileStoreBuilder;

// Create IPFS DataStore
IPFSDataStore blobStore = new IPFSDataStore();
blobStore.setIpfsApiEndpoint("/ip4/127.0.0.1/tcp/5001");
blobStore.setMinRecordLength(16 * 1024); // 16KB threshold
blobStore.init();

// Create FileStore with IPFS BlobStore
FileStore fileStore = FileStoreBuilder.fileStoreBuilder(new File("segmentstore"))
    .withBlobStore(blobStore)
    .build();

// Create NodeStore
SegmentNodeStore nodeStore = SegmentNodeStoreBuilders.builder(fileStore).build();
```

**OSGi configuration** (`org.apache.jackrabbit.oak.blob.cloud.ipfs.IPFSDataStore.cfg`):

```properties
# IPFS API endpoint (multiaddr format)
ipfsApiEndpoint=/ip4/127.0.0.1/tcp/5001

# Minimum size for external storage (bytes)
# Binaries smaller than this are stored inline in segments
minRecordLength=16384

# Cache settings (inherited from AbstractSharedCachingDataStore)
cacheSize=68719476736
# Other cache settings...
```

### 3. Test Binary Upload/Retrieval

```java
import javax.jcr.Session;
import javax.jcr.Node;
import javax.jcr.Binary;
import java.io.FileInputStream;

// Create JCR session (Oak with IPFS BlobStore)
Session session = repository.login();

// Upload binary
Node fileNode = session.getRootNode().addNode("myfile", "nt:file");
Node contentNode = fileNode.addNode("jcr:content", "nt:resource");

InputStream is = new FileInputStream("image.jpg");
Binary binary = session.getValueFactory().createBinary(is);
contentNode.setProperty("jcr:data", binary);
contentNode.setProperty("jcr:mimeType", "image/jpeg");

session.save(); // Binary uploaded to IPFS

// Retrieve binary
Binary retrieved = contentNode.getProperty("jcr:data").getBinary();
InputStream data = retrieved.getStream();
// data is fetched from IPFS by CID
```

## Docker Deployment

### Docker Compose Example

```yaml
services:
  ipfs-node:
    image: ipfs/kubo:latest
    ports:
      - "4001:4001"   # P2P
      - "5001:5001"   # API
      - "8080:8080"   # Gateway
    volumes:
      - ipfs-data:/data/ipfs
    environment:
      - IPFS_PROFILE=server
  
  oak-validator:
    image: oak-global-store:latest
    environment:
      - BLOBSTORE_TYPE=ipfs
      - IPFS_API_ENDPOINT=/ip4/ipfs-node/tcp/5001
    depends_on:
      - ipfs-node
    volumes:
      - oak-segments:/var/oak/segmentstore

volumes:
  ipfs-data:
  oak-segments:
```

## Configuration Options

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `ipfsApiEndpoint` | String | `/ip4/127.0.0.1/tcp/5001` | IPFS HTTP API multiaddr |
| `minRecordLength` | int | 16384 (16KB) | Minimum binary size for IPFS storage |
| `cacheSize` | long | 64GB | Local cache size (bytes) |
| `cachePurgeTrigFactor` | double | 0.95 | Cache purge trigger factor |
| `cachePurgeResizeFactor` | double | 0.85 | Cache purge resize factor |

## Monitoring & Debugging

### Check IPFS Node Status

```bash
# IPFS version
ipfs version

# Connected peers
ipfs swarm peers

# Pinned CIDs (persistent storage)
ipfs pin ls

# Storage stats
ipfs repo stat

# Garbage collection
ipfs repo gc
```

### Check Oak Logs

```bash
# Look for IPFS DataStore logs
grep "IPFS" oak.log

# Should see:
# 🚀 Initializing IPFS Backend...
# ✅ Connected to IPFS node: /ip4/127.0.0.1/tcp/5001 (version: 0.24.0)
# 📦 Uploaded binary to IPFS: abc123... → CID: QmXyz...
# 📌 Pinned CID: QmXyz...
# 📥 Fetching binary from IPFS: CID: QmXyz...
# ✅ Retrieved binary from IPFS: abc123... (1048576 bytes)
```

### Debug Binary Storage

```java
// Get IPFS backend
IPFSDataStore dataStore = (IPFSDataStore) nodeStore.getBlobStore();
IPFSBackend backend = (IPFSBackend) dataStore.getBackend();

// Check CID for a binary
DataIdentifier id = new DataIdentifier("abc123...");
String cid = backend.getCID(id);
System.out.println("Binary stored at IPFS CID: " + cid);

// Verify in IPFS directly
// ipfs cat <cid> > retrieved-binary.jpg
```

## Garbage Collection

Oak's GC integrates with IPFS pinning:

1. Oak GC identifies unreferenced binaries (no jcr:data properties point to them)
2. Calls `IPFSBackend.deleteRecord(identifier)`
3. Backend unpins CID from IPFS (`ipfs pin rm <cid>`)
4. IPFS garbage collection removes unpinned blocks (`ipfs repo gc`)

**Manual GC:**

```bash
# Trigger IPFS garbage collection
ipfs repo gc

# Check freed space
ipfs repo stat
```

## Performance Characteristics

### Write Performance
- **Upload:** ~50-200ms per binary (network-dependent)
- **Pin:** ~10-50ms per CID
- **Total:** Similar to S3 (100-300ms)

### Read Performance
- **Local cache hit:** ~1-5ms (memory)
- **IPFS local node:** ~10-50ms (disk)
- **IPFS network:** ~100-500ms (P2P fetch)
- **Comparison:** ~2-4x slower than S3, acceptable for CMS

### Storage Efficiency
- **Deduplication:** Automatic (same binary = same CID)
- **Overhead:** Minimal (CID = 34-byte hash)
- **Replication:** Configurable (pin on N validators)

## Limitations (POC Version)

### Current Limitations

1. **CID Mapping:** In-memory cache only (not persisted)
   - **Impact:** After restart, need to rebuild identifier → CID mapping
   - **Mitigation:** Store mapping in Oak metadata nodes
   - **Roadmap:** Phase 2 (production hardening)

2. **No S3 Fallback:** Pure IPFS (no hybrid mode)
   - **Impact:** If IPFS unavailable, reads fail
   - **Mitigation:** Ensure IPFS node highly available
   - **Roadmap:** Phase 2 (hybrid S3+IPFS)

3. **Single IPFS Node:** No clustering
   - **Impact:** Single point of failure
   - **Mitigation:** IPFS Cluster in Phase 3
   - **Roadmap:** Phase 3 (production scale)

4. **Limited Testing:** POC-level test coverage
   - **Impact:** May have edge case bugs
   - **Mitigation:** Extensive testing before production
   - **Roadmap:** Phase 2 (production hardening)

### Production Roadmap

**Phase 2 (Q1 2026) - Production Hardening:**
- Persistent CID mapping (stored in Oak)
- Hybrid S3+IPFS fallback
- Comprehensive test suite
- Performance benchmarking

**Phase 3 (Q2 2026) - Scale:**
- IPFS Cluster (coordinated pinning)
- Multi-validator replication guarantees
- Monitoring & alerting

**Phase 4 (Q3 2026+) - Advanced:**
- Filecoin archival storage
- CDN integration (IPFS gateways)
- Content licensing (wallet-based access)

## Testing

### Unit Tests

```bash
cd oak-blob-cloud-ipfs
mvn test
```

### Integration Test (Requires IPFS Node)

```bash
# Start IPFS daemon
ipfs daemon &

# Run integration tests
mvn verify -Pipfs-integration-tests
```

### Manual Test

```bash
# Build module
cd jackrabbit-oak
mvn clean install -pl oak-blob-cloud-ipfs -am -DskipTests

# Run test program
java -cp "oak-blob-cloud-ipfs/target/*:..." \
  org.apache.jackrabbit.oak.blob.cloud.ipfs.IPFSDataStoreTest
```

## Troubleshooting

### Issue: "Failed to initialize IPFS backend"
**Cause:** IPFS node not running or wrong endpoint  
**Solution:**
```bash
# Check IPFS daemon
ipfs id

# Test API endpoint
curl http://127.0.0.1:5001/api/v0/version
```

### Issue: "IPFS read failed for <identifier>"
**Cause:** CID not found (not pinned or GC'd)  
**Solution:**
```bash
# Check if CID is pinned
ipfs pin ls <cid>

# Re-pin if needed
ipfs pin add <cid>
```

### Issue: "IPFS write failed - connection refused"
**Cause:** IPFS node not accessible  
**Solution:**
```bash
# Check IPFS API is listening
netstat -an | grep 5001

# Check firewall rules
# Ensure port 5001 open
```

## References

- **ADR 014**: [Oak Segment Format as Strategic Anchor](../../Blockchain-AEM/adr/014-oak-segment-format-strategic-anchor.md)
- **ADR 015**: [IPFS DataStore for Binary Storage](../../Blockchain-AEM/adr/015-ipfs-datastore-binary-storage.md)
- **IPFS Documentation**: https://docs.ipfs.tech/
- **Java IPFS HTTP Client**: https://github.com/ipfs/java-ipfs-http-client
- **Oak BlobStore Architecture**: https://jackrabbit.apache.org/oak/docs/features/direct-binary-access.html

## License

Apache License 2.0 (same as Apache Jackrabbit Oak)

## Contributing

This is POC code for Blockchain AEM Garage Week (Dec 15, 2025). For production use, see roadmap above.

**Questions?** See `Blockchain-AEM/adr/015-ipfs-datastore-binary-storage.md` for complete rationale.

