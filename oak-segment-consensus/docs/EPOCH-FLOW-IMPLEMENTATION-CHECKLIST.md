# Epoch Flow Implementation Checklist

## Goal
Make epoch flow a first-class control-plane primitive so dashboard views reflect true proposal residency and Aeron load, not inferred aggregates.

## Scope
- Module: `oak-segment-consensus`
- Primary API: `GET /v1/proposals/epochs`
- Contract target: `oak-chain-dashboard-eds/docs/ops-api-contract-v1.md` (`/ops/v1/proposals/epochs`)

## Phase 1: Upstream Endpoint (In Progress)
- [x] Add upstream endpoint route: `/v1/proposals/epochs`
- [x] Expose handler delegation through `ConsensusApiHandler`/`ProposalQueryHandler`
- [x] Return epoch blocks: `Finalized`, `Next to be Finalized`, `Current`
- [x] Include priority lanes (`standard`, `express`, `priority`) with state counts
- [x] Include Aeron load summary in payload
- [x] Track terminal counters by epoch+tier (`finalized`, `rejected`) at transition points
- [ ] Add unit tests for payload shape and state accounting

## Phase 2: First-Class Counter Model
- [ ] Add immutable proposal metadata assertions:
  - `ingestEpoch`
  - `targetFinalityEpoch`
  - `priorityClass`
- [ ] Migrate from mixed derived counters to canonical epoch+priority state ledger
- [ ] Add per-type lanes (`write`, `delete`) inside each priority bucket
- [ ] Persist epoch counters across restarts (storage strategy + recovery path)

## Phase 3: Finality Semantics Validation
- [ ] Validate tier semantics against runtime:
  - `standard` waits 2 epochs
  - `express` waits 1 epoch
  - `priority` fast-path to Aeron
- [ ] Add deterministic tests for epoch advancement and bucket rollover
- [ ] Add regression test for leader rotation continuity of counters

## Phase 4: Dashboard/Adapter Alignment
- [ ] Update edge worker to prefer upstream `/v1/proposals/epochs` over derived fallback
- [ ] Keep fallback only when upstream endpoint unavailable
- [ ] Wire EDS `proposal-epoch-flow` block to upstream contract fields only
- [ ] Add visual validation during heavy load test (`03-heavy-30min.sh`)

## Phase 5: Packing Effectiveness Metrics
- [ ] Add upstream packing metrics:
  - writes buffered per epoch
  - writes released at finalization
  - estimated fanout reduction signal
- [ ] Add explicit caveat labels when values are estimated vs exact

## Exit Criteria
- [ ] Proposal epoch flow remains stable during 30-min heavy load
- [ ] Priority/Express/Standard lanes match expected finality timing
- [ ] Dashboard no longer shows placeholder inferred lane values
- [ ] Tests cover endpoint shape and transition accounting
