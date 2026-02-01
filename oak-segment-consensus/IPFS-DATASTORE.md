# IPFS DataStore Configuration for Oak Segment Consensus

**Status**: POC / Garage Week Demo  
**ADR**: See [ADR 015 - IPFS DataStore for Binary Storage](../Blockchain-AEM/adr/015-ipfs-datastore-binary-storage.md)

## Overview

Oak-segment-consensus now supports **IPFS DataStore** for storing large binaries (jcr:data) in a decentralized, content-addressed storage layer.

**Strategic positioning (ADR 014 + ADR 015):**
- ✅ Oak segments → AEM compatibility (structured content)
- ✅ IPFS binaries → Blockchain-native (decentralized storage)

## Quick Start (Local POC)

###  1. Start IPFS Node (Per Validator)

Each validator needs its own IPFS node:

```bash
# Install IPFS (macOS)
brew install ipfs

# Or Linux:
wget https://dist.ipfs.tech/kubo/v0.24.0/kubo_v0.24.0_linux-amd64.tar.gz
tar -xvzf kubo_v0.24.0_linux-amd64.tar.gz
cd kubo && sudo bash install.sh

# Initialize IPFS
ipfs init --profile server

# Start IPFS daemon
ipfs daemon
# API: http://127.0.0.1:5001
# Gateway: http://127.0.0.1:8080
# P2P: /ip4/0.0.0.0/tcp/4001
```

### 2. Enable IPFS DataStore in oak-segment-consensus

**Environment variables:**

```bash
# Enable IPFS BlobStore
export BLOBSTORE_TYPE=ipfs

# Configure IPFS API endpoint (default: localhost:5001)
export IPFS_API_ENDPOINT=/ip4/127.0.0.1/tcp/5001

# Start validator
java -jar oak-segment-consensus.jar \
  -Dblobstore.type=ipfs \
  -Dipfs.api.endpoint=/ip4/127.0.0.1/tcp/5001
```

**Or via Java system properties:**

```bash
java -Dblobstore.type=ipfs \
     -Dipfs.api.endpoint=/ip4/127.0.0.1/tcp/5001 \
     -jar oak-segment-consensus.jar
```

### 3. Verify IPFS DataStore is Active

Check startup logs:

```
📦 Configuring IPFS BlobStore for binaries...
✅ IPFS BlobStore initialized
   - IPFS API: /ip4/127.0.0.1/tcp/5001
   - Min size: 16 KB (smaller binaries inline in segments)
   - Storage: Decentralized (P2P replication)
   - Strategy: Oak segments (AEM compatible) + IPFS binaries (blockchain-native)
```

### 4. Test Binary Upload

Upload a binary via JCR API (or Composum):

```java
// Create node with binary
Node fileNode = session.getRootNode().addNode("test-image", "nt:file");
Node contentNode = fileNode.addNode("jcr:content", "nt:resource");

// Upload binary (will go to IPFS if > 16KB)
InputStream is = new FileInputStream("image.jpg");
Binary binary = session.getValueFactory().createBinary(is);
contentNode.setProperty("jcr:data", binary);
contentNode.setProperty("jcr:mimeType", "image/jpeg");

session.save(); // Binary uploaded to IPFS!
```

Verify in IPFS:

```bash
# List pinned CIDs (persistent storage)
ipfs pin ls

# Should see your binary CID
# QmXyz... (example CID)

# Retrieve binary directly from IPFS
ipfs cat <CID> > retrieved-image.jpg
```

---

## Local Storage for Garage Week POC

### Where Binaries are Stored

IPFS stores all data **locally** in its repository:

```
~/.ipfs/                   (default location)
├── blocks/                ← Your binaries here (content-addressed blocks)
│   ├── 2A/                ← Organized by CID prefix
│   │   └── CIDXYZ.data    ← Binary data
│   └── ...
├── datastore/             ← Index/metadata
└── config                 ← IPFS configuration
```

**For Docker:**

```
/data/ipfs/                (mounted volume)
├── blocks/
├── datastore/
└── config
```

### Local P2P Replication

**How it works:**

1. **Validator 1** uploads 10MB image → IPFS node 1 stores it locally
2. IPFS node 1 announces CID to P2P network (even if all nodes on localhost)
3. **Validator 2** requests same CID → IPFS node 2 fetches from node 1 (local transfer)
4. IPFS node 2 now has copy (also stored locally)

**Result:** Each validator has local storage, but binaries replicate P2P.

---

## Docker Compose Configuration

### Single Validator with IPFS

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

  validator-1:
    image: oak-global-store:latest
    environment:
      - BLOBSTORE_TYPE=ipfs
      - IPFS_API_ENDPOINT=/ip4/ipfs-node/tcp/5001
    depends_on:
      - ipfs-node
    volumes:
      - validator1-segments:/var/oak/segmentstore
    ports:
      - "8090:8090"

volumes:
  ipfs-data:          # IPFS local storage
  validator1-segments: # Oak segments
```

### 3 Validators with Shared IPFS Network

```yaml
services:
  ipfs-node-1:
    image: ipfs/kubo:latest
    ports:
      - "4001:4001"
      - "5001:5001"
      - "8080:8080"
    volumes:
      - ipfs1-data:/data/ipfs
    environment:
      - IPFS_PROFILE=server

  ipfs-node-2:
    image: ipfs/kubo:latest
    ports:
      - "4002:4001"
      - "5002:5001"
      - "8081:8080"
    volumes:
      - ipfs2-data:/data/ipfs
    environment:
      - IPFS_PROFILE=server

  ipfs-node-3:
    image: ipfs/kubo:latest
    ports:
      - "4003:4001"
      - "5003:5001"
      - "8082:8080"
    volumes:
      - ipfs3-data:/data/ipfs
    environment:
      - IPFS_PROFILE=server

  validator-1:
    image: oak-global-store:latest
    environment:
      - BLOBSTORE_TYPE=ipfs
      - IPFS_API_ENDPOINT=/ip4/ipfs-node-1/tcp/5001
    depends_on:
      - ipfs-node-1
    volumes:
      - validator1-segments:/var/oak/segmentstore
    ports:
      - "8090:8090"

  validator-2:
    image: oak-global-store:latest
    environment:
      - BLOBSTORE_TYPE=ipfs
      - IPFS_API_ENDPOINT=/ip4/ipfs-node-2/tcp/5001
    depends_on:
      - ipfs-node-2
    volumes:
      - validator2-segments:/var/oak/segmentstore
    ports:
      - "8092:8090"

  validator-3:
    image: oak-global-store:latest
    environment:
      - BLOBSTORE_TYPE=ipfs
      - IPFS_API_ENDPOINT=/ip4/ipfs-node-3/tcp/5001
    depends_on:
      - ipfs-node-3
    volumes:
      - validator3-segments:/var/oak/segmentstore
    ports:
      - "8094:8090"

volumes:
  ipfs1-data:          # Validator 1 local IPFS storage
  ipfs2-data:          # Validator 2 local IPFS storage
  ipfs3-data:          # Validator 3 local IPFS storage
  validator1-segments:
  validator2-segments:
  validator3-segments:
```

**Key points:**
- ✅ Each validator has own IPFS node (isolated storage)
- ✅ Each IPFS node stores binaries locally (in Docker volume)
- ✅ IPFS nodes connect P2P automatically (replicate binaries)
- ✅ No external dependencies (all local)

---

## Configuration Options

| Variable | Default | Description |
|----------|---------|-------------|
| `BLOBSTORE_TYPE` | (none) | Set to `ipfs` to enable IPFS DataStore |
| `IPFS_API_ENDPOINT` | `/ip4/127.0.0.1/tcp/5001` | IPFS HTTP API multiaddr |

**Threshold:**
- Binaries < 16KB → Stored inline in Oak segments
- Binaries ≥ 16KB → Stored in IPFS (separate from segments)

---

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

# Garbage collection (free space)
ipfs repo gc
```

### Check Oak Logs

```bash
# Look for IPFS DataStore initialization
grep "IPFS BlobStore" oak.log

# Should see:
# 📦 Configuring IPFS BlobStore for binaries...
# ✅ IPFS BlobStore initialized
#    - IPFS API: /ip4/127.0.0.1/tcp/5001
```

### Verify Binary in IPFS

```java
// Get CID for a binary (custom logging in IPFSBackend)
// Look for log lines like:
// 📦 Uploaded binary to IPFS: abc123... → CID: QmXyz...

// Then verify in IPFS:
ipfs cat QmXyz... | file -
# Output: /dev/stdin: JPEG image data...
```

---

## Garage Week Demo Flow

### Demo Script

1. **Start 3 validators with IPFS** (Docker Compose above)
   
2. **Upload image via Composum:**
   - Navigate to `http://localhost:8090/bin/browser.html`
   - Create node `/content/demo/image`
   - Upload 1MB image to `jcr:data` property
   - Save

3. **Show IPFS storage:**
   ```bash
   # Validator 1 has binary
   docker exec validator-1-ipfs ipfs pin ls
   # QmXyz... (CID of image)
   
   # Validator 2 doesn't have it yet
   docker exec validator-2-ipfs ipfs pin ls
   # (empty or different CIDs)
   ```

4. **Access image from Validator 2:**
   - Navigate to `http://localhost:8092/bin/browser.html`
   - Open `/content/demo/image` node
   - View `jcr:data` property
   - **IPFS auto-replicates:** Validator 2's IPFS fetches from Validator 1

5. **Show P2P replication:**
   ```bash
   # Now Validator 2 has it too
   docker exec validator-2-ipfs ipfs pin ls
   # QmXyz... (same CID - replicated!)
   ```

6. **Show deduplication:**
   - Upload same image to different path
   - IPFS returns same CID (no duplicate storage)
   - Disk usage doesn't increase

### Demo Narrative

> **"Blockchain AEM uses Oak segments for AEM compatibility - same format, APIs, tools. But large binaries are stored in IPFS - decentralized, content-addressed, P2P replicated. This hybrid strategy maintains the AEM ecosystem moat while adding blockchain-native storage."**

**Key points:**
- ✅ Oak segments = compatibility (structured content)
- ✅ IPFS binaries = differentiation (decentralized storage)
- ✅ Local storage (each validator owns data)
- ✅ P2P replication (no central server)
- ✅ Deduplication (same binary = stored once)

---

## Troubleshooting

### Issue: "Failed to initialize IPFS BlobStore"
**Cause:** IPFS daemon not running  
**Solution:**
```bash
ipfs daemon &
```

### Issue: "Connection refused" to IPFS API
**Cause:** Wrong endpoint or firewall  
**Solution:**
```bash
# Test API
curl http://127.0.0.1:5001/api/v0/version

# Check IPFS is listening
netstat -an | grep 5001
```

### Issue: Binary not replicating to other validators
**Cause:** IPFS nodes not connected (P2P)  
**Solution:**
```bash
# Check peers
ipfs swarm peers

# Connect manually
ipfs swarm connect /ip4/192.168.1.100/tcp/4001/p2p/<peer-id>
```

### Issue: IPFS storage growing too large
**Cause:** No garbage collection  
**Solution:**
```bash
# Run GC
ipfs repo gc

# Set storage limit
ipfs config Datastore.StorageMax 10GB
```

---

## Production Roadmap

**Phase 1 (POC - Now):** Pure IPFS, local storage  
**Phase 2 (Q1 2026):** Hybrid S3+IPFS (fallback)  
**Phase 3 (Q2 2026):** IPFS Cluster (coordinated pinning)  
**Phase 4 (Q3 2026+):** Filecoin archival (permanent storage)  

See [oak-blob-cloud-ipfs/README.md](../oak-blob-cloud-ipfs/README.md) for complete documentation.

---

## References

- **ADR 014**: [Oak Segment Format as Strategic Anchor](../../Blockchain-AEM/adr/014-oak-segment-format-strategic-anchor.md)
- **ADR 015**: [IPFS DataStore for Binary Storage](../../Blockchain-AEM/adr/015-ipfs-datastore-binary-storage.md)
- **IPFS Docs**: https://docs.ipfs.tech/
- **oak-blob-cloud-ipfs**: [Module README](../oak-blob-cloud-ipfs/README.md)

