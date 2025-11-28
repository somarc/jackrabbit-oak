# Oak Segment Consensus - Configuration Reference

**Complete guide to environment variables and system properties for oak-segment-consensus**

## Quick Reference

```bash
# Blockchain Mode
export OAK_BLOCKCHAIN_MODE=mock|sepolia|mainnet

# Binary Storage
export BLOBSTORE_TYPE=ipfs
export IPFS_API_ENDPOINT=/ip4/127.0.0.1/tcp/5001

# Consensus
export CONSENSUS_MODE=aeron
export AERON_NODE_ID=0

# Security
export OAK_VALIDATOR_AUTH_TOKEN=your-secret-token

# Ethereum
export OAK_BLOCKCHAIN_RPC_URL=https://sepolia.infura.io/v3/YOUR-PROJECT-ID
export OAK_BLOCKCHAIN_CONTRACT_ADDRESS=0x742d35Cc6634C0532925a3b844Bc9e7595f0bEb0
```

---

## Configuration Priority

**All settings follow this priority order:**

1. **Environment variables** (highest priority) - `export VAR=value`
2. **System properties** (medium priority) - `-Dvar=value`
3. **Default values** (lowest priority) - hardcoded defaults

**Example:**
```bash
# Environment variable (highest priority)
export OAK_BLOCKCHAIN_MODE=sepolia

# System property (overridden by env var if both present)
java -Doak.blockchain.mode=mock -jar oak-segment-consensus.jar
# Result: Uses "sepolia" from environment variable
```

---

## 1. Blockchain Mode Configuration

### OAK_BLOCKCHAIN_MODE

**Description:** Blockchain verification mode for payment validation

**Values:**
- `mock` - Pure simulation (instant, no blockchain, no gas costs) **[DEFAULT]**
- `sepolia` - Sepolia testnet (real verification, test ETH)
- `mainnet` - Ethereum mainnet (real verification, real ETH)

**Environment Variable:**
```bash
export OAK_BLOCKCHAIN_MODE=sepolia
```

**System Property:**
```bash
java -Doak.blockchain.mode=sepolia -jar oak-segment-consensus.jar
```

**Use Cases:**
- **mock**: Development, unit testing, Garage Week demos
- **sepolia**: Integration testing, pre-production validation
- **mainnet**: Production deployment (future)

---

### OAK_BLOCKCHAIN_CONTRACT_ADDRESS

**Description:** Ethereum smart contract address for payment verification

**Defaults:**
- **Sepolia:** `0x742d35Cc6634C0532925a3b844Bc9e7595f0bEb0` (ValidatorPaymentV3_1)
- **Mainnet:** Not deployed yet

**Environment Variable:**
```bash
export OAK_BLOCKCHAIN_CONTRACT_ADDRESS=0x742d35Cc6634C0532925a3b844Bc9e7595f0bEb0
```

**System Property:**
```bash
java -Doak.blockchain.contractAddress=0x742d35Cc6634C0532925a3b844Bc9e7595f0bEb0 -jar oak-segment-consensus.jar
```

**When to Override:**
- Testing new smart contract versions
- Using custom deployment for your validator network

---

### OAK_BLOCKCHAIN_RPC_URL

**Description:** Ethereum RPC endpoint for Web3j client (required for sepolia/mainnet)

**Required for:** `sepolia` and `mainnet` modes  
**Not used in:** `mock` mode

**Environment Variable:**
```bash
export OAK_BLOCKCHAIN_RPC_URL=https://sepolia.infura.io/v3/YOUR-PROJECT-ID
```

**System Property:**
```bash
java -Doak.blockchain.rpcUrl=https://sepolia.infura.io/v3/YOUR-PROJECT-ID -jar oak-segment-consensus.jar
```

**Common Providers:**
- **Infura:** `https://sepolia.infura.io/v3/{YOUR-PROJECT-ID}`
- **Alchemy:** `https://eth-sepolia.g.alchemy.com/v2/{YOUR-API-KEY}`
- **Local node:** `http://localhost:8545`

**Important:**
- ⚠️ **Never commit API keys to git!**
- ⚠️ Use environment variables for production
- ⚠️ Rate limits apply (Infura: 100K req/day free tier)

---

## 2. Binary Storage (IPFS DataStore)

### BLOBSTORE_TYPE

**Description:** Binary storage backend for large files (jcr:data properties)

**Values:**
- _(empty)_ - Default FileDataStore (local filesystem) **[DEFAULT]**
- `ipfs` - IPFS DataStore (decentralized, content-addressed)

**Environment Variable:**
```bash
export BLOBSTORE_TYPE=ipfs
```

**System Property:**
```bash
java -Dblobstore.type=ipfs -jar oak-segment-consensus.jar
```

**When Enabled:**
- Binaries < 16 KB → Stored inline in Oak segments
- Binaries ≥ 16 KB → Stored in IPFS
- Content-addressed (CID = hash of content)
- P2P replication between validators

**See Also:** [IPFS-DATASTORE.md](IPFS-DATASTORE.md) for complete documentation

---

### IPFS_API_ENDPOINT

**Description:** IPFS HTTP API endpoint (multiaddr format)

**Default:** `/ip4/127.0.0.1/tcp/5001`

**Environment Variable:**
```bash
export IPFS_API_ENDPOINT=/ip4/127.0.0.1/tcp/5001
```

**System Property:**
```bash
java -Dipfs.api.endpoint=/ip4/127.0.0.1/tcp/5001 -jar oak-segment-consensus.jar
```

**Examples:**
- **Localhost:** `/ip4/127.0.0.1/tcp/5001`
- **Docker service:** `/ip4/ipfs-node/tcp/5001`
- **Remote node:** `/ip4/192.168.1.100/tcp/5001`

**Required:** Only when `BLOBSTORE_TYPE=ipfs`

---

## 3. Consensus Configuration

### CONSENSUS_MODE

**Description:** Consensus protocol for multi-validator coordination

**Values:**
- `aeron` - Aeron Raft consensus (multi-validator, production-grade) **[RECOMMENDED]**
- `leader` - Leader-based (single validator, epoch-based)
- _(empty)_ - No consensus (single node)

**Environment Variable:**
```bash
export CONSENSUS_MODE=aeron
```

**System Property:**
```bash
java -Dconsensus.mode=aeron -jar oak-segment-consensus.jar
```

**When to Use:**
- **aeron**: 3+ validators (Raft quorum: 2-of-3, 3-of-5, etc.)
- **leader**: Single validator with epoch-based batching
- **_(empty)_**: Development, single node testing

---

### AERON_NODE_ID

**Description:** Unique node identifier in Aeron cluster (integer)

**Required for:** `CONSENSUS_MODE=aeron`

**Values:** `0`, `1`, `2`, `3`, ... (must be unique per validator)

**Environment Variable:**
```bash
export AERON_NODE_ID=0
```

**System Property:**
```bash
java -Daeron.node.id=0 -jar oak-segment-consensus.jar
```

**Example Cluster:**
```bash
# Validator 1
export AERON_NODE_ID=0

# Validator 2
export AERON_NODE_ID=1

# Validator 3
export AERON_NODE_ID=2
```

---

### AERON_CLUSTER_MEMBERS

**Description:** Comma-separated list of Aeron cluster members (IP:port pairs)

**Required for:** `CONSENSUS_MODE=aeron`

**Format:** `{nodeId}={host}:{port},{nodeId}={host}:{port},...`

**Environment Variable:**
```bash
export AERON_CLUSTER_MEMBERS="0=localhost:20110,1=localhost:20111,2=localhost:20112"
```

**System Property:**
```bash
java -Daeron.cluster.members="0=localhost:20110,1=localhost:20111,2=localhost:20112" -jar oak-segment-consensus.jar
```

**Docker Example:**
```bash
export AERON_CLUSTER_MEMBERS="0=validator-1:20110,1=validator-2:20110,2=validator-3:20110"
```

**Ports:**
- Each validator needs 3 sequential ports (control, log, consensus)
- Example: Node 0 uses 20110, 20111, 20112

---

### consensus.peers

**Description:** Comma-separated list of peer HTTP URLs (for bootstrap and metrics)

**Format:** `http://host:port,http://host:port,...`

**Environment Variable:**
```bash
export CONSENSUS_PEERS="http://localhost:8091,http://localhost:8092"
```

**System Property:**
```bash
java -Dconsensus.peers="http://localhost:8091,http://localhost:8092" -jar oak-segment-consensus.jar
```

**Used For:**
- Bootstrap (syncing Oak FileStore from peers)
- Peer discovery
- Health checking

---

### consensus.self.url

**Description:** This validator's public HTTP URL (for peer discovery)

**Default:** Auto-resolved from `localhost:{PORT}`

**Environment Variable:**
```bash
export CONSENSUS_SELF_URL=http://192.168.1.100:8090
```

**System Property:**
```bash
java -Dconsensus.self.url=http://192.168.1.100:8090 -jar oak-segment-consensus.jar
```

**When to Override:**
- Docker containers (use container name, not localhost)
- Cloud deployments (use public IP or domain)
- ngrok/tunnels (use tunnel URL)

---

## 4. Security & Authentication

### OAK_VALIDATOR_AUTH_TOKEN

**Description:** Secret token for validator HTTP endpoint authentication

**Default:** _(none)_ - Authentication disabled (POC mode)

**Environment Variable:**
```bash
export OAK_VALIDATOR_AUTH_TOKEN=your-secret-token-here
```

**System Property:**
```bash
java -Doak.validator.auth.token=your-secret-token-here -jar oak-segment-consensus.jar
```

**When Enabled:**
- Clients must include `Authorization: {token}` header
- Protects write endpoints (`/v1/propose-write`, etc.)
- Health checks remain public

**Production Recommendation:**
```bash
# Generate secure random token
export OAK_VALIDATOR_AUTH_TOKEN=$(openssl rand -hex 32)
```

---

## 5. Server Configuration

### PORT

**Description:** HTTP server port for validator

**Default:** `8090`

**Environment Variable:**
```bash
export PORT=8091
```

**System Property:**
```bash
java -Dport=8091 -jar oak-segment-consensus.jar
```

**Common Ports:**
- **8090**: Validator 1 (default)
- **8092**: Validator 2
- **8094**: Validator 3

---

### STORE_DIR

**Description:** Directory for Oak segment store (TAR files, journal)

**Default:** `./segmentstore`

**Environment Variable:**
```bash
export STORE_DIR=/var/oak/segmentstore
```

**System Property:**
```bash
java -Dstore.dir=/var/oak/segmentstore -jar oak-segment-consensus.jar
```

**Docker:**
```yaml
volumes:
  - validator1-segments:/var/oak/segmentstore
```

---

## 6. Garbage Collection (GC)

### gc.usdc.per.mb

**Description:** USDC cost per MB for GC cost estimation

**Default:** `0.10` ($0.10 per MB)

**Environment Variable:**
```bash
export GC_USDC_PER_MB=0.10
```

**System Property:**
```bash
java -Dgc.usdc.per.mb=0.10 -jar oak-segment-consensus.jar
```

**Use Cases:**
- Adjust tokenomics for GC cost calculation
- Testing different pricing models

---

## 7. Bootstrap Configuration

### bootstrap.primary.host

**Description:** Primary validator host for Cold Standby bootstrap

**Used when:** Empty FileStore syncing from existing validators

**Environment Variable:**
```bash
export BOOTSTRAP_PRIMARY_HOST=validator-1
```

**System Property:**
```bash
java -Dbootstrap.primary.host=validator-1 -jar oak-segment-consensus.jar
```

---

### bootstrap.primary.port

**Description:** Primary validator standby port (HTTP port + 1)

**Default:** `{PORT} + 1` (e.g., 8091 if PORT=8090)

**Environment Variable:**
```bash
export BOOTSTRAP_PRIMARY_PORT=8091
```

**System Property:**
```bash
java -Dbootstrap.primary.port=8091 -jar oak-segment-consensus.jar
```

---

## Complete Examples

### Example 1: Single Validator (Mock Mode, IPFS)

```bash
#!/bin/bash
# Single validator for development/testing

# Blockchain Mode
export OAK_BLOCKCHAIN_MODE=mock

# Binary Storage
export BLOBSTORE_TYPE=ipfs
export IPFS_API_ENDPOINT=/ip4/127.0.0.1/tcp/5001

# Server
export PORT=8090
export STORE_DIR=./segmentstore

# Start IPFS
ipfs daemon &

# Start validator
java -jar oak-segment-consensus.jar
```

---

### Example 2: 3-Validator Cluster (Sepolia, IPFS)

**Validator 1:**
```bash
#!/bin/bash
# validator-1-start.sh

# Blockchain Mode (Sepolia testnet)
export OAK_BLOCKCHAIN_MODE=sepolia
export OAK_BLOCKCHAIN_RPC_URL=https://sepolia.infura.io/v3/YOUR-PROJECT-ID
export OAK_BLOCKCHAIN_CONTRACT_ADDRESS=0x742d35Cc6634C0532925a3b844Bc9e7595f0bEb0

# Binary Storage (IPFS)
export BLOBSTORE_TYPE=ipfs
export IPFS_API_ENDPOINT=/ip4/127.0.0.1/tcp/5001

# Consensus (Aeron Raft)
export CONSENSUS_MODE=aeron
export AERON_NODE_ID=0
export AERON_CLUSTER_MEMBERS="0=localhost:20110,1=localhost:20111,2=localhost:20112"
export CONSENSUS_PEERS="http://localhost:8092,http://localhost:8094"
export CONSENSUS_SELF_URL=http://localhost:8090

# Security
export OAK_VALIDATOR_AUTH_TOKEN=secret-token-123

# Server
export PORT=8090
export STORE_DIR=./validator1/segmentstore

# Start IPFS
ipfs daemon &

# Start validator
java -jar oak-segment-consensus.jar
```

**Validator 2:**
```bash
#!/bin/bash
# validator-2-start.sh

export OAK_BLOCKCHAIN_MODE=sepolia
export OAK_BLOCKCHAIN_RPC_URL=https://sepolia.infura.io/v3/YOUR-PROJECT-ID
export BLOBSTORE_TYPE=ipfs
export IPFS_API_ENDPOINT=/ip4/127.0.0.1/tcp/5002  # Different port

export CONSENSUS_MODE=aeron
export AERON_NODE_ID=1  # Different node ID
export AERON_CLUSTER_MEMBERS="0=localhost:20110,1=localhost:20111,2=localhost:20112"
export CONSENSUS_PEERS="http://localhost:8090,http://localhost:8094"
export CONSENSUS_SELF_URL=http://localhost:8092

export OAK_VALIDATOR_AUTH_TOKEN=secret-token-123
export PORT=8092  # Different port
export STORE_DIR=./validator2/segmentstore

ipfs daemon &
java -jar oak-segment-consensus.jar
```

**Validator 3:**
```bash
#!/bin/bash
# validator-3-start.sh

export OAK_BLOCKCHAIN_MODE=sepolia
export OAK_BLOCKCHAIN_RPC_URL=https://sepolia.infura.io/v3/YOUR-PROJECT-ID
export BLOBSTORE_TYPE=ipfs
export IPFS_API_ENDPOINT=/ip4/127.0.0.1/tcp/5003  # Different port

export CONSENSUS_MODE=aeron
export AERON_NODE_ID=2  # Different node ID
export AERON_CLUSTER_MEMBERS="0=localhost:20110,1=localhost:20111,2=localhost:20112"
export CONSENSUS_PEERS="http://localhost:8090,http://localhost:8092"
export CONSENSUS_SELF_URL=http://localhost:8094

export OAK_VALIDATOR_AUTH_TOKEN=secret-token-123
export PORT=8094  # Different port
export STORE_DIR=./validator3/segmentstore

ipfs daemon &
java -jar oak-segment-consensus.jar
```

---

### Example 3: Docker Compose (3 Validators)

**`.env` file:**
```bash
# Shared configuration
OAK_BLOCKCHAIN_MODE=sepolia
OAK_BLOCKCHAIN_RPC_URL=https://sepolia.infura.io/v3/YOUR-PROJECT-ID
OAK_BLOCKCHAIN_CONTRACT_ADDRESS=0x742d35Cc6634C0532925a3b844Bc9e7595f0bEb0
BLOBSTORE_TYPE=ipfs
OAK_VALIDATOR_AUTH_TOKEN=secret-token-123
```

**`docker-compose.yml`:**
```yaml
version: '3.8'

services:
  ipfs-1:
    image: ipfs/kubo:latest
    ports: ["4001:4001", "5001:5001", "8080:8080"]
    volumes: ["ipfs1-data:/data/ipfs"]
    environment:
      IPFS_PROFILE: server

  validator-1:
    image: oak-global-store:latest
    environment:
      OAK_BLOCKCHAIN_MODE: ${OAK_BLOCKCHAIN_MODE}
      OAK_BLOCKCHAIN_RPC_URL: ${OAK_BLOCKCHAIN_RPC_URL}
      BLOBSTORE_TYPE: ${BLOBSTORE_TYPE}
      IPFS_API_ENDPOINT: /ip4/ipfs-1/tcp/5001
      CONSENSUS_MODE: aeron
      AERON_NODE_ID: 0
      AERON_CLUSTER_MEMBERS: "0=validator-1:20110,1=validator-2:20110,2=validator-3:20110"
      CONSENSUS_PEERS: "http://validator-2:8090,http://validator-3:8090"
      CONSENSUS_SELF_URL: "http://validator-1:8090"
      PORT: 8090
    depends_on: [ipfs-1]
    volumes: ["validator1-segments:/var/oak/segmentstore"]
    ports: ["8090:8090"]

  # Similar for validator-2 and validator-3...

volumes:
  ipfs1-data:
  validator1-segments:
```

---

## Quick Troubleshooting

### Check Current Configuration

Add this to your start script:

```bash
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "Configuration:"
echo "  Blockchain Mode: $OAK_BLOCKCHAIN_MODE"
echo "  BlobStore: $BLOBSTORE_TYPE"
echo "  IPFS API: $IPFS_API_ENDPOINT"
echo "  Consensus: $CONSENSUS_MODE"
echo "  Node ID: $AERON_NODE_ID"
echo "  Port: $PORT"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
```

### Verify Configuration at Runtime

Check logs after startup:

```bash
# Blockchain config
grep "🔧 Blockchain Configuration" oak.log

# IPFS config
grep "IPFS BlobStore" oak.log

# Consensus config
grep "✈️  AERON MODE" oak.log
```

### Common Mistakes

1. **Mixing modes:**
   ```bash
   # ❌ WRONG - Using Sepolia RPC but mock mode
   export OAK_BLOCKCHAIN_MODE=mock
   export OAK_BLOCKCHAIN_RPC_URL=https://sepolia.infura.io/...
   
   # ✅ CORRECT
   export OAK_BLOCKCHAIN_MODE=sepolia
   export OAK_BLOCKCHAIN_RPC_URL=https://sepolia.infura.io/...
   ```

2. **IPFS without daemon:**
   ```bash
   # ❌ WRONG - IPFS enabled but daemon not running
   export BLOBSTORE_TYPE=ipfs
   # (no ipfs daemon started)
   
   # ✅ CORRECT
   export BLOBSTORE_TYPE=ipfs
   ipfs daemon &
   ```

3. **Duplicate node IDs:**
   ```bash
   # ❌ WRONG - Both validators use same ID
   # Validator 1: export AERON_NODE_ID=0
   # Validator 2: export AERON_NODE_ID=0
   
   # ✅ CORRECT - Unique IDs
   # Validator 1: export AERON_NODE_ID=0
   # Validator 2: export AERON_NODE_ID=1
   ```

---

## See Also

- **[IPFS-DATASTORE.md](IPFS-DATASTORE.md)** - Complete IPFS DataStore guide
- **[README.md](README.md)** - Module overview and architecture
- **[ADR 014](../../Blockchain-AEM/adr/014-oak-segment-format-strategic-anchor.md)** - Why Oak (strategic rationale)
- **[ADR 015](../../Blockchain-AEM/adr/015-ipfs-datastore-binary-storage.md)** - IPFS DataStore decision

---

## Configuration Checklist for Garage Week

```bash
# ✅ Blockchain Mode
export OAK_BLOCKCHAIN_MODE=mock  # or sepolia

# ✅ Binary Storage (optional but recommended)
export BLOBSTORE_TYPE=ipfs
export IPFS_API_ENDPOINT=/ip4/127.0.0.1/tcp/5001
# Don't forget: ipfs daemon &

# ✅ Consensus (if 3+ validators)
export CONSENSUS_MODE=aeron
export AERON_NODE_ID=0  # unique per validator
export AERON_CLUSTER_MEMBERS="..."

# ✅ Server
export PORT=8090  # or 8092, 8094 for other validators

# ✅ Start
java -jar oak-segment-consensus.jar
```

**Ready for demo!** 🎉

