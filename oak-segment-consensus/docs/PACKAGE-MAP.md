# oak-segment-consensus Package Map

**Purpose**: Document the Java package structure and what each package represents  
**Date**: January 10, 2026  
**Module**: `oak-segment-consensus`

---

## Package Hierarchy Overview

```
org.apache.jackrabbit.oak.segment
├── consensus/                    # Core consensus & blockchain logic
│   ├── aeron/                    # Aeron Cluster Raft implementation
│   ├── bootstrap/                # Validator startup/initialization
│   ├── config/                   # Blockchain configuration
│   ├── economics/                # Payment tier economics
│   ├── eth/                      # Ethereum Beacon Chain integration
│   ├── evm/                      # EVM payment verification
│   │   └── impl/                 # EVM bridge implementations
│   ├── fragmentation/            # Storage fragmentation tracking
│   ├── gc/                       # Garbage collection consensus
│   ├── leader/                   # Leader election & health
│   ├── metrics/                  # Consensus metrics
│   ├── mount/                    # Oak mount point integration
│   ├── osgi/                     # OSGi service components
│   ├── queue/                    # Proposal queue management
│   ├── security/                 # Cryptographic verification
│   ├── server/                   # Main server entry point
│   ├── sharding/                 # Wallet-based sharding
│   ├── state/                    # Consensus state data
│   ├── store/                    # Composite store building
│   │   └── impl/                 # Store implementations
│   ├── test/                     # Test utilities
│   ├── util/                     # General utilities
│   └── wallet/                   # Wallet abstraction
│
└── http/                         # HTTP server layer
    └── server/                   # Jetty HTTP server
        ├── binary/               # Binary upload handling
        ├── handlers/             # HTTP request handlers
        ├── model/                # Data transfer objects
        ├── sse/                  # Server-Sent Events
        └── util/                 # HTTP utilities

```

---

## Package Details

### `consensus/` - Root Consensus Package (1 file)

| File | Purpose |
|------|---------|
| `WriteProposal.java` | Data class for write proposals (wallet, path, content, signature) |

---

### `consensus/aeron/` - Aeron Cluster Raft (11 files) ⭐ CORE

The heart of distributed consensus using Aeron Cluster's Raft implementation.

| File | Purpose |
|------|---------|
| `AeronConsensusEngine.java` | **Main consensus engine** (~4400 lines) - Raft state machine, log replication, leader election |
| `AeronClusterLauncher.java` | Starts Aeron Cluster with configured members |
| `AeronWriteClient.java` | Client for sending proposals to Aeron cluster |
| `MessageDispatcher.java` | Routes incoming Aeron messages to handlers |
| `SimpleMessageHeader.java` | SBE message header encoding |
| `SnapshotService.java` | Aeron snapshot creation/restoration |
| `LeaderDiscoveryService.java` | Discovers current cluster leader |
| `MediaDriverHealthMonitor.java` | Monitors Aeron Media Driver health |
| `AeronPerformanceMetrics.java` | Performance metrics collection |
| `AeronPrometheusMetrics.java` | Prometheus metrics export |
| `CrashHandler.java` | Handles Aeron crash recovery |

**Key Concepts**:
- Aeron Cluster provides Raft consensus with ~100μs latency
- All writes go through leader, replicated to followers
- Snapshots persist state for fast recovery

---

### `consensus/bootstrap/` - Validator Startup (1 file)

| File | Purpose |
|------|---------|
| `ValidatorBootstrap.java` | Initializes validator node on startup |

---

### `consensus/config/` - Configuration (1 file)

| File | Purpose |
|------|---------|
| `BlockchainConfig.java` | Ethereum network config (Sepolia/Mainnet addresses, RPC URLs) |

---

### `consensus/economics/` - Payment Economics (1 file)

| File | Purpose |
|------|---------|
| `ValidatorEarningsTracker.java` | Tracks validator earnings, payment tiers (STANDARD/EXPRESS/PRIORITY) |

**Payment Tiers**:
- `STANDARD`: 2-epoch finality (~12.8 min), lowest cost
- `EXPRESS`: 1-epoch finality (~6.4 min), medium cost
- `PRIORITY`: Immediate, highest cost

---

### `consensus/eth/` - Ethereum Beacon Chain (3 files)

| File | Purpose |
|------|---------|
| `BeaconChainClient.java` | Polls Beacon Chain API for epoch data |
| `EpochData.java` | Data class for Ethereum epoch information |
| `EpochListener.java` | Callback interface for epoch events |

**Purpose**: Uses Ethereum's Beacon Chain epochs as an external time oracle for finality guarantees.

---

### `consensus/evm/` - EVM Payment Verification (2 files + 3 impl)

**Interface Layer**:

| File | Purpose |
|------|---------|
| `EvmBridge.java` | Interface for EVM payment verification |
| `PaymentProof.java` | Interface for payment proof data |

**Implementations** (`evm/impl/`):

| File | Purpose |
|------|---------|
| `SimpleEvmBridge.java` | Mock EVM bridge for development/testing |
| `EventDrivenEvmBridge.java` | Real Web3j-based EVM bridge (Sepolia/Mainnet) |
| `SimplePaymentProof.java` | Simple payment proof implementation |

---

### `consensus/fragmentation/` - Storage Fragmentation (2 files)

| File | Purpose |
|------|---------|
| `FragmentationTracker.java` | Tracks TAR file fragmentation metrics |
| `WalletStorageMetrics.java` | Per-wallet storage usage metrics |

**Purpose**: Monitors storage efficiency for GC cost estimation.

---

### `consensus/gc/` - Garbage Collection Consensus (11 files) ⭐ CORE

Distributed GC with economic incentives.

| File | Purpose |
|------|---------|
| `GCProposalManager.java` | Manages GC proposal lifecycle |
| `GCProposal.java` | GC proposal data class |
| `GCVote.java` | Validator vote on GC proposals |
| `GCAccountManager.java` | Per-wallet GC debt tracking |
| `EntityGCAccount.java` | Individual wallet's GC account |
| `GCCostEstimator.java` | Estimates GC cost based on fragmentation |
| `GCCostEstimate.java` | GC cost estimate data class |
| `GCExecutionResult.java` | Result of GC execution |
| `GasOracle.java` | Interface for gas price oracle |
| `MockGasOracle.java` | Mock gas oracle for testing |
| `PeriodicGCJob.java` | Scheduled GC execution |

**Key Concepts**:
- Deletes create "GC debt" for the wallet
- GC requires consensus (voting) before execution
- Economic model: users pay for cleanup costs

---

### `consensus/leader/` - Leader Election (4 files)

| File | Purpose |
|------|---------|
| `LeaderElection.java` | Leader election logic |
| `LeaderHealthMonitor.java` | Monitors leader health |
| `LeadershipClaimTracker.java` | Tracks leadership claims and acknowledgments |
| `ValidatorRole.java` | Enum: LEADER, FOLLOWER, CANDIDATE |

---

### `consensus/metrics/` - Metrics (1 file)

| File | Purpose |
|------|---------|
| `ConsensusMetrics.java` | Consensus-specific metrics |

---

### `consensus/mount/` - Oak Mount Integration (1 file)

| File | Purpose |
|------|---------|
| `GlobalChainMounter.java` | Mounts global chain as Oak composite mount |

---

### `consensus/osgi/` - OSGi Services (3 files)

| File | Purpose |
|------|---------|
| `BlockchainNodeStoreService.java` | OSGi service for blockchain NodeStore |
| `ConsensusActivator.java` | OSGi bundle activator |
| `GlobalChainMountProvider.java` | Provides global chain mount to Oak |

---

### `consensus/queue/` - Proposal Queue (9 files) ⭐ CORE

Manages the flow from HTTP API → verification → Aeron.

| File | Purpose |
|------|---------|
| `ProposalQueueManagerOptimized.java` | **Production queue** - tri-agent, epoch batching |
| `EpochBasedBatchQueue.java` | Groups proposals by Ethereum epoch |
| `QueuedProposal.java` | Queued proposal data class (includes `ipfsCid` for ADR 016) |
| `ProposalState.java` | Enum: PENDING, VERIFIED, REJECTED, PROCESSED |
| `ProposalStatus.java` | Status DTO for API responses |
| `BackpressureManager.java` | Flow control for Aeron |
| `BackpressureTimeoutException.java` | Backpressure timeout exception |
| `RaftAppendCallback.java` | Interface for appending to Raft log (includes `ipfsCid` param) |

**Architecture**:
```
HTTP API → Unverified Queue → [EVM Verifier] → Epoch Queue → [Aeron Sender] → Raft
```

**ADR 016 - Client-Side IPFS Upload**:
- `QueuedProposal.ipfsCid`: IPFS CID from client-side upload
- `RaftAppendCallback.appendProposal()`: Now accepts `ipfsCid` parameter
- CID flows through: Client → API → Queue → Aeron → Content Node property

---

### `consensus/security/` - Cryptographic Security (5 files)

| File | Purpose |
|------|---------|
| `EthereumWallet.java` | Ethereum wallet key management |
| `ClaimSigner.java` | Signs leadership claims |
| `ClaimVerifier.java` | Verifies leadership claim signatures |
| `JoinProof.java` | Proof for joining validator network |
| `ProofVerifier.java` | Verifies various cryptographic proofs |

---

### `consensus/server/` - Main Server (1 file)

| File | Purpose |
|------|---------|
| `GlobalStoreServer.java` | **Main entry point** - starts FileStore, HTTP server, Aeron cluster |

---

### `consensus/sharding/` - Wallet Sharding (5 files)

| File | Purpose |
|------|---------|
| `ShardRouter.java` | Routes requests to correct shard |
| `ShardingStrategy.java` | Interface for sharding strategies |
| `WalletShardingStrategy.java` | Shards by wallet address prefix |
| `ShardDirectory.java` | Shard directory structure |
| `ShardInfo.java` | Shard metadata |

**Sharding Pattern**:
```
/oak-chain/{L1}/{L2}/{L3}/0xWALLET/content/...
         ↑    ↑    ↑
         First 6 hex chars of wallet address
```

---

### `consensus/state/` - Consensus State (1 file)

| File | Purpose |
|------|---------|
| `ConsensusState.java` | Data class for consensus state (used by dashboard) |

---

### `consensus/store/` - Composite Store (2 files + 2 impl)

**Interface Layer**:

| File | Purpose |
|------|---------|
| `CompositeStoreBuilder.java` | Interface for building composite stores |
| `GlobalStoreMount.java` | Interface for global store mount |

**Implementations** (`store/impl/`):

| File | Purpose |
|------|---------|
| `SimpleCompositeStoreBuilder.java` | Simple composite store builder |
| `SimpleGlobalStoreMount.java` | Simple global store mount |

---

### `consensus/test/` - Test Utilities (2 files)

| File | Purpose |
|------|---------|
| `DoItLiveTest.java` | Live integration test |
| `VerifyGenesisReadable.java` | Verifies genesis block is readable |

---

### `consensus/util/` - Utilities (2 files)

| File | Purpose |
|------|---------|
| `WalletPathUtil.java` | Wallet path utilities (shard root calculation) |
| `SegmentReplicator.java` | Segment replication utilities |

---

### `consensus/wallet/` - Wallet Abstraction (1 file)

| File | Purpose |
|------|---------|
| `Wallet.java` | Wallet abstraction interface |

---

## HTTP Server Packages

### `http/server/` - HTTP Server Core (4 files)

| File | Purpose |
|------|---------|
| `SegmentHttpServer.java` | Jetty HTTP server setup |
| `RequestRouter.java` | Routes HTTP requests to handlers |
| `ServerContext.java` | Shared context for all handlers |
| `AuthTokenValidator.java` | Validates authentication tokens |

---

### `http/server/binary/` - Binary Upload (4 files)

| File | Purpose |
|------|---------|
| `UploadSessionManager.java` | Manages binary upload sessions |
| `UploadSession.java` | Individual upload session |
| `UploadStatus.java` | Upload status enum |
| `CidMappingService.java` | Maps Oak blob IDs to IPFS CIDs |

---

### `http/server/handlers/` - HTTP Handlers (15 files) ⭐ CORE

| File | Purpose |
|------|---------|
| `DashboardHandler.java` | Validator dashboard UI |
| `ConsensusApiHandler.java` | Write/delete proposal API |
| `ExplorerApiHandler.java` | Content explorer API |
| `FragmentationApiHandler.java` | GC and fragmentation API |
| `BinaryUploadHandler.java` | Binary upload API |
| `CidApiHandler.java` | IPFS CID mapping API |
| `AeronApiHandler.java` | Aeron cluster status API |
| `BlockchainConfigApiHandler.java` | Blockchain config API |
| `HealthHandler.java` | Health check endpoint |
| `MetricsHandler.java` | Prometheus metrics endpoint |
| `RegistrationHandler.java` | Validator registration |
| `PeerDiscoveryHandler.java` | Peer discovery |
| `LeaderConsensusHandler.java` | Leader consensus operations |
| `EventStreamHandler.java` | SSE event stream |
| `FileHandler.java` | Static file serving |

---

### `http/server/model/` - Data Models (3 files)

| File | Purpose |
|------|---------|
| `ClientRegistration.java` | Client registration DTO |
| `ValidatorRegistration.java` | Validator registration DTO |
| `WriteMetadata.java` | Write metadata DTO |

---

### `http/server/sse/` - Server-Sent Events (3 files)

| File | Purpose |
|------|---------|
| `EventBroadcaster.java` | Broadcasts events to SSE clients |
| `SSEClient.java` | Individual SSE client connection |
| `ContentEvent.java` | Content change event |

---

### `http/server/util/` - HTTP Utilities (3 files)

| File | Purpose |
|------|---------|
| `DashboardDataService.java` | Gathers data for dashboard |
| `FormatUtils.java` | Formatting utilities (bytes, dates) |
| `JsonParser.java` | Simple JSON parsing |

---

## Package Statistics

| Package | Files | Description |
|---------|-------|-------------|
| `consensus/aeron` | 11 | Aeron Cluster Raft |
| `consensus/gc` | 11 | Garbage collection |
| `consensus/queue` | 9 | Proposal queue |
| `http/server/handlers` | 15 | HTTP handlers |
| `consensus/sharding` | 5 | Wallet sharding |
| `consensus/security` | 5 | Cryptographic security |
| `http/server/binary` | 4 | Binary upload |
| `consensus/leader` | 4 | Leader election |
| **Total** | **~100** | |

---

## Technical Debt Notes

### Legacy Code
- ~~`ProposalQueueManager.java`~~ - **DELETED** (January 10, 2026) - Tests migrated to `ProposalQueueManagerOptimized`

### Missing Tests
- Most packages have 0% test coverage
- Priority: `aeron/`, `gc/`, `queue/`, `handlers/`

### TODOs
- See `GAPS-AND-TESTING-REQUIREMENTS.md` for full TODO list

---

*Generated: January 10, 2026*
