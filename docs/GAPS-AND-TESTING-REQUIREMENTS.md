# Blockchain AEM - Gaps Analysis & Testing Requirements

**Purpose**: Identify implementation gaps and formalize testing requirements  
**Date**: January 10, 2026  
**Status**: Active Development / POC  
**Related**: [STATE-MACHINE-DIAGRAMS.md](STATE-MACHINE-DIAGRAMS.md)

---

## Table of Contents

1. [Executive Summary](#executive-summary)
2. [Implementation Gaps by Module](#implementation-gaps-by-module)
3. [Current Test Coverage](#current-test-coverage)
4. [Unit Test Requirements](#unit-test-requirements)
5. [Integration Test Requirements](#integration-test-requirements)
6. [Test Infrastructure Needs](#test-infrastructure-needs)
7. [Priority Matrix](#priority-matrix)

---

## Executive Summary

### Overall Status

| Module | Implementation | Unit Tests | Integration Tests | Production Ready |
|--------|---------------|------------|-------------------|------------------|
| **oak-segment-consensus** | 🟡 80% | 🟡 ~40% | 🔴 ~10% | ❌ No |
| **oak-segment-http** | 🟢 90% | 🟡 ~30% | 🟡 ~20% | ❌ No |
| **oak-blob-cloud-ipfs** | 🟡 70% | 🔴 0% | 🔴 0% | ❌ No |
| **oak-auth-web3** | 🟢 85% | 🔴 0% | 🔴 0% | ❌ No |

### Critical Gaps Summary

1. **Signature Verification**: Real ECDSA/secp256k1 verification not implemented (marked TODO)
2. **Aeron Step-Down**: Leader step-down API not integrated
3. **IPFS CID Persistence**: CID mappings only in-memory (lost on restart)
4. **Snapshot Restoration**: Aeron snapshot restore not implemented
5. **Test Coverage**: Most modules lack comprehensive unit tests

---

## Implementation Gaps by Module

### oak-segment-consensus

#### Critical Gaps (Must Fix for Production)

| Gap | Location | Current State | Required State |
|-----|----------|---------------|----------------|
| ~~**Signature Verification**~~ | `EthereumSignatureVerifier.java` | ✅ **Implemented** | Full secp256k1 ECDSA with Bouncy Castle |
| ~~**Leader Step-Down**~~ | `AeronConsensusEngine.java:3493` | ✅ **Implemented** | Session-based step-down with fallback |
| **Snapshot Restoration** | `SnapshotService.java:171` | TODO comment | Implement full snapshot restore |
| **Mainnet Contract** | `BlockchainConfig.java:106` | Zero address | Deploy and configure mainnet contract |
| **Event Subscription** | `EventDrivenEvmBridge.java:306` | TODO comment | Implement Web3j event subscription |

#### State Machine Gaps

```
Write Proposal State Machine:
┌─────────────────────────────────────────────────────────────────────────────┐
│ PENDING → CONFIRMED → VERIFIED → PROCESSED                                  │
│                                                                             │
│ GAP: PENDING → CONFIRMED transition requires real Ethereum TX monitoring    │
│      Currently: Mock mode skips directly to PROCESSED                       │
│      Missing: Web3j transaction receipt polling                             │
│                                                                             │
│ GAP: CONFIRMED → VERIFIED requires on-chain verification                    │
│      Currently: Always passes in mock mode                                  │
│      Missing: Smart contract event verification                             │
└─────────────────────────────────────────────────────────────────────────────┘

GC Proposal State Machine:
┌─────────────────────────────────────────────────────────────────────────────┐
│ PENDING → VOTING → APPROVED → EXECUTING → COMPLETED                         │
│                                                                             │
│ ✅ RESOLVED: Aeron replication of GC proposals                              │
│      Location: AeronConsensusEngine.java (sendGCProposalThroughIngress)     │
│      Implementation: GC proposals, votes, and execute commands replicated   │
│      via Aeron cluster using template IDs 103, 104, 105                     │
│                                                                             │
│ GAP: Payment verification for GC                                            │
│      Currently: Optional payment proof                                      │
│      Missing: Enforce USDC payment for GC operations                        │
└─────────────────────────────────────────────────────────────────────────────┘

Leadership Claim State Machine:
┌─────────────────────────────────────────────────────────────────────────────┐
│ PENDING → ACCEPTED / REJECTED / SUPERSEDED                                  │
│                                                                             │
│ GAP: Aeron Cluster doesn't expose leaderMemberId() directly                 │
│      Location: LeaderDiscoveryService.java:177                              │
│      Workaround: Query peers via HTTP                                       │
│      Ideal: Native Aeron API access                                         │
└─────────────────────────────────────────────────────────────────────────────┘
```

#### Medium Priority Gaps

| Gap | Location | Impact |
|-----|----------|--------|
| Content size estimation | `ConsensusApiHandler.java:850` | Inaccurate storage cost calculation |
| Genesis hash verification | `SegmentHttpServer.java:187` | POC placeholder, not cryptographic |
| Wallet-based registration | `SegmentHttpServer.java:622` | IP-based fallback still in use |
| Retry count tracking | `ProposalQueueManagerOptimized.java:691` | No retry metadata |
| Segment reference parsing | `GlobalStoreServer.java:2417` | Incomplete segment graph traversal |

---

### oak-segment-http

#### Implementation Status: 🟢 90% Complete

This module is relatively complete with minimal gaps.

#### Gaps Identified

| Gap | Location | Impact |
|-----|----------|--------|
| None critical | - | Module is well-implemented |

#### State Machine Completeness

```
HTTP Persistence Service State Machine:
┌─────────────────────────────────────────────────────────────────────────────┐
│ ACTIVATE → [HEALTH_CHECK] → REGISTER → ACTIVE → SHUTDOWN                    │
│                                                                             │
│ STATUS: ✅ Complete - All transitions implemented                           │
│ - Lazy mount mode works correctly                                           │
│ - Health check thread properly managed                                      │
│ - Service registration/unregistration handled                               │
└─────────────────────────────────────────────────────────────────────────────┘

Write Proposal Flow:
┌─────────────────────────────────────────────────────────────────────────────┐
│ CHECK_WALLET → CREATE_TX → SIGN → SUBMIT → AWAIT_RESPONSE                   │
│                                                                             │
│ STATUS: ✅ Complete - All transitions implemented                           │
│ NOTE: Depends on validator's signature verification (gap in consensus)      │
└─────────────────────────────────────────────────────────────────────────────┘

Delete Proposal Flow:
┌─────────────────────────────────────────────────────────────────────────────┐
│ CHECK_WALLET → VERIFY_OWNERSHIP → CREATE_TX → SIGN → SUBMIT                 │
│                                                                             │
│ STATUS: ✅ Complete - Ownership verification implemented                    │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

### oak-blob-cloud-ipfs

#### Architecture Clarification (January 2026)

**Key Insight**: The original gaps assumed validator-side IPFS upload. The actual architecture
uses **client-side IPFS upload** where the CID flows through the proposal:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│  CLIENT-SIDE IPFS UPLOAD (Correct Architecture)                             │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  Browser/Client                                                             │
│  ├── User uploads binary to IPFS (js-ipfs / HTTP gateway)                   │
│  ├── Gets CID: "QmXyz..."                                                   │
│  └── Submits write proposal with ipfsCid parameter                          │
│                                                                             │
│  Sling Author (NO IPFS BlobStore needed)                                    │
│  ├── Receives proposal with ipfsCid                                         │
│  ├── Signs and forwards to validator                                        │
│  └── Reads ipfs:cid property for rendering                                  │
│                                                                             │
│  Validator                                                                  │
│  ├── Receives proposal with ipfsCid FROM CLIENT                             │
│  ├── Stores ipfs:cid property on content node                               │
│  ├── Optionally: Pins CID to local IPFS for redundancy                      │
│  └── IPFSBackend.cidCache NOT needed for this flow                          │
│                                                                             │
│  EDS Layer / Readers                                                        │
│  └── Read ipfs:cid property, fetch via IPFS gateway                         │
└─────────────────────────────────────────────────────────────────────────────┘
```

#### Revised Gap Analysis

| Original Gap | Status | Resolution |
|--------------|--------|------------|
| ~~CID Persistence~~ | ✅ **Not a gap** | CID stored as `ipfs:cid` property on content node |
| ~~CID Recovery~~ | ✅ **Not a gap** | CID comes from client proposal, not derived |
| **Accept ipfsCid param** | 🔲 **NEW** | `ConsensusApiHandler` needs to accept `ipfsCid` in proposal |
| IPFS Cluster | 🟡 Future | Nice-to-have for validator redundancy |
| S3 Fallback | 🟡 Future | Nice-to-have for hybrid storage |

#### Remaining Work

| Gap | Location | Current State | Required State |
|-----|----------|---------------|----------------|
| **Accept ipfsCid** | `ConsensusApiHandler.java:126` | TODO comment | Parse and store from proposal |
| **Simplify cidCache** | `IPFSBackend.java:68` | In-memory HashMap | Can be removed or made optional |

#### State Machine (Revised)

```
Binary Lifecycle State Machine (Client-Side Upload):
┌─────────────────────────────────────────────────────────────────────────────┐
│                                                                             │
│  CLIENT: UPLOAD → GET_CID → SUBMIT_PROPOSAL                                 │
│                      │                                                      │
│                      ▼                                                      │
│  VALIDATOR: RECEIVE → VERIFY_PAYMENT → STORE_NODE → REPLICATE               │
│                                            │                                │
│                                            ▼                                │
│  CONTENT NODE:  ipfs:cid = "QmXyz..."  (persisted in Oak, replicated)       │
│                                                                             │
│  READER: READ_NODE → GET_CID_PROPERTY → FETCH_FROM_GATEWAY                  │
│                                                                             │
│  STATUS: ✅ Architecture is sound                                           │
│  REMAINING: Accept ipfsCid parameter in proposal API                        │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

### oak-auth-web3

#### Implementation Status: 🟢 85% Complete

Core JAAS integration is complete; needs testing and OSGi deployment.

#### Gaps Identified

| Gap | Location | Current State | Required State |
|-----|----------|---------------|----------------|
| **Unit Tests** | `src/test/` | Empty directory | Comprehensive test suite |
| **OSGi Factory** | N/A | Basic factory exists | Full ConfigAdmin integration |
| **Challenge Service** | N/A | Not implemented | Server-side challenge generation |
| **Passkey Storage** | N/A | Not implemented | Store registered passkeys in Oak |

#### State Machine Completeness

```
JAAS Login Module State Machine:
┌─────────────────────────────────────────────────────────────────────────────┐
│ INITIALIZE → READY → LOGIN → COMMIT → ACTIVE → LOGOUT                       │
│                                                                             │
│ STATUS: ✅ Complete - All JAAS phases implemented                           │
│ - login(): Signature verification works                                     │
│ - commit(): Principal creation and user auto-creation works                 │
│ - abort(): Cleanup implemented                                              │
│ - logout(): Principal removal implemented                                   │
│                                                                             │
│ GAP: Challenge freshness validation                                         │
│      Currently: Trusts client-provided challenge                            │
│      Required: Server-side challenge generation and validation              │
└─────────────────────────────────────────────────────────────────────────────┘

Biometric Authentication Flow:
┌─────────────────────────────────────────────────────────────────────────────┐
│ BROWSER → SERVLET → CREDENTIALS → JAAS → SESSION                            │
│                                                                             │
│ STATUS: 🟡 Mostly Complete                                                  │
│ - WebAuthn integration: Client-side only (no server challenge)              │
│ - MetaMask integration: ✅ Complete with pre-verification                   │
│ - P-256 verification: ✅ Local JVM crypto works                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Current Test Coverage

### oak-segment-consensus

| Test Class | Tests | Coverage Area |
|------------|-------|---------------|
| `GCCostEstimatorTest` | 10 | GC cost calculation |
| `GCCostEstimateTest` | 7 | GC estimate data structure |
| `EvmBridgeTest` | 12 | EVM payment verification |
| `EventDrivenEvmBridgeTest` | 4 | Event-driven bridge |
| ~~`SegmentGossipTest`~~ | ~~9~~ | ~~P2P segment gossip~~ (deleted - P2P package removed) |
| `ProposalQueueIntegrationTest` | 6 | **ProposalQueueManagerOptimized** (migrated Jan 2026) |
| `CompositeStoreTest` | 9 | Composite store |
| **Total** | **~48** | |

**Recent Improvements (January 2026):**
- `ProposalQueueIntegrationTest` migrated to use production `ProposalQueueManagerOptimized`
- Tests now cover: PRIORITY tier fast-path, STANDARD tier epoch batching, DELETE proposals, queue stats

**Missing Test Coverage:**
- `AeronConsensusEngine` - 0 tests (4400+ lines of code!)
- `GlobalStoreServer` - 0 tests
- `LeadershipClaimTracker` - 0 tests
- `GCProposalManager` - 0 tests
- `ConsensusApiHandler` - 0 tests
- `ProposalState` transitions - 0 tests
- `ValidatorRole` transitions - 0 tests

### oak-segment-http

| Test Class | Tests | Coverage Area |
|------------|-------|---------------|
| `SlingWriteProposalServiceTest` | 5 | Write proposal client |
| `EndToEndWriteFlowTest` | 3 | E2E write flow |
| **Total** | **~8** | |

**Missing Test Coverage:**
- `HttpPersistence` - 0 tests
- `HttpPersistenceService` - 0 tests
- `HttpSegmentArchiveReader` - 0 tests
- `SlingDeleteProposalService` - 0 tests
- `SlingAuthorWalletService` - 0 tests

### oak-blob-cloud-ipfs

| Test Class | Tests | Coverage Area |
|------------|-------|---------------|
| **None** | **0** | |

**Missing Test Coverage:**
- `IPFSDataStore` - 0 tests
- `IPFSBackend` - 0 tests
- Binary lifecycle - 0 tests

### oak-auth-web3

| Test Class | Tests | Coverage Area |
|------------|-------|---------------|
| **None** | **0** | |

**Missing Test Coverage:**
- `Web3BiometricLoginModule` - 0 tests
- `LocalP256Verifier` - 0 tests
- `Web3BiometricCredentials` - 0 tests
- `Web3Principal` - 0 tests

---

## Unit Test Requirements

### Priority 1: Critical Path Tests

#### oak-segment-consensus

```java
// AeronConsensusEngineTest.java
public class AeronConsensusEngineTest {
    
    // State Machine Tests
    @Test void testRoleTransitionFollowerToLeader();
    @Test void testRoleTransitionLeaderToFollower();
    @Test void testRoleTransitionOnHigherTerm();
    
    // Write Processing Tests
    @Test void testApplyReplicatedWrite();
    @Test void testApplyReplicatedDelete();
    @Test void testWriteWithInvalidSignature();
    @Test void testWriteToUnauthorizedPath();
    
    // Snapshot Tests
    @Test void testSnapshotCreation();
    @Test void testSnapshotRestoration();
    
    // Leader Discovery Tests
    @Test void testDiscoverLeaderFromCache();
    @Test void testDiscoverLeaderFromPeers();
}

// ProposalStateTest.java
public class ProposalStateTest {
    @Test void testPendingToConfirmed();
    @Test void testConfirmedToVerified();
    @Test void testVerifiedToProcessed();
    @Test void testPendingToRejectedOnTimeout();
    @Test void testStateTransitionValidation();
}

// GCProposalStateTest.java
public class GCProposalStateTest {
    @Test void testPendingToVoting();
    @Test void testVotingToApproved();
    @Test void testVotingToRejected();
    @Test void testApprovedToExecuting();
    @Test void testExecutingToCompleted();
    @Test void testExecutingToFailed();
    @Test void testQuorumCalculation();
}

// LeadershipClaimTrackerTest.java
public class LeadershipClaimTrackerTest {
    @Test void testPendingToAccepted();
    @Test void testPendingToRejected();
    @Test void testPendingToSuperseded();
    @Test void testQuorumReached();
    @Test void testDuplicateAckIgnored();
}
```

#### oak-blob-cloud-ipfs

```java
// IPFSBackendTest.java
public class IPFSBackendTest {
    
    // Lifecycle Tests
    @Test void testInitializeWithValidEndpoint();
    @Test void testInitializeWithInvalidEndpoint();
    @Test void testClose();
    
    // Write Tests
    @Test void testWriteFile();
    @Test void testWriteCreatesPin();
    @Test void testWriteCachesCID();
    
    // Read Tests
    @Test void testReadFromCache();
    @Test void testReadMissingCID();
    
    // Delete Tests
    @Test void testDeleteUnpins();
    @Test void testDeleteRemovesFromCache();
    
    // Metadata Tests
    @Test void testAddMetadataRecord();
    @Test void testGetMetadataRecord();
    @Test void testDeleteMetadataRecord();
}

// IPFSDataStoreTest.java
public class IPFSDataStoreTest {
    @Test void testMinRecordLength();
    @Test void testGetCID();
    @Test void testGetAllCIDMappings();
}
```

#### oak-auth-web3

```java
// Web3BiometricLoginModuleTest.java
public class Web3BiometricLoginModuleTest {
    
    // JAAS Phase Tests
    @Test void testLoginWithValidBiometricCredentials();
    @Test void testLoginWithInvalidSignature();
    @Test void testLoginWithNonBiometricCredentials();
    @Test void testCommitAddsPrincipalToSubject();
    @Test void testCommitCreatesUserIfNotExists();
    @Test void testAbortClearsState();
    @Test void testLogoutRemovesPrincipals();
    
    // MetaMask Tests
    @Test void testLoginWithPreVerifiedMetaMask();
    @Test void testMetaMaskWalletAddressExtraction();
}

// LocalP256VerifierTest.java
public class LocalP256VerifierTest {
    @Test void testVerifyValidSignature();
    @Test void testVerifyInvalidSignature();
    @Test void testVerifyWithMalformedPublicKey();
    @Test void testVerifyWithMalformedSignature();
}

// Web3PrincipalTest.java
public class Web3PrincipalTest {
    @Test void testGetName();
    @Test void testGetWalletAddress();
    @Test void testEquals();
    @Test void testHashCode();
}
```

### Priority 2: Supporting Tests

#### oak-segment-http

```java
// HttpPersistenceServiceTest.java
public class HttpPersistenceServiceTest {
    @Test void testActivateImmediateMode();
    @Test void testActivateLazyMode();
    @Test void testHealthCheckSuccess();
    @Test void testHealthCheckFailure();
    @Test void testServiceRegistration();
    @Test void testDeactivate();
}

// SlingDeleteProposalServiceTest.java
public class SlingDeleteProposalServiceTest {
    @Test void testProposeDeleteSuccess();
    @Test void testProposeDeleteOwnershipViolation();
    @Test void testProposeDeleteWalletUnavailable();
}
```

---

## Integration Test Requirements

### End-to-End Test Scenarios

#### Scenario 1: Write Flow (Single Validator)

```java
@IntegrationTest
public class SingleValidatorWriteFlowTest {
    
    @Test
    void testWriteFlowEndToEnd() {
        // 1. Start single validator
        // 2. Create Sling author with wallet
        // 3. Submit write proposal
        // 4. Verify content appears in /oak-chain
        // 5. Verify segment created
    }
    
    @Test
    void testWriteWithBinaryEndToEnd() {
        // 1. Start validator with IPFS
        // 2. Upload binary via write proposal
        // 3. Verify binary stored in IPFS
        // 4. Verify CID reference in Oak
        // 5. Retrieve binary and verify content
    }
}
```

#### Scenario 2: Consensus (Multi-Validator)

```java
@IntegrationTest
public class MultiValidatorConsensusTest {
    
    @Test
    void testThreeValidatorConsensus() {
        // 1. Start 3 validators
        // 2. Wait for leader election
        // 3. Submit write to any validator
        // 4. Verify all validators have same HEAD
        // 5. Verify all validators have same content
    }
    
    @Test
    void testLeaderFailover() {
        // 1. Start 3 validators
        // 2. Identify leader
        // 3. Stop leader
        // 4. Wait for new leader election
        // 5. Submit write to new leader
        // 6. Verify consensus maintained
    }
    
    @Test
    void testNetworkPartitionRecovery() {
        // 1. Start 3 validators
        // 2. Partition one validator
        // 3. Submit writes to majority
        // 4. Heal partition
        // 5. Verify partitioned validator catches up
    }
}
```

#### Scenario 3: GC Consensus

```java
@IntegrationTest
public class GCConsensusTest {
    
    @Test
    void testGCProposalApproval() {
        // 1. Start 3 validators
        // 2. Create content, then delete
        // 3. Propose GC
        // 4. Verify voting process
        // 5. Verify GC execution on all validators
    }
    
    @Test
    void testGCProposalRejection() {
        // 1. Start 3 validators
        // 2. Propose GC with insufficient payment
        // 3. Verify rejection
    }
}
```

#### Scenario 4: Authentication

```java
@IntegrationTest
public class AuthenticationFlowTest {
    
    @Test
    void testBiometricAuthenticationEndToEnd() {
        // 1. Start Sling with oak-auth-web3
        // 2. Generate WebAuthn assertion (mock)
        // 3. Login with biometric credentials
        // 4. Verify session created
        // 5. Verify wallet address as principal
    }
    
    @Test
    void testMetaMaskAuthenticationEndToEnd() {
        // 1. Start Sling with oak-auth-web3
        // 2. Generate MetaMask signature (mock)
        // 3. Login with pre-verified credentials
        // 4. Verify session created
        // 5. Verify user auto-created
    }
}
```

### Docker-Based Integration Tests

```yaml
# docker-compose.test.yml
version: '3.8'
services:
  validator-0:
    image: oak-global-store:test
    environment:
      - AERON_NODE_ID=0
      - AERON_CLUSTER_MEMBERS=0=validator-0:20110,1=validator-1:20111,2=validator-2:20112
    
  validator-1:
    image: oak-global-store:test
    environment:
      - AERON_NODE_ID=1
      - AERON_CLUSTER_MEMBERS=0=validator-0:20110,1=validator-1:20111,2=validator-2:20112
    
  validator-2:
    image: oak-global-store:test
    environment:
      - AERON_NODE_ID=2
      - AERON_CLUSTER_MEMBERS=0=validator-0:20110,1=validator-1:20111,2=validator-2:20112
    
  ipfs:
    image: ipfs/kubo:latest
    
  sling-author:
    image: sling-starter:test
    environment:
      - OAK_GLOBAL_STORE_URL=http://validator-0:8090
    depends_on:
      - validator-0
```

---

## Test Infrastructure Needs

### Required Test Dependencies

```xml
<!-- oak-segment-consensus/pom.xml -->
<dependencies>
    <!-- Test dependencies -->
    <dependency>
        <groupId>junit</groupId>
        <artifactId>junit</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.mockito</groupId>
        <artifactId>mockito-core</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>testcontainers</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.awaitility</groupId>
        <artifactId>awaitility</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>
```

### Mock Services Needed

| Service | Purpose | Implementation |
|---------|---------|----------------|
| MockIPFSNode | Test IPFS operations without real node | HTTP server returning mock CIDs |
| MockEthereumNode | Test blockchain operations | In-memory transaction simulation |
| MockAeronCluster | Test consensus without full cluster | Single-node mock cluster |
| MockWebAuthn | Test biometric auth | Pre-generated test vectors |

### CI/CD Integration

```yaml
# .github/workflows/test.yml
name: Test Suite
on: [push, pull_request]

jobs:
  unit-tests:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - name: Unit Tests
        run: mvn test -pl oak-segment-consensus,oak-segment-http,oak-blob-cloud-ipfs,oak-auth-web3
        
  integration-tests:
    runs-on: ubuntu-latest
    services:
      ipfs:
        image: ipfs/kubo:latest
    steps:
      - uses: actions/checkout@v3
      - name: Integration Tests
        run: mvn verify -Pintegration-tests
```

---

## Priority Matrix

### Immediate (Before Demo)

| Item | Module | Effort | Impact |
|------|--------|--------|--------|
| ~~Signature verification~~ | ~~consensus~~ | ~~Medium~~ | ✅ **Implemented** - EthereumSignatureVerifier |
| ~~IPFS CID persistence~~ | ~~ipfs~~ | ~~Medium~~ | ✅ **Resolved** - CID from client proposal |
| ~~Accept ipfsCid in proposal API~~ | ~~consensus~~ | ~~Low~~ | ✅ **Implemented** - Full flow |
| Basic unit tests for state machines | all | High | High |

### Short-Term (Q1 2026)

| Item | Module | Effort | Impact |
|------|--------|--------|--------|
| ~~Aeron step-down API~~ | ~~consensus~~ | ~~Medium~~ | ✅ **Implemented** - Session-based step-down |
| ~~GC Aeron replication~~ | ~~consensus~~ | ~~Medium~~ | ✅ **Implemented** - Full GC flow via Aeron |
| Snapshot restoration | consensus | High | High |
| Challenge service | auth-web3 | Medium | Medium |
| Integration test suite | all | High | High |

### Medium-Term (Q2 2026)

| Item | Module | Effort | Impact |
|------|--------|--------|--------|
| Event subscription | consensus | High | Medium |
| IPFS Cluster support | ipfs | High | Medium |
| S3 fallback | ipfs | Medium | Medium |
| Full test coverage (>80%) | all | Very High | High |

### Long-Term (Q3+ 2026)

| Item | Module | Effort | Impact |
|------|--------|--------|--------|
| Mainnet contract deployment | consensus | Medium | Critical |
| Production hardening | all | Very High | Critical |
| Performance benchmarks | all | High | Medium |

---

## Appendix: TODO Comments in Codebase

### oak-segment-consensus (~20 TODOs remaining)

```
~~ConsensusApiHandler.java:127    - ADR 016: ipfsCid handling~~ ✅ DONE
~~ConsensusApiHandler.java:416-417 - Real signature verification~~ ✅ DONE (EthereumSignatureVerifier)
ConsensusApiHandler.java:850    - Actual content size from NodeStore
FragmentationApiHandler.java:356 - Aeron replication for GC
BinaryUploadHandler.java:235    - Validate CID reachability
SegmentHttpServer.java:187      - Actual genesis hash
SegmentHttpServer.java:192      - Sign nonce for production
SegmentHttpServer.java:622      - Wallet-based registration
GlobalStoreServer.java:1389     - Smart contract event listener
GlobalStoreServer.java:2417     - Parse segment references
ProofVerifier.java:186          - Verify against actual genesis
ProofVerifier.java:227          - Actual segment loading
ProofVerifier.java:258          - Actual signature verification
ProposalQueueManagerOptimized.java:691 - Retry count tracking
EventDrivenEvmBridge.java:302-306 - Web3j event subscription
BlockchainConfig.java:106       - Deploy mainnet contract
SnapshotService.java:147        - Get epoch from tracker
SnapshotService.java:171        - Implement snapshot restoration
LeaderDiscoveryService.java:177 - Aeron API for leaderMemberId
AeronConsensusEngine.java:3609  - ClusterControl step-down
DashboardHandler.java:1125      - Full signature verification
DashboardHandler.java:1155      - Aeron replication for GC
```

### oak-segment-http (0 TODOs)

No TODO comments found - module is well-implemented.

### oak-blob-cloud-ipfs (0 TODOs)

No TODO comments found - but has implicit gaps (CID persistence).

### oak-auth-web3 (0 TODOs)

No TODO comments found - but needs tests and challenge service.

---

## Related Documentation

- **[STATE-MACHINE-DIAGRAMS.md](STATE-MACHINE-DIAGRAMS.md)** - State machine documentation for all components
- **[TECHNICAL-DEBT-ANALYSIS.md](TECHNICAL-DEBT-ANALYSIS.md)** - Dead code and deprecation analysis

---

*Generated: January 10, 2026*  
*Next Review: After unit test implementation*
