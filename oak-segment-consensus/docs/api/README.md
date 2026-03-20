# API Reference

**Complete HTTP API documentation for oak-segment-consensus**

---

## Base URL

```
http://localhost:8090
```

Default port: `8090` (configurable via `PORT` environment variable)

---

## Authentication

### Token-Based (Optional)

If `OAK_VALIDATOR_AUTH_TOKEN` is configured:

```bash
curl -H "Authorization: YOUR_TOKEN" http://localhost:8090/v1/consensus/status
```

**Note**: Health endpoints (`/health`, `/health/deep`) are always public.
The validator currently expects the raw token value in the `Authorization`
header, not a `Bearer` prefix.

---

## API Categories

### [Consensus & Proposals](consensus.md)
Write/delete proposals, consensus status, proposal management.

### [Aeron Cluster](aeron.md)
Aeron Cluster state, leadership, Raft metrics.

### [Garbage Collection](gc.md)
GC proposals, cost estimation, account management.

### [Content Operations](content.md)
Read content, explore node tree, binary operations.

### [Health & Metrics](health.md)
Health checks, Prometheus metrics, performance monitoring.

### [Control Plane Contract](control-plane-dashboard-contract.md)
API/CLI-first `ops.v1` contract for external dashboard derivation, including async operation lifecycle and SSE event schema (ADR 063/064/066/067).

### [Control Plane Checklist](control-plane-dashboard-contract-checklist.md)
Implementation mapping of `ops.v1` contract requirements to current handlers/endpoints with `implemented/partial/missing` status.

### [OSGi Config Introspection](osgi-config.md)
Read-only operator surface for effective config, schema, source provenance,
coverage, and default drift.

### [Segment Transfer](segments.md)
Journal, manifest, segment fetching (for Sling authors).

### [Registration & Discovery](registration.md)
Client registration, peer discovery, validator info.

**Binary upload note**: Validator-hosted IPFS is the default and recommended path. Client-side `ipfsCid` is restricted to registered `enterprise` clients and must map to a validator-known CID.

---

## Interactive API Browser

Access the interactive API browser at:
```
http://localhost:8090/api-browser
```

This provides:
- Complete endpoint list
- Request/response examples
- Interactive testing interface
- Response formatting
- Direct discovery of the OSGi config surface under `/v1/config/osgi*`

---

## Common Response Formats

### Success Response
```json
{
  "status": "success",
  "data": { ... }
}
```

### Error Response
```json
{
  "status": "error",
  "error": "Error message",
  "code": "ERROR_CODE"
}
```

### Proposal Response
```json
{
  "proposalId": "uuid-123",
  "type": "WRITE",
  "state": "PENDING",
  "timestamp": 1733421234000
}
```

---

## Rate Limiting

Rate limiting is enabled by default:
- **Per-client**: 100 requests/minute
- **Per-wallet**: 1000 requests/minute

Rate limit headers:
```
X-RateLimit-Limit: 100
X-RateLimit-Remaining: 95
X-RateLimit-Reset: 1733421234
```

---

## Error Codes

| Code | Status | Description |
|------|--------|-------------|
| `CLUSTER_UNHEALTHY` | 503 | Cluster is not healthy, retry later |
| `PATH_OWNERSHIP_VIOLATION` | 403 | Path does not belong to wallet |
| `WALLET_NOT_REGISTERED` | 403 | Wallet not registered as client |
| `INVALID_SIGNATURE` | 401 | Signature verification failed |
| `PROPOSAL_NOT_FOUND` | 404 | Proposal ID not found |
| `RATE_LIMIT_EXCEEDED` | 429 | Too many requests |

---

*See individual API category docs for detailed endpoint documentation.*
