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

*See [Monitoring Integration](../integration/README.md) for Prometheus/Grafana setup.*
