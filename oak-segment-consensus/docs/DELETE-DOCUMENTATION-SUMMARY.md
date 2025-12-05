# Delete Proposal & Revision Cleanup - Documentation Summary

**Created**: December 4, 2025  
**Purpose**: Navigation guide for delete proposal and GC documentation

## 📚 Documentation Set

I've created a comprehensive documentation set covering the delete proposal flow and revision cleanup mechanisms in `oak-segment-consensus`:

### 1. **DELETE-PROPOSAL-AND-GC-DEEP-DIVE.md** (Main Document)
**Location**: `jackrabbit-oak/oak-segment-consensus/DELETE-PROPOSAL-AND-GC-DEEP-DIVE.md`

**Contents**:
- Complete delete proposal lifecycle (7 phases)
- GC debt tracking and Account Tax Model
- Revision cleanup mechanisms (Periodic GC + Actual Cleanup)
- Implementation deep dive with code references
- Security & ownership model (path sharding)
- Economics & tokenomics ($0.10/MB cost model)
- Edge cases & failure modes
- Performance characteristics

**Target Audience**: Engineers needing comprehensive understanding of delete/GC architecture

---

### 2. **DELETE-QUICK-REFERENCE.md** (Developer Guide)
**Location**: `jackrabbit-oak/oak-segment-consensus/DELETE-QUICK-REFERENCE.md`

**Contents**:
- Key files and line numbers
- API endpoint examples
- Testing scenarios with curl commands
- Debugging tips
- Performance benchmarks
- Code style guidelines
- Development workflow

**Target Audience**: Developers actively working on delete/GC features

---

### 3. **docs/delete-flow-diagram.md** (Visual Reference)
**Location**: `jackrabbit-oak/oak-segment-consensus/docs/delete-flow-diagram.md`

**Contents**:
- Complete end-to-end flow diagram
- Phase-by-phase breakdowns with ASCII art
- GC debt state machine
- Segment lifecycle visualization
- Key insights summary

**Target Audience**: Visual learners, architecture reviews, onboarding

---

## 🎯 Quick Navigation

### I want to understand...

| Goal | Document | Section |
|------|----------|---------|
| **How deletes work end-to-end** | `DELETE-PROPOSAL-AND-GC-DEEP-DIVE.md` | [Delete Proposal Flow](#delete-proposal-flow) |
| **How GC debt is tracked** | `DELETE-PROPOSAL-AND-GC-DEEP-DIVE.md` | [GC Debt Tracking](#gc-debt-tracking) |
| **How segments are cleaned up** | `DELETE-PROPOSAL-AND-GC-DEEP-DIVE.md` | [Revision Cleanup Mechanisms](#revision-cleanup-mechanisms) |
| **Visual flow diagrams** | `docs/delete-flow-diagram.md` | All sections |
| **API endpoints to call** | `DELETE-QUICK-REFERENCE.md` | [API Endpoints](#api-endpoints) |
| **How to test deletes** | `DELETE-QUICK-REFERENCE.md` | [Testing Scenarios](#testing-scenarios) |
| **How to debug issues** | `DELETE-QUICK-REFERENCE.md` | [Debugging Tips](#debugging-tips) |
| **Which files to modify** | `DELETE-QUICK-REFERENCE.md` | [Key Files](#key-files) |
| **Code examples** | `DELETE-PROPOSAL-AND-GC-DEEP-DIVE.md` | [Implementation Deep Dive](#implementation-deep-dive) |

---

## 🔑 Key Concepts at a Glance

### Delete Proposal Flow (7 Phases)

```
1. API Entry (< 10ms)
   └─► Validate wallet, path ownership, client registration
   
2. Ethereum Verification (1-15s)
   └─► EvmBridge confirms payment on Sepolia testnet
   
3. Epoch Batching (0-32min)
   └─► Batch by payment tier (PRIORITY/EXPRESS/STANDARD)
   
4. Aeron Replication (< 100ms)
   └─► Raft consensus, all validators receive message
   
5. Deterministic Application (< 50ms)
   └─► All validators execute same Oak operations
   
6. Periodic GC (every 5min)
   └─► Convert pending debt → executed debt
   
7. Actual Cleanup (manual)
   └─► Oak FileStore.cleanup() reclaims segments
```

### GC Debt Model

```
Delete 1MB → $0.10 pending debt
    ↓
Periodic GC (5 min) → pending → executed debt
    ↓
If executedDebt >= $100 → BLOCKED
    ↓
Payment → executedDebt -= amount → UNBLOCKED
```

### Two-Phase Deletion

| Phase | Duration | Result |
|-------|----------|--------|
| **Logical Delete** | < 50ms | Content removed from tree, user sees immediate deletion |
| **Physical Cleanup** | Minutes/hours | Segments reclaimed from disk, space freed |

**Key Insight**: Immediate UX, deferred cost amortization.

---

## 📊 Architecture Highlights

### Deterministic State Machine
✅ **All validators execute SAME operations in SAME order**
- Aeron Cluster guarantees message ordering (Raft)
- Oak commits are deterministic → same HEAD on all nodes
- No manual HEAD broadcasting needed (natural consequence)

### Security Model
✅ **Path sharding enforcement**
- Wallet `0xdd870fa1b7c4700f2bd7f44238821c26f7392148`
- Shard root: `/oak-chain/dd/87/0f/0xdd870fa1b7c4700f2bd7f44238821c26f7392148/`
- Can only delete content under their shard root

✅ **Ethereum payment required**
- All deletes require on-chain payment (like writes)
- Payment tiers: PRIORITY (3x), EXPRESS (2x), STANDARD (1x)

### Economic Incentives
✅ **GC Account Tax Model**
- Entities pay for storage footprint (writes) AND cleanup burden (deletes)
- Debt becomes "due" after periodic GC
- Writes blocked when executed debt > limit ($100 default)
- Payment clears debt and unblocks writes

---

## 🧪 Testing Quick Start

### Test Delete Proposal
```bash
curl -X POST http://localhost:8090/v1/propose-delete \
  -d "walletAddress=0xdd870fa1b7c4700f2bd7f44238821c26f7392148" \
  -d "signature=0x..." \
  -d "contentPath=/oak-chain/dd/87/0f/0xdd870fa1b7c4700f2bd7f44238821c26f7392148/content/page1" \
  -d "ethereumTxHash=0xabcd..."
```

### Check GC Debt
```bash
curl http://localhost:8090/v1/gc/account?wallet=0xdd870fa1b7c4700f2bd7f44238821c26f7392148
```

### Trigger GC Proposal
```bash
curl -X POST http://localhost:8090/v1/gc/propose \
  -d "proposerWallet=0xdd870fa1b7c4700f2bd7f44238821c26f7392148" \
  -d "targetRevision=HEAD"
```

**Full testing scenarios**: See `DELETE-QUICK-REFERENCE.md` → Testing Scenarios

---

## 🐛 Common Issues

### Issue: Delete proposal accepted but content not removed
**Check**:
1. Proposal state: `GET /v1/proposal/{proposalId}` → should be `CONFIRMED`
2. Path ownership: Ensure path starts with wallet's shard root
3. Aeron logs: `grep "DELETE_PROPOSAL" /var/log/oak-validator.log`

**See**: `DELETE-QUICK-REFERENCE.md` → Debugging Tips

---

### Issue: GC debt not tracked
**Check**:
1. GCAccountManager initialized: `grep "GCAccountManager" /var/log/oak-validator.log`
2. Size estimation: Check if sizeMB > 0
3. Account state: `GET /v1/gc/account?wallet=...`

**See**: `DELETE-QUICK-REFERENCE.md` → Debugging Tips

---

### Issue: Writes not blocked despite high debt
**Check**:
1. Periodic GC running: `GET /v1/gc/periodic-job/stats`
2. Debt conversion: `grep "Converted pending debt" /var/log/oak-validator.log`
3. Account state: `shouldBlockWrites()` logic

**See**: `DELETE-QUICK-REFERENCE.md` → Debugging Tips

---

## 🔧 Code References

| Component | File | Lines | What It Does |
|-----------|------|-------|--------------|
| **API Entry** | `ConsensusApiHandler.java` | 537-748 | Handles `POST /v1/propose-delete` |
| **GC Debt** | `GCAccountManager.java` | 71-81 | Adds debt when content deleted |
| **Delete Application** | `ConsensusApiHandler.java` | 1099-1161 | Applies replicated delete to Oak |
| **Periodic GC** | `PeriodicGCJob.java` | 146-237 | Converts pending → executed debt |
| **GC Cleanup** | `GCProposalManager.java` | 253-325 | Executes FileStore.cleanup() |
| **Cost Estimation** | `GCCostEstimator.java` | 161-193 | BFS segment graph traversal |

**Full code references**: See `DELETE-QUICK-REFERENCE.md` → Key Files

---

## 📈 Performance Characteristics

| Operation | Latency | Notes |
|-----------|---------|-------|
| **Delete API validation** | < 10ms | In-memory checks |
| **Ethereum verification** | 1-15s | External blockchain query |
| **Aeron replication** | < 100ms | UDP multicast, Raft |
| **Oak commit** | < 50ms | FileStore merge + flush |
| **GC cost estimation** | 10-15s | BFS traverse 4M segments |
| **FileStore.cleanup()** | 30-120s | Depends on TAR count |

**Full benchmarks**: See `DELETE-QUICK-REFERENCE.md` → Performance Benchmarks

---

## 🚀 Future Enhancements

### 1. Actual Size Calculation
**Current**: Heuristic (1MB per content item)  
**Future**: Calculate from Oak NodeStore (sum binary property sizes + recursive children)

### 2. Smart Contract Integration
**Current**: Manual payment recording via API  
**Future**: Listen for on-chain payment events from `GCDebtManager` contract

### 3. Wallet-Specific GC
**Current**: Full repository GC affects all wallets  
**Future**: GC only segments owned by specific wallet (incremental cleanup)

### 4. Automatic GC Scheduling
**Current**: Manual GC proposals  
**Future**: Auto-trigger when fragmentation > 30% or disk space low

**Details**: See `DELETE-PROPOSAL-AND-GC-DEEP-DIVE.md` → Future Enhancements

---

## 📝 Documentation Status

| Document | Status | Last Updated |
|----------|--------|--------------|
| `DELETE-PROPOSAL-AND-GC-DEEP-DIVE.md` | ✅ Complete | Dec 4, 2025 |
| `DELETE-QUICK-REFERENCE.md` | ✅ Complete | Dec 4, 2025 |
| `docs/delete-flow-diagram.md` | ✅ Complete | Dec 4, 2025 |
| ADR 017 - GC Account Tax Model | ✅ Complete | (existing) |
| ADR 018 - GC Proposal Complexity | ✅ Complete | (existing) |

---

## 🔗 Related Documentation

### In Blockchain-AEM Repository
- [ADR 017 - GC Account Tax Model](../../Blockchain-AEM/adr/017-gc-account-tax-model.md)
- [ADR 018 - GC Proposal Complexity Analysis](../../Blockchain-AEM/adr/018-gc-proposal-formal-complexity.md)
- [AERON-CLUSTER-STRATEGY.md](../../Blockchain-AEM/02-architecture/AERON-CLUSTER-STRATEGY.md)
- [FRAGMENTATION-TRACKING-DESIGN.md](../../Blockchain-AEM/02-architecture/FRAGMENTATION-TRACKING-DESIGN.md)

### In oak-segment-consensus
- [CONFIGURATION.md](CONFIGURATION.md) - Environment variables
- [IPFS-DATASTORE.md](IPFS-DATASTORE.md) - Binary storage (ADR 015)
- [README.md](README.md) - Module overview

---

## 🎓 Learning Path

### For New Engineers
1. Start with `docs/delete-flow-diagram.md` (visual overview)
2. Read `DELETE-PROPOSAL-AND-GC-DEEP-DIVE.md` sections 1-3 (overview + flow)
3. Try testing scenarios from `DELETE-QUICK-REFERENCE.md`
4. Deep dive into specific phases in main document

### For Contributors
1. Use `DELETE-QUICK-REFERENCE.md` for file references
2. Follow code style guidelines
3. Add tests for new features
4. Update documentation when changing behavior

### For Architects
1. Read `DELETE-PROPOSAL-AND-GC-DEEP-DIVE.md` sections 4-6 (implementation + security + economics)
2. Review ADR 017 and ADR 018 for rationale
3. Consider future enhancements for production

---

## ✅ Summary

This documentation set provides:

✅ **Comprehensive coverage** of delete proposal flow (7 phases)  
✅ **Deep dive** into GC debt tracking and cleanup mechanisms  
✅ **Visual diagrams** for architecture understanding  
✅ **Practical guidance** for testing and debugging  
✅ **Code references** with line numbers  
✅ **Performance benchmarks** and optimization opportunities  

**Total Documentation**: ~15,000 words across 3 documents

**Maintenance**: These documents are living documentation. Update them when:
- Adding new delete/GC features
- Changing API behavior
- Discovering new edge cases
- Implementing future enhancements

---

**Questions?** Check:
1. `DELETE-QUICK-REFERENCE.md` → Debugging Tips
2. `DELETE-PROPOSAL-AND-GC-DEEP-DIVE.md` → Edge Cases & Failure Modes
3. GitHub Issues / Slack: `#blockchain-aem`

**Feedback?** Submit PR or create issue with tag `documentation`.

---

**Created by**: Claude (AI Assistant)  
**Reviewed by**: Oak Segment Consensus Team  
**Status**: 🧪 POC / Active Development  
**Next Review**: After Garage Week (Dec 15+)

