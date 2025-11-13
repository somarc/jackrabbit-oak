# EpochLeaderEngine Rename Summary

**Date**: November 12, 2025  
**Status**: ✅ **COMPLETE**

---

## Changes Made

### 1. Renamed Class
- **Old**: `LeaderConsensusEngine`
- **New**: `EpochLeaderEngine`
- **Location**: `oak-segment-consensus/src/main/java/org/apache/jackrabbit/oak/segment/consensus/leader/EpochLeaderEngine.java`

### 2. Updated All References
Updated references in:
- ✅ `ServerContext.java` - Field renamed to `epochLeaderEngine`
- ✅ `SegmentHttpServer.java` - Method renamed to `setEpochLeaderEngine()`
- ✅ `RequestRouter.java` - Updated handler initialization
- ✅ `GlobalStoreServer.java` - Updated instantiation and method calls
- ✅ `LeaderConsensusHandler.java` - Updated all references
- ✅ `RegistrationHandler.java` - Updated all references
- ✅ `PeerDiscoveryHandler.java` - Updated all references
- ✅ `ConsensusApiHandler.java` - Updated all references
- ✅ `DashboardHandler.java` - Updated all references
- ✅ `MetricsHandler.java` - Updated constructor and field
- ✅ `HealthHandler.java` - Updated constructor and field

### 3. Deprecated ConsensusEngine
- ✅ Added `@Deprecated` annotation
- ✅ Added deprecation Javadoc explaining replacement
- ✅ Documented migration path to `EpochLeaderEngine`

---

## Epoch Definition

### Current Implementation
- **Source**: System time-based
- **Formula**: `epoch = floor(current_time_seconds / leaderTermSeconds)`
- **Duration**: Configurable (default 300s = 5 min, testing 60s = 1 min)

### Future Enhancement: Ethereum Epoch Integration
- **Source**: Ethereum Beacon Chain epochs
- **Duration**: 384 seconds (~6.4 minutes per Ethereum epoch)
- **Benefits**:
  - Solves clock skew (Ethereum is authoritative)
  - Aligns with blockchain consensus
  - Meaningful epochs (tied to real blockchain activity)

---

## Architecture Vision

**All write events to the global oak-chain will be driven by actual Ethereum transactions that get read by the bridge.**

This makes Oak a true blockchain-backed content repository where:
1. Ethereum transactions trigger writes
2. Bridge reads and validates transactions
3. EpochLeaderEngine sequences writes via leader consensus
4. Followers replicate state
5. Sling authors see content via HTTP segment transfer

See: `Blockchain-AEM/01-current-spec/ETHEREUM-DRIVEN-WRITES.md`

---

## Verification

- ✅ Compilation successful
- ✅ All references updated
- ✅ No remaining `LeaderConsensusEngine` references in Java code
- ✅ ConsensusEngine deprecated with clear migration path

---

## Next Steps

1. **Ethereum Epoch Integration** (Future)
   - Modify `LeaderElection.getCurrentEpoch()` to use Ethereum epochs
   - Add configuration: `consensus.epoch.source=ethereum|system`
   - Integrate `BeaconChainClient` for epoch source

2. **Transaction Event Listener** (Future)
   - Create `TransactionListener` (similar to `EpochListener`)
   - Parse Ethereum transaction events
   - Generate write proposals from transactions

3. **Smart Contract Integration** (Future)
   - Read from OakNetwork.sol
   - Parse contract events
   - Execute contract logic

