# Blockchain AEM - State Machine Diagrams

**Purpose**: Comprehensive state machine documentation for all Blockchain AEM components  
**Date**: January 10, 2026  
**Modules Covered**: oak-segment-consensus, oak-segment-http, oak-blob-cloud-ipfs, oak-auth-web3

---

## Table of Contents

1. [System Overview](#system-overview)
2. [oak-segment-consensus State Machines](#oak-segment-consensus-state-machines)
   - [Validator Role State Machine](#1-validator-role-state-machine)
   - [Write Proposal State Machine](#2-write-proposal-state-machine)
   - [GC Proposal State Machine](#3-gc-proposal-state-machine)
   - [Leadership Claim State Machine](#4-leadership-claim-state-machine)
   - [Aeron Cluster Role State Machine](#5-aeron-cluster-role-state-machine)
3. [oak-segment-http State Machines](#oak-segment-http-state-machines)
   - [HTTP Persistence Service State Machine](#6-http-persistence-service-state-machine)
   - [Write Proposal Flow State Machine](#7-write-proposal-flow-state-machine)
   - [Delete Proposal Flow State Machine](#8-delete-proposal-flow-state-machine)
4. [oak-blob-cloud-ipfs State Machines](#oak-blob-cloud-ipfs-state-machines)
   - [IPFS Backend State Machine](#9-ipfs-backend-state-machine)
   - [Binary Lifecycle State Machine](#10-binary-lifecycle-state-machine)
5. [oak-auth-web3 State Machines](#oak-auth-web3-state-machines)
   - [JAAS Login Module State Machine](#11-jaas-login-module-state-machine)
   - [Biometric Authentication Flow](#12-biometric-authentication-flow)
6. [Cross-Module Integration Flows](#cross-module-integration-flows)
   - [End-to-End Write Flow](#end-to-end-write-flow)
   - [End-to-End Read Flow](#end-to-end-read-flow)

---

## System Overview

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                        BLOCKCHAIN AEM ARCHITECTURE                               │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                  │
│  ┌─────────────────────┐     ┌─────────────────────┐     ┌─────────────────────┐│
│  │   Sling Author      │     │   Sling Author      │     │   Sling Author      ││
│  │   (oak-auth-web3)   │     │   (oak-auth-web3)   │     │   (oak-auth-web3)   ││
│  │   (oak-segment-http)│     │   (oak-segment-http)│     │   (oak-segment-http)││
│  └──────────┬──────────┘     └──────────┬──────────┘     └──────────┬──────────┘│
│             │                           │                           │            │
│             │ HTTP (propose-write,      │                           │            │
│             │ propose-delete, segments) │                           │            │
│             ▼                           ▼                           ▼            │
│  ┌──────────────────────────────────────────────────────────────────────────────┐│
│  │                    VALIDATOR NETWORK (oak-segment-consensus)                 ││
│  │  ┌──────────────┐      ┌──────────────┐      ┌──────────────┐              ││
│  │  │ Validator-0  │◄────►│ Validator-1  │◄────►│ Validator-2  │              ││
│  │  │ (LEADER)     │      │ (FOLLOWER)   │      │ (FOLLOWER)   │              ││
│  │  └──────┬───────┘      └──────┬───────┘      └──────┬───────┘              ││
│  │         │                     │                      │                       ││
│  │         └─────────────────────┼──────────────────────┘                       ││
│  │                               │                                              ││
│  │                    Aeron Cluster (Raft Consensus)                           ││
│  └──────────────────────────────────────────────────────────────────────────────┘│
│                               │                                                  │
│                               ▼                                                  │
│  ┌──────────────────────────────────────────────────────────────────────────────┐│
│  │                    STORAGE LAYER                                             ││
│  │  ┌─────────────────────────┐    ┌─────────────────────────┐                ││
│  │  │  Oak Segment Store      │    │  IPFS Node              │                ││
│  │  │  (TAR files, journal)   │    │  (oak-blob-cloud-ipfs)  │                ││
│  │  │  - Structure/metadata   │    │  - Large binaries       │                ││
│  │  └─────────────────────────┘    └─────────────────────────┘                ││
│  └──────────────────────────────────────────────────────────────────────────────┘│
│                                                                                  │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

## oak-segment-consensus State Machines

### 1. Validator Role State Machine

**Location**: `org.apache.jackrabbit.oak.segment.consensus.leader.ValidatorRole`

The fundamental role a validator plays in the consensus network.

```
                    ┌─────────────────────────────────────────┐
                    │         VALIDATOR ROLE STATE            │
                    └─────────────────────────────────────────┘
                    
                                    ┌─────────┐
                                    │ STARTUP │
                                    └────┬────┘
                                         │
                                         │ Initialize
                                         ▼
                    ┌────────────────────────────────────────┐
                    │                                        │
                    │              ┌──────────┐              │
                    │              │ FOLLOWER │◄─────────────┤
                    │              └────┬─────┘              │
                    │                   │                    │
                    │    Win Election   │   Lose Election    │
                    │    (Raft term)    │   / Step Down      │
                    │                   │                    │
                    │                   ▼                    │
                    │              ┌──────────┐              │
                    │              │  LEADER  │──────────────┘
                    │              └──────────┘
                    │                                        │
                    └────────────────────────────────────────┘

States:
┌──────────┬────────────────────────────────────────────────────────────────┐
│ LEADER   │ Source of truth, accepts writes, sequences all changes.       │
│          │ Only one validator is leader at a time (per Raft term).       │
├──────────┼────────────────────────────────────────────────────────────────┤
│ FOLLOWER │ Replicates state from leader, serves reads from local copy.   │
│          │ All non-leader validators are followers.                      │
└──────────┴────────────────────────────────────────────────────────────────┘

Transitions:
┌─────────────────────┬────────────────────────────────────────────────────┐
│ FOLLOWER → LEADER   │ Win Raft election (majority votes received)        │
├─────────────────────┼────────────────────────────────────────────────────┤
│ LEADER → FOLLOWER   │ Higher term discovered, network partition healed,  │
│                     │ or voluntary step-down                             │
└─────────────────────┴────────────────────────────────────────────────────┘
```

---

### 2. Write Proposal State Machine

**Location**: `org.apache.jackrabbit.oak.segment.consensus.queue.ProposalState`

Tracks the lifecycle of a write proposal from submission to processing.

```
                    ┌─────────────────────────────────────────┐
                    │      WRITE PROPOSAL STATE MACHINE       │
                    └─────────────────────────────────────────┘

                              ┌───────────┐
                              │  CLIENT   │
                              │  SUBMIT   │
                              └─────┬─────┘
                                    │
                                    │ Submit write proposal
                                    │ (wallet signature required)
                                    ▼
                              ┌───────────┐
                              │  PENDING  │
                              └─────┬─────┘
                                    │
                    ┌───────────────┼───────────────┐
                    │               │               │
                    │ Timeout/      │ Ethereum TX   │
                    │ Invalid       │ Confirmed     │
                    │               │               │
                    ▼               ▼               │
              ┌───────────┐  ┌───────────┐         │
              │ REJECTED  │  │ CONFIRMED │         │
              └───────────┘  └─────┬─────┘         │
                                   │               │
                                   │ On-chain      │
                                   │ verification  │
                                   ▼               │
                             ┌───────────┐         │
                             │ VERIFIED  │         │
                             └─────┬─────┘         │
                                   │               │
                                   │ Append to     │
                                   │ Raft log      │
                                   ▼               │
                             ┌───────────┐         │
                             │ PROCESSED │◄────────┘
                             └───────────┘     (Fast path:
                                               Mock mode)

States:
┌───────────┬──────────────────────────────────────────────────────────────┐
│ PENDING   │ Waiting for Ethereum transaction confirmation               │
├───────────┼──────────────────────────────────────────────────────────────┤
│ CONFIRMED │ Ethereum transaction confirmed (mined)                      │
├───────────┼──────────────────────────────────────────────────────────────┤
│ VERIFIED  │ Verified on-chain, ready for Raft append                    │
├───────────┼──────────────────────────────────────────────────────────────┤
│ REJECTED  │ Failed or timed out - rejected                              │
├───────────┼──────────────────────────────────────────────────────────────┤
│ PROCESSED │ Already appended to Raft log                                │
└───────────┴──────────────────────────────────────────────────────────────┘

Note: In mock/POC mode, proposals skip CONFIRMED/VERIFIED and go directly
      from PENDING to PROCESSED.
```

---

### 3. GC Proposal State Machine

**Location**: `org.apache.jackrabbit.oak.segment.consensus.gc.GCProposal.GCProposalState`

Garbage collection proposals require validator consensus before execution.

```
                    ┌─────────────────────────────────────────┐
                    │       GC PROPOSAL STATE MACHINE         │
                    └─────────────────────────────────────────┘

                              ┌───────────┐
                              │  PROPOSE  │
                              │    GC     │
                              └─────┬─────┘
                                    │
                                    │ Create proposal
                                    │ (cost estimate calculated)
                                    ▼
                              ┌───────────┐
                              │  PENDING  │
                              └─────┬─────┘
                                    │
                                    │ First vote received
                                    ▼
                              ┌───────────┐
                              │  VOTING   │
                              └─────┬─────┘
                                    │
                    ┌───────────────┴───────────────┐
                    │                               │
                    │ 2/3+ Approve                  │ 2/3+ Reject
                    │                               │
                    ▼                               ▼
              ┌───────────┐                   ┌───────────┐
              │ APPROVED  │                   │ REJECTED  │
              └─────┬─────┘                   └───────────┘
                    │
                    │ Schedule execution
                    ▼
              ┌───────────┐
              │ EXECUTING │
              └─────┬─────┘
                    │
          ┌────────┴────────┐
          │                 │
          │ Success         │ Failure
          │                 │
          ▼                 ▼
    ┌───────────┐     ┌───────────┐
    │ COMPLETED │     │  FAILED   │
    └───────────┘     └───────────┘

States:
┌───────────┬──────────────────────────────────────────────────────────────┐
│ PENDING   │ Proposal created, waiting for replication                   │
├───────────┼──────────────────────────────────────────────────────────────┤
│ VOTING    │ Validators voting on proposal                               │
├───────────┼──────────────────────────────────────────────────────────────┤
│ APPROVED  │ Quorum reached (2/3+), approved for execution               │
├───────────┼──────────────────────────────────────────────────────────────┤
│ REJECTED  │ Quorum reached (2/3+), rejected                             │
├───────────┼──────────────────────────────────────────────────────────────┤
│ EXECUTING │ GC execution in progress                                    │
├───────────┼──────────────────────────────────────────────────────────────┤
│ COMPLETED │ GC execution completed successfully                         │
├───────────┼──────────────────────────────────────────────────────────────┤
│ FAILED    │ GC execution failed                                         │
└───────────┴──────────────────────────────────────────────────────────────┘

Quorum Calculation:
- quorumSize = (totalValidators * 2 / 3) + 1
- Example: 3 validators → quorum = 3, 5 validators → quorum = 4
```

---

### 4. Leadership Claim State Machine

**Location**: `org.apache.jackrabbit.oak.segment.consensus.leader.LeadershipClaimTracker.ClaimState`

Tracks leadership claims and acknowledgments for quorum-based consensus.

```
                    ┌─────────────────────────────────────────┐
                    │     LEADERSHIP CLAIM STATE MACHINE      │
                    └─────────────────────────────────────────┘

                              ┌───────────┐
                              │  CLAIM    │
                              │  LEADER   │
                              └─────┬─────┘
                                    │
                                    │ Broadcast claim
                                    │ (epoch, claimant URL)
                                    ▼
                              ┌───────────┐
                              │  PENDING  │◄─────────────────┐
                              └─────┬─────┘                  │
                                    │                        │
          ┌─────────────────────────┼────────────────────────┤
          │                         │                        │
          │ Timeout /               │ Quorum ACKs            │ New epoch
          │ Insufficient ACKs       │ received               │ started
          │                         │ (majority)             │
          ▼                         ▼                        │
    ┌───────────┐             ┌───────────┐            ┌───────────┐
    │ REJECTED  │             │ ACCEPTED  │            │SUPERSEDED │
    └───────────┘             └───────────┘            └───────────┘

States:
┌────────────┬─────────────────────────────────────────────────────────────┐
│ PENDING    │ Claim sent, waiting for ACKs from other validators         │
├────────────┼─────────────────────────────────────────────────────────────┤
│ ACCEPTED   │ Quorum reached, leader finalized                           │
├────────────┼─────────────────────────────────────────────────────────────┤
│ REJECTED   │ Timeout or insufficient ACKs                               │
├────────────┼─────────────────────────────────────────────────────────────┤
│ SUPERSEDED │ New epoch started before quorum achieved                   │
└────────────┴─────────────────────────────────────────────────────────────┘

Quorum Calculation:
- requiredQuorum = (electorateSize / 2) + 1  (simple majority)
- Example: 3 validators → quorum = 2, 5 validators → quorum = 3
```

---

### 5. Aeron Cluster Role State Machine

**Location**: `io.aeron.cluster.service.Cluster.Role` (Aeron library)

The underlying Raft consensus roles managed by Aeron Cluster.

```
                    ┌─────────────────────────────────────────┐
                    │     AERON CLUSTER ROLE STATE MACHINE    │
                    └─────────────────────────────────────────┘

                              ┌───────────┐
                              │  STARTUP  │
                              └─────┬─────┘
                                    │
                                    │ Join cluster
                                    ▼
                              ┌───────────┐
                              │ FOLLOWER  │◄─────────────────────────┐
                              └─────┬─────┘                          │
                                    │                                │
                    ┌───────────────┼───────────────┐                │
                    │               │               │                │
                    │ Election      │ Leader        │ Higher term    │
                    │ timeout       │ heartbeat     │ discovered     │
                    │               │ received      │                │
                    ▼               │               │                │
              ┌───────────┐        │               │                │
              │ CANDIDATE │────────┘               │                │
              └─────┬─────┘                        │                │
                    │                              │                │
          ┌────────┴────────┐                     │                │
          │                 │                     │                │
          │ Win election    │ Lose election      │                │
          │ (majority)      │ / Higher term      │                │
          │                 │                     │                │
          ▼                 └─────────────────────┤                │
    ┌───────────┐                                 │                │
    │  LEADER   │─────────────────────────────────┴────────────────┘
    └───────────┘

States:
┌───────────┬──────────────────────────────────────────────────────────────┐
│ FOLLOWER  │ Receives log entries from leader, responds to requests      │
├───────────┼──────────────────────────────────────────────────────────────┤
│ CANDIDATE │ Requesting votes from other nodes for leadership            │
├───────────┼──────────────────────────────────────────────────────────────┤
│ LEADER    │ Handles client requests, replicates log to followers        │
└───────────┴──────────────────────────────────────────────────────────────┘

Raft Guarantees:
- Election Safety: At most one leader per term
- Leader Append-Only: Leader never overwrites/deletes entries
- Log Matching: Same index + term → identical entries
- Leader Completeness: Committed entries appear in future leaders
```

---

## oak-segment-http State Machines

### 6. HTTP Persistence Service State Machine

**Location**: `org.apache.jackrabbit.oak.segment.http.HttpPersistenceService`

Manages the lifecycle of HTTP-based segment persistence for Sling authors.

```
                    ┌─────────────────────────────────────────┐
                    │   HTTP PERSISTENCE SERVICE STATE MACHINE │
                    └─────────────────────────────────────────┘

                              ┌───────────┐
                              │  OSGI     │
                              │ ACTIVATE  │
                              └─────┬─────┘
                                    │
                    ┌───────────────┴───────────────┐
                    │                               │
                    │ lazyMount=false               │ lazyMount=true
                    │ (immediate)                   │ (deferred)
                    ▼                               ▼
              ┌───────────┐                   ┌───────────┐
              │ REGISTER  │                   │  HEALTH   │
              │ SERVICE   │                   │  CHECK    │
              └─────┬─────┘                   │  THREAD   │
                    │                         └─────┬─────┘
                    │                               │
                    │                    ┌──────────┴──────────┐
                    │                    │                     │
                    │                    │ Validator           │ Validator
                    │                    │ unreachable         │ reachable
                    │                    │                     │
                    │                    ▼                     ▼
                    │              ┌───────────┐         ┌───────────┐
                    │              │  WAITING  │────────►│ REGISTER  │
                    │              │  (retry)  │         │ SERVICE   │
                    │              └───────────┘         └─────┬─────┘
                    │                    ▲                     │
                    │                    │                     │
                    │                    └─────────────────────┤
                    │                                          │
                    ▼                                          ▼
              ┌───────────────────────────────────────────────────┐
              │                    ACTIVE                         │
              │  SegmentNodeStorePersistence registered in OSGi   │
              │  Composite mount can now initialize               │
              └───────────────────────────────────────────────────┘
                                    │
                                    │ OSGi deactivate
                                    ▼
                              ┌───────────┐
                              │ SHUTDOWN  │
                              └───────────┘

States:
┌─────────────┬────────────────────────────────────────────────────────────┐
│ ACTIVATE    │ OSGi component activation, read configuration             │
├─────────────┼────────────────────────────────────────────────────────────┤
│ HEALTH CHECK│ Background thread checking validator availability         │
├─────────────┼────────────────────────────────────────────────────────────┤
│ WAITING     │ Validator not reachable, retry after interval             │
├─────────────┼────────────────────────────────────────────────────────────┤
│ REGISTER    │ Register SegmentNodeStorePersistence in OSGi              │
├─────────────┼────────────────────────────────────────────────────────────┤
│ ACTIVE      │ Service registered, serving segment requests              │
├─────────────┼────────────────────────────────────────────────────────────┤
│ SHUTDOWN    │ Unregister service, stop health check thread              │
└─────────────┴────────────────────────────────────────────────────────────┘

Configuration:
- globalStoreUrl: URL of validator (e.g., http://oak-global-store:8090)
- lazyMount: true = defer until validator available, false = immediate
- healthCheckIntervalSeconds: Retry interval (default: 10)
```

---

## oak-blob-cloud-ipfs State Machines

### 9. IPFS Backend State Machine

**Location**: `org.apache.jackrabbit.oak.blob.cloud.ipfs.IPFSBackend`

Manages the lifecycle of the IPFS backend connection.

```
                    ┌─────────────────────────────────────────┐
                    │       IPFS BACKEND STATE MACHINE        │
                    └─────────────────────────────────────────┘

                              ┌───────────┐
                              │  CREATE   │
                              │  BACKEND  │
                              └─────┬─────┘
                                    │
                                    │ new IPFSBackend()
                                    ▼
                              ┌───────────┐
                              │UNINITIALIZED│
                              └─────┬─────┘
                                    │
                                    │ init()
                                    ▼
                              ┌───────────┐
                              │ CONNECTING│
                              │  TO IPFS  │
                              └─────┬─────┘
                                    │
                    ┌───────────────┴───────────────┐
                    │                               │
                    │ Connection failed             │ Connection success
                    │                               │
                    ▼                               ▼
              ┌───────────┐                   ┌───────────┐
              │   ERROR   │                   │   READY   │
              │  (throws) │                   │           │
              └───────────┘                   └─────┬─────┘
                                                    │
                                                    │ Operations:
                                                    │ - write()
                                                    │ - read()
                                                    │ - exists()
                                                    │ - deleteRecord()
                                                    │
                                                    ▼
                                              ┌───────────┐
                                              │ OPERATING │◄────────┐
                                              └─────┬─────┘         │
                                                    │               │
                                                    │ close()       │ Operations
                                                    │               │ continue
                                                    ▼               │
                                              ┌───────────┐         │
                                              │  CLOSED   │         │
                                              └───────────┘         │
                                                                    │
                                              ┌───────────┐         │
                                              │ Operation │─────────┘
                                              │ Complete  │
                                              └───────────┘

States:
┌──────────────┬───────────────────────────────────────────────────────────┐
│ UNINITIALIZED│ Backend created but not connected to IPFS                 │
├──────────────┼───────────────────────────────────────────────────────────┤
│ CONNECTING   │ Establishing connection to IPFS HTTP API                  │
├──────────────┼───────────────────────────────────────────────────────────┤
│ READY        │ Connected and ready for operations                        │
├──────────────┼───────────────────────────────────────────────────────────┤
│ OPERATING    │ Actively processing read/write operations                 │
├──────────────┼───────────────────────────────────────────────────────────┤
│ CLOSED       │ Backend closed, CID cache cleared                         │
└──────────────┴───────────────────────────────────────────────────────────┘
```

---

### 10. Binary Lifecycle State Machine

**Location**: `org.apache.jackrabbit.oak.blob.cloud.ipfs.IPFSBackend`

Tracks the lifecycle of a binary stored in IPFS.

```
                    ┌─────────────────────────────────────────┐
                    │      BINARY LIFECYCLE STATE MACHINE     │
                    └─────────────────────────────────────────┘

                              ┌───────────┐
                              │  UPLOAD   │
                              │  BINARY   │
                              └─────┬─────┘
                                    │
                                    │ write(identifier, file)
                                    ▼
                              ┌───────────┐
                              │  ADDING   │
                              │  TO IPFS  │
                              └─────┬─────┘
                                    │
                                    │ ipfs.add(file)
                                    ▼
                              ┌───────────┐
                              │  PINNING  │
                              │   CID     │
                              └─────┬─────┘
                                    │
                                    │ ipfs.pin.add(cid)
                                    ▼
                              ┌───────────┐
                              │  CACHING  │
                              │  MAPPING  │
                              └─────┬─────┘
                                    │
                                    │ cidCache.put(identifier, cid)
                                    ▼
                              ┌───────────┐
                              │  STORED   │◄────────────────────────┐
                              │ (PINNED)  │                         │
                              └─────┬─────┘                         │
                                    │                               │
                    ┌───────────────┼───────────────┐               │
                    │               │               │               │
                    │ read()        │ deleteRecord()│               │
                    │               │               │               │
                    ▼               ▼               │               │
              ┌───────────┐  ┌───────────┐         │               │
              │ FETCHING  │  │ UNPINNING │         │               │
              │ FROM IPFS │  │   CID     │         │               │
              └─────┬─────┘  └─────┬─────┘         │               │
                    │              │               │               │
                    │              │ ipfs.pin.rm() │               │
                    │              ▼               │               │
                    │        ┌───────────┐         │               │
                    │        │ UNPINNED  │         │               │
                    │        │ (GC-able) │         │               │
                    │        └─────┬─────┘         │               │
                    │              │               │               │
                    │              │ ipfs repo gc  │               │
                    │              ▼               │               │
                    │        ┌───────────┐         │               │
                    │        │  DELETED  │         │               │
                    │        └───────────┘         │               │
                    │                              │               │
                    └──────────────────────────────┴───────────────┘

CID (Content Identifier):
┌────────────────────────────────────────────────────────────────────────────┐
│ IPFS CID = Cryptographic hash of content (immutable, content-addressed)    │
│                                                                            │
│ Example: QmYwAPJzv5CZsnA625s3Xf2nemtYgPpHdWEz79ojWnPbdG                    │
│                                                                            │
│ Properties:                                                                │
│ - Same content → Same CID (automatic deduplication)                        │
│ - CID is derived from content, not assigned                                │
│ - Pinning prevents garbage collection                                      │
└────────────────────────────────────────────────────────────────────────────┘
```

---

## oak-auth-web3 State Machines

### 11. JAAS Login Module State Machine

**Location**: `org.apache.jackrabbit.oak.spi.security.authentication.web3.Web3BiometricLoginModule`

Standard JAAS LoginModule lifecycle with Web3 biometric authentication.

```
                    ┌─────────────────────────────────────────┐
                    │    JAAS LOGIN MODULE STATE MACHINE      │
                    └─────────────────────────────────────────┘

                              ┌───────────┐
                              │ INITIALIZE│
                              │ (JAAS)    │
                              └─────┬─────┘
                                    │
                                    │ Subject, CallbackHandler, options
                                    ▼
                              ┌───────────┐
                              │   READY   │
                              └─────┬─────┘
                                    │
                                    │ login()
                                    ▼
                              ┌───────────┐
                              │  LOGIN    │
                              │  PHASE    │
                              └─────┬─────┘
                                    │
                    ┌───────────────┴───────────────┐
                    │                               │
                    │ Credentials not               │ Credentials
                    │ Web3BiometricCredentials      │ supported
                    │                               │
                    ▼                               ▼
              ┌───────────┐                   ┌───────────┐
              │  IGNORE   │                   │  VERIFY   │
              │ (false)   │                   │ SIGNATURE │
              └───────────┘                   └─────┬─────┘
                                                    │
                                    ┌───────────────┴───────────────┐
                                    │                               │
                                    │ Invalid signature             │ Valid signature
                                    │                               │
                                    ▼                               ▼
                              ┌───────────┐                   ┌───────────┐
                              │  LOGIN    │                   │  LOGIN    │
                              │  FAILED   │                   │ SUCCEEDED │
                              │ (throws)  │                   │ (true)    │
                              └───────────┘                   └─────┬─────┘
                                                                    │
                                                                    │ commit()
                                                                    ▼
                                                              ┌───────────┐
                                                              │  COMMIT   │
                                                              │  PHASE    │
                                                              └─────┬─────┘
                                                                    │
                                    ┌───────────────────────────────┤
                                    │                               │
                                    │ User doesn't exist            │ User exists
                                    │                               │
                                    ▼                               │
                              ┌───────────┐                         │
                              │  CREATE   │                         │
                              │   USER    │                         │
                              └─────┬─────┘                         │
                                    │                               │
                                    └───────────────┬───────────────┘
                                                    │
                                                    │ Add principals to Subject
                                                    ▼
                                              ┌───────────┐
                                              │ COMMITTED │
                                              │  (true)   │
                                              └─────┬─────┘
                                                    │
                                                    │ Session active...
                                                    │
                                                    │ logout()
                                                    ▼
                                              ┌───────────┐
                                              │  LOGOUT   │
                                              │  PHASE    │
                                              └─────┬─────┘
                                                    │
                                                    │ Remove principals
                                                    ▼
                                              ┌───────────┐
                                              │ LOGGED    │
                                              │   OUT     │
                                              └───────────┘

JAAS Phases:
┌──────────┬───────────────────────────────────────────────────────────────┐
│ login()  │ Phase 1: Authenticate user (verify signature)                │
├──────────┼───────────────────────────────────────────────────────────────┤
│ commit() │ Phase 2: Add principals to Subject (if login succeeded)      │
├──────────┼───────────────────────────────────────────────────────────────┤
│ abort()  │ Called if any LoginModule failed (cleanup)                   │
├──────────┼───────────────────────────────────────────────────────────────┤
│ logout() │ Remove principals and credentials from Subject               │
└──────────┴───────────────────────────────────────────────────────────────┘
```

---

### 12. Biometric Authentication Flow

**Location**: `org.apache.jackrabbit.oak.spi.security.authentication.web3.*`

End-to-end biometric authentication flow from browser to Oak session.

```
                    ┌─────────────────────────────────────────┐
                    │    BIOMETRIC AUTHENTICATION FLOW        │
                    └─────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────┐
│                              BROWSER                                        │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌───────────┐     ┌───────────┐     ┌───────────┐     ┌───────────┐      │
│  │  USER     │────►│ WEBAUTHN  │────►│ BIOMETRIC │────►│  P-256    │      │
│  │  LOGIN    │     │  PROMPT   │     │   SCAN    │     │ SIGNATURE │      │
│  └───────────┘     └───────────┘     └───────────┘     └─────┬─────┘      │
│                                                              │             │
│                                                              │ POST        │
│                                                              ▼             │
└─────────────────────────────────────────────────────────────────────────────┘
                                                               │
                                                               │ credentials
                                                               ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                           SLING / AEM                                       │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌───────────┐     ┌───────────┐     ┌───────────┐                        │
│  │  SERVLET  │────►│  CREATE   │────►│ REPOSITORY│                        │
│  │  RECEIVE  │     │  CREDS    │     │  .login() │                        │
│  └───────────┘     └───────────┘     └─────┬─────┘                        │
│                                            │                               │
└────────────────────────────────────────────┼───────────────────────────────┘
                                             │
                                             │ Web3BiometricCredentials
                                             ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                         OAK REPOSITORY                                      │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌───────────┐     ┌───────────┐     ┌───────────┐     ┌───────────┐      │
│  │   JAAS    │────►│ LOGIN     │────►│  VERIFY   │────►│  CREATE   │      │
│  │  CONTEXT  │     │  MODULE   │     │ SIGNATURE │     │ PRINCIPAL │      │
│  └───────────┘     └───────────┘     └───────────┘     └─────┬─────┘      │
│                                                              │             │
│                                                              │             │
│  ┌───────────┐     ┌───────────┐     ┌───────────┐          │             │
│  │  SESSION  │◄────│  COMMIT   │◄────│   ADD     │◄─────────┘             │
│  │  CREATED  │     │  PHASE    │     │ PRINCIPALS│                        │
│  └───────────┘     └───────────┘     └───────────┘                        │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘

Authentication Methods Supported:
┌────────────────────┬─────────────────────────────────────────────────────────┐
│ Biometric (P-256)  │ Face ID, Touch ID, Windows Hello via WebAuthn          │
│                    │ Signature verified locally using JVM EC crypto          │
├────────────────────┼─────────────────────────────────────────────────────────┤
│ MetaMask (secp256k1)│ Ethereum wallet signature via browser extension        │
│                    │ Signature pre-verified by servlet using web3j          │
└────────────────────┴─────────────────────────────────────────────────────────┘

Principal Created:
┌────────────────────────────────────────────────────────────────────────────┐
│ Web3Principal(walletAddress)                                               │
│                                                                            │
│ Example: Web3Principal("0x742d35Cc6634C0532925a3b844Bc9e7595f8fEb")       │
│                                                                            │
│ Used for:                                                                  │
│ - ACL evaluation (rep:principalName = wallet address)                      │
│ - Content ownership (path sharding by wallet)                              │
│ - Audit logging                                                            │
└────────────────────────────────────────────────────────────────────────────┘
```

---

## Cross-Module Integration Flows

### End-to-End Write Flow

Complete flow from Sling author write to consensus commit.

```
                    ┌─────────────────────────────────────────┐
                    │         END-TO-END WRITE FLOW           │
                    └─────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────┐
│ 1. SLING AUTHOR (oak-segment-http, oak-auth-web3)                          │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌───────────┐     ┌───────────┐     ┌───────────┐     ┌───────────┐      │
│  │ BIOMETRIC │────►│  SESSION  │────►│  CREATE   │────►│   SIGN    │      │
│  │   AUTH    │     │  ACTIVE   │     │  CONTENT  │     │ PROPOSAL  │      │
│  └───────────┘     └───────────┘     └───────────┘     └─────┬─────┘      │
│                                                              │             │
│                                                              │ HTTP POST   │
│                                                              ▼             │
└─────────────────────────────────────────────────────────────────────────────┘
                                                               │
                                                               │ /v1/propose-write
                                                               ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ 2. VALIDATOR (oak-segment-consensus) - Any Node                            │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌───────────┐     ┌───────────┐     ┌───────────┐                        │
│  │  RECEIVE  │────►│  VERIFY   │────►│  FORWARD  │                        │
│  │  PROPOSAL │     │ SIGNATURE │     │ TO LEADER │                        │
│  └───────────┘     └───────────┘     └─────┬─────┘                        │
│                                            │                               │
└────────────────────────────────────────────┼───────────────────────────────┘
                                             │
                                             │ Aeron Cluster Ingress
                                             ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ 3. AERON CLUSTER (Raft Consensus)                                          │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌───────────┐     ┌───────────┐     ┌───────────┐     ┌───────────┐      │
│  │  LEADER   │────►│ REPLICATE │────►│  QUORUM   │────►│  COMMIT   │      │
│  │  RECEIVE  │     │ TO FOLLOWERS│   │  REACHED  │     │   LOG     │      │
│  └───────────┘     └───────────┘     └───────────┘     └─────┬─────┘      │
│                                                              │             │
└──────────────────────────────────────────────────────────────┼─────────────┘
                                                               │
                                                               │ All validators
                                                               ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ 4. ALL VALIDATORS - Deterministic State Machine                            │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌───────────┐     ┌───────────┐     ┌───────────┐     ┌───────────┐      │
│  │  APPLY    │────►│  WRITE    │────►│  UPDATE   │────►│  STORE    │      │
│  │  MESSAGE  │     │  SEGMENT  │     │   HEAD    │     │  BINARY   │      │
│  └───────────┘     └───────────┘     └───────────┘     └─────┬─────┘      │
│                                                              │             │
│                                                              │ If binary   │
│                                                              ▼             │
└─────────────────────────────────────────────────────────────────────────────┘
                                                               │
                                                               │ (optional)
                                                               ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ 5. IPFS NODE (oak-blob-cloud-ipfs)                                         │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌───────────┐     ┌───────────┐     ┌───────────┐                        │
│  │   ADD     │────►│   PIN     │────►│ REPLICATE │                        │
│  │  BINARY   │     │   CID     │     │   P2P     │                        │
│  └───────────┘     └───────────┘     └───────────┘                        │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘

Guarantees:
┌────────────────────────────────────────────────────────────────────────────┐
│ ✅ Deterministic: All validators process writes identically                │
│ ✅ Consistent: Same message order = same state                             │
│ ✅ Durable: Committed writes survive validator failures                    │
│ ✅ Authenticated: All writes require valid wallet signature                │
│ ✅ Owned: Content stored under wallet's path shard                         │
└────────────────────────────────────────────────────────────────────────────┘
```

---

### End-to-End Read Flow

Complete flow from Sling author read to segment retrieval.

```
                    ┌─────────────────────────────────────────┐
                    │          END-TO-END READ FLOW           │
                    └─────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────┐
│ 1. SLING AUTHOR (oak-segment-http)                                         │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌───────────┐     ┌───────────┐     ┌───────────┐                        │
│  │   JCR     │────►│ COMPOSITE │────►│   HTTP    │                        │
│  │  getNode()│     │ NODESTORE │     │PERSISTENCE│                        │
│  └───────────┘     └───────────┘     └─────┬─────┘                        │
│                                            │                               │
│                    Local mount             │ /oak-chain mount              │
│                    (read-write)            │ (read-only)                   │
│                                            │                               │
│                                            │ HTTP GET                      │
│                                            ▼                               │
└────────────────────────────────────────────────────────────────────────────┘
                                             │
                                             │ /journal.log, /segments/{id}
                                             ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ 2. VALIDATOR (oak-segment-consensus)                                       │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌───────────┐     ┌───────────┐     ┌───────────┐                        │
│  │  RECEIVE  │────►│   READ    │────►│  RETURN   │                        │
│  │  REQUEST  │     │  SEGMENT  │     │   DATA    │                        │
│  └───────────┘     └───────────┘     └─────┬─────┘                        │
│                                            │                               │
│                    From FileStore          │                               │
│                    (TAR files)             │                               │
│                                            │                               │
└────────────────────────────────────────────┼───────────────────────────────┘
                                             │
                                             │ Segment bytes
                                             ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ 3. SLING AUTHOR - Process Response                                         │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌───────────┐     ┌───────────┐     ┌───────────┐     ┌───────────┐      │
│  │  RECEIVE  │────►│   PARSE   │────►│  RESOLVE  │────►│  RETURN   │      │
│  │  SEGMENT  │     │  SEGMENT  │     │   NODE    │     │   NODE    │      │
│  └───────────┘     └───────────┘     └───────────┘     └───────────┘      │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘

Binary Read (with IPFS):
┌─────────────────────────────────────────────────────────────────────────────┐
│                                                                             │
│  ┌───────────┐     ┌───────────┐     ┌───────────┐     ┌───────────┐      │
│  │   JCR     │────►│  RESOLVE  │────►│   IPFS    │────►│  RETURN   │      │
│  │ getBinary()│    │   CID     │     │   FETCH   │     │  STREAM   │      │
│  └───────────┘     └───────────┘     └───────────┘     └───────────┘      │
│                                                                             │
│                    From segment         From IPFS node                      │
│                    (CID reference)      (local or P2P)                      │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘

Read Characteristics:
┌────────────────────────────────────────────────────────────────────────────┐
│ ✅ Read-only: /oak-chain mount is read-only on Sling authors              │
│ ✅ Consistent: Reads reflect committed consensus state                     │
│ ✅ Cached: Segments cached locally after first fetch                       │
│ ✅ Lazy: Segments fetched on-demand, not eagerly replicated               │
│ ✅ Any validator: Reads can go to any validator (all have same state)     │
└────────────────────────────────────────────────────────────────────────────┘
```

---

## Summary

This document captures the state machines for all major components of the Blockchain AEM project:

| Module | State Machines | Key States |
|--------|---------------|------------|
| **oak-segment-consensus** | 5 | ValidatorRole, ProposalState, GCProposalState, ClaimState, Cluster.Role |
| **oak-segment-http** | 1 | HttpPersistenceService lifecycle |
| **oak-blob-cloud-ipfs** | 2 | IPFSBackend lifecycle, Binary lifecycle |
| **oak-auth-web3** | 2 | JAAS LoginModule phases, Biometric auth flow |

The cross-module integration flows show how these state machines interact to provide:
- **Deterministic consensus** via Aeron Cluster Raft
- **Wallet-based authentication** via Web3 biometrics
- **Content-addressed storage** via IPFS
- **HTTP-based segment transfer** for distributed reads

---

## Related Documentation

- **[GAPS-AND-TESTING-REQUIREMENTS.md](GAPS-AND-TESTING-REQUIREMENTS.md)** - Implementation gaps analysis and testing requirements
- **[CONFIGURATION.md](../oak-segment-consensus/CONFIGURATION.md)** - Environment variables and system properties
- **[DELETE-PROPOSAL-AND-GC-DEEP-DIVE.md](../oak-segment-consensus/DELETE-PROPOSAL-AND-GC-DEEP-DIVE.md)** - Delete and GC mechanisms

---

*Generated: January 10, 2026*  
*Modules: oak-segment-consensus, oak-segment-http, oak-blob-cloud-ipfs, oak-auth-web3*
