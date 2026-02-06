# Consensus & Proposals API

**Endpoints for write/delete proposals and consensus status**

---

## POST /v1/propose-write

Propose a write transaction. Requires wallet signature and Ethereum payment verification.

### Request

**Content-Type**: `application/x-www-form-urlencoded` or `multipart/form-data`

**Parameters**:
- `walletAddress` (required) - Ethereum wallet address (0x...)
- `signature` (required) - Signed message (walletAddress:timestamp:contentType:message)
- `message` (optional) - Content to write (JSON string or text; canonical fields: title, body, tags, meta, payload)
- `contentType` (optional) - Content type (default: "page")
- `ethereumTxHash` (required) - Ethereum transaction hash for payment
- `paymentTier` (optional) - Payment tier: `STANDARD`, `EXPRESS`, `PRIORITY` (default: STANDARD)
- `organization` (optional) - Organization name (ADR 037)
- `ipfsCid` (optional) - IPFS CID for binary content (ADR 016, client-side default)
- `intentToken` (optional) - Lazy binary upload token (ADR 020)
- `binaryData` (optional) - Legacy base64 binary payload (validator-hosted, requires PRIORITY)
- `mimeType` (optional) - MIME type for legacy base64

**Multipart Form Data** (for binary uploads):
- `file` - Binary file (validator-hosted, requires paymentTier=PRIORITY)
- Other parameters as form fields

### Response

**202 Accepted** (proposal queued)
```json
{
  "contractVersion": "ops.v1",
  "status": "accepted",
  "operationId": "uuid-123",
  "receivedAtMs": 1733421234000,
  "ackState": "ACCEPTED",
  "links": {
    "self": "/v1/ops/operations/uuid-123"
  },
  "proposalId": "uuid-123",
  "state": "PENDING",
  "message": "Proposal queued, waiting for Ethereum confirmation",
  "ethereumTxHash": "0xabcd...",
  "timeoutTimestamp": 1733421234000,
  "wallet": "0xdd870fa1b7c4700f2bd7f44238821c26f7392148",
  "storagePath": "/oak-chain/dd/87/0f/0xdd870fa1b7c4700f2bd7f44238821c26f7392148/content/page-1733421234000",
  "contentType": "page"
}
```

**200 OK** (immediate mode fallback)
```json
{
  "success": true,
  "proposalId": "uuid-123",
  "wallet": "0xdd870fa1b7c4700f2bd7f44238821c26f7392148",
  "contentId": "page-1733421234000",
  "storagePath": "/oak-chain/dd/87/0f/0xdd870fa1b7c4700f2bd7f44238821c26f7392148/content/page-1733421234000",
  "newHead": "abc123...",
  "message": "Hello World",
  "contentType": "page",
  "mode": "immediate"
}
```

**400 Bad Request** (validation error)
```json
{
  "error": "Invalid wallet address format",
  "code": "VALIDATION_ERROR"
}
```

**503 Service Unavailable** (cluster unhealthy)
```json
{
  "error": "Cluster unhealthy: No leader elected. Please retry in a few seconds.",
  "code": "CLUSTER_UNHEALTHY"
}
```

### Example

```bash
curl -X POST http://localhost:8090/v1/propose-write \
  -d "walletAddress=0xdd870fa1b7c4700f2bd7f44238821c26f7392148" \
  -d "signature=0x1a2b3c..." \
  -d "message={\"title\":\"Hello World\"}" \
  -d "ethereumTxHash=0xabcd..." \
  -d "paymentTier=EXPRESS"
```

---

## POST /v1/propose-delete

Propose a delete transaction. Requires wallet signature and path ownership verification.

### Request

**Parameters**:
- `walletAddress` (required) - Ethereum wallet address
- `signature` (required) - Signed message
- `contentPath` (required) - Path to delete (must belong to wallet)
- `ethereumTxHash` (required) - Ethereum transaction hash

### Response

**202 Accepted**
```json
{
  "contractVersion": "ops.v1",
  "status": "accepted",
  "operationId": "uuid-456",
  "receivedAtMs": 1733421234000,
  "ackState": "ACCEPTED",
  "links": {
    "self": "/v1/ops/operations/uuid-456"
  },
  "proposalId": "uuid-456",
  "type": "DELETE",
  "state": "PENDING",
  "message": "Delete proposal queued, waiting for Ethereum confirmation",
  "ethereumTxHash": "0xabcd...",
  "tier": "STANDARD",
  "timeoutTimestamp": 1733421234000,
  "wallet": "0xdd870fa1b7c4700f2bd7f44238821c26f7392148",
  "contentPath": "/oak-chain/dd/87/0f/0xdd870fa1b7c4700f2bd7f44238821c26f7392148/content/page1",
  "gcDebtIncurred": "0.10",
  "totalDebt": "5.40",
  "pendingDebt": "0.10",
  "writesBlocked": false
}
```

**403 Forbidden** (path ownership violation)
```json
{
  "error": "Path ownership violation: Content at /oak-chain/... does not belong to wallet 0x...",
  "code": "PATH_OWNERSHIP_VIOLATION"
}
```

**402 Payment Required** (GC debt blocks writes/deletes)
```json
{
  "success": false,
  "error": "Writes blocked due to unpaid GC debt. Please pay debt to resume.",
  "code": "write_blocked_gc_debt",
  "status": 402,
  "timestamp": 1733421234000,
  "wallet": "0xdd870fa1b7c4700f2bd7f44238821c26f7392148",
  "totalDebt": "5.40",
  "pendingDebt": "0.10",
  "debtLimit": "5.00",
  "paymentUrl": "/v1/gc/account/0xdd870fa1b7c4700f2bd7f44238821c26f7392148/pay"
}
```

### Example

```bash
curl -X POST http://localhost:8090/v1/propose-delete \
  -d "walletAddress=0xdd870fa1b7c4700f2bd7f44238821c26f7392148" \
  -d "signature=0x1a2b3c..." \
  -d "contentPath=/oak-chain/dd/87/0f/0xdd870fa1b7c4700f2bd7f44238821c26f7392148/content/page1" \
  -d "ethereumTxHash=0xabcd..."
```

---

## GET /v1/consensus/status

Get current consensus status and cluster state.

### Response

```json
{
  "consensusMode": "aeron",
  "role": "LEADER",
  "clusterHealthy": true,
  "leaderUrl": "http://localhost:8090",
  "clusterSize": 3,
  "connectedPeers": 2,
  "currentTerm": 5,
  "head": "abc123...",
  "lastHeartbeat": 1733421234000
}
```

### Example

```bash
curl http://localhost:8090/v1/consensus/status
```

---

## GET /v1/proposals/{id}/status

Get status of a specific proposal.

### Response

```json
{
  "proposalId": "uuid-123",
  "state": "VERIFIED",
  "ethereumTxHash": "0xabcd...",
  "timeoutTimestamp": 1733421234000,
  "confirmedBlock": 12345678,
  "rejectionReason": null,
  "durabilityState": "ACKED",
  "durabilityTimestamp": 1733421240000,
  "durabilityError": null,
  "durableHead": "abc123..."
}
```

**States**: `PENDING` → `VERIFIED` → `COMMITTED` or `REJECTED` (terminal)

### Example

```bash
curl http://localhost:8090/v1/proposals/uuid-123/status
```

---

## GET /v1/ops/operations/{id}

Get `ops.v1` operation status (adapter over proposal status).

### Response

```json
{
  "contractVersion": "ops.v1",
  "timestampMs": 1733421245000,
  "operationId": "uuid-123",
  "proposalId": "uuid-123",
  "state": "PROCESSING",
  "type": "WRITE_PROPOSAL",
  "correlationId": "uuid-123",
  "queue": {
    "name": "proposal-queue",
    "position": null
  },
  "startedAtMs": null,
  "updatedAtMs": 1733421245000,
  "completedAtMs": null,
  "deadlineMs": 1733421534000,
  "sourceState": "VERIFIED",
  "durabilityState": "PENDING",
  "durabilityTimestamp": 1733421240000,
  "durabilityError": null,
  "rejectionReason": null,
  "ethereumTxHash": "0xabcd...",
  "confirmedBlock": 12345678,
  "error": null
}
```

Notes:
- This is currently an adapter endpoint; `operationId` maps to existing `proposalId`.
- Lifecycle state is derived from proposal + durability states.

### Example

```bash
curl http://localhost:8090/v1/ops/operations/uuid-123
```

---

## GET /v1/ops/events/stream

Open `ops.v1` SSE stream for live control-plane updates.

### Headers

- `Last-Event-ID` (optional) - resume from recent in-memory buffer window

### SSE payload shape

Each event uses `ops.v1` envelope:

```json
{
  "contractVersion": "ops.v1",
  "eventId": "1733421245000",
  "eventType": "proposal.state.changed",
  "sourceNode": "http://localhost:8090",
  "timestampMs": 1733421245000,
  "data": {
    "legacyType": "content",
    "legacyAction": "write",
    "path": "/oak-chain/.../content/page-1733421234000",
    "wallet": "0xdd870fa1b7c4700f2bd7f44238821c26f7392148"
  }
}
```

Notes:
- Event taxonomy is currently mapped from existing ADR-036 events.
- Legacy stream remains available at `GET /v1/events/stream`.

### Example

```bash
curl -N http://localhost:8090/v1/ops/events/stream
```

---

## GET /v1/ops/snapshots/queue

Get queue snapshot with `ops.v1` freshness/degraded metadata and short TTL cache.

### Response

```json
{
  "contractVersion": "ops.v1",
  "sourceTimestampMs": 1733421245000,
  "servedAtMs": 1733421245100,
  "stalenessMs": 100,
  "degraded": false,
  "degradedReason": null,
  "cache": {
    "hit": true,
    "ttlMs": 1000
  },
  "data": {
    "pendingCount": 42,
    "mempoolDepth": 10746
  }
}
```

Notes:
- On upstream computation failure, endpoint may return stale cached data with:
  - `degraded=true`
  - `degradedReason=STALE_CACHE_FALLBACK`

### Example

```bash
curl http://localhost:8090/v1/ops/snapshots/queue
```

---

## GET /v1/ops/snapshots/cluster

Get cluster snapshot with `ops.v1` freshness/degraded metadata and short TTL cache.

### Response

```json
{
  "contractVersion": "ops.v1",
  "sourceTimestampMs": 1733421245000,
  "servedAtMs": 1733421245100,
  "stalenessMs": 100,
  "degraded": false,
  "degradedReason": null,
  "cache": {
    "hit": true,
    "ttlMs": 1000
  },
  "data": {
    "role": "LEADER",
    "clusterMemberCount": 3,
    "reachableCount": 3
  }
}
```

### Example

```bash
curl http://localhost:8090/v1/ops/snapshots/cluster
```

---

## GET /v1/ops/snapshots/replication

Get replication-lag snapshot with `ops.v1` freshness/degraded metadata and short TTL cache.

### Response

```json
{
  "contractVersion": "ops.v1",
  "sourceTimestampMs": 1733421245000,
  "servedAtMs": 1733421245100,
  "stalenessMs": 100,
  "degraded": false,
  "degradedReason": null,
  "cache": {
    "hit": true,
    "ttlMs": 1000
  },
  "data": {
    "role": "FOLLOWER",
    "replicationLag": 5,
    "healthy": true
  }
}
```

### Example

```bash
curl http://localhost:8090/v1/ops/snapshots/replication
```

---

## GET /v1/ops/snapshots/health

Get lightweight health snapshot with `ops.v1` freshness/degraded metadata and short TTL cache.

### Response

```json
{
  "contractVersion": "ops.v1",
  "sourceTimestampMs": 1733421245000,
  "servedAtMs": 1733421245100,
  "stalenessMs": 100,
  "degraded": false,
  "degradedReason": null,
  "cache": {
    "hit": true,
    "ttlMs": 1000
  },
  "data": {
    "status": "UP",
    "clusterHealthy": true,
    "blobStoreType": "ipfs",
    "blobStoreActive": true,
    "reachableCount": 3,
    "totalMembers": 3,
    "quorumSize": 2,
    "currentRole": "LEADER"
  }
}
```

### Example

```bash
curl http://localhost:8090/v1/ops/snapshots/health
```

---

## GET /v1/proposals/pending/count

Get count of pending proposals.

### Response

```json
{
  "pendingCount": 5
}
```

---

*See [DELETE-QUICK-REFERENCE.md](../../DELETE-QUICK-REFERENCE.md) for detailed delete proposal flow.*
