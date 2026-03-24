# Oak Segment Consensus

**Status**: Active fork / standalone validator baseline established / architecture cleanup and production hardening ongoing
**Purpose**: Standalone validator runtime for Oak Segment Store with Aeron consensus, Ethereum-backed write enforcement, and IPFS-capable blob storage
**Origin**: Blockchain AEM prototype track, now maintained as a fork-first architecture track

**Current Phase**: Standalone validator build is the source of truth. Fragment-host coupling has been removed; boundary cleanup, test hardening, and chain-backed deployment paths remain active work.

## Documentation

Canonical module docs live in the **Blockchain-AEM** docs project:

- [Module documentation home](../../Blockchain-AEM/implementation/oak-segment-consensus/README.md)

This README stays code-centric. Quick start, configuration, IPFS, delete/GC,
API, testing, troubleshooting, and integration docs now live in that doc tree.

## Overview

`oak-segment-consensus` implements a **distributed consensus layer** for Apache Jackrabbit Oak Segment Store, enabling blockchain-backed AEM content repositories with:

- ✅ **Deterministic state machine** (guaranteed consistency via Aeron Cluster)
- ✅ **Aeron Cluster-based Raft consensus** (Aeron is the consensus substrate; validator hardening is still in progress)
- ✅ **Ethereum wallet-based access control** (path ownership enforcement supports mock and chain-backed modes)
- ✅ **HTTP segment transfer** (read-only mounts - write storage is local TAR files only)
- ✅ **Distributed validator network** (all nodes commit writes identically via Aeron Raft - transient differences during genesis/bootstrap)
- ✅ **Dynamic backpressure** (flow control for write throughput)
- ✅ **Embedded HTTP server** (dashboard, APIs, segment serving)
- ✅ **LLM Chat Interface** (optional AI assistant via `oak-segment-agentic`)

This module is part of the **Blockchain AEM** project and is maintained as a forked standalone validator architecture on top of Oak Segment Tar.

## Key Features

### Consensus & State Machine
- **Deterministic State Machine**: All nodes commit writes identically via Aeron's guaranteed message ordering
  - ⚠️ **Genesis Bootstrap**: Followers may have transient extra journal entries until first snapshot (harmless, cleaned up automatically)
- **Aeron Cluster Raft**: Battle-tested consensus algorithm used as the validator's ordering and replication substrate
- **Automatic Leader Election**: Aeron Cluster handles leader election and failover
- **Guaranteed Consistency**: Same message order = same processing = same SegmentStore state (after genesis bootstrap)
- **Quorum Requirements**: Majority-based consensus (2 of 3, 3 of 5, etc.)
- **No Manual Sync**: HEAD consistency for writes is automatic via Aeron Raft (HTTP segment transfer uses polling for read-only mounts)
- **Backpressure Management**: Dynamic flow control prevents cluster overload

### Ethereum Integration
- **Wallet-Based Writes**: All writes require Ethereum wallet signature
  - ⚠️ **Mock Mode**: Payment verification is simulated (no blockchain)
  - ✅ **Sepolia/Mainnet Mode**: Cryptographic verification via smart contract
- **Path Sharding**: Content stored at `/oak-chain/content/{L1}/{L2}/{L3}/0x{wallet}/`
- **Client Registration**: Writes only allowed from registered clients with matching wallet
- **Epoch Tracking**: Ethereum Beacon Chain epoch integration (via `EpochListener`)

### HTTP Server & APIs
- **Dashboard UI**: Web-based dashboard at `/` (cluster state, metrics, explorer)
- **Chat Interface**: LLM-powered chat at `/chat` (requires `oak-segment-agentic`)
- **REST APIs**: Comprehensive API endpoints for cluster state, consensus status, health
- **OSGi Config Surface**: Read-only introspection endpoints at `/v1/config/osgi*`
- **Segment Serving**: HTTP endpoints for segment transfer (`/segments/{id}`, `/journal.log`)

**Security Note**: Validators are pure Oak (no Sling), so they don't have Sling authentication.

**Token-Based Authentication (Optional)**:
- Validators support optional token-based authentication
- Configure token via system property: `-Doak.validator.auth.token=<token>`
- Or environment variable: `OAK_VALIDATOR_AUTH_TOKEN=<token>`
- If no token is configured, authentication is **disabled** (development/default mode - all requests allowed)
- Health checks (`/health`, `/health/deep`) are always public (needed for monitoring)
- Clients should send the raw token value in the `Authorization` header when token auth is configured

**Production Deployment**:
- For production, validators should be protected by:
  - Token-based authentication (configure token)
  - Network security (firewall, VPN, private networks)
  - Reverse proxy authentication

### Segment Replication
- **HTTP Segment Transfer**: Segments replicated via HTTP GET requests (read-only mounts)
  - ⚠️ **Write Storage**: Local TAR files only (cloud storage backend pending)
  - ✅ **Read Transfer**: HTTP segment transfer works for read-only mounts
- **CAS Updates**: Conditional requests with HEAD checks for efficiency
- **Multi-Peer Mounts**: Multiple Sling authors can mount read-only global store

## Architecture

### Deterministic State Machine Model

**Key Insight**: All validators process writes identically via Aeron's guaranteed message ordering.

```
Write Flow (Deterministic):
1. Client submits write to ANY validator (leader or follower)
2. Validator validates Ethereum wallet signature
3. Validator sends write through Aeron Cluster ingress
4. Aeron routes to leader (automatic)
5. Leader replicates via Raft to ALL nodes (automatic)
6. ALL nodes receive message in SAME ORDER
7. ALL nodes execute SAME commit logic
8. Result: IDENTICAL SegmentStore state on all nodes
   - Same content tree
   - Same segments
   - Same HEAD pointer (as natural consequence)
```

**Benefits**:
- ✅ Guaranteed consistency (no eventual consistency window after genesis bootstrap)
- ✅ No manual HEAD broadcasting needed (Aeron Raft handles replication)
- ✅ Simpler architecture (Aeron handles consensus, we focus on Ethereum integration)
- ✅ Better performance (no HTTP sync overhead for writes)
- ✅ Strong consensus foundation (Aeron Cluster is production-grade; validator-specific production hardening remains in progress)

### Distributed Validator Network

```
┌─────────────────────────────────────────────────────────────────────┐
│              Distributed Validator Network                         │
│  ┌──────────────┐      ┌──────────────┐      ┌──────────────┐    │
│  │ Validator-0  │      │ Validator-1  │      │ Validator-2  │    │
│  │ (US-East)    │      │ (EU-West)     │      │ (AP-South)   │    │
│  │ 10.0.1.10    │◄────►│ 10.0.2.10    │◄────►│ 10.0.3.10    │    │
│  └──────┬───────┘      └──────┬───────┘      └──────┬───────┘    │
│         │                     │                      │              │
│         └─────────────────────┼──────────────────────┘              │
│                               │                                     │
│                    UDP/IP Network (Aeron Cluster Raft)             │
└─────────────────────────────────────────────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────┐
│              GlobalStoreServer (Standalone JAR)              │
│  ┌──────────────────────────────────────────────────────┐   │
│  │  SegmentHttpServer (HTTP API + Dashboard)          │   │
│  │  - Dashboard UI (/)                                 │   │
│  │  - Chat Interface (/chat)                           │   │
│  │  - REST APIs (/v1/*)                               │   │
│  │  - Segment Serving (/segments/*, /journal.log)     │   │
│  └──────────────────────────────────────────────────────┘   │
│                                                              │
│  ┌──────────────────────────────────────────────────────┐   │
│  │  AeronConsensusEngine (Deterministic State Machine) │   │
│  │  - Deterministic write processing (all nodes)       │   │
│  │  - Aeron Cluster message ordering                   │   │
│  │  - Backpressure management                          │   │
│  │  - Leader election (automatic)                      │   │
│  │  - Network partition tolerance                      │   │
│  └──────────────────────────────────────────────────────┘   │
│                                                              │
│  ┌──────────────────────────────────────────────────────┐   │
│  │  SegmentNodeStore (Oak Segment Store)               │   │
│  │  - FileStore (local TAR files)                      │   │
│  │  - Journal.log (revision history)                   │   │
│  └──────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

**Network Topology:**
- Validators communicate via UDP/IP (configurable endpoints)
- Peer URLs can be IP addresses, hostnames, or public URLs
- Supports deployment across multiple data centers/regions
- No hard-coded localhost assumptions - fully distributed

### Key Components

- **`GlobalStoreServer`**: Main server class that orchestrates consensus engine and HTTP server
- **`AeronConsensusEngine`**: Raft-based consensus implementation using Aeron Cluster
- **`SegmentHttpServer`**: Embedded Jetty server with dashboard and APIs
- **`RequestRouter`**: Routes HTTP requests to appropriate handlers
- **`DashboardHandler`**: Renders dashboard UI and chat interface
- **`EpochListener`**: Polls Ethereum Beacon Chain for epoch data
- **`EthereumWallet`**: Wallet signature verification and path enforcement

## Building

### Prerequisites
- Java 11+
- Maven 3.8+
- Docker (for running validators)

### Build Module
```bash
cd jackrabbit-oak
mvn clean install -pl oak-segment-consensus -am -DskipTests -Dbaseline.skip=true -Drat.skip=true
```

### Build Standalone JAR
The module builds a shaded JAR that includes all dependencies:
```bash
cd oak-segment-consensus
ls target/oak-segment-consensus.jar
```

## Running

### Start Validator
```bash
java -jar oak-segment-consensus.jar \
  --port 8091 \
  --aeron-node-id 0 \
  --aeron-cluster-members "0=localhost:20110,1=localhost:20111,2=localhost:20112" \
  --consensus-mode aeron
```

### Environment Variables

**See the [module documentation home](../../Blockchain-AEM/implementation/oak-segment-consensus/README.md) for configuration and operator docs.**

**Quick essentials:**
- `OAK_BLOCKCHAIN_MODE`: `mock` | `sepolia` | `mainnet`
- `BLOBSTORE_TYPE`: `ipfs` (optional - enables IPFS binary storage)
- `IPFS_API_ENDPOINT`: `/ip4/127.0.0.1/tcp/5001` (IPFS API)
- `CONSENSUS_MODE`: `aeron` (multi-validator Raft consensus)
- `AERON_NODE_ID`: `0`, `1`, `2`, ... (unique per validator)
- `AERON_CLUSTER_MEMBERS`: `"0=host:port,1=host:port,..."`
- `PORT`: HTTP server port (default: `8090`)
- `OAK_VALIDATOR_AUTH_TOKEN`: Secret token for API authentication (optional)

### Access Dashboard
Once running, access the dashboard at:
```
http://localhost:8091/
```

## API Endpoints

### Dashboard & UI
- `GET /` - Main dashboard (cluster state, metrics, explorer links)
- `GET /chat` - LLM Chat interface (requires `oak-segment-agentic`)
- `GET /explorer` - Content explorer UI
- `GET /api-browser` - Interactive API browser

### Consensus APIs
- `GET /v1/aeron/cluster-state` - Current cluster state and leader info
- `GET /v1/consensus/status` - Consensus status (Aeron-aware)
- `GET /v1/peers` - List all peers in cluster
- `GET /v1/aeron/leadership-history` - Leadership change history
- `GET /v1/aeron/raft-metrics` - Raft metrics
- `GET /v1/aeron/replication-lag` - Replication lag for followers
- `GET /v1/aeron/node-status` - Node status snapshot
- `GET /v1/blockchain/config` - Effective blockchain mode, config source, gas model, and tier cost estimates

### Health & Metrics
- `GET /health` - Basic health check
- `GET /health/deep` - Comprehensive health validation
- `GET /metrics` - Prometheus metrics
- `GET /api/metrics` - JSON metrics

### Segment Transfer
- `GET /journal.log` - Journal file (revision history)
- `GET /manifest` - Manifest file
- `GET /segments/{id}` - Get segment by ID
- `GET /api/explore?path=/` - Browse node tree (JSON)

### Write Operations
- `POST /v1/propose-write` - Propose write transaction (requires wallet signature)
- `POST /v1/propose-delete` - Propose delete transaction
- `GET /v1/proposals/{id}/status` - Proposal status
- `GET /v1/proposals/pending/count` - Pending proposal count
- `POST /v1/register-client` - Register Sling author client with validator

### Binary / IPFS
- `POST /v1/binary/declare-intent` - Declare binary upload intent
- `GET /v1/binary/check-intent/{intentToken}` - Check intent status
- `POST /v1/binary/complete-upload` - Complete upload with CID
- `GET /api/cid/{blobId}` - CID lookup for blob
- `GET /api/cid/reverse/{cid}` - Reverse CID lookup
- `GET /api/cid/gateway/{blobId}` - Gateway URL for blob
- `GET /api/cid/stats` - CID mapping stats

### Events
- `GET /v1/events/stream` - SSE stream
- `GET /v1/events/recent` - Recent events
- `GET /v1/events/stats` - Event stats

## Integration

### Internal Read-Mount Client
- `oak-segment-consensus` owns the HTTP persistence layer for **cross-cluster reads**
- Used by validators via `LazyHttpNodeStore` to mount other clusters as read-only stores
- The internal client lives under `org.apache.jackrabbit.oak.segment.consensus.mount.http`
- **Note**: AEM customers should use `oak-chain-connector` for client-side mounting

### With `oak-segment-agentic`
- Optional LLM chat module
- Integrated via reflection (no hard dependencies)
- Provides `/chat` endpoint and AI assistant capabilities

### With Sling Authors
- Sling authors mount validator-managed stores as read-only composite mounts
- Dynamic validator URL configuration via `OAK_GLOBAL_STORE_URL`
- Automatic client registration on startup
- Standalone validator packaging is the source of truth; client-side mount adapters belong outside this module

## Module Structure

```
oak-segment-consensus/
├── src/main/java/org/apache/jackrabbit/oak/segment/consensus/
│   ├── aeron/              # Aeron Cluster consensus engine
│   ├── server/             # GlobalStoreServer main class
│   ├── http/server/       # HTTP server and handlers
│   ├── eth/                # Ethereum integration (Beacon Chain)
│   ├── security/           # Wallet signature verification
│   ├── store/              # Composite store builders
│   └── mount/              # Remote mount helpers for composite read paths
│       └── http/           # Internal HTTP transport for read-only cross-cluster mounts
├── pom.xml                 # Maven build configuration
└── README.md               # This file
```

## Documentation

Canonical docs: [../../Blockchain-AEM/implementation/oak-segment-consensus/README.md](../../Blockchain-AEM/implementation/oak-segment-consensus/README.md)

This code-repo README intentionally avoids duplicating reference and operator
docs that now live in `Blockchain-AEM`.

## Status

### ✅ Implemented
- Deterministic state machine (guaranteed consistency after genesis bootstrap)
- Aeron Cluster Raft consensus
- Dynamic backpressure management
- Standalone validator runtime and embedded HTTP control plane
- HTTP segment transfer (read-only mounts)
- Dashboard UI
- Wallet-based write enforcement (mock and chain-backed modes)
- Client registration
- Health monitoring
- Prometheus metrics

### 🚧 Active Work
- Validator/core boundary cleanup (apply path, adapter split, module boundaries)
- Chain-backed deployment paths (Sepolia and beyond)
- Production hardening (monitoring, resilience, security)
- Comprehensive test suite (current: ~50% unit, ~10% integration)
- Production deployment automation

### ⚠️ Known Limitations
- **Transaction Model**: Direct Oak commits via Aeron (no explicit START/COMMIT/ABORT boundaries)
- **Cloud Storage**: Segments stored in local TAR files (no Azure/S3/GCS backend)
- **Multi-Cluster**: Architecture designed for multi-cluster sharding, but implementation is single-cluster only
- **Genesis Bootstrap**: Transient extra journal entries on followers (cleaned up by first snapshot)

### 🔮 Future Enhancements
- Dynamic validator membership
- Revision cleanup governance
- Ethereum smart contract integration (Sepolia → Mainnet)
- Multi-cluster deployment (sharding implementation)
- Cloud-native segment storage (Azure/S3/GCS)

## License

Apache License 2.0 - See [LICENSE](../../LICENSE) for details

## Related Modules

- **`oak-segment-agentic`**: Optional LLM chat interface
- **`oak-segment-tar`**: Core segment store implementation

---

**Part of**: [Blockchain AEM](https://github.com/mhess_adobe/blockchain-aem)
**Repository**: [jackrabbit-oak](https://github.com/somarc/jackrabbit-oak/tree/feature/blockchain-aem-poc)
**Branch**: `feature/blockchain-aem-poc`
