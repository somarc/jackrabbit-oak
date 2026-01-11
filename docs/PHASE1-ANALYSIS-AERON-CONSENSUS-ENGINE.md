# Phase 1 Analysis: AeronConsensusEngine.java

**Date**: January 10, 2026  
**Status**: Analysis Complete  
**File**: `oak-segment-consensus/src/main/java/org/apache/jackrabbit/oak/segment/consensus/aeron/AeronConsensusEngine.java`  
**Lines of Code**: 4,586  
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

### Overall Assessment: 🔴 HIGH TECHNICAL DEBT

`AeronConsensusEngine` is a **God Class** anti-pattern with 4,586 lines handling at least **12 distinct responsibilities**. While the class has excellent JavaDoc at the class level, it violates Single Responsibility Principle severely and has zero unit tests.

### Key Metrics

| Metric | Value | Target | Status |
|--------|-------|--------|--------|
| Lines of Code | 4,586 | <500 | 🔴 9x over |
| Public Methods | 45+ | <20 | 🔴 2x over |
| Private Methods | 35+ | <15 | 🔴 2x over |
| Inner Classes | 4 | <2 | 🟡 |
| Unit Tests | 0 | >50 | 🔴 Critical |
| Cyclomatic Complexity | High | Low | 🔴 |

### Positive Observations

1. ✅ Excellent class-level JavaDoc with architecture diagrams
2. ✅ Good use of section separators (`// ━━━━━━━━━━━━━━━━━━`)
3. ✅ Service layer extraction already started (MessageDispatcher, SnapshotService, LeaderDiscoveryService)
4. ✅ Clear naming conventions
5. ✅ Logging is comprehensive

---

## Class Responsibilities Analysis

The class currently handles **12+ distinct responsibilities**:

### 1. Aeron ClusteredService Implementation (Core)
- `onStart()`, `onSessionOpen()`, `onSessionClose()`, `onTakeSnapshot()`, `onSessionMessage()`, `onTimerEvent()`, `onRoleChange()`, `onTerminate()`
- **Lines**: ~800
- **Recommendation**: Keep in this class (interface contract)

### 2. Message Processing & Dispatching
- `onSessionMessage()` - 360 lines of switch/if logic
- `extractJsonField()`, `extractJsonFieldInt()`, `extractJsonFieldBoolean()`, `extractJsonFieldLong()`
- **Lines**: ~500
- **Recommendation**: Extract to `MessageProcessor` class

### 3. Snapshot Management
- `sendSnapshotMetadata()`, `streamTarFiles()`, `streamJournal()`, `streamFile()`, `offerWithRetry()`, `loadSnapshotFromImage()`
- Inner class: `FileReceiver`, `SnapshotState`
- **Lines**: ~400
- **Recommendation**: Already partially extracted to `SnapshotService` - complete extraction

### 4. Write Proposal Ingress
- `sendWriteThroughIngress()` (2 overloads), `sendWriteBatchThroughIngress()`, `sendDeleteThroughIngress()`
- `ensureInternalClusterClient()` - 200 lines
- **Lines**: ~600
- **Recommendation**: Extract to `WriteIngressService`

### 5. GC Proposal Handling
- `handleGCProposal()`, `handleGCVote()`, `handleGCExecution()`
- `sendGCProposalThroughIngress()`, `sendGCVoteThroughIngress()`, `sendGCExecuteThroughIngress()`
- **Lines**: ~300
- **Recommendation**: Extract to `GCIngressService`

### 6. Ethereum Integration
- `initializeEthereumIntegration()`, `startEthereumEpochPolling()`, `getCurrentEthereumEpoch()`
- **Lines**: ~100
- **Recommendation**: Already has `BeaconChainClient` - delegate more

### 7. Leader Discovery & Management
- `discoverLeaderFromAeronClusterState()`, `discoverLeaderFromPeers()`, `getCurrentLeader()`, `getLeaderMemberId()`
- **Lines**: ~200
- **Recommendation**: Already extracted to `LeaderDiscoveryService` - complete delegation

### 8. HEAD Synchronization
- `syncHeadFromLeaderOnStartup()`, `waitForGenesisAndSync()`, `pullSegmentsForHead()`
- **Lines**: ~300
- **Recommendation**: Extract to `HeadSyncService`

### 9. HEAD Broadcasting
- `scheduleHeadBroadcast()`, `broadcastHeadToFollowersImmediate()`, `checkAndBroadcastAtFinalityBoundary()`
- `startHeadBroadcastTimer()`, `stopHeadBroadcastTimer()`, `checkPendingHeadBroadcasts()`
- **Lines**: ~200
- **Recommendation**: Extract to `HeadBroadcastService`

### 10. Genesis Creation
- `createGenesisViaConsensus()`, `applyGenesisCreation()` (not shown but referenced)
- **Lines**: ~100
- **Recommendation**: Extract to `GenesisService`

### 11. Role & State Management
- `updateRoleFromCluster()`, `getCurrentRole()`, `isLeader()`, `getCurrentTerm()`, `getCurrentEpoch()`
- `recordLeadershipChange()`, `getLeadershipHistory()`
- **Lines**: ~200
- **Recommendation**: Keep core state, extract history to `LeadershipHistoryTracker`

### 12. Metrics & Performance
- Uses `AeronPerformanceMetrics`, `BackpressureManager`
- Various throughput tracking fields
- **Lines**: ~100
- **Recommendation**: Already extracted - good

---

## Technical Debt Identified

### 1. 🔴 God Class Anti-Pattern (Critical)

**Problem**: Single class with 4,586 lines handling 12+ responsibilities.

**Impact**: 
- Impossible to unit test effectively
- High cognitive load for maintainers
- Changes in one area risk breaking others
- Merge conflicts likely

**Solution**: Extract into focused service classes (see Refactoring Recommendations)

---

### 2. 🔴 Unused Fields (Linter Warnings)

From linter output:
```
L130:37: The value of the field AeronConsensusEngine.messageDispatcher is not used
L131:35: The value of the field AeronConsensusEngine.snapshotService is not used
L132:42: The value of the field AeronConsensusEngine.leaderDiscoveryService is not used
L2316:35: The value of the field AeronConsensusEngine.gcCallback is not used
L2795:27: The value of the field AeronConsensusEngine.lastHeadBroadcastTime is not used
L2796:26: The value of the field AeronConsensusEngine.writesSinceLastBroadcast is not used
L2799:26: The value of the field AeronConsensusEngine.lastFinalizedEpoch is not used
L2824:30: The value of the field AeronConsensusEngine.running is not used
```

**Problem**: Service layer components created but not fully utilized.

**Solution**: Complete the extraction and delegate to these services.

---

### 3. 🔴 Unused Methods (Linter Warnings)

```
L432:18: The method startEthereumEpochPolling() is never used locally
L3315:18: The method waitForGenesisAndSync() is never used locally
L3550:20: The method extractUrlFromEndpoint(String) is never used locally
```

**Problem**: Dead code that should be removed or integrated.

**Solution**: Remove if truly dead, or integrate if needed.

---

### 4. 🟡 Manual JSON Parsing

**Location**: Lines 1179-1333

```java
private String extractJsonField(String json, String field) {
    String fieldPrefix = "\"" + field + "\"";
    int fieldStart = json.indexOf(fieldPrefix);
    // ... manual string parsing
}
```

**Problem**: Fragile, error-prone, doesn't handle edge cases (escaped quotes, nested objects).

**Solution**: Use a proper JSON library (Jackson, Gson) or at least `javax.json`.

---

### 5. 🟡 Long Methods

| Method | Lines | Recommended Max |
|--------|-------|-----------------|
| `onSessionMessage()` | ~360 | 50 |
| `ensureInternalClusterClient()` | ~200 | 50 |
| `sendWriteBatchThroughIngress()` | ~185 | 50 |
| `syncHeadFromLeaderOnStartup()` | ~220 | 50 |
| `loadSnapshotFromImage()` | ~110 | 50 |

---

### 6. 🟡 Excessive Debug Logging

**Example** (lines 831-833):
```java
log.debug("🔍DEBUG_BATCH [RCV-1]: onSessionMessage() CALLED - session: {}, length: {}, role: {}", ...);
log.debug("📨 onSessionMessage() called - session: {}, length: {}, role: {}, timestamp: {}", ...);
```

**Problem**: Duplicate debug statements, numbered debug markers suggest debugging-in-progress code left in.

**Solution**: Clean up debug logging, use structured logging, remove numbered markers.

---

### 7. 🟡 Hardcoded Constants

**Examples**:
```java
private static final long LEADER_CACHE_TTL_MS = 10000; // 10 seconds
private static final long SUMMARY_LOG_INTERVAL_MS = 10000;
private static final int DEFAULT_BATCH_SIZE_WRITES = 100;
private static final long DEFAULT_BATCH_INTERVAL_MS = 5000;
private static final int MAX_HISTORY_ENTRIES = 100;
```

**Problem**: Constants scattered throughout, not configurable.

**Solution**: Consolidate into a `ConsensusConfig` class with builder pattern.

---

### 8. 🟡 Thread Safety Concerns

**Example** (lines 2221-2223):
```java
synchronized (this) {
    internalClusterClient = null;
    ensureInternalClusterClient();
}
```

**Problem**: Mixing `volatile` fields with `synchronized` blocks, potential race conditions.

**Solution**: Use proper concurrent data structures or a dedicated connection manager.

---

## Refactoring Recommendations

### Proposed Architecture

```
AeronConsensusEngine (Core - ~800 lines)
├── ClusteredService implementation
├── Role/state management
└── Delegates to:
    ├── MessageProcessor (new - ~400 lines)
    │   ├── Write proposal processing
    │   ├── Delete proposal processing
    │   ├── GC message processing
    │   └── JSON parsing utilities
    ├── WriteIngressService (new - ~500 lines)
    │   ├── sendWriteThroughIngress()
    │   ├── sendWriteBatchThroughIngress()
    │   ├── sendDeleteThroughIngress()
    │   └── Client connection management
    ├── GCIngressService (new - ~200 lines)
    │   ├── sendGCProposalThroughIngress()
    │   ├── sendGCVoteThroughIngress()
    │   └── sendGCExecuteThroughIngress()
    ├── SnapshotService (existing - complete extraction)
    │   ├── takeSnapshot()
    │   └── loadSnapshot()
    ├── HeadSyncService (new - ~300 lines)
    │   ├── syncHeadFromLeader()
    │   ├── pullSegmentsForHead()
    │   └── waitForGenesisAndSync()
    ├── HeadBroadcastService (new - ~150 lines)
    │   ├── scheduleHeadBroadcast()
    │   └── broadcastAtFinalityBoundary()
    ├── LeaderDiscoveryService (existing - complete delegation)
    └── GenesisService (new - ~100 lines)
        └── createGenesisViaConsensus()
```

### Extraction Priority

| Priority | Service | Effort | Impact |
|----------|---------|--------|--------|
| 1 | MessageProcessor | Medium | High - enables testing |
| 2 | WriteIngressService | Medium | High - critical path |
| 3 | HeadSyncService | Low | Medium |
| 4 | Complete SnapshotService | Low | Medium |
| 5 | GCIngressService | Low | Low |
| 6 | HeadBroadcastService | Low | Low |
| 7 | GenesisService | Low | Low |

---

## State Machine Documentation

### 1. Validator Role State Machine

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                     VALIDATOR ROLE STATE MACHINE                            │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│                    ┌──────────────┐                                         │
│                    │   STARTUP    │                                         │
│                    └──────┬───────┘                                         │
│                           │                                                 │
│                           ▼                                                 │
│                    ┌──────────────┐                                         │
│                    │   FOLLOWER   │◄─────────────────────┐                  │
│                    └──────┬───────┘                      │                  │
│                           │                              │                  │
│                           │ onRoleChange(LEADER)         │                  │
│                           │ (Aeron Raft election)        │                  │
│                           ▼                              │                  │
│                    ┌──────────────┐                      │                  │
│                    │    LEADER    │──────────────────────┘                  │
│                    └──────────────┘   onRoleChange(FOLLOWER)                │
│                           │           (higher term seen,                    │
│                           │            step-down, or                        │
│                           │            network partition)                   │
│                           │                                                 │
│                           │ onTerminate()                                   │
│                           ▼                                                 │
│                    ┌──────────────┐                                         │
│                    │  TERMINATED  │                                         │
│                    └──────────────┘                                         │
│                                                                             │
│  TRANSITIONS:                                                               │
│  - STARTUP → FOLLOWER: onStart() called by Aeron                           │
│  - FOLLOWER → LEADER: Aeron Raft election (majority vote)                  │
│  - LEADER → FOLLOWER: Higher term seen, step-down, partition               │
│  - Any → TERMINATED: onTerminate() called                                  │
│                                                                             │
│  ACTIONS ON TRANSITION:                                                     │
│  - → LEADER: Check for genesis, create if missing                          │
│  - → FOLLOWER: Discover leader, sync HEAD                                  │
│  - → TERMINATED: Close internal client, cleanup                            │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 2. Write Proposal Processing State Machine

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                  WRITE PROPOSAL PROCESSING STATE MACHINE                    │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  CLIENT                                                                     │
│    │                                                                        │
│    │ HTTP POST /v1/write                                                    │
│    ▼                                                                        │
│  ┌──────────────────┐                                                       │
│  │ RECEIVED         │ ConsensusApiHandler receives proposal                 │
│  └────────┬─────────┘                                                       │
│           │                                                                 │
│           │ Validate signature, check wallet ownership                      │
│           ▼                                                                 │
│  ┌──────────────────┐                                                       │
│  │ VALIDATED        │ Proposal passes validation                            │
│  └────────┬─────────┘                                                       │
│           │                                                                 │
│           │ Queue in ProposalQueueManagerOptimized                          │
│           ▼                                                                 │
│  ┌──────────────────┐                                                       │
│  │ QUEUED           │ Waiting for epoch batch or priority dispatch          │
│  └────────┬─────────┘                                                       │
│           │                                                                 │
│           │ sendWriteThroughIngress() or sendWriteBatchThroughIngress()     │
│           ▼                                                                 │
│  ┌──────────────────┐                                                       │
│  │ INGRESSED        │ Sent to Aeron Cluster via internal client             │
│  └────────┬─────────┘                                                       │
│           │                                                                 │
│           │ Aeron Raft replication (all nodes)                              │
│           ▼                                                                 │
│  ┌──────────────────┐                                                       │
│  │ REPLICATED       │ onSessionMessage() called on ALL nodes                │
│  └────────┬─────────┘                                                       │
│           │                                                                 │
│           │ writeCallback.applyReplicatedWrite()                            │
│           ▼                                                                 │
│  ┌──────────────────┐                                                       │
│  │ APPLIED          │ Content written to Oak FileStore                      │
│  └────────┬─────────┘                                                       │
│           │                                                                 │
│           │ backpressureManager.incrementAcknowledged()                     │
│           ▼                                                                 │
│  ┌──────────────────┐                                                       │
│  │ ACKNOWLEDGED     │ Metrics updated, response sent to client              │
│  └──────────────────┘                                                       │
│                                                                             │
│  ERROR STATES:                                                              │
│  - VALIDATION_FAILED: Invalid signature, unauthorized path                  │
│  - INGRESS_FAILED: Back-pressure, not connected                             │
│  - REPLICATION_FAILED: Cluster not available                                │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 3. Snapshot Lifecycle State Machine

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                      SNAPSHOT LIFECYCLE STATE MACHINE                       │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  TAKE SNAPSHOT (onTakeSnapshot):                                            │
│                                                                             │
│  ┌──────────────────┐                                                       │
│  │ SNAPSHOT_START   │ Aeron triggers snapshot                               │
│  └────────┬─────────┘                                                       │
│           │                                                                 │
│           │ sendSnapshotMetadata()                                          │
│           ▼                                                                 │
│  ┌──────────────────┐                                                       │
│  │ METADATA_SENT    │ HEAD, epoch, timestamp sent                           │
│  └────────┬─────────┘                                                       │
│           │                                                                 │
│           │ streamTarFiles()                                                │
│           ▼                                                                 │
│  ┌──────────────────┐                                                       │
│  │ TARS_STREAMING   │ TAR files chunked and sent                            │
│  └────────┬─────────┘                                                       │
│           │                                                                 │
│           │ streamJournal()                                                 │
│           ▼                                                                 │
│  ┌──────────────────┐                                                       │
│  │ JOURNAL_SENT     │ journal.log sent                                      │
│  └────────┬─────────┘                                                       │
│           │                                                                 │
│           ▼                                                                 │
│  ┌──────────────────┐                                                       │
│  │ SNAPSHOT_COMPLETE│ All data sent to Aeron                                │
│  └──────────────────┘                                                       │
│                                                                             │
│  LOAD SNAPSHOT (onStart with snapshotImage):                                │
│                                                                             │
│  ┌──────────────────┐                                                       │
│  │ LOAD_START       │ snapshotImage present                                 │
│  └────────┬─────────┘                                                       │
│           │                                                                 │
│           │ loadSnapshotFromImage()                                         │
│           ▼                                                                 │
│  ┌──────────────────┐                                                       │
│  │ FRAGMENTS_POLLED │ Poll all fragments from image                         │
│  └────────┬─────────┘                                                       │
│           │                                                                 │
│           │ Verify HEAD matches FileStore                                   │
│           ▼                                                                 │
│  ┌──────────────────┐                                                       │
│  │ HEAD_VERIFIED    │ Snapshot HEAD == FileStore HEAD                       │
│  └────────┬─────────┘                                                       │
│           │                                                                 │
│           │ Restore epoch state                                             │
│           ▼                                                                 │
│  ┌──────────────────┐                                                       │
│  │ LOAD_COMPLETE    │ State restored, ready to process                      │
│  └──────────────────┘                                                       │
│                                                                             │
│  ERROR STATES:                                                              │
│  - HEAD_MISMATCH: FileStore HEAD != Snapshot HEAD (FATAL)                   │
│  - FRAGMENT_ERROR: Failed to process snapshot fragment                      │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 4. Internal Cluster Client State Machine

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                  INTERNAL CLUSTER CLIENT STATE MACHINE                      │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌──────────────────┐                                                       │
│  │ NOT_CREATED      │ internalClusterClient == null                         │
│  └────────┬─────────┘                                                       │
│           │                                                                 │
│           │ ensureInternalClusterClient() on first write                    │
│           ▼                                                                 │
│  ┌──────────────────┐                                                       │
│  │ CONNECTING       │ Building ingress endpoints, creating client           │
│  └────────┬─────────┘                                                       │
│           │                                                                 │
│           │ AeronCluster.connect() with retry                               │
│           ▼                                                                 │
│  ┌──────────────────┐                                                       │
│  │ CONNECTED        │ Client ready, isClosed() == false                     │
│  └────────┬─────────┘                                                       │
│           │                                                                 │
│           │ Session timeout or network issue                                │
│           ▼                                                                 │
│  ┌──────────────────┐                                                       │
│  │ CLOSED           │ isClosed() == true                                    │
│  └────────┬─────────┘                                                       │
│           │                                                                 │
│           │ Next write attempt triggers reconnect                           │
│           └──────────────────────────────────────────────┐                  │
│                                                          │                  │
│                                                          ▼                  │
│                                               ┌──────────────────┐          │
│                                               │ RECONNECTING     │          │
│                                               └────────┬─────────┘          │
│                                                        │                    │
│                                                        │ Success            │
│                                                        └───► CONNECTED      │
│                                                                             │
│  HEALTH CHECKS:                                                             │
│  - Before each offer(): Check isClosed()                                    │
│  - On CLOSED: Set to null, trigger reconnect                                │
│  - Retry with backoff on connection failure                                 │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Missing Tests

### Critical Test Scenarios (Priority 1)

```java
// AeronConsensusEngineTest.java - PROPOSED

@Test void testOnStartWithoutSnapshot_InitializesCorrectly();
@Test void testOnStartWithSnapshot_RestoresState();
@Test void testOnStartWithSnapshot_HeadMismatch_ThrowsException();

@Test void testOnSessionMessage_WriteProposal_AppliesWrite();
@Test void testOnSessionMessage_WriteBatch_AppliesAllWrites();
@Test void testOnSessionMessage_DeleteProposal_AppliesDelete();
@Test void testOnSessionMessage_GCProposal_HandlesCorrectly();
@Test void testOnSessionMessage_InvalidTemplateId_Ignored();
@Test void testOnSessionMessage_MalformedJson_HandledGracefully();

@Test void testOnRoleChange_FollowerToLeader_CreatesGenesis();
@Test void testOnRoleChange_LeaderToFollower_UpdatesState();
@Test void testOnRoleChange_IncrementsTerm();

@Test void testSendWriteThroughIngress_Success();
@Test void testSendWriteThroughIngress_BackPressure_Retries();
@Test void testSendWriteThroughIngress_NotConnected_Reconnects();
@Test void testSendWriteThroughIngress_ClientClosed_Reconnects();

@Test void testSendWriteBatchThroughIngress_Success();
@Test void testSendWriteBatchThroughIngress_EmptyBatch_ReturnsZero();

@Test void testIsLeader_WhenLeader_ReturnsTrue();
@Test void testIsLeader_WhenFollower_ReturnsFalse();
@Test void testGetCurrentRole_ReflectsAeronRole();
```

### Integration Test Scenarios (Priority 2)

```java
@Test void testThreeNodeCluster_LeaderElection();
@Test void testThreeNodeCluster_WriteReplication();
@Test void testThreeNodeCluster_LeaderFailover();
@Test void testThreeNodeCluster_SnapshotAndRestore();
```

---

## Documentation Gaps

### Methods Missing JavaDoc

| Method | Lines | Priority |
|--------|-------|----------|
| `ensureInternalClusterClient()` | 1533-1744 | High |
| `sendMessageWithRetry()` | 2500-2557 | Medium |
| `checkPendingHeadBroadcasts()` | 2937-2960 | Low |
| `recordLeadershipChange()` | 3781-3801 | Low |
| `discoverLeaderFromAeronClusterState()` | 3803-3896 | Medium |
| `isSameUrlByPort()` | 3898-3915 | Low |
| `resolveUrlToIP()` | 3917-3951 | Low |

### Missing Architecture Documentation

1. **Message Flow Diagram**: How messages flow from HTTP → Queue → Ingress → Raft → Apply
2. **Failure Modes**: What happens when leader fails, network partitions, etc.
3. **Configuration Guide**: All configurable parameters and their effects

---

## Action Items

### Immediate (This Sprint)

| # | Action | Effort | Impact |
|---|--------|--------|--------|
| 1 | Remove unused fields (linter warnings) | Low | Medium |
| 2 | Remove unused methods (linter warnings) | Low | Medium |
| 3 | Add JavaDoc to undocumented public methods | Medium | Medium |
| 4 | Create unit tests for `onSessionMessage()` | High | Critical |
| 5 | Create unit tests for role transitions | Medium | High |

### Short-Term (Next 2 Sprints)

| # | Action | Effort | Impact |
|---|--------|--------|--------|
| 6 | Extract `MessageProcessor` class | High | High |
| 7 | Extract `WriteIngressService` class | High | High |
| 8 | Complete `SnapshotService` extraction | Medium | Medium |
| 9 | Replace manual JSON parsing with library | Medium | Medium |
| 10 | Create integration tests | High | High |

### Medium-Term (Q1 2026)

| # | Action | Effort | Impact |
|---|--------|--------|--------|
| 11 | Extract remaining services | High | High |
| 12 | Consolidate configuration | Medium | Medium |
| 13 | Clean up debug logging | Low | Low |
| 14 | Add performance benchmarks | Medium | Medium |

---

## Appendix: Field Inventory

### Used Fields (Keep)

```java
private final FileStore fileStore;
private final NodeStore nodeStore;
private final String selfUrl;
private final List<String> peerUrls;
private final EthereumWallet wallet;
private final SegmentReplicator replicator;
private final String storeDirectory;
private final BackpressureManager backpressureManager;
private final BlobStore blobStore;
private Cluster cluster;
private IdleStrategy idleStrategy;
private String ingressChannelUri;
private String aeronDirectoryName;
private AeronCluster internalClusterClient;
private WriteApplicationCallback writeCallback;
private BeaconChainClient beaconClient;
private volatile int currentEthereumEpoch;
private volatile ValidatorRole currentRole;
private volatile int currentTerm;
private volatile String currentLeader;
private final Map<String, Long> validatorJoinTimes;
private final Map<Integer, String> nodeIdToUrl;
private volatile String cachedLeaderUrl;
private volatile long cachedLeaderTimestamp;
private final AtomicLong totalWritesProcessed;
private final AeronPerformanceMetrics performanceMetrics;
private final ConcurrentLinkedQueue<Long> ingressTimestamps;
private final List<LeadershipChange> leadershipHistory;
```

### Unused Fields (Remove or Integrate)

```java
private final MessageDispatcher messageDispatcher;        // Created but not used
private final SnapshotService snapshotService;            // Created but not used
private final LeaderDiscoveryService leaderDiscoveryService; // Created but not used
private GCApplicationCallback gcCallback;                 // Set but not used
private volatile long lastHeadBroadcastTime;              // Not used
private volatile int writesSinceLastBroadcast;            // Not used
private volatile int lastFinalizedEpoch;                  // Not used
private volatile boolean running;                         // Not used
```

---

*Analysis completed: January 10, 2026*  
*Next step: Begin refactoring with MessageProcessor extraction*
