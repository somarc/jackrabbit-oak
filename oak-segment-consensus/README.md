# Oak Segment Consensus

**Status**: ✅ **POC Complete (Mock Mode)** / 🚧 **Sepolia Phase Pending**  
**Purpose**: Distributed consensus layer for Oak Segment Store - Blockchain AEM proof of concept  
**Garage Week Deadline**: December 15, 2025 (✅ Completed)

**Current Phase**: POC-complete in mock mode. Sepolia testnet deployment and production hardening pending.

## 📖 Quick Links

### Module Documentation (This Repository)
- **[CONFIGURATION.md](CONFIGURATION.md)** - Complete environment variables & system properties reference
- **[QUICK-START.md](QUICK-START.md)** - Quick start guide for developers
- **[IPFS-DATASTORE.md](IPFS-DATASTORE.md)** - IPFS binary storage guide (ADR 015)
- **[DELETE-QUICK-REFERENCE.md](DELETE-QUICK-REFERENCE.md)** - Quick reference for delete/GC development

### Comprehensive Documentation

For architecture deep dives, gap analysis, and detailed implementation docs, see the **Blockchain-AEM** documentation repository:
- Architecture & implementation details
- Gap analysis vs Oak Repository Service
- Package structure maps
- Technical deep dives

## Overview

`oak-segment-consensus` implements a **distributed consensus layer** for Apache Jackrabbit Oak Segment Store, enabling blockchain-backed AEM content repositories with:

- ✅ **Deterministic state machine** (guaranteed consistency via Aeron Cluster)
- ✅ **Aeron Cluster-based Raft consensus** (Aeron is production-grade; our integration is POC-complete)
- ✅ **Ethereum wallet-based access control** (path ownership enforcement - mock mode: simulated, Sepolia/Mainnet: cryptographic)
- ✅ **HTTP segment transfer** (read-only mounts - write storage is local TAR files only)
- ✅ **Distributed validator network** (all nodes commit writes identically via Aeron Raft - transient differences during genesis/bootstrap)
- ✅ **Dynamic backpressure** (flow control for write throughput)
- ✅ **Embedded HTTP server** (dashboard, APIs, segment serving)
- ✅ **LLM Chat Interface** (optional AI assistant via `oak-segment-agentic`)

This module is part of the **Blockchain AEM POC** project, demonstrating how Oak can be extended to support distributed, consensus-based content repositories.

## Key Features

### Consensus & State Machine
- **Deterministic State Machine**: All nodes commit writes identically via Aeron's guaranteed message ordering
  - ⚠️ **Genesis Bootstrap**: Followers may have transient extra journal entries until first snapshot (harmless, cleaned up automatically)
- **Aeron Cluster Raft**: Battle-tested consensus algorithm (Aeron is production-grade; our integration is POC-complete)
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
- **Segment Serving**: HTTP endpoints for segment transfer (`/segments/{id}`, `/journal.log`)

**Security Note**: Validators are pure Oak (no Sling), so they don't have Sling authentication. 

**Token-Based Authentication (Optional)**:
- Validators support optional token-based authentication
- Configure token via system property: `-Doak.validator.auth.token=<token>`
- Or environment variable: `OAK_VALIDATOR_AUTH_TOKEN=<token>`
- If no token is configured, authentication is **disabled** (POC mode - all requests allowed)
- Health checks (`/health`, `/health/deep`) are always public (needed for monitoring)
- Clients should use `ValidatorAuthHelper` to add `Authorization` header when token is configured

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
- ✅ Production-ready pattern (Aeron Cluster is production-grade; our integration is POC-complete)

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

**See [CONFIGURATION.md](CONFIGURATION.md) for complete reference.**

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
- `POST /v1/register-client` - Register Sling author client with validator

## Integration

### With `oak-segment-http`
- `oak-segment-http` provides HTTP persistence layer for clients
- Used by Sling authors to mount read-only global store
- Handles segment fetching and journal polling

### With `oak-segment-agentic`
- Optional LLM chat module
- Integrated via reflection (no hard dependencies)
- Provides `/chat` endpoint and AI assistant capabilities

### With Sling Authors
- Sling authors mount validator's global store as read-only composite mount
- Dynamic validator URL configuration via `OAK_GLOBAL_STORE_URL`
- Automatic client registration on startup

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
│   └── osgi/               # OSGi integration
├── pom.xml                 # Maven build configuration
└── README.md               # This file
```

## Documentation

### Developer Documentation

**Essential Guides**:
- **[CONFIGURATION.md](CONFIGURATION.md)** - Environment variables and system properties
- **[QUICK-START.md](QUICK-START.md)** - Quick start guide
- **[IPFS-DATASTORE.md](IPFS-DATASTORE.md)** - IPFS binary storage setup
- **[DELETE-QUICK-REFERENCE.md](DELETE-QUICK-REFERENCE.md)** - Delete/GC quick reference

**Comprehensive Developer Docs** (`docs/`):
- **[API Reference](docs/api/README.md)** - Complete HTTP API documentation
- **[Architecture Overview](docs/architecture/README.md)** - How the system works
- **[Development Guide](docs/development/README.md)** - How to extend the module
- **[Testing Guide](docs/testing/README.md)** - How to test
- **[Troubleshooting](docs/troubleshooting/README.md)** - Common issues and solutions
- **[Integration Guide](docs/integration/README.md)** - Sling, Docker, Kubernetes integration

### Project Documentation

For architecture deep dives, gap analysis, package maps, and technical specifications, see the **Blockchain-AEM** documentation repository.

## Status

### ✅ Implemented (POC-Complete)
- Deterministic state machine (guaranteed consistency after genesis bootstrap)
- Aeron Cluster Raft consensus (mock mode)
- Dynamic backpressure management
- HTTP segment transfer (read-only mounts)
- Dashboard UI
- Wallet-based write enforcement (mock mode: simulated, Sepolia: real)
- Client registration
- Health monitoring
- Prometheus metrics

### 🚧 In Progress / Pending
- **Sepolia Phase**: Smart contract integration, real payment verification (6 TODOs)
- **Production Hardening**: Monitoring, resilience, security (6 TODOs)
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

- **`oak-segment-http`**: HTTP persistence layer for clients
- **`oak-segment-agentic`**: Optional LLM chat interface
- **`oak-segment-tar`**: Core segment store implementation

---

**Part of**: [Blockchain AEM POC](https://github.com/mhess_adobe/blockchain-aem)  
**Repository**: [jackrabbit-oak](https://github.com/somarc/jackrabbit-oak/tree/feature/blockchain-aem-poc)  
**Branch**: `feature/blockchain-aem-poc`

