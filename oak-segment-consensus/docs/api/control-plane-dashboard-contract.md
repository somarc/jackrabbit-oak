# Control Plane Dashboard Contract (API/CLI-First)

**Status**: Draft for ADR 063/064 implementation  
**Updated**: 2026-02-06

## Purpose

Define the minimum control-plane contract required for an external dashboard so UI can be fully derived from API data rather than server-rendered logic in `oak-segment-consensus`.

## Canonical Transaction Lifecycle

States:
- `STARTED`
- `COMMITTED`
- `ABORTED`
- `TIMED_OUT`

Transitions:
- `STARTED -> COMMITTED`
- `STARTED -> ABORTED`
- `STARTED -> TIMED_OUT`

Replay/idempotency rules:
- duplicate `COMMIT` for already committed tx is idempotent
- duplicate `ABORT` for already aborted/timed_out tx is idempotent
- replay `START` for terminal tx is invalid

## Required Entities

### Transaction Record

Fields:
- `transactionId`
- `correlationId`
- `initiatorWallet`
- `status`
- `startedAtMs`
- `timeoutMs`
- `deadlineMs`
- `completedAtMs`
- `abortReason`

### Transaction Stats

Fields:
- `active`
- `terminal`
- `committed`
- `aborted`
- `timedOut`

### Cluster and Consensus Health

Minimum fields:
- `clusterHealthy`
- `role`
- `leader`
- `term`
- `reachableMembers`
- `quorumRequired`

### Durability Surface

Minimum fields:
- durability queue depth
- ack progress summary
- last durability failure reason (if any)

## API Requirements

1. Every dashboard value must come from a documented endpoint response.
2. Response payloads must be stable across patch releases.
3. Error responses must be structured and machine-readable.
4. Partial failure states must be represented (degraded/unhealthy) without HTML parsing.

## CLI Requirements

1. All critical dashboard states must be obtainable via CLI-readable JSON output.
2. CLI and HTTP should expose equivalent core fields for incident workflows.

## Non-Goals

- This document does not mandate a frontend implementation.
- This document does not define auth/tenant policy details.

## Related

- `Blockchain-AEM/adr/063-dashboard-extraction-api-first-control-plane.md`
- `Blockchain-AEM/adr/064-dashboard-extraction-rollout-and-cutover.md`
