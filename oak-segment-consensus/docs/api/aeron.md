# Aeron Cluster API

**Endpoints for Aeron Cluster state, leadership, and Raft metrics**

---

## GET /v1/aeron/cluster-state

Get complete Aeron Cluster state including leader, members, and role.

### Response

```json
{
  "clusterId": "oak-consensus-cluster",
  "memberId": 0,
  "role": "LEADER",
  "leadershipTermId": 5,
  "logPosition": 12345,
  "members": [
    {
      "id": 0,
      "url": "http://localhost:8090",
      "role": "LEADER",
      "logPosition": 12345
    },
    {
      "id": 1,
      "url": "http://localhost:8092",
      "role": "FOLLOWER",
      "logPosition": 12340
    },
    {
      "id": 2,
      "url": "http://localhost:8094",
      "role": "FOLLOWER",
      "logPosition": 12340
    }
  ],
  "leaderMemberId": 0,
  "leaderUrl": "http://localhost:8090",
  "clusterSize": 3,
  "quorumSize": 2
}
```

---

## GET /v1/aeron/raft-metrics

Get Raft-specific performance metrics.

### Response

```json
{
  "replicationLatency": {
    "p50": 0.05,
    "p95": 0.12,
    "p99": 0.25,
    "max": 0.50
  },
  "throughput": {
    "messagesPerSecond": 100,
    "bytesPerSecond": 1048576
  },
  "logPosition": 12345,
  "snapshotPosition": 12000
}
```

---

## GET /v1/aeron/leadership-history

Get recent leadership changes.

### Query Parameters

- `limit` (optional) - Number of entries (default: 10)

### Response

```json
{
  "history": [
    {
      "timestamp": 1733421234000,
      "memberId": 0,
      "role": "LEADER",
      "term": 5
    },
    {
      "timestamp": 1733421000000,
      "memberId": 1,
      "role": "LEADER",
      "term": 4
    }
  ]
}
```

---

## GET /v1/aeron/node-status

Get status of specific node.

### Query Parameters

- `nodeId` (required) - Node ID (0, 1, 2, ...)

### Response

```json
{
  "nodeId": 0,
  "url": "http://localhost:8090",
  "role": "LEADER",
  "logPosition": 12345,
  "lastHeartbeat": 1733421234000,
  "connected": true
}
```

---

*See [Architecture Overview](../architecture/README.md) for Aeron Cluster details.*
