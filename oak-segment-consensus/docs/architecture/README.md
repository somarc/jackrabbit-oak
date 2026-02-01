# Architecture Overview

**How oak-segment-consensus works: Aeron Raft consensus, deterministic state machine, proposal flow**

---

## Core Concepts

### Deterministic State Machine

All validators process writes identically via Aeron's guaranteed message ordering:

```
Client → Validator → Aeron Ingress → Leader → Raft Replication → All Nodes
                                                                    ↓
                                                          Deterministic Apply
                                                                    ↓
                                                          Identical State
```

**Key Property**: Same message order = same processing = same SegmentStore state

### Aeron Cluster Raft

- **Consensus Algorithm**: Raft (via Aeron Cluster)
- **Latency**: ~100μs for replication
- **Leader Election**: Automatic, < 5 seconds failover
- **Quorum**: Majority-based (2 of 3, 3 of 5, etc.)

### Proposal Flow

1. **API Validation** (< 10ms) - Wallet, signature, path ownership
2. **Ethereum Verification** (1-15s) - Payment confirmation
3. **Epoch Batching** (0-32min) - Batched by payment tier
4. **Aeron Replication** (< 100ms) - Raft consensus
5. **Deterministic Apply** (< 50ms) - All nodes execute identically

---

## Component Architecture

### [Aeron Consensus Engine](aeron-consensus.md)
Core Raft implementation, message replication, leader election.

### [Proposal Queue](proposal-queue.md)
Epoch-based batching, payment tier handling, queue management.

### [HTTP Server](http-server.md)
Jetty server, request routing, API handlers.

### [Ethereum Integration](ethereum-integration.md)
Payment verification, wallet authentication, Beacon Chain epochs.

### [Garbage Collection](garbage-collection.md)
GC proposals, debt tracking, compaction.

---

## Data Flow

### Write Proposal Flow

```
┌─────────────┐
│   Client    │
└──────┬──────┘
       │ POST /v1/propose-write
       ▼
┌─────────────────┐
│ ConsensusApi    │ Validate wallet, signature, path
│ Handler         │
└──────┬──────────┘
       │ Queue proposal
       ▼
┌─────────────────┐
│ ProposalQueue   │ Batch by epoch, verify payment
│ Manager         │
└──────┬──────────┘
       │ Send via Aeron
       ▼
┌─────────────────┐
│ AeronConsensus  │ Replicate via Raft
│ Engine          │
└──────┬──────────┘
       │ onSessionMessage()
       ▼
┌─────────────────┐
│ WriteApplication│ Apply to Oak NodeStore
│ Service         │
└──────┬──────────┘
       │
       ▼
   All Nodes Have
   Identical State
```

### Delete Proposal Flow

```
┌─────────────┐
│   Client    │
└──────┬──────┘
       │ POST /v1/propose-delete
       ▼
┌─────────────────┐
│ ConsensusApi    │ Verify path ownership
│ Handler         │
└──────┬──────────┘
       │ Calculate GC debt
       ▼
┌─────────────────┐
│ GCAccountManager│ Track debt per wallet
└──────┬──────────┘
       │ Queue delete proposal
       ▼
┌─────────────────┐
│ AeronConsensus  │ Replicate via Raft
│ Engine          │
└──────┬──────────┘
       │ onSessionMessage()
       ▼
┌─────────────────┐
│ DeleteApplication│ Remove from Oak
│ Service         │
└──────┬──────────┘
       │
       ▼
   Content Deleted
   GC Debt Tracked
```

---

## Key Design Decisions

### Why Aeron Cluster?

- ✅ **Production-grade**: Used by Coinbase, LMAX
- ✅ **Low latency**: ~100μs replication
- ✅ **Proven**: Battle-tested Raft implementation
- ✅ **Focus**: We focus on Ethereum integration, not consensus

### Why Deterministic State Machine?

- ✅ **Guaranteed consistency**: No eventual consistency window
- ✅ **Simpler**: No manual HEAD broadcasting needed
- ✅ **Performance**: No HTTP sync overhead

### Why Epoch Batching?

- ✅ **Cost efficiency**: Batch multiple proposals per Ethereum epoch
- ✅ **Payment tiers**: STANDARD (2 epochs), EXPRESS (1 epoch), PRIORITY (immediate)
- ✅ **Throughput**: Higher throughput for lower-cost tiers

---

## Package Structure

See [Package Map](../../../Blockchain-AEM/implementation/reference/oak-segment-consensus-PACKAGE-MAP.md) for complete package structure.

**Key Packages**:
- `consensus/aeron/` - Aeron Cluster Raft implementation
- `consensus/queue/` - Proposal queue management
- `http/server/` - HTTP server and handlers
- `consensus/security/` - Wallet signature verification
- `consensus/gc/` - Garbage collection consensus

---

*For detailed implementation docs, see Blockchain-AEM repository.*
