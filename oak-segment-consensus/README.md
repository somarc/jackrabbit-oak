# Oak Segment Consensus

**Status**: 🧪 POC / Active Development  
**Purpose**: Distributed consensus layer for Oak Segment Store - Blockchain AEM proof of concept  
**Garage Week Deadline**: December 15, 2025

## Overview

`oak-segment-consensus` implements a **distributed consensus layer** for Apache Jackrabbit Oak Segment Store, enabling blockchain-backed AEM content repositories with:

- ✅ **Deterministic state machine** (guaranteed consistency via Aeron Cluster)
- ✅ **Aeron Cluster-based Raft consensus** (proven, production-grade)
- ✅ **Ethereum wallet-based access control** (path ownership enforcement)
- ✅ **HTTP segment transfer** (multi-peer read-only mounts)
- ✅ **Distributed validator network** (all nodes commit identically)
- ✅ **Dynamic backpressure** (flow control for write throughput)
- ✅ **Embedded HTTP server** (dashboard, APIs, segment serving)
- ✅ **LLM Chat Interface** (optional AI assistant via `oak-segment-agentic`)

This module is part of the **Blockchain AEM POC** project, demonstrating how Oak can be extended to support distributed, consensus-based content repositories.

## Key Features

### Consensus & State Machine
- **Deterministic State Machine**: All nodes commit writes identically via Aeron's guaranteed message ordering
- **Aeron Cluster Raft**: Battle-tested consensus algorithm with election safety guarantees
- **Automatic Leader Election**: Aeron Cluster handles leader election and failover
- **Guaranteed Consistency**: Same message order = same processing = same SegmentStore state
- **Quorum Requirements**: Majority-based consensus (2 of 3, 3 of 5, etc.)
- **No Manual Sync**: HEAD consistency is automatic, not manually broadcast
- **Backpressure Management**: Dynamic flow control prevents cluster overload

### Ethereum Integration
- **Wallet-Based Writes**: All writes require Ethereum wallet signature
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
- **HTTP Segment Transfer**: Segments replicated via HTTP GET requests
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
- ✅ Guaranteed consistency (no eventual consistency window)
- ✅ No manual HEAD broadcasting needed
- ✅ Simpler architecture (Aeron handles everything)
- ✅ Better performance (no HTTP sync overhead)
- ✅ Production-ready pattern

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
- `CONSENSUS_MODE`: `aeron` (only fully implemented mode)
- `PORT`: HTTP server port (default: 8090)
- `AERON_NODE_ID`: Aeron Cluster node ID (0, 1, 2, ...)
- `AERON_CLUSTER_MEMBERS`: Comma-separated list of cluster members

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

For comprehensive documentation, see the **Blockchain-AEM** documentation repository:

- **[Architecture Docs](../Blockchain-AEM/02-architecture/)** - Core architecture and design
- **[Current Spec](../Blockchain-AEM/01-current-spec/)** - Implementation status and features
- **[Development Guides](../Blockchain-AEM/03-development/)** - Build process, setup, contributing

Key documents:
- `FEATURES-AND-GAPS.md` - Current implementation status
- `AERON-CLUSTER-STRATEGY.md` - Consensus architecture
- `LLM-CHAT-DESIGN.md` - Chat interface design

## Status

### ✅ Implemented
- Deterministic state machine (guaranteed consistency)
- Aeron Cluster Raft consensus
- Dynamic backpressure management
- HTTP segment transfer
- Dashboard UI
- Wallet-based write enforcement
- Client registration
- Health monitoring
- Prometheus metrics

### 🚧 In Progress
- Comprehensive test suite
- Production deployment automation

### 🔮 Future Enhancements
- Dynamic validator membership
- Revision cleanup governance
- Ethereum smart contract integration
- Multi-region deployment

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

