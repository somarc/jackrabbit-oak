# Startup Architecture Cleanup

**Status**: Active cleanup task  
**Date**: 2026-03-20  
**Primary module**: `oak-segment-consensus`  
**Primary file**: `oak-segment-consensus/src/main/java/org/apache/jackrabbit/oak/segment/consensus/server/GlobalStoreServer.java`

## Objective

Reduce validator startup to the minimum required substrate for:

1. booting an Oak-backed validator node
2. forming an Aeron cluster
3. creating the `0x000...000` genesis write as the first replicated write
4. exposing that genesis hierarchy as normal JCR content for later consumers such as EDS

The cleanup goal is not to remove future economics or blockchain features. It is
to stop treating those features as part of the core boot contract.

## Evidence Summary

Clean three-node startup logs from `2026-03-20` show that normal cluster
formation is already Aeron-first:

- follower nodes detect reachable peers, explicitly skip standby bootstrap, and
  start Aeron directly
- the elected leader creates genesis through Aeron consensus
- follower nodes receive the same genesis proposal and commit it locally

Relevant evidence:

- `/Users/mhess/oak-chain/logs/validator-1.log:17`
- `/Users/mhess/oak-chain/logs/validator-2.log:17`
- `/Users/mhess/oak-chain/logs/validator-0.log:220`
- `/Users/mhess/oak-chain/logs/validator-0.log:322`
- `/Users/mhess/oak-chain/logs/validator-1.log:306`
- `/Users/mhess/oak-chain/logs/validator-2.log:305`

Relevant code:

- `oak-segment-consensus/src/main/java/org/apache/jackrabbit/oak/segment/consensus/aeron/AeronClusterLauncher.java:115`
- `oak-segment-consensus/src/main/java/org/apache/jackrabbit/oak/segment/consensus/aeron/AeronConsensusEngine.java:3037`

## Current Architectural Truth

The real substrate is:

- FileStore and NodeStore
- minimal HTTP surface
- Aeron cluster formation and replay
- genesis creation through Aeron consensus

The clean startup logs do not support the older assumption that normal startup
must bootstrap Oak content separately before Aeron can safely converge.

## Cleanup Tasks

### P0. Collapse normal startup to a single Aeron-first path

Normal startup should not branch into a separate `STANDBY` runtime mode when
the validator is simply joining a healthy cluster. The clean logs show this path
is already bypassed during normal startup.

Target result:

- one normal startup path for empty and non-empty nodes
- Aeron handles cluster formation, replay, and genesis replication
- startup flow reads as `store -> http -> aeron -> genesis/services`

### P0. Demote standby bootstrap from startup mode to recovery tool

`STANDBY` currently remains as a large branch in `GlobalStoreServer`, but clean
startup did not use it.

Target result:

- standby sync is not part of the normal startup story
- if retained, it is exposed as an explicit repair or recovery path
- startup logs no longer describe standby as a normal cluster-formation step

Before deletion, prove Aeron-only recovery for:

- late joiner with empty local store
- restarted node with existing state
- wiped node rejoining a healthy cluster

### P0. Remove duplicate Beacon Chain polling

`BeaconChainClient` is initialized both by `AeronConsensusEngine` and by
`ConsensusServicesInitializer`.

Evidence:

- `oak-segment-consensus/src/main/java/org/apache/jackrabbit/oak/segment/consensus/aeron/AeronConsensusEngine.java:565`
- `oak-segment-consensus/src/main/java/org/apache/jackrabbit/oak/segment/consensus/server/ConsensusServicesInitializer.java:97`
- duplicate startup blocks in `/Users/mhess/oak-chain/logs/validator-0.log:148`
- second startup block in `/Users/mhess/oak-chain/logs/validator-0.log:247`
- duplicate epoch advancement in `/Users/mhess/oak-chain/logs/validator-0.log:351`

Target result:

- one epoch source of truth per validator process
- one background poller
- clear ownership of epoch state

### P1. Split boot core from optional economics services

The current boot sequence initializes several optional subsystems before the
cluster is even formed:

- GC cost estimator
- CID mapping service
- fragmentation tracker
- wallet storage metrics
- GC proposal manager
- GC account manager
- periodic GC job
- EVM bridge
- proposal queue
- validator earnings tracker

Relevant code:

- `oak-segment-consensus/src/main/java/org/apache/jackrabbit/oak/segment/consensus/server/GlobalStoreServer.java:453`
- `oak-segment-consensus/src/main/java/org/apache/jackrabbit/oak/segment/consensus/server/GlobalStoreServer.java:521`
- `oak-segment-consensus/src/main/java/org/apache/jackrabbit/oak/segment/consensus/server/GlobalStoreServer.java:557`
- `oak-segment-consensus/src/main/java/org/apache/jackrabbit/oak/segment/consensus/server/ConsensusServicesInitializer.java:85`

Target result:

- boot core starts the validator and creates consensus-backed genesis
- optional services start after cluster formation or behind explicit feature flags
- startup failures in optional systems do not define substrate health

### P1. Stop starting StandbyServerSync on every normal boot

`StandbyServerSync` currently starts even when the validator already joined via
Aeron-first startup.

Relevant code:

- `oak-segment-consensus/src/main/java/org/apache/jackrabbit/oak/segment/consensus/server/GlobalStoreServer.java:1007`

Evidence:

- `/Users/mhess/oak-chain/logs/validator-0.log:285`
- `/Users/mhess/oak-chain/logs/validator-1.log:281`
- `/Users/mhess/oak-chain/logs/validator-2.log:283`

Target result:

- standby serving is opt-in or recovery-oriented
- normal startup is not described as a Cold Standby system

### P1. Clean stale startup messaging

Startup logs still carry pre-Aeron assumptions and not-yet-implemented feature
noise.

Examples:

- node `0` says it "becomes genesis" early in boot even though genesis is later
  created by the elected leader via Aeron consensus
- `Smart Contract Listener: NOT IMPLEMENTED` is printed on every startup

Evidence:

- `/Users/mhess/oak-chain/logs/validator-0.log:17`
- `/Users/mhess/oak-chain/logs/validator-0.log:110`
- `/Users/mhess/oak-chain/logs/validator-0.log:289`

Target result:

- startup output reflects the actual Aeron-first contract
- unsupported or deferred features move to diagnostics or docs instead of noisy
  boot banners

## Do Not Regress

These points appear architecturally sound and should remain true through the
cleanup:

- genesis is the first replicated consensus write, not a pre-consensus local
  write
- the `0x000...000` hierarchy is native JCR content
- follower nodes converge by applying the same replicated genesis command
- the startup contract favors Aeron as the source of truth for cluster state

## Recommended Refactor Shape

Refactor `GlobalStoreServer` into three startup tiers:

1. `Boot Core`
   - wallet
   - blob store if required
   - FileStore and NodeStore
   - HTTP bootstrap
   - Aeron startup
   - genesis and replay handling
2. `Optional Economics`
   - queue
   - EVM bridge
   - beacon integration
   - earnings
   - GC economics
   - fragmentation and storage accounting
3. `Recovery Tools`
   - standby bootstrap
   - standby serving

## Follow-up Validation

Before deleting recovery-oriented standby code, capture runtime evidence for:

1. empty late joiner while the cluster is healthy
2. restart with existing local state
3. wiped node rejoining a healthy cluster

If Aeron-only startup succeeds in all three cases, remove standby startup mode
instead of merely deprecating it.
