# Control Plane Contract Implementation Checklist (ops.v1)

**Status**: Working checklist  
**Updated**: 2026-02-06  
**Source contract**: `control-plane-dashboard-contract.md`

## Purpose

Map `ops.v1` contract requirements to current `oak-segment-consensus` implementation and identify concrete gaps.

## Legend

- `Implemented`: behavior exists and is materially aligned
- `Partial`: behavior exists but schema/semantics differ from `ops.v1`
- `Missing`: no equivalent implementation exists yet

## Endpoint and Handler Mapping

| Contract Area | ops.v1 Requirement | Current Endpoint(s) | Primary Handler/Source | Status | Gap to Close |
|---|---|---|---|---|---|
| Async command acknowledgment | Fast ack with `operationId`, `state=ACCEPTED`, link to operation status | `POST /v1/propose-write`, `POST /v1/propose-delete` | `handlers/WriteProposalHandler.java`, `handlers/DeleteProposalHandler.java` | Partial | `operationId` + `links.self` now present, but ack field is `ackState` (not canonical `state`), and legacy payload fields still dominate |
| Operation status endpoint | `GET /v1/ops/operations/{id}` lifecycle object | `GET /v1/ops/operations/{operationId}` (adapter), `GET /v1/proposals/{proposalId}/status` (legacy) | `handlers/ProposalQueryHandler.java` | Partial | Adapter exists with lifecycle mapping, but command ack still proposal-centric and lifecycle timestamps/queue position remain approximate |
| Lifecycle transitions | Strict state model and idempotency contract | Queue/proposal state exists internally | `consensus/queue/*`, `handlers/ProposalQueryHandler.java` | Partial | Transition model is not documented as `ops.v1` API contract and idempotency key behavior is not exposed |
| SSE endpoint | `GET /v1/ops/events/stream` | `GET /v1/ops/events/stream`, `GET /v1/events/stream` (legacy) | `handlers/EventStreamHandler.java` | Partial | `ops` path now emits `ops.v1` envelope, but event taxonomy is mapped from legacy ADR-036 events (not all native yet) |
| SSE event envelope | `contractVersion`, `eventId`, `eventType`, `sourceNode`, `timestampMs`, `data` | `ops.v1` envelope on ops path; legacy envelope on legacy path | `sse/SSEClient.java` | Partial | Envelope implemented on ops path; still needs stronger typed `data` payload per event domain |
| SSE required event types | role/leader/proposal/queue/backpressure/durability/replication/health events | content/binary/wallet/delete/consensus events | `sse/ContentEvent.java`, `sse/EventBroadcaster.java` | Partial | Add explicit `ops.v1` event taxonomy and mappings |
| SSE resumability | `id` + `Last-Event-ID` support | `id` emitted + `Last-Event-ID` replay on connect | `handlers/EventStreamHandler.java`, `sse/SSEClient.java` | Partial | Resume currently limited to buffered window; durable cursor store not implemented |
| SSE heartbeat | Heartbeat <= 15s | Keep-alive every 15s | `sse/EventBroadcaster.java` | Implemented | Consider making interval OSGi-configurable later |
| Hot-path snapshots | Fast snapshots for cluster/replication/queue/proposals/durability/health | `GET /v1/ops/snapshots/{queue,cluster,replication,health}` + legacy endpoints | `handlers/AeronApiHandler.java`, `handlers/ProposalQueryHandler.java`, `handlers/HealthHandler.java` | Partial | Add proposal/durability ops snapshots and document SLO targets |
| Snapshot freshness metadata | `sourceTimestampMs`, `servedAtMs`, `stalenessMs` | Implemented for `GET /v1/ops/snapshots/{queue,cluster,replication,health}`; ad hoc elsewhere | `handlers/ProposalQueryHandler.java`, `handlers/AeronApiHandler.java`, `handlers/HealthHandler.java`, other handlers | Partial | Extend to proposal/durability snapshot surfaces |
| Degraded semantics | `degraded` + `degradedReason` standard enum | Implemented for queue/cluster/replication/health snapshot fallback; mixed semantics elsewhere | `handlers/ProposalQueryHandler.java`, `handlers/AeronApiHandler.java`, `handlers/HealthHandler.java` | Partial | Normalize degraded semantics across remaining ops snapshot endpoints |
| Contract version tag | `contractVersion=ops.v1` in responses/events | Not present | all handlers | Missing | Add response/event contract version marker |
| CLI parity | JSON CLI parity for critical dashboard state | Not standardized in this doc set | external scripts/tooling | Missing | Define CLI command set and map fields to HTTP schema |

## Current Routing Inventory Relevant to ops.v1

From `RequestRouter`:

- Live events:
  - `GET /v1/ops/events/stream`
  - `GET /v1/events/stream`
  - `GET /v1/events/recent`
  - `GET /v1/events/stats`
- Command endpoints:
  - `POST /v1/propose-write`
  - `POST /v1/propose-delete`
- Proposal/queue read endpoints:
  - `GET /v1/ops/operations/{operationId}`
  - `GET /v1/ops/snapshots/queue`
  - `GET /v1/ops/snapshots/cluster`
  - `GET /v1/ops/snapshots/replication`
  - `GET /v1/ops/snapshots/health`
  - `GET /v1/proposals/{proposalId}/status`
  - `GET /v1/proposals/pending/count`
  - `GET /v1/proposals/queue/stats`
- Cluster/replication:
  - `GET /v1/consensus/status`
  - `GET /v1/aeron/cluster-state`
  - `GET /v1/aeron/raft-metrics`
  - `GET /v1/aeron/leadership-history`
  - `GET /v1/aeron/replication-lag`
  - `GET /v1/aeron/node-status`
- Health:
  - `GET /health`
  - `GET /health/deep`
  - `GET /health/cluster`

## Priority Work Plan

1. Create `ops.v1` adapter endpoints and keep existing endpoints backward-compatible.
2. Add operation resource:
   - `POST /v1/ops/commands/*` returns `operationId`.
   - `GET /v1/ops/operations/{operationId}` returns canonical lifecycle object.
3. Add `ops.v1` SSE endpoint:
   - `GET /v1/ops/events/stream` with contract envelope and required event types.
4. Add freshness and degraded metadata fields to all dashboard-facing snapshots.
5. Harden `Last-Event-ID` resume beyond in-memory buffer window (durable cursor strategy).
6. Publish CLI parity matrix for incident workflows.

## Suggested Ticket Breakdown

- `OPS-API-01`: operation lifecycle schema + operation status endpoint
- `OPS-API-02`: command ack schema (`Accepted` + `operationId`)
- `OPS-SSE-01`: `ops.v1` event envelope and taxonomy
- `OPS-SSE-02`: stream resumability (`Last-Event-ID`) + heartbeat tuning
- `OPS-SNAPSHOT-01`: freshness/degraded metadata normalization
- `OPS-CLI-01`: CLI parity output spec and mapping

## References

- `jackrabbit-oak/oak-segment-consensus/docs/api/control-plane-dashboard-contract.md`
- `jackrabbit-oak/oak-segment-consensus/src/main/java/org/apache/jackrabbit/oak/segment/http/server/RequestRouter.java`
- `jackrabbit-oak/oak-segment-consensus/src/main/java/org/apache/jackrabbit/oak/segment/http/server/handlers/WriteProposalHandler.java`
- `jackrabbit-oak/oak-segment-consensus/src/main/java/org/apache/jackrabbit/oak/segment/http/server/handlers/DeleteProposalHandler.java`
- `jackrabbit-oak/oak-segment-consensus/src/main/java/org/apache/jackrabbit/oak/segment/http/server/handlers/ProposalQueryHandler.java`
- `jackrabbit-oak/oak-segment-consensus/src/main/java/org/apache/jackrabbit/oak/segment/http/server/handlers/EventStreamHandler.java`
- `jackrabbit-oak/oak-segment-consensus/src/main/java/org/apache/jackrabbit/oak/segment/http/server/sse/ContentEvent.java`
- `jackrabbit-oak/oak-segment-consensus/src/main/java/org/apache/jackrabbit/oak/segment/http/server/sse/EventBroadcaster.java`
