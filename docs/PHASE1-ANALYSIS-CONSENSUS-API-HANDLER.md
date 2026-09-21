# Phase 1 Analysis: ConsensusApiHandler.java

**Date**: January 10, 2026  
**Status**: Analysis Complete  
**File**: `oak-segment-consensus/src/main/java/org/apache/jackrabbit/oak/segment/http/server/handlers/ConsensusApiHandler.java`  
**Lines of Code**: 2,293  
**Unit Tests**: 0

---

## Table of Contents

1. [Executive Summary](#executive-summary)
2. [Class Responsibilities Analysis](#class-responsibilities-analysis)
3. [Technical Debt Identified](#technical-debt-identified)
4. [Refactoring Recommendations](#refactoring-recommendations)
5. [State Machine Documentation](#state-machine-documentation)
6. [Missing Tests](#missing-tests)
7. [Documentation Gaps](#documentation-gaps)
8. [Action Items](#action-items)

---

## Executive Summary

### Overall Assessment: 🟡 MODERATE TECHNICAL DEBT

`ConsensusApiHandler` is a large handler class with 2,293 lines handling **8 distinct responsibilities**. While it has good JavaDoc on public methods, the class is too large and mixes HTTP handling with business logic.

### Key Metrics

| Metric | Value | Target | Status |
|--------|-------|--------|--------|
| Lines of Code | 2,293 | <500 | 🔴 4.5x over |
| Public Methods | 12 | <10 | 🟡 |
| Private Methods | 9 | <10 | 🟢 |
| Unit Tests | 0 | >30 | 🔴 Critical |
| Cyclomatic Complexity | Medium-High | Low | 🟡 |

### Positive Observations

1. ✅ Good JavaDoc on public handler methods
2. ✅ Clear separation of HTTP handlers
3. ✅ Proper validation of wallet addresses
4. ✅ Good error messages with actionable guidance
5. ✅ Security-conscious (signature validation, path ownership)

---

## Class Responsibilities Analysis

The class currently handles **8 distinct responsibilities**:

### 1. Write Proposal Handling (HTTP Layer)
- `handleProposeWrite()` - 650 lines!
- Multipart form parsing, validation, queuing
- **Lines**: ~650
- **Recommendation**: Extract to `WriteProposalHandler` + `WriteProposalValidator`

### 2. Delete Proposal Handling (HTTP Layer)
- `handleDeleteProposal()` - 230 lines
- Validation, ownership check, queuing
- **Lines**: ~230
- **Recommendation**: Extract to `DeleteProposalHandler`

### 3. Write Application (Business Logic)
- `applyReplicatedWrite()` - 270 lines
- Oak NodeStore operations, binary handling
- **Lines**: ~270
- **Recommendation**: Extract to `WriteApplicationService`

### 4. Delete Application (Business Logic)
- `applyReplicatedDelete()` - 90 lines
- Oak NodeStore delete operations
- **Lines**: ~90
- **Recommendation**: Extract to `DeleteApplicationService`

### 5. Consensus Status Queries
- `handleGetConsensusStatus()`, `handleGetProposalStatus()`, `handleGetPendingCount()`
- **Lines**: ~150
- **Recommendation**: Extract to `ConsensusStatusHandler`

### 6. GC Cost Estimation
- `handleGCCostEstimate()` - 75 lines
- **Lines**: ~75
- **Recommendation**: Keep or move to `FragmentationApiHandler`

### 7. Wallet Statistics & Content
- `handleWalletStats()`, `handleWalletContent()`, `queryWalletNode()`, `queryTopWallets()`, `queryWalletContent()`
- **Lines**: ~250
- **Recommendation**: Extract to `WalletQueryHandler`

### 8. Utility Methods
- `enrichWalletNode()`, `buildGenesisStructure()`, `extractOrganizationFromPath()`, `trackFragmentation()`, `estimateContentSizeMB()`, `discoverLeaderFromPeerClusterState()`, `resolveUrlToIP()`, `getNextValidatorInRotation()`
- **Lines**: ~300
- **Recommendation**: Extract to appropriate service classes

---

## Technical Debt Identified

### 1. 🔴 God Method: handleProposeWrite() (Critical)

**Problem**: Single method with 650 lines handling:
- Multipart parsing
- Parameter extraction
- Wallet validation
- Organization validation
- Client registration lookup
- Signature verification
- Binary upload handling
- Queue submission
- Response generation

**Impact**: 
- Impossible to unit test individual concerns
- High cognitive load
- Difficult to maintain

**Solution**: Extract into:
1. `MultipartParser` - Form parsing
2. `WriteProposalValidator` - Validation logic
3. `WriteProposalHandler` - Orchestration

---

### 2. 🟡 Mixed Concerns (HTTP + Business Logic)

**Problem**: Class mixes HTTP request handling with Oak NodeStore operations.

**Example**:
```java
// HTTP handling
public void handleProposeWrite(HttpServletRequest request, HttpServletResponse response) {
    // ... 650 lines mixing HTTP parsing with business logic
}

// Business logic
public void applyReplicatedWrite(String walletAddress, String path, ...) {
    // ... Oak NodeStore operations
}
```

**Solution**: Separate into:
- `ConsensusApiHandler` - HTTP layer only
- `WriteApplicationService` - Business logic
- `DeleteApplicationService` - Business logic

---

### 3. 🟡 Manual JSON Building

**Location**: Multiple methods

```java
String json = "{" +
    "\"proposalId\":\"" + status.getProposalId() + "\"," +
    "\"state\":\"" + status.getState().name() + "\"," +
    // ... more string concatenation
    "}";
```

**Problem**: Error-prone, no escaping, hard to maintain.

**Solution**: Use Jackson or Gson for JSON serialization.

---

### 4. 🟡 Duplicate Wallet Validation

**Problem**: Wallet validation logic duplicated in `handleProposeWrite()` and `handleDeleteProposal()`.

```java
// In handleProposeWrite() - lines 160-187
if (wallet == null || wallet.isEmpty()) { ... }
if (!wallet.startsWith("0x") || wallet.length() < 10) { ... }
if (!walletHex.matches("[a-fA-F0-9]+")) { ... }

// In handleDeleteProposal() - lines 745-765
if (wallet == null || wallet.isEmpty()) { ... }
if (!wallet.startsWith("0x") || wallet.length() < 10) { ... }
```

**Solution**: Extract to `WalletValidator.validate(wallet)`.

---

### 5. 🟡 Long Parameter Lists

**Problem**: Methods with many parameters.

```java
public void applyReplicatedWrite(
    String walletAddress, 
    String path, 
    String contentType, 
    String message, 
    String signature, 
    String intentToken, 
    String blobId, 
    String mimeType, 
    String ipfsCid  // 9 parameters!
)
```

**Solution**: Use a `WriteProposal` value object.

---

### 6. 🟡 Magic Strings

**Problem**: Hardcoded strings throughout.

```java
contentNode.setProperty("jcr:primaryType", "nt:unstructured");
contentNode.setProperty("source", "aeron-replicated");
```

**Solution**: Extract to constants or enum.

---

## Refactoring Recommendations

### Proposed Architecture

```
ConsensusApiHandler (HTTP Layer - ~400 lines)
├── handleProposeWrite() - delegates to WriteProposalHandler
├── handleDeleteProposal() - delegates to DeleteProposalHandler
├── handleGetConsensusStatus()
├── handleGetProposalStatus()
├── handleGetPendingCount()
└── handleGCCostEstimate()

WriteProposalHandler (new - ~200 lines)
├── parse(request) → WriteProposal
├── validate(WriteProposal)
└── submit(WriteProposal)

DeleteProposalHandler (new - ~100 lines)
├── parse(request) → DeleteProposal
├── validate(DeleteProposal)
└── submit(DeleteProposal)

WriteApplicationService (new - ~300 lines)
├── applyReplicatedWrite(WriteProposal)
├── enrichWalletNode()
└── buildGenesisStructure()

DeleteApplicationService (new - ~100 lines)
└── applyReplicatedDelete(DeleteProposal)

WalletQueryHandler (new - ~200 lines)
├── handleWalletStats()
├── handleWalletContent()
├── queryWalletNode()
├── queryTopWallets()
└── queryWalletContent()

WalletValidator (new - ~50 lines)
└── validate(wallet) → ValidationResult
```

### Extraction Priority

| Priority | Component | Effort | Impact |
|----------|-----------|--------|--------|
| 1 | WalletValidator | Low | High - removes duplication |
| 2 | WriteProposal value object | Low | High - cleaner APIs |
| 3 | WriteApplicationService | Medium | High - enables testing |
| 4 | DeleteApplicationService | Low | Medium |
| 5 | WalletQueryHandler | Medium | Medium |
| 6 | WriteProposalHandler | Medium | Medium |

---

## State Machine Documentation

### 1. Write Proposal Flow

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                      WRITE PROPOSAL FLOW                                    │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  HTTP Request                                                               │
│    │                                                                        │
│    │ POST /v1/propose-write                                                 │
│    ▼                                                                        │
│  ┌──────────────────┐                                                       │
│  │ PARSE_REQUEST    │ Extract wallet, signature, message, etc.              │
│  └────────┬─────────┘                                                       │
│           │                                                                 │
│           │ Multipart or URL-encoded                                        │
│           ▼                                                                 │
│  ┌──────────────────┐                                                       │
│  │ VALIDATE_WALLET  │ Check 0x format, hex characters                       │
│  └────────┬─────────┘                                                       │
│           │                                                                 │
│           │ Valid wallet                                                    │
│           ▼                                                                 │
│  ┌──────────────────┐                                                       │
│  │ VALIDATE_ORG     │ Check organization format (ADR 037)                   │
│  └────────┬─────────┘                                                       │
│           │                                                                 │
│           │ Valid or null                                                   │
│           ▼                                                                 │
│  ┌──────────────────┐                                                       │
│  │ LOOKUP_CLIENT    │ Find registered client by wallet                      │
│  └────────┬─────────┘                                                       │
│           │                                                                 │
│           │ Client found                                                    │
│           ▼                                                                 │
│  ┌──────────────────┐                                                       │
│  │ VERIFY_OWNERSHIP │ Check wallet matches registration                     │
│  └────────┬─────────┘                                                       │
│           │                                                                 │
│           │ Ownership verified                                              │
│           ▼                                                                 │
│  ┌──────────────────┐                                                       │
│  │ BUILD_PATH       │ Construct /oak-chain/{shard}/... path                 │
│  └────────┬─────────┘                                                       │
│           │                                                                 │
│           │ Path built                                                      │
│           ▼                                                                 │
│  ┌──────────────────┐                                                       │
│  │ HANDLE_BINARY    │ Upload to BlobStore if binary present                 │
│  └────────┬─────────┘                                                       │
│           │                                                                 │
│           │ Binary handled (or none)                                        │
│           ▼                                                                 │
│  ┌──────────────────┐                                                       │
│  │ QUEUE_PROPOSAL   │ Add to ProposalQueueManager                           │
│  └────────┬─────────┘                                                       │
│           │                                                                 │
│           │ Queued successfully                                             │
│           ▼                                                                 │
│  ┌──────────────────┐                                                       │
│  │ SEND_RESPONSE    │ Return proposalId, status, path                       │
│  └──────────────────┘                                                       │
│                                                                             │
│  ERROR STATES:                                                              │
│  - INVALID_WALLET: Missing or malformed wallet address                      │
│  - INVALID_ORG: Invalid organization format                                 │
│  - NOT_REGISTERED: Wallet not registered                                    │
│  - OWNERSHIP_MISMATCH: Wallet doesn't match registration                    │
│  - QUEUE_FULL: Backpressure limit reached                                   │
│  - BINARY_UPLOAD_FAILED: BlobStore error                                    │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 2. Delete Proposal Flow

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                      DELETE PROPOSAL FLOW                                   │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  HTTP Request                                                               │
│    │                                                                        │
│    │ POST /v1/propose-delete                                                │
│    ▼                                                                        │
│  ┌──────────────────┐                                                       │
│  │ PARSE_REQUEST    │ Extract wallet, signature, contentPath                │
│  └────────┬─────────┘                                                       │
│           │                                                                 │
│           ▼                                                                 │
│  ┌──────────────────┐                                                       │
│  │ VALIDATE_WALLET  │ Check 0x format                                       │
│  └────────┬─────────┘                                                       │
│           │                                                                 │
│           ▼                                                                 │
│  ┌──────────────────┐                                                       │
│  │ LOOKUP_CLIENT    │ Find registered client by wallet                      │
│  └────────┬─────────┘                                                       │
│           │                                                                 │
│           ▼                                                                 │
│  ┌──────────────────┐                                                       │
│  │ VERIFY_PATH_OWNER│ Check contentPath under wallet's shard                │
│  └────────┬─────────┘                                                       │
│           │                                                                 │
│           │ Path ownership verified                                         │
│           ▼                                                                 │
│  ┌──────────────────┐                                                       │
│  │ SEND_TO_AERON    │ sendDeleteThroughIngress()                            │
│  └────────┬─────────┘                                                       │
│           │                                                                 │
│           ▼                                                                 │
│  ┌──────────────────┐                                                       │
│  │ SEND_RESPONSE    │ Return success/failure                                │
│  └──────────────────┘                                                       │
│                                                                             │
│  ERROR STATES:                                                              │
│  - NOT_REGISTERED: Wallet not registered                                    │
│  - PATH_OWNERSHIP_VIOLATION: Path not under wallet's shard                  │
│  - AERON_UNAVAILABLE: Consensus engine not ready                            │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 3. Apply Replicated Write Flow

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    APPLY REPLICATED WRITE FLOW                              │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  Aeron Callback                                                             │
│    │                                                                        │
│    │ onSessionMessage() → applyReplicatedWrite()                            │
│    ▼                                                                        │
│  ┌──────────────────┐                                                       │
│  │ PARSE_PATH       │ Split path into segments                              │
│  └────────┬─────────┘                                                       │
│           │                                                                 │
│           ▼                                                                 │
│  ┌──────────────────┐                                                       │
│  │ BUILD_NODE_TREE  │ Create/navigate Oak node hierarchy                    │
│  └────────┬─────────┘                                                       │
│           │                                                                 │
│           ▼                                                                 │
│  ┌──────────────────┐                                                       │
│  │ ENRICH_WALLET    │ Add metadata to wallet node (if new)                  │
│  └────────┬─────────┘                                                       │
│           │                                                                 │
│           ▼                                                                 │
│  ┌──────────────────┐                                                       │
│  │ SET_PROPERTIES   │ Set content properties (message, timestamp, etc.)     │
│  └────────┬─────────┘                                                       │
│           │                                                                 │
│           │ Has binary?                                                     │
│           ▼                                                                 │
│  ┌──────────────────┐                                                       │
│  │ HANDLE_BINARY    │ Create Blob, set jcr:data, ipfsCid                    │
│  └────────┬─────────┘                                                       │
│           │                                                                 │
│           ▼                                                                 │
│  ┌──────────────────┐                                                       │
│  │ COMMIT           │ nodeStore.merge(), fileStore.flush()                  │
│  └────────┬─────────┘                                                       │
│           │                                                                 │
│           ▼                                                                 │
│  ┌──────────────────┐                                                       │
│  │ UPDATE_HEAD      │ Update latestHead cache                               │
│  └────────┬─────────┘                                                       │
│           │                                                                 │
│           ▼                                                                 │
│  ┌──────────────────┐                                                       │
│  │ EMIT_SSE_EVENT   │ Broadcast content change event                        │
│  └──────────────────┘                                                       │
│                                                                             │
│  ERROR STATES:                                                              │
│  - INVALID_PATH: Path format incorrect                                      │
│  - SIGNATURE_NULL: Security violation (should never happen)                 │
│  - BLOB_CREATE_FAILED: BlobStore error                                      │
│  - COMMIT_FAILED: Oak merge error                                           │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Missing Tests

### Critical Test Scenarios (Priority 1)

```java
// ConsensusApiHandlerTest.java - PROPOSED

// Write Proposal Tests
@Test void testHandleProposeWrite_ValidRequest_ReturnsProposalId();
@Test void testHandleProposeWrite_MissingWallet_Returns400();
@Test void testHandleProposeWrite_InvalidWalletFormat_Returns400();
@Test void testHandleProposeWrite_WalletNotRegistered_Returns403();
@Test void testHandleProposeWrite_WalletMismatch_Returns403();
@Test void testHandleProposeWrite_MultipartWithBinary_Success();
@Test void testHandleProposeWrite_BackpressureLimit_Returns503();
@Test void testHandleProposeWrite_InvalidOrganization_Returns400();

// Delete Proposal Tests
@Test void testHandleDeleteProposal_ValidRequest_Success();
@Test void testHandleDeleteProposal_PathOwnershipViolation_Returns403();
@Test void testHandleDeleteProposal_WalletNotRegistered_Returns403();

// Apply Replicated Write Tests
@Test void testApplyReplicatedWrite_CreatesNodeHierarchy();
@Test void testApplyReplicatedWrite_EnrichesWalletNode();
@Test void testApplyReplicatedWrite_HandlesBinaryWithIpfsCid();
@Test void testApplyReplicatedWrite_NullSignature_ThrowsException();
@Test void testApplyReplicatedWrite_EmitsSSEEvent();

// Apply Replicated Delete Tests
@Test void testApplyReplicatedDelete_RemovesNode();
@Test void testApplyReplicatedDelete_PathNotExists_NoError();
@Test void testApplyReplicatedDelete_EmitsSSEEvent();

// Status Queries
@Test void testHandleGetConsensusStatus_ReturnsValidJson();
@Test void testHandleGetProposalStatus_ValidId_ReturnsStatus();
@Test void testHandleGetProposalStatus_InvalidId_Returns404();
@Test void testHandleGetPendingCount_ReturnsCount();
```

### Integration Test Scenarios (Priority 2)

```java
@Test void testWriteAndReadRoundTrip();
@Test void testDeleteAndVerifyRemoved();
@Test void testBinaryUploadAndRetrieval();
@Test void testMultipleWalletsIsolation();
```

---

## Documentation Gaps

### Methods Missing JavaDoc

| Method | Lines | Priority |
|--------|-------|----------|
| `enrichWalletNode()` | 1474-1533 | Medium |
| `discoverLeaderFromPeerClusterState()` | 1535-1610 | Medium |
| `resolveUrlToIP()` | 1612-1634 | Low |
| `getNextValidatorInRotation()` | 1636-1696 | Medium |
| `extractOrganizationFromPath()` | 1773-1801 | Low |
| `trackFragmentation()` | 1803-1845 | Low |
| `queryWalletNode()` | 1899-1951 | Medium |
| `queryTopWallets()` | 1953-2007 | Medium |
| `queryWalletContent()` | 2009-2064 | Medium |
| `buildGenesisStructure()` | 2066-2171 | Medium |
| `estimateContentSizeMB()` | 2173-2293 | Low |

### Missing Architecture Documentation

1. **Path Structure**: Document the `/oak-chain/{shard}/...` path hierarchy
2. **Binary Handling**: Document the BlobStore + IPFS integration
3. **SSE Events**: Document the event types and payloads

---

## Action Items

### Immediate (This Sprint)

| # | Action | Effort | Impact |
|---|--------|--------|--------|
| 1 | Extract `WalletValidator` class | Low | High |
| 2 | Create `WriteProposal` value object | Low | High |
| 3 | Add JavaDoc to undocumented methods | Medium | Medium |
| 4 | Create unit tests for validation logic | Medium | High |

### Short-Term (Next 2 Sprints)

| # | Action | Effort | Impact |
|---|--------|--------|--------|
| 5 | Extract `WriteApplicationService` | Medium | High |
| 6 | Extract `DeleteApplicationService` | Low | Medium |
| 7 | Replace manual JSON with library | Medium | Medium |
| 8 | Create unit tests for apply methods | High | High |

### Medium-Term (Q1 2026)

| # | Action | Effort | Impact |
|---|--------|--------|--------|
| 9 | Extract `WalletQueryHandler` | Medium | Medium |
| 10 | Refactor `handleProposeWrite()` | High | High |
| 11 | Create integration tests | High | High |

---

## Appendix: Method Inventory

### Public Methods (HTTP Handlers)

| Method | Lines | Purpose | Tests |
|--------|-------|---------|-------|
| `handleProposeWrite()` | 62-709 | Write proposal endpoint | 0 |
| `handleDeleteProposal()` | 731-957 | Delete proposal endpoint | 0 |
| `handleGetConsensusStatus()` | 959-1028 | Consensus status query | 0 |
| `handleGetProposalStatus()` | 1030-1068 | Proposal status query | 0 |
| `handleGetPendingCount()` | 1074-1091 | Pending count query | 0 |
| `applyReplicatedWrite()` | 1099-1361 | Apply write (Aeron callback) | 0 |
| `applyReplicatedDelete()` | 1370-1455 | Apply delete (Aeron callback) | 0 |
| `handleGCCostEstimate()` | 1698-1771 | GC cost estimation | 0 |
| `handleWalletStats()` | 1847-1873 | Wallet statistics | 0 |
| `handleWalletContent()` | 1875-1897 | Wallet content query | 0 |

### Private Methods (Utilities)

| Method | Lines | Purpose |
|--------|-------|---------|
| `enrichWalletNode()` | 1474-1533 | Add metadata to wallet node |
| `discoverLeaderFromPeerClusterState()` | 1535-1610 | Find leader via HTTP |
| `resolveUrlToIP()` | 1612-1634 | DNS resolution |
| `getNextValidatorInRotation()` | 1636-1696 | Round-robin validator selection |
| `extractOrganizationFromPath()` | 1773-1801 | Parse org from path |
| `trackFragmentation()` | 1803-1845 | Track storage fragmentation |
| `queryWalletNode()` | 1899-1951 | Query wallet metadata |
| `queryTopWallets()` | 1953-2007 | Query top wallets by content |
| `queryWalletContent()` | 2009-2064 | Query wallet content |
| `buildGenesisStructure()` | 2066-2171 | Build genesis node |
| `estimateContentSizeMB()` | 2173-2293 | Estimate content size |

---

*Analysis completed: January 10, 2026*  
*Next step: Extract WalletValidator and WriteProposal value object*
