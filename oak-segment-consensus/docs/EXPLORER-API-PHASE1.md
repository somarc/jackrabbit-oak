# Explorer API Phase 1 (`explorer.v1`)

Purpose: provide a stable, API-first surface for an external blockscan/etherscan-style Oak Chain explorer.

Base URL:
- Validator direct: `http://<validator-host>:<port>`

Contract version marker:
- Responses include `contractVersion: "explorer.v1"`

## Endpoints

### `GET /v1/explorer/summary`

High-level cluster + queue + identity snapshot.

Includes:
- `cluster`: role/leader/term/epoch/reachability
- `queue`: raw queue stats snapshot from proposal queue manager
- `identities`: validator wallet, cluster wallet, registration counts

### `GET /v1/explorer/proposals/{proposalId}`

Proposal-focused detail by proposal ID.

Includes:
- lifecycle state (`state`)
- `ethereumTxHash`
- `confirmedBlock`
- durability fields (`durabilityState`, `durableHead`, timestamps/errors)
- rejection/timeout metadata

### `GET /v1/explorer/wallets/{walletAddress}`

Wallet-focused explorer record.

Includes:
- wallet path metadata (`walletPath`, content/write counters when present)
- recent content entries (bounded list)
- optional `gcAccount` when GC account manager is available

### `GET /v1/explorer/epochs`

Epoch flow snapshot for proposal progression.

Includes:
- `epochs`: direct payload from `getProposalEpochFlowStats()`

## Related Canonical APIs (still supported)

- `/v1/consensus/status`
- `/v1/proposals/queue/stats`
- `/v1/proposals/epochs`
- `/v1/aeron/cluster-state`
- `/v1/aeron/raft-metrics`
- `/v1/aeron/replication-lag`

## Notes

- Phase 1 focuses on read-only explorer APIs.
- Pagination/filtering/search endpoints (wallet activity windows, proposal range scans, block-like feeds) are targeted for Phase 2.
