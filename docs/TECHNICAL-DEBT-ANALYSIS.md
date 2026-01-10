# Blockchain AEM - Technical Debt Analysis

**Purpose**: Identify dead code, deprecated patterns, and evolutionary artifacts for cleanup  
**Date**: January 10, 2026  
**Status**: Active cleanup recommended  
**Related**: [GAPS-AND-TESTING-REQUIREMENTS.md](GAPS-AND-TESTING-REQUIREMENTS.md)

---

## Table of Contents

1. [Executive Summary](#executive-summary)
2. [Evolutionary Context](#evolutionary-context)
3. [Dead Code Candidates](#dead-code-candidates)
4. [Deprecated Code (Keep but Mark)](#deprecated-code-keep-but-mark)
5. [Commented-Out Code](#commented-out-code)
6. [Duplicate/Redundant Code](#duplicateredundant-code)
7. [Cleanup Recommendations](#cleanup-recommendations)
8. [Migration Checklist](#migration-checklist)

---

## Executive Summary

The Blockchain AEM project has evolved through several architectural phases:

1. **Phase 1**: Custom P2P gossip protocol (`p2p/` package)
2. **Phase 2**: Epoch-based leader election (`EpochLeaderEngine`)
3. **Phase 3**: Aeron Cluster Raft consensus (`AeronConsensusEngine`) ← **Current**

This evolution left behind code from earlier phases that is no longer used but still present in the codebase.

### Quick Stats

| Category | Count | Action |
|----------|-------|--------|
| Dead code files | 7 | Remove |
| Deprecated methods | 12 | Mark/Remove |
| Commented-out blocks | 3 | Remove |
| Unused classes | 4 | Remove |
| Test artifacts | 1 | Remove |

---

## Evolutionary Context

### Architecture Timeline

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    BLOCKCHAIN AEM EVOLUTION                                 │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  PHASE 1: P2P Gossip (Abandoned)                                           │
│  ├── p2p/SegmentGossip.java                                                │
│  ├── p2p/PeerDiscovery.java                                                │
│  ├── p2p/Peer.java                                                         │
│  └── p2p/impl/*.java                                                       │
│      Status: ❌ NOT USED - Aeron handles replication                       │
│                                                                             │
│  PHASE 2: Epoch Leader Engine (Partially Used)                             │
│  ├── leader/EpochLeaderEngine.java                                         │
│  ├── leader/LeaderElection.java                                            │
│  ├── leader/LeaderHealthMonitor.java                                       │
│  └── Vote.java                                                             │
│      Status: ⚠️ LEGACY - Only used if Aeron disabled                       │
│                                                                             │
│  PHASE 3: Aeron Cluster (Current)                                          │
│  ├── aeron/AeronConsensusEngine.java                                       │
│  ├── aeron/AeronClusterLauncher.java                                       │
│  ├── aeron/MessageDispatcher.java                                          │
│  └── aeron/SnapshotService.java                                            │
│      Status: ✅ ACTIVE - Production consensus                              │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Dead Code Candidates

### Category 1: P2P Package (Entire Package Unused)

**Location**: `oak-segment-consensus/src/main/java/org/apache/jackrabbit/oak/segment/consensus/p2p/`

| File | Lines | Status | Recommendation |
|------|-------|--------|----------------|
| `Peer.java` | ~50 | ❌ Unused | **DELETE** |
| `PeerDiscovery.java` | ~30 | ❌ Unused | **DELETE** |
| `SegmentGossip.java` | ~40 | ❌ Unused | **DELETE** |
| `impl/SimplePeer.java` | ~80 | ❌ Unused | **DELETE** |
| `impl/SimpleSegmentGossip.java` | ~150 | ❌ Unused | **DELETE** |
| `impl/StaticPeerDiscovery.java` | ~100 | ❌ Unused | **DELETE** |

**Evidence**: 
- No imports from `p2p` package in any active code
- Only referenced by test file `SegmentGossipTest.java`
- Aeron Cluster handles all segment replication

**Action**: Delete entire `p2p/` directory and `SegmentGossipTest.java`

---

### Category 2: Vote.java (Unused Class)

**Location**: `oak-segment-consensus/src/main/java/org/apache/jackrabbit/oak/segment/consensus/Vote.java`

| File | Lines | Status | Recommendation |
|------|-------|--------|----------------|
| `Vote.java` | 114 | ❌ Unused | **DELETE** |

**Evidence**:
- Zero imports of `Vote` class anywhere
- `GCVote` is used instead for GC proposals
- Write proposals don't use voting (Aeron Raft handles consensus)

**Action**: Delete `Vote.java`

---

### Category 3: Test Artifacts

**Location**: `oak-segment-consensus/src/test/java/.../BlockchainAemPocTest.java.old`

| File | Status | Recommendation |
|------|--------|----------------|
| `BlockchainAemPocTest.java.old` | ❌ Abandoned | **DELETE** |

**Action**: Delete `.old` file

---

## Deprecated Code (Keep but Mark)

### Category 1: EpochLeaderEngine (Legacy Fallback)

**Location**: `oak-segment-consensus/src/main/java/org/apache/jackrabbit/oak/segment/consensus/leader/EpochLeaderEngine.java`

**Status**: ⚠️ Still referenced but not actively used

**References** (33 occurrences across 12 files):
- `ServerContext.java` - holds reference
- `DashboardDataService.java` - queries state
- `RegistrationHandler.java` - heartbeat endpoint
- `HealthHandler.java` - health checks
- `MetricsHandler.java` - metrics

**Recommendation**: 
1. Add `@Deprecated` annotation to class
2. Add deprecation notice to JavaDoc
3. Consider removal in next major version

```java
/**
 * @deprecated Use {@link AeronConsensusEngine} instead. This class is kept
 *             for backward compatibility but will be removed in a future version.
 */
@Deprecated
public class EpochLeaderEngine {
```

---

### Category 2: Deprecated API Endpoints

**Location**: Various handlers

| Endpoint | Handler | Status | Recommendation |
|----------|---------|--------|----------------|
| `GET /v1/head` | ConsensusApiHandler | Deprecated | Keep (backward compat) |
| `POST /v1/heartbeat` | RegistrationHandler | Deprecated | Remove (Aeron handles) |

---

### Category 3: Deprecated Methods in WalletPathUtil

**Location**: `oak-segment-consensus/src/main/java/org/apache/jackrabbit/oak/segment/consensus/util/WalletPathUtil.java`

| Method | Lines | Status | Recommendation |
|--------|-------|--------|----------------|
| `getShardPrefix()` | 63-72 | `@Deprecated` | Keep (used internally) |
| `toShardedPath()` | 207-220 | `@Deprecated` | Keep (backward compat) |
| `extractWalletFromPath()` | 272-285 | `@Deprecated` | Keep (backward compat) |
| `getBucketPath()` | 287-295 | `@Deprecated` | Keep (backward compat) |
| `getContentRoot()` | 362-369 | `@Deprecated` | Keep (backward compat) |

**Action**: Already properly marked, no change needed

---

### Category 4: Deprecated Methods in BeaconChainClient

**Location**: `oak-segment-consensus/src/main/java/org/apache/jackrabbit/oak/segment/consensus/eth/BeaconChainClient.java`

| Method | Line | Status | Recommendation |
|--------|------|--------|----------------|
| `getFinalizedEpoch()` | 488-490 | `@Deprecated` | Keep (used by some callers) |

**Action**: Already properly marked, no change needed

---

## Commented-Out Code

### Block 1: broadcastHeadToFollowersOLD

**Location**: `AeronConsensusEngine.java:2860-2964`

```java
/* ✅ ADR 025: Removed broadcastHeadToFollowers implementation
   Old implementation commented out below for reference (can be deleted)
   
private void broadcastHeadToFollowersOLD(String newHeadStr) {
    // ... 100+ lines of commented code ...
}
*/
```

**Recommendation**: **DELETE** - ADR 025 documents the decision, code is not needed

---

### Block 2: Old Inline Explorer UI

**Location**: `DashboardHandler.java:614`

```java
/**
 * DEPRECATED: Old inline explorer UI (kept for reference).
 */
```

**Recommendation**: Review and delete if not used

---

### Block 3: Old Inline API Browser UI

**Location**: `DashboardHandler.java:1028`

```java
/**
 * DEPRECATED: Old inline API browser UI (kept for reference).
 */
```

**Recommendation**: Review and delete if not used

---

### Block 4: Old Inline Chat UI

**Location**: `DashboardHandler.java:1371`

```java
/**
 * DEPRECATED: Old inline chat UI (kept for reference).
 */
```

**Recommendation**: Review and delete if not used

---

## Duplicate/Redundant Code

### Issue 1: Two Consensus Engines

**Files**:
- `EpochLeaderEngine.java` (1400+ lines)
- `AeronConsensusEngine.java` (4400+ lines)

**Problem**: Both implement consensus but only Aeron is used

**Recommendation**: 
1. Mark `EpochLeaderEngine` as deprecated
2. Remove in next major version
3. Clean up references in handlers

---

### Issue 2: Vote vs GCVote

**Files**:
- `Vote.java` - Generic vote class (unused)
- `gc/GCVote.java` - GC-specific vote class (used)

**Problem**: `Vote.java` was designed for write proposal voting but Aeron handles that

**Recommendation**: Delete `Vote.java`

---

### Issue 3: Multiple Leader Discovery Mechanisms

**Files**:
- `LeaderDiscoveryService.java` - Aeron-based discovery
- `EpochLeaderEngine.java` - Epoch-based election
- `LeaderElection.java` - Deterministic election algorithm

**Problem**: Multiple overlapping mechanisms

**Recommendation**: 
1. Keep `LeaderDiscoveryService.java` (Aeron)
2. Deprecate `LeaderElection.java` (only used by EpochLeaderEngine)

---

## Cleanup Recommendations

### Immediate Actions (Safe to Do Now)

```bash
# 1. Delete dead P2P package
rm -rf oak-segment-consensus/src/main/java/org/apache/jackrabbit/oak/segment/consensus/p2p/
rm oak-segment-consensus/src/test/java/org/apache/jackrabbit/oak/segment/consensus/p2p/SegmentGossipTest.java

# 2. Delete unused Vote class
rm oak-segment-consensus/src/main/java/org/apache/jackrabbit/oak/segment/consensus/Vote.java

# 3. Delete test artifact
rm oak-segment-consensus/src/test/java/org/apache/jackrabbit/oak/segment/consensus/BlockchainAemPocTest.java.old

# 4. Delete commented-out code in AeronConsensusEngine.java
# (Manual edit - remove lines 2860-2964)
```

### Short-Term Actions (Requires Testing)

1. **Add deprecation annotations**:
   ```java
   // EpochLeaderEngine.java
   @Deprecated
   public class EpochLeaderEngine { ... }
   
   // LeaderElection.java
   @Deprecated
   public class LeaderElection { ... }
   
   // LeaderHealthMonitor.java
   @Deprecated
   public class LeaderHealthMonitor { ... }
   ```

2. **Remove deprecated dashboard UI methods** (after verifying not used)

3. **Clean up handler references** to EpochLeaderEngine

### Long-Term Actions (Next Major Version)

1. Remove `EpochLeaderEngine` and related classes
2. Remove `LeaderElection.java`
3. Remove `LeaderHealthMonitor.java`
4. Remove `/v1/heartbeat` endpoint
5. Simplify `ServerContext` to only hold Aeron engine

---

## Migration Checklist

### Before Deleting P2P Package

- [ ] Verify no imports in production code
- [ ] Delete `SegmentGossipTest.java`
- [ ] Run full test suite
- [ ] Update any documentation references

### Before Deprecating EpochLeaderEngine

- [ ] Verify Aeron is always used in production
- [ ] Add deprecation annotations
- [ ] Update JavaDoc
- [ ] Add migration notes to README

### Before Removing Deprecated Code

- [ ] Ensure no external dependencies
- [ ] Update CHANGELOG
- [ ] Bump major version
- [ ] Update documentation

---

## Appendix: File-by-File Analysis

### Files to DELETE (7 files, ~600 lines)

| File | Lines | Reason |
|------|-------|--------|
| `p2p/Peer.java` | ~50 | Unused interface |
| `p2p/PeerDiscovery.java` | ~30 | Unused interface |
| `p2p/SegmentGossip.java` | ~40 | Unused interface |
| `p2p/impl/SimplePeer.java` | ~80 | Unused implementation |
| `p2p/impl/SimpleSegmentGossip.java` | ~150 | Unused implementation |
| `p2p/impl/StaticPeerDiscovery.java` | ~100 | Unused implementation |
| `Vote.java` | 114 | Unused class |
| `BlockchainAemPocTest.java.old` | ~200 | Test artifact |

### Files to DEPRECATE (4 files, ~2000 lines)

| File | Lines | Reason |
|------|-------|--------|
| `EpochLeaderEngine.java` | ~1400 | Legacy consensus |
| `LeaderElection.java` | ~300 | Only used by EpochLeaderEngine |
| `LeaderHealthMonitor.java` | ~200 | Only used by EpochLeaderEngine |
| `LeadershipClaimTracker.java` | ~185 | Only used by EpochLeaderEngine |

### Files to CLEAN (commented code removal)

| File | Lines to Remove | Reason |
|------|-----------------|--------|
| `AeronConsensusEngine.java` | 2860-2964 | Commented-out old implementation |
| `DashboardHandler.java` | Various | Old inline UI methods |

---

## Summary

**Total Dead Code**: ~800 lines (safe to delete now)  
**Total Deprecated Code**: ~2000 lines (mark now, delete later)  
**Total Commented Code**: ~150 lines (delete now)

**Estimated Cleanup Impact**: 
- Reduces codebase by ~3000 lines
- Simplifies architecture understanding
- Removes confusion about which consensus is active
- Makes testing more focused

---

*Generated: January 10, 2026*  
*Next Review: After cleanup implementation*
