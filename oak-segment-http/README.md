# Oak Segment HTTP

**Purpose**: HTTP-based remote segment persistence for standalone Oak runtimes  
**Package**: `org.apache.jackrabbit.oak.segment.http`

## Scope

`oak-segment-http` is the read-only transport layer for remote Oak segment access.

It exists to support:
- lazy composite mounts
- segment, journal, and manifest transfer over HTTP
- cross-cluster reads in cluster-of-clusters topologies
- standalone Oak runtimes such as `oak-segment-consensus`

It does not own client-side wallet, registration, or write-proposal behavior.
Those flows live in:
- `oak-segment-consensus` for standalone validator/write paths
- [`oak-chain-connector`](../../oak-chain-connector/README.md) for AEM-facing integration

## Primary Use Case

`oak-segment-consensus` uses this module to mount other clusters read-only for shard routing and lazy remote reads.

Example:

```java
import org.apache.jackrabbit.oak.segment.http.HttpPersistence;

HttpPersistence persistence = new HttpPersistence(endpoint);
```

See [LazyHttpNodeStore.java](../oak-segment-consensus/src/main/java/org/apache/jackrabbit/oak/segment/consensus/mount/LazyHttpNodeStore.java).

## Components

| Class | Purpose |
|-------|---------|
| `HttpPersistence` | `SegmentNodeStorePersistence` implementation for HTTP-backed segment access |
| `HttpPersistenceService` | OSGi component that exposes and lazily registers HTTP persistence |
| `HttpSegmentArchiveManager` | Archive manager for remote segment archives |
| `HttpSegmentArchiveReader` | Segment reader backed by validator HTTP endpoints |
| `HttpJournalFile` | Remote `journal.log` access |
| `HttpManifestFile` | Remote manifest access |
| `HttpGCJournalFile` | Remote GC journal access |
| `HttpClientPool` | Shared HTTP/1.1 connection pool |
| `Http2ClientPool` | HTTP/2 client pool for higher-throughput fetches |
| `ValidatorAuthHelper` | Optional auth-header helper for protected validator endpoints |

## Lazy Mount Flow

When `lazyMount=true`, `HttpPersistenceService` behaves as follows:

1. Sling or Oak starts without blocking on remote validator availability.
2. A background health check polls the configured remote endpoint.
3. Once the remote validator is reachable, the service registers `SegmentNodeStorePersistence`.
4. The composite mount comes online and remote content becomes readable.

This keeps `/oak-chain` or other remote mounts resilient during startup and cluster restarts.

## Configuration

Example `HttpPersistenceService` config:

```json
{
  "globalStoreUrl": "http://validator-0:8090",
  "lazyMount": true,
  "healthCheckIntervalSeconds": 10,
  "connectionTimeoutMs": 3000
}
```

Key properties:
- `globalStoreUrl`: base URL of the remote validator HTTP server
- `lazyMount`: defer service registration until the remote store is reachable
- `healthCheckIntervalSeconds`: retry interval in lazy mode
- `connectionTimeoutMs`: timeout used during readiness checks

## Relationship To AEM

AEM customers should use [`oak-chain-connector`](../../oak-chain-connector/README.md), not this module directly.

Why:
- AEM cannot accept new Oak bundles at runtime under its security model.
- The connector externalizes the same integration surface with AEM-compatible packaging and deployment.

This module remains in `jackrabbit-oak` because standalone Oak runtimes still need the read-only HTTP persistence and transfer mechanism.

## Related Modules

| Module | Relationship |
|--------|--------------|
| `oak-segment-consensus` | Consumes this module for lazy remote mounts and cross-cluster reads |
| `oak-segment-tar` | Provides adjacent segment-store integration points |
| `oak-store-composite` | Hosts the composite mount that consumes this persistence |
| `oak-chain-connector` | Externalized AEM integration counterpart |

## Build

```bash
cd jackrabbit-oak
mvn clean install -pl oak-segment-http -am -DskipTests
```
