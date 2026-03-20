# Health & Metrics API

**Endpoints for health checks and metrics**

---

## GET /health

Basic health check. Always public (no authentication required).

### Response

**200 OK**
```
OK
```

**503 Service Unavailable** (if unhealthy)
```
UNHEALTHY: Reason
```

### Use Cases

- Load balancer health checks
- Kubernetes liveness probe
- Monitoring systems

---

## GET /health/deep

Comprehensive health validation. Always public.

### Response

```json
{
  "status": "healthy",
  "checks": {
    "aeron": {
      "status": "healthy",
      "leaderElected": true,
      "quorumMet": true
    },
    "oak": {
      "status": "healthy",
      "fileStoreAccessible": true,
      "headReadable": true
    },
    "http": {
      "status": "healthy",
      "serverRunning": true
    }
  },
  "timestamp": 1733421234000
}
```

**Status Values**: `healthy`, `degraded`, `unhealthy`

---

## GET /metrics

Prometheus metrics endpoint. Returns text format.

### Response

```
# HELP oak_consensus_proposals_total Total number of proposals
# TYPE oak_consensus_proposals_total counter
oak_consensus_proposals_total{type="write"} 1000
oak_consensus_proposals_total{type="delete"} 50

# HELP oak_consensus_replication_latency_seconds Replication latency
# TYPE oak_consensus_replication_latency_seconds histogram
oak_consensus_replication_latency_seconds_bucket{le="0.001"} 500
oak_consensus_replication_latency_seconds_bucket{le="0.01"} 900
oak_consensus_replication_latency_seconds_bucket{le="+Inf"} 1000
```

### Prometheus Configuration

```yaml
scrape_configs:
  - job_name: 'oak-validator'
    static_configs:
      - targets: ['localhost:8090']
```

---

## GET /api/metrics

JSON metrics endpoint.

### Response

```json
{
  "proposals": {
    "total": 1050,
    "byType": {
      "write": 1000,
      "delete": 50
    },
    "byState": {
      "pending": 5,
      "confirmed": 10,
      "processed": 1035
    }
  },
  "replication": {
    "latency": {
      "p50": 0.05,
      "p95": 0.12,
      "p99": 0.25
    },
    "throughput": 100
  },
  "cluster": {
    "size": 3,
    "healthy": true,
    "leaderElected": true
  }
}
```

---

## GET /v1/blockchain/config

Detect the active blockchain runtime mode and gas pricing model used for write-tier estimates.

Use this endpoint to drive dashboard/client behavior (network badges, wallet requirements, and fee displays).

Example:
```bash
curl http://localhost:8090/v1/blockchain/config
```

### Response (example)

```json
{
  "mode": "sepolia",
  "network": "Sepolia Testnet",
  "chainId": 11155111,
  "contractAddress": "0x742d35Cc6634C0532925a3b844Bc9e7595f0bEb0",
  "rpcUrl": "https://sepolia.infura.io/v3/***",
  "requiresMetaMask": true,
  "useTestnet": true,
  "displayName": "✅ SEPOLIA TESTNET",
  "badgeColor": "#10b981",
  "configSource": "env-or-system-properties",
  "gasModel": {
    "source": "measured-sepolia-baseline",
    "gasPriceGwei": 3,
    "writeGasUnitsStandard": 74534,
    "writeGasUnitsExpress": 74534,
    "writeGasUnitsPriority": 74534
  },
  "tiers": {
    "STANDARD": {
      "tier": 0,
      "maxDelay": "13 min",
      "baseFeeWei": "5000000000000000",
      "gasUnits": 74534,
      "gasPriceGwei": 3,
      "estimatedGasFeeWei": "223602000000000",
      "estimatedTotalWei": "5223602000000000",
      "estimatedCost": "~0.005224 ETH"
    }
  }
}
```

### Key Fields

- `configSource`: `osgi-config-admin` or `env-or-system-properties`
- `gasModel`: effective gas assumptions after precedence resolution
- `tiers.*.estimatedTotalWei`: base fee + estimated gas fee (wei string)

---

*See [Monitoring Integration](../integration/README.md) for Prometheus/Grafana setup.*
