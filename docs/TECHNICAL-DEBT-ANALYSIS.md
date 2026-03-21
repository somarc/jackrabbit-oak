# Technical Debt Analysis - Blockchain AEM Modules

**Purpose**: Identify large classes requiring review for test coverage, dead code, and state machine understanding  
**Date**: January 10, 2026  
**Status**: Active Review  
**Related**: [GAPS-AND-TESTING-REQUIREMENTS.md](GAPS-AND-TESTING-REQUIREMENTS.md)

---

## Table of Contents

1. [Executive Summary](#executive-summary)
2. [oak-segment-consensus](#oak-segment-consensus)
3. [oak-segment-http](#oak-segment-http)
4. [oak-blob-cloud-ipfs](#oak-blob-cloud-ipfs)
5. [oak-auth-web3](#oak-auth-web3)
6. [Review Checklist Template](#review-checklist-template)
7. [Priority Order](#priority-order)

---

## Executive Summary

### Total Lines of Code by Module

| Module | Total LOC | Files | Avg LOC/File | Largest File |
|--------|-----------|-------|--------------|--------------|
| **oak-segment-consensus** | ~25,000+ | 70+ | ~360 | AeronConsensusEngine (4,586) |
| **oak-segment-http** | ~1,700+ | 10 | ~175 | HttpPersistenceService (336) |
| **oak-blob-cloud-ipfs** | ~590 | 2 | ~295 | IPFSBackend (422) |
| **oak-auth-web3** | ~3,000 | 10 | ~300 | Web3BiometricLoginModule (536) |

### Classes Requiring Deep Review (>300 LOC)

| Priority | Class | LOC | Module | Tests | Review Status |
|----------|-------|-----|--------|-------|---------------|
| 🔴 P0 | AeronConsensusEngine | 4,586 | consensus | 0 | ✅ ANALYZED |
| 🔴 P0 | GlobalStoreServer | 2,429 | consensus | 0 | ✅ ANALYZED |
| 🔴 P0 | ConsensusApiHandler | 2,293 | consensus | 0 | ✅ ANALYZED |
| 🟡 P1 | DashboardHandler | 1,681 | consensus | 0 | 🔲 TODO |
| 🟡 P1 | ProposalQueueManagerOptimized | 1,279 | consensus | 6 | 🔲 TODO |
| 🟡 P1 | RequestRouter | 898 | consensus | 0 | 🔲 TODO |
| 🟡 P1 | AeronClusterLauncher | 892 | consensus | 0 | 🔲 TODO |
| 🟡 P1 | FragmentationApiHandler | 863 | consensus | 0 | 🔲 TODO |
| 🟡 P1 | SegmentHttpServer | 746 | consensus | 0 | 🔲 TODO |
| 🟡 P1 | AeronApiHandler | 624 | consensus | 0 | 🔲 TODO |
| 🟡 P1 | MessageDispatcher | 592 | consensus | 0 | 🔲 TODO |
| 🟡 P1 | ConsensusMetrics | 555 | consensus | 0 | 🔲 TODO |
| 🟡 P1 | EventDrivenEvmBridge | 553 | consensus | 4 | 🔲 TODO |
| 🟡 P1 | LeaderDiscoveryService | 541 | consensus | 0 | 🔲 TODO |
| 🟡 P1 | SnapshotService | 538 | consensus | 0 | 🔲 TODO |
| 🟡 P1 | GCProposalManager | 534 | consensus | 0 | 🔲 TODO |
| 🟡 P1 | BeaconChainClient | 527 | consensus | 0 | 🔲 TODO |
| 🟡 P1 | EpochBasedBatchQueue | 525 | consensus | 0 | 🔲 TODO |
| 🟢 P2 | Web3BiometricLoginModule | 536 | auth-web3 | 0 | 🔲 TODO |
| 🟢 P2 | PasskeyStore | 482 | auth-web3 | 9 | 🔲 TODO |
| 🟢 P2 | IPFSBackend | 422 | ipfs | 19 | 🔲 TODO |
| 🟢 P2 | LocalP256Verifier | 413 | auth-web3 | 15 | 🔲 TODO |
| 🟢 P2 | ChallengeService | 405 | auth-web3 | 28 | 🔲 TODO |
| 🟢 P2 | HttpPersistenceService | 336 | http | 0 | 🔲 TODO |
| 🟢 P2 | HttpSegmentArchiveManager | 235 | http | 0 | 🔲 TODO |
| 🟢 P2 | Web3BiometricLoginModuleFactory | 314 | auth-web3 | 0 | 🔲 TODO |

---

## oak-segment-consensus

### Critical Classes (>1000 LOC) - 🔴 Highest Risk

#### 1. AeronConsensusEngine.java (4,586 lines)

**Location**: `consensus/aeron/AeronConsensusEngine.java`

**Purpose**: Core Aeron Cluster consensus engine - handles Raft consensus, write replication, leadership, snapshots

**Known Issues**:
- 0 unit tests for 4,500+ lines of code
- Multiple unused fields flagged by linter
- Contains deprecated method references
- Complex state machine with multiple roles (LEADER, FOLLOWER, CANDIDATE)

**State Machines to Document**:
- [x] Role transitions (FOLLOWER ↔ LEADER ↔ CANDIDATE) ✅ See PHASE1-ANALYSIS-AERON-CONSENSUS-ENGINE.md
- [x] Write proposal lifecycle ✅ See PHASE1-ANALYSIS-AERON-CONSENSUS-ENGINE.md
- [x] Snapshot creation/restoration ✅ See PHASE1-ANALYSIS-AERON-CONSENSUS-ENGINE.md
- [x] Epoch tracking and finality ✅ See PHASE1-ANALYSIS-AERON-CONSENSUS-ENGINE.md

**Review Checklist**:
- [x] Dead code analysis ✅ Completed - see PHASE1-ANALYSIS-AERON-CONSENSUS-ENGINE.md
- [x] Unused field cleanup ✅ Identified - 8 unused fields
- [x] State machine documentation ✅ 4 state machines documented
- [ ] Unit test creation 🔲 TODO
- [ ] Integration test scenarios 🔲 TODO

**Analysis Document**: [PHASE1-ANALYSIS-AERON-CONSENSUS-ENGINE.md](PHASE1-ANALYSIS-AERON-CONSENSUS-ENGINE.md)

---

#### 2. GlobalStoreServer.java (2,429 lines)

**Location**: `consensus/server/GlobalStoreServer.java`

**Purpose**: Main server entry point - initializes FileStore, Aeron cluster, HTTP server

**Known Issues**:
- 0 unit tests
- Multiple unused fields/variables (linter warnings)
- Complex initialization sequence
- Recently had dead code removed (syncGenesisFromPeer)

**State Machines to Document**:
- [x] Server startup sequence ✅ See PHASE1-ANALYSIS-GLOBAL-STORE-SERVER.md
- [x] Aeron cluster initialization ✅ See PHASE1-ANALYSIS-GLOBAL-STORE-SERVER.md
- [x] Genesis creation flow ✅ See PHASE1-ANALYSIS-GLOBAL-STORE-SERVER.md
- [x] Shutdown sequence ✅ See PHASE1-ANALYSIS-GLOBAL-STORE-SERVER.md

**Review Checklist**:
- [x] Dead code analysis ✅ Completed - 12 linter warnings identified
- [x] Startup sequence documentation ✅ 3 state machines documented
- [x] Clean startup evidence review ✅ See STARTUP-ARCHITECTURE-CLEANUP.md
- [ ] Collapse Aeron-first boot path and demote standby to recovery-only
- [ ] Error handling review 🔲 TODO
- [ ] Unit test creation 🔲 TODO

**Analysis Document**: [PHASE1-ANALYSIS-GLOBAL-STORE-SERVER.md](PHASE1-ANALYSIS-GLOBAL-STORE-SERVER.md)
**Current Cleanup Task**: [STARTUP-ARCHITECTURE-CLEANUP.md](STARTUP-ARCHITECTURE-CLEANUP.md)

---

#### 3. ConsensusApiHandler.java (2,293 lines)

**Location**: `http/server/handlers/ConsensusApiHandler.java`

**Purpose**: HTTP API handler for write proposals, reads, and consensus operations

**Known Issues**:
- 0 unit tests
- Handles critical write path
- Complex signature verification logic
- Multiple TODO comments

**State Machines to Document**:
- [x] Write proposal flow ✅ See PHASE1-ANALYSIS-CONSENSUS-API-HANDLER.md
- [x] Delete proposal flow ✅ See PHASE1-ANALYSIS-CONSENSUS-API-HANDLER.md
- [x] Apply replicated write flow ✅ See PHASE1-ANALYSIS-CONSENSUS-API-HANDLER.md
- [ ] Signature verification 🔲 TODO (security review)

**Review Checklist**:
- [ ] Security review (signature verification) 🔲 TODO
- [x] Input validation completeness ✅ Analyzed - duplication identified
- [x] Error response consistency ✅ Good error messages
- [ ] Unit test creation 🔲 TODO

**Analysis Document**: [PHASE1-ANALYSIS-CONSENSUS-API-HANDLER.md](PHASE1-ANALYSIS-CONSENSUS-API-HANDLER.md)

---

### High Priority Classes (500-1000 LOC) - 🟡 Medium Risk

#### 4. DashboardHandler.java (1,681 lines)

**Purpose**: Web dashboard UI generation

**Risk Level**: Low (UI only, no consensus logic)

**Review Focus**:
- [ ] XSS prevention
- [ ] Information disclosure
- [ ] Dead code (unused endpoints)

---

#### 5. ProposalQueueManagerOptimized.java (1,279 lines)

**Purpose**: Manages write proposal queue with epoch-based batching

**Tests**: 6 (ProposalQueueIntegrationTest)

**State Machines to Document**:
- [ ] Proposal lifecycle (PENDING → CONFIRMED → VERIFIED → PROCESSED)
- [ ] Epoch batching logic
- [ ] Priority tier handling (STANDARD, EXPRESS, PRIORITY)

**Review Focus**:
- [ ] Concurrency safety
- [ ] Memory management
- [ ] Backpressure handling

---

#### 6. RequestRouter.java (898 lines)

**Purpose**: Routes HTTP requests to appropriate handlers

**Review Focus**:
- [ ] Route completeness
- [ ] Authentication enforcement
- [ ] Dead routes

---

#### 7. AeronClusterLauncher.java (892 lines)

**Purpose**: Launches and configures Aeron cluster

**Review Focus**:
- [ ] Configuration validation
- [ ] Error handling
- [ ] Resource cleanup

---

#### 8. FragmentationApiHandler.java (863 lines)

**Purpose**: Handles storage fragmentation and GC APIs

**Review Focus**:
- [ ] GC safety
- [ ] Concurrent access handling

---

#### 9. SegmentHttpServer.java (746 lines)

**Purpose**: Jetty HTTP server setup and configuration

**Review Focus**:
- [ ] Security headers
- [ ] TLS configuration
- [ ] Resource limits

---

#### 10. AeronApiHandler.java (624 lines)

**Purpose**: Aeron cluster status and control APIs

**Review Focus**:
- [ ] Cluster state exposure
- [ ] Control operation safety

---

#### 11. MessageDispatcher.java (592 lines)

**Purpose**: Dispatches Aeron messages to handlers

**State Machines to Document**:
- [ ] Message routing logic
- [ ] Handler registration

---

#### 12. ConsensusMetrics.java (555 lines)

**Purpose**: Prometheus metrics collection

**Review Focus**:
- [ ] Metric completeness
- [ ] Performance impact

---

#### 13. EventDrivenEvmBridge.java (553 lines)

**Purpose**: Ethereum event subscription and payment verification

**Tests**: 4 (EventDrivenEvmBridgeTest)

**State Machines to Document**:
- [ ] Event subscription lifecycle
- [ ] Payment verification flow

---

#### 14. LeaderDiscoveryService.java (541 lines)

**Purpose**: Discovers current leader in Aeron cluster

**State Machines to Document**:
- [ ] Discovery strategies (local → cache → reflection → HTTP)

---

#### 15. SnapshotService.java (538 lines)

**Purpose**: Creates and restores Aeron snapshots

**State Machines to Document**:
- [ ] Snapshot creation flow
- [ ] Snapshot restoration flow

---

#### 16. GCProposalManager.java (534 lines)

**Purpose**: Manages garbage collection proposals

**State Machines to Document**:
- [ ] GC proposal lifecycle (PENDING → VOTING → APPROVED → EXECUTING → COMPLETED)

---

#### 17. BeaconChainClient.java (527 lines)

**Purpose**: Ethereum beacon chain epoch tracking

**Review Focus**:
- [ ] API compatibility
- [ ] Error handling
- [ ] Deprecated method usage

---

#### 18. EpochBasedBatchQueue.java (525 lines)

**Purpose**: Batches proposals by Ethereum epoch

**State Machines to Document**:
- [ ] Batch lifecycle
- [ ] Epoch boundary handling

---

### Medium Priority Classes (300-500 LOC)

| Class | LOC | Purpose | Tests |
|-------|-----|---------|-------|
| WalletStorageMetrics | 445 | Storage metrics per wallet | 0 |
| ValidatorBootstrap | 445 | Validator initialization | 0 |
| DashboardDataService | 381 | Dashboard data provider | 0 |
| WalletPathUtil | 372 | Wallet path utilities | 0 |
| CrashHandler | 372 | Crash recovery | 0 |
| LeaderHealthMonitor | 362 | Leader health checks | 0 |
| HealthHandler | 360 | Health check endpoints | 0 |
| ProofVerifier | 340 | Join proof verification | 0 |
| ExplorerApiHandler | 335 | Content explorer API | 0 |
| AeronPerformanceMetrics | 335 | Performance metrics | 0 |
| EthereumSignatureVerifier | 334 | Signature verification | 0 |
| JoinProof | 322 | Join proof data structure | 0 |
| AeronPrometheusMetrics | 322 | Prometheus integration | 0 |
| GCCostEstimator | 318 | GC cost calculation | 10 |
| CidMappingService | 314 | IPFS CID mapping | 0 |
| BinaryUploadHandler | 311 | Binary upload handling | 0 |
| EventBroadcaster | 304 | SSE event broadcasting | 0 |
| ContentEvent | 297 | Content change events | 0 |
| EpochListener | 296 | Epoch change listener | 0 |
| ShardRouter | 292 | Shard routing logic | 0 |
| EthereumWallet | 292 | Wallet abstraction | 0 |

---

## oak-segment-http

### All Classes (Sorted by LOC)

| Class | LOC | Purpose | Tests | Review Status |
|-------|-----|---------|-------|---------------|
| HttpPersistenceService | 336 | OSGi service for HTTP persistence | 0 | 🔲 TODO |
| HttpSegmentArchiveManager | 235 | Archive management | 0 | 🔲 TODO |
| HttpClientPool | 209 | HTTP connection pooling | 0 | 🔲 TODO |
| HttpSegmentArchiveReader | 144 | Segment reading | 0 | 🔲 TODO |
| HttpClientPool.ConnectionPoolMonitor | 127 | Pool watchdog thread | 0 | 🔲 TODO |
| ValidatorAuthHelper | 122 | Auth helper | 4 | ✅ DONE |
| HttpJournalFile | 146 | Journal file access | 0 | 🔲 TODO |
| HttpPersistence | 155 | Persistence factory | 16 | ✅ DONE |
| HttpManifestFile | 83 | Manifest file access | 0 | 🔲 TODO |
| HttpGCJournalFile | 80 | GC journal access | 0 | 🔲 TODO |
| Http2ClientPool | 228 | HTTP/2 connection pooling | 0 | 🔲 TODO |

**Scope Note**: Client-side wallet, registration, and write-proposal classes were removed from `oak-segment-http`.
They now belong in `oak-segment-consensus` or `oak-chain-connector`, leaving this module focused on read-only transport and lazy mount behavior.

---

## oak-blob-cloud-ipfs

### All Classes (Sorted by LOC)

| Class | LOC | Purpose | Tests | Review Status |
|-------|-----|---------|-------|---------------|
| IPFSBackend | 422 | IPFS blob backend | 19 | ✅ DONE |
| IPFSDataStore | 168 | DataStore wrapper | 14 | ✅ DONE |

**Module Status**: ✅ Well-tested (33 tests for 590 LOC)

---

## oak-auth-web3

### All Classes (Sorted by LOC)

| Class | LOC | Purpose | Tests | Review Status |
|-------|-----|---------|-------|---------------|
| Web3BiometricLoginModule | 536 | JAAS login module | 0 | 🔲 TODO |
| PasskeyStore | 482 | Passkey storage | 9 | 🔲 TODO |
| LocalP256Verifier | 413 | P-256 signature verification | 15 | ✅ DONE |
| ChallengeService | 405 | Challenge generation/validation | 28 | ✅ DONE |
| Web3BiometricLoginModuleFactory | 314 | OSGi factory | 0 | 🔲 TODO |
| Web3BiometricCredentials | 248 | Credentials POJO | 12 | ✅ DONE |
| Web3BiometricAuthentication | 204 | Authentication interface | 0 | 🔲 TODO |
| Web3Principal | 152 | Principal implementation | 8 | ✅ DONE |
| Web3BiometricCredentialsSupport | 142 | Credentials support | 0 | 🔲 TODO |
| package-info.java | 106 | Package documentation | - | - |

---

## Review Checklist Template

For each class review, complete the following:

### Dead Code Analysis
- [ ] Unused private methods
- [ ] Unused fields
- [ ] Unreachable code paths
- [ ] Commented-out code
- [ ] Deprecated method usage

### Test Coverage
- [ ] Unit tests exist
- [ ] Edge cases covered
- [ ] Error paths tested
- [ ] Concurrency tested (if applicable)
- [ ] Integration tests exist

### State Machine Documentation
- [ ] All states identified
- [ ] All transitions documented
- [ ] Entry/exit actions documented
- [ ] Error states handled
- [ ] Diagram created (if complex)

### Security Review
- [ ] Input validation
- [ ] Authentication checks
- [ ] Authorization checks
- [ ] Sensitive data handling
- [ ] Error message information leakage

### Code Quality
- [ ] Single responsibility
- [ ] Method length (<50 lines)
- [ ] Cyclomatic complexity
- [ ] Documentation completeness
- [ ] Naming conventions

---

## Priority Order

### Phase 1: Critical Path (Week 1-2)
1. **AeronConsensusEngine** - Core consensus, highest risk
2. **ConsensusApiHandler** - Write path, security critical
3. **GlobalStoreServer** - Server initialization

### Phase 2: Supporting Infrastructure (Week 3-4)
4. **ProposalQueueManagerOptimized** - Queue management
5. **GCProposalManager** - GC operations
6. **SnapshotService** - State persistence
7. **LeaderDiscoveryService** - Leader election

### Phase 3: HTTP Layer (Week 5)
8. **RequestRouter** - Request routing
9. **SegmentHttpServer** - Server setup
10. **HttpPersistenceService** - OSGi integration

### Phase 4: Authentication (Week 6)
11. **Web3BiometricLoginModule** - JAAS integration
12. **Web3BiometricLoginModuleFactory** - OSGi factory
13. **PasskeyStore** - Passkey management

### Phase 5: Remaining Classes (Week 7+)
- All remaining classes with >300 LOC
- Focus on classes with 0 tests

---

## Metrics Summary

| Metric | Value |
|--------|-------|
| Total classes >300 LOC | 40+ |
| Classes with 0 tests | 35+ |
| Estimated review effort | 6-8 weeks |
| Highest risk class | AeronConsensusEngine (4,586 LOC, 0 tests) |

---

---

## Phase 1 Analysis Documents

The following detailed analysis documents were created as part of Phase 1:

| Document | Class | Key Findings |
|----------|-------|--------------|
| [PHASE1-ANALYSIS-AERON-CONSENSUS-ENGINE.md](PHASE1-ANALYSIS-AERON-CONSENSUS-ENGINE.md) | AeronConsensusEngine | God Class (12+ responsibilities), 8 unused fields, 4 state machines |
| [PHASE1-ANALYSIS-CONSENSUS-API-HANDLER.md](PHASE1-ANALYSIS-CONSENSUS-API-HANDLER.md) | ConsensusApiHandler | God Method (650 lines), duplicate validation, 3 state machines |
| [PHASE1-ANALYSIS-GLOBAL-STORE-SERVER.md](PHASE1-ANALYSIS-GLOBAL-STORE-SERVER.md) | GlobalStoreServer | 12 linter warnings, deprecated method, 3 state machines |
| [PHASE1-REFACTORING-RECOMMENDATIONS.md](PHASE1-REFACTORING-RECOMMENDATIONS.md) | All three | Consolidated action plan, extraction patterns, target architecture |

---

*Generated: January 10, 2026*  
*Phase 1 Analysis: ✅ Complete*  
*Next Step: Phase 1A - Immediate Cleanup (remove dead code, fix linter warnings)*
