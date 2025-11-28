# Oak Segment Consensus - Quick Start Cheat Sheet

**Garage Week POC - December 15, 2025**

## 🚀 30-Second Start (Single Validator)

```bash
# 1. Start IPFS
ipfs init --profile server
ipfs daemon &

# 2. Set config
export OAK_BLOCKCHAIN_MODE=mock
export BLOBSTORE_TYPE=ipfs
export IPFS_API_ENDPOINT=/ip4/127.0.0.1/tcp/5001

# 3. Start validator
java -jar oak-segment-consensus.jar

# 4. Access dashboard
open http://localhost:8090
```

**Done!** Oak validator running with IPFS binary storage.

---

## 📝 Essential Configuration Flags

### Blockchain Mode (Pick One)

```bash
export OAK_BLOCKCHAIN_MODE=mock      # ← Fast, no blockchain (Garage Week demos)
export OAK_BLOCKCHAIN_MODE=sepolia   # ← Real testnet (needs RPC URL)
export OAK_BLOCKCHAIN_MODE=mainnet   # ← Production (future)
```

**For Sepolia:**
```bash
export OAK_BLOCKCHAIN_MODE=sepolia
export OAK_BLOCKCHAIN_RPC_URL=https://sepolia.infura.io/v3/YOUR-PROJECT-ID
export OAK_BLOCKCHAIN_CONTRACT_ADDRESS=0x742d35Cc6634C0532925a3b844Bc9e7595f0bEb0
```

---

### Binary Storage (Optional but Recommended)

```bash
export BLOBSTORE_TYPE=ipfs                              # Enable IPFS
export IPFS_API_ENDPOINT=/ip4/127.0.0.1/tcp/5001      # IPFS API (default)
```

**Don't forget to start IPFS daemon first!**
```bash
ipfs daemon &
```

**What happens:**
- Binaries < 16 KB → Oak segments (inline)
- Binaries ≥ 16 KB → IPFS (decentralized, P2P)

---

### Consensus (3+ Validators Only)

**Single validator:** _(skip this section)_

**3-validator cluster:**

```bash
# Validator 1
export CONSENSUS_MODE=aeron
export AERON_NODE_ID=0
export AERON_CLUSTER_MEMBERS="0=localhost:20110,1=localhost:20111,2=localhost:20112"
export PORT=8090

# Validator 2
export CONSENSUS_MODE=aeron
export AERON_NODE_ID=1  # Different!
export AERON_CLUSTER_MEMBERS="0=localhost:20110,1=localhost:20111,2=localhost:20112"
export PORT=8092  # Different!

# Validator 3
export CONSENSUS_MODE=aeron
export AERON_NODE_ID=2  # Different!
export AERON_CLUSTER_MEMBERS="0=localhost:20110,1=localhost:20111,2=localhost:20112"
export PORT=8094  # Different!
```

---

## 🎬 Garage Week Demo Configuration

### Demo 1: Single Validator (IPFS)

```bash
#!/bin/bash
# demo-single-validator.sh

export OAK_BLOCKCHAIN_MODE=mock
export BLOBSTORE_TYPE=ipfs
export IPFS_API_ENDPOINT=/ip4/127.0.0.1/tcp/5001
export PORT=8090

ipfs daemon &
sleep 2  # Wait for IPFS to start

java -jar oak-segment-consensus.jar
```

**Demo points:**
- ✅ Oak segments (AEM compatible)
- ✅ IPFS binaries (blockchain-native)
- ✅ Upload image → stored in IPFS → show CID

---

### Demo 2: 3-Validator Cluster (Aeron + IPFS)

**Terminal 1 (Validator 1):**
```bash
export OAK_BLOCKCHAIN_MODE=mock
export BLOBSTORE_TYPE=ipfs
export IPFS_API_ENDPOINT=/ip4/127.0.0.1/tcp/5001
export CONSENSUS_MODE=aeron
export AERON_NODE_ID=0
export AERON_CLUSTER_MEMBERS="0=localhost:20110,1=localhost:20111,2=localhost:20112"
export PORT=8090

ipfs daemon &
java -jar oak-segment-consensus.jar
```

**Terminal 2 (Validator 2):**
```bash
export OAK_BLOCKCHAIN_MODE=mock
export BLOBSTORE_TYPE=ipfs
export IPFS_API_ENDPOINT=/ip4/127.0.0.1/tcp/5002  # Different IPFS port
export CONSENSUS_MODE=aeron
export AERON_NODE_ID=1  # Different ID
export AERON_CLUSTER_MEMBERS="0=localhost:20110,1=localhost:20111,2=localhost:20112"
export PORT=8092  # Different HTTP port

ipfs daemon &  # Need separate IPFS instance
java -jar oak-segment-consensus.jar
```

**Terminal 3 (Validator 3):**
```bash
export OAK_BLOCKCHAIN_MODE=mock
export BLOBSTORE_TYPE=ipfs
export IPFS_API_ENDPOINT=/ip4/127.0.0.1/tcp/5003  # Different IPFS port
export CONSENSUS_MODE=aeron
export AERON_NODE_ID=2  # Different ID
export AERON_CLUSTER_MEMBERS="0=localhost:20110,1=localhost:20111,2=localhost:20112"
export PORT=8094  # Different HTTP port

ipfs daemon &  # Need separate IPFS instance
java -jar oak-segment-consensus.jar
```

**Demo points:**
- ✅ 3 validators with Aeron Raft consensus
- ✅ Each has own IPFS node (local storage)
- ✅ Upload to V1 → replicated via consensus
- ✅ Binary in IPFS → P2P replication between nodes
- ✅ Show leader election, quorum

---

## ✅ Verification Checklist

### After Starting Validator

```bash
# Check logs show IPFS enabled
tail -f logs/oak.log | grep "IPFS BlobStore"
# Expected: "✅ IPFS BlobStore initialized"

# Check dashboard
curl http://localhost:8090/ | grep "IPFS"

# Check IPFS connection
ipfs id
ipfs swarm peers
```

### After Uploading Binary

```bash
# Check IPFS has binary
ipfs pin ls
# Should see: QmXyz... (CID of your binary)

# Retrieve binary
ipfs cat QmXyz... > retrieved.jpg

# Check Oak references it
curl http://localhost:8090/api/explore?path=/content/demo
# Should see node with jcr:data property
```

---

## 🐛 Common Issues

### "Failed to initialize IPFS BlobStore"

**Cause:** IPFS daemon not running  
**Fix:**
```bash
ipfs daemon &
sleep 2  # Wait for startup
```

---

### "Connection refused" to IPFS API

**Cause:** Wrong endpoint or firewall  
**Fix:**
```bash
# Test IPFS API
curl http://127.0.0.1:5001/api/v0/version

# Check port
netstat -an | grep 5001
```

---

### Validator doesn't start

**Cause:** Port already in use  
**Fix:**
```bash
# Check what's using the port
lsof -i :8090

# Use different port
export PORT=8091
```

---

### Aeron cluster won't form

**Cause:** Duplicate node IDs  
**Fix:**
```bash
# Ensure each validator has UNIQUE node ID
# Validator 1: export AERON_NODE_ID=0
# Validator 2: export AERON_NODE_ID=1
# Validator 3: export AERON_NODE_ID=2
```

---

## 📚 Complete Documentation

- **[CONFIGURATION.md](CONFIGURATION.md)** - Complete environment variable reference
- **[IPFS-DATASTORE.md](IPFS-DATASTORE.md)** - IPFS binary storage deep dive
- **[README.md](README.md)** - Full module documentation

---

## 🎯 Garage Week Demo Script

### Setup (5 minutes)

```bash
# Install IPFS (one-time)
brew install ipfs  # macOS
# or: wget https://dist.ipfs.tech/kubo/v0.24.0/...

# Build oak-segment-consensus (one-time)
cd jackrabbit-oak
mvn clean install -pl oak-segment-consensus,oak-blob-cloud-ipfs -am -DskipTests
```

### Demo (2 minutes)

```bash
# 1. Start IPFS
ipfs init --profile server
ipfs daemon &

# 2. Start validator with IPFS
export OAK_BLOCKCHAIN_MODE=mock
export BLOBSTORE_TYPE=ipfs
export IPFS_API_ENDPOINT=/ip4/127.0.0.1/tcp/5001

java -jar oak-segment-consensus/target/oak-segment-consensus.jar

# 3. Show dashboard
open http://localhost:8090

# 4. Upload image via Composum
# (or use JCR API to create node with jcr:data)

# 5. Show IPFS storage
ipfs pin ls
# QmXyz... (your image CID)

# 6. Retrieve from IPFS
ipfs cat QmXyz... | file -
# Output: /dev/stdin: JPEG image data...
```

**Talking points:**
> "Blockchain AEM uses Oak segments for AEM compatibility - same format, tools, APIs. Large binaries are in IPFS - decentralized, content-addressed, P2P replicated. This hybrid maintains the AEM moat while adding blockchain-native storage."

---

## 🔍 Monitoring Commands

```bash
# Check validator status
curl http://localhost:8090/health

# Check consensus state (Aeron)
curl http://localhost:8090/v1/aeron/cluster-state

# Check IPFS storage
ipfs repo stat

# Check IPFS peers (P2P connections)
ipfs swarm peers

# Check pinned binaries
ipfs pin ls --type=recursive
```

---

**For complete details, see [CONFIGURATION.md](CONFIGURATION.md)** 📖

