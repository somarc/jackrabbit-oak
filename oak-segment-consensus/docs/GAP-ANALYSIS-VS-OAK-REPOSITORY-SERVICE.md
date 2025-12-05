# Gap Analysis: oak-segment-consensus vs oak-repository-service

**Date**: December 4, 2025  
**Purpose**: Identify missing features in `oak-segment-consensus` compared to Adobe's production `oak-repository-service` implementation

---

## Executive Summary

`oak-repository-service` (OakRS) is Adobe's production-grade distributed Oak implementation for AEM Cloud Service. It uses Aeron Cluster for consensus and Azure Blob Storage for segment persistence. This document identifies critical gaps between our POC `oak-segment-consensus` and OakRS's production features.

**Key Finding**: While both use Aeron Cluster for consensus, OakRS has a mature **transaction model**, **blob storage integration**, **migration tooling**, and **production-hardened reliability features** that `oak-segment-consensus` currently lacks.

---

## Architecture Comparison

### oak-repository-service (Adobe's Production Implementation)

```
┌─────────────────────────────────────────────────────────────────┐
│                     AEM Cloud Service Author Pods                │
│  (Multiple instances with RemoteNodeStore clients)              │
└────────────────────────┬────────────────────────────────────────┘
                         │
                         │ HTTP + Custom Protocol
                         ▼
┌─────────────────────────────────────────────────────────────────┐
│               Repository Service (Leader + Standby)              │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │  Aeron Cluster (3-node Raft)                               │ │
│  │  - Leader election                                         │ │
│  │  - Transaction log replication                             │ │
│  │  - Message ordering guarantees                             │ │
│  └────────────────────────────────────────────────────────────┘ │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │  Service Layer (Leader only)                               │ │
│  │  - Dedicated Write Thread (single-threaded)                │ │
│  │  - MergeScheduler (transaction ordering)                   │ │
│  │  - Transaction management (START/COMMIT/ABORT)             │ │
│  │  - Segment queue (in-memory + retries)                     │ │
│  └────────────────────────────────────────────────────────────┘ │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │  BlobWriter (Separate component)                           │ │
│  │  - Receives segments from leader                           │ │
│  │  - Persists to Azure Blob Storage                          │ │
│  │  - Acknowledges persistence                                │ │
│  │  - Archive metadata tracking                               │ │
│  └────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────┘
         │
         │ Segment Persistence
         ▼
┌─────────────────────────────────────────────────────────────────┐
│                    Azure Blob Storage                            │
│  - Segment blobs (immutable)                                    │
│  - Archive metadata                                             │
│  - Journal/revision history                                     │
│  - Migration state (for MongoDB → OakRS)                        │
└─────────────────────────────────────────────────────────────────┘
```

**Key Characteristics**:
- ✅ **Leader-Standby Architecture**: Explicit leader election, hot standbys
- ✅ **Transaction Model**: START/COMMIT/ABORT with timeout handling
- ✅ **Blob Storage Integration**: Segments persisted to Azure (cloud-native)
- ✅ **Segment Queue**: Async persistence with acknowledgment flow
- ✅ **Migration Support**: Online migration from MongoDB
- ✅ **Production Deployment**: Running in AEM CS production

---

### oak-segment-consensus (Our POC Implementation)

```
┌─────────────────────────────────────────────────────────────────┐
│                  Sling Author Instances (Multiple)               │
│  (HTTP clients with composite mounts)                            │
└────────────────────────┬────────────────────────────────────────┘
                         │
                         │ HTTP Segment Transfer (read-only)
                         ▼
┌─────────────────────────────────────────────────────────────────┐
│              Validator Network (Aeron Cluster)                   │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │  Aeron Cluster (3-node Raft)                               │ │
│  │  - Deterministic state machine                             │ │
│  │  - Message ordering guarantees                             │ │
│  └────────────────────────────────────────────────────────────┘ │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │  Consensus Layer (All nodes)                               │ │
│  │  - Proposal queue (Ethereum verification)                  │ │
│  │  - Epoch-based batching (payment tiers)                    │ │
│  │  - Deterministic write application                         │ │
│  │  - GC debt tracking                                        │ │
│  └────────────────────────────────────────────────────────────┘ │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │  Storage Layer (All nodes)                                 │ │
│  │  - FileStore (local TAR files)                             │ │
│  │  - HTTP server (segment serving)                           │ │
│  │  - IPFS DataStore (optional, for binaries)                 │ │
│  └────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────┘
         │
         │ (Optional) Binary Storage
         ▼
┌─────────────────────────────────────────────────────────────────┐
│                         IPFS Network                             │
│  - Binary blobs (author-owned nodes)                            │
│  - CID references in Oak properties                             │
└─────────────────────────────────────────────────────────────────┘
```

**Key Characteristics**:
- ✅ **Deterministic State Machine**: All nodes apply writes identically
- ✅ **Ethereum Integration**: Wallet-based auth, payment verification
- ✅ **Epoch Batching**: Payment tier-based queueing
- ✅ **HTTP Segment Transfer**: Read-only mounts for clients
- ❌ **No Cloud Storage**: Segments stored locally (TAR files)
- ❌ **No Transaction Model**: Direct Oak commits, no START/COMMIT
- ❌ **No Migration Tools**: No support for migrating existing repositories

---

## Feature Gap Analysis

### 🔴 Critical Gaps (Production Blockers)

#### 1. **Cloud-Native Segment Storage**

**oak-repository-service**:
```java
// BlobWriter persists segments to Azure Blob Storage
public class AzureBlobWriterAgent {
    void onSegmentQueued(UUID segmentId, byte[] data) {
        // Write to Azure Blob
        blobContainer.uploadBlob(segmentId.toString(), data);
        
        // Acknowledge persistence
        aeronClient.sendSegmentPersisted(segmentId);
    }
}
```

**oak-segment-consensus**:
```java
// Segments stored in local TAR files (not cloud-native)
FileStore fileStore = FileStoreBuilder.fileStoreBuilder(new File("/var/oak"))
    .withBlobStore(ipfsDataStore)  // Only binaries on IPFS
    .build();
```

**Gap**:
- ❌ No Azure Blob Storage integration for segments
- ❌ No S3/GCS support
- ❌ Segments tied to local disk (not scalable, not durable)
- ❌ Cannot leverage cloud storage benefits (replication, durability, global access)

**Impact**: **BLOCKER** for cloud deployment. Local TAR files don't scale for AEM CS use cases.

**Recommendation**:
- Implement `AzureSegmentStore` similar to OakRS's BlobWriter
- Support S3 (AWS) and GCS (Google Cloud) backends
- Abstract storage layer: `CloudSegmentStore` interface
- Maintain backward compatibility with local FileStore for dev/testing

---

#### 2. **Transaction Model with Explicit Boundaries**

**oak-repository-service**:
```java
// Explicit transaction lifecycle
transaction = startTransaction();
try {
    // Send StartTransaction to Aeron
    aeronClient.sendStartTransaction(transactionId);
    
    // Apply writes (generates RecordWriteOperation messages)
    nodeBuilder.setProperty("foo", "bar");
    nodeStore.merge(nodeBuilder, EmptyHook.INSTANCE, commitInfo);
    
    // Send CommitTransaction to Aeron
    aeronClient.sendCommitTransaction(transactionId);
    
    // Wait for TransactionCommitted acknowledgment
    waitForAck(transactionId);
    
    // Commit head locally
    fileStore.commitHead(newHead);
    
} catch (TimeoutException e) {
    // Send abort
    aeronClient.sendCommitTransaction(transactionId, ABORT);
}
```

**oak-segment-consensus**:
```java
// No explicit transactions - direct Oak commits
NodeBuilder rootBuilder = nodeStore.getRoot().builder();
rootBuilder.setProperty("foo", "bar");

// Direct merge (no START/COMMIT protocol)
nodeStore.merge(rootBuilder, EmptyHook.INSTANCE, commitInfo);
fileStore.flush();
```

**Gap**:
- ❌ No transaction boundaries (START/COMMIT/ABORT messages)
- ❌ No timeout handling for long-running writes
- ❌ No rollback mechanism for failed writes
- ❌ No correlation between Aeron messages and Oak commits

**Impact**: **CRITICAL**. Without transactions:
- Cannot recover from partial failures
- Cannot detect/handle mismatch between Aeron log and Oak state
- Difficult to reason about system state during failures

**Recommendation**:
- Add `StartTransaction` and `CommitTransaction` Aeron messages (templateId 200, 201)
- Implement transaction timeout (configurable, default 30s)
- Add panic/recovery logic for RecordId mismatches (like OakRS)
- Track in-flight transactions per client

---

#### 3. **Segment Persistence Acknowledgment Flow**

**oak-repository-service**:
```
Leader                    Aeron Cluster            BlobWriter
  │                            │                        │
  │  QueueSegment (seg-123)   │                        │
  ├──────────────────────────►│                        │
  │                            │  QueueSegment         │
  │                            ├───────────────────────►│
  │                            │                        │
  │                            │  AckQueueSegment      │
  │                            │◄───────────────────────┤
  │                            │                        │
  │                            │   ... async write ...  │
  │                            │                        │
  │                            │  SegmentPersisted     │
  │  SegmentPersisted          │◄───────────────────────┤
  │◄───────────────────────────┤                        │
  │                            │                        │
  │  AckSegmentPersisted       │  AckSegmentPersisted  │
  ├──────────────────────────►├───────────────────────►│
  │                            │                        │
```

**oak-segment-consensus**:
```
Validator                 Aeron Cluster         Other Validators
  │                            │                        │
  │  Write Proposal            │                        │
  ├──────────────────────────►│                        │
  │                            │  Replicate            │
  │                            ├───────────────────────►│
  │                            │                        │
  │  (All nodes commit locally, no persistence acks)   │
  │                            │                        │
```

**Gap**:
- ❌ No segment queueing protocol
- ❌ No acknowledgment that segments are persisted
- ❌ No retry logic for failed persistence
- ❌ No tracking of pending segments

**Impact**: **CRITICAL**. Without acks:
- Cannot guarantee durability (segment might not be on disk)
- Cannot detect/recover from storage failures
- No visibility into storage lag

**Recommendation**:
- Implement `QueueSegment`/`SegmentPersisted` message flow
- Add `PendingSegmentTracker` (like OakRS's `PendingMessageTracker`)
- Implement retry logic with exponential backoff
- Add metrics for segment persistence lag

---

#### 4. **Leader-Standby Architecture with Failover**

**oak-repository-service**:
```java
public class LeaderStateManager {
    void onBecomeLeader() {
        log.info("Becoming leader, starting services");
        
        // Initialize leader-only components
        blobWriterClient.start();
        segmentWriter.start();
        
        // Query Aeron for current state
        aeronClient.sendGetHead();
        blobWriterClient.querySegmentQueueState();
        
        // Resume writes
        writeQueue.enableWrites();
    }
    
    void onBecomeFollower() {
        log.info("Stepping down from leader, stopping services");
        
        // Stop leader-only components
        blobWriterClient.stop();
        segmentWriter.stop();
        
        // Enter cold standby mode
        writeQueue.disableWrites();
    }
}
```

**oak-segment-consensus**:
```java
// No explicit leader/follower distinction
// All nodes execute writes deterministically
public class AeronConsensusEngine {
    // No onBecomeLeader() callback
    // No leader-specific initialization
    // No standby mode
}
```

**Gap**:
- ❌ No leader-specific initialization/cleanup
- ❌ No standby mode (all nodes active, but waste resources)
- ❌ No query protocol to sync state on leader election

**Impact**: **MODERATE**. Deterministic state machine works, but:
- Wastes resources (all nodes do full processing)
- No optimization for read-only standby nodes
- Harder to scale (can't add read-only replicas)

**Recommendation**:
- Add `onBecomeLeader()` and `onBecomeFollower()` callbacks
- Implement "hot standby" mode (accept reads, reject writes)
- Add `GetHead` message to sync state on failover
- Optimize standby nodes (lazy segment loading, read-only caches)

---

### 🟡 Important Gaps (Production Features)

#### 5. **MergeScheduler (Transaction Ordering)**

**oak-repository-service**:
```java
// Sophisticated transaction ordering to prevent conflicts
public class MergeScheduler {
    // Tracks concurrent changes
    // Enforces server-side ordering
    // Dispatches changes in sequence number order
    
    RemoteRecordId merge = queue.add(
        clientTimestamp -> service.merge(jsopDiff), 
        CommitInfo.EMPTY
    ).head();
}
```

**oak-segment-consensus**:
- ❌ No merge scheduling
- ❌ No client timestamp tracking
- ❌ No concurrent change detection
- ✅ Epoch-based batching provides some ordering

**Impact**: **MODERATE**. May cause:
- Increased merge conflicts under high concurrency
- Suboptimal transaction ordering
- Performance degradation with many concurrent writes

**Recommendation**:
- Implement simplified `MergeScheduler` for conflict resolution
- Use epoch numbers as server timestamps
- Track concurrent changes per wallet/client
- Dispatch in deterministic order (epoch → wallet → timestamp)

---

#### 6. **RecordId Mismatch Detection & Panic Recovery**

**oak-repository-service**:
```java
// Detects offset mismatch between Service and Aeron
if (receivedOffset != expectedOffset) {
    log.error("RecordId mismatch! Expected {}, got {}", 
        expectedOffset, receivedOffset);
    
    // Panic: force new segment on both sides
    aeronClient.sendPanic();
    fileStore.newSegment();
    
    // Resync offsets at 0
}
```

**oak-segment-consensus**:
- ❌ No offset tracking
- ❌ No mismatch detection
- ❌ No panic/recovery mechanism

**Impact**: **MODERATE**. Silent divergence possible if:
- Aeron messages are lost/reordered (shouldn't happen, but...)
- Oak commit fails but Aeron log persists
- Network partition causes split-brain

**Recommendation**:
- Add offset field to write messages
- Track expected offset per segment
- Implement panic message (templateId 202)
- Force segment rotation on mismatch

---

#### 7. **Compaction API & Monitoring**

**oak-repository-service**:
```bash
# Trigger compaction
curl -X POST http://localhost:13579/api/repositories/default/gc

# Check status
curl http://localhost:13579/api/repositories/default/gc | jq
{
  "status": "RUNNING",
  "progress": 45,
  "estimatedTimeRemaining": 120000,
  "reclaimedSizeMB": 1024
}

# Stop compaction
curl -X POST http://localhost:13579/api/repositories/default/gc?stop
```

**oak-segment-consensus**:
```bash
# No direct compaction API
# Must propose GC via consensus (voting required)
curl -X POST http://localhost:8090/v1/gc/propose \
  -d "proposerWallet=0x..." \
  -d "targetRevision=HEAD"
```

**Gap**:
- ❌ No direct compaction trigger (requires consensus vote)
- ❌ No real-time progress monitoring
- ❌ No stop/pause capability

**Impact**: **LOW-MODERATE**. Governance model is intentional, but:
- Difficult to test/debug compaction
- No emergency GC for operational issues
- Slower response to storage issues

**Recommendation**:
- Add admin-only direct GC API (bypass governance)
- Implement progress tracking (percentage, ETA)
- Add `/v1/gc/status` endpoint (current implementation, progress)
- Support cancellation (stop mid-compaction)

---

#### 8. **Online Migration Support**

**oak-repository-service**:
```java
// MigrationNodeStore delegates to either Mongo or OakRS
public class MigrationNodeStore implements NodeStore {
    private NodeStore delegate;  // DocumentNodeStore or RemoteNodeStore
    
    @Override
    public NodeState getRoot() {
        // Delegate based on migration state
        return delegate.getRoot();
    }
    
    void performFinalCatchup() {
        // Lock writes
        writeLock.lock();
        
        // Copy delta from Mongo → OakRS
        migrationExecutor.copyDelta();
        
        // Switch delegate
        this.delegate = remoteNodeStore;
        
        // Unlock writes
        writeLock.unlock();
    }
}
```

**oak-segment-consensus**:
- ❌ No migration tooling
- ❌ No live switchover support
- ❌ No dual-write capability

**Impact**: **HIGH** for adoption. Cannot migrate existing AEM instances without:
- Taking downtime (export/import TAR files)
- Rebuilding indexes
- Losing versioning history

**Recommendation**:
- Build `MigrationNodeStore` for DocumentNodeStore → oak-segment-consensus
- Support Azurite/Azure Blob Storage as intermediate storage
- Implement `sky oakrs migration-sync` equivalent
- Add migration state tracking (similar to OakRS's migration-state blob)

---

### 🟢 Differentiators (Features We Have That They Don't)

#### 9. **Ethereum Integration & Wallet-Based Access Control**

**oak-segment-consensus**:
```java
// Ethereum wallet-based authentication
String wallet = "0xdd870fa1b7c4700f2bd7f44238821c26f7392148";
String shardRoot = "/oak-chain/dd/87/0f/0xdd870fa1b7c4700f2bd7f44238821c26f7392148/";

// Only wallet owner can write to their shard
if (!contentPath.startsWith(shardRoot)) {
    return HTTP_403_FORBIDDEN;
}

// Payment verification on Sepolia testnet
PaymentProof proof = evmBridge.verifyPayment(ethereumTxHash);
if (!proof.isConfirmed(1)) {
    return HTTP_402_PAYMENT_REQUIRED;
}
```

**oak-repository-service**:
- ❌ No blockchain integration
- ❌ No wallet-based auth
- ❌ No payment verification
- ✅ Traditional RBAC (role-based access control)

**Advantage**: Blockchain-native access control, transparent payments, decentralized identity.

---

#### 10. **GC Account Tax Model & Debt Tracking**

**oak-segment-consensus**:
```java
// Track GC debt per entity
public class GCAccountManager {
    public BigDecimal addDebt(String wallet, String path, long sizeMB) {
        BigDecimal cost = BigDecimal.valueOf(sizeMB).multiply(GC_COST_PER_MB);
        account.addDebt(path, sizeMB, cost);
        return cost;
    }
    
    public boolean canWrite(String wallet) {
        EntityGCAccount account = getAccount(wallet);
        return !account.shouldBlockWrites();  // executedDebt < limit
    }
}
```

**oak-repository-service**:
- ❌ No GC debt tracking
- ❌ No write blocking based on cleanup burden
- ❌ No pay-to-unblock mechanism

**Advantage**: Economic incentive alignment, prevents runaway storage growth, fair cost attribution.

---

#### 11. **IPFS DataStore for Binaries**

**oak-segment-consensus**:
```java
// Binaries stored on IPFS (author-owned nodes)
IPFSDataStore ipfsDataStore = new IPFSDataStore(ipfsApiEndpoint);

// CID stored in Oak property
nodeBuilder.setProperty("asset", blobStore.createBlob(inputStream));
// → Oak stores CID: "bafybeigdyrzt5sfp7udm7hu76uh7y26nf3efuylqabf3oclgtqy55fbzdi"
```

**oak-repository-service**:
- ❌ No IPFS support
- ✅ Azure Blob Storage for all data (segments + binaries)

**Advantage**: Decentralized binary storage, author ownership, no cloud vendor lock-in.

---

## Priority Roadmap

### Phase 1: Production Readiness (Q1 2026)
**Goal**: Match OakRS's core reliability features

1. **✅ Transaction Model** (2 weeks)
   - Implement START/COMMIT/ABORT messages
   - Add transaction timeout handling
   - Implement panic/recovery for RecordId mismatch

2. **✅ Cloud Segment Storage** (3 weeks)
   - Abstract `CloudSegmentStore` interface
   - Implement `AzureSegmentStore` (primary)
   - Implement `S3SegmentStore` (secondary)
   - Maintain backward compatibility with `FileStore`

3. **✅ Segment Acknowledgment Flow** (2 weeks)
   - Implement `QueueSegment`/`SegmentPersisted` messages
   - Add `PendingSegmentTracker`
   - Implement retry logic with backoff

4. **✅ Leader-Standby Optimization** (1 week)
   - Add `onBecomeLeader()`/`onBecomeFollower()` callbacks
   - Implement hot standby mode
   - Add `GetHead` message for state sync

**Deliverable**: Production-ready consensus layer with cloud storage.

---

### Phase 2: Enterprise Features (Q2 2026)
**Goal**: Match OakRS's operational maturity

5. **✅ MergeScheduler** (2 weeks)
   - Implement client timestamp tracking
   - Add concurrent change detection
   - Enforce deterministic ordering

6. **✅ Compaction API** (1 week)
   - Add admin-only direct GC trigger
   - Implement progress monitoring
   - Support cancellation

7. **✅ Online Migration** (4 weeks)
   - Build `MigrationNodeStore`
   - Implement dual-write capability
   - Add migration CLI tools

**Deliverable**: Enterprise-grade operational tooling.

---

### Phase 3: Blockchain Differentiators (Q3 2026)
**Goal**: Leverage our unique blockchain features

8. **✅ Smart Contract Integration** (3 weeks)
   - Deploy `GCDebtManager` contract
   - Implement on-chain payment listening
   - Auto-unblock writes on payment

9. **✅ Tokenomics Dashboard** (2 weeks)
   - Real-time debt visualization
   - Payment history
   - Storage cost analytics

10. **✅ IPFS Performance Optimization** (2 weeks)
    - Implement pinning strategies
    - Add local IPFS cache
    - Lazy upload optimization

**Deliverable**: Blockchain-native CMS with economic incentives.

---

## Detailed Gap Breakdown

### Consensus Layer

| Feature | OakRS | oak-segment-consensus | Priority | Effort |
|---------|-------|----------------------|----------|--------|
| Aeron Cluster | ✅ | ✅ | N/A | Done |
| Leader Election | ✅ | ✅ (implicit) | 🟡 | 1 week |
| Transaction Boundaries | ✅ | ❌ | 🔴 | 2 weeks |
| Segment Queue | ✅ | ❌ | 🔴 | 2 weeks |
| Persistence Acks | ✅ | ❌ | 🔴 | 2 weeks |
| Panic Recovery | ✅ | ❌ | 🟡 | 1 week |
| MergeScheduler | ✅ | ❌ | 🟡 | 2 weeks |

---

### Storage Layer

| Feature | OakRS | oak-segment-consensus | Priority | Effort |
|---------|-------|----------------------|----------|--------|
| Azure Blob Storage | ✅ | ❌ | 🔴 | 3 weeks |
| S3 Support | ❌ | ❌ | 🟡 | 2 weeks |
| GCS Support | ❌ | ❌ | 🟢 | 2 weeks |
| IPFS DataStore | ❌ | ✅ | N/A | Done |
| Local FileStore | ✅ | ✅ | N/A | Done |
| Archive Metadata | ✅ | ❌ | 🟡 | 1 week |

---

### Client Integration

| Feature | OakRS | oak-segment-consensus | Priority | Effort |
|---------|-------|----------------------|----------|--------|
| RemoteNodeStore | ✅ | ❌ | 🟡 | 3 weeks |
| HTTP Segment Transfer | ✅ | ✅ | N/A | Done |
| Read-Only Mounts | ✅ | ✅ | N/A | Done |
| Online Migration | ✅ | ❌ | 🟡 | 4 weeks |

---

### Operations & Monitoring

| Feature | OakRS | oak-segment-consensus | Priority | Effort |
|---------|-------|----------------------|----------|--------|
| Compaction API | ✅ | ✅ (governance) | 🟡 | 1 week |
| Progress Monitoring | ✅ | ❌ | 🟡 | 1 week |
| Health Checks | ✅ | ✅ | N/A | Done |
| Metrics (Prometheus) | ✅ | ✅ | N/A | Done |
| Dashboard UI | ❌ | ✅ | N/A | Done |

---

### Blockchain Features (Unique to us)

| Feature | OakRS | oak-segment-consensus | Priority | Effort |
|---------|-------|----------------------|----------|--------|
| Ethereum Integration | ❌ | ✅ | N/A | Done |
| Wallet-Based Auth | ❌ | ✅ | N/A | Done |
| Payment Verification | ❌ | ✅ | N/A | Done |
| GC Debt Tracking | ❌ | ✅ | N/A | Done |
| Epoch Batching | ❌ | ✅ | N/A | Done |
| IPFS Binaries | ❌ | ✅ | N/A | Done |

---

## Key Architectural Differences

### 1. **Write Path**

**OakRS**:
```
AEM Pod → HTTP Request → Service Leader → START_TRANSACTION
                                        ↓
                          Write to SegmentStore (local)
                                        ↓
                          RECORD_WRITE_OPERATION (Aeron)
                                        ↓
                          COMMIT_TRANSACTION (Aeron)
                                        ↓
                          Wait for TransactionCommitted
                                        ↓
                          Commit HEAD locally
                                        ↓
                          QUEUE_SEGMENT (to BlobWriter)
                                        ↓
                          BlobWriter persists to Azure
                                        ↓
                          SEGMENT_PERSISTED (ack)
```

**oak-segment-consensus**:
```
Sling Author → HTTP Request → Validator → Ethereum Verification
                                        ↓
                          Epoch-based Batching
                                        ↓
                          Aeron Replication (WRITE_BATCH)
                                        ↓
                          All Validators: Deterministic Apply
                                        ↓
                          Oak commit (local FileStore)
                                        ↓
                          HEAD consensus (automatic)
```

**Key Differences**:
- OakRS: Leader-centric, explicit transactions, cloud persistence
- oak-segment-consensus: All-node consensus, implicit transactions, local storage

---

### 2. **Failure Handling**

**OakRS**:
- Transaction timeout → ABORT → retry
- RecordId mismatch → PANIC → force new segment
- Message loss → retry with timeout tracking
- Leader failure → standby promotes → query state → resume

**oak-segment-consensus**:
- Aeron message loss → Raft retries automatically
- Oak commit failure → logs error, proposal rejected
- Leader failure → Aeron elects new leader, deterministic replay
- No explicit panic/recovery (relies on Raft's correctness)

---

### 3. **Storage Model**

**OakRS**:
- **Segments**: Azure Blob Storage (immutable blobs)
- **Binaries**: Azure Blob Storage (same container)
- **Metadata**: Separate blobs (journal, archives)
- **Durability**: Cloud-native (99.999999999% durability)

**oak-segment-consensus**:
- **Segments**: Local TAR files (disk-based)
- **Binaries**: IPFS (optional, author-owned)
- **Metadata**: Local files (journal.log, manifest)
- **Durability**: Depends on disk/RAID (99.9%?)

---

## Recommendations

### Short-Term (POC → Alpha)

1. **Add Transaction Boundaries** (CRITICAL)
   - Prevents silent divergence
   - Enables proper error handling
   - Required for production confidence

2. **Implement Segment Acknowledgments** (CRITICAL)
   - Ensures durability guarantees
   - Enables retry logic
   - Improves observability

3. **Add Panic/Recovery** (IMPORTANT)
   - Handles RecordId mismatches
   - Prevents data corruption
   - Improves resilience

### Medium-Term (Alpha → Beta)

4. **Cloud Storage Backend** (CRITICAL for cloud deployment)
   - Implement Azure/S3/GCS support
   - Abstract storage layer
   - Maintain local FileStore for dev/test

5. **MergeScheduler** (IMPORTANT for concurrency)
   - Reduces conflicts
   - Improves performance under load
   - Better transaction ordering

6. **Leader-Standby Optimization** (MODERATE)
   - Reduces resource usage
   - Enables read scaling
   - Improves operational flexibility

### Long-Term (Beta → Production)

7. **Online Migration** (CRITICAL for adoption)
   - Enables zero-downtime migration
   - Reduces adoption friction
   - Preserves versioning history

8. **Compaction API** (MODERATE)
   - Improves operational control
   - Enables emergency GC
   - Better observability

9. **Smart Contract Integration** (DIFFERENTIATOR)
   - Automated debt payment
   - On-chain governance
   - Transparent economics

---

## Conclusion

**oak-segment-consensus** has a solid foundation with Aeron Cluster and demonstrates innovative blockchain integration. However, to reach production parity with **oak-repository-service**, we need:

**Must-Have** (Production Blockers):
1. ✅ Transaction boundaries (START/COMMIT/ABORT)
2. ✅ Cloud storage backend (Azure/S3)
3. ✅ Segment acknowledgment flow

**Should-Have** (Production Features):
4. ✅ Panic/recovery mechanism
5. ✅ MergeScheduler (conflict resolution)
6. ✅ Online migration tooling

**Nice-to-Have** (Operational Excellence):
7. ✅ Leader-standby optimization
8. ✅ Compaction API enhancements
9. ✅ Smart contract integration

**Timeline Estimate**: 12-16 weeks to reach production parity + blockchain differentiators.

**Strategic Recommendation**: Prioritize **cloud storage integration** and **transaction model** before Garage Week demo (Dec 15) if targeting production deployment path. Otherwise, focus on **blockchain differentiators** (GC debt tokenomics, IPFS optimization) to maximize POC value.

---

**Related Documentation**:
- [oak-repository-service README](../../../read-only-learnings/oak-repository-service/README.md)
- [OakRS Transactions](../../../read-only-learnings/oak-repository-service/docs/transactions.md)
- [OakRS Messages](../../../read-only-learnings/oak-repository-service/docs/Messages.md)
- [OakRS MergeScheduler](../../../read-only-learnings/oak-repository-service/docs/MergeScheduler.md)

